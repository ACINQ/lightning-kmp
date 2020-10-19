package fr.acinq.eclair.payment

import fr.acinq.bitcoin.Block
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.Crypto
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.bitcoin.io.ByteArrayInput
import fr.acinq.eclair.*
import fr.acinq.eclair.channel.*
import fr.acinq.eclair.crypto.sphinx.Sphinx
import fr.acinq.eclair.io.SendPayment
import fr.acinq.eclair.io.WrappedChannelEvent
import fr.acinq.eclair.router.NodeHop
import fr.acinq.eclair.tests.utils.EclairTestSuite
import fr.acinq.eclair.utils.*
import fr.acinq.eclair.wire.*
import kotlin.test.*

class OutgoingPaymentHandlerTestsCommon : EclairTestSuite() {

    private fun makeInvoice(
        recipient: PrivateKey,
        amount: MilliSatoshi?,
        supportsTrampoline: Boolean,
        timestamp: Long = currentTimestampSeconds(),
        expirySeconds: Long? = null,
    ): PaymentRequest {

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
        return PaymentRequest.create(
            chainHash = Block.LivenetGenesisBlock.hash,
            amount = amount,
            paymentHash = paymentHash,
            privateKey = recipient,
            description = "unit test",
            minFinalCltvExpiryDelta = PaymentRequest.DEFAULT_MIN_FINAL_EXPIRY_DELTA,
            features = Features(invoiceFeatures),
            timestamp = timestamp,
            expirySeconds = expirySeconds
        )
    }

    private fun makeInvoice(
        recipient: Normal,
        amount: MilliSatoshi?,
        supportsTrampoline: Boolean,
        timestamp: Long = currentTimestampSeconds(),
        expirySeconds: Long? = null,
    ): PaymentRequest {
        val recipientPrivKey = recipient.staticParams.nodeParams.nodePrivateKey
        return makeInvoice(recipientPrivKey, amount, supportsTrampoline, timestamp, expirySeconds)
    }

    private fun expectedFees(
        targetAmount: MilliSatoshi,
        channelUpdate: ChannelUpdate
    ): MilliSatoshi {

        return Eclair.nodeFee(channelUpdate.feeBaseMsat, channelUpdate.feeProportionalMillionths, targetAmount)
    }

    private fun expectedFees(
        targetAmount: MilliSatoshi,
        params: OutgoingPaymentHandler.TrampolineParams
    ): MilliSatoshi {

        return params.feeBaseSat.toMilliSatoshi() + (targetAmount * params.feePercent)
    }

    @Test
    fun `error conditions - bad paymentAmount`() {

        val (alice, bob) = TestsHelper.reachNormal()
        val currentBlockHeight = alice.currentBlockHeight

        val invoiceAmount = 100_000.msat
        val invoice = makeInvoice(recipient = bob, amount = invoiceAmount, supportsTrampoline = true)
        val outgoingPaymentHandler = OutgoingPaymentHandler(alice.staticParams.nodeParams)

        run {
            val sendPayment = SendPayment(UUID.randomUUID(), invoice, MilliSatoshi(-1)) // <= negative msats
            var result = outgoingPaymentHandler.processSendPayment(sendPayment, mapOf(), currentBlockHeight)

            assertTrue { result is OutgoingPaymentHandler.Result.Failure }
            val failure = result as OutgoingPaymentHandler.Result.Failure

            assertTrue { failure.reason == OutgoingPaymentHandler.FailureReason.INVALID_PARAMETER }
        }
    }

    @Test
    fun `error conditions - no available channels`() {

        val (alice, bob) = TestsHelper.reachNormal()
        val currentBlockHeight = alice.currentBlockHeight

        val invoiceAmount = 100_000.msat
        val invoice = makeInvoice(recipient = bob, amount = invoiceAmount, supportsTrampoline = true)
        val outgoingPaymentHandler = OutgoingPaymentHandler(alice.staticParams.nodeParams)

        val sendPayment = SendPayment(UUID.randomUUID(), invoice, invoiceAmount)
        var result = outgoingPaymentHandler.processSendPayment(sendPayment, mapOf(), currentBlockHeight)

        assertTrue { result is OutgoingPaymentHandler.Result.Failure }
        val failure = result as OutgoingPaymentHandler.Result.Failure

        assertTrue { failure.reason == OutgoingPaymentHandler.FailureReason.NO_AVAILABLE_CHANNELS }
    }

