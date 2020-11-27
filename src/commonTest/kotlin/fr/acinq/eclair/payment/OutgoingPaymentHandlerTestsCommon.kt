package fr.acinq.eclair.payment

import fr.acinq.bitcoin.Block
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.Crypto
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.eclair.*
import fr.acinq.eclair.Eclair.randomBytes32
import fr.acinq.eclair.Eclair.randomKey
import fr.acinq.eclair.channel.*
import fr.acinq.eclair.crypto.sphinx.FailurePacket
import fr.acinq.eclair.crypto.sphinx.Sphinx
import fr.acinq.eclair.io.SendPayment
import fr.acinq.eclair.tests.utils.EclairTestSuite
import fr.acinq.eclair.transactions.CommitmentSpec
import fr.acinq.eclair.utils.*
import fr.acinq.eclair.wire.*
import kotlin.test.*

@ExperimentalUnsignedTypes
class OutgoingPaymentHandlerTestsCommon : EclairTestSuite() {

    private val defaultTrampolineParams = RouteCalculation.TrampolineParams(TestConstants.Bob.nodeParams.nodeId, RouteCalculation.defaultTrampolineFees)

    @Test
    fun `invalid payment amount`() {
        val (alice, _) = TestsHelper.reachNormal()
        val invoice = makeInvoice(amount = 100_000.msat, supportsTrampoline = true)
        val outgoingPaymentHandler = OutgoingPaymentHandler(alice.staticParams.nodeParams, defaultTrampolineParams)
        val payment = SendPayment(UUID.randomUUID(), invoice, MilliSatoshi(-1))
        val result = outgoingPaymentHandler.sendPayment(payment, mapOf(), alice.currentBlockHeight)
        assertEquals(result, OutgoingPaymentHandler.Failure(payment, FinalFailure.InvalidPaymentAmount.toPaymentFailure()))
        assertNull(outgoingPaymentHandler.getPendingPayment(payment.paymentId))
    }

    @Test
    fun `no available channels`() {
        val (alice, _) = TestsHelper.reachNormal()
        val invoice = makeInvoice(amount = 100_000.msat, supportsTrampoline = true)
        val outgoingPaymentHandler = OutgoingPaymentHandler(alice.staticParams.nodeParams, defaultTrampolineParams)
        val payment = SendPayment(UUID.randomUUID(), invoice, 100_000.msat)
        val result = outgoingPaymentHandler.sendPayment(payment, mapOf(alice.channelId to Offline(alice)), alice.currentBlockHeight)
        assertEquals(result, OutgoingPaymentHandler.Failure(payment, FinalFailure.NoAvailableChannels.toPaymentFailure()))
        assertNull(outgoingPaymentHandler.getPendingPayment(payment.paymentId))
    }

    @Test
    fun `insufficient funds`() {
        val (alice, _) = TestsHelper.reachNormal()
        val amount = alice.commitments.availableBalanceForSend() + 10.msat
        val invoice = makeInvoice(amount = amount, supportsTrampoline = true)
        val outgoingPaymentHandler = OutgoingPaymentHandler(alice.staticParams.nodeParams, defaultTrampolineParams)
        val payment = SendPayment(UUID.randomUUID(), invoice, amount)
        val result = outgoingPaymentHandler.sendPayment(payment, mapOf(alice.channelId to alice), alice.currentBlockHeight)
        assertEquals(result, OutgoingPaymentHandler.Failure(payment, FinalFailure.InsufficientBalance.toPaymentFailure()))
        assertNull(outgoingPaymentHandler.getPendingPayment(payment.paymentId))
    }

    @Test
    fun `channel restrictions (maxAcceptedHtlcs)`() {
        var (alice, _) = TestsHelper.reachNormal()
        alice = alice.copy(commitments = alice.commitments.copy(remoteParams = alice.commitments.remoteParams.copy(maxAcceptedHtlcs = 1)))
        val outgoingPaymentHandler = OutgoingPaymentHandler(alice.staticParams.nodeParams, defaultTrampolineParams)

        run {
            // Send payment 1 of 2: this should work because we're still under the maxAcceptedHtlcs.
            val invoice = makeInvoice(amount = null, supportsTrampoline = true)
            val payment = SendPayment(UUID.randomUUID(), invoice, 100_000.msat)
            val result = outgoingPaymentHandler.sendPayment(payment, mapOf(alice.channelId to alice), alice.currentBlockHeight)
            assertTrue { result is OutgoingPaymentHandler.Progress }

            val progress = result as OutgoingPaymentHandler.Progress
            assertEquals(1, result.actions.size)
            val processResult = alice.process(progress.actions.first().channelEvent)
            assertTrue { processResult.first is Normal }
            assertTrue { processResult.second.filterIsInstance<ChannelAction.ProcessCmdRes>().isEmpty() }
            alice = processResult.first as Normal
            assertNotNull(outgoingPaymentHandler.getPendingPayment(payment.paymentId))
        }
        run {
            // Send payment 2 of 2: this should exceed the configured maxAcceptedHtlcs.
            val invoice = makeInvoice(amount = null, supportsTrampoline = true)
            val payment = SendPayment(UUID.randomUUID(), invoice, 50_000.msat)
            val result1 = outgoingPaymentHandler.sendPayment(payment, mapOf(alice.channelId to alice), alice.currentBlockHeight)
            assertTrue { result1 is OutgoingPaymentHandler.Progress }

            val progress = result1 as OutgoingPaymentHandler.Progress
            assertEquals(1, result1.actions.size)
            val cmdAdd = progress.actions.first().channelEvent
            val processResult = alice.process(cmdAdd)
            assertTrue { processResult.first is Normal }
            alice = processResult.first as Normal

            val addFailure = processResult.second.filterIsInstance<ChannelAction.ProcessCmdRes.AddFailed>().firstOrNull()
            assertNotNull(addFailure)
            // Now the channel error gets sent back to the OutgoingPaymentHandler.
            val result2 = outgoingPaymentHandler.processAddFailed(alice.channelId, addFailure, mapOf(alice.channelId to alice))
            val expected = OutgoingPaymentHandler.Failure(
                payment,
                OutgoingPaymentFailure(FinalFailure.NoAvailableChannels, listOf(Either.Left(TooManyAcceptedHtlcs(alice.channelId, 1))))
            )
            assertEquals(result2, expected)
            assertNull(outgoingPaymentHandler.getPendingPayment(payment.paymentId))
        }
    }

