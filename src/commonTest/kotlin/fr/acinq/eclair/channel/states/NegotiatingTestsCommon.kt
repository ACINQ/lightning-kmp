package fr.acinq.eclair.channel.states

import fr.acinq.bitcoin.Script
import fr.acinq.bitcoin.ScriptFlags
import fr.acinq.bitcoin.Transaction
import fr.acinq.bitcoin.TxOut
import fr.acinq.eclair.Eclair.randomKey
import fr.acinq.eclair.blockchain.*
import fr.acinq.eclair.channel.*
import fr.acinq.eclair.channel.TestsHelper.mutualClose
import fr.acinq.eclair.tests.utils.EclairTestSuite
import fr.acinq.eclair.utils.toByteVector
import fr.acinq.eclair.wire.ClosingSigned
import fr.acinq.eclair.wire.Shutdown
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NegotiatingTestsCommon : EclairTestSuite() {

    @Test
    fun `correctly sign and detect closing tx`() {
        // we're fundee here, not funder !!
        val (bob, alice) = TestsHelper.reachNormal()
        val priv = randomKey()

        // Alice initiates a mutual close with a custom final script
        val finalScript = Script.write(Script.pay2pkh(priv.publicKey())).toByteVector()
        val (alice1, actions1) = alice.process(ChannelEvent.ExecuteCommand(CMD_CLOSE(finalScript)))
        val shutdownA = actions1.findOutgoingMessage<Shutdown>()

        // Bob replies with Shutdown + ClosingSigned
        val (bob1, actions2) = bob.process(ChannelEvent.MessageReceived(shutdownA))
        val shutdownB = actions2.findOutgoingMessage<Shutdown>()
        val closingSignedB = actions2.findOutgoingMessage<ClosingSigned>()

        // Alice agrees with Bob's closing fee, publishes her closing tx and replies with her own ClosingSigned
        val (alice2, _) = alice1.process(ChannelEvent.MessageReceived(shutdownB))
        val (alice3, actions4) = alice2.process(ChannelEvent.MessageReceived(closingSignedB))
        alice3 as Closing
        val closingTxA = actions4.filterIsInstance<ChannelAction.Blockchain.PublishTx>().first().tx
        val closingSignedA = actions4.findOutgoingMessage<ClosingSigned>()
        val watch = actions4.findWatch<WatchConfirmed>()
        assertEquals(watch.txId, closingTxA.txid)

        val fundingTx = Transaction(
            version = 2,
            txIn = listOf(),
            txOut = listOf(bob.commitments.commitInput.txOut),
            lockTime = 0
        )
        (assertEquals(fundingTx.txid, closingTxA.txIn[0].outPoint.txid))
        // check that our closing tx is correctly signed
        Transaction.correctlySpends(closingTxA, fundingTx, ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)

        // Bob published his closing tx (which should be the same as Alice's !!!)
        val (bob2, actions5) = bob1.process(ChannelEvent.MessageReceived(closingSignedA))
        assertTrue(bob2 is Closing)
        val closingTxB = actions5.filterIsInstance<ChannelAction.Blockchain.PublishTx>().first().tx
        assertEquals(closingTxA, closingTxB)

        // Alice sees Bob's closing tx (which should be the same as the one she published)
        val (alice4, _) = alice3.process(ChannelEvent.WatchReceived(WatchEventSpent(alice3.channelId, BITCOIN_FUNDING_SPENT, closingTxB)))
        assertTrue(alice4 is Closing)

        val (alice5, _) = alice4.process(ChannelEvent.WatchReceived(WatchEventConfirmed(alice3.channelId, BITCOIN_TX_CONFIRMED(closingTxA), 144, 0, closingTxA)))
        assertTrue(alice5 is Closed)
    }

    @Test
    fun `recv ClosingSigned (theirCloseFee != ourCloseFee)`() {
        val (alice, bob, aliceCloseSig) = init()
        val (_, actions) = bob.process(ChannelEvent.MessageReceived(aliceCloseSig))
        // Bob answers with a counter proposition
        val bobCloseSig = actions.findOutgoingMessage<ClosingSigned>()
        assertTrue { aliceCloseSig.feeSatoshis > bobCloseSig.feeSatoshis }
        val (alice1, actions1) = alice.process(ChannelEvent.MessageReceived(bobCloseSig))
        val aliceCloseSig1 = actions1.findOutgoingMessage<ClosingSigned>()
        // BOLT 2: If the receiver [doesn't agree with the fee] it SHOULD propose a value strictly between the received fee-satoshis and its previously-sent fee-satoshis
        assertTrue { aliceCloseSig1.feeSatoshis < aliceCloseSig.feeSatoshis && aliceCloseSig1.feeSatoshis > bobCloseSig.feeSatoshis }
        assertEquals((alice1 as Negotiating).closingTxProposed.last().map { it.localClosingSigned }, alice.closingTxProposed.last().map { it.localClosingSigned } + listOf(aliceCloseSig1))
    }

    @Test
    fun `recv ClosingSigned (theirCloseFee == ourCloseFee)`() {
        val (alice, bob, aliceCloseSig) = init()
        assertTrue { converge(alice, bob, aliceCloseSig) != null }
    }

    @Test
    fun `recv ClosingSigned (theirCloseFee == ourCloseFee)(different fee parameters)`() {
        val (alice, bob, aliceCloseSig) = init(true)
        assertTrue { converge(alice, bob, aliceCloseSig) != null }
    }

    companion object {
        fun init(tweakFees: Boolean = false): Triple<Negotiating, Negotiating, ClosingSigned> {
            val (alice, bob) = TestsHelper.reachNormal()
            return mutualClose(alice, bob, tweakFees)
        }

        tailrec fun converge(a: ChannelState, b: ChannelState, aliceCloseSig: ClosingSigned?): Pair<Closing, Closing>? {
            return when {
                a !is ChannelStateWithCommitments || b !is ChannelStateWithCommitments -> null
                a is Closing && b is Closing -> Pair(a, b)
                aliceCloseSig != null -> {
                    val (b1, actions) = b.process(ChannelEvent.MessageReceived(aliceCloseSig))
                    val bobCloseSig = actions.findOutgoingMessageOpt<ClosingSigned>()
                    if (bobCloseSig != null) {
                        val (a1, actions2) = a.process(ChannelEvent.MessageReceived(bobCloseSig))
                        return converge(a1, b1, actions2.findOutgoingMessageOpt<ClosingSigned>())
                    }
                    val bobClosingTx = actions.filterIsInstance<ChannelAction.Blockchain.PublishTx>().map { it.tx }.firstOrNull()
                    if (bobClosingTx != null && bobClosingTx.txIn[0].outPoint == a.commitments.localCommit.publishableTxs.commitTx.input.outPoint && a !is Closing) {
                        // Bob just spent the funding tx
                        val (a1, actions2) = a.process(ChannelEvent.WatchReceived(WatchEventSpent(a.channelId, BITCOIN_FUNDING_SPENT, bobClosingTx)))
                        return converge(a1, b1, actions2.findOutgoingMessageOpt<ClosingSigned>())
                    }
                    converge(a, b1, null)
                }
                else -> null
            }
        }
    }
}
