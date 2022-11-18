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
import fr.acinq.lightning.wire.ChannelReady
import fr.acinq.lightning.utils.toMilliSatoshi
import fr.acinq.lightning.wire.Error
import fr.acinq.lightning.wire.TxSignatures
import fr.acinq.lightning.wire.Warning
import kotlin.test.*

class WaitForChannelReadyTestsCommon : LightningTestSuite() {

    @Test
    fun `recv TxSignatures -- zero conf`() {
        val (alice, _, bob, _) = init(ChannelType.SupportedChannelType.AnchorOutputsZeroReserve, zeroConf = true)
        val (alice1, actionsAlice1) = alice.process(ChannelCommand.MessageReceived(bob.state.fundingTx.localSigs))
        assertIs<WaitForChannelReady>(alice1.state)
        assertEquals(actionsAlice1.size, 3)
        val fundingTx = actionsAlice1.find<ChannelAction.Blockchain.PublishTx>().tx
        assertEquals(alice.commitments.fundingTxId, fundingTx.txid)
        assertEquals(alice.state.fundingTx.localSigs, actionsAlice1.findOutgoingMessage())
        actionsAlice1.has<ChannelAction.Storage.StoreState>()
        val (bob1, actionsBob1) = bob.process(ChannelCommand.MessageReceived(alice.state.fundingTx.localSigs))
        assertIs<WaitForChannelReady>(bob1.state)
        assertEquals(actionsBob1.size, 2)
        assertEquals(actionsBob1.find<ChannelAction.Blockchain.PublishTx>().tx, fundingTx)
        actionsBob1.has<ChannelAction.Storage.StoreState>()
    }

    @Test
    fun `recv TxSignatures and restart -- zero conf`() {
        val (alice, _, bob, _) = init(ChannelType.SupportedChannelType.AnchorOutputsZeroReserve, zeroConf = true)
        val (alice1, actionsAlice1) = alice.process(ChannelCommand.MessageReceived(bob.state.fundingTx.localSigs))
        val fundingTx = actionsAlice1.find<ChannelAction.Blockchain.PublishTx>().tx
        val (alice2, actionsAlice2) = LNChannel(alice1.ctx, WaitForInit).process(ChannelCommand.Restore(alice1.state))
        assertIs<Offline>(alice2.state)
        assertEquals(actionsAlice2.size, 2)
        assertEquals(actionsAlice2.find<ChannelAction.Blockchain.PublishTx>().tx, fundingTx)
        assertEquals(actionsAlice2.findWatch<WatchSpent>().txId, fundingTx.txid)
    }

    @Test
    fun `recv TxSignatures -- duplicate`() {
        val (alice, _, bob, _) = init()
        val (alice1, actions1) = alice.process(ChannelCommand.MessageReceived(bob.state.fundingTx.localSigs))
        assertEquals(alice1, alice)
        assertTrue(actions1.isEmpty())
    }

    @Test
    fun `recv TxSignatures -- invalid`() {
        val (alice, _, bob, _) = init(ChannelType.SupportedChannelType.AnchorOutputsZeroReserve, zeroConf = true)
        val (alice1, actionsAlice1) = alice.process(ChannelCommand.MessageReceived(bob.state.fundingTx.localSigs.copy(witnesses = listOf())))
        assertEquals(alice, alice1)
        assertEquals(actionsAlice1.size, 1)
        actionsAlice1.hasOutgoingMessage<Warning>()
    }