    @Test
    fun `error conditions - insufficient capacity - base`() {

        val (alice, bob) = TestsHelper.reachNormal()
        val currentBlockHeight = alice.currentBlockHeight
        val channels = mapOf(alice.channelId to alice)

        val invoiceAmount = alice.commitments.availableBalanceForSend() + 1.msat
        val invoice = makeInvoice(recipient = bob, amount = invoiceAmount, supportsTrampoline = true)
        val outgoingPaymentHandler = OutgoingPaymentHandler(alice.staticParams.nodeParams)

        val sendPayment = SendPayment(UUID.randomUUID(), invoice, invoiceAmount)
        var result = outgoingPaymentHandler.processSendPayment(sendPayment, channels, currentBlockHeight)

        assertTrue { result is OutgoingPaymentHandler.Result.Failure }
        val failure = result as OutgoingPaymentHandler.Result.Failure

        assertTrue { failure.reason == OutgoingPaymentHandler.FailureReason.INSUFFICIENT_BALANCE }
    }

    @Test
    fun `error conditions - insufficient capacity - fees`() {

        var (alice, bob) = TestsHelper.reachNormal()
        // Make sure that htlcMaximumMsat & maxHtlcValueInFlightMsat don't interfer with our test
        val channelUpdate = alice.channelUpdate.copy(htlcMaximumMsat = MilliSatoshi(Long.MAX_VALUE))
        val nodeParams = alice.staticParams.nodeParams.copy(maxHtlcValueInFlightMsat = Long.MAX_VALUE)
        val staticParams = alice.staticParams.copy(nodeParams = nodeParams)
        alice = alice.copy(channelUpdate = channelUpdate, staticParams = staticParams)

        val currentBlockHeight = alice.currentBlockHeight
        val channels = mapOf(alice.channelId to alice)

        val invoiceAmount = alice.commitments.availableBalanceForSend()
        val invoice = makeInvoice(recipient = bob, amount = invoiceAmount, supportsTrampoline = true)
        val sendPayment = SendPayment(UUID.randomUUID(), invoice, invoiceAmount)

        val outgoingPaymentHandler = OutgoingPaymentHandler(alice.staticParams.nodeParams)

        run {
            val result = outgoingPaymentHandler.processSendPayment(sendPayment, channels, currentBlockHeight)
            assertTrue { result is OutgoingPaymentHandler.Result.Progress }
        }
        run {

            val updateFailHtlc = UpdateFailHtlc(alice.channelId, 0, Eclair.randomBytes32())
            val processFail = ProcessFail(fail = updateFailHtlc, paymentId = sendPayment.paymentId)

            val result = outgoingPaymentHandler.processFailure(processFail, channels, currentBlockHeight)

            assertTrue { result is OutgoingPaymentHandler.Result.Failure }
            val failure = result as OutgoingPaymentHandler.Result.Failure

            assertTrue { failure.reason == OutgoingPaymentHandler.FailureReason.INSUFFICIENT_BALANCE }
        }
    }

    @Test
    fun `error conditions - channel cap restrictions - htlcMinimumMsat`() {

        val htlcMininumMsat = 100_000.msat
        val paymentAmount = 50_000.msat // less than htlcMinimumMsat

        var (alice, bob) = TestsHelper.reachNormal()
        alice = alice.copy(channelUpdate = alice.channelUpdate.copy(htlcMinimumMsat = htlcMininumMsat))

        val currentBlockHeight = alice.currentBlockHeight
        val channels = mapOf(alice.channelId to alice)

        val invoice = makeInvoice(recipient = bob, amount = null, supportsTrampoline = true)
        val sendPayment = SendPayment(UUID.randomUUID(), invoice, paymentAmount)

        val outgoingPaymentHandler = OutgoingPaymentHandler(alice.staticParams.nodeParams)

        var result = outgoingPaymentHandler.processSendPayment(sendPayment, channels, currentBlockHeight)

        assertTrue { result is OutgoingPaymentHandler.Result.Failure }
        val failure = result as OutgoingPaymentHandler.Result.Failure

        assertTrue { failure.reason == OutgoingPaymentHandler.FailureReason.CHANNEL_CAP_RESTRICTION }
    }

