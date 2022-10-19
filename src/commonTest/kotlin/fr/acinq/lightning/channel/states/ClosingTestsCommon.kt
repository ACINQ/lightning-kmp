package fr.acinq.lightning.channel.states

import fr.acinq.bitcoin.*
import fr.acinq.bitcoin.Bitcoin.computeP2PkhAddress
import fr.acinq.lightning.CltvExpiryDelta
import fr.acinq.lightning.Feature
import fr.acinq.lightning.Lightning
import fr.acinq.lightning.blockchain.*
import fr.acinq.lightning.blockchain.fee.FeeratePerKw
import fr.acinq.lightning.channel.*
import fr.acinq.lightning.channel.TestsHelper.addHtlc
import fr.acinq.lightning.channel.TestsHelper.claimHtlcSuccessTxs
import fr.acinq.lightning.channel.TestsHelper.claimHtlcTimeoutTxs
import fr.acinq.lightning.channel.TestsHelper.crossSign
import fr.acinq.lightning.channel.TestsHelper.failHtlc
import fr.acinq.lightning.channel.TestsHelper.fulfillHtlc
import fr.acinq.lightning.channel.TestsHelper.htlcSuccessTxs
import fr.acinq.lightning.channel.TestsHelper.htlcTimeoutTxs
import fr.acinq.lightning.channel.TestsHelper.localClose
import fr.acinq.lightning.channel.TestsHelper.makeCmdAdd
import fr.acinq.lightning.channel.TestsHelper.mutualCloseAlice
import fr.acinq.lightning.channel.TestsHelper.mutualCloseBob
import fr.acinq.lightning.channel.TestsHelper.processEx
import fr.acinq.lightning.channel.TestsHelper.reachNormal
import fr.acinq.lightning.channel.TestsHelper.remoteClose
import fr.acinq.lightning.db.ChannelClosingType
import fr.acinq.lightning.tests.TestConstants
import fr.acinq.lightning.tests.utils.LightningTestSuite
import fr.acinq.lightning.transactions.Scripts
import fr.acinq.lightning.transactions.Transactions
import fr.acinq.lightning.utils.*
import fr.acinq.lightning.wire.*
import kotlin.test.*

class ClosingTestsCommon : LightningTestSuite() {

    @Test
    fun `recv CMD_ADD_HTLC`() {
        val (alice, _, _) = initMutualClose()
        val (_, actions) = alice.processEx(
            ChannelCommand.ExecuteCommand(
                CMD_ADD_HTLC(
                    1000000.msat,
                    ByteVector32.Zeroes,
                    CltvExpiryDelta(144).toCltvExpiry(alice.currentBlockHeight.toLong()),
                    TestConstants.emptyOnionPacket,
                    UUID.randomUUID()
                )
            )
        )
        assertEquals(1, actions.size)
        assertTrue { (actions.first() as ChannelAction.ProcessCmdRes.AddFailed).error == ChannelUnavailable(alice.state.channelId) }
    }

    @Test
    fun `recv CMD_FULFILL_HTLC -- nonexistent htlc`() {
        val (alice, _, _) = initMutualClose()
        val (_, actions) = alice.processEx(ChannelCommand.ExecuteCommand(CMD_FULFILL_HTLC(1, ByteVector32.Zeroes)))
        assertTrue { actions.size == 1 && (actions.first() as ChannelAction.ProcessCmdRes.NotExecuted).t is UnknownHtlcId }
    }

    @Test
    fun `recv BITCOIN_FUNDING_SPENT -- mutual close before converging`() {
        val (alice0, bob0) = reachNormal()
        // alice initiates a closing with a low fee
        val (alice1, aliceActions1) = alice0.process(ChannelCommand.ExecuteCommand(CMD_CLOSE(null, ClosingFeerates(FeeratePerKw(500.sat), FeeratePerKw(250.sat), FeeratePerKw(1000.sat)))))
        val shutdown0 = aliceActions1.findOutgoingMessage<Shutdown>()
        val (bob1, bobActions1) = bob0.processEx(ChannelCommand.MessageReceived(shutdown0))
        assertIs<LNChannel<Negotiating>>(bob1)
        val shutdown1 = bobActions1.findOutgoingMessage<Shutdown>()
        val (alice2, aliceActions2) = alice1.process(ChannelCommand.MessageReceived(shutdown1))
        assertIs<LNChannel<Negotiating>>(alice2)
        val closingSigned0 = aliceActions2.findOutgoingMessage<ClosingSigned>()

        // they don't converge yet, but bob has a publishable commit tx now
        val (bob2, bobActions2) = bob1.processEx(ChannelCommand.MessageReceived(closingSigned0))
        assertIs<LNChannel<Negotiating>>(bob2)
        val mutualCloseTx = bob2.state.bestUnpublishedClosingTx
        assertNotNull(mutualCloseTx)
        val closingSigned1 = bobActions2.findOutgoingMessage<ClosingSigned>()
        assertNotEquals(closingSigned0.feeSatoshis, closingSigned1.feeSatoshis)

        // let's make bob publish this closing tx
        val (bob3, bobActions3) = bob2.processEx(ChannelCommand.MessageReceived(Error(ByteVector32.Zeroes, "")))
        assertIs<LNChannel<Closing>>(bob3)
        assertEquals(ChannelAction.Blockchain.PublishTx(mutualCloseTx.tx), bobActions3.filterIsInstance<ChannelAction.Blockchain.PublishTx>().first())
        assertEquals(mutualCloseTx, bob3.state.mutualClosePublished.last())
        bobActions3.has<ChannelAction.Storage.StoreChannelClosing>()

        // actual test starts here
        val (bob4, _) = bob3.processEx(ChannelCommand.WatchReceived(WatchEventSpent(ByteVector32.Zeroes, BITCOIN_FUNDING_SPENT, mutualCloseTx.tx)))
        val (bob5, bobActions5) = bob4.processEx(ChannelCommand.WatchReceived(WatchEventConfirmed(ByteVector32.Zeroes, BITCOIN_TX_CONFIRMED(mutualCloseTx.tx), 0, 0, mutualCloseTx.tx)))
        assertIs<LNChannel<Closed>>(bob5)
        val storeChannelClosed = bobActions5.filterIsInstance<ChannelAction.Storage.StoreChannelClosed>().firstOrNull()
        assertNotNull(storeChannelClosed)
        assertTrue(storeChannelClosed.closingTxs.all { it.closingType == ChannelClosingType.Mutual })
        assertEquals(listOf(mutualCloseTx.tx.txid), storeChannelClosed.closingTxs.map { it.txId })
    }

    @Test
    fun `recv BITCOIN_TX_CONFIRMED -- mutual close`() {
        val (alice0, _, _) = initMutualClose()
        val mutualCloseTx = alice0.state.mutualClosePublished.last()

        // actual test starts here
        val (alice1, actions1) = alice0.processEx(ChannelCommand.WatchReceived(WatchEventConfirmed(ByteVector32.Zeroes, BITCOIN_TX_CONFIRMED(mutualCloseTx.tx), 0, 0, mutualCloseTx.tx)))
        assertIs<LNChannel<Closed>>(alice1)
        val storeChannelClosed = actions1.filterIsInstance<ChannelAction.Storage.StoreChannelClosed>().firstOrNull()
        assertNotNull(storeChannelClosed)
        assertTrue(storeChannelClosed.closingTxs.all { it.closingType == ChannelClosingType.Mutual })
        assertEquals(listOf(mutualCloseTx.tx.txid), storeChannelClosed.closingTxs.map { it.txId })
    }

    @Test
    fun `recv BITCOIN_TX_CONFIRMED -- mutual close with external btc address`() {
        val pubKey = Lightning.randomKey().publicKey()
        val bobBtcAddr = computeP2PkhAddress(pubKey, TestConstants.Bob.nodeParams.chainHash)
        val bobFinalScript = Script.write(Script.pay2pkh(pubKey)).toByteVector()

        val (alice1, bob1) = reachNormal()
        val (_, bob2, aliceClosingSigned) = mutualCloseBob(alice1, bob1, scriptPubKey = bobFinalScript)

        val (bob3, bobActions3) = bob2.processEx(ChannelCommand.MessageReceived(aliceClosingSigned))
        assertIs<LNChannel<Closing>>(bob3)
        val bobClosingSigned = bobActions3.findOutgoingMessageOpt<ClosingSigned>()
        assertNotNull(bobClosingSigned)
        val storeChannelClosing = bobActions3.filterIsInstance<ChannelAction.Storage.StoreChannelClosing>().firstOrNull()
        assertNotNull(storeChannelClosing)
        assertFalse(storeChannelClosing.isSentToDefaultAddress)
        assertEquals(storeChannelClosing.closingAddress, bobBtcAddr)
    }

    @Test
    fun `recv BITCOIN_FUNDING_DEPTHOK`() {
        val (alice, bob, _) = WaitForFundingConfirmedTestsCommon.init(ChannelType.SupportedChannelType.AnchorOutputs, alicePushAmount = 0.msat)
        val fundingTx = alice.state.fundingTx.tx.buildUnsignedTx()
        run {
            val (aliceClosing, _) = localClose(alice)
            val (alice1, actions1) = aliceClosing.processEx(ChannelCommand.WatchReceived(WatchEventConfirmed(alice.state.channelId, BITCOIN_FUNDING_DEPTHOK, 561, 3, fundingTx)))
            assertEquals(alice1, aliceClosing)
            assertEquals(actions1.size, 2)
            actions1.has<ChannelAction.Storage.StoreState>()
            assertEquals(actions1.findWatch<WatchSpent>().txId, fundingTx.txid)
        }
        run {
            val (bobClosing, _) = localClose(bob)
            val (bob1, actions1) = bobClosing.processEx(ChannelCommand.WatchReceived(WatchEventConfirmed(bob.state.channelId, BITCOIN_FUNDING_DEPTHOK, 561, 3, fundingTx)))
            assertEquals(bob1, bobClosing)
            assertEquals(actions1.size, 2)
            actions1.has<ChannelAction.Storage.StoreState>()
            assertEquals(actions1.findWatch<WatchSpent>().txId, fundingTx.txid)
        }
    }

    @Test
    fun `recv BITCOIN_FUNDING_DEPTHOK -- previous funding tx`() {
        val (alice, bob, txSigsBob, walletAlice) = WaitForFundingConfirmedTestsCommon.init(ChannelType.SupportedChannelType.AnchorOutputs, alicePushAmount = 0.msat)
        val (alice1, bob1) = WaitForFundingConfirmedTestsCommon.rbf(alice, bob, txSigsBob, walletAlice)
        val fundingTxId = alice1.state.commitments.fundingTxId
        val previousFundingTx = alice1.state.previousFundingTxs.first().first.signedTx!!
        assertNotEquals(previousFundingTx.txid, fundingTxId)
        run {
            val (aliceClosing, localCommitPublished) = localClose(alice1)
            assertEquals(aliceClosing.state.commitments.fundingTxId, fundingTxId)
            assertEquals(aliceClosing.state.alternativeCommitments.size, 1)
            val (alice2, actions2) = aliceClosing.processEx(ChannelCommand.WatchReceived(WatchEventConfirmed(alice.state.channelId, BITCOIN_FUNDING_DEPTHOK, 561, 3, previousFundingTx)))
            assertIs<LNChannel<Closing>>(alice2)
            assertEquals(alice2.commitments.fundingTxId, previousFundingTx.txid)
            assertTrue(alice2.state.alternativeCommitments.isEmpty())
            actions2.has<ChannelAction.Storage.StoreState>()
            assertEquals(actions2.findWatch<WatchSpent>().txId, previousFundingTx.txid)
            assertEquals(actions2.findTxs().size, 2) // commit tx and claim main
            val localCommitPublished2 = alice2.state.localCommitPublished
            assertNotNull(localCommitPublished2)
            assertNotEquals(localCommitPublished.commitTx.txid, localCommitPublished2.commitTx.txid)
        }
        run {
            val (bobClosing, localCommitPublished) = localClose(bob1)
            assertEquals(bobClosing.state.commitments.fundingTxId, fundingTxId)
            assertEquals(bobClosing.state.alternativeCommitments.size, 1)
            val (bob2, actions2) = bobClosing.processEx(ChannelCommand.WatchReceived(WatchEventConfirmed(bob.state.channelId, BITCOIN_FUNDING_DEPTHOK, 561, 3, previousFundingTx)))
            assertIs<LNChannel<Closing>>(bob2)
            assertEquals(bob2.commitments.fundingTxId, previousFundingTx.txid)
            assertTrue(bob2.state.alternativeCommitments.isEmpty())
            actions2.has<ChannelAction.Storage.StoreState>()
            assertEquals(actions2.findWatch<WatchSpent>().txId, previousFundingTx.txid)
            assertEquals(actions2.findTxs().size, 2) // commit tx and claim main
            val localCommitPublished2 = bob2.state.localCommitPublished
            assertNotNull(localCommitPublished2)
            assertNotEquals(localCommitPublished.commitTx.txid, localCommitPublished2.commitTx.txid)
        }
    }

    @Test
    fun `recv BITCOIN_FUNDING_SPENT -- local commit`() {
        val (aliceNormal, _) = reachNormal()
        val (aliceClosing, localCommitPublished) = localClose(aliceNormal)

        // actual test starts here
        // we are notified afterwards from our watcher about the tx that we just published
        val (alice1, actions1) = aliceClosing.processEx(ChannelCommand.WatchReceived(WatchEventSpent(aliceNormal.state.channelId, BITCOIN_FUNDING_SPENT, localCommitPublished.commitTx)))
        assertEquals(aliceClosing, alice1)
        assertTrue(actions1.isEmpty())
    }

    @Test
    fun `recv BITCOIN_TX_CONFIRMED -- local commit`() {
        val (alice0, bob0) = reachNormal()
        val (aliceClosing, localCommitPublished, htlcs) = run {
            // alice sends an htlc to bob
            val (nodes1, _, htlc1) = addHtlc(50_000_000.msat, alice0, bob0)
            val (alice1, bob1) = nodes1
            // alice sends an htlc below dust to bob
            val amountBelowDust = alice0.state.commitments.localParams.dustLimit.toMilliSatoshi() - 100.msat
            val (nodes2, _, htlc2) = addHtlc(amountBelowDust, alice1, bob1)
            val (alice2, bob2) = nodes2
            val (alice3, _) = crossSign(alice2, bob2)
            val (aliceClosing, localCommitPublished) = localClose(alice3)
            Triple(aliceClosing, localCommitPublished, setOf(htlc1, htlc2))
        }

        // actual test starts here
        assertNotNull(localCommitPublished.claimMainDelayedOutputTx)
        assertTrue(localCommitPublished.htlcSuccessTxs().isEmpty())
        assertEquals(1, localCommitPublished.htlcTimeoutTxs().size)
        assertEquals(1, localCommitPublished.claimHtlcDelayedTxs.size)

        val watchConfirmed = listOf(
            WatchEventConfirmed(alice0.state.channelId, BITCOIN_TX_CONFIRMED(localCommitPublished.commitTx), 42, 0, localCommitPublished.commitTx),
            WatchEventConfirmed(alice0.state.channelId, BITCOIN_TX_CONFIRMED(localCommitPublished.claimMainDelayedOutputTx!!.tx), 200, 0, localCommitPublished.claimMainDelayedOutputTx!!.tx),
            WatchEventConfirmed(alice0.state.channelId, BITCOIN_TX_CONFIRMED(localCommitPublished.htlcTimeoutTxs().first().tx), 201, 0, localCommitPublished.htlcTimeoutTxs().first().tx),
            WatchEventConfirmed(alice0.state.channelId, BITCOIN_TX_CONFIRMED(localCommitPublished.claimHtlcDelayedTxs.first().tx), 202, 0, localCommitPublished.claimHtlcDelayedTxs.first().tx)
        )

        var alice = aliceClosing
        val addSettledActions = watchConfirmed.dropLast(1).flatMap {
            val (aliceNew, actions) = alice.processEx(ChannelCommand.WatchReceived(it))
            assertIs<LNChannel<Closing>>(aliceNew)
            assertTrue(actions.contains(ChannelAction.Storage.StoreState(aliceNew.state)))
            alice = aliceNew
            actions.filterIsInstance<ChannelAction.ProcessCmdRes>()
        }

        // We notify the payment handler that the htlcs have been failed.
        assertEquals(2, addSettledActions.size)
        val addSettledFail = addSettledActions.filterIsInstance<ChannelAction.ProcessCmdRes.AddSettledFail>()
        assertEquals(htlcs, addSettledFail.map { it.htlc }.toSet())
        assertTrue(addSettledFail.all { it.result is ChannelAction.HtlcResult.Fail.OnChainFail })

        val irrevocablySpent = setOf(localCommitPublished.commitTx, localCommitPublished.claimMainDelayedOutputTx!!.tx, localCommitPublished.htlcTimeoutTxs().first().tx)
        assertEquals(irrevocablySpent, alice.state.localCommitPublished!!.irrevocablySpent.values.toSet())

        val (aliceClosed, actions) = alice.processEx(ChannelCommand.WatchReceived(watchConfirmed.last()))
        assertIs<LNChannel<Closed>>(aliceClosed)
        assertEquals(
            listOf(ChannelAction.Storage.StoreState(aliceClosed.state)),
            actions.filterIsInstance<ChannelAction.Storage.StoreState>()
        )
        val storeChannelClosed = actions.filterIsInstance<ChannelAction.Storage.StoreChannelClosed>().firstOrNull()
        assertNotNull(storeChannelClosed)
        assertTrue(storeChannelClosed.closingTxs.all { it.closingType == ChannelClosingType.Local })
        assertEquals(
            expected = listOfNotNull(
                localCommitPublished.claimMainDelayedOutputTx,
                localCommitPublished.claimHtlcDelayedTxs.firstOrNull()
            ).map { it.tx.txid }.toSet(),
            actual = storeChannelClosed.closingTxs.map { it.txId }.toSet()
        )
    }

