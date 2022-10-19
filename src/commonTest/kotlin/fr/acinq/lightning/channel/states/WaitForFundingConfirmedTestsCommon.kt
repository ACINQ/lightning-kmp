package fr.acinq.lightning.channel.states

import fr.acinq.bitcoin.*
import fr.acinq.lightning.Features
import fr.acinq.lightning.Lightning.randomBytes
import fr.acinq.lightning.Lightning.randomBytes32
import fr.acinq.lightning.Lightning.randomKey
import fr.acinq.lightning.MilliSatoshi
import fr.acinq.lightning.blockchain.*
import fr.acinq.lightning.blockchain.electrum.UnspentItem
import fr.acinq.lightning.blockchain.electrum.WalletState
import fr.acinq.lightning.blockchain.fee.FeeratePerKw
import fr.acinq.lightning.channel.*
import fr.acinq.lightning.channel.TestsHelper.processEx
import fr.acinq.lightning.tests.TestConstants
import fr.acinq.lightning.tests.utils.LightningTestSuite
import fr.acinq.lightning.utils.msat
import fr.acinq.lightning.utils.sat
import fr.acinq.lightning.wire.*
import kotlin.test.*

class WaitForFundingConfirmedTestsCommon : LightningTestSuite() {

    @Test
    fun `recv TxSignatures`() {
        val (alice, bob, txSigsBob) = init(ChannelType.SupportedChannelType.AnchorOutputs)
        val (alice1, actionsAlice1) = alice.processEx(ChannelCommand.MessageReceived(txSigsBob), minVersion = 3)
        assertIs<LNChannel<WaitForFundingConfirmed>>(alice1)
        assertIs<FullySignedSharedTransaction>(alice1.state.fundingTx)
        assertEquals(actionsAlice1.size, 3)
        val txSigsAlice = actionsAlice1.hasOutgoingMessage<TxSignatures>()
        val fundingTx = actionsAlice1.find<ChannelAction.Blockchain.PublishTx>().tx
        actionsAlice1.has<ChannelAction.Storage.StoreState>()
        val (bob1, actionsBob1) = bob.processEx(ChannelCommand.MessageReceived(txSigsAlice), minVersion = 3)
        assertIs<LNChannel<WaitForFundingConfirmed>>(bob1)
        assertIs<FullySignedSharedTransaction>(bob1.state.fundingTx)
        assertEquals(actionsBob1.size, 2)
        actionsBob1.hasTx(fundingTx)
        actionsBob1.has<ChannelAction.Storage.StoreState>()
    }

    @Test
    fun `recv TxSignatures -- duplicate`() {
        val (alice, _, txSigsBob) = init(ChannelType.SupportedChannelType.AnchorOutputs)
        val (alice1, _) = alice.processEx(ChannelCommand.MessageReceived(txSigsBob), minVersion = 3)
        assertIs<LNChannel<WaitForFundingConfirmed>>(alice1)
        assertIs<FullySignedSharedTransaction>(alice1.state.fundingTx)
        val (alice2, actionsAlice2) = alice1.processEx(ChannelCommand.MessageReceived(txSigsBob), minVersion = 3)
        assertEquals(alice1, alice2)
        assertTrue(actionsAlice2.isEmpty())
    }

    @Test
    fun `recv TxSignatures -- invalid`() {
        val (alice, _, txSigsBob) = init(ChannelType.SupportedChannelType.AnchorOutputs)
        val (alice1, actionsAlice1) = alice.processEx(ChannelCommand.MessageReceived(txSigsBob.copy(witnesses = listOf(Script.witnessPay2wpkh(randomKey().publicKey(), randomBytes(72).byteVector())))), minVersion = 3)
        // Alice sends an error, but stays in the same state because the funding tx may still confirm.
        assertEquals(actionsAlice1.size, 1)
        actionsAlice1.findOutgoingMessage<Warning>()
        assertEquals(alice, alice1)
    }

