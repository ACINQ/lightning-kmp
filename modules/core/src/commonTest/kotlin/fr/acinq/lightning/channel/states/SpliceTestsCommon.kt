package fr.acinq.lightning.channel.states

import fr.acinq.bitcoin.*
import fr.acinq.bitcoin.crypto.musig2.IndividualNonce
import fr.acinq.lightning.Lightning.randomBytes
import fr.acinq.lightning.Lightning.randomBytes32
import fr.acinq.lightning.Lightning.randomKey
import fr.acinq.lightning.blockchain.WatchConfirmed
import fr.acinq.lightning.blockchain.WatchConfirmedTriggered
import fr.acinq.lightning.blockchain.WatchSpent
import fr.acinq.lightning.blockchain.WatchSpentTriggered
import fr.acinq.lightning.blockchain.electrum.WalletState
import fr.acinq.lightning.blockchain.fee.FeeratePerKw
import fr.acinq.lightning.channel.*
import fr.acinq.lightning.channel.TestsHelper.addHtlc
import fr.acinq.lightning.channel.TestsHelper.crossSign
import fr.acinq.lightning.channel.TestsHelper.fulfillHtlc
import fr.acinq.lightning.channel.TestsHelper.reachNormal
import fr.acinq.lightning.channel.TestsHelper.useAlternativeCommitSig
import fr.acinq.lightning.crypto.KeyManager
import fr.acinq.lightning.tests.TestConstants
import fr.acinq.lightning.tests.utils.LightningTestSuite
import fr.acinq.lightning.transactions.Transactions
import fr.acinq.lightning.transactions.incomings
import fr.acinq.lightning.transactions.outgoings
import fr.acinq.lightning.utils.msat
import fr.acinq.lightning.utils.sat
import fr.acinq.lightning.utils.sum
import fr.acinq.lightning.utils.toMilliSatoshi
import fr.acinq.lightning.wire.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlin.math.abs
import kotlin.test.*

class SpliceTestsCommon : LightningTestSuite() {

    @Test
    fun `splice funds out`() {
        val (alice, bob) = reachNormal()
        spliceOut(alice, bob, 50_000.sat)
    }

    @Test
    fun `splice funds out and upgrade to taproot`() {
        val (alice, bob) = reachNormal(channelType = ChannelType.SupportedChannelType.AnchorOutputsZeroReserve)
        assertEquals(Transactions.CommitmentFormat.AnchorOutputs, alice.commitments.latest.commitmentFormat)
        assertEquals(Transactions.CommitmentFormat.AnchorOutputs, bob.commitments.latest.commitmentFormat)

        val (alice1, bob1) = spliceOut(alice, bob, 50_000.sat)
        assertEquals(Transactions.CommitmentFormat.SimpleTaprootChannels, alice1.commitments.latest.commitmentFormat)
        assertEquals(Transactions.CommitmentFormat.SimpleTaprootChannels, bob1.commitments.latest.commitmentFormat)
    }

    @Test
    fun `splice funds in`() {
        val (alice, bob) = reachNormal()
        spliceIn(alice, bob, listOf(50_000.sat))
    }

    @Test
    fun `splice funds in and upgrade to taproot`() {
        val (alice, bob) = reachNormal(channelType = ChannelType.SupportedChannelType.AnchorOutputsZeroReserve)
        assertEquals(Transactions.CommitmentFormat.AnchorOutputs, alice.commitments.latest.commitmentFormat)
        assertEquals(Transactions.CommitmentFormat.AnchorOutputs, bob.commitments.latest.commitmentFormat)

        val (alice1, bob1) = spliceIn(alice, bob, listOf(50_000.sat))
        assertEquals(Transactions.CommitmentFormat.SimpleTaprootChannels, alice1.commitments.latest.commitmentFormat)
        assertEquals(Transactions.CommitmentFormat.SimpleTaprootChannels, bob1.commitments.latest.commitmentFormat)
    }

