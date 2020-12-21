package fr.acinq.eclair.channel.states

import fr.acinq.bitcoin.*
import fr.acinq.eclair.CltvExpiryDelta
import fr.acinq.eclair.Eclair.randomBytes32
import fr.acinq.eclair.blockchain.WatchConfirmed
import fr.acinq.eclair.blockchain.WatchSpent
import fr.acinq.eclair.channel.*
import fr.acinq.eclair.tests.TestConstants
import fr.acinq.eclair.tests.utils.EclairTestSuite
import fr.acinq.eclair.utils.UUID
import fr.acinq.eclair.utils.msat
import fr.acinq.eclair.wire.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class OfflineTestsCommon : EclairTestSuite() {

    @Test
    fun `handle disconnect - connect events (no messages sent yet)`() {
        val (alice, bob) = TestsHelper.reachNormal()
        val (alice1, _) = alice.process(ChannelEvent.Disconnected)
        val (bob1, _) = bob.process(ChannelEvent.Disconnected)
        assertTrue(alice1 is Offline)
        assertTrue(bob1 is Offline)

        val localInit = Init(ByteVector(TestConstants.Alice.channelParams.features.toByteArray()))
        val remoteInit = Init(ByteVector(TestConstants.Bob.channelParams.features.toByteArray()))

        val (alice2, actions) = alice1.process(ChannelEvent.Connected(localInit, remoteInit))
        assertTrue(alice2 is Syncing)
        val channelReestablishA = actions.findOutgoingMessage<ChannelReestablish>()
        val (bob2, actions1) = bob1.process(ChannelEvent.Connected(remoteInit, localInit))
        assertTrue(bob2 is Syncing)
        val channelReestablishB = actions1.findOutgoingMessage<ChannelReestablish>()

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

        // alice didn't receive any update or sig
        assertEquals(
            ChannelReestablish(alice.channelId, 1, 0, PrivateKey(ByteVector32.Zeroes), aliceCurrentPerCommitmentPoint),
            channelReestablishA.copy(channelData = ByteVector.empty)
        )
        assertEquals(
            ChannelReestablish(bob.channelId, 1, 0, PrivateKey(ByteVector32.Zeroes), bobCurrentPerCommitmentPoint),
            channelReestablishB
        )

        val (alice3, actions2) = alice2.process(ChannelEvent.MessageReceived(channelReestablishB))
        assertEquals(alice, alice3)
        actions2.hasOutgoingMessage<FundingLocked>()

        val (bob3, actions3) = bob2.process(ChannelEvent.MessageReceived(channelReestablishA))
        assertEquals(bob, bob3)
        actions3.hasOutgoingMessage<FundingLocked>()
    }

    @Test
    fun `re-send update and sig after first commitment`() {
        var (alice, bob) = TestsHelper.reachNormal()
        run {
            val cmdAdd = CMD_ADD_HTLC(1_000_000.msat, ByteVector32.Zeroes, CltvExpiryDelta(144).toCltvExpiry(alice.currentBlockHeight.toLong()), TestConstants.emptyOnionPacket, UUID.randomUUID())
            val (alice1, actions) = alice.process(ChannelEvent.ExecuteCommand(cmdAdd))
            alice = alice1 as Normal
            val add = actions.hasOutgoingMessage<UpdateAddHtlc>()
            val (bob1, _) = bob.process(ChannelEvent.MessageReceived(add))
            bob = bob1 as Normal
            val (alice2, actions3) = alice.process(ChannelEvent.ExecuteCommand(CMD_SIGN))
            alice = alice2 as Normal
            actions3.hasOutgoingMessage<CommitSig>()
            // bob doesn't receive the sig
        }

        val (alice1, _) = alice.process(ChannelEvent.Disconnected)
        val (bob1, _) = bob.process(ChannelEvent.Disconnected)
        assertTrue(alice1 is Offline)
        assertTrue(bob1 is Offline)

        val localInit = Init(ByteVector(TestConstants.Alice.channelParams.features.toByteArray()))
        val remoteInit = Init(ByteVector(TestConstants.Bob.channelParams.features.toByteArray()))

        val (alice2, actions) = alice1.process(ChannelEvent.Connected(localInit, remoteInit))
        assertTrue(alice2 is Syncing)
        val channelReestablishA = actions.findOutgoingMessage<ChannelReestablish>()
        val (bob2, actions1) = bob1.process(ChannelEvent.Connected(remoteInit, localInit))
        assertTrue(bob2 is Syncing)
        val channelReestablishB = actions1.findOutgoingMessage<ChannelReestablish>()

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

        // alice didn't receive any update or sig
        assertEquals(channelReestablishA, ChannelReestablish(alice.channelId, 1, 0, PrivateKey(ByteVector32.Zeroes), aliceCurrentPerCommitmentPoint))
        // bob did not receive the sig
        assertEquals(channelReestablishB, ChannelReestablish(bob.channelId, 1, 0, PrivateKey(ByteVector32.Zeroes), bobCurrentPerCommitmentPoint))

        val (alice3, actions2) = alice2.process(ChannelEvent.MessageReceived(channelReestablishB))
        alice = alice3 as Normal
        // a sends FundingLocked again
        actions2.hasOutgoingMessage<FundingLocked>()
        // a will re-send the update and the sig
        val add = actions2.hasOutgoingMessage<UpdateAddHtlc>()
        val sig = actions2.hasOutgoingMessage<CommitSig>()

        val (bob3, actions4) = bob2.process(ChannelEvent.MessageReceived(channelReestablishA))
        bob = bob3 as Normal
        actions4.hasOutgoingMessage<FundingLocked>() // bob sends FundingLocked again
        run {
            val (bob5, _) = bob.process(ChannelEvent.MessageReceived(add))
            bob = bob5 as Normal
            val (bob6, actions6) = bob.process(ChannelEvent.MessageReceived(sig))
            bob = bob6 as Normal

            // bob sends back a revocation and a sig
            val revB = actions6.hasOutgoingMessage<RevokeAndAck>()
            actions6.hasCommand<CMD_SIGN>()
            val (bob7, actions7) = bob.process(ChannelEvent.ExecuteCommand(CMD_SIGN))
            bob = bob7 as Normal
            val sigB = actions7.hasOutgoingMessage<CommitSig>()

            val (alice4, _) = alice.process(ChannelEvent.MessageReceived(revB))
            alice = alice4 as Normal
            val (alice5, actions8) = alice.process(ChannelEvent.MessageReceived(sigB))
            alice = alice5 as Normal
            val revA = actions8.hasOutgoingMessage<RevokeAndAck>()

            val (bob4, _) = bob.process(ChannelEvent.MessageReceived(revA))
            bob4 as Normal
        }

        assertEquals(1, alice.commitments.localNextHtlcId)
    }

    @Test
    fun `reprocess pending incoming htlcs after disconnection or wallet restart`() {
        val (alice, bob, htlcs) = run {
            val (alice0, bob0) = TestsHelper.reachNormal()
            val (aliceId, bobId) = Pair(alice0.staticParams.nodeParams.nodeId, bob0.staticParams.nodeParams.nodeId)
            val currentBlockHeight = alice0.currentBlockHeight.toLong()
            // We add some htlcs Alice ---> Bob
            val (alice1, bob1, htlc1) = TestsHelper.addHtlc(TestsHelper.makeCmdAdd(75_000.msat, bobId, currentBlockHeight, randomBytes32()).second, alice0, bob0)
            val (alice2, bob2, htlc2) = TestsHelper.addHtlc(TestsHelper.makeCmdAdd(50_000.msat, bobId, currentBlockHeight, randomBytes32()).second, alice1, bob1)
            val (alice3, bob3, htlc3) = TestsHelper.addHtlc(TestsHelper.makeCmdAdd(65_000.msat, bobId, currentBlockHeight, randomBytes32()).second, alice2, bob2)
            // And htlcs Bob ---> Alice
            val (bob4, alice4, htlc4) = TestsHelper.addHtlc(TestsHelper.makeCmdAdd(45_000.msat, aliceId, currentBlockHeight, randomBytes32()).second, bob3, alice3)
            val (bob5, alice5, htlc5) = TestsHelper.addHtlc(TestsHelper.makeCmdAdd(55_000.msat, aliceId, currentBlockHeight, randomBytes32()).second, bob4, alice4)
            val (alice6, bob6) = TestsHelper.crossSign(alice5, bob5)
            // And some htlcs aren't signed yet: they will be dropped when disconnecting, and may be retransmitted later.
            val (bob7, alice7, _) = TestsHelper.addHtlc(TestsHelper.makeCmdAdd(50_000.msat, aliceId, currentBlockHeight, randomBytes32()).second, bob6, alice6)
            val (alice8, bob8, _) = TestsHelper.addHtlc(TestsHelper.makeCmdAdd(70_000.msat, bobId, currentBlockHeight, randomBytes32()).second, alice7, bob7)
            assertTrue(alice8 is Normal)
            assertTrue(bob8 is Normal)
            Triple(alice8, bob8, listOf(htlc1, htlc2, htlc3, htlc4, htlc5))
        }

        // Bob's wallet disconnects, but doesn't restart.
        val (bob1, _) = bob.process(ChannelEvent.Disconnected)
        assertTrue(bob1 is Offline)

        // Alice's wallet restarts.
        val initState = WaitForInit(alice.staticParams, alice.currentTip, alice.currentOnChainFeerates)
        val (alice1, actions1) = initState.process(ChannelEvent.Restore(alice))
        assertEquals(2, actions1.size)
        actions1.hasWatch<WatchSpent>()
        actions1.hasWatch<WatchConfirmed>()
        assertTrue(alice1 is Offline)

        val localInit = Init(ByteVector(TestConstants.Alice.channelParams.features.toByteArray()))
        val remoteInit = Init(ByteVector(TestConstants.Bob.channelParams.features.toByteArray()))

        val (alice2, actionsAlice2) = alice1.process(ChannelEvent.Connected(localInit, remoteInit))
        assertTrue(alice2 is Syncing)
        assertTrue(actionsAlice2.filterIsInstance<ChannelAction.ProcessIncomingHtlc>().isEmpty())
        val channelReestablishAlice = actionsAlice2.hasOutgoingMessage<ChannelReestablish>()
        val (bob2, actionsBob2) = bob1.process(ChannelEvent.Connected(localInit, remoteInit))
        assertTrue(actionsBob2.filterIsInstance<ChannelAction.ProcessIncomingHtlc>().isEmpty())
        val channelReestablishBob = actionsBob2.hasOutgoingMessage<ChannelReestablish>()

        // Alice reprocesses the htlcs received from Bob.
        val (_, actionsAlice3) = alice2.process(ChannelEvent.MessageReceived(channelReestablishBob))
        assertEquals(3, actionsAlice3.size)
        val expectedHtlcsAlice = htlcs.drop(3).take(2).map { ChannelAction.ProcessIncomingHtlc(it) }
        assertEquals(expectedHtlcsAlice, actionsAlice3.filterIsInstance<ChannelAction.ProcessIncomingHtlc>())
        actionsAlice3.hasWatch<WatchConfirmed>()

        // Bob reprocesses the htlcs received from Alice.
        val (_, actionsBob3) = bob2.process(ChannelEvent.MessageReceived(channelReestablishAlice))
        assertEquals(4, actionsBob3.size)
        val expectedHtlcsBob = htlcs.take(3).map { ChannelAction.ProcessIncomingHtlc(it) }
        assertEquals(expectedHtlcsBob, actionsBob3.filterIsInstance<ChannelAction.ProcessIncomingHtlc>())
        actionsBob3.hasWatch<WatchConfirmed>()
    }

    @Test
    fun `reprocess pending incoming htlcs after disconnection or wallet restart (htlc settlement signed by us)`() {
        val (alice, bob, htlcs) = run {
            val (alice0, bob0) = TestsHelper.reachNormal()
            val (aliceId, bobId) = Pair(alice0.staticParams.nodeParams.nodeId, bob0.staticParams.nodeParams.nodeId)
            val currentBlockHeight = alice0.currentBlockHeight.toLong()
            val preimage = randomBytes32()
            // Alice sends some htlcs to Bob.
            val (alice1, bob1, htlc1) = TestsHelper.addHtlc(TestsHelper.makeCmdAdd(75_000.msat, bobId, currentBlockHeight, randomBytes32()).second, alice0, bob0)
            val (alice2, bob2, htlc2) = TestsHelper.addHtlc(TestsHelper.makeCmdAdd(50_000.msat, bobId, currentBlockHeight, preimage).second, alice1, bob1)
            val (alice3, bob3, htlc3) = TestsHelper.addHtlc(TestsHelper.makeCmdAdd(65_000.msat, bobId, currentBlockHeight, randomBytes32()).second, alice2, bob2)
            // Bob sends some htlcs to Alice.
            val (bob4, alice4, htlc4) = TestsHelper.addHtlc(TestsHelper.makeCmdAdd(45_000.msat, aliceId, currentBlockHeight, randomBytes32()).second, bob3, alice3)
            val (bob5, alice5, htlc5) = TestsHelper.addHtlc(TestsHelper.makeCmdAdd(55_000.msat, aliceId, currentBlockHeight, randomBytes32()).second, bob4, alice4)
            val (alice6, bob6) = TestsHelper.crossSign(alice5, bob5)
            // Bob settles the first two htlcs and sends his signature, but Alice doesn't receive these messages.
            val (bob7, _) = bob6.process(ChannelEvent.ExecuteCommand(CMD_FAIL_HTLC(htlc1.id, CMD_FAIL_HTLC.Reason.Failure(PaymentTimeout), commit = false)))
            val (bob8, _) = bob7.process(ChannelEvent.ExecuteCommand(CMD_FULFILL_HTLC(htlc2.id, preimage, commit = false)))
            val (bob9, _) = bob8.process(ChannelEvent.ExecuteCommand(CMD_SIGN))
            Triple(alice6 as Normal, bob9 as Normal, listOf(htlc1, htlc2, htlc3, htlc4, htlc5))
        }

        // Alice and Bob are disconnected.
        val (alice1, _) = alice.process(ChannelEvent.Disconnected)
        val (bob1, _) = bob.process(ChannelEvent.Disconnected)
        assertTrue(alice1 is Offline)
        assertTrue(bob1 is Offline)

        val aliceInit = Init(ByteVector(TestConstants.Alice.channelParams.features.toByteArray()))
        val bobInit = Init(ByteVector(TestConstants.Bob.channelParams.features.toByteArray()))

        val (alice2, actionsAlice) = alice1.process(ChannelEvent.Connected(aliceInit, bobInit))
        val (bob2, _) = bob1.process(ChannelEvent.Connected(bobInit, aliceInit))
        assertTrue(alice2 is Syncing)
        assertTrue(bob2 is Syncing)
        val channelReestablishAlice = actionsAlice.hasOutgoingMessage<ChannelReestablish>()

        // Bob resends htlc settlement messages to Alice and reprocesses unsettled htlcs.
        val (_, actionsBob) = bob2.process(ChannelEvent.MessageReceived(channelReestablishAlice))
        assertEquals(5, actionsBob.size)
        val fail = actionsBob.hasOutgoingMessage<UpdateFailHtlc>()
        assertEquals(fail.id, htlcs[0].id)
        val fulfill = actionsBob.hasOutgoingMessage<UpdateFulfillHtlc>()
        assertEquals(fulfill.id, htlcs[1].id)
        actionsBob.hasOutgoingMessage<CommitSig>()
        assertEquals(listOf(ChannelAction.ProcessIncomingHtlc(htlcs[2])), actionsBob.filterIsInstance<ChannelAction.ProcessIncomingHtlc>())
        actionsBob.hasWatch<WatchConfirmed>()
    }

    @Test
    fun `recv NewBlock (no htlc timed out)`() {
        val (alice0, bob0) = TestsHelper.reachNormal()
        val (nodes, _, _) = TestsHelper.addHtlc(50_000_000.msat, alice0, bob0)
        val (alice1, _) = TestsHelper.crossSign(nodes.first, nodes.second)
        val (alice2, _) = alice1.process(ChannelEvent.Disconnected)
        assertTrue(alice2 is Offline)

        val (alice3, actions3) = alice2.process(ChannelEvent.NewBlock(alice2.currentBlockHeight + 1, alice2.currentTip.second))
        assertTrue(alice3 is Offline)
        assertEquals((alice2.state as Normal).copy(currentTip = alice3.currentTip), alice3.state)
        assertTrue(actions3.isEmpty())

        val (alice4, actions4) = alice3.process(ChannelEvent.CheckHtlcTimeout)
        assertTrue(alice4 is Offline)
        assertEquals(alice3, alice4)
        assertTrue(actions4.isEmpty())
    }

    @Test
    fun `recv NewBlock (an htlc timed out)`() {
        val (alice0, bob0) = TestsHelper.reachNormal()
        val (nodes, _, htlc) = TestsHelper.addHtlc(50_000_000.msat, alice0, bob0)
        val (alice1, _) = TestsHelper.crossSign(nodes.first, nodes.second)
        val (alice2, _) = alice1.process(ChannelEvent.Disconnected)
        assertTrue(alice2 is Offline)

        // alice restarted after the htlc timed out
        val alice3 = alice2.copy(state = (alice2.state as Normal).copy(currentTip = alice2.currentTip.copy(first = htlc.cltvExpiry.toLong().toInt())))
        val (alice4, actions) = alice3.process(ChannelEvent.CheckHtlcTimeout)
        assertTrue(alice4 is Closing)
        assertNotNull(alice4.localCommitPublished)
        actions.hasOutgoingMessage<Error>()
        actions.has<ChannelAction.Storage.StoreState>()
        val lcp = alice4.localCommitPublished!!
        actions.hasTx(lcp.commitTx)
        assertEquals(1, lcp.htlcTimeoutTxs.size)
        assertEquals(1, lcp.claimHtlcDelayedTxs.size)
        assertEquals(4, actions.findTxs().size) // commit tx + main output + htlc-timeout + claim-htlc-delayed
        assertEquals(3, actions.findWatches<WatchConfirmed>().size) // commit tx + main output + claim-htlc-delayed
        assertEquals(1, actions.findWatches<WatchSpent>().size) // htlc-timeout
    }

    @Test
    fun `recv NewBlock (fulfilled signed htlc ignored by peer)`() {
        val (alice0, bob0) = TestsHelper.reachNormal()
        val (nodes, preimage, htlc) = TestsHelper.addHtlc(50_000_000.msat, alice0, bob0)
        val (_, bob1) = TestsHelper.crossSign(nodes.first, nodes.second)
        val (bob2, actions2) = bob1.process(ChannelEvent.ExecuteCommand(CMD_FULFILL_HTLC(htlc.id, preimage)))
        actions2.hasOutgoingMessage<UpdateFulfillHtlc>()
        val (bob3, _) = bob2.process(ChannelEvent.Disconnected)
        assertTrue(bob3 is Offline)

        // bob restarts when the fulfilled htlc is close to timing out: alice hasn't signed, so bob closes the channel
        val (bob4, actions4) = bob3.process(ChannelEvent.NewBlock(htlc.cltvExpiry.toLong().toInt(), bob3.state.currentTip.second))
        assertTrue(bob4 is Closing)
        assertNotNull(bob4.localCommitPublished)
        actions4.has<ChannelAction.Storage.StoreState>()

        val lcp = bob4.localCommitPublished!!
        assertNotNull(lcp.claimMainDelayedOutputTx)
        assertEquals(1, lcp.htlcSuccessTxs.size)
        Transaction.correctlySpends(lcp.htlcSuccessTxs.first(), lcp.commitTx, ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
        assertEquals(1, lcp.claimHtlcDelayedTxs.size)
        Transaction.correctlySpends(lcp.claimHtlcDelayedTxs.first(), lcp.htlcSuccessTxs.first(), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)

        val txs = setOf(lcp.commitTx, lcp.claimMainDelayedOutputTx!!, lcp.htlcSuccessTxs.first(), lcp.claimHtlcDelayedTxs.first())
        assertEquals(txs, actions4.findTxs().toSet())
        val watchConfirmed = listOf(lcp.commitTx, lcp.claimMainDelayedOutputTx!!, lcp.claimHtlcDelayedTxs.first()).map { it.txid }.toSet()
        assertEquals(watchConfirmed, actions4.findWatches<WatchConfirmed>().map { it.txId }.toSet())
        val watchSpent = lcp.htlcSuccessTxs.first().txIn.map { it.outPoint }.toSet()
        assertEquals(watchSpent, actions4.findWatches<WatchSpent>().map { OutPoint(lcp.commitTx, it.outputIndex.toLong()) }.toSet())
    }

}