    @Test
    fun `channel restrictions (maxHtlcValueInFlight)`() {
        var (alice, _) = TestsHelper.reachNormal()
        val maxHtlcValueInFlightMsat = 150_000L
        alice = alice.copy(commitments = alice.commitments.copy(remoteParams = alice.commitments.remoteParams.copy(maxHtlcValueInFlightMsat = maxHtlcValueInFlightMsat)))
        val outgoingPaymentHandler = OutgoingPaymentHandler(alice.staticParams.nodeParams, defaultTrampolineParams)

        run {
            // Send payment 1 of 2: this should work because we're still under the maxHtlcValueInFlightMsat.
            val invoice = makeInvoice(amount = null, supportsTrampoline = true)
            val payment = SendPayment(UUID.randomUUID(), invoice, 100_000.msat)
            val result = outgoingPaymentHandler.sendPayment(payment, mapOf(alice.channelId to alice), alice.currentBlockHeight)
            assertTrue { result is OutgoingPaymentHandler.Progress }

            val progress = result as OutgoingPaymentHandler.Progress
            assertEquals(1, result.actions.size)
            val processResult = alice.process(progress.actions.first().channelEvent)
            assertTrue { processResult.first is Normal }
            assertTrue { processResult.second.filterIsInstance<ChannelAction.ProcessCmdRes>().isEmpty() }
            alice = processResult.first as Normal
            assertNotNull(outgoingPaymentHandler.getPendingPayment(payment.paymentId))
        }
        run {
            // Send payment 2 of 2: this should exceed the configured maxHtlcValueInFlightMsat.
            val invoice = makeInvoice(amount = null, supportsTrampoline = true)
            val payment = SendPayment(UUID.randomUUID(), invoice, 100_000.msat)
            val result1 = outgoingPaymentHandler.sendPayment(payment, mapOf(alice.channelId to alice), alice.currentBlockHeight)
            assertTrue { result1 is OutgoingPaymentHandler.Progress }

            val progress = result1 as OutgoingPaymentHandler.Progress
            assertEquals(1, result1.actions.size)
            val cmdAdd = progress.actions.first().channelEvent
            val processResult = alice.process(cmdAdd)
            assertTrue { processResult.first is Normal }
            alice = processResult.first as Normal

            val addFailure = processResult.second.filterIsInstance<ChannelAction.ProcessCmdRes.AddFailed>().firstOrNull()
            assertNotNull(addFailure)
            // Now the channel error gets sent back to the OutgoingPaymentHandler.
            val result2 = outgoingPaymentHandler.processAddFailed(alice.channelId, addFailure, mapOf(alice.channelId to alice))
            val expected = OutgoingPaymentHandler.Failure(
                payment,
                OutgoingPaymentFailure(FinalFailure.NoAvailableChannels, listOf(Either.Left(HtlcValueTooHighInFlight(alice.channelId, maxHtlcValueInFlightMsat.toULong(), 200_000.msat))))
            )
            assertEquals(result2, expected)
            assertNull(outgoingPaymentHandler.getPendingPayment(payment.paymentId))
        }
    }

