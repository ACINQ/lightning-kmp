package fr.acinq.eclair.channel

import fr.acinq.bitcoin.*
import fr.acinq.eclair.Eclair.randomBytes32
import fr.acinq.eclair.Eclair.randomKey
import fr.acinq.eclair.blockchain.WatchConfirmed
import fr.acinq.eclair.blockchain.WatchSpent
import fr.acinq.eclair.channel.TestsHelper.claimHtlcSuccessTxs
import fr.acinq.eclair.channel.TestsHelper.claimHtlcTimeoutTxs
import fr.acinq.eclair.channel.TestsHelper.htlcSuccessTxs
import fr.acinq.eclair.channel.TestsHelper.htlcTimeoutTxs
import fr.acinq.eclair.tests.utils.EclairTestSuite
import fr.acinq.eclair.transactions.Transactions.InputInfo
import fr.acinq.eclair.transactions.Transactions.TransactionWithInputInfo.*
import fr.acinq.eclair.transactions.Transactions.TransactionWithInputInfo.ClaimHtlcTx.ClaimHtlcSuccessTx
import fr.acinq.eclair.transactions.Transactions.TransactionWithInputInfo.ClaimHtlcTx.ClaimHtlcTimeoutTx
import fr.acinq.eclair.transactions.Transactions.TransactionWithInputInfo.HtlcTx.HtlcSuccessTx
import fr.acinq.eclair.transactions.Transactions.TransactionWithInputInfo.HtlcTx.HtlcTimeoutTx
import fr.acinq.eclair.utils.sat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChannelTypesTestsCommon : EclairTestSuite() {

    @Test
    fun `standard channel features include deterministic channel key path`() {
        assertTrue(ChannelVersion.STANDARD.isSet(ChannelVersion.USE_PUBKEY_KEYPATH_BIT))
        assertTrue(!ChannelVersion.ZEROES.isSet(ChannelVersion.USE_PUBKEY_KEYPATH_BIT))
    }

    @Test
    fun `local commit published`() {
        val (lcp, _, _) = createClosingTransactions()
        assertFalse(lcp.isConfirmed())
        assertFalse(lcp.isDone())

        run {
            val actions = lcp.doPublish(randomBytes32(), 6)
            // We use watch-confirmed on the outputs only us can claim.
            val watchConfirmed = actions.findWatches<WatchConfirmed>().map { it.txId }.toSet()
            assertEquals(watchConfirmed, setOf(lcp.commitTx.txid, lcp.claimMainDelayedOutputTx!!.tx.txid) + lcp.claimHtlcDelayedTxs.map { it.tx.txid }.toSet())
            // We use watch-spent on the outputs both parties can claim (htlc outputs).
            val watchSpent = actions.findWatches<WatchSpent>().map { OutPoint(it.txId, it.outputIndex.toLong()) }.toSet()
            assertEquals(watchSpent, listOf(2L, 3L, 4L, 5L).map { OutPoint(lcp.commitTx.txid, it) }.toSet())
            val txs = actions.findTxs().toSet()
            assertEquals(txs, setOf(lcp.commitTx, lcp.claimMainDelayedOutputTx!!.tx) + lcp.htlcTxs.values.filterNotNull().map { it.tx } + lcp.claimHtlcDelayedTxs.map { it.tx }.toSet())
        }

        // Commit tx has been confirmed.
        val lcp1 = lcp.update(lcp.commitTx)
        assertTrue(lcp1.irrevocablySpent.isNotEmpty())
        assertTrue(lcp1.isConfirmed())
        assertFalse(lcp1.isDone())

        // Main output has been confirmed.
        val lcp2 = lcp1.update(lcp.claimMainDelayedOutputTx!!.tx)
        assertTrue(lcp2.isConfirmed())
        assertFalse(lcp2.isDone())

        // Our htlc-success txs and their 3rd-stage claim txs have been confirmed.
        val lcp3 = lcp2.update(lcp.htlcSuccessTxs().first().tx).update(lcp.claimHtlcDelayedTxs[0].tx).update(lcp.htlcSuccessTxs().last().tx).update(lcp.claimHtlcDelayedTxs[1].tx)
        assertTrue(lcp3.isConfirmed())
        assertFalse(lcp3.isDone())

        run {
            val actions = lcp3.doPublish(randomBytes32(), 3)
            // The only remaining transactions to watch are the 3rd-stage txs for the htlc-timeout.
            val watchConfirmed = actions.findWatches<WatchConfirmed>().map { it.txId }.toSet()
            assertEquals(watchConfirmed, lcp.claimHtlcDelayedTxs.drop(2).map { it.tx.txid }.toSet())
            // We still watch the remaining unclaimed htlc outputs.
            val watchSpent = actions.findWatches<WatchSpent>().map { OutPoint(it.txId, it.outputIndex.toLong()) }.toSet()
            assertEquals(watchSpent, listOf(4L, 5L).map { OutPoint(lcp.commitTx.txid, it) }.toSet())
            val txs = actions.findTxs()
            assertEquals(txs, lcp.htlcTimeoutTxs().map { it.tx } + lcp.claimHtlcDelayedTxs.drop(2).map { it.tx })
        }

        // Scenario 1: our htlc-timeout txs and their 3rd-stage claim txs have been confirmed.
        run {
            val lcp4a = lcp3.update(lcp.htlcTimeoutTxs().first().tx).update(lcp.claimHtlcDelayedTxs[2].tx).update(lcp.htlcTimeoutTxs().last().tx)
            assertTrue(lcp4a.isConfirmed())
            assertFalse(lcp4a.isDone())

            val lcp4b = lcp4a.update(lcp.claimHtlcDelayedTxs[3].tx)
            assertTrue(lcp4b.isConfirmed())
            assertTrue(lcp4b.isDone())
        }

        // Scenario 2: they claim the htlcs we sent before our htlc-timeout.
        run {
            val claimHtlcSuccess1 = lcp.htlcTimeoutTxs().first().tx.copy(txOut = listOf(TxOut(3_000.sat, ByteVector.empty), TxOut(2_500.sat, ByteVector.empty)))
            val lcp4a = lcp3.update(claimHtlcSuccess1)
            assertTrue(lcp4a.isConfirmed())
            assertFalse(lcp4a.isDone())

            val claimHtlcSuccess2 = lcp.htlcTimeoutTxs().last().tx.copy(txOut = listOf(TxOut(3_500.sat, ByteVector.empty), TxOut(3_100.sat, ByteVector.empty)))
            val lcp4b = lcp4a.update(claimHtlcSuccess2)
            assertTrue(lcp4b.isConfirmed())
            assertTrue(lcp4b.isDone())
        }
    }

    @Test
    fun `remote commit published`() {
        val (_, rcp, _) = createClosingTransactions()
        assertFalse(rcp.isConfirmed())
        assertFalse(rcp.isDone())

        run {
            val actions = rcp.doPublish(randomBytes32(), 6)
            // We use watch-confirmed on the outputs only us can claim.
            val watchConfirmed = actions.findWatches<WatchConfirmed>().map { it.txId }.toSet()
            assertEquals(watchConfirmed, setOf(rcp.commitTx.txid, rcp.claimMainOutputTx!!.tx.txid))
            // We use watch-spent on the outputs both parties can claim (htlc outputs).
            val watchSpent = actions.findWatches<WatchSpent>().map { OutPoint(it.txId, it.outputIndex.toLong()) }.toSet()
            assertEquals(watchSpent, listOf(2L, 3L, 4L, 5L).map { OutPoint(rcp.commitTx.txid, it) }.toSet())
            val txs = actions.findTxs().toSet()
            assertEquals(txs, setOf(rcp.claimMainOutputTx!!.tx) + rcp.claimHtlcTxs.values.filterNotNull().map { it.tx }.toSet())
        }

        // Commit tx has been confirmed.
        val rcp1 = rcp.update(rcp.commitTx)
        assertTrue(rcp1.irrevocablySpent.isNotEmpty())
        assertTrue(rcp1.isConfirmed())
        assertFalse(rcp1.isDone())

        // Main output has been confirmed.
        val rcp2 = rcp1.update(rcp.claimMainOutputTx!!.tx)
        assertTrue(rcp2.isConfirmed())
        assertFalse(rcp2.isDone())

        // One of our claim-htlc-success and claim-htlc-timeout has been confirmed.
        val rcp3 = rcp2.update(rcp.claimHtlcSuccessTxs().first().tx).update(rcp.claimHtlcTimeoutTxs().first().tx)
        assertTrue(rcp3.isConfirmed())
        assertFalse(rcp3.isDone())

        run {
            val actions = rcp3.doPublish(randomBytes32(), 3)
            // Our main output has been confirmed already.
            assertTrue(actions.findWatches<WatchConfirmed>().isEmpty())
            // We still watch the remaining unclaimed htlc outputs.
            val watchSpent = actions.findWatches<WatchSpent>().map { OutPoint(it.txId, it.outputIndex.toLong()) }.toSet()
            assertEquals(watchSpent, listOf(3L, 5L).map { OutPoint(rcp.commitTx.txid, it) }.toSet())
            val txs = actions.findTxs().toSet()
            assertEquals(txs, setOf(rcp.claimHtlcSuccessTxs().last().tx, rcp.claimHtlcTimeoutTxs().last().tx))
        }

        // Scenario 1: our remaining claim-htlc txs have been confirmed.
        run {
            val rcp4a = rcp3.update(rcp.claimHtlcSuccessTxs().last().tx)
            assertTrue(rcp4a.isConfirmed())
            assertFalse(rcp4a.isDone())

            val rcp4b = rcp4a.update(rcp.claimHtlcTimeoutTxs().last().tx)
            assertTrue(rcp4b.isConfirmed())
            assertTrue(rcp4b.isDone())
        }

        // Scenario 2: they claim the remaining htlc outputs.
        run {
            val htlcSuccess = rcp.claimHtlcSuccessTxs().last().tx.copy(txOut = listOf(TxOut(3_000.sat, ByteVector.empty), TxOut(2_500.sat, ByteVector.empty)))
            val rcp4a = rcp3.update(htlcSuccess)
            assertTrue(rcp4a.isConfirmed())
            assertFalse(rcp4a.isDone())

            val htlcTimeout = rcp.claimHtlcTimeoutTxs().last().tx.copy(txOut = listOf(TxOut(3_500.sat, ByteVector.empty), TxOut(3_100.sat, ByteVector.empty)))
            val rcp4b = rcp4a.update(htlcTimeout)
            assertTrue(rcp4b.isConfirmed())
            assertTrue(rcp4b.isDone())
        }
    }

    @Test
    fun `revoked commit published`() {
        val (_, _, rvk) = createClosingTransactions()
        assertFalse(rvk.isDone())

        run {
            val actions = rvk.doPublish(randomBytes32(), 6)
            // We use watch-confirmed on the outputs only us can claim.
            val watchConfirmed = actions.findWatches<WatchConfirmed>().map { it.txId }.toSet()
            assertEquals(watchConfirmed, setOf(rvk.commitTx.txid, rvk.claimMainOutputTx!!.tx.txid))
            // We use watch-spent on the outputs both parties can claim (htlc outputs and the remote main output).
            val watchSpent = actions.findWatches<WatchSpent>().map { OutPoint(it.txId, it.outputIndex.toLong()) }.toSet()
            assertEquals(watchSpent, listOf(1L, 2L, 3L, 4L, 5L).map { OutPoint(rvk.commitTx.txid, it) }.toSet())
            val txs = actions.findTxs().toSet()
            assertEquals(txs, setOf(rvk.claimMainOutputTx!!.tx, rvk.mainPenaltyTx!!.tx) + rvk.htlcPenaltyTxs.map { it.tx }.toSet())
        }

        // Commit tx has been confirmed.
        val rvk1 = rvk.update(rvk.commitTx)
        assertTrue(rvk1.irrevocablySpent.isNotEmpty())
        assertFalse(rvk1.isDone())

        // Main output has been confirmed.
        val rvk2 = rvk1.update(rvk.claimMainOutputTx!!.tx)
        assertFalse(rvk2.isDone())

        // Two of our htlc penalty txs have been confirmed.
        val rvk3 = rvk2.update(rvk.htlcPenaltyTxs[0].tx).update(rvk.htlcPenaltyTxs[1].tx)
        assertFalse(rvk3.isDone())

        run {
            val actions = rvk3.doPublish(randomBytes32(), 3)
            // Our main output has been confirmed already.
            assertTrue(actions.findWatches<WatchConfirmed>().isEmpty())
            // We still watch the remaining unclaimed outputs (htlc and remote main output).
            val watchSpent = actions.findWatches<WatchSpent>().map { OutPoint(it.txId, it.outputIndex.toLong()) }.toSet()
            assertEquals(watchSpent, listOf(1L, 4L, 5L).map { OutPoint(rvk.commitTx.txid, it) }.toSet())
            val txs = actions.findTxs().toSet()
            assertEquals(txs, setOf(rvk.mainPenaltyTx!!.tx, rvk.htlcPenaltyTxs[2].tx, rvk.htlcPenaltyTxs[3].tx))
        }

        // Scenario 1: the remaining penalty txs have been confirmed.
        run {
            val rvk4a = rvk3.update(rvk.htlcPenaltyTxs[2].tx).update(rvk.htlcPenaltyTxs[3].tx)
            assertFalse(rvk4a.isDone())

            val rvk4b = rvk4a.update(rvk.mainPenaltyTx!!.tx)
            assertTrue(rvk4b.isDone())
        }

        // Scenario 2: they claim the remaining outputs.
        run {
            val remoteMainOutput = rvk.mainPenaltyTx!!.tx.copy(txOut = listOf(TxOut(35_000.sat, ByteVector.empty)))
            val rvk4a = rvk3.update(remoteMainOutput)
            assertFalse(rvk4a.isDone())

            val htlcSuccess = rvk.htlcPenaltyTxs[2].tx.copy(txOut = listOf(TxOut(3_000.sat, ByteVector.empty), TxOut(2_500.sat, ByteVector.empty)))
            val htlcTimeout = rvk.htlcPenaltyTxs[3].tx.copy(txOut = listOf(TxOut(3_500.sat, ByteVector.empty), TxOut(3_100.sat, ByteVector.empty)))
            // When Bob claims these outputs, the channel should call Helpers.claimRevokedHtlcTxOutputs to punish them by claiming the output of their htlc tx.
            val rvk4b = rvk4a.update(htlcSuccess).update(htlcTimeout).copy(
                claimHtlcDelayedPenaltyTxs = listOf(
                    Transaction(2, listOf(TxIn(OutPoint(htlcSuccess, 0), 0)), listOf(TxOut(5_000.sat, ByteVector.empty)), 0),
                    Transaction(2, listOf(TxIn(OutPoint(htlcTimeout, 0), 0)), listOf(TxOut(6_000.sat, ByteVector.empty)), 0)
                ).map { ClaimHtlcDelayedOutputPenaltyTx(txInput(it), it) }
            )
            assertFalse(rvk4b.isDone())

            val actions = rvk4b.doPublish(randomBytes32(), 3)
            assertTrue(actions.findWatches<WatchConfirmed>().isEmpty())
            // NB: the channel, after calling Helpers.claimRevokedHtlcTxOutputs, will put a watch-spent on the htlc-txs.
            assertTrue(actions.findWatches<WatchSpent>().isEmpty())
            assertEquals(actions.findTxs().toSet(), rvk4b.claimHtlcDelayedPenaltyTxs.map { it.tx }.toSet())

            // We claim one of the remaining outputs, they claim the other.
            val rvk5a = rvk4b.update(rvk4b.claimHtlcDelayedPenaltyTxs[0].tx)
            assertFalse(rvk5a.isDone())
            val theyClaimHtlcTimeout = rvk4b.claimHtlcDelayedPenaltyTxs[1].tx.copy(txOut = listOf(TxOut(1_500.sat, ByteVector.empty), TxOut(2_500.sat, ByteVector.empty)))
            val rvk5b = rvk5a.update(theyClaimHtlcTimeout)
            assertTrue(rvk5b.isDone())
        }
    }

    companion object {
        private fun txInput(tx: Transaction): InputInfo {
            return InputInfo(tx.txIn.first().outPoint, TxOut(0.sat, ByteVector.empty), ByteVector.empty)
        }

        private fun createClosingTransactions(): Triple<LocalCommitPublished, RemoteCommitPublished, RevokedCommitPublished> {
            val commitTx = Transaction(
                2,
                listOf(TxIn(OutPoint(randomBytes32(), 0), 0)),
                listOf(
                    TxOut(50_000.sat, ByteVector.empty), // main output Alice
                    TxOut(40_000.sat, ByteVector.empty), // main output Bob
                    TxOut(4_000.sat, ByteVector.empty), // htlc received #1
                    TxOut(5_000.sat, ByteVector.empty), // htlc received #2
                    TxOut(6_000.sat, ByteVector.empty), // htlc sent #1
                    TxOut(7_000.sat, ByteVector.empty), // htlc sent #2
                ),
                0
            )
            val claimMainAlice = Transaction(2, listOf(TxIn(OutPoint(commitTx, 0), 144)), listOf(TxOut(49_500.sat, ByteVector.empty)), 0)
            val htlcSuccess1 = Transaction(2, listOf(TxIn(OutPoint(commitTx, 2), 1)), listOf(TxOut(3_500.sat, ByteVector.empty)), 0)
            val htlcSuccess2 = Transaction(2, listOf(TxIn(OutPoint(commitTx, 3), 1)), listOf(TxOut(4_500.sat, ByteVector.empty)), 0)
            val htlcTimeout1 = Transaction(2, listOf(TxIn(OutPoint(commitTx, 4), 1)), listOf(TxOut(5_500.sat, ByteVector.empty)), 0)
            val htlcTimeout2 = Transaction(2, listOf(TxIn(OutPoint(commitTx, 5), 1)), listOf(TxOut(6_500.sat, ByteVector.empty)), 0)

            val localCommit = run {
                val htlcTxs = mapOf(
                    htlcSuccess1.txIn.first().outPoint to HtlcSuccessTx(txInput(htlcSuccess1), htlcSuccess1, randomBytes32(), 0),
                    htlcSuccess2.txIn.first().outPoint to HtlcSuccessTx(txInput(htlcSuccess2), htlcSuccess2, randomBytes32(), 1),
                    htlcTimeout1.txIn.first().outPoint to HtlcTimeoutTx(txInput(htlcTimeout1), htlcTimeout1, 0),
                    htlcTimeout2.txIn.first().outPoint to HtlcTimeoutTx(txInput(htlcTimeout2), htlcTimeout2, 1),
                )
                val claimHtlcDelayedTxs = listOf(
                    Transaction(2, listOf(TxIn(OutPoint(htlcSuccess1, 0), 1)), listOf(TxOut(3_400.sat, ByteVector.empty)), 0),
                    Transaction(2, listOf(TxIn(OutPoint(htlcSuccess2, 0), 1)), listOf(TxOut(4_400.sat, ByteVector.empty)), 0),
                    Transaction(2, listOf(TxIn(OutPoint(htlcTimeout1, 0), 1)), listOf(TxOut(5_400.sat, ByteVector.empty)), 0),
                    Transaction(2, listOf(TxIn(OutPoint(htlcTimeout2, 0), 1)), listOf(TxOut(6_400.sat, ByteVector.empty)), 0),
                ).map { ClaimLocalDelayedOutputTx(txInput(it), it) }
                val claimMain = ClaimLocalDelayedOutputTx(txInput(claimMainAlice), claimMainAlice)
                LocalCommitPublished(commitTx, claimMain, htlcTxs, claimHtlcDelayedTxs)
            }

            val remoteCommit = run {
                val claimMain = ClaimRemoteCommitMainOutputTx.ClaimP2WPKHOutputTx(txInput(claimMainAlice), claimMainAlice)
                val claimHtlcTxs = mapOf(
                    htlcSuccess1.txIn.first().outPoint to ClaimHtlcSuccessTx(txInput(htlcSuccess1), htlcSuccess1, 0),
                    htlcSuccess2.txIn.first().outPoint to ClaimHtlcSuccessTx(txInput(htlcSuccess2), htlcSuccess2, 1),
                    htlcTimeout1.txIn.first().outPoint to ClaimHtlcTimeoutTx(txInput(htlcTimeout1), htlcTimeout1, 0),
                    htlcTimeout2.txIn.first().outPoint to ClaimHtlcTimeoutTx(txInput(htlcTimeout2), htlcTimeout2, 1),
                )
                RemoteCommitPublished(commitTx, claimMain, claimHtlcTxs)
            }

            val revokedCommit = run {
                val mainPenalty = run {
                    val tx = Transaction(2, listOf(TxIn(OutPoint(commitTx, 1), 0)), listOf(TxOut(39_500.sat, ByteVector.empty)), 0)
                    MainPenaltyTx(txInput(tx), tx)
                }
                val claimMain = ClaimRemoteCommitMainOutputTx.ClaimP2WPKHOutputTx(txInput(claimMainAlice), claimMainAlice)
                val htlcPenaltyTxs = listOf(htlcSuccess1, htlcSuccess2, htlcTimeout1, htlcTimeout2).map { HtlcPenaltyTx(txInput(it), it) }
                RevokedCommitPublished(commitTx, randomKey(), claimMain, mainPenalty, htlcPenaltyTxs)
            }

            return Triple(localCommit, remoteCommit, revokedCommit)
        }
    }

}
