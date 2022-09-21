package fr.acinq.lightning.channel.states

import fr.acinq.bitcoin.*
import fr.acinq.lightning.Features
import fr.acinq.lightning.Lightning.randomBytes
import fr.acinq.lightning.Lightning.randomBytes32
import fr.acinq.lightning.Lightning.randomKey
import fr.acinq.lightning.MilliSatoshi
import fr.acinq.lightning.blockchain.*
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
        val (alice1, actionsAlice1) = alice.processEx(ChannelEvent.MessageReceived(txSigsBob))
        assertIs<WaitForFundingConfirmed>(alice1)
        assertIs<FullySignedSharedTransaction>(alice1.fundingTx)
        assertEquals(actionsAlice1.size, 3)
        val txSigsAlice = actionsAlice1.hasOutgoingMessage<TxSignatures>()
        val fundingTx = actionsAlice1.find<ChannelAction.Blockchain.PublishTx>().tx
        actionsAlice1.has<ChannelAction.Storage.StoreState>()
        val (bob1, actionsBob1) = bob.processEx(ChannelEvent.MessageReceived(txSigsAlice))
        assertIs<WaitForFundingConfirmed>(bob1)
        assertIs<FullySignedSharedTransaction>(bob1.fundingTx)
        assertEquals(actionsBob1.size, 2)
        actionsBob1.hasTx(fundingTx)
        actionsBob1.has<ChannelAction.Storage.StoreState>()
    }

    @Test
    fun `recv TxSignatures -- duplicate`() {
        val (alice, _, txSigsBob) = init(ChannelType.SupportedChannelType.AnchorOutputs)
        val (alice1, _) = alice.processEx(ChannelEvent.MessageReceived(txSigsBob))
        assertIs<WaitForFundingConfirmed>(alice1)
        assertIs<FullySignedSharedTransaction>(alice1.fundingTx)
        val (alice2, actionsAlice2) = alice1.processEx(ChannelEvent.MessageReceived(txSigsBob))
        assertEquals(alice1, alice2)
        assertTrue(actionsAlice2.isEmpty())
    }

    @Test
    fun `recv TxSignatures -- invalid`() {
        val (alice, _, txSigsBob) = init(ChannelType.SupportedChannelType.AnchorOutputs)
        val (alice1, actionsAlice1) = alice.processEx(ChannelEvent.MessageReceived(txSigsBob.copy(witnesses = listOf(Script.witnessPay2wpkh(randomKey().publicKey(), randomBytes(72).byteVector())))))
        // Alice sends an error, but stays in the same state because the funding tx may still confirm.
        assertEquals(actionsAlice1.size, 1)
        actionsAlice1.findOutgoingMessage<Warning>()
        assertEquals(alice, alice1)
    }

    @Test
    fun `recv BITCOIN_FUNDING_DEPTHOK`() {
        val (alice, bob, txSigsBob) = init(ChannelType.SupportedChannelType.AnchorOutputs)
        val (alice1, actionsAlice1) = alice.processEx(ChannelEvent.MessageReceived(txSigsBob))
        assertIs<WaitForFundingConfirmed>(alice1)
        val txSigsAlice = actionsAlice1.hasOutgoingMessage<TxSignatures>()
        val fundingTx = actionsAlice1.find<ChannelAction.Blockchain.PublishTx>().tx
        run {
            val (alice2, actionsAlice2) = alice1.processEx(ChannelEvent.WatchReceived(WatchEventConfirmed(alice.channelId, BITCOIN_FUNDING_DEPTHOK, 42, 0, fundingTx)))
            assertIs<WaitForFundingLocked>(alice2)
            assertEquals(actionsAlice2.size, 3)
            actionsAlice2.hasOutgoingMessage<FundingLocked>()
            actionsAlice2.has<ChannelAction.Storage.StoreState>()
            val watch = actionsAlice2.hasWatch<WatchSpent>()
            assertEquals(watch.event, BITCOIN_FUNDING_SPENT)
            assertEquals(watch.txId, fundingTx.txid)
            assertEquals(watch.outputIndex.toLong(), alice.commitments.commitInput.outPoint.index)
        }
        run {
            val (bob1, _) = bob.processEx(ChannelEvent.MessageReceived(txSigsAlice))
            assertIs<WaitForFundingConfirmed>(bob1)
            val (bob2, actionsBob2) = bob1.processEx(ChannelEvent.WatchReceived(WatchEventConfirmed(bob.channelId, BITCOIN_FUNDING_DEPTHOK, 42, 0, fundingTx)))
            assertIs<WaitForFundingLocked>(bob2)
            assertEquals(actionsBob2.size, 3)
            actionsBob2.hasOutgoingMessage<FundingLocked>()
            actionsBob2.has<ChannelAction.Storage.StoreState>()
            val watch = actionsBob2.hasWatch<WatchSpent>()
            assertEquals(watch.event, BITCOIN_FUNDING_SPENT)
            assertEquals(watch.txId, fundingTx.txid)
            assertEquals(watch.outputIndex.toLong(), bob.commitments.commitInput.outPoint.index)
        }
    }

    @Test
    fun `recv BITCOIN_FUNDING_DEPTHOK -- without remote sigs`() {
        val (alice, _, _) = init(ChannelType.SupportedChannelType.AnchorOutputs)
        assertIs<PartiallySignedSharedTransaction>(alice.fundingTx)
        val fundingTx = alice.fundingTx.tx.buildUnsignedTx()
        val (alice1, actionsAlice1) = alice.processEx(ChannelEvent.WatchReceived(WatchEventConfirmed(alice.channelId, BITCOIN_FUNDING_DEPTHOK, 42, 0, fundingTx)))
        assertIs<WaitForFundingLocked>(alice1)
        assertEquals(actionsAlice1.size, 3)
        actionsAlice1.hasOutgoingMessage<FundingLocked>()
        actionsAlice1.has<ChannelAction.Storage.StoreState>()
        val watch = actionsAlice1.hasWatch<WatchSpent>()
        assertEquals(watch.event, BITCOIN_FUNDING_SPENT)
        assertEquals(watch.txId, fundingTx.txid)
        assertEquals(watch.outputIndex.toLong(), alice.commitments.commitInput.outPoint.index)
    }

    @Test
    fun `recv BITCOIN_FUNDING_DEPTHOK (rbf in progress)`() {
        val (alice, bob, txSigsBob) = init(ChannelType.SupportedChannelType.AnchorOutputs)
        val (_, actionsAlice1) = alice.processEx(ChannelEvent.MessageReceived(txSigsBob))
        val fundingTx = actionsAlice1.find<ChannelAction.Blockchain.PublishTx>().tx
        val (bob1, actionsBob1) = bob.processEx(ChannelEvent.MessageReceived(TxInitRbf(alice.channelId, 0, FeeratePerKw(6000.sat), TestConstants.aliceFundingAmount)))
        assertIs<WaitForFundingConfirmed>(bob1)
        assertIs<WaitForFundingConfirmed.Companion.RbfStatus.InProgress>(bob1.rbfStatus)
        assertEquals(actionsBob1.size, 1)
        actionsBob1.hasOutgoingMessage<TxAckRbf>()
        // The funding transaction confirms while the RBF attempt is in progress.
        val (bob2, actionsBob2) = bob1.processEx(ChannelEvent.WatchReceived(WatchEventConfirmed(bob.channelId, BITCOIN_FUNDING_DEPTHOK, 42, 0, fundingTx)))
        assertIs<WaitForFundingLocked>(bob2)
        val watch = actionsBob2.hasWatch<WatchSpent>()
        assertEquals(watch.event, BITCOIN_FUNDING_SPENT)
        assertEquals(watch.txId, fundingTx.txid)
        assertEquals(watch.outputIndex.toLong(), bob.commitments.commitInput.outPoint.index)
    }

    @Test
    fun `recv BITCOIN_FUNDING_DEPTHOK (previous funding tx)`() {
        val (alice, bob, txSigsBob) = init(ChannelType.SupportedChannelType.AnchorOutputs)
        val fundingTxId1 = alice.commitments.fundingTxId
        val (alice1, bob1) = rbf(alice, bob, txSigsBob)
        run {
            val (bob2, actionsBob2) = bob1.processEx(ChannelEvent.WatchReceived(WatchEventConfirmed(bob.channelId, BITCOIN_FUNDING_DEPTHOK, 42, 0, alice.fundingTx.tx.buildUnsignedTx())))
            assertIs<WaitForFundingLocked>(bob2)
            assertEquals(bob2.commitments.fundingTxId, fundingTxId1)
            val watch = actionsBob2.hasWatch<WatchSpent>()
            assertEquals(watch.event, BITCOIN_FUNDING_SPENT)
            assertEquals(watch.txId, fundingTxId1)
            assertEquals(watch.outputIndex.toLong(), bob.commitments.commitInput.outPoint.index)
        }
        run {
            val (alice2, actionsAlice2) = alice1.processEx(ChannelEvent.WatchReceived(WatchEventConfirmed(alice.channelId, BITCOIN_FUNDING_DEPTHOK, 42, 0, alice.fundingTx.tx.buildUnsignedTx())))
            assertIs<WaitForFundingLocked>(alice2)
            assertEquals(alice2.commitments.fundingTxId, fundingTxId1)
            val watch = actionsAlice2.hasWatch<WatchSpent>()
            assertEquals(watch.event, BITCOIN_FUNDING_SPENT)
            assertEquals(watch.txId, fundingTxId1)
            assertEquals(watch.outputIndex.toLong(), bob.commitments.commitInput.outPoint.index)
        }
    }

    @Test
    fun `recv BITCOIN_FUNDING_DEPTHOK (after restart)`() {
        val (alice, bob, _) = init(ChannelType.SupportedChannelType.AnchorOutputs)
        val fundingTx = alice.fundingTx.tx.buildUnsignedTx()
        run {
            val (alice1, actions1) = WaitForInit(alice.staticParams, alice.currentTip, alice.currentOnChainFeerates).processEx(ChannelEvent.Restore(alice))
            assertIs<Offline>(alice1)
            assertEquals(actions1.size, 1)
            assertEquals(actions1.findWatch<WatchConfirmed>().txId, fundingTx.txid)
            val (alice2, actions2) = alice1.processEx(ChannelEvent.WatchReceived(WatchEventConfirmed(alice.channelId, BITCOIN_FUNDING_DEPTHOK, 42, 0, fundingTx)))
            assertIs<Offline>(alice2)
            assertEquals(actions2.size, 1)
            val watchSpent = actions2.findWatch<WatchSpent>()
            assertEquals(watchSpent.txId, fundingTx.txid)
            assertEquals(watchSpent.event, BITCOIN_FUNDING_SPENT)
        }
        run {
            val (bob1, actions1) = WaitForInit(bob.staticParams, bob.currentTip, bob.currentOnChainFeerates).processEx(ChannelEvent.Restore(bob))
            assertIs<Offline>(bob1)
            assertEquals(actions1.size, 1)
            assertEquals(actions1.findWatch<WatchConfirmed>().txId, fundingTx.txid)
            val (bob2, actions2) = bob1.processEx(ChannelEvent.WatchReceived(WatchEventConfirmed(bob.channelId, BITCOIN_FUNDING_DEPTHOK, 42, 0, fundingTx)))
            assertIs<Offline>(bob2)
            assertEquals(actions2.size, 1)
            val watchSpent = actions2.findWatch<WatchSpent>()
            assertEquals(watchSpent.txId, fundingTx.txid)
            assertEquals(watchSpent.event, BITCOIN_FUNDING_SPENT)
        }
    }

    @Test
    fun `recv BITCOIN_FUNDING_DEPTHOK (after restart, previous funding tx)`() {
        val (alice, bob, txSigsBob) = init(ChannelType.SupportedChannelType.AnchorOutputs)
        val (alice1, bob1) = rbf(alice, bob, txSigsBob)
        val fundingTx1 = alice1.previousFundingTxs.first().first.signedTx!!
        val fundingTx2 = alice1.fundingTx.signedTx!!
        run {
            val (alice2, actions2) = WaitForInit(alice.staticParams, alice.currentTip, alice.currentOnChainFeerates).processEx(ChannelEvent.Restore(alice1))
            assertIs<Offline>(alice2)
            assertEquals(actions2.size, 3)
            actions2.hasTx(fundingTx2)
            assertEquals(actions2.findWatches<WatchConfirmed>().map { it.txId }.toSet(), setOf(fundingTx1.txid, fundingTx2.txid))
            val (alice3, actions3) = alice2.processEx(ChannelEvent.WatchReceived(WatchEventConfirmed(alice.channelId, BITCOIN_FUNDING_DEPTHOK, 42, 0, fundingTx1)))
            assertIs<Offline>(alice3)
            assertEquals(actions3.size, 1)
            val watchSpent = actions3.findWatch<WatchSpent>()
            assertEquals(watchSpent.txId, fundingTx1.txid)
            assertEquals(watchSpent.event, BITCOIN_FUNDING_SPENT)
        }
        run {
            val (bob2, actions2) = WaitForInit(bob.staticParams, bob.currentTip, bob.currentOnChainFeerates).processEx(ChannelEvent.Restore(bob1))
            assertIs<Offline>(bob2)
            assertEquals(actions2.size, 2) // Bob doesn't have Alice's signatures for the latest funding tx, so he cannot re-publish it
            assertEquals(actions2.findWatches<WatchConfirmed>().map { it.txId }.toSet(), setOf(fundingTx1.txid, fundingTx2.txid))
            val (bob3, actions3) = bob2.processEx(ChannelEvent.WatchReceived(WatchEventConfirmed(bob.channelId, BITCOIN_FUNDING_DEPTHOK, 42, 0, fundingTx1)))
            assertIs<Offline>(bob3)
            assertEquals(actions3.size, 1)
            val watchSpent = actions3.findWatch<WatchSpent>()
            assertEquals(watchSpent.txId, fundingTx1.txid)
            assertEquals(watchSpent.event, BITCOIN_FUNDING_SPENT)
        }
    }

    @Test
    fun `recv TxInitRbf`() {
        val (alice, bob, txSigsBob) = init(ChannelType.SupportedChannelType.AnchorOutputs)
        val (alice1, bob1) = rbf(alice, bob, txSigsBob)
        assertEquals(alice1.previousFundingTxs.size, 1)
        assertEquals(bob1.previousFundingTxs.size, 1)
        assertTrue(alice1.commitments.fundingTxId != alice.commitments.fundingTxId)
        assertTrue(bob1.commitments.fundingTxId != bob.commitments.fundingTxId)
        assertEquals(alice1.commitments.fundingTxId, bob1.commitments.fundingTxId)
    }

    @Test
    fun `recv TxInitRbf (invalid feerate)`() {
        val (alice, bob, _) = init(ChannelType.SupportedChannelType.AnchorOutputs)
        val (bob1, actions1) = bob.processEx(ChannelEvent.MessageReceived(TxInitRbf(alice.channelId, 0, TestConstants.feeratePerKw, alice.fundingParams.localAmount)))
        assertEquals(bob1, bob)
        assertEquals(actions1.size, 1)
        actions1.hasOutgoingMessage<TxAbort>()
    }

    @Test
    fun `recv TxInitRbf (failed rbf attempt)`() {
        val (alice, bob, _) = init(ChannelType.SupportedChannelType.AnchorOutputs)
        val (bob1, actions1) = bob.processEx(ChannelEvent.MessageReceived(TxInitRbf(alice.channelId, 0, TestConstants.feeratePerKw * 1.25, alice.fundingParams.localAmount)))
        assertIs<WaitForFundingConfirmed>(bob1)
        assertIs<WaitForFundingConfirmed.Companion.RbfStatus.InProgress>(bob1.rbfStatus)
        assertEquals(actions1.size, 1)
        actions1.hasOutgoingMessage<TxAckRbf>()
        val (bob2, actions2) = bob1.processEx(ChannelEvent.MessageReceived(alice.fundingTx.tx.localInputs.first()))
        assertEquals(actions2.size, 1)
        actions2.hasOutgoingMessage<TxAddInput>()
        val (bob3, actions3) = bob2.processEx(ChannelEvent.MessageReceived(TxAbort(alice.channelId, null)))
        assertIs<WaitForFundingConfirmed>(bob3)
        assertEquals(bob3.rbfStatus, WaitForFundingConfirmed.Companion.RbfStatus.None)
        assertTrue(actions3.isEmpty())
    }

    @Test
    fun `recv FundingLocked`() {
        val (alice, bob, _) = init(ChannelType.SupportedChannelType.AnchorOutputs)
        val fundingLockedAlice = FundingLocked(alice.channelId, randomKey().publicKey())
        val fundingLockedBob = FundingLocked(bob.channelId, randomKey().publicKey())
        val (alice1, actionsAlice1) = alice.processEx(ChannelEvent.MessageReceived(fundingLockedBob))
        assertIs<WaitForFundingConfirmed>(alice1)
        assertEquals(alice1.deferred, fundingLockedBob)
        assertTrue(actionsAlice1.isEmpty())
        val (bob1, actionsBob1) = bob.processEx(ChannelEvent.MessageReceived(fundingLockedAlice))
        assertIs<WaitForFundingConfirmed>(bob1)
        assertEquals(bob1.deferred, fundingLockedAlice)
        assertTrue(actionsBob1.isEmpty())
    }

    @Test
    fun `recv FundingLocked -- no remote contribution`() {
        val (alice, bob, _) = init(ChannelType.SupportedChannelType.AnchorOutputs, bobFundingAmount = 0.sat, pushAmount = 0.msat)
        val fundingLockedAlice = FundingLocked(alice.channelId, randomKey().publicKey())
        val fundingLockedBob = FundingLocked(bob.channelId, randomKey().publicKey())
        val (alice1, actionsAlice1) = alice.processEx(ChannelEvent.MessageReceived(fundingLockedBob))
        assertIs<WaitForFundingConfirmed>(alice1)
        assertEquals(alice1.deferred, fundingLockedBob)
        assertTrue(actionsAlice1.isEmpty())
        val (bob1, actionsBob1) = bob.processEx(ChannelEvent.MessageReceived(fundingLockedAlice))
        assertIs<WaitForFundingConfirmed>(bob1)
        assertEquals(bob1.deferred, fundingLockedAlice)
        assertTrue(actionsBob1.isEmpty())
    }

    @Test
    fun `recv Error`() {
        val (_, bob) = init(ChannelType.SupportedChannelType.AnchorOutputs)
        val (bob1, actions1) = bob.processEx(ChannelEvent.MessageReceived(Error(bob.channelId, "oops")))
        assertIs<Closing>(bob1)
        assertNotNull(bob1.localCommitPublished)
        actions1.hasTx(bob.commitments.localCommit.publishableTxs.commitTx.tx)
        assertEquals(2, actions1.findWatches<WatchConfirmed>().size) // commit tx + main output
    }

    @Test
    fun `recv Error (previous funding tx confirms)`() {
        val (alice, bob, txSigsBob) = init(ChannelType.SupportedChannelType.AnchorOutputs)
        val commitTxAlice1 = alice.commitments.localCommit.publishableTxs.commitTx.tx
        val commitTxBob1 = bob.commitments.localCommit.publishableTxs.commitTx.tx
        val fundingTxId1 = alice.commitments.fundingTxId
        val (alice1, bob1) = rbf(alice, bob, txSigsBob)
        val commitTxAlice2 = alice1.commitments.localCommit.publishableTxs.commitTx.tx
        val commitTxBob2 = bob1.commitments.localCommit.publishableTxs.commitTx.tx
        val fundingTxId2 = alice1.commitments.fundingTxId
        assertTrue(fundingTxId1 != fundingTxId2)
        assertTrue(commitTxAlice1.txid != commitTxAlice2.txid)
        assertTrue(commitTxBob1.txid != commitTxBob2.txid)
        run {
            // Bob receives an error and publishes his latest commitment.
            val (bob2, actions2) = bob1.processEx(ChannelEvent.MessageReceived(Error(bob.channelId, "oops")), latestOnly = true)
            assertIs<Closing>(bob2)
            assertFalse(bob2.alternativeCommitments.isEmpty())
            actions2.hasTx(commitTxBob2)
            val lcp1 = bob2.localCommitPublished
            assertNotNull(lcp1)
            assertTrue(lcp1.commitTx.txIn.map { it.outPoint.txid }.contains(fundingTxId2))
            // A previous funding transaction confirms, so Bob publishes the corresponding commit tx.
            val (bob3, actions3) = bob2.processEx(ChannelEvent.WatchReceived(WatchEventConfirmed(bob.channelId, BITCOIN_FUNDING_DEPTHOK, 50, 0, alice.fundingTx.tx.buildUnsignedTx())), latestOnly = true)
            assertIs<Closing>(bob3)
            assertTrue(bob3.alternativeCommitments.isEmpty())
            actions3.hasTx(commitTxBob1)
            val lcp2 = bob3.localCommitPublished
            assertNotNull(lcp2)
            assertTrue(lcp2.commitTx.txIn.map { it.outPoint.txid }.contains(fundingTxId1))
            // Alice publishes her commit tx, Bob reacts by spending his remote main output.
            val (bob4, actions4) = bob3.processEx(ChannelEvent.WatchReceived(WatchEventSpent(bob.channelId, BITCOIN_FUNDING_SPENT, commitTxAlice1)))
            assertIs<Closing>(bob4)
            assertNotNull(bob4.localCommitPublished)
            assertNotNull(bob4.remoteCommitPublished)
            val claimMain = actions4.findTxs().first()
            assertEquals(claimMain.txIn.first().outPoint.txid, commitTxAlice1.txid)
        }
        run {
            // Alice receives an error and publishes her latest commitment.
            val (alice2, actions2) = alice1.processEx(ChannelEvent.MessageReceived(Error(alice.channelId, "oops")), latestOnly = true)
            assertIs<Closing>(alice2)
            assertFalse(alice2.alternativeCommitments.isEmpty())
            actions2.hasTx(commitTxAlice2)
            val lcp1 = alice2.localCommitPublished
            assertNotNull(lcp1)
            assertTrue(lcp1.commitTx.txIn.map { it.outPoint.txid }.contains(fundingTxId2))
            // A previous funding transaction confirms, so Alice publishes the corresponding commit tx.
            val (alice3, actions3) = alice2.processEx(ChannelEvent.WatchReceived(WatchEventConfirmed(alice.channelId, BITCOIN_FUNDING_DEPTHOK, 50, 0, bob.fundingTx.tx.buildUnsignedTx())), latestOnly = true)
            assertIs<Closing>(alice3)
            assertTrue(alice3.alternativeCommitments.isEmpty())
            actions3.hasTx(commitTxAlice1)
            val lcp2 = alice3.localCommitPublished
            assertNotNull(lcp2)
            assertTrue(lcp2.commitTx.txIn.map { it.outPoint.txid }.contains(fundingTxId1))
            // Bob publishes his commit tx, Alice reacts by spending her remote main output.
            val (alice4, actions4) = alice3.processEx(ChannelEvent.WatchReceived(WatchEventSpent(alice.channelId, BITCOIN_FUNDING_SPENT, commitTxBob1)))
            assertIs<Closing>(alice4)
            assertNotNull(alice4.localCommitPublished)
            assertNotNull(alice4.remoteCommitPublished)
            val claimMain = actions4.findTxs().first()
            assertEquals(claimMain.txIn.first().outPoint.txid, commitTxBob1.txid)
        }
    }

    @Test
    fun `recv CMD_CLOSE`() {
        val (alice, bob) = init(ChannelType.SupportedChannelType.AnchorOutputs)
        listOf(alice, bob).forEach { state ->
            val (state1, actions1) = state.processEx(ChannelEvent.ExecuteCommand(CMD_CLOSE(null, null)))
            assertEquals(state, state1)
            actions1.hasCommandError<CommandUnavailableInThisState>()
        }
    }

    @Test
    fun `recv CMD_FORCECLOSE`() {
        val (alice, bob) = init(ChannelType.SupportedChannelType.AnchorOutputs)
        listOf(alice, bob).forEach { state ->
            val (state1, actions1) = state.processEx(ChannelEvent.ExecuteCommand(CMD_FORCECLOSE))
            assertIs<Closing>(state1)
            assertNotNull(state1.localCommitPublished)
            actions1.hasTx(state1.localCommitPublished!!.commitTx)
            actions1.hasTx(state1.localCommitPublished!!.claimMainDelayedOutputTx!!.tx)
            assertEquals(2, actions1.findWatches<WatchConfirmed>().size) // commit tx + main output
        }
    }

    @Test
    fun `recv CMD_FORCECLOSE -- nothing at stake`() {
        val (alice, bob) = init(ChannelType.SupportedChannelType.AnchorOutputs, bobFundingAmount = 0.sat, pushAmount = 0.msat)
        val (bob1, actions1) = bob.processEx(ChannelEvent.ExecuteCommand(CMD_FORCECLOSE))
        assertIs<Aborted>(bob1)
        assertEquals(1, actions1.size)
        val error = actions1.hasOutgoingMessage<Error>()
        assertEquals(ForcedLocalCommit(alice.channelId).message, error.toAscii())
    }

    @Test
    fun `recv NewBlock`() {
        val (alice, bob) = init(ChannelType.SupportedChannelType.AnchorOutputs)
        listOf(alice, bob).forEach { state ->
            run {
                val (state1, actions1) = state.processEx(ChannelEvent.NewBlock(state.currentBlockHeight + 1, Block.RegtestGenesisBlock.header))
                assertEquals(state.copy(currentTip = state1.currentTip), state1)
                assertTrue(actions1.isEmpty())
            }
            run {
                val (state1, actions1) = state.processEx(ChannelEvent.CheckHtlcTimeout)
                assertEquals(state, state1)
                assertTrue(actions1.isEmpty())
            }
        }
    }

    @Test
    fun `recv Disconnected`() {
        val (alice, bob) = init(ChannelType.SupportedChannelType.AnchorOutputs)
        val (alice1, actionsAlice1) = alice.processEx(ChannelEvent.Disconnected)
        assertIs<Offline>(alice1)
        assertTrue(actionsAlice1.isEmpty())
        val (bob1, actionsBob1) = bob.processEx(ChannelEvent.Disconnected)
        assertIs<Offline>(bob1)
        assertTrue(actionsBob1.isEmpty())
    }

    companion object {
        fun init(
            channelType: ChannelType.SupportedChannelType = ChannelType.SupportedChannelType.AnchorOutputs,
            aliceFeatures: Features = TestConstants.Alice.nodeParams.features,
            bobFeatures: Features = TestConstants.Bob.nodeParams.features,
            currentHeight: Int = TestConstants.defaultBlockHeight,
            aliceFundingAmount: Satoshi = TestConstants.aliceFundingAmount,
            bobFundingAmount: Satoshi = TestConstants.bobFundingAmount,
            pushAmount: MilliSatoshi = TestConstants.pushAmount,
        ): Triple<WaitForFundingConfirmed, WaitForFundingConfirmed, TxSignatures> {
            val (alice, commitAlice, bob, commitBob) = WaitForFundingSignedTestsCommon.init(channelType, aliceFeatures, bobFeatures, currentHeight, aliceFundingAmount, bobFundingAmount, pushAmount)
            val (alice1, actionsAlice1) = alice.processEx(ChannelEvent.MessageReceived(commitBob))
            assertIs<WaitForFundingConfirmed>(alice1)
            assertEquals(actionsAlice1.findWatch<WatchConfirmed>().event, BITCOIN_FUNDING_DEPTHOK)
            val (bob1, actionsBob1) = bob.processEx(ChannelEvent.MessageReceived(commitAlice))
            assertIs<WaitForFundingConfirmed>(bob1)
            assertEquals(actionsBob1.findWatch<WatchConfirmed>().event, BITCOIN_FUNDING_DEPTHOK)
            val txSigs = actionsBob1.findOutgoingMessage<TxSignatures>()
            return Triple(alice1, bob1, txSigs)
        }

        fun rbf(alice: WaitForFundingConfirmed, bob: WaitForFundingConfirmed, txSigsBob: TxSignatures): Pair<WaitForFundingConfirmed, WaitForFundingConfirmed> {
            val (alice0, _) = alice.processEx(ChannelEvent.MessageReceived(txSigsBob))
            assertIs<WaitForFundingConfirmed>(alice0)
            val fundingTx = alice0.fundingTx
            assertIs<FullySignedSharedTransaction>(fundingTx)
            // Alice adds a new input that increases her contribution and covers the additional fees.
            val command = run {
                val txOut = fundingTx.tx.localInputs.firstOrNull()?.let { it.previousTx.txOut[it.previousTxOutput.toInt()].copy(amount = 30_000.sat) }!!
                val nextInput = FundingInput(Transaction(2, listOf(TxIn(OutPoint(randomBytes32(), 3), 0)), listOf(txOut), 0), 0)
                CMD_BUMP_FUNDING_FEE(fundingTx.feerate * 1.1, nextInput, 20_000.sat, fundingTx.tx.lockTime + 1)
            }
            val (alice1, actionsAlice1) = alice0.processEx(ChannelEvent.ExecuteCommand(command))
            assertEquals(actionsAlice1.size, 1)
            val txInitRbf = actionsAlice1.findOutgoingMessage<TxInitRbf>()
            assertEquals(txInitRbf.fundingContribution, alice.fundingParams.localAmount + 20_000.sat)
            val (bob1, actionsBob1) = bob.processEx(ChannelEvent.MessageReceived(txInitRbf))
            assertIs<WaitForFundingConfirmed>(bob1)
            assertEquals(actionsBob1.size, 1)
            val txAckRbf = actionsBob1.findOutgoingMessage<TxAckRbf>()
            assertEquals(txAckRbf.fundingContribution, bob.fundingParams.localAmount) // the non-initiator doesn't change its contribution
            val (alice2, actionsAlice2) = alice1.processEx(ChannelEvent.MessageReceived(txAckRbf))
            assertIs<WaitForFundingConfirmed>(alice2)
            assertEquals(actionsAlice2.size, 1)
            // Alice and Bob build the next funding transaction.
            val (alice3, bob2) = completeInteractiveTxRbf(alice2, bob1, actionsAlice2.findOutgoingMessage())
            assertIs<WaitForFundingConfirmed>(alice3)
            val fundingTx1 = alice3.fundingTx
            assertIs<FullySignedSharedTransaction>(fundingTx1)
            assertEquals(fundingTx1.signedTx.lockTime, fundingTx.tx.lockTime + 1)
            assertEquals(alice3.commitments.fundingAmount, alice.commitments.fundingAmount + 20_000.sat)
            assertEquals(alice3.rbfStatus, WaitForFundingConfirmed.Companion.RbfStatus.None)
            assertEquals(bob2.rbfStatus, WaitForFundingConfirmed.Companion.RbfStatus.None)
            return Pair(alice3, bob2)
        }

        private fun completeInteractiveTxRbf(alice: WaitForFundingConfirmed, bob: WaitForFundingConfirmed, messageAlice: InteractiveTxMessage): Pair<WaitForFundingConfirmed, WaitForFundingConfirmed> {
            val (bob1, actionsBob1) = bob.processEx(ChannelEvent.MessageReceived(messageAlice))
            assertIs<WaitForFundingConfirmed>(bob1)
            assertEquals(actionsBob1.size, 1)
            val messageBob = actionsBob1.findOutgoingMessage<InteractiveTxConstructionMessage>()
            val (alice1, actionsAlice1) = alice.processEx(ChannelEvent.MessageReceived(messageBob))
            assertIs<WaitForFundingConfirmed>(alice1)
            return when (val txComplete = actionsAlice1.findOutgoingMessageOpt<TxComplete>()) {
                null -> {
                    assertEquals(actionsAlice1.size, 1)
                    completeInteractiveTxRbf(alice1, bob1, actionsAlice1.findOutgoingMessage())
                }
                else -> {
                    assertEquals(actionsAlice1.size, 2)
                    val commitSigAlice = actionsAlice1.findOutgoingMessage<CommitSig>()
                    val (bob2, actionsBob2) = bob1.processEx(ChannelEvent.MessageReceived(txComplete))
                    assertEquals(actionsBob2.size, 1)
                    val commitSigBob = actionsBob2.findOutgoingMessage<CommitSig>()
                    val (alice2, actionsAlice2) = alice1.processEx(ChannelEvent.MessageReceived(commitSigBob), latestOnly = true)
                    assertEquals(actionsAlice2.size, 2)
                    actionsAlice2.has<ChannelAction.Storage.StoreState>()
                    val watchAlice = actionsAlice2.findWatch<WatchConfirmed>()
                    val (bob3, actionsBob3) = bob2.processEx(ChannelEvent.MessageReceived(commitSigAlice), latestOnly = true)
                    assertIs<WaitForFundingConfirmed>(bob3)
                    assertEquals(actionsBob3.size, 3)
                    actionsBob3.has<ChannelAction.Storage.StoreState>()
                    val watchBob = actionsBob3.findWatch<WatchConfirmed>()
                    val txSigsBob = actionsBob3.findOutgoingMessage<TxSignatures>()
                    val (alice3, actionsAlice3) = alice2.processEx(ChannelEvent.MessageReceived(txSigsBob), latestOnly = true)
                    assertIs<WaitForFundingConfirmed>(alice3)
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
