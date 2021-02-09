package fr.acinq.eclair.channel.states

import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.ScriptFlags
import fr.acinq.bitcoin.Transaction
import fr.acinq.eclair.blockchain.BITCOIN_FUNDING_SPENT
import fr.acinq.eclair.blockchain.WatchConfirmed
import fr.acinq.eclair.blockchain.WatchEventSpent
import fr.acinq.eclair.channel.*
import fr.acinq.eclair.channel.TestsHelper.processEx
import fr.acinq.eclair.channel.TestsHelper.reachNormal
import fr.acinq.eclair.tests.TestConstants
import fr.acinq.eclair.tests.utils.EclairTestSuite
import fr.acinq.eclair.utils.msat
import fr.acinq.eclair.wire.ChannelReestablish
import fr.acinq.eclair.wire.Init
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SyncingTestsCommon : EclairTestSuite() {
    @Test
    fun `detect that a remote commit tx was published`() {
        val (alice, bob, _) = run {
            val (alice, bob) = reachNormal()
            disconnect(alice, bob)
        }
        val aliceCommitTx = alice.commitments.localCommit.publishableTxs.commitTx.tx
        val (bob1, actions) = bob.processEx(ChannelEvent.WatchReceived(WatchEventSpent(bob.channelId, BITCOIN_FUNDING_SPENT, aliceCommitTx)))
        assertTrue(bob1 is Closing)
        // we published a tx to claim our main output
        val claimTx = actions.filterIsInstance<ChannelAction.Blockchain.PublishTx>().map { it.tx }.first()
        Transaction.correctlySpends(claimTx, alice.commitments.localCommit.publishableTxs.commitTx.tx, ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
        val watches = actions.findWatches<WatchConfirmed>()
        assertEquals(watches.map { it.txId }.toSet(), setOf(aliceCommitTx.txid, claimTx.txid))
    }

    @Test
    fun `detect that a revoked commit tx was published`() {
        val (_, bob, revokedTx) = run {
            val (alice, bob) = reachNormal()
            val (nodes, _, _) = TestsHelper.addHtlc(10_000_000.msat, payer = alice, payee = bob)
            val (alice1, bob1) = nodes
            val (alice2, bob2) = TestsHelper.crossSign(alice1, bob1)
            alice2 as Normal
            bob2 as Normal
            val (alice3, bob3, _) = disconnect(alice2, bob2)
            Triple(alice3, bob3, alice.commitments.localCommit.publishableTxs.commitTx.tx)
        }
        val (bob1, actions) = bob.processEx(ChannelEvent.WatchReceived(WatchEventSpent(bob.channelId, BITCOIN_FUNDING_SPENT, revokedTx)))
        assertTrue(bob1 is Closing)
        val claimTxs = actions.filterIsInstance<ChannelAction.Blockchain.PublishTx>().map { it.tx }
        assertEquals(claimTxs.size, 2)
        claimTxs.forEach { Transaction.correctlySpends(it, revokedTx, ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS) }
        val watches = actions.findWatches<WatchConfirmed>()
        // we watch the revoked tx and our "claim main output tx"
        assertEquals(watches.map { it.txId }.toSet(), setOf(revokedTx.txid, bob1.revokedCommitPublished.first().claimMainOutputTx!!.txid))
    }

    companion object {
        fun disconnect(alice: Normal, bob: Normal): Triple<Syncing, Syncing, Pair<ChannelReestablish, ChannelReestablish>> {
            val (alice1, _) = alice.processEx(ChannelEvent.Disconnected)
            val (bob1, _) = bob.processEx(ChannelEvent.Disconnected)
            assertTrue(alice1 is Offline)
            assertTrue(bob1 is Offline)

            val localInit = Init(ByteVector(TestConstants.Alice.channelParams.features.toByteArray()))
            val remoteInit = Init(ByteVector(TestConstants.Bob.channelParams.features.toByteArray()))

            val (alice2, actions) = alice1.processEx(ChannelEvent.Connected(localInit, remoteInit))
            assertTrue(alice2 is Syncing)
            val channelReestablishA = actions.findOutgoingMessage<ChannelReestablish>()
            val (bob2, actions1) = bob1.processEx(ChannelEvent.Connected(remoteInit, localInit))
            assertTrue(bob2 is Syncing)
            val channelReestablishB = actions1.findOutgoingMessage<ChannelReestablish>()
            return Triple(alice2, bob2, Pair(channelReestablishA, channelReestablishB))
        }
    }
}