package fr.acinq.lightning.channel.states

import fr.acinq.bitcoin.*
import fr.acinq.bitcoin.crypto.musig2.IndividualNonce
import fr.acinq.bitcoin.utils.Either
import fr.acinq.lightning.CltvExpiryDelta
import fr.acinq.lightning.NodeParams
import fr.acinq.lightning.SensitiveTaskEvents
import fr.acinq.lightning.ShortChannelId
import fr.acinq.lightning.blockchain.WatchConfirmed
import fr.acinq.lightning.blockchain.WatchConfirmedTriggered
import fr.acinq.lightning.blockchain.WatchSpent
import fr.acinq.lightning.blockchain.WatchSpentTriggered
import fr.acinq.lightning.blockchain.fee.OnChainFeerates
import fr.acinq.lightning.channel.*
import fr.acinq.lightning.crypto.ChannelKeys
import fr.acinq.lightning.crypto.KeyManager
import fr.acinq.lightning.crypto.NonceGenerator
import fr.acinq.lightning.db.ChannelCloseOutgoingPayment.ChannelClosingType
import fr.acinq.lightning.logging.LoggingContext
import fr.acinq.lightning.logging.MDCLogger
import fr.acinq.lightning.transactions.Transactions
import fr.acinq.lightning.utils.msat
import fr.acinq.lightning.utils.sat
import fr.acinq.lightning.wire.ChannelReady
import fr.acinq.lightning.wire.ChannelReestablish
import fr.acinq.lightning.wire.ChannelUpdate
import fr.acinq.lightning.wire.Error
import fr.acinq.lightning.wire.Shutdown
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first

/*
 * Channel is implemented as a finite state machine
 * Its main method is (State, Event) -> (State, List<Action>)
 */

/** Channel static parameters. */
data class StaticParams(val nodeParams: NodeParams, val remoteNodeId: PublicKey) {
    val useZeroConf: Boolean = nodeParams.zeroConfPeers.contains(remoteNodeId)
}

data class ChannelContext(
    val staticParams: StaticParams,
    val currentBlockHeight: Int,
    val onChainFeerates: StateFlow<OnChainFeerates?>,
    override val logger: MDCLogger
) : LoggingContext {
    val keyManager: KeyManager get() = staticParams.nodeParams.keyManager
    val privateKey: PrivateKey get() = staticParams.nodeParams.nodePrivateKey
    suspend fun currentOnChainFeerates(): OnChainFeerates {
        logger.info { "retrieving feerates" }
        return onChainFeerates.filterNotNull().first()
            .also { logger.info { "using feerates=$it" } }
    }
}

/** Channel state. */
sealed class ChannelState {

    /**
     * @param cmd input event (for example, a message was received, a command was sent by the GUI/API, etc)
     * @return a (new state, list of actions) pair
     */
    abstract suspend fun ChannelContext.processInternal(cmd: ChannelCommand): Pair<ChannelState, List<ChannelAction>>

    suspend fun ChannelContext.process(cmd: ChannelCommand): Pair<ChannelState, List<ChannelAction>> {
        return try {
            processInternal(cmd)
                .let { (newState, actions) -> Pair(newState, actions + onTransition(newState)) }
        } catch (t: Throwable) {
            handleLocalError(cmd, t)
        }
    }

    /** Add actions for some transitions */
    private fun ChannelContext.onTransition(newState: ChannelState): List<ChannelAction> {
        val oldState = when (this@ChannelState) {
            is Offline -> this@ChannelState.state
            is Syncing -> this@ChannelState.state
            else -> this@ChannelState
        }
        maybeSignalSensitiveTask(oldState, newState)
        return maybeEmitClosingEvents(oldState, newState)
    }

    /** Some transitions imply that we are in the middle of tasks that may require some time. */
    private fun ChannelContext.maybeSignalSensitiveTask(oldState: ChannelState, newState: ChannelState) {
        val spliceStatusBefore = (oldState as? Normal)?.spliceStatus
        val spliceStatusAfter = (newState as? Normal)?.spliceStatus
        when {
            spliceStatusBefore !is SpliceStatus.InProgress && spliceStatusAfter is SpliceStatus.InProgress -> // splice initiated
                staticParams.nodeParams._nodeEvents.tryEmit(SensitiveTaskEvents.TaskStarted(SensitiveTaskEvents.TaskIdentifier.InteractiveTx(spliceStatusAfter.spliceSession.fundingParams)))
            spliceStatusBefore is SpliceStatus.InProgress && spliceStatusAfter !is SpliceStatus.WaitingForSigs -> // splice aborted before reaching signing phase
                staticParams.nodeParams._nodeEvents.tryEmit(SensitiveTaskEvents.TaskEnded(SensitiveTaskEvents.TaskIdentifier.InteractiveTx(spliceStatusBefore.spliceSession.fundingParams)))
            spliceStatusBefore is SpliceStatus.WaitingForSigs && spliceStatusAfter !is SpliceStatus.WaitingForSigs -> // splice leaving signing phase (successfully or not)
                staticParams.nodeParams._nodeEvents.tryEmit(SensitiveTaskEvents.TaskEnded(SensitiveTaskEvents.TaskIdentifier.InteractiveTx(spliceStatusBefore.session.fundingParams)))
            else -> {}
        }
    }

