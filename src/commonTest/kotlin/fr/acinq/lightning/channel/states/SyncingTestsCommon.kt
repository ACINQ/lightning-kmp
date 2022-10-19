package fr.acinq.lightning.channel.states

import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.ScriptFlags
import fr.acinq.bitcoin.Transaction
import fr.acinq.lightning.Feature
import fr.acinq.lightning.blockchain.*
import fr.acinq.lightning.channel.*
import fr.acinq.lightning.channel.TestsHelper.processEx
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
        val aliceCommitTx = alice.state.state.commitments.localCommit.publishableTxs.commitTx.tx
        val (bob1, actions) = bob.processEx(ChannelCommand.WatchReceived(WatchEventSpent(bob.state.channelId, BITCOIN_FUNDING_SPENT, aliceCommitTx)))
        assertIs<LNChannel<Closing>>(bob1)
        // we published a tx to claim our main output
        val claimTx = actions.filterIsInstance<ChannelAction.Blockchain.PublishTx>().map { it.tx }.first()
        Transaction.correctlySpends(claimTx, alice.state.state.commitments.localCommit.publishableTxs.commitTx.tx, ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
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
            Triple(alice3, bob3, alice.commitments.localCommit.publishableTxs.commitTx.tx)
        }
        val (bob1, actions) = bob.processEx(ChannelCommand.WatchReceived(WatchEventSpent(bob.state.channelId, BITCOIN_FUNDING_SPENT, revokedTx)))
        assertIs<LNChannel<Closing>>(bob1)
        val claimTxs = actions.filterIsInstance<ChannelAction.Blockchain.PublishTx>().map { it.tx }
        assertEquals(claimTxs.size, 2)
        claimTxs.forEach { Transaction.correctlySpends(it, revokedTx, ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS) }
        val watches = actions.findWatches<WatchConfirmed>()
        // we watch the revoked tx and our "claim main output tx"
        assertEquals(watches.map { it.txId }.toSet(), setOf(revokedTx.txid, bob1.state.revokedCommitPublished.first().claimMainOutputTx!!.tx.txid))
    }

    @Test
    fun `watch unconfirmed funding tx`() {
        val (alice, bob, txSigs) = WaitForFundingConfirmedTestsCommon.init(ChannelType.SupportedChannelType.AnchorOutputs)
        val fundingTxId = txSigs.txId
        val (alice1, bob1, channelReestablishAlice) = disconnectWithBackup(alice, bob)

        val (bob2, actionsBob2) = bob1.processEx(ChannelCommand.MessageReceived(channelReestablishAlice))
        assertEquals(bob, bob2)
        assertEquals(4, actionsBob2.size)
        val channelReestablishBob = actionsBob2.hasOutgoingMessage<ChannelReestablish>()
        actionsBob2.hasOutgoingMessage<TxSignatures>() // retransmit tx_signatures on reconnection
        val bobWatch = actionsBob2.hasWatch<WatchConfirmed>()
        assertEquals(fundingTxId, bobWatch.txId)
        assertEquals(3, bobWatch.minDepth)

        val (alice2, actionsAlice2) = alice1.processEx(ChannelCommand.MessageReceived(channelReestablishBob))
        assertEquals(alice, alice2)
        assertEquals(2, actionsAlice2.size)
        actionsAlice2.hasOutgoingMessage<TxSignatures>() // retransmit tx_signatures on reconnection
        val aliceWatch = actionsAlice2.hasWatch<WatchConfirmed>()
        assertEquals(fundingTxId, aliceWatch.txId)
        assertEquals(3, aliceWatch.minDepth)
    }

    @Test
    fun `watch unconfirmed funding tx with previous funding txs`() {
        val (alice, bob, txSigs, walletAlice) = WaitForFundingConfirmedTestsCommon.init(ChannelType.SupportedChannelType.AnchorOutputs)
        val (alice1, bob1) = WaitForFundingConfirmedTestsCommon.rbf(alice, bob, txSigs, walletAlice)
        val fundingTxId1 = alice.commitments.fundingTxId
        val fundingTxId2 = alice1.commitments.fundingTxId
        assertNotEquals(fundingTxId1, fundingTxId2)
        val (alice2, bob2, channelReestablishAlice) = disconnectWithBackup(alice1, bob1)

        val (bob3, actionsBob3) = bob2.processEx(ChannelCommand.MessageReceived(channelReestablishAlice))
        assertEquals(bob1, bob3)
        assertEquals(5, actionsBob3.size)
        val channelReestablishBob = actionsBob3.hasOutgoingMessage<ChannelReestablish>()
        actionsBob3.hasOutgoingMessage<TxSignatures>() // retransmit tx_signatures on reconnection
        assertEquals(actionsBob3.findWatches<WatchConfirmed>().map { it.txId }.toSet(), setOf(fundingTxId1, fundingTxId2))

        val (alice3, actionsAlice3) = alice2.processEx(ChannelCommand.MessageReceived(channelReestablishBob))
        assertEquals(alice1, alice3)
        assertEquals(3, actionsAlice3.size)
        actionsAlice3.hasOutgoingMessage<TxSignatures>() // retransmit tx_signatures on reconnection
        assertEquals(actionsAlice3.findWatches<WatchConfirmed>().map { it.txId }.toSet(), setOf(fundingTxId1, fundingTxId2))
    }

    @Test
    fun `recv BITCOIN_FUNDING_DEPTHOK`() {
        val (alice, bob, _) = WaitForFundingConfirmedTestsCommon.init(ChannelType.SupportedChannelType.AnchorOutputs, alicePushAmount = 0.msat)
        val fundingTx = alice.state.fundingTx.tx.buildUnsignedTx()
        val (alice1, bob1, _) = disconnectWithBackup(alice, bob)
        val (alice2, actionsAlice2) = alice1.processEx(ChannelCommand.WatchReceived(WatchEventConfirmed(alice.channelId, BITCOIN_FUNDING_DEPTHOK, 42, 0, fundingTx)))
        assertEquals(alice1, alice2)
        assertEquals(actionsAlice2.size, 1)
        assertEquals(actionsAlice2.hasWatch<WatchSpent>().txId, fundingTx.txid)
        val (bob2, actionsBob2) = bob1.processEx(ChannelCommand.WatchReceived(WatchEventConfirmed(bob.channelId, BITCOIN_FUNDING_DEPTHOK, 42, 0, fundingTx)))
        assertEquals(bob1, bob2)
        assertEquals(actionsBob2.size, 1)
        assertEquals(actionsBob2.hasWatch<WatchSpent>().txId, fundingTx.txid)
    }

    @Test
    fun `recv BITCOIN_FUNDING_DEPTHOK -- previous funding tx`() {
        val (alice, bob, txSigs, walletAlice) = WaitForFundingConfirmedTestsCommon.init(ChannelType.SupportedChannelType.AnchorOutputs)
        val (alice1, bob1) = WaitForFundingConfirmedTestsCommon.rbf(alice, bob, txSigs, walletAlice)
        val previousFundingTx = alice1.state.previousFundingTxs.first().first.signedTx!!
        val (alice2, bob2, _) = disconnectWithBackup(alice1, bob1)
        val (alice3, actionsAlice3) = alice2.processEx(ChannelCommand.WatchReceived(WatchEventConfirmed(alice.channelId, BITCOIN_FUNDING_DEPTHOK, 42, 0, previousFundingTx)))
        assertIs<LNChannel<Syncing>>(alice3)
        val aliceState3 = alice3.state.state
        assertIs<WaitForFundingConfirmed>(aliceState3)
        assertEquals(aliceState3.commitments.fundingTxId, previousFundingTx.txid)
        assertTrue(aliceState3.previousFundingTxs.isEmpty())
        assertEquals(actionsAlice3.size, 1)
        assertEquals(actionsAlice3.hasWatch<WatchSpent>().txId, previousFundingTx.txid)
        val (bob3, actionsBob3) = bob2.processEx(ChannelCommand.WatchReceived(WatchEventConfirmed(bob.channelId, BITCOIN_FUNDING_DEPTHOK, 42, 0, previousFundingTx)))
        assertIs<LNChannel<Syncing>>(bob3)
        val bobState3 = bob3.state.state
        assertIs<WaitForFundingConfirmed>(bobState3)
        assertEquals(bobState3.commitments.fundingTxId, previousFundingTx.txid)
        assertTrue(bobState3.previousFundingTxs.isEmpty())
        assertEquals(actionsBob3.size, 1)
        assertEquals(actionsBob3.hasWatch<WatchSpent>().txId, previousFundingTx.txid)
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
            val (alice1, actionsAlice1) = alice.processEx(ChannelCommand.Disconnected)
            val (bob1, actionsBob1) = bob.processEx(ChannelCommand.Disconnected)
            assertIs<LNChannel<Offline>>(alice1)
            assertTrue(actionsAlice1.isEmpty())
            assertIs<LNChannel<Offline>>(bob1)
            assertTrue(actionsBob1.isEmpty())

            val aliceInit = Init(ByteVector(alice1.state.state.commitments.localParams.features.toByteArray()))
            val bobInit = Init(ByteVector(bob1.state.state.commitments.localParams.features.toByteArray()))
            assertFalse(bob1.state.state.commitments.localParams.features.hasFeature(Feature.ChannelBackupClient))

            val (alice2, actionsAlice2) = alice1.processEx(ChannelCommand.Connected(aliceInit, bobInit))
            assertIs<LNChannel<Syncing>>(alice2)
            val channelReestablishA = actionsAlice2.findOutgoingMessage<ChannelReestablish>()
            val (bob2, actionsBob2) = bob1.processEx(ChannelCommand.Connected(bobInit, aliceInit))
            assertIs<LNChannel<Syncing>>(bob2)
            val channelReestablishB = actionsBob2.findOutgoingMessage<ChannelReestablish>()
            return Triple(alice2, bob2, Pair(channelReestablishA, channelReestablishB))
        }

        fun disconnectWithBackup(alice: LNChannel<ChannelStateWithCommitments>, bob: LNChannel<ChannelStateWithCommitments>): Triple<LNChannel<Syncing>, LNChannel<Syncing>, ChannelReestablish> {
            val (alice1, actionsAlice1) = alice.processEx(ChannelCommand.Disconnected)
            val (bob1, actionsBob1) = bob.processEx(ChannelCommand.Disconnected)
            assertIs<LNChannel<Offline>>(alice1)
            assertTrue(actionsAlice1.isEmpty())
            assertIs<LNChannel<Offline>>(bob1)
            assertTrue(actionsBob1.isEmpty())

            val aliceInit = Init(ByteVector(alice1.state.state.commitments.localParams.features.toByteArray()))
            assertTrue(alice1.state.state.commitments.localParams.features.hasFeature(Feature.ChannelBackupProvider))
            val bobInit = Init(ByteVector(bob1.state.state.commitments.localParams.features.toByteArray()))
            assertTrue(bob1.state.state.commitments.localParams.features.hasFeature(Feature.ChannelBackupClient))

            val (alice2, actionsAlice2) = alice1.processEx(ChannelCommand.Connected(aliceInit, bobInit))
            assertIs<LNChannel<Syncing>>(alice2)
            assertIs<WaitForFundingConfirmed>(alice2.state.state)
            val channelReestablish = actionsAlice2.findOutgoingMessage<ChannelReestablish>()
            val (bob2, actionsBob2) = bob1.processEx(ChannelCommand.Connected(bobInit, aliceInit))
            assertIs<LNChannel<Syncing>>(bob2)
            assertIs<WaitForFundingConfirmed>(bob2.state.state)
            assertTrue(actionsBob2.isEmpty())
            return Triple(alice2, bob2, channelReestablish)
        }
    }

}