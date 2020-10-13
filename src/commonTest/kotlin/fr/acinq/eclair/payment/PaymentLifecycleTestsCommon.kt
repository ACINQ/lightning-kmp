package fr.acinq.eclair.payment

import fr.acinq.bitcoin.*
import fr.acinq.bitcoin.io.ByteArrayInput
import fr.acinq.eclair.*
import fr.acinq.eclair.channel.*
import fr.acinq.eclair.crypto.sphinx.Sphinx
import fr.acinq.eclair.io.SendPayment
import fr.acinq.eclair.router.ChannelHop
import fr.acinq.eclair.router.NodeHop
import fr.acinq.eclair.tests.utils.EclairTestSuite
import fr.acinq.eclair.utils.*
import fr.acinq.eclair.wire.*
import kotlin.test.*

class PaymentLifecycleTestsCommon : EclairTestSuite() {

    private fun makeSendPayment(
        payee: PrivateKey,
        amount: MilliSatoshi?,
        supportsTrampoline: Boolean,
        timestamp: Long = currentTimestampSeconds(),
        expirySeconds: Long? = null,
    ): SendPayment {

        val paymentPreimage: ByteVector32 = Eclair.randomBytes32()
        val paymentHash = Crypto.sha256(paymentPreimage).toByteVector32()

        val invoiceFeatures = mutableSetOf(
            ActivatedFeature(Feature.VariableLengthOnion, FeatureSupport.Optional),
            ActivatedFeature(Feature.PaymentSecret, FeatureSupport.Mandatory),
            ActivatedFeature(Feature.BasicMultiPartPayment, FeatureSupport.Optional)
        )
        if (supportsTrampoline) {
            invoiceFeatures.add(
                ActivatedFeature(Feature.TrampolinePayment, FeatureSupport.Optional)
            )
        }
        val paymentRequest = PaymentRequest.create(
            chainHash = Block.LivenetGenesisBlock.hash,
            amount = amount,
            paymentHash = paymentHash,
            privateKey = payee, // Payee creates invoice, sends to payer
            description = "unit test",
            minFinalCltvExpiryDelta = PaymentRequest.DEFAULT_MIN_FINAL_EXPIRY_DELTA,
            features = Features(invoiceFeatures),
            timestamp = timestamp,
            expirySeconds = expirySeconds
        )

        return SendPayment(paymentId = UUID.randomUUID(), paymentRequest = paymentRequest)
    }

    private fun makeSendPayment(
        payee: Normal,
        amount: MilliSatoshi?,
        supportsTrampoline: Boolean,
        timestamp: Long = currentTimestampSeconds(),
        expirySeconds: Long? = null,
    ): SendPayment {
        val privKey = payee.staticParams.nodeParams.nodePrivateKey
        return makeSendPayment(privKey, amount, supportsTrampoline, timestamp, expirySeconds)
    }

    private fun expectedTrampolineFees(
        targetAmount: MilliSatoshi,
        channelUpdate: ChannelUpdate,
        schedule: PaymentLifecycle.PaymentAdjustmentSchedule
    ): MilliSatoshi {

        return Eclair.nodeFee(channelUpdate.feeBaseMsat, channelUpdate.feeProportionalMillionths, targetAmount) +
                schedule.feeBaseSat.toMilliSatoshi() +
                (targetAmount * schedule.feePercent)
    }

    @Test
    fun `PaymentAttempt calculations - with room for fees`() {

        val (alice, bob) = TestsHelper.reachNormal()

        val availableForSend = 1_000_000.sat.toMilliSatoshi()
        val targetAmount = 500_000.sat.toMilliSatoshi() // plenty of room for targetAmount & fees

        val sendPayment = makeSendPayment(payee = bob, amount = targetAmount, supportsTrampoline = true)

        for (schedule in PaymentLifecycle.PaymentAdjustmentSchedule.all()) {

            val additionalFeesAmount = schedule.feeBaseSat.toMilliSatoshi() + (targetAmount * schedule.feePercent)

            val paymentAttempt = PaymentLifecycle.PaymentAttempt(sendPayment)
            val pair = paymentAttempt.add(
                channelId = alice.channelId,
                channelUpdate = alice.channelUpdate,
                targetAmount = targetAmount,
                additionalFeesAmount = additionalFeesAmount,
                availableForSend = availableForSend,
                cltvExpiryDelta = CltvExpiryDelta(0)
            )

            assertNotNull(pair)
            val (trampolinePaymentAttempt, _) = pair
            assertTrue { trampolinePaymentAttempt.nextAmount == targetAmount }

            val expectedFees = expectedTrampolineFees(targetAmount, alice.channelUpdate, schedule)
            assertTrue { trampolinePaymentAttempt.amount == (targetAmount + expectedFees) }
        }
    }

