package fr.acinq.eclair.channel

import fr.acinq.bitcoin.*
import fr.acinq.eclair.*
import fr.acinq.eclair.blockchain.*
import fr.acinq.eclair.blockchain.fee.OnchainFeerates
import fr.acinq.eclair.payment.OutgoingPacket
import fr.acinq.eclair.router.ChannelHop
import fr.acinq.eclair.utils.UUID
import fr.acinq.eclair.utils.msat
import fr.acinq.eclair.utils.toByteVector32
import fr.acinq.eclair.wire.*
import kotlin.test.*

// LN Message
inline fun <reified T : LightningMessage> List<ChannelAction>.hasOutgoingMessage(): T? {
    val candidates = this.filterIsInstance<SendMessage>().map { it.message }.filterIsInstance<T>()
    return candidates.firstOrNull()
}

inline fun <reified T : LightningMessage> List<ChannelAction>.findOutgoingMessage(): T {
    return hasOutgoingMessage() ?: throw IllegalArgumentException("cannot find ${T::class}")
}

internal inline fun <reified T> List<ChannelAction>.hasMessage() = assertTrue { any { it is SendMessage && it.message is T } }
internal inline fun <reified T> List<ChannelAction>.messages() = filterIsInstance<SendMessage>().filter { it.message is T }.map { it.message as T }
internal inline fun <reified T> List<ChannelAction>.watches() = filterIsInstance<SendWatch>().filter { it.watch is T }.map { it.watch as T }
inline fun <reified T : Watch> List<ChannelAction>.findOutgoingWatch(): T {
    val candidates = this.filterIsInstance<SendWatch>().map { it.watch }.filterIsInstance<T>()
    if (candidates.isEmpty()) throw IllegalArgumentException("cannot find ${T::class}")
    return candidates.first()
}

// Commands
inline fun <reified T : Command> List<ChannelAction>.findProcessCommand(): T =
    filterIsInstance<SendToSelf>().map { it.command }.firstOrNull { it is T } as T? ?: fail("cannot find ProcessCommand ${T::class}.")

internal inline fun <reified T> List<ChannelAction>.hasCommand() = assertTrue { any { it is SendToSelf && it.command is T } }

// Errors
inline fun <reified T : Throwable> List<ChannelAction>.findError(): T =
    filterIsInstance<ProcessLocalFailure>().map { it.error }.firstOrNull { it is T } as T? ?: fail("cannot find ProcessLocalFailure ${T::class}.")

internal inline fun <reified T> List<ChannelAction>.hasError() = assertTrue { any { it is ProcessLocalFailure && it.error is T } }

inline fun <reified T : Throwable> List<ChannelAction>.findCommandError(): T? =
    filterIsInstance<HandleCommandFailed>().map { it.error }.firstOrNull { it is T } as T?

internal inline fun <reified T> List<ChannelAction>.has() = assertTrue { any { it is T } }

fun Normal.updateFeerate(feerate: Long): Normal = this.copy(currentOnchainFeerates = OnchainFeerates(feerate, feerate, feerate, feerate, feerate))
fun Negotiating.updateFeerate(feerate: Long): Negotiating = this.copy(currentOnchainFeerates = OnchainFeerates(feerate, feerate, feerate, feerate, feerate))

object TestsHelper {
    fun init(channelVersion: ChannelVersion = ChannelVersion.STANDARD, currentHeight: Int = 0, fundingAmount: Satoshi = TestConstants.fundingSatoshis): Triple<WaitForAcceptChannel, WaitForOpenChannel, OpenChannel> {
        var alice: ChannelState =
            WaitForInit(
                StaticParams(TestConstants.Alice.nodeParams, TestConstants.Bob.keyManager.nodeId),
                currentTip = Pair(currentHeight, Block.RegtestGenesisBlock.header),
                currentOnchainFeerates = OnchainFeerates(10000, 10000, 10000, 10000, 10000)
            )
        var bob: ChannelState =
            WaitForInit(
                StaticParams(TestConstants.Bob.nodeParams, TestConstants.Alice.keyManager.nodeId),
                currentTip = Pair(currentHeight, Block.RegtestGenesisBlock.header),
                currentOnchainFeerates = OnchainFeerates(10000, 10000, 10000, 10000, 10000)
            )
        val channelFlags = 0.toByte()
        var aliceChannelParams = TestConstants.Alice.channelParams
        var bobChannelParams = TestConstants.Bob.channelParams
        if (channelVersion.isSet(ChannelVersion.ZERO_RESERVE_BIT)) {
            aliceChannelParams = aliceChannelParams.copy(channelReserve = Satoshi(0))
        }
        val aliceInit = Init(ByteVector(aliceChannelParams.features.toByteArray()))
        val bobInit = Init(ByteVector(bobChannelParams.features.toByteArray()))
        var ra = alice.process(
            InitFunder(
                ByteVector32.Zeroes,
                fundingAmount,
                TestConstants.pushMsat,
                TestConstants.feeratePerKw,
                TestConstants.feeratePerKw,
                aliceChannelParams,
                bobInit,
                channelFlags,
                channelVersion
            )
        )
        alice = ra.first
        assertTrue { alice is WaitForAcceptChannel }
        var rb = bob.process(InitFundee(ByteVector32.Zeroes, bobChannelParams, aliceInit))
        bob = rb.first
        assertTrue { bob is WaitForOpenChannel }
        val open = ra.second.findOutgoingMessage<OpenChannel>()
        return Triple(alice as WaitForAcceptChannel, bob as WaitForOpenChannel, open)
    }

