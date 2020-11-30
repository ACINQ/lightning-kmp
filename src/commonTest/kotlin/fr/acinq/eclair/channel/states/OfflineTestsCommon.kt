package fr.acinq.eclair.channel.states

import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.eclair.CltvExpiryDelta
import fr.acinq.eclair.Eclair.randomBytes32
import fr.acinq.eclair.TestConstants
import fr.acinq.eclair.blockchain.WatchConfirmed
import fr.acinq.eclair.blockchain.WatchSpent
import fr.acinq.eclair.channel.*
import fr.acinq.eclair.db.InMemoryPaymentsDb
import fr.acinq.eclair.db.IncomingPayment
import fr.acinq.eclair.tests.utils.EclairTestSuite
import fr.acinq.eclair.tests.utils.runSuspendTest
import fr.acinq.eclair.utils.UUID
import fr.acinq.eclair.utils.msat
import fr.acinq.eclair.wire.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OfflineTestsCommon : EclairTestSuite() {

    @Test
    fun `handle disconnect - connect events (no messages sent yet)`() {
        val (alice, bob) = TestsHelper.reachNormal()
        val (alice1, _) = alice.process(ChannelEvent.Disconnected)
        val (bob1, _) = bob.process(ChannelEvent.Disconnected)
        assertTrue { alice1 is Offline }
        assertTrue { bob1 is Offline }

        val localInit = Init(ByteVector(TestConstants.Alice.channelParams.features.toByteArray()))
        val remoteInit = Init(ByteVector(TestConstants.Bob.channelParams.features.toByteArray()))

        val (alice2, actions) = alice1.process(ChannelEvent.Connected(localInit, remoteInit))
        assertTrue { alice2 is Syncing }
        val channelReestablishA = (actions[0] as ChannelAction.Message.Send).message as ChannelReestablish
        val (bob2, actions1) = bob1.process(ChannelEvent.Connected(remoteInit, localInit))
        assertTrue { bob2 is Syncing }
        val channelReestablishB = (actions1[0] as ChannelAction.Message.Send).message as ChannelReestablish

        val bobCommitments = bob.commitments
        val aliceCommitments = alice.commitments

        val bobCurrentPerCommitmentPoint = bob.keyManager.commitmentPoint(
            bob.keyManager.channelKeyPath(bobCommitments.localParams, bobCommitments.channelVersion),
            bobCommitments.localCommit.index
        )

        val aliceCurrentPerCommitmentPoint = alice.keyManager.commitmentPoint(
            alice.keyManager.channelKeyPath(aliceCommitments.localParams, aliceCommitments.channelVersion),
            aliceCommitments.localCommit.index
        )

        // a didn't receive any update or sig
        assertEquals(
            ChannelReestablish(alice.channelId, 1, 0, PrivateKey(ByteVector32.Zeroes), aliceCurrentPerCommitmentPoint),
            channelReestablishA.copy(channelData = ByteVector.empty)
        )
        assertEquals(
            ChannelReestablish(bob.channelId, 1, 0, PrivateKey(ByteVector32.Zeroes), bobCurrentPerCommitmentPoint),
            channelReestablishB
        )

        val (alice3, actions2) = alice2.process(ChannelEvent.MessageReceived(channelReestablishB))
        assertEquals(alice.commitments.localParams, (alice3 as Normal).commitments.localParams)
        assertEquals(alice, alice3)
        assertTrue(actions2.filterIsInstance<ChannelAction.Message.Send>().map { it.message }.filterIsInstance<FundingLocked>().size == 1)

        val (bob3, actions4) = bob2.process(ChannelEvent.MessageReceived(channelReestablishA))
        assertEquals(bob, bob3)
        assertTrue(actions4.filterIsInstance<ChannelAction.Message.Send>().map { it.message }.filterIsInstance<FundingLocked>().size == 1)
    }

    @Test
    fun `re-send update and sig after first commitment`() {
        var (alice, bob) = TestsHelper.reachNormal()
        run {
            val (alice1, actions) = alice.process(
                ChannelEvent.ExecuteCommand(
                    CMD_ADD_HTLC(
                        1000000.msat,
                        ByteVector32.Zeroes,
                        CltvExpiryDelta(144).toCltvExpiry(alice.currentBlockHeight.toLong()),
                        TestConstants.emptyOnionPacket,
                        UUID.randomUUID()
                    )
                )
            )
            alice = alice1 as Normal
            val add = actions.filterIsInstance<ChannelAction.Message.Send>().map { it.message }.filterIsInstance<UpdateAddHtlc>().first()
            val (bob1, _) = bob.process(ChannelEvent.MessageReceived(add))
            bob = bob1 as Normal
            val (alice2, actions3) = alice.process(ChannelEvent.ExecuteCommand(CMD_SIGN))
            alice = alice2 as Normal
            assertTrue { actions3.filterIsInstance<ChannelAction.Message.Send>().map { it.message }.filterIsInstance<CommitSig>().size == 1 }
            // bob doesn't receive the sig
        }

        val (alice1, _) = alice.process(ChannelEvent.Disconnected)
        val (bob1, _) = bob.process(ChannelEvent.Disconnected)
        assertTrue { alice1 is Offline }
        assertTrue { bob1 is Offline }

        val localInit = Init(ByteVector(TestConstants.Alice.channelParams.features.toByteArray()))
        val remoteInit = Init(ByteVector(TestConstants.Bob.channelParams.features.toByteArray()))

        val (alice2, actions) = alice1.process(ChannelEvent.Connected(localInit, remoteInit))
        assertTrue { alice2 is Syncing }
        val channelReestablishA = (actions[0] as ChannelAction.Message.Send).message as ChannelReestablish
        val (bob2, actions1) = bob1.process(ChannelEvent.Connected(remoteInit, localInit))
        assertTrue { bob2 is Syncing }
        val channelReestablishB = (actions1[0] as ChannelAction.Message.Send).message as ChannelReestablish

        val bobCommitments = bob.commitments
        val aliceCommitments = alice.commitments

        val bobCurrentPerCommitmentPoint = bob.keyManager.commitmentPoint(
            bob.keyManager.channelKeyPath(bobCommitments.localParams, bobCommitments.channelVersion),
            bobCommitments.localCommit.index
        )

        val aliceCurrentPerCommitmentPoint = alice.keyManager.commitmentPoint(
            alice.keyManager.channelKeyPath(aliceCommitments.localParams, aliceCommitments.channelVersion),
            aliceCommitments.localCommit.index
        )

        // a didn't receive any update or sig
        assertEquals(
            ChannelReestablish(alice.channelId, 1, 0, PrivateKey(ByteVector32.Zeroes), aliceCurrentPerCommitmentPoint),
            channelReestablishA
        )
        // b did not receive the sig
        assertEquals(
            ChannelReestablish(bob.channelId, 1, 0, PrivateKey(ByteVector32.Zeroes), bobCurrentPerCommitmentPoint),
            channelReestablishB
        )

        val (alice3, actions2) = alice2.process(ChannelEvent.MessageReceived(channelReestablishB))
        alice = alice3 as Normal
        // a sends FundingLocked again
        assertTrue(actions2.filterIsInstance<ChannelAction.Message.Send>().map { it.message }.filterIsInstance<FundingLocked>().size == 1)
        // a will re-send the update and the sig
        val add = actions2.filterIsInstance<ChannelAction.Message.Send>().map { it.message }.filterIsInstance<UpdateAddHtlc>().first()
        val sig = actions2.filterIsInstance<ChannelAction.Message.Send>().map { it.message }.filterIsInstance<CommitSig>().first()

        val (bob3, actions4) = bob2.process(ChannelEvent.MessageReceived(channelReestablishA))
        bob = bob3 as Normal
        // b sends FundingLocked again
        assertTrue(actions4.filterIsInstance<ChannelAction.Message.Send>().map { it.message }.filterIsInstance<FundingLocked>().size == 1)
        run {
            val (bob5, _) = bob.process(ChannelEvent.MessageReceived(add))
            bob = bob5 as Normal
            val (bob6, actions6) = bob.process(ChannelEvent.MessageReceived(sig))
            bob = bob6 as Normal
            // b sends back a revocation and a sig
            val revB = actions6.filterIsInstance<ChannelAction.Message.Send>().map { it.message }.filterIsInstance<RevokeAndAck>().first()
            assertTrue { actions6.filterIsInstance<ChannelAction.Message.SendToSelf>() == listOf(ChannelAction.Message.SendToSelf(CMD_SIGN)) }
            val (bob7, actions7) = bob.process(ChannelEvent.ExecuteCommand(CMD_SIGN))
            bob = bob7 as Normal
            val sigB = actions7.filterIsInstance<ChannelAction.Message.Send>().map { it.message }.filterIsInstance<CommitSig>().first()

            val (alice4, _) = alice.process(ChannelEvent.MessageReceived(revB))
            alice = alice4 as Normal
            val (alice5, actions8) = alice.process(ChannelEvent.MessageReceived(sigB))
            alice = alice5 as Normal
            val revA = actions8.filterIsInstance<ChannelAction.Message.Send>().map { it.message }.filterIsInstance<RevokeAndAck>().first()

            val (bob4, _) = bob.process(ChannelEvent.MessageReceived(revA))
            bob4 as Normal
        }

        assertEquals(1, alice.commitments.localNextHtlcId)
    }

    @Test
    fun `find pending htlcs after wallet restarts`() = runSuspendTest {
        val preimage = randomBytes32()
        val (alice, bob, htlcs) = run {
            val (alice, bob) = TestsHelper.reachNormal()
            // MPP payment alice -> bob that will be fulfilled (2 htlcs).
            val (alice1, bob1, htlc1) = TestsHelper.addHtlc(TestsHelper.makeCmdAdd(75_000.msat, alice.staticParams.nodeParams.nodeId, alice.currentBlockHeight.toLong(), preimage).second, alice, bob)
            val (alice2, bob2, htlc2) = TestsHelper.addHtlc(TestsHelper.makeCmdAdd(50_000.msat, alice.staticParams.nodeParams.nodeId, alice.currentBlockHeight.toLong(), preimage).second, alice1, bob1)
            // htlcs alice -> bob that will be failed.
            val (alice3, bob3, htlc3) = TestsHelper.addHtlc(TestsHelper.makeCmdAdd(60_000.msat, alice.staticParams.nodeParams.nodeId, alice.currentBlockHeight.toLong(), randomBytes32()).second, alice2, bob2)
            val (alice4, bob4, htlc4) = TestsHelper.addHtlc(TestsHelper.makeCmdAdd(65_000.msat, alice.staticParams.nodeParams.nodeId, alice.currentBlockHeight.toLong(), randomBytes32()).second, alice3, bob3)
            // htlcs bob -> alice that will be failed.
            val (bob5, alice5, htlc5) = TestsHelper.addHtlc(TestsHelper.makeCmdAdd(65_000.msat, alice.staticParams.nodeParams.nodeId, alice.currentBlockHeight.toLong(), randomBytes32()).second, bob4, alice4)
            val (alice6, bob6) = TestsHelper.crossSign(alice5, bob5)
            Triple(alice6 as Normal, bob6 as Normal, listOf(htlc1, htlc2, htlc3, htlc4, htlc5))
        }

        val postRestartAlice = Commitments.makePostRestartCommands(alice.commitments, InMemoryPaymentsDb())
        assertEquals(listOf(CMD_FAIL_HTLC(htlcs[4].id, CMD_FAIL_HTLC.Reason.Failure(TemporaryNodeFailure), false)), postRestartAlice)

        // Bob has received one of the payments but restarted before he could fulfill it.
        val paymentsDb = InMemoryPaymentsDb()
        paymentsDb.addIncomingPayment(preimage, IncomingPayment.Origin.KeySend)
        paymentsDb.receivePayment(htlcs[0].paymentHash, 125_000.msat, IncomingPayment.ReceivedWith.LightningPayment)
        val postRestartBob = Commitments.makePostRestartCommands(bob.commitments, paymentsDb)
        val expected = setOf(
            CMD_FULFILL_HTLC(htlcs[0].id, preimage, false),
            CMD_FULFILL_HTLC(htlcs[1].id, preimage, false),
            CMD_FAIL_HTLC(htlcs[2].id, CMD_FAIL_HTLC.Reason.Failure(TemporaryNodeFailure), false),
            CMD_FAIL_HTLC(htlcs[3].id, CMD_FAIL_HTLC.Reason.Failure(TemporaryNodeFailure), false),
        )
        assertEquals(expected, postRestartBob.toSet())
    }

    @Test
    fun `execute commands after wallet restarts`() {
        val (alice, bob) = TestsHelper.reachNormal()
        val (bob1, _) = bob.process(ChannelEvent.Disconnected)
        assertTrue { bob1 is Offline }

        val initState = WaitForInit(alice.staticParams, alice.currentTip, alice.currentOnChainFeerates)
        val postRestartCommands = listOf(
            CMD_FULFILL_HTLC(3, randomBytes32(), false),
            CMD_FAIL_HTLC(5, CMD_FAIL_HTLC.Reason.Failure(TemporaryNodeFailure), false),
            CMD_FAIL_HTLC(6, CMD_FAIL_HTLC.Reason.Failure(TemporaryNodeFailure), false),
        )
        val (alice1, actions1) = initState.process(ChannelEvent.Restore(alice, postRestartCommands))
        assertEquals(2, actions1.size)
        actions1.hasWatch<WatchSpent>()
        actions1.hasWatch<WatchConfirmed>()
        assertTrue { alice1 is Offline }

        val localInit = Init(ByteVector(TestConstants.Alice.channelParams.features.toByteArray()))
        val remoteInit = Init(ByteVector(TestConstants.Bob.channelParams.features.toByteArray()))
        val (alice2, actionsAlice2) = alice1.process(ChannelEvent.Connected(localInit, remoteInit))
        actionsAlice2.hasOutgoingMessage<ChannelReestablish>()
        assertTrue { alice2 is Syncing }
        val (_, actionsBob2) = bob1.process(ChannelEvent.Connected(localInit, remoteInit))
        val channelReestablishBob = actionsBob2.hasOutgoingMessage<ChannelReestablish>()

        val (alice3, actions3) = alice2.process(ChannelEvent.MessageReceived(channelReestablishBob))
        assertEquals(alice, alice3)
        assertEquals(6, actions3.size)
        actions3.hasOutgoingMessage<FundingLocked>()
        assertEquals(postRestartCommands, actions3.findCommands<HtlcSettlementCommand>())
        actions3.hasCommand<CMD_SIGN>()
        actions3.hasWatch<WatchConfirmed>()
    }

}
