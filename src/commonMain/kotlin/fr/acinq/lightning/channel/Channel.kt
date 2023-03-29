package fr.acinq.lightning.channel

import fr.acinq.bitcoin.*
import fr.acinq.lightning.*
import fr.acinq.lightning.blockchain.*
import fr.acinq.lightning.blockchain.electrum.WalletState
import fr.acinq.lightning.blockchain.fee.FeeratePerKw
import fr.acinq.lightning.blockchain.fee.OnChainFeerates
import fr.acinq.lightning.channel.Helpers.Closing.claimCurrentLocalCommitTxOutputs
import fr.acinq.lightning.channel.Helpers.Closing.claimRemoteCommitMainOutput
import fr.acinq.lightning.channel.Helpers.Closing.claimRemoteCommitTxOutputs
import fr.acinq.lightning.channel.Helpers.Closing.claimRevokedRemoteCommitTxOutputs
import fr.acinq.lightning.channel.Helpers.Closing.getRemotePerCommitmentSecret
import fr.acinq.lightning.crypto.KeyManager
import fr.acinq.lightning.db.LightningOutgoingPayment
import fr.acinq.lightning.serialization.Encryption.from
import fr.acinq.lightning.transactions.Transactions
import fr.acinq.lightning.transactions.Transactions.TransactionWithInputInfo.*
import fr.acinq.lightning.utils.*
import fr.acinq.lightning.wire.*

/*
 * Channel is implemented as a finite state machine
 * Its main method is (State, Event) -> (State, List<Action>)
 */

/** Channel Events (inputs to be fed to the state machine). */
sealed class ChannelCommand {
    data class InitInitiator(
        val fundingAmount: Satoshi,
        val pushAmount: MilliSatoshi,
        val wallet: WalletState,
        val commitTxFeerate: FeeratePerKw,
        val fundingTxFeerate: FeeratePerKw,
        val localParams: LocalParams,
        val remoteInit: Init,
        val channelFlags: Byte,
        val channelConfig: ChannelConfig,
        val channelType: ChannelType.SupportedChannelType,
        val channelOrigin: Origin? = null
    ) : ChannelCommand() {
        fun temporaryChannelId(keyManager: KeyManager): ByteVector32 = keyManager.channelKeys(localParams.fundingKeyPath).temporaryChannelId
    }

    data class InitNonInitiator(
        val temporaryChannelId: ByteVector32,
        val fundingAmount: Satoshi,
        val pushAmount: MilliSatoshi,
        val wallet: WalletState,
        val localParams: LocalParams,
        val channelConfig: ChannelConfig,
        val remoteInit: Init
    ) : ChannelCommand()

    data class Restore(val state: PersistedChannelState) : ChannelCommand()
    object CheckHtlcTimeout : ChannelCommand()
    data class MessageReceived(val message: LightningMessage) : ChannelCommand()
    data class WatchReceived(val watch: WatchEvent) : ChannelCommand()
    data class ExecuteCommand(val command: Command) : ChannelCommand()
    data class GetHtlcInfosResponse(val revokedCommitTxId: ByteVector32, val htlcInfos: List<ChannelAction.Storage.HtlcInfo>) : ChannelCommand()
    object Disconnected : ChannelCommand()
    data class Connected(val localInit: Init, val remoteInit: Init) : ChannelCommand()
}

/** Channel Actions (outputs produced by the state machine). */
sealed class ChannelAction {

    data class ProcessLocalError(val error: Throwable, val trigger: ChannelCommand) : ChannelAction()

    sealed class Message : ChannelAction() {
        data class Send(val message: LightningMessage) : Message()
        data class SendToSelf(val command: Command) : Message()
    }

    sealed class ChannelId : ChannelAction() {
        data class IdAssigned(val remoteNodeId: PublicKey, val temporaryChannelId: ByteVector32, val channelId: ByteVector32) : ChannelId()
    }

