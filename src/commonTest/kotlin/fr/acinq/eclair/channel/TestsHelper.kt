package fr.acinq.eclair.channel

import fr.acinq.bitcoin.*
import fr.acinq.eclair.CltvExpiryDelta
import fr.acinq.eclair.Eclair
import fr.acinq.eclair.MilliSatoshi
import fr.acinq.eclair.ShortChannelId
import fr.acinq.eclair.blockchain.*
import fr.acinq.eclair.blockchain.fee.FeeratePerKw
import fr.acinq.eclair.blockchain.fee.OnChainFeerates
import fr.acinq.eclair.payment.OutgoingPacket
import fr.acinq.eclair.router.ChannelHop
import fr.acinq.eclair.serialization.Serialization
import fr.acinq.eclair.tests.TestConstants
import fr.acinq.eclair.utils.UUID
import fr.acinq.eclair.utils.msat
import fr.acinq.eclair.utils.sat
import fr.acinq.eclair.utils.toByteVector32
import fr.acinq.eclair.wire.*
import kotlin.test.*

// LN Message
internal inline fun <reified T : LightningMessage> List<ChannelAction>.findOutgoingMessages(): List<T> = filterIsInstance<ChannelAction.Message.Send>().map { it.message }.filterIsInstance<T>()
internal inline fun <reified T : LightningMessage> List<ChannelAction>.findOutgoingMessageOpt(): T? = findOutgoingMessages<T>().firstOrNull()
internal inline fun <reified T : LightningMessage> List<ChannelAction>.findOutgoingMessage(): T = findOutgoingMessageOpt<T>() ?: fail("cannot find outgoing message ${T::class}")
internal inline fun <reified T : LightningMessage> List<ChannelAction>.hasOutgoingMessage() = assertNotNull(findOutgoingMessageOpt<T>())

// Blockchain Watches
internal inline fun <reified T : Watch> List<ChannelAction>.findWatches(): List<T> = filterIsInstance<ChannelAction.Blockchain.SendWatch>().map { it.watch }.filterIsInstance<T>()
internal inline fun <reified T : Watch> List<ChannelAction>.findWatch(): T = findWatches<T>().firstOrNull() ?: fail("cannot find watch ${T::class}")
internal inline fun <reified T : Watch> List<ChannelAction>.hasWatch() = assertNotNull(findWatches<T>().firstOrNull())

// Commands
internal inline fun <reified T : Command> List<ChannelAction>.findCommands(): List<T> = filterIsInstance<ChannelAction.Message.SendToSelf>().map { it.command }.filterIsInstance<T>()
internal inline fun <reified T : Command> List<ChannelAction>.findCommandOpt(): T? = findCommands<T>().firstOrNull()
internal inline fun <reified T : Command> List<ChannelAction>.findCommand(): T = findCommandOpt<T>() ?: fail("cannot find command ${T::class}")
internal inline fun <reified T : Command> List<ChannelAction>.hasCommand() = assertNotNull(findCommandOpt<T>())

// Transactions
internal fun List<ChannelAction>.findTxs(): List<Transaction> = filterIsInstance<ChannelAction.Blockchain.PublishTx>().map { it.tx }
internal fun List<ChannelAction>.hasTx(tx: Transaction) = assertTrue(findTxs().contains(tx))

// Errors
internal inline fun <reified T : Throwable> List<ChannelAction>.findErrorOpt(): T? = filterIsInstance<ChannelAction.ProcessLocalError>().map { it.error }.filterIsInstance<T>().firstOrNull()
internal inline fun <reified T : Throwable> List<ChannelAction>.findError(): T = findErrorOpt<T>() ?: fail("cannot find error ${T::class}")
internal inline fun <reified T : Throwable> List<ChannelAction>.hasError() = assertNotNull(findErrorOpt<T>())

internal inline fun <reified T : ChannelException> List<ChannelAction>.findCommandErrorOpt(): T? {
    val cmdAddError = filterIsInstance<ChannelAction.ProcessCmdRes.AddFailed>().map { it.error }.filterIsInstance<T>().firstOrNull()
    val otherCmdError = filterIsInstance<ChannelAction.ProcessCmdRes.NotExecuted>().map { it.t }.filterIsInstance<T>().firstOrNull()
    return cmdAddError ?: otherCmdError
}