    @Test
    fun `recv BITCOIN_TX_CONFIRMED -- local commit with multiple htlcs for the same payment`() {
        val (alice0, bob0) = reachNormal()
        // alice sends an htlc to bob
        val (aliceClosing, localCommitPublished) = run {
            val (nodes1, preimage, _) = addHtlc(30_000_000.msat, alice0, bob0)
            val (alice1, bob1) = nodes1
            // and more htlcs with the same payment_hash
            val (_, cmd2) = makeCmdAdd(25_000_000.msat, bob0.staticParams.nodeParams.nodeId, alice1.currentBlockHeight.toLong(), preimage)
            val (alice2, bob2, _) = addHtlc(cmd2, alice1, bob1)
            val (_, cmd3) = makeCmdAdd(30_000_000.msat, bob0.staticParams.nodeParams.nodeId, alice2.currentBlockHeight.toLong(), preimage)
            val (alice3, bob3, _) = addHtlc(cmd3, alice2, bob2)
            val amountBelowDust = alice0.state.commitments.localParams.dustLimit.toMilliSatoshi() - 100.msat
            val (_, dustCmd) = makeCmdAdd(amountBelowDust, bob0.staticParams.nodeParams.nodeId, alice3.currentBlockHeight.toLong(), preimage)
            val (alice4, bob4, _) = addHtlc(dustCmd, alice3, bob3)
            val (_, cmd4) = makeCmdAdd(20_000_000.msat, bob0.staticParams.nodeParams.nodeId, alice4.currentBlockHeight.toLong() + 1, preimage)
            val (alice5, bob5, _) = addHtlc(cmd4, alice4, bob4)
            val (alice6, _) = crossSign(alice5, bob5)
            localClose(alice6)
        }

        // actual test starts here
        assertNotNull(localCommitPublished.claimMainDelayedOutputTx)
        assertTrue(localCommitPublished.htlcSuccessTxs().isEmpty())
        assertEquals(4, localCommitPublished.htlcTimeoutTxs().size)
        assertEquals(4, localCommitPublished.claimHtlcDelayedTxs.size)

        // if commit tx and htlc-timeout txs end up in the same block, we may receive the htlc-timeout confirmation before the commit tx confirmation
        val watchConfirmed = listOf(
            WatchEventConfirmed(alice0.state.channelId, BITCOIN_TX_CONFIRMED(localCommitPublished.htlcTimeoutTxs()[2].tx), 42, 0, localCommitPublished.htlcTimeoutTxs()[2].tx),
            WatchEventConfirmed(alice0.state.channelId, BITCOIN_TX_CONFIRMED(localCommitPublished.commitTx), 42, 1, localCommitPublished.commitTx),
            WatchEventConfirmed(alice0.state.channelId, BITCOIN_TX_CONFIRMED(localCommitPublished.claimMainDelayedOutputTx!!.tx), 200, 0, localCommitPublished.claimMainDelayedOutputTx!!.tx),
            WatchEventConfirmed(alice0.state.channelId, BITCOIN_TX_CONFIRMED(localCommitPublished.htlcTimeoutTxs()[1].tx), 202, 0, localCommitPublished.htlcTimeoutTxs()[1].tx),
            WatchEventConfirmed(alice0.state.channelId, BITCOIN_TX_CONFIRMED(localCommitPublished.claimHtlcDelayedTxs[2].tx), 203, 2, localCommitPublished.claimHtlcDelayedTxs[2].tx),
            WatchEventConfirmed(alice0.state.channelId, BITCOIN_TX_CONFIRMED(localCommitPublished.htlcTimeoutTxs()[0].tx), 202, 1, localCommitPublished.htlcTimeoutTxs()[0].tx),
            WatchEventConfirmed(alice0.state.channelId, BITCOIN_TX_CONFIRMED(localCommitPublished.claimHtlcDelayedTxs[0].tx), 203, 0, localCommitPublished.claimHtlcDelayedTxs[0].tx),
            WatchEventConfirmed(alice0.state.channelId, BITCOIN_TX_CONFIRMED(localCommitPublished.claimHtlcDelayedTxs[1].tx), 203, 1, localCommitPublished.claimHtlcDelayedTxs[1].tx),
            WatchEventConfirmed(alice0.state.channelId, BITCOIN_TX_CONFIRMED(localCommitPublished.htlcTimeoutTxs()[3].tx), 203, 0, localCommitPublished.htlcTimeoutTxs()[3].tx),
            WatchEventConfirmed(alice0.state.channelId, BITCOIN_TX_CONFIRMED(localCommitPublished.claimHtlcDelayedTxs[3].tx), 203, 3, localCommitPublished.claimHtlcDelayedTxs[3].tx)
        )
        confirmWatchedTxs(aliceClosing, watchConfirmed)
    }

    @Test
    fun `recv BITCOIN_TX_CONFIRMED -- local commit with htlcs only signed by local`() {
        val (alice0, bob0) = reachNormal()
        val aliceCommitTx = alice0.commitments.localCommit.publishableTxs.commitTx.tx
        val (aliceClosing, localCommitPublished, add) = run {
            // alice sends an htlc to bob
            val (nodes1, _, add) = addHtlc(50_000_000.msat, alice0, bob0)
            val alice1 = nodes1.first
            // alice signs it, but bob doesn't receive the signature
            val (alice2, actions2) = alice1.processEx(ChannelCommand.ExecuteCommand(CMD_SIGN))
            actions2.hasOutgoingMessage<CommitSig>()
            val (aliceClosing, localCommitPublished) = localClose(alice2)
            Triple(aliceClosing, localCommitPublished, add)
        }

        assertEquals(aliceCommitTx, localCommitPublished.commitTx)
        assertTrue(localCommitPublished.htlcTimeoutTxs().isEmpty())
        assertTrue(localCommitPublished.htlcSuccessTxs().isEmpty())

        val (alice1, actions1) = aliceClosing.processEx(ChannelCommand.WatchReceived(WatchEventConfirmed(alice0.channelId, BITCOIN_TX_CONFIRMED(aliceCommitTx), 42, 1, aliceCommitTx)))
        assertIs<LNChannel<Closing>>(alice1)
        // when the commit tx is confirmed, alice knows that the htlc she sent right before the unilateral close will never reach the chain, so she fails it
        assertEquals(2, actions1.size)
        assertTrue(actions1.contains(ChannelAction.Storage.StoreState(alice1.state)))
        val addFailed = actions1.filterIsInstance<ChannelAction.ProcessCmdRes.AddSettledFail>().firstOrNull()
        assertNotNull(addFailed)
        assertEquals(add, addFailed.htlc)
        assertTrue(addFailed.result is ChannelAction.HtlcResult.Fail.OnChainFail)
    }

    @Test
    fun `recv BITCOIN_TX_CONFIRMED -- local commit -- followed by CMD_FULFILL_HTLC`() {
        val (alice0, bob0) = reachNormal()
        val (aliceClosing, localCommitPublished, fulfill) = run {
            // An HTLC Bob -> Alice is cross-signed that will be fulfilled later.
            val (nodes1, preimage, htlc) = addHtlc(110_000_000.msat, bob0, alice0)
            val (bob1, alice1) = nodes1
            // An HTLC Alice -> Bob is cross-signed and will timeout later.
            val (nodes2, _, _) = addHtlc(95_000_000.msat, alice1, bob1)
            val (alice2, bob2) = nodes2
            val (alice3, _) = crossSign(alice2, bob2)
            val (aliceClosing, localCommitPublished) = localClose(alice3)
            Triple(aliceClosing, localCommitPublished, CMD_FULFILL_HTLC(htlc.id, preimage, commit = true))
        }

        assertNotNull(localCommitPublished.claimMainDelayedOutputTx)
        // we don't have the preimage to claim the htlc-success yet
        assertTrue(localCommitPublished.htlcSuccessTxs().isEmpty())
        assertEquals(1, localCommitPublished.htlcTimeoutTxs().size)
        assertEquals(1, localCommitPublished.claimHtlcDelayedTxs.size)

        // Alice receives the preimage for the first HTLC from the payment handler; she can now claim the corresponding HTLC output.
        val (aliceFulfill, actionsFulfill) = aliceClosing.processEx(ChannelCommand.ExecuteCommand(fulfill))
        assertIs<LNChannel<Closing>>(aliceFulfill)
        assertEquals(1, aliceFulfill.state.localCommitPublished!!.htlcSuccessTxs().size)
        assertEquals(1, aliceFulfill.state.localCommitPublished!!.htlcTimeoutTxs().size)
        assertEquals(2, aliceFulfill.state.localCommitPublished!!.claimHtlcDelayedTxs.size)
        val htlcSuccess = aliceFulfill.state.localCommitPublished!!.htlcSuccessTxs().first()
        actionsFulfill.hasTx(htlcSuccess.tx)
        assertTrue(actionsFulfill.findWatches<WatchSpent>().map { Pair(it.txId, it.outputIndex.toLong()) }.contains(Pair(localCommitPublished.commitTx.txid, htlcSuccess.input.outPoint.index)))
        Transaction.correctlySpends(htlcSuccess.tx, localCommitPublished.commitTx, ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)

        val watchConfirmed = listOf(
            WatchEventConfirmed(alice0.channelId, BITCOIN_TX_CONFIRMED(localCommitPublished.commitTx), 42, 1, localCommitPublished.commitTx),
            WatchEventConfirmed(alice0.channelId, BITCOIN_TX_CONFIRMED(localCommitPublished.htlcTimeoutTxs()[0].tx), 210, 0, localCommitPublished.htlcTimeoutTxs()[0].tx),
            WatchEventConfirmed(alice0.channelId, BITCOIN_TX_CONFIRMED(htlcSuccess.tx), 210, 1, htlcSuccess.tx),
            WatchEventConfirmed(alice0.channelId, BITCOIN_TX_CONFIRMED(aliceFulfill.state.localCommitPublished!!.claimHtlcDelayedTxs[0].tx), 215, 1, aliceFulfill.state.localCommitPublished!!.claimHtlcDelayedTxs[0].tx),
            WatchEventConfirmed(alice0.channelId, BITCOIN_TX_CONFIRMED(aliceFulfill.state.localCommitPublished!!.claimHtlcDelayedTxs[1].tx), 215, 0, aliceFulfill.state.localCommitPublished!!.claimHtlcDelayedTxs[1].tx),
            WatchEventConfirmed(alice0.channelId, BITCOIN_TX_CONFIRMED(localCommitPublished.claimMainDelayedOutputTx!!.tx), 250, 0, localCommitPublished.claimMainDelayedOutputTx!!.tx)
        )
        confirmWatchedTxs(aliceFulfill, watchConfirmed)
    }

    @Test
    fun `recv BITCOIN_TX_CONFIRMED -- local commit --  followed by UpdateFailHtlc not acked by remote`() {
        val (alice0, bob0) = reachNormal()
        val (aliceClosing, localCommitPublished, htlc) = run {
            // An HTLC Alice -> Bob is cross-signed that will be failed later.
            val (nodes1, _, htlc) = addHtlc(25_000_000.msat, alice0, bob0)
            val (alice1, bob1) = nodes1
            val (alice2, bob2) = crossSign(alice1, bob1)
            val (alice3, bob3) = failHtlc(htlc.id, alice2, bob2)
            val (bob4, bobActions4) = bob3.processEx(ChannelCommand.ExecuteCommand(CMD_SIGN))
            val sigBob = bobActions4.hasOutgoingMessage<CommitSig>()
            val (alice4, aliceActions4) = alice3.processEx(ChannelCommand.MessageReceived(sigBob))
            val revAlice = aliceActions4.hasOutgoingMessage<RevokeAndAck>()
            aliceActions4.hasCommand<CMD_SIGN>()
            val (alice5, aliceActions5) = alice4.processEx(ChannelCommand.ExecuteCommand(CMD_SIGN))
            val sigAlice = aliceActions5.hasOutgoingMessage<CommitSig>()
            val (bob5, _) = bob4.processEx(ChannelCommand.MessageReceived(revAlice))
            val (_, bobActions6) = bob5.processEx(ChannelCommand.MessageReceived(sigAlice))
            bobActions6.hasOutgoingMessage<RevokeAndAck>()
            // alice closes before receiving Bob's revocation
            val (aliceClosing, localCommitPublished) = localClose(alice5)
            Triple(aliceClosing, localCommitPublished, htlc)
        }

        assertNotNull(localCommitPublished.claimMainDelayedOutputTx)
        assertTrue(localCommitPublished.htlcSuccessTxs().isEmpty())
        assertTrue(localCommitPublished.htlcTimeoutTxs().isEmpty())
        assertTrue(localCommitPublished.claimHtlcDelayedTxs.isEmpty())

        val (alice1, actions1) = aliceClosing.processEx(ChannelCommand.WatchReceived(WatchEventConfirmed(alice0.channelId, BITCOIN_TX_CONFIRMED(localCommitPublished.commitTx), 42, 1, localCommitPublished.commitTx)))
        assertIs<LNChannel<Closing>>(alice1)
        // when the commit tx is confirmed, alice knows that the htlc will never reach the chain
        assertEquals(2, actions1.size)
        assertTrue(actions1.contains(ChannelAction.Storage.StoreState(alice1.state)))
        val addFailed = actions1.filterIsInstance<ChannelAction.ProcessCmdRes.AddSettledFail>().firstOrNull()
        assertNotNull(addFailed)
        assertEquals(htlc, addFailed.htlc)
        assertTrue(addFailed.result is ChannelAction.HtlcResult.Fail.OnChainFail)
    }

