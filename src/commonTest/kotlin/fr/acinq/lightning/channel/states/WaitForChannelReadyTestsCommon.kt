package fr.acinq.lightning.channel.states

import fr.acinq.bitcoin.Satoshi
import fr.acinq.bitcoin.Transaction
import fr.acinq.lightning.Features
import fr.acinq.lightning.MilliSatoshi
import fr.acinq.lightning.blockchain.*
import fr.acinq.lightning.channel.*
import fr.acinq.lightning.tests.TestConstants
import fr.acinq.lightning.tests.utils.LightningTestSuite
import fr.acinq.lightning.utils.msat
import fr.acinq.lightning.utils.sat
import fr.acinq.lightning.utils.toMilliSatoshi
import fr.acinq.lightning.wire.ChannelReady
import fr.acinq.lightning.wire.Error
import fr.acinq.lightning.wire.TxSignatures
import fr.acinq.lightning.wire.Warning
import kotlin.test.*

class WaitForChannelReadyTestsCommon : LightningTestSuite() {

    @Test
    fun `recv TxSignatures -- zero conf`() {
        val (alice, _, bob, _) = init(ChannelType.SupportedChannelType.AnchorOutputsZeroReserve, zeroConf = true)
        val (alice1, actionsAlice1) = alice.process(ChannelCommand.MessageReceived(getFundingSigs(bob)))
        assertIs<LNChannel<WaitForChannelReady>>(alice1)
        assertEquals(actionsAlice1.size, 2)
        val fundingTx = actionsAlice1.find<ChannelAction.Blockchain.PublishTx>().tx
        assertEquals(alice.commitments.latest.fundingTxId, fundingTx.txid)
        actionsAlice1.has<ChannelAction.Storage.StoreState>()
        val (bob1, actionsBob1) = bob.process(ChannelCommand.MessageReceived(getFundingSigs(alice)))
        assertIs<LNChannel<WaitForChannelReady>>(bob1)
        assertEquals(actionsBob1.size, 2)
        assertEquals(actionsBob1.find<ChannelAction.Blockchain.PublishTx>().tx, fundingTx)
        actionsBob1.has<ChannelAction.Storage.StoreState>()
    }

    @Test
    fun `recv TxSignatures and restart -- zero conf`() {
        val (alice, _, bob, _) = init(ChannelType.SupportedChannelType.AnchorOutputsZeroReserve, zeroConf = true)
        val (alice1, actionsAlice1) = alice.process(ChannelCommand.MessageReceived(getFundingSigs(bob)))
        val fundingTx = actionsAlice1.find<ChannelAction.Blockchain.PublishTx>().tx
        val (alice2, actionsAlice2) = LNChannel(alice1.ctx, WaitForInit).process(ChannelCommand.Restore(alice1.state as ChannelStateWithCommitments))
        assertIs<LNChannel<Offline>>(alice2)
        assertEquals(actionsAlice2.size, 2)
        assertEquals(actionsAlice2.find<ChannelAction.Blockchain.PublishTx>().tx, fundingTx)
        assertEquals(actionsAlice2.findWatch<WatchConfirmed>().txId, fundingTx.txid)
    }

    @Test
    fun `recv TxSignatures -- duplicate`() {
        val (alice, _, _, _) = init()
        val (alice1, actions1) = alice.process(ChannelCommand.MessageReceived(TxSignatures(alice.channelId, alice.commitments.latest.fundingTxId.reversed(), listOf())))
        assertEquals(alice1, alice)
        assertTrue(actions1.isEmpty())
    }

    @Test
    fun `recv TxSignatures -- invalid`() {
        val (alice, _, bob, _) = init(ChannelType.SupportedChannelType.AnchorOutputsZeroReserve, zeroConf = true)
        val (alice1, actionsAlice1) = alice.process(ChannelCommand.MessageReceived(getFundingSigs(bob).copy(witnesses = listOf())))
        assertEquals(alice, alice1)
        assertEquals(actionsAlice1.size, 1)
        actionsAlice1.hasOutgoingMessage<Warning>()
    }