    @Test
    fun `error conditions - channel cap restrictions - htlcMaximumMsat`() {

        val htlcMaximumMsat = 100_000_000.msat
        val paymentAmount = 200_000_000.msat // more than htlcMaximumMsat

        var (alice, bob) = TestsHelper.reachNormal()
        alice = alice.copy(channelUpdate = alice.channelUpdate.copy(htlcMaximumMsat = htlcMaximumMsat))

        val currentBlockHeight = alice.currentBlockHeight
        val channels = mapOf(alice.channelId to alice)

        val invoice = makeInvoice(recipient = bob, amount = null, supportsTrampoline = true)
        val sendPayment = SendPayment(UUID.randomUUID(), invoice, paymentAmount)

        val outgoingPaymentHandler = OutgoingPaymentHandler(alice.staticParams.nodeParams)

        var result = outgoingPaymentHandler.processSendPayment(sendPayment, channels, currentBlockHeight)

        assertTrue { result is OutgoingPaymentHandler.Result.Failure }
        val failure = result as OutgoingPaymentHandler.Result.Failure

        assertTrue { failure.reason == OutgoingPaymentHandler.FailureReason.CHANNEL_CAP_RESTRICTION }
    }

    @Test
    fun `error conditions - channel cap restrictions - maxAccpetedHtlcs`() {

        var (alice, bob) = TestsHelper.reachNormal()
        val nodeParams = alice.staticParams.nodeParams.copy(maxAcceptedHtlcs = 1)
        alice = alice.copy(staticParams = alice.staticParams.copy(nodeParams = nodeParams))

        val currentBlockHeight = alice.currentBlockHeight

        val outgoingPaymentHandler = OutgoingPaymentHandler(alice.staticParams.nodeParams)

        run {
            val invoice = makeInvoice(recipient = bob, amount = null, supportsTrampoline = true)
            val sendPayment = SendPayment(UUID.randomUUID(), invoice, 100_000.msat)

            val channels = mapOf(alice.channelId to alice)
            var result = outgoingPaymentHandler.processSendPayment(sendPayment, channels, currentBlockHeight)
            assertTrue { result is OutgoingPaymentHandler.Result.Progress }
            val progress = result as OutgoingPaymentHandler.Result.Progress

            val channelEvents = progress.actions.filterIsInstance<WrappedChannelEvent>()
            channelEvents.mapNotNull { it.channelEvent as? ExecuteCommand }.forEach { executeCommand ->
                var processResult = alice.process(executeCommand)

                assertTrue { processResult.first is Normal }
                alice = processResult.first as Normal
            }
        }
        run {
            val invoice = makeInvoice(recipient = bob, amount = null, supportsTrampoline = true)
            val sendPayment = SendPayment(UUID.randomUUID(), invoice, 100_000.msat)

            val channels = mapOf(alice.channelId to alice)
            var result = outgoingPaymentHandler.processSendPayment(sendPayment, channels, currentBlockHeight)

            assertTrue { result is OutgoingPaymentHandler.Result.Failure }
            val failure = result as OutgoingPaymentHandler.Result.Failure

            assertTrue { failure.reason == OutgoingPaymentHandler.FailureReason.CHANNEL_CAP_RESTRICTION }
        }
    }