    fun reachNormal(channelVersion: ChannelVersion = ChannelVersion.STANDARD, currentHeight: Int = 0, fundingAmount: Satoshi = TestConstants.fundingSatoshis): Pair<Normal, Normal> {
        val (a, b, open) = init(channelVersion, currentHeight, fundingAmount)
        var alice = a as ChannelState
        var bob = b as ChannelState
        var rb = bob.process(MessageReceived(open))
        bob = rb.first
        val accept = rb.second.findOutgoingMessage<AcceptChannel>()
        var ra = alice.process(MessageReceived(accept))
        alice = ra.first
        val makeFundingTx = run {
            val candidates = ra.second.filterIsInstance<MakeFundingTx>()
            if (candidates.isEmpty()) throw IllegalArgumentException("cannot find MakeFundingTx")
            candidates.first()
        }
        val fundingTx = Transaction(
            version = 2,
            txIn = listOf(),
            txOut = listOf(TxOut(makeFundingTx.amount, makeFundingTx.pubkeyScript)),
            lockTime = 0
        )
        ra = alice.process(MakeFundingTxResponse(fundingTx, 0, Satoshi((100))))
        alice = ra.first
        val created = ra.second.findOutgoingMessage<FundingCreated>()
        rb = bob.process(MessageReceived(created))
        bob = rb.first
        val signedBob = rb.second.findOutgoingMessage<FundingSigned>()
        ra = alice.process(MessageReceived(signedBob))
        alice = ra.first
        val watchConfirmed = run {
            val candidates = ra.second.filterIsInstance<SendWatch>().map { it.watch }.filterIsInstance<WatchConfirmed>()
            if (candidates.isEmpty()) throw IllegalArgumentException("cannot find WatchConfirmed")
            candidates.first()
        }

        ra = alice.process(WatchReceived(WatchEventConfirmed(watchConfirmed.channelId, watchConfirmed.event, currentHeight + 144, 1, fundingTx)))
        alice = ra.first
        val fundingLockedAlice = ra.second.findOutgoingMessage<FundingLocked>()

        rb = bob.process(WatchReceived(WatchEventConfirmed(watchConfirmed.channelId, watchConfirmed.event, currentHeight + 144, 1, fundingTx)))
        bob = rb.first
        val fundingLockedBob = rb.second.findOutgoingMessage<FundingLocked>()

        ra = alice.process(MessageReceived(fundingLockedBob))
        alice = ra.first

        rb = bob.process(MessageReceived(fundingLockedAlice))
        bob = rb.first

        return Pair(alice as Normal, bob as Normal)
    }

    fun mutualClose(alice: Normal, bob: Normal, tweakFees: Boolean = false): Triple<Negotiating, Negotiating, ClosingSigned> {
        val alice1 = alice.updateFeerate(if (tweakFees) 4319 else 10000)
        val bob1 = bob.updateFeerate(if (tweakFees) 4319 else 10000)

        // Bob is fundee and initiates the closing
        val (bob2, actions) = bob1.process(ExecuteCommand(CMD_CLOSE(null)))
        val shutdown = actions.findOutgoingMessage<Shutdown>()

        // Alice is funder, she will sign the first closing tx
        val (alice2, actions1) = alice1.process(MessageReceived(shutdown))
        assertTrue { alice2 is Negotiating }
        val shutdown1 = actions1.findOutgoingMessage<Shutdown>()
        val closingSigned = actions1.findOutgoingMessage<ClosingSigned>()

        val alice3 = (alice2 as Negotiating).updateFeerate(if (tweakFees) 4316 else 5000)
        val bob3 = (bob2 as Normal).updateFeerate(if (tweakFees) 4316 else 5000)

        val (bob4, _) = bob3.process(MessageReceived(shutdown1))
        assertTrue { bob4 is Negotiating }
        return Triple(alice3, bob4 as Negotiating, closingSigned)
    }