    @Test
    fun `recv ChannelReady`() {
        val (alice, channelReadyAlice, bob, channelReadyBob) = init()
        val (alice1, actionsAlice1) = alice.process(ChannelCommand.MessageReceived(channelReadyBob))
        assertIs<Normal>(alice1.state)
        assertEquals(3, actionsAlice1.size)
        actionsAlice1.has<ChannelAction.Storage.StoreState>()
        assertEquals(actionsAlice1.findWatch<WatchConfirmed>().event, BITCOIN_FUNDING_DEEPLYBURIED)
        val (bob1, actionsBob1) = bob.process(ChannelCommand.MessageReceived(channelReadyAlice))
        assertIs<Normal>(bob1.state)
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
        assertIs<Normal>(alice1.state)
        assertEquals(alice1.commitments.localCommit.spec.toLocal, aliceBalance)
        assertEquals(alice1.commitments.localCommit.spec.toRemote, bobBalance)
        val (bob1, _) = bob.process(ChannelCommand.MessageReceived(fundingLockedAlice))
        assertIs<Normal>(bob1.state)
        assertEquals(bob1.commitments.localCommit.spec.toLocal, bobBalance)
        assertEquals(bob1.commitments.localCommit.spec.toRemote, aliceBalance)
    }

    @Test
    fun `recv FundingLocked -- push amount on both sides -- zero-conf`() {
        val (alice, fundingLockedAlice, bob, fundingLockedBob) = init(bobPushAmount = 25_000_000.msat, zeroConf = true)
        val aliceBalance = TestConstants.aliceFundingAmount.toMilliSatoshi() - TestConstants.alicePushAmount + 25_000_000.msat
        assertEquals(aliceBalance, 825_000_000.msat)
        val bobBalance = TestConstants.bobFundingAmount.toMilliSatoshi() - 25_000_000.msat + TestConstants.alicePushAmount
        assertEquals(bobBalance, 175_000_000.msat)
        val (alice1, _) = alice.process(ChannelCommand.MessageReceived(fundingLockedBob))
        assertIs<Normal>(alice1.state)
        assertEquals(alice1.commitments.localCommit.spec.toLocal, aliceBalance)
        assertEquals(alice1.commitments.localCommit.spec.toRemote, bobBalance)
        val (bob1, _) = bob.process(ChannelCommand.MessageReceived(fundingLockedAlice))
        assertIs<Normal>(bob1.state)
        assertEquals(bob1.commitments.localCommit.spec.toLocal, bobBalance)
        assertEquals(bob1.commitments.localCommit.spec.toRemote, aliceBalance)
    }

    @Test
    fun `recv BITCOIN_FUNDING_SPENT -- remote commit`() {
        val (alice, _, bob, _) = init()
        // bob publishes his commitment tx
        run {
            val bobCommitTx = bob.commitments.localCommit.publishableTxs.commitTx.tx
            val (alice1, actions1) = alice.process(ChannelCommand.WatchReceived(WatchEventSpent(alice.channelId, BITCOIN_FUNDING_SPENT, bobCommitTx)))
            assertIs<Closing>(alice1.state)
            assertNotNull(alice1.state.remoteCommitPublished)
            assertEquals(1, actions1.findTxs().size)
            assertEquals(2, actions1.findWatches<WatchConfirmed>().size) // commit tx + main output
        }
        // alice publishes her commitment tx
        run {
            val aliceCommitTx = alice.commitments.localCommit.publishableTxs.commitTx.tx
            val (bob1, actions1) = bob.process(ChannelCommand.WatchReceived(WatchEventSpent(bob.channelId, BITCOIN_FUNDING_SPENT, aliceCommitTx)))
            assertIs<Closing>(bob1.state)
            assertNotNull(bob1.state.remoteCommitPublished)
            assertEquals(1, actions1.findTxs().size)
            assertEquals(2, actions1.findWatches<WatchConfirmed>().size) // commit tx + main output
        }
    }

    @Test
    fun `recv BITCOIN_FUNDING_SPENT -- other commit`() {
        val (alice, _, _) = init()
        val aliceCommitTx = alice.commitments.localCommit.publishableTxs.commitTx.tx
        val unknownTx = Transaction(2, aliceCommitTx.txIn, listOf(), 0)
        val (alice1, actions1) = alice.process(ChannelCommand.WatchReceived(WatchEventSpent(alice.channelId, BITCOIN_FUNDING_SPENT, unknownTx)))
        assertIs<ErrorInformationLeak>(alice1.state)
        assertTrue(actions1.isEmpty())
    }