    @Test
    fun `recv BITCOIN_OUTPUT_SPENT -- local commit`() {
        val (alice0, bob0) = reachNormal()
        val (aliceClosing, localCommitPublished, preimage) = run {
            val (nodes1, preimage, _) = addHtlc(20_000_000.msat, alice0, bob0)
            val (alice1, bob1) = nodes1
            val (nodes2, _, _) = addHtlc(15_000_000.msat, alice1, bob1)
            val (alice2, bob2) = nodes2
            val (nodes3, ra1, addBob1) = addHtlc(10_000_000.msat, bob2, alice2)
            val (bob3, alice3) = nodes3
            val (nodes4, ra2, addBob2) = addHtlc(12_000_000.msat, bob3, alice3)
            val (bob4, alice4) = nodes4
            val (alice5, _) = crossSign(alice4, bob4)
            // alice is ready to claim incoming htlcs
            val (alice6, _) = alice5.processEx(ChannelCommand.ExecuteCommand(CMD_FULFILL_HTLC(addBob1.id, ra1, commit = false)))
            val (alice7, _) = alice6.processEx(ChannelCommand.ExecuteCommand(CMD_FULFILL_HTLC(addBob2.id, ra2, commit = false)))
            val (alice8, localCommitPublished) = localClose(alice7)
            Triple(alice8, localCommitPublished, preimage)
        }

        assertEquals(2, localCommitPublished.htlcTimeoutTxs().size)
        assertEquals(2, localCommitPublished.htlcSuccessTxs().size)
        assertEquals(4, localCommitPublished.claimHtlcDelayedTxs.size)

        // Bob tries to claim 2 htlc outputs.
        val bobClaimSuccessTx = Transaction(
            version = 2,
            txIn = listOf(TxIn(localCommitPublished.htlcTimeoutTxs()[0].input.outPoint, ByteVector.empty, 0, Scripts.witnessClaimHtlcSuccessFromCommitTx(Transactions.PlaceHolderSig, preimage, ByteArray(130) { 33 }.byteVector()))),
            txOut = emptyList(),
            lockTime = 0
        )
        val bobClaimTimeoutTx = Transaction(
            version = 2,
            txIn = listOf(TxIn(localCommitPublished.htlcSuccessTxs()[0].input.outPoint, ByteVector.empty, 0, Scripts.witnessClaimHtlcTimeoutFromCommitTx(Transactions.PlaceHolderSig, ByteVector.empty))),
            txOut = emptyList(),
            lockTime = 0
        )

        val (alice1, actions1) = aliceClosing.processEx(ChannelCommand.WatchReceived(WatchEventSpent(alice0.channelId, BITCOIN_OUTPUT_SPENT, bobClaimSuccessTx)))
        assertEquals(aliceClosing, alice1)
        assertEquals(3, actions1.size)
        assertTrue(actions1.contains(ChannelAction.Storage.StoreState(aliceClosing.state)))
        assertEquals(WatchConfirmed(alice0.channelId, bobClaimSuccessTx, 3, BITCOIN_TX_CONFIRMED(bobClaimSuccessTx)), actions1.findWatch())
        // alice extracts Bob's preimage from his claim-htlc-success tx.
        val addSettled = actions1.filterIsInstance<ChannelAction.ProcessCmdRes.AddSettledFulfill>().first()
        assertEquals(ChannelAction.HtlcResult.Fulfill.OnChainFulfill(preimage), addSettled.result)

        val (alice2, actions2) = aliceClosing.processEx(ChannelCommand.WatchReceived(WatchEventSpent(alice0.channelId, BITCOIN_OUTPUT_SPENT, bobClaimTimeoutTx)))
        assertEquals(aliceClosing, alice2)
        assertEquals(2, actions2.size)
        assertTrue(actions2.contains(ChannelAction.Storage.StoreState(aliceClosing.state)))
        assertEquals(WatchConfirmed(alice0.channelId, bobClaimTimeoutTx, 3, BITCOIN_TX_CONFIRMED(bobClaimTimeoutTx)), actions2.findWatch())

        val claimHtlcSuccessDelayed = localCommitPublished.claimHtlcDelayedTxs.find { it.input.outPoint.txid == localCommitPublished.htlcSuccessTxs()[1].tx.txid }!!
        val claimHtlcTimeoutDelayed = localCommitPublished.claimHtlcDelayedTxs.find { it.input.outPoint.txid == localCommitPublished.htlcTimeoutTxs()[1].tx.txid }!!
        val watchConfirmed = listOf(
            WatchEventConfirmed(alice0.channelId, BITCOIN_TX_CONFIRMED(bobClaimSuccessTx), 42, 0, bobClaimSuccessTx),
            WatchEventConfirmed(alice0.channelId, BITCOIN_TX_CONFIRMED(localCommitPublished.commitTx), 42, 1, localCommitPublished.commitTx),
            WatchEventConfirmed(alice0.channelId, BITCOIN_TX_CONFIRMED(localCommitPublished.claimMainDelayedOutputTx!!.tx), 200, 0, localCommitPublished.claimMainDelayedOutputTx!!.tx),
            WatchEventConfirmed(alice0.channelId, BITCOIN_TX_CONFIRMED(localCommitPublished.htlcSuccessTxs()[1].tx), 202, 0, localCommitPublished.htlcSuccessTxs()[1].tx),
            WatchEventConfirmed(alice0.channelId, BITCOIN_TX_CONFIRMED(bobClaimTimeoutTx), 202, 0, bobClaimTimeoutTx),
            WatchEventConfirmed(alice0.channelId, BITCOIN_TX_CONFIRMED(localCommitPublished.htlcTimeoutTxs()[1].tx), 202, 0, localCommitPublished.htlcTimeoutTxs()[1].tx),
            WatchEventConfirmed(alice0.channelId, BITCOIN_TX_CONFIRMED(claimHtlcSuccessDelayed.tx), 203, 0, claimHtlcSuccessDelayed.tx),
            WatchEventConfirmed(alice0.channelId, BITCOIN_TX_CONFIRMED(claimHtlcTimeoutDelayed.tx), 203, 0, claimHtlcTimeoutDelayed.tx)
        )
        confirmWatchedTxs(aliceClosing, watchConfirmed)
    }

    @Test
    fun `recv ChannelEvent Restore -- local commit`() {
        val (alice0, bob0) = reachNormal()
        val (aliceClosing, localCommitPublished) = run {
            // alice sends an htlc to bob
            val (nodes1, _, _) = addHtlc(50_000_000.msat, alice0, bob0)
            val (alice1, bob1) = nodes1
            val (alice2, _) = crossSign(alice1, bob1)
            val (aliceClosing, localCommitPublished) = localClose(alice2)
            Pair(aliceClosing, localCommitPublished)
        }

        assertNotNull(localCommitPublished.claimMainDelayedOutputTx)
        assertTrue(localCommitPublished.htlcSuccessTxs().isEmpty())
        assertEquals(1, localCommitPublished.htlcTimeoutTxs().size)
        assertEquals(1, localCommitPublished.claimHtlcDelayedTxs.size)

        // Simulate a wallet restart
        val initState = LNChannel(aliceClosing.ctx, WaitForInit)
        val (alice1, actions1) = initState.processEx(ChannelCommand.Restore(aliceClosing.state))
        assertIs<LNChannel<Closing>>(alice1)
        assertEquals(aliceClosing, alice1)
        actions1.doesNotHave<ChannelAction.Storage.StoreChannelClosing>()

        // We should republish closing transactions
        val txs = listOf(
            localCommitPublished.commitTx,
            localCommitPublished.claimMainDelayedOutputTx!!.tx,
            localCommitPublished.htlcTimeoutTxs().first().tx,
            localCommitPublished.claimHtlcDelayedTxs.first().tx,
        )
        assertEquals(actions1.findTxs(), txs)
        val watchConfirmed = listOf(
            localCommitPublished.commitTx.txid,
            localCommitPublished.claimMainDelayedOutputTx!!.tx.txid,
            localCommitPublished.claimHtlcDelayedTxs.first().tx.txid,
        )
        assertEquals(actions1.findWatches<WatchConfirmed>().map { it.txId }, watchConfirmed)
        val watchSpent = listOf(
            localCommitPublished.commitTx.txIn.first().outPoint,
            localCommitPublished.htlcTimeoutTxs().first().input.outPoint,
        )
        assertEquals(actions1.findWatches<WatchSpent>().map { OutPoint(it.txId.reversed(), it.outputIndex.toLong()) }, watchSpent)
    }

    @Test
    fun `recv BITCOIN_FUNDING_SPENT -- remote commit`() {
        val (alice, _, bobCommitTxs) = initMutualClose(withPayments = true)
        // bob publishes his last current commit tx, the one it had when entering NEGOTIATING state
        val bobCommitTx = bobCommitTxs.last().commitTx.tx
        assertEquals(4, bobCommitTx.txOut.size) // main outputs and anchors
        val (aliceClosing, remoteCommitPublished) = remoteClose(bobCommitTx, alice)
        assertNotNull(remoteCommitPublished.claimMainOutputTx)
        assertTrue(remoteCommitPublished.claimHtlcSuccessTxs().isEmpty())
        assertTrue(remoteCommitPublished.claimHtlcTimeoutTxs().isEmpty())
        assertEquals(alice.state, aliceClosing.state.copy(remoteCommitPublished = null))
    }

    @Test
    fun `recv BITCOIN_TX_CONFIRMED -- remote commit`() {
        val (alice0, bob0) = reachNormal()
        val (aliceClosing, remoteCommitPublished, htlcs) = run {
            // alice sends an htlc to bob
            val (nodes1, _, htlc1) = addHtlc(50_000_000.msat, alice0, bob0)
            val (alice1, bob1) = nodes1
            // alice sends an htlc below dust to bob
            val amountBelowDust = alice0.commitments.localParams.dustLimit.toMilliSatoshi() - 100.msat
            val (nodes2, _, htlc2) = addHtlc(amountBelowDust, alice1, bob1)
            val (alice2, bob2) = nodes2
            val (alice3, bob3) = crossSign(alice2, bob2)
            val (aliceClosing, remoteCommitPublished) = remoteClose(bob3.commitments.localCommit.publishableTxs.commitTx.tx, alice3)
            Triple(aliceClosing, remoteCommitPublished, listOf(htlc1, htlc2))
        }

        // actual test starts here
        assertNotNull(remoteCommitPublished.claimMainOutputTx)
        assertTrue(remoteCommitPublished.claimHtlcSuccessTxs().isEmpty())
        assertEquals(1, remoteCommitPublished.claimHtlcTimeoutTxs().size)

        val watchConfirmed = listOf(
            WatchEventConfirmed(alice0.channelId, BITCOIN_TX_CONFIRMED(remoteCommitPublished.commitTx), 42, 0, remoteCommitPublished.commitTx),
            WatchEventConfirmed(alice0.channelId, BITCOIN_TX_CONFIRMED(remoteCommitPublished.claimMainOutputTx!!.tx), 43, 0, remoteCommitPublished.claimMainOutputTx!!.tx),
            WatchEventConfirmed(alice0.channelId, BITCOIN_TX_CONFIRMED(remoteCommitPublished.claimHtlcTimeoutTxs().first().tx), 201, 0, remoteCommitPublished.claimHtlcTimeoutTxs().first().tx),
        )

        var alice = aliceClosing
        val addSettledActions = watchConfirmed.dropLast(1).flatMap {
            val (aliceNew, actions) = alice.processEx(ChannelCommand.WatchReceived(it))
            assertIs<LNChannel<Closing>>(aliceNew)
            assertTrue(actions.contains(ChannelAction.Storage.StoreState(aliceNew.state)))
            alice = aliceNew
            actions.filterIsInstance<ChannelAction.ProcessCmdRes>()
        }

        // We notify the payment handler that the dust htlc has been failed.
        assertEquals(1, addSettledActions.size)
        val dustHtlcFail = addSettledActions.filterIsInstance<ChannelAction.ProcessCmdRes.AddSettledFail>().first()
        assertEquals(htlcs[1], dustHtlcFail.htlc)
        assertTrue(dustHtlcFail.result is ChannelAction.HtlcResult.Fail.OnChainFail)

        val irrevocablySpent = setOf(remoteCommitPublished.commitTx, remoteCommitPublished.claimMainOutputTx!!.tx)
        assertEquals(irrevocablySpent, alice.state.remoteCommitPublished!!.irrevocablySpent.values.toSet())

        val (aliceClosed, actions) = alice.processEx(ChannelCommand.WatchReceived(watchConfirmed.last()))
        assertIs<LNChannel<Closed>>(aliceClosed)
        assertTrue(actions.contains(ChannelAction.Storage.StoreState(aliceClosed.state)))
        val storeChannelClosed = actions.filterIsInstance<ChannelAction.Storage.StoreChannelClosed>().firstOrNull()
        assertNotNull(storeChannelClosed)
        assertTrue(storeChannelClosed.closingTxs.all { it.closingType == ChannelClosingType.Remote })
        assertEquals(
            expected = listOfNotNull(remoteCommitPublished.claimMainOutputTx, remoteCommitPublished.claimHtlcTimeoutTxs().firstOrNull()).map { it.tx.txid }.toSet(),
            actual = storeChannelClosed.closingTxs.map { it.txId }.toSet()
        )
        // We notify the payment handler that the non-dust htlc has been failed.
        val htlcFail = actions.filterIsInstance<ChannelAction.ProcessCmdRes.AddSettledFail>().first()
        assertEquals(htlcs[0], htlcFail.htlc)
        assertTrue(htlcFail.result is ChannelAction.HtlcResult.Fail.OnChainFail)
        assertEquals(3, actions.size)
    }

    @Test
    fun `recv BITCOIN_TX_CONFIRMED -- remote commit with multiple htlcs for the same payment`() {
        val (alice0, bob0) = reachNormal()
        // alice sends an htlc to bob
        val (aliceClosing, remoteCommitPublished) = run {
            val (nodes1, preimage, _) = addHtlc(30_000_000.msat, alice0, bob0)
            val (alice1, bob1) = nodes1
            // and more htlcs with the same payment_hash
            val (_, cmd2) = makeCmdAdd(25_000_000.msat, bob0.staticParams.nodeParams.nodeId, alice1.currentBlockHeight.toLong(), preimage)
            val (alice2, bob2, _) = addHtlc(cmd2, alice1, bob1)
            val (_, cmd3) = makeCmdAdd(20_000_000.msat, bob0.staticParams.nodeParams.nodeId, alice2.currentBlockHeight.toLong() - 1, preimage)
            val (alice3, bob3, _) = addHtlc(cmd3, alice2, bob2)
            val (alice4, bob4) = crossSign(alice3, bob3)
            remoteClose(bob4.commitments.localCommit.publishableTxs.commitTx.tx, alice4)
        }

        // actual test starts here
        assertNotNull(remoteCommitPublished.claimMainOutputTx)
        assertEquals(3, remoteCommitPublished.claimHtlcTimeoutTxs().size)

        // if commit tx and claim-htlc-timeout txs end up in the same block, we may receive them in any order
        val watchConfirmed = listOf(
            WatchEventConfirmed(alice0.channelId, BITCOIN_TX_CONFIRMED(remoteCommitPublished.claimHtlcTimeoutTxs()[1].tx), 42, 0, remoteCommitPublished.claimHtlcTimeoutTxs()[1].tx),
            WatchEventConfirmed(alice0.channelId, BITCOIN_TX_CONFIRMED(remoteCommitPublished.commitTx), 42, 1, remoteCommitPublished.commitTx),
            WatchEventConfirmed(alice0.channelId, BITCOIN_TX_CONFIRMED(remoteCommitPublished.claimHtlcTimeoutTxs()[2].tx), 201, 0, remoteCommitPublished.claimHtlcTimeoutTxs()[2].tx),
            WatchEventConfirmed(alice0.channelId, BITCOIN_TX_CONFIRMED(remoteCommitPublished.claimMainOutputTx!!.tx), 200, 0, remoteCommitPublished.claimMainOutputTx!!.tx),
            WatchEventConfirmed(alice0.channelId, BITCOIN_TX_CONFIRMED(remoteCommitPublished.claimHtlcTimeoutTxs()[0].tx), 204, 0, remoteCommitPublished.claimHtlcTimeoutTxs()[0].tx)
        )
        confirmWatchedTxs(aliceClosing, watchConfirmed)
    }

    @Test
    fun `recv BITCOIN_TX_CONFIRMED -- remote commit with htlcs only signed by local in next remote commit`() {
        val (alice0, bob0) = reachNormal()
        val bobCommitTx = bob0.commitments.localCommit.publishableTxs.commitTx.tx
        val (aliceClosing, remoteCommitPublished, add) = run {
            // alice sends an htlc to bob
            val (nodes1, _, add) = addHtlc(50_000_000.msat, alice0, bob0)
            val alice1 = nodes1.first
            // alice signs it, but bob doesn't receive the signature
            val (alice2, actions2) = alice1.processEx(ChannelCommand.ExecuteCommand(CMD_SIGN))
            actions2.hasOutgoingMessage<CommitSig>()
            val (aliceClosing, remoteCommitPublished) = remoteClose(bobCommitTx, alice2)
            Triple(aliceClosing, remoteCommitPublished, add)
        }

        assertTrue(remoteCommitPublished.claimHtlcTimeoutTxs().isEmpty())

        val (alice1, actions1) = aliceClosing.processEx(ChannelCommand.WatchReceived(WatchEventConfirmed(alice0.channelId, BITCOIN_TX_CONFIRMED(bobCommitTx), 42, 1, bobCommitTx)))
        assertIs<LNChannel<Closing>>(alice1)
        // when the commit tx is confirmed, alice knows that the htlc she sent right before the unilateral close will never reach the chain, so she fails it
        assertEquals(2, actions1.size)
        assertTrue(actions1.contains(ChannelAction.Storage.StoreState(alice1.state)))
        val addFailed = actions1.filterIsInstance<ChannelAction.ProcessCmdRes.AddSettledFail>().firstOrNull()
        assertNotNull(addFailed)
        assertEquals(add, addFailed.htlc)
        assertTrue(addFailed.result is ChannelAction.HtlcResult.Fail.OnChainFail)
    }