    private fun ChannelContext.maybeEmitClosingEvents(oldState: ChannelState, newState: ChannelState): List<ChannelAction> {
        return when {
            // ignore init transitions from init or without a commitment
            oldState !is ChannelStateWithCommitments -> emptyList()
            // normal mutual close flow
            oldState is Negotiating && oldState.publishedClosingTxs.isEmpty() && newState is Negotiating && newState.publishedClosingTxs.isNotEmpty() -> emitMutualCloseEvents(newState, newState.publishedClosingTxs.first())
            // we have been notified of a published mutual close tx that we didn't see before, while disconnected
            oldState is Negotiating && oldState.publishedClosingTxs.isEmpty() && newState is Offline && newState.state is Negotiating && newState.state.publishedClosingTxs.isNotEmpty() -> emitMutualCloseEvents(newState.state, newState.state.publishedClosingTxs.first())
            oldState is Negotiating && oldState.publishedClosingTxs.isEmpty() && newState is Syncing && newState.state is Negotiating && newState.state.publishedClosingTxs.isNotEmpty() -> emitMutualCloseEvents(newState.state, newState.state.publishedClosingTxs.first())
            // we have been notified of a confirmed mutual close tx that we didn't see before, while disconnected
            oldState is Negotiating && oldState.publishedClosingTxs.isEmpty() && newState is Closed && newState.state.mutualClosePublished.isNotEmpty() -> emitMutualCloseEvents(newState, newState.state.mutualClosePublished.first())
            // force closes
            oldState !is Closing && newState is Closing && newState.localCommitPublished is LocalCommitPublished -> emitForceCloseEvents(newState, newState.localCommitPublished.commitTx, ChannelClosingType.Local)
            oldState !is Closing && newState is Closing && newState.remoteCommitPublished is RemoteCommitPublished -> emitForceCloseEvents(newState, newState.remoteCommitPublished.commitTx, ChannelClosingType.Remote)
            oldState !is Closing && newState is Closing && newState.nextRemoteCommitPublished is RemoteCommitPublished -> emitForceCloseEvents(newState, newState.nextRemoteCommitPublished.commitTx, ChannelClosingType.Remote)
            oldState !is Closing && newState is Closing && newState.futureRemoteCommitPublished is RemoteCommitPublished -> emitForceCloseEvents(newState, newState.futureRemoteCommitPublished.commitTx, ChannelClosingType.Remote)
            oldState !is Closing && newState is Closing && newState.revokedCommitPublished.isNotEmpty() -> emitForceCloseEvents(newState, newState.revokedCommitPublished.first().commitTx, ChannelClosingType.Revoked)
            else -> emptyList()
        }
    }

    private fun ChannelContext.emitMutualCloseEvents(state: ChannelStateWithCommitments, mutualCloseTx: Transactions.ClosingTx): List<ChannelAction> {
        val channelBalance = state.commitments.latest.localCommit.spec.toLocal
        val finalAmount = mutualCloseTx.toLocalOutput?.amount ?: 0.sat
        val address = mutualCloseTx.toLocalOutput?.publicKeyScript?.let { Bitcoin.addressFromPublicKeyScript(staticParams.nodeParams.chainHash, it.toByteArray()).right } ?: "unknown"
        return buildList {
            if (channelBalance > 0.msat) {
                add(
                    ChannelAction.Storage.StoreOutgoingPayment.ViaClose(
                        amount = finalAmount,
                        miningFee = channelBalance.truncateToSatoshi() - finalAmount,
                        address = address,
                        txId = mutualCloseTx.tx.txid,
                        isSentToDefaultAddress = mutualCloseTx.toLocalOutput?.publicKeyScript == state.commitments.channelParams.localParams.defaultFinalScriptPubKey,
                        closingType = ChannelClosingType.Mutual
                    )
                )
            }
        }
    }

    private fun ChannelContext.emitForceCloseEvents(state: ChannelStateWithCommitments, commitTx: Transaction, closingType: ChannelClosingType): List<ChannelAction> {
        val channelBalance = state.commitments.latest.localCommit.spec.toLocal
        val address = Bitcoin.addressFromPublicKeyScript(
            chainHash = staticParams.nodeParams.chainHash,
            pubkeyScript = state.commitments.channelParams.localParams.defaultFinalScriptPubKey.toByteArray() // force close always send to the default script
        ).right ?: "unknown"
        return buildList {
            if (channelBalance > 0.msat) {
                // this is a force close, the closing tx is a commit tx
                // since force close scenarios may be complicated with multiple layers of transactions, we estimate global fees by listing all the final outputs
                // going to us, and subtracting that from the current balance
                add(
                    ChannelAction.Storage.StoreOutgoingPayment.ViaClose(
                        amount = channelBalance.truncateToSatoshi(),
                        miningFee = 0.sat, // TODO: mining fees are tricky in force close scenario, we just lump everything in the amount field
                        address = address,
                        txId = commitTx.txid,
                        isSentToDefaultAddress = true, // force close always send to the default script
                        closingType = closingType
                    )
                )
            }
        }
    }

    internal fun ChannelContext.unhandled(cmd: ChannelCommand): Pair<ChannelState, List<ChannelAction>> {
        when (cmd) {
            is ChannelCommand.MessageReceived -> logger.warning { "unhandled message ${cmd.message::class.simpleName} in state ${this@ChannelState::class.simpleName}" }
            is ChannelCommand.WatchReceived -> logger.warning { "unhandled watch event ${cmd.watch::class.simpleName} in state ${this@ChannelState::class.simpleName}" }
            else -> logger.warning { "unhandled command ${cmd::class.simpleName} in state ${this@ChannelState::class.simpleName}" }
        }
        return Pair(this@ChannelState, listOf())
    }

    internal fun ChannelContext.handleCommandError(cmd: ChannelCommand, error: ChannelException, channelUpdate: ChannelUpdate? = null): Pair<ChannelState, List<ChannelAction>> {
        logger.warning(error) { "processing command ${cmd::class.simpleName} in state ${this@ChannelState::class.simpleName} failed" }
        return when (cmd) {
            is ChannelCommand.Htlc.Add -> Pair(this@ChannelState, listOf(ChannelAction.ProcessCmdRes.AddFailed(cmd, error, channelUpdate)))
            else -> Pair(this@ChannelState, listOf(ChannelAction.ProcessCmdRes.NotExecuted(cmd, error)))
        }
    }

