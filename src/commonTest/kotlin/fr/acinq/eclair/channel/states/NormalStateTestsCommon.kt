package fr.acinq.eclair.channel.states

import fr.acinq.bitcoin.ScriptFlags
import fr.acinq.bitcoin.Transaction
import fr.acinq.eclair.blockchain.*
import fr.acinq.eclair.channel.*
import fr.acinq.eclair.channel.hasMessage
import fr.acinq.eclair.channel.watches
import fr.acinq.eclair.transactions.Transactions
import fr.acinq.eclair.utils.msat
import fr.acinq.eclair.utils.sat
import fr.acinq.eclair.utils.sum
import fr.acinq.eclair.utils.toByteVector
import fr.acinq.eclair.wire.CommitSig
import fr.acinq.eclair.wire.Error
import fr.acinq.eclair.wire.RevokeAndAck
import fr.acinq.eclair.wire.UpdateAddHtlc
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class NormalStateTestsCommon {
    @Test fun `recv CMD_SIGN (channel backup, zero-reserve channel, fundee)`() {
        val currentBlockHeight = 500L
        val (alice, bob) = TestsHelper.reachNormal(ChannelVersion.STANDARD or ChannelVersion.ZERO_RESERVE)
        val (_, cmdAdd) = TestsHelper.makeCmdAdd(50000000.msat, alice.staticParams.nodeParams.nodeId, currentBlockHeight)
        val (bob1, actions) = bob.process(ExecuteCommand(cmdAdd))
        val add = actions.findOutgoingMessage<UpdateAddHtlc>()
        val (alice1, _) = alice.process(MessageReceived(add))
        assertTrue { (alice1 as Normal).commitments.remoteChanges.proposed.contains(add) }
        val (bob2, actions2) = bob1.process(ExecuteCommand(CMD_SIGN))
        val commitSig = actions2.findOutgoingMessage<CommitSig>()
        val blob = Helpers.encrypt(bob.staticParams.nodeParams.nodePrivateKey.value, bob2 as Normal)
        assertEquals(blob.toByteVector(), commitSig.channelData)
    }

    @Test fun `recv RevokeAndAck (channel backup, zero-reserve channel, fundee)`() {
        val currentBlockHeight = 500L
        val (alice, bob) = TestsHelper.reachNormal(ChannelVersion.STANDARD or ChannelVersion.ZERO_RESERVE)
        val (_, cmdAdd) = TestsHelper.makeCmdAdd(50000000.msat, alice.staticParams.nodeParams.nodeId, currentBlockHeight)
        val (bob1, actions) = bob.process(ExecuteCommand(cmdAdd))
        val add = actions.findOutgoingMessage<UpdateAddHtlc>()
        val (alice1, _) = alice.process(MessageReceived(add))
        assertTrue { (alice1 as Normal).commitments.remoteChanges.proposed.contains(add) }
        val (bob2, actions2) = bob1.process(ExecuteCommand(CMD_SIGN))
        val commitSig = actions2.findOutgoingMessage<CommitSig>()
        val (alice2, actions3) = alice1.process(MessageReceived(commitSig))
        val revack = actions3.findOutgoingMessage<RevokeAndAck>()
        val (bob3, _) = bob2.process(MessageReceived(revack))
        val (_, actions4) = alice2.process(ExecuteCommand(CMD_SIGN))
        val commitSig1 = actions4.findOutgoingMessage<CommitSig>()
        val (bob4, actions5) = bob3.process(MessageReceived(commitSig1))
        val revack1 = actions5.findOutgoingMessage<RevokeAndAck>()
        val blob = Helpers.encrypt(bob4.staticParams.nodeParams.nodePrivateKey.value, bob4 as Normal)
        assertEquals(blob.toByteVector(), revack1.channelData)
    }

    @Test fun `recv BITCOIN_FUNDING_SPENT (their commit with htlc)`() {
        val (alice0, bob0) = TestsHelper.reachNormal()

        val (nodes0, _,_) = TestsHelper.addHtlc(250_000_000.msat, payer = alice0, payee = bob0)
        val (alice1, bob1) = nodes0
        val (nodes1, preimage_alice2bob_2, _) = TestsHelper.addHtlc(100_000_000.msat, payer = alice1, payee = bob1)
        val (alice2, bob2) = nodes1
        val (nodes2, _, _) = TestsHelper.addHtlc(10_000.msat, payer = alice2, payee = bob2)
        val (alice3, bob3) = nodes2
        val (nodes3, preimage_bob2alice_1, _) = TestsHelper.addHtlc(50_000_000.msat, payer = bob3, payee = alice3)
        val (bob4, alice4) = nodes3
        val (nodes4, _, _) = TestsHelper.addHtlc(55_000_000.msat, payer = bob4, payee = alice4)
        val (bob5, alice5) = nodes4

        val (alice6, bob6) = TestsHelper.crossSign(alice5, bob5)
        val (alice7, bob7) = TestsHelper.fulfillHtlc(1, preimage_alice2bob_2, payer = alice6, payee = bob6)
        val (bob8, alice8) = TestsHelper.fulfillHtlc(0, preimage_bob2alice_1, payer = bob7, payee = alice7)

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

        alice8 as HasCommitments ; bob8 as HasCommitments
        val bobCommitTx = bob8.commitments.localCommit.publishableTxs.commitTx.tx
        assertEquals(6, bobCommitTx.txOut.size) // 2 main outputs and 4 pending htlcs

        val (aliceClosing, actions) = alice8.process(WatchReceived(WatchEventSpent(alice8.channelId, BITCOIN_FUNDING_SPENT, bobCommitTx)))

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
        assertEquals(815220.sat, amountClaimed) // TODO formerly 814880.sat ?

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
        val (alice0, bob0) = TestsHelper.reachNormal()

        val (nodes0, _, _) = TestsHelper.addHtlc(250_000_000.msat, payer = alice0, payee = bob0)
        val (alice1, bob1) = nodes0
        val (nodes1, preimage_alice2bob_2, _) = TestsHelper.addHtlc(100_000_000.msat, payer = alice1, payee = bob1)
        val (alice2, bob2) = nodes1
        val (nodes2, _, _) = TestsHelper.addHtlc(10_000.msat, payer = alice2, payee = bob2)
        val (alice3, bob3) = nodes2
        val (nodes3, preimage_bob2alice_1, _) = TestsHelper.addHtlc(50_000_000.msat, payer = bob3, payee = alice3)
        val (bob4, alice4) = nodes3
        val (nodes4, _, _) = TestsHelper.addHtlc(55_000_000.msat, payer = bob4, payee = alice4)
        val (bob5, alice5) = nodes4

        val (alice6, bob6) = TestsHelper.crossSign(alice5, bob5)
        val (alice7, bob7) = TestsHelper.fulfillHtlc(1, preimage_alice2bob_2, payer = alice6, payee = bob6)
        val (bob8, alice8) = TestsHelper.fulfillHtlc(0, preimage_bob2alice_1, payer = bob7, payee = alice7)

        // alice sign but we intercept bob's revocation
        val (alice9, aActions8) = alice8.process(ExecuteCommand(CMD_SIGN))
        val commitSig0 = aActions8.findOutgoingMessage<CommitSig>()
        val (bob9, _) = bob8.process(MessageReceived(commitSig0))

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

        alice9 as HasCommitments ; bob9 as HasCommitments
        // bob publishes his current commit tx
        val bobCommitTx = bob9.commitments.localCommit.publishableTxs.commitTx.tx
        assertEquals(5, bobCommitTx.txOut.size) // 2 main outputs and 3 pending htlcs

        val (aliceClosing, actions) = alice9.process(WatchReceived(WatchEventSpent(alice9.channelId, BITCOIN_FUNDING_SPENT, bobCommitTx)))

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
        assertEquals(822330.sat, amountClaimed) // TODO formerly 822310.sat ?

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

    @Test fun `recv BITCOIN_FUNDING_SPENT (revoked commit)`() {
        var (alice, bob) = TestsHelper.reachNormal()
        // initially we have :
        // alice = 800 000
        //   bob = 200 000
        fun send(): Transaction {
            // alice sends 8 000 sat
            TestsHelper.addHtlc(10_000_000.msat, payer = alice, payee = bob)
                .first.run { alice = first as Normal; bob = second as Normal }
            TestsHelper.crossSign(alice, bob)
                .run { alice = first as Normal; bob = second as Normal }

            return bob.commitments.localCommit.publishableTxs.commitTx.tx
        }

        val txes = (0..9).map { send() }
        // bob now has 10 spendable tx, 9 of them being revoked

        // let's say that bob published this tx
        val revokedTx = txes[3]
        // channel state for this revoked tx is as follows:
        // alice = 760 000
        //   bob = 200 000
        //  a->b =  10 000
        //  a->b =  10 000
        //  a->b =  10 000
        //  a->b =  10 000
        // 2 main outputs + 4 htlc
        assertEquals(6, revokedTx.txOut.size)

        val (aliceClosing, actions) = alice.process(WatchReceived(WatchEventSpent(alice.channelId, BITCOIN_FUNDING_SPENT, revokedTx)))

        assertTrue(aliceClosing is Closing)
        assertEquals(1, aliceClosing.revokedCommitPublished.size)
        assertTrue(actions.isNotEmpty())
        assertTrue(actions.hasMessage<Error>())

        val claimTxes = actions.filterIsInstance<PublishTx>().map { it.tx }
        val mainTx = claimTxes[0]
        val mainPenaltyTx = claimTxes[1]
        // TODO business code is disabled for now
        //      val htlcPenaltyTxs = claimTxes.drop(2)
        //      assertEquals(2, htlcPenaltyTxs.size)
        //      // let's make sure that htlc-penalty txs each spend a different output
        //      assertEquals(htlcPenaltyTxs.map { it.txIn.first().outPoint.index }.toSet().size, htlcPenaltyTxs.size)

        assertEquals(BITCOIN_TX_CONFIRMED(revokedTx), actions.watches<WatchConfirmed>()[0].event)
        assertEquals(BITCOIN_TX_CONFIRMED(mainTx), actions.watches<WatchConfirmed>()[1].event)
        assertTrue(actions.watches<WatchSpent>().all { it.event is BITCOIN_OUTPUT_SPENT })
        // TODO business code is disabled for now
        //        assert(alice2blockchain.expectMsgType[WatchSpent].event === BITCOIN_OUTPUT_SPENT) // main-penalty
        //        htlcPenaltyTxs.foreach(htlcPenaltyTx => assert(alice2blockchain.expectMsgType[WatchSpent].event === BITCOIN_OUTPUT_SPENT))

        Transaction.correctlySpends(mainTx, listOf(revokedTx), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
        Transaction.correctlySpends(mainPenaltyTx, listOf(revokedTx), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
        // TODO business code is disabled for now
//            htlcPenaltyTxs.forEach {
//                Transaction.correctlySpends(it, listOf(revokedTx), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
//            }

        // two main outputs are 760 000 and 200 000
        assertEquals(741500.sat, mainTx.txOut[0].amount)
        assertEquals(195160.sat, mainPenaltyTx.txOut[0].amount)
        // TODO business code is disabled for now
        //        assertEquals(4540.sat, htlcPenaltyTxs[0].txOut[0].amount)
        //        assertEquals(4540.sat, htlcPenaltyTxs[1].txOut[0].amount)
        //        assertEquals(4540.sat, htlcPenaltyTxs[2].txOut[0].amount)
        //        assertEquals(4540.sat, htlcPenaltyTxs[3].txOut[0].amount)
    }

    @Test fun `recv BITCOIN_FUNDING_SPENT (revoked commit with identical htlcs)`() {
        val (alice0, bob0) = TestsHelper.reachNormal()
        // initially we have :
        // alice = 800 000
        //   bob = 200 000
        val (_, cmdAddHtlc) = TestsHelper.makeCmdAdd(
            10_000_000.msat,
            bob0.staticParams.nodeParams.nodeId,
            alice0.currentBlockHeight.toLong()
        )

        val (alice1, bob1) = TestsHelper.addHtlc(cmdAdd = cmdAddHtlc, payer = alice0, payee = bob0)
        val (alice2, bob2) = TestsHelper.addHtlc(cmdAdd = cmdAddHtlc, payer = alice1, payee = bob1)

        val (alice3, bob3) = TestsHelper.crossSign(alice2, bob2)

        // bob will publish this tx after it is revoked
        val revokedTx = (bob3 as HasCommitments).commitments.localCommit.publishableTxs.commitTx.tx

        val (alice4, bob4) = TestsHelper.addHtlc(amount = 10000000.msat, payer = alice3, payee = bob3).first
        val (alice5, bob5) = TestsHelper.crossSign(alice4, bob4)

        // channel state for this revoked tx is as follows:
        // alice = 780 000
        //   bob = 200 000
        //  a->b =  10 000
        //  a->b =  10 000
        assertEquals(4, revokedTx.txOut.size)

        val (aliceClosing, actions) = alice5.process(WatchReceived(WatchEventSpent((alice5 as HasCommitments).channelId, BITCOIN_FUNDING_SPENT, revokedTx)))

        assertTrue(aliceClosing is Closing)
        assertTrue(actions.isNotEmpty())
        assertTrue(actions.hasMessage<Error>())

        val claimTxes = actions.filterIsInstance<PublishTx>().map { it.tx }
        val mainTx = claimTxes[0]
        val mainPenaltyTx = claimTxes[1]
        // TODO business code is disabled for now
        //      val htlcPenaltyTxs = claimTxes.drop(2)
        //      assertEquals(2, htlcPenaltyTxs.size)
        //      // let's make sure that htlc-penalty txs each spend a different output
        //      assertEquals(htlcPenaltyTxs.map { it.txIn.first().outPoint.index }.toSet().size, htlcPenaltyTxs.size)

        assertEquals(BITCOIN_TX_CONFIRMED(revokedTx), actions.watches<WatchConfirmed>()[0].event)
        assertEquals(BITCOIN_TX_CONFIRMED(mainTx), actions.watches<WatchConfirmed>()[1].event)
        assertTrue(actions.watches<WatchSpent>().all { it.event is BITCOIN_OUTPUT_SPENT })
        // TODO business code is disabled for now
        //        assert(alice2blockchain.expectMsgType[WatchSpent].event === BITCOIN_OUTPUT_SPENT) // main-penalty
        //        htlcPenaltyTxs.foreach(htlcPenaltyTx => assert(alice2blockchain.expectMsgType[WatchSpent].event === BITCOIN_OUTPUT_SPENT))

        Transaction.correctlySpends(mainTx, listOf(revokedTx), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
        Transaction.correctlySpends(mainPenaltyTx, listOf(revokedTx), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
        // TODO business code is disabled for now
//            htlcPenaltyTxs.forEach {
//                Transaction.correctlySpends(it, listOf(revokedTx), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
//            }
    }
}
