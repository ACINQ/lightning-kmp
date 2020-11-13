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
import fr.acinq.eclair.io.WrappedChannelError
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
            assertTrue { processResult.second.filterIsInstance<ProcessLocalFailure>().isEmpty() }
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

            val localFailure = processResult.second.filterIsInstance<ProcessLocalFailure>().firstOrNull()
            assertNotNull(localFailure)
            val channelError = WrappedChannelError(alice.channelId, localFailure.error, cmdAdd)
            // Now the channel error gets sent back to the OutgoingPaymentHandler.
            val result2 = outgoingPaymentHandler.processLocalFailure(channelError, mapOf(alice.channelId to alice))
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
            assertTrue { processResult.second.filterIsInstance<ProcessLocalFailure>().isEmpty() }
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

            val localFailure = processResult.second.filterIsInstance<ProcessLocalFailure>().firstOrNull()
            assertNotNull(localFailure)
            val channelError = WrappedChannelError(alice.channelId, localFailure.error, cmdAdd)
            // Now the channel error gets sent back to the OutgoingPaymentHandler.
            val result2 = outgoingPaymentHandler.processLocalFailure(channelError, mapOf(alice.channelId to alice))
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
        val add = adds.first()
        assertEquals(205_000.msat, add.amount)
        assertEquals(payment.paymentHash, add.paymentHash)
        val channelId = result.actions.first().channelId

        // The trampoline node should receive the right forwarding information.
        val (outerB, innerB, packetC) = PaymentPacketTestsCommon.decryptNodeRelay(UpdateAddHtlc(channelId, 0, add.amount, add.paymentHash, add.cltvExpiry, add.onion), TestConstants.Bob.nodeParams.nodePrivateKey)
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

        val fulfill = UpdateFulfillHtlc(channelId, 0, randomBytes32())
        val success = outgoingPaymentHandler.processFulfill(ProcessFulfill(fulfill, add.paymentId))
        assertEquals(OutgoingPaymentHandler.Success(payment, fulfill.paymentPreimage, 5_000.msat), success)
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
        assertEquals(310_000.msat, adds.map { it.amount }.sum())
        adds.forEach { assertEquals(payment.paymentHash, it.paymentHash) }

        adds.forEach { add ->
            // The trampoline node should receive the right forwarding information.
            val (outerB, innerB, packetC) = PaymentPacketTestsCommon.decryptNodeRelay(UpdateAddHtlc(randomBytes32(), 0, add.amount, add.paymentHash, add.cltvExpiry, add.onion), TestConstants.Bob.nodeParams.nodePrivateKey)
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
        val success1 = outgoingPaymentHandler.processFulfill(ProcessFulfill(UpdateFulfillHtlc(randomBytes32(), 0, preimage), adds[0].paymentId))
        assertEquals(OutgoingPaymentHandler.PreimageReceived(payment, preimage), success1)
        val success2 = outgoingPaymentHandler.processFulfill(ProcessFulfill(UpdateFulfillHtlc(randomBytes32(), 0, preimage), adds[1].paymentId))
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
        assertEquals(310_000.msat, adds.map { it.amount }.sum())
        adds.forEach { assertEquals(payment.paymentHash, it.paymentHash) }

        adds.forEach { add ->
            // The trampoline node should receive the right forwarding information.
            val (outerB, innerB, _) = PaymentPacketTestsCommon.decryptNodeRelay(UpdateAddHtlc(randomBytes32(), 0, add.amount, add.paymentHash, add.cltvExpiry, add.onion), TestConstants.Bob.nodeParams.nodePrivateKey)
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
        val success1 = outgoingPaymentHandler.processFulfill(ProcessFulfill(UpdateFulfillHtlc(randomBytes32(), 0, preimage), adds[0].paymentId))
        assertEquals(OutgoingPaymentHandler.PreimageReceived(payment, preimage), success1)
        val success2 = outgoingPaymentHandler.processFulfill(ProcessFulfill(UpdateFulfillHtlc(randomBytes32(), 0, preimage), adds[1].paymentId))
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
        assertEquals(300_000.msat, adds1.map { it.amount }.sum())

        // This first attempt fails because fees are too low.
        val attempt = outgoingPaymentHandler.getPendingPayment(payment.paymentId)!!
        val fail1 = outgoingPaymentHandler.processRemoteFailure(createRemoteFailure(adds1[0], attempt, TrampolineFeeInsufficient), channels, TestConstants.defaultBlockHeight)
        assertNull(fail1)
        val progress2 = outgoingPaymentHandler.processRemoteFailure(createRemoteFailure(adds1[1], attempt, TrampolineFeeInsufficient), channels, TestConstants.defaultBlockHeight) as OutgoingPaymentHandler.Progress
        val adds2 = filterAddHtlcCommands(progress2)
        assertEquals(2, adds2.size)
        assertEquals(301_030.msat, adds2.map { it.amount }.sum())
        adds2.forEach { assertEquals(payment.paymentHash, it.paymentHash) }
        adds2.forEach { add ->
            // The trampoline node should receive the right forwarding information.
            val (outerB, innerB, packetC) = PaymentPacketTestsCommon.decryptNodeRelay(UpdateAddHtlc(randomBytes32(), 0, add.amount, add.paymentHash, add.cltvExpiry, add.onion), TestConstants.Bob.nodeParams.nodePrivateKey)
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
        val success1 = outgoingPaymentHandler.processFulfill(ProcessFulfill(UpdateFulfillHtlc(randomBytes32(), 0, preimage), adds2[0].paymentId))
        assertEquals(OutgoingPaymentHandler.PreimageReceived(payment, preimage), success1)
        assertNotNull(outgoingPaymentHandler.getPendingPayment(payment.paymentId))
        val success2 = outgoingPaymentHandler.processFulfill(ProcessFulfill(UpdateFulfillHtlc(randomBytes32(), 0, preimage), adds2[1].paymentId))
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
        assertEquals(560_000.msat, adds1.map { it.amount }.sum())

        val attempt = outgoingPaymentHandler.getPendingPayment(payment.paymentId)!!
        assertNull(outgoingPaymentHandler.processRemoteFailure(createRemoteFailure(adds1[0], attempt, TrampolineFeeInsufficient), channels, TestConstants.defaultBlockHeight))
        assertNull(outgoingPaymentHandler.processRemoteFailure(createRemoteFailure(adds1[1], attempt, TrampolineFeeInsufficient), channels, TestConstants.defaultBlockHeight))
        val fail = outgoingPaymentHandler.processRemoteFailure(createRemoteFailure(adds1[2], attempt, TrampolineFeeInsufficient), channels, TestConstants.defaultBlockHeight) as OutgoingPaymentHandler.Failure
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
        assertEquals(230_000.msat, adds1.map { it.amount }.sum())

        val attempt1 = outgoingPaymentHandler.getPendingPayment(payment.paymentId)!!
        val progress2 = outgoingPaymentHandler.processRemoteFailure(createRemoteFailure(adds1[0], attempt1, TrampolineFeeInsufficient), channels, TestConstants.defaultBlockHeight) as OutgoingPaymentHandler.Progress
        val adds2 = filterAddHtlcCommands(progress2)
        assertEquals(1, adds2.size)
        assertEquals(240_000.msat, adds2.map { it.amount }.sum())

        val attempt2 = outgoingPaymentHandler.getPendingPayment(payment.paymentId)!!
        val fail = outgoingPaymentHandler.processRemoteFailure(createRemoteFailure(adds2[0], attempt2, TrampolineFeeInsufficient), channels, TestConstants.defaultBlockHeight) as OutgoingPaymentHandler.Failure
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
            assertEquals(50_000.msat, adds.map { it.amount }.sum())

            val attempt = outgoingPaymentHandler.getPendingPayment(payment.paymentId)!!
            val fail = outgoingPaymentHandler.processRemoteFailure(createRemoteFailure(adds[0], attempt, remoteFailure), channels, TestConstants.defaultBlockHeight) as OutgoingPaymentHandler.Failure
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
        assertEquals(5_000.msat, filterAddHtlcCommands(progress).map { it.amount }.sum())

        // Channels fail, so we retry with different channels, without raising the fees.
        val localFailures = listOf(
            { channelId: ByteVector32 -> TooManyAcceptedHtlcs(channelId, 15) },
            { channelId: ByteVector32 -> InsufficientFunds(channelId, 5_000.msat, 1.sat, 20.sat, 1.sat) },
            { channelId: ByteVector32 -> HtlcValueTooHighInFlight(channelId, 150_000U, 155_000.msat) },
        )
        localFailures.forEach { localFailure ->
            val channelId = progress.actions.first().channelId
            val channelEvent = progress.actions.first().channelEvent
            progress = outgoingPaymentHandler.processLocalFailure(WrappedChannelError(channelId, localFailure(channelId), channelEvent), channels) as OutgoingPaymentHandler.Progress
            assertEquals(5_000.msat, filterAddHtlcCommands(progress).map { it.amount }.sum())
        }

        // The last channel fails: we don't have any channels available to retry.
        val channelId = progress.actions.first().channelId
        val channelEvent = progress.actions.first().channelEvent
        val fail = outgoingPaymentHandler.processLocalFailure(WrappedChannelError(channelId, TooManyAcceptedHtlcs(channelId, 15), channelEvent), channels) as OutgoingPaymentHandler.Failure
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
        assertEquals(5_000.msat, filterAddHtlcCommands(progress1).map { it.amount }.sum())

        // This first payment fails:
        val channelId = progress1.actions.first().channelId
        val channelEvent = progress1.actions.first().channelEvent
        val progress2 = outgoingPaymentHandler.processLocalFailure(WrappedChannelError(channelId, TooManyAcceptedHtlcs(channelId, 1), channelEvent), channels) as OutgoingPaymentHandler.Progress
        assertEquals(1, progress2.actions.size)
        val adds = filterAddHtlcCommands(progress2)
        assertEquals(5_000.msat, adds.map { it.amount }.sum())

        // This second attempt succeeds:
        val preimage = randomBytes32()
        val success = outgoingPaymentHandler.processFulfill(ProcessFulfill(UpdateFulfillHtlc(randomBytes32(), 0, preimage), adds[0].paymentId))
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
        assertEquals(310_000.msat, adds.map { it.amount }.sum())

        val attempt = outgoingPaymentHandler.getPendingPayment(payment.paymentId)!!
        val remoteFailure = IncorrectOrUnknownPaymentDetails(310_000.msat, TestConstants.defaultBlockHeight.toLong())
        assertNull(outgoingPaymentHandler.processRemoteFailure(createRemoteFailure(adds[0], attempt, remoteFailure), channels, TestConstants.defaultBlockHeight))

        // The recipient released the preimage without receiving the full payment amount.
        // This is a spec violation and is too bad for them, we obtained a proof of payment without paying the full amount.
        val preimage = randomBytes32()
        val success = outgoingPaymentHandler.processFulfill(ProcessFulfill(UpdateFulfillHtlc(randomBytes32(), 0, preimage), adds[1].paymentId)) as OutgoingPaymentHandler.Success
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
        assertEquals(310_000.msat, adds.map { it.amount }.sum())

        val preimage = randomBytes32()
        val expected = OutgoingPaymentHandler.PreimageReceived(payment, preimage)
        val result = outgoingPaymentHandler.processFulfill(ProcessFulfill(UpdateFulfillHtlc(randomBytes32(), 0, preimage), adds[0].paymentId))
        assertEquals(expected, result)

        // The recipient released the preimage without receiving the full payment amount.
        // This is a spec violation and is too bad for them, we obtained a proof of payment without paying the full amount.
        val attempt = outgoingPaymentHandler.getPendingPayment(payment.paymentId)!!
        val remoteFailure = IncorrectOrUnknownPaymentDetails(310_000.msat, TestConstants.defaultBlockHeight.toLong())
        val success = outgoingPaymentHandler.processRemoteFailure(createRemoteFailure(adds[1], attempt, remoteFailure), channels, TestConstants.defaultBlockHeight) as OutgoingPaymentHandler.Success
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
                    remoteCommit = alice.commitments.remoteCommit.copy(spec = CommitmentSpec(setOf(), 0, 50_000.msat, it.second + reserve.toMilliSatoshi()))
                )
            )
            channelId to channel
        }.toMap()
    }

    private fun filterAddHtlcCommands(progress: OutgoingPaymentHandler.Progress): List<CMD_ADD_HTLC> =
        progress.actions.map {
            it.channelEvent
        }.filterIsInstance<ExecuteCommand>().map {
            it.command
        }.filterIsInstance<CMD_ADD_HTLC>()

    private fun createRemoteFailure(add: CMD_ADD_HTLC, attempt: OutgoingPaymentHandler.PaymentAttempt, failureMessage: FailureMessage): ProcessFail {
        val sharedSecrets = attempt.pending.getValue(add.paymentId).second
        val reason = FailurePacket.create(sharedSecrets.perHopSecrets.last().first, failureMessage)
        return ProcessFail(UpdateFailHtlc(randomBytes32(), 0, reason.toByteVector()), add.paymentId)
    }

}