    internal fun ChannelContext.doPublish(tx: Transactions.ClosingTx, channelId: ByteVector32): List<ChannelAction.Blockchain> = listOf(
        ChannelAction.Blockchain.PublishTx(tx),
        ChannelAction.Blockchain.SendWatch(WatchConfirmed(channelId, tx.tx, staticParams.nodeParams.minDepthBlocks, WatchConfirmed.ClosingTxConfirmed))
    )

    internal suspend fun ChannelContext.handleLocalError(cmd: ChannelCommand, t: Throwable): Pair<ChannelState, List<ChannelAction>> {
        when (cmd) {
            is ChannelCommand.MessageReceived -> logger.error(t) { "error on message ${cmd.message::class.simpleName}" }
            is ChannelCommand.WatchReceived -> logger.error { "error on watch event ${cmd.watch::class.simpleName}" }
            else -> logger.error(t) { "error on command ${cmd::class.simpleName}" }
        }

        fun abort(channelId: ByteVector32?, state: ChannelState): Pair<ChannelState, List<ChannelAction>> {
            val actions = buildList {
                channelId
                    ?.let { Error(it, t.message) }
                    ?.let { add(ChannelAction.Message.Send(it)) }
                (state as? PersistedChannelState)
                    ?.let { add(ChannelAction.Storage.RemoveChannel(state)) }
            }
            return Pair(Aborted, actions)
        }

        suspend fun forceClose(state: ChannelStateWithCommitments): Pair<ChannelState, List<ChannelAction>> {
            val error = Error(state.channelId, t.message)
            return state.run { spendLocalCurrent().run { copy(second = second + ChannelAction.Message.Send(error)) } }
        }

        return when (val state = this@ChannelState) {
            is WaitForInit -> abort(null, state)
            is WaitForOpenChannel -> abort(state.temporaryChannelId, state)
            is WaitForAcceptChannel -> abort(state.temporaryChannelId, state)
            is WaitForFundingCreated -> abort(state.channelId, state)
            is WaitForFundingSigned -> abort(state.channelId, state)
            is WaitForFundingConfirmed -> forceClose(state)
            is WaitForChannelReady -> forceClose(state)
            is Normal -> forceClose(state)
            is ShuttingDown -> forceClose(state)
            is Negotiating -> when {
                state.publishedClosingTxs.isNotEmpty() -> {
                    // If we were in the process of closing and already received a closing sig from the counterparty, it's always better to use that.
                    val nextState = Closing(
                        state.commitments,
                        waitingSinceBlock = state.waitingSinceBlock,
                        mutualCloseProposed = state.proposedClosingTxs.flatMap { it.all },
                        mutualClosePublished = state.publishedClosingTxs
                    )
                    Pair(nextState, listOf(ChannelAction.Storage.StoreState(nextState)))
                }
                else -> forceClose(state)
            }
            is Closing -> {
                if (state.mutualClosePublished.isNotEmpty()) {
                    // we already have published a mutual close tx, it's always better to use that
                    Pair(state, emptyList())
                } else {
                    state.localCommitPublished?.let {
                        // we're already trying to claim our commitment, there's nothing more we can do
                        Pair(state, emptyList())
                    } ?: state.run { spendLocalCurrent() }
                }
            }
            is Closed -> Pair(state, emptyList())
            is Aborted -> Pair(state, emptyList())
            is Offline -> state.run { handleLocalError(cmd, t) }
            is Syncing -> state.run { handleLocalError(cmd, t) }
            is WaitForRemotePublishFutureCommitment -> Pair(state, emptyList())
        }
    }

    suspend fun ChannelContext.handleRemoteError(e: Error): Pair<ChannelState, List<ChannelAction>> {
        // see BOLT 1: only print out data verbatim if is composed of printable ASCII characters
        logger.error { "peer sent error: ascii='${e.toAscii()}' bin=${e.data.toHex()}" }
        return when (this@ChannelState) {
            is Closing -> Pair(this@ChannelState, listOf()) // nothing to do, there is already a spending tx published
            is Negotiating -> when {
                publishedClosingTxs.isNotEmpty() -> {
                    // If we were in the process of closing and already received a closing sig from the counterparty, it's always better to use that.
                    val nexState = Closing(
                        commitments = commitments,
                        waitingSinceBlock = waitingSinceBlock,
                        mutualCloseProposed = proposedClosingTxs.flatMap { it.all },
                        mutualClosePublished = publishedClosingTxs
                    )
                    Pair(nexState, listOf(ChannelAction.Storage.StoreState(nexState)))
                }
                else -> this.spendLocalCurrent()
            }
            is WaitForFundingSigned -> Pair(Aborted, listOf(ChannelAction.Storage.RemoveChannel(this@ChannelState)))
            // NB: we publish the commitment even if we have nothing at stake (in a dataloss situation our peer will send us an error just for that)
            is ChannelStateWithCommitments -> this.spendLocalCurrent()
            // when there is no commitment yet, we just go to CLOSED state in case an error occurs
            else -> Pair(Aborted, listOf())
        }
    }

    val stateName: String
        get() = when (this) {
            is Offline -> "${this::class.simpleName}(${this.state::class.simpleName})"
            is Syncing -> "${this::class.simpleName}(${this.state::class.simpleName})"
            else -> "${this::class.simpleName}"
        }
}

/** A channel state that is persisted to the DB. */
sealed class PersistedChannelState : ChannelState() {
    abstract val channelId: ByteVector32
    abstract val fundingTxId: TxId
    abstract val fundingTxIndex: Long

    fun ChannelContext.channelKeys(): ChannelKeys = when (val state = this@PersistedChannelState) {
        is WaitForFundingSigned -> state.channelParams.localParams.channelKeys(keyManager)
        is ChannelStateWithCommitments -> state.commitments.channelKeys(keyManager)
    }