    fun localClose(s: ChannelState): Pair<Closing, LocalCommitPublished> {
        require(s is ChannelStateWithCommitments)
        // an error occurs and alice publishes her commit tx
        val sCommitTx = s.commitments.localCommit.publishableTxs.commitTx.tx
        val (s1, actions1) = s.process(MessageReceived(Error(ByteVector32.Zeroes, "oops")))
        actions1.has<PublishTx>()
        assertTrue { s1 is Closing }; s1 as Closing

        val localCommitPublished = s1.localCommitPublished
        assertNotNull(localCommitPublished)

        assertEquals(actions1.filterIsInstance<PublishTx>()[0], PublishTx(localCommitPublished.commitTx))
        assertNotNull(localCommitPublished.claimMainDelayedOutputTx)
        assertEquals(
            actions1.filterIsInstance<PublishTx>()[1],
            PublishTx(localCommitPublished.claimMainDelayedOutputTx!!)
        )
        // all htlcs success/timeout should be published
        (localCommitPublished.htlcSuccessTxs + localCommitPublished.htlcTimeoutTxs)
            .forEach { tx ->
                actions1.contains(PublishTx(tx))
            }
        // and their outputs should be claimed
        localCommitPublished.claimHtlcDelayedTxs.forEach { tx ->
            actions1.contains(PublishTx(tx))
        }

        // we watch the confirmation of the "final" transactions that send funds to our wallets (main delayed output and 2nd stage htlc transactions)
        val watchConfirmedList = actions1.watches<WatchConfirmed>()
        assertEquals(BITCOIN_TX_CONFIRMED(localCommitPublished.commitTx), watchConfirmedList[0].event)
        assertEquals(BITCOIN_TX_CONFIRMED(localCommitPublished.claimMainDelayedOutputTx!!), watchConfirmedList[1].event)
        assertEquals(
            localCommitPublished.claimHtlcDelayedTxs.map { BITCOIN_TX_CONFIRMED(it) }.toSet(),
            actions1.watches<WatchConfirmed>().drop(2).map { it.event }.toSet()
        )

        // we watch outputs of the commitment tx that both parties may spend
        val htlcOutputIndexes = (localCommitPublished.htlcSuccessTxs + localCommitPublished.htlcTimeoutTxs).map { it.txIn.first().outPoint.index }
        val spentWatches = htlcOutputIndexes.zip(actions1.watches<WatchSpent>())
        spentWatches.forEach { (_, watch) ->
            assertEquals(BITCOIN_OUTPUT_SPENT, watch.event)
            assertEquals(watch.txId, sCommitTx.txid)
        }
        assertEquals(htlcOutputIndexes.toSet(), spentWatches.map { it.second.outputIndex.toLong() }.toSet())

        return s1 to localCommitPublished
    }

