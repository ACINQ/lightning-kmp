package fr.acinq.lightning.channel.states

import fr.acinq.bitcoin.ByteVector64
import fr.acinq.bitcoin.Satoshi
import fr.acinq.lightning.Features
import fr.acinq.lightning.Lightning.randomBytes32
import fr.acinq.lightning.MilliSatoshi
import fr.acinq.lightning.ShortChannelId
import fr.acinq.lightning.blockchain.BITCOIN_FUNDING_DEPTHOK
import fr.acinq.lightning.blockchain.WatchConfirmed
import fr.acinq.lightning.blockchain.WatchSpent
import fr.acinq.lightning.blockchain.fee.FeeratePerKw
import fr.acinq.lightning.channel.*
import fr.acinq.lightning.tests.TestConstants
import fr.acinq.lightning.tests.utils.LightningTestSuite
import fr.acinq.lightning.utils.sat
import fr.acinq.lightning.wire.*
import kotlin.test.*

class WaitForFundingSignedTestsCommon : LightningTestSuite() {

    @Test
    fun `recv CommitSig`() {
        val (alice, commitSigAlice, bob, commitSigBob) = init()
        val commitInput = alice.firstCommitTx.localCommitTx.input
        run {
            val (alice1, actionsAlice1) = alice.process(ChannelEvent.MessageReceived(commitSigBob))
            assertIs<WaitForFundingConfirmed>(alice1)
            // Alice contributed more than Bob, so Bob must send their signatures first.
            assertEquals(actionsAlice1.size, 2)
            assertNull(actionsAlice1.findOutgoingMessageOpt<TxSignatures>())
            actionsAlice1.has<ChannelAction.Storage.StoreState>()
            val watchConfirmed = actionsAlice1.findWatch<WatchConfirmed>()
            assertEquals(WatchConfirmed(alice1.channelId, commitInput.outPoint.txid, commitInput.txOut.publicKeyScript, 3, BITCOIN_FUNDING_DEPTHOK), watchConfirmed)
        }
        run {
            val (bob1, actionsBob1) = bob.process(ChannelEvent.MessageReceived(commitSigAlice))
            assertIs<WaitForFundingConfirmed>(bob1)
            // Bob contributed less than Alice, so Bob must send their signatures first.
            assertEquals(actionsBob1.size, 4)
            actionsBob1.hasOutgoingMessage<TxSignatures>()
            actionsBob1.has<ChannelAction.Storage.StoreState>()
            actionsBob1.contains(ChannelAction.Storage.StoreIncomingAmount(TestConstants.pushAmount, null))
            val watchConfirmed = actionsBob1.findWatch<WatchConfirmed>()
            assertEquals(WatchConfirmed(bob1.channelId, commitInput.outPoint.txid, commitInput.txOut.publicKeyScript, 3, BITCOIN_FUNDING_DEPTHOK), watchConfirmed)
        }
    }

    @Test
    fun `recv CommitSig -- zero conf`() {
        val (alice, commitSigAlice, bob, commitSigBob) = init(ChannelType.SupportedChannelType.AnchorOutputsZeroReserve, zeroConf = true)
        run {
            val (alice1, actionsAlice1) = alice.process(ChannelEvent.MessageReceived(commitSigBob))
            assertIs<WaitForFundingLocked>(alice1)
            assertEquals(actionsAlice1.size, 3)
            assertEquals(actionsAlice1.hasOutgoingMessage<FundingLocked>().alias, ShortChannelId.peerId(alice.staticParams.nodeParams.nodeId))
            assertEquals(actionsAlice1.findWatch<WatchSpent>().txId, alice1.commitments.fundingTxId)
            actionsAlice1.has<ChannelAction.Storage.StoreState>()
        }
        run {
            val (bob1, actionsBob1) = bob.process(ChannelEvent.MessageReceived(commitSigAlice))
            assertIs<WaitForFundingLocked>(bob1)
            assertEquals(actionsBob1.size, 5)
            actionsBob1.hasOutgoingMessage<TxSignatures>()
            assertEquals(actionsBob1.hasOutgoingMessage<FundingLocked>().alias, ShortChannelId.peerId(bob.staticParams.nodeParams.nodeId))
            assertEquals(actionsBob1.findWatch<WatchSpent>().txId, bob1.commitments.fundingTxId)
            actionsBob1.has<ChannelAction.Storage.StoreState>()
            actionsBob1.contains(ChannelAction.Storage.StoreIncomingAmount(TestConstants.pushAmount, null))
        }
    }

    @Test
    fun `recv CommitSig -- with channel origin`() {
        val channelOrigin = ChannelOrigin.PayToOpenOrigin(randomBytes32(), 42.sat)
        val (_, commitSigAlice, bob, _) = init(pushAmount = TestConstants.pushAmount, channelOrigin = channelOrigin)
        val (bob1, actionsBob1) = bob.process(ChannelEvent.MessageReceived(commitSigAlice))
        assertIs<WaitForFundingConfirmed>(bob1)
        assertEquals(actionsBob1.size, 4)
        actionsBob1.hasOutgoingMessage<TxSignatures>()
        actionsBob1.has<ChannelAction.Storage.StoreState>()
        actionsBob1.contains(ChannelAction.Storage.StoreIncomingAmount(TestConstants.pushAmount, channelOrigin))
        actionsBob1.hasWatch<WatchConfirmed>()
    }

