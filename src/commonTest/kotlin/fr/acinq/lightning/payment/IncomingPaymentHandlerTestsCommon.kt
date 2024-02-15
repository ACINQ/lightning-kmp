package fr.acinq.lightning.payment

import fr.acinq.bitcoin.*
import fr.acinq.bitcoin.utils.Either
import fr.acinq.lightning.CltvExpiryDelta
import fr.acinq.lightning.Lightning
import fr.acinq.lightning.Lightning.randomBytes32
import fr.acinq.lightning.MilliSatoshi
import fr.acinq.lightning.ShortChannelId
import fr.acinq.lightning.channel.*
import fr.acinq.lightning.crypto.sphinx.Sphinx
import fr.acinq.lightning.db.InMemoryPaymentsDb
import fr.acinq.lightning.db.IncomingPayment
import fr.acinq.lightning.db.IncomingPaymentsDb
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
import kotlin.time.Duration.Companion.milliseconds

class IncomingPaymentHandlerTestsCommon : LightningTestSuite() {

    @Test
    fun `add HTLC to channel commitments`() {
        var (alice, bob) = TestsHelper.reachNormal()
        val cmdAddHtlc = makeCmdAddHtlc(
            bob.staticParams.nodeParams.nodeId, defaultPaymentHash,
            makeMppPayload(100_000.msat, 150_000.msat, randomBytes32(), currentBlockHeight = alice.currentBlockHeight)
        )

        // Step 1: alice ---> update_add_htlc ---> bob

        var processResult = alice.processSameState(cmdAddHtlc)
        alice = processResult.first
        var actions = processResult.second
        assertEquals(2, actions.size)
        val add = actions.findOutgoingMessage<UpdateAddHtlc>()
        val aliceCmdSign = actions.findCommand<ChannelCommand.Commitment.Sign>()

        processResult = bob.processSameState(ChannelCommand.MessageReceived(add))
        bob = processResult.first
        actions = processResult.second
        assertTrue { actions.filterIsInstance<ChannelAction.Message.Send>().isEmpty() }

        assertTrue { alice.commitments.changes.localChanges.proposed.size == 1 }
        assertTrue { alice.commitments.changes.localChanges.signed.isEmpty() }
        assertTrue { alice.commitments.changes.localChanges.acked.isEmpty() }

        assertTrue { bob.commitments.changes.remoteChanges.proposed.size == 1 }
        assertTrue { bob.commitments.changes.remoteChanges.acked.isEmpty() }
        assertTrue { bob.commitments.changes.remoteChanges.signed.isEmpty() }

        // Step 2: alice ---> commitment_signed ---> bob

        processResult = alice.processSameState(aliceCmdSign)
        alice = processResult.first
        actions = processResult.second
        val aliceSig = actions.findOutgoingMessage<CommitSig>()

        processResult = bob.processSameState(ChannelCommand.MessageReceived(aliceSig))
        bob = processResult.first
        actions = processResult.second
        val bobRev = actions.findOutgoingMessage<RevokeAndAck>()
        val bobCmdSign = actions.findCommand<ChannelCommand.Commitment.Sign>()

        assertTrue { alice.commitments.changes.localChanges.proposed.isEmpty() }
        assertTrue { alice.commitments.changes.localChanges.signed.size == 1 }
        assertTrue { alice.commitments.changes.localChanges.acked.isEmpty() }

        assertTrue { bob.commitments.changes.remoteChanges.proposed.isEmpty() }
        assertTrue { bob.commitments.changes.remoteChanges.acked.size == 1 }
        assertTrue { bob.commitments.changes.remoteChanges.signed.isEmpty() }

        // Step 3: alice <--- revoke_and_ack <--- bob

        processResult = alice.processSameState(ChannelCommand.MessageReceived(bobRev))
        alice = processResult.first
        actions = processResult.second
        assertTrue { actions.filterIsInstance<ChannelAction.Message.Send>().isEmpty() }

        assertTrue { alice.commitments.changes.localChanges.proposed.isEmpty() }
        assertTrue { alice.commitments.changes.localChanges.signed.isEmpty() }
        assertTrue { alice.commitments.changes.localChanges.acked.size == 1 }

        assertTrue { bob.commitments.changes.remoteChanges.proposed.isEmpty() }
        assertTrue { bob.commitments.changes.remoteChanges.acked.size == 1 }
        assertTrue { bob.commitments.changes.remoteChanges.signed.isEmpty() }

        // Step 4: alice <--- commitment_signed <--- bob

        processResult = bob.processSameState(bobCmdSign)
        bob = processResult.first
        actions = processResult.second
        val bobSig = actions.findOutgoingMessage<CommitSig>()

        processResult = alice.processSameState(ChannelCommand.MessageReceived(bobSig))
        alice = processResult.first
        actions = processResult.second
        val aliceRev = actions.findOutgoingMessage<RevokeAndAck>()

        assertTrue { alice.commitments.changes.localChanges.proposed.isEmpty() }
        assertTrue { alice.commitments.changes.localChanges.signed.isEmpty() }
        assertTrue { alice.commitments.changes.localChanges.acked.isEmpty() }

        assertTrue { bob.commitments.changes.remoteChanges.proposed.isEmpty() }
        assertTrue { bob.commitments.changes.remoteChanges.acked.isEmpty() }
        assertTrue { bob.commitments.changes.remoteChanges.signed.size == 1 }

        // Step 5: alice ---> revoke_and_ack ---> bob

        processResult = bob.processSameState(ChannelCommand.MessageReceived(aliceRev))
        bob = processResult.first
        actions = processResult.second
        assertTrue { actions.filterIsInstance<ChannelAction.Message.Send>().isEmpty() }
        assertTrue { actions.filterIsInstance<ChannelAction.ProcessIncomingHtlc>().size == 1 }

        assertTrue { alice.commitments.changes.localChanges.proposed.isEmpty() }
        assertTrue { alice.commitments.changes.localChanges.signed.isEmpty() }
        assertTrue { alice.commitments.changes.localChanges.acked.isEmpty() }

        assertTrue { bob.commitments.changes.remoteChanges.proposed.isEmpty() }
        assertTrue { bob.commitments.changes.remoteChanges.acked.isEmpty() }
        assertTrue { bob.commitments.changes.remoteChanges.signed.isEmpty() }
    }

    @Test
    fun `receive multipart payment with single HTLC`() = runSuspendTest {
        val (paymentHandler, incomingPayment, paymentSecret) = createFixture(defaultAmount)
        checkDbPayment(incomingPayment, paymentHandler.db)
        val channelId = randomBytes32()
        val add = makeUpdateAddHtlc(12, channelId, paymentHandler, incomingPayment.paymentHash, makeMppPayload(defaultAmount, defaultAmount, paymentSecret))
        val result = paymentHandler.process(add, TestConstants.defaultBlockHeight)

        assertIs<IncomingPaymentHandler.ProcessAddResult.Accepted>(result)
        val expected = ChannelCommand.Htlc.Settlement.Fulfill(add.id, incomingPayment.preimage, commit = true)
        assertEquals(setOf(WrappedChannelCommand(add.channelId, expected)), result.actions.toSet())

        assertEquals(result.incomingPayment.received, result.received)
        assertEquals(defaultAmount, result.received.amount)
        assertEquals(listOf(IncomingPayment.ReceivedWith.LightningPayment(amount = defaultAmount, channelId = channelId, htlcId = 12)), result.received.receivedWith)

        checkDbPayment(result.incomingPayment, paymentHandler.db)
    }