    @Test
    fun `recv ChannelReady`() {
        val (alice, channelReadyAlice, bob, channelReadyBob) = init()
        val (alice1, actionsAlice1) = alice.process(ChannelCommand.MessageReceived(channelReadyBob))
        assertIs<LNChannel<Normal>>(alice1)
        assertEquals(3, actionsAlice1.size)
        actionsAlice1.has<ChannelAction.Storage.StoreState>()
        assertEquals(actionsAlice1.findWatch<WatchConfirmed>().event, BITCOIN_FUNDING_DEEPLYBURIED)
        val (bob1, actionsBob1) = bob.process(ChannelCommand.MessageReceived(channelReadyAlice))
        assertIs<LNChannel<Normal>>(bob1)
        assertEquals(3, actionsBob1.size)
        actionsBob1.has<ChannelAction.Storage.StoreState>()
        assertEquals(actionsBob1.findWatch<WatchConfirmed>().event, BITCOIN_FUNDING_DEEPLYBURIED)
    }

    @Test
    fun `recv FundingLocked -- push amount on both sides`() {
        val (alice, fundingLockedAlice, bob, fundingLockedBob) = init(bobPushAmount = 25_000_000.msat)
        val aliceBalance = TestConstants.aliceFundingAmount.toMilliSatoshi() - TestConstants.alicePushAmount + 25_000_000.msat
        assertEquals(aliceBalance, 825_000_000.msat)
        val bobBalance = TestConstants.bobFundingAmount.toMilliSatoshi() - 25_000_000.msat + TestConstants.alicePushAmount
        assertEquals(bobBalance, 175_000_000.msat)
        val (alice1, _) = alice.process(ChannelCommand.MessageReceived(fundingLockedBob))
        assertIs<LNChannel<Normal>>(alice1)
        assertEquals(alice1.commitments.latest.localCommit.spec.toLocal, aliceBalance)
        assertEquals(alice1.commitments.latest.localCommit.spec.toRemote, bobBalance)
        val (bob1, _) = bob.process(ChannelCommand.MessageReceived(fundingLockedAlice))
        assertIs<LNChannel<Normal>>(bob1)
        assertEquals(bob1.commitments.latest.localCommit.spec.toLocal, bobBalance)
        assertEquals(bob1.commitments.latest.localCommit.spec.toRemote, aliceBalance)
    }

    @Test
    fun `recv FundingLocked -- push amount on both sides -- zero-conf`() {
        val (alice, fundingLockedAlice, bob, fundingLockedBob) = init(bobPushAmount = 25_000_000.msat, zeroConf = true)
        val aliceBalance = TestConstants.aliceFundingAmount.toMilliSatoshi() - TestConstants.alicePushAmount + 25_000_000.msat
        assertEquals(aliceBalance, 825_000_000.msat)
        val bobBalance = TestConstants.bobFundingAmount.toMilliSatoshi() - 25_000_000.msat + TestConstants.alicePushAmount
        assertEquals(bobBalance, 175_000_000.msat)
        val (alice1, _) = alice.process(ChannelCommand.MessageReceived(fundingLockedBob))
        assertIs<LNChannel<Normal>>(alice1)
        assertEquals(alice1.commitments.latest.localCommit.spec.toLocal, aliceBalance)
        assertEquals(alice1.commitments.latest.localCommit.spec.toRemote, bobBalance)
        val (bob1, _) = bob.process(ChannelCommand.MessageReceived(fundingLockedAlice))
        assertIs<LNChannel<Normal>>(bob1)
        assertEquals(bob1.commitments.latest.localCommit.spec.toLocal, bobBalance)
        assertEquals(bob1.commitments.latest.localCommit.spec.toRemote, aliceBalance)
    }