    @Test
    fun `successful first attempt (single part)`() {
        val channels = makeChannels()
        val trampolineParams = defaultTrampolineParams.copy(attempts = listOf(RouteCalculation.TrampolineFees(3.sat, 0.01f, CltvExpiryDelta(144))))
        val outgoingPaymentHandler = OutgoingPaymentHandler(TestConstants.Alice.nodeParams, trampolineParams)
        val recipientKey = randomKey()
        val payment = SendPayment(UUID.randomUUID(), makeInvoice(amount = 195_000.msat, supportsTrampoline = true, privKey = recipientKey), 200_000.msat) // we slightly overpay the invoice amount

        val result = outgoingPaymentHandler.sendPayment(payment, channels, TestConstants.defaultBlockHeight) as OutgoingPaymentHandler.Progress
        val adds = filterAddHtlcCommands(result)
        assertEquals(1, adds.size)
        val (channelId, add) = adds.first()
        assertEquals(205_000.msat, add.amount)
        assertEquals(payment.paymentHash, add.paymentHash)

        // The trampoline node should receive the right forwarding information.
        val (outerB, innerB, packetC) = PaymentPacketTestsCommon.decryptNodeRelay(makeUpdateAddHtlc(channelId, add), TestConstants.Bob.nodeParams.nodePrivateKey)
        assertEquals(205_000.msat, outerB.amount)
        assertEquals(205_000.msat, outerB.totalAmount)
        assertEquals(CltvExpiry(TestConstants.defaultBlockHeight.toLong()) + CltvExpiryDelta(144) + PaymentRequest.DEFAULT_MIN_FINAL_EXPIRY_DELTA, outerB.expiry)
        assertEquals(200_000.msat, innerB.amountToForward)
        assertEquals(CltvExpiry(TestConstants.defaultBlockHeight.toLong()) + PaymentRequest.DEFAULT_MIN_FINAL_EXPIRY_DELTA, innerB.outgoingCltv)
        assertEquals(payment.recipientNodeId, innerB.outgoingNodeId)
        assertNull(innerB.invoiceRoutingInfo)
        assertNull(innerB.invoiceFeatures)
        assertNull(innerB.paymentSecret)

        // The recipient should receive the right amount and expiry.
        val payloadBytesC = Sphinx.peel(recipientKey, payment.paymentHash, packetC, OnionRoutingPacket.TrampolinePacketLength).right!!
        val payloadC = FinalPayload.read(payloadBytesC.payload.toByteArray())
        assertEquals(200_000.msat, payloadC.amount)
        assertEquals(CltvExpiry(TestConstants.defaultBlockHeight.toLong()) + PaymentRequest.DEFAULT_MIN_FINAL_EXPIRY_DELTA, payloadC.expiry)
        assertEquals(payloadC.amount, payloadC.totalAmount)
        assertEquals(payment.paymentRequest.paymentSecret, payloadC.paymentSecret)

        val preimage = randomBytes32()
        val success = outgoingPaymentHandler.processAddSettled(createRemoteFulfill(channelId, add, preimage))
        assertEquals(OutgoingPaymentHandler.Success(payment, preimage, 5_000.msat), success)
        assertNull(outgoingPaymentHandler.getPendingPayment(payment.paymentId))
    }

    @Test
    fun `successful first attempt (multiple parts)`() {
        val channels = makeChannels()
        val trampolineParams = defaultTrampolineParams.copy(attempts = listOf(RouteCalculation.TrampolineFees(10.sat, 0f, CltvExpiryDelta(144))))
        val outgoingPaymentHandler = OutgoingPaymentHandler(TestConstants.Alice.nodeParams, trampolineParams)
        val recipientKey = randomKey()
        val payment = SendPayment(UUID.randomUUID(), makeInvoice(amount = null, supportsTrampoline = true, privKey = recipientKey), 300_000.msat)

        val result = outgoingPaymentHandler.sendPayment(payment, channels, TestConstants.defaultBlockHeight) as OutgoingPaymentHandler.Progress
        val adds = filterAddHtlcCommands(result)
        assertEquals(2, adds.size)
        assertEquals(310_000.msat, adds.map { it.second.amount }.sum())
        adds.forEach { assertEquals(payment.paymentHash, it.second.paymentHash) }

        adds.forEach { (channelId, add) ->
            // The trampoline node should receive the right forwarding information.
            val (outerB, innerB, packetC) = PaymentPacketTestsCommon.decryptNodeRelay(makeUpdateAddHtlc(channelId, add), TestConstants.Bob.nodeParams.nodePrivateKey)
            assertEquals(add.amount, outerB.amount)
            assertEquals(310_000.msat, outerB.totalAmount)
            assertEquals(CltvExpiry(TestConstants.defaultBlockHeight.toLong()) + CltvExpiryDelta(144) + PaymentRequest.DEFAULT_MIN_FINAL_EXPIRY_DELTA, outerB.expiry)
            assertEquals(300_000.msat, innerB.amountToForward)
            assertEquals(CltvExpiry(TestConstants.defaultBlockHeight.toLong()) + PaymentRequest.DEFAULT_MIN_FINAL_EXPIRY_DELTA, innerB.outgoingCltv)
            assertEquals(payment.recipientNodeId, innerB.outgoingNodeId)
            assertNull(innerB.invoiceRoutingInfo)
            assertNull(innerB.invoiceFeatures)
            assertNull(innerB.paymentSecret)

            // The recipient should receive the right amount and expiry.
            val payloadBytesC = Sphinx.peel(recipientKey, payment.paymentHash, packetC, OnionRoutingPacket.TrampolinePacketLength).right!!
            val payloadC = FinalPayload.read(payloadBytesC.payload.toByteArray())
            assertEquals(300_000.msat, payloadC.amount)
            assertEquals(CltvExpiry(TestConstants.defaultBlockHeight.toLong()) + PaymentRequest.DEFAULT_MIN_FINAL_EXPIRY_DELTA, payloadC.expiry)
            assertEquals(payloadC.amount, payloadC.totalAmount)
            assertEquals(payment.paymentRequest.paymentSecret, payloadC.paymentSecret)
        }

        val preimage = randomBytes32()
        val (channelId1, add1) = adds[0]
        val fulfill1 = createRemoteFulfill(channelId1, add1, preimage)
        val success1 = outgoingPaymentHandler.processAddSettled(fulfill1)
        assertEquals(OutgoingPaymentHandler.PreimageReceived(payment, preimage), success1)
        val (channelId2, add2) = adds[1]
        val fulfill2 = ChannelAction.ProcessCmdRes.AddSettledFulfill(add2.paymentId, makeUpdateAddHtlc(channelId2, add2), ChannelAction.HtlcResult.Fulfill.OnChainFulfill(preimage))
        val success2 = outgoingPaymentHandler.processAddSettled(fulfill2)
        assertEquals(OutgoingPaymentHandler.Success(payment, preimage, 10_000.msat), success2)
        assertNull(outgoingPaymentHandler.getPendingPayment(payment.paymentId))
    }