    @Test
    fun `PaymentAttempt calculations - with room for fees if we reduce targetAmount`() {

        val (alice, bob) = TestsHelper.reachNormal()

        val availableForSend = 500_000.sat.toMilliSatoshi()
        val targetAmount = 500_000.sat.toMilliSatoshi() // channel has room for a payment, but less than target

        // We are asking to send a payment of 500_000 sats on a channel with a cap of 500_000.
        // So we cannot send the targetAmount.
        // But we can still send something on this channel. And we can max it out.

        val sendPayment = makeSendPayment(payee = bob, amount = targetAmount, supportsTrampoline = true)

        val paymentAttempt = PaymentLifecycle.PaymentAttempt(sendPayment)
        val pair = paymentAttempt.add(
            channelId = alice.channelId,
            channelUpdate = alice.channelUpdate,
            targetAmount = targetAmount,
            additionalFeesAmount = MilliSatoshi(0),
            availableForSend = availableForSend,
            cltvExpiryDelta = CltvExpiryDelta(0)
        )

        assertNotNull(pair)
        val (trampolinePaymentAttempt, _) = pair
        assertTrue { trampolinePaymentAttempt.amount == availableForSend } // maxed out channel

        val schedule = PaymentLifecycle.PaymentAdjustmentSchedule.get(0)!!
        val expectedFees = expectedTrampolineFees(trampolinePaymentAttempt.nextAmount, alice.channelUpdate, schedule)

        assertTrue { trampolinePaymentAttempt.nextAmount == (availableForSend - expectedFees) }
    }

    @Test
    fun `PaymentAttempt calculations - no room for payment after fees`() {

        val (alice, bob) = TestsHelper.reachNormal()

        val availableForSend = 546_000.msat
        val targetAmount = 500_000_000.msat

        // There won't be enough room in the channel to send anything.
        // Because, once the fees are taken into account, there's no room for anything else.

        val sendPayment = makeSendPayment(payee = bob, amount = targetAmount, supportsTrampoline = true)
        val paymentAttempt = PaymentLifecycle.PaymentAttempt(sendPayment)

        val pair = paymentAttempt.add(
            channelId = alice.channelId,
            channelUpdate = alice.channelUpdate,
            targetAmount = targetAmount,
            additionalFeesAmount = MilliSatoshi(0),
            availableForSend = availableForSend,
            cltvExpiryDelta = CltvExpiryDelta(0)
        )

        assertNull(pair)
    }

    private fun decryptNodeRelay(
        onion: OnionRoutingPacket,
        paymentHash: ByteVector32,
        privateKey: PrivateKey
    ): Triple<FinalPayload, NodeRelayPayload, OnionRoutingPacket> {
        val decrypted = Sphinx.peel(privateKey, paymentHash, onion, OnionRoutingPacket.PaymentPacketLength).right!!
        assertTrue(decrypted.isLastPacket)
        val outerPayload = FinalPayload.read(ByteArrayInput(decrypted.payload.toByteArray()))
        assertTrue(outerPayload is FinalTlvPayload)
        val trampolineOnion = outerPayload.records.get<OnionTlv.TrampolineOnion>()
        assertNotNull(trampolineOnion)
        val decryptedInner = Sphinx.peel(
            privateKey,
            paymentHash,
            trampolineOnion.packet,
            OnionRoutingPacket.TrampolinePacketLength
        ).right!!
        val innerPayload = NodeRelayPayload.read(ByteArrayInput(decryptedInner.payload.toByteArray()))
        return Triple(outerPayload, innerPayload, decryptedInner.nextPacket)
    }