    @Test
    fun `recv BITCOIN_FUNDING_DEPTHOK`() {
        val (alice, bob, txSigsBob) = init(ChannelType.SupportedChannelType.AnchorOutputs)
        val (alice1, actionsAlice1) = alice.processEx(ChannelCommand.MessageReceived(txSigsBob), minVersion = 3)
        assertIs<LNChannel<WaitForFundingConfirmed>>(alice1)
        val txSigsAlice = actionsAlice1.hasOutgoingMessage<TxSignatures>()
        val fundingTx = actionsAlice1.find<ChannelAction.Blockchain.PublishTx>().tx
        run {
            val (alice2, actionsAlice2) = alice1.processEx(ChannelCommand.WatchReceived(WatchEventConfirmed(alice.state.channelId, BITCOIN_FUNDING_DEPTHOK, 42, 0, fundingTx)), minVersion = 3)
            assertIs<LNChannel<WaitForChannelReady>>(alice2)
            assertEquals(actionsAlice2.size, 3)
            actionsAlice2.hasOutgoingMessage<ChannelReady>()
            actionsAlice2.has<ChannelAction.Storage.StoreState>()
            val watch = actionsAlice2.hasWatch<WatchSpent>()
            assertEquals(watch.event, BITCOIN_FUNDING_SPENT)
            assertEquals(watch.txId, fundingTx.txid)
            assertEquals(watch.outputIndex.toLong(), alice.state.commitments.commitInput.outPoint.index)
        }
        run {
            val (bob1, _) = bob.processEx(ChannelCommand.MessageReceived(txSigsAlice), minVersion = 3)
            assertIs<LNChannel<WaitForFundingConfirmed>>(bob1)
            val (bob2, actionsBob2) = bob1.processEx(ChannelCommand.WatchReceived(WatchEventConfirmed(bob.state.channelId, BITCOIN_FUNDING_DEPTHOK, 42, 0, fundingTx)), minVersion = 3)
            assertIs<LNChannel<WaitForChannelReady>>(bob2)
            assertEquals(actionsBob2.size, 3)
            actionsBob2.hasOutgoingMessage<ChannelReady>()
            actionsBob2.has<ChannelAction.Storage.StoreState>()
            val watch = actionsBob2.hasWatch<WatchSpent>()
            assertEquals(watch.event, BITCOIN_FUNDING_SPENT)
            assertEquals(watch.txId, fundingTx.txid)
            assertEquals(watch.outputIndex.toLong(), bob.state.commitments.commitInput.outPoint.index)
        }
    }

    @Test
    fun `recv BITCOIN_FUNDING_DEPTHOK -- without remote sigs`() {
        val (alice, _, _) = init(ChannelType.SupportedChannelType.AnchorOutputs)
        assertIs<PartiallySignedSharedTransaction>(alice.state.fundingTx)
        val fundingTx = alice.state.fundingTx.tx.buildUnsignedTx()
        val (alice1, actionsAlice1) = alice.processEx(ChannelCommand.WatchReceived(WatchEventConfirmed(alice.state.channelId, BITCOIN_FUNDING_DEPTHOK, 42, 0, fundingTx)), minVersion = 3)
        assertIs<LNChannel<WaitForChannelReady>>(alice1)
        assertEquals(actionsAlice1.size, 3)
        actionsAlice1.hasOutgoingMessage<ChannelReady>()
        actionsAlice1.has<ChannelAction.Storage.StoreState>()
        val watch = actionsAlice1.hasWatch<WatchSpent>()
        assertEquals(watch.event, BITCOIN_FUNDING_SPENT)
        assertEquals(watch.txId, fundingTx.txid)
        assertEquals(watch.outputIndex.toLong(), alice.state.commitments.commitInput.outPoint.index)
    }

    @Test
    fun `recv BITCOIN_FUNDING_DEPTHOK -- rbf in progress`() {
        val (alice, bob, txSigsBob) = init(ChannelType.SupportedChannelType.AnchorOutputs)
        val (_, actionsAlice1) = alice.processEx(ChannelCommand.MessageReceived(txSigsBob), minVersion = 3)
        val fundingTx = actionsAlice1.find<ChannelAction.Blockchain.PublishTx>().tx
        val (bob1, actionsBob1) = bob.processEx(ChannelCommand.MessageReceived(TxInitRbf(alice.state.channelId, 0, FeeratePerKw(6000.sat), TestConstants.aliceFundingAmount)), minVersion = 3)
        assertIs<LNChannel<WaitForFundingConfirmed>>(bob1)
        assertIs<WaitForFundingConfirmed.Companion.RbfStatus.InProgress>(bob1.state.rbfStatus)
        assertEquals(actionsBob1.size, 1)
        actionsBob1.hasOutgoingMessage<TxAckRbf>()
        // The funding transaction confirms while the RBF attempt is in progress.
        val (bob2, actionsBob2) = bob1.processEx(ChannelCommand.WatchReceived(WatchEventConfirmed(bob.state.channelId, BITCOIN_FUNDING_DEPTHOK, 42, 0, fundingTx)), minVersion = 3)
        assertIs<LNChannel<WaitForChannelReady>>(bob2)
        val watch = actionsBob2.hasWatch<WatchSpent>()
        assertEquals(watch.event, BITCOIN_FUNDING_SPENT)
        assertEquals(watch.txId, fundingTx.txid)
        assertEquals(watch.outputIndex.toLong(), bob.state.commitments.commitInput.outPoint.index)
    }

