package fr.acinq.lightning.payment

import fr.acinq.bitcoin.*
import fr.acinq.lightning.CltvExpiryDelta
import fr.acinq.lightning.Lightning
import fr.acinq.lightning.Lightning.randomBytes32
import fr.acinq.lightning.MilliSatoshi
import fr.acinq.lightning.ShortChannelId
import fr.acinq.lightning.channel.*
import fr.acinq.lightning.channel.TestsHelper.processSameState
import fr.acinq.lightning.crypto.sphinx.Sphinx
import fr.acinq.lightning.db.InMemoryPaymentsDb
import fr.acinq.lightning.db.IncomingPayment
import fr.acinq.lightning.db.IncomingPaymentsDb
import fr.acinq.lightning.db.PaymentTypeFilter
import fr.acinq.lightning.io.PayToOpenResponseCommand
import fr.acinq.lightning.io.WrappedChannelCommand
import fr.acinq.lightning.router.ChannelHop
import fr.acinq.lightning.router.NodeHop
import fr.acinq.lightning.tests.TestConstants
import fr.acinq.lightning.tests.utils.LightningTestSuite
import fr.acinq.lightning.tests.utils.runSuspendTest
import fr.acinq.lightning.utils.*
import fr.acinq.lightning.wire.*
import kotlinx.coroutines.delay
import kotlin.test.*

class IncomingPaymentHandlerTestsCommon : LightningTestSuite() {

