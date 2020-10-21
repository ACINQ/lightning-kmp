package fr.acinq.eclair.channel.states

import fr.acinq.bitcoin.*
import fr.acinq.eclair.CltvExpiryDelta
import fr.acinq.eclair.Eclair.randomBytes32
import fr.acinq.eclair.TestConstants
import fr.acinq.eclair.blockchain.*
import fr.acinq.eclair.channel.*
import fr.acinq.eclair.channel.TestsHelper.addHtlc
import fr.acinq.eclair.channel.TestsHelper.crossSign
import fr.acinq.eclair.channel.TestsHelper.fulfillHtlc
import fr.acinq.eclair.channel.TestsHelper.localClose
import fr.acinq.eclair.channel.TestsHelper.makeCmdAdd
import fr.acinq.eclair.channel.TestsHelper.mutualClose
import fr.acinq.eclair.channel.TestsHelper.reachNormal
import fr.acinq.eclair.channel.TestsHelper.remoteClose
import fr.acinq.eclair.transactions.Scripts
import fr.acinq.eclair.transactions.Transactions
import fr.acinq.eclair.utils.UUID
import fr.acinq.eclair.utils.msat
import fr.acinq.eclair.utils.toMilliSatoshi
import fr.acinq.eclair.wire.*
import kotlin.test.*

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
        val (alice, _, _) = init()
        val (_, actions) = alice.process(ExecuteCommand(CMD_ADD_HTLC(1000000.msat, ByteVector32.Zeroes, CltvExpiryDelta(144).toCltvExpiry(alice.currentBlockHeight.toLong()), TestConstants.emptyOnionPacket, UUID.randomUUID())))
        assertTrue {
            actions.size == 1 &&
                    (actions.first() as HandleCommandFailed).error is AddHtlcFailed &&
                    ((actions.first() as HandleCommandFailed).error as AddHtlcFailed).t == ChannelUnavailable(alice.channelId)
        }
    }

    @Test
    fun `recv CMD_FULFILL_HTLC (unexisting htlc)`() {
        val (alice, _, _) = init()
        val (_, actions) = alice.process(ExecuteCommand(CMD_FULFILL_HTLC(1, ByteVector32.Zeroes)))
        assertTrue { actions.size == 1 && (actions.first() as HandleCommandFailed).error is UnknownHtlcId }
    }

    @Test
    fun `recv BITCOIN_FUNDING_SPENT (mutual close before converging)`() {
        val (alice0, bob0) = reachNormal()
        // alice initiates a closing
        val (alice1, aliceActions1) = alice0.process(ExecuteCommand(CMD_CLOSE(null)))
        val shutdown0 = aliceActions1.findOutgoingMessage<Shutdown>()
        val (bob1, bobActions1) = bob0.process(MessageReceived(shutdown0))
        val shutdown1 = bobActions1.findOutgoingMessage<Shutdown>()
        val (alice2, aliceActions2) = alice1.process(MessageReceived(shutdown1))

        // agreeing on a closing fee
        val closingSigned0 = aliceActions2.findOutgoingMessage<ClosingSigned>()
        val aliceCloseFee = closingSigned0.feeSatoshis
        val bob2 = (bob1 as Negotiating).updateFeerate(5000)
        val (bob3, bobActions3) = bob2.process(MessageReceived(closingSigned0))
        val closingSigned1 = bobActions3.findOutgoingMessage<ClosingSigned>()
        val bobCloseFee = closingSigned1.feeSatoshis
        val (alice3, _) = alice2.process(MessageReceived(closingSigned1))

        // they don't converge yet, but alice has a publishable commit tx now
        assertNotEquals(aliceCloseFee, bobCloseFee)
        val mutualCloseTx = (alice3 as Negotiating).bestUnpublishedClosingTx
        assertNotNull(mutualCloseTx)

        // let's make alice publish this closing tx
        val (alice4, aliceActions4) = alice3.process(MessageReceived(Error(ByteVector32.Zeroes, "")))
        assertTrue { alice4 is Closing }
        assertEquals(PublishTx(mutualCloseTx), aliceActions4.filterIsInstance<PublishTx>().first())
        assertEquals(mutualCloseTx, (alice4 as Closing).mutualClosePublished.last())

        // actual test starts here
        val (alice5, _) = alice4.process(WatchReceived(WatchEventSpent(ByteVector32.Zeroes, BITCOIN_FUNDING_SPENT, mutualCloseTx)))
        val (alice6, _) = alice5.process(WatchReceived(WatchEventConfirmed(ByteVector32.Zeroes, BITCOIN_TX_CONFIRMED(mutualCloseTx), 0,0, mutualCloseTx)))

        assertTrue { alice6 is Closed }
    }

    @Test
    fun `recv BITCOIN_TX_CONFIRMED (mutual close)`() {
        val (alice0, bob0, _) = init()
        val mutualCloseTx = alice0.mutualClosePublished.last()

        // actual test starts here
        val (alice1, _) = alice0.process(WatchReceived(WatchEventConfirmed(ByteVector32.Zeroes, BITCOIN_TX_CONFIRMED(mutualCloseTx), 0, 0, mutualCloseTx)))
        assertTrue { alice1 is Closed }
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
        val (alice0, bob0) = reachNormal()
        val (nodes, ra1, _) = addHtlc(50_000_000.msat, alice0, bob0)
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

        val (alice1, _) = aliceClosing.process(WatchReceived(WatchEventSpent(ByteVector32.Zeroes, BITCOIN_OUTPUT_SPENT, claimHtlcSuccessFromCommitTx)))
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
        val (alice2, _) = alice1.process(WatchReceived(WatchEventSpent(ByteVector32.Zeroes, BITCOIN_OUTPUT_SPENT, claimHtlcSuccessTx)))
        assertEquals(aliceClosing, alice2) // this was a no-op
    }

    @Test
    fun `recv BITCOIN_TX_CONFIRMED (local commit)`() {
        val (alice0, bob0) = reachNormal()
        // alice sends an htlc to bob
        val (nodes1, _, htlca1) = addHtlc(50_000_000.msat, alice0, bob0)
        val (alice1, bob1) = nodes1
        // alice sends an htlc below dust to bob
        val amountBelowDust = (alice1 as Normal).commitments.localParams.dustLimit.toMilliSatoshi() - 100.msat
        val (nodes2, _, htlca2) = addHtlc(amountBelowDust, alice1, bob1)
        val (alice2, bob2) = nodes2

        val (alice3, bob3) = crossSign(alice2, bob2)
        val (aliceClosing, localCommitPublished) = localClose(alice3)

        // actual test starts here
        assertNotNull(localCommitPublished.claimMainDelayedOutputTx)
        assertTrue(localCommitPublished.htlcSuccessTxs.isEmpty())
        assertEquals(1, localCommitPublished.htlcTimeoutTxs.size)
        assertEquals(1, localCommitPublished.claimHtlcDelayedTxs.size)

        val (alice4, _) = aliceClosing.process(WatchReceived(WatchEventConfirmed(
            ByteVector32.Zeroes,
            BITCOIN_TX_CONFIRMED(localCommitPublished.commitTx),
            42,
            0,
            localCommitPublished.commitTx
        )))
        val (alice5, _) = alice4.process(WatchReceived(WatchEventConfirmed(
            ByteVector32.Zeroes,
            BITCOIN_TX_CONFIRMED(localCommitPublished.claimMainDelayedOutputTx!!),
            200,
            0,
            localCommitPublished.claimMainDelayedOutputTx!!
        )))
        val (alice6, _) = alice5.process(WatchReceived(WatchEventConfirmed(
            ByteVector32.Zeroes,
            BITCOIN_TX_CONFIRMED(localCommitPublished.htlcTimeoutTxs.first()),
            201,
            0,
            localCommitPublished.htlcTimeoutTxs.first()
        )))

        assertNotNull((alice6 as Closing).localCommitPublished)
        assertEquals(
            setOfNotNull(
                localCommitPublished.commitTx.txid,
                localCommitPublished.claimMainDelayedOutputTx?.txid,
                localCommitPublished.htlcTimeoutTxs.first().txid
            ), alice6.localCommitPublished!!.irrevocablySpent.values.toSet())

        val (alice7, _) = alice6.process(WatchReceived(WatchEventConfirmed(
            ByteVector32.Zeroes,
            BITCOIN_TX_CONFIRMED(localCommitPublished.claimHtlcDelayedTxs.first()),
            202,
            0,
            localCommitPublished.claimHtlcDelayedTxs.first()
        )))

        assertTrue { alice7 is Closed }
    }

    @Test
    fun `recv BITCOIN_TX_CONFIRMED (local commit with multiple htlcs for the same payment)`() {
        val (alice0, bob0) = reachNormal()
        // alice sends an htlc to bob
        val (nodes1, ra1, htlca1) = addHtlc(30_000_000.msat, alice0, bob0)
        val (alice1, bob1) = nodes1
        // and more htlcs with the same payment_hash
        val (_, cmd2) = makeCmdAdd(25_000_000.msat, bob0.staticParams.nodeParams.nodeId, alice1.currentBlockHeight.toLong(), ra1)
        val (alice2, bob2, htlca2) = addHtlc(cmd2, alice1, bob1)
        val (_, cmd3) = makeCmdAdd(30_000_000.msat, bob0.staticParams.nodeParams.nodeId, alice2.currentBlockHeight.toLong(), ra1)
        val (alice3, bob3, htlca3) = addHtlc(cmd3, alice2, bob2)
        val amountBelowDust = (alice3 as Normal).commitments.localParams.dustLimit.toMilliSatoshi() - 100.msat
        val (_, dustCmd) = makeCmdAdd(amountBelowDust, bob0.staticParams.nodeParams.nodeId, alice3.currentBlockHeight.toLong(), ra1)
        val (alice4, bob4, dust) = addHtlc(dustCmd, alice3, bob3)
        val (_, cmd4) = makeCmdAdd(20_000_000.msat, bob0.staticParams.nodeParams.nodeId, alice4.currentBlockHeight.toLong() + 1, ra1)
        val (alice5, bob5, htlca4) = addHtlc(cmd4, alice4, bob4)
        val (alice6, bob6)= crossSign(alice5, bob5)
        val (aliceClosing, localCommitPublished) = localClose(alice6)

        // actual test starts here
        assertNotNull(localCommitPublished.claimMainDelayedOutputTx)
        assertTrue(localCommitPublished.htlcSuccessTxs.isEmpty())
        assertEquals(4, localCommitPublished.htlcTimeoutTxs.size)
        assertEquals(4, localCommitPublished.claimHtlcDelayedTxs.size)

        // if commit tx and htlc-timeout txs end up in the same block, we may receive the htlc-timeout confirmation before the commit tx confirmation

        val (alice7, _) = aliceClosing.process(WatchReceived(WatchEventConfirmed(
            ByteVector32.Zeroes,
            BITCOIN_TX_CONFIRMED(localCommitPublished.htlcTimeoutTxs.first()),
            42,
            0,
            localCommitPublished.htlcTimeoutTxs.first()
        )))
        val (alice8, _) = alice7.process(WatchReceived(WatchEventConfirmed(
            ByteVector32.Zeroes,
            BITCOIN_TX_CONFIRMED(localCommitPublished.commitTx),
            42,
            1,
            localCommitPublished.commitTx
        )))
        val (alice9, _) = alice8.process(WatchReceived(WatchEventConfirmed(
            ByteVector32.Zeroes,
            BITCOIN_TX_CONFIRMED(localCommitPublished.claimMainDelayedOutputTx!!),
            200,
            0,
            localCommitPublished.claimMainDelayedOutputTx!!
        )))
        val (alice10, _) = alice9.process(WatchReceived(WatchEventConfirmed(
            ByteVector32.Zeroes,
            BITCOIN_TX_CONFIRMED(localCommitPublished.htlcTimeoutTxs[1]),
            202,
            0,
            localCommitPublished.htlcTimeoutTxs[1]
        )))
        val (alice11, _) = alice10.process(WatchReceived(WatchEventConfirmed(
            ByteVector32.Zeroes,
            BITCOIN_TX_CONFIRMED(localCommitPublished.htlcTimeoutTxs[2]),
            202,
            1,
            localCommitPublished.htlcTimeoutTxs[2]
        )))
        val (alice12, _) = alice11.process(WatchReceived(WatchEventConfirmed(
            ByteVector32.Zeroes,
            BITCOIN_TX_CONFIRMED(localCommitPublished.htlcTimeoutTxs[3]),
            203,
            0,
            localCommitPublished.htlcTimeoutTxs[3]
        )))

        val (alice13, _) = alice12.process(WatchReceived(WatchEventConfirmed(
            ByteVector32.Zeroes,
            BITCOIN_TX_CONFIRMED(localCommitPublished.claimHtlcDelayedTxs.first()),
            203,
            0,
            localCommitPublished.claimHtlcDelayedTxs.first()
        )))
        val (alice14, _) = alice13.process(WatchReceived(WatchEventConfirmed(
            ByteVector32.Zeroes,
            BITCOIN_TX_CONFIRMED(localCommitPublished.claimHtlcDelayedTxs[1]),
            203,
            1,
            localCommitPublished.claimHtlcDelayedTxs[1]
        )))
        val (alice15, _) = alice14.process(WatchReceived(WatchEventConfirmed(
            ByteVector32.Zeroes,
            BITCOIN_TX_CONFIRMED(localCommitPublished.claimHtlcDelayedTxs[2]),
            203,
            2,
            localCommitPublished.claimHtlcDelayedTxs[2]
        )))
        val (alice16, _) = alice15.process(WatchReceived(WatchEventConfirmed(
            ByteVector32.Zeroes,
            BITCOIN_TX_CONFIRMED(localCommitPublished.claimHtlcDelayedTxs[3]),
            203,
            3,
            localCommitPublished.claimHtlcDelayedTxs[3]
        )))

        assertTrue { alice16 is Closed }
    }

    @Ignore
    fun `recv BITCOIN_TX_CONFIRMED (local commit with htlcs only signed by local)`() {
        TODO("implement this when channels can retrieve htlc infos")
    }

    @Ignore
    fun `recv BITCOIN_TX_CONFIRMED (remote commit with htlcs only signed by local in next remote commit)`() {
        TODO("implement this when channels can retrieve htlc infos")
    }

    @Test
    fun `recv BITCOIN_FUNDING_SPENT (remote commit)`() {
        val (alice, _, bobCommitTxes) = init(withPayments = true)
        // bob publishes his last current commit tx, the one it had when entering NEGOTIATING state
        val bobCommitTx = bobCommitTxes.last().commitTx.tx
        assertEquals(2, bobCommitTx.txOut.size)
        val (aliceClosing, remoteCommitPublished) = remoteClose(bobCommitTx, alice)
        assertNotNull(remoteCommitPublished.claimMainOutputTx)
        assertTrue(remoteCommitPublished.claimHtlcSuccessTxs.isEmpty())
        assertTrue(remoteCommitPublished.claimHtlcTimeoutTxs.isEmpty())
        assertEquals(alice, aliceClosing.copy(remoteCommitPublished = null))
    }

    @Test
    fun `recv BITCOIN_TX_CONFIRMED (remote commit)`() {
        val (alice0, bob0, bobCommitTxes) = init(withPayments = true)
        assertEquals(ChannelVersion.STANDARD, alice0.commitments.channelVersion)
        // bob publishes his last current commit tx, the one it had when entering NEGOTIATING state
        val bobCommitTx = bobCommitTxes.last().commitTx.tx
        assertEquals(2, bobCommitTx.txOut.size) // two main outputs
        val (aliceClosing, remoteCommitPublished) = remoteClose(bobCommitTx, alice0)

        // actual test starts here
        assertNotNull(remoteCommitPublished.claimMainOutputTx)
        assertTrue(remoteCommitPublished.claimHtlcSuccessTxs.isEmpty())
        assertTrue(remoteCommitPublished.claimHtlcTimeoutTxs.isEmpty())
        assertEquals(alice0, aliceClosing.copy(remoteCommitPublished = null))

        val (alice1, _) = aliceClosing.process(WatchReceived(WatchEventConfirmed(
            ByteVector32.Zeroes,
            BITCOIN_TX_CONFIRMED(bobCommitTx),
            0, 0, bobCommitTx)))
        val (alice2, _) = alice1.process(WatchReceived(WatchEventConfirmed(
            ByteVector32.Zeroes,
            BITCOIN_TX_CONFIRMED(remoteCommitPublished.claimMainOutputTx!!),
            0, 0, remoteCommitPublished.claimMainOutputTx!!
        )))

        assertTrue { alice2 is Closed }
    }

    @Test
    fun `recv BITCOIN_TX_CONFIRMED (remote commit with multiple htlcs for the same payment)`() {
        val (alice0, bob0) = reachNormal()
        // alice sends an htlc to bob
        val (nodes1, ra1, htlca1) = addHtlc(15000000.msat, alice0, bob0)
        val (alice1, bob1) = nodes1
        // and more htlcs with the same payment_hash
        val (_, cmd2) = makeCmdAdd(15000000.msat, bob0.staticParams.nodeParams.nodeId, alice1.currentBlockHeight.toLong(), ra1)
        val (alice2, bob2, htlca2) = addHtlc(cmd2, alice1, bob1)
        val (_, cmd3) = makeCmdAdd(20000000.msat, bob0.staticParams.nodeParams.nodeId, alice2.currentBlockHeight.toLong() - 1, ra1)
        val (alice3, bob3, htlca3) = addHtlc(cmd3, alice2, bob2)
        val (alice4, bob4)= crossSign(alice3, bob3)

        // Bob publishes the latest commit tx.
        val bobCommitTx = (bob4 as Normal).commitments.localCommit.publishableTxs.commitTx.tx
        assertEquals(5, bobCommitTx.txOut.size)
        val (aliceClosing, remoteCommitPublished) = remoteClose(bobCommitTx, alice4)
        assertNotNull(remoteCommitPublished.claimMainOutputTx)
        assertEquals(3, remoteCommitPublished.claimHtlcTimeoutTxs.size)

        val (alice5, _) = aliceClosing.process(WatchReceived(WatchEventConfirmed(
            ByteVector32.Zeroes,
            BITCOIN_TX_CONFIRMED(bobCommitTx),
            42, 0, bobCommitTx)))
        val (alice6, _) = alice5.process(WatchReceived(WatchEventConfirmed(
            ByteVector32.Zeroes,
            BITCOIN_TX_CONFIRMED(remoteCommitPublished.claimMainOutputTx!!),
            45, 0, remoteCommitPublished.claimMainOutputTx!!)))
        val (alice7, _) = alice6.process(WatchReceived(WatchEventConfirmed(
            ByteVector32.Zeroes,
            BITCOIN_TX_CONFIRMED(remoteCommitPublished.claimHtlcTimeoutTxs.first()),
            201, 0, remoteCommitPublished.claimHtlcTimeoutTxs.first())))
        val (alice8, _) = alice7.process(WatchReceived(WatchEventConfirmed(
            ByteVector32.Zeroes,
            BITCOIN_TX_CONFIRMED(remoteCommitPublished.claimHtlcTimeoutTxs[1]),
            202, 0, remoteCommitPublished.claimHtlcTimeoutTxs[1])))
        val (alice9, _) = alice8.process(WatchReceived(WatchEventConfirmed(
            ByteVector32.Zeroes,
            BITCOIN_TX_CONFIRMED(remoteCommitPublished.claimHtlcTimeoutTxs[2]),
            203, 1, remoteCommitPublished.claimHtlcTimeoutTxs[2])))

        assertTrue { alice9 is Closed }
    }

    @Test
    fun `recv BITCOIN_TX_CONFIRMED (remote commit) followed by CMD_FULFILL_HTLC`() {
        val (alice0, bob0) = reachNormal()
        // An HTLC Bob -> Alice is cross-signed that will be fulfilled later.
        val (nodes1, r1, htlc1) = addHtlc(110000000.msat, bob0, alice0)
        val (bob1, alice1) = nodes1
        val (bob2, alice2) = crossSign(bob1, alice1)
        // An HTLC Alice -> Bob is only signed by Alice: Bob has two spendable commit tx.
        val (nodes2, _, htlc2) = addHtlc(95000000.msat, alice2, bob2)
        val (alice3, bob3) = nodes2

        val (alice4, aliceActions4) = alice3.process(ExecuteCommand(CMD_SIGN))
        aliceActions4.hasMessage<CommitSig>() // We stop here: Alice sent her CommitSig, but doesn't hear back from Bob.

        // Now Bob publishes the first commit tx (force-close).
        val bobCommitTx = (bob3 as Normal).commitments.localCommit.publishableTxs.commitTx.tx
        assertEquals(3, bobCommitTx.txOut.size) // two main outputs + 1 HTLC

        val (aliceClosing, remoteCommitPublished) = remoteClose(bobCommitTx, alice4)
        assertNotNull(remoteCommitPublished.claimMainOutputTx)
        // we don't have the preimage to claim the htlc-success yet
        assertTrue { remoteCommitPublished.claimHtlcSuccessTxs.isEmpty() }
        assertTrue { remoteCommitPublished.claimHtlcTimeoutTxs.isEmpty() }

        // Alice receives the preimage for the first HTLC from downstream; she can now claim the corresponding HTLC output.
        val (alice5, aliceActions5) = aliceClosing.process(ExecuteCommand(CMD_FULFILL_HTLC(htlc1.id, r1, commit = true)))
        val publishTxes = aliceActions5.filterIsInstance<PublishTx>()
        assertEquals(PublishTx(remoteCommitPublished.claimMainOutputTx!!), publishTxes.first())

        val claimHtlcSuccessTx = (alice5 as Closing).remoteCommitPublished?.claimHtlcSuccessTxs?.first()
        assertNotNull(claimHtlcSuccessTx)
        Transaction.correctlySpends(claimHtlcSuccessTx, bobCommitTx, ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
        assertEquals(PublishTx(claimHtlcSuccessTx), publishTxes[1])

        // Alice resets watches on all relevant transactions.
        assertEquals(BITCOIN_TX_CONFIRMED(bobCommitTx), aliceActions5.watches<WatchConfirmed>()[0].event)
        assertEquals(BITCOIN_TX_CONFIRMED(remoteCommitPublished.claimMainOutputTx!!), aliceActions5.watches<WatchConfirmed>()[1].event)
        val watchHtlcSuccess = aliceActions5.findOutgoingWatch<WatchSpent>()
        assertEquals(BITCOIN_OUTPUT_SPENT, watchHtlcSuccess.event)
        assertEquals(bobCommitTx.txid, watchHtlcSuccess.txId)
        assertEquals(claimHtlcSuccessTx.txIn.first().outPoint.index, watchHtlcSuccess.outputIndex.toLong())

        val (alice6, _) = alice5.process(WatchReceived(WatchEventConfirmed(ByteVector32.Zeroes,
            BITCOIN_TX_CONFIRMED(bobCommitTx),0,0 , bobCommitTx)))
        val (alice7, _) = alice6.process(WatchReceived(WatchEventConfirmed(ByteVector32.Zeroes,
            BITCOIN_TX_CONFIRMED(remoteCommitPublished.claimMainOutputTx!!),0,0 , remoteCommitPublished.claimMainOutputTx!!)))
        val (alice8, _) = alice7.process(WatchReceived(WatchEventConfirmed(ByteVector32.Zeroes,
            BITCOIN_TX_CONFIRMED(claimHtlcSuccessTx),0,0 , claimHtlcSuccessTx)))

        assertTrue { alice8 is Closed }
    }

    @Test
    fun `recv BITCOIN_TX_CONFIRMED (next remote commit)`() {
        val (alice0, bob0) = reachNormal()
        // alice sends a first htlc to bob
        val (nodes1, ra1, _) = addHtlc(15_000_000.msat, alice0, bob0)
        val (alice1, bob1) = nodes1
        // alice sends more htlcs with the same payment_hash
        val (_, cmd2) = makeCmdAdd(20_000_000.msat, bob0.staticParams.nodeParams.nodeId, alice1.currentBlockHeight.toLong(), ra1)
        val (alice2, bob2, _) = addHtlc(cmd2, alice1, bob1)
        val (alice3, bob3) = crossSign(alice2, bob2)
        // The last one is only signed by Alice: Bob has two spendable commit tx.
        val (_, cmd3) = makeCmdAdd(20_000_000.msat, bob0.staticParams.nodeParams.nodeId, alice3.currentBlockHeight.toLong(), ra1)
        val (alice4, bob4, _) = addHtlc(cmd3, alice3, bob3)

        val (alice5, aliceActions5) = alice4.process(ExecuteCommand(CMD_SIGN))
        val commitSig = aliceActions5.findOutgoingMessage<CommitSig>()
        val (bob5, bobActions5) = bob4.process(MessageReceived(commitSig))
        bobActions5.hasMessage<RevokeAndAck>() // not forwarded to Alice (malicious Bob)
        val cmdSign = bobActions5.findProcessCommand<CMD_SIGN>()
        val (bob6, bobActions6) = bob5.process(ExecuteCommand(cmdSign))
        bobActions6.hasMessage<CommitSig>() // not forwarded to Alice (malicious Bob)

        // Bob publishes the next commit tx.
        val bobCommitTx = (bob6 as Normal).commitments.localCommit.publishableTxs.commitTx.tx
        assertEquals(5, bobCommitTx.txOut.size)
        val (aliceClosing, remoteCommitPublished) = remoteClose(bobCommitTx, alice5)
        assertNotNull(remoteCommitPublished.claimMainOutputTx)
        assertEquals(3, remoteCommitPublished.claimHtlcTimeoutTxs.size)

        val (alice6, _) = aliceClosing.process(WatchReceived(WatchEventConfirmed(ByteVector32.Zeroes, BITCOIN_TX_CONFIRMED(bobCommitTx), 42, 0, bobCommitTx)))
        val (alice7, _) = alice6.process(WatchReceived(WatchEventConfirmed(ByteVector32.Zeroes, BITCOIN_TX_CONFIRMED(remoteCommitPublished.claimMainOutputTx!!), 45, 0, remoteCommitPublished.claimMainOutputTx!!)))
        val (alice8, _) = alice7.process(WatchReceived(WatchEventConfirmed(ByteVector32.Zeroes, BITCOIN_TX_CONFIRMED(remoteCommitPublished.claimHtlcTimeoutTxs.first()), 201, 0, remoteCommitPublished.claimHtlcTimeoutTxs.first())))
        val (alice9, _) = alice8.process(WatchReceived(WatchEventConfirmed(ByteVector32.Zeroes, BITCOIN_TX_CONFIRMED(remoteCommitPublished.claimHtlcTimeoutTxs[1]), 202, 0, remoteCommitPublished.claimHtlcTimeoutTxs[1])))
        val (alice10, _) = alice9.process(WatchReceived(WatchEventConfirmed(ByteVector32.Zeroes, BITCOIN_TX_CONFIRMED(remoteCommitPublished.claimHtlcTimeoutTxs[2]), 203, 1, remoteCommitPublished.claimHtlcTimeoutTxs[2])))

        assertTrue { alice10 is Closed }
    }

    @Test
    fun `recv BITCOIN_TX_CONFIRMED (next remote commit) followed by CMD_FULFILL_HTLC`() {
        val (alice0, bob0) = reachNormal()
        // An HTLC Bob -> Alice is cross-signed that will be fulfilled later.
        val (nodes1, r1, htlc1) = addHtlc(110_000_000.msat, bob0, alice0)
        val (bob1, alice1) = nodes1
        val (bob2, alice2) = crossSign(bob1, alice1)
        // An HTLC Alice -> Bob is only signed by Alice: Bob has two spendable commit tx.
        val (nodes2, _, htlc2) = addHtlc(95_000_000.msat, alice2, bob2)
        val (alice3, bob3) = nodes2

        val (alice4, aliceActions4) = alice3.process(ExecuteCommand(CMD_SIGN))
        val commitSig = aliceActions4.findOutgoingMessage<CommitSig>()
        val (bob4, bobActions4) = bob3.process(MessageReceived(commitSig))
        bobActions4.hasMessage<RevokeAndAck>() // not forwarded to Alice (malicious Bob)
        val cmdSign = bobActions4.findProcessCommand<CMD_SIGN>()
        val (bob5, bobActions5) = bob4.process(ExecuteCommand(cmdSign))
        bobActions5.hasMessage<CommitSig>() // not forwarded to Alice (malicious Bob)

        // Now Bob publishes the next commit tx (force-close).
        val bobCommitTx = (bob5 as Normal).commitments.localCommit.publishableTxs.commitTx.tx
        assertEquals(4, bobCommitTx.txOut.size)  // two main outputs + 2 HTLCs

        val (aliceClosing, nextRemoteCommitPublished) = remoteClose(bobCommitTx, alice4)
        assertNotNull(nextRemoteCommitPublished.claimMainOutputTx)
        assertTrue(nextRemoteCommitPublished.claimHtlcSuccessTxs.isEmpty()) // we don't have the preimage to claim the htlc-success yet
        assertEquals(1, nextRemoteCommitPublished.claimHtlcTimeoutTxs.size)
        val claimHtlcTimeoutTx = nextRemoteCommitPublished.claimHtlcTimeoutTxs.first()

        // Alice receives the preimage for the first HTLC from downstream; she can now claim the corresponding HTLC output.
        val (alice5, aliceActions5) = aliceClosing.process(ExecuteCommand(CMD_FULFILL_HTLC(htlc1.id, r1, commit = true)))
        val publishTxes = aliceActions5.filterIsInstance<PublishTx>()
        assertEquals(PublishTx(nextRemoteCommitPublished.claimMainOutputTx!!), publishTxes.first())
        val claimHtlcSuccessTx = (alice5 as Closing).nextRemoteCommitPublished?.claimHtlcSuccessTxs?.first()
        assertNotNull(claimHtlcSuccessTx)
        Transaction.correctlySpends(claimHtlcSuccessTx, bobCommitTx, ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
        assertEquals(PublishTx(claimHtlcSuccessTx), publishTxes[1])
        assertEquals(PublishTx(claimHtlcTimeoutTx), publishTxes[2])

        /*

    assert(alice2blockchain.expectMsgType[WatchConfirmed].event === BITCOIN_TX_CONFIRMED(bobCommitTx))
    assert(alice2blockchain.expectMsgType[WatchConfirmed].event === BITCOIN_TX_CONFIRMED(closingState.claimMainOutputTx.get))
    val watchHtlcs = alice2blockchain.expectMsgType[WatchSpent] :: alice2blockchain.expectMsgType[WatchSpent] :: Nil
    watchHtlcs.foreach(ws => assert(ws.event === BITCOIN_OUTPUT_SPENT))
    watchHtlcs.foreach(ws => assert(ws.txId === bobCommitTx.txid))
    assert(watchHtlcs.map(_.outputIndex).toSet === (claimHtlcSuccessTx :: closingState.claimHtlcTimeoutTxs).map(_.txIn.head.outPoint.index).toSet)

    alice ! WatchEventConfirmed(BITCOIN_TX_CONFIRMED(bobCommitTx), 0, 0, bobCommitTx)
    alice ! WatchEventConfirmed(BITCOIN_TX_CONFIRMED(closingState.claimMainOutputTx.get), 0, 0, closingState.claimMainOutputTx.get)
    alice ! WatchEventConfirmed(BITCOIN_TX_CONFIRMED(claimHtlcSuccessTx), 0, 0, claimHtlcSuccessTx)
    alice ! WatchEventConfirmed(BITCOIN_TX_CONFIRMED(claimHtlcTimeoutTx), 0, 0, claimHtlcTimeoutTx)

    awaitCond(alice.stateName == CLOSED)
         */
    }

    @Test
    fun `recv BITCOIN_TX_CONFIRMED (future remote commit)`() {
        val (alice0, bob0) = reachNormal()
        // This HTLC will be fulfilled.
        val (nodes1, ra1, htlca1) = addHtlc(25_000_000.msat, alice0, bob0)
        val (alice1, bob1) = nodes1
        // These 2 HTLCs should timeout on-chain, but since alice lost data, she won't be able to claim them.
        val (nodes2, ra2, _) = addHtlc(15_000_000.msat, alice1, bob1)
        val (alice2, bob2) = nodes2
        val (_, cmd) = makeCmdAdd(15_000_000.msat, bob0.staticParams.nodeParams.nodeId, alice2.currentBlockHeight.toLong(), ra2)
        val (alice3, bob3, _) = addHtlc(cmd, alice2, bob2)
        val (alice4, bob4) = crossSign(alice3, bob3)
        val (alice5, bob5) = fulfillHtlc(htlca1.id, ra1, alice4, bob4)
        val (bob6, alice6) = crossSign(bob5, alice5)

        // we simulate a disconnection
        val (alice7, _) = alice6.process(Disconnected)
        assertTrue { alice7 is Offline }
        val (bob7, _) = bob6.process(Disconnected)
        assertTrue { bob7 is Offline }

        val localInit = Init(ByteVector(TestConstants.Alice.channelParams.features.toByteArray()))
        val remoteInit = Init(ByteVector(TestConstants.Bob.channelParams.features.toByteArray()))

        // then we manually replace alice's state with an older one and reconnect them
        val (alice8, aliceActions8) = Offline(alice0).process(Connected(localInit, remoteInit))
        assertTrue { alice8 is Syncing }
        val channelReestablishA = (aliceActions8[0] as SendMessage).message as ChannelReestablish
        val (bob8, bobActions8) = bob7.process(Connected(remoteInit, localInit))
        assertTrue { bob8 is Syncing }
        val channelReestablishB = (bobActions8[0] as SendMessage).message as ChannelReestablish

        // peers exchange channel_reestablish messages
        val (alice9, aliceActions9) = alice8.process(MessageReceived(channelReestablishB))
        val (bob9, _) = bob8.process(MessageReceived(channelReestablishA))
        assertNotEquals(bob0, bob3)

        // alice then realizes it has an old state...
        assertTrue { alice9 is WaitForRemotePublishFutureComitment }
        val error = aliceActions9.findOutgoingMessage<Error>()
        assertEquals(PleasePublishYourCommitment((alice9 as WaitForRemotePublishFutureComitment).channelId).message, error.toAscii())
        // ... and ask bob to publish its current commitment
        val (bob10, _) = bob9.process(MessageReceived(error))
        // bob is nice and publishes its commitment
        val bobCommitTx = (bob10 as Closing).commitments.localCommit.publishableTxs.commitTx.tx
        assertEquals(4, bobCommitTx.txOut.size) // two main outputs + 2 HTLCs

        val (alice10, aliceActions10) = alice9.process(WatchReceived(WatchEventSpent(ByteVector32.Zeroes, BITCOIN_FUNDING_SPENT, bobCommitTx)))
        // alice is able to claim its main output
        val claimMainTx = aliceActions10.filterIsInstance<PublishTx>().first().tx
        Transaction.correctlySpends(claimMainTx, bobCommitTx, ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
        assertEquals(bobCommitTx.txid, aliceActions10.watches<WatchConfirmed>()[0].txId)
        assertEquals(claimMainTx.txid, aliceActions10.watches<WatchConfirmed>()[1].txId)

        // actual test starts here
        val (alice11, _) = alice10.process(WatchReceived(WatchEventConfirmed(ByteVector32.Zeroes, BITCOIN_TX_CONFIRMED(bobCommitTx), 0, 0, bobCommitTx)))
        val (alice12, _) = alice11.process(WatchReceived(WatchEventConfirmed(ByteVector32.Zeroes, BITCOIN_TX_CONFIRMED(claimMainTx), 0, 0, claimMainTx)))

        assertTrue { alice12 is Closed }
    }

    @Test
    fun `recv BITCOIN_FUNDING_SPENT (one revoked tx)`() {
        val (alice, _, bobCommitTxes) = init(withPayments = true)

        // bob publishes one of his revoked txes
        val bobRevokedTx = bobCommitTxes.first().commitTx.tx
        assertEquals(3, bobRevokedTx.txOut.size)

        val (alice1, aliceActions1) = alice.process(WatchReceived(WatchEventSpent(ByteVector32.Zeroes, BITCOIN_FUNDING_SPENT, bobRevokedTx)))
        assertTrue { alice1 is Closing } ; alice1 as Closing
        assertEquals(1,  alice1.revokedCommitPublished.size)
        assertEquals(alice,  alice1.copy(revokedCommitPublished = listOf()))

        // alice publishes and watches the penalty tx
        val penalties = aliceActions1.filterIsInstance<PublishTx>().map { it.tx }
        val claimMain = penalties[0]
        val mainPenalty = penalties[1]
        // TODO need to implement business logic about HTLC penalties in [Helpers.claimRevokedRemoteCommitTxOutputs]
        //        val htlcPenalty = penalties[2]

        penalties.forEach { penaltyTx ->
            Transaction.correctlySpends(penaltyTx, bobRevokedTx, ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
        }

        // TODO need to implement business logic about HTLC penalties in [Helpers.claimRevokedRemoteCommitTxOutputs]
        //        assertEquals(bobRevokedTx.txOut.size, penalties.map { it.txIn.first().outPoint }.toSet().size)

        assertEquals(bobRevokedTx.txid, aliceActions1.watches<WatchConfirmed>()[0].txId)
        assertEquals(claimMain.txid, aliceActions1.watches<WatchConfirmed>()[1].txId)
        assertEquals(mainPenalty.txIn.first().outPoint.index, aliceActions1.watches<WatchSpent>()[0].outputIndex.toLong())
        // TODO need to implement business logic about HTLC penalties in [Helpers.claimRevokedRemoteCommitTxOutputs]
        //        assertEquals(htlcPenalty.txIn.first().outPoint.index, aliceActions1.watches<WatchSpent>()[1].outputIndex)
    }

    @Test
    fun `recv BITCOIN_FUNDING_SPENT (multiple revoked tx)`() {
        val (alice, _, bobCommitTxes) = init(withPayments = true)
        assertEquals(bobCommitTxes.size, bobCommitTxes.distinctBy { it.commitTx.tx.txid }.size) // all commit txs are distinct

        // bob publishes multiple revoked txes (last one isn't revoked)
        val (alice1, aliceActions1) = alice.process(WatchReceived(WatchEventSpent(ByteVector32.Zeroes, BITCOIN_FUNDING_SPENT, bobCommitTxes.first().commitTx.tx)))
        assertTrue { alice1 is Closing } ; alice1 as Closing

        // alice publishes and watches the penalty tx
        val penalties1 = aliceActions1.filterIsInstance<PublishTx>().map { it.tx }
        val claimMain1 = penalties1[0]
        val mainPenalty1 = penalties1[1]
        // TODO need to implement business logic about HTLC penalties in [Helpers.claimRevokedRemoteCommitTxOutputs]
        //        val htlcPenalty1 = penalties[2]

        penalties1.forEach { penaltyTx ->
            Transaction.correctlySpends(penaltyTx, bobCommitTxes.first().commitTx.tx, ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
        }

        assertEquals(bobCommitTxes.first().commitTx.tx.txid, aliceActions1.watches<WatchConfirmed>()[0].txId)
        assertEquals(claimMain1.txid, aliceActions1.watches<WatchConfirmed>()[1].txId)
        assertEquals(mainPenalty1.txIn.first().outPoint.index, aliceActions1.watches<WatchSpent>()[0].outputIndex.toLong())
        // TODO need to implement business logic about HTLC penalties in [Helpers.claimRevokedRemoteCommitTxOutputs]
        //        assertEquals(htlcPenalty1.txIn.first().outPoint.index, aliceActions1.watches<WatchSpent>()[1].outputIndex)

        val (alice2, aliceActions2) = alice1.process(WatchReceived(WatchEventSpent(ByteVector32.Zeroes, BITCOIN_FUNDING_SPENT, bobCommitTxes[1].commitTx.tx)))
        assertTrue { alice2 is Closing } ; alice2 as Closing
        // alice publishes and watches the penalty tx (no HTLC in that commitment)
        val penalties2 = aliceActions2.filterIsInstance<PublishTx>().map { it.tx }
        val claimMain2 = penalties2[0]
        val mainPenalty2 = penalties2[1]
        penalties2.forEach { penaltyTx ->
            Transaction.correctlySpends(penaltyTx, bobCommitTxes[1].commitTx.tx, ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
        }

        assertEquals(bobCommitTxes[1].commitTx.tx.txid, aliceActions2.watches<WatchConfirmed>()[0].txId)
        assertEquals(claimMain2.txid, aliceActions2.watches<WatchConfirmed>()[1].txId)
        assertEquals(mainPenalty2.txIn.first().outPoint.index, aliceActions2.watches<WatchSpent>()[0].outputIndex.toLong())

        val (alice3, aliceActions3) = alice2.process(WatchReceived(WatchEventSpent(ByteVector32.Zeroes, BITCOIN_FUNDING_SPENT, bobCommitTxes[2].commitTx.tx)))
        assertTrue { alice3 is Closing } ; alice3 as Closing
        // alice publishes and watches the penalty tx (no HTLC in that commitment)
        val penalties3 = aliceActions3.filterIsInstance<PublishTx>().map { it.tx }
        val claimMain3 = penalties3[0]
        val mainPenalty3 = penalties3[1]
        // TODO need to implement business logic about HTLC penalties in [Helpers.claimRevokedRemoteCommitTxOutputs]
        //        val htlcPenalty3 = penalties3[2]

        penalties3.forEach { penaltyTx ->
            Transaction.correctlySpends(penaltyTx, bobCommitTxes[2].commitTx.tx, ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
        }

        assertEquals(bobCommitTxes[2].commitTx.tx.txid, aliceActions3.watches<WatchConfirmed>()[0].txId)
        assertEquals(claimMain3.txid, aliceActions3.watches<WatchConfirmed>()[1].txId)
        assertEquals(mainPenalty3.txIn.first().outPoint.index, aliceActions3.watches<WatchSpent>()[0].outputIndex.toLong())
        // TODO need to implement business logic about HTLC penalties in [Helpers.claimRevokedRemoteCommitTxOutputs]
        //        assertEquals(htlcPenalty3.txIn.first().outPoint.index, aliceActions3.watches<WatchSpent>()[1].outputIndex)

        assertEquals(3, alice3.revokedCommitPublished.size)
    }

    @Ignore
    fun `recv BITCOIN_OUTPUT_SPENT (one revoked tx, counterparty published HtlcSuccess tx)`() {
        TODO("implement this when channels can retrieve htlc infos")
    }

    @Ignore
    fun `recv BITCOIN_TX_CONFIRMED (one revoked tx)`() {
        TODO("implement this when channels can retrieve htlc infos")
    }

    @Test
    fun `recv CMD_CLOSE`() {
        val(alice0, _, _) = init()
        val cmdClose = CMD_CLOSE(null)
        val (_, actions) = alice0.process(ExecuteCommand(cmdClose))
        val commandError = actions.filterIsInstance<HandleCommandFailed>().first()
        assertEquals(cmdClose, commandError.cmd)
        assertEquals(ClosingAlreadyInProgress(alice0.channelId), commandError.error)
    }

    companion object {
        fun init(withPayments: Boolean = false): Triple<Closing, Closing, List<PublishableTxs>> {
            val (aliceInit, bobInit) = reachNormal(ChannelVersion.STANDARD)

            var mutableAlice: Normal = aliceInit
            var mutableBob: Normal = bobInit

            val bobCommitTxes =
                if (!withPayments) {
                    listOf()
                } else {
                    listOf(100_000_000.msat, 200_000_000.msat, 300_000_000.msat).map { amount ->
                        val (nodes, r, htlc) = addHtlc(amount, payer = mutableAlice, payee = mutableBob)
                        mutableAlice = nodes.first as Normal
                        mutableBob = nodes.second as Normal

                        with(crossSign(mutableAlice, mutableBob)) {
                            mutableAlice = first as Normal
                            mutableBob = second as Normal
                        }

                        val bobCommitTx1 = mutableBob.commitments.localCommit.publishableTxs

                        with(fulfillHtlc(htlc.id, r, payer = mutableAlice, payee = mutableBob)) {
                            mutableAlice = first as Normal
                            mutableBob = second as Normal
                        }

                        with(crossSign(mutableBob, mutableAlice)) {
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
