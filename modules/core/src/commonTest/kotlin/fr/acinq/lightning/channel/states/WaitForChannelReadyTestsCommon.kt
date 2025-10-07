package fr.acinq.lightning.channel.states

import fr.acinq.bitcoin.Satoshi
import fr.acinq.bitcoin.ScriptFlags
import fr.acinq.bitcoin.Transaction
import fr.acinq.lightning.ChannelEvents
import fr.acinq.lightning.Features
import fr.acinq.lightning.blockchain.WatchConfirmed
import fr.acinq.lightning.blockchain.WatchConfirmedTriggered
import fr.acinq.lightning.blockchain.WatchSpent
import fr.acinq.lightning.blockchain.WatchSpentTriggered
import fr.acinq.lightning.channel.*
import fr.acinq.lightning.tests.TestConstants
import fr.acinq.lightning.tests.utils.LightningTestSuite
import fr.acinq.lightning.tests.utils.runSuspendTest
import fr.acinq.lightning.wire.*
import kotlinx.coroutines.CompletableDeferred
import kotlin.test.*

class WaitForChannelReadyTestsCommon : LightningTestSuite() {

    @Test
    fun `recv TxSignatures -- zero conf`() {
        val (alice, _, bob, _) = init(zeroConf = true)
        val txSigsAlice = getFundingSigs(alice)
        val (bob1, actionsBob1) = bob.process(ChannelCommand.MessageReceived(txSigsAlice))
        assertIs<WaitForChannelReady>(bob1.state)
        assertEquals(actionsBob1.size, 2)
        assertEquals(actionsBob1.find<ChannelAction.Blockchain.PublishTx>().tx.txid, txSigsAlice.txId)
        actionsBob1.has<ChannelAction.Storage.StoreState>()
    }

    @Test
    fun `recv TxSignatures and restart -- zero conf`() {
        val (alice, _, bob, _) = init(zeroConf = true)
        val txSigsAlice = getFundingSigs(alice)
        val (bob1, actionsBob1) = bob.process(ChannelCommand.MessageReceived(txSigsAlice))
        val fundingTx = actionsBob1.find<ChannelAction.Blockchain.PublishTx>().tx
        val (bob2, actionsBob2) = LNChannel(bob1.ctx, WaitForInit).process(ChannelCommand.Init.Restore(bob1.state as PersistedChannelState))
        assertIs<Offline>(bob2.state)
        assertEquals(actionsBob2.size, 2)
        assertEquals(actionsBob2.find<ChannelAction.Blockchain.PublishTx>().tx, fundingTx)
        assertEquals(actionsBob2.findWatch<WatchConfirmed>().txId, fundingTx.txid)
    }

    @Test
    fun `recv TxSignatures -- duplicate`() {
        val (alice, _, _, _) = init()
        val (alice1, actions1) = alice.process(ChannelCommand.MessageReceived(TxSignatures(alice.channelId, alice.commitments.latest.fundingTxId, listOf())))
        assertEquals(alice1, alice)
        assertTrue(actions1.isEmpty())
    }

    @Test
    fun `recv TxSignatures -- invalid`() {
        val (alice, _, bob, _) = init(zeroConf = true)
        val invalidTxSigsAlice = getFundingSigs(alice).copy(tlvs = TlvStream.empty())
        val (bob1, actionsBob1) = bob.process(ChannelCommand.MessageReceived(invalidTxSigsAlice))
        assertEquals(bob, bob1)
        assertEquals(actionsBob1.size, 1)
        actionsBob1.hasOutgoingMessage<Warning>()
    }

    @Test
    fun `recv ChannelReady`() {
        val (alice, channelReadyAlice, bob, channelReadyBob) = init()
        val (alice1, actionsAlice1) = alice.process(ChannelCommand.MessageReceived(channelReadyBob))
        assertIs<Normal>(alice1.state)
        actionsAlice1.find<ChannelAction.Storage.SetLocked>().also {
            assertEquals(alice.commitments.latest.fundingTxId, it.txId)
        }
        actionsAlice1.has<ChannelAction.Storage.StoreState>()
        assertIs<ChannelEvents.Confirmed>(actionsAlice1.find<ChannelAction.EmitEvent>().event)
        val (bob1, actionsBob1) = bob.process(ChannelCommand.MessageReceived(channelReadyAlice))
        assertIs<Normal>(bob1.state)
        actionsBob1.find<ChannelAction.Storage.SetLocked>().also {
            assertEquals(bob.commitments.latest.fundingTxId, it.txId)
        }
        actionsBob1.has<ChannelAction.Storage.StoreState>()
        assertIs<ChannelEvents.Confirmed>(actionsBob1.find<ChannelAction.EmitEvent>().event)
    }