    @Test
    fun `recv BITCOIN_FUNDING_DEPTHOK -- previous funding tx`() {
        val (alice, bob, txSigsBob, walletAlice) = init(ChannelType.SupportedChannelType.AnchorOutputs)
        val fundingTxId1 = alice.state.commitments.fundingTxId
        val (alice1, bob1) = rbf(alice, bob, txSigsBob, walletAlice)
        run {
            val (bob2, actionsBob2) = bob1.processEx(ChannelCommand.WatchReceived(WatchEventConfirmed(bob.state.channelId, BITCOIN_FUNDING_DEPTHOK, 42, 0, alice.state.fundingTx.tx.buildUnsignedTx())), minVersion = 3)
            assertIs<LNChannel<WaitForChannelReady>>(bob2)
            assertEquals(bob2.commitments.fundingTxId, fundingTxId1)
            val watch = actionsBob2.hasWatch<WatchSpent>()
            assertEquals(watch.event, BITCOIN_FUNDING_SPENT)
            assertEquals(watch.txId, fundingTxId1)
            assertEquals(watch.outputIndex.toLong(), bob.state.commitments.commitInput.outPoint.index)
        }
        run {
            val (alice2, actionsAlice2) = alice1.processEx(ChannelCommand.WatchReceived(WatchEventConfirmed(alice.state.channelId, BITCOIN_FUNDING_DEPTHOK, 42, 0, alice.state.fundingTx.tx.buildUnsignedTx())), minVersion = 3)
            assertIs<LNChannel<WaitForChannelReady>>(alice2)
            assertEquals(alice2.commitments.fundingTxId, fundingTxId1)
            val watch = actionsAlice2.hasWatch<WatchSpent>()
            assertEquals(watch.event, BITCOIN_FUNDING_SPENT)
            assertEquals(watch.txId, fundingTxId1)
            assertEquals(watch.outputIndex.toLong(), bob.state.commitments.commitInput.outPoint.index)
        }
    }

    @Test
    fun `recv BITCOIN_FUNDING_DEPTHOK -- after restart`() {
        val (alice, bob, _) = init(ChannelType.SupportedChannelType.AnchorOutputs)
        val fundingTx = alice.state.fundingTx.tx.buildUnsignedTx()
        run {
            val (alice1, actions1) = LNChannel(alice.ctx, WaitForInit).processEx(ChannelCommand.Restore(alice.state), minVersion = 3)
            assertIs<LNChannel<Offline>>(alice1)
            assertEquals(actions1.size, 1)
            assertEquals(actions1.findWatch<WatchConfirmed>().txId, fundingTx.txid)
            val (alice2, actions2) = alice1.processEx(ChannelCommand.WatchReceived(WatchEventConfirmed(alice.state.channelId, BITCOIN_FUNDING_DEPTHOK, 42, 0, fundingTx)), minVersion = 3)
            assertIs<LNChannel<Offline>>(alice2)
            assertEquals(actions2.size, 1)
            val watchSpent = actions2.findWatch<WatchSpent>()
            assertEquals(watchSpent.txId, fundingTx.txid)
            assertEquals(watchSpent.event, BITCOIN_FUNDING_SPENT)
        }
        run {
            val (bob1, actions1) = LNChannel(bob.ctx, WaitForInit).processEx(ChannelCommand.Restore(bob.state), minVersion = 3)
            assertIs<LNChannel<Offline>>(bob1)
            assertEquals(actions1.size, 1)
            assertEquals(actions1.findWatch<WatchConfirmed>().txId, fundingTx.txid)
            val (bob2, actions2) = bob1.processEx(ChannelCommand.WatchReceived(WatchEventConfirmed(bob.state.channelId, BITCOIN_FUNDING_DEPTHOK, 42, 0, fundingTx)), minVersion = 3)
            assertIs<LNChannel<Offline>>(bob2)
            assertEquals(actions2.size, 1)
            val watchSpent = actions2.findWatch<WatchSpent>()
            assertEquals(watchSpent.txId, fundingTx.txid)
            assertEquals(watchSpent.event, BITCOIN_FUNDING_SPENT)
        }
    }

