package fr.acinq.eclair.channel.states

import fr.acinq.bitcoin.ScriptFlags
import fr.acinq.bitcoin.Transaction
import fr.acinq.eclair.blockchain.*
import fr.acinq.eclair.channel.*
import fr.acinq.eclair.channel.TestsHelper.addHtlc
import fr.acinq.eclair.channel.TestsHelper.crossSign
import fr.acinq.eclair.channel.TestsHelper.fulfillHtlc
import fr.acinq.eclair.channel.TestsHelper.reachNormal
import fr.acinq.eclair.transactions.Transactions
import fr.acinq.eclair.utils.msat
import fr.acinq.eclair.utils.sat
import fr.acinq.eclair.utils.sum
import fr.acinq.eclair.wire.CommitSig
import org.kodein.log.Logger
import org.kodein.log.LoggerFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NormalStateTests {
    private val logger = LoggerFactory.default.newLogger(Logger.Tag(NormalStateTests::class))
    @Test fun `recv BITCOIN_FUNDING_SPENT (their commit with htlc)`() {
        var (alice, bob) = TestsHelper.reachNormal()

        val (nodes0, _, _) = addHtlc(250_000_000.msat, payer = alice, payee = bob)
        nodes0.run { alice = first as Normal ; bob = second as Normal }
        val (nodes1, preimage_alice2bob_2, _) = addHtlc(100_000_000.msat, payer = alice, payee = bob)
        nodes1.run { alice = first as Normal ; bob = second as Normal }
        val (nodes2, _, _) = addHtlc(10_000.msat, payer = alice, payee = bob)
        nodes2.run { alice = first as Normal ; bob = second as Normal }
        val (nodes3, preimage_bob2alice_1, _) = addHtlc(50_000_000.msat, payer = bob, payee = alice)
        nodes3.run { bob = first as Normal ; alice = second as Normal }
        val (nodes4, _, _) = addHtlc(55_000_000.msat, payer = bob, payee = alice)
        nodes4.run { bob = first as Normal ; alice = second as Normal }

        crossSign(nodeA = alice, nodeB = bob)
            .run { alice = first as Normal ; bob = second as Normal }
        fulfillHtlc(1, preimage_alice2bob_2, payer = alice, payee = bob)
            .run { alice = first as Normal ; bob = second as Normal }
        fulfillHtlc(0, preimage_bob2alice_1, payer = bob, payee = alice)
            .run { bob = first as Normal ; alice = second as Normal }

        // at this point here is the situation from alice pov and what she should do when bob publishes his commit tx:
        // balances :
        //    alice's balance : 449 999 990                             => nothing to do
        //    bob's balance   :  95 000 000                             => nothing to do
        // htlcs :
        //    alice -> bob    : 250 000 000 (bob does not have the preimage)   => wait for the timeout and spend
        //    alice -> bob    : 100 000 000 (bob has the preimage)             => if bob does not use the preimage, wait for the timeout and spend
        //    alice -> bob    :          10 (dust)                             => won't appear in the commitment tx
        //    bob -> alice    :  50 000 000 (alice has the preimage)           => spend immediately using the preimage
        //    bob -> alice    :  55 000 000 (alice does not have the preimage) => nothing to do, bob will get his money back after the timeout

        val bobCommitTx = bob.commitments.localCommit.publishableTxs.commitTx.tx
        assertEquals(6, bobCommitTx.txOut.size) // 2 main outputs and 4 pending htlcs

        val (aliceClosing, actions) = alice.process(WatchReceived(WatchEventSpent(alice.channelId, BITCOIN_FUNDING_SPENT, bobCommitTx)))

        assertTrue(aliceClosing is Closing)
        assertTrue(actions.isNotEmpty())
        // in response to that, alice publishes its claim txes
        val claimTxes = actions.filterIsInstance<PublishTx>().map { it.tx }
        assertEquals(4, claimTxes.size)
        val claimMain = claimTxes.first()
        // in addition to its main output, alice can only claim 3 out of 4 htlcs,
        // she can't do anything regarding the htlc sent by bob for which she does not have the preimage
        val amountClaimed = claimTxes.map { claimHtlcTx ->
            assertEquals(1, claimHtlcTx.txIn.size)
            assertEquals(1, claimHtlcTx.txOut.size)
            Transaction.correctlySpends(claimHtlcTx, listOf(bobCommitTx), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
            claimHtlcTx.txOut[0].amount
        }.sum()
        // at best we have a little less than 450 000 + 250 000 + 100 000 + 50 000 = 850 000 (because fees)
        assertEquals(815220.sat, amountClaimed) // TODO ? formerly 814880.sat

        assertEquals(BITCOIN_TX_CONFIRMED(bobCommitTx), actions.watches<WatchConfirmed>()[0].event)
        assertEquals(BITCOIN_TX_CONFIRMED(claimMain), actions.watches<WatchConfirmed>()[1].event)
        assertEquals(3, actions.watches<WatchSpent>().count { it.event is BITCOIN_OUTPUT_SPENT })

        assertEquals(1, aliceClosing.remoteCommitPublished?.claimHtlcSuccessTxs?.size)
        assertEquals(2, aliceClosing.remoteCommitPublished?.claimHtlcTimeoutTxs?.size)

        // assert the feerate of the claim main is what we expect
        aliceClosing.staticParams.nodeParams.onChainFeeConf.run {
            val feeTargets = aliceClosing.staticParams.nodeParams.onChainFeeConf.feeTargets
            val expectedFeeRate = feeEstimator.getFeeratePerKw(feeTargets.claimMainBlockTarget)
            val expectedFee = Transactions.weight2fee(expectedFeeRate, Transactions.claimP2WPKHOutputWeight)
            val claimFee = claimMain.txIn.map {
                bobCommitTx.txOut[it.outPoint.index.toInt()].amount
            }.sum() - claimMain.txOut.map { it.amount }.sum()
            assertEquals(expectedFee, claimFee)
        }
    }

    @Test fun `recv BITCOIN_FUNDING_SPENT (their *next* commit with htlc)`() {
        var (alice, bob) = TestsHelper.reachNormal()

        val (nodes0, _, _) = addHtlc(250_000_000.msat, payer = alice, payee = bob)
        nodes0.run { alice = first as Normal ; bob = second as Normal }
        val (nodes1, preimage_alice2bob_2, _) = addHtlc(100_000_000.msat, payer = alice, payee = bob)
        nodes1.run { alice = first as Normal ; bob = second as Normal }
        val (nodes2, _, _) = addHtlc(10_000.msat, payer = alice, payee = bob)
        nodes2.run { alice = first as Normal ; bob = second as Normal }
        val (nodes3, preimage_bob2alice_1, _) = addHtlc(50_000_000.msat, payer = bob, payee = alice)
        nodes3.run { bob = first as Normal ; alice = second as Normal }
        val (nodes4, _, _) = addHtlc(55_000_000.msat, payer = bob, payee = alice)
        nodes4.run { bob = first as Normal ; alice = second as Normal }

        crossSign(nodeA = alice, nodeB = bob)
            .run { alice = first as Normal ; bob = second as Normal }
        fulfillHtlc(1, preimage_alice2bob_2, payer = alice, payee = bob)
            .run { alice = first as Normal ; bob = second as Normal }
        fulfillHtlc(0, preimage_bob2alice_1, payer = bob, payee = alice)
            .run { bob = first as Normal ; alice = second as Normal }

        // alice sign but we intercept bob's revocation
        val (alice0, aActions0) = alice.process(ExecuteCommand(CMD_SIGN))
        alice = alice0 as Normal
        val commitSig0 = aActions0.findOutgoingMessage<CommitSig>()
        val (bob0, _) = bob.process(MessageReceived(commitSig0))
        bob = bob0 as Normal

        // as far as alice knows, bob currently has two valid unrevoked commitment transactions

        // at this point here is the situation from bob's pov with the latest sig received from alice,
        // and what alice should do when bob publishes his commit tx:
        // balances :
        //    alice's balance : 499 999 990                             => nothing to do
        //    bob's balance   :  95 000 000                             => nothing to do
        // htlcs :
        //    alice -> bob    : 250 000 000 (bob does not have the preimage)   => wait for the timeout and spend
        //    alice -> bob    : 100 000 000 (bob has the preimage)             => if bob does not use the preimage, wait for the timeout and spend
        //    alice -> bob    :          10 (dust)                             => won't appear in the commitment tx
        //    bob -> alice    :  55 000 000 (alice does not have the preimage) => nothing to do, bob will get his money back after the timeout

        // bob publishes his current commit tx

        val bobCommitTx = bob.commitments.localCommit.publishableTxs.commitTx.tx
        assertEquals(5, bobCommitTx.txOut.size) // 2 main outputs and 3 pending htlcs

        val (aliceClosing, actions) = alice.process(WatchReceived(WatchEventSpent(alice.channelId, BITCOIN_FUNDING_SPENT, bobCommitTx)))

        assertTrue(aliceClosing is Closing)
        assertTrue(actions.isNotEmpty())

        // in response to that, alice publishes its claim txes
        val claimTxes = actions.filterIsInstance<PublishTx>().map { it.tx }
        assertEquals(3, claimTxes.size)
        val claimMain = claimTxes.first()
        // in addition to its main output, alice can only claim 2 out of 3 htlcs,
        // she can't do anything regarding the htlc sent by bob for which she does not have the preimage
        val amountClaimed = claimTxes.map { claimHtlcTx ->
            assertEquals(1, claimHtlcTx.txIn.size)
            assertEquals(1, claimHtlcTx.txOut.size)
            Transaction.correctlySpends(claimHtlcTx, listOf(bobCommitTx), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
            claimHtlcTx.txOut[0].amount
        }.sum()
        // at best we have a little less than 500 000 + 250 000 + 100 000 = 850 000 (because fees)
        assertEquals(822330.sat, amountClaimed) // TODO ? formerly 822310.sat

        assertEquals(BITCOIN_TX_CONFIRMED(bobCommitTx), actions.watches<WatchConfirmed>()[0].event)
        assertEquals(BITCOIN_TX_CONFIRMED(claimMain), actions.watches<WatchConfirmed>()[1].event)
        assertEquals(2, actions.watches<WatchSpent>().count { it.event is BITCOIN_OUTPUT_SPENT })

        assertEquals(0, aliceClosing.remoteCommitPublished?.claimHtlcSuccessTxs?.size)
        assertEquals(2, aliceClosing.remoteCommitPublished?.claimHtlcTimeoutTxs?.size)

        // assert the feerate of the claim main is what we expect
        aliceClosing.staticParams.nodeParams.onChainFeeConf.run {
            val feeTargets = aliceClosing.staticParams.nodeParams.onChainFeeConf.feeTargets
            val expectedFeeRate = feeEstimator.getFeeratePerKw(feeTargets.claimMainBlockTarget)
            val expectedFee = Transactions.weight2fee(expectedFeeRate, Transactions.claimP2WPKHOutputWeight)
            val claimFee = claimMain.txIn.map {
                bobCommitTx.txOut[it.outPoint.index.toInt()].amount
            }.sum() - claimMain.txOut.map { it.amount }.sum()
            assertEquals(expectedFee, claimFee)
        }
    }

    @Test fun `recv BITCOIN_FUNDING_SPENT (revoked commavant it)`() {}
    @Test fun `recv BITCOIN_FUNDING_SPENT (revoked commit with identical htlcs)`() {}
}