    sealed class Blockchain : ChannelAction() {
        data class SendWatch(val watch: Watch) : Blockchain()
        data class PublishTx(val tx: Transaction, val txType: Type) : Blockchain() {
            // region txType
            enum class Type {
                FundingTx,
                CommitTx,
                HtlcSuccessTx,
                HtlcTimeoutTx,
                ClaimHtlcSuccessTx,
                ClaimHtlcTimeoutTx,
                ClaimLocalAnchorOutputTx,
                ClaimRemoteAnchorOutputTx,
                ClaimLocalDelayedOutputTx,
                ClaimRemoteDelayedOutputTx,
                MainPenaltyTx,
                HtlcPenaltyTx,
                ClaimHtlcDelayedOutputPenaltyTx,
                ClosingTx,
            }

            constructor(txinfo: Transactions.TransactionWithInputInfo) : this(
                tx = txinfo.tx,
                txType = when (txinfo) {
                    is CommitTx -> Type.CommitTx
                    is HtlcTx.HtlcSuccessTx -> Type.HtlcSuccessTx
                    is HtlcTx.HtlcTimeoutTx -> Type.HtlcTimeoutTx
                    is ClaimHtlcTx.ClaimHtlcSuccessTx -> Type.ClaimHtlcSuccessTx
                    is ClaimHtlcTx.ClaimHtlcTimeoutTx -> Type.ClaimHtlcTimeoutTx
                    is ClaimAnchorOutputTx.ClaimLocalAnchorOutputTx -> Type.ClaimLocalAnchorOutputTx
                    is ClaimAnchorOutputTx.ClaimRemoteAnchorOutputTx -> Type.ClaimRemoteAnchorOutputTx
                    is ClaimLocalDelayedOutputTx -> Type.ClaimLocalDelayedOutputTx
                    is ClaimRemoteCommitMainOutputTx.ClaimRemoteDelayedOutputTx -> Type.ClaimRemoteDelayedOutputTx
                    is MainPenaltyTx -> Type.MainPenaltyTx
                    is HtlcPenaltyTx -> Type.HtlcPenaltyTx
                    is ClaimHtlcDelayedOutputPenaltyTx -> Type.ClaimHtlcDelayedOutputPenaltyTx
                    is ClosingTx -> Type.ClosingTx
                    is SpliceTx -> Type.FundingTx
                }
            )
            // endregion
        }
    }

    sealed class Storage : ChannelAction() {
        data class StoreState(val data: PersistedChannelState) : Storage()
        data class HtlcInfo(val channelId: ByteVector32, val commitmentNumber: Long, val paymentHash: ByteVector32, val cltvExpiry: CltvExpiry)
        data class StoreHtlcInfos(val htlcs: List<HtlcInfo>) : Storage()
        data class GetHtlcInfos(val revokedCommitTxId: ByteVector32, val commitmentNumber: Long) : Storage()
        data class StoreIncomingAmount(val amount: MilliSatoshi, val localInputs: Set<OutPoint>, val origin: Origin?) : Storage()
        data class StoreChannelClosing(val amount: MilliSatoshi, val closingAddress: String, val isSentToDefaultAddress: Boolean) : Storage()
        data class StoreChannelClosed(val closingTxs: List<LightningOutgoingPayment.ClosingTxPart>) : Storage()
    }

    data class ProcessIncomingHtlc(val add: UpdateAddHtlc) : ChannelAction()

    /**
     * Process the result of executing a given command.
     * [[CMD_ADD_HTLC]] has a special treatment: there are two response patterns for this command:
     *  - either [[ProcessCmdRes.AddFailed]] immediately
     *  - or [[ProcessCmdRes.AddSettledFail]] / [[ProcessCmdRes.AddSettledFulfill]] (usually a while later)
     */
    sealed class ProcessCmdRes : ChannelAction() {
        data class NotExecuted(val cmd: Command, val t: ChannelException) : ProcessCmdRes()
        data class AddSettledFulfill(val paymentId: UUID, val htlc: UpdateAddHtlc, val result: HtlcResult.Fulfill) : ProcessCmdRes()
        data class AddSettledFail(val paymentId: UUID, val htlc: UpdateAddHtlc, val result: HtlcResult.Fail) : ProcessCmdRes()
        data class AddFailed(val cmd: CMD_ADD_HTLC, val error: ChannelException, val channelUpdate: ChannelUpdate?) : ProcessCmdRes() {
            override fun toString() = "cannot add htlc with paymentId=${cmd.paymentId} reason=${error.message}"
        }
    }

