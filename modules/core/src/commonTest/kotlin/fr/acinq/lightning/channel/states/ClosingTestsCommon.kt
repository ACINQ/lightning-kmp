package fr.acinq.lightning.channel.states

import fr.acinq.bitcoin.*
import fr.acinq.lightning.CltvExpiryDelta
import fr.acinq.lightning.Lightning
import fr.acinq.lightning.blockchain.WatchConfirmed
import fr.acinq.lightning.blockchain.WatchConfirmedTriggered
import fr.acinq.lightning.blockchain.WatchSpent
import fr.acinq.lightning.blockchain.WatchSpentTriggered
import fr.acinq.lightning.channel.*
import fr.acinq.lightning.channel.TestsHelper.addHtlc
import fr.acinq.lightning.channel.TestsHelper.crossSign
import fr.acinq.lightning.channel.TestsHelper.failHtlc
import fr.acinq.lightning.channel.TestsHelper.fulfillHtlc
import fr.acinq.lightning.channel.TestsHelper.localClose
import fr.acinq.lightning.channel.TestsHelper.makeCmdAdd
import fr.acinq.lightning.channel.TestsHelper.reachNormal
import fr.acinq.lightning.channel.TestsHelper.remoteClose
import fr.acinq.lightning.channel.TestsHelper.useAlternativeCommitSig
import fr.acinq.lightning.db.ChannelCloseOutgoingPayment.ChannelClosingType
import fr.acinq.lightning.tests.TestConstants
import fr.acinq.lightning.tests.utils.LightningTestSuite
import fr.acinq.lightning.transactions.Transactions
import fr.acinq.lightning.utils.UUID
import fr.acinq.lightning.utils.msat
import fr.acinq.lightning.utils.sat
import fr.acinq.lightning.utils.toMilliSatoshi
import fr.acinq.lightning.wire.*
import kotlin.test.*

class ClosingTestsCommon : LightningTestSuite() {

    @Test
    fun `recv ChannelCommand_Htlc_Add`() {
        val (alice, _) = reachNormal()
        val (alice1, _) = localClose(alice)
        val (_, actions) = alice1.process(
            ChannelCommand.Htlc.Add(
                1000000.msat,
                ByteVector32.Zeroes,
                CltvExpiryDelta(144).toCltvExpiry(alice.currentBlockHeight.toLong()),
                TestConstants.emptyOnionPacket,
                UUID.randomUUID()
            )
        )
        assertEquals(1, actions.size)
        assertEquals((actions.first() as ChannelAction.ProcessCmdRes.AddFailed).error, ChannelUnavailable(alice.state.channelId))
    }

    @Test
    fun `recv ChannelFundingDepthOk`() {
        val (alice, bob, _) = WaitForFundingConfirmedTestsCommon.init()
        val fundingTx = alice.state.latestFundingTx.sharedTx.tx.buildUnsignedTx()
        run {
            val (aliceClosing, _) = localClose(alice)
            val (_, _) = aliceClosing.process(ChannelCommand.WatchReceived(WatchConfirmedTriggered(alice.state.channelId, WatchConfirmed.ChannelFundingDepthOk, 561, 3, fundingTx)))
                .also { (state, actions) ->
                    assertEquals(1, state.commitments.active.size)
                    actions.has<ChannelAction.Storage.StoreState>()
                    actions.hasWatchFundingSpent(fundingTx.txid)
                }
        }
        run {
            val (bobClosing, _) = localClose(bob)
            val (_, _) = bobClosing.process(ChannelCommand.WatchReceived(WatchConfirmedTriggered(bob.state.channelId, WatchConfirmed.ChannelFundingDepthOk, 561, 3, fundingTx)))
                .also { (state, actions) ->
                    assertEquals(1, state.commitments.active.size)
                    assertIs<LocalFundingStatus.ConfirmedFundingTx>(state.commitments.latest.localFundingStatus)
                    actions.has<ChannelAction.Storage.StoreState>()
                    assertEquals(actions.findWatch<WatchSpent>().txId, fundingTx.txid)
                }
        }
    }

    @Test
    fun `recv ChannelFundingDepthOk -- previous funding tx`() {
        val (alice, bob, previousFundingTx, walletAlice) = WaitForFundingConfirmedTestsCommon.init()
        val (alice1, bob1, fundingTx) = WaitForFundingConfirmedTestsCommon.rbf(alice, bob, walletAlice)
        assertNotEquals(previousFundingTx.txid, fundingTx.txid)
        run {
            val (aliceClosing, localCommitPublished) = localClose(alice1)
            assertEquals(fundingTx.txid, aliceClosing.commitments.latest.fundingTxId)
            assertEquals(2, aliceClosing.commitments.active.size)
            val (_, _) = aliceClosing.process(ChannelCommand.WatchReceived(WatchConfirmedTriggered(alice.state.channelId, WatchConfirmed.ChannelFundingDepthOk, 561, 3, previousFundingTx)))
                .also { (state, actions) ->
                    assertIs<Closing>(state.state)
                    assertEquals(previousFundingTx.txid, state.commitments.latest.fundingTxId)
                    assertEquals(1, state.commitments.active.size) // the other funding tx has been pruned
                    actions.has<ChannelAction.Storage.StoreState>()
                    actions.hasWatchFundingSpent(previousFundingTx.txid)
                    assertEquals(actions.findPublishTxs().size, 2) // commit tx and claim main
                    val localCommitPublished2 = state.state.localCommitPublished
                    assertNotNull(localCommitPublished2)
                    assertNotEquals(localCommitPublished.commitTx.txid, localCommitPublished2.commitTx.txid)
                }
        }
        run {
            val (bobClosing, localCommitPublished) = localClose(bob1)
            assertEquals(fundingTx.txid, bobClosing.commitments.latest.fundingTxId)
            assertEquals(2, bobClosing.commitments.active.size)
            val (_, _) = bobClosing.process(ChannelCommand.WatchReceived(WatchConfirmedTriggered(bob.state.channelId, WatchConfirmed.ChannelFundingDepthOk, 561, 3, previousFundingTx)))
                .also { (state, actions) ->
                    assertIs<Closing>(state.state)
                    assertEquals(state.commitments.latest.fundingTxId, previousFundingTx.txid)
                    assertEquals(state.commitments.active.size, 1)
                    actions.has<ChannelAction.Storage.StoreState>()
                    actions.hasWatchFundingSpent(previousFundingTx.txid)
                    assertEquals(actions.findPublishTxs().size, 2) // commit tx and claim main
                    val localCommitPublished2 = state.state.localCommitPublished
                    assertNotNull(localCommitPublished2)
                    assertNotEquals(localCommitPublished.commitTx.txid, localCommitPublished2.commitTx.txid)
                }
        }
    }

    @Test
    fun `recv ChannelSpent -- local commit`() {
        val (aliceNormal, _) = reachNormal()
        val (aliceClosing, localCommitPublished) = localClose(aliceNormal)

        // actual test starts here
        // we are notified afterwards from our watcher about the tx that we just published
        val (alice1, actions1) = aliceClosing.process(ChannelCommand.WatchReceived(WatchSpentTriggered(aliceNormal.state.channelId, WatchSpent.ChannelSpent(TestConstants.fundingAmount), localCommitPublished.commitTx)))
        assertEquals(aliceClosing, alice1)
        assertTrue(actions1.isEmpty())
    }

    @Test
    fun `recv ClosingTxConfirmed -- local commit`() {
        val (alice0, bob0) = reachNormal()
        // alice sends an htlc to bob
        val (nodes1, _, htlc1) = addHtlc(50_000_000.msat, alice0, bob0)
        val (alice1, bob1) = nodes1
        // alice sends an htlc below dust to bob
        val amountBelowDust = alice0.state.commitments.latest.localCommitParams.dustLimit.toMilliSatoshi() - 100.msat
        val (nodes2, _, htlc2) = addHtlc(amountBelowDust, alice1, bob1)
        val (alice2, bob2) = nodes2
        val (alice3, _) = crossSign(alice2, bob2)
        val (aliceClosing, localCommitPublished, closingTxs) = localClose(alice3, htlcTimeoutCount = 1)

        // actual test starts here
        assertNotNull(localCommitPublished.localOutput)
        assertEquals(1, localCommitPublished.htlcOutputs.size)
        assertEquals(1, closingTxs.htlcTimeoutTxs.size)
        assertTrue(localCommitPublished.htlcDelayedOutputs.isEmpty())

        // The commit tx confirms.
        val (aliceClosing1, actions1) = aliceClosing.process(ChannelCommand.WatchReceived(WatchConfirmedTriggered(alice0.channelId, WatchConfirmed.ClosingTxConfirmed, 42, 6, localCommitPublished.commitTx)))
        assertIs<LNChannel<Closing>>(aliceClosing1)
        assertEquals(2, actions1.size)
        actions1.has<ChannelAction.Storage.StoreState>()
        assertEquals(setOf(htlc2), actions1.filterIsInstance<ChannelAction.ProcessCmdRes.AddSettledFail>().map { it.htlc }.toSet())
        actions1.filterIsInstance<ChannelAction.ProcessCmdRes.AddSettledFail>().forEach { assertIs<ChannelAction.HtlcResult.Fail.OnChainFail>(it.result) }
        assertNotNull(aliceClosing1.state.localCommitPublished)
        assertTrue(aliceClosing1.state.localCommitPublished.isConfirmed)
        assertFalse(aliceClosing1.state.localCommitPublished.isDone)

        // Our main transaction confirms.
        val (aliceClosing2, actions2) = aliceClosing1.process(ChannelCommand.WatchReceived(WatchConfirmedTriggered(alice0.channelId, WatchConfirmed.ClosingTxConfirmed, 45, 0, closingTxs.mainTx)))
        assertIs<LNChannel<Closing>>(aliceClosing2)
        assertEquals(1, actions2.size)
        actions2.has<ChannelAction.Storage.StoreState>()

        // Our HTLC-timeout transaction confirms.
        val (aliceClosing3, actions3) = aliceClosing2.process(ChannelCommand.WatchReceived(WatchConfirmedTriggered(alice0.channelId, WatchConfirmed.ClosingTxConfirmed, 45, 1, closingTxs.htlcTimeoutTxs.first())))
        assertIs<LNChannel<Closing>>(aliceClosing3)
        assertEquals(4, actions3.size)
        actions3.has<ChannelAction.Storage.StoreState>()
        assertEquals(setOf(htlc1), actions3.filterIsInstance<ChannelAction.ProcessCmdRes.AddSettledFail>().map { it.htlc }.toSet())
        actions3.filterIsInstance<ChannelAction.ProcessCmdRes.AddSettledFail>().forEach { assertIs<ChannelAction.HtlcResult.Fail.OnChainFail>(it.result) }
        val htlcDelayedTx = actions3.hasPublishTx(ChannelAction.Blockchain.PublishTx.Type.HtlcDelayedTx)
        assertNotNull(aliceClosing3.state.localCommitPublished)
        assertEquals(setOf(htlcDelayedTx.txIn.first().outPoint), aliceClosing3.state.localCommitPublished.htlcDelayedOutputs)
        assertEquals(setOf(localCommitPublished.commitTx, closingTxs.mainTx, closingTxs.htlcTimeoutTxs.first()), aliceClosing3.state.localCommitPublished.irrevocablySpent.values.toSet())
        assertFalse(aliceClosing3.state.localCommitPublished.isDone)
        actions3.hasWatchOutputSpent(htlcDelayedTx.txIn.first().outPoint)

        // Our HTLC-delayed transaction confirms.
        val (aliceClosing4, actions4) = aliceClosing3.process(ChannelCommand.WatchReceived(WatchSpentTriggered(alice0.channelId, WatchSpent.ClosingOutputSpent(50_000.sat), htlcDelayedTx)))
        assertIs<LNChannel<Closing>>(aliceClosing3)
        assertEquals(1, actions4.size)
        actions4.hasWatchConfirmed(htlcDelayedTx.txid)
        val (aliceClosing5, actions5) = aliceClosing4.process(ChannelCommand.WatchReceived(WatchConfirmedTriggered(alice0.channelId, WatchConfirmed.ClosingTxConfirmed, 51, 113, htlcDelayedTx)))
        assertIs<Closed>(aliceClosing5.state)
        assertEquals(2, actions5.size)
        actions5.has<ChannelAction.Storage.StoreState>()
        assertContains(actions5, ChannelAction.Storage.SetLocked(localCommitPublished.commitTx.txid))
    }

    @Test
    fun `recv ClosingTxConfirmed -- local commit -- non-initiator pays commit fees`() {
        val (alice0, bob0) = reachNormal(requestRemoteFunding = TestConstants.bobFundingAmount)
        assertFalse(alice0.commitments.channelParams.localParams.paysCommitTxFees)
        assertTrue(bob0.commitments.channelParams.localParams.paysCommitTxFees)
        val (alice1, localCommitPublished, closingTxs) = localClose(alice0)
        val (alice2, _) = alice1.process(ChannelCommand.WatchReceived(WatchConfirmedTriggered(alice0.channelId, WatchConfirmed.ClosingTxConfirmed, 42, 7, localCommitPublished.commitTx)))
        val (alice3, actions3) = alice2.process(ChannelCommand.WatchReceived(WatchConfirmedTriggered(alice0.state.channelId, WatchConfirmed.ClosingTxConfirmed, 43, 3, closingTxs.mainTx)))
        assertIs<Closed>(alice3.state)
        assertEquals(2, actions3.size)
        actions3.has<ChannelAction.Storage.StoreState>()
        actions3.find<ChannelAction.Storage.SetLocked>().also { assertEquals(localCommitPublished.commitTx.txid, it.txId) }
    }

    @Test
    fun `recv ClosingTxConfirmed -- local commit with multiple htlcs for the same payment`() {
        val (alice0, bob0) = reachNormal()
        // alice sends an htlc to bob
        val (aliceClosing, localCommitPublished, closingTxs) = run {
            val (nodes1, preimage, _) = addHtlc(30_000_000.msat, alice0, bob0)
            val (alice1, bob1) = nodes1
            // and more htlcs with the same payment_hash
            val (_, cmd2) = makeCmdAdd(25_000_000.msat, bob0.staticParams.nodeParams.nodeId, alice1.currentBlockHeight.toLong(), preimage)
            val (alice2, bob2, _) = addHtlc(cmd2, alice1, bob1)
            val (_, cmd3) = makeCmdAdd(30_000_000.msat, bob0.staticParams.nodeParams.nodeId, alice2.currentBlockHeight.toLong(), preimage)
            val (alice3, bob3, _) = addHtlc(cmd3, alice2, bob2)
            val amountBelowDust = alice0.state.commitments.latest.localCommitParams.dustLimit.toMilliSatoshi() - 100.msat
            val (_, dustCmd) = makeCmdAdd(amountBelowDust, bob0.staticParams.nodeParams.nodeId, alice3.currentBlockHeight.toLong(), preimage)
            val (alice4, bob4, _) = addHtlc(dustCmd, alice3, bob3)
            val (_, cmd4) = makeCmdAdd(20_000_000.msat, bob0.staticParams.nodeParams.nodeId, alice4.currentBlockHeight.toLong() + 1, preimage)
            val (alice5, bob5, _) = addHtlc(cmd4, alice4, bob4)
            val (alice6, _) = crossSign(alice5, bob5)
            localClose(alice6, htlcTimeoutCount = 4)
        }

        // actual test starts here
        assertNotNull(localCommitPublished.localOutput)
        assertTrue(closingTxs.htlcSuccessTxs.isEmpty())
        assertEquals(4, closingTxs.htlcTimeoutTxs.size)

        // if commit tx and htlc-timeout txs end up in the same block, we may receive the htlc-timeout confirmation before the commit tx confirmation
        val watchConfirmed = listOf(
            WatchConfirmedTriggered(alice0.state.channelId, WatchConfirmed.ClosingTxConfirmed, 42, 0, closingTxs.htlcTimeoutTxs[2]),
            WatchConfirmedTriggered(alice0.state.channelId, WatchConfirmed.ClosingTxConfirmed, 42, 1, localCommitPublished.commitTx),
            WatchConfirmedTriggered(alice0.state.channelId, WatchConfirmed.ClosingTxConfirmed, 200, 0, closingTxs.mainTx),
            WatchConfirmedTriggered(alice0.state.channelId, WatchConfirmed.ClosingTxConfirmed, 202, 0, closingTxs.htlcTimeoutTxs[1]),
            WatchConfirmedTriggered(alice0.state.channelId, WatchConfirmed.ClosingTxConfirmed, 202, 1, closingTxs.htlcTimeoutTxs[0]),
            WatchConfirmedTriggered(alice0.state.channelId, WatchConfirmed.ClosingTxConfirmed, 203, 0, closingTxs.htlcTimeoutTxs[3]),
        )
        confirmClosingTxs(aliceClosing, watchConfirmed)
    }

