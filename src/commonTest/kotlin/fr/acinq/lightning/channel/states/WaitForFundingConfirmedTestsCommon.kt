package fr.acinq.lightning.channel.states

import fr.acinq.bitcoin.Block
import fr.acinq.bitcoin.Satoshi
import fr.acinq.bitcoin.Script
import fr.acinq.bitcoin.byteVector
import fr.acinq.lightning.Feature
import fr.acinq.lightning.Features
import fr.acinq.lightning.Lightning.randomBytes
import fr.acinq.lightning.Lightning.randomKey
import fr.acinq.lightning.MilliSatoshi
import fr.acinq.lightning.blockchain.*
import fr.acinq.lightning.channel.*
import fr.acinq.lightning.channel.TestsHelper.processEx
import fr.acinq.lightning.tests.TestConstants
import fr.acinq.lightning.tests.utils.LightningTestSuite
import fr.acinq.lightning.utils.msat
import fr.acinq.lightning.utils.sat
import fr.acinq.lightning.wire.Error
import fr.acinq.lightning.wire.FundingLocked
import fr.acinq.lightning.wire.TxSignatures
import kotlin.test.*

class WaitForFundingConfirmedTestsCommon : LightningTestSuite() {

    @Test
    fun `recv TxSignatures`() {
        val (alice, bob, txSigsBob) = init(ChannelType.SupportedChannelType.AnchorOutputs, TestConstants.aliceFundingAmount, TestConstants.bobFundingAmount, TestConstants.pushAmount)
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
        val (alice, _, txSigsBob) = init(ChannelType.SupportedChannelType.AnchorOutputs, TestConstants.aliceFundingAmount, TestConstants.bobFundingAmount, TestConstants.pushAmount)
        val (alice1, _) = alice.processEx(ChannelEvent.MessageReceived(txSigsBob))
        assertIs<WaitForFundingConfirmed>(alice1)
        assertIs<FullySignedSharedTransaction>(alice1.fundingTx)
        val (alice2, actionsAlice2) = alice1.processEx(ChannelEvent.MessageReceived(txSigsBob))
        assertEquals(alice1, alice2)
        assertTrue(actionsAlice2.isEmpty())
    }

    @Test
    fun `recv TxSignatures -- invalid`() {
        val (alice, _, txSigsBob) = init(ChannelType.SupportedChannelType.AnchorOutputs, TestConstants.aliceFundingAmount, TestConstants.bobFundingAmount, TestConstants.pushAmount)
        val (alice1, actionsAlice1) = alice.processEx(ChannelEvent.MessageReceived(txSigsBob.copy(witnesses = listOf(Script.witnessPay2wpkh(randomKey().publicKey(), randomBytes(72).byteVector())))))
        // Alice sends an error, but stays in the same state because the funding tx may still confirm.
        actionsAlice1.findOutgoingMessage<Error>()
        assertEquals(alice, alice1)
    }