    sealed class HtlcResult {
        sealed class Fulfill : HtlcResult() {
            abstract val paymentPreimage: ByteVector32

            data class OnChainFulfill(override val paymentPreimage: ByteVector32) : Fulfill()
            data class RemoteFulfill(val fulfill: UpdateFulfillHtlc) : Fulfill() {
                override val paymentPreimage = fulfill.paymentPreimage
            }
        }

        sealed class Fail : HtlcResult() {
            data class RemoteFail(val fail: UpdateFailHtlc) : Fail()
            data class RemoteFailMalformed(val fail: UpdateFailMalformedHtlc) : Fail()
            data class OnChainFail(val cause: ChannelException) : Fail()
            object Disconnected : Fail()
        }
    }

    data class EmitEvent(val event: ChannelEvents) : ChannelAction()
}

/** Channel static parameters. */
data class StaticParams(val nodeParams: NodeParams, val remoteNodeId: PublicKey) {
    val useZeroConf: Boolean = nodeParams.zeroConfPeers.contains(remoteNodeId)
}

data class ChannelContext(
    val staticParams: StaticParams,
    val currentBlockHeight: Int,
    val currentOnChainFeerates: OnChainFeerates,
    override val logger: MDCLogger
) : LoggingContext {
    val keyManager: KeyManager get() = staticParams.nodeParams.keyManager
    val privateKey: PrivateKey get() = staticParams.nodeParams.nodePrivateKey
}

/** Channel state. */
sealed class ChannelState {

    /**
     * @param cmd input event (for example, a message was received, a command was sent by the GUI/API, etc)
     * @return a (new state, list of actions) pair
     */
    abstract fun ChannelContext.processInternal(cmd: ChannelCommand): Pair<ChannelState, List<ChannelAction>>

    fun ChannelContext.process(cmd: ChannelCommand): Pair<ChannelState, List<ChannelAction>> {
        return try {
            processInternal(cmd)
                .let { (newState, actions) -> Pair(newState, newState.run { maybeAddBackupToMessages(actions) }) }
                .let { (newState, actions) -> Pair(newState, actions + onTransition(newState)) }
        } catch (t: Throwable) {
            handleLocalError(cmd, t)
        }
    }

    /** Update outgoing messages to include an encrypted backup when necessary. */
    private fun ChannelContext.maybeAddBackupToMessages(actions: List<ChannelAction>): List<ChannelAction> = when {
        this@ChannelState is PersistedChannelState && staticParams.nodeParams.features.hasFeature(Feature.ChannelBackupClient) -> actions.map {
            when {
                it is ChannelAction.Message.Send && it.message is TxSignatures -> it.copy(message = it.message.withChannelData(EncryptedChannelData.from(privateKey, this@ChannelState), logger))
                it is ChannelAction.Message.Send && it.message is CommitSig -> it.copy(message = it.message.withChannelData(EncryptedChannelData.from(privateKey, this@ChannelState), logger))
                it is ChannelAction.Message.Send && it.message is RevokeAndAck -> it.copy(message = it.message.withChannelData(EncryptedChannelData.from(privateKey, this@ChannelState), logger))
                it is ChannelAction.Message.Send && it.message is Shutdown -> it.copy(message = it.message.withChannelData(EncryptedChannelData.from(privateKey, this@ChannelState), logger))
                it is ChannelAction.Message.Send && it.message is ClosingSigned -> it.copy(message = it.message.withChannelData(EncryptedChannelData.from(privateKey, this@ChannelState), logger))
                else -> it
            }
        }
        else -> actions
    }