    @Test
    fun `recv BITCOIN_TX_CONFIRMED -- remote commit --  followed by CMD_FULFILL_HTLC`() {
        val (alice0, bob0) = reachNormal()
        val (aliceClosing, remoteCommitPublished, fulfill) = run {
            // An HTLC Bob -> Alice is cross-signed that will be fulfilled later.
            val (nodes1, preimage, htlc) = addHtlc(110_000_000.msat, bob0, alice0)
            val (bob1, alice1) = nodes1
            // An HTLC Alice -> Bob is cross-signed and will timeout later.
            val (nodes2, _, _) = addHtlc(95_000_000.msat, alice1, bob1)
            val (alice2, bob2) = nodes2
            val (alice3, bob3) = crossSign(alice2, bob2)
            // Now Bob publishes his commit tx (force-close).
            val bobCommitTx = bob3.state.commitments.localCommit.publishableTxs.commitTx.tx
            assertEquals(6, bobCommitTx.txOut.size) // two main outputs + 2 anchors + 2 HTLCs
            val (aliceClosing, remoteCommitPublished) = remoteClose(bobCommitTx, alice3)
            Triple(aliceClosing, remoteCommitPublished, CMD_FULFILL_HTLC(htlc.id, preimage, commit = true))
        }

        assertNotNull(remoteCommitPublished.claimMainOutputTx)
        // we don't have the preimage to claim the htlc-success yet
        assertTrue(remoteCommitPublished.claimHtlcSuccessTxs().isEmpty())
        assertEquals(1, remoteCommitPublished.claimHtlcTimeoutTxs().size)

        // Alice receives the preimage for the first HTLC from the payment handler; she can now claim the corresponding HTLC output.
        val (aliceFulfill, actionsFulfill) = aliceClosing.processEx(ChannelCommand.ExecuteCommand(fulfill))
        assertIs<LNChannel<Closing>>(aliceFulfill)
        assertEquals(1, aliceFulfill.state.remoteCommitPublished!!.claimHtlcSuccessTxs().size)
        assertEquals(1, aliceFulfill.state.remoteCommitPublished!!.claimHtlcTimeoutTxs().size)
        val claimHtlcSuccess = aliceFulfill.state.remoteCommitPublished!!.claimHtlcSuccessTxs().first()
        actionsFulfill.hasTx(claimHtlcSuccess.tx)
        assertTrue(actionsFulfill.findWatches<WatchSpent>().map { Pair(it.txId, it.outputIndex.toLong()) }.contains(Pair(remoteCommitPublished.commitTx.txid, claimHtlcSuccess.input.outPoint.index)))
        Transaction.correctlySpends(claimHtlcSuccess.tx, remoteCommitPublished.commitTx, ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)

        val watchConfirmed = listOf(
            WatchEventConfirmed(alice0.channelId, BITCOIN_TX_CONFIRMED(remoteCommitPublished.commitTx), 42, 1, remoteCommitPublished.commitTx),
            WatchEventConfirmed(alice0.channelId, BITCOIN_TX_CONFIRMED(remoteCommitPublished.claimHtlcTimeoutTxs()[0].tx), 210, 0, remoteCommitPublished.claimHtlcTimeoutTxs()[0].tx),
            WatchEventConfirmed(alice0.channelId, BITCOIN_TX_CONFIRMED(claimHtlcSuccess.tx), 210, 0, claimHtlcSuccess.tx),
            WatchEventConfirmed(alice0.channelId, BITCOIN_TX_CONFIRMED(remoteCommitPublished.claimMainOutputTx!!.tx), 250, 0, remoteCommitPublished.claimMainOutputTx!!.tx)
        )
        confirmWatchedTxs(aliceFulfill, watchConfirmed)
    }

    @Test
    fun `recv BITCOIN_OUTPUT_SPENT -- remote commit`() {
        val (alice0, bob0) = reachNormal()
        val (aliceClosing, remoteCommitPublished, preimage) = run {
            val (nodes1, rb1, addAlice1) = addHtlc(20_000_000.msat, alice0, bob0)
            val (alice1, bob1) = nodes1
            val (nodes2, rb2, addAlice2) = addHtlc(15_000_000.msat, alice1, bob1)
            val (alice2, bob2) = nodes2
            val (nodes3, ra1, addBob1) = addHtlc(10_000_000.msat, bob2, alice2)
            val (bob3, alice3) = nodes3
            val (nodes4, ra2, addBob2) = addHtlc(12_000_000.msat, bob3, alice3)
            val (bob4, alice4) = nodes4
            val (alice5, bob5) = crossSign(alice4, bob4)
            // alice is ready to claim incoming htlcs
            val (alice6, _) = alice5.processEx(ChannelCommand.ExecuteCommand(CMD_FULFILL_HTLC(addBob1.id, ra1, commit = false)))
            val (alice7, _) = alice6.processEx(ChannelCommand.ExecuteCommand(CMD_FULFILL_HTLC(addBob2.id, ra2, commit = false)))
            // bob is ready to claim incoming htlcs
            val (bob6, _) = bob5.processEx(ChannelCommand.ExecuteCommand(CMD_FULFILL_HTLC(addAlice1.id, rb1, commit = false)))
            val (bob7, _) = bob6.processEx(ChannelCommand.ExecuteCommand(CMD_FULFILL_HTLC(addAlice2.id, rb2, commit = false)))
            assertIs<LNChannel<Normal>>(bob7)
            val bobCommitTx = bob7.state.commitments.localCommit.publishableTxs.commitTx.tx
            assertEquals(8, bobCommitTx.txOut.size) // 2 main outputs, 2 anchors and 4 htlcs

            // alice publishes her commitment
            val (alice8, localCommitPublished) = localClose(alice7)
            // bob also publishes his commitment, and wins the race to confirm
            val (alice9, remoteCommitPublished) = remoteClose(bobCommitTx, alice8.copy(state = alice8.state.copy(localCommitPublished = null)))
            assertEquals(2, localCommitPublished.htlcTimeoutTxs().size)
            assertEquals(2, localCommitPublished.htlcSuccessTxs().size)
            assertEquals(4, localCommitPublished.claimHtlcDelayedTxs.size)
            assertEquals(2, remoteCommitPublished.claimHtlcSuccessTxs().size)
            assertEquals(2, remoteCommitPublished.claimHtlcTimeoutTxs().size)

            Triple(alice9, remoteCommitPublished, rb1)
        }

        assertNotNull(aliceClosing.state.remoteCommitPublished)
        assertNotNull(remoteCommitPublished.claimMainOutputTx)

        // Bob claims 2 htlc outputs, alice will claim the other 2.
        val bobHtlcSuccessTx = Transaction(
            version = 2,
            txIn = listOf(TxIn(remoteCommitPublished.claimHtlcTimeoutTxs()[0].input.outPoint, ByteVector.empty, 0, Scripts.witnessHtlcSuccess(Transactions.PlaceHolderSig, Transactions.PlaceHolderSig, preimage, ByteVector.empty))),
            txOut = emptyList(),
            lockTime = 0
        )
        val bobHtlcTimeoutTx = Transaction(
            version = 2,
            txIn = listOf(TxIn(remoteCommitPublished.claimHtlcSuccessTxs()[0].input.outPoint, ByteVector.empty, 0, Scripts.witnessHtlcTimeout(Transactions.PlaceHolderSig, Transactions.PlaceHolderSig, ByteVector.empty))),
            txOut = emptyList(),
            lockTime = 0
        )

        val (alice1, actions1) = aliceClosing.processEx(ChannelCommand.WatchReceived(WatchEventSpent(alice0.channelId, BITCOIN_OUTPUT_SPENT, bobHtlcSuccessTx)))
        assertEquals(aliceClosing, alice1)
        assertEquals(3, actions1.size)
        assertTrue(actions1.contains(ChannelAction.Storage.StoreState(aliceClosing.state)))
        assertEquals(WatchConfirmed(alice0.channelId, bobHtlcSuccessTx, 3, BITCOIN_TX_CONFIRMED(bobHtlcSuccessTx)), actions1.findWatch())
        // alice extracts Bob's preimage from his htlc-success tx.
        val addSettled = actions1.filterIsInstance<ChannelAction.ProcessCmdRes.AddSettledFulfill>().first()
        assertEquals(ChannelAction.HtlcResult.Fulfill.OnChainFulfill(preimage), addSettled.result)

        val (alice2, actions2) = aliceClosing.processEx(ChannelCommand.WatchReceived(WatchEventSpent(alice0.channelId, BITCOIN_OUTPUT_SPENT, bobHtlcTimeoutTx)))
        assertEquals(aliceClosing, alice2)
        assertEquals(2, actions2.size)
        assertTrue(actions2.contains(ChannelAction.Storage.StoreState(aliceClosing.state)))
        assertEquals(WatchConfirmed(alice0.channelId, bobHtlcTimeoutTx, 3, BITCOIN_TX_CONFIRMED(bobHtlcTimeoutTx)), actions2.findWatch())

        val watchConfirmed = listOf(
            WatchEventConfirmed(alice0.channelId, BITCOIN_TX_CONFIRMED(bobHtlcSuccessTx), 42, 0, bobHtlcSuccessTx),
            WatchEventConfirmed(alice0.channelId, BITCOIN_TX_CONFIRMED(remoteCommitPublished.commitTx), 42, 1, remoteCommitPublished.commitTx),
            WatchEventConfirmed(alice0.channelId, BITCOIN_TX_CONFIRMED(remoteCommitPublished.claimMainOutputTx!!.tx), 200, 0, remoteCommitPublished.claimMainOutputTx!!.tx),
            WatchEventConfirmed(alice0.channelId, BITCOIN_TX_CONFIRMED(remoteCommitPublished.claimHtlcSuccessTxs()[1].tx), 202, 0, remoteCommitPublished.claimHtlcSuccessTxs()[1].tx),
            WatchEventConfirmed(alice0.channelId, BITCOIN_TX_CONFIRMED(bobHtlcTimeoutTx), 202, 0, bobHtlcTimeoutTx),
            WatchEventConfirmed(alice0.channelId, BITCOIN_TX_CONFIRMED(remoteCommitPublished.claimHtlcTimeoutTxs()[1].tx), 202, 0, remoteCommitPublished.claimHtlcTimeoutTxs()[1].tx)
        )
        confirmWatchedTxs(aliceClosing, watchConfirmed)
    }

    @Test
    fun `recv ChannelEvent Restore -- remote commit`() {
        val (alice0, bob0) = reachNormal()
        val (aliceClosing, remoteCommitPublished) = run {
            // alice sends an htlc to bob
            val (nodes1, _, _) = addHtlc(50_000_000.msat, alice0, bob0)
            val (alice1, bob1) = nodes1
            val (alice2, bob2) = crossSign(alice1, bob1)
            assertIs<LNChannel<Normal>>(bob2)
            val bobCommitTx = bob2.commitments.localCommit.publishableTxs.commitTx.tx
            val (aliceClosing, remoteCommitPublished) = remoteClose(bobCommitTx, alice2)
            Pair(aliceClosing, remoteCommitPublished)
        }

        assertNotNull(remoteCommitPublished.claimMainOutputTx)
        assertTrue(remoteCommitPublished.claimHtlcSuccessTxs().isEmpty())
        assertEquals(1, remoteCommitPublished.claimHtlcTimeoutTxs().size)

        // Simulate a wallet restart
        val initState = LNChannel(aliceClosing.ctx, WaitForInit)
        val (alice1, actions1) = initState.processEx(ChannelCommand.Restore(aliceClosing.state))
        assertIs<LNChannel<Closing>>(alice1)
        assertEquals(aliceClosing, alice1)
        actions1.doesNotHave<ChannelAction.Storage.StoreChannelClosing>()

        // We should republish closing transactions
        val txs = listOf(
            remoteCommitPublished.claimMainOutputTx!!.tx,
            remoteCommitPublished.claimHtlcTimeoutTxs().first().tx,
        )
        assertEquals(actions1.findTxs(), txs)
        val watchConfirmed = listOf(
            remoteCommitPublished.commitTx.txid,
            remoteCommitPublished.claimMainOutputTx!!.tx.txid,
        )
        assertEquals(actions1.findWatches<WatchConfirmed>().map { it.txId }, watchConfirmed)
        val watchSpent = listOf(
            remoteCommitPublished.commitTx.txIn.first().outPoint,
            remoteCommitPublished.claimHtlcTimeoutTxs().first().input.outPoint,
        )
        assertEquals(actions1.findWatches<WatchSpent>().map { OutPoint(it.txId.reversed(), it.outputIndex.toLong()) }, watchSpent)
    }

    @Test
    fun `recv BITCOIN_FUNDING_SPENT -- next remote commit`() {
        val (alice0, bob0) = reachNormal()
        // alice sends an htlc to bob
        val (nodes1, _, _) = addHtlc(50_000_000.msat, alice0, bob0)
        val (alice1, bob1) = nodes1
        val bobCommitTx1 = bob1.state.commitments.localCommit.publishableTxs.commitTx.tx
        // alice signs it, but bob doesn't revoke
        val (alice2, actionsAlice2) = alice1.processEx(ChannelCommand.ExecuteCommand(CMD_SIGN))
        val commitSig = actionsAlice2.hasOutgoingMessage<CommitSig>()
        val (bob2, actionsBob2) = bob1.processEx(ChannelCommand.MessageReceived(commitSig))
        actionsBob2.hasOutgoingMessage<RevokeAndAck>() // not forwarded to Alice (malicious Bob)
        // Bob publishes the next commit tx.
        val bobCommitTx2 = (bob2.state as Normal).commitments.localCommit.publishableTxs.commitTx.tx
        assertNotEquals(bobCommitTx1.txid, bobCommitTx2.txid)
        val (aliceClosing, remoteCommitPublished) = remoteClose(bobCommitTx2, alice2)

        assertNotNull(remoteCommitPublished.claimMainOutputTx)
        assertEquals(1, remoteCommitPublished.claimHtlcTimeoutTxs().size)
        assertTrue(remoteCommitPublished.claimHtlcSuccessTxs().isEmpty())

        assertNull(aliceClosing.state.remoteCommitPublished)
        assertNotNull(aliceClosing.state.nextRemoteCommitPublished)
        assertNull(aliceClosing.state.futureRemoteCommitPublished)
    }

    @Test
    fun `recv BITCOIN_TX_CONFIRMED -- next remote commit`() {
        val (alice0, bob0) = reachNormal()
        // alice sends an htlc to bob
        val (aliceClosing, remoteCommitPublished) = run {
            val (nodes1, preimage, _) = addHtlc(30_000_000.msat, alice0, bob0)
            val (alice1, bob1) = nodes1
            // and more htlcs with the same payment_hash
            val (_, cmd2) = makeCmdAdd(25_000_000.msat, bob0.staticParams.nodeParams.nodeId, alice1.currentBlockHeight.toLong() - 1, preimage)
            val (alice2, bob2, _) = addHtlc(cmd2, alice1, bob1)
            val (alice3, bob3) = crossSign(alice2, bob2)
            val bobCommitTx1 = bob2.state.commitments.localCommit.publishableTxs.commitTx.tx
            // add more htlcs that bob doesn't revoke
            val (_, cmd3) = makeCmdAdd(20_000_000.msat, bob0.staticParams.nodeParams.nodeId, alice3.currentBlockHeight.toLong(), preimage)
            val (alice4, bob4, _) = addHtlc(cmd3, alice3, bob3)
            val (alice5, actionsAlice5) = alice4.processEx(ChannelCommand.ExecuteCommand(CMD_SIGN))
            val commitSig = actionsAlice5.hasOutgoingMessage<CommitSig>()
            val (bob5, actionsBob5) = bob4.processEx(ChannelCommand.MessageReceived(commitSig))
            actionsBob5.hasOutgoingMessage<RevokeAndAck>() // not forwarded to Alice (malicious Bob)
            // Bob publishes the next commit tx.
            val bobCommitTx2 = (bob5.state as Normal).commitments.localCommit.publishableTxs.commitTx.tx
            assertNotEquals(bobCommitTx1.txid, bobCommitTx2.txid)
            remoteClose(bobCommitTx2, alice5)
        }

        // actual test starts here
        assertNotNull(aliceClosing.state.nextRemoteCommitPublished)
        assertNotNull(remoteCommitPublished.claimMainOutputTx)
        assertEquals(3, remoteCommitPublished.claimHtlcTimeoutTxs().size)
        assertTrue(remoteCommitPublished.claimHtlcSuccessTxs().isEmpty())

        val watchConfirmed = listOf(
            WatchEventConfirmed(alice0.channelId, BITCOIN_TX_CONFIRMED(remoteCommitPublished.commitTx), 42, 1, remoteCommitPublished.commitTx),
            WatchEventConfirmed(alice0.channelId, BITCOIN_TX_CONFIRMED(remoteCommitPublished.claimHtlcTimeoutTxs()[1].tx), 201, 0, remoteCommitPublished.claimHtlcTimeoutTxs()[1].tx),
            WatchEventConfirmed(alice0.channelId, BITCOIN_TX_CONFIRMED(remoteCommitPublished.claimHtlcTimeoutTxs()[2].tx), 201, 0, remoteCommitPublished.claimHtlcTimeoutTxs()[2].tx),
            WatchEventConfirmed(alice0.channelId, BITCOIN_TX_CONFIRMED(remoteCommitPublished.claimMainOutputTx!!.tx), 202, 0, remoteCommitPublished.claimMainOutputTx!!.tx),
            WatchEventConfirmed(alice0.channelId, BITCOIN_TX_CONFIRMED(remoteCommitPublished.claimHtlcTimeoutTxs()[0].tx), 204, 0, remoteCommitPublished.claimHtlcTimeoutTxs()[0].tx)
        )
        confirmWatchedTxs(aliceClosing, watchConfirmed)
    }