    @Test
    fun `successful first attempt (multiple parts, legacy recipient)`() {
        val channels = makeChannels()
        val trampolineParams = defaultTrampolineParams.copy(attempts = listOf(RouteCalculation.TrampolineFees(10.sat, 0f, CltvExpiryDelta(144))))
        val outgoingPaymentHandler = OutgoingPaymentHandler(TestConstants.Alice.nodeParams, trampolineParams)
        val recipientKey = randomKey()
        val extraHops = listOf(listOf(PaymentRequest.TaggedField.ExtraHop(randomKey().publicKey(), ShortChannelId(42), 10.msat, 100, CltvExpiryDelta(48))))
        val payment = SendPayment(UUID.randomUUID(), makeInvoice(amount = null, supportsTrampoline = false, privKey = recipientKey, extraHops = extraHops), 300_000.msat)

        val result = outgoingPaymentHandler.sendPayment(payment, channels, TestConstants.defaultBlockHeight) as OutgoingPaymentHandler.Progress
        val adds = filterAddHtlcCommands(result)
        assertEquals(2, adds.size)
        assertEquals(310_000.msat, adds.map { it.second.amount }.sum())
        adds.forEach { assertEquals(payment.paymentHash, it.second.paymentHash) }

        adds.forEach { (channelId, add) ->
            // The trampoline node should receive the right forwarding information.
            val (outerB, innerB, _) = PaymentPacketTestsCommon.decryptNodeRelay(makeUpdateAddHtlc(channelId, add), TestConstants.Bob.nodeParams.nodePrivateKey)
            assertEquals(add.amount, outerB.amount)
            assertEquals(310_000.msat, outerB.totalAmount)
            assertEquals(CltvExpiry(TestConstants.defaultBlockHeight.toLong()) + CltvExpiryDelta(144) + PaymentRequest.DEFAULT_MIN_FINAL_EXPIRY_DELTA, outerB.expiry)
            assertEquals(300_000.msat, innerB.amountToForward)
            assertEquals(CltvExpiry(TestConstants.defaultBlockHeight.toLong()) + PaymentRequest.DEFAULT_MIN_FINAL_EXPIRY_DELTA, innerB.outgoingCltv)
            assertEquals(payment.recipientNodeId, innerB.outgoingNodeId)
            assertEquals(payment.paymentRequest.paymentSecret, innerB.paymentSecret)
            assertEquals(payment.paymentRequest.features!!, innerB.invoiceFeatures)
            assertFalse(innerB.invoiceRoutingInfo.isNullOrEmpty())
            assertEquals(payment.paymentRequest.routingInfo.map { it.hints }, innerB.invoiceRoutingInfo)
        }

        val preimage = randomBytes32()
        val (channelId1, add1) = adds[0]
        val success1 = outgoingPaymentHandler.processAddSettled(createRemoteFulfill(channelId1, add1, preimage))
        assertEquals(OutgoingPaymentHandler.PreimageReceived(payment, preimage), success1)
        val (channelId2, add2) = adds[1]
        val success2 = outgoingPaymentHandler.processAddSettled(createRemoteFulfill(channelId2, add2, preimage))
        assertEquals(OutgoingPaymentHandler.Success(payment, preimage, 10_000.msat), success2)
        assertNull(outgoingPaymentHandler.getPendingPayment(payment.paymentId))
    }

