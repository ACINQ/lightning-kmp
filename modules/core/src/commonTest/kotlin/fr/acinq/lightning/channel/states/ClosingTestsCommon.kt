package fr.acinq.lightning.channel.states

import fr.acinq.bitcoin.*
import fr.acinq.lightning.CltvExpiryDelta
import fr.acinq.lightning.Feature
import fr.acinq.lightning.Lightning
import fr.acinq.lightning.blockchain.WatchConfirmed
import fr.acinq.lightning.blockchain.WatchConfirmedTriggered
import fr.acinq.lightning.blockchain.WatchSpent
import fr.acinq.lightning.blockchain.WatchSpentTriggered
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
import fr.acinq.lightning.channel.TestsHelper.reachNormal
import fr.acinq.lightning.channel.TestsHelper.remoteClose
import fr.acinq.lightning.channel.TestsHelper.useAlternativeCommitSig
import fr.acinq.lightning.db.ChannelCloseOutgoingPayment.ChannelClosingType
import fr.acinq.lightning.tests.TestConstants
import fr.acinq.lightning.tests.utils.LightningTestSuite
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
        val (alice, bob, _) = WaitForFundingConfirmedTestsCommon.init(ChannelType.SupportedChannelType.AnchorOutputs)
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
        val (alice, bob, previousFundingTx, walletAlice) = WaitForFundingConfirmedTestsCommon.init(ChannelType.SupportedChannelType.AnchorOutputs)
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
        val (aliceClosing, localCommitPublished, htlcs) = run {
            // alice sends an htlc to bob
            val (nodes1, _, htlc1) = addHtlc(50_000_000.msat, alice0, bob0)
            val (alice1, bob1) = nodes1
            // alice sends an htlc below dust to bob
            val amountBelowDust = alice0.state.commitments.params.localParams.dustLimit.toMilliSatoshi() - 100.msat
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
            WatchConfirmedTriggered(alice0.state.channelId, WatchConfirmed.ClosingTxConfirmed, 42, 0, localCommitPublished.commitTx),
            WatchConfirmedTriggered(alice0.state.channelId, WatchConfirmed.ClosingTxConfirmed, 200, 0, localCommitPublished.claimMainDelayedOutputTx.tx),
            WatchConfirmedTriggered(alice0.state.channelId, WatchConfirmed.ClosingTxConfirmed, 201, 0, localCommitPublished.htlcTimeoutTxs().first().tx),
            WatchConfirmedTriggered(alice0.state.channelId, WatchConfirmed.ClosingTxConfirmed, 202, 0, localCommitPublished.claimHtlcDelayedTxs.first().tx)
        )

        var alice = aliceClosing
        val addSettledActions = watchConfirmed.dropLast(1).flatMap {
            val (aliceNew, actions) = alice.process(ChannelCommand.WatchReceived(it))
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

        val irrevocablySpent = setOf(localCommitPublished.commitTx, localCommitPublished.claimMainDelayedOutputTx.tx, localCommitPublished.htlcTimeoutTxs().first().tx)
        assertEquals(irrevocablySpent, alice.state.localCommitPublished!!.irrevocablySpent.values.toSet())

        val (aliceClosed, actions) = alice.process(ChannelCommand.WatchReceived(watchConfirmed.last()))
        assertIs<Closed>(aliceClosed.state)
        assertEquals(
            listOf(ChannelAction.Storage.StoreState(aliceClosed.state)),
            actions.filterIsInstance<ChannelAction.Storage.StoreState>()
        )
        assertContains(actions, ChannelAction.Storage.SetLocked(localCommitPublished.commitTx.txid))
    }

    @Test
    fun `recv ClosingTxConfirmed -- local commit -- non-initiator pays commit fees`() {
        val (alice0, bob0) = reachNormal(requestRemoteFunding = TestConstants.bobFundingAmount)
        assertFalse(alice0.commitments.params.localParams.paysCommitTxFees)
        assertTrue(bob0.commitments.params.localParams.paysCommitTxFees)
        val (alice1, localCommitPublished) = localClose(alice0)
        val (alice2, _) = alice1.process(ChannelCommand.WatchReceived(WatchConfirmedTriggered(alice0.channelId, WatchConfirmed.ClosingTxConfirmed, 42, 7, localCommitPublished.commitTx)))
        val claimMain = localCommitPublished.claimMainDelayedOutputTx!!.tx
        val (alice3, actions3) = alice2.process(ChannelCommand.WatchReceived(WatchConfirmedTriggered(alice0.state.channelId, WatchConfirmed.ClosingTxConfirmed, 43, 3, claimMain)))
        assertIs<Closed>(alice3.state)
        assertEquals(2, actions3.size)
        actions3.has<ChannelAction.Storage.StoreState>()
        actions3.find<ChannelAction.Storage.SetLocked>().also { assertEquals(localCommitPublished.commitTx.txid, it.txId) }
    }

    @Test
    fun `recv ClosingTxConfirmed -- local commit with multiple htlcs for the same payment`() {
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
            val amountBelowDust = alice0.state.commitments.params.localParams.dustLimit.toMilliSatoshi() - 100.msat
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
            WatchConfirmedTriggered(alice0.state.channelId, WatchConfirmed.ClosingTxConfirmed, 42, 0, localCommitPublished.htlcTimeoutTxs()[2].tx),
            WatchConfirmedTriggered(alice0.state.channelId, WatchConfirmed.ClosingTxConfirmed, 42, 1, localCommitPublished.commitTx),
            WatchConfirmedTriggered(alice0.state.channelId, WatchConfirmed.ClosingTxConfirmed, 200, 0, localCommitPublished.claimMainDelayedOutputTx.tx),
            WatchConfirmedTriggered(alice0.state.channelId, WatchConfirmed.ClosingTxConfirmed, 202, 0, localCommitPublished.htlcTimeoutTxs()[1].tx),
            WatchConfirmedTriggered(alice0.state.channelId, WatchConfirmed.ClosingTxConfirmed, 203, 2, localCommitPublished.claimHtlcDelayedTxs[2].tx),
            WatchConfirmedTriggered(alice0.state.channelId, WatchConfirmed.ClosingTxConfirmed, 202, 1, localCommitPublished.htlcTimeoutTxs()[0].tx),
            WatchConfirmedTriggered(alice0.state.channelId, WatchConfirmed.ClosingTxConfirmed, 203, 0, localCommitPublished.claimHtlcDelayedTxs[0].tx),
            WatchConfirmedTriggered(alice0.state.channelId, WatchConfirmed.ClosingTxConfirmed, 203, 1, localCommitPublished.claimHtlcDelayedTxs[1].tx),
            WatchConfirmedTriggered(alice0.state.channelId, WatchConfirmed.ClosingTxConfirmed, 203, 0, localCommitPublished.htlcTimeoutTxs()[3].tx),
            WatchConfirmedTriggered(alice0.state.channelId, WatchConfirmed.ClosingTxConfirmed, 203, 3, localCommitPublished.claimHtlcDelayedTxs[3].tx)
        )
        confirmWatchedTxs(aliceClosing, watchConfirmed)
    }

    @Test
    fun `recv ClosingTxConfirmed -- local commit with htlcs only signed by local`() {
        val (alice0, bob0) = reachNormal()
        val aliceCommitTx = alice0.commitments.latest.localCommit.publishableTxs.commitTx.tx
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
        assertTrue(localCommitPublished.htlcTimeoutTxs().isEmpty())
        assertTrue(localCommitPublished.htlcSuccessTxs().isEmpty())

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
        val aliceCommitTx = alice1.commitments.latest.localCommit.publishableTxs.commitTx.tx
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
        val (_, localCommitPublished) = localClose(alice2)
        assertEquals(aliceCommitTx.txid, localCommitPublished.commitTx.txid)
        assertTrue(localCommitPublished.htlcTimeoutTxs().isEmpty())
        assertEquals(1, localCommitPublished.htlcSuccessTxs().size)
    }

    @Test
    fun `recv ClosingTxConfirmed -- local commit -- followed by preimage`() {
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
            Triple(aliceClosing, localCommitPublished, ChannelCommand.Htlc.Settlement.Fulfill(htlc.id, preimage, commit = true))
        }

        assertNotNull(localCommitPublished.claimMainDelayedOutputTx)
        // we don't have the preimage to claim the htlc-success yet
        assertTrue(localCommitPublished.htlcSuccessTxs().isEmpty())
        assertEquals(1, localCommitPublished.htlcTimeoutTxs().size)
        assertEquals(1, localCommitPublished.claimHtlcDelayedTxs.size)

        // Alice receives the preimage for the first HTLC from the payment handler; she can now claim the corresponding HTLC output.
        val (aliceFulfill, actionsFulfill) = aliceClosing.process(fulfill)
        assertIs<LNChannel<Closing>>(aliceFulfill)
        assertEquals(1, aliceFulfill.state.localCommitPublished!!.htlcSuccessTxs().size)
        assertEquals(1, aliceFulfill.state.localCommitPublished.htlcTimeoutTxs().size)
        assertEquals(2, aliceFulfill.state.localCommitPublished.claimHtlcDelayedTxs.size)
        val htlcSuccess = aliceFulfill.state.localCommitPublished.htlcSuccessTxs().first()
        actionsFulfill.hasPublishTx(htlcSuccess.tx)
        assertTrue(actionsFulfill.findWatches<WatchSpent>().map { Pair(it.txId, it.outputIndex.toLong()) }.contains(Pair(localCommitPublished.commitTx.txid, htlcSuccess.input.outPoint.index)))
        Transaction.correctlySpends(htlcSuccess.tx, localCommitPublished.commitTx, ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)

        val watchConfirmed = listOf(
            WatchConfirmedTriggered(alice0.channelId, WatchConfirmed.ClosingTxConfirmed, 42, 1, localCommitPublished.commitTx),
            WatchConfirmedTriggered(alice0.channelId, WatchConfirmed.ClosingTxConfirmed, 210, 0, localCommitPublished.htlcTimeoutTxs()[0].tx),
            WatchConfirmedTriggered(alice0.channelId, WatchConfirmed.ClosingTxConfirmed, 210, 1, htlcSuccess.tx),
            WatchConfirmedTriggered(alice0.channelId, WatchConfirmed.ClosingTxConfirmed, 215, 1, aliceFulfill.state.localCommitPublished.claimHtlcDelayedTxs[0].tx),
            WatchConfirmedTriggered(alice0.channelId, WatchConfirmed.ClosingTxConfirmed, 215, 0, aliceFulfill.state.localCommitPublished.claimHtlcDelayedTxs[1].tx),
            WatchConfirmedTriggered(alice0.channelId, WatchConfirmed.ClosingTxConfirmed, 250, 0, localCommitPublished.claimMainDelayedOutputTx.tx)
        )
        confirmWatchedTxs(aliceFulfill, watchConfirmed)
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

        assertNotNull(localCommitPublished.claimMainDelayedOutputTx)
        assertTrue(localCommitPublished.htlcSuccessTxs().isEmpty())
        assertTrue(localCommitPublished.htlcTimeoutTxs().isEmpty())
        assertTrue(localCommitPublished.claimHtlcDelayedTxs.isEmpty())

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
        val (alice4, localCommitPublished) = localClose(alice3)
        assertEquals(2, localCommitPublished.htlcTimeoutTxs().size)
        assertTrue(localCommitPublished.htlcSuccessTxs().isEmpty())
        assertEquals(2, localCommitPublished.claimHtlcDelayedTxs.size)

        val (_, actionsBob5) = bob4.process(ChannelCommand.WatchReceived(WatchSpentTriggered(bob0.channelId, WatchSpent.ChannelSpent(TestConstants.fundingAmount), localCommitPublished.commitTx)))
        actionsBob5.has<ChannelAction.Storage.StoreState>()
        val claimHtlcSuccessTxs = actionsBob5.filterIsInstance<ChannelAction.Blockchain.PublishTx>().filter { it.txType == ChannelAction.Blockchain.PublishTx.Type.ClaimHtlcSuccessTx }.map { it.tx }
        assertEquals(2, claimHtlcSuccessTxs.size)
        assertEquals(2, claimHtlcSuccessTxs.flatMap { it.txIn.map { it.outPoint } }.toSet().size)

        // Alice extracts the preimage and forwards it to the payment handler.
        val (alice5, actionsAlice5) = alice4.process(ChannelCommand.WatchReceived(WatchSpentTriggered(alice0.channelId, WatchSpent.ClosingOutputSpent(20_000.sat), claimHtlcSuccessTxs.first())))
        assertEquals(4, actionsAlice5.size)
        actionsAlice5.has<ChannelAction.Storage.StoreState>()
        assertEquals(WatchConfirmed(alice0.channelId, claimHtlcSuccessTxs.first(), alice0.staticParams.nodeParams.minDepthBlocks, WatchConfirmed.ClosingTxConfirmed), actionsAlice5.findWatch())
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
            WatchConfirmedTriggered(alice0.channelId, WatchConfirmed.ClosingTxConfirmed, 200, 0, localCommitPublished.claimMainDelayedOutputTx!!.tx)
        )
        confirmWatchedTxs(alice6, watchConfirmed)
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
        val (alice1, actions1) = initState.process(ChannelCommand.Init.Restore(aliceClosing.state))
        assertIs<Closing>(alice1.state)
        assertEquals(aliceClosing, alice1)
        actions1.doesNotHave<ChannelAction.Storage.StoreOutgoingPayment.ViaClose>()

        // We should republish closing transactions
        val txs = listOf(
            localCommitPublished.commitTx,
            localCommitPublished.claimMainDelayedOutputTx.tx,
            localCommitPublished.htlcTimeoutTxs().first().tx,
            localCommitPublished.claimHtlcDelayedTxs.first().tx,
        )
        assertEquals(actions1.findPublishTxs(), txs)
        val watchConfirmed = listOf(
            localCommitPublished.commitTx.txid,
            localCommitPublished.claimMainDelayedOutputTx.tx.txid,
            localCommitPublished.claimHtlcDelayedTxs.first().tx.txid,
        )
        assertEquals(actions1.findWatches<WatchConfirmed>().map { it.txId }, watchConfirmed)
        val watchSpent = listOf(
            localCommitPublished.commitTx.txIn.first().outPoint,
            localCommitPublished.htlcTimeoutTxs().first().input.outPoint,
        )
        assertEquals(actions1.findWatches<WatchSpent>().map { OutPoint(it.txId, it.outputIndex.toLong()) }, watchSpent)
    }

    @Test
    fun `recv ChannelSpent -- remote commit`() {
        val (alice0, bob0) = reachNormal()
        val bobCommitTx = bob0.commitments.latest.localCommit.publishableTxs.commitTx.tx
        assertEquals(4, bobCommitTx.txOut.size) // main outputs and anchors
        val (_, remoteCommitPublished) = remoteClose(bobCommitTx, alice0)
        assertNotNull(remoteCommitPublished.claimMainOutputTx)
        assertTrue(remoteCommitPublished.claimHtlcSuccessTxs().isEmpty())
        assertTrue(remoteCommitPublished.claimHtlcTimeoutTxs().isEmpty())
    }

    @Test
    fun `recv ClosingTxConfirmed -- remote commit`() {
        val (alice0, bob0) = reachNormal()
        val (aliceClosing, remoteCommitPublished, htlcs) = run {
            // alice sends an htlc to bob
            val (nodes1, _, htlc1) = addHtlc(50_000_000.msat, alice0, bob0)
            val (alice1, bob1) = nodes1
            // alice sends an htlc below dust to bob
            val amountBelowDust = alice0.commitments.params.localParams.dustLimit.toMilliSatoshi() - 100.msat
            val (nodes2, _, htlc2) = addHtlc(amountBelowDust, alice1, bob1)
            val (alice2, bob2) = nodes2
            val (alice3, bob3) = crossSign(alice2, bob2)
            val (aliceClosing, remoteCommitPublished) = remoteClose(bob3.commitments.latest.localCommit.publishableTxs.commitTx.tx, alice3)
            Triple(aliceClosing, remoteCommitPublished, listOf(htlc1, htlc2))
        }

        // actual test starts here
        assertNotNull(remoteCommitPublished.claimMainOutputTx)
        assertTrue(remoteCommitPublished.claimHtlcSuccessTxs().isEmpty())
        assertEquals(1, remoteCommitPublished.claimHtlcTimeoutTxs().size)

        val watchConfirmed = listOf(
            WatchConfirmedTriggered(alice0.channelId, WatchConfirmed.ClosingTxConfirmed, 42, 0, remoteCommitPublished.commitTx),
            WatchConfirmedTriggered(alice0.channelId, WatchConfirmed.ClosingTxConfirmed, 43, 0, remoteCommitPublished.claimMainOutputTx.tx),
            WatchConfirmedTriggered(alice0.channelId, WatchConfirmed.ClosingTxConfirmed, 201, 0, remoteCommitPublished.claimHtlcTimeoutTxs().first().tx),
        )

        var alice = aliceClosing
        val addSettledActions = watchConfirmed.dropLast(1).flatMap {
            val (aliceNew, actions) = alice.process(ChannelCommand.WatchReceived(it))
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

        val irrevocablySpent = setOf(remoteCommitPublished.commitTx, remoteCommitPublished.claimMainOutputTx.tx)
        assertEquals(irrevocablySpent, alice.state.remoteCommitPublished!!.irrevocablySpent.values.toSet())

        val (aliceClosed, actions) = alice.process(ChannelCommand.WatchReceived(watchConfirmed.last()))
        assertIs<Closed>(aliceClosed.state)
        assertTrue(actions.contains(ChannelAction.Storage.StoreState(aliceClosed.state)))

        assertContains(actions, ChannelAction.Storage.SetLocked(remoteCommitPublished.commitTx.txid))
        // We notify the payment handler that the non-dust htlc has been failed.
        val htlcFail = actions.filterIsInstance<ChannelAction.ProcessCmdRes.AddSettledFail>().first()
        assertEquals(htlcs[0], htlcFail.htlc)
        assertTrue(htlcFail.result is ChannelAction.HtlcResult.Fail.OnChainFail)
        assertEquals(3, actions.size)
    }

    @Test
    fun `recv ClosingTxConfirmed -- remote commit -- non-initiator pays commit fees`() {
        val (alice0, bob0) = reachNormal(requestRemoteFunding = TestConstants.bobFundingAmount)
        assertFalse(alice0.commitments.params.localParams.paysCommitTxFees)
        assertTrue(bob0.commitments.params.localParams.paysCommitTxFees)
        val remoteCommitTx = bob0.commitments.latest.localCommit.publishableTxs.commitTx.tx
        val (alice1, remoteCommitPublished) = remoteClose(remoteCommitTx, alice0)
        val (alice2, _) = alice1.process(ChannelCommand.WatchReceived(WatchConfirmedTriggered(alice0.channelId, WatchConfirmed.ClosingTxConfirmed, 42, 7, remoteCommitTx)))
        val claimMain = remoteCommitPublished.claimMainOutputTx!!.tx
        val (alice3, actions3) = alice2.process(ChannelCommand.WatchReceived(WatchConfirmedTriggered(alice0.state.channelId, WatchConfirmed.ClosingTxConfirmed, 43, 3, claimMain)))
        assertIs<Closed>(alice3.state)
        assertEquals(2, actions3.size)
        actions3.has<ChannelAction.Storage.StoreState>()
        actions3.find<ChannelAction.Storage.SetLocked>().also { assertEquals(remoteCommitTx.txid, it.txId) }
    }

    @Test
    fun `recv ClosingTxConfirmed -- remote commit with multiple htlcs for the same payment`() {
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
            remoteClose(bob4.commitments.latest.localCommit.publishableTxs.commitTx.tx, alice4)
        }

        // actual test starts here
        assertNotNull(remoteCommitPublished.claimMainOutputTx)
        assertEquals(3, remoteCommitPublished.claimHtlcTimeoutTxs().size)

        // if commit tx and claim-htlc-timeout txs end up in the same block, we may receive them in any order
        val watchConfirmed = listOf(
            WatchConfirmedTriggered(alice0.channelId, WatchConfirmed.ClosingTxConfirmed, 42, 0, remoteCommitPublished.claimHtlcTimeoutTxs()[1].tx),
            WatchConfirmedTriggered(alice0.channelId, WatchConfirmed.ClosingTxConfirmed, 42, 1, remoteCommitPublished.commitTx),
            WatchConfirmedTriggered(alice0.channelId, WatchConfirmed.ClosingTxConfirmed, 201, 0, remoteCommitPublished.claimHtlcTimeoutTxs()[2].tx),
            WatchConfirmedTriggered(alice0.channelId, WatchConfirmed.ClosingTxConfirmed, 200, 0, remoteCommitPublished.claimMainOutputTx.tx),
            WatchConfirmedTriggered(alice0.channelId, WatchConfirmed.ClosingTxConfirmed, 204, 0, remoteCommitPublished.claimHtlcTimeoutTxs()[0].tx)
        )
        confirmWatchedTxs(aliceClosing, watchConfirmed)
    }

    @Test
    fun `recv ClosingTxConfirmed -- remote commit with htlcs only signed by local in next remote commit`() {
        val (alice0, bob0) = reachNormal()
        val bobCommitTx = bob0.commitments.latest.localCommit.publishableTxs.commitTx.tx
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

        assertTrue(remoteCommitPublished.claimHtlcTimeoutTxs().isEmpty())

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
        val (aliceClosing, remoteCommitPublished, fulfill) = run {
            // An HTLC Bob -> Alice is cross-signed that will be fulfilled later.
            val (nodes1, preimage, htlc) = addHtlc(110_000_000.msat, bob0, alice0)
            val (bob1, alice1) = nodes1
            // An HTLC Alice -> Bob is cross-signed and will timeout later.
            val (nodes2, _, _) = addHtlc(95_000_000.msat, alice1, bob1)
            val (alice2, bob2) = nodes2
            val (alice3, bob3) = crossSign(alice2, bob2)
            // Now Bob publishes his commit tx (force-close).
            val bobCommitTx = bob3.state.commitments.latest.localCommit.publishableTxs.commitTx.tx
            assertEquals(6, bobCommitTx.txOut.size) // two main outputs + 2 anchors + 2 HTLCs
            val (aliceClosing, remoteCommitPublished) = remoteClose(bobCommitTx, alice3)
            Triple(aliceClosing, remoteCommitPublished, ChannelCommand.Htlc.Settlement.Fulfill(htlc.id, preimage, commit = true))
        }

        assertNotNull(remoteCommitPublished.claimMainOutputTx)
        // we don't have the preimage to claim the htlc-success yet
        assertTrue(remoteCommitPublished.claimHtlcSuccessTxs().isEmpty())
        assertEquals(1, remoteCommitPublished.claimHtlcTimeoutTxs().size)

        // Alice receives the preimage for the first HTLC from the payment handler; she can now claim the corresponding HTLC output.
        val (aliceFulfill, actionsFulfill) = aliceClosing.process(fulfill)
        assertIs<LNChannel<Closing>>(aliceFulfill)
        assertEquals(1, aliceFulfill.state.remoteCommitPublished!!.claimHtlcSuccessTxs().size)
        assertEquals(1, aliceFulfill.state.remoteCommitPublished.claimHtlcTimeoutTxs().size)
        val claimHtlcSuccess = aliceFulfill.state.remoteCommitPublished.claimHtlcSuccessTxs().first()
        actionsFulfill.hasPublishTx(claimHtlcSuccess.tx)
        assertTrue(actionsFulfill.findWatches<WatchSpent>().map { Pair(it.txId, it.outputIndex.toLong()) }.contains(Pair(remoteCommitPublished.commitTx.txid, claimHtlcSuccess.input.outPoint.index)))
        Transaction.correctlySpends(claimHtlcSuccess.tx, remoteCommitPublished.commitTx, ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)

        val watchConfirmed = listOf(
            WatchConfirmedTriggered(alice0.channelId, WatchConfirmed.ClosingTxConfirmed, 42, 1, remoteCommitPublished.commitTx),
            WatchConfirmedTriggered(alice0.channelId, WatchConfirmed.ClosingTxConfirmed, 210, 0, remoteCommitPublished.claimHtlcTimeoutTxs()[0].tx),
            WatchConfirmedTriggered(alice0.channelId, WatchConfirmed.ClosingTxConfirmed, 210, 0, claimHtlcSuccess.tx),
            WatchConfirmedTriggered(alice0.channelId, WatchConfirmed.ClosingTxConfirmed, 250, 0, remoteCommitPublished.claimMainOutputTx.tx)
        )
        confirmWatchedTxs(aliceFulfill, watchConfirmed)
    }

    @Test
    fun `recv ClosingTxConfirmed -- remote commit -- alternative feerate`() {
        val (alice0, bob0) = reachNormal()
        val (bobClosing, remoteCommitPublished) = run {
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
            val alternativeCommitTx = useAlternativeCommitSig(alice6, alice6.commitments.active.first(), commitSigBob.alternativeFeerateSigs.first())
            remoteClose(alternativeCommitTx, bob6)
        }

        assertNotNull(remoteCommitPublished.claimMainOutputTx)
        val claimMain = remoteCommitPublished.claimMainOutputTx.tx
        Transaction.correctlySpends(claimMain, remoteCommitPublished.commitTx, ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)

        val (bobClosing1, _) = bobClosing.process(ChannelCommand.WatchReceived(WatchConfirmedTriggered(bob0.channelId, WatchConfirmed.ClosingTxConfirmed, 42, 0, remoteCommitPublished.commitTx)))
        val (bobClosed, actions) = bobClosing1.process(ChannelCommand.WatchReceived(WatchConfirmedTriggered(bob0.channelId, WatchConfirmed.ClosingTxConfirmed, 43, 0, claimMain)))
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
        val (_, remoteCommitPublished) = localClose(bob4) //
        // Bob claims the htlc outputs from his own commit tx using its preimage.
        assertEquals(setOf(htlc1.id, htlc2.id), remoteCommitPublished.htlcSuccessTxs().map { it.htlcId }.toSet())
        assertEquals(setOf(preimage.sha256()), remoteCommitPublished.htlcSuccessTxs().map { it.paymentHash }.toSet())
        val htlcSuccessTxs = remoteCommitPublished.htlcSuccessTxs().map { it.tx }

        // Alice extracts the preimage and forwards it to the payment handler.
        val (alice4, actionsAlice4) = alice3.process(ChannelCommand.WatchReceived(WatchSpentTriggered(alice0.channelId, WatchSpent.ChannelSpent(TestConstants.fundingAmount), remoteCommitPublished.commitTx)))
        actionsAlice4.has<ChannelAction.Storage.StoreState>()
        actionsAlice4.hasWatchConfirmed(remoteCommitPublished.commitTx.txid)
        val (alice5, actionsAlice5) = alice4.process(ChannelCommand.WatchReceived(WatchSpentTriggered(alice0.channelId, WatchSpent.ClosingOutputSpent(50_000.sat), htlcSuccessTxs.first())))
        assertEquals(4, actionsAlice5.size)
        actionsAlice5.has<ChannelAction.Storage.StoreState>()
        actionsAlice5.hasWatchConfirmed(htlcSuccessTxs.first().txid)
        val addSettled = actionsAlice5.filterIsInstance<ChannelAction.ProcessCmdRes.AddSettledFulfill>()
        assertEquals(setOf(htlc1, htlc2), addSettled.map { it.htlc }.toSet())
        assertEquals(setOf(ChannelAction.HtlcResult.Fulfill.OnChainFulfill(preimage)), addSettled.map { it.result }.toSet())

        // The HTLC-success transaction confirms: nothing to do, preimage has already been relayed.
        val (alice6, actionsAlice6) = alice5.process(ChannelCommand.WatchReceived(WatchConfirmedTriggered(alice0.channelId, WatchConfirmed.ClosingTxConfirmed, 42, 1, htlcSuccessTxs.first())))
        assertIs<LNChannel<Closing>>(alice6)
        assertEquals(1, actionsAlice6.size)
        actionsAlice6.has<ChannelAction.Storage.StoreState>()

        // The remaining transactions confirm.
        val watchConfirmed = listOf(
            WatchConfirmedTriggered(alice0.channelId, WatchConfirmed.ClosingTxConfirmed, 42, 0, remoteCommitPublished.commitTx),
            WatchConfirmedTriggered(alice0.channelId, WatchConfirmed.ClosingTxConfirmed, 47, 0, htlcSuccessTxs.last()),
            WatchConfirmedTriggered(alice0.channelId, WatchConfirmed.ClosingTxConfirmed, 47, 0, alice6.state.remoteCommitPublished?.claimMainOutputTx?.tx!!),
        )
        confirmWatchedTxs(alice6, watchConfirmed)
    }

    @Test
    fun `recv ChannelEvent Restore -- remote commit`() {
        val (alice0, bob0) = reachNormal()
        val (aliceClosing, remoteCommitPublished) = run {
            // alice sends an htlc to bob
            val (nodes1, _, _) = addHtlc(50_000_000.msat, alice0, bob0)
            val (alice1, bob1) = nodes1
            val (alice2, bob2) = crossSign(alice1, bob1)
            assertIs<Normal>(bob2.state)
            val bobCommitTx = bob2.commitments.latest.localCommit.publishableTxs.commitTx.tx
            val (aliceClosing, remoteCommitPublished) = remoteClose(bobCommitTx, alice2)
            Pair(aliceClosing, remoteCommitPublished)
        }

        assertNotNull(remoteCommitPublished.claimMainOutputTx)
        assertTrue(remoteCommitPublished.claimHtlcSuccessTxs().isEmpty())
        assertEquals(1, remoteCommitPublished.claimHtlcTimeoutTxs().size)

        // Simulate a wallet restart
        val initState = LNChannel(aliceClosing.ctx, WaitForInit)
        val (alice1, actions1) = initState.process(ChannelCommand.Init.Restore(aliceClosing.state))
        assertIs<Closing>(alice1.state)
        assertEquals(aliceClosing, alice1)
        actions1.doesNotHave<ChannelAction.Storage.StoreOutgoingPayment.ViaClose>()

        // We should republish closing transactions
        val txs = listOf(
            remoteCommitPublished.claimMainOutputTx.tx,
            remoteCommitPublished.claimHtlcTimeoutTxs().first().tx,
        )
        assertEquals(actions1.findPublishTxs(), txs)
        val watchConfirmed = listOf(
            remoteCommitPublished.commitTx.txid,
            remoteCommitPublished.claimMainOutputTx.tx.txid,
        )
        assertEquals(actions1.findWatches<WatchConfirmed>().map { it.txId }, watchConfirmed)
        val watchSpent = listOf(
            remoteCommitPublished.commitTx.txIn.first().outPoint,
            remoteCommitPublished.claimHtlcTimeoutTxs().first().input.outPoint,
        )
        assertEquals(actions1.findWatches<WatchSpent>().map { OutPoint(it.txId, it.outputIndex.toLong()) }, watchSpent)
    }

    @Test
    fun `recv ChannelSpent -- next remote commit`() {
        val (alice0, bob0) = reachNormal()
        // alice sends an htlc to bob
        val (nodes1, _, _) = addHtlc(50_000_000.msat, alice0, bob0)
        val (alice1, bob1) = nodes1
        val bobCommitTx1 = bob1.state.commitments.latest.localCommit.publishableTxs.commitTx.tx
        // alice signs it, but bob doesn't revoke
        val (alice2, actionsAlice2) = alice1.process(ChannelCommand.Commitment.Sign)
        val commitSig = actionsAlice2.hasOutgoingMessage<CommitSig>()
        val (bob2, actionsBob2) = bob1.process(ChannelCommand.MessageReceived(commitSig))
        actionsBob2.hasOutgoingMessage<RevokeAndAck>() // not forwarded to Alice (malicious Bob)
        // Bob publishes the next commit tx.
        val bobCommitTx2 = (bob2.state as Normal).commitments.latest.localCommit.publishableTxs.commitTx.tx
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
    fun `recv ClosingTxConfirmed -- next remote commit`() {
        val (alice0, bob0) = reachNormal()
        // alice sends an htlc to bob
        val (aliceClosing, remoteCommitPublished) = run {
            val (nodes1, preimage, _) = addHtlc(30_000_000.msat, alice0, bob0)
            val (alice1, bob1) = nodes1
            // and more htlcs with the same payment_hash
            val (_, cmd2) = makeCmdAdd(25_000_000.msat, bob0.staticParams.nodeParams.nodeId, alice1.currentBlockHeight.toLong() - 1, preimage)
            val (alice2, bob2, _) = addHtlc(cmd2, alice1, bob1)
            val (alice3, bob3) = crossSign(alice2, bob2)
            val bobCommitTx1 = bob2.state.commitments.latest.localCommit.publishableTxs.commitTx.tx
            // add more htlcs that bob doesn't revoke
            val (_, cmd3) = makeCmdAdd(20_000_000.msat, bob0.staticParams.nodeParams.nodeId, alice3.currentBlockHeight.toLong(), preimage)
            val (alice4, bob4, _) = addHtlc(cmd3, alice3, bob3)
            val (alice5, actionsAlice5) = alice4.process(ChannelCommand.Commitment.Sign)
            val commitSig = actionsAlice5.hasOutgoingMessage<CommitSig>()
            val (bob5, actionsBob5) = bob4.process(ChannelCommand.MessageReceived(commitSig))
            actionsBob5.hasOutgoingMessage<RevokeAndAck>() // not forwarded to Alice (malicious Bob)
            // Bob publishes the next commit tx.
            val bobCommitTx2 = (bob5.state as Normal).commitments.latest.localCommit.publishableTxs.commitTx.tx
            assertNotEquals(bobCommitTx1.txid, bobCommitTx2.txid)
            remoteClose(bobCommitTx2, alice5)
        }

        // actual test starts here
        assertNotNull(aliceClosing.state.nextRemoteCommitPublished)
        assertNotNull(remoteCommitPublished.claimMainOutputTx)
        assertEquals(3, remoteCommitPublished.claimHtlcTimeoutTxs().size)
        assertTrue(remoteCommitPublished.claimHtlcSuccessTxs().isEmpty())

        val watchConfirmed = listOf(
            WatchConfirmedTriggered(alice0.channelId, WatchConfirmed.ClosingTxConfirmed, 42, 1, remoteCommitPublished.commitTx),
            WatchConfirmedTriggered(alice0.channelId, WatchConfirmed.ClosingTxConfirmed, 201, 0, remoteCommitPublished.claimHtlcTimeoutTxs()[1].tx),
            WatchConfirmedTriggered(alice0.channelId, WatchConfirmed.ClosingTxConfirmed, 201, 0, remoteCommitPublished.claimHtlcTimeoutTxs()[2].tx),
            WatchConfirmedTriggered(alice0.channelId, WatchConfirmed.ClosingTxConfirmed, 202, 0, remoteCommitPublished.claimMainOutputTx.tx),
            WatchConfirmedTriggered(alice0.channelId, WatchConfirmed.ClosingTxConfirmed, 204, 0, remoteCommitPublished.claimHtlcTimeoutTxs()[0].tx)
        )
        confirmWatchedTxs(aliceClosing, watchConfirmed)
    }

    @Test
    fun `recv ClosingTxConfirmed -- next remote commit -- followed by preimage`() {
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
            val (alice5, actionsAlice5) = alice4.process(ChannelCommand.Commitment.Sign)
            val commitSig = actionsAlice5.hasOutgoingMessage<CommitSig>()
            val (bob5, actionsBob5) = bob4.process(ChannelCommand.MessageReceived(commitSig))
            actionsBob5.hasOutgoingMessage<RevokeAndAck>() // not forwarded to Alice (malicious Bob)
            // Now Bob publishes his commit tx (force-close).
            val bobCommitTx = (bob5.state as Normal).commitments.latest.localCommit.publishableTxs.commitTx.tx
            assertEquals(7, bobCommitTx.txOut.size) // two main outputs + 2 anchors + 3 HTLCs
            val (aliceClosing, remoteCommitPublished) = remoteClose(bobCommitTx, alice5)
            Triple(aliceClosing, remoteCommitPublished, ChannelCommand.Htlc.Settlement.Fulfill(htlc.id, preimage, commit = true))
        }

        assertNotNull(aliceClosing.state.nextRemoteCommitPublished)
        assertNotNull(remoteCommitPublished.claimMainOutputTx)
        // we don't have the preimage to claim the htlc-success yet
        assertTrue(remoteCommitPublished.claimHtlcSuccessTxs().isEmpty())
        assertEquals(2, remoteCommitPublished.claimHtlcTimeoutTxs().size)

        // Alice receives the preimage for the first HTLC from the payment handler; she can now claim the corresponding HTLC output.
        val (aliceFulfill, actionsFulfill) = aliceClosing.process(fulfill)
        assertIs<LNChannel<Closing>>(aliceFulfill)
        assertEquals(1, aliceFulfill.state.nextRemoteCommitPublished!!.claimHtlcSuccessTxs().size)
        assertEquals(2, aliceFulfill.state.nextRemoteCommitPublished.claimHtlcTimeoutTxs().size)
        val claimHtlcSuccess = aliceFulfill.state.nextRemoteCommitPublished.claimHtlcSuccessTxs().first()
        actionsFulfill.hasPublishTx(claimHtlcSuccess.tx)
        assertTrue(actionsFulfill.findWatches<WatchSpent>().map { Pair(it.txId, it.outputIndex.toLong()) }.contains(Pair(remoteCommitPublished.commitTx.txid, claimHtlcSuccess.input.outPoint.index)))
        Transaction.correctlySpends(claimHtlcSuccess.tx, remoteCommitPublished.commitTx, ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
        actionsFulfill.doesNotHave<ChannelAction.Storage.StoreOutgoingPayment.ViaClose>()

        val watchConfirmed = listOf(
            WatchConfirmedTriggered(alice0.channelId, WatchConfirmed.ClosingTxConfirmed, 42, 1, remoteCommitPublished.commitTx),
            WatchConfirmedTriggered(alice0.channelId, WatchConfirmed.ClosingTxConfirmed, 210, 0, remoteCommitPublished.claimHtlcTimeoutTxs()[0].tx),
            WatchConfirmedTriggered(alice0.channelId, WatchConfirmed.ClosingTxConfirmed, 210, 1, claimHtlcSuccess.tx),
            WatchConfirmedTriggered(alice0.channelId, WatchConfirmed.ClosingTxConfirmed, 210, 3, remoteCommitPublished.claimHtlcTimeoutTxs()[1].tx),
            WatchConfirmedTriggered(alice0.channelId, WatchConfirmed.ClosingTxConfirmed, 250, 0, remoteCommitPublished.claimMainOutputTx.tx)
        )
        confirmWatchedTxs(aliceFulfill, watchConfirmed)
    }

    @Test
    fun `recv ClosingTxConfirmed -- next remote commit -- alternative feerate`() {
        val (alice0, bob0) = reachNormal()
        val (bobClosing, remoteCommitPublished) = run {
            val (nodes1, r, htlc) = addHtlc(75_000_000.msat, alice0, bob0)
            val (alice1, bob1) = nodes1
            val (alice2, bob2) = crossSign(alice1, bob1)
            val (alice3, bob3) = fulfillHtlc(htlc.id, r, alice2, bob2)
            val (bob4, actionsBob4) = bob3.process(ChannelCommand.Commitment.Sign)
            val commitSigBob = actionsBob4.hasOutgoingMessage<CommitSig>()
            val (alice4, _) = alice3.process(ChannelCommand.MessageReceived(commitSigBob))
            val alternativeCommitTx = useAlternativeCommitSig(alice4, alice4.commitments.active.first(), commitSigBob.alternativeFeerateSigs.first())
            remoteClose(alternativeCommitTx, bob4)
        }

        assertNotNull(remoteCommitPublished.claimMainOutputTx)
        val claimMain = remoteCommitPublished.claimMainOutputTx.tx
        Transaction.correctlySpends(claimMain, remoteCommitPublished.commitTx, ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)

        val (bobClosing1, _) = bobClosing.process(ChannelCommand.WatchReceived(WatchConfirmedTriggered(bob0.channelId, WatchConfirmed.ClosingTxConfirmed, 42, 0, remoteCommitPublished.commitTx)))
        val (bobClosed, actions) = bobClosing1.process(ChannelCommand.WatchReceived(WatchConfirmedTriggered(bob0.channelId, WatchConfirmed.ClosingTxConfirmed, 43, 0, claimMain)))
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
        assertTrue(bobCommit.publishableTxs.htlcTxsAndSigs.isEmpty())
        val commitTx = bobCommit.publishableTxs.commitTx.tx
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
        val remoteCommitPublished = run {
            val (bob5, _) = bob4.process(ChannelCommand.Htlc.Settlement.Fulfill(htlc1.id, preimage))
            val (bob6, _) = bob5.process(ChannelCommand.Htlc.Settlement.Fulfill(htlc2.id, preimage))
            val (_, remoteCommitPublished) = localClose(bob6)
            assertEquals(3, remoteCommitPublished.htlcTxs.size)
            assertEquals(2, remoteCommitPublished.htlcSuccessTxs().size) // Bob doesn't have the preimage for the last HTLC.
            remoteCommitPublished
        }

        // Alice prepares Claim-HTLC-timeout transactions for each HTLC.
        val (alice6, actionsAlice6) = alice5.process(ChannelCommand.WatchReceived(WatchSpentTriggered(alice0.channelId, WatchSpent.ChannelSpent(TestConstants.fundingAmount), remoteCommitPublished.commitTx)))
        assertIs<LNChannel<Closing>>(alice6)
        actionsAlice6.has<ChannelAction.Storage.StoreState>()
        assertNotNull(alice6.state.remoteCommitPublished)
        actionsAlice6.hasWatchConfirmed(remoteCommitPublished.commitTx.txid)
        alice6.state.remoteCommitPublished.claimMainOutputTx?.let {
            actionsAlice6.hasPublishTx(it.tx)
            actionsAlice6.hasWatchConfirmed(it.tx.txid)
        }
        val claimHtlcTimeoutTxs = alice6.state.remoteCommitPublished.claimHtlcTimeoutTxs()
        assertEquals(setOf(htlc1.id, htlc2.id, htlc3.id), claimHtlcTimeoutTxs.map { it.htlcId }.toSet())
        claimHtlcTimeoutTxs.forEach { actionsAlice6.hasPublishTx(it.tx) }
        assertEquals(claimHtlcTimeoutTxs.map { it.input.outPoint }.toSet(), actionsAlice6.findWatches<WatchSpent>().map { OutPoint(it.txId, it.outputIndex.toLong()) }.toSet())

        // Bob's commitment confirms.
        val (alice7, actionsAlice7) = alice6.process(ChannelCommand.WatchReceived(WatchConfirmedTriggered(alice0.channelId, WatchConfirmed.ClosingTxConfirmed, 42, 7, remoteCommitPublished.commitTx)))
        assertEquals(1, actionsAlice7.size)
        actionsAlice7.has<ChannelAction.Storage.StoreState>()

        // Alice extracts the preimage from Bob's HTLC-success and forwards it upstream.
        val (alice8, actionsAlice8) = alice7.process(ChannelCommand.WatchReceived(WatchSpentTriggered(alice0.channelId, WatchSpent.ClosingOutputSpent(50_000.sat), remoteCommitPublished.htlcSuccessTxs().first().tx)))
        val addSettled = actionsAlice8.filterIsInstance<ChannelAction.ProcessCmdRes.AddSettledFulfill>()
        assertEquals(setOf(htlc1, htlc2), addSettled.map { it.htlc }.toSet())
        assertEquals(setOf(ChannelAction.HtlcResult.Fulfill.OnChainFulfill(preimage)), addSettled.map { it.result }.toSet())

        // Alice's Claim-HTLC-timeout transaction confirms: we relay the failure upstream.
        val claimHtlcTimeout = claimHtlcTimeoutTxs.find { it.htlcId == htlc3.id }
        assertNotNull(claimHtlcTimeout)
        val (alice9, actionsAlice9) = alice8.process(ChannelCommand.WatchReceived(WatchConfirmedTriggered(alice0.channelId, WatchConfirmed.ClosingTxConfirmed, 43, 1, claimHtlcTimeout.tx)))
        assertIs<LNChannel<Closing>>(alice9)
        assertEquals(2, actionsAlice9.size)
        actionsAlice9.has<ChannelAction.Storage.StoreState>()
        val addFailed = actionsAlice9.filterIsInstance<ChannelAction.ProcessCmdRes.AddSettledFail>()
        assertEquals(setOf(htlc3), addFailed.map { it.htlc }.toSet())

        // The remaining transactions confirm.
        val watchConfirmed = listOf(
            WatchConfirmedTriggered(alice0.channelId, WatchConfirmed.ClosingTxConfirmed, 47, 0, remoteCommitPublished.htlcSuccessTxs().first().tx),
            WatchConfirmedTriggered(alice0.channelId, WatchConfirmed.ClosingTxConfirmed, 47, 1, remoteCommitPublished.htlcSuccessTxs().last().tx),
            WatchConfirmedTriggered(alice0.channelId, WatchConfirmed.ClosingTxConfirmed, 47, 0, alice9.state.remoteCommitPublished?.claimMainOutputTx?.tx!!),
        )
        confirmWatchedTxs(alice9, watchConfirmed)
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
        val (bob5, remoteCommitPublished) = localClose(bob4)
        assertEquals(3, remoteCommitPublished.htlcTxs.size)
        // Bob doesn't have the preimage yet for any of those HTLCs.
        remoteCommitPublished.htlcTxs.forEach { assertNull(it.value) }
        // Bob receives the preimage for the first two HTLCs.
        val (bob6, actionsBob6) = bob5.process(ChannelCommand.Htlc.Settlement.Fulfill(htlc1.id, preimage))
        assertIs<LNChannel<Closing>>(bob6)
        assertEquals(setOf(htlc1.id, htlc2.id), bob6.state.localCommitPublished?.htlcSuccessTxs().orEmpty().map { it.htlcId }.toSet())
        val htlcSuccessTxs = actionsBob6.filterIsInstance<ChannelAction.Blockchain.PublishTx>().filter { it.txType == ChannelAction.Blockchain.PublishTx.Type.HtlcSuccessTx }
        assertEquals(2, htlcSuccessTxs.size)
        val batchHtlcSuccessTx = Transaction(2, htlcSuccessTxs.flatMap { it.tx.txIn }, htlcSuccessTxs.flatMap { it.tx.txOut }, 0)

        // Alice prepares Claim-HTLC-timeout transactions for each HTLC.
        val (alice5, actionsAlice5) = alice4.process(ChannelCommand.WatchReceived(WatchSpentTriggered(alice0.channelId, WatchSpent.ChannelSpent(TestConstants.fundingAmount), remoteCommitPublished.commitTx)))
        assertIs<LNChannel<Closing>>(alice5)
        val rcp = alice5.state.nextRemoteCommitPublished
        assertNotNull(rcp)
        assertEquals(setOf(htlc1.id, htlc2.id, htlc3.id), rcp.claimHtlcTxs.values.filterNotNull().map { it.htlcId }.toSet())
        val claimHtlcTimeoutTxs = actionsAlice5.filterIsInstance<ChannelAction.Blockchain.PublishTx>().filter { it.txType == ChannelAction.Blockchain.PublishTx.Type.ClaimHtlcTimeoutTx }
        assertEquals(3, claimHtlcTimeoutTxs.size)
        assertEquals(rcp.claimHtlcTxs.keys, actionsAlice5.findWatches<WatchSpent>().map { OutPoint(it.txId, it.outputIndex.toLong()) }.toSet())

        // Alice extracts the preimage from Bob's batched HTLC-success and forwards it upstream.
        val (alice6, actionsAlice6) = alice5.process(ChannelCommand.WatchReceived(WatchSpentTriggered(alice0.channelId, WatchSpent.ClosingOutputSpent(50_000.sat), batchHtlcSuccessTx)))
        val addSettled = actionsAlice6.filterIsInstance<ChannelAction.ProcessCmdRes.AddSettledFulfill>()
        assertEquals(setOf(htlc1, htlc2), addSettled.map { it.htlc }.toSet())
        assertEquals(setOf(ChannelAction.HtlcResult.Fulfill.OnChainFulfill(preimage)), addSettled.map { it.result }.toSet())
        actionsAlice6.hasWatchConfirmed(batchHtlcSuccessTx.txid)

        // Alice's Claim-HTLC-timeout transaction confirms: we relay the failure upstream.
        val claimHtlcTimeout = rcp.claimHtlcTimeoutTxs().find { it.htlcId == htlc3.id }
        assertNotNull(claimHtlcTimeout)
        val (alice7, actionsAlice7) = alice6.process(ChannelCommand.WatchReceived(WatchConfirmedTriggered(alice0.channelId, WatchConfirmed.ClosingTxConfirmed, 42, 0, claimHtlcTimeout.tx)))
        assertIs<LNChannel<Closing>>(alice7)
        val addFailed = actionsAlice7.filterIsInstance<ChannelAction.ProcessCmdRes.AddSettledFail>()
        assertEquals(setOf(htlc3), addFailed.map { it.htlc }.toSet())

        // The remaining transactions confirm.
        val watchConfirmed = listOf(
            WatchConfirmedTriggered(alice0.channelId, WatchConfirmed.ClosingTxConfirmed, 40, 0, remoteCommitPublished.commitTx),
            WatchConfirmedTriggered(alice0.channelId, WatchConfirmed.ClosingTxConfirmed, 41, 1, batchHtlcSuccessTx),
            WatchConfirmedTriggered(alice0.channelId, WatchConfirmed.ClosingTxConfirmed, 47, 7, alice7.state.nextRemoteCommitPublished?.claimMainOutputTx?.tx!!),
        )
        confirmWatchedTxs(alice7, watchConfirmed)
    }

    @Test
    fun `recv ChannelEvent Restore -- next remote commit`() {
        val (alice0, bob0) = reachNormal()
        val (aliceClosing, remoteCommitPublished) = run {
            // alice sends an htlc to bob
            val (nodes1, preimage, _) = addHtlc(30_000_000.msat, alice0, bob0)
            val (alice1, bob1) = nodes1
            assertIs<Normal>(bob1.state)
            val bobCommitTx1 = bob1.commitments.latest.localCommit.publishableTxs.commitTx.tx
            // add more htlcs that bob doesn't revoke
            val (_, cmd1) = makeCmdAdd(20_000_000.msat, bob0.staticParams.nodeParams.nodeId, alice1.currentBlockHeight.toLong(), preimage)
            val (alice2, bob2, _) = addHtlc(cmd1, alice1, bob1)
            val (alice3, actionsAlice3) = alice2.process(ChannelCommand.Commitment.Sign)
            val commitSig = actionsAlice3.hasOutgoingMessage<CommitSig>()
            val (bob3, actionsBob3) = bob2.process(ChannelCommand.MessageReceived(commitSig))
            assertIs<Normal>(bob3.state)
            actionsBob3.hasOutgoingMessage<RevokeAndAck>() // not forwarded to Alice (malicious Bob)
            // Bob publishes the next commit tx.
            val bobCommitTx2 = bob3.commitments.latest.localCommit.publishableTxs.commitTx.tx
            assertNotEquals(bobCommitTx1.txid, bobCommitTx2.txid)
            remoteClose(bobCommitTx2, alice3)
        }

        assertNotNull(remoteCommitPublished.claimMainOutputTx)
        assertTrue(remoteCommitPublished.claimHtlcSuccessTxs().isEmpty())
        assertEquals(2, remoteCommitPublished.claimHtlcTimeoutTxs().size)

        // Simulate a wallet restart
        val initState = LNChannel(aliceClosing.ctx, WaitForInit)
        val (alice1, actions1) = initState.process(ChannelCommand.Init.Restore(aliceClosing.state))
        assertTrue(alice1.state is Closing)
        assertEquals(aliceClosing, alice1)
        actions1.doesNotHave<ChannelAction.Storage.StoreOutgoingPayment.ViaClose>()

        // We should republish closing transactions
        val txs = listOf(
            remoteCommitPublished.claimMainOutputTx.tx,
            remoteCommitPublished.claimHtlcTimeoutTxs().first().tx,
            remoteCommitPublished.claimHtlcTimeoutTxs().last().tx,
        )
        assertEquals(actions1.findPublishTxs(), txs)
        val watchConfirmed = listOf(
            remoteCommitPublished.commitTx.txid,
            remoteCommitPublished.claimMainOutputTx.tx.txid,
        )
        assertEquals(actions1.findWatches<WatchConfirmed>().map { it.txId }, watchConfirmed)
        val watchSpent = listOf(
            remoteCommitPublished.commitTx.txIn.first().outPoint,
            remoteCommitPublished.claimHtlcTimeoutTxs().first().input.outPoint,
            remoteCommitPublished.claimHtlcTimeoutTxs().last().input.outPoint,
        )
        assertEquals(actions1.findWatches<WatchSpent>().map { OutPoint(it.txId, it.outputIndex.toLong()) }, watchSpent)
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

        val localInit = Init(alice0.commitments.params.localParams.features)
        val remoteInit = Init(bob0.commitments.params.localParams.features)

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
        val bobCommitTx = bob2.commitments.latest.localCommit.publishableTxs.commitTx.tx
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
        assertEquals(bobCommitTx.txid, aliceActions3.findWatches<WatchConfirmed>()[0].txId)
        // alice is able to claim its main output
        val aliceTxs = aliceActions3.findPublishTxs()
        assertEquals(listOf(futureRemoteCommitPublished.claimMainOutputTx!!.tx), aliceTxs)
        Transaction.correctlySpends(futureRemoteCommitPublished.claimMainOutputTx.tx, listOf(bobCommitTx), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)

        // simulate a wallet restart
        run {
            val initState = LNChannel(alice3.ctx, WaitForInit)
            val (alice4, actions4) = initState.process(ChannelCommand.Init.Restore(alice3.state))
            assertIs<Closing>(alice4.state)
            assertEquals(alice3, alice4)
            assertEquals(actions4.findPublishTxs(), listOf(futureRemoteCommitPublished.claimMainOutputTx.tx))
            assertEquals(actions4.findWatches<WatchConfirmed>().map { it.txId }, listOf(bobCommitTx.txid, futureRemoteCommitPublished.claimMainOutputTx.tx.txid))
            assertEquals(actions4.findWatches<WatchSpent>().map { OutPoint(it.txId, it.outputIndex.toLong()) }, listOf(bobCommitTx.txIn.first().outPoint))
            actions4.doesNotHave<ChannelAction.Storage.StoreOutgoingPayment.ViaClose>()
        }

        val (alice4, aliceActions4) = alice3.process(ChannelCommand.WatchReceived(WatchConfirmedTriggered(alice0.channelId, WatchConfirmed.ClosingTxConfirmed, 50, 0, bobCommitTx)))
        assertIs<Closing>(alice4.state)
        assertEquals(listOf(ChannelAction.Storage.StoreState(alice4.state)), aliceActions4)

        val (alice5, aliceActions5) = alice4.process(ChannelCommand.WatchReceived(WatchConfirmedTriggered(alice0.channelId, WatchConfirmed.ClosingTxConfirmed, 60, 3, aliceTxs[0])))
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
        val bobRevokedTx = bobCommitTxs[1].commitTx.tx
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
        run {
            assertEquals(1, alice1.state.revokedCommitPublished.size)
            val revokedCommitPublished = alice1.state.revokedCommitPublished[0]
            assertEquals(bobRevokedTx, revokedCommitPublished.commitTx)
            assertNotNull(revokedCommitPublished.claimMainOutputTx)
            assertNotNull(revokedCommitPublished.mainPenaltyTx)
            assertTrue(revokedCommitPublished.htlcPenaltyTxs.isEmpty())
            assertTrue(revokedCommitPublished.claimHtlcDelayedPenaltyTxs.isEmpty())
            Transaction.correctlySpends(revokedCommitPublished.mainPenaltyTx.tx, bobRevokedTx, ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
            // alice publishes txs for the main outputs
            assertEquals(setOf(revokedCommitPublished.claimMainOutputTx.tx, revokedCommitPublished.mainPenaltyTx.tx), aliceActions1.findPublishTxs().toSet())
            // alice watches confirmation for the commit tx and her main output
            assertEquals(setOf(bobRevokedTx.txid, revokedCommitPublished.claimMainOutputTx.tx.txid), aliceActions1.findWatches<WatchConfirmed>().map { it.txId }.toSet())
            // alice watches bob's main output
            assertEquals(setOf(revokedCommitPublished.mainPenaltyTx.input.outPoint.index), aliceActions1.findWatches<WatchSpent>().map { it.outputIndex.toLong() }.toSet())
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
        val aliceTxs = setOf(revokedCommitPublished.claimMainOutputTx.tx, revokedCommitPublished.mainPenaltyTx.tx) + revokedCommitPublished.htlcPenaltyTxs.map { it.tx }.toSet()
        assertEquals(aliceTxs, aliceActions2.findPublishTxs().toSet())
        // alice watches confirmation for the commit tx and her main output
        assertEquals(setOf(bobRevokedTx.txid, revokedCommitPublished.claimMainOutputTx.tx.txid), aliceActions2.findWatches<WatchConfirmed>().map { it.txId }.toSet())
        // alice watches bob's outputs
        val outputsToWatch = buildSet {
            add(revokedCommitPublished.mainPenaltyTx.input.outPoint)
            addAll(revokedCommitPublished.htlcPenaltyTxs.map { it.input.outPoint })
        }
        assertEquals(3, outputsToWatch.size)
        assertEquals(outputsToWatch, aliceActions2.findWatches<WatchSpent>().map { OutPoint(it.txId, it.outputIndex.toLong()) }.toSet())

        // simulate a wallet restart
        run {
            val initState = LNChannel(alice2.ctx, WaitForInit)
            val (alice3, actions3) = initState.process(ChannelCommand.Init.Restore(alice2.state))
            assertIs<Closing>(alice3.state)
            assertEquals(alice2, alice3)
            actions3.doesNotHave<ChannelAction.Storage.StoreOutgoingPayment>()

            // alice republishes transactions
            assertEquals(aliceTxs, actions3.findPublishTxs().toSet())
            assertEquals(setOf(bobRevokedTx.txid, revokedCommitPublished.claimMainOutputTx.tx.txid), actions3.findWatches<WatchConfirmed>().map { it.txId }.toSet())
            val watchSpent = outputsToWatch + alice3.commitments.latest.commitInput.outPoint
            assertEquals(watchSpent, actions3.findWatches<WatchSpent>().map { OutPoint(it.txId, it.outputIndex.toLong()) }.toSet())
        }

        val watchConfirmed = listOf(
            WatchConfirmedTriggered(alice0.channelId, WatchConfirmed.ClosingTxConfirmed, 42, 0, revokedCommitPublished.commitTx),
            WatchConfirmedTriggered(alice0.channelId, WatchConfirmed.ClosingTxConfirmed, 43, 0, revokedCommitPublished.claimMainOutputTx.tx),
            WatchConfirmedTriggered(alice0.channelId, WatchConfirmed.ClosingTxConfirmed, 43, 5, revokedCommitPublished.mainPenaltyTx.tx),
            WatchConfirmedTriggered(alice0.channelId, WatchConfirmed.ClosingTxConfirmed, 50, 1, revokedCommitPublished.htlcPenaltyTxs[1].tx),
            WatchConfirmedTriggered(alice0.channelId, WatchConfirmed.ClosingTxConfirmed, 52, 2, revokedCommitPublished.htlcPenaltyTxs[0].tx),
        )
        confirmWatchedTxs(alice2, watchConfirmed)
    }

    @Test
    fun `recv ChannelSpent -- multiple revoked tx`() {
        val (alice0, _, bobCommitTxs, htlcsAlice, htlcsBob) = prepareRevokedClose()
        assertEquals(bobCommitTxs.size, bobCommitTxs.map { it.commitTx.tx.txid }.toSet().size) // all commit txs are distinct

        // bob publishes one of his revoked txs
        val (alice1, aliceActions1) = alice0.process(ChannelCommand.WatchReceived(WatchSpentTriggered(alice0.channelId, WatchSpent.ChannelSpent(TestConstants.fundingAmount), bobCommitTxs[0].commitTx.tx)))
        assertIs<Closing>(alice1.state)
        aliceActions1.hasOutgoingMessage<Error>()
        aliceActions1.has<ChannelAction.Storage.StoreState>()
        aliceActions1.find<ChannelAction.Storage.StoreOutgoingPayment.ViaClose>().also {
            assertEquals(bobCommitTxs[0].commitTx.tx.txid, it.txId)
            assertEquals(ChannelClosingType.Revoked, it.closingType)
            assertTrue(it.isSentToDefaultAddress)
        }
        assertEquals(1, alice1.state.revokedCommitPublished.size)

        // alice creates penalty txs
        run {
            // alice publishes txs for the main outputs
            assertEquals(2, aliceActions1.findPublishTxs().size)
            // alice watches confirmation for the commit tx and her main output
            assertEquals(2, aliceActions1.findWatches<WatchConfirmed>().size)
            // alice watches bob's main output
            assertEquals(1, aliceActions1.findWatches<WatchSpent>().size)
            // alice fetches information about the revoked htlcs
            assertEquals(ChannelAction.Storage.GetHtlcInfos(bobCommitTxs[0].commitTx.tx.txid, 0), aliceActions1.find())
        }

        // bob publishes another one of his revoked txs
        val (alice2, aliceActions2) = alice1.process(ChannelCommand.WatchReceived(WatchSpentTriggered(alice0.channelId, WatchSpent.ChannelSpent(TestConstants.fundingAmount), bobCommitTxs[1].commitTx.tx)))
        assertIs<Closing>(alice2.state)
        aliceActions2.hasOutgoingMessage<Error>()
        aliceActions2.has<ChannelAction.Storage.StoreState>()
        aliceActions2.doesNotHave<ChannelAction.Storage.StoreOutgoingPayment>()
        assertEquals(2, alice2.state.revokedCommitPublished.size)

        // alice creates penalty txs
        run {
            // alice publishes txs for the main outputs
            assertEquals(2, aliceActions2.findPublishTxs().size)
            // alice watches confirmation for the commit tx and her main output
            assertEquals(2, aliceActions2.findWatches<WatchConfirmed>().size)
            // alice watches bob's main output
            assertEquals(1, aliceActions2.findWatches<WatchSpent>().size)
            // alice fetches information about the revoked htlcs
            assertEquals(ChannelAction.Storage.GetHtlcInfos(bobCommitTxs[1].commitTx.tx.txid, 2), aliceActions2.find())
        }

        val (alice3, aliceActions3) = alice2.process(ChannelCommand.Closing.GetHtlcInfosResponse(bobCommitTxs[0].commitTx.tx.txid, listOf()))
        assertIs<Closing>(alice3.state)
        assertNull(aliceActions3.findOutgoingMessageOpt<Error>())
        aliceActions3.has<ChannelAction.Storage.StoreState>()
        aliceActions3.doesNotHave<ChannelAction.Storage.StoreOutgoingPayment>()

        // alice rebroadcasts main txs for bob's first revoked commitment (no htlc in this commitment)
        run {
            assertEquals(2, alice3.state.revokedCommitPublished.size)
            val revokedCommitPublished = alice3.state.revokedCommitPublished[0]
            assertEquals(bobCommitTxs[0].commitTx.tx, revokedCommitPublished.commitTx)
            assertNotNull(revokedCommitPublished.claimMainOutputTx)
            assertNotNull(revokedCommitPublished.mainPenaltyTx)
            Transaction.correctlySpends(revokedCommitPublished.mainPenaltyTx.tx, bobCommitTxs[0].commitTx.tx, ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
            assertTrue(revokedCommitPublished.htlcPenaltyTxs.isEmpty())
            assertTrue(revokedCommitPublished.claimHtlcDelayedPenaltyTxs.isEmpty())
            // alice publishes txs for all outputs
            assertEquals(2, aliceActions3.findPublishTxs().size)
            assertEquals(2, aliceActions3.findWatches<WatchConfirmed>().size)
            assertEquals(1, aliceActions3.findWatches<WatchSpent>().size)
        }

        val htlcInfos = listOf(
            ChannelAction.Storage.HtlcInfo(alice0.channelId, 2, htlcsAlice[0].paymentHash, htlcsAlice[0].cltvExpiry),
            ChannelAction.Storage.HtlcInfo(alice0.channelId, 2, htlcsBob[0].paymentHash, htlcsBob[0].cltvExpiry),
        )
        val (alice4, aliceActions4) = alice3.process(ChannelCommand.Closing.GetHtlcInfosResponse(bobCommitTxs[1].commitTx.tx.txid, htlcInfos))
        assertIs<LNChannel<Closing>>(alice4)
        assertIs<Closing>(alice4.state)
        assertNull(aliceActions4.findOutgoingMessageOpt<Error>())
        aliceActions4.has<ChannelAction.Storage.StoreState>()
        aliceActions4.doesNotHave<ChannelAction.Storage.StoreOutgoingPayment>()

        // alice creates htlc penalty txs and rebroadcasts main txs for bob's second commitment
        run {
            assertEquals(2, alice4.state.revokedCommitPublished.size)
            val revokedCommitPublished = alice4.state.revokedCommitPublished[1]
            assertEquals(bobCommitTxs[1].commitTx.tx, revokedCommitPublished.commitTx)
            assertNotNull(revokedCommitPublished.claimMainOutputTx)
            assertNotNull(revokedCommitPublished.mainPenaltyTx)
            Transaction.correctlySpends(revokedCommitPublished.mainPenaltyTx.tx, bobCommitTxs[1].commitTx.tx, ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
            assertEquals(2, revokedCommitPublished.htlcPenaltyTxs.size)
            assertTrue(revokedCommitPublished.claimHtlcDelayedPenaltyTxs.isEmpty())
            revokedCommitPublished.htlcPenaltyTxs.forEach { Transaction.correctlySpends(it.tx, bobCommitTxs[1].commitTx.tx, ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS) }
            // alice publishes txs for all outputs
            assertEquals(4, aliceActions4.findPublishTxs().size)
            assertEquals(2, aliceActions4.findWatches<WatchConfirmed>().size)
            assertEquals(3, aliceActions4.findWatches<WatchSpent>().size)

            // this revoked transaction is the one to confirm
            val watchConfirmed = listOf(
                WatchConfirmedTriggered(alice0.channelId, WatchConfirmed.ClosingTxConfirmed, 42, 0, revokedCommitPublished.commitTx),
                WatchConfirmedTriggered(alice0.channelId, WatchConfirmed.ClosingTxConfirmed, 43, 0, revokedCommitPublished.claimMainOutputTx.tx),
                WatchConfirmedTriggered(alice0.channelId, WatchConfirmed.ClosingTxConfirmed, 43, 5, revokedCommitPublished.mainPenaltyTx.tx),
                WatchConfirmedTriggered(alice0.channelId, WatchConfirmed.ClosingTxConfirmed, 50, 1, revokedCommitPublished.htlcPenaltyTxs[1].tx),
                WatchConfirmedTriggered(alice0.channelId, WatchConfirmed.ClosingTxConfirmed, 52, 2, revokedCommitPublished.htlcPenaltyTxs[0].tx),
            )
            confirmWatchedTxs(alice4, watchConfirmed)
        }
    }

    @Test
    fun `recv ClosingTxConfirmed -- one revoked tx + pending htlcs`() {
        val (alice0, bob0) = reachNormal()
        // bob's first commit tx doesn't contain any htlc
        assertEquals(4, bob0.commitments.latest.localCommit.publishableTxs.commitTx.tx.txOut.size) // 2 main outputs + 2 anchors

        // bob's second commit tx contains 2 incoming htlcs
        val (alice1, bob1, htlcs1) = run {
            val (nodes1, _, htlc1) = addHtlc(35_000_000.msat, alice0, bob0)
            val (alice1, bob1) = nodes1
            val (nodes2, _, htlc2) = addHtlc(20_000_000.msat, alice1, bob1)
            val (alice2, bob2) = nodes2
            val (alice3, bob3) = crossSign(alice2, bob2)
            assertIs<Normal>(bob3.state)
            assertEquals(6, bob3.commitments.latest.localCommit.publishableTxs.commitTx.tx.txOut.size)
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
            assertEquals(7, bob5.state.commitments.latest.localCommit.publishableTxs.commitTx.tx.txOut.size)
            Triple(alice5, bob5, listOf(htlc3, htlc4))
        }

        // bob publishes a revoked tx
        val bobRevokedTx = bob1.commitments.latest.localCommit.publishableTxs.commitTx.tx
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

        // bob publishes one of his revoked txs
        val bobRevokedTx = bobCommitTxs[2]
        assertEquals(8, bobRevokedTx.commitTx.tx.txOut.size)

        val (alice1, aliceActions1) = alice0.process(ChannelCommand.WatchReceived(WatchSpentTriggered(alice0.channelId, WatchSpent.ChannelSpent(TestConstants.fundingAmount), bobRevokedTx.commitTx.tx)))
        assertIs<Closing>(alice1.state)
        aliceActions1.hasOutgoingMessage<Error>()
        aliceActions1.has<ChannelAction.Storage.StoreState>()
        aliceActions1.find<ChannelAction.Storage.StoreOutgoingPayment.ViaClose>().also {
            assertEquals(bobRevokedTx.commitTx.tx.txid, it.txId)
            assertEquals(ChannelClosingType.Revoked, it.closingType)
            assertTrue(it.isSentToDefaultAddress)
        }

        // alice creates penalty txs
        run {
            val revokedCommitPublished = alice1.state.revokedCommitPublished[0]
            assertTrue(revokedCommitPublished.htlcPenaltyTxs.isEmpty())
            assertTrue(revokedCommitPublished.claimHtlcDelayedPenaltyTxs.isEmpty())
            // alice publishes txs for the main outputs and sets watches
            assertEquals(2, aliceActions1.findPublishTxs().size)
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
        val (alice2, aliceActions2) = alice1.process(ChannelCommand.Closing.GetHtlcInfosResponse(bobRevokedTx.commitTx.tx.txid, htlcInfos))
        assertIs<Closing>(alice2.state)
        assertNull(aliceActions2.findOutgoingMessageOpt<Error>())
        aliceActions2.has<ChannelAction.Storage.StoreState>()
        aliceActions2.doesNotHave<ChannelAction.Storage.StoreOutgoingPayment>()

        // alice creates htlc penalty txs and rebroadcasts main txs
        run {
            val revokedCommitPublished = alice2.state.revokedCommitPublished[0]
            assertNotNull(revokedCommitPublished.claimMainOutputTx)
            assertNotNull(revokedCommitPublished.mainPenaltyTx)
            assertEquals(4, revokedCommitPublished.htlcPenaltyTxs.size)
            assertTrue(revokedCommitPublished.claimHtlcDelayedPenaltyTxs.isEmpty())
            revokedCommitPublished.htlcPenaltyTxs.forEach { Transaction.correctlySpends(it.tx, bobRevokedTx.commitTx.tx, ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS) }
            // alice publishes txs for all outputs
            assertEquals(setOf(revokedCommitPublished.claimMainOutputTx.tx, revokedCommitPublished.mainPenaltyTx.tx) + revokedCommitPublished.htlcPenaltyTxs.map { it.tx }.toSet(), aliceActions2.findPublishTxs().toSet())
            // alice watches confirmation for the commit tx and her main output
            assertEquals(setOf(bobRevokedTx.commitTx.tx.txid, revokedCommitPublished.claimMainOutputTx.tx.txid), aliceActions2.findWatches<WatchConfirmed>().map { it.txId }.toSet())
            // alice watches bob's outputs
            val outputsToWatch = buildSet {
                add(revokedCommitPublished.mainPenaltyTx.input.outPoint.index)
                addAll(revokedCommitPublished.htlcPenaltyTxs.map { it.input.outPoint.index })
            }
            assertEquals(5, outputsToWatch.size)
            assertEquals(outputsToWatch, aliceActions2.findWatches<WatchSpent>().map { it.outputIndex.toLong() }.toSet())

            // bob manages to claim 2 htlc outputs before alice can penalize him: 1 htlc-success and 1 htlc-timeout.
            val bobHtlcSuccessTx = bobRevokedTx.htlcTxsAndSigs.find { htlcsAlice[0].amountMsat == it.txinfo.amountIn.toMilliSatoshi() }!!
            val bobHtlcTimeoutTx = bobRevokedTx.htlcTxsAndSigs.find { htlcsBob[1].amountMsat == it.txinfo.amountIn.toMilliSatoshi() }!!
            val bobOutpoints = listOf(bobHtlcSuccessTx, bobHtlcTimeoutTx).map { it.txinfo.input.outPoint }.toSet()
            assertEquals(2, bobOutpoints.size)

            // alice reacts by publishing penalty txs that spend bob's htlc transactions
            val (alice3, actions3) = alice2.process(ChannelCommand.WatchReceived(WatchSpentTriggered(alice0.channelId, WatchSpent.ClosingOutputSpent(bobHtlcSuccessTx.txinfo.amountIn), bobHtlcSuccessTx.txinfo.tx)))
            assertIs<Closing>(alice3.state)
            assertEquals(4, actions3.size)
            assertEquals(1, alice3.state.revokedCommitPublished[0].claimHtlcDelayedPenaltyTxs.size)
            assertTrue(actions3.contains(ChannelAction.Storage.StoreState(alice3.state)))
            assertEquals(WatchConfirmed(alice0.channelId, bobHtlcSuccessTx.txinfo.tx, alice0.staticParams.nodeParams.minDepthBlocks, WatchConfirmed.ClosingTxConfirmed), actions3.findWatch())
            actions3.hasPublishTx(alice3.state.revokedCommitPublished[0].claimHtlcDelayedPenaltyTxs[0].tx)
            assertEquals(WatchSpent(alice0.channelId, bobHtlcSuccessTx.txinfo.tx, alice3.state.revokedCommitPublished[0].claimHtlcDelayedPenaltyTxs[0].input.outPoint.index.toInt(), WatchSpent.ClosingOutputSpent(bobHtlcSuccessTx.txinfo.tx.txOut[0].amount)), actions3.findWatch())

            val (alice4, actions4) = alice3.process(ChannelCommand.WatchReceived(WatchSpentTriggered(alice0.channelId, WatchSpent.ClosingOutputSpent(bobHtlcTimeoutTx.txinfo.amountIn), bobHtlcTimeoutTx.txinfo.tx)))
            assertIs<LNChannel<Closing>>(alice4)
            assertIs<Closing>(alice4.state)
            assertEquals(4, actions4.size)
            assertEquals(2, alice4.state.revokedCommitPublished[0].claimHtlcDelayedPenaltyTxs.size)
            assertTrue(actions4.contains(ChannelAction.Storage.StoreState(alice4.state)))
            assertEquals(WatchConfirmed(alice0.channelId, bobHtlcTimeoutTx.txinfo.tx, alice0.staticParams.nodeParams.minDepthBlocks, WatchConfirmed.ClosingTxConfirmed), actions4.findWatch())
            actions4.hasPublishTx(alice4.state.revokedCommitPublished[0].claimHtlcDelayedPenaltyTxs[1].tx)
            assertEquals(WatchSpent(alice0.channelId, bobHtlcTimeoutTx.txinfo.tx, alice4.state.revokedCommitPublished[0].claimHtlcDelayedPenaltyTxs[1].input.outPoint.index.toInt(), WatchSpent.ClosingOutputSpent(bobHtlcTimeoutTx.txinfo.tx.txOut[0].amount)), actions4.findWatch())

            val claimHtlcDelayedPenaltyTxs = alice4.state.revokedCommitPublished[0].claimHtlcDelayedPenaltyTxs
            val remainingHtlcPenaltyTxs = revokedCommitPublished.htlcPenaltyTxs.filterNot { bobOutpoints.contains(it.input.outPoint) }
            assertEquals(2, remainingHtlcPenaltyTxs.size)
            val watchConfirmed = listOf(
                WatchConfirmedTriggered(alice0.channelId, WatchConfirmed.ClosingTxConfirmed, 42, 0, revokedCommitPublished.commitTx),
                WatchConfirmedTriggered(alice0.channelId, WatchConfirmed.ClosingTxConfirmed, 43, 0, revokedCommitPublished.claimMainOutputTx.tx),
                WatchConfirmedTriggered(alice0.channelId, WatchConfirmed.ClosingTxConfirmed, 43, 5, revokedCommitPublished.mainPenaltyTx.tx),
                WatchConfirmedTriggered(alice0.channelId, WatchConfirmed.ClosingTxConfirmed, 50, 1, remainingHtlcPenaltyTxs[1].tx),
                WatchConfirmedTriggered(alice0.channelId, WatchConfirmed.ClosingTxConfirmed, 50, 2, bobHtlcSuccessTx.txinfo.tx),
                WatchConfirmedTriggered(alice0.channelId, WatchConfirmed.ClosingTxConfirmed, 50, 3, remainingHtlcPenaltyTxs[0].tx),
                WatchConfirmedTriggered(alice0.channelId, WatchConfirmed.ClosingTxConfirmed, 51, 3, claimHtlcDelayedPenaltyTxs[0].tx),
                WatchConfirmedTriggered(alice0.channelId, WatchConfirmed.ClosingTxConfirmed, 51, 0, bobHtlcTimeoutTx.txinfo.tx),
                WatchConfirmedTriggered(alice0.channelId, WatchConfirmed.ClosingTxConfirmed, 51, 1, claimHtlcDelayedPenaltyTxs[1].tx),
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

        val (alice1, _) = alice0.process(ChannelCommand.WatchReceived(WatchSpentTriggered(alice0.channelId, WatchSpent.ChannelSpent(TestConstants.fundingAmount), bobRevokedTx.commitTx.tx)))
        assertIs<Closing>(alice1.state)

        // alice fetches information about the revoked htlcs
        val htlcInfos = listOf(
            ChannelAction.Storage.HtlcInfo(alice0.channelId, 4, htlcsAlice[0].paymentHash, htlcsAlice[0].cltvExpiry),
            ChannelAction.Storage.HtlcInfo(alice0.channelId, 4, htlcsAlice[1].paymentHash, htlcsAlice[1].cltvExpiry),
            ChannelAction.Storage.HtlcInfo(alice0.channelId, 4, htlcsBob[0].paymentHash, htlcsBob[0].cltvExpiry),
            ChannelAction.Storage.HtlcInfo(alice0.channelId, 4, htlcsBob[1].paymentHash, htlcsBob[1].cltvExpiry),
        )
        val (alice2, _) = alice1.process(ChannelCommand.Closing.GetHtlcInfosResponse(bobRevokedTx.commitTx.tx.txid, htlcInfos))
        assertIs<Closing>(alice2.state)

        // bob claims multiple htlc outputs in a single transaction (this is possible with anchor outputs because signatures
        // use sighash_single | sighash_anyonecanpay)
        assertEquals(4, bobRevokedTx.htlcTxsAndSigs.size)
        val bobHtlcTx = Transaction(
            2,
            listOf(
                TxIn(OutPoint(TxId(Lightning.randomBytes32()), 4), listOf(), 1), // unrelated utxo (maybe used for fee bumping)
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

        val (alice3, actions3) = alice2.process(ChannelCommand.WatchReceived(WatchSpentTriggered(alice0.channelId, WatchSpent.ClosingOutputSpent(bobRevokedTx.htlcTxsAndSigs[0].txinfo.amountIn), bobHtlcTx)))
        assertIs<Closing>(alice3.state)
        assertEquals(10, actions3.size)
        assertEquals(4, alice3.state.revokedCommitPublished[0].claimHtlcDelayedPenaltyTxs.size)
        val claimHtlcDelayedPenaltyTxs = alice3.state.revokedCommitPublished[0].claimHtlcDelayedPenaltyTxs
        claimHtlcDelayedPenaltyTxs.forEach { Transaction.correctlySpends(it.tx, bobHtlcTx, ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS) }
        assertEquals(setOf(OutPoint(bobHtlcTx, 1), OutPoint(bobHtlcTx, 2), OutPoint(bobHtlcTx, 3), OutPoint(bobHtlcTx, 4)), claimHtlcDelayedPenaltyTxs.map { it.input.outPoint }.toSet())
        assertTrue(actions3.contains(ChannelAction.Storage.StoreState(alice3.state)))
        assertEquals(WatchConfirmed(alice0.channelId, bobHtlcTx, alice0.staticParams.nodeParams.minDepthBlocks, WatchConfirmed.ClosingTxConfirmed), actions3.findWatch())
        actions3.hasPublishTx(claimHtlcDelayedPenaltyTxs[0].tx)
        actions3.hasPublishTx(claimHtlcDelayedPenaltyTxs[1].tx)
        actions3.hasPublishTx(claimHtlcDelayedPenaltyTxs[2].tx)
        actions3.hasPublishTx(claimHtlcDelayedPenaltyTxs[3].tx)
        val watchSpent = actions3.findWatches<WatchSpent>().toSet()
        val expected = setOf(
            WatchSpent(alice0.channelId, bobHtlcTx, 1, WatchSpent.ClosingOutputSpent(claimHtlcDelayedPenaltyTxs[0].amountIn)),
            WatchSpent(alice0.channelId, bobHtlcTx, 2, WatchSpent.ClosingOutputSpent(claimHtlcDelayedPenaltyTxs[1].amountIn)),
            WatchSpent(alice0.channelId, bobHtlcTx, 3, WatchSpent.ClosingOutputSpent(claimHtlcDelayedPenaltyTxs[2].amountIn)),
            WatchSpent(alice0.channelId, bobHtlcTx, 4, WatchSpent.ClosingOutputSpent(claimHtlcDelayedPenaltyTxs[3].amountIn)),
        )
        assertEquals(expected, watchSpent)
    }

    @Test
    fun `recv ChannelReestablish`() {
        val (alice, bob) = reachNormal()
        val (alice1, lcp) = localClose(alice)
        val bobCurrentPerCommitmentPoint = bob.commitments.params.localParams.channelKeys(bob.ctx.keyManager).commitmentPoint(bob.commitments.localCommitIndex)
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
            val (alice3, _) = localClose(alice2)
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
        data class RevokedCloseFixture(val alice: LNChannel<Normal>, val bob: LNChannel<Normal>, val bobRevokedTxs: List<PublishableTxs>, val htlcsAlice: List<UpdateAddHtlc>, val htlcsBob: List<UpdateAddHtlc>)

        fun prepareRevokedClose(): RevokedCloseFixture {
            val (aliceInit, bobInit) = reachNormal()
            var mutableAlice: LNChannel<Normal> = aliceInit
            var mutableBob: LNChannel<Normal> = bobInit

            // Bob's first commit tx doesn't contain any htlc
            val commitTx1 = bobInit.commitments.latest.localCommit.publishableTxs
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

                val commitTx = mutableBob.commitments.latest.localCommit.publishableTxs
                Triple(commitTx, htlcAlice, htlcBob)
            }
            assertEquals(6, commitTx2.commitTx.tx.txOut.size)
            assertEquals(6, mutableAlice.commitments.latest.localCommit.publishableTxs.commitTx.tx.txOut.size)

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

                val commitTx = mutableBob.commitments.latest.localCommit.publishableTxs
                Triple(commitTx, htlcAlice, htlcBob)
            }
            assertEquals(8, commitTx3.commitTx.tx.txOut.size)
            assertEquals(8, mutableAlice.commitments.latest.localCommit.publishableTxs.commitTx.tx.txOut.size)

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
                mutableBob.commitments.latest.localCommit.publishableTxs
            }
            assertEquals(4, commitTx4.commitTx.tx.txOut.size)
            assertEquals(4, mutableAlice.commitments.latest.localCommit.publishableTxs.commitTx.tx.txOut.size)

            return RevokedCloseFixture(mutableAlice, mutableBob, listOf(commitTx1, commitTx2, commitTx3, commitTx4), listOf(htlcAlice1, htlcAlice2), listOf(htlcBob1, htlcBob2))
        }

        private fun confirmWatchedTxs(firstClosingState: LNChannel<Closing>, watchConfirmed: List<WatchConfirmedTriggered>) {
            var alice = firstClosingState
            watchConfirmed.dropLast(1).forEach {
                val (aliceNew, actions) = alice.process(ChannelCommand.WatchReceived(it))
                assertIs<LNChannel<Closing>>(aliceNew)
                assertTrue(actions.contains(ChannelAction.Storage.StoreState(aliceNew.state)))
                // The only other possible actions are for settling htlcs
                assertEquals(actions.size - 1, actions.count { action -> action is ChannelAction.ProcessCmdRes })
                alice = aliceNew
            }

            val (aliceClosed, actions) = alice.process(ChannelCommand.WatchReceived(watchConfirmed.last()))
            assertIs<Closed>(aliceClosed.state)
            assertContains(actions, ChannelAction.Storage.StoreState(aliceClosed.state))
            actions.has<ChannelAction.Storage.SetLocked>()
        }
    }

}