    @Test
    fun `recv ChannelSpent -- remote commit`() {
        val (alice, _, bob, _) = init()
        // bob publishes his commitment tx
        run {
            val bobCommitTx = bob.signCommitTx()
            val (alice1, actions1) = alice.process(ChannelCommand.WatchReceived(WatchSpentTriggered(alice.channelId, WatchSpent.ChannelSpent(TestConstants.fundingAmount), bobCommitTx)))
            assertIs<Closing>(alice1.state)
            assertNotNull(alice1.state.remoteCommitPublished)
            assertNotNull(alice1.state.remoteCommitPublished.localOutput)
            val mainTx = actions1.hasPublishTx(ChannelAction.Blockchain.PublishTx.Type.ClaimRemoteDelayedOutputTx)
            Transaction.correctlySpends(mainTx, listOf(bobCommitTx), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
            actions1.hasWatchConfirmed(bobCommitTx.txid)
            actions1.hasWatchOutputSpent(mainTx.txIn.first().outPoint)
        }
        // alice publishes her commitment tx
        run {
            val aliceCommitTx = alice.signCommitTx()
            val (bob1, actions1) = bob.process(ChannelCommand.WatchReceived(WatchSpentTriggered(bob.channelId, WatchSpent.ChannelSpent(TestConstants.fundingAmount), aliceCommitTx)))
            assertIs<Closing>(bob1.state)
            assertNotNull(bob1.state.remoteCommitPublished)
            assertNotNull(bob1.state.remoteCommitPublished.localOutput)
            val mainTx = actions1.hasPublishTx(ChannelAction.Blockchain.PublishTx.Type.ClaimRemoteDelayedOutputTx)
            Transaction.correctlySpends(mainTx, listOf(aliceCommitTx), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
            actions1.hasWatchConfirmed(aliceCommitTx.txid)
            actions1.hasWatchOutputSpent(mainTx.txIn.first().outPoint)
        }
    }

    @Test
    fun `recv ChannelSpent -- other commit`() {
        val (alice, _, _) = init()
        val aliceCommitTx = alice.signCommitTx()
        val unknownTx = Transaction(2, aliceCommitTx.txIn, listOf(), 0)
        val (alice1, actions1) = alice.process(ChannelCommand.WatchReceived(WatchSpentTriggered(alice.channelId, WatchSpent.ChannelSpent(TestConstants.fundingAmount), unknownTx)))
        assertEquals(alice.state, alice1.state)
        assertTrue(actions1.isEmpty())
    }

    @Test
    fun `recv Error`() {
        val (alice, _, bob, _) = init()
        listOf(alice, bob).forEach { state ->
            val commitTx = state.signCommitTx()
            val (state1, actions1) = state.process(ChannelCommand.MessageReceived(Error(state.channelId, "no lightning for you sir")))
            assertIs<Closing>(state1.state)
            assertNotNull(state1.state.localCommitPublished)
            assertNull(actions1.findOutgoingMessageOpt<Error>())
            actions1.hasPublishTx(commitTx)
            actions1.hasWatchConfirmed(commitTx.txid)
        }
    }

    @Test
    fun `recv ChannelCommand_Close_MutualClose`() = runSuspendTest {
        val (alice, _, bob, _) = init()
        listOf(alice, bob).forEach { state ->
            val cmd = ChannelCommand.Close.MutualClose(CompletableDeferred(), null, TestConstants.feeratePerKw)
            val (state1, actions1) = state.process(cmd)
            assertEquals(state, state1)
            assertEquals(1, actions1.size)
            assertEquals(ChannelCloseResponse.Failure.ChannelNotOpenedYet("WaitForChannelReady"), cmd.replyTo.await())
            actions1.hasCommandError<CommandUnavailableInThisState>()
        }
    }

    @Test
    fun `recv ChannelCommand_Close_ForceClose`() {
        val (alice, _, bob, _) = init()
        listOf(alice, bob).forEach { state ->
            val commitTx = state.signCommitTx()
            val (state1, actions1) = state.process(ChannelCommand.Close.ForceClose)
            assertIs<Closing>(state1.state)
            assertNotNull(state1.state.localCommitPublished)
            val error = actions1.hasOutgoingMessage<Error>()
            assertEquals(ForcedLocalCommit(bob.channelId).message, error.toAscii())
            actions1.hasPublishTx(commitTx)
            actions1.hasWatchConfirmed(commitTx.txid)
        }
    }

    private fun getFundingSigs(channel: LNChannel<WaitForChannelReady>): TxSignatures {
        assertIs<LocalFundingStatus.UnconfirmedFundingTx>(channel.commitments.latest.localFundingStatus)
        return (channel.commitments.latest.localFundingStatus as LocalFundingStatus.UnconfirmedFundingTx).sharedTx.localSigs
    }

    companion object {
        data class Fixture(val alice: LNChannel<WaitForChannelReady>, val channelReadyAlice: ChannelReady, val bob: LNChannel<WaitForChannelReady>, val channelReadyBob: ChannelReady)

        fun init(
            channelType: ChannelType.SupportedChannelType = ChannelType.SupportedChannelType.SimpleTaprootChannels,
            aliceFeatures: Features = TestConstants.Alice.nodeParams.features,
            bobFeatures: Features = TestConstants.Bob.nodeParams.features,
            bobUsePeerStorage: Boolean = true,
            currentHeight: Int = TestConstants.defaultBlockHeight,
            aliceFundingAmount: Satoshi = TestConstants.aliceFundingAmount,
            bobFundingAmount: Satoshi = TestConstants.bobFundingAmount,
            requestRemoteFunding: Satoshi? = null,
            zeroConf: Boolean = false,
        ): Fixture {
            return if (zeroConf) {
                val (alice, commitAlice, bob, commitBob) = WaitForFundingSignedTestsCommon.init(channelType, aliceFeatures, bobFeatures, bobUsePeerStorage, currentHeight, aliceFundingAmount, bobFundingAmount, requestRemoteFunding, zeroConf)
                val (alice1, actionsAlice1) = alice.process(ChannelCommand.MessageReceived(commitBob))
                assertIs<LNChannel<WaitForFundingSigned>>(alice1)
                assertTrue(actionsAlice1.isEmpty())
                val (bob1, actionsBob1) = bob.process(ChannelCommand.MessageReceived(commitAlice))
                assertIs<LNChannel<WaitForChannelReady>>(bob1)
                assertEquals(actionsBob1.findWatch<WatchConfirmed>().event, WatchConfirmed.ChannelFundingDepthOk)
                val txSigsBob = actionsBob1.findOutgoingMessage<TxSignatures>()
                val channelReadyBob = actionsBob1.findOutgoingMessage<ChannelReady>()
                val (alice2, actionsAlice2) = alice1.process(ChannelCommand.MessageReceived(txSigsBob))
                assertIs<LNChannel<WaitForChannelReady>>(alice2)
                assertEquals(actionsAlice2.find<ChannelAction.Blockchain.PublishTx>().tx.txid, txSigsBob.txId)
                assertEquals(actionsAlice2.findWatch<WatchConfirmed>().event, WatchConfirmed.ChannelFundingDepthOk)
                val channelReadyAlice = actionsAlice2.findOutgoingMessage<ChannelReady>()
                actionsAlice2.has<ChannelAction.Storage.StoreState>()
                Fixture(alice2, channelReadyAlice, bob1, channelReadyBob)
            } else {
                val (alice, bob, fundingTx) = WaitForFundingConfirmedTestsCommon.init(channelType, aliceFeatures, bobFeatures, bobUsePeerStorage, currentHeight, aliceFundingAmount, bobFundingAmount, requestRemoteFunding)
                val (alice1, actionsAlice1) = alice.process(ChannelCommand.WatchReceived(WatchConfirmedTriggered(alice.channelId, WatchConfirmed.ChannelFundingDepthOk, 42, 0, fundingTx)))
                assertIs<LNChannel<WaitForChannelReady>>(alice1)
                val channelReadyAlice = actionsAlice1.findOutgoingMessage<ChannelReady>()
                val (bob1, actionsBob1) = bob.process(ChannelCommand.WatchReceived(WatchConfirmedTriggered(bob.channelId, WatchConfirmed.ChannelFundingDepthOk, 42, 0, fundingTx)))
                assertIs<LNChannel<WaitForChannelReady>>(bob1)
                val channelReadyBob = actionsBob1.findOutgoingMessage<ChannelReady>()
                Fixture(alice1, channelReadyAlice, bob1, channelReadyBob)
            }
        }
    }

}