    @Test
    fun `recv ClosingTxConfirmed -- local commit with htlcs only signed by local`() {
        val (alice0, bob0) = reachNormal()
        val aliceCommitTx = alice0.signCommitTx()
        val (aliceClosing, localCommitPublished, add) = run {
            // alice sends an htlc to bob
            val (nodes1, _, add) = addHtlc(50_000_000.msat, alice0, bob0)
            val alice1 = nodes1.first
            // alice signs it, but bob doesn't receive the signature
            val (alice2, actions2) = alice1.process(ChannelCommand.Commitment.Sign)
            actions2.hasOutgoingMessage<CommitSig>()
            val (aliceClosing, localCommitPublished) = localClose(alice2)
            Triple(aliceClosing, localCommitPublished, add)
        }

        assertEquals(aliceCommitTx, localCommitPublished.commitTx)
        assertTrue(localCommitPublished.htlcOutputs.isEmpty())

        val (alice1, actions1) = aliceClosing.process(ChannelCommand.WatchReceived(WatchConfirmedTriggered(alice0.channelId, WatchConfirmed.ClosingTxConfirmed, 42, 1, aliceCommitTx)))
        assertIs<Closing>(alice1.state)
        // when the commit tx is confirmed, alice knows that the htlc she sent right before the unilateral close will never reach the chain, so she fails it
        assertEquals(2, actions1.size)
        assertTrue(actions1.contains(ChannelAction.Storage.StoreState(alice1.state)))
        val addFailed = actions1.filterIsInstance<ChannelAction.ProcessCmdRes.AddSettledFail>().firstOrNull()
        assertNotNull(addFailed)
        assertEquals(add, addFailed.htlc)
        assertTrue(addFailed.result is ChannelAction.HtlcResult.Fail.OnChainFail)
    }

    @Test
    fun `recv ClosingTxConfirmed -- local commit with fulfill only signed by local`() {
        val (alice0, bob0) = reachNormal()
        // Bob sends an htlc to Alice.
        val (nodes1, r, htlc) = addHtlc(110_000_000.msat, bob0, alice0)
        val (bob1, alice1) = crossSign(nodes1.first, nodes1.second)
        val aliceCommitTx = alice1.signCommitTx()
        assertEquals(5, aliceCommitTx.txOut.size) // 2 main outputs + 2 anchors + 1 htlc

        // Alice fulfills the HTLC but Bob doesn't receive the signature.
        val (alice2, actionsAlice2) = alice1.process(ChannelCommand.Htlc.Settlement.Fulfill(htlc.id, r, commit = true))
        assertEquals(2, actionsAlice2.size)
        val fulfill = actionsAlice2.hasOutgoingMessage<UpdateFulfillHtlc>()
        actionsAlice2.hasCommand<ChannelCommand.Commitment.Sign>()
        val (_, actionsBob2) = bob1.process(ChannelCommand.MessageReceived(fulfill))
        assertEquals(1, actionsBob2.size)
        actionsBob2.find<ChannelAction.ProcessCmdRes.AddSettledFulfill>().also {
            assertEquals(htlc, it.htlc)
            assertEquals(r, it.result.paymentPreimage)
        }

        // Then we make Alice unilaterally close the channel.
        val (_, localCommitPublished) = localClose(alice2, htlcSuccessCount = 1)
        assertEquals(aliceCommitTx.txid, localCommitPublished.commitTx.txid)
        assertEquals(1, localCommitPublished.htlcOutputs.size)
    }

    @Test
    fun `recv ClosingTxConfirmed -- local commit -- followed by preimage`() {
        val (alice0, bob0) = reachNormal()
        // An HTLC Bob -> Alice is cross-signed that will be fulfilled later.
        val (nodes1, preimage, htlc) = addHtlc(110_000_000.msat, bob0, alice0)
        val (bob1, alice1) = nodes1
        // An HTLC Alice -> Bob is cross-signed and will timeout later.
        val (nodes2, _, _) = addHtlc(95_000_000.msat, alice1, bob1)
        val (alice2, bob2) = nodes2
        val (alice3, _) = crossSign(alice2, bob2)
        val (aliceClosing, localCommitPublished, closingTxs) = localClose(alice3, htlcTimeoutCount = 1)
        assertNotNull(localCommitPublished.localOutput)
        // we don't have the preimage to claim the htlc-success yet
        assertTrue(closingTxs.htlcSuccessTxs.isEmpty())
        assertEquals(2, localCommitPublished.htlcOutputs.size)

        // Alice receives the preimage for the first HTLC from the payment handler; she can now claim the corresponding HTLC output.
        val (aliceFulfill, actionsFulfill) = aliceClosing.process(ChannelCommand.Htlc.Settlement.Fulfill(htlc.id, preimage, commit = true))
        assertIs<LNChannel<Closing>>(aliceFulfill)
        val htlcSuccessTx = actionsFulfill.hasPublishTx(ChannelAction.Blockchain.PublishTx.Type.HtlcSuccessTx)
        Transaction.correctlySpends(htlcSuccessTx, localCommitPublished.commitTx, ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)

        val watchConfirmed = listOf(
            WatchConfirmedTriggered(alice0.channelId, WatchConfirmed.ClosingTxConfirmed, 42, 1, localCommitPublished.commitTx),
            WatchConfirmedTriggered(alice0.channelId, WatchConfirmed.ClosingTxConfirmed, 210, 0, closingTxs.htlcTimeoutTxs[0]),
            WatchConfirmedTriggered(alice0.channelId, WatchConfirmed.ClosingTxConfirmed, 210, 1, htlcSuccessTx),
            WatchConfirmedTriggered(alice0.channelId, WatchConfirmed.ClosingTxConfirmed, 250, 0, closingTxs.mainTx)
        )
        confirmClosingTxs(aliceFulfill, watchConfirmed)
    }

    @Test
    fun `recv ClosingTxConfirmed -- local commit --  followed by UpdateFailHtlc not acked by remote`() {
        val (alice0, bob0) = reachNormal()
        val (aliceClosing, localCommitPublished, htlc) = run {
            // An HTLC Alice -> Bob is cross-signed that will be failed later.
            val (nodes1, _, htlc) = addHtlc(25_000_000.msat, alice0, bob0)
            val (alice1, bob1) = nodes1
            val (alice2, bob2) = crossSign(alice1, bob1)
            val (alice3, bob3) = failHtlc(htlc.id, alice2, bob2)
            val (bob4, bobActions4) = bob3.process(ChannelCommand.Commitment.Sign)
            val sigBob = bobActions4.hasOutgoingMessage<CommitSig>()
            val (alice4, aliceActions4) = alice3.process(ChannelCommand.MessageReceived(sigBob))
            val revAlice = aliceActions4.hasOutgoingMessage<RevokeAndAck>()
            aliceActions4.hasCommand<ChannelCommand.Commitment.Sign>()
            val (alice5, aliceActions5) = alice4.process(ChannelCommand.Commitment.Sign)
            val sigAlice = aliceActions5.hasOutgoingMessage<CommitSig>()
            val (bob5, _) = bob4.process(ChannelCommand.MessageReceived(revAlice))
            val (_, bobActions6) = bob5.process(ChannelCommand.MessageReceived(sigAlice))
            bobActions6.hasOutgoingMessage<RevokeAndAck>()
            // alice closes before receiving Bob's revocation
            val (aliceClosing, localCommitPublished) = localClose(alice5)
            Triple(aliceClosing, localCommitPublished, htlc)
        }

        assertNotNull(localCommitPublished.localOutput)
        assertTrue(localCommitPublished.htlcOutputs.isEmpty())

        val (alice1, actions1) = aliceClosing.process(ChannelCommand.WatchReceived(WatchConfirmedTriggered(alice0.channelId, WatchConfirmed.ClosingTxConfirmed, 42, 1, localCommitPublished.commitTx)))
        assertIs<Closing>(alice1.state)
        // when the commit tx is confirmed, alice knows that the htlc will never reach the chain
        assertEquals(2, actions1.size)
        assertTrue(actions1.contains(ChannelAction.Storage.StoreState(alice1.state)))
        val addFailed = actions1.filterIsInstance<ChannelAction.ProcessCmdRes.AddSettledFail>().firstOrNull()
        assertNotNull(addFailed)
        assertEquals(htlc, addFailed.htlc)
        assertTrue(addFailed.result is ChannelAction.HtlcResult.Fail.OnChainFail)
    }

    @Test
    fun `recv BITCOIN_OUTPUT_SPENT -- local commit -- extract preimage from claim-htlc-success tx`() {
        val (alice0, bob0) = reachNormal()
        // Alice sends htlcs to Bob with the same payment_hash.
        val (nodes1, preimage, htlc1) = addHtlc(20_000_000.msat, alice0, bob0)
        val (alice1, bob1) = nodes1
        val (alice2, bob2, htlc2) = addHtlc(makeCmdAdd(15_000_000.msat, bob1.staticParams.nodeParams.nodeId, alice1.currentBlockHeight.toLong(), preimage).second, alice1, bob1)
        assertEquals(htlc1.paymentHash, htlc2.paymentHash)
        val (alice3, bob3) = crossSign(alice2, bob2)
        // Bob has the preimage for those HTLCs, but Alice force-closes before receiving it.
        val (bob4, actionsBob4) = bob3.process(ChannelCommand.Htlc.Settlement.Fulfill(htlc1.id, preimage))
        actionsBob4.hasOutgoingMessage<UpdateFulfillHtlc>() // ignored
        val (alice4, localCommitPublished, closingTxs) = localClose(alice3, htlcTimeoutCount = 2)
        assertEquals(2, localCommitPublished.htlcOutputs.size)

        val (_, actionsBob5) = bob4.process(ChannelCommand.WatchReceived(WatchSpentTriggered(bob0.channelId, WatchSpent.ChannelSpent(TestConstants.fundingAmount), localCommitPublished.commitTx)))
        actionsBob5.has<ChannelAction.Storage.StoreState>()
        val claimHtlcSuccessTxs = actionsBob5.filterIsInstance<ChannelAction.Blockchain.PublishTx>().filter { it.txType == ChannelAction.Blockchain.PublishTx.Type.ClaimHtlcSuccessTx }.map { it.tx }
        assertEquals(2, claimHtlcSuccessTxs.size)
        assertEquals(2, claimHtlcSuccessTxs.flatMap { tx -> tx.txIn.map { it.outPoint } }.toSet().size)

        // Alice extracts the preimage and forwards it to the payment handler.
        val (alice5, actionsAlice5) = alice4.process(ChannelCommand.WatchReceived(WatchSpentTriggered(alice0.channelId, WatchSpent.ClosingOutputSpent(20_000.sat), claimHtlcSuccessTxs.first())))
        assertEquals(3, actionsAlice5.size)
        actionsAlice5.hasWatchConfirmed(claimHtlcSuccessTxs.first().txid)
        val addSettled = actionsAlice5.filterIsInstance<ChannelAction.ProcessCmdRes.AddSettledFulfill>()
        assertEquals(setOf(htlc1, htlc2), addSettled.map { it.htlc }.toSet())
        assertEquals(setOf(ChannelAction.HtlcResult.Fulfill.OnChainFulfill(preimage)), addSettled.map { it.result }.toSet())

        // The Claim-HTLC-success transaction confirms: nothing to do, preimage has already been relayed.
        val (alice6, actionsAlice6) = alice5.process(ChannelCommand.WatchReceived(WatchConfirmedTriggered(alice0.channelId, WatchConfirmed.ClosingTxConfirmed, 41, 1, claimHtlcSuccessTxs.first())))
        assertIs<LNChannel<Closing>>(alice6)
        assertEquals(1, actionsAlice6.size)
        actionsAlice6.has<ChannelAction.Storage.StoreState>()

        // The remaining transactions confirm.
        val watchConfirmed = listOf(
            WatchConfirmedTriggered(alice0.channelId, WatchConfirmed.ClosingTxConfirmed, 41, 0, localCommitPublished.commitTx),
            WatchConfirmedTriggered(alice0.channelId, WatchConfirmed.ClosingTxConfirmed, 47, 0, claimHtlcSuccessTxs.last()),
            WatchConfirmedTriggered(alice0.channelId, WatchConfirmed.ClosingTxConfirmed, 200, 0, closingTxs.mainTx)
        )
        confirmClosingTxs(alice6, watchConfirmed)
    }

    @Test
    fun `recv ChannelEvent Restore -- local commit`() {
        val (alice0, bob0) = reachNormal()
        val (aliceClosing, localCommitPublished, closingTxs) = run {
            // alice sends an htlc to bob
            val (nodes1, _, _) = addHtlc(50_000_000.msat, alice0, bob0)
            val (alice1, bob1) = nodes1
            val (alice2, _) = crossSign(alice1, bob1)
            localClose(alice2, htlcTimeoutCount = 1)
        }

        assertNotNull(localCommitPublished.localOutput)
        assertEquals(1, localCommitPublished.htlcOutputs.size)

        // Simulate a wallet restart: we republish closing transactions.
        val initState = LNChannel(aliceClosing.ctx, WaitForInit)
        val (alice1, actions1) = initState.process(ChannelCommand.Init.Restore(aliceClosing.state))
        assertIs<Closing>(alice1.state)
        assertEquals(aliceClosing, alice1)
        assertEquals(7, actions1.size)
        actions1.doesNotHave<ChannelAction.Storage.StoreOutgoingPayment.ViaClose>()
        actions1.hasWatchFundingSpent(aliceClosing.commitments.latest.fundingInput.txid)
        actions1.hasPublishTx(localCommitPublished.commitTx)
        actions1.hasWatchConfirmed(localCommitPublished.commitTx.txid)
        actions1.hasPublishTx(closingTxs.mainTx)
        actions1.hasWatchOutputSpent(closingTxs.mainTx.txIn.first().outPoint)
        actions1.hasPublishTx(closingTxs.htlcTimeoutTxs.first())
        actions1.hasWatchOutputSpent(closingTxs.htlcTimeoutTxs.first().txIn.first().outPoint)

        // Our HTLC-timeout transaction confirms: we publish an HTLC-delayed transaction.
        val (alice2, actions2) = alice1.process(ChannelCommand.WatchReceived(WatchConfirmedTriggered(alice0.channelId, WatchConfirmed.ClosingTxConfirmed, 0, 0, closingTxs.htlcTimeoutTxs.first())))
        assertIs<Closing>(alice2.state)
        val htlcDelayedTx = actions2.hasPublishTx(ChannelAction.Blockchain.PublishTx.Type.HtlcDelayedTx)
        actions2.hasWatchOutputSpent(htlcDelayedTx.txIn.first().outPoint)

        // Sinmulate a wallet restart: we republish 3rd-stage transactions.
        val (alice3, actions3) = initState.process(ChannelCommand.Init.Restore(alice2.state))
        assertIs<Closing>(alice3.state)
        assertEquals(alice2, alice3)
        assertEquals(6, actions3.size)
        actions3.hasPublishTx(localCommitPublished.commitTx)
        actions3.hasWatchConfirmed(localCommitPublished.commitTx.txid)
        actions3.hasPublishTx(closingTxs.mainTx)
        actions3.hasWatchOutputSpent(closingTxs.mainTx.txIn.first().outPoint)
        actions3.hasPublishTx(htlcDelayedTx)
        actions3.hasWatchOutputSpent(htlcDelayedTx.txIn.first().outPoint)
    }