    internal fun ChannelContext.createChannelReestablish(): ChannelReestablish = when (val state = this@PersistedChannelState) {
        is WaitForFundingSigned -> {
            val myFirstPerCommitmentPoint = channelKeys().commitmentPoint(0)
            val nextFundingTxId = state.signingSession.fundingTxId
            val (currentCommitNonce, nextCommitNonce) = when (state.signingSession.fundingParams.commitmentFormat) {
                Transactions.CommitmentFormat.AnchorOutputs -> Pair(null, null)
                Transactions.CommitmentFormat.SimpleTaprootChannels -> {
                    val localFundingKey = channelKeys().fundingKey(fundingTxIndex = 0)
                    val remoteFundingPubKey = state.signingSession.fundingParams.remoteFundingPubkey
                    val currentCommitNonce = when (state.signingSession.localCommit) {
                        is Either.Left -> NonceGenerator.verificationNonce(nextFundingTxId, localFundingKey, remoteFundingPubKey, 0)
                        is Either.Right -> null
                    }
                    val nextCommitNonce = NonceGenerator.verificationNonce(nextFundingTxId, localFundingKey, remoteFundingPubKey, 1)
                    Pair(currentCommitNonce?.publicNonce, nextCommitNonce.publicNonce)
                }
            }
            ChannelReestablish(
                channelId = channelId,
                nextLocalCommitmentNumber = state.signingSession.nextLocalCommitmentNumber,
                nextRemoteRevocationNumber = 0,
                yourLastCommitmentSecret = PrivateKey(ByteVector32.Zeroes),
                myCurrentPerCommitmentPoint = myFirstPerCommitmentPoint,
                nextCommitNonces = nextCommitNonce?.let { listOf(nextFundingTxId to it) } ?: listOf(),
                nextFundingTxId = nextFundingTxId,
                currentCommitNonce = currentCommitNonce
            )
        }
        is ChannelStateWithCommitments -> {
            val channelKeys = channelKeys()
            val yourLastPerCommitmentSecret = state.commitments.remotePerCommitmentSecrets.lastIndex?.let { state.commitments.remotePerCommitmentSecrets.getHash(it) } ?: ByteVector32.Zeroes
            val myCurrentPerCommitmentPoint = channelKeys.commitmentPoint(state.commitments.localCommitIndex)
            // If we disconnected while signing a funding transaction, we may need our peer to retransmit their commit_sig.
            val nextLocalCommitmentNumber = when (state) {
                is WaitForFundingConfirmed -> when (state.rbfStatus) {
                    is RbfStatus.WaitingForSigs -> state.rbfStatus.session.nextLocalCommitmentNumber
                    else -> state.commitments.localCommitIndex + 1
                }
                is Normal -> when (state.spliceStatus) {
                    is SpliceStatus.WaitingForSigs -> state.spliceStatus.session.nextLocalCommitmentNumber
                    else -> state.commitments.localCommitIndex + 1
                }
                else -> state.commitments.localCommitIndex + 1
            }
            // If we disconnected while signing a funding transaction, we may need our peer to (re)transmit their tx_signatures.
            val unsignedFundingTxId = when (state) {
                is WaitForFundingConfirmed -> state.getUnsignedFundingTxId()
                is Normal -> state.getUnsignedFundingTxId()
                else -> null
            }
            // We send our verification nonces for all active commitments.
            val nextCommitNonces = state.commitments.active.mapNotNull { c ->
                when (c.commitmentFormat) {
                    Transactions.CommitmentFormat.AnchorOutputs -> null
                    Transactions.CommitmentFormat.SimpleTaprootChannels -> {
                        val localFundingKey = channelKeys.fundingKey(c.fundingTxIndex)
                        val localCommitNonce = NonceGenerator.verificationNonce(c.fundingTxId, localFundingKey, c.remoteFundingPubkey, c.localCommit.index + 1)
                        c.fundingTxId to localCommitNonce.publicNonce
                    }
                }
            }
            // If an interactive-tx session hasn't been fully signed, we also need to include the corresponding nonces.
            val (interactiveTxCurrentCommitNonce, interactiveTxNextCommitNonce) = run {
                val signingSession = when {
                    state is WaitForFundingConfirmed && state.rbfStatus is RbfStatus.WaitingForSigs -> state.rbfStatus.session
                    state is Normal && state.spliceStatus is SpliceStatus.WaitingForSigs -> state.spliceStatus.session
                    else -> null
                }
                when (signingSession?.fundingParams?.commitmentFormat) {
                    null -> Pair(null, null)
                    Transactions.CommitmentFormat.AnchorOutputs -> Pair(null, null)
                    Transactions.CommitmentFormat.SimpleTaprootChannels -> {
                        val currentCommitNonce = signingSession.currentCommitNonce(channelKeys)?.publicNonce
                        val nextCommitNonce = signingSession.nextCommitNonce(channelKeys).publicNonce
                        Pair(currentCommitNonce, signingSession.fundingTxId to nextCommitNonce)
                    }
                }
            }
            ChannelReestablish(
                channelId = channelId,
                nextLocalCommitmentNumber = nextLocalCommitmentNumber,
                nextRemoteRevocationNumber = state.commitments.remoteCommitIndex,
                yourLastCommitmentSecret = PrivateKey(yourLastPerCommitmentSecret),
                myCurrentPerCommitmentPoint = myCurrentPerCommitmentPoint,
                nextCommitNonces = nextCommitNonces + listOfNotNull(interactiveTxNextCommitNonce),
                nextFundingTxId = unsignedFundingTxId,
                currentCommitNonce = interactiveTxCurrentCommitNonce,
            )
        }
    }

    companion object {
        // this companion object is used by static extended function `fun PersistedChannelState.Companion.from` in Encryption.kt
    }
}

