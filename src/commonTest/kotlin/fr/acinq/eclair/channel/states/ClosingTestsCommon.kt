package fr.acinq.eclair.channel.states

import fr.acinq.bitcoin.*
import fr.acinq.eclair.CltvExpiryDelta
import fr.acinq.eclair.Eclair.randomBytes32
import fr.acinq.eclair.TestConstants
import fr.acinq.eclair.blockchain.BITCOIN_FUNDING_SPENT
import fr.acinq.eclair.blockchain.WatchEventSpent
import fr.acinq.eclair.channel.*
import fr.acinq.eclair.channel.TestsHelper.addHtlc
import fr.acinq.eclair.channel.TestsHelper.crossSign
import fr.acinq.eclair.channel.TestsHelper.fulfillHtlc
import fr.acinq.eclair.channel.TestsHelper.localClose
import fr.acinq.eclair.channel.TestsHelper.mutualClose
import fr.acinq.eclair.channel.TestsHelper.reachNormal
import fr.acinq.eclair.channel.TestsHelper.remotClose
import fr.acinq.eclair.transactions.Scripts
import fr.acinq.eclair.transactions.Transactions
import fr.acinq.eclair.utils.UUID
import fr.acinq.eclair.utils.msat
import fr.acinq.eclair.wire.ClosingSigned
import fr.acinq.eclair.wire.Shutdown
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ClosingTestsCommon {
    @Test
    fun `start fee negotiation from configured block target`() {
        val (alice, bob) = reachNormal()
        val (alice1, actions) = alice.process(ExecuteCommand(CMD_CLOSE(null)))
        val shutdown = actions.findOutgoingMessage<Shutdown>()
        val (_, actions1) = bob.process(MessageReceived(shutdown))
        val shutdown1 = actions1.findOutgoingMessage<Shutdown>()
        val (alice2, actions2) = alice1.process(MessageReceived(shutdown1))
        val closingSigned = actions2.findOutgoingMessage<ClosingSigned>()
        val expectedProposedFee = Helpers.Closing.firstClosingFee(
            (alice2 as Negotiating).commitments,
            alice2.localShutdown.scriptPubKey.toByteArray(),
            alice2.remoteShutdown.scriptPubKey.toByteArray(),
            alice2.currentOnchainFeerates.mutualCloseFeeratePerKw
        )
        assertEquals(closingSigned.feeSatoshis, expectedProposedFee)
    }

    @Test
    fun `recv CMD_ADD_HTLC`() {
        val (alice, _) = init()
        val (_, actions) = alice.process(ExecuteCommand(CMD_ADD_HTLC(1000000.msat, ByteVector32.Zeroes, CltvExpiryDelta(144).toCltvExpiry(alice.currentBlockHeight.toLong()), TestConstants.emptyOnionPacket, UUID.randomUUID())))
        assertTrue {
            actions.size == 1 &&
                    (actions.first() as HandleCommandFailed).error is AddHtlcFailed &&
                    ((actions.first() as HandleCommandFailed).error as AddHtlcFailed).t == ChannelUnavailable(alice.channelId)
        }
    }

    @Test
    fun `recv CMD_FULFILL_HTLC (unexisting htlc)`() {
        val (alice, _) = init()
        val (_, actions) = alice.process(ExecuteCommand(CMD_FULFILL_HTLC(1, ByteVector32.Zeroes)))
        assertTrue { actions.size == 1 && (actions.first() as HandleCommandFailed).error is UnknownHtlcId }
    }

    @Test
    fun `recv BITCOIN_FUNDING_SPENT (local commit)`() {
        val (aliceNormal, _) = reachNormal()
        // an error occurs and alice publishes her commit tx
        val aliceCommitTx = aliceNormal.commitments.localCommit.publishableTxs.commitTx.tx
        val (aliceClosing, _) = localClose(aliceNormal)

        // actual test starts here
        // we are notified afterwards from our watcher about the tx that we just published
        val (alice1, _) = aliceClosing.process(WatchReceived(WatchEventSpent(ByteVector32.Zeroes, BITCOIN_FUNDING_SPENT, aliceCommitTx)))
        assertEquals(aliceClosing, alice1)
    }

    @Test
    fun `recv BITCOIN_OUTPUT_SPENT`() {
        val (alice, bob) = reachNormal()
        val (nodes, ra1, htlca1) = addHtlc(50_000_000.msat, alice, bob)
        val (aliceInit, _) = crossSign(nodes.first, nodes.second)
        val (aliceClosing, _) = localClose(aliceInit as Normal)

        // scenario 1: bob claims the htlc output from the commit tx using its preimage
        val claimHtlcSuccessFromCommitTx = Transaction(
            version = 0,
            txIn = listOf(
                TxIn(outPoint = OutPoint(randomBytes32(), 0),
                    signatureScript = ByteVector.empty,
                    sequence = 0,
                    witness = Scripts.witnessClaimHtlcSuccessFromCommitTx(Transactions.PlaceHolderSig, ra1, ByteArray(130) { 33 }.byteVector()))
            ),
            txOut = emptyList(),
            lockTime = 0)

        val (alice1, actionsAlice1) = aliceClosing.process(WatchReceived(WatchEventSpent(ByteVector32.Zeroes, BITCOIN_FUNDING_SPENT, claimHtlcSuccessFromCommitTx)))
        assertEquals(aliceClosing, alice1)

        // scenario 2: bob claims the htlc output from his own commit tx using its preimage (let's assume both parties had published their commitment tx)
        val claimHtlcSuccessTx = Transaction(
            version = 0,
            txIn = listOf(TxIn(outPoint = OutPoint(randomBytes32(), 0),
                signatureScript = ByteVector.empty,
                sequence = 0,
                witness = Scripts.witnessHtlcSuccess(Transactions.PlaceHolderSig,
                    Transactions.PlaceHolderSig,
                    ra1,
                    ByteArray(130) { 33 }.byteVector()))),
            txOut = emptyList(),
            lockTime = 0
        )
        val (alice2, actions2) = alice1.process(WatchReceived(WatchEventSpent(ByteVector32.Zeroes, BITCOIN_FUNDING_SPENT, claimHtlcSuccessTx)))
        assertEquals(aliceClosing, alice2) // this was a no-op
    }

    @Test
    fun `recv BITCOIN_FUNDING_SPENT (remote commit)`() {
        val (alice, bob, bobCommitTxes) = init(withPayments = true)
        // bob publishes his last current commit tx, the one it had when entering NEGOTIATING state
        val bobCommitTx = bobCommitTxes.last().commitTx.tx
        assertEquals(2, bobCommitTx.txOut.size)
        val (aliceClosing, remoteCommitPublished) = remotClose(bobCommitTx, alice)
        assertNotNull(remoteCommitPublished.claimMainOutputTx)
        assertTrue(remoteCommitPublished.claimHtlcSuccessTxs.isEmpty())
        assertTrue(remoteCommitPublished.claimHtlcTimeoutTxs.isEmpty())
        assertEquals(alice, aliceClosing.copy(remoteCommitPublished = null))
    }

    @Test
    fun `recv BITCOIN_FUNDING_SPENT (one revoked tx)`() {
        val (alice, bob) = reachNormal()

        // bob publishes one of his revoked txes
//        val bobRevokedTx = bobCommitTxes.head.commitTx.tx
//        alice ! WatchEventSpent(BITCOIN_FUNDING_SPENT, bobRevokedTx)

    }
    @Test
    fun `recv BITCOIN_FUNDING_SPENT (multiple revoked tx)`() {
        TODO("not implemented yet!")
    }
    @Test
    fun `recv BITCOIN_OUTPUT_SPENT (one revoked tx, counterparty published HtlcSuccess tx)`() {
        TODO("not implemented yet!")
    }
    @Test
    fun `recv BITCOIN_TX_CONFIRMED (one revoked tx)`() {
        TODO("not implemented yet!")
    }

    companion object {
//        fun init(): Pair<Closing, Closing> {
//            val (alice, bob, closingSigned) = NegotiatingTestsCommon.init()
//            val (alice1, bob1) = NegotiatingTestsCommon.converge(alice, bob, closingSigned)!!
//            return Pair(alice1, bob1)
//        }

        fun init(withPayments: Boolean = false): Triple<Closing, Closing, List<PublishableTxs>> {
            val (aliceInit, bobInit) = reachNormal(ChannelVersion.STANDARD)


            var mutableAlice: Normal = aliceInit
            var mutableBob: Normal = bobInit
            fun updateAliceAndBob(){}
            val bobCommitTxes =
                if (!withPayments) {
                    listOf()
                } else {

                    listOf(100_000_000.msat, 200_000_000.msat, 300_000_000.msat).map { amount ->
                        val (nodes, r, htlc) = addHtlc(amount, mutableAlice, mutableBob)
                        with(crossSign(nodes.first, nodes.second)) {
                            mutableAlice = first as Normal
                            mutableBob = second as Normal
                        }

                        val bobCommitTx1 = mutableBob.commitments.localCommit.publishableTxs

                        with(fulfillHtlc(htlc.id, r, mutableAlice, mutableBob)) {
                            mutableBob = first as Normal
                            mutableAlice = second as Normal
                        }

                        with(crossSign(mutableAlice, mutableBob)) {
                            mutableBob = first as Normal
                            mutableAlice = second as Normal
                        }

                        val bobCommitTx2 = mutableBob.commitments.localCommit.publishableTxs
                        listOf(bobCommitTx1, bobCommitTx2)
                    }.flatten()
                }

            val (alice1, bob1, aliceCloseSig) = mutualClose(mutableAlice, mutableBob)
            val (alice2, bob2) = NegotiatingTestsCommon.converge(alice1, bob1, aliceCloseSig) ?: error("converge should not return null")

            return Triple(alice2, bob2, bobCommitTxes)
        }
    }
}