    @Test
    fun `recv BITCOIN_FUNDING_DEPTHOK -- after restart -- previous funding tx`() {
        val (alice, bob, txSigsBob, walletAlice) = init(ChannelType.SupportedChannelType.AnchorOutputs)
        val (alice1, bob1) = rbf(alice, bob, txSigsBob, walletAlice)
        val fundingTx1 = alice1.state.previousFundingTxs.first().first.signedTx!!
        val fundingTx2 = alice1.state.fundingTx.signedTx!!
        run {
            val (alice2, actions2) = LNChannel(alice.ctx, WaitForInit).processEx(ChannelCommand.Restore(alice1.state), minVersion = 3)
            assertIs<LNChannel<Offline>>(alice2)
            assertEquals(actions2.size, 3)
            actions2.hasTx(fundingTx2)
            assertEquals(actions2.findWatches<WatchConfirmed>().map { it.txId }.toSet(), setOf(fundingTx1.txid, fundingTx2.txid))
            val (alice3, actions3) = alice2.processEx(ChannelCommand.WatchReceived(WatchEventConfirmed(alice.state.channelId, BITCOIN_FUNDING_DEPTHOK, 42, 0, fundingTx1)), minVersion = 3)
            assertIs<LNChannel<Offline>>(alice3)
            assertEquals(actions3.size, 1)
            val watchSpent = actions3.findWatch<WatchSpent>()
            assertEquals(watchSpent.txId, fundingTx1.txid)
            assertEquals(watchSpent.event, BITCOIN_FUNDING_SPENT)
        }
        run {
            val (bob2, actions2) = LNChannel(bob.ctx, WaitForInit).processEx(ChannelCommand.Restore(bob1.state), minVersion = 3)
            assertIs<LNChannel<Offline>>(bob2)
            assertEquals(actions2.size, 2) // Bob doesn't have Alice's signatures for the latest funding tx, so he cannot re-publish it
            assertEquals(actions2.findWatches<WatchConfirmed>().map { it.txId }.toSet(), setOf(fundingTx1.txid, fundingTx2.txid))
            val (bob3, actions3) = bob2.processEx(ChannelCommand.WatchReceived(WatchEventConfirmed(bob.state.channelId, BITCOIN_FUNDING_DEPTHOK, 42, 0, fundingTx1)), minVersion = 3)
            assertIs<LNChannel<Offline>>(bob3)
            assertEquals(actions3.size, 1)
            val watchSpent = actions3.findWatch<WatchSpent>()
            assertEquals(watchSpent.txId, fundingTx1.txid)
            assertEquals(watchSpent.event, BITCOIN_FUNDING_SPENT)
        }
    }

    @Test
    fun `recv TxInitRbf`() {
        val (alice, bob, txSigsBob, walletAlice) = init(ChannelType.SupportedChannelType.AnchorOutputs)
        val (alice1, bob1) = rbf(alice, bob, txSigsBob, walletAlice)
        assertEquals(alice1.state.previousFundingTxs.size, 1)
        assertEquals(bob1.state.previousFundingTxs.size, 1)
        assertTrue(alice1.state.commitments.fundingTxId != alice.state.commitments.fundingTxId)
        assertTrue(bob1.state.commitments.fundingTxId != bob.state.commitments.fundingTxId)
        assertEquals(alice1.state.commitments.fundingTxId, bob1.state.commitments.fundingTxId)
    }

    @Test
    fun `recv TxInitRbf -- invalid feerate`() {
        val (alice, bob, _) = init(ChannelType.SupportedChannelType.AnchorOutputs)
        val (bob1, actions1) = bob.processEx(ChannelCommand.MessageReceived(TxInitRbf(alice.state.channelId, 0, TestConstants.feeratePerKw, alice.state.fundingParams.localAmount)), minVersion = 3)
        assertEquals(bob1, bob)
        assertEquals(actions1.size, 1)
        assertEquals(actions1.hasOutgoingMessage<TxAbort>().toAscii(), InvalidRbfFeerate(alice.state.channelId, TestConstants.feeratePerKw, TestConstants.feeratePerKw * 25 / 24).message)
    }

    @Test
    fun `recv TxInitRbf -- invalid push amount`() {
        val (alice, bob, _) = init(ChannelType.SupportedChannelType.AnchorOutputs)
        val (bob1, actions1) = bob.processEx(ChannelCommand.MessageReceived(TxInitRbf(alice.state.channelId, 0, TestConstants.feeratePerKw * 1.25, TestConstants.alicePushAmount.truncateToSatoshi() - 1.sat)), minVersion = 3)
        assertEquals(bob1, bob)
        assertEquals(actions1.size, 1)
        assertEquals(actions1.hasOutgoingMessage<TxAbort>().toAscii(), InvalidPushAmount(alice.state.channelId, TestConstants.alicePushAmount, TestConstants.alicePushAmount - 1000.msat).message)
    }

    @Test
    fun `recv TxInitRbf -- failed rbf attempt`() {
        val (alice, bob, _) = init(ChannelType.SupportedChannelType.AnchorOutputs)
        val (bob1, actions1) = bob.processEx(ChannelCommand.MessageReceived(TxInitRbf(alice.state.channelId, 0, TestConstants.feeratePerKw * 1.25, alice.state.fundingParams.localAmount)), minVersion = 3)
        assertIs<LNChannel<WaitForFundingConfirmed>>(bob1)
        assertIs<WaitForFundingConfirmed.Companion.RbfStatus.InProgress>(bob1.state.rbfStatus)
        assertEquals(actions1.size, 1)
        actions1.hasOutgoingMessage<TxAckRbf>()
        val (bob2, actions2) = bob1.processEx(ChannelCommand.MessageReceived(alice.state.fundingTx.tx.localInputs.first()), minVersion = 3)
        assertEquals(actions2.size, 1)
        actions2.hasOutgoingMessage<TxAddInput>()
        val (bob3, actions3) = bob2.processEx(ChannelCommand.MessageReceived(TxAbort(alice.state.channelId, null)), minVersion = 3)
        assertIs<LNChannel<WaitForFundingConfirmed>>(bob3)
        assertEquals(bob3.state.rbfStatus, WaitForFundingConfirmed.Companion.RbfStatus.None)
        assertTrue(actions3.isEmpty())
    }