sealed class ChannelStateWithCommitments : PersistedChannelState() {
    abstract val commitments: Commitments
    // Remote nonces that must be used when signing the next remote commitment transaction (one per active commitment).
    abstract val remoteNextCommitNonces: Map<TxId, IndividualNonce>
    override val channelId: ByteVector32 get() = commitments.channelId
    override val fundingTxId: TxId get() = commitments.latest.fundingTxId
    override val fundingTxIndex: Long get() = commitments.latest.fundingTxIndex
    val isChannelOpener: Boolean get() = commitments.channelParams.localParams.isChannelOpener
    val paysCommitTxFees: Boolean get() = commitments.channelParams.localParams.paysCommitTxFees
    val remoteNodeId: PublicKey get() = commitments.remoteNodeId

    abstract fun updateCommitments(input: Commitments): ChannelStateWithCommitments

    /**
     * When a funding transaction confirms, we can prune previous commitments.
     * We also watch this funding transaction to be able to detect force-close attempts.
     */
    internal fun ChannelContext.acceptFundingTxConfirmed(w: WatchConfirmedTriggered): Either<Commitments, Triple<Commitments, Commitment, List<ChannelAction>>> {
        logger.info { "funding txid=${w.tx.txid} was confirmed at blockHeight=${w.blockHeight} txIndex=${w.txIndex}" }
        return commitments.run {
            updateLocalFundingConfirmed(w.tx, w.blockHeight, w.txIndex).map { (commitments1, commitment) ->
                val commitInput = commitment.commitInput(channelKeys())
                val watchSpent = WatchSpent(channelId, commitment.fundingTxId, commitInput.outPoint.index.toInt(), commitInput.txOut.publicKeyScript, WatchSpent.ChannelSpent(commitment.fundingAmount))
                val actions = buildList {
                    newlyLocked(commitments, commitments1).forEach { add(ChannelAction.Storage.SetLocked(it.fundingTxId)) }
                    add(ChannelAction.Blockchain.SendWatch(watchSpent))
                }
                Triple(commitments1, commitment, actions)
            }
        }
    }

    internal fun ChannelContext.createChannelReady(): ChannelReady {
        val localFundingKey = channelKeys().fundingKey(fundingTxIndex = 0)
        val remoteFundingKey = commitments.latest.remoteFundingPubkey
        val nextPerCommitmentPoint = channelKeys().commitmentPoint(1)
        val nextCommitNonce = NonceGenerator.verificationNonce(commitments.latest.fundingTxId, localFundingKey, remoteFundingKey, commitIndex = 1)
        return ChannelReady(channelId, nextPerCommitmentPoint, ShortChannelId.peerId(staticParams.nodeParams.nodeId), nextCommitNonce.publicNonce)
    }

    /**
     * Default handler when a funding transaction confirms.
     */
    internal fun ChannelContext.updateFundingTxStatus(w: WatchConfirmedTriggered): Pair<ChannelStateWithCommitments, List<ChannelAction>> {
        return when (val res = acceptFundingTxConfirmed(w)) {
            is Either.Left -> Pair(this@ChannelStateWithCommitments, listOf())
            is Either.Right -> {
                val (commitments1, _, actions) = res.value
                val nextState = this@ChannelStateWithCommitments.updateCommitments(commitments1)
                Pair(nextState, actions + listOf(ChannelAction.Storage.StoreState(nextState)))
            }
        }
    }

    /**
     * List [Commitment] that have been locked by both sides for the first time. It is more complicated that it may seem, because:
     * - remote will re-emit splice_locked at reconnection
     * - a splice_locked implicitly applies to all previous splices, and they may be pruned instantly
     */
    internal fun ChannelContext.newlyLocked(before: Commitments, after: Commitments): List<Commitment> {
        val lastLockedBefore = before.run { lastLocked() }?.fundingTxIndex ?: -1
        val lastLockedAfter = after.run { lastLocked() }?.fundingTxIndex ?: -1
        return commitments.all.filter { it.fundingTxIndex > 0 && it.fundingTxIndex > lastLockedBefore && it.fundingTxIndex <= lastLockedAfter }
    }

    internal fun ChannelContext.startClosingNegotiation(
        cmd: ChannelCommand.Close.MutualClose?,
        commitments: Commitments,
        localShutdown: Shutdown,
        localCloseeNonce: Transactions.LocalNonce?,
        remoteShutdown: Shutdown,
        actions: List<ChannelAction>,
    ): Pair<Negotiating, List<ChannelAction>> {
        val localScript = localShutdown.scriptPubKey
        val remoteScript = remoteShutdown.scriptPubKey
        val remoteCloseeNonce = remoteShutdown.closeeNonce
        val currentHeight = currentBlockHeight.toLong()
        return when (cmd) {
            null -> {
                logger.info { "mutual close was initiated by our peer, waiting for remote closing_complete" }
                val nextState = Negotiating(
                    commitments,
                    localScript,
                    remoteScript,
                    listOf(),
                    listOf(),
                    currentHeight,
                    cmd,
                    localCloseeNonce,
                    remoteCloseeNonce,
                    localCloserNonces = null
                )
                val actions1 = listOf(ChannelAction.Storage.StoreState(nextState))
                Pair(nextState, actions + actions1)
            }
            else -> {
                when (val closingResult = Helpers.Closing.makeClosingTxs(channelKeys(), commitments.latest, localScript, remoteScript, cmd.feerate, currentHeight, remoteCloseeNonce)) {
                    is Either.Left -> {
                        logger.warning { "cannot create local closing txs, waiting for remote closing_complete: ${closingResult.value.message}" }
                        cmd.replyTo.complete(ChannelCloseResponse.Failure.Unknown(closingResult.value))
                        val nextState = Negotiating(
                            commitments,
                            localScript,
                            remoteScript,
                            listOf(),
                            listOf(),
                            currentHeight,
                            cmd,
                            localCloseeNonce,
                            remoteCloseeNonce,
                            localCloserNonces = null
                        )
                        val actions1 = listOf(ChannelAction.Storage.StoreState(nextState))
                        Pair(nextState, actions + actions1)
                    }
                    is Either.Right -> {
                        val (closingTxs, closingComplete, localCloserNonces) = closingResult.value
                        val nextState =
                            Negotiating(
                                commitments,
                                localScript,
                                remoteScript,
                                listOf(closingTxs),
                                listOf(),
                                currentHeight,
                                cmd,
                                localCloseeNonce,
                                remoteCloseeNonce,
                                localCloserNonces
                            )
                        val actions1 = listOf(
                            ChannelAction.Storage.StoreState(nextState),
                            ChannelAction.Message.Send(closingComplete),
                        )
                        Pair(nextState, actions + actions1)
                    }
                }
            }
        }
    }

