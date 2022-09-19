package fr.acinq.lightning.channel.states

import fr.acinq.bitcoin.Satoshi
import fr.acinq.bitcoin.Transaction
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

class WaitForFundingLockedTestsCommon : LightningTestSuite() {

    @Test
    fun `recv FundingLocked`() {
        val (alice, fundingLockedAlice, bob, fundingLockedBob) = init()
        val (alice1, actionsAlice1) = alice.processEx(ChannelEvent.MessageReceived(fundingLockedBob))
        assertIs<Normal>(alice1)
        assertEquals(2, actionsAlice1.size)
        actionsAlice1.has<ChannelAction.Storage.StoreState>()
        assertEquals(actionsAlice1.findWatch<WatchConfirmed>().event, BITCOIN_FUNDING_DEEPLYBURIED)
        val (bob1, actionsBob1) = bob.processEx(ChannelEvent.MessageReceived(fundingLockedAlice))
        assertIs<Normal>(bob1)
        assertEquals(2, actionsBob1.size)
        actionsBob1.has<ChannelAction.Storage.StoreState>()
        assertEquals(actionsBob1.findWatch<WatchConfirmed>().event, BITCOIN_FUNDING_DEEPLYBURIED)
    }

    @Test
    fun `recv BITCOIN_FUNDING_SPENT -- remote commit`() {
        val (alice, _, bob, _) = init()
        // bob publishes his commitment tx
        run {
            val bobCommitTx = bob.commitments.localCommit.publishableTxs.commitTx.tx
            val (alice1, actions1) = alice.processEx(ChannelEvent.WatchReceived(WatchEventSpent(alice.channelId, BITCOIN_FUNDING_SPENT, bobCommitTx)))
            assertIs<Closing>(alice1)
            assertNotNull(alice1.remoteCommitPublished)
            assertEquals(1, actions1.findTxs().size)
            assertEquals(2, actions1.findWatches<WatchConfirmed>().size) // commit tx + main output
        }
        // alice publishes her commitment tx
        run {
            val aliceCommitTx = alice.commitments.localCommit.publishableTxs.commitTx.tx
            val (bob1, actions1) = bob.processEx(ChannelEvent.WatchReceived(WatchEventSpent(bob.channelId, BITCOIN_FUNDING_SPENT, aliceCommitTx)))
            assertIs<Closing>(bob1)
            assertNotNull(bob1.remoteCommitPublished)
            assertEquals(1, actions1.findTxs().size)
            assertEquals(2, actions1.findWatches<WatchConfirmed>().size) // commit tx + main output
        }
    }

    @Test
    fun `recv BITCOIN_FUNDING_SPENT -- other commit`() {
        val (alice, _, _) = init()
        val aliceCommitTx = alice.commitments.localCommit.publishableTxs.commitTx.tx
        val unknownTx = Transaction(2, aliceCommitTx.txIn, listOf(), 0)
        val (alice1, actions1) = alice.processEx(ChannelEvent.WatchReceived(WatchEventSpent(alice.channelId, BITCOIN_FUNDING_SPENT, unknownTx)))
        assertIs<ErrorInformationLeak>(alice1)
        assertTrue(actions1.isEmpty())
    }

    @Test
    fun `recv Error`() {
        val (alice, _, bob, _) = init()
        listOf(alice, bob).forEach { state ->
            val commitTx = state.commitments.localCommit.publishableTxs.commitTx.tx
            val (state1, actions1) = state.processEx(ChannelEvent.MessageReceived(Error(state.channelId, "no lightning for you sir")))
            assertIs<Closing>(state1)
            assertNotNull(state1.localCommitPublished)
            assertNull(actions1.findOutgoingMessageOpt<Error>())
            actions1.hasTx(commitTx)
            actions1.hasWatch<WatchConfirmed>()
        }
    }

    @Test
    fun `recv CMD_CLOSE`() {
        val (alice, _, bob, _) = init()
        listOf(alice, bob).forEach { state ->
            val (state1, actions1) = state.processEx(ChannelEvent.ExecuteCommand(CMD_CLOSE(null, null)))
            assertEquals(state, state1)
            assertEquals(1, actions1.size)
            actions1.hasCommandError<CommandUnavailableInThisState>()
        }
    }

    @Test
    fun `recv CMD_FORCECLOSE`() {
        val (alice, _, bob, _) = init()
        listOf(alice, bob).forEach { state ->
            val commitTx = state.commitments.localCommit.publishableTxs.commitTx.tx
            val (state1, actions1) = state.processEx(ChannelEvent.ExecuteCommand(CMD_FORCECLOSE))
            assertIs<Closing>(state1)
            assertNotNull(state1.localCommitPublished)
            val error = actions1.hasOutgoingMessage<Error>()
            assertEquals(ForcedLocalCommit(bob.channelId).message, error.toAscii())
            actions1.hasTx(commitTx)
            actions1.hasWatch<WatchConfirmed>()
        }
    }

    @Test
    fun `recv CMD_FORCECLOSE -- nothing at stake`() {
        val (_, _, bob, _) = init(bobFundingAmount = 0.sat, pushAmount = 0.msat)
        val (bob1, actions1) = bob.processEx(ChannelEvent.ExecuteCommand(CMD_FORCECLOSE))
        assertIs<Aborted>(bob1)
        assertEquals(1, actions1.size)
        val error = actions1.hasOutgoingMessage<Error>()
        assertEquals(ForcedLocalCommit(bob.channelId).message, error.toAscii())
    }

    companion object {
        data class Fixture(val alice: WaitForFundingLocked, val fundingLockedAlice: FundingLocked, val bob: WaitForFundingLocked, val fundingLockedBob: FundingLocked)

        fun init(
            channelType: ChannelType.SupportedChannelType = ChannelType.SupportedChannelType.AnchorOutputs,
            currentHeight: Int = TestConstants.defaultBlockHeight,
            aliceFundingAmount: Satoshi = TestConstants.aliceFundingAmount,
            bobFundingAmount: Satoshi = TestConstants.bobFundingAmount,
            pushAmount: MilliSatoshi = TestConstants.pushAmount
        ): Fixture {
            val (alice, bob, txSigsBob) = WaitForFundingConfirmedTestsCommon.init(channelType, aliceFundingAmount, bobFundingAmount, pushAmount, currentHeight)
            val (alice1, actionsAlice1) = alice.processEx(ChannelEvent.MessageReceived(txSigsBob))
            val txSigsAlice = actionsAlice1.hasOutgoingMessage<TxSignatures>()
            val fundingTx = actionsAlice1.find<ChannelAction.Blockchain.PublishTx>().tx
            val (bob1, _) = bob.processEx(ChannelEvent.MessageReceived(txSigsAlice))
            val (alice2, actionsAlice2) = alice1.processEx(ChannelEvent.WatchReceived(WatchEventConfirmed(alice.channelId, BITCOIN_FUNDING_DEPTHOK, 42, 0, fundingTx)))
            assertIs<WaitForFundingLocked>(alice2)
            val fundingLockedAlice = actionsAlice2.findOutgoingMessage<FundingLocked>()
            val (bob2, actionsBob2) = bob1.processEx(ChannelEvent.WatchReceived(WatchEventConfirmed(bob.channelId, BITCOIN_FUNDING_DEPTHOK, 42, 0, fundingTx)))
            assertIs<WaitForFundingLocked>(bob2)
            val fundingLockedBob = actionsBob2.findOutgoingMessage<FundingLocked>()
            return Fixture(alice2, fundingLockedAlice, bob2, fundingLockedBob)
        }
    }

}