    @Test
    fun `recv ChannelSpent -- remote commit`() {
        val (alice0, bob0) = reachNormal()
        val bobCommitTx = bob0.signCommitTx()
        assertEquals(4, bobCommitTx.txOut.size) // main outputs and anchors
        val (_, remoteCommitPublished) = remoteClose(bobCommitTx, alice0)
        assertNotNull(remoteCommitPublished.htlcOutputs)
        assertTrue(remoteCommitPublished.htlcOutputs.isEmpty())
    }

    @Test
    fun `recv ClosingTxConfirmed -- remote commit`() {
        val (alice0, bob0) = reachNormal()
        // alice sends an htlc to bob
        val (nodes1, _, htlc1) = addHtlc(50_000_000.msat, alice0, bob0)
        val (alice1, bob1) = nodes1
        // alice sends an htlc below dust to bob
        val amountBelowDust = alice0.commitments.latest.localCommitParams.dustLimit.toMilliSatoshi() - 100.msat
        val (nodes2, _, htlc2) = addHtlc(amountBelowDust, alice1, bob1)
        val (alice2, bob2) = nodes2
        val (alice3, bob3) = crossSign(alice2, bob2)
        val (aliceClosing, remoteCommitPublished, closingTxs) = remoteClose(bob3.signCommitTx(), alice3, htlcTimeoutCount = 1)

        // actual test starts here
        assertNotNull(remoteCommitPublished.localOutput)
        assertEquals(1, remoteCommitPublished.htlcOutputs.size)

        // The commit tx confirms.
        val (aliceClosing1, actions1) = aliceClosing.process(ChannelCommand.WatchReceived(WatchConfirmedTriggered(alice0.channelId, WatchConfirmed.ClosingTxConfirmed, 42, 6, remoteCommitPublished.commitTx)))
        assertIs<LNChannel<Closing>>(aliceClosing1)
        assertEquals(2, actions1.size)
        actions1.has<ChannelAction.Storage.StoreState>()
        assertEquals(setOf(htlc2), actions1.filterIsInstance<ChannelAction.ProcessCmdRes.AddSettledFail>().map { it.htlc }.toSet())
        actions1.filterIsInstance<ChannelAction.ProcessCmdRes.AddSettledFail>().forEach { assertIs<ChannelAction.HtlcResult.Fail.OnChainFail>(it.result) }
        assertNotNull(aliceClosing1.state.remoteCommitPublished)
        assertTrue(aliceClosing1.state.remoteCommitPublished.isConfirmed)
        assertFalse(aliceClosing1.state.remoteCommitPublished.isDone)

        // Our main transaction confirms.
        val (aliceClosing2, actions2) = aliceClosing1.process(ChannelCommand.WatchReceived(WatchConfirmedTriggered(alice0.channelId, WatchConfirmed.ClosingTxConfirmed, 45, 0, closingTxs.mainTx)))
        assertIs<LNChannel<Closing>>(aliceClosing2)
        assertEquals(1, actions2.size)
        actions2.has<ChannelAction.Storage.StoreState>()
        assertNotNull(aliceClosing2.state.remoteCommitPublished)
        assertFalse(aliceClosing2.state.remoteCommitPublished.isDone)
        assertEquals(setOf(remoteCommitPublished.commitTx, closingTxs.mainTx), aliceClosing2.state.remoteCommitPublished.irrevocablySpent.values.toSet())

        // Our HTLC-timeout transaction confirms.
        val (aliceClosing3, actions3) = aliceClosing2.process(ChannelCommand.WatchReceived(WatchConfirmedTriggered(alice0.channelId, WatchConfirmed.ClosingTxConfirmed, 45, 1, closingTxs.htlcTimeoutTxs.first())))
        assertIs<LNChannel<Closed>>(aliceClosing3)
        assertEquals(3, actions3.size)
        actions3.has<ChannelAction.Storage.StoreState>()
        assertEquals(setOf(htlc1), actions3.filterIsInstance<ChannelAction.ProcessCmdRes.AddSettledFail>().map { it.htlc }.toSet())
        actions3.filterIsInstance<ChannelAction.ProcessCmdRes.AddSettledFail>().forEach { assertIs<ChannelAction.HtlcResult.Fail.OnChainFail>(it.result) }
        assertNotNull(aliceClosing3.state.state.remoteCommitPublished)
        assertTrue(aliceClosing3.state.state.remoteCommitPublished.isDone)
        assertEquals(setOf(remoteCommitPublished.commitTx, closingTxs.mainTx, closingTxs.htlcTimeoutTxs.first()), aliceClosing3.state.state.remoteCommitPublished.irrevocablySpent.values.toSet())
        assertContains(actions3, ChannelAction.Storage.SetLocked(remoteCommitPublished.commitTx.txid))
    }

    @Test
    fun `recv ClosingTxConfirmed -- remote commit -- non-initiator pays commit fees`() {
        val (alice0, bob0) = reachNormal(requestRemoteFunding = TestConstants.bobFundingAmount)
        assertFalse(alice0.commitments.channelParams.localParams.paysCommitTxFees)
        assertTrue(bob0.commitments.channelParams.localParams.paysCommitTxFees)
        val remoteCommitTx = bob0.signCommitTx()
        val (alice1, _, closingTxs) = remoteClose(remoteCommitTx, alice0)
        val (alice2, _) = alice1.process(ChannelCommand.WatchReceived(WatchConfirmedTriggered(alice0.channelId, WatchConfirmed.ClosingTxConfirmed, 42, 7, remoteCommitTx)))
        val (alice3, actions3) = alice2.process(ChannelCommand.WatchReceived(WatchConfirmedTriggered(alice0.state.channelId, WatchConfirmed.ClosingTxConfirmed, 43, 3, closingTxs.mainTx)))
        assertIs<Closed>(alice3.state)
        assertEquals(2, actions3.size)
        actions3.has<ChannelAction.Storage.StoreState>()
        actions3.find<ChannelAction.Storage.SetLocked>().also { assertEquals(remoteCommitTx.txid, it.txId) }
    }

    @Test
    fun `recv ClosingTxConfirmed -- remote commit with multiple htlcs for the same payment`() {
        val (alice0, bob0) = reachNormal()
        // alice sends an htlc to bob
        val (aliceClosing, remoteCommitPublished, closingTxs) = run {
            val (nodes1, preimage, _) = addHtlc(30_000_000.msat, alice0, bob0)
            val (alice1, bob1) = nodes1
            // and more htlcs with the same payment_hash
            val (_, cmd2) = makeCmdAdd(25_000_000.msat, bob0.staticParams.nodeParams.nodeId, alice1.currentBlockHeight.toLong(), preimage)
            val (alice2, bob2, _) = addHtlc(cmd2, alice1, bob1)
            val (_, cmd3) = makeCmdAdd(20_000_000.msat, bob0.staticParams.nodeParams.nodeId, alice2.currentBlockHeight.toLong() - 1, preimage)
            val (alice3, bob3, _) = addHtlc(cmd3, alice2, bob2)
            val (alice4, bob4) = crossSign(alice3, bob3)
            remoteClose(bob4.signCommitTx(), alice4, htlcTimeoutCount = 3)
        }

        // actual test starts here
        assertNotNull(remoteCommitPublished.localOutput)
        assertEquals(3, remoteCommitPublished.htlcOutputs.size)

        // if commit tx and claim-htlc-timeout txs end up in the same block, we may receive them in any order
        val watchConfirmed = listOf(
            WatchConfirmedTriggered(alice0.channelId, WatchConfirmed.ClosingTxConfirmed, 42, 0, closingTxs.htlcTimeoutTxs[1]),
            WatchConfirmedTriggered(alice0.channelId, WatchConfirmed.ClosingTxConfirmed, 42, 1, remoteCommitPublished.commitTx),
            WatchConfirmedTriggered(alice0.channelId, WatchConfirmed.ClosingTxConfirmed, 201, 0, closingTxs.htlcTimeoutTxs[2]),
            WatchConfirmedTriggered(alice0.channelId, WatchConfirmed.ClosingTxConfirmed, 200, 0, closingTxs.mainTx),
            WatchConfirmedTriggered(alice0.channelId, WatchConfirmed.ClosingTxConfirmed, 204, 0, closingTxs.htlcTimeoutTxs[0])
        )
        confirmClosingTxs(aliceClosing, watchConfirmed)
    }

    @Test
    fun `recv ClosingTxConfirmed -- remote commit with htlcs only signed by local in next remote commit`() {
        val (alice0, bob0) = reachNormal()
        val bobCommitTx = bob0.signCommitTx()
        val (aliceClosing, remoteCommitPublished, add) = run {
            // alice sends an htlc to bob
            val (nodes1, _, add) = addHtlc(50_000_000.msat, alice0, bob0)
            val alice1 = nodes1.first
            // alice signs it, but bob doesn't receive the signature
            val (alice2, actions2) = alice1.process(ChannelCommand.Commitment.Sign)
            actions2.hasOutgoingMessage<CommitSig>()
            val (aliceClosing, remoteCommitPublished) = remoteClose(bobCommitTx, alice2)
            Triple(aliceClosing, remoteCommitPublished, add)
        }

        assertTrue(remoteCommitPublished.htlcOutputs.isEmpty())

        val (alice1, actions1) = aliceClosing.process(ChannelCommand.WatchReceived(WatchConfirmedTriggered(alice0.channelId, WatchConfirmed.ClosingTxConfirmed, 42, 1, bobCommitTx)))
        assertIs<Closing>(alice1.state)
        // when the commit tx is confirmed, alice knows that the htlc she sent right before the unilateral close will never reach the chain, so she fails it
        assertEquals(2, actions1.size)
        assertTrue(actions1.contains(ChannelAction.Storage.StoreState(alice1.state)))
        val addFailed = actions1.filterIsInstance<ChannelAction.ProcessCmdRes.AddSettledFail>().firstOrNull()
        assertNotNull(addFailed)
        assertEquals(add, addFailed.htlc)
        assertTrue(addFailed.result is ChannelAction.HtlcResult.Fail.OnChainFail)
    }

    @Test
    fun `recv ClosingTxConfirmed -- remote commit --  followed by preimage`() {
        val (alice0, bob0) = reachNormal()
        // An HTLC Bob -> Alice is cross-signed that will be fulfilled later.
        val (nodes1, preimage, htlc) = addHtlc(110_000_000.msat, bob0, alice0)
        val (bob1, alice1) = nodes1
        // An HTLC Alice -> Bob is cross-signed and will timeout later.
        val (nodes2, _, _) = addHtlc(95_000_000.msat, alice1, bob1)
        val (alice2, bob2) = nodes2
        val (alice3, bob3) = crossSign(alice2, bob2)
        // Now Bob publishes his commit tx (force-close).
        val bobCommitTx = bob3.signCommitTx()
        assertEquals(6, bobCommitTx.txOut.size) // two main outputs + 2 anchors + 2 HTLCs
        val (aliceClosing, remoteCommitPublished, closingTxs) = remoteClose(bobCommitTx, alice3, htlcTimeoutCount = 1)

        assertNotNull(remoteCommitPublished.localOutput)
        // we don't have the preimage to claim the htlc-success yet
        assertTrue(closingTxs.htlcSuccessTxs.isEmpty())
        assertEquals(1, closingTxs.htlcTimeoutTxs.size)
        assertEquals(2, remoteCommitPublished.htlcOutputs.size)

        // Alice receives the preimage for the first HTLC from the payment handler; she can now claim the corresponding HTLC output.
        val (aliceFulfill, actionsFulfill) = aliceClosing.process(ChannelCommand.Htlc.Settlement.Fulfill(htlc.id, preimage, commit = true))
        assertIs<LNChannel<Closing>>(aliceFulfill)
        val claimHtlcSuccess = actionsFulfill.hasPublishTx(ChannelAction.Blockchain.PublishTx.Type.ClaimHtlcSuccessTx)
        Transaction.correctlySpends(claimHtlcSuccess, remoteCommitPublished.commitTx, ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)

        val watchConfirmed = listOf(
            WatchConfirmedTriggered(alice0.channelId, WatchConfirmed.ClosingTxConfirmed, 42, 1, remoteCommitPublished.commitTx),
            WatchConfirmedTriggered(alice0.channelId, WatchConfirmed.ClosingTxConfirmed, 210, 0, closingTxs.htlcTimeoutTxs.first()),
            WatchConfirmedTriggered(alice0.channelId, WatchConfirmed.ClosingTxConfirmed, 210, 0, claimHtlcSuccess),
            WatchConfirmedTriggered(alice0.channelId, WatchConfirmed.ClosingTxConfirmed, 250, 0, closingTxs.mainTx)
        )
        confirmClosingTxs(aliceFulfill, watchConfirmed)
    }

    @Test
    fun `recv ClosingTxConfirmed -- remote commit -- alternative feerate`() {
        val (alice0, bob0) = reachNormal()
        val (bobClosing, remoteCommitPublished, closingTxs) = run {
            val (nodes1, r, htlc) = addHtlc(75_000_000.msat, alice0, bob0)
            val (alice1, bob1) = nodes1
            val (alice2, bob2) = crossSign(alice1, bob1)
            val (alice3, bob3) = fulfillHtlc(htlc.id, r, alice2, bob2)
            val (bob4, actionsBob4) = bob3.process(ChannelCommand.Commitment.Sign)
            val commitSigBob = actionsBob4.hasOutgoingMessage<CommitSig>()
            val (alice4, actionsAlice4) = alice3.process(ChannelCommand.MessageReceived(commitSigBob))
            val revAlice = actionsAlice4.hasOutgoingMessage<RevokeAndAck>()
            val (alice5, actionsAlice5) = alice4.process(ChannelCommand.Commitment.Sign)
            val commitSigAlice = actionsAlice5.hasOutgoingMessage<CommitSig>()
            val (bob5, _) = bob4.process(ChannelCommand.MessageReceived(revAlice))
            val (bob6, actionsBob6) = bob5.process(ChannelCommand.MessageReceived(commitSigAlice))
            val revBob = actionsBob6.hasOutgoingMessage<RevokeAndAck>()
            val (alice6, _) = alice5.process(ChannelCommand.MessageReceived(revBob))
            val alternativeCommitTx = useAlternativeCommitSig(alice6, alice6.commitments.active.first(), Commitments.alternativeFeerates.first())
            remoteClose(alternativeCommitTx, bob6)
        }

        assertNotNull(remoteCommitPublished.localOutput)
        Transaction.correctlySpends(closingTxs.mainTx, remoteCommitPublished.commitTx, ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)

        val (bobClosing1, _) = bobClosing.process(ChannelCommand.WatchReceived(WatchConfirmedTriggered(bob0.channelId, WatchConfirmed.ClosingTxConfirmed, 42, 0, remoteCommitPublished.commitTx)))
        val (bobClosed, actions) = bobClosing1.process(ChannelCommand.WatchReceived(WatchConfirmedTriggered(bob0.channelId, WatchConfirmed.ClosingTxConfirmed, 43, 0, closingTxs.mainTx)))
        assertIs<Closed>(bobClosed.state)
        assertTrue(actions.contains(ChannelAction.Storage.StoreState(bobClosed.state)))
    }