    @Test
    fun `receive pay-to-open payment with single HTLC`() = runSuspendTest {
        val (paymentHandler, incomingPayment, paymentSecret) = createFixture(defaultAmount)
        checkDbPayment(incomingPayment, paymentHandler.db)
        val payToOpenRequest = makePayToOpenRequest(incomingPayment, makeMppPayload(defaultAmount, defaultAmount, paymentSecret))
        val result = paymentHandler.process(payToOpenRequest, TestConstants.defaultBlockHeight)

        assertIs<IncomingPaymentHandler.ProcessAddResult.Accepted>(result)
        val expected = PayToOpenResponseCommand(PayToOpenResponse(payToOpenRequest.chainHash, payToOpenRequest.paymentHash, PayToOpenResponse.Result.Success(incomingPayment.preimage)))
        assertEquals(setOf(expected), result.actions.toSet())

        // the pay-to-open part is not yet inserted in db
        assertTrue(result.received.receivedWith.isEmpty())
        assertEquals(0.msat, result.received.amount)
        assertEquals(0.msat, result.received.fees)

        // later on, a channel is created
        val channelId = randomBytes32()
        val amountOrigin = ChannelAction.Storage.StoreIncomingPayment.ViaNewChannel(
            amount = payToOpenRequest.amountMsat,
            serviceFee = payToOpenRequest.payToOpenFeeSatoshis.toMilliSatoshi(),
            miningFee = 0.sat,
            localInputs = emptySet(),
            txId = TxId(randomBytes32()),
            origin = Origin.PayToOpenOrigin(amount = payToOpenRequest.amountMsat, paymentHash = payToOpenRequest.paymentHash, serviceFee = 0.msat, miningFee = payToOpenRequest.payToOpenFeeSatoshis)
        )
        paymentHandler.process(channelId, amountOrigin)
        paymentHandler.db.getIncomingPayment(payToOpenRequest.paymentHash).also { dbPayment ->
            assertNotNull(dbPayment)
            assertIs<IncomingPayment.Origin.Invoice>(dbPayment.origin)
            assertNotNull(dbPayment.received)
            assertEquals(1, dbPayment.received!!.receivedWith.size)
            dbPayment.received!!.receivedWith.first().also { part ->
                assertIs<IncomingPayment.ReceivedWith.NewChannel>(part)
                assertEquals(amountOrigin.amount, part.amount)
                assertEquals(amountOrigin.serviceFee, part.serviceFee)
                assertEquals(amountOrigin.miningFee, part.miningFee)
                assertEquals(channelId, part.channelId)
                assertNull(part.confirmedAt)
            }
            assertEquals(amountOrigin.amount, dbPayment.received?.amount)
            assertEquals(amountOrigin.serviceFee, dbPayment.received?.fees)
        }

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

        // pay-to-open parts are not yet inserted in db
        assertTrue(result2.received.receivedWith.isEmpty())
    }

    @Test
    fun `receive pay-to-open payment with two unevenly-split HTLCs`() = runSuspendTest {
        val (paymentHandler, incomingPayment, paymentSecret) = createFixture(defaultAmount)
        checkDbPayment(incomingPayment, paymentHandler.db)
        val payToOpenRequest1 = makePayToOpenRequest(incomingPayment, makeMppPayload(40_000.msat, defaultAmount, paymentSecret))
        val payToOpenRequest2 = makePayToOpenRequest(incomingPayment, makeMppPayload(60_000.msat, defaultAmount, paymentSecret))

        val result1 = paymentHandler.process(payToOpenRequest1, TestConstants.defaultBlockHeight)
        assertIs<IncomingPaymentHandler.ProcessAddResult.Pending>(result1)
        assertEquals(emptyList(), result1.actions)
        val result2 = paymentHandler.process(payToOpenRequest2, TestConstants.defaultBlockHeight)
        assertIs<IncomingPaymentHandler.ProcessAddResult.Accepted>(result2)
        val payToOpenResponse = PayToOpenResponseCommand(PayToOpenResponse(payToOpenRequest1.chainHash, payToOpenRequest1.paymentHash, PayToOpenResponse.Result.Success(incomingPayment.preimage)))
        assertEquals(listOf(payToOpenResponse), result2.actions)

        assertEquals(0.msat, result2.received.amount)
        assertEquals(0.msat, result2.received.fees)

        checkDbPayment(result2.incomingPayment, paymentHandler.db)
    }