    @Test
    fun `successful second attempt`() {
        val channels = makeChannels()
        val outgoingPaymentHandler = OutgoingPaymentHandler(TestConstants.Alice.nodeParams, defaultTrampolineParams)
        val recipientKey = randomKey()
        val payment = SendPayment(UUID.randomUUID(), makeInvoice(amount = null, supportsTrampoline = true, privKey = recipientKey), 300_000.msat)

        val progress1 = outgoingPaymentHandler.sendPayment(payment, channels, TestConstants.defaultBlockHeight) as OutgoingPaymentHandler.Progress
        val adds1 = filterAddHtlcCommands(progress1)
        assertEquals(2, adds1.size)
        assertEquals(300_000.msat, adds1.map { it.second.amount }.sum())

        // This first attempt fails because fees are too low.
        val attempt = outgoingPaymentHandler.getPendingPayment(payment.paymentId)!!
        val fail1 = outgoingPaymentHandler.processAddSettled(adds1[0].first, createRemoteFailure(adds1[0].second, attempt, TrampolineFeeInsufficient), channels, TestConstants.defaultBlockHeight)
        assertNull(fail1)
        val progress2 = outgoingPaymentHandler.processAddSettled(adds1[1].first, createRemoteFailure(adds1[1].second, attempt, TrampolineFeeInsufficient), channels, TestConstants.defaultBlockHeight) as OutgoingPaymentHandler.Progress
        val adds2 = filterAddHtlcCommands(progress2)
        assertEquals(2, adds2.size)
        assertEquals(301_030.msat, adds2.map { it.second.amount }.sum())
        adds2.forEach { assertEquals(payment.paymentHash, it.second.paymentHash) }
        adds2.forEach { (channelId, add) ->
            // The trampoline node should receive the right forwarding information.
            val (outerB, innerB, packetC) = PaymentPacketTestsCommon.decryptNodeRelay(makeUpdateAddHtlc(channelId, add), TestConstants.Bob.nodeParams.nodePrivateKey)
            assertEquals(add.amount, outerB.amount)
            assertEquals(301_030.msat, outerB.totalAmount)
            assertEquals(CltvExpiry(TestConstants.defaultBlockHeight.toLong()) + CltvExpiryDelta(576) + PaymentRequest.DEFAULT_MIN_FINAL_EXPIRY_DELTA, outerB.expiry)
            assertEquals(300_000.msat, innerB.amountToForward)
            assertEquals(CltvExpiry(TestConstants.defaultBlockHeight.toLong()) + PaymentRequest.DEFAULT_MIN_FINAL_EXPIRY_DELTA, innerB.outgoingCltv)
            assertEquals(payment.recipientNodeId, innerB.outgoingNodeId)
            assertNull(innerB.invoiceRoutingInfo)
            assertNull(innerB.invoiceFeatures)
            assertNull(innerB.paymentSecret)

            // The recipient should receive the right amount and expiry.
            val payloadBytesC = Sphinx.peel(recipientKey, payment.paymentHash, packetC, OnionRoutingPacket.TrampolinePacketLength).right!!
            val payloadC = FinalPayload.read(payloadBytesC.payload.toByteArray())
            assertEquals(300_000.msat, payloadC.amount)
            assertEquals(CltvExpiry(TestConstants.defaultBlockHeight.toLong()) + PaymentRequest.DEFAULT_MIN_FINAL_EXPIRY_DELTA, payloadC.expiry)
            assertEquals(payloadC.amount, payloadC.totalAmount)
            assertEquals(payment.paymentRequest.paymentSecret, payloadC.paymentSecret)
        }

        // The second attempt succeeds.
        val preimage = randomBytes32()
        val success1 = outgoingPaymentHandler.processAddSettled(createRemoteFulfill(adds2[0].first, adds2[0].second, preimage))
        assertEquals(OutgoingPaymentHandler.PreimageReceived(payment, preimage), success1)
        assertNotNull(outgoingPaymentHandler.getPendingPayment(payment.paymentId))
        val success2 = outgoingPaymentHandler.processAddSettled(createRemoteFulfill(adds2[1].first, adds2[1].second, preimage))
        assertEquals(OutgoingPaymentHandler.Success(payment, preimage, 1_030.msat), success2)
        assertNull(outgoingPaymentHandler.getPendingPayment(payment.paymentId))
    }

    @Test
    fun `insufficient funds when retrying with higher fees`() {
        val channels = makeChannels()
        val trampolineParams = defaultTrampolineParams.copy(
            attempts = listOf(
                RouteCalculation.TrampolineFees(10.sat, 0f, CltvExpiryDelta(144)),
                RouteCalculation.TrampolineFees(100.sat, 0f, CltvExpiryDelta(144)),
            )
        )
        val outgoingPaymentHandler = OutgoingPaymentHandler(TestConstants.Alice.nodeParams, trampolineParams)
        val payment = SendPayment(UUID.randomUUID(), makeInvoice(amount = null, supportsTrampoline = true), 550_000.msat)

        val progress1 = outgoingPaymentHandler.sendPayment(payment, channels, TestConstants.defaultBlockHeight) as OutgoingPaymentHandler.Progress
        val adds1 = filterAddHtlcCommands(progress1)
        assertEquals(3, adds1.size)
        assertEquals(560_000.msat, adds1.map { it.second.amount }.sum())

        val attempt = outgoingPaymentHandler.getPendingPayment(payment.paymentId)!!
        assertNull(outgoingPaymentHandler.processAddSettled(adds1[0].first, createRemoteFailure(adds1[0].second, attempt, TrampolineFeeInsufficient), channels, TestConstants.defaultBlockHeight))
        assertNull(outgoingPaymentHandler.processAddSettled(adds1[1].first, createRemoteFailure(adds1[1].second, attempt, TrampolineFeeInsufficient), channels, TestConstants.defaultBlockHeight))
        val fail = outgoingPaymentHandler.processAddSettled(adds1[2].first, createRemoteFailure(adds1[2].second, attempt, TrampolineFeeInsufficient), channels, TestConstants.defaultBlockHeight) as OutgoingPaymentHandler.Failure
        val expected = OutgoingPaymentHandler.Failure(payment, OutgoingPaymentFailure(FinalFailure.InsufficientBalance, listOf(Either.Right(TrampolineFeeInsufficient))))
        assertEquals(expected, fail)
        assertNull(outgoingPaymentHandler.getPendingPayment(payment.paymentId))
    }