    /** Add actions for some transitions */
    private fun ChannelContext.onTransition(newState: ChannelState): List<ChannelAction> {
        val oldState = when (this@ChannelState) {
            is Offline -> this@ChannelState.state
            is Syncing -> this@ChannelState.state
            else -> this@ChannelState
        }
        return when {
            oldState is WaitForFundingSigned && (newState is WaitForFundingConfirmed || newState is WaitForChannelReady) -> {
                val channelCreated = ChannelAction.EmitEvent(ChannelEvents.Created(newState as ChannelStateWithCommitments))
                when {
                    !oldState.channelParams.localParams.isInitiator -> {
                        val amount = oldState.signingSession.fundingParams.localContribution.toMilliSatoshi() + oldState.remotePushAmount - oldState.localPushAmount
                        val localInputs = oldState.signingSession.fundingTx.tx.localInputs.map { OutPoint(it.previousTx, it.previousTxOutput) }.toSet()
                        listOf(ChannelAction.Storage.StoreIncomingAmount(amount, localInputs, oldState.channelOrigin), channelCreated)
                    }
                    else -> listOf(channelCreated)
                }
            }
            // we only want to fire the PaymentSent event when we transition to Closing for the first time
            oldState is ChannelStateWithCommitments && oldState !is Closing && newState is Closing -> emitClosingEvents(oldState)
            else -> emptyList()
        }
    }

    private fun ChannelContext.emitClosingEvents(oldState: ChannelStateWithCommitments): List<ChannelAction> {
        val channelBalance = oldState.commitments.latest.localCommit.spec.toLocal
        return if (channelBalance > 0.msat) {
            val defaultScriptPubKey = oldState.commitments.params.localParams.defaultFinalScriptPubKey
            val localShutdown = when (this@ChannelState) {
                is Normal -> this@ChannelState.localShutdown
                is Negotiating -> this@ChannelState.localShutdown
                is ShuttingDown -> this@ChannelState.localShutdown
                else -> null
            }
            if (localShutdown != null && localShutdown.scriptPubKey != defaultScriptPubKey) {
                // Non-default output address
                val btcAddr = Helpers.Closing.btcAddressFromScriptPubKey(
                    scriptPubKey = localShutdown.scriptPubKey,
                    chainHash = staticParams.nodeParams.chainHash
                ) ?: "unknown"
                listOf(
                    ChannelAction.Storage.StoreChannelClosing(
                        amount = channelBalance,
                        closingAddress = btcAddr,
                        isSentToDefaultAddress = false
                    )
                )
            } else {
                // Default output address
                val btcAddr = Helpers.Closing.btcAddressFromScriptPubKey(
                    scriptPubKey = defaultScriptPubKey,
                    chainHash = staticParams.nodeParams.chainHash
                ) ?: "unknown"
                listOf(
                    ChannelAction.Storage.StoreChannelClosing(
                        amount = channelBalance,
                        closingAddress = btcAddr,
                        isSentToDefaultAddress = true
                    )
                )
            }
        } else emptyList() // balance == 0
    }

    internal fun ChannelContext.unhandled(cmd: ChannelCommand): Pair<ChannelState, List<ChannelAction>> {
        logger.warning { "unhandled command ${cmd::class.simpleName} in state ${this@ChannelState::class.simpleName}" }
        return Pair(this@ChannelState, listOf())
    }

    internal fun ChannelContext.handleCommandError(cmd: Command, error: ChannelException, channelUpdate: ChannelUpdate? = null): Pair<ChannelState, List<ChannelAction>> {
        logger.warning(error) { "processing command ${cmd::class.simpleName} in state ${this@ChannelState::class.simpleName} failed" }
        return when (cmd) {
            is CMD_ADD_HTLC -> Pair(this@ChannelState, listOf(ChannelAction.ProcessCmdRes.AddFailed(cmd, error, channelUpdate)))
            else -> Pair(this@ChannelState, listOf(ChannelAction.ProcessCmdRes.NotExecuted(cmd, error)))
        }
    }