    @Test
    fun `error conditions - channel cap restrictions - maxHtlcValueInFlight`() {

        var (alice, bob) = TestsHelper.reachNormal()
        val nodeParams = alice.staticParams.nodeParams.copy(maxHtlcValueInFlightMsat = 150_000)
        alice = alice.copy(staticParams = alice.staticParams.copy(nodeParams = nodeParams))

        val currentBlockHeight = alice.currentBlockHeight

        val outgoingPaymentHandler = OutgoingPaymentHandler(alice.staticParams.nodeParams)

        run {
            val invoice = makeInvoice(recipient = bob, amount = null, supportsTrampoline = true)
            val sendPayment = SendPayment(UUID.randomUUID(), invoice, 100_000.msat)

            val channels = mapOf(alice.channelId to alice)
            var result = outgoingPaymentHandler.processSendPayment(sendPayment, channels, currentBlockHeight)
            assertTrue { result is OutgoingPaymentHandler.Result.Progress }
            val progress = result as OutgoingPaymentHandler.Result.Progress

            val channelEvents = progress.actions.filterIsInstance<WrappedChannelEvent>()
            channelEvents.mapNotNull { it.channelEvent as? ExecuteCommand }.forEach { executeCommand ->
                var processResult = alice.process(executeCommand)

                assertTrue { processResult.first is Normal }
                alice = processResult.first as Normal
            }
        }
        run {
            val invoice = makeInvoice(recipient = bob, amount = null, supportsTrampoline = true)
            val sendPayment = SendPayment(UUID.randomUUID(), invoice, 100_000.msat)

            val channels = mapOf(alice.channelId to alice)
            var result = outgoingPaymentHandler.processSendPayment(sendPayment, channels, currentBlockHeight)

            assertTrue { result is OutgoingPaymentHandler.Result.Failure }
            val failure = result as OutgoingPaymentHandler.Result.Failure

            assertTrue { failure.reason == OutgoingPaymentHandler.FailureReason.CHANNEL_CAP_RESTRICTION }
        }
    }

    @Test
    fun `increase trampolineFees according to schedule`() {

        val (alice, bob) = TestsHelper.reachNormal()
        val currentBlockHeight = alice.currentBlockHeight
        val channels = mapOf(alice.channelId to alice)

        val invoiceAmount = 100_000.msat
        val invoice = makeInvoice(recipient = bob, amount = invoiceAmount, supportsTrampoline = true)
        val sendPayment = SendPayment(UUID.randomUUID(), invoice, invoiceAmount)

        val outgoingPaymentHandler = OutgoingPaymentHandler(alice.staticParams.nodeParams)
        var result: OutgoingPaymentHandler.Result? = null

        var allParams = mutableListOf<OutgoingPaymentHandler.TrampolineParams>()
        run {
            var i = 0
            while (true) {
                val params = OutgoingPaymentHandler.TrampolineParams.get(i++)
                if (params == null) {
                    break
                } else {
                    allParams.add(params)
                }
            }
        }

        for (params in allParams) {

            if (result == null) {
                result = outgoingPaymentHandler.processSendPayment(sendPayment, channels, currentBlockHeight)

            } else {
                val updateFailHtlc = UpdateFailHtlc(alice.channelId, 0, Eclair.randomBytes32())
                val processFail = ProcessFail(fail = updateFailHtlc, paymentId = sendPayment.paymentId)

                result = outgoingPaymentHandler.processFailure(processFail, channels, currentBlockHeight)
            }

            assertTrue { result is OutgoingPaymentHandler.Result.Progress }
            val progress = result as OutgoingPaymentHandler.Result.Progress

            val expectedTrampolineFees = expectedFees(sendPayment.paymentAmount, params)
            assertTrue { progress.trampolineFees == expectedTrampolineFees }
        }

        run {

            val updateFailHtlc = UpdateFailHtlc(alice.channelId, 0, Eclair.randomBytes32())
            val processFail = ProcessFail(fail = updateFailHtlc, paymentId = sendPayment.paymentId)

            result = outgoingPaymentHandler.processFailure(processFail, channels, currentBlockHeight)

            assertTrue { result is OutgoingPaymentHandler.Result.Failure }
            val failure = result as OutgoingPaymentHandler.Result.Failure

            assertTrue { failure.reason == OutgoingPaymentHandler.FailureReason.NO_ROUTE_TO_RECIPIENT }
        }
    }