    @Test
    fun `recv BITCOIN_OUTPUT_SPENT -- remote commit -- extract preimage from HTLC-success tx`() {
        val (alice0, bob0) = reachNormal()
        // Alice sends htlcs to Bob with the same payment_hash.
        val (nodes1, preimage, htlc1) = addHtlc(50_000_000.msat, alice0, bob0)
        val (alice1, bob1) = nodes1
        val (alice2, bob2, htlc2) = addHtlc(makeCmdAdd(40_000_000.msat, bob0.staticParams.nodeParams.nodeId, alice0.currentBlockHeight.toLong(), preimage).second, alice1, bob1)
        assertEquals(htlc1.paymentHash, htlc2.paymentHash)
        val (alice3, bob3) = crossSign(alice2, bob2)

        // Bob has the preimage for those HTLCs, but he force-closes before Alice receives it.
        val (bob4, actionsBob4) = bob3.process(ChannelCommand.Htlc.Settlement.Fulfill(htlc1.id, preimage))
        actionsBob4.hasOutgoingMessage<UpdateFulfillHtlc>() // ignored
        val (_, remoteCommitPublished, bobClosingTxs) = localClose(bob4, htlcSuccessCount = 2)
        // Bob claims the htlc outputs from his own commit tx using its preimage.
        assertEquals(setOf(htlc1.id, htlc2.id), remoteCommitPublished.incomingHtlcs.values.toSet())

        // Alice extracts the preimage and forwards it to the payment handler.
        val (alice4, actionsAlice4) = alice3.process(ChannelCommand.WatchReceived(WatchSpentTriggered(alice0.channelId, WatchSpent.ChannelSpent(TestConstants.fundingAmount), remoteCommitPublished.commitTx)))
        actionsAlice4.has<ChannelAction.Storage.StoreState>()
        actionsAlice4.hasWatchConfirmed(remoteCommitPublished.commitTx.txid)
        val mainTx = actionsAlice4.hasPublishTx(ChannelAction.Blockchain.PublishTx.Type.ClaimRemoteDelayedOutputTx)
        actionsAlice4.hasWatchOutputSpent(mainTx.txIn.first().outPoint)
        val (alice5, actionsAlice5) = alice4.process(ChannelCommand.WatchReceived(WatchSpentTriggered(alice0.channelId, WatchSpent.ClosingOutputSpent(50_000.sat), bobClosingTxs.htlcSuccessTxs.first())))
        assertEquals(3, actionsAlice5.size)
        actionsAlice5.hasWatchConfirmed(bobClosingTxs.htlcSuccessTxs.first().txid)
        val addSettled = actionsAlice5.filterIsInstance<ChannelAction.ProcessCmdRes.AddSettledFulfill>()
        assertEquals(setOf(htlc1, htlc2), addSettled.map { it.htlc }.toSet())
        assertEquals(setOf(ChannelAction.HtlcResult.Fulfill.OnChainFulfill(preimage)), addSettled.map { it.result }.toSet())

        // The HTLC-success transaction confirms: nothing to do, preimage has already been relayed.
        val (alice6, actionsAlice6) = alice5.process(ChannelCommand.WatchReceived(WatchConfirmedTriggered(alice0.channelId, WatchConfirmed.ClosingTxConfirmed, 42, 1, bobClosingTxs.htlcSuccessTxs.first())))
        assertIs<LNChannel<Closing>>(alice6)
        assertEquals(1, actionsAlice6.size)
        actionsAlice6.has<ChannelAction.Storage.StoreState>()

        // The remaining transactions confirm.
        val watchConfirmed = listOf(
            WatchConfirmedTriggered(alice0.channelId, WatchConfirmed.ClosingTxConfirmed, 42, 0, remoteCommitPublished.commitTx),
            WatchConfirmedTriggered(alice0.channelId, WatchConfirmed.ClosingTxConfirmed, 47, 0, bobClosingTxs.htlcSuccessTxs.last()),
            WatchConfirmedTriggered(alice0.channelId, WatchConfirmed.ClosingTxConfirmed, 47, 0, mainTx),
        )
        confirmClosingTxs(alice6, watchConfirmed)
    }

    @Test
    fun `recv ChannelEvent Restore -- remote commit`() {
        val (alice0, bob0) = reachNormal()
        val (aliceClosing, remoteCommitPublished, closingTxs) = run {
            // alice sends an htlc to bob
            val (nodes1, _, _) = addHtlc(50_000_000.msat, alice0, bob0)
            val (alice1, bob1) = nodes1
            val (alice2, bob2) = crossSign(alice1, bob1)
            assertIs<Normal>(bob2.state)
            val bobCommitTx = bob2.signCommitTx()
            remoteClose(bobCommitTx, alice2, htlcTimeoutCount = 1)
        }

        assertNotNull(remoteCommitPublished.localOutput)
        assertEquals(1, remoteCommitPublished.htlcOutputs.size)

        // Simulate a wallet restart
        val initState = LNChannel(aliceClosing.ctx, WaitForInit)
        val (alice1, actions1) = initState.process(ChannelCommand.Init.Restore(aliceClosing.state))
        assertIs<Closing>(alice1.state)
        assertEquals(aliceClosing, alice1)
        assertEquals(6, actions1.size)
        actions1.doesNotHave<ChannelAction.Storage.StoreOutgoingPayment.ViaClose>()
        actions1.hasWatchFundingSpent(aliceClosing.commitments.latest.fundingInput.txid)
        actions1.hasWatchConfirmed(remoteCommitPublished.commitTx.txid)
        actions1.hasPublishTx(closingTxs.mainTx)
        actions1.hasWatchOutputSpent(closingTxs.mainTx.txIn.first().outPoint)
        actions1.hasPublishTx(closingTxs.htlcTimeoutTxs.first())
        actions1.hasWatchOutputSpent(closingTxs.htlcTimeoutTxs.first().txIn.first().outPoint)
    }

    @Test
    fun `recv ChannelSpent -- next remote commit`() {
        val (alice0, bob0) = reachNormal()
        // alice sends an htlc to bob
        val (nodes1, _, _) = addHtlc(50_000_000.msat, alice0, bob0)
        val (alice1, bob1) = nodes1
        val bobCommitTx1 = bob1.signCommitTx()
        // alice signs it, but bob doesn't revoke
        val (alice2, actionsAlice2) = alice1.process(ChannelCommand.Commitment.Sign)
        val commitSig = actionsAlice2.hasOutgoingMessage<CommitSig>()
        val (bob2, actionsBob2) = bob1.process(ChannelCommand.MessageReceived(commitSig))
        actionsBob2.hasOutgoingMessage<RevokeAndAck>() // not forwarded to Alice (malicious Bob)
        // Bob publishes the next commit tx.
        val bobCommitTx2 = bob2.signCommitTx()
        assertNotEquals(bobCommitTx1.txid, bobCommitTx2.txid)
        val (aliceClosing, remoteCommitPublished) = remoteClose(bobCommitTx2, alice2, htlcTimeoutCount = 1)

        assertNotNull(remoteCommitPublished.localOutput)
        assertEquals(1, remoteCommitPublished.outgoingHtlcs.size)
        assertTrue(remoteCommitPublished.incomingHtlcs.isEmpty())

        assertNull(aliceClosing.state.remoteCommitPublished)
        assertNotNull(aliceClosing.state.nextRemoteCommitPublished)
        assertNull(aliceClosing.state.futureRemoteCommitPublished)
    }

    @Test
    fun `recv ClosingTxConfirmed -- next remote commit`() {
        val (alice0, bob0) = reachNormal()
        // alice sends an htlc to bob
        val (aliceClosing, remoteCommitPublished, closingTxs) = run {
            val (nodes1, preimage, _) = addHtlc(30_000_000.msat, alice0, bob0)
            val (alice1, bob1) = nodes1
            // and more htlcs with the same payment_hash
            val (_, cmd2) = makeCmdAdd(25_000_000.msat, bob0.staticParams.nodeParams.nodeId, alice1.currentBlockHeight.toLong() - 1, preimage)
            val (alice2, bob2, _) = addHtlc(cmd2, alice1, bob1)
            val (alice3, bob3) = crossSign(alice2, bob2)
            val bobCommitTx1 = bob2.signCommitTx()
            // add more htlcs that bob doesn't revoke
            val (_, cmd3) = makeCmdAdd(20_000_000.msat, bob0.staticParams.nodeParams.nodeId, alice3.currentBlockHeight.toLong(), preimage)
            val (alice4, bob4, _) = addHtlc(cmd3, alice3, bob3)
            val (alice5, actionsAlice5) = alice4.process(ChannelCommand.Commitment.Sign)
            val commitSig = actionsAlice5.hasOutgoingMessage<CommitSig>()
            val (bob5, actionsBob5) = bob4.process(ChannelCommand.MessageReceived(commitSig))
            actionsBob5.hasOutgoingMessage<RevokeAndAck>() // not forwarded to Alice (malicious Bob)
            // Bob publishes the next commit tx.
            val bobCommitTx2 = bob5.signCommitTx()
            assertNotEquals(bobCommitTx1.txid, bobCommitTx2.txid)
            remoteClose(bobCommitTx2, alice5, htlcTimeoutCount = 3)
        }

        // actual test starts here
        assertNotNull(aliceClosing.state.nextRemoteCommitPublished)
        assertNotNull(remoteCommitPublished.localOutput)
        assertEquals(3, remoteCommitPublished.outgoingHtlcs.size)
        assertTrue(remoteCommitPublished.incomingHtlcs.isEmpty())

        val watchConfirmed = listOf(
            WatchConfirmedTriggered(alice0.channelId, WatchConfirmed.ClosingTxConfirmed, 42, 1, remoteCommitPublished.commitTx),
            WatchConfirmedTriggered(alice0.channelId, WatchConfirmed.ClosingTxConfirmed, 201, 0, closingTxs.htlcTimeoutTxs[1]),
            WatchConfirmedTriggered(alice0.channelId, WatchConfirmed.ClosingTxConfirmed, 201, 0, closingTxs.htlcTimeoutTxs[2]),
            WatchConfirmedTriggered(alice0.channelId, WatchConfirmed.ClosingTxConfirmed, 202, 0, closingTxs.mainTx),
            WatchConfirmedTriggered(alice0.channelId, WatchConfirmed.ClosingTxConfirmed, 204, 0, closingTxs.htlcTimeoutTxs[0])
        )
        confirmClosingTxs(aliceClosing, watchConfirmed)
    }