    internal fun ChannelContext.doPublish(tx: ClosingTx, channelId: ByteVector32): List<ChannelAction.Blockchain> = listOf(
        ChannelAction.Blockchain.PublishTx(tx),
        ChannelAction.Blockchain.SendWatch(WatchConfirmed(channelId, tx.tx, staticParams.nodeParams.minDepthBlocks.toLong(), BITCOIN_TX_CONFIRMED(tx.tx)))
    )

    abstract fun ChannelContext.handleLocalError(cmd: ChannelCommand, t: Throwable): Pair<ChannelState, List<ChannelAction>>

    fun ChannelContext.handleRemoteError(e: Error): Pair<ChannelState, List<ChannelAction>> {
        // see BOLT 1: only print out data verbatim if is composed of printable ASCII characters
        logger.error { "peer sent error: ascii='${e.toAscii()}' bin=${e.data.toHex()}" }
        return when {
            this@ChannelState is Closing -> Pair(this@ChannelState, listOf()) // nothing to do, there is already a spending tx published
            this@ChannelState is Negotiating && this@ChannelState.bestUnpublishedClosingTx != null -> {
                val nexState = Closing(
                    commitments = commitments,
                    waitingSinceBlock = currentBlockHeight.toLong(),
                    mutualCloseProposed = closingTxProposed.flatten().map { it.unsignedTx },
                    mutualClosePublished = listOfNotNull(bestUnpublishedClosingTx)
                )
                Pair(nexState, buildList {
                    add(ChannelAction.Storage.StoreState(nexState))
                    addAll(doPublish(bestUnpublishedClosingTx, nexState.channelId))
                })
            }
            // NB: we publish the commitment even if we have nothing at stake (in a dataloss situation our peer will send us an error just for that)
            this@ChannelState is ChannelStateWithCommitments -> this.spendLocalCurrent()
            // when there is no commitment yet, we just go to CLOSED state in case an error occurs
            else -> Pair(Aborted, listOf())
        }
    }
}

/** A channel state that is persisted to the DB. */
sealed class PersistedChannelState : ChannelState() {
    abstract val channelId: ByteVector32

    internal fun ChannelContext.createChannelReestablish(): HasEncryptedChannelData = when (val state = this@PersistedChannelState) {
        is WaitForFundingSigned -> {
            val myFirstPerCommitmentPoint = keyManager.channelKeys(state.channelParams.localParams.fundingKeyPath).commitmentPoint(0)
            ChannelReestablish(
                channelId = channelId,
                nextLocalCommitmentNumber = 1,
                nextRemoteRevocationNumber = 0,
                yourLastCommitmentSecret = PrivateKey(ByteVector32.Zeroes),
                myCurrentPerCommitmentPoint = myFirstPerCommitmentPoint,
                TlvStream(ChannelReestablishTlv.NextFunding(state.signingSession.fundingTx.txId.reversed()))
            ).withChannelData(state.remoteChannelData, logger)
        }
        is ChannelStateWithCommitments -> {
            val yourLastPerCommitmentSecret = state.commitments.remotePerCommitmentSecrets.lastIndex?.let { state.commitments.remotePerCommitmentSecrets.getHash(it) } ?: ByteVector32.Zeroes
            val myCurrentPerCommitmentPoint = keyManager.channelKeys(state.commitments.params.localParams.fundingKeyPath).commitmentPoint(state.commitments.localCommitIndex)
            val unsignedFundingTxId = when (state) {
                is WaitForFundingConfirmed -> state.getUnsignedFundingTxId()
                is Normal -> state.getUnsignedFundingTxId() // a splice was in progress, we tell our peer that we are remembering it and are expecting signatures
                else -> null
            }
            val tlvs: TlvStream<ChannelReestablishTlv> = unsignedFundingTxId?.let { TlvStream(ChannelReestablishTlv.NextFunding(it.reversed())) } ?: TlvStream.empty()
            ChannelReestablish(
                channelId = channelId,
                nextLocalCommitmentNumber = state.commitments.localCommitIndex + 1,
                nextRemoteRevocationNumber = state.commitments.remoteCommitIndex,
                yourLastCommitmentSecret = PrivateKey(yourLastPerCommitmentSecret),
                myCurrentPerCommitmentPoint = myCurrentPerCommitmentPoint,
                tlvStream = tlvs
            ).withChannelData(state.commitments.remoteChannelData, logger)
        }
    }

