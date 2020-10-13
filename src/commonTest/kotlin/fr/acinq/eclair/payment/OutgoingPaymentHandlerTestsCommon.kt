package fr.acinq.eclair.payment

import fr.acinq.bitcoin.*
import fr.acinq.bitcoin.io.ByteArrayInput
import fr.acinq.eclair.*
import fr.acinq.eclair.channel.*
import fr.acinq.eclair.crypto.sphinx.Sphinx
import fr.acinq.eclair.io.SendPayment
import fr.acinq.eclair.router.NodeHop
import fr.acinq.eclair.tests.utils.EclairTestSuite
import fr.acinq.eclair.utils.*
import fr.acinq.eclair.wire.*
import kotlin.test.*

class OutgoingPaymentHandlerTestsCommon : EclairTestSuite() {

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

    private fun expectedFees(
        targetAmount: MilliSatoshi,
        channelUpdate: ChannelUpdate
    ): MilliSatoshi {

        return Eclair.nodeFee(channelUpdate.feeBaseMsat, channelUpdate.feeProportionalMillionths, targetAmount)
    }

    private fun expectedFees(
        targetAmount: MilliSatoshi,
        schedule: OutgoingPaymentHandler.PaymentAdjustmentSchedule
    ): MilliSatoshi {

        return schedule.feeBaseSat.toMilliSatoshi() + (targetAmount * schedule.feePercent)
    }

    @Test
    fun `payment amount calculations - with room for fees`() {

        val (alice, bob) = TestsHelper.reachNormal()

        val availableForSend = 1_000_000.sat.toMilliSatoshi()
        val targetAmount = 500_000.sat.toMilliSatoshi() // plenty of room for targetAmount & fees

        val sendPayment = makeSendPayment(payee = bob, amount = targetAmount, supportsTrampoline = true)

        for (schedule in OutgoingPaymentHandler.PaymentAdjustmentSchedule.all()) {

            val additionalFees = expectedFees(targetAmount, schedule)

            val paymentAttempt = OutgoingPaymentHandler.PaymentAttempt(sendPayment)
            val pair = paymentAttempt.add(
                channelId = alice.channelId,
                channelUpdate = alice.channelUpdate,
                targetAmount = targetAmount,
                additionalFeesAmount = additionalFees,
                availableForSend = availableForSend,
                cltvExpiryDelta = CltvExpiryDelta(0)
            )

            assertNotNull(pair)
            val (_, calculations) = pair
            assertTrue { calculations.payeeAmount == targetAmount }

            val channelFees = expectedFees(targetAmount, alice.channelUpdate)
            assertTrue { calculations.channelFees == channelFees }
            assertTrue { calculations.additionalFees == additionalFees }
            assertTrue { calculations.trampolineAmount == (targetAmount + channelFees + additionalFees) }
        }
    }

    @Test
    fun `payment amount calculations - with room for fees if we reduce targetAmount`() {

        val (alice, bob) = TestsHelper.reachNormal()

        val availableForSend = 500_000.sat.toMilliSatoshi()
        val targetAmount = 500_000.sat.toMilliSatoshi() // channel has room for a payment, but less than target

        // We are asking to send a payment of 500_000 sats on a channel with a cap of 500_000.
        // So we cannot send the targetAmount.
        // But we can still send something on this channel. And we can max it out.

        val sendPayment = makeSendPayment(payee = bob, amount = targetAmount, supportsTrampoline = true)

        val paymentAttempt = OutgoingPaymentHandler.PaymentAttempt(sendPayment)
        val pair = paymentAttempt.add(
            channelId = alice.channelId,
            channelUpdate = alice.channelUpdate,
            targetAmount = targetAmount,
            additionalFeesAmount = MilliSatoshi(0),
            availableForSend = availableForSend,
            cltvExpiryDelta = CltvExpiryDelta(0)
        )

        assertNotNull(pair)
        val (_, calculations) = pair
        assertTrue { calculations.trampolineAmount == availableForSend } // maxed out channel

        val channelFees = expectedFees(calculations.payeeAmount, alice.channelUpdate)
        assertTrue { calculations.payeeAmount == (availableForSend - channelFees) }
    }