    @Test
    fun `splice funds in and out with pending htlcs`() {
        val (alice, bob) = reachNormalWithConfirmedFundingTx()
        val (alice1, bob1, htlcs) = setupHtlcs(alice, bob)
        val (alice2, bob2) = spliceInAndOut(alice1, bob1, inAmounts = listOf(50_000.sat), outAmount = 100_000.sat)

        // Bob sends an HTLC that is applied to both commitments.
        val (nodes3, preimage, add) = addHtlc(10_000_000.msat, bob2, alice2)
        val (bob4, alice4) = crossSign(nodes3.first, nodes3.second, commitmentsCount = 2)

        alice4.commitments.active.forEach { c ->
            val commitTx = c.fullySignedCommitTx(alice4.commitments.channelParams, alice4.channelKeys)
            Transaction.correctlySpends(commitTx, mapOf(c.fundingInput to c.localFundingStatus.txOut), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
        }
        bob4.state.commitments.active.forEach { c ->
            val commitTx = c.fullySignedCommitTx(bob4.commitments.channelParams, bob4.channelKeys)
            Transaction.correctlySpends(commitTx, mapOf(c.fundingInput to c.localFundingStatus.txOut), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
        }

        // Alice fulfills that HTLC in both commitments.
        val (bob5, alice5) = fulfillHtlc(add.id, preimage, bob4, alice4)
        val (alice6, bob6) = crossSign(alice5, bob5, commitmentsCount = 2)

        alice6.state.commitments.active.forEach { c ->
            val commitTx = c.fullySignedCommitTx(alice6.commitments.channelParams, alice6.channelKeys)
            Transaction.correctlySpends(commitTx, mapOf(c.fundingInput to c.localFundingStatus.txOut), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
        }
        bob6.state.commitments.active.forEach { c ->
            val commitTx = c.fullySignedCommitTx(bob6.commitments.channelParams, bob6.channelKeys)
            Transaction.correctlySpends(commitTx, mapOf(c.fundingInput to c.localFundingStatus.txOut), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
        }

        resolveHtlcs(alice6, bob6, htlcs, commitmentsCount = 2)
    }

    @Test
    fun `splice funds in and out with pending htlcs -- upgrade to taproot`() {
        val (alice, bob) = reachNormalWithConfirmedFundingTx(channelType = ChannelType.SupportedChannelType.AnchorOutputsZeroReserve)
        val (alice1, bob1, htlcs) = setupHtlcs(alice, bob)
        val (alice2, bob2) = spliceInAndOut(alice1, bob1, inAmounts = listOf(50_000.sat), outAmount = 100_000.sat)

        // Bob sends an HTLC that is applied to both commitments.
        val (nodes3, preimage, add) = addHtlc(10_000_000.msat, bob2, alice2)
        val (bob4, alice4) = crossSign(nodes3.first, nodes3.second, commitmentsCount = 2)

        alice4.commitments.active.forEach { c ->
            val commitTx = c.fullySignedCommitTx(alice4.commitments.channelParams, alice4.channelKeys)
            Transaction.correctlySpends(commitTx, mapOf(c.fundingInput to c.localFundingStatus.txOut), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
        }
        bob4.state.commitments.active.forEach { c ->
            val commitTx = c.fullySignedCommitTx(bob4.commitments.channelParams, bob4.channelKeys)
            Transaction.correctlySpends(commitTx, mapOf(c.fundingInput to c.localFundingStatus.txOut), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
        }

        // Alice fulfills that HTLC in both commitments.
        val (bob5, alice5) = fulfillHtlc(add.id, preimage, bob4, alice4)
        val (alice6, bob6) = crossSign(alice5, bob5, commitmentsCount = 2)

        alice6.state.commitments.active.forEach { c ->
            val commitTx = c.fullySignedCommitTx(alice6.commitments.channelParams, alice6.channelKeys)
            Transaction.correctlySpends(commitTx, mapOf(c.fundingInput to c.localFundingStatus.txOut), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
        }
        bob6.state.commitments.active.forEach { c ->
            val commitTx = c.fullySignedCommitTx(bob6.commitments.channelParams, bob6.channelKeys)
            Transaction.correctlySpends(commitTx, mapOf(c.fundingInput to c.localFundingStatus.txOut), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
        }

        resolveHtlcs(alice6, bob6, htlcs, commitmentsCount = 2)
    }

    @Test
    fun `splice funds in and out with pending htlcs -- missing nonce`() {
        val (alice, bob) = reachNormalWithConfirmedFundingTx()
        val (alice1, bob1, htlcs) = setupHtlcs(alice, bob)
        val (alice2, bob2) = spliceInAndOut(alice1, bob1, inAmounts = listOf(50_000.sat), outAmount = 100_000.sat)
        // Bob starts fulfilling an HTLC after the splice.
        val (preimage1a, htlc1a) = htlcs.aliceToBob.first()
        val (alice3, bob3) = fulfillHtlc(htlc1a.id, preimage1a, alice2, bob2)
        val (bob4, actionsBob4) = bob3.process(ChannelCommand.Commitment.Sign)
        val (alice4, actionsAlice4) = alice3.process(ChannelCommand.MessageReceived(actionsBob4.findOutgoingMessage<CommitSigs>()))
        val rev = actionsAlice4.findOutgoingMessage<RevokeAndAck>()
        assertEquals(2, rev.nextCommitNonces.size)
        assertNotNull(rev.nextCommitNonces[alice.commitments.latest.fundingTxId])
        assertNotNull(rev.nextCommitNonces[alice4.commitments.latest.fundingTxId])
        // Alice doesn't include a nonce for the previous commitment.
        val missingNextCommitNonces = RevokeAndAckTlv.NextLocalNonces(listOf(Pair(alice4.commitments.latest.fundingTxId, rev.nextCommitNonces[alice4.commitments.latest.fundingTxId]!!)))
        val invalidRev = rev.copy(tlvStream = TlvStream(rev.tlvStream.records.filterNot { it is RevokeAndAckTlv.NextLocalNonces }.toSet() + missingNextCommitNonces))
        val (bob5, actionsBob5) = bob4.process(ChannelCommand.MessageReceived(invalidRev))
        assertIs<Closing>(bob5.state)
        actionsBob5.hasOutgoingMessage<Error>()
    }

    @Test
    fun `splice funds in and out with pending htlcs -- invalid nonce`() {
        val (alice, bob) = reachNormalWithConfirmedFundingTx()
        val (alice1, bob1, htlcs) = setupHtlcs(alice, bob)
        val (alice2, bob2) = spliceInAndOut(alice1, bob1, inAmounts = listOf(50_000.sat), outAmount = 100_000.sat)
        // Bob starts fulfilling an HTLC after the splice.
        val (preimage1a, htlc1a) = htlcs.aliceToBob.first()
        val (alice3, bob3) = fulfillHtlc(htlc1a.id, preimage1a, alice2, bob2)
        val (bob4, actionsBob4) = bob3.process(ChannelCommand.Commitment.Sign)
        val (alice4, actionsAlice4) = alice3.process(ChannelCommand.MessageReceived(actionsBob4.findOutgoingMessage<CommitSigs>()))
        val rev = actionsAlice4.findOutgoingMessage<RevokeAndAck>()
        assertEquals(2, rev.nextCommitNonces.size)
        assertNotNull(rev.nextCommitNonces[alice.commitments.latest.fundingTxId])
        assertNotNull(rev.nextCommitNonces[alice4.commitments.latest.fundingTxId])
        // Alice includes an invalid nonce for the previous commitment.
        // This will apply for the next update, not the current one: the channel isn't immediately closed.
        val invalidNextCommitNonces = RevokeAndAckTlv.NextLocalNonces((rev.nextCommitNonces + mapOf(alice.commitments.latest.fundingTxId to IndividualNonce(randomBytes(66)))).toList())
        val invalidRev = rev.copy(tlvStream = TlvStream(rev.tlvStream.records.filterNot { it is RevokeAndAckTlv.NextLocalNonces }.toSet() + invalidNextCommitNonces))
        val (bob5, actionsBob5) = bob4.process(ChannelCommand.MessageReceived(invalidRev))
        assertEquals(1, actionsBob5.size)
        actionsBob5.has<ChannelAction.Storage.StoreState>()
        val (alice5, actionsAlice5) = alice4.process(ChannelCommand.Commitment.Sign)
        val (bob6, actionsBob6) = bob5.process(ChannelCommand.MessageReceived(actionsAlice5.findOutgoingMessage<CommitSigs>()))
        val (alice6, _) = alice5.process(ChannelCommand.MessageReceived(actionsBob6.findOutgoingMessage<RevokeAndAck>()))
        // Bob will fail to sign the next commitment: Alice will need to reconnect and send valid nonces.
        val (preimage2a, htlc2a) = htlcs.aliceToBob.last()
        val (_, bob7) = fulfillHtlc(htlc2a.id, preimage2a, alice6, bob6)
        val (bob8, actionsBob8) = bob7.process(ChannelCommand.Commitment.Sign)
        assertIs<Normal>(bob8.state)
        assertEquals(1, actionsBob8.size)
        actionsBob8.find<ChannelAction.ProcessCmdRes.NotExecuted>().also { assertIs<InvalidCommitNonce>(it.t) }
    }

    @Test
    fun `splice funds in and out with pending htlcs resolved after splice locked`() {
        val (alice, bob) = reachNormalWithConfirmedFundingTx()
        val (alice1, bob1, htlcs) = setupHtlcs(alice, bob)
        val (alice2, bob2) = spliceInAndOut(alice1, bob1, inAmounts = listOf(50_000.sat), outAmount = 100_000.sat)
        val spliceTx = alice2.commitments.latest.localFundingStatus.signedTx!!
        val (alice3, actionsAlice3) = alice2.process(ChannelCommand.WatchReceived(WatchConfirmedTriggered(alice2.channelId, WatchConfirmed.ChannelFundingDepthOk, 42, 0, spliceTx)))
        val (bob3, _) = bob2.process(ChannelCommand.MessageReceived(actionsAlice3.findOutgoingMessage<SpliceLocked>()))
        val (bob4, actionsBob4) = bob3.process(ChannelCommand.WatchReceived(WatchConfirmedTriggered(bob3.channelId, WatchConfirmed.ChannelFundingDepthOk, 42, 0, spliceTx)))
        val (alice4, _) = alice3.process(ChannelCommand.MessageReceived(actionsBob4.findOutgoingMessage<SpliceLocked>()))
        assertIs<LNChannel<Normal>>(alice4)
        assertIs<LNChannel<Normal>>(bob4)
        resolveHtlcs(alice4, bob4, htlcs, commitmentsCount = 1)
    }

    @Test
    fun `splice funds in -- non-initiator`() {
        val (alice, bob) = reachNormal()
        spliceIn(bob, alice, listOf(50_000.sat))
    }

    @Test
    fun `splice funds in -- many utxos`() {
        val (alice, bob) = reachNormal()
        spliceIn(alice, bob, listOf(30_000.sat, 40_000.sat, 25_000.sat))
    }

    @Test
    fun `splice funds in -- local and remote commit index mismatch`() {
        // Alice and Bob asynchronously exchange HTLCs, which makes their commit indices diverge.
        val (nodes, preimages) = run {
            val (alice0, bob0) = reachNormal()
            // Alice sends an HTLC to Bob and signs it.
            val (nodes1, preimage1, _) = addHtlc(15_000_000.msat, alice0, bob0)
            val (alice1, bob1) = nodes1
            val (alice2, actionsAlice2) = alice1.process(ChannelCommand.Commitment.Sign)
            val sigAlice1 = actionsAlice2.findOutgoingMessage<CommitSig>()
            // Bob sends an HTLC to Alice before receiving her commit_sig.
            val (nodes3, preimage2, _) = addHtlc(10_000_000.msat, bob1, alice2)
            val (bob3, alice3) = nodes3
            // Bob receives Alice's commit_sig and also signs his HTLC.
            val (bob4, actionsBob4) = bob3.process(ChannelCommand.MessageReceived(sigAlice1))
            val revBob1 = actionsBob4.findOutgoingMessage<RevokeAndAck>()
            actionsBob4.hasCommand<ChannelCommand.Commitment.Sign>()
            val (bob5, actionsBob5) = bob4.process(ChannelCommand.Commitment.Sign)
            val sigBob = actionsBob5.findOutgoingMessage<CommitSig>()
            val (alice4, _) = alice3.process(ChannelCommand.MessageReceived(revBob1))
            val (alice5, actionsAlice5) = alice4.process(ChannelCommand.MessageReceived(sigBob))
            val revAlice = actionsAlice5.findOutgoingMessage<RevokeAndAck>()
            actionsAlice5.hasCommand<ChannelCommand.Commitment.Sign>()
            val (alice6, actionsAlice6) = alice5.process(ChannelCommand.Commitment.Sign)
            val sigAlice2 = actionsAlice6.findOutgoingMessage<CommitSig>()
            val (bob6, _) = bob5.process(ChannelCommand.MessageReceived(revAlice))
            val (bob7, actionsBob7) = bob6.process(ChannelCommand.MessageReceived(sigAlice2))
            assertIs<LNChannel<Normal>>(bob7)
            val revBob2 = actionsBob7.findOutgoingMessage<RevokeAndAck>()
            val (alice7, _) = alice6.process(ChannelCommand.MessageReceived(revBob2))
            assertIs<LNChannel<Normal>>(alice7)
            assertEquals(835_000_000.msat, alice7.commitments.latest.localCommit.spec.toLocal)
            assertEquals(140_000_000.msat, alice7.commitments.latest.localCommit.spec.toRemote)
            assertEquals(1, alice7.commitments.localCommitIndex)
            assertEquals(2, alice7.commitments.remoteCommitIndex)
            assertEquals(2, bob7.commitments.localCommitIndex)
            assertEquals(1, bob7.commitments.remoteCommitIndex)
            Pair(Pair(alice7, bob7), Pair(preimage1, preimage2))
        }
        val (alice1, bob1) = spliceIn(nodes.first, nodes.second, listOf(500_000.sat))
        val (alice2, bob2) = fulfillHtlc(0, preimages.first, alice1, bob1)
        val (bob3, alice3) = fulfillHtlc(0, preimages.second, bob2, alice2)
        val (alice4, _) = crossSign(alice3, bob3, commitmentsCount = 2)
        assertEquals(2, alice4.commitments.localCommitIndex)
        assertEquals(4, alice4.commitments.remoteCommitIndex)
    }

    @Test
    fun `splice funds out -- would go below reserve`() {
        val (alice, bob) = reachNormalWithConfirmedFundingTx(channelType = ChannelType.SupportedChannelType.AnchorOutputs)
        val (alice1, bob1, _) = setupHtlcs(alice, bob)
        val cmd = createSpliceOutRequest(810_000.sat)
        val (alice2, actionsAlice2) = alice1.process(cmd)

        val aliceStfu = actionsAlice2.findOutgoingMessage<Stfu>()
        val (_, actionsBob2) = bob1.process(ChannelCommand.MessageReceived(aliceStfu))
        val bobStfu = actionsBob2.findOutgoingMessage<Stfu>()
        val (_, actionsAlice3) = alice2.process(ChannelCommand.MessageReceived(bobStfu))
        actionsAlice3.findOutgoingMessage<TxAbort>()
        runBlocking {
            val response = cmd.replyTo.await()
            assertIs<ChannelFundingResponse.Failure.InsufficientFunds>(response)
        }
    }

    @Test
    fun `splice cpfp`() {
        val (alice, bob) = reachNormal()
        val (nodes, preimage, _) = addHtlc(15_000_000.msat, alice, bob)
        val (alice0, bob0) = crossSign(nodes.first, nodes.second)
        val (alice1, bob1) = spliceIn(alice0, bob0, listOf(50_000.sat))
        val fee1 = spliceFee(alice1, capacity = 1_050_000.sat)
        val (alice2, bob2) = spliceOut(alice1, bob1, 50_000.sat)
        val fee2 = spliceFee(alice2, capacity = 1_000_000.sat - fee1)
        val (alice3, bob3) = spliceCpfp(alice2, bob2)
        val (alice4, bob4) = fulfillHtlc(0, preimage, alice3, bob3)
        val (_, alice5) = crossSign(bob4, alice4, commitmentsCount = 4)
        val fee3 = spliceFee(alice5, capacity = 1_000_000.sat - fee1 - fee2)
        assertEquals(alice5.state.commitments.latest.localCommit.spec.toLocal, TestConstants.aliceFundingAmount.toMilliSatoshi() - (fee1 + fee2 + fee3).toMilliSatoshi() - 15_000_000.msat)
        assertEquals(alice5.state.commitments.latest.localCommit.spec.toRemote, TestConstants.bobFundingAmount.toMilliSatoshi() + 15_000_000.msat)
    }

    @Test
    fun `splice cpfp -- not enough funds`() {
        val (alice, bob) = reachNormal(aliceFundingAmount = 75_000.sat, bobFundingAmount = 25_000.sat)
        val (alice1, bob1) = spliceOut(alice, bob, 72_000.sat)
        // After the splice-out, Alice doesn't have enough funds to pay the mining fees to CPFP.
        val spliceCpfp = ChannelCommand.Commitment.Splice.Request(
            replyTo = CompletableDeferred(),
            spliceIn = null,
            spliceOut = null,
            requestRemoteFunding = null,
            currentFeeCredit = 0.msat,
            feerate = FeeratePerKw(20_000.sat),
            origins = listOf(),
        )
        val (alice2, actionsAlice2) = alice1.process(spliceCpfp)
        val aliceStfu = actionsAlice2.findOutgoingMessage<Stfu>()
        val (_, actionsBob2) = bob1.process(ChannelCommand.MessageReceived(aliceStfu))
        val bobStfu = actionsBob2.findOutgoingMessage<Stfu>()
        val (alice3, actionsAlice3) = alice2.process(ChannelCommand.MessageReceived(bobStfu))
        actionsAlice3.hasOutgoingMessage<TxAbort>()
        assertIs<Normal>(alice3.state)
        assertEquals(SpliceStatus.Aborted, alice3.state.spliceStatus)
        runBlocking {
            val response = spliceCpfp.replyTo.await()
            assertIs<ChannelFundingResponse.Failure.InsufficientFunds>(response)
        }
    }

    @Test
    fun `splice to purchase inbound liquidity`() {
        val (alice, bob) = reachNormal()
        val fundingRates = LiquidityAds.WillFundRates(
            fundingRates = listOf(LiquidityAds.FundingRate(100_000.sat, 500_000.sat, 0, 250 /* 2.5% */, 0.sat, 1000.sat)),
            paymentTypes = setOf(LiquidityAds.PaymentType.FromChannelBalance),
        )
        val liquidityRequest = LiquidityAds.RequestFunding(200_000.sat, fundingRates.findRate(200_000.sat)!!, LiquidityAds.PaymentDetails.FromChannelBalance)
        val cmd = ChannelCommand.Commitment.Splice.Request(CompletableDeferred(), null, null, liquidityRequest, 0.msat, FeeratePerKw(1000.sat), listOf())
        val (alice1, bob1, spliceInit) = reachQuiescent(cmd, alice, bob)
        assertEquals(spliceInit.requestFunding, liquidityRequest)
        // Alice's contribution is negative: she needs to pay on-chain fees for the splice.
        assertTrue(spliceInit.fundingContribution < 0.sat)
        // We haven't implemented the seller side, so we mock it.
        val (_, actionsBob2) = bob1.process(ChannelCommand.MessageReceived(spliceInit))
        val defaultSpliceAck = actionsBob2.findOutgoingMessage<SpliceAck>()
        assertNull(defaultSpliceAck.willFund)
        val fundingScript = Transactions.makeFundingScript(spliceInit.fundingPubkey, defaultSpliceAck.fundingPubkey, Transactions.CommitmentFormat.SimpleTaprootChannels).pubkeyScript
        run {
            val willFund = fundingRates.validateRequest(bob.staticParams.nodeParams.nodePrivateKey, fundingScript, cmd.feerate, spliceInit.requestFunding!!, isChannelCreation = false, 0.msat)?.willFund
            assertNotNull(willFund)
            val spliceAck = SpliceAck(alice.channelId, liquidityRequest.requestedAmount, defaultSpliceAck.fundingPubkey, willFund, channelType = null)
            val (alice2, actionsAlice2) = alice1.process(ChannelCommand.MessageReceived(spliceAck))
            assertIs<Normal>(alice2.state)
            assertIs<SpliceStatus.InProgress>(alice2.state.spliceStatus)
            actionsAlice2.hasOutgoingMessage<TxAddInput>()
        }
        run {
            val willFund = fundingRates.validateRequest(bob.staticParams.nodeParams.nodePrivateKey, fundingScript, cmd.feerate, spliceInit.requestFunding!!, isChannelCreation = false, 5_000_000.msat)?.willFund
            assertNotNull(willFund)
            val spliceAck = SpliceAck(alice.channelId, liquidityRequest.requestedAmount, defaultSpliceAck.fundingPubkey, TlvStream(ChannelTlv.ProvideFundingTlv(willFund), ChannelTlv.FeeCreditUsedTlv(5_000_000.msat)))
            val (alice2, actionsAlice2) = alice1.process(ChannelCommand.MessageReceived(spliceAck))
            assertIs<Normal>(alice2.state)
            assertIs<SpliceStatus.InProgress>(alice2.state.spliceStatus)
            actionsAlice2.hasOutgoingMessage<TxAddInput>()
        }
        run {
            // Bob uses a different funding script than what Alice expects.
            val willFund = fundingRates.validateRequest(bob.staticParams.nodeParams.nodePrivateKey, ByteVector("deadbeef"), cmd.feerate, spliceInit.requestFunding!!, isChannelCreation = false, 0.msat)?.willFund
            assertNotNull(willFund)
            val spliceAck = SpliceAck(alice.channelId, liquidityRequest.requestedAmount, defaultSpliceAck.fundingPubkey, willFund, channelType = null)
            val (alice2, actionsAlice2) = alice1.process(ChannelCommand.MessageReceived(spliceAck))
            assertIs<Normal>(alice2.state)
            assertIs<SpliceStatus.Aborted>(alice2.state.spliceStatus)
            actionsAlice2.hasOutgoingMessage<TxAbort>()
        }
        run {
            // Bob doesn't fund the splice.
            val spliceAck = SpliceAck(alice.channelId, liquidityRequest.requestedAmount, defaultSpliceAck.fundingPubkey, willFund = null, channelType = null)
            val (alice2, actionsAlice2) = alice1.process(ChannelCommand.MessageReceived(spliceAck))
            assertIs<Normal>(alice2.state)
            assertIs<SpliceStatus.Aborted>(alice2.state.spliceStatus)
            actionsAlice2.hasOutgoingMessage<TxAbort>()
        }
    }

    @Test
    fun `splice to purchase inbound liquidity -- not enough funds`() {
        val (alice, bob) = reachNormal(aliceFundingAmount = 100_000.sat, bobFundingAmount = 10_000.sat)
        val fundingRate = LiquidityAds.FundingRate(100_000.sat, 10_000_000.sat, 0, 100 /* 1% */, 0.sat, 1000.sat)
        val fundingRates = LiquidityAds.WillFundRates(listOf(fundingRate), setOf(LiquidityAds.PaymentType.FromChannelBalance, LiquidityAds.PaymentType.FromFutureHtlc))
        run {
            val liquidityRequest = LiquidityAds.RequestFunding(1_000_000.sat, fundingRate, LiquidityAds.PaymentDetails.FromChannelBalance)
            assertEquals(10_000.sat, liquidityRequest.fees(FeeratePerKw(1000.sat), isChannelCreation = false).total)
            val cmd = ChannelCommand.Commitment.Splice.Request(CompletableDeferred(), null, null, liquidityRequest, 0.msat, FeeratePerKw(1000.sat), listOf())
            val (bob1, actionsBob1) = bob.process(cmd)
            val bobStfu = actionsBob1.findOutgoingMessage<Stfu>()
            val (_, actionsAlice1) = alice.process(ChannelCommand.MessageReceived(bobStfu))
            val aliceStfu = actionsAlice1.findOutgoingMessage<Stfu>()
            val (bob2, actionsBob2) = bob1.process(ChannelCommand.MessageReceived(aliceStfu))
            assertIs<Normal>(bob2.state)
            assertEquals(SpliceStatus.Aborted, bob2.state.spliceStatus)
            actionsBob2.hasOutgoingMessage<TxAbort>()
            runBlocking {
                val response = cmd.replyTo.await()
                assertIs<ChannelFundingResponse.Failure.InsufficientFunds>(response)
                assertEquals(10_000_000.msat, response.liquidityFees)
            }
            val (bob3, actionsBob3) = bob2.process(ChannelCommand.MessageReceived(TxAbort(bob.channelId, SpliceAborted(bob.channelId).message)))
            assertIs<Normal>(bob3.state)
            assertEquals(SpliceStatus.None, bob3.state.spliceStatus)
            assertTrue(actionsBob3.isEmpty())
        }
        run {
            val liquidityRequest = LiquidityAds.RequestFunding(900_000.sat, fundingRate, LiquidityAds.PaymentDetails.FromChannelBalance)
            assertEquals(9_000.sat, liquidityRequest.fees(FeeratePerKw(1000.sat), isChannelCreation = false).total)
            val cmd = ChannelCommand.Commitment.Splice.Request(CompletableDeferred(), null, null, liquidityRequest, 0.msat, FeeratePerKw(1000.sat), listOf())
            val (bob1, actionsBob1) = bob.process(cmd)
            val bobStfu = actionsBob1.findOutgoingMessage<Stfu>()
            val (alice1, actionsAlice1) = alice.process(ChannelCommand.MessageReceived(bobStfu))
            val aliceStfu = actionsAlice1.findOutgoingMessage<Stfu>()
            val (bob2, actionsBob2) = bob1.process(ChannelCommand.MessageReceived(aliceStfu))
            val spliceInit = actionsBob2.hasOutgoingMessage<SpliceInit>().also { assertEquals(liquidityRequest, it.requestFunding) }
            val (_, actionsAlice2) = alice1.process(ChannelCommand.MessageReceived(spliceInit))
            val spliceAck = actionsAlice2.hasOutgoingMessage<SpliceAck>()
            // We don't implement the liquidity provider side, so we must fake it.
            assertNull(spliceAck.willFund)
            val fundingScript = Transactions.makeFundingScript(spliceInit.fundingPubkey, spliceAck.fundingPubkey, Transactions.CommitmentFormat.SimpleTaprootChannels).pubkeyScript
            val willFund = fundingRates.validateRequest(alice.staticParams.nodeParams.nodePrivateKey, fundingScript, cmd.feerate, spliceInit.requestFunding!!, isChannelCreation = false, 0.msat)!!.willFund
            val (_, actionsBob3) = bob2.process(ChannelCommand.MessageReceived(spliceAck.copy(fundingContribution = liquidityRequest.requestedAmount, tlvStream = TlvStream(ChannelTlv.ProvideFundingTlv(willFund)))))
            assertEquals(1, actionsBob3.size)
            actionsBob3.hasOutgoingMessage<TxAddInput>()
        }
        run {
            // When we don't have enough funds in our channel balance, fees can be paid via future HTLCs.
            val liquidityRequest = LiquidityAds.RequestFunding(1_000_000.sat, fundingRate, LiquidityAds.PaymentDetails.FromFutureHtlc(listOf(randomBytes32())))
            assertEquals(10_000.sat, liquidityRequest.fees(FeeratePerKw(1000.sat), isChannelCreation = false).total)
            val cmd = ChannelCommand.Commitment.Splice.Request(CompletableDeferred(), null, null, liquidityRequest, 0.msat, FeeratePerKw(1000.sat), listOf())
            val (bob1, actionsBob1) = bob.process(cmd)
            val bobStfu = actionsBob1.findOutgoingMessage<Stfu>()
            val (alice1, actionsAlice1) = alice.process(ChannelCommand.MessageReceived(bobStfu))
            val aliceStfu = actionsAlice1.findOutgoingMessage<Stfu>()
            val (bob2, actionsBob2) = bob1.process(ChannelCommand.MessageReceived(aliceStfu))
            val spliceInit = actionsBob2.hasOutgoingMessage<SpliceInit>().also { assertEquals(liquidityRequest, it.requestFunding) }
            val (_, actionsAlice2) = alice1.process(ChannelCommand.MessageReceived(spliceInit))
            val spliceAck = actionsAlice2.hasOutgoingMessage<SpliceAck>()
            // We don't implement the liquidity provider side, so we must fake it.
            assertNull(spliceAck.willFund)
            val fundingScript = Transactions.makeFundingScript(spliceInit.fundingPubkey, spliceAck.fundingPubkey, Transactions.CommitmentFormat.SimpleTaprootChannels).pubkeyScript
            val willFund = fundingRates.validateRequest(alice.staticParams.nodeParams.nodePrivateKey, fundingScript, cmd.feerate, spliceInit.requestFunding!!, isChannelCreation = false, 0.msat)!!.willFund
            val (_, actionsBob3) = bob2.process(ChannelCommand.MessageReceived(spliceAck.copy(fundingContribution = liquidityRequest.requestedAmount, tlvStream = TlvStream(ChannelTlv.ProvideFundingTlv(willFund)))))
            assertEquals(1, actionsBob3.size)
            actionsBob3.hasOutgoingMessage<TxAddInput>()
        }
    }

    @Test
    fun `splice to purchase inbound liquidity -- not enough funds but on-the-fly funding`() {
        val (alice, bob) = reachNormal(bobFundingAmount = 0.sat)
        val fundingRate = LiquidityAds.FundingRate(0.sat, 500_000.sat, 0, 50, 0.sat, 1000.sat)
        val fundingRates = LiquidityAds.WillFundRates(listOf(fundingRate), setOf(LiquidityAds.PaymentType.FromChannelBalanceForFutureHtlc, LiquidityAds.PaymentType.FromFutureHtlc))
        val origin = Origin.OffChainPayment(randomBytes32(), 25_000_000.msat, ChannelManagementFees(0.sat, 500.sat))
        run {
            // We don't have enough funds nor fee credit to pay fees from our channel balance.
            val fundingRequest = LiquidityAds.RequestFunding(100_000.sat, fundingRate, LiquidityAds.PaymentDetails.FromChannelBalanceForFutureHtlc(listOf(origin.paymentHash)))
            val currentFeeCredit = 499_999.msat
            val cmd = ChannelCommand.Commitment.Splice.Request(CompletableDeferred(), null, null, fundingRequest, currentFeeCredit, FeeratePerKw(1000.sat), listOf(origin))
            val (bob1, actionsBob1) = bob.process(cmd)
            val bobStfu = actionsBob1.findOutgoingMessage<Stfu>()
            val (_, actionsAlice1) = alice.process(ChannelCommand.MessageReceived(bobStfu))
            val aliceStfu = actionsAlice1.findOutgoingMessage<Stfu>()
            val (bob2, actionsBob2) = bob1.process(ChannelCommand.MessageReceived(aliceStfu))
            assertIs<Normal>(bob2.state)
            assertEquals(SpliceStatus.Aborted, bob2.state.spliceStatus)
            actionsBob2.hasOutgoingMessage<TxAbort>()
            runBlocking {
                val response = cmd.replyTo.await()
                assertIs<ChannelFundingResponse.Failure.InsufficientFunds>(response)
                assertEquals(500_000.msat, response.liquidityFees)
                assertEquals(currentFeeCredit, response.currentFeeCredit)
            }
        }
        run {
            // We can use our fee credit to pay fees for the liquidity we're purchasing.
            val fundingRequest = LiquidityAds.RequestFunding(100_000.sat, fundingRate, LiquidityAds.PaymentDetails.FromChannelBalanceForFutureHtlc(listOf(origin.paymentHash)))
            val currentFeeCredit = 500_000.msat
            val cmd = ChannelCommand.Commitment.Splice.Request(CompletableDeferred(), null, null, fundingRequest, currentFeeCredit, FeeratePerKw(1000.sat), listOf(origin))
            val (bob1, actionsBob1) = bob.process(cmd)
            val bobStfu = actionsBob1.findOutgoingMessage<Stfu>()
            val (_, actionsAlice1) = alice.process(ChannelCommand.MessageReceived(bobStfu))
            val aliceStfu = actionsAlice1.findOutgoingMessage<Stfu>()
            val (_, actionsBob2) = bob1.process(ChannelCommand.MessageReceived(aliceStfu))
            actionsBob2.findOutgoingMessage<SpliceInit>().also {
                assertEquals(0.sat, it.fundingContribution)
                assertEquals(fundingRequest, it.requestFunding)
            }
        }
        run {
            // We can use future HTLCs to pay fees for the liquidity we're purchasing.
            val fundingRequest = LiquidityAds.RequestFunding(100_000.sat, fundingRate, LiquidityAds.PaymentDetails.FromFutureHtlc(listOf(origin.paymentHash)))
            val cmd = ChannelCommand.Commitment.Splice.Request(CompletableDeferred(), null, null, fundingRequest, 0.msat, FeeratePerKw(1000.sat), listOf(origin))
            val (bob1, actionsBob1) = bob.process(cmd)
            val bobStfu = actionsBob1.findOutgoingMessage<Stfu>()
            val (alice1, actionsAlice1) = alice.process(ChannelCommand.MessageReceived(bobStfu))
            val aliceStfu = actionsAlice1.findOutgoingMessage<Stfu>()
            val (bob2, actionsBob2) = bob1.process(ChannelCommand.MessageReceived(aliceStfu))
            val spliceInit = actionsBob2.findOutgoingMessage<SpliceInit>().also {
                assertEquals(0.sat, it.fundingContribution)
                assertEquals(fundingRequest, it.requestFunding)
            }
            val (_, actionsAlice2) = alice1.process(ChannelCommand.MessageReceived(spliceInit))
            val spliceAck = actionsAlice2.hasOutgoingMessage<SpliceAck>()
            // We don't implement the liquidity provider side, so we must fake it.
            assertNull(spliceAck.willFund)
            val fundingScript = Transactions.makeFundingScript(spliceInit.fundingPubkey, spliceAck.fundingPubkey, Transactions.CommitmentFormat.SimpleTaprootChannels).pubkeyScript
            val willFund = fundingRates.validateRequest(alice.staticParams.nodeParams.nodePrivateKey, fundingScript, cmd.feerate, spliceInit.requestFunding!!, isChannelCreation = false, 0.msat)!!.willFund
            val (_, actionsBob3) = bob2.process(ChannelCommand.MessageReceived(spliceAck.copy(fundingContribution = fundingRequest.requestedAmount, tlvStream = TlvStream(ChannelTlv.ProvideFundingTlv(willFund)))))
            actionsBob3.hasOutgoingMessage<TxAddInput>()
        }
    }

    @Test
    fun `reject splice_init`() {
        val cmd = createSpliceOutRequest(25_000.sat)
        val (alice, bob) = reachNormal()
        val (nodes, _, _) = addHtlc(15_000_000.msat, alice, bob)
        val (alice0, bob0) = crossSign(nodes.first, nodes.second)
        val (alice1, _, _) = reachQuiescent(cmd, alice0, bob0)
        val (alice2, actionsAlice2) = alice1.process(ChannelCommand.MessageReceived(TxAbort(alice.channelId, "thanks but no thanks")))
        assertIs<Normal>(alice2.state)
        assertEquals(alice2.state.spliceStatus, SpliceStatus.None)
        assertEquals(actionsAlice2.size, 1)
        actionsAlice2.hasOutgoingMessage<TxAbort>()
    }

    @Test
    fun `reject splice_init -- cancel on-the-fly funding`() {
        val cmd = createSpliceOutRequest(50_000.sat)
        val (alice, bob) = reachNormal()
        val (alice1, _, _) = reachQuiescent(cmd, alice, bob)
        val (alice2, actionsAlice2) = alice1.process(ChannelCommand.MessageReceived(CancelOnTheFlyFunding(alice.channelId, listOf(randomBytes32()), "cancelling on-the-fly funding")))
        assertIs<Normal>(alice2.state)
        assertEquals(alice2.state.spliceStatus, SpliceStatus.None)
        assertTrue(actionsAlice2.isEmpty())
    }

    @Test
    fun `reject splice_ack`() {
        val cmd = createSpliceOutRequest(25_000.sat)
        val (alice, bob) = reachNormal()
        val (nodes, _, _) = addHtlc(15_000_000.msat, alice, bob)
        val (alice0, bob0) = crossSign(nodes.first, nodes.second)
        val (_, bob1, spliceInit) = reachQuiescent(cmd, alice0, bob0)
        val (bob2, actionsBob2) = bob1.process(ChannelCommand.MessageReceived(spliceInit))
        actionsBob2.hasOutgoingMessage<SpliceAck>()
        val (bob3, actionsBob3) = bob2.process(ChannelCommand.MessageReceived(TxAbort(alice.channelId, "changed my mind")))
        assertIs<Normal>(bob3.state)
        assertEquals(bob3.state.spliceStatus, SpliceStatus.None)
        assertEquals(actionsBob3.size, 2)
        actionsBob3.hasOutgoingMessage<TxAbort>()
        actionsBob3.has<ChannelAction.ProcessIncomingHtlc>()
    }

    @Test
    fun `abort before tx_complete`() {
        val cmd = createSpliceOutRequest(20_000.sat)
        val (alice, bob) = reachNormal()
        val (nodes, _, _) = addHtlc(15_000_000.msat, alice, bob)
        val (alice0, bob0) = crossSign(nodes.first, nodes.second)
        val (alice1, bob1, spliceInit) = reachQuiescent(cmd, alice0, bob0)
        val (bob2, actionsBob2) = bob1.process(ChannelCommand.MessageReceived(spliceInit))
        val (alice2, actionsAlice2) = alice1.process(ChannelCommand.MessageReceived(actionsBob2.findOutgoingMessage<SpliceAck>()))
        val (bob3, actionsBob3) = bob2.process(ChannelCommand.MessageReceived(actionsAlice2.findOutgoingMessage<TxAddInput>()))
        val (alice3, actionsAlice3) = alice2.process(ChannelCommand.MessageReceived(actionsBob3.findOutgoingMessage<TxComplete>()))
        actionsAlice3.hasOutgoingMessage<TxAddOutput>()
        run {
            val (alice4, actionsAlice4) = alice3.process(ChannelCommand.MessageReceived(TxAbort(alice.channelId, "internal error")))
            assertIs<Normal>(alice4.state)
            assertEquals(alice4.state.spliceStatus, SpliceStatus.None)
            assertEquals(actionsAlice4.size, 1)
            actionsAlice4.hasOutgoingMessage<TxAbort>()
        }
        run {
            val (bob4, actionsBob4) = bob3.process(ChannelCommand.MessageReceived(TxAbort(alice.channelId, "internal error")))
            assertIs<Normal>(bob4.state)
            assertEquals(bob4.state.spliceStatus, SpliceStatus.None)
            assertEquals(actionsBob4.size, 2)
            actionsBob4.hasOutgoingMessage<TxAbort>()
            actionsBob4.has<ChannelAction.ProcessIncomingHtlc>()
        }
    }

    @Test
    fun `abort after tx_complete`() {
        val cmd = createSpliceOutRequest(31_000.sat)
        val (alice, bob) = reachNormal()
        val (nodes, _, _) = addHtlc(15_000_000.msat, alice, bob)
        val (alice0, bob0) = crossSign(nodes.first, nodes.second)
        val (alice1, bob1, spliceInit) = reachQuiescent(cmd, alice0, bob0)
        val (bob2, actionsBob2) = bob1.process(ChannelCommand.MessageReceived(spliceInit))
        val (alice2, actionsAlice2) = alice1.process(ChannelCommand.MessageReceived(actionsBob2.findOutgoingMessage<SpliceAck>()))
        val (bob3, actionsBob3) = bob2.process(ChannelCommand.MessageReceived(actionsAlice2.findOutgoingMessage<TxAddInput>()))
        val (alice3, actionsAlice3) = alice2.process(ChannelCommand.MessageReceived(actionsBob3.findOutgoingMessage<TxComplete>()))
        val (bob4, actionsBob4) = bob3.process(ChannelCommand.MessageReceived(actionsAlice3.findOutgoingMessage<TxAddOutput>()))
        val (alice4, actionsAlice4) = alice3.process(ChannelCommand.MessageReceived(actionsBob4.findOutgoingMessage<TxComplete>()))
        val (bob5, actionsBob5) = bob4.process(ChannelCommand.MessageReceived(actionsAlice4.findOutgoingMessage<TxAddOutput>()))
        val (alice5, actionsAlice5) = alice4.process(ChannelCommand.MessageReceived(actionsBob5.findOutgoingMessage<TxComplete>()))
        actionsAlice5.hasOutgoingMessage<CommitSig>()
        val (bob6, actionsBob6) = bob5.process(ChannelCommand.MessageReceived(actionsAlice5.findOutgoingMessage<TxComplete>()))
        actionsBob6.hasOutgoingMessage<CommitSig>()
        run {
            val (alice6, actionsAlice6) = alice5.process(ChannelCommand.MessageReceived(TxAbort(alice.channelId, "internal error")))
            assertIs<Normal>(alice6.state)
            assertEquals(alice6.state.spliceStatus, SpliceStatus.None)
            assertEquals(actionsAlice6.size, 2)
            actionsAlice6.hasOutgoingMessage<TxAbort>()
            actionsAlice6.has<ChannelAction.Storage.StoreState>()
        }
        run {
            val (bob7, actionsBob7) = bob6.process(ChannelCommand.MessageReceived(TxAbort(alice.channelId, "internal error")))
            assertIs<Normal>(bob7.state)
            assertEquals(bob7.state.spliceStatus, SpliceStatus.None)
            assertEquals(actionsBob7.size, 3)
            actionsBob7.hasOutgoingMessage<TxAbort>()
            actionsBob7.has<ChannelAction.Storage.StoreState>()
            actionsBob7.has<ChannelAction.ProcessIncomingHtlc>()
        }
    }

    @Test
    fun `abort after tx_complete then receive commit_sig`() {
        val cmd = createSpliceOutRequest(50_000.sat)
        val (alice, bob) = reachNormal()
        val (nodes, _, _) = addHtlc(15_000_000.msat, alice, bob)
        val (alice0, bob0) = crossSign(nodes.first, nodes.second)
        val (alice1, bob1, spliceInit) = reachQuiescent(cmd, alice0, bob0)
        val (bob2, actionsBob2) = bob1.process(ChannelCommand.MessageReceived(spliceInit))
        val (alice2, actionsAlice2) = alice1.process(ChannelCommand.MessageReceived(actionsBob2.findOutgoingMessage<SpliceAck>()))
        val (bob3, actionsBob3) = bob2.process(ChannelCommand.MessageReceived(actionsAlice2.findOutgoingMessage<TxAddInput>()))
        val (alice3, actionsAlice3) = alice2.process(ChannelCommand.MessageReceived(actionsBob3.findOutgoingMessage<TxComplete>()))
        val txOut1 = actionsAlice3.findOutgoingMessage<TxAddOutput>()
        val (bob4, actionsBob4) = bob3.process(ChannelCommand.MessageReceived(txOut1))
        val (alice4, actionsAlice4) = alice3.process(ChannelCommand.MessageReceived(actionsBob4.findOutgoingMessage<TxComplete>()))
        // Instead of relaying the second output, we duplicate the first one, which will make Bob abort after receiving tx_complete.
        actionsAlice4.hasOutgoingMessage<TxAddOutput>()
        val (bob5, actionsBob5) = bob4.process(ChannelCommand.MessageReceived(txOut1.copy(serialId = 100)))
        val (alice5, actionsAlice5) = alice4.process(ChannelCommand.MessageReceived(actionsBob5.findOutgoingMessage<TxComplete>()))
        val commitSigAlice = actionsAlice5.findOutgoingMessage<CommitSig>()
        val (bob6, actionsBob6) = bob5.process(ChannelCommand.MessageReceived(actionsAlice5.findOutgoingMessage<TxComplete>()))
        val txAbortBob = actionsBob6.findOutgoingMessage<TxAbort>()
        val (alice6, actionsAlice6) = alice5.process(ChannelCommand.MessageReceived(txAbortBob))
        assertIs<Normal>(alice6.state)
        assertEquals(1, alice6.commitments.active.size)
        assertEquals(SpliceStatus.None, alice6.state.spliceStatus)
        val txAbortAlice = actionsAlice6.findOutgoingMessage<TxAbort>()
        val (bob7, actionsBob7) = bob6.process(ChannelCommand.MessageReceived(commitSigAlice))
        assertTrue(actionsBob7.isEmpty())
        val (bob8, actionsBob8) = bob7.process(ChannelCommand.MessageReceived(txAbortAlice))
        assertIs<Normal>(bob8.state)
        assertEquals(1, bob8.commitments.active.size)
        assertEquals(SpliceStatus.None, bob8.state.spliceStatus)
        assertEquals(1, actionsBob8.size)
        actionsBob8.has<ChannelAction.ProcessIncomingHtlc>()
    }

    @Test
    fun `recv invalid htlc signatures during splice`() {
        val (alice, bob) = reachNormalWithConfirmedFundingTx()
        val (alice1, bob1, htlcs) = setupHtlcs(alice, bob)
        val (alice2, commitSigAlice, bob2, commitSigBob) = spliceInAndOutWithoutSigs(alice1, bob1, inAmounts = listOf(50_000.sat), outAmount = 100_000.sat)
        assertEquals(commitSigAlice.htlcSignatures.size, 4)
        assertEquals(commitSigBob.htlcSignatures.size, 4)

        val (alice3, _) = alice2.process(ChannelCommand.MessageReceived(commitSigBob))
        val (bob3, actionsBob3) = bob2.process(ChannelCommand.MessageReceived(commitSigAlice.copy(htlcSignatures = commitSigAlice.htlcSignatures.reversed())))
        val (alice4, actionsAlice4) = alice3.process(ChannelCommand.MessageReceived(actionsBob3.findOutgoingMessage<TxAbort>()))
        assertIs<LNChannel<Normal>>(alice4)
        assertEquals(SpliceStatus.None, alice4.state.spliceStatus)
        val (bob4, _) = bob3.process(ChannelCommand.MessageReceived(actionsAlice4.findOutgoingMessage<TxAbort>()))
        assertIs<LNChannel<Normal>>(bob4)
        assertEquals(SpliceStatus.None, bob4.state.spliceStatus)
        // resolve pre-splice HTLCs after aborting the splice attempt
        resolveHtlcs(alice4, bob4, htlcs, commitmentsCount = 1)
    }

    @Test
    fun `exchange splice_locked`() {
        val (alice, bob) = reachNormal()
        val (alice1, bob1) = spliceOut(alice, bob, 60_000.sat)
        val spliceTx = alice1.commitments.latest.localFundingStatus.signedTx!!

        val (alice2, actionsAlice2) = alice1.process(ChannelCommand.WatchReceived(WatchConfirmedTriggered(alice.channelId, WatchConfirmed.ChannelFundingDepthOk, 100, 0, spliceTx)))
        assertEquals(alice2.commitments.active.size, 2)
        assertIs<LocalFundingStatus.ConfirmedFundingTx>(alice2.commitments.latest.localFundingStatus)
        assertEquals(actionsAlice2.size, 3)
        actionsAlice2.hasWatchFundingSpent(spliceTx.txid)
        actionsAlice2.has<ChannelAction.Storage.StoreState>()
        val spliceLockedAlice = actionsAlice2.hasOutgoingMessage<SpliceLocked>()

        val (bob2, actionsBob2) = bob1.process(ChannelCommand.MessageReceived(spliceLockedAlice))
        assertEquals(bob2.commitments.active.size, 2)
        assertEquals(actionsBob2.size, 1)
        actionsBob2.has<ChannelAction.Storage.StoreState>()
        val (bob3, actionsBob3) = bob2.process(ChannelCommand.WatchReceived(WatchConfirmedTriggered(bob.channelId, WatchConfirmed.ChannelFundingDepthOk, 100, 0, spliceTx)))
        assertEquals(bob3.commitments.active.size, 1)
        assertIs<LocalFundingStatus.ConfirmedFundingTx>(bob3.commitments.latest.localFundingStatus)
        assertEquals(actionsBob3.size, 4)
        actionsBob3.hasWatchFundingSpent(spliceTx.txid)
        actionsBob3.has<ChannelAction.Storage.StoreState>()
        actionsBob3.has<ChannelAction.Storage.SetLocked>()
        val spliceLockedBob = actionsBob3.hasOutgoingMessage<SpliceLocked>()

        val (alice3, actionsAlice3) = alice2.process(ChannelCommand.MessageReceived(spliceLockedBob))
        assertEquals(alice3.commitments.active.size, 1)
        assertEquals(actionsAlice3.size, 2)
        actionsAlice3.has<ChannelAction.Storage.StoreState>()
        actionsAlice3.has<ChannelAction.Storage.SetLocked>()
    }

    @Test
    fun `exchange splice_locked -- zero-conf`() {
        val (alice, bob) = reachNormal(zeroConf = true)
        val (alice1, bob1) = spliceOut(alice, bob, 60_000.sat)
        val spliceTx = alice1.commitments.latest.localFundingStatus.signedTx!!

        val (alice2, actionsAlice2) = alice1.process(ChannelCommand.MessageReceived(SpliceLocked(alice.channelId, spliceTx.txid)))
        assertEquals(actionsAlice2.size, 2)
        actionsAlice2.has<ChannelAction.Storage.StoreState>()
        actionsAlice2.has<ChannelAction.Storage.SetLocked>()
        assertEquals(alice2.commitments.active.size, 1)
        assertNotEquals(alice2.commitments.latest.fundingTxId, alice.commitments.latest.fundingTxId)
        assertIs<LocalFundingStatus.UnconfirmedFundingTx>(alice2.commitments.latest.localFundingStatus)

        val (bob2, actionsBob2) = bob1.process(ChannelCommand.MessageReceived(SpliceLocked(bob.channelId, spliceTx.txid)))
        assertEquals(actionsBob2.size, 2)
        actionsBob2.has<ChannelAction.Storage.StoreState>()
        actionsBob2.has<ChannelAction.Storage.SetLocked>()
        assertEquals(bob2.commitments.active.size, 1)
        assertNotEquals(bob2.commitments.latest.fundingTxId, bob.commitments.latest.fundingTxId)
        assertIs<LocalFundingStatus.UnconfirmedFundingTx>(bob2.commitments.latest.localFundingStatus)
    }

    @Test
    fun `remote splice_locked applies to previous splices`() {
        val (alice, bob) = reachNormal(zeroConf = true)
        val (alice1, bob1) = spliceOut(alice, bob, 60_000.sat)
        val spliceTx1 = alice1.commitments.latest.localFundingStatus.signedTx!!
        val (alice2, _) = spliceOut(alice1, bob1, 60_000.sat)
        val spliceTx2 = alice2.commitments.latest.localFundingStatus.signedTx!!

        val (_, actionsAlice3) = alice2.process(ChannelCommand.MessageReceived(SpliceLocked(alice.channelId, spliceTx2.txid)))
        assertEquals(3, actionsAlice3.size)
        actionsAlice3.has<ChannelAction.Storage.StoreState>()
        assertContains(actionsAlice3, ChannelAction.Storage.SetLocked(spliceTx1.txid))
        assertContains(actionsAlice3, ChannelAction.Storage.SetLocked(spliceTx2.txid))
    }

    @Test
    fun `use channel before splice_locked -- zero-conf`() {
        val (alice, bob) = reachNormal(zeroConf = true)
        val (alice1, bob1) = spliceOut(alice, bob, 50_000.sat)
        assertEquals(alice1.commitments.active.size, 2)
        assertEquals(bob1.commitments.active.size, 2)
        val spliceTx = alice1.commitments.latest.localFundingStatus.signedTx!!

        val (nodes2, preimage, htlc) = addHtlc(15_000_000.msat, alice1, bob1)
        val (alice3, bob3) = crossSign(nodes2.first, nodes2.second, commitmentsCount = 2)

        val (alice4, actionsAlice4) = alice3.process(ChannelCommand.MessageReceived(SpliceLocked(alice.channelId, spliceTx.txid)))
        actionsAlice4.has<ChannelAction.Storage.StoreState>()
        assertEquals(alice4.commitments.active.size, 1)
        val (bob4, actionsBob4) = bob3.process(ChannelCommand.MessageReceived(SpliceLocked(bob.channelId, spliceTx.txid)))
        actionsBob4.has<ChannelAction.Storage.StoreState>()
        assertEquals(bob4.commitments.active.size, 1)

        val (alice5, bob5) = fulfillHtlc(htlc.id, preimage, alice4, bob4)
        assertIs<LNChannel<Normal>>(alice5)
        assertIs<LNChannel<Normal>>(bob5)
        val (bob6, alice6) = crossSign(bob5, alice5, commitmentsCount = 1)
        listOf(bob6, alice6).forEach { node ->
            assertEquals(node.commitments.active.size, 1)
            assertEquals(node.commitments.inactive.size, 1)
            assertEquals(node.commitments.active.first().localCommit.index, node.commitments.inactive.first().localCommit.index + 1)
            assertEquals(node.commitments.active.first().remoteCommit.index, node.commitments.inactive.first().remoteCommit.index + 1)
            assertTrue(node.commitments.active.first().localCommit.spec.htlcs.isEmpty())
            assertTrue(node.commitments.inactive.first().localCommit.spec.htlcs.isNotEmpty())
        }
    }

    @Test
    fun `use channel during splice_locked -- zero-conf`() {
        val (alice, bob) = reachNormal(zeroConf = true)
        val (alice1, bob1) = spliceOut(alice, bob, 30_000.sat)
        val (alice2, bob2) = spliceOut(alice1, bob1, 20_000.sat)
        assertEquals(alice2.commitments.active.size, 3)
        assertEquals(bob2.commitments.active.size, 3)
        val spliceTx = alice2.commitments.latest.localFundingStatus.signedTx!!
        val spliceLocked = SpliceLocked(alice.channelId, spliceTx.txid)

        // Alice adds a new HTLC, and sends commit_sigs before receiving Bob's splice_locked.
        //
        //   Alice                           Bob
        //     |        splice_locked         |
        //     |----------------------------->|
        //     |       update_add_htlc        |
        //     |----------------------------->|
        //     |         commit_sig           | batch_size = 3
        //     |----------------------------->|
        //     |        splice_locked         |
        //     |<-----------------------------|
        //     |         commit_sig           | batch_size = 3
        //     |----------------------------->|
        //     |         commit_sig           | batch_size = 3
        //     |----------------------------->|
        //     |       revoke_and_ack         |
        //     |<-----------------------------|
        //     |         commit_sig           | batch_size = 1
        //     |<-----------------------------|
        //     |       revoke_and_ack         |
        //     |----------------------------->|

        val (bob3, _) = bob2.process(ChannelCommand.MessageReceived(spliceLocked))
        assertEquals(bob3.commitments.active.size, 1)
        assertEquals(bob3.commitments.inactive.size, 2)
        val (nodes, preimage, htlc) = addHtlc(20_000_000.msat, alice2, bob3)
        val (alice3, bob4) = nodes
        val (alice4, actionsAlice4) = alice3.process(ChannelCommand.Commitment.Sign)
        val commitSigsAlice = actionsAlice4.findOutgoingMessage<CommitSigBatch>()
        assertEquals(commitSigsAlice.batchSize, 3)
        val (alice5, _) = alice4.process(ChannelCommand.MessageReceived(spliceLocked))
        assertEquals(alice5.commitments.active.size, 1)
        assertEquals(alice5.commitments.inactive.size, 2)
        val (bob5, actionsBob5) = bob4.process(ChannelCommand.MessageReceived(commitSigsAlice))
        assertEquals(actionsBob5.size, 3)
        val revokeAndAckBob = actionsBob5.findOutgoingMessage<RevokeAndAck>()
        actionsBob5.contains(ChannelAction.Message.SendToSelf(ChannelCommand.Commitment.Sign))
        actionsBob5.has<ChannelAction.Storage.StoreState>()
        val (bob6, actionsBob6) = bob5.process(ChannelCommand.Commitment.Sign)
        assertEquals(actionsBob6.size, 3)
        val commitSigBob = actionsBob6.findOutgoingMessage<CommitSig>()
        assertEquals(commitSigBob.batchSize, 1)
        actionsBob6.has<ChannelAction.Storage.StoreHtlcInfos>()
        actionsBob6.has<ChannelAction.Storage.StoreState>()
        val (alice6, actionsAlice6) = alice5.process(ChannelCommand.MessageReceived(revokeAndAckBob))
        assertEquals(actionsAlice6.size, 1)
        actionsAlice6.has<ChannelAction.Storage.StoreState>()
        val (alice7, actionsAlice7) = alice6.process(ChannelCommand.MessageReceived(commitSigBob))
        assertIs<LNChannel<Normal>>(alice7)
        assertEquals(actionsAlice7.size, 2)
        val revokeAndAckAlice = actionsAlice7.findOutgoingMessage<RevokeAndAck>()
        actionsAlice7.has<ChannelAction.Storage.StoreState>()
        val (bob7, actionsBob7) = bob6.process(ChannelCommand.MessageReceived(revokeAndAckAlice))
        assertIs<LNChannel<Normal>>(bob7)
        assertEquals(actionsBob7.size, 2)
        actionsBob7.has<ChannelAction.ProcessIncomingHtlc>()
        actionsBob7.has<ChannelAction.Storage.StoreState>()

        // Bob fulfills the HTLC.
        val (alice8, bob8) = fulfillHtlc(htlc.id, preimage, alice7, bob7)
        val (bob9, alice9) = crossSign(bob8, alice8, commitmentsCount = 1)
        assertEquals(alice9.commitments.active.size, 1)
        alice9.commitments.inactive.forEach { assertTrue(it.localCommit.index < alice9.commitments.localCommitIndex) }
        assertEquals(bob9.commitments.active.size, 1)
        bob9.commitments.inactive.forEach { assertTrue(it.localCommit.index < bob9.commitments.localCommitIndex) }
    }

    @Test
    fun `disconnect -- commit_sig not received`() {
        val (alice, bob) = reachNormalWithConfirmedFundingTx()
        val (alice0, bob0, htlcs) = setupHtlcs(alice, bob)
        val aliceCommitIndex = alice0.commitments.localCommitIndex
        val bobCommitIndex = bob0.commitments.localCommitIndex
        val (alice1, _, bob1, _) = spliceInAndOutWithoutSigs(alice0, bob0, inAmounts = listOf(50_000.sat), outAmount = 100_000.sat)
        val spliceStatus = alice1.state.spliceStatus
        assertIs<SpliceStatus.WaitingForSigs>(spliceStatus)

        val (alice2, bob2, channelReestablishAlice) = disconnect(alice1, bob1)
        assertEquals(channelReestablishAlice.nextFundingTxId, spliceStatus.session.fundingTx.txId)
        assertNotNull(channelReestablishAlice.currentCommitNonce)
        assertContains(channelReestablishAlice.nextCommitNonces, alice.commitments.latest.fundingTxId)
        assertContains(channelReestablishAlice.nextCommitNonces, spliceStatus.session.fundingTx.txId)
        assertEquals(channelReestablishAlice.nextLocalCommitmentNumber, aliceCommitIndex)
        val (bob3, actionsBob3) = bob2.process(ChannelCommand.MessageReceived(channelReestablishAlice))
        assertIs<LNChannel<Normal>>(bob3)
        assertEquals(actionsBob3.size, 4)
        val channelReestablishBob = actionsBob3.findOutgoingMessage<ChannelReestablish>()
        assertNotNull(channelReestablishBob.currentCommitNonce)
        assertContains(channelReestablishBob.nextCommitNonces, bob.commitments.latest.fundingTxId)
        assertContains(channelReestablishBob.nextCommitNonces, spliceStatus.session.fundingTx.txId)
        val commitSigBob = actionsBob3.findOutgoingMessage<CommitSig>()
        assertEquals(htlcs.aliceToBob.map { it.second }.toSet(), actionsBob3.filterIsInstance<ChannelAction.ProcessIncomingHtlc>().map { it.add }.toSet())
        assertEquals(channelReestablishBob.nextFundingTxId, spliceStatus.session.fundingTx.txId)
        assertEquals(channelReestablishBob.nextLocalCommitmentNumber, bobCommitIndex)
        val (alice3, actionsAlice3) = alice2.process(ChannelCommand.MessageReceived(channelReestablishBob))
        assertIs<LNChannel<Normal>>(alice3)
        assertEquals(actionsAlice3.size, 3)
        val commitSigAlice = actionsAlice3.findOutgoingMessage<CommitSig>()
        val (alice4, bob4) = exchangeSpliceSigs(alice3, commitSigAlice, bob3, commitSigBob)
        assertEquals(htlcs.bobToAlice.map { it.second }.toSet(), actionsAlice3.filterIsInstance<ChannelAction.ProcessIncomingHtlc>().map { it.add }.toSet())
        resolveHtlcs(alice4, bob4, htlcs, commitmentsCount = 2)
    }

    @Test
    fun `disconnect -- commit_sig received by alice`() {
        val (alice, bob) = reachNormalWithConfirmedFundingTx()
        val (alice1, bob1, htlcs) = setupHtlcs(alice, bob)
        val aliceCommitIndex = alice1.commitments.localCommitIndex
        val bobCommitIndex = bob1.commitments.localCommitIndex
        val (alice2, _, bob2, commitSigBob) = spliceInAndOutWithoutSigs(alice1, bob1, inAmounts = listOf(50_000.sat), outAmount = 100_000.sat)
        val (alice3, actionsAlice3) = alice2.process(ChannelCommand.MessageReceived(commitSigBob))
        assertIs<LNChannel<Normal>>(alice3)
        assertTrue(actionsAlice3.isEmpty())
        val spliceStatus = alice3.state.spliceStatus
        assertIs<SpliceStatus.WaitingForSigs>(spliceStatus)
        val spliceTxId = spliceStatus.session.fundingTx.txId

        val (alice4, bob3, channelReestablishAlice) = disconnect(alice3, bob2)
        assertEquals(channelReestablishAlice.nextFundingTxId, spliceTxId)
        assertNull(channelReestablishAlice.currentCommitNonce)
        assertContains(channelReestablishAlice.nextCommitNonces, alice.commitments.latest.fundingTxId)
        assertContains(channelReestablishAlice.nextCommitNonces, spliceTxId)
        assertEquals(channelReestablishAlice.nextLocalCommitmentNumber, aliceCommitIndex + 1)
        val (bob4, actionsBob4) = bob3.process(ChannelCommand.MessageReceived(channelReestablishAlice))
        assertEquals(actionsBob4.size, 3)
        val channelReestablishBob = actionsBob4.findOutgoingMessage<ChannelReestablish>()
        assertNotNull(channelReestablishBob.currentCommitNonce)
        assertContains(channelReestablishBob.nextCommitNonces, bob.commitments.latest.fundingTxId)
        assertContains(channelReestablishBob.nextCommitNonces, spliceTxId)
        assertNull(actionsBob4.findOutgoingMessageOpt<CommitSig>())
        assertEquals(htlcs.aliceToBob.map { it.second }.toSet(), actionsBob4.filterIsInstance<ChannelAction.ProcessIncomingHtlc>().map { it.add }.toSet())
        assertEquals(channelReestablishBob.nextFundingTxId, spliceTxId)
        assertEquals(channelReestablishBob.nextLocalCommitmentNumber, bobCommitIndex)
        val (alice5, actionsAlice5) = alice4.process(ChannelCommand.MessageReceived(channelReestablishBob))
        assertEquals(actionsAlice5.size, 3)
        val commitSigAlice = actionsAlice5.findOutgoingMessage<CommitSig>()
        assertEquals(htlcs.bobToAlice.map { it.second }.toSet(), actionsAlice5.filterIsInstance<ChannelAction.ProcessIncomingHtlc>().map { it.add }.toSet())

        val (bob5, actionsBob5) = bob4.process(ChannelCommand.MessageReceived(commitSigAlice))
        assertEquals(actionsBob5.size, 5)
        val txSigsBob = actionsBob5.findOutgoingMessage<TxSignatures>()
        actionsBob5.hasWatchConfirmed(txSigsBob.txId)
        assertEquals(htlcs.aliceToBob.map { it.second }.toSet(), actionsBob5.filterIsInstance<ChannelAction.ProcessIncomingHtlc>().map { it.add }.toSet())
        actionsBob5.has<ChannelAction.Storage.StoreState>()
        val (alice6, actionsAlice6) = alice5.process(ChannelCommand.MessageReceived(txSigsBob))
        assertIs<LNChannel<Normal>>(alice6)
        assertEquals(actionsAlice6.size, 8)
        assertEquals(actionsAlice6.hasPublishTx(ChannelAction.Blockchain.PublishTx.Type.FundingTx).txid, spliceTxId)
        actionsAlice6.hasWatchConfirmed(spliceTxId)
        assertEquals(htlcs.bobToAlice.map { it.second }.toSet(), actionsAlice6.filterIsInstance<ChannelAction.ProcessIncomingHtlc>().map { it.add }.toSet())
        actionsAlice6.has<ChannelAction.Storage.StoreState>()
        actionsAlice6.has<ChannelAction.Storage.StoreOutgoingPayment.ViaSpliceOut>()
        actionsAlice6.has<ChannelAction.Storage.StoreIncomingPayment.ViaSpliceIn>()
        val txSigsAlice = actionsAlice6.findOutgoingMessage<TxSignatures>()

        val (bob6, actionsBob6) = bob5.process(ChannelCommand.MessageReceived(txSigsAlice))
        assertIs<LNChannel<Normal>>(bob6)
        assertEquals(actionsBob6.size, 2)
        assertEquals(actionsBob6.hasPublishTx(ChannelAction.Blockchain.PublishTx.Type.FundingTx).txid, txSigsBob.txId)
        actionsBob6.has<ChannelAction.Storage.StoreState>()

        resolveHtlcs(alice6, bob6, htlcs, commitmentsCount = 2)
    }

    @Test
    fun `disconnect -- commit_sig received by bob`() {
        val (alice, bob) = reachNormalWithConfirmedFundingTx()
        val (alice0, bob0, htlcs) = setupHtlcs(alice, bob)
        val aliceCommitIndex = alice0.commitments.localCommitIndex
        val bobCommitIndex = bob0.commitments.localCommitIndex
        val (alice1, commitSigAlice, bob1, _) = spliceInAndOutWithoutSigs(alice0, bob0, inAmounts = listOf(80_000.sat), outAmount = 50_000.sat)
        val (bob2, actionsBob2) = bob1.process(ChannelCommand.MessageReceived(commitSigAlice))
        assertIs<LNChannel<Normal>>(bob2)
        val spliceTxId = actionsBob2.hasOutgoingMessage<TxSignatures>().txId
        assertEquals(bob2.state.spliceStatus, SpliceStatus.None)

        val (alice2, bob3, channelReestablishAlice) = disconnect(alice1, bob2)
        assertEquals(channelReestablishAlice.nextFundingTxId, spliceTxId)
        assertNotNull(channelReestablishAlice.currentCommitNonce)
        assertContains(channelReestablishAlice.nextCommitNonces, alice.commitments.latest.fundingTxId)
        assertContains(channelReestablishAlice.nextCommitNonces, spliceTxId)
        assertEquals(channelReestablishAlice.nextLocalCommitmentNumber, aliceCommitIndex)
        val (bob4, actionsBob4) = bob3.process(ChannelCommand.MessageReceived(channelReestablishAlice))
        assertEquals(actionsBob4.size, 5)
        val channelReestablishBob = actionsBob4.findOutgoingMessage<ChannelReestablish>()
        assertNull(channelReestablishBob.currentCommitNonce)
        assertContains(channelReestablishBob.nextCommitNonces, bob.commitments.latest.fundingTxId)
        assertContains(channelReestablishBob.nextCommitNonces, spliceTxId)
        val commitSigBob = actionsBob4.findOutgoingMessage<CommitSig>()
        assertEquals(htlcs.aliceToBob.map { it.second }.toSet(), actionsBob4.filterIsInstance<ChannelAction.ProcessIncomingHtlc>().map { it.add }.toSet())
        val txSigsBob = actionsBob4.findOutgoingMessage<TxSignatures>()
        assertEquals(channelReestablishBob.nextFundingTxId, spliceTxId)
        assertEquals(channelReestablishBob.nextLocalCommitmentNumber, bobCommitIndex + 1)
        val (alice3, actionsAlice3) = alice2.process(ChannelCommand.MessageReceived(channelReestablishBob))
        assertEquals(actionsAlice3.size, 2)
        assertEquals(htlcs.bobToAlice.map { it.second }.toSet(), actionsAlice3.filterIsInstance<ChannelAction.ProcessIncomingHtlc>().map { it.add }.toSet())
        assertNull(actionsAlice3.findOutgoingMessageOpt<CommitSig>())

        val (alice4, actionsAlice4) = alice3.process(ChannelCommand.MessageReceived(commitSigBob))
        assertTrue(actionsAlice4.isEmpty())
        val (alice5, actionsAlice5) = alice4.process(ChannelCommand.MessageReceived(txSigsBob))
        assertIs<LNChannel<Normal>>(alice5)
        assertEquals(alice5.state.commitments.active.size, 2)
        assertEquals(actionsAlice5.size, 8)
        assertEquals(actionsAlice5.hasPublishTx(ChannelAction.Blockchain.PublishTx.Type.FundingTx).txid, spliceTxId)
        actionsAlice5.hasWatchConfirmed(spliceTxId)
        assertEquals(htlcs.bobToAlice.map { it.second }.toSet(), actionsAlice5.filterIsInstance<ChannelAction.ProcessIncomingHtlc>().map { it.add }.toSet())
        actionsAlice5.has<ChannelAction.Storage.StoreState>()
        actionsAlice5.has<ChannelAction.Storage.StoreOutgoingPayment.ViaSpliceOut>()
        actionsAlice5.has<ChannelAction.Storage.StoreIncomingPayment.ViaSpliceIn>()
        val txSigsAlice = actionsAlice5.findOutgoingMessage<TxSignatures>()

        val (bob5, actionsBob5) = bob4.process(ChannelCommand.MessageReceived(txSigsAlice))
        assertIs<LNChannel<Normal>>(bob5)
        assertEquals(bob5.state.commitments.active.size, 2)
        assertEquals(actionsBob5.size, 2)
        assertEquals(actionsBob5.hasPublishTx(ChannelAction.Blockchain.PublishTx.Type.FundingTx).txid, spliceTxId)
        actionsBob5.has<ChannelAction.Storage.StoreState>()

        resolveHtlcs(alice5, bob5, htlcs, commitmentsCount = 2)
    }

    @Test
    fun `disconnect -- commit_sig received by bob -- zero-conf`() {
        val (alice, bob) = reachNormalWithConfirmedFundingTx(zeroConf = true)
        val (alice0, bob0, htlcs) = setupHtlcs(alice, bob)
        val (alice1, commitSigAlice, bob1, _) = spliceInAndOutWithoutSigs(alice0, bob0, inAmounts = listOf(75_000.sat), outAmount = 120_000.sat)
        val (bob2, actionsBob2) = bob1.process(ChannelCommand.MessageReceived(commitSigAlice))
        assertIs<LNChannel<Normal>>(bob2)
        val spliceTxId = actionsBob2.hasOutgoingMessage<TxSignatures>().txId
        actionsBob2.hasOutgoingMessage<SpliceLocked>()
        assertEquals(bob2.state.spliceStatus, SpliceStatus.None)

        val (alice2, bob3, channelReestablishAlice) = disconnect(alice1, bob2)
        assertEquals(channelReestablishAlice.nextFundingTxId, spliceTxId)
        assertNotNull(channelReestablishAlice.currentCommitNonce)
        assertContains(channelReestablishAlice.nextCommitNonces, alice.commitments.latest.fundingTxId)
        assertContains(channelReestablishAlice.nextCommitNonces, spliceTxId)
        val (bob4, actionsBob4) = bob3.process(ChannelCommand.MessageReceived(channelReestablishAlice))
        assertEquals(actionsBob4.size, 6)
        val channelReestablishBob = actionsBob4.findOutgoingMessage<ChannelReestablish>()
        assertNull(channelReestablishBob.currentCommitNonce)
        assertContains(channelReestablishBob.nextCommitNonces, bob.commitments.latest.fundingTxId)
        assertContains(channelReestablishBob.nextCommitNonces, spliceTxId)
        val commitSigBob = actionsBob4.findOutgoingMessage<CommitSig>()
        val txSigsBob = actionsBob4.findOutgoingMessage<TxSignatures>()
        // splice_locked must always be sent *after* tx_signatures
        assertIs<SpliceLocked>(actionsBob4.filterIsInstance<ChannelAction.Message.Send>().last().message)
        val spliceLockedBob = actionsBob4.findOutgoingMessage<SpliceLocked>()
        assertEquals(htlcs.aliceToBob.map { it.second }.toSet(), actionsBob4.filterIsInstance<ChannelAction.ProcessIncomingHtlc>().map { it.add }.toSet())
        assertEquals(channelReestablishBob.nextFundingTxId, spliceTxId)
        val (alice3, actionsAlice3) = alice2.process(ChannelCommand.MessageReceived(channelReestablishBob))
        assertEquals(actionsAlice3.size, 2)
        assertEquals(htlcs.bobToAlice.map { it.second }.toSet(), actionsAlice3.filterIsInstance<ChannelAction.ProcessIncomingHtlc>().map { it.add }.toSet())
        assertNull(actionsAlice3.findOutgoingMessageOpt<CommitSig>())

        val (alice4, actionsAlice4) = alice3.process(ChannelCommand.MessageReceived(commitSigBob))
        assertTrue(actionsAlice4.isEmpty())
        val (alice5, actionsAlice5) = alice4.process(ChannelCommand.MessageReceived(txSigsBob))
        assertIs<LNChannel<Normal>>(alice5)
        assertEquals(alice5.state.commitments.active.size, 2)
        assertEquals(actionsAlice5.size, 9)
        assertEquals(actionsAlice5.hasPublishTx(ChannelAction.Blockchain.PublishTx.Type.FundingTx).txid, spliceTxId)
        actionsAlice5.hasWatchConfirmed(spliceTxId)
        actionsAlice5.has<ChannelAction.Storage.StoreState>()
        actionsAlice5.has<ChannelAction.Storage.StoreOutgoingPayment.ViaSpliceOut>()
        val txSigsAlice = actionsAlice5.findOutgoingMessage<TxSignatures>()
        assertIs<SpliceLocked>(actionsAlice5.filterIsInstance<ChannelAction.Message.Send>().last().message)
        val spliceLockedAlice = actionsAlice5.findOutgoingMessage<SpliceLocked>()
        assertEquals(htlcs.bobToAlice.map { it.second }.toSet(), actionsAlice5.filterIsInstance<ChannelAction.ProcessIncomingHtlc>().map { it.add }.toSet())
        val (alice6, actionsAlice6) = alice5.process(ChannelCommand.MessageReceived(spliceLockedBob))
        assertIs<LNChannel<Normal>>(alice6)
        assertEquals(alice6.state.commitments.active.size, 1)
        assertEquals(actionsAlice6.size, 2)
        actionsAlice6.find<ChannelAction.Storage.SetLocked>().also { assertEquals(it.txId, spliceTxId) }
        actionsAlice6.has<ChannelAction.Storage.StoreState>()

        val (bob5, actionsBob5) = bob4.process(ChannelCommand.MessageReceived(txSigsAlice))
        assertIs<LNChannel<Normal>>(bob5)
        assertEquals(bob5.state.commitments.active.size, 2)
        assertEquals(actionsBob5.size, 2)
        assertEquals(actionsBob5.hasPublishTx(ChannelAction.Blockchain.PublishTx.Type.FundingTx).txid, spliceTxId)
        actionsBob5.has<ChannelAction.Storage.StoreState>()
        val (bob6, actionsBob6) = bob5.process(ChannelCommand.MessageReceived(spliceLockedAlice))
        assertIs<LNChannel<Normal>>(bob6)
        assertEquals(bob6.state.commitments.active.size, 1)
        assertEquals(actionsBob6.size, 2)
        actionsBob6.find<ChannelAction.Storage.SetLocked>().also { assertEquals(it.txId, spliceTxId) }
        actionsBob6.has<ChannelAction.Storage.StoreState>()
        resolveHtlcs(alice6, bob6, htlcs, commitmentsCount = 1)
    }

    @Test
    fun `disconnect -- tx_signatures received by alice -- confirms while bob is offline`() {
        val (alice, bob) = reachNormalWithConfirmedFundingTx()
        val (alice0, bob0, htlcs) = setupHtlcs(alice, bob)
        val (alice1, commitSigAlice, bob1, commitSigBob) = spliceInAndOutWithoutSigs(alice0, bob0, inAmounts = listOf(70_000.sat, 60_000.sat), outAmount = 150_000.sat)

        // Bob completes the splice, but is missing Alice's tx_signatures.
        val (bob2, actionsBob2) = bob1.process(ChannelCommand.MessageReceived(commitSigAlice))
        assertIs<LNChannel<Normal>>(bob2)
        val txSigsBob = actionsBob2.hasOutgoingMessage<TxSignatures>()
        assertEquals(bob2.state.spliceStatus, SpliceStatus.None)

        // Alice completes the splice, but Bob doesn't receive her tx_signatures.
        val (alice2, actionsAlice2) = alice1.process(ChannelCommand.MessageReceived(commitSigBob))
        assertTrue(actionsAlice2.isEmpty())
        val (alice3, actionsAlice3) = alice2.process(ChannelCommand.MessageReceived(txSigsBob))
        assertIs<LNChannel<Normal>>(alice3)
        actionsAlice3.hasOutgoingMessage<TxSignatures>()
        assertEquals(alice3.state.spliceStatus, SpliceStatus.None)
        val spliceTx = alice3.commitments.latest.localFundingStatus.signedTx!!

        // The transaction confirms.
        val (alice4, actionsAlice4) = alice3.process(ChannelCommand.WatchReceived(WatchConfirmedTriggered(alice.channelId, WatchConfirmed.ChannelFundingDepthOk, 100, 0, spliceTx)))
        assertIs<LNChannel<Normal>>(alice4)
        assertEquals(3, actionsAlice4.size)
        actionsAlice4.hasWatchFundingSpent(spliceTx.txid)
        actionsAlice4.hasOutgoingMessage<SpliceLocked>()
        actionsAlice4.has<ChannelAction.Storage.StoreState>()

        val (alice5, bob3, channelReestablishAlice) = disconnect(alice4, bob2)
        assertNull(channelReestablishAlice.nextFundingTxId)
        assertNull(channelReestablishAlice.currentCommitNonce)
        assertContains(channelReestablishAlice.nextCommitNonces, alice.commitments.latest.fundingTxId)
        assertContains(channelReestablishAlice.nextCommitNonces, spliceTx.txid)
        val (bob4, actionsBob4) = bob3.process(ChannelCommand.MessageReceived(channelReestablishAlice))
        assertEquals(3, actionsBob4.size)
        val channelReestablishBob = actionsBob4.findOutgoingMessage<ChannelReestablish>()
        assertNull(channelReestablishBob.currentCommitNonce)
        assertContains(channelReestablishBob.nextCommitNonces, bob.commitments.latest.fundingTxId)
        assertContains(channelReestablishBob.nextCommitNonces, spliceTx.txid)
        assertEquals(htlcs.aliceToBob.map { it.second }.toSet(), actionsBob4.filterIsInstance<ChannelAction.ProcessIncomingHtlc>().map { it.add }.toSet())
        assertEquals(channelReestablishBob.nextFundingTxId, spliceTx.txid)
        val (alice6, actionsAlice6) = alice5.process(ChannelCommand.MessageReceived(channelReestablishBob))
        assertIs<LNChannel<Normal>>(alice6)
        assertEquals(alice6.state.spliceStatus, SpliceStatus.None)
        assertEquals(4, actionsAlice6.size)
        val txSigsAlice = actionsAlice6.hasOutgoingMessage<TxSignatures>()
        actionsAlice6.hasOutgoingMessage<SpliceLocked>()
        assertEquals(htlcs.bobToAlice.map { it.second }.toSet(), actionsAlice6.filterIsInstance<ChannelAction.ProcessIncomingHtlc>().map { it.add }.toSet())

        // Bob receives tx_signatures, which completes the splice.
        val (bob5, actionsBob5) = bob4.process(ChannelCommand.MessageReceived(txSigsAlice))
        assertIs<LNChannel<Normal>>(bob5)
        assertEquals(bob5.state.spliceStatus, SpliceStatus.None)
        assertEquals(2, actionsBob5.size)
        actionsBob5.hasPublishTx(spliceTx)
        actionsBob5.has<ChannelAction.Storage.StoreState>()
        resolveHtlcs(alice6, bob5, htlcs, commitmentsCount = 2)
    }

    @Test
    fun `disconnect -- tx_signatures received by alice`() {
        val (alice, bob) = reachNormalWithConfirmedFundingTx()
        val (alice0, bob0, htlcs) = setupHtlcs(alice, bob)
        val (alice1, commitSigAlice, bob1, commitSigBob) = spliceInAndOutWithoutSigs(alice0, bob0, inAmounts = listOf(315_000.sat), outAmount = 25_000.sat)
        val (alice2, actionsAlice2) = alice1.process(ChannelCommand.MessageReceived(commitSigBob))
        assertTrue(actionsAlice2.isEmpty())
        val (bob2, actionsBob2) = bob1.process(ChannelCommand.MessageReceived(commitSigAlice))
        assertIs<LNChannel<Normal>>(bob2)
        val txSigsBob = actionsBob2.findOutgoingMessage<TxSignatures>()
        val (alice3, actionsAlice3) = alice2.process(ChannelCommand.MessageReceived(txSigsBob))
        assertIs<LNChannel<Normal>>(alice3)
        assertEquals(alice3.state.spliceStatus, SpliceStatus.None)
        actionsAlice3.hasOutgoingMessage<TxSignatures>()
        val spliceTx = actionsAlice3.hasPublishTx(ChannelAction.Blockchain.PublishTx.Type.FundingTx)

        val (alice4, bob3, channelReestablishAlice) = disconnect(alice3, bob2)
        assertNull(channelReestablishAlice.nextFundingTxId)
        assertNull(channelReestablishAlice.currentCommitNonce)
        assertContains(channelReestablishAlice.nextCommitNonces, alice.commitments.latest.fundingTxId)
        assertContains(channelReestablishAlice.nextCommitNonces, spliceTx.txid)
        val (bob4, actionsBob4) = bob3.process(ChannelCommand.MessageReceived(channelReestablishAlice))
        assertEquals(actionsBob4.size, 3)
        val channelReestablishBob = actionsBob4.findOutgoingMessage<ChannelReestablish>()
        assertNull(channelReestablishBob.currentCommitNonce)
        assertContains(channelReestablishBob.nextCommitNonces, bob.commitments.latest.fundingTxId)
        assertContains(channelReestablishBob.nextCommitNonces, spliceTx.txid)
        assertEquals(htlcs.aliceToBob.map { it.second }.toSet(), actionsBob4.filterIsInstance<ChannelAction.ProcessIncomingHtlc>().map { it.add }.toSet())
        assertEquals(channelReestablishBob.nextFundingTxId, spliceTx.txid)
        val (alice5, actionsAlice5) = alice4.process(ChannelCommand.MessageReceived(channelReestablishBob))
        assertIs<LNChannel<Normal>>(alice5)
        assertEquals(alice5.state.commitments.active.size, 2)
        assertEquals(actionsAlice5.size, 3)
        val txSigsAlice = actionsAlice5.findOutgoingMessage<TxSignatures>()
        assertEquals(htlcs.bobToAlice.map { it.second }.toSet(), actionsAlice5.filterIsInstance<ChannelAction.ProcessIncomingHtlc>().map { it.add }.toSet())

        val (bob5, actionsBob5) = bob4.process(ChannelCommand.MessageReceived(txSigsAlice))
        assertIs<LNChannel<Normal>>(bob5)
        assertEquals(bob5.state.commitments.active.size, 2)
        assertEquals(actionsBob5.size, 2)
        assertEquals(actionsBob5.hasPublishTx(ChannelAction.Blockchain.PublishTx.Type.FundingTx).txid, spliceTx.txid)
        actionsBob5.has<ChannelAction.Storage.StoreState>()
        resolveHtlcs(alice5, bob5, htlcs, commitmentsCount = 2)
    }

    @Test
    fun `disconnect -- new changes before splice_locked`() {
        val (alice, bob) = reachNormalWithConfirmedFundingTx()
        val (alice1, bob1) = spliceOut(alice, bob, 70_000.sat)
        val (nodes2, _, htlc) = addHtlc(50_000_000.msat, alice1, bob1)
        val (alice3, actionsAlice3) = nodes2.first.process(ChannelCommand.Commitment.Sign)
        assertIs<LNChannel<Normal>>(alice3)
        val commitSigsAlice1 = actionsAlice3.findOutgoingMessage<CommitSigBatch>()
        assertEquals(2, commitSigsAlice1.batchSize)
        // Bob disconnects before receiving Alice's commit_sig.
        val (alice4, bob3, channelReestablishAlice) = disconnect(alice3, nodes2.second)
        val (bob4, actionsBob4) = bob3.process(ChannelCommand.MessageReceived(channelReestablishAlice))
        val channelReestablishBob = actionsBob4.findOutgoingMessage<ChannelReestablish>()
        val (_, actionsAlice5) = alice4.process(ChannelCommand.MessageReceived(channelReestablishBob))
        actionsAlice5.hasOutgoingMessage<UpdateAddHtlc>().also { assertEquals(htlc, it) }
        val commitSigsAlice2 = actionsAlice5.findOutgoingMessage<CommitSigBatch>()
        assertEquals(2, commitSigsAlice2.batchSize)
        val (bob5, _) = bob4.process(ChannelCommand.MessageReceived(htlc))
        val (_, actionsBob6) = bob5.process(ChannelCommand.MessageReceived(commitSigsAlice2))
        actionsBob6.findOutgoingMessage<RevokeAndAck>()
    }

    @Test
    fun `disconnect -- new changes before splice_locked -- partially locked`() {
        val (alice, bob) = reachNormalWithConfirmedFundingTx()
        val (alice1, bob1) = spliceOut(alice, bob, 70_000.sat)
        val spliceTx = alice1.commitments.latest.localFundingStatus.signedTx!!

        // The splice confirms on Alice's side.
        val (alice2, actionsAlice2) = alice1.process(ChannelCommand.WatchReceived(WatchConfirmedTriggered(alice.channelId, WatchConfirmed.ChannelFundingDepthOk, 100, 0, spliceTx)))
        val spliceLockedAlice = actionsAlice2.hasOutgoingMessage<SpliceLocked>()
        assertEquals(spliceTx.txid, spliceLockedAlice.fundingTxId)
        val (bob2, actionsBob2) = bob1.process(ChannelCommand.MessageReceived(spliceLockedAlice))
        assertNull(actionsBob2.findOutgoingMessageOpt<SpliceLocked>())

        // Alice sends an HTLC to Bob, but Bob doesn't receive the commit_sig messages.
        val (nodes3, _, htlc) = addHtlc(50_000_000.msat, alice2, bob2)
        val (alice3, actionsAlice3) = nodes3.first.process(ChannelCommand.Commitment.Sign)
        actionsAlice3.hasOutgoingMessage<CommitSigBatch>().also { batch ->
            assertEquals(2, batch.batchSize)
            batch.messages.forEach { sig -> assertEquals(2, sig.batchSize) }
        }

        // At the same time, the splice confirms on Bob's side, who now expects a single commit_sig message.
        val (bob3, actionsBob3) = nodes3.second.process(ChannelCommand.WatchReceived(WatchConfirmedTriggered(bob.channelId, WatchConfirmed.ChannelFundingDepthOk, 100, 0, spliceTx)))
        assertIs<LNChannel<Normal>>(bob3)
        val spliceLockedBob = actionsBob3.hasOutgoingMessage<SpliceLocked>()
        assertEquals(spliceTx.txid, spliceLockedBob.fundingTxId)
        val (alice4, _) = alice3.process(ChannelCommand.MessageReceived(spliceLockedBob))
        assertIs<LNChannel<Normal>>(alice4)

        // On reconnection, Alice will only re-send commit_sig for the (locked) splice transaction.
        val (alice5, bob4, channelReestablishAlice) = disconnect(alice4, bob3)
        val (bob5, actionsBob5) = bob4.process(ChannelCommand.MessageReceived(channelReestablishAlice))
        val channelReestablishBob = actionsBob5.findOutgoingMessage<ChannelReestablish>()
        val (_, actionsAlice6) = alice5.process(ChannelCommand.MessageReceived(channelReestablishBob))
        actionsAlice6.hasOutgoingMessage<UpdateAddHtlc>().also { assertEquals(htlc, it) }
        assertEquals(1, actionsAlice6.findOutgoingMessages<CommitSig>().size)
        val commitSigAlice = actionsAlice6.hasOutgoingMessage<CommitSig>()
        assertEquals(1, commitSigAlice.batchSize)
        val (bob6, _) = bob5.process(ChannelCommand.MessageReceived(htlc))
        val (bob7, actionsBob7) = bob6.process(ChannelCommand.MessageReceived(commitSigAlice))
        assertIs<LNChannel<Normal>>(bob7)
        actionsBob7.hasOutgoingMessage<RevokeAndAck>()
    }

    @Test
    fun `disconnect -- splice_locked sent`() {
        val (alice, bob) = reachNormalWithConfirmedFundingTx()
        val (alice0, bob0, htlcs) = setupHtlcs(alice, bob)
        val (alice1, bob1) = spliceInAndOut(alice0, bob0, inAmounts = listOf(150_000.sat, 25_000.sat, 15_000.sat), outAmount = 250_000.sat)
        val spliceTx = alice1.commitments.latest.localFundingStatus.signedTx!!

        val (alice2, actionsAlice2) = alice1.process(ChannelCommand.WatchReceived(WatchConfirmedTriggered(alice.channelId, WatchConfirmed.ChannelFundingDepthOk, 100, 0, spliceTx)))
        assertIs<LNChannel<Normal>>(alice2)
        val spliceLockedAlice1 = actionsAlice2.hasOutgoingMessage<SpliceLocked>()
        val (bob2, _) = bob1.process(ChannelCommand.MessageReceived(spliceLockedAlice1))
        val (bob3, actionsBob3) = bob2.process(ChannelCommand.WatchReceived(WatchConfirmedTriggered(bob.channelId, WatchConfirmed.ChannelFundingDepthOk, 100, 0, spliceTx)))
        assertIs<LNChannel<Normal>>(bob3)
        actionsBob3.hasOutgoingMessage<SpliceLocked>()

        // Alice disconnects before receiving Bob's splice_locked.
        val (alice3, bob4, channelReestablishAlice) = disconnect(alice2, bob3)
        val (bob5, actionsBob5) = bob4.process(ChannelCommand.MessageReceived(channelReestablishAlice))
        assertEquals(actionsBob5.size, 4)
        val channelReestablishBob = actionsBob5.findOutgoingMessage<ChannelReestablish>()
        val spliceLockedBob = actionsBob5.findOutgoingMessage<SpliceLocked>()
        assertEquals(htlcs.aliceToBob.map { it.second }.toSet(), actionsBob5.filterIsInstance<ChannelAction.ProcessIncomingHtlc>().map { it.add }.toSet())

        val (alice4, actionsAlice4) = alice3.process(ChannelCommand.MessageReceived(channelReestablishBob))
        assertEquals(actionsAlice4.size, 3)
        val spliceLockedAlice2 = actionsAlice4.hasOutgoingMessage<SpliceLocked>()
        assertEquals(htlcs.bobToAlice.map { it.second }.toSet(), actionsAlice4.filterIsInstance<ChannelAction.ProcessIncomingHtlc>().map { it.add }.toSet())
        val (alice5, actionsAlice5) = alice4.process(ChannelCommand.MessageReceived(spliceLockedBob))
        assertIs<LNChannel<Normal>>(alice5)
        assertEquals(alice5.state.commitments.active.size, 1)
        assertEquals(2, actionsAlice5.size)
        actionsAlice5.has<ChannelAction.Storage.StoreState>()
        actionsAlice5.has<ChannelAction.Storage.SetLocked>()

        val (bob6, actionsBob6) = bob5.process(ChannelCommand.MessageReceived(spliceLockedAlice2))
        assertIs<LNChannel<Normal>>(bob6)
        assertEquals(bob6.state.commitments.active.size, 1)
        assertEquals(actionsBob6.size, 1)
        actionsBob6.has<ChannelAction.Storage.StoreState>()
        resolveHtlcs(alice5, bob6, htlcs, commitmentsCount = 1)
    }

    @Test
    fun `disconnect -- latest commitment locked remotely and locally -- zero-conf`() {
        val (alice, bob) = reachNormalWithConfirmedFundingTx(zeroConf = true)
        val (alice0, bob0, htlcs) = setupHtlcs(alice, bob)
        val (alice1, bob1) = spliceIn(alice0, bob0, listOf(50_000.sat))
        val (alice2, bob2) = spliceOut(alice1, bob1, 100_000.sat)

        // Alice and Bob have not received any remote splice_locked yet.
        assertEquals(alice2.commitments.active.size, 3)
        alice2.commitments.active.forEach { assertEquals(it.remoteFundingStatus, RemoteFundingStatus.NotLocked) }
        assertEquals(bob2.commitments.active.size, 3)
        bob2.commitments.active.forEach { assertEquals(it.remoteFundingStatus, RemoteFundingStatus.NotLocked) }

        // On reconnection, Alice and Bob only send splice_locked for the latest commitment.
        val (alice3, bob3, channelReestablishAlice) = disconnect(alice2, bob2)
        val (bob4, actionsBob4) = bob3.process(ChannelCommand.MessageReceived(channelReestablishAlice))
        assertEquals(actionsBob4.size, 4)
        val channelReestablishBob = actionsBob4.findOutgoingMessage<ChannelReestablish>()
        val spliceLockedBob = actionsBob4.findOutgoingMessage<SpliceLocked>()
        assertEquals(htlcs.aliceToBob.map { it.second }.toSet(), actionsBob4.filterIsInstance<ChannelAction.ProcessIncomingHtlc>().map { it.add }.toSet())
        assertEquals(spliceLockedBob.fundingTxId, bob2.commitments.latest.fundingTxId)

        val (alice4, actionsAlice4) = alice3.process(ChannelCommand.MessageReceived(channelReestablishBob))
        assertEquals(actionsAlice4.size, 3)
        val spliceLockedAlice = actionsAlice4.hasOutgoingMessage<SpliceLocked>()
        assertEquals(htlcs.bobToAlice.map { it.second }.toSet(), actionsAlice4.filterIsInstance<ChannelAction.ProcessIncomingHtlc>().map { it.add }.toSet())
        assertEquals(spliceLockedAlice.fundingTxId, spliceLockedBob.fundingTxId)
        val (alice5, actionsAlice5) = alice4.process(ChannelCommand.MessageReceived(spliceLockedBob))
        assertEquals(actionsAlice5.size, 3)
        assertEquals(alice5.commitments.active.size, 1)
        assertEquals(alice5.commitments.latest.fundingTxId, spliceLockedBob.fundingTxId)
        actionsAlice5.has<ChannelAction.Storage.StoreState>()
        assertContains(actionsAlice5, ChannelAction.Storage.SetLocked(alice1.commitments.latest.fundingTxId))
        assertContains(actionsAlice5, ChannelAction.Storage.SetLocked(alice2.commitments.latest.fundingTxId))

        val (bob5, actionsBob5) = bob4.process(ChannelCommand.MessageReceived(spliceLockedAlice))
        assertEquals(actionsBob5.size, 3)
        assertEquals(bob5.commitments.active.size, 1)
        assertEquals(bob5.commitments.latest.fundingTxId, spliceLockedAlice.fundingTxId)
        actionsBob5.has<ChannelAction.Storage.StoreState>()
        assertContains(actionsBob5, ChannelAction.Storage.SetLocked(bob1.commitments.latest.fundingTxId))
        assertContains(actionsBob5, ChannelAction.Storage.SetLocked(bob2.commitments.latest.fundingTxId))
        assertIs<LNChannel<Normal>>(alice5)
        assertIs<LNChannel<Normal>>(bob5)
        resolveHtlcs(alice5, bob5, htlcs, commitmentsCount = 1)
    }

    @Test
    fun `disconnect -- latest commitment locked remotely but not locally`() {
        val (alice, bob) = reachNormalWithConfirmedFundingTx()
        val (alice0, bob0, htlcs) = setupHtlcs(alice, bob)
        val (alice1, bob1) = spliceIn(alice0, bob0, listOf(50_000.sat))
        val spliceTx1 = alice1.commitments.latest.localFundingStatus.signedTx!!
        val (alice2, bob2) = spliceOut(alice1, bob1, 100_000.sat)
        val spliceTx2 = alice2.commitments.latest.localFundingStatus.signedTx!!
        assertNotEquals(spliceTx1.txid, spliceTx2.txid)

        // Alice and Bob have not received any remote splice_locked yet.
        assertEquals(alice2.commitments.active.size, 3)
        alice2.commitments.active.forEach { assertEquals(it.remoteFundingStatus, RemoteFundingStatus.NotLocked) }
        assertEquals(bob2.commitments.active.size, 3)
        bob2.commitments.active.forEach { assertEquals(it.remoteFundingStatus, RemoteFundingStatus.NotLocked) }

        // Alice locks the last commitment.
        val (alice3, actionsAlice3) = alice2.process(ChannelCommand.WatchReceived(WatchConfirmedTriggered(alice.channelId, WatchConfirmed.ChannelFundingDepthOk, 100, 0, spliceTx2)))
        assertIs<LNChannel<Normal>>(alice3)
        assertEquals(actionsAlice3.size, 3)
        assertEquals(actionsAlice3.hasOutgoingMessage<SpliceLocked>().fundingTxId, spliceTx2.txid)
        actionsAlice3.hasWatchFundingSpent(spliceTx2.txid)
        actionsAlice3.has<ChannelAction.Storage.StoreState>()

        // Bob locks the previous commitment.
        val (bob3, actionsBob3) = bob2.process(ChannelCommand.WatchReceived(WatchConfirmedTriggered(bob.channelId, WatchConfirmed.ChannelFundingDepthOk, 100, 0, spliceTx1)))
        assertIs<LNChannel<Normal>>(bob3)
        assertEquals(actionsBob3.size, 3)
        assertEquals(actionsBob3.hasOutgoingMessage<SpliceLocked>().fundingTxId, spliceTx1.txid)
        actionsBob3.hasWatchFundingSpent(spliceTx1.txid)
        actionsBob3.has<ChannelAction.Storage.StoreState>()

        // Alice and Bob disconnect before receiving each other's splice_locked.
        // On reconnection, the latest commitment is still unlocked by Bob so they have two active commitments.
        val (alice4, bob4, channelReestablishAlice) = disconnect(alice3, bob3)
        val (bob5, actionsBob5) = bob4.process(ChannelCommand.MessageReceived(channelReestablishAlice))
        assertEquals(actionsBob5.size, 4)
        val channelReestablishBob = actionsBob5.findOutgoingMessage<ChannelReestablish>()
        val spliceLockedBob = actionsBob5.findOutgoingMessage<SpliceLocked>()
        assertEquals(htlcs.aliceToBob.map { it.second }.toSet(), actionsBob5.filterIsInstance<ChannelAction.ProcessIncomingHtlc>().map { it.add }.toSet())
        assertEquals(spliceLockedBob.fundingTxId, spliceTx1.txid)

        val (alice5, actionsAlice5) = alice4.process(ChannelCommand.MessageReceived(channelReestablishBob))
        assertEquals(actionsAlice5.size, 3)
        val spliceLockedAlice = actionsAlice5.hasOutgoingMessage<SpliceLocked>()
        assertEquals(htlcs.bobToAlice.map { it.second }.toSet(), actionsAlice5.filterIsInstance<ChannelAction.ProcessIncomingHtlc>().map { it.add }.toSet())
        assertEquals(spliceLockedAlice.fundingTxId, spliceTx2.txid)
        val (alice6, actionsAlice6) = alice5.process(ChannelCommand.MessageReceived(spliceLockedBob))
        assertEquals(actionsAlice6.size, 2)
        assertEquals(alice6.commitments.active.map { it.fundingTxId }, listOf(spliceTx2.txid, spliceTx1.txid))
        actionsAlice6.has<ChannelAction.Storage.StoreState>()
        actionsAlice6.contains(ChannelAction.Storage.SetLocked(spliceTx1.txid))

        val (bob6, actionsBob6) = bob5.process(ChannelCommand.MessageReceived(spliceLockedAlice))
        assertEquals(actionsBob6.size, 2)
        assertEquals(bob6.commitments.active.map { it.fundingTxId }, listOf(spliceTx2.txid, spliceTx1.txid))
        actionsBob6.has<ChannelAction.Storage.StoreState>()
        actionsBob6.contains(ChannelAction.Storage.SetLocked(spliceTx1.txid))
        assertIs<LNChannel<Normal>>(alice6)
        assertIs<LNChannel<Normal>>(bob6)
        resolveHtlcs(alice6, bob6, htlcs, commitmentsCount = 2)
    }

    @Test
    fun `disconnect -- splice tx published`() {
        val (alice, bob) = reachNormalWithConfirmedFundingTx()
        val (alice0, bob0, _) = setupHtlcs(alice, bob)
        val (alice1, bob1) = spliceOut(alice0, bob0, 40_000.sat)
        val spliceTx = alice1.commitments.latest.localFundingStatus.signedTx!!

        val (alice2, _) = alice1.process(ChannelCommand.Disconnected)
        val (bob2, _) = bob1.process(ChannelCommand.Disconnected)
        assertIs<Offline>(alice2.state)
        assertIs<Offline>(bob2.state)

        val (alice3, actionsAlice3) = alice2.process(ChannelCommand.WatchReceived(WatchSpentTriggered(alice.channelId, WatchSpent.ChannelSpent(TestConstants.fundingAmount), spliceTx)))
        assertIs<Offline>(alice3.state)
        assertTrue(actionsAlice3.isEmpty())
        val (bob3, actionsBob3) = bob2.process(ChannelCommand.WatchReceived(WatchSpentTriggered(bob.channelId, WatchSpent.ChannelSpent(TestConstants.fundingAmount), spliceTx)))
        assertIs<Offline>(bob3.state)
        assertTrue(actionsBob3.isEmpty())
    }

    @Test
    fun `force-close -- latest active commitment`() {
        val (alice, bob) = reachNormalWithConfirmedFundingTx()
        val (alice0, bob0, _) = setupHtlcs(alice, bob)
        val (alice1, bob1) = spliceOut(alice0, bob0, 75_000.sat)

        // Bob force-closes using the latest active commitment.
        val bobCommitTx = bob1.signCommitTx()
        val (bob2, actionsBob2) = bob1.process(ChannelCommand.Close.ForceClose)
        assertIs<Closing>(bob2.state)
        assertEquals(actionsBob2.size, 13)
        actionsBob2.hasPublishTx(bobCommitTx)
        val claimMain = actionsBob2.hasPublishTx(ChannelAction.Blockchain.PublishTx.Type.ClaimLocalDelayedOutputTx)
        actionsBob2.hasWatchConfirmed(bobCommitTx.txid)
        actionsBob2.hasWatchOutputSpent(claimMain.txIn.first().outPoint)
        actionsBob2.has<ChannelAction.Storage.StoreState>()
        actionsBob2.hasOutgoingMessage<Error>()
        actionsBob2.has<ChannelAction.Storage.StoreOutgoingPayment.ViaClose>()

        // Alice detects the force-close.
        val commitment = alice1.commitments.active.first()
        val (alice2, actionsAlice2) = alice1.process(ChannelCommand.WatchReceived(WatchSpentTriggered(alice.channelId, WatchSpent.ChannelSpent(TestConstants.fundingAmount), bobCommitTx)))
        assertIs<LNChannel<Closing>>(alice2)
        actionsAlice2.has<ChannelAction.Storage.StoreOutgoingPayment.ViaClose>()
        handleRemoteClose(alice2, actionsAlice2, commitment, bobCommitTx)
    }

    @Test
    fun `force-close -- latest active commitment -- alternative feerate`() {
        val (alice, bob) = reachNormalWithConfirmedFundingTx(channelType = ChannelType.SupportedChannelType.AnchorOutputsZeroReserve)
        val (alice1, commitSigAlice, bob1, commitSigBob) = spliceOutWithoutSigs(alice, bob, 75_000.sat)
        val (alice2, bob2) = exchangeSpliceSigs(alice1, commitSigAlice, bob1, commitSigBob)

        // Bob force-closes using the latest active commitment and an optional feerate.
        val bobCommitTx = useAlternativeCommitSig(bob2, bob2.commitments.active.first(), Commitments.alternativeFeerates.last())
        val commitment = alice1.commitments.active.first()
        val (alice3, actionsAlice3) = alice2.process(ChannelCommand.WatchReceived(WatchSpentTriggered(alice.channelId, WatchSpent.ChannelSpent(TestConstants.fundingAmount), bobCommitTx)))
        assertIs<LNChannel<Closing>>(alice3)
        actionsAlice3.has<ChannelAction.Storage.StoreOutgoingPayment.ViaClose>()
        handleRemoteClose(alice3, actionsAlice3, commitment, bobCommitTx)
    }

    @Test
    fun `force-close -- latest active commitment -- after taproot upgrade`() {
        val (alice, bob) = reachNormalWithConfirmedFundingTx(channelType = ChannelType.SupportedChannelType.AnchorOutputsZeroReserve)
        val (alice0, bob0, _) = setupHtlcs(alice, bob)
        val (alice1, bob1) = spliceOut(alice0, bob0, 75_000.sat)
        assertEquals(Transactions.CommitmentFormat.SimpleTaprootChannels, alice1.commitments.active.first().commitmentFormat)
        assertEquals(Transactions.CommitmentFormat.AnchorOutputs, alice1.commitments.active.last().commitmentFormat)
        assertEquals(Transactions.CommitmentFormat.SimpleTaprootChannels, bob1.commitments.active.first().commitmentFormat)
        assertEquals(Transactions.CommitmentFormat.AnchorOutputs, bob1.commitments.active.last().commitmentFormat)

        // Bob force-closes using the latest active commitment.
        val bobCommitTx = bob1.signCommitTx()
        val (bob2, actionsBob2) = bob1.process(ChannelCommand.Close.ForceClose)
        assertIs<Closing>(bob2.state)
        assertEquals(actionsBob2.size, 13)
        actionsBob2.hasPublishTx(bobCommitTx)
        val claimMain = actionsBob2.hasPublishTx(ChannelAction.Blockchain.PublishTx.Type.ClaimLocalDelayedOutputTx)
        actionsBob2.hasWatchConfirmed(bobCommitTx.txid)
        actionsBob2.hasWatchOutputSpent(claimMain.txIn.first().outPoint)
        actionsBob2.has<ChannelAction.Storage.StoreState>()
        actionsBob2.hasOutgoingMessage<Error>()
        actionsBob2.has<ChannelAction.Storage.StoreOutgoingPayment.ViaClose>()

        // Alice detects the force-close.
        val commitment = alice1.commitments.active.first()
        val (alice2, actionsAlice2) = alice1.process(ChannelCommand.WatchReceived(WatchSpentTriggered(alice.channelId, WatchSpent.ChannelSpent(TestConstants.fundingAmount), bobCommitTx)))
        assertIs<LNChannel<Closing>>(alice2)
        actionsAlice2.has<ChannelAction.Storage.StoreOutgoingPayment.ViaClose>()
        handleRemoteClose(alice2, actionsAlice2, commitment, bobCommitTx)
    }

    @Test
    fun `force-close -- previous active commitment`() {
        val (alice, bob) = reachNormalWithConfirmedFundingTx()
        val (alice0, bob0, _) = setupHtlcs(alice, bob)
        val (alice1, bob1) = spliceOut(alice0, bob0, 75_000.sat)

        // Bob force-closes using an older active commitment.
        assertEquals(bob1.commitments.active.map { it.localCommit.txId }.toSet().size, 2)
        val bobCommitTx = bob1.commitments.active.last().fullySignedCommitTx(bob.commitments.channelParams, bob.channelKeys)
        handlePreviousRemoteClose(alice1, bobCommitTx)
    }

    @Test
    fun `force-close -- previous active commitment -- alternative feerate`() {
        val (alice, bob) = reachNormalWithConfirmedFundingTx(channelType = ChannelType.SupportedChannelType.AnchorOutputsZeroReserve)
        val (alice1, commitSigAlice1, bob1, commitSigBob1) = spliceOutWithoutSigs(alice, bob, 75_000.sat)
        val (alice2, bob2) = exchangeSpliceSigs(alice1, commitSigAlice1, bob1, commitSigBob1)
        val (alice3, commitSigAlice3, bob3, commitSigBob3) = spliceOutWithoutSigs(alice2, bob2, 75_000.sat)
        val (alice4, bob4) = exchangeSpliceSigs(alice3, commitSigAlice3, bob3, commitSigBob3)

        // Bob force-closes using an older active commitment with an alternative feerate.
        assertEquals(bob4.commitments.active.map { it.localCommit.txId }.toSet().size, 3)
        val bobCommitTx = useAlternativeCommitSig(bob4, bob4.commitments.active[1], Commitments.alternativeFeerates.first())
        handlePreviousRemoteClose(alice4, bobCommitTx)
    }

    @Test
    fun `force-close -- previous active commitment -- after taproot upgrade`() {
        val (alice, bob) = reachNormalWithConfirmedFundingTx(channelType = ChannelType.SupportedChannelType.AnchorOutputsZeroReserve)
        val (alice0, bob0, _) = setupHtlcs(alice, bob)
        val (alice1, bob1) = spliceOut(alice0, bob0, 75_000.sat)
        assertEquals(Transactions.CommitmentFormat.SimpleTaprootChannels, alice1.commitments.active.first().commitmentFormat)
        assertEquals(Transactions.CommitmentFormat.AnchorOutputs, alice1.commitments.active.last().commitmentFormat)
        assertEquals(Transactions.CommitmentFormat.SimpleTaprootChannels, bob1.commitments.active.first().commitmentFormat)
        assertEquals(Transactions.CommitmentFormat.AnchorOutputs, bob1.commitments.active.last().commitmentFormat)

        // Bob force-closes using an older active commitment.
        assertEquals(bob1.commitments.active.map { it.localCommit.txId }.toSet().size, 2)
        val bobCommitTx = bob1.commitments.active.last().fullySignedCommitTx(bob.commitments.channelParams, bob.channelKeys)
        handlePreviousRemoteClose(alice1, bobCommitTx)
    }

    @Test
    fun `force-close -- previous inactive commitment`() {
        val (alice, bob) = reachNormalWithConfirmedFundingTx(zeroConf = true)
        val (alice0, bob0, _) = setupHtlcs(alice, bob)
        val (alice1, bob1) = spliceOut(alice0, bob0, 50_000.sat)
        val spliceTx = alice1.commitments.latest.localFundingStatus.signedTx!!
        val (alice2, _) = alice1.process(ChannelCommand.MessageReceived(SpliceLocked(alice.channelId, spliceTx.txid)))
        assertEquals(alice2.commitments.active.size, 1)
        assertEquals(alice2.commitments.inactive.size, 1)
        val (bob2, _) = bob1.process(ChannelCommand.MessageReceived(SpliceLocked(bob.channelId, spliceTx.txid)))
        assertEquals(bob2.commitments.active.size, 1)
        assertEquals(bob2.commitments.inactive.size, 1)

        // Bob force-closes using an inactive commitment.
        assertNotEquals(bob2.commitments.active.first().fundingTxId, bob2.commitments.inactive.first().fundingTxId)
        val bobCommitTx = bob2.commitments.inactive.first().fullySignedCommitTx(bob.commitments.channelParams, bob.channelKeys)
        handlePreviousRemoteClose(alice1, bobCommitTx)
    }

    @Test
    fun `force-close -- revoked latest active commitment`() {
        val (alice, bob) = reachNormalWithConfirmedFundingTx()
        val (alice0, bob0, _) = setupHtlcs(alice, bob)
        val (alice1, bob1) = spliceOut(alice0, bob0, 50_000.sat)
        val bobCommitTx = bob1.commitments.active.first().fullySignedCommitTx(bob.commitments.channelParams, bob.channelKeys)

        // Alice sends an HTLC to Bob, which revokes the previous commitment.
        val (nodes2, _, _) = addHtlc(25_000_000.msat, alice1, bob1)
        val (alice3, bob3) = crossSign(nodes2.first, nodes2.second, commitmentsCount = 2)
        assertEquals(alice3.commitments.active.size, 2)
        assertEquals(bob3.commitments.active.size, 2)

        // Bob force-closes using the revoked commitment.
        handleCurrentRevokedRemoteClose(alice3, bobCommitTx)
    }

    @Test
    fun `force-close -- revoked latest active commitment -- alternative feerate`() {
        val (alice, bob) = reachNormalWithConfirmedFundingTx(channelType = ChannelType.SupportedChannelType.AnchorOutputsZeroReserve)
        val (alice1, commitSigAlice, bob1, commitSigBob) = spliceOutWithoutSigs(alice, bob, 50_000.sat)
        val (alice2, bob2) = exchangeSpliceSigs(alice1, commitSigAlice, bob1, commitSigBob)
        val bobCommitTx = useAlternativeCommitSig(bob2, bob2.commitments.active.first(), Commitments.alternativeFeerates.first())

        // Alice sends an HTLC to Bob, which revokes the previous commitment.
        val (nodes3, _, _) = addHtlc(25_000_000.msat, alice2, bob2)
        val (alice4, bob4) = crossSign(nodes3.first, nodes3.second, commitmentsCount = 2)
        assertEquals(alice4.commitments.active.size, 2)
        assertEquals(bob4.commitments.active.size, 2)

        // Bob force-closes using the revoked commitment.
        handleCurrentRevokedRemoteClose(alice4, bobCommitTx)
    }

    @Test
    fun `force-close -- revoked previous active commitment`() {
        val (alice, bob) = reachNormalWithConfirmedFundingTx()
        val (alice0, bob0, htlcs) = setupHtlcs(alice, bob)
        // We make a first splice transaction, but don't exchange splice_locked.
        val (alice1, bob1) = spliceOut(alice0, bob0, 50_000.sat)
        val spliceTx1 = bob1.commitments.latest.localFundingStatus.signedTx!!
        val bobRevokedCommitTx = bob1.commitments.active.last().fullySignedCommitTx(bob.commitments.channelParams, bob.channelKeys)
        // We make a second splice transaction, but don't exchange splice_locked.
        val (alice2, bob2) = spliceOut(alice1, bob1, 60_000.sat)
        // From Alice's point of view, we now have two unconfirmed splices, both active.
        // They both send additional HTLCs, that apply to both commitments.
        val (nodes3, _, _) = addHtlc(10_000_000.msat, bob2, alice2)
        val (bob3, alice3) = nodes3
        val (nodes4, _, htlcOut1) = addHtlc(20_000_000.msat, alice3, bob3)
        val (alice5, bob5) = crossSign(nodes4.first, nodes4.second, commitmentsCount = 3)
        // Alice adds another HTLC that isn't signed by Bob.
        val (nodes6, _, htlcOut2) = addHtlc(15_000_000.msat, alice5, bob5)
        val (alice6, bob6) = nodes6
        val (alice7, actionsAlice7) = alice6.process(ChannelCommand.Commitment.Sign)
        actionsAlice7.hasOutgoingMessage<CommitSigBatch>() // Bob ignores Alice's message
        assertEquals(3, bob6.commitments.active.size)
        assertEquals(3, alice7.commitments.active.size)

        // The first splice transaction confirms.
        val (alice8, actionsAlice8) = alice7.process(ChannelCommand.WatchReceived(WatchConfirmedTriggered(alice0.channelId, WatchConfirmed.ChannelFundingDepthOk, 40, 2, spliceTx1)))
        actionsAlice8.has<ChannelAction.Storage.StoreState>()
        actionsAlice8.hasWatchFundingSpent(spliceTx1.txid)

        // Bob publishes a revoked commitment for fundingTx1!
        val (alice9, actionsAlice9) = alice8.process(ChannelCommand.WatchReceived(WatchSpentTriggered(bob0.channelId, WatchSpent.ChannelSpent(TestConstants.fundingAmount), bobRevokedCommitTx)))
        assertIs<LNChannel<Closing>>(alice9)
        assertEquals(WatchConfirmed.AlternativeCommitTxConfirmed, actionsAlice9.hasWatchConfirmed(bobRevokedCommitTx.txid).event)

        // Bob's revoked commit tx confirms.
        val (alice10, actionsAlice10) = alice9.process(ChannelCommand.WatchReceived(WatchConfirmedTriggered(bob0.channelId, WatchConfirmed.AlternativeCommitTxConfirmed, 41, 7, bobRevokedCommitTx)))
        assertIs<LNChannel<Closing>>(alice10)
        actionsAlice10.hasWatchConfirmed(bobRevokedCommitTx.txid).also { assertEquals(WatchConfirmed.ClosingTxConfirmed, it.event) }
        val rvk = alice10.state.revokedCommitPublished.firstOrNull()
        assertNotNull(rvk)
        // Alice reacts by punishing Bob.
        assertNotNull(rvk.localOutput)
        val mainTx = actionsAlice10.hasPublishTx(ChannelAction.Blockchain.PublishTx.Type.ClaimRemoteDelayedOutputTx)
        Transaction.correctlySpends(mainTx, bobRevokedCommitTx, ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
        actionsAlice10.hasWatchOutputSpent(mainTx.txIn.first().outPoint)
        assertNotNull(rvk.remoteOutput)
        val penaltyTx = actionsAlice10.hasPublishTx(ChannelAction.Blockchain.PublishTx.Type.MainPenaltyTx)
        Transaction.correctlySpends(penaltyTx, bobRevokedCommitTx, ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
        actionsAlice10.hasWatchOutputSpent(penaltyTx.txIn.first().outPoint)
        // Alice marks every outgoing HTLC as failed, including the ones that don't appear in the revoked commitment.
        val outgoingHtlcs = htlcs.aliceToBob.map { it.second }.toSet() + setOf(htlcOut1, htlcOut2)
        val addSettled = actionsAlice10.filterIsInstance<ChannelAction.ProcessCmdRes.AddSettledFail>()
        assertEquals(outgoingHtlcs, addSettled.map { it.htlc }.toSet())
        addSettled.forEach { assertEquals(it.result, ChannelAction.HtlcResult.Fail.OnChainFail(HtlcOverriddenByRemoteCommit(it.htlc.channelId, it.htlc))) }
        val getHtlcInfos = actionsAlice10.find<ChannelAction.Storage.GetHtlcInfos>()
        assertEquals(bobRevokedCommitTx.txid, getHtlcInfos.revokedCommitTxId)
        // Alice claims every HTLC output from the revoked commitment.
        val htlcInfos = (htlcs.aliceToBob + htlcs.bobToAlice).map { ChannelAction.Storage.HtlcInfo(bob0.channelId, getHtlcInfos.commitmentNumber, it.second.paymentHash, it.second.cltvExpiry) }
        val (alice11, actionsAlice11) = alice10.process(ChannelCommand.Closing.GetHtlcInfosResponse(bobRevokedCommitTx.txid, htlcInfos))
        assertIs<LNChannel<Closing>>(alice11)
        val rvk1 = alice11.state.revokedCommitPublished.firstOrNull()
        assertNotNull(rvk1)
        val htlcPenaltyTxs = actionsAlice11.findPublishTxs(ChannelAction.Blockchain.PublishTx.Type.HtlcPenaltyTx)
        assertEquals(htlcs.aliceToBob.size + htlcs.bobToAlice.size, rvk1.htlcOutputs.size)
        assertEquals(rvk1.htlcOutputs, htlcPenaltyTxs.flatMap { tx -> tx.txIn.map { it.outPoint } }.toSet())
        htlcPenaltyTxs.forEach { Transaction.correctlySpends(it, bobRevokedCommitTx, ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS) }
        actionsAlice11.hasWatchOutputsSpent(rvk1.htlcOutputs)

        // The remaining transactions confirm.
        val (alice12, _) = alice11.process(ChannelCommand.WatchReceived(WatchConfirmedTriggered(alice0.channelId, WatchConfirmed.ClosingTxConfirmed, 57, 0, bobRevokedCommitTx)))
        val (alice13, _) = alice12.process(ChannelCommand.WatchReceived(WatchConfirmedTriggered(alice0.channelId, WatchConfirmed.ClosingTxConfirmed, 57, 1, mainTx)))
        val (alice14, _) = alice13.process(ChannelCommand.WatchReceived(WatchConfirmedTriggered(alice0.channelId, WatchConfirmed.ClosingTxConfirmed, 57, 3, penaltyTx)))
        val (alice15, _) = alice14.process(ChannelCommand.WatchReceived(WatchConfirmedTriggered(alice0.channelId, WatchConfirmed.ClosingTxConfirmed, 57, 2, htlcPenaltyTxs[0])))
        val (alice16, _) = alice15.process(ChannelCommand.WatchReceived(WatchConfirmedTriggered(alice0.channelId, WatchConfirmed.ClosingTxConfirmed, 57, 5, htlcPenaltyTxs[1])))
        val (alice17, _) = alice16.process(ChannelCommand.WatchReceived(WatchConfirmedTriggered(alice0.channelId, WatchConfirmed.ClosingTxConfirmed, 57, 7, htlcPenaltyTxs[2])))
        assertIs<Closing>(alice17.state)
        val (alice18, _) = alice17.process(ChannelCommand.WatchReceived(WatchConfirmedTriggered(alice0.channelId, WatchConfirmed.ClosingTxConfirmed, 57, 6, htlcPenaltyTxs[3])))
        assertIs<Closed>(alice18.state)
    }

    @Test
    fun `force-close -- revoked previous active commitment -- after taproot upgrade`() {
        val (alice, bob) = reachNormalWithConfirmedFundingTx(channelType = ChannelType.SupportedChannelType.AnchorOutputsZeroReserve)
        val (alice0, bob0, htlcs) = setupHtlcs(alice, bob)
        assertEquals(alice0.commitments.latest.commitmentFormat, Transactions.CommitmentFormat.AnchorOutputs)
        assertEquals(bob0.commitments.latest.commitmentFormat, Transactions.CommitmentFormat.AnchorOutputs)
        val bobRevokedCommitTx = bob0.commitments.active.last().fullySignedCommitTx(bob.commitments.channelParams, bob.channelKeys)

        // We make a first splice transaction, but don't exchange splice_locked.
        val (alice1, bob1) = spliceOut(alice0, bob0, 50_000.sat)
        assertEquals(alice1.commitments.latest.commitmentFormat, Transactions.CommitmentFormat.SimpleTaprootChannels)
        assertEquals(bob1.commitments.latest.commitmentFormat, Transactions.CommitmentFormat.SimpleTaprootChannels)
        val spliceTx1 = bob1.commitments.latest.localFundingStatus.signedTx!!
        // We make a second splice transaction, but don't exchange splice_locked.
        val (alice2, bob2) = spliceOut(alice1, bob1, 60_000.sat)
        // From Alice's point of view, we now have two unconfirmed splices, both active.
        // They both send additional HTLCs, that apply to both commitments.
        val (nodes3, _, _) = addHtlc(10_000_000.msat, bob2, alice2)
        val (bob3, alice3) = nodes3
        val (nodes4, _, htlcOut1) = addHtlc(20_000_000.msat, alice3, bob3)
        val (alice5, bob5) = crossSign(nodes4.first, nodes4.second, commitmentsCount = 3)
        // Alice adds another HTLC that isn't signed by Bob.
        val (nodes6, _, htlcOut2) = addHtlc(15_000_000.msat, alice5, bob5)
        val (alice6, bob6) = nodes6
        val (alice7, actionsAlice7) = alice6.process(ChannelCommand.Commitment.Sign)
        actionsAlice7.hasOutgoingMessage<CommitSigBatch>() // Bob ignores Alice's message
        assertEquals(3, bob6.commitments.active.size)
        assertEquals(3, alice7.commitments.active.size)

        // The first splice transaction confirms.
        val (alice8, actionsAlice8) = alice7.process(ChannelCommand.WatchReceived(WatchConfirmedTriggered(alice0.channelId, WatchConfirmed.ChannelFundingDepthOk, 40, 2, spliceTx1)))
        actionsAlice8.has<ChannelAction.Storage.StoreState>()
        actionsAlice8.hasWatchFundingSpent(spliceTx1.txid)

        // Bob publishes a revoked commitment for the first funding tx
        val (alice9, actionsAlice9) = alice8.process(ChannelCommand.WatchReceived(WatchSpentTriggered(bob0.channelId, WatchSpent.ChannelSpent(TestConstants.fundingAmount), bobRevokedCommitTx)))
        assertIs<LNChannel<Closing>>(alice9)
        assertEquals(WatchConfirmed.AlternativeCommitTxConfirmed, actionsAlice9.hasWatchConfirmed(bobRevokedCommitTx.txid).event)

        // Bob's revoked commit tx confirms.
        val (alice10, actionsAlice10) = alice9.process(ChannelCommand.WatchReceived(WatchConfirmedTriggered(bob0.channelId, WatchConfirmed.AlternativeCommitTxConfirmed, 41, 7, bobRevokedCommitTx)))
        assertIs<LNChannel<Closing>>(alice10)
        actionsAlice10.hasWatchConfirmed(bobRevokedCommitTx.txid).also { assertEquals(WatchConfirmed.ClosingTxConfirmed, it.event) }
        val rvk = alice10.state.revokedCommitPublished.firstOrNull()
        assertNotNull(rvk)
        // Alice reacts by punishing Bob.
        assertNotNull(rvk.localOutput)
        val mainTx = actionsAlice10.hasPublishTx(ChannelAction.Blockchain.PublishTx.Type.ClaimRemoteDelayedOutputTx)
        Transaction.correctlySpends(mainTx, bobRevokedCommitTx, ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
        actionsAlice10.hasWatchOutputSpent(mainTx.txIn.first().outPoint)
        assertNotNull(rvk.remoteOutput)
        val penaltyTx = actionsAlice10.hasPublishTx(ChannelAction.Blockchain.PublishTx.Type.MainPenaltyTx)
        Transaction.correctlySpends(penaltyTx, bobRevokedCommitTx, ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
        actionsAlice10.hasWatchOutputSpent(penaltyTx.txIn.first().outPoint)
        // Alice marks every outgoing HTLC as failed, including the ones that don't appear in the revoked commitment.
        val outgoingHtlcs = htlcs.aliceToBob.map { it.second }.toSet() + setOf(htlcOut1, htlcOut2)
        val addSettled = actionsAlice10.filterIsInstance<ChannelAction.ProcessCmdRes.AddSettledFail>()
        assertEquals(outgoingHtlcs, addSettled.map { it.htlc }.toSet())
        addSettled.forEach { assertEquals(it.result, ChannelAction.HtlcResult.Fail.OnChainFail(HtlcOverriddenByRemoteCommit(it.htlc.channelId, it.htlc))) }
        val getHtlcInfos = actionsAlice10.find<ChannelAction.Storage.GetHtlcInfos>()
        assertEquals(bobRevokedCommitTx.txid, getHtlcInfos.revokedCommitTxId)
        // Alice claims every HTLC output from the revoked commitment.
        val htlcInfos = (htlcs.aliceToBob + htlcs.bobToAlice).map { ChannelAction.Storage.HtlcInfo(bob0.channelId, getHtlcInfos.commitmentNumber, it.second.paymentHash, it.second.cltvExpiry) }
        val (alice11, actionsAlice11) = alice10.process(ChannelCommand.Closing.GetHtlcInfosResponse(bobRevokedCommitTx.txid, htlcInfos))
        assertIs<LNChannel<Closing>>(alice11)
        val rvk1 = alice11.state.revokedCommitPublished.firstOrNull()
        assertNotNull(rvk1)
        val htlcPenaltyTxs = actionsAlice11.findPublishTxs(ChannelAction.Blockchain.PublishTx.Type.HtlcPenaltyTx)
        assertEquals(htlcs.aliceToBob.size + htlcs.bobToAlice.size, rvk1.htlcOutputs.size)
        assertEquals(rvk1.htlcOutputs, htlcPenaltyTxs.flatMap { tx -> tx.txIn.map { it.outPoint } }.toSet())
        htlcPenaltyTxs.forEach { Transaction.correctlySpends(it, bobRevokedCommitTx, ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS) }
        actionsAlice11.hasWatchOutputsSpent(rvk1.htlcOutputs)

        // The remaining transactions confirm.
        val (alice12, _) = alice11.process(ChannelCommand.WatchReceived(WatchConfirmedTriggered(alice0.channelId, WatchConfirmed.ClosingTxConfirmed, 57, 0, bobRevokedCommitTx)))
        val (alice13, _) = alice12.process(ChannelCommand.WatchReceived(WatchConfirmedTriggered(alice0.channelId, WatchConfirmed.ClosingTxConfirmed, 57, 1, mainTx)))
        val (alice14, _) = alice13.process(ChannelCommand.WatchReceived(WatchConfirmedTriggered(alice0.channelId, WatchConfirmed.ClosingTxConfirmed, 57, 3, penaltyTx)))
        val (alice15, _) = alice14.process(ChannelCommand.WatchReceived(WatchConfirmedTriggered(alice0.channelId, WatchConfirmed.ClosingTxConfirmed, 57, 2, htlcPenaltyTxs[0])))
        val (alice16, _) = alice15.process(ChannelCommand.WatchReceived(WatchConfirmedTriggered(alice0.channelId, WatchConfirmed.ClosingTxConfirmed, 57, 5, htlcPenaltyTxs[1])))
        val (alice17, _) = alice16.process(ChannelCommand.WatchReceived(WatchConfirmedTriggered(alice0.channelId, WatchConfirmed.ClosingTxConfirmed, 57, 7, htlcPenaltyTxs[2])))
        assertIs<Closing>(alice17.state)
        val (alice18, _) = alice17.process(ChannelCommand.WatchReceived(WatchConfirmedTriggered(alice0.channelId, WatchConfirmed.ClosingTxConfirmed, 57, 6, htlcPenaltyTxs[3])))
        assertIs<Closed>(alice18.state)
    }

    @Test
    fun `force-close -- revoked inactive commitment`() {
        val (alice, bob) = reachNormalWithConfirmedFundingTx(zeroConf = true)
        val (alice0, bob0, htlcs) = setupHtlcs(alice, bob)
        val (alice1, bob1) = spliceOut(alice0, bob0, 50_000.sat)
        val spliceTx1 = alice1.commitments.latest.localFundingStatus.signedTx!!
        val (alice2, _) = alice1.process(ChannelCommand.MessageReceived(SpliceLocked(alice.channelId, spliceTx1.txid)))
        assertIs<LNChannel<Normal>>(alice2)
        assertEquals(alice2.commitments.active.size, 1)
        assertEquals(alice2.commitments.inactive.size, 1)
        val (bob2, _) = bob1.process(ChannelCommand.MessageReceived(SpliceLocked(bob.channelId, spliceTx1.txid)))
        assertIs<LNChannel<Normal>>(bob2)
        assertEquals(bob2.commitments.active.size, 1)
        assertEquals(bob2.commitments.inactive.size, 1)
        val bobRevokedCommitTx = bob2.commitments.inactive.first().fullySignedCommitTx(bob.commitments.channelParams, bob.channelKeys)

        // Alice sends an HTLC to Bob, which revokes the inactive commitment.
        val (nodes3, _, htlcOut1) = addHtlc(25_000_000.msat, alice2, bob2)
        val (alice4, bob4) = crossSign(nodes3.first, nodes3.second, commitmentsCount = 1)

        // We create another splice transaction.
        val (alice5, bob5) = spliceOut(alice4, bob4, 50_000.sat)
        val spliceTx2 = alice5.commitments.latest.localFundingStatus.signedTx!!
        val (alice6, _) = alice5.process(ChannelCommand.MessageReceived(SpliceLocked(alice.channelId, spliceTx2.txid)))
        assertIs<LNChannel<Normal>>(alice6)
        assertEquals(alice6.commitments.active.size, 1)
        assertEquals(alice6.commitments.inactive.size, 2)
        val (bob6, _) = bob5.process(ChannelCommand.MessageReceived(SpliceLocked(bob.channelId, spliceTx2.txid)))
        assertIs<LNChannel<Normal>>(bob6)
        assertEquals(bob6.commitments.active.size, 1)
        assertEquals(bob6.commitments.inactive.size, 2)

        // Alice sends another HTLC to Bob, which revokes the inactive commitment.
        val (nodes7, _, htlcOut2) = addHtlc(25_000_000.msat, alice6, bob6)
        val (alice7, bob7) = nodes7
        // Bob also sends an HTLC to Alice.
        val (nodes8, _, htlcIn) = addHtlc(15_000_000.msat, bob7, alice7)
        val (bob8, alice8) = nodes8
        val (alice9, bob9) = crossSign(alice8, bob8, commitmentsCount = 1)
        // Alice adds another HTLC that isn't signed by Bob.
        val (nodes10, _, htlcOut3) = addHtlc(20_000_000.msat, alice9, bob9)
        val (alice10, _) = nodes10
        val (alice11, actionsAlice11) = alice10.process(ChannelCommand.Commitment.Sign)
        actionsAlice11.hasOutgoingMessage<CommitSig>() // Bob ignores Alice's message

        // Bob publishes his latest commitment for the initial fundingTx, which is now revoked.
        val (alice12, actionsAlice12) = alice11.process(ChannelCommand.WatchReceived(WatchSpentTriggered(alice.channelId, WatchSpent.ChannelSpent(TestConstants.fundingAmount), bobRevokedCommitTx)))
        assertIs<LNChannel<Closing>>(alice12)
        assertEquals(actionsAlice12.hasWatchConfirmed(bobRevokedCommitTx.txid).event, WatchConfirmed.AlternativeCommitTxConfirmed)
        // Alice attempts to force-close with her latest active commitment.
        val localCommitTx = actionsAlice12.hasPublishTx(ChannelAction.Blockchain.PublishTx.Type.CommitTx)
        assertEquals(localCommitTx.txid, alice12.commitments.active.first().localCommit.txId)
        assertNotNull(alice12.state.localCommitPublished)
        val localOutgoingHtlcs = htlcs.aliceToBob.map { it.second }.toSet() + setOf(htlcOut1, htlcOut2) // the last HTLC was not signed by Bob yet
        assertEquals(localOutgoingHtlcs.size, actionsAlice12.findPublishTxs(ChannelAction.Blockchain.PublishTx.Type.HtlcTimeoutTx).size)
        val incomingHtlcs = htlcs.bobToAlice.map { it.second }.toSet() + setOf(htlcIn)
        assertEquals(incomingHtlcs.size + localOutgoingHtlcs.size, alice12.state.localCommitPublished.htlcOutputs.size)
        actionsAlice12.hasWatchOutputsSpent(alice12.state.localCommitPublished.htlcOutputs)
        actionsAlice12.has<ChannelAction.Storage.StoreOutgoingPayment.ViaClose>()

        // Bob's revoked commit tx confirms.
        val (alice13, actionsAlice13) = alice12.process(ChannelCommand.WatchReceived(WatchConfirmedTriggered(bob0.channelId, WatchConfirmed.AlternativeCommitTxConfirmed, 41, 7, bobRevokedCommitTx)))
        assertIs<LNChannel<Closing>>(alice13)
        actionsAlice13.hasWatchConfirmed(bobRevokedCommitTx.txid).also { assertEquals(WatchConfirmed.ClosingTxConfirmed, it.event) }
        val rvk = alice13.state.revokedCommitPublished.firstOrNull()
        assertNotNull(rvk)
        // Alice reacts by punishing Bob.
        assertNotNull(rvk.localOutput)
        val mainTx = actionsAlice13.hasPublishTx(ChannelAction.Blockchain.PublishTx.Type.ClaimRemoteDelayedOutputTx)
        Transaction.correctlySpends(mainTx, bobRevokedCommitTx, ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
        actionsAlice13.hasWatchOutputSpent(mainTx.txIn.first().outPoint)
        assertNotNull(rvk.remoteOutput)
        val penaltyTx = actionsAlice13.hasPublishTx(ChannelAction.Blockchain.PublishTx.Type.MainPenaltyTx)
        Transaction.correctlySpends(penaltyTx, bobRevokedCommitTx, ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
        actionsAlice13.hasWatchOutputSpent(penaltyTx.txIn.first().outPoint)
        // Alice marks every outgoing HTLC as failed, including the ones that don't appear in the revoked commitment.
        val addSettled = actionsAlice13.filterIsInstance<ChannelAction.ProcessCmdRes.AddSettledFail>()
        val outgoingHtlcs = htlcs.aliceToBob.map { it.second }.toSet() + setOf(htlcOut1, htlcOut2, htlcOut3)
        assertEquals(outgoingHtlcs, addSettled.map { it.htlc }.toSet())
        addSettled.forEach { assertEquals(it.result, ChannelAction.HtlcResult.Fail.OnChainFail(HtlcOverriddenByRemoteCommit(it.htlc.channelId, it.htlc))) }
        val getHtlcInfos = actionsAlice13.find<ChannelAction.Storage.GetHtlcInfos>()
        assertEquals(bobRevokedCommitTx.txid, getHtlcInfos.revokedCommitTxId)
        // Alice claims every HTLC output from the revoked commitment.
        val htlcInfos = (htlcs.aliceToBob + htlcs.bobToAlice).map { ChannelAction.Storage.HtlcInfo(bob0.channelId, getHtlcInfos.commitmentNumber, it.second.paymentHash, it.second.cltvExpiry) }
        val (alice14, actionsAlice14) = alice13.process(ChannelCommand.Closing.GetHtlcInfosResponse(bobRevokedCommitTx.txid, htlcInfos))
        assertIs<LNChannel<Closing>>(alice14)
        val rvk1 = alice14.state.revokedCommitPublished.firstOrNull()
        assertNotNull(rvk1)
        val htlcPenaltyTxs = actionsAlice14.findPublishTxs(ChannelAction.Blockchain.PublishTx.Type.HtlcPenaltyTx)
        assertEquals(htlcs.aliceToBob.size + htlcs.bobToAlice.size, rvk1.htlcOutputs.size)
        assertEquals(rvk1.htlcOutputs, htlcPenaltyTxs.flatMap { tx -> tx.txIn.map { it.outPoint } }.toSet())
        htlcPenaltyTxs.forEach { Transaction.correctlySpends(it, bobRevokedCommitTx, ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS) }

        // The remaining transactions confirm.
        val (alice15, _) = alice14.process(ChannelCommand.WatchReceived(WatchConfirmedTriggered(alice0.channelId, WatchConfirmed.ClosingTxConfirmed, 57, 0, bobRevokedCommitTx)))
        val (alice16, _) = alice15.process(ChannelCommand.WatchReceived(WatchConfirmedTriggered(alice0.channelId, WatchConfirmed.ClosingTxConfirmed, 57, 1, mainTx)))
        val (alice17, _) = alice16.process(ChannelCommand.WatchReceived(WatchConfirmedTriggered(alice0.channelId, WatchConfirmed.ClosingTxConfirmed, 57, 3, penaltyTx)))
        val (alice18, _) = alice17.process(ChannelCommand.WatchReceived(WatchConfirmedTriggered(alice0.channelId, WatchConfirmed.ClosingTxConfirmed, 57, 2, htlcPenaltyTxs[0])))
        val (alice19, _) = alice18.process(ChannelCommand.WatchReceived(WatchConfirmedTriggered(alice0.channelId, WatchConfirmed.ClosingTxConfirmed, 57, 5, htlcPenaltyTxs[1])))
        val (alice20, _) = alice19.process(ChannelCommand.WatchReceived(WatchConfirmedTriggered(alice0.channelId, WatchConfirmed.ClosingTxConfirmed, 57, 7, htlcPenaltyTxs[2])))
        assertIs<Closing>(alice20.state)
        val (alice21, _) = alice20.process(ChannelCommand.WatchReceived(WatchConfirmedTriggered(alice0.channelId, WatchConfirmed.ClosingTxConfirmed, 57, 6, htlcPenaltyTxs[3])))
        assertIs<Closed>(alice21.state)
    }

    companion object {
        private val spliceFeerate = FeeratePerKw(253.sat)

        private fun reachNormalWithConfirmedFundingTx(channelType: ChannelType.SupportedChannelType = ChannelType.SupportedChannelType.SimpleTaprootChannels, zeroConf: Boolean = false): Pair<LNChannel<Normal>, LNChannel<Normal>> {
            val (alice, bob) = reachNormal(channelType = channelType, zeroConf = zeroConf)
            return when (val fundingStatus = alice.commitments.latest.localFundingStatus) {
                is LocalFundingStatus.UnconfirmedFundingTx -> {
                    val fundingTx = fundingStatus.signedTx!!
                    val (alice1, _) = alice.process(ChannelCommand.WatchReceived(WatchConfirmedTriggered(alice.channelId, WatchConfirmed.ChannelFundingDepthOk, 42, 3, fundingTx)))
                    val (bob1, _) = bob.process(ChannelCommand.WatchReceived(WatchConfirmedTriggered(bob.channelId, WatchConfirmed.ChannelFundingDepthOk, 42, 3, fundingTx)))
                    assertIs<LNChannel<Normal>>(alice1)
                    assertIs<LNChannel<Normal>>(bob1)
                    Pair(alice1, bob1)
                }
                is LocalFundingStatus.ConfirmedFundingTx -> Pair(alice, bob)
            }
        }

        private fun createSpliceOutRequest(amount: Satoshi): ChannelCommand.Commitment.Splice.Request = ChannelCommand.Commitment.Splice.Request(
            replyTo = CompletableDeferred(),
            spliceIn = null,
            spliceOut = ChannelCommand.Commitment.Splice.Request.SpliceOut(amount, Script.write(Script.pay2wpkh(randomKey().publicKey())).byteVector()),
            requestRemoteFunding = null,
            currentFeeCredit = 0.msat,
            feerate = spliceFeerate,
            origins = listOf()
        )

        private fun spliceOut(alice: LNChannel<Normal>, bob: LNChannel<Normal>, amount: Satoshi): Pair<LNChannel<Normal>, LNChannel<Normal>> {
            val (alice1, commitSigAlice, bob1, commitSigBob) = spliceOutWithoutSigs(alice, bob, amount)
            return exchangeSpliceSigs(alice1, commitSigAlice, bob1, commitSigBob)
        }

        data class UnsignedSpliceFixture(val alice: LNChannel<Normal>, val commitSigAlice: CommitSig, val bob: LNChannel<Normal>, val commitSigBob: CommitSig)

        private fun spliceOutWithoutSigs(alice: LNChannel<Normal>, bob: LNChannel<Normal>, amount: Satoshi): UnsignedSpliceFixture {
            val parentCommitment = alice.commitments.active.first()
            val cmd = createSpliceOutRequest(amount)
            // Negotiate a splice transaction where Alice is the only contributor.
            val (alice1, bob1, spliceInit) = reachQuiescent(cmd, alice, bob)
            // If we weren't using taproot yet, we update our channel type when splicing.
            when (parentCommitment.commitmentFormat) {
                Transactions.CommitmentFormat.AnchorOutputs -> assertEquals(ChannelType.SupportedChannelType.SimpleTaprootChannels, spliceInit.channelType)
                Transactions.CommitmentFormat.SimpleTaprootChannels -> assertNull(spliceInit.channelType)
            }
            // Alice takes more than the spliced out amount from her local balance because she must pay on-chain fees.
            assertTrue(-amount - 500.sat < spliceInit.fundingContribution && spliceInit.fundingContribution < -amount)
            val (bob2, actionsBob2) = bob1.process(ChannelCommand.MessageReceived(spliceInit))
            val spliceAck = actionsBob2.findOutgoingMessage<SpliceAck>()
            assertEquals(spliceAck.fundingContribution, 0.sat)
            val (alice2, actionsAlice2) = alice1.process(ChannelCommand.MessageReceived(spliceAck))
            val (bob3, actionsBob3) = bob2.process(ChannelCommand.MessageReceived(actionsAlice2.findOutgoingMessage<TxAddInput>()))
            val (alice3, actionsAlice3) = alice2.process(ChannelCommand.MessageReceived(actionsBob3.findOutgoingMessage<TxComplete>()))
            val (bob4, actionsBob4) = bob3.process(ChannelCommand.MessageReceived(actionsAlice3.findOutgoingMessage<TxAddOutput>()))
            val (alice4, actionsAlice4) = alice3.process(ChannelCommand.MessageReceived(actionsBob4.findOutgoingMessage<TxComplete>()))
            val (bob5, actionsBob5) = bob4.process(ChannelCommand.MessageReceived(actionsAlice4.findOutgoingMessage<TxAddOutput>()))
            val (alice5, actionsAlice5) = alice4.process(ChannelCommand.MessageReceived(actionsBob5.findOutgoingMessage<TxComplete>()))
            assertIs<LNChannel<Normal>>(alice5)
            val commitSigAlice = actionsAlice5.findOutgoingMessage<CommitSig>()
            actionsAlice5.has<ChannelAction.Storage.StoreState>()
            val (bob6, actionsBob6) = bob5.process(ChannelCommand.MessageReceived(actionsAlice5.findOutgoingMessage<TxComplete>()))
            assertIs<LNChannel<Normal>>(bob6)
            val commitSigBob = actionsBob6.findOutgoingMessage<CommitSig>()
            actionsBob6.has<ChannelAction.Storage.StoreState>()
            checkCommandResponse(cmd.replyTo, parentCommitment, spliceInit)
            return UnsignedSpliceFixture(alice5, commitSigAlice, bob6, commitSigBob)
        }

        fun spliceIn(alice: LNChannel<Normal>, bob: LNChannel<Normal>, amounts: List<Satoshi>): Pair<LNChannel<Normal>, LNChannel<Normal>> {
            val parentCommitment = alice.commitments.active.first()
            val cmd = ChannelCommand.Commitment.Splice.Request(
                replyTo = CompletableDeferred(),
                spliceIn = ChannelCommand.Commitment.Splice.Request.SpliceIn(createWalletWithFunds(alice.staticParams.nodeParams.keyManager, amounts)),
                spliceOut = null,
                requestRemoteFunding = null,
                currentFeeCredit = 0.msat,
                feerate = spliceFeerate,
                origins = listOf()
            )
            // Negotiate a splice transaction where Alice is the only contributor.
            val (alice1, bob1, spliceInit) = reachQuiescent(cmd, alice, bob)
            // If we weren't using taproot yet, we update our channel type when splicing.
            when (parentCommitment.commitmentFormat) {
                Transactions.CommitmentFormat.AnchorOutputs -> assertEquals(ChannelType.SupportedChannelType.SimpleTaprootChannels, spliceInit.channelType)
                Transactions.CommitmentFormat.SimpleTaprootChannels -> assertNull(spliceInit.channelType)
            }
            // Alice adds slightly less than her wallet amount because she must pay on-chain fees.
            assertTrue(amounts.sum() - 500.sat < spliceInit.fundingContribution && spliceInit.fundingContribution < amounts.sum())
            val (bob2, actionsBob2) = bob1.process(ChannelCommand.MessageReceived(spliceInit))
            val spliceAck = actionsBob2.findOutgoingMessage<SpliceAck>()
            assertEquals(spliceAck.fundingContribution, 0.sat)
            val (alice2, actionsAlice2) = alice1.process(ChannelCommand.MessageReceived(spliceAck))
            // Alice adds the shared input and one input per wallet utxo.
            val (alice3, actionsAlice3, bob3) = (0 until amounts.size + 1).fold(Triple(alice2, actionsAlice2, bob2)) { triple, _ ->
                val (alicePrev, actionsAlicePrev, bobPrev) = triple
                val (bobNext, actionsBobNext) = bobPrev.process(ChannelCommand.MessageReceived(actionsAlicePrev.findOutgoingMessage<TxAddInput>()))
                val (aliceNext, actionsAliceNext) = alicePrev.process(ChannelCommand.MessageReceived(actionsBobNext.findOutgoingMessage<TxComplete>()))
                Triple(aliceNext, actionsAliceNext, bobNext)
            }
            val (bob4, actionsBob4) = bob3.process(ChannelCommand.MessageReceived(actionsAlice3.findOutgoingMessage<TxAddOutput>()))
            val (alice4, actionsAlice4) = alice3.process(ChannelCommand.MessageReceived(actionsBob4.findOutgoingMessage<TxComplete>()))
            assertIs<LNChannel<Normal>>(alice4)
            val commitSigAlice = actionsAlice4.findOutgoingMessage<CommitSig>()
            actionsAlice4.has<ChannelAction.Storage.StoreState>()
            val (bob5, actionsBob5) = bob4.process(ChannelCommand.MessageReceived(actionsAlice4.findOutgoingMessage<TxComplete>()))
            assertIs<LNChannel<Normal>>(bob5)
            val commitSigBob = actionsBob5.findOutgoingMessage<CommitSig>()
            actionsBob5.has<ChannelAction.Storage.StoreState>()
            checkCommandResponse(cmd.replyTo, parentCommitment, spliceInit)
            return exchangeSpliceSigs(alice4, commitSigAlice, bob5, commitSigBob)
        }

        private fun spliceCpfp(alice: LNChannel<Normal>, bob: LNChannel<Normal>): Pair<LNChannel<Normal>, LNChannel<Normal>> {
            val parentCommitment = alice.commitments.active.first()
            val cmd = ChannelCommand.Commitment.Splice.Request(
                replyTo = CompletableDeferred(),
                spliceIn = null,
                spliceOut = null,
                requestRemoteFunding = null,
                currentFeeCredit = 0.msat,
                feerate = spliceFeerate,
                origins = listOf(),
            )
            // Negotiate a splice transaction with no contribution.
            val (alice1, bob1, spliceInit) = reachQuiescent(cmd, alice, bob)
            // Alice's contribution is negative: that amount goes to on-chain fees.
            assertTrue(spliceInit.fundingContribution < 0.sat)
            val (bob2, actionsBob2) = bob1.process(ChannelCommand.MessageReceived(spliceInit))
            val spliceAck = actionsBob2.findOutgoingMessage<SpliceAck>()
            assertEquals(spliceAck.fundingContribution, 0.sat)
            val (alice2, actionsAlice2) = alice1.process(ChannelCommand.MessageReceived(spliceAck))
            // Alice adds one shared input and one shared output
            val (bob3, actionsBob3) = bob2.process(ChannelCommand.MessageReceived(actionsAlice2.findOutgoingMessage<TxAddInput>()))
            val (alice3, actionsAlice3) = alice2.process(ChannelCommand.MessageReceived(actionsBob3.findOutgoingMessage<TxComplete>()))
            val (bob4, actionsBob4) = bob3.process(ChannelCommand.MessageReceived(actionsAlice3.findOutgoingMessage<TxAddOutput>()))
            val (alice4, actionsAlice4) = alice3.process(ChannelCommand.MessageReceived(actionsBob4.findOutgoingMessage<TxComplete>()))
            assertIs<LNChannel<Normal>>(alice4)
            val commitSigAlice = actionsAlice4.findOutgoingMessage<CommitSig>()
            actionsAlice4.has<ChannelAction.Storage.StoreState>()
            val (bob5, actionsBob5) = bob4.process(ChannelCommand.MessageReceived(actionsAlice4.findOutgoingMessage<TxComplete>()))
            assertIs<LNChannel<Normal>>(bob5)
            val commitSigBob = actionsBob5.findOutgoingMessage<CommitSig>()
            actionsBob5.has<ChannelAction.Storage.StoreState>()
            checkCommandResponse(cmd.replyTo, parentCommitment, spliceInit)
            return exchangeSpliceSigs(alice4, commitSigAlice, bob5, commitSigBob)
        }

        fun spliceInAndOutWithoutSigs(alice: LNChannel<Normal>, bob: LNChannel<Normal>, inAmounts: List<Satoshi>, outAmount: Satoshi): UnsignedSpliceFixture {
            val parentCommitment = alice.commitments.active.first()
            val cmd = ChannelCommand.Commitment.Splice.Request(
                replyTo = CompletableDeferred(),
                spliceIn = ChannelCommand.Commitment.Splice.Request.SpliceIn(createWalletWithFunds(alice.staticParams.nodeParams.keyManager, inAmounts)),
                spliceOut = ChannelCommand.Commitment.Splice.Request.SpliceOut(outAmount, Script.write(Script.pay2wpkh(randomKey().publicKey())).byteVector()),
                feerate = spliceFeerate,
                requestRemoteFunding = null,
                currentFeeCredit = 0.msat,
                origins = listOf()
            )
            val (alice1, bob1, spliceInit) = reachQuiescent(cmd, alice, bob)
            val (bob2, actionsBob2) = bob1.process(ChannelCommand.MessageReceived(spliceInit))
            val spliceAck = actionsBob2.findOutgoingMessage<SpliceAck>()
            assertEquals(spliceAck.fundingContribution, 0.sat)
            val (alice2, actionsAlice2) = alice1.process(ChannelCommand.MessageReceived(spliceAck))
            // Alice adds the shared input and one input per wallet utxo.
            val (alice3, actionsAlice3, bob3) = (0 until inAmounts.size + 1).fold(Triple(alice2, actionsAlice2, bob2)) { triple, _ ->
                val (alicePrev, actionsAlicePrev, bobPrev) = triple
                val (bobNext, actionsBobNext) = bobPrev.process(ChannelCommand.MessageReceived(actionsAlicePrev.findOutgoingMessage<TxAddInput>()))
                val (aliceNext, actionsAliceNext) = alicePrev.process(ChannelCommand.MessageReceived(actionsBobNext.findOutgoingMessage<TxComplete>()))
                Triple(aliceNext, actionsAliceNext, bobNext)
            }
            // Alice adds the shared output.
            val (bob4, actionsBob4) = bob3.process(ChannelCommand.MessageReceived(actionsAlice3.findOutgoingMessage<TxAddOutput>()))
            val (alice4, actionsAlice4) = alice3.process(ChannelCommand.MessageReceived(actionsBob4.findOutgoingMessage<TxComplete>()))
            // Alice adds the splice-out output.
            val (bob5, actionsBob5) = bob4.process(ChannelCommand.MessageReceived(actionsAlice4.findOutgoingMessage<TxAddOutput>()))
            val (alice5, actionsAlice5) = alice4.process(ChannelCommand.MessageReceived(actionsBob5.findOutgoingMessage<TxComplete>()))
            assertIs<LNChannel<Normal>>(alice5)
            val commitSigAlice = actionsAlice5.findOutgoingMessage<CommitSig>()
            actionsAlice5.has<ChannelAction.Storage.StoreState>()
            val (bob6, actionsBob6) = bob5.process(ChannelCommand.MessageReceived(actionsAlice5.findOutgoingMessage<TxComplete>()))
            assertIs<LNChannel<Normal>>(bob6)
            val commitSigBob = actionsBob6.findOutgoingMessage<CommitSig>()
            actionsBob6.has<ChannelAction.Storage.StoreState>()
            checkCommandResponse(cmd.replyTo, parentCommitment, spliceInit)
            return UnsignedSpliceFixture(alice5, commitSigAlice, bob6, commitSigBob)
        }

        private fun spliceInAndOut(alice: LNChannel<Normal>, bob: LNChannel<Normal>, inAmounts: List<Satoshi>, outAmount: Satoshi): Pair<LNChannel<Normal>, LNChannel<Normal>> {
            val (alice1, commitSigAlice, bob1, commitSigBob) = spliceInAndOutWithoutSigs(alice, bob, inAmounts, outAmount)
            return exchangeSpliceSigs(alice1, commitSigAlice, bob1, commitSigBob)
        }

        private fun checkCommandResponse(replyTo: CompletableDeferred<ChannelFundingResponse>, parentCommitment: Commitment, spliceInit: SpliceInit): TxId = runBlocking {
            val response = replyTo.await()
            assertIs<ChannelFundingResponse.Success>(response)
            assertEquals(response.capacity, parentCommitment.fundingAmount + spliceInit.fundingContribution)
            assertEquals(response.balance, parentCommitment.localCommit.spec.toLocal + spliceInit.fundingContribution.toMilliSatoshi())
            assertEquals(response.fundingTxIndex, parentCommitment.fundingTxIndex + 1)
            response.fundingTxId
        }

        fun exchangeSpliceSigs(alice: LNChannel<Normal>, commitSigAlice: CommitSig, bob: LNChannel<Normal>, commitSigBob: CommitSig): Pair<LNChannel<Normal>, LNChannel<Normal>> {
            val aliceSpliceStatus = alice.state.spliceStatus
            assertIs<SpliceStatus.WaitingForSigs>(aliceSpliceStatus)
            val bobSpliceStatus = bob.state.spliceStatus
            assertIs<SpliceStatus.WaitingForSigs>(bobSpliceStatus)

            val (alice1, actionsAlice1) = alice.process(ChannelCommand.MessageReceived(commitSigBob))
            assertTrue(actionsAlice1.isEmpty())
            val (bob1, actionsBob1) = bob.process(ChannelCommand.MessageReceived(commitSigAlice))
            val incomingHtlcsBob = bob1.commitments.latest.localCommit.spec.htlcs.incomings()
            when {
                bob1.staticParams.useZeroConf -> assertEquals(actionsBob1.size, 4 + incomingHtlcsBob.size)
                else -> assertEquals(actionsBob1.size, 3 + incomingHtlcsBob.size)
            }
            incomingHtlcsBob.forEach { htlc ->
                // Bob re-processes incoming HTLCs, which may trigger a fulfill now that the splice has been created.
                assertNotNull(actionsBob1.filterIsInstance<ChannelAction.ProcessIncomingHtlc>().find { it.add == htlc })
            }
            val txSigsBob = actionsBob1.findOutgoingMessage<TxSignatures>()
            assertEquals(txSigsBob.swapInServerSigs.size, aliceSpliceStatus.session.fundingTx.tx.localInputs.size)
            actionsBob1.hasWatchConfirmed(txSigsBob.txId)
            actionsBob1.has<ChannelAction.Storage.StoreState>()
            if (bob1.staticParams.useZeroConf) {
                assertEquals(actionsBob1.hasOutgoingMessage<SpliceLocked>().fundingTxId, txSigsBob.txId)
            }
            val (alice2, actionsAlice2) = alice1.process(ChannelCommand.MessageReceived(txSigsBob))
            val txSigsAlice = actionsAlice2.findOutgoingMessage<TxSignatures>()
            assertEquals(txSigsAlice.swapInServerSigs.size, bobSpliceStatus.session.fundingTx.tx.localInputs.size)
            assertEquals(actionsAlice2.hasPublishTx(ChannelAction.Blockchain.PublishTx.Type.FundingTx).txid, txSigsAlice.txId)
            actionsAlice2.hasWatchConfirmed(txSigsAlice.txId)
            if (alice1.staticParams.useZeroConf) {
                assertEquals(actionsAlice2.hasOutgoingMessage<SpliceLocked>().fundingTxId, txSigsAlice.txId)
            } else {
                assertNull(actionsAlice2.findOutgoingMessageOpt<SpliceLocked>())
            }
            actionsAlice2.has<ChannelAction.Storage.StoreState>()
            val incomingHtlcsAlice = alice1.commitments.latest.localCommit.spec.htlcs.incomings()
            incomingHtlcsAlice.forEach { htlc ->
                // Alice re-processes incoming HTLCs, which may trigger a fulfill now that the splice has been created.
                assertNotNull(actionsAlice2.filterIsInstance<ChannelAction.ProcessIncomingHtlc>().find { it.add == htlc })
            }
            when {
                aliceSpliceStatus.session.fundingParams.localContribution > 0.sat -> actionsAlice2.has<ChannelAction.Storage.StoreIncomingPayment.ViaSpliceIn>()
                aliceSpliceStatus.session.fundingParams.localContribution < 0.sat && aliceSpliceStatus.session.fundingParams.localOutputs.isNotEmpty() -> actionsAlice2.has<ChannelAction.Storage.StoreOutgoingPayment.ViaSpliceOut>()
                aliceSpliceStatus.session.fundingParams.localContribution < 0.sat && aliceSpliceStatus.session.fundingParams.localOutputs.isEmpty() -> actionsAlice2.has<ChannelAction.Storage.StoreOutgoingPayment.ViaSpliceCpfp>()
                else -> {}
            }
            val (bob2, actionsBob2) = bob1.process(ChannelCommand.MessageReceived(txSigsAlice))
            assertEquals(actionsBob2.size, 2)
            assertEquals(actionsBob2.hasPublishTx(ChannelAction.Blockchain.PublishTx.Type.FundingTx).txid, txSigsBob.txId)
            actionsBob2.has<ChannelAction.Storage.StoreState>()

            assertEquals(alice.commitments.active.size + 1, alice2.commitments.active.size)
            assertEquals(bob.commitments.active.size + 1, bob2.commitments.active.size)
            assertTrue(alice2.commitments.isMoreRecent(alice.commitments))
            assertTrue(bob2.commitments.isMoreRecent(bob.commitments))

            assertIs<LNChannel<Normal>>(alice2)
            assertIs<LNChannel<Normal>>(bob2)
            return Pair(alice2, bob2)
        }

        private fun createWalletWithFunds(keyManager: KeyManager, amounts: List<Satoshi>): List<WalletState.Utxo> {
            val script = keyManager.swapInOnChainWallet.legacySwapInProtocol.pubkeyScript
            return amounts.map { amount ->
                val txIn = listOf(TxIn(OutPoint(TxId(randomBytes32()), 2), 0))
                val txOut = listOf(TxOut(amount, script), TxOut(150.sat, Script.pay2wpkh(randomKey().publicKey())))
                val parentTx = Transaction(2, txIn, txOut, 0)
                WalletState.Utxo(parentTx.txid, 0, 42, parentTx, WalletState.AddressMeta.Single)
            }
        }

        fun disconnect(alice: LNChannel<Normal>, bob: LNChannel<Normal>): Triple<LNChannel<Syncing>, LNChannel<Syncing>, ChannelReestablish> {
            val (alice1, actionsAlice1) = alice.process(ChannelCommand.Disconnected)
            val (bob1, actionsBob1) = bob.process(ChannelCommand.Disconnected)
            assertIs<Offline>(alice1.state)
            assertTrue(actionsAlice1.isEmpty())
            assertIs<Offline>(bob1.state)
            assertTrue(actionsBob1.isEmpty())

            val aliceInit = Init(alice1.commitments.channelParams.localParams.features)
            val bobInit = Init(bob1.commitments.channelParams.localParams.features)
            val (alice2, actionsAlice2) = alice1.process(ChannelCommand.Connected(aliceInit, bobInit))
            assertIs<LNChannel<Syncing>>(alice2)
            val channelReestablish = actionsAlice2.findOutgoingMessage<ChannelReestablish>()
            val (bob2, actionsBob2) = bob1.process(ChannelCommand.Connected(bobInit, aliceInit))
            assertIs<LNChannel<Syncing>>(bob2)
            assertTrue(actionsBob2.isEmpty())
            return Triple(alice2, bob2, channelReestablish)
        }

        /** Full remote commit resolution from tx detection to channel close */
        private fun handleRemoteClose(channel1: LNChannel<Closing>, actions1: List<ChannelAction>, commitment: Commitment, remoteCommitTx: Transaction) {
            assertIs<Closing>(channel1.state)

            // Spend our outputs from the remote commitment.
            actions1.has<ChannelAction.Storage.StoreState>()
            val claimRemoteDelayedOutputTx = actions1.hasPublishTx(ChannelAction.Blockchain.PublishTx.Type.ClaimRemoteDelayedOutputTx)
            Transaction.correctlySpends(claimRemoteDelayedOutputTx, remoteCommitTx, ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
            actions1.hasWatchConfirmed(remoteCommitTx.txid)
            actions1.hasWatchOutputSpent(claimRemoteDelayedOutputTx.txIn.first().outPoint)
            assertEquals(commitment.localCommit.spec.htlcs.outgoings().size, actions1.findPublishTxs(ChannelAction.Blockchain.PublishTx.Type.ClaimHtlcTimeoutTx).size)
            actions1.hasWatchOutputsSpent(channel1.state.remoteCommitPublished!!.htlcOutputs)

            // Remote commit confirms.
            val (channel2, actions2) = channel1.process(ChannelCommand.WatchReceived(WatchConfirmedTriggered(channel1.channelId, WatchConfirmed.ClosingTxConfirmed, channel1.currentBlockHeight, 42, remoteCommitTx)))
            assertIs<Closing>(channel2.state)
            assertEquals(actions2.size, 1)
            actions2.has<ChannelAction.Storage.StoreState>()

            // Claim main output confirms.
            val (channel3, actions3) = channel2.process(ChannelCommand.WatchReceived(WatchConfirmedTriggered(channel1.channelId, WatchConfirmed.ClosingTxConfirmed, channel2.currentBlockHeight, 43, claimRemoteDelayedOutputTx)))
            if (commitment.remoteCommit.spec.htlcs.isEmpty()) {
                assertIs<Closed>(channel3.state)
                assertEquals(actions3.size, 2)
                actions3.has<ChannelAction.Storage.StoreState>()
                actions3.has<ChannelAction.Storage.SetLocked>()
            } else {
                // Htlc outputs must be resolved before channel is closed.
                assertIs<Closing>(channel2.state)
                assertEquals(actions3.size, 1)
                actions3.has<ChannelAction.Storage.StoreState>()
            }
        }

        private fun handlePreviousRemoteClose(alice1: LNChannel<Normal>, bobCommitTx: Transaction) {
            // Alice detects the force-close.
            val (alice2, actionsAlice2) = alice1.process(ChannelCommand.WatchReceived(WatchSpentTriggered(alice1.channelId, WatchSpent.ChannelSpent(TestConstants.fundingAmount), bobCommitTx)))
            assertIs<Closing>(alice2.state)
            // Alice attempts to force-close and in parallel puts a watch on the remote commit.
            val localCommit = actionsAlice2.hasPublishTx(ChannelAction.Blockchain.PublishTx.Type.CommitTx)
            assertEquals(localCommit.txid, alice1.commitments.active.first().localCommit.txId)
            val claimMain = actionsAlice2.hasPublishTx(ChannelAction.Blockchain.PublishTx.Type.ClaimLocalDelayedOutputTx)
            assertEquals(
                alice1.commitments.active.first().localCommit.spec.htlcs.outgoings().size,
                actionsAlice2.findPublishTxs(ChannelAction.Blockchain.PublishTx.Type.HtlcTimeoutTx).size
            )
            actionsAlice2.hasWatchConfirmed(localCommit.txid)
            actionsAlice2.hasWatchOutputSpent(claimMain.txIn.first().outPoint)
            assertEquals(actionsAlice2.hasWatchConfirmed(bobCommitTx.txid).event, WatchConfirmed.AlternativeCommitTxConfirmed)
            actionsAlice2.hasWatchOutputsSpent(alice2.state.localCommitPublished!!.htlcOutputs)
            actionsAlice2.has<ChannelAction.Storage.StoreState>()
            actionsAlice2.has<ChannelAction.Storage.StoreOutgoingPayment.ViaClose>()

            // Bob's commitment confirms.
            val (alice3, actionsAlice3) = alice2.process(ChannelCommand.WatchReceived(WatchConfirmedTriggered(alice2.channelId, WatchConfirmed.AlternativeCommitTxConfirmed, alice2.currentBlockHeight, 43, bobCommitTx)))
            assertIs<LNChannel<Closing>>(alice3)
            // Alice cleans up the commitments.
            assertTrue(alice2.commitments.active.size > 1)
            assertEquals(1, alice3.commitments.active.size)
            assertEquals(alice3.commitments.active.first().fundingTxId, bobCommitTx.txIn.first().outPoint.txid)
            // And processes the remote commit.
            actionsAlice3.doesNotHave<ChannelAction.Storage.StoreOutgoingPayment.ViaClose>()
            handleRemoteClose(alice3, actionsAlice3, alice3.commitments.active.first(), bobCommitTx)
        }

        private fun handleCurrentRevokedRemoteClose(alice1: LNChannel<Normal>, bobCommitTx: Transaction) {
            // Alice detects the revoked force-close.
            val (alice2, actionsAlice2) = alice1.process(ChannelCommand.WatchReceived(WatchSpentTriggered(alice1.channelId, WatchSpent.ChannelSpent(TestConstants.fundingAmount), bobCommitTx)))
            assertIs<Closing>(alice2.state)
            assertEquals(actionsAlice2.size, 9)
            val rvk = alice2.state.revokedCommitPublished.firstOrNull()
            assertNotNull(rvk)
            assertNotNull(rvk.localOutput)
            val mainTx = actionsAlice2.hasPublishTx(ChannelAction.Blockchain.PublishTx.Type.ClaimRemoteDelayedOutputTx)
            Transaction.correctlySpends(mainTx, bobCommitTx, ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
            actionsAlice2.hasWatchOutputSpent(mainTx.txIn.first().outPoint)
            assertNotNull(rvk.remoteOutput)
            val penaltyTx = actionsAlice2.hasPublishTx(ChannelAction.Blockchain.PublishTx.Type.MainPenaltyTx)
            Transaction.correctlySpends(penaltyTx, bobCommitTx, ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
            actionsAlice2.hasWatchOutputSpent(penaltyTx.txIn.first().outPoint)
            actionsAlice2.hasWatchConfirmed(bobCommitTx.txid).also { assertEquals(WatchConfirmed.ClosingTxConfirmed, it.event) }
            assertEquals(actionsAlice2.find<ChannelAction.Storage.GetHtlcInfos>().revokedCommitTxId, bobCommitTx.txid)
            actionsAlice2.hasOutgoingMessage<Error>()
            actionsAlice2.has<ChannelAction.Storage.StoreState>()
            actionsAlice2.has<ChannelAction.Storage.StoreOutgoingPayment.ViaClose>()

            // Bob's commitment confirms.
            val (alice3, actionsAlice3) = alice2.process(ChannelCommand.WatchReceived(WatchConfirmedTriggered(alice2.channelId, WatchConfirmed.ClosingTxConfirmed, alice2.currentBlockHeight, 43, bobCommitTx)))
            assertIs<Closing>(alice3.state)
            actionsAlice3.has<ChannelAction.Storage.StoreState>()

            // Alice's transactions confirm.
            val (alice4, actionsAlice4) = alice3.process(ChannelCommand.WatchReceived(WatchConfirmedTriggered(alice3.channelId, WatchConfirmed.ClosingTxConfirmed, alice3.currentBlockHeight, 44, mainTx)))
            assertIs<Closing>(alice4.state)
            assertEquals(actionsAlice4.size, 1)
            actionsAlice4.has<ChannelAction.Storage.StoreState>()
            val (alice5, actionsAlice5) = alice4.process(ChannelCommand.WatchReceived(WatchConfirmedTriggered(alice4.channelId, WatchConfirmed.ClosingTxConfirmed, alice4.currentBlockHeight, 45, penaltyTx)))
            assertIs<Closed>(alice5.state)
            actionsAlice5.has<ChannelAction.Storage.StoreState>()
            actionsAlice5.has<ChannelAction.Storage.SetLocked>()
        }

        private fun reachQuiescent(cmd: ChannelCommand.Commitment.Splice.Request, alice: LNChannel<Normal>, bob: LNChannel<Normal>): Triple<LNChannel<ChannelState>, LNChannel<ChannelState>, SpliceInit> {
            val (alice1, actionsAlice1) = alice.process(cmd)
            val aliceStfu = actionsAlice1.findOutgoingMessage<Stfu>()
            val (bob1, actionsBob1) = bob.process(ChannelCommand.MessageReceived(aliceStfu))
            val bobStfu = actionsBob1.findOutgoingMessage<Stfu>()
            val (alice2, actionsAlice2) = alice1.process(ChannelCommand.MessageReceived(bobStfu))
            val spliceInit = actionsAlice2.findOutgoingMessage<SpliceInit>()
            return Triple(alice2, bob1, spliceInit)
        }

        private fun spliceFee(alice: LNChannel<Normal>, capacity: Satoshi): Satoshi {
            // The splice initiator always pays fees from their local balance; this reduces the funding amount.
            assertIs<ChannelStateWithCommitments>(alice.state)
            val fundingTx = alice.commitments.latest.localFundingStatus.signedTx!!
            val expectedMiningFee = Transactions.weight2fee(spliceFeerate, fundingTx.weight())
            val actualMiningFee = capacity - alice.state.commitments.latest.fundingAmount
            // Fee computation is approximate (signature size isn't constant).
            assertTrue(actualMiningFee >= 0.sat && abs(actualMiningFee.toLong() - expectedMiningFee.toLong()) < 100)
            return actualMiningFee
        }

        data class TestHtlcs(val aliceToBob: List<Pair<ByteVector32, UpdateAddHtlc>>, val bobToAlice: List<Pair<ByteVector32, UpdateAddHtlc>>)

        private fun setupHtlcs(alice: LNChannel<Normal>, bob: LNChannel<Normal>): Triple<LNChannel<Normal>, LNChannel<Normal>, TestHtlcs> {
            val (nodes1, preimage1, add1) = addHtlc(15_000_000.msat, alice, bob)
            val (alice1, bob1) = nodes1
            val (nodes2, preimage2, add2) = addHtlc(15_000_000.msat, alice1, bob1)
            val (alice2, bob2) = nodes2
            val (nodes3, preimage3, add3) = addHtlc(20_000_000.msat, bob2, alice2)
            val (bob3, alice3) = nodes3
            val (alice4, bob4) = crossSign(alice3, bob3)
            val (nodes5, preimage4, add4) = addHtlc(15_000_000.msat, bob4, alice4)
            val (bob5, alice5) = nodes5
            val (bob6, alice6) = crossSign(bob5, alice5)

            assertIs<Normal>(alice6.state)
            assertEquals(1_000_000.sat, alice6.state.commitments.latest.fundingAmount)
            assertEquals(820_000_000.msat, alice6.state.commitments.latest.localCommit.spec.toLocal)
            assertEquals(115_000_000.msat, alice6.state.commitments.latest.localCommit.spec.toRemote)
            assertNotEquals(alice6.state.commitments.localCommitIndex, bob6.state.commitments.localCommitIndex)

            val aliceToBob = listOf(Pair(preimage1, add1), Pair(preimage2, add2))
            val bobToAlice = listOf(Pair(preimage3, add3), Pair(preimage4, add4))
            return Triple(alice6, bob6, TestHtlcs(aliceToBob, bobToAlice))
        }

        private fun resolveHtlcs(alice: LNChannel<Normal>, bob: LNChannel<Normal>, htlcs: TestHtlcs, commitmentsCount: Int): Pair<LNChannel<Normal>, LNChannel<Normal>> {
            // resolve pre-splice HTLCs after splice
            val (preimage1a, htlc1a) = htlcs.aliceToBob.first()
            val (preimage2a, htlc2a) = htlcs.aliceToBob.last()
            val (preimage1b, htlc1b) = htlcs.bobToAlice.first()
            val (preimage2b, htlc2b) = htlcs.bobToAlice.last()
            val nodes1 = fulfillHtlc(htlc1a.id, preimage1a, alice, bob)
            val nodes2 = fulfillHtlc(htlc2a.id, preimage2a, nodes1.first, nodes1.second)
            val nodes3 = fulfillHtlc(htlc1b.id, preimage1b, nodes2.second, nodes2.first)
            val nodes4 = fulfillHtlc(htlc2b.id, preimage2b, nodes3.first, nodes3.second)
            val nodes5 = crossSign(nodes4.first, nodes4.second, commitmentsCount)
            val aliceFinal = nodes5.second.commitments.latest
            val bobFinal = nodes5.first.commitments.latest
            assertTrue(aliceFinal.localCommit.spec.htlcs.isEmpty())
            assertTrue(aliceFinal.remoteCommit.spec.htlcs.isEmpty())
            assertTrue(bobFinal.localCommit.spec.htlcs.isEmpty())
            assertTrue(bobFinal.remoteCommit.spec.htlcs.isEmpty())
            assertEquals(alice.commitments.latest.localCommit.spec.toLocal + htlc1b.amountMsat + htlc2b.amountMsat, aliceFinal.localCommit.spec.toLocal)
            assertEquals(alice.commitments.latest.localCommit.spec.toRemote + htlc1a.amountMsat + htlc2a.amountMsat, aliceFinal.localCommit.spec.toRemote)
            assertEquals(bob.commitments.latest.localCommit.spec.toLocal + htlc1a.amountMsat + htlc2a.amountMsat, bobFinal.localCommit.spec.toLocal)
            assertEquals(bob.commitments.latest.localCommit.spec.toRemote + htlc1b.amountMsat + htlc2b.amountMsat, bobFinal.localCommit.spec.toRemote)
            return Pair(nodes5.second, nodes5.first)
        }

    }

}