    @Test
    fun `recv BITCOIN_TX_CONFIRMED -- next remote commit --  followed by CMD_FULFILL_HTLC`() {
        val (alice0, bob0) = reachNormal()
        val (aliceClosing, remoteCommitPublished, fulfill) = run {
            // An HTLC Bob -> Alice is cross-signed that will be fulfilled later.
            val (nodes1, preimage, htlc) = addHtlc(110_000_000.msat, bob0, alice0)
            val (bob1, alice1) = nodes1
            // An HTLC Alice -> Bob is cross-signed and will timeout later.
            val (nodes2, _, _) = addHtlc(95_000_000.msat, alice1, bob1)
            val (alice2, bob2) = nodes2
            val (alice3, bob3) = crossSign(alice2, bob2)
            // add another htlc that bob doesn't revoke
            val (nodes4, _, _) = addHtlc(20_000_000.msat, alice3, bob3)
            val (alice4, bob4) = nodes4
            val (alice5, actionsAlice5) = alice4.processEx(ChannelCommand.ExecuteCommand(CMD_SIGN))
            val commitSig = actionsAlice5.hasOutgoingMessage<CommitSig>()
            val (bob5, actionsBob5) = bob4.processEx(ChannelCommand.MessageReceived(commitSig))
            actionsBob5.hasOutgoingMessage<RevokeAndAck>() // not forwarded to Alice (malicious Bob)
            // Now Bob publishes his commit tx (force-close).
            val bobCommitTx = (bob5.state as Normal).commitments.localCommit.publishableTxs.commitTx.tx
            assertEquals(7, bobCommitTx.txOut.size) // two main outputs + 2 anchors + 3 HTLCs
            val (aliceClosing, remoteCommitPublished) = remoteClose(bobCommitTx, alice5)
            Triple(aliceClosing, remoteCommitPublished, CMD_FULFILL_HTLC(htlc.id, preimage, commit = true))
        }

        assertNotNull(aliceClosing.state.nextRemoteCommitPublished)
        assertNotNull(remoteCommitPublished.claimMainOutputTx)
        // we don't have the preimage to claim the htlc-success yet
        assertTrue(remoteCommitPublished.claimHtlcSuccessTxs().isEmpty())
        assertEquals(2, remoteCommitPublished.claimHtlcTimeoutTxs().size)

        // Alice receives the preimage for the first HTLC from the payment handler; she can now claim the corresponding HTLC output.
        val (aliceFulfill, actionsFulfill) = aliceClosing.processEx(ChannelCommand.ExecuteCommand(fulfill))
        assertIs<LNChannel<Closing>>(aliceFulfill)
        assertEquals(1, aliceFulfill.state.nextRemoteCommitPublished!!.claimHtlcSuccessTxs().size)
        assertEquals(2, aliceFulfill.state.nextRemoteCommitPublished!!.claimHtlcTimeoutTxs().size)
        val claimHtlcSuccess = aliceFulfill.state.nextRemoteCommitPublished!!.claimHtlcSuccessTxs().first()
        actionsFulfill.hasTx(claimHtlcSuccess.tx)
        assertTrue(actionsFulfill.findWatches<WatchSpent>().map { Pair(it.txId, it.outputIndex.toLong()) }.contains(Pair(remoteCommitPublished.commitTx.txid, claimHtlcSuccess.input.outPoint.index)))
        Transaction.correctlySpends(claimHtlcSuccess.tx, remoteCommitPublished.commitTx, ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
        actionsFulfill.doesNotHave<ChannelAction.Storage.StoreChannelClosing>()

        val watchConfirmed = listOf(
            WatchEventConfirmed(alice0.channelId, BITCOIN_TX_CONFIRMED(remoteCommitPublished.commitTx), 42, 1, remoteCommitPublished.commitTx),
            WatchEventConfirmed(alice0.channelId, BITCOIN_TX_CONFIRMED(remoteCommitPublished.claimHtlcTimeoutTxs()[0].tx), 210, 0, remoteCommitPublished.claimHtlcTimeoutTxs()[0].tx),
            WatchEventConfirmed(alice0.channelId, BITCOIN_TX_CONFIRMED(claimHtlcSuccess.tx), 210, 1, claimHtlcSuccess.tx),
            WatchEventConfirmed(alice0.channelId, BITCOIN_TX_CONFIRMED(remoteCommitPublished.claimHtlcTimeoutTxs()[1].tx), 210, 3, remoteCommitPublished.claimHtlcTimeoutTxs()[1].tx),
            WatchEventConfirmed(alice0.channelId, BITCOIN_TX_CONFIRMED(remoteCommitPublished.claimMainOutputTx!!.tx), 250, 0, remoteCommitPublished.claimMainOutputTx!!.tx)
        )
        confirmWatchedTxs(aliceFulfill, watchConfirmed)
    }

    @Test
    fun `recv BITCOIN_OUTPUT_SPENT -- next remote commit`() {
        val (alice0, bob0) = reachNormal()
        val (aliceClosing, remoteCommitPublished) = run {
            val (nodes1, rb1, addAlice1) = addHtlc(20_000_000.msat, alice0, bob0)
            val (alice1, bob1) = nodes1
            val (nodes2, rb2, addAlice2) = addHtlc(15_000_000.msat, alice1, bob1)
            val (alice2, bob2) = nodes2
            val (nodes3, ra1, addBob1) = addHtlc(10_000_000.msat, bob2, alice2)
            val (bob3, alice3) = nodes3
            val (nodes4, ra2, addBob2) = addHtlc(12_000_000.msat, bob3, alice3)
            val (bob4, alice4) = nodes4
            val (alice5, bob5) = crossSign(alice4, bob4)
            // add another htlc that bob doesn't revoke
            val (nodes6, _, _) = addHtlc(20_000_000.msat, alice5, bob5)
            val (alice6, bob6) = nodes6
            val (alice7, actionsAlice7) = alice6.processEx(ChannelCommand.ExecuteCommand(CMD_SIGN))
            val commitSig = actionsAlice7.hasOutgoingMessage<CommitSig>()
            val (bob7, actionsBob7) = bob6.processEx(ChannelCommand.MessageReceived(commitSig))
            actionsBob7.hasOutgoingMessage<RevokeAndAck>() // not forwarded to Alice (malicious Bob)
            // alice is ready to claim incoming htlcs
            val (alice8, _) = alice7.processEx(ChannelCommand.ExecuteCommand(CMD_FULFILL_HTLC(addBob1.id, ra1, commit = false)))
            val (alice9, _) = alice8.processEx(ChannelCommand.ExecuteCommand(CMD_FULFILL_HTLC(addBob2.id, ra2, commit = false)))
            // bob is ready to claim incoming htlcs
            val (bob8, _) = bob7.processEx(ChannelCommand.ExecuteCommand(CMD_FULFILL_HTLC(addAlice1.id, rb1, commit = false)))
            val (bob9, _) = bob8.processEx(ChannelCommand.ExecuteCommand(CMD_FULFILL_HTLC(addAlice2.id, rb2, commit = false)))
            val bobCommitTx = (bob9.state as Normal).commitments.localCommit.publishableTxs.commitTx.tx
            assertEquals(9, bobCommitTx.txOut.size) // 2 main outputs, 2 anchors and 5 htlcs

            // alice publishes her commitment
            val (alice10, localCommitPublished) = localClose(alice9)
            // bob also publishes his next commitment, and wins the race to confirm
            val (alice11, remoteCommitPublished) = remoteClose(bobCommitTx, alice10.copy(state = alice10.state.copy(localCommitPublished = null)))
            assertEquals(2, localCommitPublished.htlcTimeoutTxs().size)
            assertEquals(2, localCommitPublished.htlcSuccessTxs().size)
            assertEquals(4, localCommitPublished.claimHtlcDelayedTxs.size)
            assertEquals(2, remoteCommitPublished.claimHtlcSuccessTxs().size)
            assertEquals(3, remoteCommitPublished.claimHtlcTimeoutTxs().size)

            Pair(alice11, remoteCommitPublished)
        }

        assertNotNull(aliceClosing.state.nextRemoteCommitPublished)
        assertNotNull(remoteCommitPublished.claimMainOutputTx)

        // Bob claims 2 htlc outputs, alice will claim the other 3.
        val bobHtlcSuccessTx = Transaction(
            version = 2,
            txIn = listOf(TxIn(remoteCommitPublished.claimHtlcTimeoutTxs()[0].input.outPoint, ByteVector.empty, 0, ScriptWitness.empty)),
            txOut = emptyList(),
            lockTime = 0
        )
        val bobHtlcTimeoutTx = Transaction(
            version = 2,
            txIn = listOf(TxIn(remoteCommitPublished.claimHtlcSuccessTxs()[0].input.outPoint, ByteVector.empty, 0, ScriptWitness.empty)),
            txOut = emptyList(),
            lockTime = 0
        )

        val (alice1, actions1) = aliceClosing.processEx(ChannelCommand.WatchReceived(WatchEventSpent(alice0.channelId, BITCOIN_OUTPUT_SPENT, bobHtlcSuccessTx)))
        assertEquals(aliceClosing, alice1)
        assertEquals(2, actions1.size)
        assertTrue(actions1.contains(ChannelAction.Storage.StoreState(aliceClosing.state)))
        assertEquals(WatchConfirmed(alice0.channelId, bobHtlcSuccessTx, 3, BITCOIN_TX_CONFIRMED(bobHtlcSuccessTx)), actions1.findWatch())

        val (alice2, actions2) = aliceClosing.processEx(ChannelCommand.WatchReceived(WatchEventSpent(alice0.channelId, BITCOIN_OUTPUT_SPENT, bobHtlcTimeoutTx)))
        assertEquals(aliceClosing, alice2)
        assertEquals(2, actions2.size)
        assertTrue(actions2.contains(ChannelAction.Storage.StoreState(aliceClosing.state)))
        assertEquals(WatchConfirmed(alice0.channelId, bobHtlcTimeoutTx, 3, BITCOIN_TX_CONFIRMED(bobHtlcTimeoutTx)), actions2.findWatch())

        val watchConfirmed = listOf(
            WatchEventConfirmed(alice0.channelId, BITCOIN_TX_CONFIRMED(bobHtlcSuccessTx), 42, 0, bobHtlcSuccessTx),
            WatchEventConfirmed(alice0.channelId, BITCOIN_TX_CONFIRMED(remoteCommitPublished.commitTx), 42, 1, remoteCommitPublished.commitTx),
            WatchEventConfirmed(alice0.channelId, BITCOIN_TX_CONFIRMED(remoteCommitPublished.claimMainOutputTx!!.tx), 200, 0, remoteCommitPublished.claimMainOutputTx!!.tx),
            WatchEventConfirmed(alice0.channelId, BITCOIN_TX_CONFIRMED(remoteCommitPublished.claimHtlcSuccessTxs()[1].tx), 202, 0, remoteCommitPublished.claimHtlcSuccessTxs()[1].tx),
            WatchEventConfirmed(alice0.channelId, BITCOIN_TX_CONFIRMED(bobHtlcTimeoutTx), 202, 0, bobHtlcTimeoutTx),
            WatchEventConfirmed(alice0.channelId, BITCOIN_TX_CONFIRMED(remoteCommitPublished.claimHtlcTimeoutTxs()[2].tx), 202, 0, remoteCommitPublished.claimHtlcTimeoutTxs()[2].tx),
            WatchEventConfirmed(alice0.channelId, BITCOIN_TX_CONFIRMED(remoteCommitPublished.claimHtlcTimeoutTxs()[1].tx), 202, 0, remoteCommitPublished.claimHtlcTimeoutTxs()[1].tx)
        )
        confirmWatchedTxs(aliceClosing, watchConfirmed)
    }

    @Test
    fun `recv ChannelEvent Restore -- next remote commit`() {
        val (alice0, bob0) = reachNormal()
        val (aliceClosing, remoteCommitPublished) = run {
            // alice sends an htlc to bob
            val (nodes1, preimage, _) = addHtlc(30_000_000.msat, alice0, bob0)
            val (alice1, bob1) = nodes1
            assertIs<LNChannel<Normal>>(bob1)
            val bobCommitTx1 = bob1.commitments.localCommit.publishableTxs.commitTx.tx
            // add more htlcs that bob doesn't revoke
            val (_, cmd1) = makeCmdAdd(20_000_000.msat, bob0.staticParams.nodeParams.nodeId, alice1.currentBlockHeight.toLong(), preimage)
            val (alice2, bob2, _) = addHtlc(cmd1, alice1, bob1)
            val (alice3, actionsAlice3) = alice2.processEx(ChannelCommand.ExecuteCommand(CMD_SIGN))
            val commitSig = actionsAlice3.hasOutgoingMessage<CommitSig>()
            val (bob3, actionsBob3) = bob2.processEx(ChannelCommand.MessageReceived(commitSig))
            assertIs<LNChannel<Normal>>(bob3)
            actionsBob3.hasOutgoingMessage<RevokeAndAck>() // not forwarded to Alice (malicious Bob)
            // Bob publishes the next commit tx.
            val bobCommitTx2 = bob3.commitments.localCommit.publishableTxs.commitTx.tx
            assertNotEquals(bobCommitTx1.txid, bobCommitTx2.txid)
            remoteClose(bobCommitTx2, alice3)
        }

        assertNotNull(remoteCommitPublished.claimMainOutputTx)
        assertTrue(remoteCommitPublished.claimHtlcSuccessTxs().isEmpty())
        assertEquals(2, remoteCommitPublished.claimHtlcTimeoutTxs().size)

        // Simulate a wallet restart
        val initState = LNChannel(aliceClosing.ctx, WaitForInit)
        val (alice1, actions1) = initState.processEx(ChannelCommand.Restore(aliceClosing.state))
        assertTrue(alice1.state is Closing)
        assertEquals(aliceClosing, alice1)
        actions1.doesNotHave<ChannelAction.Storage.StoreChannelClosing>()

        // We should republish closing transactions
        val txs = listOf(
            remoteCommitPublished.claimMainOutputTx!!.tx,
            remoteCommitPublished.claimHtlcTimeoutTxs().first().tx,
            remoteCommitPublished.claimHtlcTimeoutTxs().last().tx,
        )
        assertEquals(actions1.findTxs(), txs)
        val watchConfirmed = listOf(
            remoteCommitPublished.commitTx.txid,
            remoteCommitPublished.claimMainOutputTx!!.tx.txid,
        )
        assertEquals(actions1.findWatches<WatchConfirmed>().map { it.txId }, watchConfirmed)
        val watchSpent = listOf(
            remoteCommitPublished.commitTx.txIn.first().outPoint,
            remoteCommitPublished.claimHtlcTimeoutTxs().first().input.outPoint,
            remoteCommitPublished.claimHtlcTimeoutTxs().last().input.outPoint,
        )
        assertEquals(actions1.findWatches<WatchSpent>().map { OutPoint(it.txId.reversed(), it.outputIndex.toLong()) }, watchSpent)
    }

    @Test
    fun `recv BITCOIN_TX_CONFIRMED -- future remote commit`() {
        val (alice0, bob0) = reachNormal(bobFeatures = TestConstants.Bob.nodeParams.features.remove(Feature.ChannelBackupClient))
        val (_, bobDisconnected) = run {
            // This HTLC will be fulfilled.
            val (nodes1, preimage, htlc) = addHtlc(25_000_000.msat, alice0, bob0)
            val (alice1, bob1) = nodes1
            // These 2 HTLCs should timeout on-chain, but since alice lost data, she won't be able to claim them.
            val (nodes2, _, _) = addHtlc(15_000_000.msat, alice1, bob1)
            val (alice2, bob2) = nodes2
            val (nodes3, _, _) = addHtlc(18_000_000.msat, alice2, bob2)
            val (alice3, bob3) = nodes3
            val (alice4, bob4) = crossSign(alice3, bob3)
            val (alice5, bob5) = fulfillHtlc(htlc.id, preimage, alice4, bob4)
            val (bob6, alice6) = crossSign(bob5, alice5)
            // we simulate a disconnection
            val (alice7, _) = alice6.processEx(ChannelCommand.Disconnected)
            assertIs<LNChannel<Offline>>(alice7)
            val (bob7, _) = bob6.processEx(ChannelCommand.Disconnected)
            assertIs<LNChannel<Offline>>(bob7)
            Pair(alice7, bob7)
        }

        val localInit = Init(ByteVector(alice0.commitments.localParams.features.toByteArray()))
        val remoteInit = Init(ByteVector(bob0.commitments.localParams.features.toByteArray()))

        // then we manually replace alice's state with an older one and reconnect them.
        val (alice1, aliceActions1) = LNChannel(alice0.ctx, Offline(alice0.state)).processEx(ChannelCommand.Connected(localInit, remoteInit))
        assertIs<LNChannel<Syncing>>(alice1)
        val channelReestablishA = aliceActions1.findOutgoingMessage<ChannelReestablish>()
        val (bob1, bobActions1) = bobDisconnected.processEx(ChannelCommand.Connected(remoteInit, localInit))
        assertIs<LNChannel<Syncing>>(bob1)
        val channelReestablishB = bobActions1.findOutgoingMessage<ChannelReestablish>()

        // alice realizes it has an old state and asks bob to publish its current commitment
        val (alice2, aliceActions2) = alice1.processEx(ChannelCommand.MessageReceived(channelReestablishB))
        assertIs<LNChannel<WaitForRemotePublishFutureCommitment>>(alice2)
        val error = aliceActions2.findOutgoingMessage<Error>()
        assertEquals(PleasePublishYourCommitment(alice2.channelId).message, error.toAscii())

        // bob is nice and publishes its commitment as soon as it detects that alice has an outdated commitment
        val (bob2, bobActions2) = bob1.processEx(ChannelCommand.MessageReceived(channelReestablishA))
        assertIs<LNChannel<Closing>>(bob2)
        bobActions2.has<ChannelAction.Storage.StoreChannelClosing>()
        bobActions2.hasOutgoingMessage<Error>()
        val bobCommitTx = bob2.commitments.localCommit.publishableTxs.commitTx.tx
        assertEquals(6, bobCommitTx.txOut.size) // 2 main outputs + 2 anchors + 2 HTLCs

        val (bob3, bobActions3) = bob2.processEx(ChannelCommand.MessageReceived(error))
        assertEquals(bob2, bob3)
        assertTrue(bobActions3.isEmpty())

        val (alice3, aliceActions3) = alice2.processEx(ChannelCommand.WatchReceived(WatchEventSpent(alice0.channelId, BITCOIN_FUNDING_SPENT, bobCommitTx)))
        assertIs<LNChannel<Closing>>(alice3)
        aliceActions3.has<ChannelAction.Storage.StoreChannelClosing>()
        val futureRemoteCommitPublished = alice3.state.futureRemoteCommitPublished
        assertNotNull(futureRemoteCommitPublished)
        assertEquals(bobCommitTx.txid, aliceActions3.findWatches<WatchConfirmed>()[0].txId)
        // alice is able to claim its main output
        val aliceTxs = aliceActions3.findTxs()
        assertEquals(listOf(futureRemoteCommitPublished.claimMainOutputTx!!.tx), aliceTxs)

        // simulate a wallet restart
        run {
            val initState = LNChannel(alice3.ctx, WaitForInit)
            val (alice4, actions4) = initState.processEx(ChannelCommand.Restore(alice3.state))
            assertIs<LNChannel<Closing>>(alice4)
            assertEquals(alice3, alice4)
            assertEquals(actions4.findTxs(), listOf(futureRemoteCommitPublished.claimMainOutputTx!!.tx))
            assertEquals(actions4.findWatches<WatchConfirmed>().map { it.txId }, listOf(bobCommitTx.txid, futureRemoteCommitPublished.claimMainOutputTx!!.tx.txid))
            assertEquals(actions4.findWatches<WatchSpent>().map { OutPoint(it.txId.reversed(), it.outputIndex.toLong()) }, listOf(bobCommitTx.txIn.first().outPoint))
            actions4.doesNotHave<ChannelAction.Storage.StoreChannelClosing>()
        }

        val (alice4, aliceActions4) = alice3.processEx(ChannelCommand.WatchReceived(WatchEventConfirmed(alice0.channelId, BITCOIN_TX_CONFIRMED(bobCommitTx), 50, 0, bobCommitTx)))
        assertIs<LNChannel<Closing>>(alice4)
        assertEquals(listOf(ChannelAction.Storage.StoreState(alice4.state)), aliceActions4)

        val (alice5, aliceActions5) = alice4.processEx(ChannelCommand.WatchReceived(WatchEventConfirmed(alice0.channelId, BITCOIN_TX_CONFIRMED(aliceTxs[0]), 60, 3, aliceTxs[0])))
        assertIs<LNChannel<Closed>>(alice5)
        assertEquals(
            listOf(ChannelAction.Storage.StoreState(alice5.state)),
            aliceActions5.filterIsInstance<ChannelAction.Storage.StoreState>()
        )
        val storeChannelClosed = aliceActions5.filterIsInstance<ChannelAction.Storage.StoreChannelClosed>().firstOrNull()
        assertNotNull(storeChannelClosed)
        assertTrue(storeChannelClosed.closingTxs.all { it.closingType == ChannelClosingType.Remote })
        assertEquals(aliceTxs.map { it.txid }.toSet(), storeChannelClosed.closingTxs.map { it.txId }.toSet())
    }

    @Test
    fun `recv BITCOIN_FUNDING_SPENT -- one revoked tx`() {
        val (alice0, _, bobCommitTxs, htlcsAlice, htlcsBob) = prepareRevokedClose()

        // bob publishes one of his revoked txs
        val bobRevokedTx = bobCommitTxs[1].commitTx.tx
        assertEquals(6, bobRevokedTx.txOut.size)

        val (alice1, aliceActions1) = alice0.processEx(ChannelCommand.WatchReceived(WatchEventSpent(alice0.channelId, BITCOIN_FUNDING_SPENT, bobRevokedTx)))
        assertIs<LNChannel<Closing>>(alice1)
        aliceActions1.hasOutgoingMessage<Error>()
        aliceActions1.has<ChannelAction.Storage.StoreState>()
        aliceActions1.has<ChannelAction.Storage.StoreChannelClosing>()

        // alice creates penalty txs
        run {
            assertEquals(1, alice1.state.revokedCommitPublished.size)
            val revokedCommitPublished = alice1.state.revokedCommitPublished[0]
            assertEquals(bobRevokedTx, revokedCommitPublished.commitTx)
            assertNotNull(revokedCommitPublished.claimMainOutputTx)
            assertNotNull(revokedCommitPublished.mainPenaltyTx)
            assertTrue(revokedCommitPublished.htlcPenaltyTxs.isEmpty())
            assertTrue(revokedCommitPublished.claimHtlcDelayedPenaltyTxs.isEmpty())
            Transaction.correctlySpends(revokedCommitPublished.mainPenaltyTx!!.tx, bobRevokedTx, ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
            // alice publishes txs for the main outputs
            assertEquals(setOf(revokedCommitPublished.claimMainOutputTx!!.tx, revokedCommitPublished.mainPenaltyTx!!.tx), aliceActions1.findTxs().toSet())
            // alice watches confirmation for the commit tx and her main output
            assertEquals(setOf(bobRevokedTx.txid, revokedCommitPublished.claimMainOutputTx!!.tx.txid), aliceActions1.findWatches<WatchConfirmed>().map { it.txId }.toSet())
            // alice watches bob's main output
            assertEquals(setOf(revokedCommitPublished.mainPenaltyTx!!.input.outPoint.index), aliceActions1.findWatches<WatchSpent>().map { it.outputIndex.toLong() }.toSet())
        }

        // alice fetches information about the revoked htlcs
        assertEquals(ChannelAction.Storage.GetHtlcInfos(bobRevokedTx.txid, 2), aliceActions1.find())
        val htlcInfos = listOf(
            ChannelAction.Storage.HtlcInfo(alice0.channelId, 2, htlcsAlice[0].paymentHash, htlcsAlice[0].cltvExpiry),
            ChannelAction.Storage.HtlcInfo(alice0.channelId, 2, htlcsBob[0].paymentHash, htlcsBob[0].cltvExpiry),
        )
        val (alice2, aliceActions2) = alice1.processEx(ChannelCommand.GetHtlcInfosResponse(bobRevokedTx.txid, htlcInfos))
        assertIs<LNChannel<Closing>>(alice2)
        assertNull(aliceActions2.findOutgoingMessageOpt<Error>())
        aliceActions2.has<ChannelAction.Storage.StoreState>()
        aliceActions2.doesNotHave<ChannelAction.Storage.StoreChannelClosing>()

        // alice creates htlc penalty txs and rebroadcasts main txs
        assertEquals(1, alice2.state.revokedCommitPublished.size)
        val revokedCommitPublished = alice2.state.revokedCommitPublished[0]
        assertEquals(bobRevokedTx, revokedCommitPublished.commitTx)
        assertNotNull(revokedCommitPublished.claimMainOutputTx)
        assertNotNull(revokedCommitPublished.mainPenaltyTx)
        assertEquals(2, revokedCommitPublished.htlcPenaltyTxs.size)
        assertTrue(revokedCommitPublished.claimHtlcDelayedPenaltyTxs.isEmpty())
        revokedCommitPublished.htlcPenaltyTxs.forEach { Transaction.correctlySpends(it.tx, bobRevokedTx, ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS) }
        // alice publishes txs for all outputs
        val aliceTxs = setOf(revokedCommitPublished.claimMainOutputTx!!.tx, revokedCommitPublished.mainPenaltyTx!!.tx) + revokedCommitPublished.htlcPenaltyTxs.map { it.tx }.toSet()
        assertEquals(aliceTxs, aliceActions2.findTxs().toSet())
        // alice watches confirmation for the commit tx and her main output
        assertEquals(setOf(bobRevokedTx.txid, revokedCommitPublished.claimMainOutputTx!!.tx.txid), aliceActions2.findWatches<WatchConfirmed>().map { it.txId }.toSet())
        // alice watches bob's outputs
        val outputsToWatch = buildSet {
            add(revokedCommitPublished.mainPenaltyTx!!.input.outPoint)
            addAll(revokedCommitPublished.htlcPenaltyTxs.map { it.input.outPoint })
        }
        assertEquals(3, outputsToWatch.size)
        assertEquals(outputsToWatch, aliceActions2.findWatches<WatchSpent>().map { OutPoint(it.txId.reversed(), it.outputIndex.toLong()) }.toSet())

        // simulate a wallet restart
        run {
            val initState = LNChannel(alice2.ctx, WaitForInit)
            val (alice3, actions3) = initState.processEx(ChannelCommand.Restore(alice2.state))
            assertTrue(alice3.state is Closing)
            assertEquals(alice2, alice3)
            actions3.doesNotHave<ChannelAction.Storage.StoreChannelClosing>()

            // alice republishes transactions
            assertEquals(aliceTxs, actions3.findTxs().toSet())
            assertEquals(setOf(bobRevokedTx.txid, revokedCommitPublished.claimMainOutputTx!!.tx.txid), actions3.findWatches<WatchConfirmed>().map { it.txId }.toSet())
            val watchSpent = outputsToWatch + alice3.commitments.commitInput.outPoint
            assertEquals(watchSpent, actions3.findWatches<WatchSpent>().map { OutPoint(it.txId.reversed(), it.outputIndex.toLong()) }.toSet())
        }

        val watchConfirmed = listOf(
            WatchEventConfirmed(alice0.channelId, BITCOIN_TX_CONFIRMED(revokedCommitPublished.commitTx), 42, 0, revokedCommitPublished.commitTx),
            WatchEventConfirmed(alice0.channelId, BITCOIN_TX_CONFIRMED(revokedCommitPublished.claimMainOutputTx!!.tx), 43, 0, revokedCommitPublished.claimMainOutputTx!!.tx),
            WatchEventConfirmed(alice0.channelId, BITCOIN_TX_CONFIRMED(revokedCommitPublished.mainPenaltyTx!!.tx), 43, 5, revokedCommitPublished.mainPenaltyTx!!.tx),
            WatchEventConfirmed(alice0.channelId, BITCOIN_TX_CONFIRMED(revokedCommitPublished.htlcPenaltyTxs[1].tx), 50, 1, revokedCommitPublished.htlcPenaltyTxs[1].tx),
            WatchEventConfirmed(alice0.channelId, BITCOIN_TX_CONFIRMED(revokedCommitPublished.htlcPenaltyTxs[0].tx), 52, 2, revokedCommitPublished.htlcPenaltyTxs[0].tx),
        )
        confirmWatchedTxs(alice2, watchConfirmed)
    }

    @Test
    fun `recv BITCOIN_FUNDING_SPENT -- multiple revoked tx`() {
        val (alice0, _, bobCommitTxs, htlcsAlice, htlcsBob) = prepareRevokedClose()
        assertEquals(bobCommitTxs.size, bobCommitTxs.map { it.commitTx.tx.txid }.toSet().size) // all commit txs are distinct

        // bob publishes one of his revoked txs
        val (alice1, aliceActions1) = alice0.processEx(ChannelCommand.WatchReceived(WatchEventSpent(alice0.channelId, BITCOIN_FUNDING_SPENT, bobCommitTxs[0].commitTx.tx)))
        assertIs<LNChannel<Closing>>(alice1)
        aliceActions1.hasOutgoingMessage<Error>()
        aliceActions1.has<ChannelAction.Storage.StoreState>()
        aliceActions1.has<ChannelAction.Storage.StoreChannelClosing>()
        assertEquals(1, alice1.state.revokedCommitPublished.size)

        // alice creates penalty txs
        run {
            // alice publishes txs for the main outputs
            assertEquals(2, aliceActions1.findTxs().size)
            // alice watches confirmation for the commit tx and her main output
            assertEquals(2, aliceActions1.findWatches<WatchConfirmed>().size)
            // alice watches bob's main output
            assertEquals(1, aliceActions1.findWatches<WatchSpent>().size)
            // alice fetches information about the revoked htlcs
            assertEquals(ChannelAction.Storage.GetHtlcInfos(bobCommitTxs[0].commitTx.tx.txid, 0), aliceActions1.find())
        }

        // bob publishes another one of his revoked txs
        val (alice2, aliceActions2) = alice1.processEx(ChannelCommand.WatchReceived(WatchEventSpent(alice0.channelId, BITCOIN_FUNDING_SPENT, bobCommitTxs[1].commitTx.tx)))
        assertIs<LNChannel<Closing>>(alice2)
        aliceActions2.hasOutgoingMessage<Error>()
        aliceActions2.has<ChannelAction.Storage.StoreState>()
        aliceActions2.doesNotHave<ChannelAction.Storage.StoreChannelClosing>()
        assertEquals(2, alice2.state.revokedCommitPublished.size)

        // alice creates penalty txs
        run {
            // alice publishes txs for the main outputs
            assertEquals(2, aliceActions2.findTxs().size)
            // alice watches confirmation for the commit tx and her main output
            assertEquals(2, aliceActions2.findWatches<WatchConfirmed>().size)
            // alice watches bob's main output
            assertEquals(1, aliceActions2.findWatches<WatchSpent>().size)
            // alice fetches information about the revoked htlcs
            assertEquals(ChannelAction.Storage.GetHtlcInfos(bobCommitTxs[1].commitTx.tx.txid, 2), aliceActions2.find())
        }

        val (alice3, aliceActions3) = alice2.processEx(ChannelCommand.GetHtlcInfosResponse(bobCommitTxs[0].commitTx.tx.txid, listOf()))
        assertIs<LNChannel<Closing>>(alice3)
        assertNull(aliceActions3.findOutgoingMessageOpt<Error>())
        aliceActions3.has<ChannelAction.Storage.StoreState>()
        aliceActions3.doesNotHave<ChannelAction.Storage.StoreChannelClosing>()

        // alice rebroadcasts main txs for bob's first revoked commitment (no htlc in this commitment)
        run {
            assertEquals(2, alice3.state.revokedCommitPublished.size)
            val revokedCommitPublished = alice3.state.revokedCommitPublished[0]
            assertEquals(bobCommitTxs[0].commitTx.tx, revokedCommitPublished.commitTx)
            assertNotNull(revokedCommitPublished.claimMainOutputTx)
            assertNotNull(revokedCommitPublished.mainPenaltyTx)
            Transaction.correctlySpends(revokedCommitPublished.mainPenaltyTx!!.tx, bobCommitTxs[0].commitTx.tx, ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
            assertTrue(revokedCommitPublished.htlcPenaltyTxs.isEmpty())
            assertTrue(revokedCommitPublished.claimHtlcDelayedPenaltyTxs.isEmpty())
            // alice publishes txs for all outputs
            assertEquals(2, aliceActions3.findTxs().size)
            assertEquals(2, aliceActions3.findWatches<WatchConfirmed>().size)
            assertEquals(1, aliceActions3.findWatches<WatchSpent>().size)
        }

        val htlcInfos = listOf(
            ChannelAction.Storage.HtlcInfo(alice0.channelId, 2, htlcsAlice[0].paymentHash, htlcsAlice[0].cltvExpiry),
            ChannelAction.Storage.HtlcInfo(alice0.channelId, 2, htlcsBob[0].paymentHash, htlcsBob[0].cltvExpiry),
        )
        val (alice4, aliceActions4) = alice3.processEx(ChannelCommand.GetHtlcInfosResponse(bobCommitTxs[1].commitTx.tx.txid, htlcInfos))
        assertIs<LNChannel<Closing>>(alice4)
        assertNull(aliceActions4.findOutgoingMessageOpt<Error>())
        aliceActions4.has<ChannelAction.Storage.StoreState>()
        aliceActions4.doesNotHave<ChannelAction.Storage.StoreChannelClosing>()

        // alice creates htlc penalty txs and rebroadcasts main txs for bob's second commitment
        run {
            assertEquals(2, alice4.state.revokedCommitPublished.size)
            val revokedCommitPublished = alice4.state.revokedCommitPublished[1]
            assertEquals(bobCommitTxs[1].commitTx.tx, revokedCommitPublished.commitTx)
            assertNotNull(revokedCommitPublished.claimMainOutputTx)
            assertNotNull(revokedCommitPublished.mainPenaltyTx)
            Transaction.correctlySpends(revokedCommitPublished.mainPenaltyTx!!.tx, bobCommitTxs[1].commitTx.tx, ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
            assertEquals(2, revokedCommitPublished.htlcPenaltyTxs.size)
            assertTrue(revokedCommitPublished.claimHtlcDelayedPenaltyTxs.isEmpty())
            revokedCommitPublished.htlcPenaltyTxs.forEach { Transaction.correctlySpends(it.tx, bobCommitTxs[1].commitTx.tx, ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS) }
            // alice publishes txs for all outputs
            assertEquals(4, aliceActions4.findTxs().size)
            assertEquals(2, aliceActions4.findWatches<WatchConfirmed>().size)
            assertEquals(3, aliceActions4.findWatches<WatchSpent>().size)

            // this revoked transaction is the one to confirm
            val watchConfirmed = listOf(
                WatchEventConfirmed(alice0.channelId, BITCOIN_TX_CONFIRMED(revokedCommitPublished.commitTx), 42, 0, revokedCommitPublished.commitTx),
                WatchEventConfirmed(alice0.channelId, BITCOIN_TX_CONFIRMED(revokedCommitPublished.claimMainOutputTx!!.tx), 43, 0, revokedCommitPublished.claimMainOutputTx!!.tx),
                WatchEventConfirmed(alice0.channelId, BITCOIN_TX_CONFIRMED(revokedCommitPublished.mainPenaltyTx!!.tx), 43, 5, revokedCommitPublished.mainPenaltyTx!!.tx),
                WatchEventConfirmed(alice0.channelId, BITCOIN_TX_CONFIRMED(revokedCommitPublished.htlcPenaltyTxs[1].tx), 50, 1, revokedCommitPublished.htlcPenaltyTxs[1].tx),
                WatchEventConfirmed(alice0.channelId, BITCOIN_TX_CONFIRMED(revokedCommitPublished.htlcPenaltyTxs[0].tx), 52, 2, revokedCommitPublished.htlcPenaltyTxs[0].tx),
            )
            confirmWatchedTxs(alice4, watchConfirmed)
        }
    }

    @Test
    fun `recv BITCOIN_TX_CONFIRMED -- one revoked tx + pending htlcs`() {
        val (alice0, bob0) = reachNormal()
        // bob's first commit tx doesn't contain any htlc
        assertEquals(4, bob0.commitments.localCommit.publishableTxs.commitTx.tx.txOut.size) // 2 main outputs + 2 anchors

        // bob's second commit tx contains 2 incoming htlcs
        val (alice1, bob1, htlcs1) = run {
            val (nodes1, _, htlc1) = addHtlc(35_000_000.msat, alice0, bob0)
            val (alice1, bob1) = nodes1
            val (nodes2, _, htlc2) = addHtlc(20_000_000.msat, alice1, bob1)
            val (alice2, bob2) = nodes2
            val (alice3, bob3) = crossSign(alice2, bob2)
            assertIs<LNChannel<Normal>>(bob3)
            assertEquals(6, bob3.commitments.localCommit.publishableTxs.commitTx.tx.txOut.size)
            Triple(alice3, bob3, listOf(htlc1, htlc2))
        }

        // bob's third commit tx contains 1 of the previous htlcs and 2 new htlcs
        val (alice2, _, htlcs2) = run {
            val (nodes2, _, htlc3) = addHtlc(25_000_000.msat, alice1, bob1)
            val (alice2, bob2) = nodes2
            val (nodes3, _, htlc4) = addHtlc(18_000_000.msat, alice2, bob2)
            val (alice3, bob3) = nodes3
            val (alice4, bob4) = failHtlc(htlcs1[0].id, alice3, bob3)
            assertIs<LNChannel<ChannelStateWithCommitments>>(alice4)
            assertIs<LNChannel<ChannelStateWithCommitments>>(bob4)
            val (alice5, bob5) = crossSign(alice4, bob4)
            assertIs<LNChannel<Normal>>(bob5)
            assertEquals(7, bob5.state.commitments.localCommit.publishableTxs.commitTx.tx.txOut.size)
            Triple(alice5, bob5, listOf(htlc3, htlc4))
        }

        // bob publishes a revoked tx
        val bobRevokedTx = bob1.commitments.localCommit.publishableTxs.commitTx.tx
        val (alice3, actions3) = alice2.processEx(ChannelCommand.WatchReceived(WatchEventSpent(alice0.channelId, BITCOIN_FUNDING_SPENT, bobRevokedTx)))
        assertIs<LNChannel<Closing>>(alice3)
        actions3.hasOutgoingMessage<Error>()
        actions3.has<ChannelAction.Storage.StoreChannelClosing>()

        val htlcInfos = listOf(
            ChannelAction.Storage.HtlcInfo(alice0.channelId, 1, htlcs1[0].paymentHash, htlcs1[0].cltvExpiry),
            ChannelAction.Storage.HtlcInfo(alice0.channelId, 1, htlcs1[1].paymentHash, htlcs1[1].cltvExpiry)
        )
        val (alice4, actions4) = alice3.processEx(ChannelCommand.GetHtlcInfosResponse(bobRevokedTx.txid, htlcInfos))
        assertIs<LNChannel<Closing>>(alice4)
        actions4.doesNotHave<ChannelAction.Storage.StoreChannelClosing>()

        // bob's revoked tx confirms: alice should fail all pending htlcs
        val (alice5, actions5) = alice4.processEx(ChannelCommand.WatchReceived(WatchEventConfirmed(alice0.channelId, BITCOIN_TX_CONFIRMED(bobRevokedTx), 100, 3, bobRevokedTx)))
        assertTrue(alice5.state is Closing)
        actions5.doesNotHave<ChannelAction.Storage.StoreChannelClosing>()
        val addSettledActions = actions5.filterIsInstance<ChannelAction.ProcessCmdRes>()
        assertEquals(3, addSettledActions.size)
        val addSettledFails = addSettledActions.filterIsInstance<ChannelAction.ProcessCmdRes.AddSettledFail>()
        assertEquals(setOf(htlcs1[1], htlcs2[0], htlcs2[1]), addSettledFails.map { it.htlc }.toSet())
        assertTrue(addSettledFails.all { it.result is ChannelAction.HtlcResult.Fail.OnChainFail })
    }

    @Test
    fun `recv BITCOIN_OUTPUT_SPENT -- one revoked tx + counterparty published HtlcSuccess tx`() {
        val (alice0, _, bobCommitTxs, htlcsAlice, htlcsBob) = prepareRevokedClose()

        // bob publishes one of his revoked txs
        val bobRevokedTx = bobCommitTxs[2]
        assertEquals(8, bobRevokedTx.commitTx.tx.txOut.size)

        val (alice1, aliceActions1) = alice0.processEx(ChannelCommand.WatchReceived(WatchEventSpent(alice0.channelId, BITCOIN_FUNDING_SPENT, bobRevokedTx.commitTx.tx)))
        assertIs<LNChannel<Closing>>(alice1)
        aliceActions1.hasOutgoingMessage<Error>()
        aliceActions1.has<ChannelAction.Storage.StoreState>()
        aliceActions1.has<ChannelAction.Storage.StoreChannelClosing>()

        // alice creates penalty txs
        run {
            val revokedCommitPublished = alice1.state.revokedCommitPublished[0]
            assertTrue(revokedCommitPublished.htlcPenaltyTxs.isEmpty())
            assertTrue(revokedCommitPublished.claimHtlcDelayedPenaltyTxs.isEmpty())
            // alice publishes txs for the main outputs and sets watches
            assertEquals(2, aliceActions1.findTxs().size)
            assertEquals(2, aliceActions1.findWatches<WatchConfirmed>().size)
            assertEquals(1, aliceActions1.findWatches<WatchSpent>().size)
        }

        // alice fetches information about the revoked htlcs
        assertEquals(ChannelAction.Storage.GetHtlcInfos(bobRevokedTx.commitTx.tx.txid, 4), aliceActions1.find())
        val htlcInfos = listOf(
            ChannelAction.Storage.HtlcInfo(alice0.channelId, 4, htlcsAlice[0].paymentHash, htlcsAlice[0].cltvExpiry),
            ChannelAction.Storage.HtlcInfo(alice0.channelId, 4, htlcsAlice[1].paymentHash, htlcsAlice[1].cltvExpiry),
            ChannelAction.Storage.HtlcInfo(alice0.channelId, 4, htlcsBob[0].paymentHash, htlcsBob[0].cltvExpiry),
            ChannelAction.Storage.HtlcInfo(alice0.channelId, 4, htlcsBob[1].paymentHash, htlcsBob[1].cltvExpiry),
        )
        val (alice2, aliceActions2) = alice1.processEx(ChannelCommand.GetHtlcInfosResponse(bobRevokedTx.commitTx.tx.txid, htlcInfos))
        assertIs<LNChannel<Closing>>(alice2)
        assertNull(aliceActions2.findOutgoingMessageOpt<Error>())
        aliceActions2.has<ChannelAction.Storage.StoreState>()
        aliceActions2.doesNotHave<ChannelAction.Storage.StoreChannelClosing>()

        // alice creates htlc penalty txs and rebroadcasts main txs
        run {
            val revokedCommitPublished = alice2.state.revokedCommitPublished[0]
            assertNotNull(revokedCommitPublished.claimMainOutputTx)
            assertNotNull(revokedCommitPublished.mainPenaltyTx)
            assertEquals(4, revokedCommitPublished.htlcPenaltyTxs.size)
            assertTrue(revokedCommitPublished.claimHtlcDelayedPenaltyTxs.isEmpty())
            revokedCommitPublished.htlcPenaltyTxs.forEach { Transaction.correctlySpends(it.tx, bobRevokedTx.commitTx.tx, ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS) }
            // alice publishes txs for all outputs
            assertEquals(setOf(revokedCommitPublished.claimMainOutputTx!!.tx, revokedCommitPublished.mainPenaltyTx!!.tx) + revokedCommitPublished.htlcPenaltyTxs.map { it.tx }.toSet(), aliceActions2.findTxs().toSet())
            // alice watches confirmation for the commit tx and her main output
            assertEquals(setOf(bobRevokedTx.commitTx.tx.txid, revokedCommitPublished.claimMainOutputTx!!.tx.txid), aliceActions2.findWatches<WatchConfirmed>().map { it.txId }.toSet())
            // alice watches bob's outputs
            val outputsToWatch = buildSet {
                add(revokedCommitPublished.mainPenaltyTx!!.input.outPoint.index)
                addAll(revokedCommitPublished.htlcPenaltyTxs.map { it.input.outPoint.index })
            }
            assertEquals(5, outputsToWatch.size)
            assertEquals(outputsToWatch, aliceActions2.findWatches<WatchSpent>().map { it.outputIndex.toLong() }.toSet())

            // bob manages to claim 2 htlc outputs before alice can penalize him: 1 htlc-success and 1 htlc-timeout.
            val bobHtlcSuccessTx = bobRevokedTx.htlcTxsAndSigs.find { htlcsAlice[0].amountMsat == it.txinfo.input.txOut.amount.toMilliSatoshi() }!!
            val bobHtlcTimeoutTx = bobRevokedTx.htlcTxsAndSigs.find { htlcsBob[1].amountMsat == it.txinfo.input.txOut.amount.toMilliSatoshi() }!!
            val bobOutpoints = listOf(bobHtlcSuccessTx, bobHtlcTimeoutTx).map { it.txinfo.input.outPoint }.toSet()
            assertEquals(2, bobOutpoints.size)

            // alice reacts by publishing penalty txs that spend bob's htlc transactions
            val (alice3, actions3) = alice2.processEx(ChannelCommand.WatchReceived(WatchEventSpent(alice0.channelId, BITCOIN_OUTPUT_SPENT, bobHtlcSuccessTx.txinfo.tx)))
            assertIs<LNChannel<Closing>>(alice3)
            assertEquals(4, actions3.size)
            assertEquals(1, alice3.state.revokedCommitPublished[0].claimHtlcDelayedPenaltyTxs.size)
            assertTrue(actions3.contains(ChannelAction.Storage.StoreState(alice3.state)))
            assertEquals(WatchConfirmed(alice0.channelId, bobHtlcSuccessTx.txinfo.tx, 3, BITCOIN_TX_CONFIRMED(bobHtlcSuccessTx.txinfo.tx)), actions3.findWatch())
            actions3.hasTx(alice3.state.revokedCommitPublished[0].claimHtlcDelayedPenaltyTxs[0].tx)
            assertEquals(WatchSpent(alice0.channelId, bobHtlcSuccessTx.txinfo.tx, alice3.state.revokedCommitPublished[0].claimHtlcDelayedPenaltyTxs[0].input.outPoint.index.toInt(), BITCOIN_OUTPUT_SPENT), actions3.findWatch())

            val (alice4, actions4) = alice3.processEx(ChannelCommand.WatchReceived(WatchEventSpent(alice0.channelId, BITCOIN_OUTPUT_SPENT, bobHtlcTimeoutTx.txinfo.tx)))
            assertIs<LNChannel<Closing>>(alice4)
            assertEquals(4, actions4.size)
            assertEquals(2, alice4.state.revokedCommitPublished[0].claimHtlcDelayedPenaltyTxs.size)
            assertTrue(actions4.contains(ChannelAction.Storage.StoreState(alice4.state)))
            assertEquals(WatchConfirmed(alice0.channelId, bobHtlcTimeoutTx.txinfo.tx, 3, BITCOIN_TX_CONFIRMED(bobHtlcTimeoutTx.txinfo.tx)), actions4.findWatch())
            actions4.hasTx(alice4.state.revokedCommitPublished[0].claimHtlcDelayedPenaltyTxs[1].tx)
            assertEquals(WatchSpent(alice0.channelId, bobHtlcTimeoutTx.txinfo.tx, alice4.state.revokedCommitPublished[0].claimHtlcDelayedPenaltyTxs[1].input.outPoint.index.toInt(), BITCOIN_OUTPUT_SPENT), actions4.findWatch())

            val claimHtlcDelayedPenaltyTxs = alice4.state.revokedCommitPublished[0].claimHtlcDelayedPenaltyTxs
            val remainingHtlcPenaltyTxs = revokedCommitPublished.htlcPenaltyTxs.filterNot { bobOutpoints.contains(it.input.outPoint) }
            assertEquals(2, remainingHtlcPenaltyTxs.size)
            val watchConfirmed = listOf(
                WatchEventConfirmed(alice0.channelId, BITCOIN_TX_CONFIRMED(revokedCommitPublished.commitTx), 42, 0, revokedCommitPublished.commitTx),
                WatchEventConfirmed(alice0.channelId, BITCOIN_TX_CONFIRMED(revokedCommitPublished.claimMainOutputTx!!.tx), 43, 0, revokedCommitPublished.claimMainOutputTx!!.tx),
                WatchEventConfirmed(alice0.channelId, BITCOIN_TX_CONFIRMED(revokedCommitPublished.mainPenaltyTx!!.tx), 43, 5, revokedCommitPublished.mainPenaltyTx!!.tx),
                WatchEventConfirmed(alice0.channelId, BITCOIN_TX_CONFIRMED(remainingHtlcPenaltyTxs[1].tx), 50, 1, remainingHtlcPenaltyTxs[1].tx),
                WatchEventConfirmed(alice0.channelId, BITCOIN_TX_CONFIRMED(bobHtlcSuccessTx.txinfo.tx), 50, 2, bobHtlcSuccessTx.txinfo.tx),
                WatchEventConfirmed(alice0.channelId, BITCOIN_TX_CONFIRMED(remainingHtlcPenaltyTxs[0].tx), 50, 3, remainingHtlcPenaltyTxs[0].tx),
                WatchEventConfirmed(alice0.channelId, BITCOIN_TX_CONFIRMED(claimHtlcDelayedPenaltyTxs[0].tx), 51, 3, claimHtlcDelayedPenaltyTxs[0].tx),
                WatchEventConfirmed(alice0.channelId, BITCOIN_TX_CONFIRMED(bobHtlcTimeoutTx.txinfo.tx), 51, 0, bobHtlcTimeoutTx.txinfo.tx),
                WatchEventConfirmed(alice0.channelId, BITCOIN_TX_CONFIRMED(claimHtlcDelayedPenaltyTxs[1].tx), 51, 1, claimHtlcDelayedPenaltyTxs[1].tx),
            )
            confirmWatchedTxs(alice4, watchConfirmed)
        }
    }

    @Test
    fun `recv BITCOIN_OUTPUT_SPENT -- one revoked tx + counterparty published aggregated htlc tx`() {
        val (alice0, _, bobCommitTxs, htlcsAlice, htlcsBob) = prepareRevokedClose()

        // bob publishes one of his revoked txs
        val bobRevokedTx = bobCommitTxs[2]
        assertEquals(8, bobRevokedTx.commitTx.tx.txOut.size)

        val (alice1, _) = alice0.processEx(ChannelCommand.WatchReceived(WatchEventSpent(alice0.channelId, BITCOIN_FUNDING_SPENT, bobRevokedTx.commitTx.tx)))
        assertIs<LNChannel<Closing>>(alice1)

        // alice fetches information about the revoked htlcs
        val htlcInfos = listOf(
            ChannelAction.Storage.HtlcInfo(alice0.channelId, 4, htlcsAlice[0].paymentHash, htlcsAlice[0].cltvExpiry),
            ChannelAction.Storage.HtlcInfo(alice0.channelId, 4, htlcsAlice[1].paymentHash, htlcsAlice[1].cltvExpiry),
            ChannelAction.Storage.HtlcInfo(alice0.channelId, 4, htlcsBob[0].paymentHash, htlcsBob[0].cltvExpiry),
            ChannelAction.Storage.HtlcInfo(alice0.channelId, 4, htlcsBob[1].paymentHash, htlcsBob[1].cltvExpiry),
        )
        val (alice2, _) = alice1.processEx(ChannelCommand.GetHtlcInfosResponse(bobRevokedTx.commitTx.tx.txid, htlcInfos))
        assertIs<LNChannel<Closing>>(alice2)

        // bob claims multiple htlc outputs in a single transaction (this is possible with anchor outputs because signatures
        // use sighash_single | sighash_anyonecanpay)
        assertEquals(4, bobRevokedTx.htlcTxsAndSigs.size)
        val bobHtlcTx = Transaction(
            2,
            listOf(
                TxIn(OutPoint(Lightning.randomBytes32(), 4), listOf(), 1), // unrelated utxo (maybe used for fee bumping)
                bobRevokedTx.htlcTxsAndSigs[0].txinfo.tx.txIn.first(),
                bobRevokedTx.htlcTxsAndSigs[1].txinfo.tx.txIn.first(),
                bobRevokedTx.htlcTxsAndSigs[2].txinfo.tx.txIn.first(),
                bobRevokedTx.htlcTxsAndSigs[3].txinfo.tx.txIn.first(),
            ),
            listOf(
                TxOut(10_000.sat, listOf()), // unrelated output (maybe change output)
                bobRevokedTx.htlcTxsAndSigs[0].txinfo.tx.txOut.first(),
                bobRevokedTx.htlcTxsAndSigs[1].txinfo.tx.txOut.first(),
                bobRevokedTx.htlcTxsAndSigs[2].txinfo.tx.txOut.first(),
                bobRevokedTx.htlcTxsAndSigs[3].txinfo.tx.txOut.first(),
            ),
            0
        )

        val (alice3, actions3) = alice2.processEx(ChannelCommand.WatchReceived(WatchEventSpent(alice0.channelId, BITCOIN_OUTPUT_SPENT, bobHtlcTx)))
        assertIs<LNChannel<Closing>>(alice3)
        assertEquals(10, actions3.size)
        assertEquals(4, alice3.state.revokedCommitPublished[0].claimHtlcDelayedPenaltyTxs.size)
        val claimHtlcDelayedPenaltyTxs = alice3.state.revokedCommitPublished[0].claimHtlcDelayedPenaltyTxs
        claimHtlcDelayedPenaltyTxs.forEach { Transaction.correctlySpends(it.tx, bobHtlcTx, ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS) }
        assertEquals(setOf(OutPoint(bobHtlcTx, 1), OutPoint(bobHtlcTx, 2), OutPoint(bobHtlcTx, 3), OutPoint(bobHtlcTx, 4)), claimHtlcDelayedPenaltyTxs.map { it.input.outPoint }.toSet())
        assertTrue(actions3.contains(ChannelAction.Storage.StoreState(alice3.state)))
        assertEquals(WatchConfirmed(alice0.channelId, bobHtlcTx, 3, BITCOIN_TX_CONFIRMED(bobHtlcTx)), actions3.findWatch())
        actions3.hasTx(claimHtlcDelayedPenaltyTxs[0].tx)
        actions3.hasTx(claimHtlcDelayedPenaltyTxs[1].tx)
        actions3.hasTx(claimHtlcDelayedPenaltyTxs[2].tx)
        actions3.hasTx(claimHtlcDelayedPenaltyTxs[3].tx)
        val watchSpent = actions3.findWatches<WatchSpent>().toSet()
        val expected = setOf(
            WatchSpent(alice0.channelId, bobHtlcTx, 1, BITCOIN_OUTPUT_SPENT),
            WatchSpent(alice0.channelId, bobHtlcTx, 2, BITCOIN_OUTPUT_SPENT),
            WatchSpent(alice0.channelId, bobHtlcTx, 3, BITCOIN_OUTPUT_SPENT),
            WatchSpent(alice0.channelId, bobHtlcTx, 4, BITCOIN_OUTPUT_SPENT),
        )
        assertEquals(expected, watchSpent)
    }

    @Test
    fun `recv ChannelReestablish`() {
        val (alice0, bob0, _) = initMutualClose()
        val bobCurrentPerCommitmentPoint = bob0.ctx.keyManager.commitmentPoint(
            bob0.ctx.keyManager.channelKeyPath(bob0.commitments.localParams, bob0.commitments.channelConfig),
            bob0.commitments.localCommit.index
        )
        val channelReestablish = ChannelReestablish(bob0.channelId, 42, 42, PrivateKey(ByteVector32.Zeroes), bobCurrentPerCommitmentPoint)
        val (alice1, actions1) = alice0.processEx(ChannelCommand.MessageReceived(channelReestablish))
        assertIs<LNChannel<Closing>>(alice1)
        assertNull(alice1.state.localCommitPublished)
        assertNull(alice1.state.remoteCommitPublished)
        assertTrue(alice1.state.mutualClosePublished.isNotEmpty())
        val error = actions1.hasOutgoingMessage<Error>()
        assertEquals(error.toAscii(), FundingTxSpent(alice0.channelId, alice0.state.mutualClosePublished.first().tx.txid).message)
    }

    @Test
    fun `recv CMD_CLOSE`() {
        val (alice0, _, _) = initMutualClose()
        val cmdClose = CMD_CLOSE(null, null)
        val (_, actions) = alice0.processEx(ChannelCommand.ExecuteCommand(cmdClose))
        val commandError = actions.filterIsInstance<ChannelAction.ProcessCmdRes.NotExecuted>().first()
        assertEquals(cmdClose, commandError.cmd)
        assertEquals(ClosingAlreadyInProgress(alice0.channelId), commandError.t)
    }

    @Test
    fun `recv NewBlock -- an htlc timed out`() {
        val (alice, bob) = reachNormal()
        val (aliceClosing, htlc) = run {
            val (nodes1, _, htlc) = addHtlc(30_000_000.msat, alice, bob)
            val (alice1, bob1) = nodes1
            val (alice2, _) = crossSign(alice1, bob1)
            val (alice3, _) = localClose(alice2)
            Pair(alice3, htlc)
        }

        run {
            val alice1 = aliceClosing.copy(ctx = alice.ctx.copy(currentTip = aliceClosing.ctx.currentTip.copy(first = htlc.cltvExpiry.toLong().toInt())))
            val (alice2, actions) = alice1.processEx(ChannelCommand.CheckHtlcTimeout)
            assertEquals(alice1, alice2)
            assertTrue(actions.isEmpty())
        }
    }

    @Test
    fun `recv Disconnected`() {
        val (alice0, _, _) = initMutualClose()
        val (alice1, _) = alice0.processEx(ChannelCommand.Disconnected)
        assertTrue { alice1.state is Offline }
    }

    companion object {
        fun initMutualClose(withPayments: Boolean = false): Triple<LNChannel<Closing>, LNChannel<Closing>, List<PublishableTxs>> {
            val (aliceInit, bobInit) = reachNormal()
            var mutableAlice: LNChannel<Normal> = aliceInit
            var mutableBob: LNChannel<Normal> = bobInit

            val bobCommitTxs = if (!withPayments) {
                listOf()
            } else {
                listOf(100_000_000.msat, 200_000_000.msat, 300_000_000.msat).map { amount ->
                    val (nodes, r, htlc) = addHtlc(amount, payer = mutableAlice, payee = mutableBob)
                    mutableAlice = nodes.first
                    mutableBob = nodes.second

                    with(crossSign(mutableAlice, mutableBob)) {
                        mutableAlice = first
                        mutableBob = second
                    }

                    val bobCommitTx1 = mutableBob.commitments.localCommit.publishableTxs

                    with(fulfillHtlc(htlc.id, r, payer = mutableAlice, payee = mutableBob)) {
                        mutableAlice = first
                        mutableBob = second
                    }
                    with(crossSign(mutableBob, mutableAlice)) {
                        mutableBob = first
                        mutableAlice = second
                    }

                    val bobCommitTx2 = mutableBob.commitments.localCommit.publishableTxs
                    listOf(bobCommitTx1, bobCommitTx2)
                }.flatten()
            }

            val (alice1, bob1, aliceCloseSig) = mutualCloseAlice(mutableAlice, mutableBob)
            val (alice2, bob2) = NegotiatingTestsCommon.converge(alice1, bob1, aliceCloseSig) ?: error("converge should not return null")

            return Triple(alice2, bob2, bobCommitTxs)
        }

        data class RevokedCloseFixture(val alice: LNChannel<Normal>, val bob: LNChannel<Normal>, val bobRevokedTxs: List<PublishableTxs>, val htlcsAlice: List<UpdateAddHtlc>, val htlcsBob: List<UpdateAddHtlc>)

        fun prepareRevokedClose(): RevokedCloseFixture {
            val (aliceInit, bobInit) = reachNormal()
            var mutableAlice: LNChannel<Normal> = aliceInit
            var mutableBob: LNChannel<Normal> = bobInit

            // Bob's first commit tx doesn't contain any htlc
            val commitTx1 = bobInit.commitments.localCommit.publishableTxs
            assertEquals(4, commitTx1.commitTx.tx.txOut.size) // 2 main outputs + 2 anchors

            // Bob's second commit tx contains 1 incoming htlc and 1 outgoing htlc
            val (commitTx2, htlcAlice1, htlcBob1) = run {
                val (nodes1, _, htlcAlice) = addHtlc(35_000_000.msat, mutableAlice, mutableBob)
                mutableAlice = nodes1.first
                mutableBob = nodes1.second

                with(crossSign(mutableAlice, mutableBob)) {
                    mutableAlice = first
                    mutableBob = second
                }

                val (nodes2, _, htlcBob) = addHtlc(20_000_000.msat, mutableBob, mutableAlice)
                mutableBob = nodes2.first
                mutableAlice = nodes2.second

                with(crossSign(mutableBob, mutableAlice)) {
                    mutableBob = first
                    mutableAlice = second
                }

                val commitTx = mutableBob.commitments.localCommit.publishableTxs
                Triple(commitTx, htlcAlice, htlcBob)
            }
            assertEquals(6, commitTx2.commitTx.tx.txOut.size)
            assertEquals(6, mutableAlice.commitments.localCommit.publishableTxs.commitTx.tx.txOut.size)

            // Bob's third commit tx contains 2 incoming htlcs and 2 outgoing htlcs
            val (commitTx3, htlcAlice2, htlcBob2) = run {
                val (nodes1, _, htlcAlice) = addHtlc(25_000_000.msat, mutableAlice, mutableBob)
                mutableAlice = nodes1.first
                mutableBob = nodes1.second

                with(crossSign(mutableAlice, mutableBob)) {
                    mutableAlice = first
                    mutableBob = second
                }

                val (nodes2, _, htlcBob) = addHtlc(18_000_000.msat, mutableBob, mutableAlice)
                mutableBob = nodes2.first
                mutableAlice = nodes2.second

                with(crossSign(mutableBob, mutableAlice)) {
                    mutableBob = first
                    mutableAlice = second
                }

                val commitTx = mutableBob.commitments.localCommit.publishableTxs
                Triple(commitTx, htlcAlice, htlcBob)
            }
            assertEquals(8, commitTx3.commitTx.tx.txOut.size)
            assertEquals(8, mutableAlice.commitments.localCommit.publishableTxs.commitTx.tx.txOut.size)

            // Bob's fourth commit tx doesn't contain any htlc
            val commitTx4 = run {
                listOf(htlcAlice1, htlcAlice2).forEach { htlcAlice ->
                    val nodes = failHtlc(htlcAlice.id, mutableAlice, mutableBob)
                    mutableAlice = nodes.first
                    mutableBob = nodes.second
                }
                listOf(htlcBob1, htlcBob2).forEach { htlcBob ->
                    val nodes = failHtlc(htlcBob.id, mutableBob, mutableAlice)
                    mutableBob = nodes.first
                    mutableAlice = nodes.second
                }
                with(crossSign(mutableAlice, mutableBob)) {
                    mutableAlice = first
                    mutableBob = second
                }
                mutableBob.commitments.localCommit.publishableTxs
            }
            assertEquals(4, commitTx4.commitTx.tx.txOut.size)
            assertEquals(4, mutableAlice.commitments.localCommit.publishableTxs.commitTx.tx.txOut.size)

            return RevokedCloseFixture(mutableAlice, mutableBob, listOf(commitTx1, commitTx2, commitTx3, commitTx4), listOf(htlcAlice1, htlcAlice2), listOf(htlcBob1, htlcBob2))
        }

        private fun confirmWatchedTxs(firstClosingState: LNChannel<Closing>, watchConfirmed: List<WatchEventConfirmed>) {
            var alice = firstClosingState
            watchConfirmed.dropLast(1).forEach {
                val (aliceNew, actions) = alice.processEx(ChannelCommand.WatchReceived(it))
                assertIs<LNChannel<Closing>>(aliceNew)
                assertTrue(actions.contains(ChannelAction.Storage.StoreState(aliceNew.state)))
                // The only other possible actions are for settling htlcs
                assertEquals(actions.size - 1, actions.count { action -> action is ChannelAction.ProcessCmdRes })
                alice = aliceNew
            }

            val (aliceClosed, actions) = alice.processEx(ChannelCommand.WatchReceived(watchConfirmed.last()))
            assertIs<LNChannel<Closed>>(aliceClosed)
            assertTrue(actions.contains(ChannelAction.Storage.StoreState(aliceClosed.state)))
            // The only other possible actions are for settling htlcs & ChannelClosing
            assertEquals(actions.size - 1, actions.count { action ->
                when (action) {
                    is ChannelAction.ProcessCmdRes -> true
                    is ChannelAction.Storage.StoreChannelClosing -> true
                    is ChannelAction.Storage.StoreChannelClosed -> true
                    else -> false
                }
            })
        }
    }

}