internal inline fun <reified T : ChannelException> List<ChannelAction>.findCommandError(): T = findCommandErrorOpt<T>() ?: fail("cannot find command error ${T::class}")
internal inline fun <reified T : ChannelException> List<ChannelAction>.hasCommandError() = assertNotNull(findCommandErrorOpt<T>())

internal inline fun <reified T : ChannelAction> List<ChannelAction>.findOpt(): T? = filterIsInstance<T>().firstOrNull()
internal inline fun <reified T : ChannelAction> List<ChannelAction>.find() = findOpt<T>() ?: fail("cannot find action ${T::class}")
internal inline fun <reified T : ChannelAction> List<ChannelAction>.has() = assertTrue { any { it is T } }

fun Normal.updateFeerate(feerate: FeeratePerKw): Normal = this.copy(currentOnChainFeerates = OnChainFeerates(feerate, feerate, feerate))
fun Negotiating.updateFeerate(feerate: FeeratePerKw): Negotiating = this.copy(currentOnChainFeerates = OnChainFeerates(feerate, feerate, feerate))

object TestsHelper {
    fun init(
        channelVersion: ChannelVersion = ChannelVersion.STANDARD,
        currentHeight: Int = TestConstants.defaultBlockHeight,
        fundingAmount: Satoshi = TestConstants.fundingAmount,
        pushMsat: MilliSatoshi = TestConstants.pushMsat,
        channelOrigin: ChannelOrigin? = null
    ): Triple<WaitForAcceptChannel, WaitForOpenChannel, OpenChannel> {
        var alice: ChannelState = WaitForInit(
            StaticParams(TestConstants.Alice.nodeParams, TestConstants.Bob.keyManager.nodeId),
            currentTip = Pair(currentHeight, Block.RegtestGenesisBlock.header),
            currentOnChainFeerates = OnChainFeerates(TestConstants.feeratePerKw, TestConstants.feeratePerKw, TestConstants.feeratePerKw)
        )
        var bob: ChannelState = WaitForInit(
            StaticParams(TestConstants.Bob.nodeParams, TestConstants.Alice.keyManager.nodeId),
            currentTip = Pair(currentHeight, Block.RegtestGenesisBlock.header),
            currentOnChainFeerates = OnChainFeerates(TestConstants.feeratePerKw, TestConstants.feeratePerKw, TestConstants.feeratePerKw)
        )
        val channelFlags = 0.toByte()
        var aliceChannelParams = TestConstants.Alice.channelParams
        val bobChannelParams = TestConstants.Bob.channelParams
        if (channelVersion.isSet(ChannelVersion.ZERO_RESERVE_BIT)) {
            aliceChannelParams = aliceChannelParams.copy(channelReserve = 0.sat)
        }
        val aliceInit = Init(ByteVector(aliceChannelParams.features.toByteArray()))
        val bobInit = Init(ByteVector(bobChannelParams.features.toByteArray()))
        val ra = alice.process(
            ChannelEvent.InitFunder(
                ByteVector32.Zeroes,
                fundingAmount,
                pushMsat,
                FeeratePerKw.CommitmentFeerate,
                TestConstants.feeratePerKw,
                aliceChannelParams,
                bobInit,
                channelFlags,
                channelVersion,
                channelOrigin
            )
        )
        alice = ra.first
        assertTrue(alice is WaitForAcceptChannel)
        val rb = bob.process(ChannelEvent.InitFundee(ByteVector32.Zeroes, bobChannelParams, aliceInit))
        bob = rb.first
        assertTrue(bob is WaitForOpenChannel)
        val open = ra.second.findOutgoingMessage<OpenChannel>()
        return Triple(alice, bob, open)
    }

