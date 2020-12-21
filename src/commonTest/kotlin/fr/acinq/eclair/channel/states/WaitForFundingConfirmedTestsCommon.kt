package fr.acinq.eclair.channel.states

import fr.acinq.bitcoin.Block
import fr.acinq.bitcoin.Satoshi
import fr.acinq.bitcoin.Transaction
import fr.acinq.bitcoin.updated
import fr.acinq.eclair.Eclair
import fr.acinq.eclair.MilliSatoshi
import fr.acinq.eclair.blockchain.*
import fr.acinq.eclair.channel.*
import fr.acinq.eclair.tests.TestConstants
import fr.acinq.eclair.tests.utils.EclairTestSuite
import fr.acinq.eclair.transactions.Scripts
import fr.acinq.eclair.utils.sat
import fr.acinq.eclair.wire.Error
import fr.acinq.eclair.wire.FundingLocked
import fr.acinq.eclair.wire.FundingSigned
import kotlin.test.*

class WaitForFundingConfirmedTestsCommon : EclairTestSuite() {
    @Test
    fun `receive FundingLocked`() {
        val (alice, bob) = init(ChannelVersion.STANDARD, TestConstants.fundingAmount, TestConstants.pushMsat)
        val fundingTx = alice.fundingTx!!
        val (bob1, actionsBob) = bob.process(ChannelEvent.WatchReceived(WatchEventConfirmed(bob.channelId, BITCOIN_FUNDING_DEPTHOK, 42, 0, fundingTx)))
        val fundingLocked = actionsBob.findOutgoingMessage<FundingLocked>()
        assertTrue { bob1 is WaitForFundingLocked }
        val (alice1, actionsAlice) = alice.process(ChannelEvent.MessageReceived(fundingLocked))
        assertTrue { alice1 is WaitForFundingConfirmed && alice1.deferred == fundingLocked }
        assertTrue { actionsAlice.isEmpty() } // alice waits until she sees on-chain confirmations herself
    }

    @Test
    fun `receive BITCOIN_FUNDING_DEPTHOK`() {
        val (alice, bob) = init(ChannelVersion.STANDARD, TestConstants.fundingAmount, TestConstants.pushMsat)
        val fundingTx = alice.fundingTx!!
        val (bob1, actions) = bob.process(ChannelEvent.WatchReceived(WatchEventConfirmed(bob.channelId, BITCOIN_FUNDING_DEPTHOK, 42, 0, fundingTx)))
        actions.findOutgoingMessage<FundingLocked>()
        actions.hasWatch<WatchLost>()
        actions.has<ChannelAction.Storage.StoreState>()
        assertTrue { bob1 is WaitForFundingLocked }
    }

    @Test
    fun `receive BITCOIN_FUNDING_DEPTHOK (bad funding pubkey script)`() {
        val (alice, bob) = init(ChannelVersion.STANDARD, TestConstants.fundingAmount, TestConstants.pushMsat)
        val fundingTx = alice.fundingTx!!
        val badOutputScript = Scripts.multiSig2of2(Eclair.randomKey().publicKey(), Eclair.randomKey().publicKey())
        val badFundingTx = fundingTx.copy(txOut = fundingTx.txOut.updated(0, fundingTx.txOut[0].updatePublicKeyScript(badOutputScript)))
        val (bob1, actions) = bob.process(ChannelEvent.WatchReceived(WatchEventConfirmed(bob.channelId, BITCOIN_FUNDING_DEPTHOK, 42, 0, badFundingTx)))
        assertTrue { bob1 is Aborted }
        actions.hasOutgoingMessage<Error>()
    }

    @Test
    fun `receive BITCOIN_FUNDING_DEPTHOK (bad funding amount)`() {
        val (alice, bob) = init(ChannelVersion.STANDARD, TestConstants.fundingAmount, TestConstants.pushMsat)
        val fundingTx = alice.fundingTx!!
        val badAmount = 1_234_567.sat
        val badFundingTx = fundingTx.copy(txOut = fundingTx.txOut.updated(0, fundingTx.txOut[0].updateAmount(badAmount)))
        val (bob1, actions) = bob.process(ChannelEvent.WatchReceived(WatchEventConfirmed(bob.channelId, BITCOIN_FUNDING_DEPTHOK, 42, 0, badFundingTx)))
        assertTrue { bob1 is Aborted }
        actions.hasOutgoingMessage<Error>()
    }

    @Test
    fun `recv BITCOIN_FUNDING_SPENT (remote commit)`() {
        val (alice, bob) = init(ChannelVersion.STANDARD, TestConstants.fundingAmount, TestConstants.pushMsat)

        // case 1: alice publishes her commitment tx
        run {
            val (bob1, actions1) = bob.process(ChannelEvent.WatchReceived(WatchEventSpent(bob.channelId, BITCOIN_FUNDING_SPENT, alice.commitments.localCommit.publishableTxs.commitTx.tx)))
            assertTrue(bob1 is Closing)
            assertNotNull(bob1.remoteCommitPublished)
            actions1.hasTx(bob1.remoteCommitPublished!!.claimMainOutputTx!!)
            assertEquals(2, actions1.findWatches<WatchConfirmed>().size) // commit tx + main output
        }

        // case 2: bob publishes his commitment tx
        run {
            val (alice1, actions1) = alice.process(ChannelEvent.WatchReceived(WatchEventSpent(alice.channelId, BITCOIN_FUNDING_SPENT, bob.commitments.localCommit.publishableTxs.commitTx.tx)))
            assertTrue(alice1 is Closing)
            assertNotNull(alice1.remoteCommitPublished)
            actions1.hasTx(alice1.remoteCommitPublished!!.claimMainOutputTx!!)
            assertEquals(2, actions1.findWatches<WatchConfirmed>().size) // commit tx + main output
        }
    }