    /**
     * Analyze and react to a potential force-close transaction spending one of our funding transactions.
     */
    internal suspend fun ChannelContext.handlePotentialForceClose(w: WatchSpentTriggered): Pair<ChannelStateWithCommitments, List<ChannelAction>> = when {
        w.event !is WatchSpent.ChannelSpent -> Pair(this@ChannelStateWithCommitments, listOf())
        commitments.all.any { it.fundingTxId == w.spendingTx.txid } -> Pair(this@ChannelStateWithCommitments, listOf()) // if the spending tx is itself a funding tx, this is a splice and there is nothing to do
        w.spendingTx.txid == commitments.latest.localCommit.txId -> spendLocalCurrent()
        w.spendingTx.txid == commitments.latest.remoteCommit.txid -> handleRemoteSpentCurrent(w.spendingTx, commitments.latest)
        w.spendingTx.txid == commitments.latest.nextRemoteCommit?.txid -> handleRemoteSpentNext(w.spendingTx, commitments.latest)
        w.spendingTx.txIn.any { it.outPoint == commitments.latest.fundingInput } -> handleRemoteSpentOther(w.spendingTx)
        else -> when (val commitment = commitments.resolveCommitment(w.spendingTx)) {
            is Commitment -> {
                logger.warning { "a commit tx for an older commitment has been published fundingTxId=${commitment.fundingTxId} fundingTxIndex=${commitment.fundingTxIndex}" }
                // We try spending our latest commitment but we also watch their commitment: if it confirms, we will react by spending our corresponding outputs.
                val watch = ChannelAction.Blockchain.SendWatch(WatchConfirmed(channelId, w.spendingTx, staticParams.nodeParams.minDepthBlocks, WatchConfirmed.AlternativeCommitTxConfirmed))
                spendLocalCurrent().run { copy(second = second + watch) }
            }
            else -> {
                logger.warning { "unrecognized tx=${w.spendingTx.txid}" }
                // This case can happen in the following (harmless) scenario:
                //  - we create and publish a splice transaction, then we go offline
                //  - the transaction confirms while we are offline
                //  - we restart and set a watch-confirmed for the splice transaction and a watch-spent for the previous funding transaction
                //  - the watch-confirmed triggers first and we prune the previous funding transaction
                //  - the watch-spent for the previous funding transaction triggers because of the splice transaction
                //  - but we've already pruned the corresponding commitment: we should simply ignore the event
                Pair(this@ChannelStateWithCommitments, listOf())
            }
        }
    }

    internal suspend fun ChannelContext.handleRemoteSpentCurrent(commitTx: Transaction, commitment: FullCommitment): Pair<Closing, List<ChannelAction>> {
        logger.warning { "they published their current commit in txid=${commitTx.txid}" }
        require(commitTx.txid == commitment.remoteCommit.txid) { "txid mismatch" }
        val (remoteCommitPublished, closingTxs) = Helpers.Closing.RemoteClose.run {
            claimCommitTxOutputs(channelKeys(), commitment, commitment.remoteCommit, commitTx, currentOnChainFeerates())
        }
        val nextState = when (this@ChannelStateWithCommitments) {
            is Closing -> this@ChannelStateWithCommitments.copy(remoteCommitPublished = remoteCommitPublished)
            is Negotiating -> Closing(
                commitments = commitments,
                waitingSinceBlock = waitingSinceBlock,
                mutualCloseProposed = proposedClosingTxs.flatMap { it.all },
                mutualClosePublished = publishedClosingTxs,
                remoteCommitPublished = remoteCommitPublished
            )
            else -> Closing(
                commitments = commitments,
                waitingSinceBlock = currentBlockHeight.toLong(),
                remoteCommitPublished = remoteCommitPublished
            )
        }
        return Pair(nextState, buildList {
            add(ChannelAction.Storage.StoreState(nextState))
            addAll(remoteCommitPublished.run { doPublish(staticParams.nodeParams, channelId, closingTxs) })
        })
    }

    internal suspend fun ChannelContext.handleRemoteSpentNext(commitTx: Transaction, commitment: FullCommitment): Pair<ChannelStateWithCommitments, List<ChannelAction>> {
        logger.warning { "they published their next commit in txid=${commitTx.txid}" }
        require(commitment.nextRemoteCommit != null) { "next remote commit must be defined" }
        val remoteCommit = commitment.nextRemoteCommit
        require(commitTx.txid == remoteCommit.txid) { "txid mismatch" }
        val (remoteCommitPublished, closingTxs) = Helpers.Closing.RemoteClose.run {
            claimCommitTxOutputs(channelKeys(), commitment, remoteCommit, commitTx, currentOnChainFeerates())
        }
        val nextState = when (this@ChannelStateWithCommitments) {
            is Closing -> copy(nextRemoteCommitPublished = remoteCommitPublished)
            is Negotiating -> Closing(
                commitments = commitments,
                waitingSinceBlock = waitingSinceBlock,
                mutualCloseProposed = proposedClosingTxs.flatMap { it.all },
                mutualClosePublished = publishedClosingTxs,
                nextRemoteCommitPublished = remoteCommitPublished
            )
            else -> Closing(
                commitments = commitments,
                waitingSinceBlock = currentBlockHeight.toLong(),
                nextRemoteCommitPublished = remoteCommitPublished
            )
        }
        return Pair(nextState, buildList {
            add(ChannelAction.Storage.StoreState(nextState))
            addAll(remoteCommitPublished.run { doPublish(staticParams.nodeParams, channelId, closingTxs) })
        })
    }