    @Test
    fun `recv BITCOIN_FUNDING_SPENT -- remote commit`() {
        val (alice, _, bob, _) = init()
        // bob publishes his commitment tx
        run {
            val bobCommitTx = bob.commitments.latest.localCommit.publishableTxs.commitTx.tx
            val (alice1, actions1) = alice.process(ChannelCommand.WatchReceived(WatchEventSpent(alice.channelId, BITCOIN_FUNDING_SPENT, bobCommitTx)))
            assertIs<LNChannel<Closing>>(alice1)
            assertNotNull(alice1.state.remoteCommitPublished)
            assertEquals(1, actions1.findTxs().size)
            assertEquals(2, actions1.findWatches<WatchConfirmed>().size) // commit tx + main output
        }
        // alice publishes her commitment tx
        run {
            val aliceCommitTx = alice.commitments.latest.localCommit.publishableTxs.commitTx.tx
            val (bob1, actions1) = bob.process(ChannelCommand.WatchReceived(WatchEventSpent(bob.channelId, BITCOIN_FUNDING_SPENT, aliceCommitTx)))
            assertIs<LNChannel<Closing>>(bob1)
            assertNotNull(bob1.state.remoteCommitPublished)
            assertEquals(1, actions1.findTxs().size)
            assertEquals(2, actions1.findWatches<WatchConfirmed>().size) // commit tx + main output
        }
    }

    @Test
    fun `recv BITCOIN_FUNDING_SPENT -- other commit`() {
        val (alice, _, _) = init()
        val aliceCommitTx = alice.commitments.latest.localCommit.publishableTxs.commitTx.tx
        val unknownTx = Transaction(2, aliceCommitTx.txIn, listOf(), 0)
        val (alice1, actions1) = alice.process(ChannelCommand.WatchReceived(WatchEventSpent(alice.channelId, BITCOIN_FUNDING_SPENT, unknownTx)))
        assertIs<LNChannel<ErrorInformationLeak>>(alice1)
        assertTrue(actions1.isEmpty())
    }

    @Test
    fun `recv Error`() {
        val (alice, _, bob, _) = init()
        listOf(alice, bob).forEach { state ->
            val commitTx = state.commitments.latest.localCommit.publishableTxs.commitTx.tx
            val (state1, actions1) = state.process(ChannelCommand.MessageReceived(Error(state.channelId, "no lightning for you sir")))
            assertIs<LNChannel<Closing>>(state1)
            assertNotNull(state1.state.localCommitPublished)
            assertNull(actions1.findOutgoingMessageOpt<Error>())
            actions1.hasTx(commitTx)
            actions1.hasWatch<WatchConfirmed>()
        }
    }

    @Test
    fun `recv CMD_CLOSE`() {
        val (alice, _, bob, _) = init()
        listOf(alice, bob).forEach { state ->
            val (state1, actions1) = state.process(ChannelCommand.ExecuteCommand(CMD_CLOSE(null, null)))
            assertEquals(state, state1)
            assertEquals(1, actions1.size)
            actions1.hasCommandError<CommandUnavailableInThisState>()
        }
    }

    @Test
    fun `recv CMD_FORCECLOSE`() {
        val (alice, _, bob, _) = init()
        listOf(alice, bob).forEach { state ->
            val commitTx = state.commitments.latest.localCommit.publishableTxs.commitTx.tx
            val (state1, actions1) = state.process(ChannelCommand.ExecuteCommand(CMD_FORCECLOSE))
            assertIs<LNChannel<Closing>>(state1)
            assertNotNull(state1.state.localCommitPublished)
            val error = actions1.hasOutgoingMessage<Error>()
            assertEquals(ForcedLocalCommit(bob.channelId).message, error.toAscii())
            actions1.hasTx(commitTx)
            actions1.hasWatch<WatchConfirmed>()
        }
    }

    @Test
    fun `recv CMD_FORCECLOSE -- nothing at stake`() {
        val (_, _, bob, _) = init(bobFundingAmount = 0.sat, alicePushAmount = 0.msat)
        val (bob1, actions1) = bob.process(ChannelCommand.ExecuteCommand(CMD_FORCECLOSE))
        assertIs<LNChannel<Aborted>>(bob1)
        assertEquals(1, actions1.size)
        val error = actions1.hasOutgoingMessage<Error>()
        assertEquals(ForcedLocalCommit(bob.channelId).message, error.toAscii())
    }