    @Test
    fun `recv ChannelReady`() {
        val (alice, bob, _) = init(ChannelType.SupportedChannelType.AnchorOutputs)
        val channelReadyAlice = ChannelReady(alice.state.channelId, randomKey().publicKey())
        val channelReadyBob = ChannelReady(bob.state.channelId, randomKey().publicKey())
        val (alice1, actionsAlice1) = alice.processEx(ChannelCommand.MessageReceived(channelReadyBob), minVersion = 3)
        assertIs<LNChannel<WaitForFundingConfirmed>>(alice1)
        assertEquals(alice1.state.deferred, channelReadyBob)
        assertTrue(actionsAlice1.isEmpty())
        val (bob1, actionsBob1) = bob.processEx(ChannelCommand.MessageReceived(channelReadyAlice), minVersion = 3)
        assertIs<LNChannel<WaitForFundingConfirmed>>(bob1)
        assertEquals(bob1.state.deferred, channelReadyAlice)
        assertTrue(actionsBob1.isEmpty())
    }

    @Test
    fun `recv ChannelReady -- no remote contribution`() {
        val (alice, bob, _) = init(ChannelType.SupportedChannelType.AnchorOutputs, bobFundingAmount = 0.sat, alicePushAmount = 0.msat)
        val channelReadyAlice = ChannelReady(alice.state.channelId, randomKey().publicKey())
        val channelReadyBob = ChannelReady(bob.state.channelId, randomKey().publicKey())
        val (alice1, actionsAlice1) = alice.processEx(ChannelCommand.MessageReceived(channelReadyBob), minVersion = 3)
        assertIs<LNChannel<WaitForFundingConfirmed>>(alice1)
        assertEquals(alice1.state.deferred, channelReadyBob)
        assertTrue(actionsAlice1.isEmpty())
        val (bob1, actionsBob1) = bob.processEx(ChannelCommand.MessageReceived(channelReadyAlice), minVersion = 3)
        assertIs<LNChannel<WaitForFundingConfirmed>>(bob1)
        assertEquals(bob1.state.deferred, channelReadyAlice)
        assertTrue(actionsBob1.isEmpty())
    }

    @Test
    fun `recv Error`() {
        val (_, bob) = init(ChannelType.SupportedChannelType.AnchorOutputs)
        val (bob1, actions1) = bob.processEx(ChannelCommand.MessageReceived(Error(bob.state.channelId, "oops")), minVersion = 3)
        assertIs<LNChannel<Closing>>(bob1)
        assertNotNull(bob1.state.localCommitPublished)
        actions1.hasTx(bob.state.commitments.localCommit.publishableTxs.commitTx.tx)
        assertEquals(2, actions1.findWatches<WatchConfirmed>().size) // commit tx + main output
    }