    internal suspend fun ChannelContext.handleRemoteSpentOther(tx: Transaction): Pair<ChannelStateWithCommitments, List<ChannelAction>> {
        logger.warning { "funding tx spent in txid=${tx.txid}" }
        return Helpers.Closing.RevokedClose.getRemotePerCommitmentSecret(commitments.channelParams, channelKeys(), commitments.remotePerCommitmentSecrets, tx)?.let { (remotePerCommitmentSecret, commitmentNumber) ->
            logger.warning { "txId=${tx.txid} was a revoked commitment for commitmentNumber=$commitmentNumber, publishing penalty transactions" }
            val (revokedCommitPublished, closingTxs) = Helpers.Closing.RevokedClose.run {
                // TODO: once we allow changing the commitment format or to_self_delay during a splice, those values may be incorrect.
                val toSelfDelay = commitments.latest.remoteCommitParams.toSelfDelay
                val commitmentFormat = commitments.latest.commitmentFormat
                val dustLimit = commitments.latest.localCommitParams.dustLimit
                claimCommitTxOutputs(commitments.channelParams, channelKeys(), tx, remotePerCommitmentSecret, dustLimit, toSelfDelay, commitmentFormat, currentOnChainFeerates())
            }
            val ex = FundingTxSpent(channelId, tx.txid)
            val error = Error(channelId, ex.message)
            val nextState = when (this@ChannelStateWithCommitments) {
                is Closing -> if (this@ChannelStateWithCommitments.revokedCommitPublished.contains(revokedCommitPublished)) {
                    this@ChannelStateWithCommitments
                } else {
                    this@ChannelStateWithCommitments.copy(revokedCommitPublished = this@ChannelStateWithCommitments.revokedCommitPublished + revokedCommitPublished)
                }
                is Negotiating -> Closing(
                    commitments = commitments,
                    waitingSinceBlock = waitingSinceBlock,
                    mutualCloseProposed = proposedClosingTxs.flatMap { it.all },
                    mutualClosePublished = publishedClosingTxs,
                    revokedCommitPublished = listOf(revokedCommitPublished)
                )
                else -> Closing(
                    commitments = commitments,
                    waitingSinceBlock = currentBlockHeight.toLong(),
                    revokedCommitPublished = listOf(revokedCommitPublished)
                )
            }
            Pair(nextState, buildList {
                add(ChannelAction.Storage.StoreState(nextState))
                addAll(revokedCommitPublished.run { doPublish(staticParams.nodeParams, channelId, closingTxs) })
                add(ChannelAction.Message.Send(error))
                add(ChannelAction.Storage.GetHtlcInfos(revokedCommitPublished.commitTx.txid, commitmentNumber))
            })
        } ?: run {
            when (this@ChannelStateWithCommitments) {
                is WaitForRemotePublishFutureCommitment -> {
                    logger.warning { "they published their future commit (because we asked them to) in txid=${tx.txid}" }
                    val commitKeys = channelKeys().remoteCommitmentKeys(commitments.channelParams, remoteChannelReestablish.myCurrentPerCommitmentPoint)
                    val mainTx = Helpers.Closing.RemoteClose.run {
                        claimMainOutput(
                            commitKeys,
                            tx,
                            commitments.latest.localCommitParams.dustLimit,
                            commitments.latest.commitmentFormat,
                            commitments.channelParams.localParams.defaultFinalScriptPubKey,
                            currentOnChainFeerates().claimMainFeerate
                        )
                    }
                    mainTx?.let { logger.warning { "our recovery transaction is tx=${it.tx}" } }
                    val remoteCommitPublished = RemoteCommitPublished(
                        commitTx = tx,
                        localOutput = mainTx?.input?.outPoint,
                        anchorOutput = null,
                        incomingHtlcs = mapOf(),
                        outgoingHtlcs = mapOf(),
                        irrevocablySpent = mapOf(),
                    )
                    val nextState = Closing(
                        commitments = commitments,
                        waitingSinceBlock = currentBlockHeight.toLong(),
                        futureRemoteCommitPublished = remoteCommitPublished,
                    )
                    Pair(nextState, buildList {
                        add(ChannelAction.Storage.StoreState(nextState))
                        addAll(remoteCommitPublished.run { doPublish(staticParams.nodeParams, channelId, RemoteCommitSecondStageTransactions(mainTx, listOf())) })
                    })
                }
                else -> {
                    // Our peer may publish an alternative version of their commitment using a different feerate.
                    when (val remoteCommit = Commitments.alternativeFeerateCommits(commitments, channelKeys()).find { it.txid == tx.txid }) {
                        null -> {
                            logger.warning { "unrecognized tx=${tx.txid}" }
                            // This can happen if the user has two devices.
                            // - user creates a wallet on device #1
                            // - user restores the same wallet on device #2
                            // - user does a splice on device #2
                            // - user starts wallet on device #1
                            // The wallet on device #1 has a previous version of the channel, it is not aware of the splice tx. It won't be able
                            // to recognize the tx when the watcher notifies that the (old) funding tx was spent.
                            // However, there is a race with the reconnection logic, because then the device #1 will recover its latest state from the
                            // remote backup.
                            // So, the best thing to do here is to ignore the spending tx.
                            Pair(this@ChannelStateWithCommitments, listOf())
                        }
                        else -> {
                            logger.warning { "they published an alternative commitment with feerate=${remoteCommit.spec.feerate} txid=${tx.txid}" }
                            // We only provide alternative feerate signatures when there are no pending HTLCs: we only need to claim our main output.
                            val commitKeys = channelKeys().remoteCommitmentKeys(commitments.channelParams, remoteCommit.remotePerCommitmentPoint)
                            val mainTx = Helpers.Closing.RemoteClose.run {
                                claimMainOutput(
                                    commitKeys,
                                    tx,
                                    commitments.latest.localCommitParams.dustLimit,
                                    commitments.latest.commitmentFormat,
                                    commitments.channelParams.localParams.defaultFinalScriptPubKey,
                                    currentOnChainFeerates().claimMainFeerate
                                )
                            }
                            val remoteCommitPublished = RemoteCommitPublished(
                                commitTx = tx,
                                localOutput = mainTx?.input?.outPoint,
                                anchorOutput = null,
                                incomingHtlcs = mapOf(),
                                outgoingHtlcs = mapOf(),
                                irrevocablySpent = mapOf(),
                            )
                            val nextState = when (this@ChannelStateWithCommitments) {
                                is Closing -> this@ChannelStateWithCommitments.copy(remoteCommitPublished = remoteCommitPublished)
                                is Negotiating -> Closing(commitments, waitingSinceBlock, proposedClosingTxs.flatMap { it.all }, publishedClosingTxs, remoteCommitPublished = remoteCommitPublished)
                                else -> Closing(commitments, waitingSinceBlock = currentBlockHeight.toLong(), remoteCommitPublished = remoteCommitPublished)
                            }
                            return Pair(nextState, buildList {
                                add(ChannelAction.Storage.StoreState(nextState))
                                addAll(remoteCommitPublished.run { doPublish(staticParams.nodeParams, channelId, RemoteCommitSecondStageTransactions(mainTx, listOf())) })
                            })
                        }
                    }
                }
            }
        }
    }

