package fr.acinq.eclair.payment

import fr.acinq.bitcoin.*
import fr.acinq.eclair.*
import fr.acinq.eclair.Eclair.randomBytes32
import fr.acinq.eclair.channel.*
import fr.acinq.eclair.crypto.sphinx.Sphinx
import fr.acinq.eclair.db.IncomingPayment
import fr.acinq.eclair.io.PayToOpenResponseEvent
import fr.acinq.eclair.io.WrappedChannelEvent
import fr.acinq.eclair.router.ChannelHop
import fr.acinq.eclair.router.NodeHop
import fr.acinq.eclair.tests.utils.EclairTestSuite
import fr.acinq.eclair.utils.*
import fr.acinq.eclair.wire.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IncomingPaymentHandlerTestsCommon : EclairTestSuite() {

    @Test
    fun `add HTLC to channel commitments`() {
        var (alice, bob) = TestsHelper.reachNormal()
        val cmdAddHtlc = makeCmdAddHtlc(
            bob.staticParams.nodeParams.nodeId, defaultPaymentHash,
            makeMppPayload(100_000.msat, 150_000.msat, randomBytes32(), currentBlockHeight = alice.currentBlockHeight)
        )

        var processResult: Pair<ChannelState, List<ChannelAction>>
        var actions: List<ChannelAction>

        // Step 1: alice ---> update_add_htlc ---> bob

        processResult = alice.process(ChannelEvent.ExecuteCommand(cmdAddHtlc))
        alice = processResult.first as Normal
        actions = processResult.second
        assertEquals(2, actions.size)
        val add = actions.findOutgoingMessage<UpdateAddHtlc>()
        val aliceCmdSign = actions.findCommand<CMD_SIGN>()

        processResult = bob.process(ChannelEvent.MessageReceived(add))
        bob = processResult.first as Normal
        actions = processResult.second
        assertTrue { actions.filterIsInstance<ChannelAction.Message.Send>().isEmpty() }

        assertTrue { alice.commitments.localChanges.proposed.size == 1 }
        assertTrue { alice.commitments.localChanges.signed.isEmpty() }
        assertTrue { alice.commitments.localChanges.acked.isEmpty() }

        assertTrue { bob.commitments.remoteChanges.proposed.size == 1 }
        assertTrue { bob.commitments.remoteChanges.acked.isEmpty() }
        assertTrue { bob.commitments.remoteChanges.signed.isEmpty() }

        // Step 2: alice ---> commitment_signed ---> bob

        processResult = alice.process(ChannelEvent.ExecuteCommand(aliceCmdSign))
        alice = processResult.first as Normal
        actions = processResult.second
        val aliceSig = actions.findOutgoingMessage<CommitSig>()

        processResult = bob.process(ChannelEvent.MessageReceived(aliceSig))
        bob = processResult.first as Normal
        actions = processResult.second
        val bobRev = actions.findOutgoingMessage<RevokeAndAck>()
        val bobCmdSign = actions.findCommand<CMD_SIGN>()

        assertTrue { alice.commitments.localChanges.proposed.isEmpty() }
        assertTrue { alice.commitments.localChanges.signed.size == 1 }
        assertTrue { alice.commitments.localChanges.acked.isEmpty() }

        assertTrue { bob.commitments.remoteChanges.proposed.isEmpty() }
        assertTrue { bob.commitments.remoteChanges.acked.size == 1 }
        assertTrue { bob.commitments.remoteChanges.signed.isEmpty() }

        // Step 3: alice <--- revoke_and_ack <--- bob

        processResult = alice.process(ChannelEvent.MessageReceived(bobRev))
        alice = processResult.first as Normal
        actions = processResult.second
        assertTrue { actions.filterIsInstance<ChannelAction.Message.Send>().isEmpty() }

        assertTrue { alice.commitments.localChanges.proposed.isEmpty() }
        assertTrue { alice.commitments.localChanges.signed.isEmpty() }
        assertTrue { alice.commitments.localChanges.acked.size == 1 }

        assertTrue { bob.commitments.remoteChanges.proposed.isEmpty() }
        assertTrue { bob.commitments.remoteChanges.acked.size == 1 }
        assertTrue { bob.commitments.remoteChanges.signed.isEmpty() }

        // Step 4: alice <--- commitment_signed <--- bob

        processResult = bob.process(ChannelEvent.ExecuteCommand(bobCmdSign))
        bob = processResult.first as Normal
        actions = processResult.second
        val bobSig = actions.findOutgoingMessage<CommitSig>()

        processResult = alice.process(ChannelEvent.MessageReceived(bobSig))
        alice = processResult.first as Normal
        actions = processResult.second
        val aliceRev = actions.findOutgoingMessage<RevokeAndAck>()

        assertTrue { alice.commitments.localChanges.proposed.isEmpty() }
        assertTrue { alice.commitments.localChanges.signed.isEmpty() }
        assertTrue { alice.commitments.localChanges.acked.isEmpty() }

        assertTrue { bob.commitments.remoteChanges.proposed.isEmpty() }
        assertTrue { bob.commitments.remoteChanges.acked.isEmpty() }
        assertTrue { bob.commitments.remoteChanges.signed.size == 1 }

        // Step 5: alice ---> revoke_and_ack ---> bob

        processResult = bob.process(ChannelEvent.MessageReceived(aliceRev))
        bob = processResult.first as Normal
        actions = processResult.second
        assertTrue { actions.filterIsInstance<ChannelAction.Message.Send>().isEmpty() }
        assertTrue { actions.filterIsInstance<ChannelAction.ProcessIncomingHtlc>().size == 1 }

        assertTrue { alice.commitments.localChanges.proposed.isEmpty() }
        assertTrue { alice.commitments.localChanges.signed.isEmpty() }
        assertTrue { alice.commitments.localChanges.acked.isEmpty() }

        assertTrue { bob.commitments.remoteChanges.proposed.isEmpty() }
        assertTrue { bob.commitments.remoteChanges.acked.isEmpty() }
        assertTrue { bob.commitments.remoteChanges.signed.isEmpty() }
    }

    @Test
    fun `unsupported legacy onion (payment secret missing)`() {
        val paymentHandler = IncomingPaymentHandler(TestConstants.Bob.nodeParams)
        val (incomingPayment, _) = makeIncomingPayment(paymentHandler, defaultAmount)
        val add = makeUpdateAddHtlc(0, randomBytes32(), paymentHandler, incomingPayment.paymentRequest.paymentHash, makeLegacyPayload(defaultAmount))
        val result = paymentHandler.process(add, TestConstants.defaultBlockHeight)

        assertTrue { result.status == IncomingPaymentHandler.Status.REJECTED }
        val expected = ChannelEvent.ExecuteCommand(CMD_FAIL_HTLC(add.id, CMD_FAIL_HTLC.Reason.Failure(IncorrectOrUnknownPaymentDetails(defaultAmount, TestConstants.defaultBlockHeight.toLong())), commit = true))
        assertEquals(setOf(WrappedChannelEvent(add.channelId, expected)), result.actions.toSet())
    }

    @Test
    fun `receive multipart payment with single HTLC`() {
        val paymentHandler = IncomingPaymentHandler(TestConstants.Bob.nodeParams)
        val (incomingPayment, paymentSecret) = makeIncomingPayment(paymentHandler, defaultAmount)
        val add = makeUpdateAddHtlc(0, randomBytes32(), paymentHandler, incomingPayment.paymentRequest.paymentHash, makeMppPayload(defaultAmount, defaultAmount, paymentSecret))
        val result = paymentHandler.process(add, TestConstants.defaultBlockHeight)

        assertTrue { result.status == IncomingPaymentHandler.Status.ACCEPTED }
        val expected = ChannelEvent.ExecuteCommand(CMD_FULFILL_HTLC(add.id, incomingPayment.paymentPreimage, commit = true))
        assertEquals(setOf(WrappedChannelEvent(add.channelId, expected)), result.actions.toSet())
    }

    @Test
    fun `receive pay-to-open payment with single HTLC`() {
        val paymentHandler = IncomingPaymentHandler(TestConstants.Bob.nodeParams)
        val (incomingPayment, paymentSecret) = makeIncomingPayment(paymentHandler, defaultAmount)
        val payToOpenRequest = makePayToOpenRequest(incomingPayment, makeMppPayload(defaultAmount, defaultAmount, paymentSecret))
        val result = paymentHandler.process(payToOpenRequest, TestConstants.defaultBlockHeight)

        assertTrue { result.status == IncomingPaymentHandler.Status.ACCEPTED }
        val expected = PayToOpenResponseEvent(PayToOpenResponse(payToOpenRequest.chainHash, payToOpenRequest.paymentHash, PayToOpenResponse.Result.Success(incomingPayment.paymentPreimage)))
        assertEquals(setOf(expected), result.actions.toSet())
    }

    @Test
    fun `receive pay-to-open payment with an unknown payment hash`() {
        val paymentHandler = IncomingPaymentHandler(TestConstants.Bob.nodeParams)
        val payToOpenRequest = PayToOpenRequest(
            chainHash = ByteVector32.Zeroes,
            fundingSatoshis = 100_000.sat,
            amountMsat = defaultAmount,
            feeSatoshis = 100.sat,
            paymentHash = ByteVector32.One, // <-- not associated to a pending invoice
            feeThresholdSatoshis = 1_000.sat,
            feeProportionalMillionths = 100,
            expireAt = Long.MAX_VALUE,
            finalPacket = OutgoingPacket.buildPacket(
                paymentHash = ByteVector32.One, // <-- has to be the same as the one above otherwise encryption fails
                hops = channelHops(paymentHandler.nodeParams.nodeId),
                finalPayload = makeMppPayload(defaultAmount, defaultAmount, randomBytes32()),
                payloadLength = OnionRoutingPacket.PaymentPacketLength
            ).third.packet
        )
        val result = paymentHandler.process(payToOpenRequest, TestConstants.defaultBlockHeight)

        assertTrue { result.status == IncomingPaymentHandler.Status.REJECTED }
        val expected = PayToOpenResponseEvent(
            PayToOpenResponse(
                payToOpenRequest.chainHash,
                payToOpenRequest.paymentHash,
                PayToOpenResponse.Result.Failure(
                    OutgoingPacket.buildHtlcFailure(
                        paymentHandler.nodeParams.nodePrivateKey,
                        payToOpenRequest.paymentHash,
                        payToOpenRequest.finalPacket,
                        CMD_FAIL_HTLC.Reason.Failure(IncorrectOrUnknownPaymentDetails(payToOpenRequest.amountMsat, TestConstants.defaultBlockHeight.toLong()))
                    ).right!!
                )
            )
        )
        assertEquals(setOf(expected), result.actions.toSet())
    }

    @Test
    fun `receive pay-to-open payment with an incorrect payment secret`() {
        val paymentHandler = IncomingPaymentHandler(TestConstants.Bob.nodeParams)
        val (incomingPayment, paymentSecret) = makeIncomingPayment(paymentHandler, defaultAmount)
        val payToOpenRequest = makePayToOpenRequest(incomingPayment, makeMppPayload(defaultAmount, defaultAmount, paymentSecret.reversed())) // <--- wrong secret
        val result = paymentHandler.process(payToOpenRequest, TestConstants.defaultBlockHeight)

        assertTrue { result.status == IncomingPaymentHandler.Status.REJECTED }
        val expected = PayToOpenResponseEvent(
            PayToOpenResponse(
                payToOpenRequest.chainHash,
                payToOpenRequest.paymentHash,
                PayToOpenResponse.Result.Failure(
                    OutgoingPacket.buildHtlcFailure(
                        paymentHandler.nodeParams.nodePrivateKey,
                        payToOpenRequest.paymentHash,
                        payToOpenRequest.finalPacket,
                        CMD_FAIL_HTLC.Reason.Failure(IncorrectOrUnknownPaymentDetails(payToOpenRequest.amountMsat, TestConstants.defaultBlockHeight.toLong()))
                    ).right!!
                )
            )
        )
        assertEquals(setOf(expected), result.actions.toSet())
    }

    @Test
    fun `receive pay-to-open trampoline payment with an incorrect payment secret`() {
        val paymentHandler = IncomingPaymentHandler(TestConstants.Bob.nodeParams)
        val (incomingPayment, paymentSecret) = makeIncomingPayment(paymentHandler, defaultAmount)
        val trampolineHops = listOf(
            NodeHop(TestConstants.Alice.nodeParams.nodeId, TestConstants.Bob.nodeParams.nodeId, CltvExpiryDelta(144), 0.msat)
        )
        val payToOpenRequest = PayToOpenRequest(
            chainHash = ByteVector32.Zeroes,
            fundingSatoshis = 100_000.sat,
            amountMsat = defaultAmount,
            feeSatoshis = 100.sat,
            paymentHash = incomingPayment.paymentRequest.paymentHash,
            feeThresholdSatoshis = 1_000.sat,
            feeProportionalMillionths = 100,
            expireAt = Long.MAX_VALUE,
            finalPacket = OutgoingPacket.buildPacket(
                paymentHash = incomingPayment.paymentRequest.paymentHash,
                hops = trampolineHops,
                finalPayload = makeMppPayload(defaultAmount, defaultAmount, paymentSecret.reversed()), // <-- wrong secret
                payloadLength = OnionRoutingPacket.TrampolinePacketLength
            ).third.packet
        )
        val result = paymentHandler.process(payToOpenRequest, TestConstants.defaultBlockHeight)

        assertTrue { result.status == IncomingPaymentHandler.Status.REJECTED }
        val expected = PayToOpenResponseEvent(
            PayToOpenResponse(
                payToOpenRequest.chainHash,
                payToOpenRequest.paymentHash,
                PayToOpenResponse.Result.Failure(
                    OutgoingPacket.buildHtlcFailure(
                        paymentHandler.nodeParams.nodePrivateKey,
                        payToOpenRequest.paymentHash,
                        payToOpenRequest.finalPacket,
                        CMD_FAIL_HTLC.Reason.Failure(IncorrectOrUnknownPaymentDetails(payToOpenRequest.amountMsat, TestConstants.defaultBlockHeight.toLong()))
                    ).right!!
                )
            )
        )
        assertEquals(setOf(expected), result.actions.toSet())
    }

    @Test
    fun `receive multipart payment with multiple HTLCs via same channel`() {
        val channelId = randomBytes32()
        val (amount1, amount2) = Pair(100_000.msat, 50_000.msat)
        val totalAmount = amount1 + amount2
        val paymentHandler = IncomingPaymentHandler(TestConstants.Bob.nodeParams)
        val (incomingPayment, paymentSecret) = makeIncomingPayment(paymentHandler, totalAmount)

        // Step 1 of 2:
        // - Alice sends first multipart htlc to Bob
        // - Bob doesn't accept the MPP set yet
        run {
            val add = makeUpdateAddHtlc(0, channelId, paymentHandler, incomingPayment.paymentRequest.paymentHash, makeMppPayload(amount1, totalAmount, paymentSecret))
            val result = paymentHandler.process(add, TestConstants.defaultBlockHeight)
            assertTrue { result.status == IncomingPaymentHandler.Status.PENDING }
            assertTrue { result.actions.isEmpty() }
        }

        // Step 2 of 2:
        // - Alice sends second multipart htlc to Bob
        // - Bob now accepts the MPP set
        run {
            val add = makeUpdateAddHtlc(1, channelId, paymentHandler, incomingPayment.paymentRequest.paymentHash, makeMppPayload(amount2, totalAmount, paymentSecret))
            val result = paymentHandler.process(add, TestConstants.defaultBlockHeight)
            assertTrue { result.status == IncomingPaymentHandler.Status.ACCEPTED }
            val expected = setOf(
                WrappedChannelEvent(channelId, ChannelEvent.ExecuteCommand(CMD_FULFILL_HTLC(0, defaultPreimage, commit = true))),
                WrappedChannelEvent(channelId, ChannelEvent.ExecuteCommand(CMD_FULFILL_HTLC(1, defaultPreimage, commit = true))),
            )
            assertEquals(expected, result.actions.toSet())
        }
    }

    @Test
    fun `receive multipart payment with multiple HTLCs via different channels`() {
        val (channelId1, channelId2) = Pair(randomBytes32(), randomBytes32())
        val (amount1, amount2) = Pair(100_000.msat, 50_000.msat)
        val totalAmount = amount1 + amount2
        val paymentHandler = IncomingPaymentHandler(TestConstants.Bob.nodeParams)
        val (incomingPayment, paymentSecret) = makeIncomingPayment(paymentHandler, totalAmount)

        // Step 1 of 2:
        // - Alice sends first multipart htlc to Bob
        // - Bob doesn't accept the MPP set yet
        run {
            val add = makeUpdateAddHtlc(7, channelId1, paymentHandler, incomingPayment.paymentRequest.paymentHash, makeMppPayload(amount1, totalAmount, paymentSecret))
            val result = paymentHandler.process(add, TestConstants.defaultBlockHeight)
            assertTrue { result.status == IncomingPaymentHandler.Status.PENDING }
            assertTrue { result.actions.isEmpty() }
        }

        // Step 2 of 2:
        // - Alice sends second multipart htlc to Bob
        // - Bob now accepts the MPP set
        run {
            val add = makeUpdateAddHtlc(5, channelId2, paymentHandler, incomingPayment.paymentRequest.paymentHash, makeMppPayload(amount2, totalAmount, paymentSecret))
            val result = paymentHandler.process(add, TestConstants.defaultBlockHeight)
            assertTrue { result.status == IncomingPaymentHandler.Status.ACCEPTED }
            val expected = setOf(
                WrappedChannelEvent(channelId1, ChannelEvent.ExecuteCommand(CMD_FULFILL_HTLC(7, defaultPreimage, commit = true))),
                WrappedChannelEvent(channelId2, ChannelEvent.ExecuteCommand(CMD_FULFILL_HTLC(5, defaultPreimage, commit = true))),
            )
            assertEquals(expected, result.actions.toSet())
        }
    }

    @Test
    fun `receive multipart payment via pay-to-open`() {
        val (amount1, amount2) = Pair(100_000.msat, 50_000.msat)
        val totalAmount = amount1 + amount2
        val paymentHandler = IncomingPaymentHandler(TestConstants.Bob.nodeParams)
        val (incomingPayment, paymentSecret) = makeIncomingPayment(paymentHandler, totalAmount)

        // Step 1 of 2:
        // - Alice sends first multipart htlc to Bob
        // - Bob doesn't accept the MPP set yet
        run {
            val payToOpenRequest = makePayToOpenRequest(incomingPayment, makeMppPayload(amount1, totalAmount, paymentSecret))
            val result = paymentHandler.process(payToOpenRequest, TestConstants.defaultBlockHeight)
            assertTrue { result.status == IncomingPaymentHandler.Status.PENDING }
            assertTrue { result.actions.isEmpty() }
        }

        // Step 2 of 2:
        // - Alice sends second multipart htlc to Bob
        // - Bob now accepts the MPP set
        run {
            val payToOpenRequest = makePayToOpenRequest(incomingPayment, makeMppPayload(amount2, totalAmount, paymentSecret))
            val result = paymentHandler.process(payToOpenRequest, TestConstants.defaultBlockHeight)
            assertTrue { result.status == IncomingPaymentHandler.Status.ACCEPTED }
            val expected = PayToOpenResponseEvent(
                PayToOpenResponse(
                    payToOpenRequest.chainHash,
                    payToOpenRequest.paymentHash,
                    PayToOpenResponse.Result.Success(incomingPayment.paymentPreimage)
                )
            )
            assertEquals(setOf(expected), result.actions.toSet())
        }
    }

    @Test
    fun `receive multipart payment with a mix of HTLC and pay-to-open`() {
        val channelId = randomBytes32()
        val (amount1, amount2) = Pair(100_000.msat, 50_000.msat)
        val totalAmount = amount1 + amount2
        val paymentHandler = IncomingPaymentHandler(TestConstants.Bob.nodeParams)
        val (incomingPayment, paymentSecret) = makeIncomingPayment(paymentHandler, totalAmount)

        // Step 1 of 2:
        // - Alice sends first multipart htlc to Bob
        // - Bob doesn't accept the MPP set yet
        run {
            val add = makeUpdateAddHtlc(0, channelId, paymentHandler, incomingPayment.paymentRequest.paymentHash, makeMppPayload(amount1, totalAmount, paymentSecret))
            val result = paymentHandler.process(add, TestConstants.defaultBlockHeight)
            assertTrue { result.status == IncomingPaymentHandler.Status.PENDING }
            assertTrue { result.actions.isEmpty() }
        }

        // Step 2 of 2:
        // - Alice sends second multipart htlc to Bob
        // - Bob now accepts the MPP set
        run {
            val payToOpenRequest = makePayToOpenRequest(incomingPayment, makeMppPayload(amount2, totalAmount, paymentSecret))
            val result = paymentHandler.process(payToOpenRequest, TestConstants.defaultBlockHeight)
            assertTrue { result.status == IncomingPaymentHandler.Status.ACCEPTED }
            val expected = setOf(
                WrappedChannelEvent(channelId, ChannelEvent.ExecuteCommand(CMD_FULFILL_HTLC(0, incomingPayment.paymentPreimage, commit = true))),
                PayToOpenResponseEvent(
                    PayToOpenResponse(payToOpenRequest.chainHash, payToOpenRequest.paymentHash, PayToOpenResponse.Result.Success(incomingPayment.paymentPreimage))
                ),
            )
            assertEquals(expected, result.actions.toSet())
        }
    }

    @Test
    fun `receive normal single HTLC, with amount-less invoice`() {
        val paymentHandler = IncomingPaymentHandler(TestConstants.Bob.nodeParams)
        val (incomingPayment, paymentSecret) = makeIncomingPayment(paymentHandler, amount = null)
        val add = makeUpdateAddHtlc(0, randomBytes32(), paymentHandler, incomingPayment.paymentRequest.paymentHash, makeMppPayload(defaultAmount, defaultAmount, paymentSecret))
        val result = paymentHandler.process(add, TestConstants.defaultBlockHeight)

        assertTrue { result.status == IncomingPaymentHandler.Status.ACCEPTED }
        val expected = WrappedChannelEvent(add.channelId, ChannelEvent.ExecuteCommand(CMD_FULFILL_HTLC(add.id, incomingPayment.paymentPreimage, commit = true)))
        assertEquals(setOf(expected), result.actions.toSet())
    }

    @Test
    fun `receive multipart payment, with amount-less invoice`() {
        val channelId = randomBytes32()
        val (amount1, amount2) = Pair(100_000.msat, 50_000.msat)
        val totalAmount = amount1 + amount2
        val paymentHandler = IncomingPaymentHandler(TestConstants.Bob.nodeParams)
        val (incomingPayment, paymentSecret) = makeIncomingPayment(paymentHandler, amount = null)

        // Step 1 of 2:
        // - Alice sends first multipart htlc to Bob
        // - Bob doesn't accept the MPP set yet
        run {
            val add = makeUpdateAddHtlc(7, channelId, paymentHandler, incomingPayment.paymentRequest.paymentHash, makeMppPayload(amount1, totalAmount, paymentSecret))
            val result = paymentHandler.process(add, TestConstants.defaultBlockHeight)
            assertTrue { result.status == IncomingPaymentHandler.Status.PENDING }
            assertTrue { result.actions.isEmpty() }
        }

        // Step 2 of 2:
        // - Alice sends second multipart htlc to Bob
        // - Bob now accepts the MPP set
        run {
            val add = makeUpdateAddHtlc(11, channelId, paymentHandler, incomingPayment.paymentRequest.paymentHash, makeMppPayload(amount2, totalAmount, paymentSecret))
            val result = paymentHandler.process(add, TestConstants.defaultBlockHeight)
            assertTrue { result.status == IncomingPaymentHandler.Status.ACCEPTED }
            val expected = setOf(
                WrappedChannelEvent(channelId, ChannelEvent.ExecuteCommand(CMD_FULFILL_HTLC(7, incomingPayment.paymentPreimage, commit = true))),
                WrappedChannelEvent(channelId, ChannelEvent.ExecuteCommand(CMD_FULFILL_HTLC(11, incomingPayment.paymentPreimage, commit = true))),
            )
            assertEquals(expected, result.actions.toSet())
        }
    }

    @Test
    fun `receive multipart payment, with amount greater than total amount`() {
        val channelId = randomBytes32()
        val (amount1, amount2, amount3) = listOf(100_000.msat, 60_000.msat, 40_000.msat)
        val requestedAmount = 180_000.msat
        val totalAmount = amount1 + amount2 + amount3
        val paymentHandler = IncomingPaymentHandler(TestConstants.Bob.nodeParams)
        val (incomingPayment, paymentSecret) = makeIncomingPayment(paymentHandler, requestedAmount)

        // Step 1 of 2:
        // - Alice sends first 2 multipart htlcs to Bob.
        // - Bob doesn't accept the MPP set yet
        listOf(Pair(3L, amount1), Pair(5L, amount2)).forEach { (id, amount) ->
            val add = makeUpdateAddHtlc(id, channelId, paymentHandler, incomingPayment.paymentRequest.paymentHash, makeMppPayload(amount, totalAmount, paymentSecret))
            val result = paymentHandler.process(add, TestConstants.defaultBlockHeight)
            assertTrue { result.status == IncomingPaymentHandler.Status.PENDING }
            assertTrue { result.actions.isEmpty() }
        }

        // Step 2 of 2:
        // - Alice sends third multipart htlc to Bob
        // - Bob now accepts the MPP set
        run {
            val add = makeUpdateAddHtlc(6L, channelId, paymentHandler, incomingPayment.paymentRequest.paymentHash, makeMppPayload(amount3, totalAmount, incomingPayment.paymentRequest.paymentSecret!!))
            val result = paymentHandler.process(add, TestConstants.defaultBlockHeight)
            assertTrue { result.status == IncomingPaymentHandler.Status.ACCEPTED }
            val expected = setOf(
                WrappedChannelEvent(channelId, ChannelEvent.ExecuteCommand(CMD_FULFILL_HTLC(3, incomingPayment.paymentPreimage, commit = true))),
                WrappedChannelEvent(channelId, ChannelEvent.ExecuteCommand(CMD_FULFILL_HTLC(5, incomingPayment.paymentPreimage, commit = true))),
                WrappedChannelEvent(channelId, ChannelEvent.ExecuteCommand(CMD_FULFILL_HTLC(6, incomingPayment.paymentPreimage, commit = true)))
            )
            assertEquals(expected, result.actions.toSet())
        }
    }

    @Test
    fun `invoice expired`() {
        val paymentHandler = IncomingPaymentHandler(TestConstants.Bob.nodeParams)
        val (incomingPayment, paymentSecret) = makeIncomingPayment(
            payee = paymentHandler,
            amount = defaultAmount,
            timestamp = currentTimestampSeconds() - 3600 - 60, // over one hour ago
            expirySeconds = 3600 // one hour expiration
        )
        val add = makeUpdateAddHtlc(0, randomBytes32(), paymentHandler, incomingPayment.paymentRequest.paymentHash, makeMppPayload(10_000.msat, defaultAmount, paymentSecret))
        val result = paymentHandler.process(add, TestConstants.defaultBlockHeight)

        assertTrue { result.status == IncomingPaymentHandler.Status.REJECTED }
        val expected = ChannelEvent.ExecuteCommand(CMD_FAIL_HTLC(add.id, CMD_FAIL_HTLC.Reason.Failure(IncorrectOrUnknownPaymentDetails(defaultAmount, TestConstants.defaultBlockHeight.toLong())), commit = true))
        assertEquals(setOf(WrappedChannelEvent(add.channelId, expected)), result.actions.toSet())
    }

    @Test
    fun `invoice unknown`() {
        val paymentHandler = IncomingPaymentHandler(TestConstants.Bob.nodeParams)
        val add = makeUpdateAddHtlc(0, randomBytes32(), paymentHandler, randomBytes32(), makeMppPayload(defaultAmount, defaultAmount, randomBytes32()))
        val result = paymentHandler.process(add, TestConstants.defaultBlockHeight)

        assertTrue { result.status == IncomingPaymentHandler.Status.REJECTED }
        val expected = ChannelEvent.ExecuteCommand(CMD_FAIL_HTLC(add.id, CMD_FAIL_HTLC.Reason.Failure(IncorrectOrUnknownPaymentDetails(defaultAmount, TestConstants.defaultBlockHeight.toLong())), commit = true))
        assertEquals(setOf(WrappedChannelEvent(add.channelId, expected)), result.actions.toSet())
    }

    @Test
    fun `invalid onion`() {
        val paymentHandler = IncomingPaymentHandler(TestConstants.Bob.nodeParams)
        val (incomingPayment, _) = makeIncomingPayment(paymentHandler, defaultAmount)

        val cltvExpiry = CltvExpiryDelta(144).toCltvExpiry(TestConstants.defaultBlockHeight.toLong())
        val badOnion = OnionRoutingPacket(0, ByteVector("0x02eec7245d6b7d2ccb30380bfbe2a3648cd7a942653f5aa340edcea1f283686619"), Eclair.randomBytes(OnionRoutingPacket.PaymentPacketLength).toByteVector(), randomBytes32())
        val add = UpdateAddHtlc(randomBytes32(), 0, defaultAmount, incomingPayment.paymentRequest.paymentHash, cltvExpiry, badOnion)
        val result = paymentHandler.process(add, TestConstants.defaultBlockHeight)

        assertTrue { result.status == IncomingPaymentHandler.Status.REJECTED }
        // The current flow of error checking within the codebase would be:
        // 1. InvalidOnionKey
        // 2. InvalidOnionHmac
        // Since we used a valid pubKey, we should get an hmac failure.
        val expectedErr = InvalidOnionHmac(Sphinx.hash(badOnion))
        val expected = ChannelEvent.ExecuteCommand(CMD_FAIL_MALFORMED_HTLC(add.id, expectedErr.onionHash, expectedErr.code, commit = true))
        assertEquals(setOf(WrappedChannelEvent(add.channelId, expected)), result.actions.toSet())
    }

    @Test
    fun `invalid cltv expiry`() {
        val paymentHandler = IncomingPaymentHandler(TestConstants.Bob.nodeParams)
        val (incomingPayment, paymentSecret) = makeIncomingPayment(paymentHandler, defaultAmount)
        val lowExpiry = CltvExpiryDelta(2)
        val add = makeUpdateAddHtlc(0, randomBytes32(), paymentHandler, incomingPayment.paymentRequest.paymentHash, makeMppPayload(defaultAmount / 2, defaultAmount, paymentSecret, lowExpiry))
        val result = paymentHandler.process(add, TestConstants.defaultBlockHeight)

        assertTrue { result.status == IncomingPaymentHandler.Status.REJECTED }
        val expected = ChannelEvent.ExecuteCommand(CMD_FAIL_HTLC(add.id, CMD_FAIL_HTLC.Reason.Failure(IncorrectOrUnknownPaymentDetails(defaultAmount, TestConstants.defaultBlockHeight.toLong())), commit = true))
        assertEquals(setOf(WrappedChannelEvent(add.channelId, expected)), result.actions.toSet())
    }

    @Test
    fun `amount too low or too high`() {
        val paymentHandler = IncomingPaymentHandler(TestConstants.Bob.nodeParams)
        val requestedAmount = 30_000.msat
        val (incomingPayment, paymentSecret) = makeIncomingPayment(paymentHandler, requestedAmount)

        val payloads = listOf(
            makeMppPayload(requestedAmount / 3, requestedAmount / 3, paymentSecret), // too low
            makeMppPayload(requestedAmount * 3, requestedAmount * 3, paymentSecret) // too high
        )
        payloads.forEach { payload ->
            val add = makeUpdateAddHtlc(3, randomBytes32(), paymentHandler, incomingPayment.paymentRequest.paymentHash, payload)
            val result = paymentHandler.process(add, TestConstants.defaultBlockHeight)
            assertTrue { result.status == IncomingPaymentHandler.Status.REJECTED }
            val expected = ChannelEvent.ExecuteCommand(CMD_FAIL_HTLC(add.id, CMD_FAIL_HTLC.Reason.Failure(IncorrectOrUnknownPaymentDetails(payload.totalAmount, TestConstants.defaultBlockHeight.toLong())), commit = true))
            assertEquals(setOf(WrappedChannelEvent(add.channelId, expected)), result.actions.toSet())
        }
    }

    @Test
    fun `multipart total_amount mismatch`() {
        val channelId = randomBytes32()
        val (amount1, amount2, amount3) = listOf(25_000.msat, 40_000.msat, 30_000.msat)
        val totalAmount = amount1 + amount2 + amount3
        val paymentHandler = IncomingPaymentHandler(TestConstants.Bob.nodeParams)
        val (incomingPayment, paymentSecret) = makeIncomingPayment(paymentHandler, totalAmount)

        // Step 1 of 2:
        // - Alice sends first multipart htlc to Bob
        // - Bob doesn't accept the MPP set yet
        run {
            val add = makeUpdateAddHtlc(1, channelId, paymentHandler, incomingPayment.paymentRequest.paymentHash, makeMppPayload(amount1, totalAmount, paymentSecret))
            val result = paymentHandler.process(add, TestConstants.defaultBlockHeight)
            assertTrue { result.status == IncomingPaymentHandler.Status.PENDING }
            assertTrue { result.actions.isEmpty() }
        }

        // Step 2 of 2:
        // - Alice sends second multipart htlc to Bob.
        // - Bob detects some shenanigans
        // - Bob rejects the entire MPP set
        run {
            val payload = makeMppPayload(amount2, totalAmount + MilliSatoshi(1), paymentSecret)
            val add = makeUpdateAddHtlc(2, channelId, paymentHandler, incomingPayment.paymentRequest.paymentHash, payload)
            val result = paymentHandler.process(add, TestConstants.defaultBlockHeight)
            assertTrue { result.status == IncomingPaymentHandler.Status.REJECTED }
            val expected = setOf(
                WrappedChannelEvent(
                    channelId,
                    ChannelEvent.ExecuteCommand(CMD_FAIL_HTLC(1, CMD_FAIL_HTLC.Reason.Failure(IncorrectOrUnknownPaymentDetails(totalAmount, TestConstants.defaultBlockHeight.toLong())), commit = true))
                ),
                WrappedChannelEvent(
                    channelId,
                    ChannelEvent.ExecuteCommand(CMD_FAIL_HTLC(2, CMD_FAIL_HTLC.Reason.Failure(IncorrectOrUnknownPaymentDetails(totalAmount + 1.msat, TestConstants.defaultBlockHeight.toLong())), commit = true))
                ),
            )
            assertEquals(expected, result.actions.toSet())
        }
    }

    @Test
    fun `invalid payment secret`() {
        val (amount1, amount2) = listOf(50_000.msat, 45_000.msat)
        val totalAmount = amount1 + amount2
        val paymentHandler = IncomingPaymentHandler(TestConstants.Bob.nodeParams)
        val (incomingPayment, paymentSecret) = makeIncomingPayment(paymentHandler, totalAmount)

        // Step 1 of 2:
        // - Alice sends first multipart htlc to Bob
        // - Bob doesn't accept the MPP set yet
        run {
            val add = makeUpdateAddHtlc(1, randomBytes32(), paymentHandler, incomingPayment.paymentRequest.paymentHash, makeMppPayload(amount1, totalAmount, paymentSecret))
            val result = paymentHandler.process(add, TestConstants.defaultBlockHeight)
            assertTrue { result.status == IncomingPaymentHandler.Status.PENDING }
            assertTrue { result.actions.isEmpty() }
        }

        // Step 2 of 2:
        // - Someone sends an htlc with a different payment secret
        // - Bob rejects only that htlc, not Alice's valid one
        run {
            val payload = makeMppPayload(amount2, totalAmount, randomBytes32()) // <--- invalid payment secret
            val add = makeUpdateAddHtlc(1, randomBytes32(), paymentHandler, incomingPayment.paymentRequest.paymentHash, payload)
            val result = paymentHandler.process(add, TestConstants.defaultBlockHeight)
            assertTrue { result.status == IncomingPaymentHandler.Status.REJECTED }
            val expected = ChannelEvent.ExecuteCommand(CMD_FAIL_HTLC(add.id, CMD_FAIL_HTLC.Reason.Failure(IncorrectOrUnknownPaymentDetails(totalAmount, TestConstants.defaultBlockHeight.toLong())), commit = true))
            assertEquals(setOf(WrappedChannelEvent(add.channelId, expected)), result.actions.toSet())
        }
    }

    @Test
    fun `mpp timeout`() {
        val startTime = currentTimestampSeconds()
        val channelId = randomBytes32()
        val paymentHandler = IncomingPaymentHandler(TestConstants.Bob.nodeParams)
        val (incomingPayment, paymentSecret) = makeIncomingPayment(paymentHandler, defaultAmount)

        // Step 1 of 3:
        // - Alice sends (unfinished) multipart htlcs to Bob.
        run {
            listOf(1L, 2L).forEach { id ->
                val add = makeUpdateAddHtlc(id, channelId, paymentHandler, incomingPayment.paymentRequest.paymentHash, makeMppPayload(10_000.msat, defaultAmount, paymentSecret))
                val result = paymentHandler.process(add, TestConstants.defaultBlockHeight)
                assertTrue { result.status == IncomingPaymentHandler.Status.PENDING }
                assertTrue { result.actions.isEmpty() }
            }
        }

        // Step 2 of 3:
        // - don't expire the multipart htlcs too soon.
        run {
            val currentTimestampSeconds = startTime + paymentHandler.nodeParams.multiPartPaymentExpiry - 2
            val actions = paymentHandler.checkPaymentsTimeout(currentTimestampSeconds)
            assertTrue { actions.isEmpty() }
        }

        // Step 3 of 3:
        // - expire the htlc-set after configured expiration.
        run {
            val currentTimestampSeconds = startTime + paymentHandler.nodeParams.multiPartPaymentExpiry + 2
            val actions = paymentHandler.checkPaymentsTimeout(currentTimestampSeconds)
            val expected = setOf(
                WrappedChannelEvent(channelId, ChannelEvent.ExecuteCommand(CMD_FAIL_HTLC(1, CMD_FAIL_HTLC.Reason.Failure(PaymentTimeout), commit = true))),
                WrappedChannelEvent(channelId, ChannelEvent.ExecuteCommand(CMD_FAIL_HTLC(2, CMD_FAIL_HTLC.Reason.Failure(PaymentTimeout), commit = true))),
            )
            assertEquals(expected, actions.toSet())
        }
    }

    @Test
    fun `mpp timeout then success`() {
        val startTime = currentTimestampSeconds()
        val channelId = randomBytes32()
        val (amount1, amount2) = listOf(60_000.msat, 30_000.msat)
        val totalAmount = amount1 + amount2
        val paymentHandler = IncomingPaymentHandler(TestConstants.Bob.nodeParams)
        val (incomingPayment, paymentSecret) = makeIncomingPayment(paymentHandler, totalAmount)

        // Step 1 of 4:
        // - Alice sends single (unfinished) multipart htlc to Bob.
        run {
            val add = makeUpdateAddHtlc(1, channelId, paymentHandler, incomingPayment.paymentRequest.paymentHash, makeMppPayload(amount1, totalAmount, paymentSecret))
            val result = paymentHandler.process(add, TestConstants.defaultBlockHeight)
            assertTrue { result.status == IncomingPaymentHandler.Status.PENDING }
            assertTrue { result.actions.isEmpty() }
        }

        // Step 2 of 4:
        // - the MPP set times out
        run {
            val currentTimestampSeconds = startTime + paymentHandler.nodeParams.multiPartPaymentExpiry + 2
            val actions = paymentHandler.checkPaymentsTimeout(currentTimestampSeconds)
            val expected = WrappedChannelEvent(channelId, ChannelEvent.ExecuteCommand(CMD_FAIL_HTLC(1, CMD_FAIL_HTLC.Reason.Failure(PaymentTimeout), commit = true)))
            assertEquals(setOf(expected), actions.toSet())
        }

        // Step 3 of 4:
        // - Alice tries again, and sends another single (unfinished) multipart htlc to Bob.
        run {
            val add = makeUpdateAddHtlc(3, channelId, paymentHandler, incomingPayment.paymentRequest.paymentHash, makeMppPayload(amount1, totalAmount, paymentSecret))
            val result = paymentHandler.process(add, TestConstants.defaultBlockHeight)
            assertTrue { result.status == IncomingPaymentHandler.Status.PENDING }
            assertTrue { result.actions.isEmpty() }
        }

        // Step 4 of 4:
        // - Alice sends second and last part of mpp
        // - Bob accepts htlc set
        run {
            val add = makeUpdateAddHtlc(4, channelId, paymentHandler, incomingPayment.paymentRequest.paymentHash, makeMppPayload(amount2, totalAmount, paymentSecret))
            val result = paymentHandler.process(add, TestConstants.defaultBlockHeight)
            assertTrue { result.status == IncomingPaymentHandler.Status.ACCEPTED }
            val expected = setOf(
                WrappedChannelEvent(channelId, ChannelEvent.ExecuteCommand(CMD_FULFILL_HTLC(3, incomingPayment.paymentPreimage, commit = true))),
                WrappedChannelEvent(channelId, ChannelEvent.ExecuteCommand(CMD_FULFILL_HTLC(4, incomingPayment.paymentPreimage, commit = true))),
            )
            assertEquals(expected, result.actions.toSet())
        }
    }

    @Test
    fun `mpp success then additional HTLC`() {
        val (amount1, amount2) = listOf(60_000.msat, 30_000.msat)
        val totalAmount = amount1 + amount2
        val paymentHandler = IncomingPaymentHandler(TestConstants.Bob.nodeParams)
        val (incomingPayment, paymentSecret) = makeIncomingPayment(paymentHandler, totalAmount)

        // Step 1 of 2:
        // - Alice receives complete mpp set
        run {
            val add1 = makeUpdateAddHtlc(8, randomBytes32(), paymentHandler, incomingPayment.paymentRequest.paymentHash, makeMppPayload(amount1, totalAmount, paymentSecret))
            val result1 = paymentHandler.process(add1, TestConstants.defaultBlockHeight)
            assertTrue { result1.status == IncomingPaymentHandler.Status.PENDING }
            assertTrue { result1.actions.isEmpty() }

            val add2 = makeUpdateAddHtlc(4, randomBytes32(), paymentHandler, incomingPayment.paymentRequest.paymentHash, makeMppPayload(amount2, totalAmount, paymentSecret))
            val result2 = paymentHandler.process(add2, TestConstants.defaultBlockHeight)
            assertTrue { result2.status == IncomingPaymentHandler.Status.ACCEPTED }
            val expected = setOf(
                WrappedChannelEvent(add1.channelId, ChannelEvent.ExecuteCommand(CMD_FULFILL_HTLC(add1.id, incomingPayment.paymentPreimage, commit = true))),
                WrappedChannelEvent(add2.channelId, ChannelEvent.ExecuteCommand(CMD_FULFILL_HTLC(add2.id, incomingPayment.paymentPreimage, commit = true))),
            )
            assertEquals(expected, result2.actions.toSet())
        }

        // Step 2 of 2:
        // - Alice receives and additional htlc for the invoice, which she already completed
        run {
            val add = makeUpdateAddHtlc(3, randomBytes32(), paymentHandler, incomingPayment.paymentRequest.paymentHash, makeMppPayload(1_500.msat, totalAmount, paymentSecret))
            val result = paymentHandler.process(add, TestConstants.defaultBlockHeight)
            assertTrue { result.status == IncomingPaymentHandler.Status.REJECTED }
            val expected = ChannelEvent.ExecuteCommand(CMD_FAIL_HTLC(add.id, CMD_FAIL_HTLC.Reason.Failure(IncorrectOrUnknownPaymentDetails(totalAmount, TestConstants.defaultBlockHeight.toLong())), commit = true))
            assertEquals(setOf(WrappedChannelEvent(add.channelId, expected)), result.actions.toSet())
        }
    }

    companion object {
        val defaultPreimage = randomBytes32()
        val defaultPaymentHash = Crypto.sha256(defaultPreimage).toByteVector32()
        val defaultAmount = 100_000.msat

        private fun channelHops(destination: PublicKey): List<ChannelHop> {
            val dummyKey = PrivateKey(ByteVector32("0101010101010101010101010101010101010101010101010101010101010101")).publicKey()
            val dummyUpdate = ChannelUpdate(
                signature = ByteVector64.Zeroes,
                chainHash = ByteVector32.Zeroes,
                shortChannelId = ShortChannelId(144, 0, 0),
                timestamp = 0,
                messageFlags = 0,
                channelFlags = 0,
                cltvExpiryDelta = CltvExpiryDelta(144),
                htlcMinimumMsat = 1000.msat,
                feeBaseMsat = 1.msat,
                feeProportionalMillionths = 10,
                htlcMaximumMsat = null
            )
            val channelHop = ChannelHop(dummyKey, destination, dummyUpdate)
            return listOf(channelHop)
        }

        private fun makeCmdAddHtlc(destination: PublicKey, paymentHash: ByteVector32, finalPayload: FinalPayload): CMD_ADD_HTLC {
            return OutgoingPacket.buildCommand(UUID.randomUUID(), paymentHash, channelHops(destination), finalPayload).first.copy(commit = true)
        }

        private fun makeUpdateAddHtlc(id: Long, channelId: ByteVector32, destination: IncomingPaymentHandler, paymentHash: ByteVector32, finalPayload: FinalPayload): UpdateAddHtlc {
            val (_, _, packetAndSecrets) = OutgoingPacket.buildPacket(paymentHash, channelHops(destination.nodeParams.nodeId), finalPayload, OnionRoutingPacket.PaymentPacketLength)
            return UpdateAddHtlc(channelId, id, finalPayload.amount, paymentHash, finalPayload.expiry, packetAndSecrets.packet)
        }

        private fun makeLegacyPayload(amount: MilliSatoshi, cltvExpiryDelta: CltvExpiryDelta = CltvExpiryDelta(144), currentBlockHeight: Int = TestConstants.defaultBlockHeight): FinalPayload {
            val expiry = cltvExpiryDelta.toCltvExpiry(currentBlockHeight.toLong())
            return FinalPayload.createSinglePartPayload(amount, expiry)
        }

        private fun makeMppPayload(
            amount: MilliSatoshi,
            totalAmount: MilliSatoshi,
            paymentSecret: ByteVector32,
            cltvExpiryDelta: CltvExpiryDelta = CltvExpiryDelta(144),
            currentBlockHeight: Int = TestConstants.defaultBlockHeight
        ): FinalPayload {
            val expiry = cltvExpiryDelta.toCltvExpiry(currentBlockHeight.toLong())
            return FinalPayload.createMultiPartPayload(amount, totalAmount, expiry, paymentSecret)
        }

        private fun makePayToOpenRequest(incomingPayment: IncomingPayment, finalPayload: FinalPayload): PayToOpenRequest {
            return PayToOpenRequest(
                chainHash = ByteVector32.Zeroes,
                fundingSatoshis = 100_000.sat,
                amountMsat = finalPayload.amount,
                feeSatoshis = 100.sat,
                paymentHash = incomingPayment.paymentRequest.paymentHash,
                feeThresholdSatoshis = 1_000.sat,
                feeProportionalMillionths = 100,
                expireAt = Long.MAX_VALUE,
                finalPacket = OutgoingPacket.buildPacket(
                    paymentHash = incomingPayment.paymentRequest.paymentHash,
                    hops = channelHops(incomingPayment.paymentRequest.nodeId),
                    finalPayload = finalPayload,
                    payloadLength = OnionRoutingPacket.PaymentPacketLength
                ).third.packet
            )
        }

        private fun makeIncomingPayment(payee: IncomingPaymentHandler, amount: MilliSatoshi?, expirySeconds: Long? = null, timestamp: Long = currentTimestampSeconds()): Pair<IncomingPayment, ByteVector32> {
            val paymentRequest = payee.createInvoice(defaultPreimage, amount, "unit test", expirySeconds, timestamp)
            return Pair(IncomingPayment(paymentRequest, defaultPreimage), paymentRequest.paymentSecret!!)
        }
    }
}