    @Test
    fun `recv ClosingTxConfirmed -- next remote commit -- followed by preimage`() {
        val (alice0, bob0) = reachNormal()
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
        val (alice5, actionsAlice5) = alice4.process(ChannelCommand.Commitment.Sign)
        val commitSig = actionsAlice5.hasOutgoingMessage<CommitSig>()
        val (bob5, actionsBob5) = bob4.process(ChannelCommand.MessageReceived(commitSig))
        actionsBob5.hasOutgoingMessage<RevokeAndAck>() // not forwarded to Alice (malicious Bob)
        // Now Bob publishes his commit tx (force-close).
        val bobCommitTx = bob5.signCommitTx()
        assertEquals(7, bobCommitTx.txOut.size) // two main outputs + 2 anchors + 3 HTLCs
        val (aliceClosing, remoteCommitPublished, closingTxs) = remoteClose(bobCommitTx, alice5, htlcTimeoutCount = 2)

        assertNotNull(aliceClosing.state.nextRemoteCommitPublished)
        assertNotNull(remoteCommitPublished.localOutput)
        // we don't have the preimage to claim the htlc-success yet
        assertEquals(1, remoteCommitPublished.incomingHtlcs.size)
        assertTrue(closingTxs.htlcSuccessTxs.isEmpty())
        assertEquals(2, remoteCommitPublished.outgoingHtlcs.size)

        // Alice receives the preimage for the first HTLC from the payment handler; she can now claim the corresponding HTLC output.
        val (aliceFulfill, actionsFulfill) = aliceClosing.process(ChannelCommand.Htlc.Settlement.Fulfill(htlc.id, preimage, commit = true))
        assertIs<LNChannel<Closing>>(aliceFulfill)
        val claimHtlcSuccess = actionsFulfill.hasPublishTx(ChannelAction.Blockchain.PublishTx.Type.ClaimHtlcSuccessTx)
        Transaction.correctlySpends(claimHtlcSuccess, remoteCommitPublished.commitTx, ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
        actionsFulfill.doesNotHave<ChannelAction.Storage.StoreOutgoingPayment.ViaClose>()

        val watchConfirmed = listOf(
            WatchConfirmedTriggered(alice0.channelId, WatchConfirmed.ClosingTxConfirmed, 42, 1, remoteCommitPublished.commitTx),
            WatchConfirmedTriggered(alice0.channelId, WatchConfirmed.ClosingTxConfirmed, 210, 0, closingTxs.htlcTimeoutTxs[0]),
            WatchConfirmedTriggered(alice0.channelId, WatchConfirmed.ClosingTxConfirmed, 210, 1, claimHtlcSuccess),
            WatchConfirmedTriggered(alice0.channelId, WatchConfirmed.ClosingTxConfirmed, 210, 3, closingTxs.htlcTimeoutTxs[1]),
            WatchConfirmedTriggered(alice0.channelId, WatchConfirmed.ClosingTxConfirmed, 250, 0, closingTxs.mainTx)
        )
        confirmClosingTxs(aliceFulfill, watchConfirmed)
    }

    @Test
    fun `recv ClosingTxConfirmed -- next remote commit -- alternative feerate`() {
        val (alice0, bob0) = reachNormal()
        val (bobClosing, remoteCommitPublished, closingTxs) = run {
            val (nodes1, r, htlc) = addHtlc(75_000_000.msat, alice0, bob0)
            val (alice1, bob1) = nodes1
            val (alice2, bob2) = crossSign(alice1, bob1)
            val (alice3, bob3) = fulfillHtlc(htlc.id, r, alice2, bob2)
            val (bob4, actionsBob4) = bob3.process(ChannelCommand.Commitment.Sign)
            val commitSigBob = actionsBob4.hasOutgoingMessage<CommitSig>()
            val (alice4, _) = alice3.process(ChannelCommand.MessageReceived(commitSigBob))
            val alternativeCommitTx = useAlternativeCommitSig(alice4, alice4.commitments.active.first(), Commitments.alternativeFeerates.first())
            remoteClose(alternativeCommitTx, bob4)
        }

        assertNotNull(remoteCommitPublished.localOutput)
        Transaction.correctlySpends(closingTxs.mainTx, remoteCommitPublished.commitTx, ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)

        val (bobClosing1, _) = bobClosing.process(ChannelCommand.WatchReceived(WatchConfirmedTriggered(bob0.channelId, WatchConfirmed.ClosingTxConfirmed, 42, 0, remoteCommitPublished.commitTx)))
        val (bobClosed, actions) = bobClosing1.process(ChannelCommand.WatchReceived(WatchConfirmedTriggered(bob0.channelId, WatchConfirmed.ClosingTxConfirmed, 43, 0, closingTxs.mainTx)))
        assertIs<Closed>(bobClosed.state)
        assertTrue(actions.contains(ChannelAction.Storage.StoreState(bobClosed.state)))
    }

    @Test
    fun `recv ClosingTxConfirmed -- next remote commit -- with settled htlcs`() {
        val (alice0, bob0) = reachNormal()
        // Alice sends two htlcs to Bob.
        val (nodes1, preimage1, htlc1) = addHtlc(30_000_000.msat, alice0, bob0)
        val (nodes2, _, htlc2) = addHtlc(30_000_000.msat, nodes1.first, nodes1.second)
        val (alice3, bob3) = crossSign(nodes2.first, nodes2.second)

        // Bob fulfills one HTLC and fails the other one without revoking its previous commitment.
        val (bob4, actionsBob4) = bob3.process(ChannelCommand.Htlc.Settlement.Fulfill(htlc1.id, preimage1))
        val fulfill = actionsBob4.hasOutgoingMessage<UpdateFulfillHtlc>()
        val (bob5, actionsBob5) = bob4.process(ChannelCommand.Htlc.Settlement.Fail(htlc2.id, ChannelCommand.Htlc.Settlement.Fail.Reason.Failure(TemporaryNodeFailure)))
        val fail = actionsBob5.hasOutgoingMessage<UpdateFailHtlc>()
        val (bob6, actionsBob6) = bob5.process(ChannelCommand.Commitment.Sign)
        val commitBob = actionsBob6.hasOutgoingMessage<CommitSig>()
        val (alice4, actionsAlice4) = alice3.process(ChannelCommand.MessageReceived(fulfill))
        val addSettled = actionsAlice4.find<ChannelAction.ProcessCmdRes.AddSettledFulfill>()
        assertEquals(htlc1, addSettled.htlc)
        assertEquals(preimage1, addSettled.result.paymentPreimage)
        val (alice5, actionsAlice5) = alice4.process(ChannelCommand.MessageReceived(fail))
        assertTrue(actionsAlice5.isEmpty()) // we don't settle the failed HTLC until revoked
        val (alice6, actionsAlice6) = alice5.process(ChannelCommand.MessageReceived(commitBob))
        assertEquals(3, actionsAlice6.size)
        actionsAlice6.has<ChannelAction.Storage.StoreState>()
        val revokeAlice = actionsAlice6.hasOutgoingMessage<RevokeAndAck>()
        actionsAlice6.hasCommand<ChannelCommand.Commitment.Sign>()
        val (alice7, actionsAlice7) = alice6.process(ChannelCommand.Commitment.Sign)
        val commitAlice = actionsAlice7.hasOutgoingMessage<CommitSig>()
        val (bob7, _) = bob6.process(ChannelCommand.MessageReceived(revokeAlice))
        val (bob8, actionsBob8) = bob7.process(ChannelCommand.MessageReceived(commitAlice))
        actionsBob8.hasOutgoingMessage<RevokeAndAck>() // ignored

        // Bob closes the channel using his latest commitment, which doesn't contain any htlc.
        val bobCommit = bob8.commitments.latest.localCommit
        assertTrue(bobCommit.htlcRemoteSigs.isEmpty())
        val commitTx = bob8.signCommitTx()
        val (alice8, actionsAlice8) = alice7.process(ChannelCommand.WatchReceived(WatchSpentTriggered(alice0.channelId, WatchSpent.ChannelSpent(TestConstants.fundingAmount), commitTx)))
        assertTrue(actionsAlice8.filterIsInstance<ChannelAction.ProcessCmdRes>().isEmpty())
        actionsAlice8.hasWatchConfirmed(commitTx.txid)
        val (_, actionsAlice9) = alice8.process(ChannelCommand.WatchReceived(WatchConfirmedTriggered(alice0.channelId, WatchConfirmed.ClosingTxConfirmed, 40, 3, commitTx)))
        // The two HTLCs have been overridden by the on-chain commit.
        // The first one is a no-op since we already relayed the fulfill upstream.
        val addFailed = actionsAlice9.filterIsInstance<ChannelAction.ProcessCmdRes.AddSettledFail>()
        assertEquals(setOf(htlc1, htlc2), addFailed.map { it.htlc }.toSet())
        addFailed.forEach { assertIs<ChannelAction.HtlcResult.Fail.OnChainFail>(it.result) }
    }

    @Test
    fun `recv BITCOIN_OUTPUT_SPENT -- next remote commit -- extract preimage from removed HTLC`() {
        val (alice0, bob0) = reachNormal()
        // Alice sends htlcs to Bob with the same payment_hash.
        val (nodes1, preimage, htlc1) = addHtlc(50_000_000.msat, alice0, bob0)
        val (alice2, bob2, htlc2) = addHtlc(makeCmdAdd(40_000_000.msat, bob0.staticParams.nodeParams.nodeId, alice0.currentBlockHeight.toLong(), preimage).second, nodes1.first, nodes1.second)
        assertEquals(htlc1.paymentHash, htlc2.paymentHash)
        // And another HTLC with a different payment_hash.
        val (nodes3, _, htlc3) = addHtlc(60_000_000.msat, alice2, bob2)
        val (alice4, bob4) = crossSign(nodes3.first, nodes3.second)

        // Bob has the preimage for the first two HTLCs, but he fails them instead of fulfilling them.
        val alice5 = run {
            val (alice5, bob5) = failHtlc(htlc1.id, alice4, bob4)
            val (alice6, bob6) = failHtlc(htlc2.id, alice5, bob5)
            val (alice7, bob7) = failHtlc(htlc3.id, alice6, bob6)
            val (_, actionsBob8) = bob7.process(ChannelCommand.Commitment.Sign)
            val (alice8, actionsAlice8) = alice7.process(ChannelCommand.MessageReceived(actionsBob8.findOutgoingMessage<CommitSig>()))
            actionsAlice8.hasOutgoingMessage<RevokeAndAck>()
            alice8
        }

        // At that point, the HTLCs are not in Alice's commitment anymore.
        // But Bob has not revoked his commitment yet that contains them.
        // Bob claims the htlc outputs from his previous commit tx using its preimage.
        val (remoteCommitPublished, bobClosingTxs) = run {
            val (bob5, _) = bob4.process(ChannelCommand.Htlc.Settlement.Fulfill(htlc1.id, preimage))
            val (bob6, _) = bob5.process(ChannelCommand.Htlc.Settlement.Fulfill(htlc2.id, preimage))
            val (_, remoteCommitPublished, closingTxs) = localClose(bob6, htlcSuccessCount = 2)
            assertEquals(3, remoteCommitPublished.incomingHtlcs.size)
            assertEquals(2, closingTxs.htlcSuccessTxs.size) // Bob doesn't have the preimage for the last HTLC.
            Pair(remoteCommitPublished, closingTxs)
        }

        // Alice prepares Claim-HTLC-timeout transactions for each HTLC.
        val (alice6, actionsAlice6) = alice5.process(ChannelCommand.WatchReceived(WatchSpentTriggered(alice0.channelId, WatchSpent.ChannelSpent(TestConstants.fundingAmount), remoteCommitPublished.commitTx)))
        assertIs<LNChannel<Closing>>(alice6)
        actionsAlice6.has<ChannelAction.Storage.StoreState>()
        assertNotNull(alice6.state.remoteCommitPublished)
        actionsAlice6.hasWatchConfirmed(remoteCommitPublished.commitTx.txid)
        val mainTx = actionsAlice6.hasPublishTx(ChannelAction.Blockchain.PublishTx.Type.ClaimRemoteDelayedOutputTx)
        actionsAlice6.hasWatchOutputSpent(mainTx.txIn.first().outPoint)
        val claimHtlcTimeoutTxs = actionsAlice6.findPublishTxs(ChannelAction.Blockchain.PublishTx.Type.ClaimHtlcTimeoutTx)
        assertEquals(setOf(htlc1.id, htlc2.id, htlc3.id), alice6.state.remoteCommitPublished.outgoingHtlcs.values.toSet())
        assertEquals(alice6.state.remoteCommitPublished.outgoingHtlcs.keys, claimHtlcTimeoutTxs.map { it.txIn.first().outPoint }.toSet())
        actionsAlice6.hasWatchOutputsSpent(alice6.state.remoteCommitPublished.htlcOutputs)

        // Bob's commitment confirms.
        val (alice7, actionsAlice7) = alice6.process(ChannelCommand.WatchReceived(WatchConfirmedTriggered(alice0.channelId, WatchConfirmed.ClosingTxConfirmed, 42, 7, remoteCommitPublished.commitTx)))
        assertEquals(1, actionsAlice7.size)
        actionsAlice7.has<ChannelAction.Storage.StoreState>()

        // Alice extracts the preimage from Bob's HTLC-success and forwards it upstream.
        val (alice8, actionsAlice8) = alice7.process(ChannelCommand.WatchReceived(WatchSpentTriggered(alice0.channelId, WatchSpent.ClosingOutputSpent(50_000.sat), bobClosingTxs.htlcSuccessTxs.first())))
        val addSettled = actionsAlice8.filterIsInstance<ChannelAction.ProcessCmdRes.AddSettledFulfill>()
        assertEquals(setOf(htlc1, htlc2), addSettled.map { it.htlc }.toSet())
        assertEquals(setOf(ChannelAction.HtlcResult.Fulfill.OnChainFulfill(preimage)), addSettled.map { it.result }.toSet())

        // Alice's Claim-HTLC-timeout transaction confirms: we relay the failure upstream.
        val claimHtlcTimeout = claimHtlcTimeoutTxs.find { tx -> alice6.state.remoteCommitPublished.outgoingHtlcs.filter { it.value == htlc3.id }.contains(tx.txIn.first().outPoint) }
        assertNotNull(claimHtlcTimeout)
        val (alice9, actionsAlice9) = alice8.process(ChannelCommand.WatchReceived(WatchConfirmedTriggered(alice0.channelId, WatchConfirmed.ClosingTxConfirmed, 43, 1, claimHtlcTimeout)))
        assertIs<LNChannel<Closing>>(alice9)
        assertEquals(2, actionsAlice9.size)
        actionsAlice9.has<ChannelAction.Storage.StoreState>()
        val addFailed = actionsAlice9.filterIsInstance<ChannelAction.ProcessCmdRes.AddSettledFail>()
        assertEquals(setOf(htlc3), addFailed.map { it.htlc }.toSet())

        // The remaining transactions confirm.
        val watchConfirmed = listOf(
            WatchConfirmedTriggered(alice0.channelId, WatchConfirmed.ClosingTxConfirmed, 47, 0, bobClosingTxs.htlcSuccessTxs.first()),
            WatchConfirmedTriggered(alice0.channelId, WatchConfirmed.ClosingTxConfirmed, 47, 1, bobClosingTxs.htlcSuccessTxs.last()),
            WatchConfirmedTriggered(alice0.channelId, WatchConfirmed.ClosingTxConfirmed, 47, 0, mainTx),
        )
        confirmClosingTxs(alice9, watchConfirmed)
    }

    @Test
    fun `recv BITCOIN_OUTPUT_SPENT -- next remote commit -- extract preimage from next batch of HTLCs`() {
        val (alice0, bob0) = reachNormal()
        // Alice sends htlcs to Bob with the same payment_hash.
        val (nodes1, preimage, htlc1) = addHtlc(50_000_000.msat, alice0, bob0)
        val (alice2, bob2, htlc2) = addHtlc(makeCmdAdd(40_000_000.msat, bob0.staticParams.nodeParams.nodeId, alice0.currentBlockHeight.toLong(), preimage).second, nodes1.first, nodes1.second)
        assertEquals(htlc1.paymentHash, htlc2.paymentHash)
        // And another HTLC with a different payment_hash.
        val (nodes3, _, htlc3) = addHtlc(60_000_000.msat, alice2, bob2)
        val (alice3, bob3) = nodes3
        val (alice4, _) = alice3.process(ChannelCommand.Commitment.Sign)
        // We want to test what happens when we stop at that point.
        // But for Bob to create HTLC transactions, he must have received Alice's revocation.
        // So for the sake of the test, we exchange revocation and then reset Alice's state.
        val (_, bob4) = crossSign(alice3, bob3)

        // At that point, the HTLCs are not in Alice's commitment yet.
        val (bob5, remoteCommitPublished, bobClosingTxs5) = localClose(bob4)
        assertEquals(3, remoteCommitPublished.incomingHtlcs.size)
        // Bob doesn't have the preimage yet for any of those HTLCs.
        assertTrue(bobClosingTxs5.htlcSuccessTxs.isEmpty())
        // Bob receives the preimage for the first two HTLCs.
        val (bob6, actionsBob6) = bob5.process(ChannelCommand.Htlc.Settlement.Fulfill(htlc1.id, preimage))
        assertIs<LNChannel<Closing>>(bob6)
        val htlcSuccessTxs = actionsBob6.findPublishTxs(ChannelAction.Blockchain.PublishTx.Type.HtlcSuccessTx)
        assertEquals(2, htlcSuccessTxs.size)
        assertEquals(setOf(htlc1.id, htlc2.id), htlcSuccessTxs.map { tx -> remoteCommitPublished.incomingHtlcs[tx.txIn.first().outPoint] }.toSet())
        val batchHtlcSuccessTx = Transaction(2, htlcSuccessTxs.flatMap { it.txIn }, htlcSuccessTxs.flatMap { it.txOut }, 0)

        // Alice prepares Claim-HTLC-timeout transactions for each HTLC.
        val (alice5, actionsAlice5) = alice4.process(ChannelCommand.WatchReceived(WatchSpentTriggered(alice0.channelId, WatchSpent.ChannelSpent(TestConstants.fundingAmount), remoteCommitPublished.commitTx)))
        assertIs<LNChannel<Closing>>(alice5)
        val rcp = alice5.state.nextRemoteCommitPublished
        assertNotNull(rcp)
        val mainTx = actionsAlice5.hasPublishTx(ChannelAction.Blockchain.PublishTx.Type.ClaimRemoteDelayedOutputTx)
        assertEquals(setOf(htlc1.id, htlc2.id, htlc3.id), rcp.outgoingHtlcs.values.toSet())
        val claimHtlcTimeoutTxs = actionsAlice5.findPublishTxs(ChannelAction.Blockchain.PublishTx.Type.ClaimHtlcTimeoutTx)
        assertEquals(3, claimHtlcTimeoutTxs.size)
        actionsAlice5.hasWatchOutputsSpent(rcp.outgoingHtlcs.keys)

        // Alice extracts the preimage from Bob's batched HTLC-success and forwards it upstream.
        val (alice6, actionsAlice6) = alice5.process(ChannelCommand.WatchReceived(WatchSpentTriggered(alice0.channelId, WatchSpent.ClosingOutputSpent(50_000.sat), batchHtlcSuccessTx)))
        val addSettled = actionsAlice6.filterIsInstance<ChannelAction.ProcessCmdRes.AddSettledFulfill>()
        assertEquals(setOf(htlc1, htlc2), addSettled.map { it.htlc }.toSet())
        assertEquals(setOf(ChannelAction.HtlcResult.Fulfill.OnChainFulfill(preimage)), addSettled.map { it.result }.toSet())
        actionsAlice6.hasWatchConfirmed(batchHtlcSuccessTx.txid)

        // Alice's Claim-HTLC-timeout transaction confirms: we relay the failure upstream.
        val claimHtlcTimeout = claimHtlcTimeoutTxs.find { tx -> rcp.outgoingHtlcs.filter { it.value == htlc3.id }.contains(tx.txIn.first().outPoint) }
        assertNotNull(claimHtlcTimeout)
        val (alice7, actionsAlice7) = alice6.process(ChannelCommand.WatchReceived(WatchConfirmedTriggered(alice0.channelId, WatchConfirmed.ClosingTxConfirmed, 42, 0, claimHtlcTimeout)))
        assertIs<LNChannel<Closing>>(alice7)
        val addFailed = actionsAlice7.filterIsInstance<ChannelAction.ProcessCmdRes.AddSettledFail>()
        assertEquals(setOf(htlc3), addFailed.map { it.htlc }.toSet())

        // The remaining transactions confirm.
        val watchConfirmed = listOf(
            WatchConfirmedTriggered(alice0.channelId, WatchConfirmed.ClosingTxConfirmed, 40, 0, remoteCommitPublished.commitTx),
            WatchConfirmedTriggered(alice0.channelId, WatchConfirmed.ClosingTxConfirmed, 41, 1, batchHtlcSuccessTx),
            WatchConfirmedTriggered(alice0.channelId, WatchConfirmed.ClosingTxConfirmed, 47, 7, mainTx),
        )
        confirmClosingTxs(alice7, watchConfirmed)
    }

    @Test
    fun `recv ChannelEvent Restore -- next remote commit`() {
        val (alice0, bob0) = reachNormal()
        val (aliceClosing, remoteCommitPublished, closingTxs) = run {
            // alice sends an htlc to bob
            val (nodes1, preimage, _) = addHtlc(30_000_000.msat, alice0, bob0)
            val (alice1, bob1) = nodes1
            assertIs<Normal>(bob1.state)
            val bobCommitTx1 = bob1.signCommitTx()
            // add more htlcs that bob doesn't revoke
            val (_, cmd1) = makeCmdAdd(20_000_000.msat, bob0.staticParams.nodeParams.nodeId, alice1.currentBlockHeight.toLong(), preimage)
            val (alice2, bob2, _) = addHtlc(cmd1, alice1, bob1)
            val (alice3, actionsAlice3) = alice2.process(ChannelCommand.Commitment.Sign)
            val commitSig = actionsAlice3.hasOutgoingMessage<CommitSig>()
            val (bob3, actionsBob3) = bob2.process(ChannelCommand.MessageReceived(commitSig))
            assertIs<Normal>(bob3.state)
            actionsBob3.hasOutgoingMessage<RevokeAndAck>() // not forwarded to Alice (malicious Bob)
            // Bob publishes the next commit tx.
            val bobCommitTx2 = bob3.signCommitTx()
            assertNotEquals(bobCommitTx1.txid, bobCommitTx2.txid)
            remoteClose(bobCommitTx2, alice3, htlcTimeoutCount = 2)
        }

        assertNotNull(remoteCommitPublished.localOutput)
        assertTrue(remoteCommitPublished.incomingHtlcs.isEmpty())
        assertEquals(2, remoteCommitPublished.outgoingHtlcs.size)

        // Simulate a wallet restart
        val initState = LNChannel(aliceClosing.ctx, WaitForInit)
        val (alice1, actions1) = initState.process(ChannelCommand.Init.Restore(aliceClosing.state))
        assertTrue(alice1.state is Closing)
        assertEquals(aliceClosing, alice1)
        assertEquals(8, actions1.size)
        actions1.doesNotHave<ChannelAction.Storage.StoreOutgoingPayment.ViaClose>()
        actions1.hasWatchFundingSpent(aliceClosing.commitments.latest.fundingInput.txid)
        actions1.hasWatchConfirmed(remoteCommitPublished.commitTx.txid)
        actions1.hasPublishTx(closingTxs.mainTx)
        actions1.hasWatchOutputSpent(closingTxs.mainTx.txIn.first().outPoint)
        closingTxs.htlcTimeoutTxs.forEach { tx ->
            actions1.hasPublishTx(tx)
            actions1.hasWatchOutputSpent(tx.txIn.first().outPoint)
        }
    }

    @Test
    fun `recv ClosingTxConfirmed -- future remote commit`() {
        val (alice0, bob0) = reachNormal(bobUsePeerStorage = false)
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
            val (alice7, _) = alice6.process(ChannelCommand.Disconnected)
            assertIs<Offline>(alice7.state)
            val (bob7, _) = bob6.process(ChannelCommand.Disconnected)
            assertIs<Offline>(bob7.state)
            Pair(alice7, bob7)
        }

        val localInit = Init(alice0.commitments.channelParams.localParams.features)
        val remoteInit = Init(bob0.commitments.channelParams.localParams.features)

        // then we manually replace alice's state with an older one and reconnect them.
        val (alice1, aliceActions1) = LNChannel(alice0.ctx, Offline(alice0.state)).process(ChannelCommand.Connected(localInit, remoteInit))
        assertIs<Syncing>(alice1.state)
        val channelReestablishA = aliceActions1.findOutgoingMessage<ChannelReestablish>()
        val (bob1, bobActions1) = bobDisconnected.process(ChannelCommand.Connected(remoteInit, localInit))
        assertIs<Syncing>(bob1.state)
        val channelReestablishB = bobActions1.findOutgoingMessage<ChannelReestablish>()

        // alice realizes it has an old state and asks bob to publish its current commitment
        val (alice2, aliceActions2) = alice1.process(ChannelCommand.MessageReceived(channelReestablishB))
        assertIs<WaitForRemotePublishFutureCommitment>(alice2.state)
        val errorA = aliceActions2.findOutgoingMessage<Error>()
        assertEquals(PleasePublishYourCommitment(alice2.channelId).message, errorA.toAscii())

        // bob detects that alice has an outdated commitment and stands by
        val (bob2, bobActions2) = bob1.process(ChannelCommand.MessageReceived(channelReestablishA))
        assertIs<Syncing>(bob2.state)
        assertTrue { bobActions2.isEmpty() }

        // bob receives the error from alice and publishes his local commitment tx
        val (bob3, bobActions3) = bob2.process(ChannelCommand.MessageReceived(errorA))
        assertIs<Closing>(bob3.state)
        val bobCommitTx = bob2.signCommitTx()
        assertEquals(6, bobCommitTx.txOut.size) // 2 main outputs + 2 anchors + 2 HTLCs
        bobActions3.find<ChannelAction.Storage.StoreOutgoingPayment.ViaClose>().also {
            assertEquals(bobCommitTx.txid, it.txId)
            assertEquals(ChannelClosingType.Local, it.closingType)
            assertTrue(it.isSentToDefaultAddress)
        }

        val (alice3, aliceActions3) = alice2.process(ChannelCommand.WatchReceived(WatchSpentTriggered(alice0.channelId, WatchSpent.ChannelSpent(TestConstants.fundingAmount), bobCommitTx)))
        assertIs<Closing>(alice3.state)
        aliceActions3.find<ChannelAction.Storage.StoreOutgoingPayment.ViaClose>().also {
            assertEquals(bobCommitTx.txid, it.txId)
            assertEquals(ChannelClosingType.Remote, it.closingType)
            assertTrue(it.isSentToDefaultAddress)
        }
        val futureRemoteCommitPublished = alice3.state.futureRemoteCommitPublished
        assertNotNull(futureRemoteCommitPublished)
        aliceActions3.hasWatchConfirmed(bobCommitTx.txid)
        // alice is able to claim its main output
        val mainTx = aliceActions3.hasPublishTx(ChannelAction.Blockchain.PublishTx.Type.ClaimRemoteDelayedOutputTx)
        Transaction.correctlySpends(mainTx, listOf(bobCommitTx), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)

        // simulate a wallet restart
        run {
            val initState = LNChannel(alice3.ctx, WaitForInit)
            val (alice4, actions4) = initState.process(ChannelCommand.Init.Restore(alice3.state))
            assertIs<Closing>(alice4.state)
            assertEquals(alice3, alice4)
            actions4.hasWatchConfirmed(bobCommitTx.txid)
            actions4.hasWatchOutputSpent(mainTx.txIn.first().outPoint)
            actions4.doesNotHave<ChannelAction.Storage.StoreOutgoingPayment.ViaClose>()
        }

        val (alice4, aliceActions4) = alice3.process(ChannelCommand.WatchReceived(WatchConfirmedTriggered(alice0.channelId, WatchConfirmed.ClosingTxConfirmed, 50, 0, bobCommitTx)))
        assertIs<Closing>(alice4.state)
        assertEquals(listOf(ChannelAction.Storage.StoreState(alice4.state)), aliceActions4)

        val (alice5, aliceActions5) = alice4.process(ChannelCommand.WatchReceived(WatchConfirmedTriggered(alice0.channelId, WatchConfirmed.ClosingTxConfirmed, 60, 3, mainTx)))
        assertIs<Closed>(alice5.state)
        assertEquals(
            listOf(ChannelAction.Storage.StoreState(alice5.state)),
            aliceActions5.filterIsInstance<ChannelAction.Storage.StoreState>()
        )
        assertContains(aliceActions5, ChannelAction.Storage.SetLocked(bobCommitTx.txid))
    }