    @Test
    fun `recv Error`() {
        val (alice, _, bob, _) = init()
        listOf(alice, bob).forEach { state ->
            val commitTx = state.commitments.localCommit.publishableTxs.commitTx.tx
            val (state1, actions1) = state.process(ChannelCommand.MessageReceived(Error(state.channelId, "no lightning for you sir")))
            assertIs<Closing>(state1.state)
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
            val commitTx = state.commitments.localCommit.publishableTxs.commitTx.tx
            val (state1, actions1) = state.process(ChannelCommand.ExecuteCommand(CMD_FORCECLOSE))
            assertIs<Closing>(state1.state)
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
        assertIs<Aborted>(bob1.state)
        assertEquals(1, actions1.size)
        val error = actions1.hasOutgoingMessage<Error>()
        assertEquals(ForcedLocalCommit(bob.channelId).message, error.toAscii())
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
                assertIs<WaitForChannelReady>(alice1.state)
                assertIs<LNChannel<WaitForChannelReady>>(alice1) // for compiler: only tests LNChannel<Any>
                assertEquals(actionsAlice1.findWatch<WatchSpent>().event, BITCOIN_FUNDING_SPENT)
                val channelReadyAlice = actionsAlice1.findOutgoingMessage<ChannelReady>()
                val (bob1, actionsBob1) = bob.process(ChannelCommand.MessageReceived(commitAlice))
                assertIs<WaitForChannelReady>(bob1.state)
                assertIs<LNChannel<WaitForChannelReady>>(bob1) // for compiler: only tests LNChannel<Any>
                assertEquals(actionsBob1.findWatch<WatchSpent>().event, BITCOIN_FUNDING_SPENT)
                val channelReadyBob = actionsBob1.findOutgoingMessage<ChannelReady>()
                Fixture(alice1, channelReadyAlice, bob1, channelReadyBob)
            } else {
                val (alice, bob, txSigsBob) = WaitForFundingConfirmedTestsCommon.init(channelType, aliceFeatures, bobFeatures, currentHeight, aliceFundingAmount, bobFundingAmount, alicePushAmount, bobPushAmount)
                val (alice1, actionsAlice1) = alice.process(ChannelCommand.MessageReceived(txSigsBob))
                val txSigsAlice = actionsAlice1.hasOutgoingMessage<TxSignatures>()
                val fundingTx = actionsAlice1.find<ChannelAction.Blockchain.PublishTx>().tx
                assertEquals(fundingTx.hash, txSigsAlice.txHash)
                val (bob1, _) = bob.process(ChannelCommand.MessageReceived(txSigsAlice))
                val (alice2, actionsAlice2) = alice1.process(ChannelCommand.WatchReceived(WatchEventConfirmed(alice.channelId, BITCOIN_FUNDING_DEPTHOK, 42, 0, fundingTx)))
                assertIs<WaitForChannelReady>(alice2.state)
                assertIs<LNChannel<WaitForChannelReady>>(alice2) // for compiler: only tests LNChannel<Any>
                val channelReadyAlice = actionsAlice2.findOutgoingMessage<ChannelReady>()
                val (bob2, actionsBob2) = bob1.process(ChannelCommand.WatchReceived(WatchEventConfirmed(bob.channelId, BITCOIN_FUNDING_DEPTHOK, 42, 0, fundingTx)))
                assertIs<WaitForChannelReady>(bob2.state)
                assertIs<LNChannel<WaitForChannelReady>>(bob2) // for compiler: only tests LNChannel<Any>
                val channelReadyBob = actionsBob2.findOutgoingMessage<ChannelReady>()
                Fixture(alice2, channelReadyAlice, bob2, channelReadyBob)
            }
        }
    }

}