    fun reachNormal(
        channelVersion: ChannelVersion = ChannelVersion.STANDARD,
        currentHeight: Int = TestConstants.defaultBlockHeight,
        fundingAmount: Satoshi = TestConstants.fundingAmount,
        pushMsat: MilliSatoshi = TestConstants.pushMsat
    ): Pair<Normal, Normal> {
        val (a, b, open) = init(channelVersion, currentHeight, fundingAmount, pushMsat)
        var alice = a as ChannelState
        var bob = b as ChannelState
        var rb = bob.process(ChannelEvent.MessageReceived(open))
        bob = rb.first
        val accept = rb.second.findOutgoingMessage<AcceptChannel>()
        var ra = alice.process(ChannelEvent.MessageReceived(accept))
        alice = ra.first
        val makeFundingTx = run {
            val candidates = ra.second.filterIsInstance<ChannelAction.Blockchain.MakeFundingTx>()
            if (candidates.isEmpty()) throw IllegalArgumentException("cannot find MakeFundingTx")
            candidates.first()
        }
        val fundingTx = Transaction(
            version = 2,
            txIn = listOf(TxIn(OutPoint(ByteVector32.Zeroes, 0), TxIn.SEQUENCE_FINAL)),
            txOut = listOf(TxOut(makeFundingTx.amount, makeFundingTx.pubkeyScript)),
            lockTime = 0
        )
        ra = alice.process(ChannelEvent.MakeFundingTxResponse(fundingTx, 0, 100.sat))
        alice = ra.first
        val created = ra.second.findOutgoingMessage<FundingCreated>()
        rb = bob.process(ChannelEvent.MessageReceived(created))
        bob = rb.first
        val signedBob = rb.second.findOutgoingMessage<FundingSigned>()
        ra = alice.process(ChannelEvent.MessageReceived(signedBob))
        alice = ra.first
        val watchConfirmed = run {
            val candidates = ra.second.findWatches<WatchConfirmed>()
            if (candidates.isEmpty()) throw IllegalArgumentException("cannot find WatchConfirmed")
            candidates.first()
        }

        ra = alice.process(ChannelEvent.WatchReceived(WatchEventConfirmed(watchConfirmed.channelId, watchConfirmed.event, currentHeight + 144, 1, fundingTx)))
        alice = ra.first
        val fundingLockedAlice = ra.second.findOutgoingMessage<FundingLocked>()

        rb = bob.process(ChannelEvent.WatchReceived(WatchEventConfirmed(watchConfirmed.channelId, watchConfirmed.event, currentHeight + 144, 1, fundingTx)))
        bob = rb.first
        val fundingLockedBob = rb.second.findOutgoingMessage<FundingLocked>()

        ra = alice.process(ChannelEvent.MessageReceived(fundingLockedBob))
        alice = ra.first

        rb = bob.process(ChannelEvent.MessageReceived(fundingLockedAlice))
        bob = rb.first

        return Pair(alice as Normal, bob as Normal)
    }

    fun mutualClose(alice: Normal, bob: Normal, tweakFees: Boolean = false): Triple<Negotiating, Negotiating, ClosingSigned> {
        val alice1 = alice.updateFeerate(if (tweakFees) FeeratePerKw(4_319.sat) else FeeratePerKw(10_000.sat))
        val bob1 = bob.updateFeerate(if (tweakFees) FeeratePerKw(4_319.sat) else FeeratePerKw(10_000.sat))

        // Bob is fundee and initiates the closing
        val (bob2, actions) = bob1.process(ChannelEvent.ExecuteCommand(CMD_CLOSE(null)))
        assertTrue(bob2 is Normal)
        val shutdown = actions.findOutgoingMessage<Shutdown>()

        // Alice is funder, she will sign the first closing tx
        val (alice2, actions1) = alice1.process(ChannelEvent.MessageReceived(shutdown))
        assertTrue(alice2 is Negotiating)
        val shutdown1 = actions1.findOutgoingMessage<Shutdown>()
        val closingSigned = actions1.findOutgoingMessage<ClosingSigned>()

        val alice3 = alice2.updateFeerate(if (tweakFees) FeeratePerKw(4_316.sat) else FeeratePerKw(5_000.sat))
        val bob3 = bob2.updateFeerate(if (tweakFees) FeeratePerKw(4_316.sat) else FeeratePerKw(5_000.sat))

        val (bob4, _) = bob3.process(ChannelEvent.MessageReceived(shutdown1))
        assertTrue(bob4 is Negotiating)
        return Triple(alice3, bob4, closingSigned)
    }

