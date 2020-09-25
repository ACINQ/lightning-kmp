package fr.acinq.eclair.channel.states

import fr.acinq.bitcoin.ByteVector32
import fr.acinq.eclair.blockchain.BITCOIN_FUNDING_SPENT
import fr.acinq.eclair.blockchain.BITCOIN_TX_CONFIRMED
import fr.acinq.eclair.blockchain.WatchConfirmed
import fr.acinq.eclair.blockchain.WatchEventSpent
import fr.acinq.eclair.channel.*
import fr.acinq.eclair.utils.msat
import org.kodein.log.Logger
import org.kodein.log.LoggerFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NormalStateTests {
    private val logger = LoggerFactory.default.newLogger(Logger.Tag(NormalStateTests::class))

    @Test fun `recv BITCOIN_FUNDING_SPENT (their commit with htlc)`() {
        var (alice, bob) = TestsHelper.reachNormal()

        val fee = 1720000.msat // fee due to the additional htlc output
        val funderFeeReserve = fee * 2 // extra reserve to handle future fee increase

        val ac0 = alice.commitments
        val bc0 = bob.commitments

        var aliceBalance = ac0.availableBalanceForSend() // initial alice's balance
        var bobBalance = bc0.availableBalanceForSend() // initial bob's balance

        val payment1 = 42000000.msat
        val (alice1, bob1) = TestsHelper.makePayment(payment1, alice, bob, logger)
        alice = alice1 as Normal
        bob = bob1 as Normal

        val ac1 = alice.commitments
        val bc1 = bob.commitments

        assertEquals(ac1.availableBalanceForSend(),  aliceBalance - payment1)
        assertEquals(ac1.availableBalanceForReceive(), bobBalance + payment1)
        assertEquals(bc1.availableBalanceForSend(), bobBalance + payment1)
        assertEquals(bc1.availableBalanceForReceive(), aliceBalance - payment1)

        aliceBalance = ac1.availableBalanceForSend() // update alice's balance
        bobBalance = bc1.availableBalanceForSend() // update bob's balance

        val bobCommitTx = bob.commitments.localCommit.publishableTxs.commitTx.tx
        assertEquals(2, bobCommitTx.txOut.size)
        assertEquals(992760, bobCommitTx.txOut.sumOf { it.amount.sat })

        val (alice2, actions) = alice.process(WatchReceived(WatchEventSpent(ByteVector32.Zeroes, BITCOIN_FUNDING_SPENT, bobCommitTx)))

        assertTrue(alice2 is Closing)
        assertEquals(4, actions.size)
        assertTrue(actions[0] is StoreState)
        assertTrue(actions[1] is PublishTx)
        assertTrue(actions[2] is SendWatch)
        assertEquals(WatchConfirmed(bob.channelId, bobCommitTx, 3, BITCOIN_TX_CONFIRMED(bobCommitTx)), (actions[2] as SendWatch).watch)
        assertTrue(actions[3] is SendWatch)
        val publishTx = (actions[1] as PublishTx).tx
        assertEquals(WatchConfirmed(alice.channelId, publishTx, 3, BITCOIN_TX_CONFIRMED(publishTx)), (actions[3] as SendWatch).watch)
    }

    @Test fun `recv BITCOIN_FUNDING_SPENT (their *next* commit with htlc)`() {


    }

    @Test fun `recv BITCOIN_FUNDING_SPENT (revoked commavant it)`() {}
    @Test fun `recv BITCOIN_FUNDING_SPENT (revoked commit with identical htlcs)`() {}
}