    @Test
    fun `retries exhausted`() {
        val channels = makeChannels()
        val trampolineParams = defaultTrampolineParams.copy(
            attempts = listOf(
                RouteCalculation.TrampolineFees(10.sat, 0f, CltvExpiryDelta(144)),
                RouteCalculation.TrampolineFees(20.sat, 0f, CltvExpiryDelta(144)),
            )
        )
        val outgoingPaymentHandler = OutgoingPaymentHandler(TestConstants.Alice.nodeParams, trampolineParams)
        val payment = SendPayment(UUID.randomUUID(), makeInvoice(amount = null, supportsTrampoline = true), 220_000.msat)

        val progress1 = outgoingPaymentHandler.sendPayment(payment, channels, TestConstants.defaultBlockHeight) as OutgoingPaymentHandler.Progress
        val adds1 = filterAddHtlcCommands(progress1)
        assertEquals(1, adds1.size)
        assertEquals(230_000.msat, adds1.map { it.second.amount }.sum())

        val attempt1 = outgoingPaymentHandler.getPendingPayment(payment.paymentId)!!
        val progress2 = outgoingPaymentHandler.processAddSettled(adds1[0].first, createRemoteFailure(adds1[0].second, attempt1, TrampolineFeeInsufficient), channels, TestConstants.defaultBlockHeight) as OutgoingPaymentHandler.Progress
        val adds2 = filterAddHtlcCommands(progress2)
        assertEquals(1, adds2.size)
        assertEquals(240_000.msat, adds2.map { it.second.amount }.sum())

        val attempt2 = outgoingPaymentHandler.getPendingPayment(payment.paymentId)!!
        val fail = outgoingPaymentHandler.processAddSettled(adds2[0].first, createRemoteFailure(adds2[0].second, attempt2, TrampolineFeeInsufficient), channels, TestConstants.defaultBlockHeight) as OutgoingPaymentHandler.Failure
        val expected = OutgoingPaymentHandler.Failure(payment, OutgoingPaymentFailure(FinalFailure.RetryExhausted, listOf(Either.Right(TrampolineFeeInsufficient))))
        assertEquals(expected, fail)
        assertNull(outgoingPaymentHandler.getPendingPayment(payment.paymentId))
    }

    @Test
    fun `non-retriable remote failure`() {
        val fatalFailures = listOf(UnknownNextPeer, IncorrectOrUnknownPaymentDetails(50_000.msat, TestConstants.defaultBlockHeight.toLong()))
        fatalFailures.forEach { remoteFailure ->
            val channels = makeChannels()
            val outgoingPaymentHandler = OutgoingPaymentHandler(TestConstants.Alice.nodeParams, defaultTrampolineParams)
            val payment = SendPayment(UUID.randomUUID(), makeInvoice(amount = null, supportsTrampoline = true), 50_000.msat)

            val progress = outgoingPaymentHandler.sendPayment(payment, channels, TestConstants.defaultBlockHeight) as OutgoingPaymentHandler.Progress
            val adds = filterAddHtlcCommands(progress)
            assertEquals(1, adds.size)
            assertEquals(50_000.msat, adds.map { it.second.amount }.sum())

            val attempt = outgoingPaymentHandler.getPendingPayment(payment.paymentId)!!
            val fail = outgoingPaymentHandler.processAddSettled(adds[0].first, createRemoteFailure(adds[0].second, attempt, remoteFailure), channels, TestConstants.defaultBlockHeight) as OutgoingPaymentHandler.Failure
            val expected = OutgoingPaymentHandler.Failure(payment, OutgoingPaymentFailure(FinalFailure.UnknownError, listOf(Either.Right(remoteFailure))))
            assertEquals(expected, fail)
            assertNull(outgoingPaymentHandler.getPendingPayment(payment.paymentId))
        }
    }

    @Test
    fun `local channel failures`() {
        val channels = makeChannels()
        val outgoingPaymentHandler = OutgoingPaymentHandler(TestConstants.Alice.nodeParams, defaultTrampolineParams)
        val payment = SendPayment(UUID.randomUUID(), makeInvoice(amount = null, supportsTrampoline = true), 5_000.msat)

        var progress = outgoingPaymentHandler.sendPayment(payment, channels, TestConstants.defaultBlockHeight) as OutgoingPaymentHandler.Progress
        assertEquals(1, progress.actions.size)
        assertEquals(5_000.msat, filterAddHtlcCommands(progress).map { it.second.amount }.sum())

        // Channels fail, so we retry with different channels, without raising the fees.
        val localFailures = listOf(
            { channelId: ByteVector32 -> TooManyAcceptedHtlcs(channelId, 15) },
            { channelId: ByteVector32 -> InsufficientFunds(channelId, 5_000.msat, 1.sat, 20.sat, 1.sat) },
            { channelId: ByteVector32 -> HtlcValueTooHighInFlight(channelId, 150_000U, 155_000.msat) },
        )
        localFailures.forEach { localFailure ->
            val (channelId, add) = filterAddHtlcCommands(progress).first()
            progress = outgoingPaymentHandler.processAddFailed(channelId, ChannelAction.ProcessCmdRes.AddFailed(add, localFailure(channelId), null), channels) as OutgoingPaymentHandler.Progress
            assertEquals(5_000.msat, add.amount)
        }

        // The last channel fails: we don't have any channels available to retry.
        val (channelId, add) = filterAddHtlcCommands(progress).first()
        val fail = outgoingPaymentHandler.processAddFailed(channelId, ChannelAction.ProcessCmdRes.AddFailed(add, TooManyAcceptedHtlcs(channelId, 15), null), channels) as OutgoingPaymentHandler.Failure
        assertEquals(FinalFailure.InsufficientBalance, fail.failure.reason)
        assertEquals(4, fail.failure.failures.filter { it.isLeft }.size)
        assertNull(outgoingPaymentHandler.getPendingPayment(payment.paymentId))
    }