    @Test
    fun `recv CommitSig -- with invalid signature`() {
        val (alice, commitSigAlice, bob, commitSigBob) = init()
        run {
            val (alice1, actionsAlice1) = alice.process(ChannelEvent.MessageReceived(commitSigBob.copy(signature = ByteVector64.Zeroes)))
            assertEquals(actionsAlice1.size, 1)
            actionsAlice1.hasOutgoingMessage<Error>()
            assertIs<Aborted>(alice1)
        }
        run {
            val (bob1, actionsBob1) = bob.process(ChannelEvent.MessageReceived(commitSigAlice.copy(signature = ByteVector64.Zeroes)))
            assertEquals(actionsBob1.size, 1)
            actionsBob1.hasOutgoingMessage<Error>()
            assertIs<Aborted>(bob1)
        }
    }

    @Test
    fun `recv TxSignatures`() {
        val (alice, _, bob, _) = init()
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
        val (alice, _, bob, _) = init()
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
        val (alice, _, bob, _) = init()
        run {
            val (alice1, actionsAlice1) = alice.process(ChannelEvent.MessageReceived(TxInitRbf(alice.channelId, 0, FeeratePerKw(5000.sat))))
            assertEquals(actionsAlice1.size, 1)
            assertEquals(actionsAlice1.findOutgoingMessage<Warning>().toAscii(), InvalidRbfAttempt(alice.channelId).message)
            assertEquals(alice, alice1)
        }
        run {
            val (bob1, actionsBob1) = bob.process(ChannelEvent.MessageReceived(TxInitRbf(bob.channelId, 0, FeeratePerKw(5000.sat))))
            assertEquals(actionsBob1.size, 1)
            assertEquals(actionsBob1.findOutgoingMessage<Warning>().toAscii(), InvalidRbfAttempt(bob.channelId).message)
            assertEquals(bob, bob1)
        }
    }

    @Test
    fun `recv TxAckRbf`() {
        val (alice, _, bob, _) = init()
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
    fun `recv CMD_CLOSE`() {
        val (alice, _, _, _) = init()
        val (alice1, actions1) = alice.process(ChannelEvent.ExecuteCommand(CMD_CLOSE(null, null)))
        assertEquals(actions1.findOutgoingMessage<Error>().toAscii(), ChannelFundingError(alice.channelId).message)
        assertIs<Aborted>(alice1)
    }

    @Test
    fun `recv CMD_FORCECLOSE`() {
        val (alice, _, _, _) = init()
        val (alice1, actions1) = alice.process(ChannelEvent.ExecuteCommand(CMD_FORCECLOSE))
        assertEquals(actions1.findOutgoingMessage<Error>().toAscii(), ChannelFundingError(alice.channelId).message)
        assertIs<Aborted>(alice1)
    }

    @Test
    fun `recv Disconnected`() {
        val (alice, _, _, _) = init()
        val (alice1, actions1) = alice.process(ChannelEvent.Disconnected)
        assertTrue(actions1.isEmpty())
        assertIs<Aborted>(alice1)
    }

    companion object {
        data class Fixture(val alice: WaitForFundingSigned, val commitSigAlice: CommitSig, val bob: WaitForFundingSigned, val commitSigBob: CommitSig)

        fun init(
            channelType: ChannelType.SupportedChannelType = ChannelType.SupportedChannelType.AnchorOutputs,
            aliceFeatures: Features = TestConstants.Alice.nodeParams.features,
            bobFeatures: Features = TestConstants.Bob.nodeParams.features,
            currentHeight: Int = TestConstants.defaultBlockHeight,
            aliceFundingAmount: Satoshi = TestConstants.aliceFundingAmount,
            bobFundingAmount: Satoshi = TestConstants.bobFundingAmount,
            pushAmount: MilliSatoshi = TestConstants.pushAmount,
            zeroConf: Boolean = false,
            channelOrigin: ChannelOrigin? = null
        ): Fixture {
            val (alice, bob, inputAlice) = WaitForFundingCreatedTestsCommon.init(channelType, aliceFeatures, bobFeatures, currentHeight, aliceFundingAmount, bobFundingAmount, pushAmount, zeroConf, channelOrigin)
            val (bob1, actionsBob1) = bob.process(ChannelEvent.MessageReceived(inputAlice))
            // Bob's message will either be tx_add_input or tx_complete depending on whether Bob contributes or not.
            val (alice1, actionsAlice1) = alice.process(ChannelEvent.MessageReceived(actionsBob1.findOutgoingMessage<InteractiveTxMessage>()))
            val (bob2, actionsBob2) = bob1.process(ChannelEvent.MessageReceived(actionsAlice1.findOutgoingMessage<TxAddOutput>()))
            val (alice2, actionsAlice2) = alice1.process(ChannelEvent.MessageReceived(actionsBob2.findOutgoingMessage<TxComplete>()))
            val (bob3, actionsBob3) = bob2.process(ChannelEvent.MessageReceived(actionsAlice2.findOutgoingMessage<TxComplete>()))
            val commitSigAlice = actionsAlice2.findOutgoingMessage<CommitSig>()
            assertTrue(commitSigAlice.channelData.isEmpty())
            val commitSigBob = actionsBob3.findOutgoingMessage<CommitSig>()
            assertTrue(commitSigBob.channelData.isEmpty())
            assertIs<WaitForFundingSigned>(alice2)
            assertIs<WaitForFundingSigned>(bob3)
            return Fixture(alice2, commitSigAlice, bob3, commitSigBob)
        }
    }

}