    @Test
    fun `successful trampoline response`() {

        val (alice, bob) = TestsHelper.reachNormal()
        val currentBlockHeight = TestConstants.defaultBlockHeight
        val channels = mapOf(alice.channelId to alice)

        val invoiceAmount = 100_000.msat
        val invoice = makeInvoice(recipient = bob, amount = invoiceAmount, supportsTrampoline = true)
        val sendPayment = SendPayment(UUID.randomUUID(), invoice, invoiceAmount)

        val outgoingPaymentHandler = OutgoingPaymentHandler(alice.staticParams.nodeParams)

        var result = outgoingPaymentHandler.processSendPayment(sendPayment, channels, currentBlockHeight)
        assertTrue { result is OutgoingPaymentHandler.Result.Progress }

        val updateFulfillHtlc = UpdateFulfillHtlc(alice.channelId, 0, Eclair.randomBytes32())
        val processFulfill = ProcessFulfill(fulfill = updateFulfillHtlc, paymentId = sendPayment.paymentId)

        result = outgoingPaymentHandler.processFulfill(processFulfill)
        assertTrue { result is OutgoingPaymentHandler.Result.Success }
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

        val invoice = makeInvoice(recipient = privKeyC, amount = targetAmount, supportsTrampoline = true)
        val sendPayment = SendPayment(UUID.randomUUID(), invoice, targetAmount)

        val paymentAttempt = OutgoingPaymentHandler.PaymentAttempt(sendPayment, 0)
        val part = OutgoingPaymentHandler.TrampolinePaymentPart(
            channelId = channel.channelId,
            amount = targetAmount,
            trampolineFees = MilliSatoshi(0),
            cltvExpiryDelta = CltvExpiryDelta(576),
            status = OutgoingPaymentHandler.Status.INFLIGHT
        )

        val expectedAmountAtB = part.amount
        val expectedAmountAtC = part.amount - part.trampolineFees

        val expectedExpiryDeltaAtC = PaymentRequest.DEFAULT_MIN_FINAL_EXPIRY_DELTA
        val expectedExpiryDeltaAtB = expectedExpiryDeltaAtC + part.cltvExpiryDelta

        val expectedExpiryAtC = expectedExpiryDeltaAtC.toCltvExpiry(blockHeight)
        val expectedExpiryAtB = expectedExpiryDeltaAtB.toCltvExpiry(blockHeight)

        val paymentLifecycle = OutgoingPaymentHandler(channel.staticParams.nodeParams)
        val wrappedChannelEvent = paymentLifecycle.createHtlc(
            channel = channel,
            paymentAttempt = paymentAttempt,
            part = part,
            currentBlockHeight = channel.currentBlockHeight
        )

        val executeCommand = wrappedChannelEvent.channelEvent as? ExecuteCommand
        val cmdAddHtlc = executeCommand?.command as? CMD_ADD_HTLC

        assertNotNull(cmdAddHtlc)

        assertTrue { cmdAddHtlc.amount == expectedAmountAtB }
        assertTrue { cmdAddHtlc.cltvExpiry == expectedExpiryAtB }

        // When nodeB receives the packet, it will be decrypted, and we expect to find:
        // - isLastPacket == true (last on channel-hop sequence)
        // - contains in inner trampoline onion
        // - trampoline packet requests a trampoline-forward to nodeC

        val (outerB, innerB, packetC) = decryptNodeRelay(cmdAddHtlc.onion, cmdAddHtlc.paymentHash, privKeyB)
        assertEquals(expectedAmountAtB, outerB.amount)
        assertEquals(expectedAmountAtB, outerB.totalAmount)
        assertEquals(expectedExpiryAtB, outerB.expiry)
        assertEquals(expectedAmountAtC, innerB.amountToForward)
        assertEquals(expectedExpiryAtC, innerB.outgoingCltv)
        assertEquals(pubKeyC, innerB.outgoingNodeId)
        assertNull(innerB.invoiceRoutingInfo)
        assertNull(innerB.invoiceFeatures)
        assertNull(innerB.paymentSecret)

        // NodeB will wrap the remaining trampolinePacket, and forward it to nodeC.
        // It doesn't matter which route is used to forward the packet.

        val lastTrampolinePayload = FinalPayload.createTrampolinePayload(
            amount = innerB.amountToForward,
            totalAmount = innerB.amountToForward,
            expiry = innerB.outgoingCltv,
            paymentSecret = Eclair.randomBytes32(), // real paymentSecret is inside trampoline (packetC)
            trampolinePacket = packetC
        )
        val lastTrampolineHop = NodeHop(
            nodeId = pubKeyB,
            nextNodeId = pubKeyC,
            cltvExpiryDelta = innerB.outgoingCltv - outerB.expiry,
            fee = MilliSatoshi(0) // not unit testing this - decided by trampoline
        )
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
                        OnionTlv.OutgoingCltv(expectedExpiryAtC),
                        OnionTlv.PaymentData(sendPayment.paymentRequest.paymentSecret!!, targetAmount)
                    )
                )
            )
        )
    }
}