    @Test
    fun `recv ChannelSpent -- one revoked tx`() {
        val (alice0, _, bobCommitTxs, htlcsAlice, htlcsBob) = prepareRevokedClose()

        // bob publishes one of his revoked txs
        val bobRevokedTx = bobCommitTxs[1].commitTx
        assertEquals(6, bobRevokedTx.txOut.size)

        val (alice1, aliceActions1) = alice0.process(ChannelCommand.WatchReceived(WatchSpentTriggered(alice0.channelId, WatchSpent.ChannelSpent(TestConstants.fundingAmount), bobRevokedTx)))
        assertIs<Closing>(alice1.state)
        aliceActions1.hasOutgoingMessage<Error>()
        aliceActions1.has<ChannelAction.Storage.StoreState>()
        aliceActions1.find<ChannelAction.Storage.StoreOutgoingPayment.ViaClose>().also {
            assertEquals(bobRevokedTx.txid, it.txId)
            assertEquals(ChannelClosingType.Revoked, it.closingType)
            assertTrue(it.isSentToDefaultAddress)
        }

        // alice creates penalty txs
        val (mainTx, penaltyTx) = run {
            assertEquals(1, alice1.state.revokedCommitPublished.size)
            val revokedCommitPublished = alice1.state.revokedCommitPublished[0]
            assertEquals(bobRevokedTx, revokedCommitPublished.commitTx)
            assertNotNull(revokedCommitPublished.localOutput)
            assertNotNull(revokedCommitPublished.remoteOutput)
            assertTrue(revokedCommitPublished.htlcOutputs.isEmpty())
            assertTrue(revokedCommitPublished.htlcDelayedOutputs.isEmpty())
            val mainTx = aliceActions1.hasPublishTx(ChannelAction.Blockchain.PublishTx.Type.ClaimRemoteDelayedOutputTx)
            assertEquals(revokedCommitPublished.localOutput, mainTx.txIn.first().outPoint)
            Transaction.correctlySpends(mainTx, bobRevokedTx, ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
            val penaltyTx = aliceActions1.hasPublishTx(ChannelAction.Blockchain.PublishTx.Type.MainPenaltyTx)
            assertEquals(revokedCommitPublished.remoteOutput, penaltyTx.txIn.first().outPoint)
            Transaction.correctlySpends(penaltyTx, bobRevokedTx, ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
            assertTrue(aliceActions1.findPublishTxs(ChannelAction.Blockchain.PublishTx.Type.HtlcPenaltyTx).isEmpty())
            aliceActions1.hasWatchConfirmed(bobRevokedTx.txid)
            aliceActions1.hasWatchOutputsSpent(setOf(revokedCommitPublished.localOutput, revokedCommitPublished.remoteOutput))
            aliceActions1.hasWatchOutputsSpent(revokedCommitPublished.htlcOutputs)
            Pair(mainTx, penaltyTx)
        }

        // alice fetches information about the revoked htlcs
        assertEquals(ChannelAction.Storage.GetHtlcInfos(bobRevokedTx.txid, 2), aliceActions1.find())
        val htlcInfos = listOf(
            ChannelAction.Storage.HtlcInfo(alice0.channelId, 2, htlcsAlice[0].paymentHash, htlcsAlice[0].cltvExpiry),
            ChannelAction.Storage.HtlcInfo(alice0.channelId, 2, htlcsBob[0].paymentHash, htlcsBob[0].cltvExpiry),
        )
        val (alice2, aliceActions2) = alice1.process(ChannelCommand.Closing.GetHtlcInfosResponse(bobRevokedTx.txid, htlcInfos))
        assertIs<LNChannel<Closing>>(alice2)
        assertIs<Closing>(alice2.state)
        assertNull(aliceActions2.findOutgoingMessageOpt<Error>())
        aliceActions2.has<ChannelAction.Storage.StoreState>()
        aliceActions2.doesNotHave<ChannelAction.Storage.StoreOutgoingPayment>()

        // alice creates htlc penalty txs
        assertEquals(1, alice2.state.revokedCommitPublished.size)
        val revokedCommitPublished = alice2.state.revokedCommitPublished[0]
        assertEquals(bobRevokedTx, revokedCommitPublished.commitTx)
        assertEquals(2, revokedCommitPublished.htlcOutputs.size)
        assertTrue(revokedCommitPublished.htlcDelayedOutputs.isEmpty())
        val htlcPenaltyTxs = aliceActions2.findPublishTxs(ChannelAction.Blockchain.PublishTx.Type.HtlcPenaltyTx)
        assertEquals(2, htlcPenaltyTxs.size)
        htlcPenaltyTxs.forEach { Transaction.correctlySpends(it, bobRevokedTx, ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS) }

        // simulate a wallet restart
        run {
            val initState = LNChannel(alice2.ctx, WaitForInit)
            val (alice3, actions3) = initState.process(ChannelCommand.Init.Restore(alice2.state))
            assertIs<Closing>(alice3.state)
            assertEquals(alice2, alice3)
            actions3.doesNotHave<ChannelAction.Storage.StoreOutgoingPayment>()
            // alice republishes transactions
            actions3.hasPublishTx(mainTx)
            actions3.hasPublishTx(penaltyTx)
            assertEquals(ChannelAction.Storage.GetHtlcInfos(bobRevokedTx.txid, 2), actions3.find())
            val (alice4, actions4) = alice3.process(ChannelCommand.Closing.GetHtlcInfosResponse(bobRevokedTx.txid, htlcInfos))
            assertIs<Closing>(alice4.state)
            assertEquals(alice2, alice4)
            htlcPenaltyTxs.forEach { tx -> actions4.hasPublishTx(tx) }
            actions4.hasWatchOutputsSpent((listOf(mainTx, penaltyTx) + htlcPenaltyTxs).map { it.txIn.first().outPoint }.toSet())
        }

        val watchConfirmed = listOf(
            WatchConfirmedTriggered(alice0.channelId, WatchConfirmed.ClosingTxConfirmed, 42, 0, revokedCommitPublished.commitTx),
            WatchConfirmedTriggered(alice0.channelId, WatchConfirmed.ClosingTxConfirmed, 43, 0, mainTx),
            WatchConfirmedTriggered(alice0.channelId, WatchConfirmed.ClosingTxConfirmed, 43, 5, penaltyTx),
            WatchConfirmedTriggered(alice0.channelId, WatchConfirmed.ClosingTxConfirmed, 50, 1, htlcPenaltyTxs.last()),
            WatchConfirmedTriggered(alice0.channelId, WatchConfirmed.ClosingTxConfirmed, 52, 2, htlcPenaltyTxs.first()),
        )
        confirmClosingTxs(alice2, watchConfirmed)
    }

    @Test
    fun `recv ChannelSpent -- multiple revoked tx`() {
        val (alice0, _, bobCommitTxs, htlcsAlice, htlcsBob) = prepareRevokedClose()
        assertEquals(bobCommitTxs.size, bobCommitTxs.map { it.commitTx.txid }.toSet().size) // all commit txs are distinct

        // bob publishes one of his revoked txs
        val (alice1, aliceActions1) = alice0.process(ChannelCommand.WatchReceived(WatchSpentTriggered(alice0.channelId, WatchSpent.ChannelSpent(TestConstants.fundingAmount), bobCommitTxs[0].commitTx)))
        assertIs<Closing>(alice1.state)
        aliceActions1.hasOutgoingMessage<Error>()
        aliceActions1.has<ChannelAction.Storage.StoreState>()
        aliceActions1.find<ChannelAction.Storage.StoreOutgoingPayment.ViaClose>().also {
            assertEquals(bobCommitTxs[0].commitTx.txid, it.txId)
            assertEquals(ChannelClosingType.Revoked, it.closingType)
            assertTrue(it.isSentToDefaultAddress)
        }
        assertEquals(1, alice1.state.revokedCommitPublished.size)

        // alice creates penalty txs
        run {
            // alice publishes txs for the main outputs
            assertEquals(2, aliceActions1.findPublishTxs().size)
            val mainTx = aliceActions1.hasPublishTx(ChannelAction.Blockchain.PublishTx.Type.ClaimRemoteDelayedOutputTx)
            val penaltyTx = aliceActions1.hasPublishTx(ChannelAction.Blockchain.PublishTx.Type.MainPenaltyTx)
            listOf(mainTx, penaltyTx).forEach { tx -> Transaction.correctlySpends(tx, bobCommitTxs[0].commitTx, ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS) }
            aliceActions1.hasWatchConfirmed(bobCommitTxs[0].commitTx.txid)
            aliceActions1.hasWatchOutputsSpent(listOf(mainTx, penaltyTx).map { it.txIn.first().outPoint }.toSet())
            // alice fetches information about the revoked htlcs
            assertEquals(ChannelAction.Storage.GetHtlcInfos(bobCommitTxs[0].commitTx.txid, 0), aliceActions1.find())
        }

        // bob publishes another one of his revoked txs
        val (alice2, aliceActions2) = alice1.process(ChannelCommand.WatchReceived(WatchSpentTriggered(alice0.channelId, WatchSpent.ChannelSpent(TestConstants.fundingAmount), bobCommitTxs[1].commitTx)))
        assertIs<Closing>(alice2.state)
        aliceActions2.hasOutgoingMessage<Error>()
        aliceActions2.has<ChannelAction.Storage.StoreState>()
        aliceActions2.doesNotHave<ChannelAction.Storage.StoreOutgoingPayment>()
        assertEquals(2, alice2.state.revokedCommitPublished.size)

        // alice creates penalty txs
        val (mainTx, penaltyTx) = run {
            // alice publishes txs for the main outputs
            assertEquals(2, aliceActions2.findPublishTxs().size)
            val mainTx = aliceActions2.hasPublishTx(ChannelAction.Blockchain.PublishTx.Type.ClaimRemoteDelayedOutputTx)
            val penaltyTx = aliceActions2.hasPublishTx(ChannelAction.Blockchain.PublishTx.Type.MainPenaltyTx)
            listOf(mainTx, penaltyTx).forEach { tx -> Transaction.correctlySpends(tx, bobCommitTxs[1].commitTx, ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS) }
            aliceActions2.hasWatchConfirmed(bobCommitTxs[1].commitTx.txid)
            aliceActions2.hasWatchOutputsSpent(listOf(mainTx, penaltyTx).map { it.txIn.first().outPoint }.toSet())
            // alice fetches information about the revoked htlcs
            assertEquals(ChannelAction.Storage.GetHtlcInfos(bobCommitTxs[1].commitTx.txid, 2), aliceActions2.find())
            Pair(mainTx, penaltyTx)
        }

        val (alice3, aliceActions3) = alice2.process(ChannelCommand.Closing.GetHtlcInfosResponse(bobCommitTxs[0].commitTx.txid, listOf()))
        assertIs<Closing>(alice3.state)
        assertNull(aliceActions3.findOutgoingMessageOpt<Error>())
        aliceActions3.has<ChannelAction.Storage.StoreState>()
        aliceActions3.doesNotHave<ChannelAction.Storage.StoreOutgoingPayment>()
        assertEquals(2, alice3.state.revokedCommitPublished.size)
        alice3.state.revokedCommitPublished.forEach { rvk ->
            assertNotNull(rvk.localOutput)
            assertNotNull(rvk.remoteOutput)
            assertTrue(rvk.htlcOutputs.isEmpty())
            assertTrue(rvk.htlcDelayedOutputs.isEmpty())
        }

        val htlcInfos = listOf(
            ChannelAction.Storage.HtlcInfo(alice0.channelId, 2, htlcsAlice[0].paymentHash, htlcsAlice[0].cltvExpiry),
            ChannelAction.Storage.HtlcInfo(alice0.channelId, 2, htlcsBob[0].paymentHash, htlcsBob[0].cltvExpiry),
        )
        val (alice4, aliceActions4) = alice3.process(ChannelCommand.Closing.GetHtlcInfosResponse(bobCommitTxs[1].commitTx.txid, htlcInfos))
        assertIs<LNChannel<Closing>>(alice4)
        assertIs<Closing>(alice4.state)
        assertNull(aliceActions4.findOutgoingMessageOpt<Error>())
        aliceActions4.has<ChannelAction.Storage.StoreState>()
        aliceActions4.doesNotHave<ChannelAction.Storage.StoreOutgoingPayment>()
        // alice creates htlc penalty txs
        assertEquals(2, alice4.state.revokedCommitPublished.size)
        val revokedCommitPublished = alice4.state.revokedCommitPublished[1]
        assertEquals(bobCommitTxs[1].commitTx, revokedCommitPublished.commitTx)
        val htlcPenaltyTxs = aliceActions4.findPublishTxs(ChannelAction.Blockchain.PublishTx.Type.HtlcPenaltyTx)
        assertEquals(2, revokedCommitPublished.htlcOutputs.size)
        assertEquals(2, htlcPenaltyTxs.size)
        assertEquals(htlcPenaltyTxs.map { it.txIn.first().outPoint }.toSet(), revokedCommitPublished.htlcOutputs)
        aliceActions4.hasWatchOutputsSpent(revokedCommitPublished.htlcOutputs)
        assertTrue(revokedCommitPublished.htlcDelayedOutputs.isEmpty())
        htlcPenaltyTxs.forEach { Transaction.correctlySpends(it, bobCommitTxs[1].commitTx, ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS) }

        // this revoked transaction is the one to confirm
        val watchConfirmed = listOf(
            WatchConfirmedTriggered(alice0.channelId, WatchConfirmed.ClosingTxConfirmed, 42, 0, revokedCommitPublished.commitTx),
            WatchConfirmedTriggered(alice0.channelId, WatchConfirmed.ClosingTxConfirmed, 43, 0, mainTx),
            WatchConfirmedTriggered(alice0.channelId, WatchConfirmed.ClosingTxConfirmed, 43, 5, penaltyTx),
            WatchConfirmedTriggered(alice0.channelId, WatchConfirmed.ClosingTxConfirmed, 50, 1, htlcPenaltyTxs.last()),
            WatchConfirmedTriggered(alice0.channelId, WatchConfirmed.ClosingTxConfirmed, 52, 2, htlcPenaltyTxs.first()),
        )
        confirmClosingTxs(alice4, watchConfirmed)
    }

    @Test
    fun `recv ClosingTxConfirmed -- one revoked tx + pending htlcs`() {
        val (alice0, bob0) = reachNormal()
        // bob's first commit tx doesn't contain any htlc
        assertEquals(4, bob0.signCommitTx().txOut.size) // 2 main outputs + 2 anchors

        // bob's second commit tx contains 2 incoming htlcs
        val (alice1, bob1, htlcs1) = run {
            val (nodes1, _, htlc1) = addHtlc(35_000_000.msat, alice0, bob0)
            val (alice1, bob1) = nodes1
            val (nodes2, _, htlc2) = addHtlc(20_000_000.msat, alice1, bob1)
            val (alice2, bob2) = nodes2
            val (alice3, bob3) = crossSign(alice2, bob2)
            assertIs<Normal>(bob3.state)
            assertEquals(6, bob3.signCommitTx().txOut.size)
            Triple(alice3, bob3, listOf(htlc1, htlc2))
        }

        // bob's third commit tx contains 1 of the previous htlcs and 2 new htlcs
        val (alice2, _, htlcs2) = run {
            val (nodes2, _, htlc3) = addHtlc(25_000_000.msat, alice1, bob1)
            val (alice2, bob2) = nodes2
            val (nodes3, _, htlc4) = addHtlc(18_000_000.msat, alice2, bob2)
            val (alice3, bob3) = nodes3
            val (alice4, bob4) = failHtlc(htlcs1[0].id, alice3, bob3)
            assertIs<ChannelStateWithCommitments>(alice4.state)
            assertIs<ChannelStateWithCommitments>(bob4.state)
            val (alice5, bob5) = crossSign(alice4, bob4)
            assertIs<Normal>(bob5.state)
            assertEquals(7, bob5.signCommitTx().txOut.size)
            Triple(alice5, bob5, listOf(htlc3, htlc4))
        }

        // bob publishes a revoked tx
        val bobRevokedTx = bob1.signCommitTx()
        val (alice3, actions3) = alice2.process(ChannelCommand.WatchReceived(WatchSpentTriggered(alice0.channelId, WatchSpent.ChannelSpent(TestConstants.fundingAmount), bobRevokedTx)))
        assertIs<Closing>(alice3.state)
        actions3.hasOutgoingMessage<Error>()
        actions3.find<ChannelAction.Storage.StoreOutgoingPayment.ViaClose>().also {
            assertEquals(bobRevokedTx.txid, it.txId)
            assertEquals(ChannelClosingType.Revoked, it.closingType)
            assertTrue(it.isSentToDefaultAddress)
        }

        val htlcInfos = listOf(
            ChannelAction.Storage.HtlcInfo(alice0.channelId, 1, htlcs1[0].paymentHash, htlcs1[0].cltvExpiry),
            ChannelAction.Storage.HtlcInfo(alice0.channelId, 1, htlcs1[1].paymentHash, htlcs1[1].cltvExpiry)
        )
        val (alice4, actions4) = alice3.process(ChannelCommand.Closing.GetHtlcInfosResponse(bobRevokedTx.txid, htlcInfos))
        assertIs<Closing>(alice4.state)
        actions4.doesNotHave<ChannelAction.Storage.StoreOutgoingPayment>()

        // bob's revoked tx confirms: alice should fail all pending htlcs
        val (alice5, actions5) = alice4.process(ChannelCommand.WatchReceived(WatchConfirmedTriggered(alice0.channelId, WatchConfirmed.ClosingTxConfirmed, 100, 3, bobRevokedTx)))
        assertTrue(alice5.state is Closing)
        actions5.doesNotHave<ChannelAction.Storage.StoreOutgoingPayment>()
        val addSettledActions = actions5.filterIsInstance<ChannelAction.ProcessCmdRes>()
        assertEquals(3, addSettledActions.size)
        val addSettledFails = addSettledActions.filterIsInstance<ChannelAction.ProcessCmdRes.AddSettledFail>()
        assertEquals(setOf(htlcs1[1], htlcs2[0], htlcs2[1]), addSettledFails.map { it.htlc }.toSet())
        assertTrue(addSettledFails.all { it.result is ChannelAction.HtlcResult.Fail.OnChainFail })
    }

    @Test
    fun `recv BITCOIN_OUTPUT_SPENT -- one revoked tx + counterparty published HtlcSuccess tx`() {
        val (alice0, _, bobCommitTxs, htlcsAlice, htlcsBob) = prepareRevokedClose()

        // Bob publishes one of his revoked txs.
        val bobRevokedTx = bobCommitTxs[2].commitTx
        assertEquals(8, bobRevokedTx.txOut.size)

        val (alice1, aliceActions1) = alice0.process(ChannelCommand.WatchReceived(WatchSpentTriggered(alice0.channelId, WatchSpent.ChannelSpent(TestConstants.fundingAmount), bobRevokedTx)))
        assertIs<Closing>(alice1.state)
        aliceActions1.hasOutgoingMessage<Error>()
        aliceActions1.has<ChannelAction.Storage.StoreState>()
        aliceActions1.find<ChannelAction.Storage.StoreOutgoingPayment.ViaClose>().also {
            assertEquals(bobRevokedTx.txid, it.txId)
            assertEquals(ChannelClosingType.Revoked, it.closingType)
            assertTrue(it.isSentToDefaultAddress)
        }

        // Alice creates penalty txs for the main outputs.
        val (mainTx, penaltyTx) = run {
            val revokedCommitPublished = alice1.state.revokedCommitPublished[0]
            assertNotNull(revokedCommitPublished.localOutput)
            assertNotNull(revokedCommitPublished.remoteOutput)
            assertTrue(revokedCommitPublished.htlcOutputs.isEmpty())
            assertTrue(revokedCommitPublished.htlcDelayedOutputs.isEmpty())
            // alice publishes txs for the main outputs and sets watches
            assertEquals(2, aliceActions1.findPublishTxs().size)
            val mainTx = aliceActions1.hasPublishTx(ChannelAction.Blockchain.PublishTx.Type.ClaimRemoteDelayedOutputTx)
            val penaltyTx = aliceActions1.hasPublishTx(ChannelAction.Blockchain.PublishTx.Type.MainPenaltyTx)
            aliceActions1.hasWatchConfirmed(bobRevokedTx.txid)
            aliceActions1.hasWatchOutputsSpent(listOf(mainTx, penaltyTx).map { it.txIn.first().outPoint }.toSet())
            Pair(mainTx, penaltyTx)
        }

        // Alice fetches information about the revoked htlcs.
        assertEquals(ChannelAction.Storage.GetHtlcInfos(bobRevokedTx.txid, 4), aliceActions1.find())
        val htlcInfos = listOf(
            ChannelAction.Storage.HtlcInfo(alice0.channelId, 4, htlcsAlice[0].paymentHash, htlcsAlice[0].cltvExpiry),
            ChannelAction.Storage.HtlcInfo(alice0.channelId, 4, htlcsAlice[1].paymentHash, htlcsAlice[1].cltvExpiry),
            ChannelAction.Storage.HtlcInfo(alice0.channelId, 4, htlcsBob[0].paymentHash, htlcsBob[0].cltvExpiry),
            ChannelAction.Storage.HtlcInfo(alice0.channelId, 4, htlcsBob[1].paymentHash, htlcsBob[1].cltvExpiry),
        )
        val (alice2, aliceActions2) = alice1.process(ChannelCommand.Closing.GetHtlcInfosResponse(bobRevokedTx.txid, htlcInfos))
        assertIs<Closing>(alice2.state)
        assertNull(aliceActions2.findOutgoingMessageOpt<Error>())
        aliceActions2.has<ChannelAction.Storage.StoreState>()
        aliceActions2.doesNotHave<ChannelAction.Storage.StoreOutgoingPayment>()

        // Alice creates htlc penalty txs.
        val htlcPenaltyTxs = run {
            val revokedCommitPublished = alice2.state.revokedCommitPublished[0]
            assertNotNull(revokedCommitPublished.localOutput)
            assertNotNull(revokedCommitPublished.remoteOutput)
            assertEquals(4, revokedCommitPublished.htlcOutputs.size)
            assertTrue(revokedCommitPublished.htlcDelayedOutputs.isEmpty())
            val htlcPenaltyTxs = aliceActions2.findPublishTxs(ChannelAction.Blockchain.PublishTx.Type.HtlcPenaltyTx)
            assertEquals(4, htlcPenaltyTxs.size)
            htlcPenaltyTxs.forEach { Transaction.correctlySpends(it, bobRevokedTx, ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS) }
            aliceActions2.hasWatchOutputsSpent(htlcPenaltyTxs.map { it.txIn.first().outPoint }.toSet())
            htlcPenaltyTxs
        }

        // Bob manages to claim 2 htlc outputs before alice can penalize him: 1 htlc-success and 1 htlc-timeout.
        val bobHtlcSuccessTx = bobCommitTxs[2].htlcSuccessTxs.find { it.paymentHash == htlcsAlice[0].paymentHash }!!
        val bobHtlcTimeoutTx = bobCommitTxs[2].htlcTimeoutTxs.find { it.paymentHash == htlcsBob[1].paymentHash }!!
        val bobOutpoints = listOf(bobHtlcTimeoutTx, bobHtlcSuccessTx).map { it.input.outPoint }.toSet()
        assertEquals(2, bobOutpoints.size)

        // Alice reacts by publishing penalty txs that spend bob's htlc transactions.
        val (alice3, actions3) = alice2.process(ChannelCommand.WatchReceived(WatchSpentTriggered(alice0.channelId, WatchSpent.ClosingOutputSpent(bobHtlcSuccessTx.amountIn), bobHtlcSuccessTx.tx)))
        assertEquals(1, actions3.size)
        actions3.hasWatchConfirmed(bobHtlcSuccessTx.tx.txid)
        val (alice4, actions4) = alice3.process(ChannelCommand.WatchReceived(WatchConfirmedTriggered(alice0.channelId, WatchConfirmed.ClosingTxConfirmed, 0, 0, bobHtlcSuccessTx.tx)))
        assertIs<Closing>(alice4.state)
        assertEquals(3, actions4.size)
        assertEquals(1, alice4.state.revokedCommitPublished[0].htlcDelayedOutputs.size)
        assertTrue(actions4.contains(ChannelAction.Storage.StoreState(alice4.state)))
        val htlcSuccessPenalty = actions4.hasPublishTx(ChannelAction.Blockchain.PublishTx.Type.ClaimHtlcDelayedOutputPenaltyTx)
        Transaction.correctlySpends(htlcSuccessPenalty, bobHtlcSuccessTx.tx, ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
        actions4.hasWatchOutputSpent(htlcSuccessPenalty.txIn.first().outPoint)

        val (alice5, actions5) = alice4.process(ChannelCommand.WatchReceived(WatchSpentTriggered(alice0.channelId, WatchSpent.ClosingOutputSpent(bobHtlcTimeoutTx.amountIn), bobHtlcTimeoutTx.tx)))
        assertEquals(1, actions5.size)
        actions5.hasWatchConfirmed(bobHtlcTimeoutTx.tx.txid)
        val (alice6, actions6) = alice5.process(ChannelCommand.WatchReceived(WatchConfirmedTriggered(alice0.channelId, WatchConfirmed.ClosingTxConfirmed, 0, 0, bobHtlcTimeoutTx.tx)))
        assertIs<Closing>(alice6.state)
        assertEquals(3, actions6.size)
        assertEquals(2, alice6.state.revokedCommitPublished[0].htlcDelayedOutputs.size)
        assertTrue(actions6.contains(ChannelAction.Storage.StoreState(alice6.state)))
        val htlcTimeoutPenalty = actions6.hasPublishTx(ChannelAction.Blockchain.PublishTx.Type.ClaimHtlcDelayedOutputPenaltyTx)
        Transaction.correctlySpends(htlcTimeoutPenalty, bobHtlcTimeoutTx.tx, ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
        actions6.hasWatchOutputSpent(htlcTimeoutPenalty.txIn.first().outPoint)

        val remainingHtlcPenaltyTxs = htlcPenaltyTxs.filterNot { bobOutpoints.contains(it.txIn.first().outPoint) }
        assertEquals(2, remainingHtlcPenaltyTxs.size)

        // We re-publish penalty transactions on restart.
        run {
            val initState = LNChannel(alice6.ctx, WaitForInit)
            val (alice7, actions7) = initState.process(ChannelCommand.Init.Restore(alice6.state))
            assertIs<Closing>(alice7.state)
            assertEquals(alice6, alice7)
            actions7.hasPublishTx(mainTx)
            actions7.hasPublishTx(penaltyTx)
            assertEquals(ChannelAction.Storage.GetHtlcInfos(bobRevokedTx.txid, 4), actions7.find())
            val (alice8, actions8) = alice7.process(ChannelCommand.Closing.GetHtlcInfosResponse(bobRevokedTx.txid, htlcInfos))
            assertIs<LNChannel<Closing>>(alice8)
            assertIs<Closing>(alice8.state)
            assertEquals(alice7, alice8)
            assertEquals(8, actions8.size)
            actions8.has<ChannelAction.Storage.StoreState>()
            actions8.hasWatchConfirmed(bobRevokedTx.txid)
            remainingHtlcPenaltyTxs.forEach { tx -> actions8.hasPublishTx(tx) }
            actions8.hasWatchOutputsSpent((listOf(mainTx, penaltyTx) + remainingHtlcPenaltyTxs).map { it.txIn.first().outPoint }.toSet())

            val watchConfirmed = listOf(
                WatchConfirmedTriggered(alice0.channelId, WatchConfirmed.ClosingTxConfirmed, 42, 0, bobRevokedTx),
                WatchConfirmedTriggered(alice0.channelId, WatchConfirmed.ClosingTxConfirmed, 43, 0, mainTx),
                WatchConfirmedTriggered(alice0.channelId, WatchConfirmed.ClosingTxConfirmed, 43, 5, penaltyTx),
                WatchConfirmedTriggered(alice0.channelId, WatchConfirmed.ClosingTxConfirmed, 50, 1, remainingHtlcPenaltyTxs[1]),
                WatchConfirmedTriggered(alice0.channelId, WatchConfirmed.ClosingTxConfirmed, 50, 3, remainingHtlcPenaltyTxs[0]),
                WatchConfirmedTriggered(alice0.channelId, WatchConfirmed.ClosingTxConfirmed, 51, 3, htlcSuccessPenalty),
                WatchConfirmedTriggered(alice0.channelId, WatchConfirmed.ClosingTxConfirmed, 51, 1, htlcTimeoutPenalty),
            )
            confirmClosingTxs(alice8, watchConfirmed)
        }
    }

    @Test
    fun `recv BITCOIN_OUTPUT_SPENT -- one revoked tx + counterparty published aggregated htlc tx`() {
        val (alice0, _, bobCommitTxs, htlcsAlice, htlcsBob) = prepareRevokedClose()

        // bob publishes one of his revoked txs
        val bobRevokedTx = bobCommitTxs[2].commitTx
        assertEquals(8, bobRevokedTx.txOut.size)

        val (alice1, _) = alice0.process(ChannelCommand.WatchReceived(WatchSpentTriggered(alice0.channelId, WatchSpent.ChannelSpent(TestConstants.fundingAmount), bobRevokedTx)))
        assertIs<Closing>(alice1.state)

        // alice fetches information about the revoked htlcs
        val htlcInfos = listOf(
            ChannelAction.Storage.HtlcInfo(alice0.channelId, 4, htlcsAlice[0].paymentHash, htlcsAlice[0].cltvExpiry),
            ChannelAction.Storage.HtlcInfo(alice0.channelId, 4, htlcsAlice[1].paymentHash, htlcsAlice[1].cltvExpiry),
            ChannelAction.Storage.HtlcInfo(alice0.channelId, 4, htlcsBob[0].paymentHash, htlcsBob[0].cltvExpiry),
            ChannelAction.Storage.HtlcInfo(alice0.channelId, 4, htlcsBob[1].paymentHash, htlcsBob[1].cltvExpiry),
        )
        val (alice2, _) = alice1.process(ChannelCommand.Closing.GetHtlcInfosResponse(bobRevokedTx.txid, htlcInfos))
        assertIs<Closing>(alice2.state)

        // bob claims multiple htlc outputs in a single transaction (this is possible with anchor outputs because signatures
        // use sighash_single | sighash_anyonecanpay)
        assertEquals(2, bobCommitTxs[2].htlcSuccessTxs.size)
        assertEquals(2, bobCommitTxs[2].htlcTimeoutTxs.size)
        val bobHtlcTx = Transaction(
            2,
            listOf(
                TxIn(OutPoint(TxId(Lightning.randomBytes32()), 4), listOf(), 1), // unrelated utxo (maybe used for fee bumping)
                bobCommitTxs[2].htlcSuccessTxs[0].tx.txIn.first(),
                bobCommitTxs[2].htlcTimeoutTxs[1].tx.txIn.first(),
                bobCommitTxs[2].htlcTimeoutTxs[0].tx.txIn.first(),
                bobCommitTxs[2].htlcSuccessTxs[1].tx.txIn.first(),
            ),
            listOf(
                TxOut(10_000.sat, listOf()), // unrelated output (maybe change output)
                bobCommitTxs[2].htlcSuccessTxs[0].tx.txOut.first(),
                bobCommitTxs[2].htlcTimeoutTxs[1].tx.txOut.first(),
                bobCommitTxs[2].htlcTimeoutTxs[0].tx.txOut.first(),
                bobCommitTxs[2].htlcSuccessTxs[1].tx.txOut.first(),
            ),
            0
        )

        val (alice3, actions3) = alice2.process(ChannelCommand.WatchReceived(WatchSpentTriggered(alice0.channelId, WatchSpent.ClosingOutputSpent(0.sat), bobHtlcTx)))
        assertEquals(1, actions3.size)
        actions3.hasWatchConfirmed(bobHtlcTx.txid)
        val (alice4, actions4) = alice3.process(ChannelCommand.WatchReceived(WatchConfirmedTriggered(alice0.channelId, WatchConfirmed.ClosingTxConfirmed, 0, 0, bobHtlcTx)))
        assertIs<Closing>(alice4.state)
        assertEquals(9, actions4.size)
        assertTrue(actions4.contains(ChannelAction.Storage.StoreState(alice4.state)))
        assertEquals(4, alice4.state.revokedCommitPublished[0].htlcDelayedOutputs.size)
        assertEquals(setOf(1, 2, 3, 4).map { OutPoint(bobHtlcTx.txid, it.toLong()) }.toSet(), alice4.state.revokedCommitPublished[0].htlcDelayedOutputs)
        val htlcDelayedPenaltyTxs = actions4.findPublishTxs(ChannelAction.Blockchain.PublishTx.Type.ClaimHtlcDelayedOutputPenaltyTx)
        assertEquals(alice4.state.revokedCommitPublished[0].htlcDelayedOutputs, htlcDelayedPenaltyTxs.map { it.txIn.first().outPoint }.toSet())
        htlcDelayedPenaltyTxs.forEach { Transaction.correctlySpends(it, bobHtlcTx, ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS) }
        actions4.hasWatchOutputsSpent(alice4.state.revokedCommitPublished[0].htlcDelayedOutputs)
    }

    @Test
    fun `recv ChannelReestablish`() {
        val (alice, bob) = reachNormal()
        val (alice1, lcp) = localClose(alice)
        val bobCurrentPerCommitmentPoint = bob.channelKeys.commitmentPoint(bob.commitments.localCommitIndex)
        val channelReestablish = ChannelReestablish(bob.channelId, 42, 42, PrivateKey(ByteVector32.Zeroes), bobCurrentPerCommitmentPoint)
        val (alice2, actions2) = alice1.process(ChannelCommand.MessageReceived(channelReestablish))
        assertIs<Closing>(alice2.state)
        assertNotNull(alice2.state.localCommitPublished)
        assertEquals(lcp, alice2.state.localCommitPublished)
        assertNull(alice2.state.remoteCommitPublished)
        val error = actions2.hasOutgoingMessage<Error>()
        assertEquals(error.toAscii(), FundingTxSpent(alice.channelId, lcp.commitTx.txid).message)
    }

    @Test
    fun `recv NewBlock -- an htlc timed out`() {
        val (alice, bob) = reachNormal()
        val (aliceClosing, htlc) = run {
            val (nodes1, _, htlc) = addHtlc(30_000_000.msat, alice, bob)
            val (alice1, bob1) = nodes1
            val (alice2, _) = crossSign(alice1, bob1)
            val (alice3, _) = localClose(alice2, htlcTimeoutCount = 1)
            Pair(alice3, htlc)
        }

        run {
            val alice1 = aliceClosing.copy(ctx = alice.ctx.copy(currentBlockHeight = htlc.cltvExpiry.toLong().toInt()))
            val (alice2, actions) = alice1.process(ChannelCommand.Commitment.CheckHtlcTimeout)
            assertEquals(alice1.state, alice2.state)
            assertTrue(actions.isEmpty())
        }
    }

    @Test
    fun `recv Disconnected`() {
        val (alice, _) = reachNormal()
        val (alice1, _) = localClose(alice)
        val (alice2, _) = alice1.process(ChannelCommand.Disconnected)
        assertIs<Closing>(alice2.state)
    }

    companion object {
        data class RevokedTxs(val commitTx: Transaction, val htlcTimeoutTxs: List<Transactions.HtlcTimeoutTx>, val htlcSuccessTxs: List<Transactions.HtlcSuccessTx>)

        data class RevokedCloseFixture(val alice: LNChannel<Normal>, val bob: LNChannel<Normal>, val bobRevokedTxs: List<RevokedTxs>, val htlcsAlice: List<UpdateAddHtlc>, val htlcsBob: List<UpdateAddHtlc>)

        fun prepareRevokedClose(): RevokedCloseFixture {
            val (aliceInit, bobInit) = reachNormal()
            var mutableAlice: LNChannel<Normal> = aliceInit
            var mutableBob: LNChannel<Normal> = bobInit
            val preimages: MutableSet<ByteVector32> = mutableSetOf()

            // Bob's first commit tx doesn't contain any htlc
            val revokedTxs1 = RevokedTxs(bobInit.signCommitTx(), listOf(), listOf())
            assertEquals(4, revokedTxs1.commitTx.txOut.size) // 2 main outputs + 2 anchors

            // Bob's second commit tx contains 1 incoming htlc and 1 outgoing htlc
            val (revokedTxs2, htlcAlice1, htlcBob1) = run {
                val (nodes1, preimage, htlcAlice) = addHtlc(35_000_000.msat, mutableAlice, mutableBob)
                mutableAlice = nodes1.first
                mutableBob = nodes1.second
                preimages.add(preimage)

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

                val commitTx = mutableBob.signCommitTx()
                val htlcTimeoutTxs = mutableBob.signHtlcTimeoutTxs()
                assertEquals(1, htlcTimeoutTxs.size)
                val htlcSuccessTxs = mutableBob.signHtlcSuccessTxs(preimages)
                assertEquals(1, htlcSuccessTxs.size)
                Triple(RevokedTxs(commitTx, htlcTimeoutTxs, htlcSuccessTxs), htlcAlice, htlcBob)
            }
            assertEquals(6, revokedTxs2.commitTx.txOut.size)

            // Bob's third commit tx contains 2 incoming htlcs and 2 outgoing htlcs
            val (revokedTxs3, htlcAlice2, htlcBob2) = run {
                val (nodes1, preimage, htlcAlice) = addHtlc(25_000_000.msat, mutableAlice, mutableBob)
                mutableAlice = nodes1.first
                mutableBob = nodes1.second
                preimages.add(preimage)

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

                val commitTx = mutableBob.signCommitTx()
                val htlcTimeoutTxs = mutableBob.signHtlcTimeoutTxs()
                assertEquals(2, htlcTimeoutTxs.size)
                val htlcSuccessTxs = mutableBob.signHtlcSuccessTxs(preimages)
                assertEquals(2, htlcSuccessTxs.size)
                Triple(RevokedTxs(commitTx, htlcTimeoutTxs, htlcSuccessTxs), htlcAlice, htlcBob)
            }
            assertEquals(8, revokedTxs3.commitTx.txOut.size)

            // Bob's fourth commit tx doesn't contain any htlc
            val revokedTxs4 = run {
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
                RevokedTxs(mutableBob.signCommitTx(), listOf(), listOf())
            }
            assertEquals(4, revokedTxs4.commitTx.txOut.size)

            return RevokedCloseFixture(mutableAlice, mutableBob, listOf(revokedTxs1, revokedTxs2, revokedTxs3, revokedTxs4), listOf(htlcAlice1, htlcAlice2), listOf(htlcBob1, htlcBob2))
        }

        private fun confirmClosingTxs(firstClosingState: LNChannel<Closing>, watchConfirmed: List<WatchConfirmedTriggered>) {
            var alice = firstClosingState
            watchConfirmed.dropLast(1).forEach {
                val alice1 = confirmClosingTx(alice, it)
                assertIs<LNChannel<Closing>>(alice1)
                alice = alice1
            }
            val aliceClosed = confirmClosingTx(alice, watchConfirmed.last())
            assertIs<Closed>(aliceClosed.state)
        }

        private fun confirmClosingTx(closing: LNChannel<Closing>, watch: WatchConfirmedTriggered): LNChannel<ChannelState> {
            val (closing1, actions1) = closing.process(ChannelCommand.WatchReceived(watch))
            actions1.has<ChannelAction.Storage.StoreState>()
            // If this was an HTLC transaction, we may publish an HTLC-delayed transaction.
            val htlcDelayedTx = actions1.findPublishTxs(ChannelAction.Blockchain.PublishTx.Type.HtlcDelayedTx).firstOrNull()
            htlcDelayedTx?.let { tx -> actions1.hasWatchOutputSpent(tx.txIn.first().outPoint) }
            // The HTLC-delayed transaction confirms as well.
            return when (htlcDelayedTx) {
                null -> closing1
                else -> {
                    val htlcDelayedWatch = WatchConfirmedTriggered(closing.channelId, WatchConfirmed.ClosingTxConfirmed, 0, 0, htlcDelayedTx)
                    val (closing2, actions2) = closing1.process(ChannelCommand.WatchReceived(htlcDelayedWatch))
                    actions2.has<ChannelAction.Storage.StoreState>()
                    when (closing2.state) {
                        is Closed -> {
                            assertEquals(2, actions2.size)
                            actions2.has<ChannelAction.Storage.SetLocked>()
                        }
                        else -> assertEquals(1, actions2.size)
                    }
                    closing2
                }
            }
        }
    }

}