    @Ignore
    fun `recv BITCOIN_FUNDING_SPENT (other commit)`() {
        val (alice, bob) = init(ChannelVersion.STANDARD, TestConstants.fundingAmount, TestConstants.pushMsat)
        val spendingTx = Transaction(version = 2, txIn = listOf(), txOut = listOf(), lockTime = 0)
        listOf(alice, bob).forEach { state ->
            val (state1, actions1) = state.process(ChannelEvent.WatchReceived(WatchEventSpent(state.channelId, BITCOIN_FUNDING_SPENT, spendingTx)))
            assertTrue(state1 is ErrorInformationLeak)
            actions1.hasOutgoingMessage<Error>()
            actions1.hasTx(state.commitments.localCommit.publishableTxs.commitTx.tx)
        }
    }

    @Test
    fun `recv Error`() {
        val (_, bob) = init(ChannelVersion.STANDARD, TestConstants.fundingAmount, TestConstants.pushMsat)
        val (bob1, actions1) = bob.process(ChannelEvent.MessageReceived(Error(bob.channelId, "oops")))
        assertTrue(bob1 is Closing)
        assertNotNull(bob1.localCommitPublished)
        actions1.hasTx(bob.commitments.localCommit.publishableTxs.commitTx.tx)
        assertEquals(2, actions1.findWatches<WatchConfirmed>().size) // commit tx + main output
    }

    @Test
    fun `recv CMD_CLOSE`() {
        val (alice, bob) = init(ChannelVersion.STANDARD, TestConstants.fundingAmount, TestConstants.pushMsat)
        listOf(alice, bob).forEach { state ->
            val (state1, actions1) = state.process(ChannelEvent.ExecuteCommand(CMD_CLOSE(null)))
            assertEquals(state, state1)
            actions1.hasCommandError<CommandUnavailableInThisState>()
        }
    }

    @Test
    fun `recv CMD_FORCECLOSE`() {
        val (alice, bob) = init(ChannelVersion.STANDARD, TestConstants.fundingAmount, TestConstants.pushMsat)
        listOf(alice, bob).forEach { state ->
            val (state1, actions1) = state.process(ChannelEvent.ExecuteCommand(CMD_FORCECLOSE))
            assertTrue(state1 is Closing)
            assertNotNull(state1.localCommitPublished)
            actions1.hasTx(state1.localCommitPublished!!.commitTx)
            actions1.hasTx(state1.localCommitPublished!!.claimMainDelayedOutputTx!!)
            assertEquals(2, actions1.findWatches<WatchConfirmed>().size) // commit tx + main output
        }
    }

    @Test
    fun `recv NewBlock`() {
        val (alice, bob) = init(ChannelVersion.STANDARD, TestConstants.fundingAmount, TestConstants.pushMsat)
        listOf(alice, bob).forEach { state ->
            run {
                val (state1, actions1) = state.process(ChannelEvent.NewBlock(state.currentBlockHeight + 1, Block.RegtestGenesisBlock.header))
                assertEquals(state.copy(currentTip = state1.currentTip), state1)
                assertTrue(actions1.isEmpty())
            }
            run {
                val (state1, actions1) = state.process(ChannelEvent.CheckHtlcTimeout)
                assertEquals(state, state1)
                assertTrue(actions1.isEmpty())
            }
        }
    }

    @Test
    fun `recv Disconnected`() {
        val (alice, bob) = init(ChannelVersion.STANDARD, TestConstants.fundingAmount, TestConstants.pushMsat)
        val (alice1, _) = alice.process(ChannelEvent.Disconnected)
        assertTrue { alice1 is Offline }
        val (bob1, _) = bob.process(ChannelEvent.Disconnected)
        assertTrue { bob1 is Offline }
    }

    companion object {
        fun init(channelVersion: ChannelVersion, fundingAmount: Satoshi, pushAmount: MilliSatoshi): Pair<WaitForFundingConfirmed, WaitForFundingConfirmed> {
            val (alice, bob, fundingCreated) = WaitForFundingCreatedTestsCommon.init(channelVersion, fundingAmount, pushAmount)
            val (bob1, actions1) = bob.process(ChannelEvent.MessageReceived(fundingCreated))
            val fundingSigned = actions1.findOutgoingMessage<FundingSigned>()
            val (alice1, _) = alice.process(ChannelEvent.MessageReceived(fundingSigned))
            return Pair(alice1 as WaitForFundingConfirmed, bob1 as WaitForFundingConfirmed)
        }
    }
}
