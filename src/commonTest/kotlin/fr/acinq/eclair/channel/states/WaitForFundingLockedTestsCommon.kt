package fr.acinq.eclair.channel.states

import fr.acinq.bitcoin.Satoshi
import fr.acinq.bitcoin.Transaction
import fr.acinq.bitcoin.TxOut
import fr.acinq.eclair.blockchain.*
import fr.acinq.eclair.channel.*
import fr.acinq.eclair.tests.TestConstants
import fr.acinq.eclair.tests.utils.EclairTestSuite
import fr.acinq.eclair.utils.sat
import fr.acinq.eclair.wire.*
import kotlin.test.*

class WaitForFundingLockedTestsCommon : EclairTestSuite() {

    @Test
    fun `recv FundingLocked`() {
        val (alice, bob, fundingLocked) = init()
        val (alice1, actionsAlice1) = alice.process(ChannelEvent.MessageReceived(fundingLocked.second))
        assertTrue(alice1 is Normal)
        assertEquals(2, actionsAlice1.size)
        actionsAlice1.has<ChannelAction.Storage.StoreState>()
        assertEquals(actionsAlice1.findWatch<WatchConfirmed>().event, BITCOIN_FUNDING_DEEPLYBURIED)
        val (bob1, actionsBob1) = bob.process(ChannelEvent.MessageReceived(fundingLocked.first))
        assertTrue(bob1 is Normal)
        assertEquals(2, actionsBob1.size)
        actionsBob1.has<ChannelAction.Storage.StoreState>()
        assertEquals(actionsBob1.findWatch<WatchConfirmed>().event, BITCOIN_FUNDING_DEEPLYBURIED)
    }

    @Test
    fun `recv BITCOIN_FUNDING_SPENT (remote commit)`() {
        val (alice, bob, _) = init()
        // bob publishes his commitment tx
        run {
            val bobCommitTx = bob.commitments.localCommit.publishableTxs.commitTx.tx
            val (alice1, actions1) = alice.process(ChannelEvent.WatchReceived(WatchEventSpent(alice.channelId, BITCOIN_FUNDING_SPENT, bobCommitTx)))
            assertTrue(alice1 is Closing)
            assertNotNull(alice1.remoteCommitPublished)
            assertEquals(1, actions1.findTxs().size)
            assertEquals(2, actions1.findWatches<WatchConfirmed>().size) // commit tx + main output
        }
        // alice publishes her commitment tx
        run {
            val aliceCommitTx = alice.commitments.localCommit.publishableTxs.commitTx.tx
            val (bob1, actions1) = bob.process(ChannelEvent.WatchReceived(WatchEventSpent(bob.channelId, BITCOIN_FUNDING_SPENT, aliceCommitTx)))
            assertTrue(bob1 is Closing)
            assertNotNull(bob1.remoteCommitPublished)
            assertEquals(1, actions1.findTxs().size)
            assertEquals(2, actions1.findWatches<WatchConfirmed>().size) // commit tx + main output
        }
    }

    @Test
    fun `recv BITCOIN_FUNDING_SPENT (other commit)`() {
        val (alice, _, _) = init()
        val aliceCommitTx = alice.commitments.localCommit.publishableTxs.commitTx.tx
        val unknownTx = Transaction(2, aliceCommitTx.txIn, listOf(), 0)
        val (alice1, actions1) = alice.process(ChannelEvent.WatchReceived(WatchEventSpent(alice.channelId, BITCOIN_FUNDING_SPENT, unknownTx)))
        assertTrue(alice1 is ErrorInformationLeak)
        assertTrue(actions1.isEmpty())
    }

    @Test
    fun `recv Error`() {
        val (alice, bob, _) = init()
        listOf(alice, bob).forEach { state ->
            val commitTx = state.commitments.localCommit.publishableTxs.commitTx.tx
            val (state1, actions1) = state.process(ChannelEvent.MessageReceived(Error(state.channelId, "no lightning for you sir")))
            assertTrue(state1 is Closing)
            assertNotNull(state1.localCommitPublished)
            assertNull(actions1.findOutgoingMessageOpt<Error>())
            actions1.hasTx(commitTx)
            actions1.hasWatch<WatchConfirmed>()
        }
    }

