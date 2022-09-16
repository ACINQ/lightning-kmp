package fr.acinq.lightning.channel.states

import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.ByteVector64
import fr.acinq.bitcoin.Satoshi
import fr.acinq.bitcoin.Script
import fr.acinq.lightning.Feature
import fr.acinq.lightning.Features
import fr.acinq.lightning.Lightning.randomBytes32
import fr.acinq.lightning.Lightning.randomKey
import fr.acinq.lightning.MilliSatoshi
import fr.acinq.lightning.blockchain.fee.FeeratePerKw
import fr.acinq.lightning.channel.*
import fr.acinq.lightning.tests.TestConstants
import fr.acinq.lightning.tests.utils.LightningTestSuite
import fr.acinq.lightning.utils.msat
import fr.acinq.lightning.utils.sat
import fr.acinq.lightning.utils.toByteVector
import fr.acinq.lightning.utils.toMilliSatoshi
import fr.acinq.lightning.wire.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class WaitForFundingCreatedTestsCommon : LightningTestSuite() {

    private fun verifyCommits(commitAlice: Helpers.Funding.FirstCommitTx, commitBob: Helpers.Funding.FirstCommitTx, balanceAlice: MilliSatoshi, balanceBob: MilliSatoshi) {
        assertEquals(commitAlice.localSpec.toLocal, balanceAlice)
        assertEquals(commitAlice.localSpec.toRemote, balanceBob)
        assertEquals(commitAlice.remoteSpec.toLocal, balanceBob)
        assertEquals(commitAlice.remoteSpec.toRemote, balanceAlice)
        assertEquals(commitBob.localSpec.toLocal, balanceBob)
        assertEquals(commitBob.localSpec.toRemote, balanceAlice)
        assertEquals(commitBob.remoteSpec.toLocal, balanceAlice)
        assertEquals(commitBob.remoteSpec.toRemote, balanceBob)
    }

    @Test
    fun `complete interactive-tx protocol`() {
        val (alice, bob, inputAlice) = init(ChannelType.SupportedChannelType.AnchorOutputs, TestConstants.aliceFundingAmount, 0.sat, TestConstants.pushAmount)
        // Alice ---- tx_add_input ----> Bob
        val (bob1, actionsBob1) = bob.process(ChannelEvent.MessageReceived(inputAlice))
        // Alice <--- tx_complete ----- Bob
        val (alice1, actionsAlice1) = alice.process(ChannelEvent.MessageReceived(actionsBob1.findOutgoingMessage<TxComplete>()))
        // Alice ---- tx_add_output ----> Bob
        val (bob2, actionsBob2) = bob1.process(ChannelEvent.MessageReceived(actionsAlice1.findOutgoingMessage<TxAddOutput>()))
        // Alice <--- tx_complete ----- Bob
        val (alice2, actionsAlice2) = alice1.process(ChannelEvent.MessageReceived(actionsBob2.findOutgoingMessage<TxComplete>()))
        // Alice ---- tx_complete ----> Bob
        val (bob3, actionsBob3) = bob2.process(ChannelEvent.MessageReceived(actionsAlice2.findOutgoingMessage<TxComplete>()))
        val commitSigAlice = actionsAlice2.findOutgoingMessage<CommitSig>()
        val commitSigBob = actionsBob3.findOutgoingMessage<CommitSig>()
        assertEquals(commitSigAlice.channelId, commitSigBob.channelId)
        assertTrue(commitSigAlice.htlcSignatures.isEmpty())
        assertTrue(commitSigAlice.channelData.isEmpty())
        assertTrue(commitSigBob.htlcSignatures.isEmpty())
        assertTrue(commitSigBob.channelData.isEmpty())
        assertIs<WaitForFundingSigned>(alice2)
        assertIs<WaitForFundingSigned>(bob3)
        assertEquals(alice2.channelFeatures, ChannelFeatures(setOf(Feature.StaticRemoteKey, Feature.AnchorOutputs, Feature.Wumbo)))
        assertEquals(bob3.channelFeatures, ChannelFeatures(setOf(Feature.StaticRemoteKey, Feature.AnchorOutputs, Feature.Wumbo)))
        verifyCommits(alice2.firstCommitTx, bob3.firstCommitTx, TestConstants.aliceFundingAmount.toMilliSatoshi() - TestConstants.pushAmount, TestConstants.pushAmount)
    }

    @Test
    fun `complete interactive-tx protocol -- with non-initiator contributions`() {
        val (alice, bob, inputAlice) = init(ChannelType.SupportedChannelType.AnchorOutputs, TestConstants.aliceFundingAmount, TestConstants.bobFundingAmount, TestConstants.pushAmount)
        // Alice ---- tx_add_input ----> Bob
        val (bob1, actionsBob1) = bob.process(ChannelEvent.MessageReceived(inputAlice))
        // Alice <--- tx_add_input ----- Bob
        val (alice1, actionsAlice1) = alice.process(ChannelEvent.MessageReceived(actionsBob1.findOutgoingMessage<TxAddInput>()))
        // Alice ---- tx_add_output ----> Bob
        val (bob2, actionsBob2) = bob1.process(ChannelEvent.MessageReceived(actionsAlice1.findOutgoingMessage<TxAddOutput>()))
        // Alice <--- tx_complete ----- Bob
        val (alice2, actionsAlice2) = alice1.process(ChannelEvent.MessageReceived(actionsBob2.findOutgoingMessage<TxComplete>()))
        // Alice ---- tx_complete ----> Bob
        val (bob3, actionsBob3) = bob2.process(ChannelEvent.MessageReceived(actionsAlice2.findOutgoingMessage<TxComplete>()))
        val commitSigAlice = actionsAlice2.findOutgoingMessage<CommitSig>()
        val commitSigBob = actionsBob3.findOutgoingMessage<CommitSig>()
        assertEquals(commitSigAlice.channelId, commitSigBob.channelId)
        assertIs<WaitForFundingSigned>(alice2)
        assertIs<WaitForFundingSigned>(bob3)
        assertEquals(alice2.channelFeatures, ChannelFeatures(setOf(Feature.StaticRemoteKey, Feature.AnchorOutputs, Feature.Wumbo)))
        assertEquals(bob3.channelFeatures, ChannelFeatures(setOf(Feature.StaticRemoteKey, Feature.AnchorOutputs, Feature.Wumbo)))
        verifyCommits(alice2.firstCommitTx, bob3.firstCommitTx, TestConstants.aliceFundingAmount.toMilliSatoshi() - TestConstants.pushAmount, TestConstants.bobFundingAmount.toMilliSatoshi() + TestConstants.pushAmount)
    }

    @Test
    fun `complete interactive-tx protocol -- zero conf -- zero reserve`() {
        val (alice, bob, inputAlice) = init(ChannelType.SupportedChannelType.AnchorOutputsZeroConfZeroReserve, TestConstants.aliceFundingAmount, TestConstants.bobFundingAmount, 0.msat)
        // Alice ---- tx_add_input ----> Bob
        val (bob1, actionsBob1) = bob.process(ChannelEvent.MessageReceived(inputAlice))
        // Alice <--- tx_add_input ----- Bob
        val (alice1, actionsAlice1) = alice.process(ChannelEvent.MessageReceived(actionsBob1.findOutgoingMessage<TxAddInput>()))
        // Alice ---- tx_add_output ----> Bob
        val (bob2, actionsBob2) = bob1.process(ChannelEvent.MessageReceived(actionsAlice1.findOutgoingMessage<TxAddOutput>()))
        // Alice <--- tx_complete ----- Bob
        val (alice2, actionsAlice2) = alice1.process(ChannelEvent.MessageReceived(actionsBob2.findOutgoingMessage<TxComplete>()))
        // Alice ---- tx_complete ----> Bob
        val (bob3, actionsBob3) = bob2.process(ChannelEvent.MessageReceived(actionsAlice2.findOutgoingMessage<TxComplete>()))
        val commitSigAlice = actionsAlice2.findOutgoingMessage<CommitSig>()
        val commitSigBob = actionsBob3.findOutgoingMessage<CommitSig>()
        assertEquals(commitSigAlice.channelId, commitSigBob.channelId)
        assertIs<WaitForFundingSigned>(alice2)
        assertIs<WaitForFundingSigned>(bob3)
        assertEquals(alice2.channelFeatures, ChannelFeatures(setOf(Feature.StaticRemoteKey, Feature.AnchorOutputs, Feature.Wumbo, Feature.ZeroReserveChannels, Feature.ZeroConfChannels)))
        assertEquals(bob3.channelFeatures, ChannelFeatures(setOf(Feature.StaticRemoteKey, Feature.AnchorOutputs, Feature.Wumbo, Feature.ZeroReserveChannels, Feature.ZeroConfChannels)))
        verifyCommits(alice2.firstCommitTx, bob3.firstCommitTx, TestConstants.aliceFundingAmount.toMilliSatoshi(), TestConstants.bobFundingAmount.toMilliSatoshi())
    }

    @Test
    fun `complete interactive-tx protocol -- initiator can't pay fees`() {
        val (alice, bob, inputAlice) = init(ChannelType.SupportedChannelType.AnchorOutputs, 1_000_100.sat, 0.sat, 1_000_000.sat.toMilliSatoshi())
        // Alice ---- tx_add_input ----> Bob
        val (bob1, actionsBob1) = bob.process(ChannelEvent.MessageReceived(inputAlice))
        // Alice <--- tx_complete ----- Bob
        val (alice1, actionsAlice1) = alice.process(ChannelEvent.MessageReceived(actionsBob1.findOutgoingMessage<TxComplete>()))
        // Alice ---- tx_add_output ----> Bob
        val (bob2, actionsBob2) = bob1.process(ChannelEvent.MessageReceived(actionsAlice1.findOutgoingMessage<TxAddOutput>()))
        // Alice <--- tx_complete ----- Bob
        val (alice2, actionsAlice2) = alice1.process(ChannelEvent.MessageReceived(actionsBob2.findOutgoingMessage<TxComplete>()))
        assertIs<WaitForFundingSigned>(alice2)
        // Alice ---- tx_complete ----> Bob
        val (bob3, actionsBob3) = bob2.process(ChannelEvent.MessageReceived(actionsAlice2.findOutgoingMessage<TxComplete>()))
        actionsBob3.hasOutgoingMessage<Error>()
        assertIs<Aborted>(bob3)
    }

    @Test
    fun `recv invalid interactive-tx message`() {
        val (_, bob, inputAlice) = init(ChannelType.SupportedChannelType.AnchorOutputs, TestConstants.aliceFundingAmount, 0.sat, 0.msat)
        run {
            // Invalid serial_id.
            val (bob1, actionsBob1) = bob.process(ChannelEvent.MessageReceived(inputAlice.copy(serialId = 1)))
            actionsBob1.hasOutgoingMessage<Error>()
            assertIs<Aborted>(bob1)
        }
        run {
            // Below dust.
            val txAddOutput = TxAddOutput(inputAlice.channelId, 2, 100.sat, Script.write(Script.pay2wpkh(randomKey().publicKey())).toByteVector())
            val (bob1, actionsBob1) = bob.process(ChannelEvent.MessageReceived(txAddOutput))
            actionsBob1.hasOutgoingMessage<Error>()
            assertIs<Aborted>(bob1)
        }
    }

    @Test
    fun `recv CommitSig`() {
        val (alice, bob, _) = init(ChannelType.SupportedChannelType.AnchorOutputs, TestConstants.aliceFundingAmount, 0.sat, 0.msat)
        run {
            val (alice1, actionsAlice1) = alice.process(ChannelEvent.MessageReceived(CommitSig(alice.channelId, ByteVector64.Zeroes, listOf())))
            assertEquals(actionsAlice1.findOutgoingMessage<Error>().toAscii(), UnexpectedCommitSig(alice.channelId).message)
            assertIs<Aborted>(alice1)
        }
        run {
            val (bob1, actionsBob1) = bob.process(ChannelEvent.MessageReceived(CommitSig(bob.channelId, ByteVector64.Zeroes, listOf())))
            assertEquals(actionsBob1.findOutgoingMessage<Error>().toAscii(), UnexpectedCommitSig(bob.channelId).message)
            assertIs<Aborted>(bob1)
        }
    }

    @Test
    fun `recv TxSignatures`() {
        val (alice, bob, _) = init(ChannelType.SupportedChannelType.AnchorOutputs, TestConstants.aliceFundingAmount, 0.sat, 0.msat)
        run {
            val (alice1, actionsAlice1) = alice.process(ChannelEvent.MessageReceived(TxSignatures(alice.channelId, randomBytes32(), listOf())))
            assertEquals(actionsAlice1.findOutgoingMessage<Error>().toAscii(), UnexpectedFundingSignatures(alice.channelId).message)
            assertIs<Aborted>(alice1)
        }
        run {
            val (bob1, actionsBob1) = bob.process(ChannelEvent.MessageReceived(TxSignatures(bob.channelId, randomBytes32(), listOf())))
            assertEquals(actionsBob1.findOutgoingMessage<Error>().toAscii(), UnexpectedFundingSignatures(bob.channelId).message)
            assertIs<Aborted>(bob1)
        }
    }

    @Test
    fun `recv TxAbort`() {
        val (alice, bob, _) = init(ChannelType.SupportedChannelType.AnchorOutputs, TestConstants.aliceFundingAmount, 0.sat, 0.msat)
        run {
            val (alice1, actionsAlice1) = alice.process(ChannelEvent.MessageReceived(TxAbort(alice.channelId, "changed my mind")))
            assertTrue(actionsAlice1.isEmpty())
            assertIs<Aborted>(alice1)
        }
        run {
            val (bob1, actionsBob1) = bob.process(ChannelEvent.MessageReceived(TxAbort(bob.channelId, "changed my mind")))
            assertTrue(actionsBob1.isEmpty())
            assertIs<Aborted>(bob1)
        }
    }

    @Test
    fun `recv TxInitRbf`() {
        val (alice, bob, _) = init(ChannelType.SupportedChannelType.AnchorOutputs, TestConstants.aliceFundingAmount, 0.sat, 0.msat)
        run {
            val (alice1, actionsAlice1) = alice.process(ChannelEvent.MessageReceived(TxInitRbf(alice.channelId, 0, FeeratePerKw(7500.sat))))
            assertEquals(actionsAlice1.size, 1)
            assertEquals(actionsAlice1.findOutgoingMessage<Warning>().toAscii(), InvalidRbfAttempt(alice.channelId).message)
            assertEquals(alice, alice1)
        }
        run {
            val (bob1, actionsBob1) = bob.process(ChannelEvent.MessageReceived(TxInitRbf(bob.channelId, 0, FeeratePerKw(7500.sat))))
            assertEquals(actionsBob1.size, 1)
            assertEquals(actionsBob1.findOutgoingMessage<Warning>().toAscii(), InvalidRbfAttempt(bob.channelId).message)
            assertEquals(bob, bob1)
        }
    }

    @Test
    fun `recv TxAckRbf`() {
        val (alice, bob, _) = init(ChannelType.SupportedChannelType.AnchorOutputs, TestConstants.aliceFundingAmount, 0.sat, 0.msat)
        run {
            val (alice1, actionsAlice1) = alice.process(ChannelEvent.MessageReceived(TxAckRbf(alice.channelId)))
            assertEquals(actionsAlice1.size, 1)
            assertEquals(actionsAlice1.findOutgoingMessage<Warning>().toAscii(), InvalidRbfAttempt(alice.channelId).message)
            assertEquals(alice, alice1)
        }
        run {
            val (bob1, actionsBob1) = bob.process(ChannelEvent.MessageReceived(TxAckRbf(bob.channelId)))
            assertEquals(actionsBob1.size, 1)
            assertEquals(actionsBob1.findOutgoingMessage<Warning>().toAscii(), InvalidRbfAttempt(bob.channelId).message)
            assertEquals(bob, bob1)
        }
    }

    @Test
    fun `recv Error`() {
        val (_, bob, _) = init(ChannelType.SupportedChannelType.AnchorOutputs, TestConstants.aliceFundingAmount, 0.sat, TestConstants.pushAmount)
        val (bob1, actions1) = bob.process(ChannelEvent.MessageReceived(Error(ByteVector32.Zeroes, "oops")))
        assertIs<Aborted>(bob1)
        assertTrue(actions1.isEmpty())
    }

    @Test
    fun `recv CMD_CLOSE`() {
        val (_, bob, _) = init(ChannelType.SupportedChannelType.AnchorOutputs, TestConstants.aliceFundingAmount, 0.sat, TestConstants.pushAmount)
        val (bob1, actions1) = bob.process(ChannelEvent.ExecuteCommand(CMD_CLOSE(null, null)))
        assertEquals(actions1.findOutgoingMessage<Error>().toAscii(), ChannelFundingError(bob.channelId).message)
        assertIs<Aborted>(bob1)
    }

    @Test
    fun `recv CMD_FORCECLOSE`() {
        val (_, bob, _) = init(ChannelType.SupportedChannelType.AnchorOutputs, TestConstants.aliceFundingAmount, 0.sat, TestConstants.pushAmount)
        val (bob1, actions1) = bob.process(ChannelEvent.ExecuteCommand(CMD_FORCECLOSE))
        assertEquals(actions1.findOutgoingMessage<Error>().toAscii(), ChannelFundingError(bob.channelId).message)
        assertIs<Aborted>(bob1)
    }

    @Test
    fun `recv Disconnected`() {
        val (_, bob, txAddInput) = init(ChannelType.SupportedChannelType.AnchorOutputs, TestConstants.aliceFundingAmount, 0.sat, TestConstants.pushAmount)
        val (bob1, _) = bob.process(ChannelEvent.MessageReceived(txAddInput))
        assertIs<WaitForFundingCreated>(bob1)
        val (bob2, actions2) = bob1.process(ChannelEvent.Disconnected)
        assertIs<Aborted>(bob2)
        assertTrue(actions2.isEmpty())
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
            channelOrigin: ChannelOrigin? = null
        ): Triple<WaitForFundingCreated, WaitForFundingCreated, TxAddInput> {
            val (a, b, open) = TestsHelper.init(channelType, aliceFeatures, bobFeatures, currentHeight, aliceFundingAmount, bobFundingAmount, pushAmount, channelOrigin)
            val (b1, actions) = b.process(ChannelEvent.MessageReceived(open))
            val accept = actions.findOutgoingMessage<AcceptDualFundedChannel>()
            assertIs<WaitForFundingCreated>(b1)
            val (a1, actions2) = a.process(ChannelEvent.MessageReceived(accept))
            val aliceInput = actions2.findOutgoingMessage<TxAddInput>()
            assertIs<WaitForFundingCreated>(a1)
            return Triple(a1, b1, aliceInput)
        }
    }
}