    fun localClose(s: ChannelState): Pair<Closing, LocalCommitPublished> {
        assertTrue(s is ChannelStateWithCommitments)
        assertEquals(ChannelVersion.STANDARD, s.commitments.channelVersion)
        // an error occurs and alice publishes her commit tx
        val commitTx = s.commitments.localCommit.publishableTxs.commitTx.tx
        val (s1, actions1) = s.process(ChannelEvent.MessageReceived(Error(ByteVector32.Zeroes, "oops")))
        actions1.has<ChannelAction.Storage.StoreState>()
        assertTrue(s1 is Closing)

        val localCommitPublished = s1.localCommitPublished
        assertNotNull(localCommitPublished)
        assertEquals(commitTx, localCommitPublished.commitTx)
        actions1.hasTx(commitTx)
        assertNotNull(localCommitPublished.claimMainDelayedOutputTx)
        actions1.hasTx(localCommitPublished.claimMainDelayedOutputTx!!)
        Transaction.correctlySpends(localCommitPublished.claimMainDelayedOutputTx!!, localCommitPublished.commitTx, ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
        // all htlcs success/timeout should be published
        (localCommitPublished.htlcSuccessTxs + localCommitPublished.htlcTimeoutTxs).forEach { tx ->
            Transaction.correctlySpends(tx, localCommitPublished.commitTx, ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
            actions1.hasTx(tx)
        }
        // and their outputs should be claimed
        localCommitPublished.claimHtlcDelayedTxs.forEach { tx -> actions1.hasTx(tx) }

        // we watch the confirmation of the "final" transactions that send funds to our wallets (main delayed output and 2nd stage htlc transactions)
        val expectedWatchConfirmed = buildSet {
            add(BITCOIN_TX_CONFIRMED(localCommitPublished.commitTx))
            add(BITCOIN_TX_CONFIRMED(localCommitPublished.claimMainDelayedOutputTx!!))
            addAll(localCommitPublished.claimHtlcDelayedTxs.map { BITCOIN_TX_CONFIRMED(it) })
        }
        assertEquals(expectedWatchConfirmed, actions1.findWatches<WatchConfirmed>().map { it.event }.toSet())

        // we watch outputs of the commitment tx that both parties may spend
        val htlcOutputIndexes = (localCommitPublished.htlcSuccessTxs + localCommitPublished.htlcTimeoutTxs).map { it.txIn.find { txIn -> txIn.outPoint.txid == commitTx.txid }!!.outPoint.index }
        val watchSpent = actions1.findWatches<WatchSpent>()
        watchSpent.forEach { watch ->
            assertEquals(BITCOIN_OUTPUT_SPENT, watch.event)
            assertEquals(watch.txId, commitTx.txid)
        }
        assertEquals(htlcOutputIndexes.toSet(), watchSpent.map { it.outputIndex.toLong() }.toSet())

        return s1 to localCommitPublished
    }

    fun remoteClose(rCommitTx: Transaction, s: ChannelState): Pair<Closing, RemoteCommitPublished> {
        assertTrue(s is ChannelStateWithCommitments)
        assertEquals(ChannelVersion.STANDARD, s.commitments.channelVersion)
        // we make s believe r unilaterally closed the channel
        val (s1, actions1) = s.process(ChannelEvent.WatchReceived(WatchEventSpent(s.channelId, BITCOIN_FUNDING_SPENT, rCommitTx)))
        assertTrue(s1 is Closing)

        val remoteCommitPublished = s1.remoteCommitPublished ?: s1.nextRemoteCommitPublished ?: s1.futureRemoteCommitPublished
        assertNotNull(remoteCommitPublished)
        assertNull(s1.localCommitPublished)

        // if s has a main output in the commit tx (when it has a non-dust balance), it should be claimed
        remoteCommitPublished.claimMainOutputTx?.let { tx ->
            Transaction.correctlySpends(tx, rCommitTx, ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
            actions1.hasTx(tx)
        }
        // all htlcs success/timeout should be claimed
        val claimHtlcTxs = (remoteCommitPublished.claimHtlcSuccessTxs + remoteCommitPublished.claimHtlcTimeoutTxs)
        claimHtlcTxs.forEach { tx ->
            Transaction.correctlySpends(tx, rCommitTx, ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
            actions1.hasTx(tx)
        }

        // we watch the confirmation of the "final" transactions that send funds to our wallets (main delayed output and 2nd stage htlc transactions)
        val watchConfirmedList = actions1.findWatches<WatchConfirmed>()
        assertEquals(BITCOIN_TX_CONFIRMED(rCommitTx), watchConfirmedList.first().event)
        remoteCommitPublished.claimMainOutputTx?.let { tx ->
            assertEquals(BITCOIN_TX_CONFIRMED(tx), watchConfirmedList.drop(1).first().event)
        }

        // we watch outputs of the commitment tx that both parties may spend
        val htlcOutputIndexes = claimHtlcTxs.map { it.txIn.find { txIn -> txIn.outPoint.txid == rCommitTx.txid }!!.outPoint.index }
        val watchSpent = actions1.findWatches<WatchSpent>()
        watchSpent.forEach { watch ->
            assertEquals(BITCOIN_OUTPUT_SPENT, watch.event)
            assertEquals(watch.txId, rCommitTx.txid)
        }
        assertEquals(htlcOutputIndexes.toSet(), watchSpent.map { it.outputIndex.toLong() }.toSet())

        // s is now in CLOSING state with txs pending for confirmation before going in CLOSED state
        return s1 to remoteCommitPublished
    }

    fun signAndRevack(alice: ChannelState, bob: ChannelState): Pair<ChannelState, ChannelState> {
        val (alice1, actions1) = alice.process(ChannelEvent.ExecuteCommand(CMD_SIGN))
        val commitSig = actions1.findOutgoingMessage<CommitSig>()
        val (bob1, actions2) = bob.process(ChannelEvent.MessageReceived(commitSig))
        val revack = actions2.findOutgoingMessage<RevokeAndAck>()
        val (alice2, _) = alice1.process(ChannelEvent.MessageReceived(revack))
        return Pair(alice2, bob1)
    }

    fun makeCmdAdd(amount: MilliSatoshi, destination: PublicKey, currentBlockHeight: Long, paymentPreimage: ByteVector32 = Eclair.randomBytes32(), paymentId: UUID = UUID.randomUUID()): Pair<ByteVector32, CMD_ADD_HTLC> {
        val paymentHash: ByteVector32 = Crypto.sha256(paymentPreimage).toByteVector32()
        val expiry = CltvExpiryDelta(144).toCltvExpiry(currentBlockHeight)
        val dummyKey = PrivateKey(ByteVector32("0101010101010101010101010101010101010101010101010101010101010101")).publicKey()
        val dummyUpdate = ChannelUpdate(ByteVector64.Zeroes, ByteVector32.Zeroes, ShortChannelId(144, 0, 0), 0, 0, 0, CltvExpiryDelta(1), 0.msat, 0.msat, 0, null)
        val cmd = OutgoingPacket.buildCommand(paymentId, paymentHash, listOf(ChannelHop(dummyKey, destination, dummyUpdate)), FinalLegacyPayload(amount, expiry)).first.copy(commit = false)
        return Pair(paymentPreimage, cmd)
    }

    fun addHtlc(amount: MilliSatoshi, payer: ChannelState, payee: ChannelState): Triple<Pair<ChannelState, ChannelState>, ByteVector32, UpdateAddHtlc> {
        val currentBlockHeight = payer.currentBlockHeight.toLong()
        val (paymentPreimage, cmd) = makeCmdAdd(amount, payee.staticParams.nodeParams.nodeId, currentBlockHeight)
        val (sender0, receiver0, htlc) = addHtlc(cmd, payer, payee)
        return Triple(sender0 to receiver0, paymentPreimage, htlc)
    }

    fun addHtlc(cmdAdd: CMD_ADD_HTLC, payer: ChannelState, payee: ChannelState): Triple<ChannelState, ChannelState, UpdateAddHtlc> {
        val (sender0, senderActions0) = payer.process(ChannelEvent.ExecuteCommand(cmdAdd))
        val htlc = senderActions0.findOutgoingMessage<UpdateAddHtlc>()

        val (receiver0, _) = payee.process(ChannelEvent.MessageReceived(htlc))
        assertTrue(receiver0 is ChannelStateWithCommitments)
        assertTrue(receiver0.commitments.remoteChanges.proposed.contains(htlc))

        return Triple(sender0, receiver0, htlc)
    }

    fun fulfillHtlc(id: Long, paymentPreimage: ByteVector32, payer: ChannelState, payee: ChannelState): Pair<ChannelState, ChannelState> {
        val (payee0, payeeActions0) = payee.process(ChannelEvent.ExecuteCommand(CMD_FULFILL_HTLC(id, paymentPreimage)))
        val fulfillHtlc = payeeActions0.findOutgoingMessage<UpdateFulfillHtlc>()

        val (payer0, _) = payer.process(ChannelEvent.MessageReceived(fulfillHtlc))
        assertTrue(payer0 is ChannelStateWithCommitments)
        assertTrue(payer0.commitments.remoteChanges.proposed.contains(fulfillHtlc))

        return payer0 to payee0
    }

    fun failHtlc(id: Long, payer: ChannelState, payee: ChannelState): Pair<ChannelState, ChannelState> {
        val (payee0, payeeActions0) = payee.process(ChannelEvent.ExecuteCommand(CMD_FAIL_HTLC(id, CMD_FAIL_HTLC.Reason.Failure(TemporaryNodeFailure))))
        val failHtlc = payeeActions0.findOutgoingMessage<UpdateFailHtlc>()

        val (payer0, _) = payer.process(ChannelEvent.MessageReceived(failHtlc))
        assertTrue(payer0 is ChannelStateWithCommitments)
        assertTrue(payer0.commitments.remoteChanges.proposed.contains(failHtlc))

        return payer0 to payee0
    }

    /**
     * Cross sign nodes where nodeA initiate the signature exchange
     */
    fun crossSign(nodeA: ChannelState, nodeB: ChannelState): Pair<ChannelState, ChannelState> {
        assertTrue(nodeA is ChannelStateWithCommitments)
        assertTrue(nodeB is ChannelStateWithCommitments)

        val sCommitIndex = nodeA.commitments.localCommit.index
        val rCommitIndex = nodeB.commitments.localCommit.index
        val rHasChanges = nodeB.commitments.localHasChanges()

        val (sender0, sActions0) = nodeA.process(ChannelEvent.ExecuteCommand(CMD_SIGN))
        val commitSig0 = sActions0.findOutgoingMessage<CommitSig>()

        val (receiver0, rActions0) = nodeB.process(ChannelEvent.MessageReceived(commitSig0))
        val revokeAndAck0 = rActions0.findOutgoingMessage<RevokeAndAck>()
        val commandSign0 = rActions0.findCommand<CMD_SIGN>()

        val (sender1, _) = sender0.process(ChannelEvent.MessageReceived(revokeAndAck0))
        val (receiver1, rActions1) = receiver0.process(ChannelEvent.ExecuteCommand(commandSign0))
        val commitSig1 = rActions1.findOutgoingMessage<CommitSig>()

        val (sender2, sActions2) = sender1.process(ChannelEvent.MessageReceived(commitSig1))
        val revokeAndAck1 = sActions2.findOutgoingMessage<RevokeAndAck>()
        val (receiver2, _) = receiver1.process(ChannelEvent.MessageReceived(revokeAndAck1))

        if (rHasChanges) {
            val commandSign1 = sActions2.findCommand<CMD_SIGN>()
            val (sender3, sActions3) = sender2.process(ChannelEvent.ExecuteCommand(commandSign1))
            val commitSig2 = sActions3.findOutgoingMessage<CommitSig>()

            val (receiver3, rActions3) = receiver2.process(ChannelEvent.MessageReceived(commitSig2))
            val revokeAndAck2 = rActions3.findOutgoingMessage<RevokeAndAck>()
            val (sender4, _) = sender3.process(ChannelEvent.MessageReceived(revokeAndAck2))

            sender4 as ChannelStateWithCommitments; receiver3 as ChannelStateWithCommitments
            assertEquals(sCommitIndex + 1, sender4.commitments.localCommit.index)
            assertEquals(sCommitIndex + 2, sender4.commitments.remoteCommit.index)
            assertEquals(rCommitIndex + 2, receiver3.commitments.localCommit.index)
            assertEquals(rCommitIndex + 1, receiver3.commitments.remoteCommit.index)

            return sender4 to receiver3
        } else {
            sender2 as ChannelStateWithCommitments; receiver2 as ChannelStateWithCommitments
            assertEquals(sCommitIndex + 1, sender2.commitments.localCommit.index)
            assertEquals(sCommitIndex + 1, sender2.commitments.remoteCommit.index)
            assertEquals(rCommitIndex + 1, receiver2.commitments.localCommit.index)
            assertEquals(rCommitIndex + 1, receiver2.commitments.remoteCommit.index)

            return sender2 to receiver2
        }
    }

    // we check that serialization works by checking that deserialize(serialize(state)) == state
    fun checkSerialization(state: ChannelStateWithCommitments) {
        val serialized = Serialization.serialize(state)
        val deserialized = Serialization.deserialize(serialized, state.staticParams.nodeParams)
        assertEquals(deserialized, state, "serialization error")
    }

    fun checkSerialization(actions: List<ChannelAction>) {
        // we check that serialization works everytime we're suppose to persist channel data
        actions.filterIsInstance<ChannelAction.Storage.StoreState>().forEach { checkSerialization(it.data) }
    }

    // test-specific extension that allows for extra checks during tests
    fun ChannelState.processEx(event: ChannelEvent): Pair<ChannelState, List<ChannelAction>> {
        val result = this.process(event)
        checkSerialization(result.second)
        return result
    }
}