    @Test
    fun `recv BITCOIN_FUNDING_DEPTHOK`() {
        val (alice, bob, txSigsBob) = init(ChannelType.SupportedChannelType.AnchorOutputs, TestConstants.aliceFundingAmount, TestConstants.bobFundingAmount, TestConstants.pushAmount)
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
        val (alice, _, _) = init(ChannelType.SupportedChannelType.AnchorOutputs, TestConstants.aliceFundingAmount, TestConstants.bobFundingAmount, TestConstants.pushAmount)
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
    fun `recv FundingLocked`() {
        val (alice, bob, _) = init(ChannelType.SupportedChannelType.AnchorOutputs, TestConstants.aliceFundingAmount, TestConstants.bobFundingAmount, TestConstants.pushAmount)
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
        val (alice, bob, _) = init(ChannelType.SupportedChannelType.AnchorOutputs, TestConstants.aliceFundingAmount, 0.sat, 0.msat)
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
        val (_, bob) = init(ChannelType.SupportedChannelType.AnchorOutputs, TestConstants.aliceFundingAmount, TestConstants.bobFundingAmount, TestConstants.pushAmount)
        val (bob1, actions1) = bob.processEx(ChannelEvent.MessageReceived(Error(bob.channelId, "oops")))
        assertIs<Closing>(bob1)
        assertNotNull(bob1.localCommitPublished)
        actions1.hasTx(bob.commitments.localCommit.publishableTxs.commitTx.tx)
        assertEquals(2, actions1.findWatches<WatchConfirmed>().size) // commit tx + main output
    }

    @Test
    fun `recv CMD_CLOSE`() {
        val (alice, bob) = init(ChannelType.SupportedChannelType.AnchorOutputs, TestConstants.aliceFundingAmount, TestConstants.bobFundingAmount, TestConstants.pushAmount)
        listOf(alice, bob).forEach { state ->
            val (state1, actions1) = state.processEx(ChannelEvent.ExecuteCommand(CMD_CLOSE(null, null)))
            assertEquals(state, state1)
            actions1.hasCommandError<CommandUnavailableInThisState>()
        }
    }

    @Test
    fun `recv CMD_FORCECLOSE`() {
        val (alice, bob) = init(ChannelType.SupportedChannelType.AnchorOutputs, TestConstants.aliceFundingAmount, TestConstants.bobFundingAmount, TestConstants.pushAmount)
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
        val (alice, bob) = init(ChannelType.SupportedChannelType.AnchorOutputs, TestConstants.aliceFundingAmount, 0.sat, 0.msat)
        val (bob1, actions1) = bob.processEx(ChannelEvent.ExecuteCommand(CMD_FORCECLOSE))
        assertIs<Aborted>(bob1)
        assertEquals(1, actions1.size)
        val error = actions1.hasOutgoingMessage<Error>()
        assertEquals(ForcedLocalCommit(alice.channelId).message, error.toAscii())
    }

    @Test
    fun `recv NewBlock`() {
        val (alice, bob) = init(ChannelType.SupportedChannelType.AnchorOutputs, TestConstants.aliceFundingAmount, TestConstants.bobFundingAmount, TestConstants.pushAmount)
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
        val (alice, bob) = init(ChannelType.SupportedChannelType.AnchorOutputs, TestConstants.aliceFundingAmount, TestConstants.bobFundingAmount, TestConstants.pushAmount)
        val (alice1, actionsAlice1) = alice.processEx(ChannelEvent.Disconnected)
        assertIs<Offline>(alice1)
        assertTrue(actionsAlice1.isEmpty())
        val (bob1, actionsBob1) = bob.processEx(ChannelEvent.Disconnected)
        assertIs<Offline>(bob1)
        assertTrue(actionsBob1.isEmpty())
    }

    companion object {
        fun init(
            channelType: ChannelType.SupportedChannelType,
            aliceFundingAmount: Satoshi,
            bobFundingAmount: Satoshi,
            pushAmount: MilliSatoshi,
            currentHeight: Int = TestConstants.defaultBlockHeight,
            aliceFeatures: Features = TestConstants.Alice.nodeParams.features,
            bobFeatures: Features = TestConstants.Bob.nodeParams.features,
        ): Triple<WaitForFundingConfirmed, WaitForFundingConfirmed, TxSignatures> {
            val (alice, commitAlice, bob, commitBob) = WaitForFundingSignedTestsCommon.init(channelType, currentHeight, aliceFundingAmount, bobFundingAmount, pushAmount, aliceFeatures, bobFeatures)
            val (alice1, _) = alice.processEx(ChannelEvent.MessageReceived(commitBob))
            assertIs<WaitForFundingConfirmed>(alice1)
            val (bob1, actionsBob1) = bob.processEx(ChannelEvent.MessageReceived(commitAlice))
            assertIs<WaitForFundingConfirmed>(bob1)
            val txSigs = actionsBob1.findOutgoingMessage<TxSignatures>()
            if (bobFeatures.hasFeature(Feature.ChannelBackupClient)) {
                assertFalse(txSigs.channelData.isEmpty())
            }
            return Triple(alice1, bob1, txSigs)
        }
    }

}
