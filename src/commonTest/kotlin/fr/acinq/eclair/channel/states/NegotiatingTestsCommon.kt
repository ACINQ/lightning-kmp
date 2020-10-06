package fr.acinq.eclair.channel.states

import fr.acinq.eclair.blockchain.BITCOIN_FUNDING_SPENT
import fr.acinq.eclair.blockchain.WatchEventSpent
import fr.acinq.eclair.blockchain.fee.ConstantFeeEstimator
import fr.acinq.eclair.blockchain.fee.OnchainFeerates
import fr.acinq.eclair.channel.*
import fr.acinq.eclair.channel.TestsHelper.findOutgoingMessage
import fr.acinq.eclair.channel.TestsHelper.hasOutgoingMessage
import fr.acinq.eclair.wire.ClosingSigned
import fr.acinq.eclair.wire.Shutdown
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NegotiatingTestsCommon {
    @Test
    fun `recv ClosingSigned (theirCloseFee != ourCloseFee)`() {
        val (alice, bob, aliceCloseSig) = init()
        val (bob1, actions) = bob.process(MessageReceived(aliceCloseSig))
        // Bob answers with a counter proposition
        val bobCloseSig = actions.findOutgoingMessage<ClosingSigned>()
        assertTrue { aliceCloseSig.feeSatoshis > bobCloseSig.feeSatoshis }
        val (alice1, actions1) = alice.process(MessageReceived(bobCloseSig))
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
            val (alice, bob) = TestsHelper.reachNormal(ChannelVersion.STANDARD)

            fun Normal.updateFeerate(feerate: Long): Normal = this.copy(currentOnchainFeerates = OnchainFeerates(feerate, feerate, feerate, feerate))

            fun Negotiating.updateFeerate(feerate: Long): Negotiating = this.copy(currentOnchainFeerates = OnchainFeerates(feerate, feerate, feerate, feerate))

            val alice1 = alice.updateFeerate(if (tweakFees) 4319 else 10000)
            val bob1 = bob.updateFeerate(if (tweakFees) 4319 else 10000)

            // Bob is fundee and initiates the closing
            val (bob2, actions) = bob1.process(ExecuteCommand(CMD_CLOSE(null)))
            val shutdown = actions.findOutgoingMessage<Shutdown>()

            // Alice is funder, she will sign the first closing tx
            val (alice2, actions1) = alice1.process(MessageReceived(shutdown))
            assertTrue { alice2 is Negotiating }
            val shutdown1 = actions1.findOutgoingMessage<Shutdown>()
            val closingSigned = actions1.findOutgoingMessage<ClosingSigned>()

            val alice3 = (alice2 as Negotiating).updateFeerate(if (tweakFees) 4316 else 5000)
            val bob3 = (bob2 as Normal).updateFeerate(if (tweakFees) 4316 else 5000)

            val (bob4, _) = bob3.process(MessageReceived(shutdown1))
            assertTrue { bob4 is Negotiating }
            return Triple(alice3 as Negotiating, bob4 as Negotiating, closingSigned)
        }

        tailrec fun converge(a: ChannelState, b: ChannelState, aliceCloseSig: ClosingSigned?): Pair<Closing, Closing>? {
            return when {
                a !is HasCommitments || b !is HasCommitments -> null
                a is Closing && b is Closing -> Pair(a, b)
                aliceCloseSig != null -> {
                    val (b1, actions) = b.process(MessageReceived(aliceCloseSig))
                    val bobCloseSig = actions.hasOutgoingMessage<ClosingSigned>()
                    if (bobCloseSig != null) {
                        val (a1, actions2) = a.process(MessageReceived(bobCloseSig))
                        return converge(a1, b1, actions2.hasOutgoingMessage<ClosingSigned>())
                    }
                    val bobClosingTx = actions.filterIsInstance<PublishTx>().map { it.tx }.firstOrNull()
                    if (bobClosingTx != null && bobClosingTx.txIn[0].outPoint == a.commitments.localCommit.publishableTxs.commitTx.input.outPoint && a !is Closing) {
                        // Bob just spent the funding tx
                        val (a1, actions2) = a.process(WatchReceived(WatchEventSpent(a.channelId, BITCOIN_FUNDING_SPENT, bobClosingTx)))
                        return converge(a1, b1, actions2.hasOutgoingMessage<ClosingSigned>())
                    }
                    converge(a, b1, null)
                }
                else -> null
            }
        }
    }
}