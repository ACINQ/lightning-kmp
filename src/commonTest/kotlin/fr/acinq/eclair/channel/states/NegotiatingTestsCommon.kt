package fr.acinq.eclair.channel.states

import fr.acinq.eclair.blockchain.BITCOIN_FUNDING_SPENT
import fr.acinq.eclair.blockchain.WatchEventSpent
import fr.acinq.eclair.channel.*
import fr.acinq.eclair.channel.TestsHelper.mutualClose
import fr.acinq.eclair.wire.ClosingSigned
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NegotiatingTestsCommon {
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
            val (alice, bob) = TestsHelper.reachNormal(ChannelVersion.STANDARD)
            return mutualClose(alice, bob, tweakFees)
        }

        tailrec fun converge(a: ChannelState, b: ChannelState, aliceCloseSig: ClosingSigned?): Pair<Closing, Closing>? {
            return when {
                a !is ChannelStateWithCommitments || b !is ChannelStateWithCommitments -> null
                a is Closing && b is Closing -> Pair(a, b)
                aliceCloseSig != null -> {
                    val (b1, actions) = b.process(ChannelEvent.MessageReceived(aliceCloseSig))
                    val bobCloseSig = actions.hasOutgoingMessage<ClosingSigned>()
                    if (bobCloseSig != null) {
                        val (a1, actions2) = a.process(ChannelEvent.MessageReceived(bobCloseSig))
                        return converge(a1, b1, actions2.hasOutgoingMessage<ClosingSigned>())
                    }
                    val bobClosingTx = actions.filterIsInstance<ChannelAction.Blockchain.PublishTx>().map { it.tx }.firstOrNull()
                    if (bobClosingTx != null && bobClosingTx.txIn[0].outPoint == a.commitments.localCommit.publishableTxs.commitTx.input.outPoint && a !is Closing) {
                        // Bob just spent the funding tx
                        val (a1, actions2) = a.process(ChannelEvent.WatchReceived(WatchEventSpent(a.channelId, BITCOIN_FUNDING_SPENT, bobClosingTx)))
                        return converge(a1, b1, actions2.hasOutgoingMessage<ClosingSigned>())
                    }
                    converge(a, b1, null)
                }
                else -> null
            }
        }
    }
}