    fun remoteClose(rCommitTx: Transaction, s: ChannelState): Pair<Closing, RemoteCommitPublished> {
        require(s is ChannelStateWithCommitments)
        // we make s believe r unilaterally closed the channel
        val (s1, actions1) = s.process(WatchReceived(WatchEventSpent(ByteVector32.Zeroes, BITCOIN_FUNDING_SPENT, rCommitTx)))
        assertTrue { s1 is Closing }; s1 as Closing

        val remoteCommitPublished = s1.remoteCommitPublished ?: s1.nextRemoteCommitPublished ?: s1.futureRemoteCommitPublished
        assertNotNull(remoteCommitPublished)
        assertNull(s1.localCommitPublished)

        // if s has a main output in the commit tx (when it has a non-dust balance), it should be claimed
        remoteCommitPublished.claimMainOutputTx?.let { tx ->
            Transaction.correctlySpends(tx, listOf(rCommitTx), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
            assertEquals(PublishTx(tx), actions1.filterIsInstance<PublishTx>().first())
        }
        // all htlcs success/timeout should be claimed
        val claimHtlcTxes = (remoteCommitPublished.claimHtlcSuccessTxs + remoteCommitPublished.claimHtlcTimeoutTxs)
        claimHtlcTxes.forEach { tx ->
            Transaction.correctlySpends(tx, listOf(rCommitTx), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
        }
        assertTrue { actions1.containsAll(claimHtlcTxes.map { PublishTx(it) }) }

        // we watch the confirmation of the "final" transactions that send funds to our wallets (main delayed output and 2nd stage htlc transactions)
        val watchConfirmedList = actions1.watches<WatchConfirmed>()
        assertEquals(BITCOIN_TX_CONFIRMED(rCommitTx), watchConfirmedList.first().event)
        remoteCommitPublished.claimMainOutputTx?.let { tx ->
            assertEquals(BITCOIN_TX_CONFIRMED(tx), watchConfirmedList.drop(1).first().event)
        }

        // we watch outputs of the commitment tx that both parties may spend
        val htlcOutputIndexes = claimHtlcTxes.map { it.txIn.first().outPoint.index }
        val spentWatches = htlcOutputIndexes.zip(actions1.watches<WatchSpent>())
        spentWatches.forEach { (_, watch) ->
            assertEquals(BITCOIN_OUTPUT_SPENT, watch.event)
            assertEquals(watch.txId, rCommitTx.txid)
        }
        assertEquals(htlcOutputIndexes.toSet(), spentWatches.map { it.second.outputIndex.toLong() }.toSet())

        // s is now in CLOSING state with txes pending for confirmation before going in CLOSED state
        return s1 to remoteCommitPublished
    }

    fun signAndRevack(alice: ChannelState, bob: ChannelState): Pair<ChannelState, ChannelState> {
        val (alice1, actions1) = alice.process(ExecuteCommand(CMD_SIGN))
        val commitSig = actions1.findOutgoingMessage<CommitSig>()
        val (bob1, actions2) = bob.process(MessageReceived(commitSig))
        val revack = actions2.findOutgoingMessage<RevokeAndAck>()
        val (alice2, _) = alice1.process(MessageReceived(revack))
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
        val (sender0, senderActions0) = payer.process(ExecuteCommand(cmdAdd))
        val htlc = senderActions0.findOutgoingMessage<UpdateAddHtlc>()

        val (receiver0, _) = payee.process(MessageReceived(htlc))
        assertTrue(receiver0 is ChannelStateWithCommitments)
        assertTrue(receiver0.commitments.remoteChanges.proposed.contains(htlc))

        return Triple(sender0, receiver0, htlc)
    }

    fun fulfillHtlc(id: Long, paymentPreimage: ByteVector32, payer: ChannelState, payee: ChannelState): Pair<ChannelState, ChannelState> {
        val (payee0, payeeActions0) = payee.process(ExecuteCommand(CMD_FULFILL_HTLC(id, paymentPreimage)))
        val fulfillHtlc = payeeActions0.findOutgoingMessage<UpdateFulfillHtlc>()

        val (payer0, _) = payer.process(MessageReceived(fulfillHtlc))
        assertTrue(payer0 is ChannelStateWithCommitments)
        assertTrue(payer0.commitments.remoteChanges.proposed.contains(fulfillHtlc))

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

        val (sender0, sActions0) = nodeA.process(ExecuteCommand(CMD_SIGN))
        val commitSig0 = sActions0.findOutgoingMessage<CommitSig>()

        val (receiver0, rActions0) = nodeB.process(MessageReceived(commitSig0))
        val revokeAndAck0 = rActions0.findOutgoingMessage<RevokeAndAck>()
        val commandSign0 = rActions0.findProcessCommand<CMD_SIGN>()

        val (sender1, _) = sender0.process(MessageReceived(revokeAndAck0))
        val (receiver1, rActions1) = receiver0.process(ExecuteCommand(commandSign0))
        val commitSig1 = rActions1.findOutgoingMessage<CommitSig>()

        val (sender2, sActions2) = sender1.process(MessageReceived(commitSig1))
        val revokeAndAck1 = sActions2.findOutgoingMessage<RevokeAndAck>()
        val (receiver2, _) = receiver1.process(MessageReceived(revokeAndAck1))

        if (rHasChanges) {
            val commandSign1 = sActions2.findProcessCommand<CMD_SIGN>()
            val (sender3, sActions3) = sender2.process(ExecuteCommand(commandSign1))
            val commitSig2 = sActions3.findOutgoingMessage<CommitSig>()

            val (receiver3, rActions3) = receiver2.process(MessageReceived(commitSig2))
            val revokeAndAck2 = rActions3.findOutgoingMessage<RevokeAndAck>()
            val (sender4, _) = sender3.process(MessageReceived(revokeAndAck2))

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
}
