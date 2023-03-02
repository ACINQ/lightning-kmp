package fr.acinq.lightning.channel.states

import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.ScriptFlags
import fr.acinq.bitcoin.Transaction
import fr.acinq.lightning.Feature
import fr.acinq.lightning.blockchain.*
import fr.acinq.lightning.channel.*

import fr.acinq.lightning.channel.TestsHelper.reachNormal
import fr.acinq.lightning.tests.TestConstants
import fr.acinq.lightning.tests.utils.LightningTestSuite
import fr.acinq.lightning.utils.msat
import fr.acinq.lightning.wire.ChannelReestablish
import fr.acinq.lightning.wire.Error
import fr.acinq.lightning.wire.Init
import fr.acinq.lightning.wire.TxSignatures
import kotlin.test.*

class SyncingTestsCommon : LightningTestSuite() {

    @Test
    fun `detect that a remote commit tx was published`() {
        val (alice, bob, _) = run {
            val (alice, bob) = init()
            disconnect(alice, bob)
        }
        val aliceCommitTx = alice.state.state.commitments.latest.localCommit.publishableTxs.commitTx.tx
        val (bob1, actions) = bob.process(ChannelCommand.WatchReceived(WatchEventSpent(bob.state.channelId, BITCOIN_FUNDING_SPENT, aliceCommitTx)))
        assertIs<LNChannel<Closing>>(bob1)
        // we published a tx to claim our main output
        val claimTx = actions.filterIsInstance<ChannelAction.Blockchain.PublishTx>().map { it.tx }.first()
        Transaction.correctlySpends(claimTx, alice.state.state.commitments.latest.localCommit.publishableTxs.commitTx.tx, ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
        val watches = actions.findWatches<WatchConfirmed>()
        assertEquals(watches.map { it.txId }.toSet(), setOf(aliceCommitTx.txid, claimTx.txid))
    }

    @Test
    fun `detect that a revoked commit tx was published`() {
        val (_, bob, revokedTx) = run {
            val (alice, bob) = init()
            val (nodes, _, _) = TestsHelper.addHtlc(10_000_000.msat, payer = alice, payee = bob)
            val (alice1, bob1) = nodes
            val (alice2, bob2) = TestsHelper.crossSign(alice1, bob1)
            val (alice3, bob3, _) = disconnect(alice2, bob2)
            Triple(alice3, bob3, alice.commitments.latest.localCommit.publishableTxs.commitTx.tx)
        }
        val (bob1, actions) = bob.process(ChannelCommand.WatchReceived(WatchEventSpent(bob.state.channelId, BITCOIN_FUNDING_SPENT, revokedTx)))
        assertIs<LNChannel<Closing>>(bob1)
        val claimTxs = actions.filterIsInstance<ChannelAction.Blockchain.PublishTx>().map { it.tx }
        assertEquals(claimTxs.size, 2)
        claimTxs.forEach { Transaction.correctlySpends(it, revokedTx, ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS) }
        val watches = actions.findWatches<WatchConfirmed>()
        // we watch the revoked tx and our "claim main output tx"
        assertEquals(watches.map { it.txId }.toSet(), setOf(revokedTx.txid, bob1.state.revokedCommitPublished.first().claimMainOutputTx!!.tx.txid))
    }

    @Test
    fun `reestablish channel with previous funding txs`() {
        val (alice, bob, txSigs, walletAlice) = WaitForFundingConfirmedTestsCommon.init(ChannelType.SupportedChannelType.AnchorOutputs)
        val (alice1, bob1) = WaitForFundingConfirmedTestsCommon.rbf(alice, bob, txSigs, walletAlice)
        val fundingTxId1 = alice.commitments.latest.fundingTxId
        val fundingTxId2 = alice1.commitments.latest.fundingTxId
        assertNotEquals(fundingTxId1, fundingTxId2)
        val (alice2, bob2, channelReestablishAlice) = disconnectWithBackup(alice1, bob1)

        val (bob3, actionsBob3) = bob2.process(ChannelCommand.MessageReceived(channelReestablishAlice))
        assertEquals(bob1, bob3)
        assertEquals(2, actionsBob3.size)
        val channelReestablishBob = actionsBob3.hasOutgoingMessage<ChannelReestablish>()
        assertEquals(actionsBob3.hasOutgoingMessage<TxSignatures>().txId, fundingTxId2) // retransmit tx_signatures on reconnection

        val (alice3, actionsAlice3) = alice2.process(ChannelCommand.MessageReceived(channelReestablishBob))
        assertEquals(alice1, alice3)
        assertEquals(1, actionsAlice3.size)
        assertEquals(actionsAlice3.hasOutgoingMessage<TxSignatures>().txId, fundingTxId2) // retransmit tx_signatures on reconnection
    }

    @Test
    fun `recv BITCOIN_FUNDING_DEPTHOK`() {
        val (alice, bob, _) = WaitForFundingConfirmedTestsCommon.init(ChannelType.SupportedChannelType.AnchorOutputs, alicePushAmount = 0.msat)
        val fundingTx = alice.state.latestFundingTx.sharedTx.tx.buildUnsignedTx()
        val (alice1, bob1, _) = disconnectWithBackup(alice, bob)
        assertIs<WaitForFundingConfirmed>(alice1.state.state)
        assertIs<WaitForFundingConfirmed>(bob1.state.state)
        val (alice2, actionsAlice2) = alice1.process(ChannelCommand.WatchReceived(WatchEventConfirmed(alice.channelId, BITCOIN_FUNDING_DEPTHOK, 42, 0, fundingTx)))
        assertIs<LNChannel<Syncing>>(alice2)
        assertIs<WaitForChannelReady>(alice2.state.state)
        assertEquals(actionsAlice2.size, 2)
        assertEquals(actionsAlice2.hasWatch<WatchSpent>().txId, fundingTx.txid)
        actionsAlice2.has<ChannelAction.Storage.StoreState>()
        val (bob2, actionsBob2) = bob1.process(ChannelCommand.WatchReceived(WatchEventConfirmed(bob.channelId, BITCOIN_FUNDING_DEPTHOK, 42, 0, fundingTx)))
        assertIs<LNChannel<Syncing>>(bob2)
        assertIs<WaitForChannelReady>(bob2.state.state)
        assertEquals(actionsBob2.size, 2)
        assertEquals(actionsBob2.hasWatch<WatchSpent>().txId, fundingTx.txid)
        actionsBob2.has<ChannelAction.Storage.StoreState>()
    }

    @Test
    fun `recv BITCOIN_FUNDING_DEPTHOK -- previous funding tx`() {
        val (alice, bob, txSigs, walletAlice) = WaitForFundingConfirmedTestsCommon.init(ChannelType.SupportedChannelType.AnchorOutputs)
        val (alice1, bob1) = WaitForFundingConfirmedTestsCommon.rbf(alice, bob, txSigs, walletAlice)
        val previousFundingTx = alice1.state.previousFundingTxs.first().signedTx!!
        val (alice2, bob2, _) = disconnectWithBackup(alice1, bob1)
        assertIs<WaitForFundingConfirmed>(alice2.state.state)
        assertIs<WaitForFundingConfirmed>(bob2.state.state)
        val (alice3, actionsAlice3) = alice2.process(ChannelCommand.WatchReceived(WatchEventConfirmed(alice.channelId, BITCOIN_FUNDING_DEPTHOK, 42, 0, previousFundingTx)))
        assertIs<LNChannel<Syncing>>(alice3)
        val aliceState3 = alice3.state.state
        assertIs<WaitForChannelReady>(aliceState3)
        assertEquals(aliceState3.commitments.active.size, 1)
        assertEquals(aliceState3.commitments.latest.fundingTxId, previousFundingTx.txid)
        assertEquals(actionsAlice3.size, 2)
        assertEquals(actionsAlice3.hasWatch<WatchSpent>().txId, previousFundingTx.txid)
        actionsAlice3.has<ChannelAction.Storage.StoreState>()
        val (bob3, actionsBob3) = bob2.process(ChannelCommand.WatchReceived(WatchEventConfirmed(bob.channelId, BITCOIN_FUNDING_DEPTHOK, 42, 0, previousFundingTx)))
        assertIs<LNChannel<Syncing>>(bob3)
        val bobState3 = bob3.state.state
        assertIs<WaitForChannelReady>(bobState3)
        assertEquals(bobState3.commitments.active.size, 1)
        assertEquals(bobState3.commitments.latest.fundingTxId, previousFundingTx.txid)
        assertEquals(actionsBob3.size, 2)
        assertEquals(actionsBob3.hasWatch<WatchSpent>().txId, previousFundingTx.txid)
        actionsBob3.has<ChannelAction.Storage.StoreState>()
    }

    @Test
    fun `recv CMD_FORCECLOSE`() {
        val (alice, _, _) = run {
            val (alice, bob) = init()
            disconnect(alice, bob)
        }
        val (alice1, actions1) = alice.process(ChannelCommand.ExecuteCommand(CMD_FORCECLOSE))
        assertIs<LNChannel<Closing>>(alice1)
        actions1.hasOutgoingMessage<Error>()
    }

    companion object {
        fun init(): Pair<LNChannel<Normal>, LNChannel<Normal>> {
            // NB: we disable channel backups to ensure Bob sends his channel_reestablish on reconnection.
            val (alice, bob, _) = reachNormal(bobFeatures = TestConstants.Bob.nodeParams.features.remove(Feature.ChannelBackupClient))
            return Pair(alice, bob)
        }

        fun disconnect(alice: LNChannel<ChannelStateWithCommitments>, bob: LNChannel<ChannelStateWithCommitments>): Triple<LNChannel<Syncing>, LNChannel<Syncing>, Pair<ChannelReestablish, ChannelReestablish>> {
            val (alice1, actionsAlice1) = alice.process(ChannelCommand.Disconnected)
            val (bob1, actionsBob1) = bob.process(ChannelCommand.Disconnected)
            assertIs<LNChannel<Offline>>(alice1)
            assertTrue(actionsAlice1.isEmpty())
            assertIs<LNChannel<Offline>>(bob1)
            assertTrue(actionsBob1.isEmpty())

            val aliceInit = Init(ByteVector(alice1.state.state.commitments.params.localParams.features.toByteArray()))
            val bobInit = Init(ByteVector(bob1.state.state.commitments.params.localParams.features.toByteArray()))
            assertFalse(bob1.state.state.commitments.params.localParams.features.hasFeature(Feature.ChannelBackupClient))

            val (alice2, actionsAlice2) = alice1.process(ChannelCommand.Connected(aliceInit, bobInit))
            assertIs<LNChannel<Syncing>>(alice2)
            val channelReestablishA = actionsAlice2.findOutgoingMessage<ChannelReestablish>()
            val (bob2, actionsBob2) = bob1.process(ChannelCommand.Connected(bobInit, aliceInit))
            assertIs<LNChannel<Syncing>>(bob2)
            val channelReestablishB = actionsBob2.findOutgoingMessage<ChannelReestablish>()
            return Triple(alice2, bob2, Pair(channelReestablishA, channelReestablishB))
        }

        fun disconnectWithBackup(alice: LNChannel<ChannelStateWithCommitments>, bob: LNChannel<ChannelStateWithCommitments>): Triple<LNChannel<Syncing>, LNChannel<Syncing>, ChannelReestablish> {
            val (alice1, actionsAlice1) = alice.process(ChannelCommand.Disconnected)
            val (bob1, actionsBob1) = bob.process(ChannelCommand.Disconnected)
            assertIs<LNChannel<Offline>>(alice1)
            assertTrue(actionsAlice1.isEmpty())
            assertIs<LNChannel<Offline>>(bob1)
            assertTrue(actionsBob1.isEmpty())

            val aliceInit = Init(ByteVector(alice1.state.state.commitments.params.localParams.features.toByteArray()))
            assertTrue(alice1.state.state.commitments.params.localParams.features.hasFeature(Feature.ChannelBackupProvider))
            val bobInit = Init(ByteVector(bob1.state.state.commitments.params.localParams.features.toByteArray()))
            assertTrue(bob1.state.state.commitments.params.localParams.features.hasFeature(Feature.ChannelBackupClient))

            val (alice2, actionsAlice2) = alice1.process(ChannelCommand.Connected(aliceInit, bobInit))
            assertIs<LNChannel<Syncing>>(alice2)
            assertIs<WaitForFundingConfirmed>(alice2.state.state)
            val channelReestablish = actionsAlice2.findOutgoingMessage<ChannelReestablish>()
            val (bob2, actionsBob2) = bob1.process(ChannelCommand.Connected(bobInit, aliceInit))
            assertIs<LNChannel<Syncing>>(bob2)
            assertIs<WaitForFundingConfirmed>(bob2.state.state)
            assertTrue(actionsBob2.isEmpty())
            return Triple(alice2, bob2, channelReestablish)
        }
    }

}