    @Test
    fun `PaymentLifecycle actionify - full trampoline`() {

        // full trampoline route to c:
        //        .--.
        //       /    \
        // a -> b      c

        val (channel, _) = TestsHelper.reachNormal()

        val privKeyB = TestConstants.Bob.nodeParams.nodePrivateKey
        val pubKeyB = privKeyB.publicKey()
        val privKeyC = Eclair.randomKey()
        val pubKeyC = privKeyC.publicKey()

        val blockHeight = channel.currentBlockHeight.toLong()

        val availableForSend = 1_000_000_000.msat
        val targetAmount = availableForSend / 2

        val sendPayment = makeSendPayment(payee = privKeyC, amount = targetAmount, supportsTrampoline = true)
        val invoice = sendPayment.paymentRequest

        val paymentAttempt = PaymentLifecycle.PaymentAttempt(sendPayment)
        val pair = paymentAttempt.add(
            channelId = channel.channelId,
            channelUpdate = channel.channelUpdate,
            targetAmount = targetAmount,
            additionalFeesAmount = MilliSatoshi(0),
            availableForSend = availableForSend,
            cltvExpiryDelta = CltvExpiryDelta(0)
        )

        assertNotNull(pair)
        val (trampolinePaymentAttempt, _) = pair

        val amountAB = trampolinePaymentAttempt.amount
        val amountBC = trampolinePaymentAttempt.nextAmount

        val expiryDeltaBC = Channel.MIN_CLTV_EXPIRY_DELTA
        val expiryDeltaAB = expiryDeltaBC + trampolinePaymentAttempt.cltvExpiryDelta

        val expiryBC = expiryDeltaBC.toCltvExpiry(blockHeight)
        val expiryAB = expiryDeltaAB.toCltvExpiry(blockHeight)

        val paymentLifecycle = PaymentLifecycle(channel.staticParams.nodeParams)
        val wrappedChannelEvent = paymentLifecycle.actionify(
            channel = channel,
            paymentAttempt = paymentAttempt,
            trampolinePaymentAttempt = trampolinePaymentAttempt,
            currentBlockHeight = channel.currentBlockHeight
        )

        val executeCommand = wrappedChannelEvent.channelEvent as? ExecuteCommand
        val cmdAddHtlc = executeCommand?.command as? CMD_ADD_HTLC

        assertNotNull(cmdAddHtlc)

        assertTrue { cmdAddHtlc.amount == amountAB }
        assertTrue { cmdAddHtlc.cltvExpiry == expiryAB }

        // When nodeB receives the packet, it will be decrypted, and we expect to find:
        // - isLastPacket == true (last on channel-hop sequence)
        // - contains in inner trampoline onion
        // - trampoline packet requests a trampoline-forward to nodeC

        val (outerB, innerB, packetC) = decryptNodeRelay(cmdAddHtlc.onion, cmdAddHtlc.paymentHash, privKeyB)
        assertEquals(amountAB, outerB.amount)
        assertEquals(amountAB, outerB.totalAmount)
        assertEquals(expiryAB, outerB.expiry)
        assertEquals(amountBC, innerB.amountToForward)
        assertEquals(expiryBC, innerB.outgoingCltv)
        assertEquals(pubKeyC, innerB.outgoingNodeId)
        assertNull(innerB.invoiceRoutingInfo)
        assertNull(innerB.invoiceFeatures)
        assertNull(innerB.paymentSecret)

        // NodeB will wrap the remaining trampolinePacket, and forward it to nodeC.
        // It doesn't matter which route is used to forward the packet.

        val lastTrampolinePayload = FinalPayload.createTrampolinePayload(
            amount = amountBC,
            totalAmount = amountBC,
            expiry = expiryBC,
            paymentSecret = Eclair.randomBytes32(), // real paymentSecret is inside trampoline (packetC)
            trampolinePacket = packetC
        )
        val lastTrampolineHop = NodeHop(pubKeyB, pubKeyC, expiryDeltaBC, MilliSatoshi(0))
        val (amountC, expiryC, onionC) = OutgoingPacket.buildPacket(
            paymentHash = invoice.paymentHash,
            hops = listOf(lastTrampolineHop),
            finalPayload = lastTrampolinePayload,
            payloadLength = OnionRoutingPacket.PaymentPacketLength
        )
        val addC = UpdateAddHtlc(Eclair.randomBytes32(), 0, amountC, invoice.paymentHash, expiryC, onionC.packet)

        // Nodec should be able to decrypt the trampoline.
        // And the finalPayload should match our expectations.

        val payloadC = IncomingPacket.decrypt(addC, privKeyC).right!!
        assertEquals(
            payloadC, FinalTlvPayload(
                TlvStream(
                    listOf(
                        OnionTlv.AmountToForward(targetAmount),
                        OnionTlv.OutgoingCltv(expiryBC),
                        OnionTlv.PaymentData(sendPayment.paymentRequest.paymentSecret!!, targetAmount)
                    )
                )
            )
        )
    }