    @Test
    fun `local channel failure followed by success`() {
        val channels = makeChannels()
        val outgoingPaymentHandler = OutgoingPaymentHandler(TestConstants.Alice.nodeParams, defaultTrampolineParams)
        val payment = SendPayment(UUID.randomUUID(), makeInvoice(amount = null, supportsTrampoline = true), 5_000.msat)

        val progress1 = outgoingPaymentHandler.sendPayment(payment, channels, TestConstants.defaultBlockHeight) as OutgoingPaymentHandler.Progress
        assertEquals(1, progress1.actions.size)
        assertEquals(5_000.msat, filterAddHtlcCommands(progress1).map { it.second.amount }.sum())

        // This first payment fails:
        val (channelId, add) = filterAddHtlcCommands(progress1).first()
        val progress2 = outgoingPaymentHandler.processAddFailed(channelId, ChannelAction.ProcessCmdRes.AddFailed(add, TooManyAcceptedHtlcs(channelId, 1), null), channels) as OutgoingPaymentHandler.Progress
        assertEquals(1, progress2.actions.size)
        val adds = filterAddHtlcCommands(progress2)
        assertEquals(5_000.msat, adds.map { it.second.amount }.sum())

        // This second attempt succeeds:
        val preimage = randomBytes32()
        val success = outgoingPaymentHandler.processAddSettled(createRemoteFulfill(adds[0].first, adds[0].second, preimage))
        assertEquals(OutgoingPaymentHandler.Success(payment, preimage, 0.msat), success)
        assertNull(outgoingPaymentHandler.getPendingPayment(payment.paymentId))
    }

    @Test
    fun `partial failure then fulfill (spec violation)`() {
        val channels = makeChannels()
        val outgoingPaymentHandler = OutgoingPaymentHandler(TestConstants.Alice.nodeParams, defaultTrampolineParams)
        val payment = SendPayment(UUID.randomUUID(), makeInvoice(amount = null, supportsTrampoline = true), 310_000.msat)

        val progress = outgoingPaymentHandler.sendPayment(payment, channels, TestConstants.defaultBlockHeight) as OutgoingPaymentHandler.Progress
        val adds = filterAddHtlcCommands(progress)
        assertEquals(2, adds.size)
        assertEquals(310_000.msat, adds.map { it.second.amount }.sum())

        val attempt = outgoingPaymentHandler.getPendingPayment(payment.paymentId)!!
        val remoteFailure = IncorrectOrUnknownPaymentDetails(310_000.msat, TestConstants.defaultBlockHeight.toLong())
        assertNull(outgoingPaymentHandler.processAddSettled(adds[0].first, createRemoteFailure(adds[0].second, attempt, remoteFailure), channels, TestConstants.defaultBlockHeight))

        // The recipient released the preimage without receiving the full payment amount.
        // This is a spec violation and is too bad for them, we obtained a proof of payment without paying the full amount.
        val preimage = randomBytes32()
        val success = outgoingPaymentHandler.processAddSettled(createRemoteFulfill(adds[1].first, adds[1].second, preimage)) as OutgoingPaymentHandler.Success
        assertEquals(preimage, success.preimage)
        assertTrue(success.fees < 0.msat) // since we paid much less than the expected amount, it results in negative fees
    }

    @Test
    fun `partial fulfill then failure (spec violation)`() {
        val channels = makeChannels()
        val outgoingPaymentHandler = OutgoingPaymentHandler(TestConstants.Alice.nodeParams, defaultTrampolineParams)
        val payment = SendPayment(UUID.randomUUID(), makeInvoice(amount = null, supportsTrampoline = true), 310_000.msat)

        val progress = outgoingPaymentHandler.sendPayment(payment, channels, TestConstants.defaultBlockHeight) as OutgoingPaymentHandler.Progress
        val adds = filterAddHtlcCommands(progress)
        assertEquals(2, adds.size)
        assertEquals(310_000.msat, adds.map { it.second.amount }.sum())

        val preimage = randomBytes32()
        val expected = OutgoingPaymentHandler.PreimageReceived(payment, preimage)
        val result = outgoingPaymentHandler.processAddSettled(createRemoteFulfill(adds[0].first, adds[0].second, preimage))
        assertEquals(expected, result)

        // The recipient released the preimage without receiving the full payment amount.
        // This is a spec violation and is too bad for them, we obtained a proof of payment without paying the full amount.
        val attempt = outgoingPaymentHandler.getPendingPayment(payment.paymentId)!!
        val remoteFailure = IncorrectOrUnknownPaymentDetails(310_000.msat, TestConstants.defaultBlockHeight.toLong())
        val success = outgoingPaymentHandler.processAddSettled(adds[1].first, createRemoteFailure(adds[1].second, attempt, remoteFailure), channels, TestConstants.defaultBlockHeight) as OutgoingPaymentHandler.Success
        assertEquals(preimage, success.preimage)
        assertTrue(success.fees < 0.msat) // since we paid much less than the expected amount, it results in negative fees
    }