    @Test
    fun `recv Error -- previous funding tx confirms`() {
        val (alice, bob, txSigsBob, walletAlice) = init(ChannelType.SupportedChannelType.AnchorOutputs)
        val commitTxAlice1 = alice.state.commitments.localCommit.publishableTxs.commitTx.tx
        val commitTxBob1 = bob.state.commitments.localCommit.publishableTxs.commitTx.tx
        val fundingTxId1 = alice.state.commitments.fundingTxId
        val (alice1, bob1) = rbf(alice, bob, txSigsBob, walletAlice)
        val commitTxAlice2 = alice1.state.commitments.localCommit.publishableTxs.commitTx.tx
        val commitTxBob2 = bob1.state.commitments.localCommit.publishableTxs.commitTx.tx
        val fundingTxId2 = alice1.state.commitments.fundingTxId
        assertTrue(fundingTxId1 != fundingTxId2)
        assertTrue(commitTxAlice1.txid != commitTxAlice2.txid)
        assertTrue(commitTxBob1.txid != commitTxBob2.txid)
        run {
            // Bob receives an error and publishes his latest commitment.
            val (bob2, actions2) = bob1.processEx(ChannelCommand.MessageReceived(Error(bob.state.channelId, "oops")), minVersion = 3)
            assertIs<LNChannel<Closing>>(bob2)
            assertFalse(bob2.state.alternativeCommitments.isEmpty())
            actions2.hasTx(commitTxBob2)
            val lcp1 = bob2.state.localCommitPublished
            assertNotNull(lcp1)
            assertTrue(lcp1.commitTx.txIn.map { it.outPoint.txid }.contains(fundingTxId2))
            // A previous funding transaction confirms, so Bob publishes the corresponding commit tx.
            val (bob3, actions3) = bob2.processEx(ChannelCommand.WatchReceived(WatchEventConfirmed(bob.state.channelId, BITCOIN_FUNDING_DEPTHOK, 50, 0, alice.state.fundingTx.tx.buildUnsignedTx())), minVersion = 3)
            assertIs<LNChannel<Closing>>(bob3)
            assertTrue(bob3.state.alternativeCommitments.isEmpty())
            actions3.hasTx(commitTxBob1)
            val lcp2 = bob3.state.localCommitPublished
            assertNotNull(lcp2)
            assertTrue(lcp2.commitTx.txIn.map { it.outPoint.txid }.contains(fundingTxId1))
            // Alice publishes her commit tx, Bob reacts by spending his remote main output.
            val (bob4, actions4) = bob3.processEx(ChannelCommand.WatchReceived(WatchEventSpent(bob.state.channelId, BITCOIN_FUNDING_SPENT, commitTxAlice1)), minVersion = 3)
            assertIs<LNChannel<Closing>>(bob4)
            assertNotNull(bob4.state.localCommitPublished)
            assertNotNull(bob4.state.remoteCommitPublished)
            val claimMain = actions4.findTxs().first()
            assertEquals(claimMain.txIn.first().outPoint.txid, commitTxAlice1.txid)
        }
        run {
            // Alice receives an error and publishes her latest commitment.
            val (alice2, actions2) = alice1.processEx(ChannelCommand.MessageReceived(Error(alice.state.channelId, "oops")), minVersion = 3)
            assertIs<LNChannel<Closing>>(alice2)
            assertFalse(alice2.state.alternativeCommitments.isEmpty())
            actions2.hasTx(commitTxAlice2)
            val lcp1 = alice2.state.localCommitPublished
            assertNotNull(lcp1)
            assertTrue(lcp1.commitTx.txIn.map { it.outPoint.txid }.contains(fundingTxId2))
            // A previous funding transaction confirms, so Alice publishes the corresponding commit tx.
            val (alice3, actions3) = alice2.processEx(ChannelCommand.WatchReceived(WatchEventConfirmed(alice.state.channelId, BITCOIN_FUNDING_DEPTHOK, 50, 0, bob.state.fundingTx.tx.buildUnsignedTx())), minVersion = 3)
            assertIs<LNChannel<Closing>>(alice3)
            assertTrue(alice3.state.alternativeCommitments.isEmpty())
            actions3.hasTx(commitTxAlice1)
            val lcp2 = alice3.state.localCommitPublished
            assertNotNull(lcp2)
            assertTrue(lcp2.commitTx.txIn.map { it.outPoint.txid }.contains(fundingTxId1))
            // Bob publishes his commit tx, Alice reacts by spending her remote main output.
            val (alice4, actions4) = alice3.processEx(ChannelCommand.WatchReceived(WatchEventSpent(alice.state.channelId, BITCOIN_FUNDING_SPENT, commitTxBob1)), minVersion = 3)
            assertIs<LNChannel<Closing>>(alice4)
            assertNotNull(alice4.state.localCommitPublished)
            assertNotNull(alice4.state.remoteCommitPublished)
            val claimMain = actions4.findTxs().first()
            assertEquals(claimMain.txIn.first().outPoint.txid, commitTxBob1.txid)
        }
    }

    @Test
    fun `recv CMD_CLOSE`() {
        val (alice, bob) = init(ChannelType.SupportedChannelType.AnchorOutputs)
        listOf(alice, bob).forEach { state ->
            val (state1, actions1) = state.processEx(ChannelCommand.ExecuteCommand(CMD_CLOSE(null, null)), minVersion = 3)
            assertEquals(state, state1)
            actions1.hasCommandError<CommandUnavailableInThisState>()
        }
    }

    @Test
    fun `recv CMD_FORCECLOSE`() {
        val (alice, bob) = init(ChannelType.SupportedChannelType.AnchorOutputs)
        listOf(alice, bob).forEach { state ->
            val (state1, actions1) = state.processEx(ChannelCommand.ExecuteCommand(CMD_FORCECLOSE), minVersion = 3)
            assertIs<LNChannel<Closing>>(state1)
            assertNotNull(state1.state.localCommitPublished)
            actions1.hasTx(state1.state.localCommitPublished!!.commitTx)
            actions1.hasTx(state1.state.localCommitPublished!!.claimMainDelayedOutputTx!!.tx)
            assertEquals(2, actions1.findWatches<WatchConfirmed>().size) // commit tx + main output
        }
    }

    @Test
    fun `recv CMD_FORCECLOSE -- nothing at stake`() {
        val (alice, bob) = init(ChannelType.SupportedChannelType.AnchorOutputs, bobFundingAmount = 0.sat, alicePushAmount = 0.msat)
        val (bob1, actions1) = bob.processEx(ChannelCommand.ExecuteCommand(CMD_FORCECLOSE), minVersion = 3)
        assertIs<LNChannel<Aborted>>(bob1)
        assertEquals(1, actions1.size)
        val error = actions1.hasOutgoingMessage<Error>()
        assertEquals(ForcedLocalCommit(alice.state.channelId).message, error.toAscii())
    }