    @Test
    fun `PaymentLifecycle actionify - legacy trampoline`() {

        // simple trampoline route to c, where c doesn't support trampoline:
        //        .xx.
        //       /    \
        // a -> b ->-> c

        val (channel, _) = TestsHelper.reachNormal()

        val privKeyB = TestConstants.Bob.nodeParams.nodePrivateKey
        val privKeyC = Eclair.randomKey()
        val pubKeyC = privKeyC.publicKey()

        val blockHeight = channel.currentBlockHeight.toLong()

        val availableForSend = 1_000_000_000.msat
        val targetAmount = availableForSend / 2

        val sendPayment = makeSendPayment(payee = privKeyC, amount = targetAmount, supportsTrampoline = false)
        val invoice = sendPayment.paymentRequest

        val paymentAttempt = PaymentLifecycle.PaymentAttempt(sendPayment)
        val pair = paymentAttempt.add(
            channelId = channel.channelId,
            channelUpdate = channel.channelUpdate,
            targetAmount = targetAmount,
            additionalFeesAmount = MilliSatoshi(0),
            availableForSend = availableForSend,
            cltvExpiryDelta = CltvExpiryDelta(0)
        )

        assertNotNull(pair)
        val (trampolinePaymentAttempt, _) = pair

        val amountAB = trampolinePaymentAttempt.amount
//      val amountBC = trampolinePaymentAttempt.nextAmount

        val expiryDeltaBC = Channel.MIN_CLTV_EXPIRY_DELTA
        val expiryDeltaAB = expiryDeltaBC + trampolinePaymentAttempt.cltvExpiryDelta

        val expiryBC = expiryDeltaBC.toCltvExpiry(blockHeight)
        val expiryAB = expiryDeltaAB.toCltvExpiry(blockHeight)

        val paymentLifecycle = PaymentLifecycle(channel.staticParams.nodeParams)
        val wrappedChannelEvent = paymentLifecycle.actionify(
            channel = channel,
            paymentAttempt = paymentAttempt,
            trampolinePaymentAttempt = trampolinePaymentAttempt,
            currentBlockHeight = channel.currentBlockHeight
        )

        val executeCommand = wrappedChannelEvent.channelEvent as? ExecuteCommand
        val cmdAddHtlc = executeCommand?.command as? CMD_ADD_HTLC

        assertNotNull(cmdAddHtlc)

        assertTrue { cmdAddHtlc.amount == amountAB }
        assertTrue { cmdAddHtlc.cltvExpiry == expiryAB }

        // When nodeB receives the packet, it will be decrypted, and we expect to find:
        // - isLastPacket == true (last on channel-hop sequence)
        // - contains in inner trampoline onion
        // - trampoline packet requests a legacy (non-trampoline) forward to nodeC

        val (outerB, innerB, _) = decryptNodeRelay(cmdAddHtlc.onion, cmdAddHtlc.paymentHash, privKeyB)
        assertEquals(amountAB, outerB.amount)
        assertEquals(amountAB, outerB.totalAmount)
        assertEquals(expiryAB, outerB.expiry)
        assertNotEquals(invoice.paymentSecret, outerB.paymentSecret)
        assertEquals(targetAmount, innerB.amountToForward)
        assertEquals(targetAmount, innerB.totalAmount)
        assertEquals(expiryBC, innerB.outgoingCltv)
        assertEquals(pubKeyC, innerB.outgoingNodeId)
        assertEquals(invoice.paymentSecret, innerB.paymentSecret)
        assertEquals(invoice.features!!, innerB.invoiceFeatures)

        // invoice.routingInfo => List<PaymentRequest.TaggedField.RoutingInfo>
        // innerB.invoiceRoutingInfo => List<List<PaymentRequest.TaggedField.ExtraHop>>
        invoice.routingInfo.map { it.hints }.let {
            assertEquals(it, innerB.invoiceRoutingInfo)
        }
    }
}