    companion object {
        // this companion object is used by static extended function `fun PersistedChannelState.Companion.from` in Encryption.kt
    }
}

sealed class ChannelStateWithCommitments : PersistedChannelState() {
    abstract val commitments: Commitments
    override val channelId: ByteVector32 get() = commitments.channelId
    val isInitiator: Boolean get() = commitments.params.localParams.isInitiator
    val remoteNodeId: PublicKey get() = commitments.remoteNodeId

    fun ChannelContext.channelKeys(): KeyManager.ChannelKeys = commitments.params.localParams.channelKeys(keyManager)

    abstract fun updateCommitments(input: Commitments): ChannelStateWithCommitments

    /**
     * When a funding transaction confirms, we can prune previous commitments.
     * We also watch this funding transaction to be able to detect force-close attempts.
     */
    internal fun ChannelContext.acceptFundingTxConfirmed(w: WatchEventConfirmed): Either<Commitments, Triple<Commitments, Commitment, List<ChannelAction>>> {
        logger.info { "funding txid=${w.tx.txid} was confirmed at blockHeight=${w.blockHeight} txIndex=${w.txIndex}" }
        val fundingStatus = LocalFundingStatus.ConfirmedFundingTx(w.tx)
        return commitments.run {
            updateLocalFundingStatus(w.tx.txid, fundingStatus).map { (commitments1, commitment) ->
                val watchSpent = WatchSpent(channelId, commitment.fundingTxId, commitment.commitInput.outPoint.index.toInt(), commitment.commitInput.txOut.publicKeyScript, BITCOIN_FUNDING_SPENT)
                Triple(commitments1, commitment, listOf(ChannelAction.Blockchain.SendWatch(watchSpent)))
            }
        }
    }