    private fun makeInvoice(amount: MilliSatoshi?, supportsTrampoline: Boolean, privKey: PrivateKey = randomKey(), extraHops: List<List<PaymentRequest.TaggedField.ExtraHop>> = listOf()): PaymentRequest {
        val paymentPreimage: ByteVector32 = randomBytes32()
        val paymentHash = Crypto.sha256(paymentPreimage).toByteVector32()

        val invoiceFeatures = mutableSetOf(
            ActivatedFeature(Feature.VariableLengthOnion, FeatureSupport.Optional),
            ActivatedFeature(Feature.PaymentSecret, FeatureSupport.Mandatory),
            ActivatedFeature(Feature.BasicMultiPartPayment, FeatureSupport.Optional)
        )
        if (supportsTrampoline) {
            invoiceFeatures.add(ActivatedFeature(Feature.TrampolinePayment, FeatureSupport.Optional))
        }

        return PaymentRequest.create(
            chainHash = Block.LivenetGenesisBlock.hash,
            amount = amount,
            paymentHash = paymentHash,
            privateKey = privKey,
            description = "unit test",
            minFinalCltvExpiryDelta = PaymentRequest.DEFAULT_MIN_FINAL_EXPIRY_DELTA,
            features = Features(invoiceFeatures),
            extraHops = extraHops
        )
    }

    private fun makeChannels(): Map<ByteVector32, Normal> {
        val (alice, _) = TestsHelper.reachNormal()
        val reserve = alice.commitments.remoteParams.channelReserve
        val channelDetails = listOf(
            Pair(ShortChannelId(1), 250_000.msat),
            Pair(ShortChannelId(2), 150_000.msat),
            Pair(ShortChannelId(3), 0.msat),
            Pair(ShortChannelId(4), 10_000.msat),
            Pair(ShortChannelId(5), 200_000.msat),
        )
        return channelDetails.map {
            val channelId = randomBytes32()
            val channel = alice.copy(
                shortChannelId = it.first,
                commitments = alice.commitments.copy(
                    channelId = channelId,
                    remoteCommit = alice.commitments.remoteCommit.copy(spec = CommitmentSpec(setOf(), 0, 50_000.msat, (it.second + ((Commitments.ANCHOR_AMOUNT * 2) + reserve).toMilliSatoshi())))
                )
            )
            channelId to channel
        }.toMap()
    }

    private fun filterAddHtlcCommands(progress: OutgoingPaymentHandler.Progress): List<Pair<ByteVector32, CMD_ADD_HTLC>> {
        val addCommands = mutableListOf<Pair<ByteVector32, CMD_ADD_HTLC>>()
        for (action in progress.actions) {
            val addCommand = (action.channelEvent as? ChannelEvent.ExecuteCommand)?.command as? CMD_ADD_HTLC
            if (addCommand != null) {
                addCommands.add(Pair(action.channelId, addCommand))
            }
        }
        return addCommands.toList()
    }

    private fun makeUpdateAddHtlc(channelId: ByteVector32, cmd: CMD_ADD_HTLC, htlcId: Long = 0): UpdateAddHtlc =
        UpdateAddHtlc(channelId, htlcId, cmd.amount, cmd.paymentHash, cmd.cltvExpiry, cmd.onion)

    private fun createRemoteFulfill(channelId: ByteVector32, add: CMD_ADD_HTLC, preimage: ByteVector32): ChannelAction.ProcessCmdRes.AddSettledFulfill {
        val updateAddHtlc = makeUpdateAddHtlc(channelId, add)
        return ChannelAction.ProcessCmdRes.AddSettledFulfill(add.paymentId, updateAddHtlc, ChannelAction.HtlcResult.Fulfill.RemoteFulfill(UpdateFulfillHtlc(channelId, updateAddHtlc.id, preimage)))
    }

    private fun createRemoteFailure(add: CMD_ADD_HTLC, attempt: OutgoingPaymentHandler.PaymentAttempt, failureMessage: FailureMessage): ChannelAction.ProcessCmdRes.AddSettledFail {
        val sharedSecrets = attempt.pending.getValue(add.paymentId).second
        val reason = FailurePacket.create(sharedSecrets.perHopSecrets.last().first, failureMessage)
        val updateAddHtlc = makeUpdateAddHtlc(randomBytes32(), add)
        return ChannelAction.ProcessCmdRes.AddSettledFail(
            add.paymentId,
            updateAddHtlc,
            ChannelAction.HtlcResult.Fail.RemoteFail(UpdateFailHtlc(updateAddHtlc.channelId, updateAddHtlc.id, reason.toByteVector()))
        )
    }

}