    @Test
    fun `receive pay-to-open payment with an unknown payment hash`() = runSuspendTest {
        val (paymentHandler, _, _) = createFixture(defaultAmount)
        val payToOpenRequest = PayToOpenRequest(
            chainHash = BlockHash(ByteVector32.Zeroes),
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

        assertIs<IncomingPaymentHandler.ProcessAddResult.Rejected>(result)
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
                        ChannelCommand.Htlc.Settlement.Fail.Reason.Failure(IncorrectOrUnknownPaymentDetails(payToOpenRequest.amountMsat, TestConstants.defaultBlockHeight.toLong()))
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

        assertIs<IncomingPaymentHandler.ProcessAddResult.Rejected>(result)
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
                        ChannelCommand.Htlc.Settlement.Fail.Reason.Failure(IncorrectOrUnknownPaymentDetails(payToOpenRequest.amountMsat, TestConstants.defaultBlockHeight.toLong()))
                    ).right!!
                )
            )
        )
        assertEquals(setOf(expected), result.actions.toSet())
    }

    @Test
    fun `receive pay-to-open payment with a fee too high`() = runSuspendTest {
        val (paymentHandler, incomingPayment, paymentSecret) = createFixture(defaultAmount)
        val payToOpenRequest = makePayToOpenRequest(incomingPayment, makeMppPayload(defaultAmount, defaultAmount, paymentSecret)).copy(payToOpenFeeSatoshis = 2_000.sat)
        val result = paymentHandler.process(payToOpenRequest, TestConstants.defaultBlockHeight)

        assertIs<IncomingPaymentHandler.ProcessAddResult.Rejected>(result)
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
                        ChannelCommand.Htlc.Settlement.Fail.Reason.Failure(TemporaryNodeFailure)
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
            chainHash = BlockHash(ByteVector32.Zeroes),
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

        assertIs<IncomingPaymentHandler.ProcessAddResult.Rejected>(result)
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
                        ChannelCommand.Htlc.Settlement.Fail.Reason.Failure(IncorrectOrUnknownPaymentDetails(payToOpenRequest.amountMsat, TestConstants.defaultBlockHeight.toLong()))
                    ).right!!
                )
            )
        )
        assertEquals(setOf(expected), result.actions.toSet())
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
            assertIs<IncomingPaymentHandler.ProcessAddResult.Pending>(result)
            assertNull(result.incomingPayment.received)
            assertTrue(result.actions.isEmpty())
        }

        // Step 2 of 2:
        // - Alice sends second multipart htlc to Bob
        // - Bob now accepts the MPP set
        run {
            val add = makeUpdateAddHtlc(1, channelId, paymentHandler, incomingPayment.paymentHash, makeMppPayload(amount2, totalAmount, paymentSecret))
            val result = paymentHandler.process(add, TestConstants.defaultBlockHeight)
            assertIs<IncomingPaymentHandler.ProcessAddResult.Accepted>(result)
            val (expectedActions, expectedReceivedWith) = setOf(
                // @formatter:off
                WrappedChannelCommand(channelId, ChannelCommand.Htlc.Settlement.Fulfill(0, defaultPreimage, commit = true)) to IncomingPayment.ReceivedWith.LightningPayment(amount1, channelId, 0),
                WrappedChannelCommand(channelId, ChannelCommand.Htlc.Settlement.Fulfill(1, defaultPreimage, commit = true)) to IncomingPayment.ReceivedWith.LightningPayment(amount2, channelId, 1),
                // @formatter:on
            ).unzip()
            assertEquals(expectedActions.toSet(), result.actions.toSet())
            assertEquals(totalAmount, result.received.amount)
            assertEquals(expectedReceivedWith, result.received.receivedWith)
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
            assertIs<IncomingPaymentHandler.ProcessAddResult.Pending>(result)
            assertTrue(result.actions.isEmpty())
        }

        // Step 2 of 2:
        // - Alice sends second multipart htlc to Bob
        // - Bob now accepts the MPP set
        run {
            val add = makeUpdateAddHtlc(5, channelId2, paymentHandler, incomingPayment.paymentHash, makeMppPayload(amount2, totalAmount, paymentSecret))
            val result = paymentHandler.process(add, TestConstants.defaultBlockHeight)
            assertIs<IncomingPaymentHandler.ProcessAddResult.Accepted>(result)
            val (expectedActions, expectedReceivedWith) = setOf(
                // @formatter:off
                WrappedChannelCommand(channelId1, ChannelCommand.Htlc.Settlement.Fulfill(7, defaultPreimage, commit = true)) to IncomingPayment.ReceivedWith.LightningPayment(amount1, channelId1, 7),
                WrappedChannelCommand(channelId2, ChannelCommand.Htlc.Settlement.Fulfill(5, defaultPreimage, commit = true)) to IncomingPayment.ReceivedWith.LightningPayment(amount2, channelId2, 5),
                // @formatter:on
            ).unzip()
            assertEquals(expectedActions.toSet(), result.actions.toSet())
            assertEquals(totalAmount, result.received.amount)
            assertEquals(expectedReceivedWith, result.received.receivedWith)
            checkDbPayment(result.incomingPayment, paymentHandler.db)
        }
    }

    @Test
    fun `receive multipart payment after disconnection`() = runSuspendTest {
        // Write exactly the scenario that happened in the witnessed issue.
        // Modify purgePayToOpenRequests to purge all pending HTLCs *for the given disconnected node* (to support future multi-node)
        val channelId = randomBytes32()
        val (amount1, amount2) = Pair(75_000.msat, 75_000.msat)
        val totalAmount = amount1 + amount2
        val (paymentHandler, incomingPayment, paymentSecret) = createFixture(totalAmount)

        // Step 1: Alice sends first multipart htlc to Bob.
        val add1 = run {
            val add = makeUpdateAddHtlc(0, channelId, paymentHandler, incomingPayment.paymentHash, makeMppPayload(amount1, totalAmount, paymentSecret))
            val result = paymentHandler.process(add, TestConstants.defaultBlockHeight)
            assertIs<IncomingPaymentHandler.ProcessAddResult.Pending>(result)
            assertNull(result.incomingPayment.received)
            assertTrue(result.actions.isEmpty())
            add
        }

        // Step 2: Bob disconnects, and cleans up pending HTLCs.
        paymentHandler.purgePendingPayments()

        // Step 3: on reconnection, the HTLC from step 1 is processed again.
        run {
            val result = paymentHandler.process(add1, TestConstants.defaultBlockHeight)
            assertIs<IncomingPaymentHandler.ProcessAddResult.Pending>(result)
            assertNull(result.incomingPayment.received)
            assertTrue(result.actions.isEmpty())
        }

        // Step 4: Alice sends second multipart htlc to Bob.
        run {
            val add = makeUpdateAddHtlc(1, channelId, paymentHandler, incomingPayment.paymentHash, makeMppPayload(amount2, totalAmount, paymentSecret))
            val result = paymentHandler.process(add, TestConstants.defaultBlockHeight)
            assertIs<IncomingPaymentHandler.ProcessAddResult.Accepted>(result)
            val (expectedActions, expectedReceivedWith) = setOf(
                // @formatter:off
                WrappedChannelCommand(channelId, ChannelCommand.Htlc.Settlement.Fulfill(0, defaultPreimage, commit = true)) to IncomingPayment.ReceivedWith.LightningPayment(amount1, channelId, 0),
                WrappedChannelCommand(channelId, ChannelCommand.Htlc.Settlement.Fulfill(1, defaultPreimage, commit = true)) to IncomingPayment.ReceivedWith.LightningPayment(amount2, channelId, 1),
                // @formatter:on
            ).unzip()
            assertEquals(expectedActions.toSet(), result.actions.toSet())
            assertEquals(totalAmount, result.received.amount)
            assertEquals(expectedReceivedWith, result.received.receivedWith)
            checkDbPayment(result.incomingPayment, paymentHandler.db)
        }
    }

    @Test
    fun `receive multipart payment via pay-to-open`() = runSuspendTest {
        val (amount1, amount2) = Pair(100_000.msat, 50_000.msat)
        val totalAmount = amount1 + amount2
        val (paymentHandler, incomingPayment, paymentSecret) = createFixture(totalAmount)

        // Step 1 of 2:
        // - Alice sends first multipart htlc to Bob
        // - Bob doesn't accept the MPP set yet
        run {
            val payToOpenRequest = makePayToOpenRequest(incomingPayment, makeMppPayload(amount1, totalAmount, paymentSecret))
            val result = paymentHandler.process(payToOpenRequest, TestConstants.defaultBlockHeight)
            assertIs<IncomingPaymentHandler.ProcessAddResult.Pending>(result)
            assertTrue(result.actions.isEmpty())
        }

        // Step 2 of 2:
        // - Alice sends second multipart htlc to Bob
        // - Bob now accepts the MPP set
        run {
            val payToOpenRequest = makePayToOpenRequest(incomingPayment, makeMppPayload(amount2, totalAmount, paymentSecret))
            val result = paymentHandler.process(payToOpenRequest, TestConstants.defaultBlockHeight)
            assertIs<IncomingPaymentHandler.ProcessAddResult.Accepted>(result)

            val payToOpenResponse = PayToOpenResponse(payToOpenRequest.chainHash, payToOpenRequest.paymentHash, PayToOpenResponse.Result.Success(incomingPayment.preimage))
            assertEquals(result.actions, listOf(PayToOpenResponseCommand(payToOpenResponse)))

            // pay-to-open parts are not yet provided
            assertTrue(result.received.receivedWith.isEmpty())
            assertEquals(0.msat, result.received.fees)

            checkDbPayment(result.incomingPayment, paymentHandler.db)
        }
    }

    @Test
    fun `receive multipart payment with a mix of HTLC and pay-to-open`() = runSuspendTest {
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
            assertIs<IncomingPaymentHandler.ProcessAddResult.Pending>(result)
            assertTrue { result.actions.isEmpty() }
        }

        // Step 2 of 2:
        // - Alice sends second multipart htlc to Bob
        // - Bob now accepts the MPP set
        run {
            val payToOpenRequest = makePayToOpenRequest(incomingPayment, makeMppPayload(amount2, totalAmount, paymentSecret))
            val result = paymentHandler.process(payToOpenRequest, TestConstants.defaultBlockHeight)
            assertIs<IncomingPaymentHandler.ProcessAddResult.Accepted>(result)

            assertEquals(2, result.actions.size)
            assertContains(result.actions, WrappedChannelCommand(channelId, ChannelCommand.Htlc.Settlement.Fulfill(0, incomingPayment.preimage, commit = true)))
            assertContains(result.actions, PayToOpenResponseCommand(PayToOpenResponse(payToOpenRequest.chainHash, payToOpenRequest.paymentHash, PayToOpenResponse.Result.Success(incomingPayment.preimage))))

            // the pay-to-open part is not yet provided
            assertEquals(1, result.received.receivedWith.size)
            assertContains(result.received.receivedWith, IncomingPayment.ReceivedWith.LightningPayment(amount1, channelId, 0))
            assertEquals(0.msat, result.received.fees)

            checkDbPayment(result.incomingPayment, paymentHandler.db)
        }
    }

    @Test
    fun `receive multipart payment with a mix of HTLC and pay-to-open -- fee too high`() = runSuspendTest {
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
            assertIs<IncomingPaymentHandler.ProcessAddResult.Pending>(result)
            assertTrue(result.actions.isEmpty())
        }

        // Step 2 of 2:
        // - Alice sends second multipart htlc to Bob
        // - Bob has received the complete MPP set
        run {
            val payToOpenRequest = makePayToOpenRequest(incomingPayment, makeMppPayload(amount2, totalAmount, paymentSecret)).copy(payToOpenFeeSatoshis = 2_000.sat)
            val result = paymentHandler.process(payToOpenRequest, TestConstants.defaultBlockHeight)
            assertIs<IncomingPaymentHandler.ProcessAddResult.Rejected>(result)
            val expected = setOf(
                WrappedChannelCommand(
                    channelId,
                    ChannelCommand.Htlc.Settlement.Fail(0, ChannelCommand.Htlc.Settlement.Fail.Reason.Failure(TemporaryNodeFailure), commit = true)
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
                                ChannelCommand.Htlc.Settlement.Fail.Reason.Failure(TemporaryNodeFailure)
                            ).right!!
                        )
                    )
                )
            )
            assertEquals(expected, result.actions.toSet())
        }
    }

    @Test
    fun `receive normal single HTLC with amount-less invoice`() = runSuspendTest {
        val (paymentHandler, incomingPayment, paymentSecret) = createFixture(invoiceAmount = null)
        val add = makeUpdateAddHtlc(0, randomBytes32(), paymentHandler, incomingPayment.paymentHash, makeMppPayload(defaultAmount, defaultAmount, paymentSecret))
        val result = paymentHandler.process(add, TestConstants.defaultBlockHeight)

        assertIs<IncomingPaymentHandler.ProcessAddResult.Accepted>(result)
        val expected = WrappedChannelCommand(add.channelId, ChannelCommand.Htlc.Settlement.Fulfill(add.id, incomingPayment.preimage, commit = true))
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
            assertIs<IncomingPaymentHandler.ProcessAddResult.Pending>(result)
            assertTrue(result.actions.isEmpty())
        }

        // Step 2 of 2:
        // - Alice sends second multipart htlc to Bob
        // - Bob now accepts the MPP set
        run {
            val add = makeUpdateAddHtlc(11, channelId, paymentHandler, incomingPayment.paymentHash, makeMppPayload(amount2, totalAmount, paymentSecret))
            val result = paymentHandler.process(add, TestConstants.defaultBlockHeight)
            assertIs<IncomingPaymentHandler.ProcessAddResult.Accepted>(result)
            val expected = setOf(
                WrappedChannelCommand(channelId, ChannelCommand.Htlc.Settlement.Fulfill(7, incomingPayment.preimage, commit = true)),
                WrappedChannelCommand(channelId, ChannelCommand.Htlc.Settlement.Fulfill(11, incomingPayment.preimage, commit = true)),
            )
            assertEquals(expected, result.actions.toSet())
        }
    }

    @Test
    fun `receive multipart payment with amount greater than total amount`() = runSuspendTest {
        val channelId = randomBytes32()
        val requestedAmount = 180_000.msat
        val (paymentHandler, incomingPayment, paymentSecret) = createFixture(requestedAmount)
        // The sender overpays at many different layers:
        //  - the invoice requests a payment of 180 000 msat
        //  - the sender announces a total amount of 190 000 msat
        //  - the sum of individual HTLC's onion amounts is 200 000 msat
        //  - the sum of individual HTLC's amounts is 205 000 msat
        val totalAmount = 190_000.msat
        val add1 = makeUpdateAddHtlc(3, channelId, paymentHandler, incomingPayment.paymentHash, makeMppPayload(100_000.msat, totalAmount, paymentSecret))
        val add2 = makeUpdateAddHtlc(5, channelId, paymentHandler, incomingPayment.paymentHash, makeMppPayload(60_000.msat, totalAmount, paymentSecret)).copy(amountMsat = 65_000.msat)
        val add3 = makeUpdateAddHtlc(6, channelId, paymentHandler, incomingPayment.paymentHash, makeMppPayload(40_000.msat, totalAmount, paymentSecret))

        // Step 1 of 2:
        // - Alice sends first 2 multipart htlcs to Bob.
        // - Bob doesn't accept the MPP set yet
        listOf(add1, add2).forEach { add ->
            val result = paymentHandler.process(add, TestConstants.defaultBlockHeight)
            assertIs<IncomingPaymentHandler.ProcessAddResult.Pending>(result)
            assertTrue(result.actions.isEmpty())
        }

        // Step 2 of 2:
        // - Alice sends third multipart htlc to Bob
        // - Bob now accepts the MPP set
        run {
            val result = paymentHandler.process(add3, TestConstants.defaultBlockHeight)
            assertIs<IncomingPaymentHandler.ProcessAddResult.Accepted>(result)
            val expected = setOf(
                WrappedChannelCommand(channelId, ChannelCommand.Htlc.Settlement.Fulfill(3, incomingPayment.preimage, commit = true)),
                WrappedChannelCommand(channelId, ChannelCommand.Htlc.Settlement.Fulfill(5, incomingPayment.preimage, commit = true)),
                WrappedChannelCommand(channelId, ChannelCommand.Htlc.Settlement.Fulfill(6, incomingPayment.preimage, commit = true))
            )
            assertEquals(expected, result.actions.toSet())
        }
    }

    @Test
    fun `receive normal single HTLC over-payment`() = runSuspendTest {
        val (paymentHandler, incomingPayment, paymentSecret) = createFixture(150_000.msat)
        val add = makeUpdateAddHtlc(0, randomBytes32(), paymentHandler, incomingPayment.paymentHash, makeSinglePartPayload(170_000.msat, paymentSecret)).copy(amountMsat = 175_000.msat)
        val result = paymentHandler.process(add, TestConstants.defaultBlockHeight)
        assertIs<IncomingPaymentHandler.ProcessAddResult.Accepted>(result)
        val expected = WrappedChannelCommand(add.channelId, ChannelCommand.Htlc.Settlement.Fulfill(add.id, incomingPayment.preimage, commit = true))
        assertEquals(setOf(expected), result.actions.toSet())
    }

    @Test
    fun `receive normal single HTLC with greater expiry`() = runSuspendTest {
        val (paymentHandler, incomingPayment, paymentSecret) = createFixture(defaultAmount)
        val add = makeUpdateAddHtlc(0, randomBytes32(), paymentHandler, incomingPayment.paymentHash, makeSinglePartPayload(defaultAmount, paymentSecret))
        val addGreaterExpiry = add.copy(cltvExpiry = add.cltvExpiry + CltvExpiryDelta(6))
        val result = paymentHandler.process(addGreaterExpiry, TestConstants.defaultBlockHeight)
        assertIs<IncomingPaymentHandler.ProcessAddResult.Accepted>(result)
        val expected = WrappedChannelCommand(add.channelId, ChannelCommand.Htlc.Settlement.Fulfill(add.id, incomingPayment.preimage, commit = true))
        assertEquals(setOf(expected), result.actions.toSet())
    }

    @Test
    fun `reprocess duplicate htlcs`() = runSuspendTest {
        val (paymentHandler, incomingPayment, paymentSecret) = createFixture(defaultAmount)

        // We receive a first multipart htlc.
        val add1 = makeUpdateAddHtlc(3, randomBytes32(), paymentHandler, incomingPayment.paymentHash, makeMppPayload(defaultAmount / 2, defaultAmount, paymentSecret))
        val result1 = paymentHandler.process(add1, TestConstants.defaultBlockHeight)
        assertIs<IncomingPaymentHandler.ProcessAddResult.Pending>(result1)
        assertTrue(result1.actions.isEmpty())

        // This htlc is reprocessed (e.g. because the wallet restarted).
        val result1b = paymentHandler.process(add1, TestConstants.defaultBlockHeight)
        assertIs<IncomingPaymentHandler.ProcessAddResult.Pending>(result1b)
        assertTrue(result1b.actions.isEmpty())

        // We receive the second multipart htlc.
        val add2 = makeUpdateAddHtlc(5, randomBytes32(), paymentHandler, incomingPayment.paymentHash, makeMppPayload(defaultAmount / 2, defaultAmount, paymentSecret))
        val result2 = paymentHandler.process(add2, TestConstants.defaultBlockHeight)
        assertIs<IncomingPaymentHandler.ProcessAddResult.Accepted>(result2)
        assertEquals(defaultAmount, result2.received.amount)
        val expected = setOf(
            WrappedChannelCommand(add1.channelId, ChannelCommand.Htlc.Settlement.Fulfill(add1.id, incomingPayment.preimage, commit = true)),
            WrappedChannelCommand(add2.channelId, ChannelCommand.Htlc.Settlement.Fulfill(add2.id, incomingPayment.preimage, commit = true))
        )
        assertEquals(expected, result2.actions.toSet())

        // The second htlc is reprocessed (e.g. because our peer disconnected before we could send them the preimage).
        val result2b = paymentHandler.process(add2, TestConstants.defaultBlockHeight)
        assertIs<IncomingPaymentHandler.ProcessAddResult.Accepted>(result2b)
        assertEquals(defaultAmount, result2b.received.amount)
        assertEquals(listOf(WrappedChannelCommand(add2.channelId, ChannelCommand.Htlc.Settlement.Fulfill(add2.id, incomingPayment.preimage, commit = true))), result2b.actions)
    }

    @Test
    fun `reprocess failed htlcs`() = runSuspendTest {
        val (paymentHandler, incomingPayment, paymentSecret) = createFixture(defaultAmount)

        // We receive a first multipart htlc.
        val add = makeUpdateAddHtlc(1, randomBytes32(), paymentHandler, incomingPayment.paymentHash, makeMppPayload(defaultAmount / 2, defaultAmount, paymentSecret))
        val result1 = paymentHandler.process(add, TestConstants.defaultBlockHeight)
        assertIs<IncomingPaymentHandler.ProcessAddResult.Pending>(result1)
        assertTrue(result1.actions.isEmpty())

        // It expires after a while.
        val actions1 = paymentHandler.checkPaymentsTimeout(currentTimestampSeconds() + paymentHandler.nodeParams.multiPartPaymentExpirySeconds + 2)
        val addTimeout = ChannelCommand.Htlc.Settlement.Fail(add.id, ChannelCommand.Htlc.Settlement.Fail.Reason.Failure(PaymentTimeout), commit = true)
        assertEquals(listOf(WrappedChannelCommand(add.channelId, addTimeout)), actions1)

        // For some reason, the channel was offline, didn't process the failure and retransmits the htlc.
        val result2 = paymentHandler.process(add, TestConstants.defaultBlockHeight)
        assertIs<IncomingPaymentHandler.ProcessAddResult.Pending>(result2)
        assertTrue(result2.actions.isEmpty())

        // It expires again.
        val actions2 = paymentHandler.checkPaymentsTimeout(currentTimestampSeconds() + paymentHandler.nodeParams.multiPartPaymentExpirySeconds + 2)
        assertEquals(listOf(WrappedChannelCommand(add.channelId, addTimeout)), actions2)

        // The channel was offline again, didn't process the failure and retransmits the htlc, but it is now close to its expiry.
        val currentBlockHeight = add.cltvExpiry.toLong().toInt() - 3
        val result3 = paymentHandler.process(add, currentBlockHeight)
        assertIs<IncomingPaymentHandler.ProcessAddResult.Rejected>(result3)
        val addExpired = ChannelCommand.Htlc.Settlement.Fail(add.id, ChannelCommand.Htlc.Settlement.Fail.Reason.Failure(IncorrectOrUnknownPaymentDetails(defaultAmount, currentBlockHeight.toLong())), commit = true)
        assertEquals(listOf(WrappedChannelCommand(add.channelId, addExpired)), result3.actions)
    }

    @Test
    fun `invoice expired`() = runSuspendTest {
        val paymentHandler = IncomingPaymentHandler(TestConstants.Bob.nodeParams, InMemoryPaymentsDb())
        val (incomingPayment, paymentSecret) = makeIncomingPayment(
            payee = paymentHandler,
            amount = defaultAmount,
            timestamp = currentTimestampSeconds() - 3600 - 60, // over one hour ago
            expirySeconds = 3600 // one hour expiration
        )
        val add = makeUpdateAddHtlc(0, randomBytes32(), paymentHandler, incomingPayment.paymentHash, makeMppPayload(10_000.msat, defaultAmount, paymentSecret))
        val result = paymentHandler.process(add, TestConstants.defaultBlockHeight)

        assertIs<IncomingPaymentHandler.ProcessAddResult.Rejected>(result)
        val expected = ChannelCommand.Htlc.Settlement.Fail(add.id, ChannelCommand.Htlc.Settlement.Fail.Reason.Failure(IncorrectOrUnknownPaymentDetails(defaultAmount, TestConstants.defaultBlockHeight.toLong())), commit = true)
        assertEquals(setOf(WrappedChannelCommand(add.channelId, expected)), result.actions.toSet())
    }

    @Test
    fun `invoice unknown`() = runSuspendTest {
        val (paymentHandler, _, _) = createFixture(defaultAmount)
        val add = makeUpdateAddHtlc(0, randomBytes32(), paymentHandler, randomBytes32(), makeMppPayload(defaultAmount, defaultAmount, randomBytes32()))
        val result = paymentHandler.process(add, TestConstants.defaultBlockHeight)

        assertIs<IncomingPaymentHandler.ProcessAddResult.Rejected>(result)
        val expected = ChannelCommand.Htlc.Settlement.Fail(add.id, ChannelCommand.Htlc.Settlement.Fail.Reason.Failure(IncorrectOrUnknownPaymentDetails(defaultAmount, TestConstants.defaultBlockHeight.toLong())), commit = true)
        assertEquals(setOf(WrappedChannelCommand(add.channelId, expected)), result.actions.toSet())
    }

    @Test
    fun `invalid onion`() = runSuspendTest {
        val (paymentHandler, incomingPayment, _) = createFixture(defaultAmount)
        val cltvExpiry = CltvExpiryDelta(144).toCltvExpiry(TestConstants.defaultBlockHeight.toLong())
        val badOnion = OnionRoutingPacket(0, ByteVector("0x02eec7245d6b7d2ccb30380bfbe2a3648cd7a942653f5aa340edcea1f283686619"), Lightning.randomBytes(OnionRoutingPacket.PaymentPacketLength).toByteVector(), randomBytes32())
        val add = UpdateAddHtlc(randomBytes32(), 0, defaultAmount, incomingPayment.paymentHash, cltvExpiry, badOnion)
        val result = paymentHandler.process(add, TestConstants.defaultBlockHeight)

        assertIs<IncomingPaymentHandler.ProcessAddResult.Rejected>(result)
        // The current flow of error checking within the codebase would be:
        // 1. InvalidOnionKey
        // 2. InvalidOnionHmac
        // Since we used a valid pubKey, we should get an hmac failure.
        val expectedErr = InvalidOnionHmac(Sphinx.hash(badOnion))
        val expected = ChannelCommand.Htlc.Settlement.FailMalformed(add.id, expectedErr.onionHash, expectedErr.code, commit = true)
        assertEquals(setOf(WrappedChannelCommand(add.channelId, expected)), result.actions.toSet())
    }

    @Test
    fun `invalid cltv expiry`() = runSuspendTest {
        val (paymentHandler, incomingPayment, paymentSecret) = createFixture(defaultAmount)
        val lowExpiry = CltvExpiryDelta(2)
        val add = makeUpdateAddHtlc(0, randomBytes32(), paymentHandler, incomingPayment.paymentHash, makeMppPayload(defaultAmount / 2, defaultAmount, paymentSecret, lowExpiry))
        val result = paymentHandler.process(add, TestConstants.defaultBlockHeight)

        assertIs<IncomingPaymentHandler.ProcessAddResult.Rejected>(result)
        val expected = ChannelCommand.Htlc.Settlement.Fail(add.id, ChannelCommand.Htlc.Settlement.Fail.Reason.Failure(IncorrectOrUnknownPaymentDetails(defaultAmount, TestConstants.defaultBlockHeight.toLong())), commit = true)
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
            assertIs<IncomingPaymentHandler.ProcessAddResult.Rejected>(result)
            val expected = ChannelCommand.Htlc.Settlement.Fail(add.id, ChannelCommand.Htlc.Settlement.Fail.Reason.Failure(IncorrectOrUnknownPaymentDetails(payload.totalAmount, TestConstants.defaultBlockHeight.toLong())), commit = true)
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
            assertIs<IncomingPaymentHandler.ProcessAddResult.Pending>(result)
            assertTrue(result.actions.isEmpty())
        }

        // Step 2 of 2:
        // - Alice sends second multipart htlc to Bob.
        // - Bob detects some shenanigans
        // - Bob rejects the entire MPP set
        run {
            val payload = makeMppPayload(amount2, totalAmount + MilliSatoshi(1), paymentSecret)
            val add = makeUpdateAddHtlc(2, channelId, paymentHandler, incomingPayment.paymentHash, payload)
            val result = paymentHandler.process(add, TestConstants.defaultBlockHeight)
            assertIs<IncomingPaymentHandler.ProcessAddResult.Rejected>(result)
            val expected = setOf(
                WrappedChannelCommand(
                    channelId,
                    ChannelCommand.Htlc.Settlement.Fail(1, ChannelCommand.Htlc.Settlement.Fail.Reason.Failure(IncorrectOrUnknownPaymentDetails(totalAmount, TestConstants.defaultBlockHeight.toLong())), commit = true)
                ),
                WrappedChannelCommand(
                    channelId,
                    ChannelCommand.Htlc.Settlement.Fail(2, ChannelCommand.Htlc.Settlement.Fail.Reason.Failure(IncorrectOrUnknownPaymentDetails(totalAmount + 1.msat, TestConstants.defaultBlockHeight.toLong())), commit = true)
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
            assertIs<IncomingPaymentHandler.ProcessAddResult.Pending>(result)
            assertTrue(result.actions.isEmpty())
        }

        // Step 2 of 2:
        // - Someone sends an htlc with a different payment secret
        // - Bob rejects only that htlc, not Alice's valid one
        run {
            val payload = makeMppPayload(amount2, totalAmount, randomBytes32()) // <--- invalid payment secret
            val add = makeUpdateAddHtlc(1, randomBytes32(), paymentHandler, incomingPayment.paymentHash, payload)
            val result = paymentHandler.process(add, TestConstants.defaultBlockHeight)
            assertIs<IncomingPaymentHandler.ProcessAddResult.Rejected>(result)
            val expected = ChannelCommand.Htlc.Settlement.Fail(add.id, ChannelCommand.Htlc.Settlement.Fail.Reason.Failure(IncorrectOrUnknownPaymentDetails(totalAmount, TestConstants.defaultBlockHeight.toLong())), commit = true)
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
                assertIs<IncomingPaymentHandler.ProcessAddResult.Pending>(result)
                assertTrue(result.actions.isEmpty())
            }
        }

        // Step 2 of 3:
        // - don't expire the multipart htlcs too soon.
        run {
            val currentTimestampSeconds = startTime + paymentHandler.nodeParams.multiPartPaymentExpirySeconds - 2
            val actions = paymentHandler.checkPaymentsTimeout(currentTimestampSeconds)
            assertTrue(actions.isEmpty())
        }

        // Step 3 of 3:
        // - expire the htlc-set after configured expiration.
        run {
            val currentTimestampSeconds = startTime + paymentHandler.nodeParams.multiPartPaymentExpirySeconds + 2
            val actions = paymentHandler.checkPaymentsTimeout(currentTimestampSeconds)
            val expected = setOf(
                WrappedChannelCommand(channelId, ChannelCommand.Htlc.Settlement.Fail(1, ChannelCommand.Htlc.Settlement.Fail.Reason.Failure(PaymentTimeout), commit = true)),
                WrappedChannelCommand(channelId, ChannelCommand.Htlc.Settlement.Fail(2, ChannelCommand.Htlc.Settlement.Fail.Reason.Failure(PaymentTimeout), commit = true)),
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
            assertIs<IncomingPaymentHandler.ProcessAddResult.Pending>(result)
            assertTrue(result.actions.isEmpty())
        }

        // Step 2 of 4:
        // - the MPP set times out
        run {
            val currentTimestampSeconds = startTime + paymentHandler.nodeParams.multiPartPaymentExpirySeconds + 2
            val actions = paymentHandler.checkPaymentsTimeout(currentTimestampSeconds)
            val expected = WrappedChannelCommand(channelId, ChannelCommand.Htlc.Settlement.Fail(1, ChannelCommand.Htlc.Settlement.Fail.Reason.Failure(PaymentTimeout), commit = true))
            assertEquals(setOf(expected), actions.toSet())
        }

        // Step 3 of 4:
        // - Alice tries again, and sends another single (unfinished) multipart htlc to Bob.
        run {
            val add = makeUpdateAddHtlc(3, channelId, paymentHandler, incomingPayment.paymentHash, makeMppPayload(amount1, totalAmount, paymentSecret))
            val result = paymentHandler.process(add, TestConstants.defaultBlockHeight)
            assertIs<IncomingPaymentHandler.ProcessAddResult.Pending>(result)
            assertTrue(result.actions.isEmpty())
        }

        // Step 4 of 4:
        // - Alice sends second and last part of mpp
        // - Bob accepts htlc set
        run {
            val add = makeUpdateAddHtlc(4, channelId, paymentHandler, incomingPayment.paymentHash, makeMppPayload(amount2, totalAmount, paymentSecret))
            val result = paymentHandler.process(add, TestConstants.defaultBlockHeight)
            assertIs<IncomingPaymentHandler.ProcessAddResult.Accepted>(result)
            val expected = setOf(
                WrappedChannelCommand(channelId, ChannelCommand.Htlc.Settlement.Fulfill(3, incomingPayment.preimage, commit = true)),
                WrappedChannelCommand(channelId, ChannelCommand.Htlc.Settlement.Fulfill(4, incomingPayment.preimage, commit = true)),
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
            assertIs<IncomingPaymentHandler.ProcessAddResult.Pending>(result1)
            assertTrue(result1.actions.isEmpty())

            val result2 = paymentHandler.process(htlc2, TestConstants.defaultBlockHeight)
            assertIs<IncomingPaymentHandler.ProcessAddResult.Accepted>(result2)

            val expected = setOf(
                WrappedChannelCommand(channelId1, ChannelCommand.Htlc.Settlement.Fulfill(htlc1.id, incomingPayment.preimage, commit = true)),
                WrappedChannelCommand(channelId2, ChannelCommand.Htlc.Settlement.Fulfill(htlc2.id, incomingPayment.preimage, commit = true)),
            )
            assertEquals(expected, result2.actions.toSet())
        }

        // Step 2 of 2:
        // - Alice receives local replay of htlc1 for the invoice she already completed. Must be fulfilled.
        run {
            val result = paymentHandler.process(htlc1, TestConstants.defaultBlockHeight)
            assertIs<IncomingPaymentHandler.ProcessAddResult.Accepted>(result)
            val expected = WrappedChannelCommand(channelId1, ChannelCommand.Htlc.Settlement.Fulfill(htlc1.id, incomingPayment.preimage, commit = true))
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
            assertIs<IncomingPaymentHandler.ProcessAddResult.Pending>(result1)
            assertTrue(result1.actions.isEmpty())

            val result2 = paymentHandler.process(htlc2, TestConstants.defaultBlockHeight)
            assertIs<IncomingPaymentHandler.ProcessAddResult.Accepted>(result2)

            val expected = setOf(
                WrappedChannelCommand(channelId1, ChannelCommand.Htlc.Settlement.Fulfill(htlc1.id, incomingPayment.preimage, commit = true)),
                WrappedChannelCommand(channelId2, ChannelCommand.Htlc.Settlement.Fulfill(htlc2.id, incomingPayment.preimage, commit = true)),
            )
            assertEquals(expected, result2.actions.toSet())
        }

        // Step 2 of 2:
        // - Alice receives an additional htlc (with new id) on channel1 for the invoice she already completed. Must be rejected.
        run {
            val add = htlc1.copy(id = 3)
            val result = paymentHandler.process(add, TestConstants.defaultBlockHeight)
            assertIs<IncomingPaymentHandler.ProcessAddResult.Rejected>(result)
            val expected = WrappedChannelCommand(
                channelId1, ChannelCommand.Htlc.Settlement.Fail(
                    3, ChannelCommand.Htlc.Settlement.Fail.Reason.Failure(
                        IncorrectOrUnknownPaymentDetails(totalAmount, TestConstants.defaultBlockHeight.toLong())
                    ), commit = true
                )
            )
            assertEquals(setOf(expected), result.actions.toSet())
        }

        // - Alice receives an htlc2 (but on a new channel) for the invoice she already completed. Must be rejected.
        run {
            val channelId3 = randomBytes32()
            val add = htlc2.copy(channelId = channelId3)
            val result = paymentHandler.process(add, TestConstants.defaultBlockHeight)
            assertIs<IncomingPaymentHandler.ProcessAddResult.Rejected>(result)
            val expected = WrappedChannelCommand(
                channelId3, ChannelCommand.Htlc.Settlement.Fail(
                    htlc2.id, ChannelCommand.Htlc.Settlement.Fail.Reason.Failure(
                        IncorrectOrUnknownPaymentDetails(totalAmount, TestConstants.defaultBlockHeight.toLong())
                    ), commit = true
                )
            )
            assertEquals(setOf(expected), result.actions.toSet())
        }
    }

    @Test
    fun `purge expired incoming payments`() = runSuspendTest {
        val paymentHandler = IncomingPaymentHandler(TestConstants.Bob.nodeParams, InMemoryPaymentsDb())

        // create incoming payment that has expired and not been paid
        val expiredInvoice = paymentHandler.createInvoice(
            randomBytes32(), defaultAmount, Either.Left("expired"), listOf(), expirySeconds = 3600,
            timestampSeconds = 1
        )

        // create incoming payment that has expired and been paid
        delay(100.milliseconds)
        val paidInvoice = paymentHandler.createInvoice(
            defaultPreimage, defaultAmount, Either.Left("paid"), listOf(), expirySeconds = 3600,
            timestampSeconds = 100
        )
        paymentHandler.db.receivePayment(
            paidInvoice.paymentHash,
            receivedWith = listOf(IncomingPayment.ReceivedWith.NewChannel(amount = 15_000_000.msat, serviceFee = 1_000_000.msat, miningFee = 0.sat, channelId = randomBytes32(), txId = TxId(randomBytes32()), confirmedAt = null, lockedAt = null)),
            receivedAt = 101 // simulate incoming payment being paid before it expired
        )

        // create unexpired payment
        delay(100.milliseconds)
        val unexpiredInvoice = paymentHandler.createInvoice(randomBytes32(), defaultAmount, Either.Left("unexpired"), listOf(), expirySeconds = 3600)

        val unexpiredPayment = paymentHandler.db.getIncomingPayment(unexpiredInvoice.paymentHash)!!
        val paidPayment = paymentHandler.db.getIncomingPayment(paidInvoice.paymentHash)!!
        val expiredPayment = paymentHandler.db.getIncomingPayment(expiredInvoice.paymentHash)!!

        val db = paymentHandler.db
        assertIs<InMemoryPaymentsDb>(db)
        assertEquals(db.listIncomingPayments(5, 0), listOf(unexpiredPayment, paidPayment, expiredPayment))
        assertEquals(db.listExpiredPayments(), listOf(expiredPayment))
        assertEquals(paymentHandler.purgeExpiredPayments(), 1)
        assertEquals(db.listExpiredPayments(), emptyList())
        assertEquals(db.listIncomingPayments(5, 0), listOf(unexpiredPayment, paidPayment))
    }

    companion object {
        val defaultPreimage = randomBytes32()
        val defaultPaymentHash = Crypto.sha256(defaultPreimage).toByteVector32()
        val defaultAmount = 100_000.msat

        private fun channelHops(destination: PublicKey): List<ChannelHop> {
            val dummyKey = PrivateKey(ByteVector32("0101010101010101010101010101010101010101010101010101010101010101")).publicKey()
            val dummyUpdate = ChannelUpdate(
                signature = ByteVector64.Zeroes,
                chainHash = BlockHash(ByteVector32.Zeroes),
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

        private fun makeCmdAddHtlc(destination: PublicKey, paymentHash: ByteVector32, finalPayload: PaymentOnion.FinalPayload): ChannelCommand.Htlc.Add {
            return OutgoingPaymentPacket.buildCommand(UUID.randomUUID(), paymentHash, channelHops(destination), finalPayload).first.copy(commit = true)
        }

        private fun makeUpdateAddHtlc(id: Long, channelId: ByteVector32, destination: IncomingPaymentHandler, paymentHash: ByteVector32, finalPayload: PaymentOnion.FinalPayload): UpdateAddHtlc {
            val (_, _, packetAndSecrets) = OutgoingPaymentPacket.buildPacket(paymentHash, channelHops(destination.nodeParams.nodeId), finalPayload, OnionRoutingPacket.PaymentPacketLength)
            return UpdateAddHtlc(channelId, id, finalPayload.amount, paymentHash, finalPayload.expiry, packetAndSecrets.packet)
        }

        private fun makeSinglePartPayload(
            amount: MilliSatoshi,
            paymentSecret: ByteVector32,
            cltvExpiryDelta: CltvExpiryDelta = CltvExpiryDelta(144),
            currentBlockHeight: Int = TestConstants.defaultBlockHeight
        ): PaymentOnion.FinalPayload {
            val expiry = cltvExpiryDelta.toCltvExpiry(currentBlockHeight.toLong())
            return PaymentOnion.FinalPayload.createSinglePartPayload(amount, expiry, paymentSecret, null)
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

        const val payToOpenFeerate = 0.1

        private fun makePayToOpenRequest(incomingPayment: IncomingPayment, finalPayload: PaymentOnion.FinalPayload): PayToOpenRequest {
            return PayToOpenRequest(
                chainHash = Block.RegtestGenesisBlock.hash,
                fundingSatoshis = 100_000.sat,
                amountMsat = finalPayload.amount,
                payToOpenMinAmountMsat = 10_000.msat,
                payToOpenFeeSatoshis = finalPayload.amount.truncateToSatoshi() * payToOpenFeerate, // 10%
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
            val paymentRequest = payee.createInvoice(defaultPreimage, amount, Either.Left("unit test"), listOf(), expirySeconds, timestamp)
            assertNotNull(paymentRequest.paymentMetadata)
            return Pair(payee.db.getIncomingPayment(paymentRequest.paymentHash)!!, paymentRequest.paymentSecret)
        }

        private fun makeReceivedWithNewChannel(payToOpen: PayToOpenRequest, feeRatio: Double = 0.1): IncomingPayment.ReceivedWith.NewChannel {
            val fee = payToOpen.amountMsat * feeRatio
            return IncomingPayment.ReceivedWith.NewChannel(amount = payToOpen.amountMsat - fee, serviceFee = fee, miningFee = 0.sat, channelId = randomBytes32(), txId = TxId(randomBytes32()), confirmedAt = null, lockedAt = null)
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
            val paymentHandler = IncomingPaymentHandler(TestConstants.Bob.nodeParams, InMemoryPaymentsDb())
            val (incomingPayment, paymentSecret) = makeIncomingPayment(paymentHandler, invoiceAmount)
            return Triple(paymentHandler, incomingPayment, paymentSecret)
        }
    }
}