    @Test
    fun `recv CheckHtlcTimeout`() {
        val (alice, bob) = init(ChannelType.SupportedChannelType.AnchorOutputs)
        listOf(alice, bob).forEach { state ->
            run {
                val (state1, actions1) = state.processEx(ChannelCommand.CheckHtlcTimeout, minVersion = 3)
                assertEquals(state, state1)
                assertTrue(actions1.isEmpty())
            }
        }
    }

    @Test
    fun `recv Disconnected`() {
        val (alice, bob) = init(ChannelType.SupportedChannelType.AnchorOutputs)
        val (alice1, actionsAlice1) = alice.processEx(ChannelCommand.Disconnected, minVersion = 3)
        assertIs<LNChannel<Offline>>(alice1)
        assertTrue(actionsAlice1.isEmpty())
        val (bob1, actionsBob1) = bob.processEx(ChannelCommand.Disconnected, minVersion = 3)
        assertIs<LNChannel<Offline>>(bob1)
        assertTrue(actionsBob1.isEmpty())
    }

    companion object {
        data class Fixture(val alice: LNChannel<WaitForFundingConfirmed>, val bob: LNChannel<WaitForFundingConfirmed>, val txSigsBob: TxSignatures, val walletAlice: WalletState)

        fun init(
            channelType: ChannelType.SupportedChannelType = ChannelType.SupportedChannelType.AnchorOutputs,
            aliceFeatures: Features = TestConstants.Alice.nodeParams.features,
            bobFeatures: Features = TestConstants.Bob.nodeParams.features,
            currentHeight: Int = TestConstants.defaultBlockHeight,
            aliceFundingAmount: Satoshi = TestConstants.aliceFundingAmount,
            bobFundingAmount: Satoshi = TestConstants.bobFundingAmount,
            alicePushAmount: MilliSatoshi = TestConstants.alicePushAmount,
            bobPushAmount: MilliSatoshi = TestConstants.bobPushAmount,
        ): Fixture {
            val (alice, commitAlice, bob, commitBob) = WaitForFundingSignedTestsCommon.init(channelType, aliceFeatures, bobFeatures, currentHeight, aliceFundingAmount, bobFundingAmount, alicePushAmount, bobPushAmount, zeroConf = false)
            val (alice1, actionsAlice1) = alice.processEx(ChannelCommand.MessageReceived(commitBob), minVersion = 3)
            assertIs<LNChannel<WaitForFundingConfirmed>>(alice1)
            assertEquals(actionsAlice1.findWatch<WatchConfirmed>().event, BITCOIN_FUNDING_DEPTHOK)
            val (bob1, actionsBob1) = bob.processEx(ChannelCommand.MessageReceived(commitAlice), minVersion = 3)
            assertIs<LNChannel<WaitForFundingConfirmed>>(bob1)
            assertEquals(actionsBob1.findWatch<WatchConfirmed>().event, BITCOIN_FUNDING_DEPTHOK)
            val txSigs = actionsBob1.findOutgoingMessage<TxSignatures>()
            return Fixture(alice1, bob1, txSigs, alice.state.wallet)
        }

        fun rbf(alice: LNChannel<WaitForFundingConfirmed>, bob: LNChannel<WaitForFundingConfirmed>, txSigsBob: TxSignatures, walletAlice: WalletState): Pair<LNChannel<WaitForFundingConfirmed>, LNChannel<WaitForFundingConfirmed>> {
            val (alice0, _) = alice.processEx(ChannelCommand.MessageReceived(txSigsBob), minVersion = 3)
            assertIs<LNChannel<WaitForFundingConfirmed>>(alice0)
            val fundingTx = alice0.state.fundingTx
            assertIs<FullySignedSharedTransaction>(fundingTx)
            // Alice adds a new input that increases her contribution and covers the additional fees.
            val command = run {
                val priv = alice.staticParams.nodeParams.keyManager.bip84PrivateKey(account = 1, addressIndex = 0)
                val parentTx = Transaction(2, listOf(TxIn(OutPoint(randomBytes32(), 1), 0)), listOf(TxOut(30_000.sat, Script.pay2wpkh(priv.publicKey()))), 0)
                val address = Bitcoin.computeP2WpkhAddress(priv.publicKey(), Block.RegtestGenesisBlock.hash)
                val wallet = WalletState(
                    walletAlice.addresses + (address to (walletAlice.addresses[address] ?: listOf()) + UnspentItem(parentTx.txid, 0, 30_000, 0)),
                    walletAlice.parentTxs + (parentTx.txid to parentTx),
                )
                CMD_BUMP_FUNDING_FEE(fundingTx.feerate * 1.1, alice0.state.fundingParams.localAmount + 20_000.sat, wallet, fundingTx.tx.lockTime + 1)
            }
            val (alice1, actionsAlice1) = alice0.processEx(ChannelCommand.ExecuteCommand(command), minVersion = 3)
            assertEquals(actionsAlice1.size, 1)
            val txInitRbf = actionsAlice1.findOutgoingMessage<TxInitRbf>()
            assertEquals(txInitRbf.fundingContribution, alice.state.fundingParams.localAmount + 20_000.sat)
            val (bob1, actionsBob1) = bob.processEx(ChannelCommand.MessageReceived(txInitRbf), minVersion = 3)
            assertIs<LNChannel<WaitForFundingConfirmed>>(bob1)
            assertEquals(actionsBob1.size, 1)
            val txAckRbf = actionsBob1.findOutgoingMessage<TxAckRbf>()
            assertEquals(txAckRbf.fundingContribution, bob.state.fundingParams.localAmount) // the non-initiator doesn't change its contribution
            val (alice2, actionsAlice2) = alice1.processEx(ChannelCommand.MessageReceived(txAckRbf), minVersion = 3)
            assertIs<LNChannel<WaitForFundingConfirmed>>(alice2)
            assertEquals(actionsAlice2.size, 1)
            // Alice and Bob build the next funding transaction.
            val (alice3, bob2) = completeInteractiveTxRbf(alice2, bob1, actionsAlice2.findOutgoingMessage())
            assertIs<LNChannel<WaitForFundingConfirmed>>(alice3)
            val fundingTx1 = alice3.state.fundingTx
            assertIs<FullySignedSharedTransaction>(fundingTx1)
            assertEquals(fundingTx1.signedTx.lockTime, fundingTx.tx.lockTime + 1)
            assertEquals(alice3.state.commitments.fundingAmount, alice.state.commitments.fundingAmount + 20_000.sat)
            assertEquals(alice3.state.rbfStatus, WaitForFundingConfirmed.Companion.RbfStatus.None)
            assertEquals(bob2.state.rbfStatus, WaitForFundingConfirmed.Companion.RbfStatus.None)
            return Pair(alice3, bob2)
        }

        private fun completeInteractiveTxRbf(alice: LNChannel<WaitForFundingConfirmed>, bob: LNChannel<WaitForFundingConfirmed>, messageAlice: InteractiveTxMessage): Pair<LNChannel<WaitForFundingConfirmed>, LNChannel<WaitForFundingConfirmed>> {
            val (bob1, actionsBob1) = bob.processEx(ChannelCommand.MessageReceived(messageAlice), minVersion = 3)
            assertIs<LNChannel<WaitForFundingConfirmed>>(bob1)
            assertEquals(actionsBob1.size, 1)
            val messageBob = actionsBob1.findOutgoingMessage<InteractiveTxConstructionMessage>()
            val (alice1, actionsAlice1) = alice.processEx(ChannelCommand.MessageReceived(messageBob), minVersion = 3)
            assertIs<LNChannel<WaitForFundingConfirmed>>(alice1)
            return when (val txComplete = actionsAlice1.findOutgoingMessageOpt<TxComplete>()) {
                null -> {
                    assertEquals(actionsAlice1.size, 1)
                    completeInteractiveTxRbf(alice1, bob1, actionsAlice1.findOutgoingMessage())
                }
                else -> {
                    assertEquals(actionsAlice1.size, 2)
                    val commitSigAlice = actionsAlice1.findOutgoingMessage<CommitSig>()
                    val (bob2, actionsBob2) = bob1.processEx(ChannelCommand.MessageReceived(txComplete), minVersion = 3)
                    assertEquals(actionsBob2.size, 1)
                    val commitSigBob = actionsBob2.findOutgoingMessage<CommitSig>()
                    val (alice2, actionsAlice2) = alice1.processEx(ChannelCommand.MessageReceived(commitSigBob), minVersion = 3)
                    assertEquals(actionsAlice2.size, 2)
                    actionsAlice2.has<ChannelAction.Storage.StoreState>()
                    val watchAlice = actionsAlice2.findWatch<WatchConfirmed>()
                    val (bob3, actionsBob3) = bob2.processEx(ChannelCommand.MessageReceived(commitSigAlice), minVersion = 3)
                    assertIs<LNChannel<WaitForFundingConfirmed>>(bob3)
                    assertEquals(actionsBob3.size, 3)
                    actionsBob3.has<ChannelAction.Storage.StoreState>()
                    val watchBob = actionsBob3.findWatch<WatchConfirmed>()
                    val txSigsBob = actionsBob3.findOutgoingMessage<TxSignatures>()
                    val (alice3, actionsAlice3) = alice2.processEx(ChannelCommand.MessageReceived(txSigsBob), minVersion = 3)
                    assertIs<LNChannel<WaitForFundingConfirmed>>(alice3)
                    assertEquals(actionsAlice3.size, 3)
                    actionsAlice3.has<ChannelAction.Storage.StoreState>()
                    actionsAlice3.hasOutgoingMessage<TxSignatures>()
                    val fundingTx = actionsAlice3.find<ChannelAction.Blockchain.PublishTx>().tx
                    assertEquals(watchAlice.txId, fundingTx.txid)
                    assertEquals(watchBob.txId, fundingTx.txid)
                    Pair(alice3, bob3)
                }
            }
        }
    }

}