    @Test
    fun `payment amount calculations - exceed capacity due to additionalFees`() {

        val (alice, bob) = TestsHelper.reachNormal()

        val availableForSend = 100_000_000.msat
        val targetAmount = 50_000_000.msat
        val additionalFees = 50_000_000.msat

        val sendPayment = makeSendPayment(payee = bob, amount = targetAmount, supportsTrampoline = true)
        val paymentAttempt = OutgoingPaymentHandler.PaymentAttempt(sendPayment)

        val pair = paymentAttempt.add(
            channelId = alice.channelId,
            channelUpdate = alice.channelUpdate,
            targetAmount = targetAmount,
            additionalFeesAmount = additionalFees,
            availableForSend = availableForSend,
            cltvExpiryDelta = CltvExpiryDelta(0)
        )

        assertNotNull(pair)
        val (_, calculations) = pair
        assertTrue { calculations.trampolineAmount == availableForSend } // maxed out channel

        val expectedChannelFees = expectedFees(targetAmount, alice.channelUpdate)
        assertTrue { calculations.channelFees == expectedChannelFees }

        val expectedAdditionalFees = availableForSend - targetAmount - expectedChannelFees
        assertTrue { calculations.additionalFees == expectedAdditionalFees }
    }

    @Test
    fun `payment amount calculations - no room for payment after fees`() {

        val (alice, bob) = TestsHelper.reachNormal()

        val availableForSend = 546_000.msat // == channel's feeBase
        val targetAmount = 500_000_000.msat

        // There won't be enough room in the channel to send anything.
        // Because, once the fees are taken into account, there's no room for anything else.

        val sendPayment = makeSendPayment(payee = bob, amount = targetAmount, supportsTrampoline = true)
        val paymentAttempt = OutgoingPaymentHandler.PaymentAttempt(sendPayment)

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

    @Test
    fun `payment amount calculations - payment too low for htlc_minimum_msat`() {

        val (alice, bob) = TestsHelper.reachNormal()
        val channelUpdate = alice.channelUpdate.copy(htlcMinimumMsat = MilliSatoshi(100_000))

        val availableForSend = 546_000.msat
        val targetAmount = 50_000.msat // less than htlcMinimumMsat

        // The target amount is below the channel's configured htlc_minimum_msat.
        // So we won't be able to make the payment.

        val sendPayment = makeSendPayment(payee = bob, amount = targetAmount, supportsTrampoline = true)
        val paymentAttempt = OutgoingPaymentHandler.PaymentAttempt(sendPayment)

        val pair = paymentAttempt.add(
            channelId = alice.channelId,
            channelUpdate = channelUpdate,
            targetAmount = targetAmount,
            additionalFeesAmount = MilliSatoshi(0),
            availableForSend = availableForSend,
            cltvExpiryDelta = CltvExpiryDelta(0)
        )

        assertNull(pair)
    }

    @Test
    fun `payment amount calculations - payment capped by htlc_maximum_msat`() {

        val (alice, bob) = TestsHelper.reachNormal()
        val channelUpdate = alice.channelUpdate.copy(htlcMaximumMsat = MilliSatoshi(100_000_000))

        val availableForSend = 500_000_000.msat
        val targetAmount = 200_000_000.msat // more than htlcMaximumMsat

        val sendPayment = makeSendPayment(payee = bob, amount = targetAmount, supportsTrampoline = true)
        val paymentAttempt = OutgoingPaymentHandler.PaymentAttempt(sendPayment)

        val pair = paymentAttempt.add(
            channelId = alice.channelId,
            channelUpdate = channelUpdate,
            targetAmount = targetAmount,
            additionalFeesAmount = MilliSatoshi(0),
            availableForSend = availableForSend,
            cltvExpiryDelta = CltvExpiryDelta(0)
        )

        assertNotNull(pair)
        val (_, calculations) = pair
        assertTrue { calculations.trampolineAmount == channelUpdate.htlcMaximumMsat!! }
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

        val paymentAttempt = OutgoingPaymentHandler.PaymentAttempt(sendPayment)
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

        val paymentLifecycle = OutgoingPaymentHandler(channel.staticParams.nodeParams)
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

        val paymentAttempt = OutgoingPaymentHandler.PaymentAttempt(sendPayment)
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

        val paymentLifecycle = OutgoingPaymentHandler(channel.staticParams.nodeParams)
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