    @Test
    fun `recv CMD_CLOSE`() {
        val (alice, bob, _) = init()
        listOf(alice, bob).forEach { state ->
            val (state1, actions1) = state.process(ChannelEvent.ExecuteCommand(CMD_CLOSE(null)))
            assertEquals(state, state1)
            assertEquals(1, actions1.size)
            actions1.hasCommandError<CommandUnavailableInThisState>()
        }
    }

    @Test
    fun `recv CMD_FORCECLOSE`() {
        val (alice, bob, _) = init()
        listOf(alice, bob).forEach { state ->
            val commitTx = state.commitments.localCommit.publishableTxs.commitTx.tx
            val (state1, actions1) = state.process(ChannelEvent.ExecuteCommand(CMD_FORCECLOSE))
            assertTrue(state1 is Closing)
            assertNotNull(state1.localCommitPublished)
            assertNull(actions1.findOutgoingMessageOpt<Error>())
            actions1.hasTx(commitTx)
            actions1.hasWatch<WatchConfirmed>()
        }
    }

    companion object {
        fun init(
            channelVersion: ChannelVersion = ChannelVersion.STANDARD,
            currentHeight: Int = TestConstants.defaultBlockHeight,
            fundingAmount: Satoshi = TestConstants.fundingAmount
        ): Triple<WaitForFundingLocked, WaitForFundingLocked, Pair<FundingLocked, FundingLocked>> {
            val (alice, bob, open) = TestsHelper.init(channelVersion, currentHeight, fundingAmount)
            val (bob1, actionsBob1) = bob.process(ChannelEvent.MessageReceived(open))
            val accept = actionsBob1.findOutgoingMessage<AcceptChannel>()
            val (alice1, actionsAlice1) = alice.process(ChannelEvent.MessageReceived(accept))
            val fundingRequest = actionsAlice1.filterIsInstance<ChannelAction.Blockchain.MakeFundingTx>().first()
            val fundingTx = Transaction(version = 2, txIn = listOf(), txOut = listOf(TxOut(fundingRequest.amount, fundingRequest.pubkeyScript)), lockTime = 0)
            val (alice2, actionsAlice2) = alice1.process(ChannelEvent.MakeFundingTxResponse(fundingTx, 0, 100.sat))
            val fundingCreated = actionsAlice2.findOutgoingMessage<FundingCreated>()
            val (bob2, actionsBob2) = bob1.process(ChannelEvent.MessageReceived(fundingCreated))
            val fundingSigned = actionsBob2.findOutgoingMessage<FundingSigned>()
            val channelId = fundingSigned.channelId
            val (alice3, _) = alice2.process(ChannelEvent.MessageReceived(fundingSigned))
            val (alice4, actionsAlice4) = alice3.process(ChannelEvent.WatchReceived(WatchEventConfirmed(channelId, BITCOIN_FUNDING_DEPTHOK, currentHeight, 42, fundingTx)))
            assertTrue(alice4 is WaitForFundingLocked)
            actionsAlice4.hasWatch<WatchLost>()
            val fundingLockedAlice = actionsAlice4.findOutgoingMessage<FundingLocked>()
            val (bob3, actionsBob3) = bob2.process(ChannelEvent.WatchReceived(WatchEventConfirmed(channelId, BITCOIN_FUNDING_DEPTHOK, currentHeight, 42, fundingTx)))
            assertTrue(bob3 is WaitForFundingLocked)
            actionsBob3.hasWatch<WatchLost>()
            val fundingLockedBob = actionsBob3.findOutgoingMessage<FundingLocked>()
            return Triple(alice4, bob3, Pair(fundingLockedAlice, fundingLockedBob))
        }
    }

}