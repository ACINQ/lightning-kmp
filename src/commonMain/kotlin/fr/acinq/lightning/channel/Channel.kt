package fr.acinq.lightning.channel

import fr.acinq.bitcoin.*
import fr.acinq.lightning.*
import fr.acinq.lightning.blockchain.BITCOIN_TX_CONFIRMED
import fr.acinq.lightning.blockchain.Watch
import fr.acinq.lightning.blockchain.WatchConfirmed
import fr.acinq.lightning.blockchain.WatchEvent
import fr.acinq.lightning.blockchain.electrum.WalletState
import fr.acinq.lightning.blockchain.fee.FeeratePerKw
import fr.acinq.lightning.blockchain.fee.OnChainFeerates
import fr.acinq.lightning.channel.Helpers.Closing.claimCurrentLocalCommitTxOutputs
import fr.acinq.lightning.channel.Helpers.Closing.claimRemoteCommitTxOutputs
import fr.acinq.lightning.channel.Helpers.Closing.claimRevokedRemoteCommitTxOutputs
import fr.acinq.lightning.crypto.KeyManager
import fr.acinq.lightning.db.OutgoingPayment
import fr.acinq.lightning.serialization.Encryption.from
import fr.acinq.lightning.transactions.Transactions.TransactionWithInputInfo.ClosingTx
import fr.acinq.lightning.transactions.outgoings
import fr.acinq.lightning.utils.*
import fr.acinq.lightning.wire.*
import org.kodein.log.Logger
import org.kodein.log.newLogger

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
        val channelOrigin: ChannelOrigin? = null
    ) : ChannelCommand() {
        fun temporaryChannelId(keyManager: KeyManager): ByteVector32 = localParams.channelKeys(keyManager).temporaryChannelId
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

    data class Restore(val state: ChannelState) : ChannelCommand()
    object CheckHtlcTimeout : ChannelCommand()
    data class MessageReceived(val message: LightningMessage) : ChannelCommand()
    data class WatchReceived(val watch: WatchEvent) : ChannelCommand()
    data class ExecuteCommand(val command: Command) : ChannelCommand()
    data class GetHtlcInfosResponse(val revokedCommitTxId: ByteVector32, val htlcInfos: List<ChannelAction.Storage.HtlcInfo>) : ChannelCommand()
    //data class NewBlock(val height: Int, val Header: BlockHeader) : ChannelCommand()
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
        data class PublishTx(val tx: Transaction) : Blockchain()
    }

    sealed class Storage : ChannelAction() {
        data class StoreState(val data: ChannelStateWithCommitments) : Storage()
        data class HtlcInfo(val channelId: ByteVector32, val commitmentNumber: Long, val paymentHash: ByteVector32, val cltvExpiry: CltvExpiry)
        data class StoreHtlcInfos(val htlcs: List<HtlcInfo>) : Storage()
        data class GetHtlcInfos(val revokedCommitTxId: ByteVector32, val commitmentNumber: Long) : Storage()
        data class StoreIncomingAmount(val amount: MilliSatoshi, val localInputs: Set<OutPoint>, val origin: ChannelOrigin?) : Storage()
        data class StoreChannelClosing(val amount: MilliSatoshi, val closingAddress: String, val isSentToDefaultAddress: Boolean) : Storage()
        data class StoreChannelClosed(val closingTxs: List<OutgoingPayment.ClosingTxPart>) : Storage()
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
            data class Disconnected(val channelUpdate: ChannelUpdate) : Fail()
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
    val currentTip: Pair<Int, BlockHeader>,
    val currentOnChainFeerates: OnChainFeerates
): LoggingContext {
    val currentBlockHeight: Int get() = currentTip.first
    val keyManager: KeyManager get() = staticParams.nodeParams.keyManager
    val privateKey: PrivateKey get() = staticParams.nodeParams.nodePrivateKey

    override val logger: Logger get() = staticParams.nodeParams.loggerFactory.newLogger(this::class)
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
            val (newState, actions) = processInternal(cmd)
            val oldState = when (this@ChannelState) {
                is Offline -> this@ChannelState.state
                is Syncing -> this@ChannelState.state
                else -> this@ChannelState
            }
            val actions1 = when {
                oldState is WaitForFundingSigned && (newState is WaitForFundingConfirmed || newState is WaitForChannelReady) -> {
                    val channelCreated = ChannelAction.EmitEvent(ChannelEvents.Created(newState as ChannelStateWithCommitments))
                    when {
                        !oldState.localParams.isInitiator -> {
                            val amount = oldState.fundingParams.localAmount.toMilliSatoshi() + oldState.remotePushAmount - oldState.localPushAmount
                            val localInputs = oldState.fundingTx.localInputs.map { OutPoint(it.previousTx, it.previousTxOutput) }.toSet()
                            actions + ChannelAction.Storage.StoreIncomingAmount(amount, localInputs, oldState.channelOrigin) + channelCreated
                        }
                        else -> actions + channelCreated
                    }
                }
                // we only want to fire the PaymentSent event when we transition to Closing for the first time
                oldState is WaitForInit && newState is Closing -> actions
                oldState is Closing && newState is Closing -> actions
                oldState is ChannelStateWithCommitments && newState is Closing -> {
                    val channelBalance = oldState.commitments.localCommit.spec.toLocal
                    if (channelBalance > 0.msat) {
                        val defaultScriptPubKey = oldState.commitments.localParams.defaultFinalScriptPubKey
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
                            actions + ChannelAction.Storage.StoreChannelClosing(
                                amount = channelBalance,
                                closingAddress = btcAddr,
                                isSentToDefaultAddress = false
                            )
                        } else {
                            // Default output address
                            val btcAddr = Helpers.Closing.btcAddressFromScriptPubKey(
                                scriptPubKey = defaultScriptPubKey,
                                chainHash = staticParams.nodeParams.chainHash
                            ) ?: "unknown"
                            actions + ChannelAction.Storage.StoreChannelClosing(
                                amount = channelBalance,
                                closingAddress = btcAddr,
                                isSentToDefaultAddress = true
                            )
                        }
                    } else /* channelBalance <= 0.msat */ {
                        actions
                    }
                }
                else -> actions
            }
            val actions2 = newState.run { updateActions(actions1) }
            Pair(newState, actions2)
        } catch (t: Throwable) {
            handleLocalError(cmd, t)
        }
    }

    abstract fun ChannelContext.handleLocalError(cmd: ChannelCommand, t: Throwable): Pair<ChannelState, List<ChannelAction>>

    internal fun ChannelContext.unhandled(cmd: ChannelCommand): Pair<ChannelState, List<ChannelAction>> {
        logger.warning { "unhandled event ${cmd::class} in state ${this@ChannelState::class}" }
        return Pair(this@ChannelState, listOf())
    }

    /** Update outgoing messages to include an encrypted backup when necessary. */
    private fun ChannelContext.updateActions(actions: List<ChannelAction>): List<ChannelAction> = when {
        // We don't add an encrypted backup while the funding tx is unconfirmed, as it contains potentially too much data.
        this@ChannelState is WaitForFundingConfirmed -> actions
        this@ChannelState is WaitForChannelReady -> actions
        this@ChannelState is ChannelStateWithCommitments && staticParams.nodeParams.features.hasFeature(Feature.ChannelBackupClient) -> actions.map {
            when {
                it is ChannelAction.Message.Send && it.message is CommitSig -> it.copy(message = it.message.withChannelData(EncryptedChannelData.from(privateKey, this, this@ChannelState)))
                it is ChannelAction.Message.Send && it.message is RevokeAndAck -> it.copy(message = it.message.withChannelData(EncryptedChannelData.from(privateKey, this, this@ChannelState)))
                it is ChannelAction.Message.Send && it.message is Shutdown -> it.copy(message = it.message.withChannelData(EncryptedChannelData.from(privateKey, this, this@ChannelState)))
                it is ChannelAction.Message.Send && it.message is ClosingSigned -> it.copy(message = it.message.withChannelData(EncryptedChannelData.from(privateKey, this, this@ChannelState)))
                else -> it
            }
        }
        else -> actions
    }

    internal fun ChannelContext.handleCommandError(cmd: Command, error: ChannelException, channelUpdate: ChannelUpdate? = null): Pair<ChannelState, List<ChannelAction>> {
        logger.warning(error) { "c:${error.channelId} processing ${cmd::class} in state ${this::class} failed" }
        return when (cmd) {
            is CMD_ADD_HTLC -> Pair(this@ChannelState, listOf(ChannelAction.ProcessCmdRes.AddFailed(cmd, error, channelUpdate)))
            else -> Pair(this@ChannelState, listOf(ChannelAction.ProcessCmdRes.NotExecuted(cmd, error)))
        }
    }

    internal fun ChannelContext.doPublish(tx: ClosingTx, channelId: ByteVector32): List<ChannelAction.Blockchain> = listOf(
        ChannelAction.Blockchain.PublishTx(tx.tx),
        ChannelAction.Blockchain.SendWatch(WatchConfirmed(channelId, tx.tx, staticParams.nodeParams.minDepthBlocks.toLong(), BITCOIN_TX_CONFIRMED(tx.tx)))
    )

    fun ChannelContext.handleRemoteError(e: Error): Pair<ChannelState, List<ChannelAction>> {
        // see BOLT 1: only print out data verbatim if is composed of printable ASCII characters
        logger.error { "c:${e.channelId} peer sent error: ascii='${e.toAscii()}' bin=${e.data.toHex()}" }
        return when {
            this@ChannelState is Closing -> Pair(this@ChannelState, listOf()) // nothing to do, there is already a spending tx published
            this@ChannelState is Negotiating && this@ChannelState.bestUnpublishedClosingTx != null -> {
                val nexState = Closing(
                    commitments = commitments,
                    fundingTx = null,
                    waitingSinceBlock = currentBlockHeight.toLong(),
                    alternativeCommitments = listOf(),
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

sealed class ChannelStateWithCommitments : ChannelState() {
    abstract val commitments: Commitments
    val channelId: ByteVector32 get() = commitments.channelId
    val isInitiator: Boolean get() = commitments.localParams.isInitiator
    val remoteNodeId: PublicKey get() = commitments.remoteParams.nodeId

    abstract fun updateCommitments(input: Commitments): ChannelStateWithCommitments

    fun nothingAtStake(): Boolean {
        return when (this) {
            is WaitForFundingConfirmed -> (listOf(commitments) + previousFundingTxs.map { it.second }).fold(true) { current, commitments -> current && commitments.nothingAtStake() }
            is Closing -> (listOf(commitments) + alternativeCommitments).fold(true) { current, commitments -> current && commitments.nothingAtStake() }
            else -> commitments.nothingAtStake()
        }
    }

    internal fun ChannelContext.handleRemoteSpentCurrent(commitTx: Transaction): Pair<Closing, List<ChannelAction>> {
        logger.warning { "c:$channelId they published their current commit in txid=${commitTx.txid}" }
        require(commitTx.txid == commitments.remoteCommit.txid) { "txid mismatch" }

        val remoteCommitPublished = claimRemoteCommitTxOutputs(keyManager, commitments, commitments.remoteCommit, commitTx, currentOnChainFeerates)

        val nextState = when (this@ChannelStateWithCommitments) {
            is Closing -> this@ChannelStateWithCommitments.copy(remoteCommitPublished = remoteCommitPublished)
            is Negotiating -> Closing(
                commitments = commitments,
                fundingTx = null,
                waitingSinceBlock = currentBlockHeight.toLong(),
                alternativeCommitments = listOf(),
                mutualCloseProposed = closingTxProposed.flatten().map { it.unsignedTx },
                remoteCommitPublished = remoteCommitPublished
            )
            is WaitForFundingConfirmed -> Closing(
                commitments = commitments,
                fundingTx = fundingTx.signedTx,
                waitingSinceBlock = currentBlockHeight.toLong(),
                alternativeCommitments = previousFundingTxs.map { it.second },
                remoteCommitPublished = remoteCommitPublished
            )
            else -> Closing(
                commitments = commitments,
                fundingTx = null,
                waitingSinceBlock = currentBlockHeight.toLong(),
                alternativeCommitments = listOf(),
                remoteCommitPublished = remoteCommitPublished
            )
        }

        return Pair(nextState, buildList {
            add(ChannelAction.Storage.StoreState(nextState))
            addAll(remoteCommitPublished.run { doPublish(channelId, staticParams.nodeParams.minDepthBlocks.toLong()) })
        })
    }

    internal fun ChannelContext.handleRemoteSpentNext(commitTx: Transaction): Pair<ChannelState, List<ChannelAction>> {
        logger.warning { "c:$channelId they published their next commit in txid=${commitTx.txid}" }
        require(commitments.remoteNextCommitInfo.isLeft) { "next remote commit must be defined" }
        val remoteCommit = commitments.remoteNextCommitInfo.left?.nextRemoteCommit
        require(remoteCommit != null) { "remote commit must not be null" }
        require(commitTx.txid == remoteCommit.txid) { "txid mismatch" }

        val remoteCommitPublished = claimRemoteCommitTxOutputs(keyManager, commitments, remoteCommit, commitTx, currentOnChainFeerates)

        val nextState = when (this@ChannelStateWithCommitments) {
            is Closing -> copy(nextRemoteCommitPublished = remoteCommitPublished)
            is Negotiating -> Closing(
                commitments = commitments,
                fundingTx = null,
                waitingSinceBlock = currentBlockHeight.toLong(),
                alternativeCommitments = listOf(),
                mutualCloseProposed = closingTxProposed.flatten().map { it.unsignedTx },
                nextRemoteCommitPublished = remoteCommitPublished
            )
            // NB: if there is a next commitment, we can't be in WaitForFundingConfirmed, so we don't have the case where fundingTx is defined
            else -> Closing(
                commitments = commitments,
                fundingTx = null,
                waitingSinceBlock = currentBlockHeight.toLong(),
                alternativeCommitments = listOf(),
                nextRemoteCommitPublished = remoteCommitPublished
            )
        }

        return Pair(nextState, buildList {
            add(ChannelAction.Storage.StoreState(nextState))
            addAll(remoteCommitPublished.run { doPublish(channelId, staticParams.nodeParams.minDepthBlocks.toLong()) })
        })
    }

    internal fun ChannelContext.handleRemoteSpentOther(tx: Transaction): Pair<ChannelState, List<ChannelAction>> {
        logger.warning { "c:$channelId funding tx spent in txid=${tx.txid}" }

        return claimRevokedRemoteCommitTxOutputs(keyManager, commitments, tx, currentOnChainFeerates)?.let { (revokedCommitPublished, txNumber) ->
            logger.warning { "c:$channelId txid=${tx.txid} was a revoked commitment, publishing the penalty tx" }
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
                    fundingTx = null,
                    waitingSinceBlock = currentBlockHeight.toLong(),
                    alternativeCommitments = listOf(),
                    mutualCloseProposed = closingTxProposed.flatten().map { it.unsignedTx },
                    revokedCommitPublished = listOf(revokedCommitPublished)
                )
                // NB: if there is a next commitment, we can't be in WaitForFundingConfirmed, so we don't have the case where fundingTx is defined
                else -> Closing(
                    commitments = commitments,
                    fundingTx = null,
                    waitingSinceBlock = currentBlockHeight.toLong(),
                    alternativeCommitments = listOf(),
                    revokedCommitPublished = listOf(revokedCommitPublished)
                )
            }

            return Pair(nextState, buildList {
                add(ChannelAction.Storage.StoreState(nextState))
                addAll(revokedCommitPublished.run { doPublish(channelId, staticParams.nodeParams.minDepthBlocks.toLong()) })
                add(ChannelAction.Message.Send(error))
                add(ChannelAction.Storage.GetHtlcInfos(revokedCommitPublished.commitTx.txid, txNumber))
            })
        } ?: run {
            // the published tx was neither their current commitment nor a revoked one
            logger.error { "c:$channelId couldn't identify txid=${tx.txid}, something very bad is going on!!!" }
            Pair(ErrorInformationLeak(commitments), listOf())
        }
    }

    internal fun ChannelContext.spendLocalCurrent(): Pair<ChannelState, List<ChannelAction>> {
        val outdatedCommitment = when (this@ChannelStateWithCommitments) {
            is WaitForRemotePublishFutureCommitment -> true
            is Closing -> this@ChannelStateWithCommitments.futureRemoteCommitPublished != null
            else -> false
        }

        return if (outdatedCommitment) {
            logger.warning { "c:$channelId we have an outdated commitment: will not publish our local tx" }
            Pair(this@ChannelStateWithCommitments as ChannelState, listOf())
        } else {
            val commitTx = commitments.localCommit.publishableTxs.commitTx.tx
            val localCommitPublished = claimCurrentLocalCommitTxOutputs(
                keyManager,
                commitments,
                commitTx,
                currentOnChainFeerates
            )
            val nextState = when (this@ChannelStateWithCommitments) {
                is Closing -> copy(localCommitPublished = localCommitPublished)
                is Negotiating -> Closing(
                    commitments = commitments,
                    fundingTx = null,
                    waitingSinceBlock = currentBlockHeight.toLong(),
                    alternativeCommitments = listOf(),
                    mutualCloseProposed = closingTxProposed.flatten().map { it.unsignedTx },
                    localCommitPublished = localCommitPublished
                )
                is WaitForFundingConfirmed -> Closing(
                    commitments = commitments,
                    fundingTx = fundingTx.signedTx,
                    waitingSinceBlock = currentBlockHeight.toLong(),
                    alternativeCommitments = previousFundingTxs.map { it.second },
                    localCommitPublished = localCommitPublished
                )
                else -> Closing(
                    commitments = commitments,
                    fundingTx = null,
                    waitingSinceBlock = currentBlockHeight.toLong(),
                    alternativeCommitments = listOf(),
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
    internal fun ChannelContext.checkHtlcTimeout(): Pair<ChannelState, List<ChannelAction>> {
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
                logger.error { "c:$channelId ${channelEx.message}" }
                when {
                    this@ChannelStateWithCommitments is Closing -> Pair(this@ChannelStateWithCommitments, listOf()) // nothing to do, there is already a spending tx published
                    this@ChannelStateWithCommitments is Negotiating && this@ChannelStateWithCommitments.bestUnpublishedClosingTx != null -> {
                        val nexState = Closing(
                            commitments,
                            fundingTx = null,
                            waitingSinceBlock = currentBlockHeight.toLong(),
                            alternativeCommitments = listOf(),
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

    companion object {

    }
}

data class LNChannel<out S : ChannelState>(
    val ctx: ChannelContext,
    val state: S
) {
    val staticParams = ctx.staticParams
    val currentBlockHeight = ctx.currentBlockHeight
    val channelId: ByteVector32 by lazy {
        when (state) {
            is ChannelStateWithCommitments -> state.channelId
            is WaitForFundingCreated -> state.channelId
            is WaitForFundingSigned -> state.channelId
            else -> error("no channel id in state ${state::class}")
        }
    }
    val commitments: Commitments by lazy {
        when (state) {
            is ChannelStateWithCommitments -> state.commitments
            else -> error("no commitments in state ${state::class}")
        }
    }
    fun process(cmd: ChannelCommand): Pair<LNChannel<ChannelState>, List<ChannelAction>> =
        state.run { ctx.process(cmd) }
            .let { (newState, actions) -> LNChannel(ctx, newState) to actions }
}

object Channel {
    // see https://github.com/lightningnetwork/lightning-rfc/blob/master/07-routing-gossip.md#requirements
    const val ANNOUNCEMENTS_MINCONF = 6

    // https://github.com/lightningnetwork/lightning-rfc/blob/master/02-peer-protocol.md#requirements
    val MAX_FUNDING = 10.btc
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

    fun ChannelContext.handleSync(channelReestablish: ChannelReestablish, d: ChannelStateWithCommitments, keyManager: KeyManager, log: Logger): Pair<Commitments, List<ChannelAction>> {
        val sendQueue = ArrayList<ChannelAction>()
        // first we clean up unacknowledged updates
        log.debug { "discarding proposed OUT: ${d.commitments.localChanges.proposed}" }
        log.debug { "discarding proposed IN: ${d.commitments.remoteChanges.proposed}" }
        val commitments1 = d.commitments.copy(
            localChanges = d.commitments.localChanges.copy(proposed = emptyList()),
            remoteChanges = d.commitments.remoteChanges.copy(proposed = emptyList()),
            localNextHtlcId = d.commitments.localNextHtlcId - d.commitments.localChanges.proposed.filterIsInstance<UpdateAddHtlc>().size,
            remoteNextHtlcId = d.commitments.remoteNextHtlcId - d.commitments.remoteChanges.proposed.filterIsInstance<UpdateAddHtlc>().size
        )
        log.debug { "localNextHtlcId=${d.commitments.localNextHtlcId}->${commitments1.localNextHtlcId}" }
        log.debug { "remoteNextHtlcId=${d.commitments.remoteNextHtlcId}->${commitments1.remoteNextHtlcId}" }

        fun resendRevocation() {
            // let's see the state of remote sigs
            when (commitments1.localCommit.index) {
                channelReestablish.nextRemoteRevocationNumber -> {
                    // nothing to do
                }
                channelReestablish.nextRemoteRevocationNumber + 1 -> {
                    // our last revocation got lost, let's resend it
                    log.debug { "re-sending last revocation" }
                    val localPerCommitmentSecret = keyManager.commitmentSecret(d.commitments.localParams.channelKeys(keyManager).shaSeed, d.commitments.localCommit.index - 1)
                    val localNextPerCommitmentPoint = keyManager.commitmentPoint(d.commitments.localParams.channelKeys(keyManager).shaSeed, d.commitments.localCommit.index + 1)
                    val revocation = RevokeAndAck(commitments1.channelId, localPerCommitmentSecret, localNextPerCommitmentPoint)
                    sendQueue.add(ChannelAction.Message.Send(revocation))
                }
                else -> throw RevocationSyncError(d.channelId)
            }
        }

        when {
            commitments1.remoteNextCommitInfo.isLeft && commitments1.remoteNextCommitInfo.left!!.nextRemoteCommit.index + 1 == channelReestablish.nextLocalCommitmentNumber -> {
                // we had sent a new sig and were waiting for their revocation
                // they had received the new sig but their revocation was lost during the disconnection
                // they will send us the revocation, nothing to do here
                log.debug { "waiting for them to re-send their last revocation" }
                resendRevocation()
            }
            commitments1.remoteNextCommitInfo.isLeft && commitments1.remoteNextCommitInfo.left!!.nextRemoteCommit.index == channelReestablish.nextLocalCommitmentNumber -> {
                // we had sent a new sig and were waiting for their revocation
                // they didn't receive the new sig because of the disconnection
                // we just resend the same updates and the same sig
                val revWasSentLast = commitments1.localCommit.index > commitments1.remoteNextCommitInfo.left!!.sentAfterLocalCommitIndex
                if (!revWasSentLast) resendRevocation()

                log.debug { "re-sending previously local signed changes: ${commitments1.localChanges.signed}" }
                commitments1.localChanges.signed.forEach { sendQueue.add(ChannelAction.Message.Send(it)) }
                log.debug { "re-sending the exact same previous sig" }
                sendQueue.add(ChannelAction.Message.Send(commitments1.remoteNextCommitInfo.left!!.sent))
                if (revWasSentLast) resendRevocation()
            }
            commitments1.remoteNextCommitInfo.isRight && commitments1.remoteCommit.index + 1 == channelReestablish.nextLocalCommitmentNumber -> {
                // there wasn't any sig in-flight when the disconnection occurred
                resendRevocation()
            }
            else -> throw RevocationSyncError(d.channelId)
        }

        if (commitments1.localHasChanges()) {
            sendQueue.add(ChannelAction.Message.SendToSelf(CMD_SIGN))
        }

        // When a channel is reestablished after a wallet restarts, we need to reprocess incoming HTLCs that may have been only partially processed
        // (either because they didn't reach the payment handler, or because the payment handler response didn't reach the channel).
        // Otherwise these HTLCs will stay in our commitment until they timeout and our peer closes the channel.
        //
        // We are interested in incoming HTLCs, that have been *cross-signed* (otherwise they wouldn't have been forwarded to the payment handler).
        // They signed it first, so the HTLC will first appear in our commitment tx, and later on in their commitment when we subsequently sign it.
        // That's why we need to look in *their* commitment with direction=OUT.
        //
        // We also need to filter out htlcs that we already settled and signed (the settlement messages are being retransmitted).
        val alreadySettled = commitments1.localChanges.signed.filterIsInstance<HtlcSettlementMessage>().map { it.id }.toSet()
        val htlcsToReprocess = commitments1.remoteCommit.spec.htlcs.outgoings().filter { !alreadySettled.contains(it.id) }
        log.debug { "re-processing signed IN: $htlcsToReprocess" }
        sendQueue.addAll(htlcsToReprocess.map { ChannelAction.ProcessIncomingHtlc(it) })

        return Pair(commitments1, sendQueue)
    }
}