    internal suspend fun ChannelContext.spendLocalCurrent(): Pair<ChannelStateWithCommitments, List<ChannelAction>> {
        val outdatedCommitment = when (this@ChannelStateWithCommitments) {
            is WaitForRemotePublishFutureCommitment -> true
            is Closing -> this@ChannelStateWithCommitments.futureRemoteCommitPublished != null
            else -> false
        }
        return if (outdatedCommitment) {
            logger.warning { "we have an outdated commitment: will not publish our local tx" }
            Pair(this@ChannelStateWithCommitments, listOf())
        } else {
            val commitTx = commitments.latest.fullySignedCommitTx(channelKeys())
            val (localCommitPublished, closingTxs) = Helpers.Closing.LocalClose.run {
                claimCommitTxOutputs(channelKeys(), commitments.latest, commitTx, currentOnChainFeerates())
            }
            val nextState = when (this@ChannelStateWithCommitments) {
                is Closing -> copy(localCommitPublished = localCommitPublished)
                is Negotiating -> Closing(
                    commitments = commitments,
                    waitingSinceBlock = waitingSinceBlock,
                    mutualCloseProposed = proposedClosingTxs.flatMap { it.all },
                    mutualClosePublished = publishedClosingTxs,
                    localCommitPublished = localCommitPublished
                )
                else -> Closing(
                    commitments = commitments,
                    waitingSinceBlock = currentBlockHeight.toLong(),
                    localCommitPublished = localCommitPublished
                )
            }
            Pair(nextState, buildList {
                add(ChannelAction.Storage.StoreState(nextState))
                addAll(localCommitPublished.run { doPublish(staticParams.nodeParams, channelId, closingTxs) })
            })
        }
    }

    /**
     * Check HTLC timeout in our commitment and our remote's.
     * If HTLCs are at risk, we will publish our local commitment and close the channel.
     */
    internal suspend fun ChannelContext.checkHtlcTimeout(): Pair<ChannelStateWithCommitments, List<ChannelAction>> {
        logger.info { "checking htlcs timeout at blockHeight=${currentBlockHeight}" }
        val timedOutOutgoing = commitments.timedOutOutgoingHtlcs(currentBlockHeight.toLong())
        val almostTimedOutIncoming = commitments.almostTimedOutIncomingHtlcs(currentBlockHeight.toLong(), staticParams.nodeParams.fulfillSafetyBeforeTimeoutBlocks)
        val channelEx: ChannelException? = when {
            timedOutOutgoing.isNotEmpty() -> HtlcsTimedOutDownstream(channelId, timedOutOutgoing)
            almostTimedOutIncoming.isNotEmpty() -> FulfilledHtlcsWillTimeout(channelId, almostTimedOutIncoming)
            else -> null
        }
        return when (channelEx) {
            null -> Pair(this@ChannelStateWithCommitments, listOf())
            else -> {
                logger.error { channelEx.message }
                when {
                    this@ChannelStateWithCommitments is Closing -> Pair(this@ChannelStateWithCommitments, listOf()) // nothing to do, there is already a spending tx published
                    else -> {
                        val error = Error(channelId, channelEx.message)
                        spendLocalCurrent().run { copy(second = second + ChannelAction.Message.Send(error)) }
                    }
                }
            }
        }
    }
}

object Channel {
    // https://github.com/lightningnetwork/lightning-rfc/blob/master/02-peer-protocol.md#requirements
    const val MAX_ACCEPTED_HTLCS = 483

    // We may need to rely on our peer's commit tx in certain cases (backup/restore) so we must ensure their transactions
    // can propagate through the bitcoin network (assuming bitcoin core nodes with default policies).
    // The various dust limits enforced by the bitcoin network are summarized here:
    // https://github.com/lightningnetwork/lightning-rfc/blob/master/03-transactions.md#dust-limits
    // A dust limit of 354 sat ensures all segwit outputs will relay with default relay policies.
    val MIN_DUST_LIMIT = 354.sat

    // this is defined in BOLT 11
    val MIN_CLTV_EXPIRY_DELTA = CltvExpiryDelta(18)
    val MAX_CLTV_EXPIRY_DELTA = CltvExpiryDelta(2 * 7 * 144) // two weeks

    // since BOLT 1.1, there is a max value for the refund delay of the main commitment tx
    val MAX_TO_SELF_DELAY = CltvExpiryDelta(2016)
}
