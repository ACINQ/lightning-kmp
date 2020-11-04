package fr.acinq.eclair.payment

import fr.acinq.bitcoin.Block
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.Crypto
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.eclair.*
import fr.acinq.eclair.channel.*
import fr.acinq.eclair.crypto.sphinx.FailurePacket
import fr.acinq.eclair.crypto.sphinx.Sphinx
import fr.acinq.eclair.io.SendPayment
import fr.acinq.eclair.io.WrappedChannelError
import fr.acinq.eclair.io.WrappedChannelEvent
import fr.acinq.eclair.tests.utils.EclairTestSuite
import fr.acinq.eclair.utils.*
import fr.acinq.eclair.wire.*
import kotlin.test.*

@ExperimentalUnsignedTypes
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

    @Test
    fun `bad payment amount`() {

        val (alice, bob) = TestsHelper.reachNormal()
        val currentBlockHeight = alice.currentBlockHeight

        val invoiceAmount = 100_000.msat
        val invoice = makeInvoice(recipient = bob, amount = invoiceAmount, supportsTrampoline = true)
        val outgoingPaymentHandler = OutgoingPaymentHandler(alice.staticParams.nodeParams)

        val sendPayment = SendPayment(UUID.randomUUID(), invoice, MilliSatoshi(-1)) // <= negative msats
        val result = outgoingPaymentHandler.sendPayment(sendPayment, mapOf(), currentBlockHeight)
        assertEquals(
            result, OutgoingPaymentHandler.SendPaymentResult.Failure(
                payment = sendPayment,
                failure = OutgoingPaymentFailure.InvalidParameter.paymentAmount()
            )
        )
    }

    @Test
    fun `no available channels`() {

        val (alice, bob) = TestsHelper.reachNormal()
        val currentBlockHeight = alice.currentBlockHeight

        val invoiceAmount = 100_000.msat
        val invoice = makeInvoice(recipient = bob, amount = invoiceAmount, supportsTrampoline = true)
        val outgoingPaymentHandler = OutgoingPaymentHandler(alice.staticParams.nodeParams)

        val sendPayment = SendPayment(UUID.randomUUID(), invoice, invoiceAmount)
        val result = outgoingPaymentHandler.sendPayment(
            sendPayment,
            mapOf(alice.channelId to Offline(alice)),
            currentBlockHeight
        )
        assertEquals(
            result, OutgoingPaymentHandler.SendPaymentResult.Failure(
                payment = sendPayment,
                failure = OutgoingPaymentFailure.make(
                    reason = OutgoingPaymentFailure.Reason.NO_AVAILABLE_CHANNELS,
                    problems = listOf()
                )
            )
        )
    }

    @Test
    fun `local channel failure`() {

        val (alice, bob) = TestsHelper.reachNormal()
        val currentBlockHeight = alice.currentBlockHeight
        val channels = mapOf(alice.channelId to alice)

        val invoiceAmount = alice.commitments.availableBalanceForSend()
        val invoice = makeInvoice(recipient = bob, amount = invoiceAmount, supportsTrampoline = true)
        val paymentId = UUID.randomUUID()
        val sendPayment = SendPayment(paymentId, invoice, invoiceAmount)

        val outgoingPaymentHandler = OutgoingPaymentHandler(alice.staticParams.nodeParams)

        val result1 = outgoingPaymentHandler.sendPayment(sendPayment, channels, currentBlockHeight)

        assertTrue { result1 is OutgoingPaymentHandler.SendPaymentResult.Progress }
        val progress = result1 as OutgoingPaymentHandler.SendPaymentResult.Progress

        val trigger = progress.actions.firstOrNull() as? WrappedChannelEvent
        assertNotNull(trigger)

        val channelId = alice.channelId
        val event = WrappedChannelError(channelId, DebugTriggeredException(channelId), trigger.channelEvent)

        val result2 = outgoingPaymentHandler.processLocalFailure(event, channels, currentBlockHeight)
        assertEquals(
            result2, OutgoingPaymentHandler.ProcessFailureResult.Failure(
                payment = sendPayment,
                failure = OutgoingPaymentFailure.make(
                    reason = OutgoingPaymentFailure.Reason.NO_AVAILABLE_CHANNELS,
                    problems = listOf(
                        Either.Left(DebugTriggeredException(channelId))
                    )
                )
            )
        )
    }

    @Test
    fun `remote channel failure - known`() {

        val (alice, bob) = TestsHelper.reachNormal()
        val currentBlockHeight = alice.currentBlockHeight
        val channels = mapOf(alice.channelId to alice)

        val invoiceAmount = alice.commitments.availableBalanceForSend()
        val invoice = makeInvoice(recipient = bob, amount = invoiceAmount, supportsTrampoline = true)
        val paymentId = UUID.randomUUID()
        val sendPayment = SendPayment(paymentId, invoice, invoiceAmount)

        val outgoingPaymentHandler = OutgoingPaymentHandler(alice.staticParams.nodeParams)

        run {
            val result = outgoingPaymentHandler.sendPayment(sendPayment, channels, currentBlockHeight)
            assertTrue { result is OutgoingPaymentHandler.SendPaymentResult.Progress }
        }
        run {
            val lastPart = outgoingPaymentHandler.pendingPaymentAttempt(paymentId)!!.parts[0]

            val reason = FailurePacket.create(lastPart.secrets[0].first, UnknownNextPeer)
            val updateFailHtlc = UpdateFailHtlc(alice.channelId, 0, reason.toByteVector())

            val processFail = ProcessFail(fail = updateFailHtlc, paymentId = sendPayment.paymentId)

            val result = outgoingPaymentHandler.processRemoteFailure(processFail, channels, currentBlockHeight)
            assertEquals(
                result, OutgoingPaymentHandler.ProcessFailureResult.Failure(
                    payment = sendPayment,
                    failure = OutgoingPaymentFailure.make(
                        reason = OutgoingPaymentFailure.Reason.OTHER_ERROR,
                        problems = listOf(
                            Either.Right(UnknownNextPeer)
                        )
                    )
                )
            )
        }
    }

    @Test
    fun `remote channel failure - unknown`() {

        val (alice, bob) = TestsHelper.reachNormal()
        val currentBlockHeight = alice.currentBlockHeight
        val channels = mapOf(alice.channelId to alice)

        val invoiceAmount = alice.commitments.availableBalanceForSend()
        val invoice = makeInvoice(recipient = bob, amount = invoiceAmount, supportsTrampoline = true)
        val paymentId = UUID.randomUUID()
        val sendPayment = SendPayment(paymentId, invoice, invoiceAmount)

        val outgoingPaymentHandler = OutgoingPaymentHandler(alice.staticParams.nodeParams)

        run {
            val result = outgoingPaymentHandler.sendPayment(sendPayment, channels, currentBlockHeight)
            assertTrue { result is OutgoingPaymentHandler.SendPaymentResult.Progress }
        }
        run {
            val reason = Eclair.randomBytes(292) // unknown error

            val updateFailHtlc = UpdateFailHtlc(alice.channelId, 0, reason.toByteVector())
            val processFail = ProcessFail(fail = updateFailHtlc, paymentId = sendPayment.paymentId)

            val result = outgoingPaymentHandler.processRemoteFailure(processFail, channels, currentBlockHeight)
            assertEquals(
                result, OutgoingPaymentHandler.ProcessFailureResult.Failure(
                    payment = sendPayment,
                    failure = OutgoingPaymentFailure.make(
                        reason = OutgoingPaymentFailure.Reason.OTHER_ERROR,
                        problems = listOf()
                    )
                )
            )
        }
    }

    @Test
    fun `insufficient capacity - base`() {

        val (alice, bob) = TestsHelper.reachNormal()
        val currentBlockHeight = alice.currentBlockHeight
        val channels = mapOf(alice.channelId to alice)

        val invoiceAmount = alice.commitments.availableBalanceForSend() + 1.msat
        val invoice = makeInvoice(recipient = bob, amount = invoiceAmount, supportsTrampoline = true)
        val outgoingPaymentHandler = OutgoingPaymentHandler(alice.staticParams.nodeParams)

        val sendPayment = SendPayment(UUID.randomUUID(), invoice, invoiceAmount)
        val result = outgoingPaymentHandler.sendPayment(sendPayment, channels, currentBlockHeight)
        assertEquals(
            result, OutgoingPaymentHandler.SendPaymentResult.Failure(
                payment = sendPayment,
                failure = OutgoingPaymentFailure.make(
                    reason = OutgoingPaymentFailure.Reason.INSUFFICIENT_BALANCE_BASE,
                    problems = listOf()
                )
            )
        )
    }

    @Test
    fun `insufficient capacity - fees`() {

        val (alice, bob) = TestsHelper.reachNormal()

        val currentBlockHeight = alice.currentBlockHeight
        val channels = mapOf(alice.channelId to alice)

        val invoiceAmount = alice.commitments.availableBalanceForSend()
        val invoice = makeInvoice(recipient = bob, amount = invoiceAmount, supportsTrampoline = true)
        val paymentId = UUID.randomUUID()
        val sendPayment = SendPayment(paymentId, invoice, invoiceAmount)

        val outgoingPaymentHandler = OutgoingPaymentHandler(alice.staticParams.nodeParams)

        run {
            val result = outgoingPaymentHandler.sendPayment(sendPayment, channels, currentBlockHeight)
            assertTrue { result is OutgoingPaymentHandler.SendPaymentResult.Progress }
        }
        run {
            val lastPart = outgoingPaymentHandler.pendingPaymentAttempt(paymentId)!!.parts[0]

            val reason = FailurePacket.create(lastPart.secrets[0].first, TrampolineFeeInsufficient)
            val updateFailHtlc = UpdateFailHtlc(alice.channelId, 0, reason.toByteVector())
            val processFail = ProcessFail(fail = updateFailHtlc, paymentId = sendPayment.paymentId)

            val result = outgoingPaymentHandler.processRemoteFailure(processFail, channels, currentBlockHeight)
            assertEquals(
                result, OutgoingPaymentHandler.ProcessFailureResult.Failure(
                    payment = sendPayment,
                    failure = OutgoingPaymentFailure.make(
                        reason = OutgoingPaymentFailure.Reason.INSUFFICIENT_BALANCE_FEES,
                        problems = listOf()
                    )
                )
            )
        }
    }

    @Test
    fun `channel cap restrictions - htlcMinimumMsat`() {

        val htlcMininumMsat = 100_000.msat
        val paymentAmount = 50_000.msat // less than htlcMinimumMsat

        var (alice, bob) = TestsHelper.reachNormal()
        alice = alice.copy(channelUpdate = alice.channelUpdate.copy(htlcMinimumMsat = htlcMininumMsat))

        val currentBlockHeight = alice.currentBlockHeight
        val channels = mapOf(alice.channelId to alice)

        val invoice = makeInvoice(recipient = bob, amount = null, supportsTrampoline = true)
        val sendPayment = SendPayment(UUID.randomUUID(), invoice, paymentAmount)

        val outgoingPaymentHandler = OutgoingPaymentHandler(alice.staticParams.nodeParams)

        val result = outgoingPaymentHandler.sendPayment(sendPayment, channels, currentBlockHeight)
        assertEquals(
            result, OutgoingPaymentHandler.SendPaymentResult.Failure(
                payment = sendPayment,
                failure = OutgoingPaymentFailure.make(
                    reason = OutgoingPaymentFailure.Reason.OTHER_ERROR,
                    problems = listOf(
                        Either.Left(
                            HtlcValueTooSmall(
                                channelId = alice.channelId,
                                minimum = htlcMininumMsat,
                                actual = paymentAmount
                            )
                        )
                    )
                )
            )
        )
    }

    @Test
    fun `channel cap restrictions - htlcMaximumMsat`() {

        val htlcMaximumMsat = 100_000_000.msat
        val paymentAmount = 200_000_000.msat // more than htlcMaximumMsat

        var (alice, bob) = TestsHelper.reachNormal()
        alice = alice.copy(channelUpdate = alice.channelUpdate.copy(htlcMaximumMsat = htlcMaximumMsat))

        val currentBlockHeight = alice.currentBlockHeight
        val channels = mapOf(alice.channelId to alice)

        val invoice = makeInvoice(recipient = bob, amount = null, supportsTrampoline = true)
        val sendPayment = SendPayment(UUID.randomUUID(), invoice, paymentAmount)

        val outgoingPaymentHandler = OutgoingPaymentHandler(alice.staticParams.nodeParams)

        val result = outgoingPaymentHandler.sendPayment(sendPayment, channels, currentBlockHeight)
        assertEquals(
            result, OutgoingPaymentHandler.SendPaymentResult.Failure(
                payment = sendPayment,
                failure = OutgoingPaymentFailure.make(
                    reason = OutgoingPaymentFailure.Reason.OTHER_ERROR,
                    problems = listOf(
                        Either.Left(
                            HtlcValueTooBig(
                                channelId = alice.channelId,
                                maximum = htlcMaximumMsat,
                                actual = paymentAmount
                            )
                        )
                    )
                )
            )
        )
    }

    @Test
    fun `channel cap restrictions - maxAcceptedHtlcs`() {

        var (alice, bob) = TestsHelper.reachNormal()

        val maxAcceptedHtlcs = 1
        val remoteParams = alice.commitments.remoteParams.copy(maxAcceptedHtlcs = maxAcceptedHtlcs)
        val commitments = alice.commitments.copy(remoteParams = remoteParams)
        alice = alice.copy(commitments = commitments)

        val currentBlockHeight = alice.currentBlockHeight

        val outgoingPaymentHandler = OutgoingPaymentHandler(alice.staticParams.nodeParams)

        run {

            // Send payment 1 of 2.
            // This should go thru fine because we're still under the maxAccpetedHtlcs

            val invoice = makeInvoice(recipient = bob, amount = null, supportsTrampoline = true)
            val sendPayment = SendPayment(UUID.randomUUID(), invoice, 100_000.msat)

            val channels = mapOf(alice.channelId to alice)
            val result = outgoingPaymentHandler.sendPayment(sendPayment, channels, currentBlockHeight)

            assertTrue { result is OutgoingPaymentHandler.SendPaymentResult.Progress }
            val progress = result as OutgoingPaymentHandler.SendPaymentResult.Progress

            // Push the CMD_ADD_HTLC thru the channel so it can update its state.

            val channelEvents = progress.actions.filterIsInstance<WrappedChannelEvent>()
            channelEvents.mapNotNull { it.channelEvent as? ExecuteCommand }.forEach { executeCommand ->
                val processResult = alice.process(executeCommand)

                assertTrue { processResult.first is Normal }
                alice = processResult.first as Normal
            }
        }
        run {

            // Send payment 2 of 2.
            // This should exceed the configured maxAcceptedHtlcs.

            val invoice = makeInvoice(recipient = bob, amount = null, supportsTrampoline = true)
            val sendPayment = SendPayment(UUID.randomUUID(), invoice, 100_000.msat)

            var channels = mapOf(alice.channelId to alice)
            val result1 = outgoingPaymentHandler.sendPayment(sendPayment, channels, currentBlockHeight)

            // At first, the result will be progress
            assertTrue { result1 is OutgoingPaymentHandler.SendPaymentResult.Progress }
            val progress = result1 as OutgoingPaymentHandler.SendPaymentResult.Progress

            // We push the CMD_ADD_HTLC thru the channel.
            // We're expecting the channel to return an error.

            val executeCommand = progress.actions.filterIsInstance<WrappedChannelEvent>().mapNotNull {
                it.channelEvent as? ExecuteCommand
            }.firstOrNull()

            assertNotNull(executeCommand)
            val processResult = alice.process(executeCommand)

            assertTrue { processResult.first is Normal }
            alice = processResult.first as Normal

            val localFailure = processResult.second.filterIsInstance<ProcessLocalFailure>().firstOrNull()
            assertNotNull(localFailure)

            val channelError = WrappedChannelError(alice.channelId, localFailure.error, executeCommand)
            assertNotNull(channelError)

            // Now the channelError gets sent back to the OutgoingPaymentHandler.

            channels = mapOf(alice.channelId to alice)
            val result2 = outgoingPaymentHandler.processLocalFailure(channelError, channels, currentBlockHeight)
            assertEquals(
                result2, OutgoingPaymentHandler.ProcessFailureResult.Failure(
                    payment = sendPayment,
                    failure = OutgoingPaymentFailure.make(
                        reason = OutgoingPaymentFailure.Reason.NO_AVAILABLE_CHANNELS,
                        problems = listOf(
                            Either.Left(
                                TooManyAcceptedHtlcs(alice.channelId, maxAcceptedHtlcs.toLong())
                            )
                        )
                    )
                )
            )
        }
    }

    @Test
    fun `channel cap restrictions - maxHtlcValueInFlight`() {

        var (alice, bob) = TestsHelper.reachNormal()

        val maxHtlcValueInFlightMsat = 10_000L
        val remoteParams = alice.commitments.remoteParams.copy(maxHtlcValueInFlightMsat = maxHtlcValueInFlightMsat)
        val commitments = alice.commitments.copy(remoteParams = remoteParams)
        alice = alice.copy(commitments = commitments)

        val currentBlockHeight = alice.currentBlockHeight

        val outgoingPaymentHandler = OutgoingPaymentHandler(alice.staticParams.nodeParams)

        run {

            // Send payment 1 of 2.
            // This should go thru fine because we're still under the maxHtlcValueInFlightMsat

            val invoice = makeInvoice(recipient = bob, amount = null, supportsTrampoline = true)
            val sendPayment = SendPayment(UUID.randomUUID(), invoice, 100_000.msat)

            val channels = mapOf(alice.channelId to alice)
            val result = outgoingPaymentHandler.sendPayment(sendPayment, channels, currentBlockHeight)
            assertTrue { result is OutgoingPaymentHandler.SendPaymentResult.Progress }
            val progress = result as OutgoingPaymentHandler.SendPaymentResult.Progress

            // Push the CMD_ADD_HTLC thru the channel so it can update its state.

            val channelEvents = progress.actions.filterIsInstance<WrappedChannelEvent>()
            channelEvents.mapNotNull { it.channelEvent as? ExecuteCommand }.forEach { executeCommand ->
                val processResult = alice.process(executeCommand)

                assertTrue { processResult.first is Normal }
                alice = processResult.first as Normal
            }
        }
        run {

            // Send payment 2 of 2.
            // This should exceed the configured maxHtlcValueInFlightMsat.

            val invoice = makeInvoice(recipient = bob, amount = null, supportsTrampoline = true)
            val sendPayment = SendPayment(UUID.randomUUID(), invoice, 100_000.msat)

            var channels = mapOf(alice.channelId to alice)
            val result1 = outgoingPaymentHandler.sendPayment(sendPayment, channels, currentBlockHeight)

            // At first, the result will be progress
            assertTrue { result1 is OutgoingPaymentHandler.SendPaymentResult.Progress }
            val progress = result1 as OutgoingPaymentHandler.SendPaymentResult.Progress

            // We push the CMD_ADD_HTLC thru the channel.
            // We're expecting the channel to return an error.

            val executeCommand = progress.actions.filterIsInstance<WrappedChannelEvent>().mapNotNull {
                it.channelEvent as? ExecuteCommand
            }.firstOrNull()

            assertNotNull(executeCommand)
            val processResult = alice.process(executeCommand)

            assertTrue { processResult.first is Normal }
            alice = processResult.first as Normal

            val localFailure = processResult.second.filterIsInstance<ProcessLocalFailure>().firstOrNull()
            assertNotNull(localFailure)

            val channelError = WrappedChannelError(alice.channelId, localFailure.error, executeCommand)
            assertNotNull(channelError)

            // Now the channelError gets sent back to the OutgoingPaymentHandler.

            channels = mapOf(alice.channelId to alice)
            val result2 = outgoingPaymentHandler.processLocalFailure(channelError, channels, currentBlockHeight)
            assertEquals(
                result2, OutgoingPaymentHandler.ProcessFailureResult.Failure(
                    payment = sendPayment,
                    failure = OutgoingPaymentFailure.make(
                        reason = OutgoingPaymentFailure.Reason.NO_AVAILABLE_CHANNELS,
                        problems = listOf(
                            Either.Left(
                                HtlcValueTooHighInFlight(
                                    channelId = alice.channelId,
                                    maximum = maxHtlcValueInFlightMsat.toULong(),
                                    actual = 100_000.msat
                                )
                            )
                        )
                    )
                )
            )
        }
    }

    @Test
    fun `increase trampoline fees according to schedule`() {

        val (alice, bob) = TestsHelper.reachNormal()
        val currentBlockHeight = alice.currentBlockHeight
        val channels = mapOf(alice.channelId to alice)

        val invoiceAmount = 100_000.msat
        val invoice = makeInvoice(recipient = bob, amount = invoiceAmount, supportsTrampoline = true)
        val paymentId = UUID.randomUUID()
        val sendPayment = SendPayment(paymentId, invoice, invoiceAmount)

        val outgoingPaymentHandler = OutgoingPaymentHandler(alice.staticParams.nodeParams)
        // these fees were calculated based on the default trampoline parameters for an amount of 100 sat
        val expectedFees = listOf(
            0.msat,
            1010.msat,
            3010.msat,
            5050.msat,
            5100.msat,
            5120.msat,
        )

        for (idx in OutgoingPaymentHandler.TrampolineParams.attempts.indices) {
            if (idx == 0) {
                val result = outgoingPaymentHandler.sendPayment(sendPayment, channels, currentBlockHeight)

                assertTrue { result is OutgoingPaymentHandler.SendPaymentResult.Progress }
                val progress = result as OutgoingPaymentHandler.SendPaymentResult.Progress
                assertEquals(progress.trampolineFees, expectedFees[idx])

            } else {
                val previousPart = outgoingPaymentHandler.pendingPaymentAttempt(paymentId)!!.parts[0]

                val reason = FailurePacket.create(previousPart.secrets[0].first, TrampolineFeeInsufficient)
                val updateFailHtlc = UpdateFailHtlc(alice.channelId, 0, reason.toByteVector())
                val processFail = ProcessFail(fail = updateFailHtlc, paymentId = sendPayment.paymentId)

                val result = outgoingPaymentHandler.processRemoteFailure(processFail, channels, currentBlockHeight)

                assertTrue { result is OutgoingPaymentHandler.ProcessFailureResult.Progress }
                val progress = result as OutgoingPaymentHandler.ProcessFailureResult.Progress
                assertEquals(progress.trampolineFees, expectedFees[idx])
            }
        }

        run {
            val lastPart = outgoingPaymentHandler.pendingPaymentAttempt(paymentId)!!.parts[0]

            val reason = FailurePacket.create(lastPart.secrets[0].first, TrampolineFeeInsufficient)
            val updateFailHtlc = UpdateFailHtlc(alice.channelId, 0, reason.toByteVector())
            val processFail = ProcessFail(fail = updateFailHtlc, paymentId = sendPayment.paymentId)

            val result = outgoingPaymentHandler.processRemoteFailure(processFail, channels, currentBlockHeight)
            assertEquals(
                result, OutgoingPaymentHandler.ProcessFailureResult.Failure(
                    payment = sendPayment,
                    failure = OutgoingPaymentFailure.make(
                        reason = OutgoingPaymentFailure.Reason.NO_ROUTE_TO_RECIPIENT,
                        problems = listOf(Either.Right(TrampolineFeeInsufficient))
                    )
                )
            )
        }
    }

    @Test
    fun `successful trampoline response`() {

        val (alice, bob) = TestsHelper.reachNormal()
        val currentBlockHeight = alice.currentBlockHeight
        val channels = mapOf(alice.channelId to alice)

        val invoiceAmount = 100_000.msat
        val invoice = makeInvoice(recipient = bob, amount = invoiceAmount, supportsTrampoline = true)
        val sendPayment = SendPayment(UUID.randomUUID(), invoice, invoiceAmount)

        val outgoingPaymentHandler = OutgoingPaymentHandler(alice.staticParams.nodeParams)

        run {
            val result = outgoingPaymentHandler.sendPayment(sendPayment, channels, currentBlockHeight)
            assertTrue { result is OutgoingPaymentHandler.SendPaymentResult.Progress }
        }

        val updateFulfillHtlc = UpdateFulfillHtlc(alice.channelId, 0, Eclair.randomBytes32())
        val processFulfill = ProcessFulfill(fulfill = updateFulfillHtlc, paymentId = sendPayment.paymentId)

        run {
            val result = outgoingPaymentHandler.processFulfill(processFulfill)
            assertTrue { result is OutgoingPaymentHandler.ProcessFulfillResult.Success }
        }
    }

    @Test
    fun `createHtlc - full trampoline`() {

        // full trampoline route to c:
        //        .--.
        //       /    \
        // a -> b      c

        val (channel, _) = TestsHelper.reachNormal()
        val currentBlockHeight = channel.currentBlockHeight
        val channels = mapOf(channel.channelId to channel)

        val privKeyB = TestConstants.Bob.nodeParams.nodePrivateKey
        val privKeyC = Eclair.randomKey()
        val pubKeyC = privKeyC.publicKey()

        val availableForSend = channel.commitments.availableBalanceForSend()
        val targetAmount = availableForSend / 2

        val invoice = makeInvoice(recipient = privKeyC, amount = targetAmount, supportsTrampoline = true)
        val sendPayment = SendPayment(UUID.randomUUID(), invoice, targetAmount)

        val trampolineParams = OutgoingPaymentHandler.TrampolineParams.get(0)!!

        val expectedAmountAtB = targetAmount
        val expectedAmountAtC = targetAmount

        val expectedExpiryAtC = invoice.minFinalExpiryDelta!!.toCltvExpiry(currentBlockHeight.toLong())
        val expectedExpiryAtB = expectedExpiryAtC + trampolineParams.cltvExpiryDelta

        val outgoingPaymentHandler = OutgoingPaymentHandler(channel.staticParams.nodeParams)
        val result = outgoingPaymentHandler.sendPayment(sendPayment, channels, currentBlockHeight)

        assertTrue { result is OutgoingPaymentHandler.SendPaymentResult.Progress }
        val progress = result as OutgoingPaymentHandler.SendPaymentResult.Progress

        val wrappedChannelEvent = progress.actions.filterIsInstance<WrappedChannelEvent>().firstOrNull()
        assertNotNull(wrappedChannelEvent)

        val executeCommand = wrappedChannelEvent.channelEvent as? ExecuteCommand
        val cmdAddHtlc = executeCommand?.command as? CMD_ADD_HTLC
        assertNotNull(cmdAddHtlc)

        assertTrue { cmdAddHtlc.amount == expectedAmountAtB }
        assertTrue { cmdAddHtlc.cltvExpiry == expectedExpiryAtB }

        // When nodeB receives the packet, it will be decrypted, and we expect to find:
        // - isLastPacket == true (last on channel-hop sequence)
        // - contains an inner trampoline onion
        // - trampoline packet requests a trampoline-forward to nodeC

        val add = UpdateAddHtlc(
            channel.channelId,
            0,
            cmdAddHtlc.amount,
            cmdAddHtlc.paymentHash,
            cmdAddHtlc.cltvExpiry,
            cmdAddHtlc.onion
        )
        val (outerB, innerB, packetC) = PaymentPacketTestsCommon.decryptNodeRelay(add, privKeyB)
        assertEquals(expectedAmountAtB, outerB.amount)
        assertEquals(expectedAmountAtB, outerB.totalAmount)
        assertEquals(expectedExpiryAtB, outerB.expiry)
        assertEquals(expectedAmountAtC, innerB.amountToForward)
        assertEquals(expectedExpiryAtC, innerB.outgoingCltv)
        assertEquals(pubKeyC, innerB.outgoingNodeId)
        assertNull(innerB.invoiceRoutingInfo)
        assertNull(innerB.invoiceFeatures)
        assertNull(innerB.paymentSecret)

        // NodeC should be able to decrypt the trampoline packet.
        val payloadBytesC =
            Sphinx.peel(privKeyC, invoice.paymentHash, packetC, OnionRoutingPacket.TrampolinePacketLength).right!!
        val payloadC = FinalPayload.read(payloadBytesC.payload.toByteArray())
        assertEquals(payloadC.amount, expectedAmountAtC)
        assertEquals(payloadC.expiry, expectedExpiryAtC)
        assertEquals(payloadC.totalAmount, payloadC.amount)
        assertEquals(payloadC.paymentSecret, invoice.paymentSecret)
    }


    @Test
    fun `createHtlc - legacy trampoline`() {

        // simple trampoline route to c, where c doesn't support trampoline:
        //        .xx.
        //       /    \
        // a -> b ->-> c

        val (channel, _) = TestsHelper.reachNormal()
        val currentBlockHeight = channel.currentBlockHeight
        val channels = mapOf(channel.channelId to channel)

        val privKeyB = TestConstants.Bob.nodeParams.nodePrivateKey
        val privKeyC = Eclair.randomKey()
        val pubKeyC = privKeyC.publicKey()

        val availableForSend = channel.commitments.availableBalanceForSend()
        val targetAmount = availableForSend / 2

        val invoice = makeInvoice(recipient = privKeyC, amount = targetAmount, supportsTrampoline = false)
        val sendPayment = SendPayment(UUID.randomUUID(), invoice, targetAmount)

        val trampolineParams = OutgoingPaymentHandler.TrampolineParams.get(0)!!

        val expectedAmountAtB = targetAmount
        val expectedAmountAtC = targetAmount

        val expectedExpiryAtC = invoice.minFinalExpiryDelta!!.toCltvExpiry(currentBlockHeight.toLong())
        val expectedExpiryAtB = expectedExpiryAtC + trampolineParams.cltvExpiryDelta

        val outgoingPaymentHandler = OutgoingPaymentHandler(channel.staticParams.nodeParams)
        val result = outgoingPaymentHandler.sendPayment(sendPayment, channels, currentBlockHeight)

        assertTrue { result is OutgoingPaymentHandler.SendPaymentResult.Progress }
        val progress = result as OutgoingPaymentHandler.SendPaymentResult.Progress

        val wrappedChannelEvent = progress.actions.filterIsInstance<WrappedChannelEvent>().firstOrNull()
        assertNotNull(wrappedChannelEvent)

        val executeCommand = wrappedChannelEvent.channelEvent as? ExecuteCommand
        val cmdAddHtlc = executeCommand?.command as? CMD_ADD_HTLC
        assertNotNull(cmdAddHtlc)

        assertTrue { cmdAddHtlc.amount == expectedAmountAtB }
        assertTrue { cmdAddHtlc.cltvExpiry == expectedExpiryAtB }

        // When nodeB receives the packet, it will be decrypted, and we expect to find:
        // - isLastPacket == true (last on channel-hop sequence)
        // - contains in inner trampoline onion
        // - trampoline packet requests a legacy (non-trampoline) forward to nodeC

        val add = UpdateAddHtlc(
            channel.channelId,
            0,
            cmdAddHtlc.amount,
            cmdAddHtlc.paymentHash,
            cmdAddHtlc.cltvExpiry,
            cmdAddHtlc.onion
        )
        val (outerB, innerB, _) = PaymentPacketTestsCommon.decryptNodeRelay(add, privKeyB)
        assertEquals(expectedAmountAtB, outerB.amount)
        assertEquals(expectedAmountAtB, outerB.totalAmount)
        assertEquals(expectedExpiryAtB, outerB.expiry)
        assertNotEquals(invoice.paymentSecret, outerB.paymentSecret)
        assertEquals(expectedAmountAtC, innerB.amountToForward)
        assertEquals(expectedAmountAtC, innerB.totalAmount)
        assertEquals(expectedExpiryAtC, innerB.outgoingCltv)
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