    @Test
    fun `add HTLC to channel commitments`() {
        var (alice, bob) = TestsHelper.reachNormal()
        val cmdAddHtlc = makeCmdAddHtlc(
            bob.staticParams.nodeParams.nodeId, defaultPaymentHash,
            makeMppPayload(100_000.msat, 150_000.msat, randomBytes32(), currentBlockHeight = alice.currentBlockHeight)
        )

        // Step 1: alice ---> update_add_htlc ---> bob

        var processResult = alice.processSameState(ChannelCommand.ExecuteCommand(cmdAddHtlc))
        alice = processResult.first
        var actions = processResult.second
        assertEquals(2, actions.size)
        val add = actions.findOutgoingMessage<UpdateAddHtlc>()
        val aliceCmdSign = actions.findCommand<CMD_SIGN>()

        processResult = bob.processSameState(ChannelCommand.MessageReceived(add))
        bob = processResult.first
        actions = processResult.second
        assertTrue { actions.filterIsInstance<ChannelAction.Message.Send>().isEmpty() }

        assertTrue { alice.commitments.localChanges.proposed.size == 1 }
        assertTrue { alice.commitments.localChanges.signed.isEmpty() }
        assertTrue { alice.commitments.localChanges.acked.isEmpty() }

        assertTrue { bob.commitments.remoteChanges.proposed.size == 1 }
        assertTrue { bob.commitments.remoteChanges.acked.isEmpty() }
        assertTrue { bob.commitments.remoteChanges.signed.isEmpty() }

        // Step 2: alice ---> commitment_signed ---> bob

        processResult = alice.processSameState(ChannelCommand.ExecuteCommand(aliceCmdSign))
        alice = processResult.first
        actions = processResult.second
        val aliceSig = actions.findOutgoingMessage<CommitSig>()

        processResult = bob.processSameState(ChannelCommand.MessageReceived(aliceSig))
        bob = processResult.first
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

        processResult = alice.processSameState(ChannelCommand.MessageReceived(bobRev))
        alice = processResult.first
        actions = processResult.second
        assertTrue { actions.filterIsInstance<ChannelAction.Message.Send>().isEmpty() }

        assertTrue { alice.commitments.localChanges.proposed.isEmpty() }
        assertTrue { alice.commitments.localChanges.signed.isEmpty() }
        assertTrue { alice.commitments.localChanges.acked.size == 1 }

        assertTrue { bob.commitments.remoteChanges.proposed.isEmpty() }
        assertTrue { bob.commitments.remoteChanges.acked.size == 1 }
        assertTrue { bob.commitments.remoteChanges.signed.isEmpty() }

        // Step 4: alice <--- commitment_signed <--- bob

        processResult = bob.processSameState(ChannelCommand.ExecuteCommand(bobCmdSign))
        bob = processResult.first
        actions = processResult.second
        val bobSig = actions.findOutgoingMessage<CommitSig>()

        processResult = alice.processSameState(ChannelCommand.MessageReceived(bobSig))
        alice = processResult.first
        actions = processResult.second
        val aliceRev = actions.findOutgoingMessage<RevokeAndAck>()

        assertTrue { alice.commitments.localChanges.proposed.isEmpty() }
        assertTrue { alice.commitments.localChanges.signed.isEmpty() }
        assertTrue { alice.commitments.localChanges.acked.isEmpty() }

        assertTrue { bob.commitments.remoteChanges.proposed.isEmpty() }
        assertTrue { bob.commitments.remoteChanges.acked.isEmpty() }
        assertTrue { bob.commitments.remoteChanges.signed.size == 1 }

        // Step 5: alice ---> revoke_and_ack ---> bob

        processResult = bob.processSameState(ChannelCommand.MessageReceived(aliceRev))
        bob = processResult.first
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
    fun `receive multipart payment with single HTLC`() = runSuspendTest {
        val (paymentHandler, incomingPayment, paymentSecret) = createFixture(defaultAmount)
        checkDbPayment(incomingPayment, paymentHandler.db)
        val channelId = randomBytes32()
        val add = makeUpdateAddHtlc(12, channelId, paymentHandler, incomingPayment.paymentHash, makeMppPayload(defaultAmount, defaultAmount, paymentSecret))
        val result = paymentHandler.process(add, TestConstants.defaultBlockHeight)

        assertTrue { result is IncomingPaymentHandler.ProcessAddResult.Accepted }
        result as IncomingPaymentHandler.ProcessAddResult.Accepted
        val expected = ChannelCommand.ExecuteCommand(CMD_FULFILL_HTLC(add.id, incomingPayment.preimage, commit = true))
        assertEquals(setOf(WrappedChannelCommand(add.channelId, expected)), result.actions.toSet())

        assertEquals(result.incomingPayment.received, result.received)
        assertEquals(defaultAmount, result.received.amount)
        assertEquals(setOf(IncomingPayment.ReceivedWith.LightningPayment(amount = defaultAmount, channelId = channelId, htlcId = 12)), result.received.receivedWith)

        checkDbPayment(result.incomingPayment, paymentHandler.db)
    }

    @Test
    fun `receive pay-to-open payment with single HTLC`() = runSuspendTest {
        val (paymentHandler, incomingPayment, paymentSecret) = createFixture(defaultAmount)
        checkDbPayment(incomingPayment, paymentHandler.db)
        val payToOpenRequest = makePayToOpenRequest(incomingPayment, makeMppPayload(defaultAmount, defaultAmount, paymentSecret))
        val result = paymentHandler.process(payToOpenRequest, TestConstants.defaultBlockHeight)

        assertTrue { result is IncomingPaymentHandler.ProcessAddResult.Accepted }
        result as IncomingPaymentHandler.ProcessAddResult.Accepted
        val expected = PayToOpenResponseCommand(PayToOpenResponse(payToOpenRequest.chainHash, payToOpenRequest.paymentHash, PayToOpenResponse.Result.Success(incomingPayment.preimage)))
        assertEquals(setOf(expected), result.actions.toSet())

        val newChannelUUID = result.received.receivedWith.filterIsInstance<IncomingPayment.ReceivedWith.NewChannel>().first().id
        val expectedFees = defaultAmount * 0.1 // 10% fees
        assertEquals(defaultAmount - expectedFees, result.received.amount)
        assertEquals(expectedFees, result.received.fees)
        assertEquals(setOf(IncomingPayment.ReceivedWith.NewChannel(id = newChannelUUID, amount = defaultAmount - expectedFees, fees = expectedFees, channelId = null)), result.received.receivedWith)

        checkDbPayment(result.incomingPayment, paymentHandler.db)
    }

    @Test
    fun `receive pay-to-open payment with two evenly-split HTLCs`() = runSuspendTest {
        val (paymentHandler, incomingPayment, paymentSecret) = createFixture(defaultAmount)
        checkDbPayment(incomingPayment, paymentHandler.db)
        val payToOpenRequest1 = makePayToOpenRequest(incomingPayment, makeMppPayload(50_000.msat, defaultAmount, paymentSecret))
        val payToOpenRequest2 = makePayToOpenRequest(incomingPayment, makeMppPayload(50_000.msat, defaultAmount, paymentSecret))

        val result1 = paymentHandler.process(payToOpenRequest1, TestConstants.defaultBlockHeight)
        assertIs<IncomingPaymentHandler.ProcessAddResult.Pending>(result1)
        val result2 = paymentHandler.process(payToOpenRequest2, TestConstants.defaultBlockHeight)
        assertIs<IncomingPaymentHandler.ProcessAddResult.Accepted>(result2)

        val expected = PayToOpenResponseCommand(PayToOpenResponse(payToOpenRequest1.chainHash, payToOpenRequest1.paymentHash, PayToOpenResponse.Result.Success(incomingPayment.preimage)))
        assertEquals(setOf(expected), (result1.actions + result2.actions).toSet())

        val expectedFees = defaultAmount * 0.1 // 10% fees
        assertEquals(defaultAmount - expectedFees, result2.received.amount)
        assertEquals(expectedFees, result2.received.fees)

        val (id1, id2) = result2.received.receivedWith.map { (it as IncomingPayment.ReceivedWith.NewChannel).id }
        assertEquals(setOf(
            makeReceivedWithNewChannel(payToOpenRequest1, uuid = id1),
            makeReceivedWithNewChannel(payToOpenRequest2, uuid = id2)
        ), result2.received.receivedWith)

        checkDbPayment(result2.incomingPayment, paymentHandler.db)
    }

    @Test
    fun `receive pay-to-open payment with two unevenly-split HTLCs`() = runSuspendTest {
        val (paymentHandler, incomingPayment, paymentSecret) = createFixture(defaultAmount)
        checkDbPayment(incomingPayment, paymentHandler.db)
        val payToOpenRequest1 = makePayToOpenRequest(incomingPayment, makeMppPayload(40_000.msat, defaultAmount, paymentSecret))
        val payToOpenRequest2 = makePayToOpenRequest(incomingPayment, makeMppPayload(60_000.msat, defaultAmount, paymentSecret))

        val result1 = paymentHandler.process(payToOpenRequest1, TestConstants.defaultBlockHeight)
        assertIs<IncomingPaymentHandler.ProcessAddResult.Pending>(result1)
        val result2 = paymentHandler.process(payToOpenRequest2, TestConstants.defaultBlockHeight)
        assertIs<IncomingPaymentHandler.ProcessAddResult.Accepted>(result2)

        val expected = PayToOpenResponseCommand(PayToOpenResponse(payToOpenRequest1.chainHash, payToOpenRequest1.paymentHash, PayToOpenResponse.Result.Success(incomingPayment.preimage)))
        assertEquals(setOf(expected), (result1.actions + result2.actions).toSet())

        val expectedFees = defaultAmount * 0.1 // 10% fees
        assertEquals(defaultAmount - expectedFees, result2.received.amount)
        assertEquals(expectedFees, result2.received.fees)

        val (id1, id2) = result2.received.receivedWith.map { (it as IncomingPayment.ReceivedWith.NewChannel).id }
        assertEquals(setOf(
            makeReceivedWithNewChannel(payToOpenRequest1, uuid = id1),
            makeReceivedWithNewChannel(payToOpenRequest2, uuid = id2)
        ), result2.received.receivedWith)

        checkDbPayment(result2.incomingPayment, paymentHandler.db)
    }

    @Test
    fun `receive pay-to-open payment with an unknown payment hash`() = runSuspendTest {
        val (paymentHandler, _, _) = createFixture(defaultAmount)
        val payToOpenRequest = PayToOpenRequest(
            chainHash = ByteVector32.Zeroes,
            fundingSatoshis = 100_000.sat,
            amountMsat = defaultAmount,
            payToOpenMinAmountMsat = 1_000_000.msat,
            payToOpenFeeSatoshis = 100.sat,
            paymentHash = ByteVector32.One, // <-- not associated to a pending invoice
            expireAt = Long.MAX_VALUE,
            finalPacket = OutgoingPaymentPacket.buildPacket(
                paymentHash = ByteVector32.One, // <-- has to be the same as the one above otherwise encryption fails
                hops = channelHops(paymentHandler.nodeParams.nodeId),
                finalPayload = makeMppPayload(defaultAmount, defaultAmount, randomBytes32()),
                payloadLength = OnionRoutingPacket.PaymentPacketLength
            ).third.packet
        )
        val result = paymentHandler.process(payToOpenRequest, TestConstants.defaultBlockHeight)

        assertTrue { result is IncomingPaymentHandler.ProcessAddResult.Rejected }
        result as IncomingPaymentHandler.ProcessAddResult.Rejected
        assertNull(result.incomingPayment)
        val expected = PayToOpenResponseCommand(
            PayToOpenResponse(
                payToOpenRequest.chainHash,
                payToOpenRequest.paymentHash,
                PayToOpenResponse.Result.Failure(
                    OutgoingPaymentPacket.buildHtlcFailure(
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
    fun `receive pay-to-open payment with an incorrect payment secret`() = runSuspendTest {
        val (paymentHandler, incomingPayment, paymentSecret) = createFixture(defaultAmount)
        val payToOpenRequest = makePayToOpenRequest(incomingPayment, makeMppPayload(defaultAmount, defaultAmount, paymentSecret.reversed())) // <--- wrong secret
        val result = paymentHandler.process(payToOpenRequest, TestConstants.defaultBlockHeight)

        assertTrue { result is IncomingPaymentHandler.ProcessAddResult.Rejected }
        result as IncomingPaymentHandler.ProcessAddResult.Rejected
        assertEquals(incomingPayment, result.incomingPayment)
        val expected = PayToOpenResponseCommand(
            PayToOpenResponse(
                payToOpenRequest.chainHash,
                payToOpenRequest.paymentHash,
                PayToOpenResponse.Result.Failure(
                    OutgoingPaymentPacket.buildHtlcFailure(
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
    fun `receive pay-to-open payment with an amount too low`() = runSuspendTest {
        val (paymentHandler, incomingPayment, paymentSecret) = createFixture(defaultAmount)
        val payToOpenRequest = makePayToOpenRequest(incomingPayment, makeMppPayload(defaultAmount, defaultAmount, paymentSecret), payToOpenMinAmount = defaultAmount + 1.msat) // <--- just below the min pay-to-open amount
        val result = paymentHandler.process(payToOpenRequest, TestConstants.defaultBlockHeight)

        assertTrue { result is IncomingPaymentHandler.ProcessAddResult.Rejected }
        result as IncomingPaymentHandler.ProcessAddResult.Rejected
        assertEquals(incomingPayment, result.incomingPayment)
        val expected = PayToOpenResponseCommand(
            PayToOpenResponse(
                payToOpenRequest.chainHash,
                payToOpenRequest.paymentHash,
                PayToOpenResponse.Result.Failure(
                    OutgoingPaymentPacket.buildHtlcFailure(
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
    fun `receive pay-to-open payment with a funding amount too low`() = runSuspendTest {
        val (paymentHandler, incomingPayment, paymentSecret) = run {
            val (paymentHandler, incomingPayment, paymentSecret) = createFixture(defaultAmount)
            val paymentHandler1 = IncomingPaymentHandler(paymentHandler.nodeParams.copy(minFundingSatoshis = 200_000.sat), paymentHandler.walletParams, paymentHandler.db)
            Triple(paymentHandler1, incomingPayment, paymentSecret)
        }

        val payToOpenRequest = makePayToOpenRequest(incomingPayment, makeMppPayload(defaultAmount, defaultAmount, paymentSecret))
        // we make sure that the pay-to-open funding amount is invalid
        assertTrue { payToOpenRequest.fundingSatoshis < paymentHandler.nodeParams.minFundingSatoshis }
        val result = paymentHandler.process(payToOpenRequest, TestConstants.defaultBlockHeight)

        assertTrue { result is IncomingPaymentHandler.ProcessAddResult.Rejected }
        result as IncomingPaymentHandler.ProcessAddResult.Rejected
        assertEquals(incomingPayment, result.incomingPayment)
        val expected = PayToOpenResponseCommand(
            PayToOpenResponse(
                payToOpenRequest.chainHash,
                payToOpenRequest.paymentHash,
                PayToOpenResponse.Result.Failure(
                    OutgoingPaymentPacket.buildHtlcFailure(
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
    fun `receive pay-to-open trampoline payment with an incorrect payment secret`() = runSuspendTest {
        val (paymentHandler, incomingPayment, paymentSecret) = createFixture(defaultAmount)
        val trampolineHops = listOf(
            NodeHop(TestConstants.Alice.nodeParams.nodeId, TestConstants.Bob.nodeParams.nodeId, CltvExpiryDelta(144), 0.msat)
        )
        val payToOpenRequest = PayToOpenRequest(
            chainHash = ByteVector32.Zeroes,
            fundingSatoshis = 100_000.sat,
            amountMsat = defaultAmount,
            payToOpenMinAmountMsat = 1_000_000.msat,
            payToOpenFeeSatoshis = 100.sat,
            paymentHash = incomingPayment.paymentHash,
            expireAt = Long.MAX_VALUE,
            finalPacket = OutgoingPaymentPacket.buildPacket(
                paymentHash = incomingPayment.paymentHash,
                hops = trampolineHops,
                finalPayload = makeMppPayload(defaultAmount, defaultAmount, paymentSecret.reversed()), // <-- wrong secret
                payloadLength = OnionRoutingPacket.TrampolinePacketLength
            ).third.packet
        )
        val result = paymentHandler.process(payToOpenRequest, TestConstants.defaultBlockHeight)

        assertTrue { result is IncomingPaymentHandler.ProcessAddResult.Rejected }
        result as IncomingPaymentHandler.ProcessAddResult.Rejected
        assertEquals(incomingPayment, result.incomingPayment)
        val expected = PayToOpenResponseCommand(
            PayToOpenResponse(
                payToOpenRequest.chainHash,
                payToOpenRequest.paymentHash,
                PayToOpenResponse.Result.Failure(
                    OutgoingPaymentPacket.buildHtlcFailure(
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
    fun `process incoming amount with unknown origin`() = runSuspendTest {
        val channelId = randomBytes32()
        val amountOrigin = ChannelAction.Storage.StoreIncomingAmount(amount = 15_000_000.msat, localInputs = setOf(), origin = null)
        val handler = IncomingPaymentHandler(TestConstants.Bob.nodeParams, TestConstants.Bob.walletParams, InMemoryPaymentsDb())
        handler.process(channelId, amountOrigin)
        val dbPayment = handler.db.getIncomingPayment(channelId.sha256().sha256())
        assertNotNull(dbPayment)
        assertTrue { dbPayment.origin is IncomingPayment.Origin.SwapIn }
        val newChannelUUID = dbPayment.received!!.receivedWith.filterIsInstance<IncomingPayment.ReceivedWith.NewChannel>().first().id
        assertEquals(setOf(IncomingPayment.ReceivedWith.NewChannel(id = newChannelUUID, amount = amountOrigin.amount, fees = 0.msat, channelId = channelId)), dbPayment.received?.receivedWith)
        assertEquals(15_000_000.msat, dbPayment.received?.amount)
    }

    @Test
    fun `process incoming amount with pay-to-open origin`() = runSuspendTest {
        val preimage = randomBytes32()
        val channelId = randomBytes32()
        val amountOrigin = ChannelAction.Storage.StoreIncomingAmount(
            amount = 15_000_000.msat,
            localInputs = setOf(),
            origin = ChannelOrigin.PayToOpenOrigin(paymentHash = preimage.sha256(), fee = 1_000.sat)
        )
        val handler = IncomingPaymentHandler(TestConstants.Bob.nodeParams, TestConstants.Bob.walletParams, InMemoryPaymentsDb())
        // simulate payment processed as a pay-to-open
        handler.db.addIncomingPayment(preimage, IncomingPayment.Origin.KeySend)
        val newChannelUUID = UUID.randomUUID()
        handler.db.receivePayment(preimage.sha256(), receivedWith = setOf(IncomingPayment.ReceivedWith.NewChannel(id = newChannelUUID, amount = 15_000_000.msat, fees = 1_000_000.msat, null)))
        // process the amount origin which must reconcile with the existing line in the database
        handler.process(channelId, amountOrigin)
        val dbPayment = handler.db.getIncomingPayment(preimage.sha256())
        assertNotNull(dbPayment)
        assertIs<IncomingPayment.Origin.KeySend>(dbPayment.origin)
        assertEquals(setOf(IncomingPayment.ReceivedWith.NewChannel(id = newChannelUUID, amount = 15_000_000.msat, fees = 1_000_000.msat, channelId = channelId)), dbPayment.received?.receivedWith)
        assertEquals(15_000_000.msat, dbPayment.received?.amount)
        assertEquals(1_000_000.msat, dbPayment.received?.fees)
    }

    @Test
    fun `process incoming amount with please-open-channel origin`() = runSuspendTest {
        val channelId = randomBytes32()
        val amountOrigin = ChannelAction.Storage.StoreIncomingAmount(
            amount = 33_000_000.msat,
            localInputs = setOf(OutPoint(randomBytes32(), 7)),
            origin = ChannelOrigin.PleaseOpenChannelOrigin(randomBytes32(), 1_200_000.msat)
        )
        val handler = IncomingPaymentHandler(TestConstants.Bob.nodeParams, TestConstants.Bob.walletParams, InMemoryPaymentsDb())
        handler.process(channelId, amountOrigin)
        val dbPayment = handler.db.getIncomingPayment(channelId.sha256().sha256())
        assertNotNull(dbPayment)
        assertIs<IncomingPayment.Origin.DualSwapIn>(dbPayment.origin)
        val newChannelUUID = dbPayment.received!!.receivedWith.filterIsInstance<IncomingPayment.ReceivedWith.NewChannel>().first().id
        assertEquals(setOf(IncomingPayment.ReceivedWith.NewChannel(id = newChannelUUID, amount = 33_000_000.msat, fees = 1_200_000.msat, channelId = channelId)), dbPayment.received?.receivedWith)
        assertEquals(33_000_000.msat, dbPayment.received?.amount)
        assertEquals(1_200_000.msat, dbPayment.received?.fees)
    }

    @Test
    fun `receive multipart payment with multiple HTLCs via same channel`() = runSuspendTest {
        val channelId = randomBytes32()
        val (amount1, amount2) = Pair(100_000.msat, 50_000.msat)
        val totalAmount = amount1 + amount2
        val (paymentHandler, incomingPayment, paymentSecret) = createFixture(totalAmount)

        // Step 1 of 2:
        // - Alice sends first multipart htlc to Bob
        // - Bob doesn't accept the MPP set yet
        run {
            val add = makeUpdateAddHtlc(0, channelId, paymentHandler, incomingPayment.paymentHash, makeMppPayload(amount1, totalAmount, paymentSecret))
            val result = paymentHandler.process(add, TestConstants.defaultBlockHeight)
            assertTrue { result is IncomingPaymentHandler.ProcessAddResult.Pending }
            result as IncomingPaymentHandler.ProcessAddResult.Pending
            assertTrue { result.incomingPayment.received == null }
            assertTrue { result.actions.isEmpty() }
        }

        // Step 2 of 2:
        // - Alice sends second multipart htlc to Bob
        // - Bob now accepts the MPP set
        run {
            val add = makeUpdateAddHtlc(1, channelId, paymentHandler, incomingPayment.paymentHash, makeMppPayload(amount2, totalAmount, paymentSecret))
            val result = paymentHandler.process(add, TestConstants.defaultBlockHeight)
            assertTrue { result is IncomingPaymentHandler.ProcessAddResult.Accepted }
            result as IncomingPaymentHandler.ProcessAddResult.Accepted
            val (expectedActions, expectedReceivedWith) = setOf(
                WrappedChannelCommand(channelId, ChannelCommand.ExecuteCommand(CMD_FULFILL_HTLC(0, defaultPreimage, commit = true)))
                        to IncomingPayment.ReceivedWith.LightningPayment(amount1, channelId, 0),
                WrappedChannelCommand(channelId, ChannelCommand.ExecuteCommand(CMD_FULFILL_HTLC(1, defaultPreimage, commit = true)))
                        to IncomingPayment.ReceivedWith.LightningPayment(amount2, channelId, 1),
            ).unzip()
            assertEquals(expectedActions.toSet(), result.actions.toSet())
            assertEquals(totalAmount, result.received.amount)
            assertEquals(expectedReceivedWith.toSet(), result.received.receivedWith)
            checkDbPayment(result.incomingPayment, paymentHandler.db)
        }
    }

    @Test
    fun `receive multipart payment with multiple HTLCs via different channels`() = runSuspendTest {
        val (channelId1, channelId2) = Pair(randomBytes32(), randomBytes32())
        val (amount1, amount2) = Pair(100_000.msat, 50_000.msat)
        val totalAmount = amount1 + amount2
        val (paymentHandler, incomingPayment, paymentSecret) = createFixture(totalAmount)

        // Step 1 of 2:
        // - Alice sends first multipart htlc to Bob
        // - Bob doesn't accept the MPP set yet
        run {
            val add = makeUpdateAddHtlc(7, channelId1, paymentHandler, incomingPayment.paymentHash, makeMppPayload(amount1, totalAmount, paymentSecret))
            val result = paymentHandler.process(add, TestConstants.defaultBlockHeight)
            assertTrue { result is IncomingPaymentHandler.ProcessAddResult.Pending }
            assertTrue { result.actions.isEmpty() }
        }

        // Step 2 of 2:
        // - Alice sends second multipart htlc to Bob
        // - Bob now accepts the MPP set
        run {
            val add = makeUpdateAddHtlc(5, channelId2, paymentHandler, incomingPayment.paymentHash, makeMppPayload(amount2, totalAmount, paymentSecret))
            val result = paymentHandler.process(add, TestConstants.defaultBlockHeight)
            assertTrue { result is IncomingPaymentHandler.ProcessAddResult.Accepted }
            result as IncomingPaymentHandler.ProcessAddResult.Accepted
            val (expectedActions, expectedReceivedWith) = setOf(
                WrappedChannelCommand(channelId1, ChannelCommand.ExecuteCommand(CMD_FULFILL_HTLC(7, defaultPreimage, commit = true)))
                        to IncomingPayment.ReceivedWith.LightningPayment(amount1, channelId1, 7),
                WrappedChannelCommand(channelId2, ChannelCommand.ExecuteCommand(CMD_FULFILL_HTLC(5, defaultPreimage, commit = true)))
                        to IncomingPayment.ReceivedWith.LightningPayment(amount2, channelId2, 5),
            ).unzip()
            assertEquals(expectedActions.toSet(), result.actions.toSet())
            assertEquals(totalAmount, result.received.amount)
            assertEquals(expectedReceivedWith.toSet(), result.received.receivedWith)
            checkDbPayment(result.incomingPayment, paymentHandler.db)
        }
    }

    @Test
    fun `receive multipart payment via pay-to-open`() = runSuspendTest {
        val (amount1, amount2) = Pair(100_000.msat, 50_000.msat)
        val (fee1, fee2) = Pair(amount1 * 0.1, amount2 * 0.1)
        val totalAmount = amount1 + amount2
        val (paymentHandler, incomingPayment, paymentSecret) = createFixture(totalAmount)

        // Step 1 of 2:
        // - Alice sends first multipart htlc to Bob
        // - Bob doesn't accept the MPP set yet
        run {
            val payToOpenRequest = makePayToOpenRequest(incomingPayment, makeMppPayload(amount1, totalAmount, paymentSecret))
            val result = paymentHandler.process(payToOpenRequest, TestConstants.defaultBlockHeight)
            assertTrue { result is IncomingPaymentHandler.ProcessAddResult.Pending }
            assertTrue { result.actions.isEmpty() }
        }

        // Step 2 of 2:
        // - Alice sends second multipart htlc to Bob
        // - Bob now accepts the MPP set
        run {
            val payToOpenRequest = makePayToOpenRequest(incomingPayment, makeMppPayload(amount2, totalAmount, paymentSecret))
            val result = paymentHandler.process(payToOpenRequest, TestConstants.defaultBlockHeight)
            assertTrue { result is IncomingPaymentHandler.ProcessAddResult.Accepted }
            result as IncomingPaymentHandler.ProcessAddResult.Accepted
            val (id1, id2) = result.received.receivedWith.map { (it as IncomingPayment.ReceivedWith.NewChannel).id }
            val (expectedActions, expectedReceivedWith) = setOf(
                PayToOpenResponseCommand(PayToOpenResponse(payToOpenRequest.chainHash, payToOpenRequest.paymentHash, PayToOpenResponse.Result.Success(incomingPayment.preimage)))
                        to IncomingPayment.ReceivedWith.NewChannel(id = id1, amount1 - fee1, fee1, null),
                PayToOpenResponseCommand(PayToOpenResponse(payToOpenRequest.chainHash, payToOpenRequest.paymentHash, PayToOpenResponse.Result.Success(incomingPayment.preimage)))
                        to IncomingPayment.ReceivedWith.NewChannel(id = id2, amount2 - fee2, fee2, null)
            ).unzip()
            assertEquals(expectedActions.toSet(), result.actions.toSet())
            val expectedFees = 15_000.msat // 10% of 150_000 msat
            assertEquals(totalAmount - expectedFees, result.received.amount)
            assertEquals(expectedFees, result.received.fees)
            assertEquals(expectedReceivedWith.toSet(), result.received.receivedWith)
            checkDbPayment(result.incomingPayment, paymentHandler.db)
        }
    }

    @Test
    fun `receive multipart payment with a mix of HTLC and pay-to-open`() = runSuspendTest {
        val channelId = randomBytes32()
        val (amount1, amount2) = Pair(100_000.msat, 50_000.msat)
        val fee2 = amount2 * 0.1
        val totalAmount = amount1 + amount2
        val (paymentHandler, incomingPayment, paymentSecret) = createFixture(totalAmount)

        // Step 1 of 2:
        // - Alice sends first multipart htlc to Bob
        // - Bob doesn't accept the MPP set yet
        run {
            val add = makeUpdateAddHtlc(0, channelId, paymentHandler, incomingPayment.paymentHash, makeMppPayload(amount1, totalAmount, paymentSecret))
            val result = paymentHandler.process(add, TestConstants.defaultBlockHeight)
            assertTrue { result is IncomingPaymentHandler.ProcessAddResult.Pending }
            assertTrue { result.actions.isEmpty() }
        }

        // Step 2 of 2:
        // - Alice sends second multipart htlc to Bob
        // - Bob now accepts the MPP set
        run {
            val payToOpenRequest = makePayToOpenRequest(incomingPayment, makeMppPayload(amount2, totalAmount, paymentSecret))
            val result = paymentHandler.process(payToOpenRequest, TestConstants.defaultBlockHeight)
            assertTrue { result is IncomingPaymentHandler.ProcessAddResult.Accepted }
            result as IncomingPaymentHandler.ProcessAddResult.Accepted
            val newChannelUUID = result.received.receivedWith.filterIsInstance<IncomingPayment.ReceivedWith.NewChannel>().first().id
            val (expectedActions, expectedReceivedWith) = setOf(
                WrappedChannelCommand(channelId, ChannelCommand.ExecuteCommand(CMD_FULFILL_HTLC(0, incomingPayment.preimage, commit = true)))
                        to IncomingPayment.ReceivedWith.LightningPayment(amount1, channelId, 0),
                PayToOpenResponseCommand(PayToOpenResponse(payToOpenRequest.chainHash, payToOpenRequest.paymentHash, PayToOpenResponse.Result.Success(incomingPayment.preimage)))
                        to IncomingPayment.ReceivedWith.NewChannel(id = newChannelUUID, amount2 - fee2, fee2, null),
            ).unzip()
            assertEquals(expectedActions.toSet(), result.actions.toSet())
            val expectedFees = 5_000.msat // 10% of the amount sent via pay-to-open (50 000 msat)
            assertEquals(totalAmount - expectedFees, result.received.amount)
            assertEquals(expectedFees, result.received.fees)
            assertEquals(expectedReceivedWith.toSet(), result.received.receivedWith)
            checkDbPayment(result.incomingPayment, paymentHandler.db)
        }
    }

    @Test
    fun `receive multipart payment with a mix of HTLC and pay-to-open -- total amount too low`() = runSuspendTest {
        val channelId = randomBytes32()
        val (amount1, amount2) = Pair(100_000.msat, 50_000.msat)
        val totalAmount = amount1 + amount2
        val (paymentHandler, incomingPayment, paymentSecret) = createFixture(totalAmount)

        // Step 1 of 2:
        // - Alice sends first multipart htlc to Bob
        // - Bob doesn't accept the MPP set yet
        run {
            val add = makeUpdateAddHtlc(0, channelId, paymentHandler, incomingPayment.paymentHash, makeMppPayload(amount1, totalAmount, paymentSecret))
            val result = paymentHandler.process(add, TestConstants.defaultBlockHeight)
            assertTrue { result is IncomingPaymentHandler.ProcessAddResult.Pending }
            assertTrue { result.actions.isEmpty() }
        }

        // Step 2 of 2:
        // - Alice sends second multipart htlc to Bob
        // - Bob has received the complete MPP set
        run {
            val payToOpenRequest = makePayToOpenRequest(incomingPayment, makeMppPayload(amount2, totalAmount, paymentSecret), payToOpenMinAmount = 200_000.msat)
            val result = paymentHandler.process(payToOpenRequest, TestConstants.defaultBlockHeight)
            assertTrue { result is IncomingPaymentHandler.ProcessAddResult.Rejected }
            val expected = setOf(
                WrappedChannelCommand(
                    channelId,
                    ChannelCommand.ExecuteCommand(CMD_FAIL_HTLC(0, CMD_FAIL_HTLC.Reason.Failure(IncorrectOrUnknownPaymentDetails(totalAmount, TestConstants.defaultBlockHeight.toLong())), commit = true))
                ),
                PayToOpenResponseCommand(
                    PayToOpenResponse(
                        payToOpenRequest.chainHash,
                        payToOpenRequest.paymentHash,
                        PayToOpenResponse.Result.Failure(
                            OutgoingPaymentPacket.buildHtlcFailure(
                                paymentHandler.nodeParams.nodePrivateKey,
                                payToOpenRequest.paymentHash,
                                payToOpenRequest.finalPacket,
                                CMD_FAIL_HTLC.Reason.Failure(IncorrectOrUnknownPaymentDetails(totalAmount, TestConstants.defaultBlockHeight.toLong()))
                            ).right!!
                        )
                    )
                )
            )
            assertEquals(expected, result.actions.toSet())
        }
    }

    @Test
    fun `receive multipart payment with a mix of HTLC and pay-to-open -- amount of the pay-to-open parts too low`() = runSuspendTest {
        val channelId = randomBytes32()
        val (amount1, amount2, amount3) = Triple(100_000.msat, 30_000.msat, 20_000.msat)
        val totalAmount = amount1 + amount2 + amount3
        val (paymentHandler, incomingPayment, paymentSecret) = createFixture(totalAmount)

        // Step 1 of 3:
        // - Alice sends first multipart htlc to Bob
        // - Bob doesn't accept the MPP set yet
        run {
            val add = makeUpdateAddHtlc(0, channelId, paymentHandler, incomingPayment.paymentHash, makeMppPayload(amount1, totalAmount, paymentSecret))
            val result = paymentHandler.process(add, TestConstants.defaultBlockHeight)
            assertTrue { result is IncomingPaymentHandler.ProcessAddResult.Pending }
            assertTrue { result.actions.isEmpty() }
        }

        // Step 2 of 3:
        // - Alice sends second multipart htlc to Bob
        // - Bob doesn't accept the MPP set yet
        val payToOpenRequest1 = run {
            val payToOpenRequest = makePayToOpenRequest(incomingPayment, makeMppPayload(amount2, totalAmount, paymentSecret), payToOpenMinAmount = 75_000.msat)
            val result = paymentHandler.process(payToOpenRequest, TestConstants.defaultBlockHeight)
            assertTrue { result is IncomingPaymentHandler.ProcessAddResult.Pending }
            assertTrue { result.actions.isEmpty() }
            payToOpenRequest
        }

        // Step 3 of 3:
        // - Alice sends last multipart htlc to Bob
        // - Bob has received the complete MPP set
        run {
            val payToOpenRequest2 = makePayToOpenRequest(incomingPayment, makeMppPayload(amount3, totalAmount, paymentSecret), payToOpenMinAmount = 75_000.msat)
            val result = paymentHandler.process(payToOpenRequest2, TestConstants.defaultBlockHeight)
            assertTrue { result is IncomingPaymentHandler.ProcessAddResult.Rejected }
            val expected = setOf(
                WrappedChannelCommand(
                    channelId,
                    ChannelCommand.ExecuteCommand(CMD_FAIL_HTLC(0, CMD_FAIL_HTLC.Reason.Failure(IncorrectOrUnknownPaymentDetails(totalAmount, TestConstants.defaultBlockHeight.toLong())), commit = true))
                ),
                PayToOpenResponseCommand(
                    PayToOpenResponse(
                        payToOpenRequest1.chainHash,
                        payToOpenRequest1.paymentHash,
                        PayToOpenResponse.Result.Failure(
                            OutgoingPaymentPacket.buildHtlcFailure(
                                paymentHandler.nodeParams.nodePrivateKey,
                                payToOpenRequest1.paymentHash,
                                payToOpenRequest1.finalPacket,
                                CMD_FAIL_HTLC.Reason.Failure(IncorrectOrUnknownPaymentDetails(totalAmount, TestConstants.defaultBlockHeight.toLong()))
                            ).right!!
                        )
                    )
                ),
                PayToOpenResponseCommand(
                    PayToOpenResponse(
                        payToOpenRequest2.chainHash,
                        payToOpenRequest2.paymentHash,
                        PayToOpenResponse.Result.Failure(
                            OutgoingPaymentPacket.buildHtlcFailure(
                                paymentHandler.nodeParams.nodePrivateKey,
                                payToOpenRequest2.paymentHash,
                                payToOpenRequest2.finalPacket,
                                CMD_FAIL_HTLC.Reason.Failure(IncorrectOrUnknownPaymentDetails(totalAmount, TestConstants.defaultBlockHeight.toLong()))
                            ).right!!
                        )
                    )
                ),
            )
            assertEquals(expected, result.actions.toSet())
        }
    }

    @Test
    fun `receive normal single HTLC with amount-less invoice`() = runSuspendTest {
        val (paymentHandler, incomingPayment, paymentSecret) = createFixture(invoiceAmount = null)
        val add = makeUpdateAddHtlc(0, randomBytes32(), paymentHandler, incomingPayment.paymentHash, makeMppPayload(defaultAmount, defaultAmount, paymentSecret))
        val result = paymentHandler.process(add, TestConstants.defaultBlockHeight)

        assertTrue { result is IncomingPaymentHandler.ProcessAddResult.Accepted }
        val expected = WrappedChannelCommand(add.channelId, ChannelCommand.ExecuteCommand(CMD_FULFILL_HTLC(add.id, incomingPayment.preimage, commit = true)))
        assertEquals(setOf(expected), result.actions.toSet())
    }

    @Test
    fun `receive multipart payment with amount-less invoice`() = runSuspendTest {
        val channelId = randomBytes32()
        val (amount1, amount2) = Pair(100_000.msat, 50_000.msat)
        val totalAmount = amount1 + amount2
        val (paymentHandler, incomingPayment, paymentSecret) = createFixture(invoiceAmount = null)

        // Step 1 of 2:
        // - Alice sends first multipart htlc to Bob
        // - Bob doesn't accept the MPP set yet
        run {
            val add = makeUpdateAddHtlc(7, channelId, paymentHandler, incomingPayment.paymentHash, makeMppPayload(amount1, totalAmount, paymentSecret))
            val result = paymentHandler.process(add, TestConstants.defaultBlockHeight)
            assertTrue { result is IncomingPaymentHandler.ProcessAddResult.Pending }
            assertTrue { result.actions.isEmpty() }
        }

        // Step 2 of 2:
        // - Alice sends second multipart htlc to Bob
        // - Bob now accepts the MPP set
        run {
            val add = makeUpdateAddHtlc(11, channelId, paymentHandler, incomingPayment.paymentHash, makeMppPayload(amount2, totalAmount, paymentSecret))
            val result = paymentHandler.process(add, TestConstants.defaultBlockHeight)
            assertTrue { result is IncomingPaymentHandler.ProcessAddResult.Accepted }
            val expected = setOf(
                WrappedChannelCommand(channelId, ChannelCommand.ExecuteCommand(CMD_FULFILL_HTLC(7, incomingPayment.preimage, commit = true))),
                WrappedChannelCommand(channelId, ChannelCommand.ExecuteCommand(CMD_FULFILL_HTLC(11, incomingPayment.preimage, commit = true))),
            )
            assertEquals(expected, result.actions.toSet())
        }
    }

    @Test
    fun `receive multipart payment with amount greater than total amount`() = runSuspendTest {
        val channelId = randomBytes32()
        val (amount1, amount2, amount3) = listOf(100_000.msat, 60_000.msat, 40_000.msat)
        val requestedAmount = 180_000.msat
        val totalAmount = amount1 + amount2 + amount3
        val (paymentHandler, incomingPayment, paymentSecret) = createFixture(requestedAmount)

        // Step 1 of 2:
        // - Alice sends first 2 multipart htlcs to Bob.
        // - Bob doesn't accept the MPP set yet
        listOf(Pair(3L, amount1), Pair(5L, amount2)).forEach { (id, amount) ->
            val add = makeUpdateAddHtlc(id, channelId, paymentHandler, incomingPayment.paymentHash, makeMppPayload(amount, totalAmount, paymentSecret))
            val result = paymentHandler.process(add, TestConstants.defaultBlockHeight)
            assertTrue { result is IncomingPaymentHandler.ProcessAddResult.Pending }
            assertTrue { result.actions.isEmpty() }
        }

        // Step 2 of 2:
        // - Alice sends third multipart htlc to Bob
        // - Bob now accepts the MPP set
        run {
            val add = makeUpdateAddHtlc(6L, channelId, paymentHandler, incomingPayment.paymentHash, makeMppPayload(amount3, totalAmount, paymentSecret))
            val result = paymentHandler.process(add, TestConstants.defaultBlockHeight)
            assertTrue { result is IncomingPaymentHandler.ProcessAddResult.Accepted }
            val expected = setOf(
                WrappedChannelCommand(channelId, ChannelCommand.ExecuteCommand(CMD_FULFILL_HTLC(3, incomingPayment.preimage, commit = true))),
                WrappedChannelCommand(channelId, ChannelCommand.ExecuteCommand(CMD_FULFILL_HTLC(5, incomingPayment.preimage, commit = true))),
                WrappedChannelCommand(channelId, ChannelCommand.ExecuteCommand(CMD_FULFILL_HTLC(6, incomingPayment.preimage, commit = true)))
            )
            assertEquals(expected, result.actions.toSet())
        }
    }

    @Test
    fun `reprocess duplicate htlcs`() = runSuspendTest {
        val (paymentHandler, incomingPayment, paymentSecret) = createFixture(defaultAmount)

        // We receive a first multipart htlc.
        val add1 = makeUpdateAddHtlc(3, randomBytes32(), paymentHandler, incomingPayment.paymentHash, makeMppPayload(defaultAmount / 2, defaultAmount, paymentSecret))
        val result1 = paymentHandler.process(add1, TestConstants.defaultBlockHeight)
        assertTrue { result1 is IncomingPaymentHandler.ProcessAddResult.Pending }
        assertTrue { result1.actions.isEmpty() }

        // This htlc is reprocessed (e.g. because the wallet restarted).
        val result1b = paymentHandler.process(add1, TestConstants.defaultBlockHeight)
        assertTrue { result1b is IncomingPaymentHandler.ProcessAddResult.Pending }
        assertTrue { result1b.actions.isEmpty() }

        // We receive the second multipart htlc.
        val add2 = makeUpdateAddHtlc(5, randomBytes32(), paymentHandler, incomingPayment.paymentHash, makeMppPayload(defaultAmount / 2, defaultAmount, paymentSecret))
        val result2 = paymentHandler.process(add2, TestConstants.defaultBlockHeight)
        assertTrue { result2 is IncomingPaymentHandler.ProcessAddResult.Accepted }
        result2 as IncomingPaymentHandler.ProcessAddResult.Accepted
        assertEquals(defaultAmount, result2.received.amount)
        val expected = setOf(
            WrappedChannelCommand(add1.channelId, ChannelCommand.ExecuteCommand(CMD_FULFILL_HTLC(add1.id, incomingPayment.preimage, commit = true))),
            WrappedChannelCommand(add2.channelId, ChannelCommand.ExecuteCommand(CMD_FULFILL_HTLC(add2.id, incomingPayment.preimage, commit = true)))
        )
        assertEquals(expected, result2.actions.toSet())

        // The second htlc is reprocessed (e.g. because our peer disconnected before we could send them the preimage).
        val result2b = paymentHandler.process(add2, TestConstants.defaultBlockHeight)
        assertTrue { result2b is IncomingPaymentHandler.ProcessAddResult.Accepted }
        result2b as IncomingPaymentHandler.ProcessAddResult.Accepted
        assertEquals(defaultAmount, result2b.received.amount)
        assertEquals(listOf(WrappedChannelCommand(add2.channelId, ChannelCommand.ExecuteCommand(CMD_FULFILL_HTLC(add2.id, incomingPayment.preimage, commit = true)))), result2b.actions)
    }

    @Test
    fun `reprocess failed htlcs`() = runSuspendTest {
        val (paymentHandler, incomingPayment, paymentSecret) = createFixture(defaultAmount)

        // We receive a first multipart htlc.
        val add = makeUpdateAddHtlc(1, randomBytes32(), paymentHandler, incomingPayment.paymentHash, makeMppPayload(defaultAmount / 2, defaultAmount, paymentSecret))
        val result1 = paymentHandler.process(add, TestConstants.defaultBlockHeight)
        assertTrue { result1 is IncomingPaymentHandler.ProcessAddResult.Pending }
        assertTrue { result1.actions.isEmpty() }

        // It expires after a while.
        val actions1 = paymentHandler.checkPaymentsTimeout(currentTimestampSeconds() + paymentHandler.nodeParams.multiPartPaymentExpirySeconds + 2)
        val addTimeout = ChannelCommand.ExecuteCommand(CMD_FAIL_HTLC(add.id, CMD_FAIL_HTLC.Reason.Failure(PaymentTimeout), commit = true))
        assertEquals(listOf(WrappedChannelCommand(add.channelId, addTimeout)), actions1)

        // For some reason, the channel was offline, didn't process the failure and retransmits the htlc.
        val result2 = paymentHandler.process(add, TestConstants.defaultBlockHeight)
        assertTrue { result2 is IncomingPaymentHandler.ProcessAddResult.Pending }
        assertTrue { result2.actions.isEmpty() }

        // It expires again.
        val actions2 = paymentHandler.checkPaymentsTimeout(currentTimestampSeconds() + paymentHandler.nodeParams.multiPartPaymentExpirySeconds + 2)
        assertEquals(listOf(WrappedChannelCommand(add.channelId, addTimeout)), actions2)

        // The channel was offline again, didn't process the failure and retransmits the htlc, but it is now close to its expiry.
        val currentBlockHeight = add.cltvExpiry.toLong().toInt() - 3
        val result3 = paymentHandler.process(add, currentBlockHeight)
        assertTrue { result3 is IncomingPaymentHandler.ProcessAddResult.Rejected }
        val addExpired = ChannelCommand.ExecuteCommand(CMD_FAIL_HTLC(add.id, CMD_FAIL_HTLC.Reason.Failure(IncorrectOrUnknownPaymentDetails(defaultAmount, currentBlockHeight.toLong())), commit = true))
        assertEquals(listOf(WrappedChannelCommand(add.channelId, addExpired)), result3.actions)
    }

    @Test
    fun `invoice expired`() = runSuspendTest {
        val paymentHandler = IncomingPaymentHandler(TestConstants.Bob.nodeParams, TestConstants.Bob.walletParams, InMemoryPaymentsDb())
        val (incomingPayment, paymentSecret) = makeIncomingPayment(
            payee = paymentHandler,
            amount = defaultAmount,
            timestamp = currentTimestampSeconds() - 3600 - 60, // over one hour ago
            expirySeconds = 3600 // one hour expiration
        )
        val add = makeUpdateAddHtlc(0, randomBytes32(), paymentHandler, incomingPayment.paymentHash, makeMppPayload(10_000.msat, defaultAmount, paymentSecret))
        val result = paymentHandler.process(add, TestConstants.defaultBlockHeight)

        assertTrue { result is IncomingPaymentHandler.ProcessAddResult.Rejected }
        val expected = ChannelCommand.ExecuteCommand(CMD_FAIL_HTLC(add.id, CMD_FAIL_HTLC.Reason.Failure(IncorrectOrUnknownPaymentDetails(defaultAmount, TestConstants.defaultBlockHeight.toLong())), commit = true))
        assertEquals(setOf(WrappedChannelCommand(add.channelId, expected)), result.actions.toSet())
    }

    @Test
    fun `invoice unknown`() = runSuspendTest {
        val (paymentHandler, _, _) = createFixture(defaultAmount)
        val add = makeUpdateAddHtlc(0, randomBytes32(), paymentHandler, randomBytes32(), makeMppPayload(defaultAmount, defaultAmount, randomBytes32()))
        val result = paymentHandler.process(add, TestConstants.defaultBlockHeight)

        assertTrue { result is IncomingPaymentHandler.ProcessAddResult.Rejected }
        val expected = ChannelCommand.ExecuteCommand(CMD_FAIL_HTLC(add.id, CMD_FAIL_HTLC.Reason.Failure(IncorrectOrUnknownPaymentDetails(defaultAmount, TestConstants.defaultBlockHeight.toLong())), commit = true))
        assertEquals(setOf(WrappedChannelCommand(add.channelId, expected)), result.actions.toSet())
    }

    @Test
    fun `invalid onion`() = runSuspendTest {
        val (paymentHandler, incomingPayment, _) = createFixture(defaultAmount)
        val cltvExpiry = CltvExpiryDelta(144).toCltvExpiry(TestConstants.defaultBlockHeight.toLong())
        val badOnion =
            OnionRoutingPacket(0, ByteVector("0x02eec7245d6b7d2ccb30380bfbe2a3648cd7a942653f5aa340edcea1f283686619"), Lightning.randomBytes(OnionRoutingPacket.PaymentPacketLength).toByteVector(), randomBytes32())
        val add = UpdateAddHtlc(randomBytes32(), 0, defaultAmount, incomingPayment.paymentHash, cltvExpiry, badOnion)
        val result = paymentHandler.process(add, TestConstants.defaultBlockHeight)

        assertTrue { result is IncomingPaymentHandler.ProcessAddResult.Rejected }
        // The current flow of error checking within the codebase would be:
        // 1. InvalidOnionKey
        // 2. InvalidOnionHmac
        // Since we used a valid pubKey, we should get an hmac failure.
        val expectedErr = InvalidOnionHmac(Sphinx.hash(badOnion))
        val expected = ChannelCommand.ExecuteCommand(CMD_FAIL_MALFORMED_HTLC(add.id, expectedErr.onionHash, expectedErr.code, commit = true))
        assertEquals(setOf(WrappedChannelCommand(add.channelId, expected)), result.actions.toSet())
    }

    @Test
    fun `invalid cltv expiry`() = runSuspendTest {
        val (paymentHandler, incomingPayment, paymentSecret) = createFixture(defaultAmount)
        val lowExpiry = CltvExpiryDelta(2)
        val add = makeUpdateAddHtlc(0, randomBytes32(), paymentHandler, incomingPayment.paymentHash, makeMppPayload(defaultAmount / 2, defaultAmount, paymentSecret, lowExpiry))
        val result = paymentHandler.process(add, TestConstants.defaultBlockHeight)

        assertTrue { result is IncomingPaymentHandler.ProcessAddResult.Rejected }
        val expected = ChannelCommand.ExecuteCommand(CMD_FAIL_HTLC(add.id, CMD_FAIL_HTLC.Reason.Failure(IncorrectOrUnknownPaymentDetails(defaultAmount, TestConstants.defaultBlockHeight.toLong())), commit = true))
        assertEquals(setOf(WrappedChannelCommand(add.channelId, expected)), result.actions.toSet())
    }

    @Test
    fun `amount too low or too high`() = runSuspendTest {
        val requestedAmount = 30_000.msat
        val (paymentHandler, incomingPayment, paymentSecret) = createFixture(requestedAmount)

        val payloads = listOf(
            makeMppPayload(requestedAmount / 3, requestedAmount / 3, paymentSecret), // too low
            makeMppPayload(requestedAmount * 3, requestedAmount * 3, paymentSecret) // too high
        )
        payloads.forEach { payload ->
            val add = makeUpdateAddHtlc(3, randomBytes32(), paymentHandler, incomingPayment.paymentHash, payload)
            val result = paymentHandler.process(add, TestConstants.defaultBlockHeight)
            assertTrue { result is IncomingPaymentHandler.ProcessAddResult.Rejected }
            val expected = ChannelCommand.ExecuteCommand(CMD_FAIL_HTLC(add.id, CMD_FAIL_HTLC.Reason.Failure(IncorrectOrUnknownPaymentDetails(payload.totalAmount, TestConstants.defaultBlockHeight.toLong())), commit = true))
            assertEquals(setOf(WrappedChannelCommand(add.channelId, expected)), result.actions.toSet())
        }
    }

    @Test
    fun `multipart total_amount mismatch`() = runSuspendTest {
        val channelId = randomBytes32()
        val (amount1, amount2, amount3) = listOf(25_000.msat, 40_000.msat, 30_000.msat)
        val totalAmount = amount1 + amount2 + amount3
        val (paymentHandler, incomingPayment, paymentSecret) = createFixture(totalAmount)

        // Step 1 of 2:
        // - Alice sends first multipart htlc to Bob
        // - Bob doesn't accept the MPP set yet
        run {
            val add = makeUpdateAddHtlc(1, channelId, paymentHandler, incomingPayment.paymentHash, makeMppPayload(amount1, totalAmount, paymentSecret))
            val result = paymentHandler.process(add, TestConstants.defaultBlockHeight)
            assertTrue { result is IncomingPaymentHandler.ProcessAddResult.Pending }
            assertTrue { result.actions.isEmpty() }
        }

        // Step 2 of 2:
        // - Alice sends second multipart htlc to Bob.
        // - Bob detects some shenanigans
        // - Bob rejects the entire MPP set
        run {
            val payload = makeMppPayload(amount2, totalAmount + MilliSatoshi(1), paymentSecret)
            val add = makeUpdateAddHtlc(2, channelId, paymentHandler, incomingPayment.paymentHash, payload)
            val result = paymentHandler.process(add, TestConstants.defaultBlockHeight)
            assertTrue { result is IncomingPaymentHandler.ProcessAddResult.Rejected }
            val expected = setOf(
                WrappedChannelCommand(
                    channelId,
                    ChannelCommand.ExecuteCommand(CMD_FAIL_HTLC(1, CMD_FAIL_HTLC.Reason.Failure(IncorrectOrUnknownPaymentDetails(totalAmount, TestConstants.defaultBlockHeight.toLong())), commit = true))
                ),
                WrappedChannelCommand(
                    channelId,
                    ChannelCommand.ExecuteCommand(CMD_FAIL_HTLC(2, CMD_FAIL_HTLC.Reason.Failure(IncorrectOrUnknownPaymentDetails(totalAmount + 1.msat, TestConstants.defaultBlockHeight.toLong())), commit = true))
                ),
            )
            assertEquals(expected, result.actions.toSet())
        }
    }

    @Test
    fun `invalid payment secret`() = runSuspendTest {
        val (amount1, amount2) = listOf(50_000.msat, 45_000.msat)
        val totalAmount = amount1 + amount2
        val (paymentHandler, incomingPayment, paymentSecret) = createFixture(totalAmount)

        // Step 1 of 2:
        // - Alice sends first multipart htlc to Bob
        // - Bob doesn't accept the MPP set yet
        run {
            val add = makeUpdateAddHtlc(1, randomBytes32(), paymentHandler, incomingPayment.paymentHash, makeMppPayload(amount1, totalAmount, paymentSecret))
            val result = paymentHandler.process(add, TestConstants.defaultBlockHeight)
            assertTrue { result is IncomingPaymentHandler.ProcessAddResult.Pending }
            assertTrue { result.actions.isEmpty() }
        }

        // Step 2 of 2:
        // - Someone sends an htlc with a different payment secret
        // - Bob rejects only that htlc, not Alice's valid one
        run {
            val payload = makeMppPayload(amount2, totalAmount, randomBytes32()) // <--- invalid payment secret
            val add = makeUpdateAddHtlc(1, randomBytes32(), paymentHandler, incomingPayment.paymentHash, payload)
            val result = paymentHandler.process(add, TestConstants.defaultBlockHeight)
            assertTrue { result is IncomingPaymentHandler.ProcessAddResult.Rejected }
            val expected = ChannelCommand.ExecuteCommand(CMD_FAIL_HTLC(add.id, CMD_FAIL_HTLC.Reason.Failure(IncorrectOrUnknownPaymentDetails(totalAmount, TestConstants.defaultBlockHeight.toLong())), commit = true))
            assertEquals(setOf(WrappedChannelCommand(add.channelId, expected)), result.actions.toSet())
        }
    }

    @Test
    fun `mpp timeout`() = runSuspendTest {
        val startTime = currentTimestampSeconds()
        val channelId = randomBytes32()
        val (paymentHandler, incomingPayment, paymentSecret) = createFixture(defaultAmount)

        // Step 1 of 3:
        // - Alice sends (unfinished) multipart htlcs to Bob.
        run {
            listOf(1L, 2L).forEach { id ->
                val add = makeUpdateAddHtlc(id, channelId, paymentHandler, incomingPayment.paymentHash, makeMppPayload(10_000.msat, defaultAmount, paymentSecret))
                val result = paymentHandler.process(add, TestConstants.defaultBlockHeight)
                assertTrue { result is IncomingPaymentHandler.ProcessAddResult.Pending }
                assertTrue { result.actions.isEmpty() }
            }
        }

        // Step 2 of 3:
        // - don't expire the multipart htlcs too soon.
        run {
            val currentTimestampSeconds = startTime + paymentHandler.nodeParams.multiPartPaymentExpirySeconds - 2
            val actions = paymentHandler.checkPaymentsTimeout(currentTimestampSeconds)
            assertTrue { actions.isEmpty() }
        }

        // Step 3 of 3:
        // - expire the htlc-set after configured expiration.
        run {
            val currentTimestampSeconds = startTime + paymentHandler.nodeParams.multiPartPaymentExpirySeconds + 2
            val actions = paymentHandler.checkPaymentsTimeout(currentTimestampSeconds)
            val expected = setOf(
                WrappedChannelCommand(channelId, ChannelCommand.ExecuteCommand(CMD_FAIL_HTLC(1, CMD_FAIL_HTLC.Reason.Failure(PaymentTimeout), commit = true))),
                WrappedChannelCommand(channelId, ChannelCommand.ExecuteCommand(CMD_FAIL_HTLC(2, CMD_FAIL_HTLC.Reason.Failure(PaymentTimeout), commit = true))),
            )
            assertEquals(expected, actions.toSet())
        }
    }

    @Test
    fun `mpp timeout then success`() = runSuspendTest {
        val startTime = currentTimestampSeconds()
        val channelId = randomBytes32()
        val (amount1, amount2) = listOf(60_000.msat, 30_000.msat)
        val totalAmount = amount1 + amount2
        val (paymentHandler, incomingPayment, paymentSecret) = createFixture(totalAmount)

        // Step 1 of 4:
        // - Alice sends single (unfinished) multipart htlc to Bob.
        run {
            val add = makeUpdateAddHtlc(1, channelId, paymentHandler, incomingPayment.paymentHash, makeMppPayload(amount1, totalAmount, paymentSecret))
            val result = paymentHandler.process(add, TestConstants.defaultBlockHeight)
            assertTrue { result is IncomingPaymentHandler.ProcessAddResult.Pending }
            assertTrue { result.actions.isEmpty() }
        }

        // Step 2 of 4:
        // - the MPP set times out
        run {
            val currentTimestampSeconds = startTime + paymentHandler.nodeParams.multiPartPaymentExpirySeconds + 2
            val actions = paymentHandler.checkPaymentsTimeout(currentTimestampSeconds)
            val expected = WrappedChannelCommand(channelId, ChannelCommand.ExecuteCommand(CMD_FAIL_HTLC(1, CMD_FAIL_HTLC.Reason.Failure(PaymentTimeout), commit = true)))
            assertEquals(setOf(expected), actions.toSet())
        }

        // Step 3 of 4:
        // - Alice tries again, and sends another single (unfinished) multipart htlc to Bob.
        run {
            val add = makeUpdateAddHtlc(3, channelId, paymentHandler, incomingPayment.paymentHash, makeMppPayload(amount1, totalAmount, paymentSecret))
            val result = paymentHandler.process(add, TestConstants.defaultBlockHeight)
            assertTrue { result is IncomingPaymentHandler.ProcessAddResult.Pending }
            assertTrue { result.actions.isEmpty() }
        }

        // Step 4 of 4:
        // - Alice sends second and last part of mpp
        // - Bob accepts htlc set
        run {
            val add = makeUpdateAddHtlc(4, channelId, paymentHandler, incomingPayment.paymentHash, makeMppPayload(amount2, totalAmount, paymentSecret))
            val result = paymentHandler.process(add, TestConstants.defaultBlockHeight)
            assertTrue { result is IncomingPaymentHandler.ProcessAddResult.Accepted }
            val expected = setOf(
                WrappedChannelCommand(channelId, ChannelCommand.ExecuteCommand(CMD_FULFILL_HTLC(3, incomingPayment.preimage, commit = true))),
                WrappedChannelCommand(channelId, ChannelCommand.ExecuteCommand(CMD_FULFILL_HTLC(4, incomingPayment.preimage, commit = true))),
            )
            assertEquals(expected, result.actions.toSet())
        }
    }

    @Test
    fun `fulfill locally replayed htlcs`() = runSuspendTest {
        val (amount1, amount2) = listOf(12_000.msat, 50_000.msat)
        val (channelId1, channelId2) = listOf(randomBytes32(), randomBytes32())
        val totalAmount = amount1 + amount2
        val (paymentHandler, incomingPayment, paymentSecret) = createFixture(totalAmount)
        val (htlc1, htlc2) = listOf(
            makeUpdateAddHtlc(876, channelId1, paymentHandler, incomingPayment.paymentHash, makeMppPayload(amount1, totalAmount, paymentSecret)),
            makeUpdateAddHtlc(2, channelId2, paymentHandler, incomingPayment.paymentHash, makeMppPayload(amount2, totalAmount, paymentSecret)),
        )

        // Step 1 of 2:
        // - Alice receives complete mpp set
        run {
            val result1 = paymentHandler.process(htlc1, TestConstants.defaultBlockHeight)
            assertTrue { result1 is IncomingPaymentHandler.ProcessAddResult.Pending }
            assertTrue { result1.actions.isEmpty() }

            val result2 = paymentHandler.process(htlc2, TestConstants.defaultBlockHeight)
            assertTrue { result2 is IncomingPaymentHandler.ProcessAddResult.Accepted }

            val expected = setOf(
                WrappedChannelCommand(channelId1, ChannelCommand.ExecuteCommand(CMD_FULFILL_HTLC(htlc1.id, incomingPayment.preimage, commit = true))),
                WrappedChannelCommand(channelId2, ChannelCommand.ExecuteCommand(CMD_FULFILL_HTLC(htlc2.id, incomingPayment.preimage, commit = true))),
            )
            assertEquals(expected, result2.actions.toSet())
        }

        // Step 2 of 2:
        // - Alice receives local replay of htlc1 for the invoice she already completed. Must be fulfilled.
        run {
            val result = paymentHandler.process(htlc1, TestConstants.defaultBlockHeight)
            assertTrue { result is IncomingPaymentHandler.ProcessAddResult.Accepted }
            val expected = WrappedChannelCommand(channelId1, ChannelCommand.ExecuteCommand(CMD_FULFILL_HTLC(htlc1.id, incomingPayment.preimage, commit = true)))
            assertEquals(setOf(expected), result.actions.toSet())
        }
    }

    @Test
    fun `reject htlcs for already paid invoices`() = runSuspendTest {
        val (amount1, amount2) = listOf(60_000.msat, 30_000.msat)
        val (channelId1, channelId2) = listOf(randomBytes32(), randomBytes32())
        val totalAmount = amount1 + amount2
        val (paymentHandler, incomingPayment, paymentSecret) = createFixture(totalAmount)
        val (htlc1, htlc2) = listOf(
            makeUpdateAddHtlc(8, channelId1, paymentHandler, incomingPayment.paymentHash, makeMppPayload(amount1, totalAmount, paymentSecret)),
            makeUpdateAddHtlc(4, channelId2, paymentHandler, incomingPayment.paymentHash, makeMppPayload(amount2, totalAmount, paymentSecret)),
        )

        // Step 1 of 2:
        // - Alice receives complete mpp set
        run {
            val result1 = paymentHandler.process(htlc1, TestConstants.defaultBlockHeight)
            assertTrue { result1 is IncomingPaymentHandler.ProcessAddResult.Pending }
            assertTrue { result1.actions.isEmpty() }

            val result2 = paymentHandler.process(htlc2, TestConstants.defaultBlockHeight)
            assertTrue { result2 is IncomingPaymentHandler.ProcessAddResult.Accepted }

            val expected = setOf(
                WrappedChannelCommand(channelId1, ChannelCommand.ExecuteCommand(CMD_FULFILL_HTLC(htlc1.id, incomingPayment.preimage, commit = true))),
                WrappedChannelCommand(channelId2, ChannelCommand.ExecuteCommand(CMD_FULFILL_HTLC(htlc2.id, incomingPayment.preimage, commit = true))),
            )
            assertEquals(expected, result2.actions.toSet())
        }

        // Step 2 of 2:
        // - Alice receives an additional htlc (with new id) on channel1 for the invoice she already completed. Must be rejected.
        run {
            val add = htlc1.copy(id = 3)
            val result = paymentHandler.process(add, TestConstants.defaultBlockHeight)
            assertTrue { result is IncomingPaymentHandler.ProcessAddResult.Rejected }
            val expected = WrappedChannelCommand(
                channelId1, ChannelCommand.ExecuteCommand(
                    CMD_FAIL_HTLC(
                        3, CMD_FAIL_HTLC.Reason.Failure(
                            IncorrectOrUnknownPaymentDetails(totalAmount, TestConstants.defaultBlockHeight.toLong())
                        ), commit = true
                    )
                )
            )
            assertEquals(setOf(expected), result.actions.toSet())
        }

        // - Alice receives an htlc2 (but on a new channel) for the invoice she already completed. Must be rejected.
        run {
            val channelId3 = randomBytes32()
            val add = htlc2.copy(channelId = channelId3)
            val result = paymentHandler.process(add, TestConstants.defaultBlockHeight)
            assertTrue { result is IncomingPaymentHandler.ProcessAddResult.Rejected }
            val expected = WrappedChannelCommand(
                channelId3, ChannelCommand.ExecuteCommand(
                    CMD_FAIL_HTLC(
                        htlc2.id, CMD_FAIL_HTLC.Reason.Failure(
                            IncorrectOrUnknownPaymentDetails(totalAmount, TestConstants.defaultBlockHeight.toLong())
                        ), commit = true
                    )
                )
            )
            assertEquals(setOf(expected), result.actions.toSet())
        }
    }

    @Test
    fun `purge expired incoming payments`() = runSuspendTest {
        val paymentHandler = IncomingPaymentHandler(TestConstants.Bob.nodeParams, TestConstants.Bob.walletParams, InMemoryPaymentsDb())

        // create incoming payment that has expired and not been paid
        val expiredInvoice = paymentHandler.createInvoice(randomBytes32(), defaultAmount, "expired", listOf(), expirySeconds = 3600,
            timestampSeconds = 1)

        // create incoming payment that has expired and been paid
        delay(100)
        val paidInvoice = paymentHandler.createInvoice(defaultPreimage, defaultAmount, "paid", listOf(), expirySeconds = 3600,
            timestampSeconds = 100)
        paymentHandler.db.receivePayment(paidInvoice.paymentHash, receivedWith = setOf(IncomingPayment.ReceivedWith.NewChannel(id = UUID.randomUUID(), amount = 15_000_000.msat, fees = 1_000_000.msat, null)),
            receivedAt = 101) // simulate incoming payment being paid before it expired

        // create unexpired payment
        delay(100)
        val unexpiredInvoice = paymentHandler.createInvoice(randomBytes32(), defaultAmount, "unexpired", listOf(), expirySeconds = 3600)

        val unexpiredPayment = paymentHandler.db.getIncomingPayment(unexpiredInvoice.paymentHash)!!
        val paidPayment = paymentHandler.db.getIncomingPayment(paidInvoice.paymentHash)!!
        val expiredPayment = paymentHandler.db.getIncomingPayment(expiredInvoice.paymentHash)!!

        assertEquals(paymentHandler.db.listIncomingPayments(5, 0, setOf(PaymentTypeFilter.Normal)), listOf(unexpiredPayment, paidPayment, expiredPayment))
        assertEquals(paymentHandler.db.listExpiredPayments(), listOf(expiredPayment))
        assertEquals(paymentHandler.purgeExpiredPayments(), 1)
        assertEquals(paymentHandler.db.listExpiredPayments(), emptyList())
        assertEquals(paymentHandler.db.listIncomingPayments(5, 0, setOf(PaymentTypeFilter.Normal)), listOf(unexpiredPayment, paidPayment))
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
                timestampSeconds = 0,
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

        private fun makeCmdAddHtlc(destination: PublicKey, paymentHash: ByteVector32, finalPayload: PaymentOnion.FinalPayload): CMD_ADD_HTLC {
            return OutgoingPaymentPacket.buildCommand(UUID.randomUUID(), paymentHash, channelHops(destination), finalPayload).first.copy(commit = true)
        }

        private fun makeUpdateAddHtlc(id: Long, channelId: ByteVector32, destination: IncomingPaymentHandler, paymentHash: ByteVector32, finalPayload: PaymentOnion.FinalPayload): UpdateAddHtlc {
            val (_, _, packetAndSecrets) = OutgoingPaymentPacket.buildPacket(paymentHash, channelHops(destination.nodeParams.nodeId), finalPayload, OnionRoutingPacket.PaymentPacketLength)
            return UpdateAddHtlc(channelId, id, finalPayload.amount, paymentHash, finalPayload.expiry, packetAndSecrets.packet)
        }

        private fun makeMppPayload(
            amount: MilliSatoshi,
            totalAmount: MilliSatoshi,
            paymentSecret: ByteVector32,
            cltvExpiryDelta: CltvExpiryDelta = CltvExpiryDelta(144),
            currentBlockHeight: Int = TestConstants.defaultBlockHeight
        ): PaymentOnion.FinalPayload {
            val expiry = cltvExpiryDelta.toCltvExpiry(currentBlockHeight.toLong())
            return PaymentOnion.FinalPayload.createMultiPartPayload(amount, totalAmount, expiry, paymentSecret, null)
        }

        private fun makePayToOpenRequest(incomingPayment: IncomingPayment, finalPayload: PaymentOnion.FinalPayload, payToOpenMinAmount: MilliSatoshi = 10_000.msat): PayToOpenRequest {
            return PayToOpenRequest(
                chainHash = ByteVector32.Zeroes,
                fundingSatoshis = 100_000.sat,
                amountMsat = finalPayload.amount,
                payToOpenMinAmountMsat = payToOpenMinAmount,
                payToOpenFeeSatoshis = finalPayload.amount.truncateToSatoshi() * 0.1, // 10%
                paymentHash = incomingPayment.paymentHash,
                expireAt = Long.MAX_VALUE,
                finalPacket = OutgoingPaymentPacket.buildPacket(
                    paymentHash = incomingPayment.paymentHash,
                    hops = channelHops(TestConstants.Bob.nodeParams.nodeId),
                    finalPayload = finalPayload,
                    payloadLength = OnionRoutingPacket.PaymentPacketLength
                ).third.packet
            )
        }

        private suspend fun makeIncomingPayment(payee: IncomingPaymentHandler, amount: MilliSatoshi?, expirySeconds: Long? = null, timestamp: Long = currentTimestampSeconds()): Pair<IncomingPayment, ByteVector32> {
            val paymentRequest = payee.createInvoice(defaultPreimage, amount, "unit test", listOf(), expirySeconds, timestamp)
            assertNotNull(paymentRequest.paymentMetadata)
            return Pair(payee.db.getIncomingPayment(paymentRequest.paymentHash)!!, paymentRequest.paymentSecret)
        }

        private fun makeReceivedWithNewChannel(payToOpen: PayToOpenRequest, uuid: UUID, feeRatio: Double = 0.1): IncomingPayment.ReceivedWith.NewChannel {
            val fee = payToOpen.amountMsat * feeRatio
            return IncomingPayment.ReceivedWith.NewChannel(id = uuid, amount = payToOpen.amountMsat - fee, fees = fee, channelId = null)
        }

        private suspend fun checkDbPayment(incomingPayment: IncomingPayment, db: IncomingPaymentsDb) {
            val dbPayment = db.getIncomingPayment(incomingPayment.paymentHash)!!
            assertEquals(incomingPayment.preimage, dbPayment.preimage)
            assertEquals(incomingPayment.paymentHash, dbPayment.paymentHash)
            assertEquals(incomingPayment.origin, dbPayment.origin)
            assertEquals(incomingPayment.amount, dbPayment.amount)
            assertEquals(incomingPayment.received?.receivedWith, dbPayment.received?.receivedWith)
        }

        private suspend fun createFixture(invoiceAmount: MilliSatoshi?): Triple<IncomingPaymentHandler, IncomingPayment, ByteVector32> {
            val paymentHandler = IncomingPaymentHandler(TestConstants.Bob.nodeParams.copy(minFundingSatoshis = 10.sat), TestConstants.Bob.walletParams, InMemoryPaymentsDb())
            val (incomingPayment, paymentSecret) = makeIncomingPayment(paymentHandler, invoiceAmount)
            return Triple(paymentHandler, incomingPayment, paymentSecret)
        }
    }
}