    private fun getFundingSigs(channel: LNChannel<WaitForChannelReady>): TxSignatures {
        assertIs<LocalFundingStatus.UnconfirmedFundingTx>(channel.commitments.latest.localFundingStatus)
        return (channel.commitments.latest.localFundingStatus as LocalFundingStatus.UnconfirmedFundingTx).sharedTx.localSigs
    }

    companion object {
        data class Fixture(val alice: LNChannel<WaitForChannelReady>, val channelReadyAlice: ChannelReady, val bob: LNChannel<WaitForChannelReady>, val channelReadyBob: ChannelReady)

        fun init(
            channelType: ChannelType.SupportedChannelType = ChannelType.SupportedChannelType.AnchorOutputs,
            aliceFeatures: Features = TestConstants.Alice.nodeParams.features,
            bobFeatures: Features = TestConstants.Bob.nodeParams.features,
            currentHeight: Int = TestConstants.defaultBlockHeight,
            aliceFundingAmount: Satoshi = TestConstants.aliceFundingAmount,
            bobFundingAmount: Satoshi = TestConstants.bobFundingAmount,
            alicePushAmount: MilliSatoshi = TestConstants.alicePushAmount,
            bobPushAmount: MilliSatoshi = TestConstants.bobPushAmount,
            zeroConf: Boolean = false,
        ): Fixture {
            return if (zeroConf) {
                val (alice, commitAlice, bob, commitBob) = WaitForFundingSignedTestsCommon.init(channelType, aliceFeatures, bobFeatures, currentHeight, aliceFundingAmount, bobFundingAmount, alicePushAmount, bobPushAmount, zeroConf)
                val (alice1, actionsAlice1) = alice.process(ChannelCommand.MessageReceived(commitBob))
                assertIs<LNChannel<WaitForChannelReady>>(alice1)
                assertEquals(actionsAlice1.findWatch<WatchConfirmed>().event, BITCOIN_FUNDING_DEPTHOK)
                val channelReadyAlice = actionsAlice1.findOutgoingMessage<ChannelReady>()
                val (bob1, actionsBob1) = bob.process(ChannelCommand.MessageReceived(commitAlice))
                assertIs<LNChannel<WaitForChannelReady>>(bob1)
                assertEquals(actionsBob1.findWatch<WatchConfirmed>().event, BITCOIN_FUNDING_DEPTHOK)
                val channelReadyBob = actionsBob1.findOutgoingMessage<ChannelReady>()
                Fixture(alice1, channelReadyAlice, bob1, channelReadyBob)
            } else {
                val (alice, bob, txSigsBob) = WaitForFundingConfirmedTestsCommon.init(channelType, aliceFeatures, bobFeatures, currentHeight, aliceFundingAmount, bobFundingAmount, alicePushAmount, bobPushAmount)
                val (alice1, actionsAlice1) = alice.process(ChannelCommand.MessageReceived(txSigsBob))
                assertIs<LNChannel<WaitForFundingConfirmed>>(alice1)
                val fundingTx = actionsAlice1.find<ChannelAction.Blockchain.PublishTx>().tx
                assertEquals(fundingTx.hash, alice1.state.latestFundingTx.sharedTx.localSigs.txHash)
                val (bob1, _) = bob.process(ChannelCommand.MessageReceived(alice1.state.latestFundingTx.sharedTx.localSigs))
                val (alice2, actionsAlice2) = alice1.process(ChannelCommand.WatchReceived(WatchEventConfirmed(alice.channelId, BITCOIN_FUNDING_DEPTHOK, 42, 0, fundingTx)))
                assertIs<LNChannel<WaitForChannelReady>>(alice2)
                val channelReadyAlice = actionsAlice2.findOutgoingMessage<ChannelReady>()
                val (bob2, actionsBob2) = bob1.process(ChannelCommand.WatchReceived(WatchEventConfirmed(bob.channelId, BITCOIN_FUNDING_DEPTHOK, 42, 0, fundingTx)))
                assertIs<LNChannel<WaitForChannelReady>>(bob2)
                val channelReadyBob = actionsBob2.findOutgoingMessage<ChannelReady>()
                Fixture(alice2, channelReadyAlice, bob2, channelReadyBob)
            }
        }
    }

}