    /**
     * Default handler when a funding transaction confirms.
     */
    internal fun ChannelContext.updateFundingTxStatus(w: WatchEventConfirmed): Pair<ChannelStateWithCommitments, List<ChannelAction>> {
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
     * Analyze and react to a potential force-close transaction spending one of our funding transactions.
     */
    internal fun ChannelContext.handlePotentialForceClose(w: WatchEventSpent): Pair<ChannelStateWithCommitments, List<ChannelAction>> = when {
        w.event != BITCOIN_FUNDING_SPENT -> Pair(this@ChannelStateWithCommitments, listOf())
        commitments.all.any { it.fundingTxId == w.tx.txid } -> Pair(this@ChannelStateWithCommitments, listOf()) // if the spending tx is itself a funding tx, this is a splice and there is nothing to do
        w.tx.txid == commitments.latest.localCommit.publishableTxs.commitTx.tx.txid -> spendLocalCurrent()
        w.tx.txid == commitments.latest.remoteCommit.txid -> handleRemoteSpentCurrent(w.tx, commitments.latest)
        w.tx.txid == commitments.latest.nextRemoteCommit?.commit?.txid -> handleRemoteSpentNext(w.tx, commitments.latest)
        w.tx.txIn.any { it.outPoint == commitments.latest.commitInput.outPoint } -> handleRemoteSpentOther(w.tx)
        else -> when (val commitment = commitments.resolveCommitment(w.tx)) {
            is Commitment -> {
                logger.warning { "a commit tx for an older commitment has been published fundingTxId=${commitment.fundingTxId} fundingTxIndex=${commitment.fundingTxIndex}" }
                // We try spending our latest commitment but we also watch their commitment: if it confirms, we will react by spending our corresponding outputs.
                val watch = ChannelAction.Blockchain.SendWatch(WatchConfirmed(channelId, w.tx, staticParams.nodeParams.minDepthBlocks.toLong(), BITCOIN_ALTERNATIVE_COMMIT_TX_CONFIRMED))
                spendLocalCurrent().run { copy(second = second + watch) }
            }
            else -> {
                logger.warning { "unrecognized tx=${w.tx.txid}" }
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

    internal fun ChannelContext.handleRemoteSpentCurrent(commitTx: Transaction, commitment: FullCommitment): Pair<Closing, List<ChannelAction>> {
        logger.warning { "they published their current commit in txid=${commitTx.txid}" }
        require(commitTx.txid == commitment.remoteCommit.txid) { "txid mismatch" }

        val remoteCommitPublished = claimRemoteCommitTxOutputs(channelKeys(), commitment, commitment.remoteCommit, commitTx, currentOnChainFeerates)

        val nextState = when (this@ChannelStateWithCommitments) {
            is Closing -> this@ChannelStateWithCommitments.copy(remoteCommitPublished = remoteCommitPublished)
            is Negotiating -> Closing(
                commitments = commitments,
                waitingSinceBlock = currentBlockHeight.toLong(),
                mutualCloseProposed = closingTxProposed.flatten().map { it.unsignedTx },
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
            addAll(remoteCommitPublished.run { doPublish(channelId, staticParams.nodeParams.minDepthBlocks.toLong()) })
        })
    }

    internal fun ChannelContext.handleRemoteSpentNext(commitTx: Transaction, commitment: FullCommitment): Pair<ChannelStateWithCommitments, List<ChannelAction>> {
        logger.warning { "they published their next commit in txid=${commitTx.txid}" }
        require(commitment.nextRemoteCommit != null) { "next remote commit must be defined" }
        val remoteCommit = commitment.nextRemoteCommit.commit
        require(commitTx.txid == remoteCommit.txid) { "txid mismatch" }

        val remoteCommitPublished = claimRemoteCommitTxOutputs(channelKeys(), commitment, remoteCommit, commitTx, currentOnChainFeerates)

        val nextState = when (this@ChannelStateWithCommitments) {
            is Closing -> copy(nextRemoteCommitPublished = remoteCommitPublished)
            is Negotiating -> Closing(
                commitments = commitments,
                waitingSinceBlock = currentBlockHeight.toLong(),
                mutualCloseProposed = closingTxProposed.flatten().map { it.unsignedTx },
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
            addAll(remoteCommitPublished.run { doPublish(channelId, staticParams.nodeParams.minDepthBlocks.toLong()) })
        })
    }

    internal fun ChannelContext.handleRemoteSpentOther(tx: Transaction): Pair<ChannelStateWithCommitments, List<ChannelAction>> {
        logger.warning { "funding tx spent in txid=${tx.txid}" }
        return getRemotePerCommitmentSecret(channelKeys(), commitments.params, commitments.remotePerCommitmentSecrets, tx)?.let { (remotePerCommitmentSecret, commitmentNumber) ->
            logger.warning { "txid=${tx.txid} was a revoked commitment, publishing the penalty tx" }
            val revokedCommitPublished = claimRevokedRemoteCommitTxOutputs(channelKeys(), commitments.params, remotePerCommitmentSecret, tx, currentOnChainFeerates)
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
                    waitingSinceBlock = currentBlockHeight.toLong(),
                    mutualCloseProposed = closingTxProposed.flatten().map { it.unsignedTx },
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
                addAll(revokedCommitPublished.run { doPublish(channelId, staticParams.nodeParams.minDepthBlocks.toLong()) })
                add(ChannelAction.Message.Send(error))
                add(ChannelAction.Storage.GetHtlcInfos(revokedCommitPublished.commitTx.txid, commitmentNumber))
            })
        } ?: run {
            when (this@ChannelStateWithCommitments) {
                is WaitForRemotePublishFutureCommitment -> {
                    logger.warning { "they published their future commit (because we asked them to) in txid=${tx.txid}" }
                    val remoteCommitPublished = claimRemoteCommitMainOutput(channelKeys(), commitments.params, tx, currentOnChainFeerates.claimMainFeerate)
                    val nextState = Closing(
                        commitments = commitments,
                        waitingSinceBlock = currentBlockHeight.toLong(),
                        futureRemoteCommitPublished = remoteCommitPublished
                    )
                    Pair(nextState, buildList {
                        add(ChannelAction.Storage.StoreState(nextState))
                        addAll(remoteCommitPublished.run { doPublish(channelId, staticParams.nodeParams.minDepthBlocks.toLong()) })
                    })
                }
                else -> {
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
            }
        }
    }

    internal fun ChannelContext.spendLocalCurrent(): Pair<ChannelStateWithCommitments, List<ChannelAction>> {
        val outdatedCommitment = when (this@ChannelStateWithCommitments) {
            is WaitForRemotePublishFutureCommitment -> true
            is Closing -> this@ChannelStateWithCommitments.futureRemoteCommitPublished != null
            else -> false
        }

        return if (outdatedCommitment) {
            logger.warning { "we have an outdated commitment: will not publish our local tx" }
            Pair(this@ChannelStateWithCommitments, listOf())
        } else {
            val commitTx = commitments.latest.localCommit.publishableTxs.commitTx.tx
            val localCommitPublished = claimCurrentLocalCommitTxOutputs(
                channelKeys(),
                commitments.latest,
                commitTx,
                currentOnChainFeerates
            )
            val nextState = when (this@ChannelStateWithCommitments) {
                is Closing -> copy(localCommitPublished = localCommitPublished)
                is Negotiating -> Closing(
                    commitments = commitments,
                    waitingSinceBlock = currentBlockHeight.toLong(),
                    mutualCloseProposed = closingTxProposed.flatten().map { it.unsignedTx },
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
                addAll(localCommitPublished.run { doPublish(channelId, staticParams.nodeParams.minDepthBlocks.toLong()) })
            })
        }
    }

    /**
     * Check HTLC timeout in our commitment and our remote's.
     * If HTLCs are at risk, we will publish our local commitment and close the channel.
     */
    internal fun ChannelContext.checkHtlcTimeout(): Pair<ChannelStateWithCommitments, List<ChannelAction>> {
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
                    this@ChannelStateWithCommitments is Negotiating && this@ChannelStateWithCommitments.bestUnpublishedClosingTx != null -> {
                        val nexState = Closing(
                            commitments,
                            waitingSinceBlock = currentBlockHeight.toLong(),
                            mutualCloseProposed = closingTxProposed.flatten().map { it.unsignedTx },
                            mutualClosePublished = listOfNotNull(bestUnpublishedClosingTx)
                        )
                        Pair(nexState, buildList {
                            add(ChannelAction.Storage.StoreState(nexState))
                            addAll(doPublish(bestUnpublishedClosingTx, nexState.channelId))
                        })
                    }
                    else -> {
                        val error = Error(channelId, channelEx.message)
                        val (nextState, actions) = spendLocalCurrent()
                        Pair(nextState, buildList {
                            addAll(actions)
                            add(ChannelAction.Message.Send(error))
                        })
                    }
                }
            }
        }
    }

    // in Normal and Shutdown we aggregate sigs for splices before processing
    var sigStash = emptyList<CommitSig>()

    /** For splices we will send one commit_sig per active commitments. */
    internal fun ChannelContext.aggregateSigs(commit: CommitSig): List<CommitSig>? {
        sigStash = sigStash + commit
        logger.debug { "received sig for batch of size=${commit.batchSize}" }
        return if (sigStash.size == commit.batchSize) {
            val sigs = sigStash
            sigStash = emptyList()
            sigs
        } else {
            null
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

    // we won't exchange more than this many signatures when negotiating the closing fee
    const val MAX_NEGOTIATION_ITERATIONS = 20

    // this is defined in BOLT 11
    val MIN_CLTV_EXPIRY_DELTA = CltvExpiryDelta(18)
    val MAX_CLTV_EXPIRY_DELTA = CltvExpiryDelta(7 * 144) // one week

    // since BOLT 1.1, there is a max value for the refund delay of the main commitment tx
    val MAX_TO_SELF_DELAY = CltvExpiryDelta(2016)
}
