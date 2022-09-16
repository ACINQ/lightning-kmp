package fr.acinq.lightning.channel

import fr.acinq.bitcoin.*
import fr.acinq.lightning.*
import fr.acinq.lightning.blockchain.*
import fr.acinq.lightning.blockchain.fee.FeeratePerKw
import fr.acinq.lightning.blockchain.fee.OnChainFeerates
import fr.acinq.lightning.channel.Channel.FUNDING_TIMEOUT_NON_INITIATOR_BLOCK
import fr.acinq.lightning.channel.Helpers.Closing.claimCurrentLocalCommitTxOutputs
import fr.acinq.lightning.channel.Helpers.Closing.claimRemoteCommitTxOutputs
import fr.acinq.lightning.channel.Helpers.Closing.claimRevokedRemoteCommitTxOutputs
import fr.acinq.lightning.crypto.KeyManager
import fr.acinq.lightning.db.OutgoingPayment
import fr.acinq.lightning.serialization.Serialization
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
sealed class ChannelEvent {
    data class InitInitiator(
        val fundingInputs: FundingInputs,
        val pushAmount: MilliSatoshi,
        val commitTxFeerate: FeeratePerKw,
        val fundingTxFeerate: FeeratePerKw,
        val localParams: LocalParams,
        val remoteInit: Init,
        val channelFlags: Byte,
        val channelConfig: ChannelConfig,
        val channelType: ChannelType.SupportedChannelType,
        val channelOrigin: ChannelOrigin? = null
    ) : ChannelEvent() {
        val temporaryChannelId: ByteVector32 = localParams.channelKeys.temporaryChannelId
        val fundingAmount: Satoshi = fundingInputs.fundingAmount
    }

    data class InitNonInitiator(
        val temporaryChannelId: ByteVector32,
        val fundingInputs: FundingInputs,
        val localParams: LocalParams,
        val channelConfig: ChannelConfig,
        val remoteInit: Init
    ) : ChannelEvent() {
        val fundingAmount: Satoshi = fundingInputs.fundingAmount
    }

    data class Restore(val state: ChannelState) : ChannelEvent()
    object CheckHtlcTimeout : ChannelEvent()
    data class MessageReceived(val message: LightningMessage) : ChannelEvent()
    data class WatchReceived(val watch: WatchEvent) : ChannelEvent()
    data class ExecuteCommand(val command: Command) : ChannelEvent()
    data class GetFundingTxResponse(val getTxResponse: GetTxWithMetaResponse) : ChannelEvent()
    data class GetHtlcInfosResponse(val revokedCommitTxId: ByteVector32, val htlcInfos: List<ChannelAction.Storage.HtlcInfo>) : ChannelEvent()
    data class NewBlock(val height: Int, val Header: BlockHeader) : ChannelEvent()
    data class SetOnChainFeerates(val feerates: OnChainFeerates) : ChannelEvent()
    object Disconnected : ChannelEvent()
    data class Connected(val localInit: Init, val remoteInit: Init) : ChannelEvent()
}

/** Channel Actions (outputs produced by the state machine). */
sealed class ChannelAction {

    data class ProcessLocalError(val error: Throwable, val trigger: ChannelEvent) : ChannelAction()

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
        data class GetFundingTx(val txid: ByteVector32) : Blockchain()
    }

    sealed class Storage : ChannelAction() {
        data class StoreState(val data: ChannelStateWithCommitments) : Storage()
        data class HtlcInfo(val channelId: ByteVector32, val commitmentNumber: Long, val paymentHash: ByteVector32, val cltvExpiry: CltvExpiry)
        data class StoreHtlcInfos(val htlcs: List<HtlcInfo>) : Storage()
        data class GetHtlcInfos(val revokedCommitTxId: ByteVector32, val commitmentNumber: Long) : Storage()
        data class StoreIncomingAmount(val amount: MilliSatoshi, val origin: ChannelOrigin?) : Storage()
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
}

/** Channel static parameters. */
data class StaticParams(val nodeParams: NodeParams, val remoteNodeId: PublicKey)

/** Channel state. */
sealed class ChannelState : LoggingContext {
    abstract val staticParams: StaticParams
    abstract val currentTip: Pair<Int, BlockHeader>
    abstract val currentOnChainFeerates: OnChainFeerates
    val currentBlockHeight: Int get() = currentTip.first
    val keyManager: KeyManager get() = staticParams.nodeParams.keyManager
    val privateKey: PrivateKey get() = staticParams.nodeParams.nodePrivateKey
    override val logger: Logger get() = staticParams.nodeParams.loggerFactory.newLogger(this::class)

    /**
     * @param event input event (for example, a message was received, a command was sent by the GUI/API, etc)
     * @return a (new state, list of actions) pair
     */
    abstract fun processInternal(event: ChannelEvent): Pair<ChannelState, List<ChannelAction>>

    fun process(event: ChannelEvent): Pair<ChannelState, List<ChannelAction>> {
        return try {
            val (newState, actions) = processInternal(event)
            val oldState = when (this) {
                is Offline -> this.state
                is Syncing -> this.state
                else -> this
            }
            val actions1 = when {
                oldState is WaitForFundingSigned && (newState is WaitForFundingConfirmed || newState is WaitForFundingLocked) && !oldState.localParams.isInitiator -> {
                    actions + ChannelAction.Storage.StoreIncomingAmount(oldState.pushAmount, oldState.channelOrigin)
                }
                // we only want to fire the PaymentSent event when we transition to Closing for the first time
                oldState is WaitForInit && newState is Closing -> actions
                oldState is Closing && newState is Closing -> actions
                oldState is ChannelStateWithCommitments && newState is Closing -> {
                    val channelBalance = oldState.commitments.localCommit.spec.toLocal
                    if (channelBalance > 0.msat) {
                        val defaultScriptPubKey = oldState.commitments.localParams.defaultFinalScriptPubKey
                        val localShutdown = when (this) {
                            is Normal -> this.localShutdown
                            is Negotiating -> this.localShutdown
                            is ShuttingDown -> this.localShutdown
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
            val actions2 = newState.updateActions(actions1)
            Pair(newState, actions2)
        } catch (t: Throwable) {
            handleLocalError(event, t)
        }
    }

    abstract fun handleLocalError(event: ChannelEvent, t: Throwable): Pair<ChannelState, List<ChannelAction>>

    internal fun unhandled(event: ChannelEvent): Pair<ChannelState, List<ChannelAction>> {
        logger.warning { "unhandled event ${event::class} in state ${this::class}" }
        return Pair(this, listOf())
    }

    /** Update outgoing messages to include an encrypted backup when necessary. */
    private fun updateActions(actions: List<ChannelAction>): List<ChannelAction> = when {
        this is ChannelStateWithCommitments && staticParams.nodeParams.features.hasFeature(Feature.ChannelBackupClient) -> actions.map {
            when {
                it is ChannelAction.Message.Send && it.message is TxSignatures -> it.copy(message = it.message.withChannelData(Serialization.encrypt(privateKey.value, this)))
                it is ChannelAction.Message.Send && it.message is CommitSig -> it.copy(message = it.message.withChannelData(Serialization.encrypt(privateKey.value, this)))
                it is ChannelAction.Message.Send && it.message is RevokeAndAck -> it.copy(message = it.message.withChannelData(Serialization.encrypt(privateKey.value, this)))
                it is ChannelAction.Message.Send && it.message is Shutdown -> it.copy(message = it.message.withChannelData(Serialization.encrypt(privateKey.value, this)))
                it is ChannelAction.Message.Send && it.message is ClosingSigned -> it.copy(message = it.message.withChannelData(Serialization.encrypt(privateKey.value, this)))
                else -> it
            }
        }
        else -> actions
    }

    internal fun handleCommandError(cmd: Command, error: ChannelException, channelUpdate: ChannelUpdate? = null): Pair<ChannelState, List<ChannelAction>> {
        logger.warning(error) { "c:${error.channelId} processing ${cmd::class} in state ${this::class} failed" }
        return when (cmd) {
            is CMD_ADD_HTLC -> Pair(this, listOf(ChannelAction.ProcessCmdRes.AddFailed(cmd, error, channelUpdate)))
            else -> Pair(this, listOf(ChannelAction.ProcessCmdRes.NotExecuted(cmd, error)))
        }
    }

    internal fun handleGetFundingTx(getTxResponse: GetTxWithMetaResponse, waitingSinceBlock: Long, fundingTx_opt: Transaction?): Pair<ChannelState, List<ChannelAction>> = when {
        getTxResponse.tx_opt != null -> Pair(this, emptyList()) // the funding tx exists, nothing to do
        fundingTx_opt != null -> {
            // if we are the initiator, we never give up
            logger.info { "republishing the funding tx..." }
            // TODO we should also check if the funding tx has been double-spent
            // see eclair-2.13 -> Channel.scala -> checkDoubleSpent(fundingTx)
            this to listOf(ChannelAction.Blockchain.PublishTx(fundingTx_opt))
        }
        (currentTip.first - waitingSinceBlock) > FUNDING_TIMEOUT_NON_INITIATOR_BLOCK -> {
            // if we are not the initiator, we give up after some time
            handleFundingTimeout()
        }
        else -> {
            // let's wait a little longer
            logger.info { "funding tx still hasn't been published in ${currentTip.first - waitingSinceBlock} blocks, will wait ${(FUNDING_TIMEOUT_NON_INITIATOR_BLOCK - currentTip.first + waitingSinceBlock)} more blocks..." }
            Pair(this, emptyList())
        }
    }

    internal fun handleFundingPublishFailed(): Pair<ChannelState, List<ChannelAction.Message.Send>> {
        require(this is ChannelStateWithCommitments) { "${this::class} must be of type HasCommitments" }
        logger.error { "c:$channelId failed to publish funding tx" }
        val exc = ChannelFundingError(channelId)
        val error = Error(channelId, exc.message)
        // NB: we don't use the handleLocalError handler because it would result in the commit tx being published, which we don't want:
        // implementation *guarantees* that in case of BITCOIN_FUNDING_PUBLISH_FAILED, the funding tx hasn't and will never be published, so we can close the channel right away
        return Pair(Aborted(staticParams, currentTip, currentOnChainFeerates), listOf(ChannelAction.Message.Send(error)))
    }

    private fun handleFundingTimeout(): Pair<ChannelState, List<ChannelAction.Message.Send>> {
        return when (this) {
            is ChannelStateWithCommitments -> this.handleFundingTimeout()
            is Offline -> this.state.handleFundingTimeout()
            is Syncing -> this.state.handleFundingTimeout()
            else -> error("${this::class} does not handle funding tx timeouts")
        }
    }

    internal fun doPublish(tx: ClosingTx, channelId: ByteVector32): List<ChannelAction.Blockchain> = listOf(
        ChannelAction.Blockchain.PublishTx(tx.tx),
        ChannelAction.Blockchain.SendWatch(WatchConfirmed(channelId, tx.tx, staticParams.nodeParams.minDepthBlocks.toLong(), BITCOIN_TX_CONFIRMED(tx.tx)))
    )

    fun handleRemoteError(e: Error): Pair<ChannelState, List<ChannelAction>> {
        // see BOLT 1: only print out data verbatim if is composed of printable ASCII characters
        logger.error { "c:${e.channelId} peer sent error: ascii='${e.toAscii()}' bin=${e.data.toHex()}" }

        return when {
            this is Closing -> Pair(this, listOf()) // nothing to do, there is already a spending tx published
            this is Negotiating && this.bestUnpublishedClosingTx != null -> {
                val nexState = Closing(
                    staticParams = staticParams,
                    currentTip = currentTip,
                    currentOnChainFeerates = currentOnChainFeerates,
                    commitments = commitments,
                    fundingTx = null,
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
            this is ChannelStateWithCommitments -> this.spendLocalCurrent()
            // when there is no commitment yet, we just go to CLOSED state in case an error occurs
            else -> Pair(Aborted(staticParams, currentTip, currentOnChainFeerates), listOf())
        }
    }
}

sealed class ChannelStateWithCommitments : ChannelState() {
    abstract val commitments: Commitments
    val channelId: ByteVector32 get() = commitments.channelId
    val isInitiator: Boolean get() = commitments.localParams.isInitiator

    abstract fun updateCommitments(input: Commitments): ChannelStateWithCommitments

    internal fun handleRemoteSpentCurrent(commitTx: Transaction): Pair<Closing, List<ChannelAction>> {
        logger.warning { "c:$channelId they published their current commit in txid=${commitTx.txid}" }
        require(commitTx.txid == commitments.remoteCommit.txid) { "txid mismatch" }

        val remoteCommitPublished = claimRemoteCommitTxOutputs(keyManager, commitments, commitments.remoteCommit, commitTx, currentOnChainFeerates)

        val nextState = when (this) {
            is Closing -> this.copy(remoteCommitPublished = remoteCommitPublished)
            is Negotiating -> Closing(
                staticParams = staticParams,
                currentTip = currentTip,
                currentOnChainFeerates = currentOnChainFeerates,
                commitments = commitments,
                fundingTx = null,
                waitingSinceBlock = currentBlockHeight.toLong(),
                mutualCloseProposed = closingTxProposed.flatten().map { it.unsignedTx },
                remoteCommitPublished = remoteCommitPublished
            )
            is WaitForFundingConfirmed -> Closing(
                staticParams = staticParams,
                currentTip = currentTip,
                currentOnChainFeerates = currentOnChainFeerates,
                commitments = commitments,
                fundingTx = fundingTx.signedTx,
                waitingSinceBlock = currentBlockHeight.toLong(),
                remoteCommitPublished = remoteCommitPublished
            )
            else -> Closing(
                staticParams = staticParams,
                currentTip = currentTip,
                currentOnChainFeerates = currentOnChainFeerates,
                commitments = commitments,
                fundingTx = null,
                waitingSinceBlock = currentBlockHeight.toLong(),
                remoteCommitPublished = remoteCommitPublished
            )
        }

        return Pair(nextState, buildList {
            add(ChannelAction.Storage.StoreState(nextState))
            addAll(remoteCommitPublished.run { doPublish(channelId, staticParams.nodeParams.minDepthBlocks.toLong()) })
        })
    }

    internal fun handleRemoteSpentNext(commitTx: Transaction): Pair<ChannelState, List<ChannelAction>> {
        logger.warning { "c:$channelId they published their next commit in txid=${commitTx.txid}" }
        require(commitments.remoteNextCommitInfo.isLeft) { "next remote commit must be defined" }
        val remoteCommit = commitments.remoteNextCommitInfo.left?.nextRemoteCommit
        require(remoteCommit != null) { "remote commit must not be null" }
        require(commitTx.txid == remoteCommit.txid) { "txid mismatch" }

        val remoteCommitPublished = claimRemoteCommitTxOutputs(keyManager, commitments, remoteCommit, commitTx, currentOnChainFeerates)

        val nextState = when (this) {
            is Closing -> copy(nextRemoteCommitPublished = remoteCommitPublished)
            is Negotiating -> Closing(
                staticParams = staticParams,
                currentTip = currentTip,
                currentOnChainFeerates = currentOnChainFeerates,
                commitments = commitments,
                fundingTx = null,
                waitingSinceBlock = currentBlockHeight.toLong(),
                mutualCloseProposed = closingTxProposed.flatten().map { it.unsignedTx },
                nextRemoteCommitPublished = remoteCommitPublished
            )
            // NB: if there is a next commitment, we can't be in WaitForFundingConfirmed so we don't have the case where fundingTx is defined
            else -> Closing(
                staticParams = staticParams,
                currentTip = currentTip,
                currentOnChainFeerates = currentOnChainFeerates,
                commitments = commitments,
                fundingTx = null,
                waitingSinceBlock = currentBlockHeight.toLong(),
                nextRemoteCommitPublished = remoteCommitPublished
            )
        }

        return Pair(nextState, buildList {
            add(ChannelAction.Storage.StoreState(nextState))
            addAll(remoteCommitPublished.run { doPublish(channelId, staticParams.nodeParams.minDepthBlocks.toLong()) })
        })
    }

    internal fun handleRemoteSpentOther(tx: Transaction): Pair<ChannelState, List<ChannelAction>> {
        logger.warning { "c:$channelId funding tx spent in txid=${tx.txid}" }

        return claimRevokedRemoteCommitTxOutputs(keyManager, commitments, tx, currentOnChainFeerates)?.let { (revokedCommitPublished, txNumber) ->
            logger.warning { "c:$channelId txid=${tx.txid} was a revoked commitment, publishing the penalty tx" }
            val ex = FundingTxSpent(channelId, tx)
            val error = Error(channelId, ex.message)

            val nextState = when (this) {
                is Closing -> if (this.revokedCommitPublished.contains(revokedCommitPublished)) {
                    this
                } else {
                    this.copy(revokedCommitPublished = this.revokedCommitPublished + revokedCommitPublished)
                }
                is Negotiating -> Closing(
                    staticParams = staticParams,
                    currentTip = currentTip,
                    currentOnChainFeerates = currentOnChainFeerates,
                    commitments = commitments,
                    fundingTx = null,
                    waitingSinceBlock = currentBlockHeight.toLong(),
                    mutualCloseProposed = closingTxProposed.flatten().map { it.unsignedTx },
                    revokedCommitPublished = listOf(revokedCommitPublished)
                )
                // NB: if there is a next commitment, we can't be in WaitForFundingConfirmed so we don't have the case where fundingTx is defined
                else -> Closing(
                    staticParams = staticParams,
                    currentTip = currentTip,
                    commitments = commitments,
                    currentOnChainFeerates = currentOnChainFeerates,
                    fundingTx = null,
                    waitingSinceBlock = currentBlockHeight.toLong(),
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
            Pair(ErrorInformationLeak(staticParams, currentTip, currentOnChainFeerates, commitments), listOf())
        }
    }

    internal fun spendLocalCurrent(): Pair<ChannelState, List<ChannelAction>> {
        val outdatedCommitment = when (this) {
            is WaitForRemotePublishFutureCommitment -> true
            is Closing -> this.futureRemoteCommitPublished != null
            else -> false
        }

        return if (outdatedCommitment) {
            logger.warning { "c:$channelId we have an outdated commitment: will not publish our local tx" }
            Pair(this as ChannelState, listOf())
        } else {
            val commitTx = commitments.localCommit.publishableTxs.commitTx.tx
            val localCommitPublished = claimCurrentLocalCommitTxOutputs(
                keyManager,
                commitments,
                commitTx,
                currentOnChainFeerates
            )
            val nextState = when (this) {
                is Closing -> copy(localCommitPublished = localCommitPublished)
                is Negotiating -> Closing(
                    staticParams = staticParams,
                    currentTip = currentTip,
                    currentOnChainFeerates = currentOnChainFeerates,
                    commitments = commitments,
                    fundingTx = null,
                    waitingSinceBlock = currentBlockHeight.toLong(),
                    mutualCloseProposed = closingTxProposed.flatten().map { it.unsignedTx },
                    localCommitPublished = localCommitPublished
                )
                is WaitForFundingConfirmed -> Closing(
                    staticParams = staticParams, currentTip = currentTip,
                    currentOnChainFeerates = currentOnChainFeerates,
                    commitments = commitments,
                    fundingTx = fundingTx.signedTx,
                    waitingSinceBlock = currentBlockHeight.toLong(),
                    localCommitPublished = localCommitPublished
                )
                else -> Closing(
                    staticParams = staticParams, currentTip = currentTip,
                    currentOnChainFeerates = currentOnChainFeerates,
                    commitments = commitments,
                    fundingTx = null,
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
    internal fun checkHtlcTimeout(): Pair<ChannelState, List<ChannelAction>> {
        val timedOutOutgoing = commitments.timedOutOutgoingHtlcs(currentBlockHeight.toLong())
        val almostTimedOutIncoming = commitments.almostTimedOutIncomingHtlcs(currentBlockHeight.toLong(), staticParams.nodeParams.fulfillSafetyBeforeTimeoutBlocks)
        val channelEx: ChannelException? = when {
            timedOutOutgoing.isNotEmpty() -> HtlcsTimedOutDownstream(channelId, timedOutOutgoing)
            almostTimedOutIncoming.isNotEmpty() -> FulfilledHtlcsWillTimeout(channelId, almostTimedOutIncoming)
            else -> null
        }
        return when (channelEx) {
            null -> Pair(this, listOf())
            else -> {
                logger.error { "c:$channelId ${channelEx.message}" }
                when {
                    this is Closing -> Pair(this, listOf()) // nothing to do, there is already a spending tx published
                    this is Negotiating && this.bestUnpublishedClosingTx != null -> {
                        val nexState = Closing(
                            staticParams,
                            currentTip,
                            currentOnChainFeerates,
                            commitments,
                            fundingTx = null,
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

    fun handleFundingTimeout(): Pair<ChannelState, List<ChannelAction.Message.Send>> {
        logger.warning { "c:$channelId funding tx hasn't been confirmed in time, cancelling channel delay=$FUNDING_TIMEOUT_NON_INITIATOR_BLOCK blocks" }
        val exc = FundingTxTimedout(channelId)
        val error = Error(channelId, exc.message)
        return Pair(Aborted(staticParams, currentTip, currentOnChainFeerates), listOf(ChannelAction.Message.Send(error)))
    }
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

    // as a non-initiator, we will wait that block count for the funding tx to confirm (the initiator will rely on the funding tx being double-spent)
    const val FUNDING_TIMEOUT_NON_INITIATOR_BLOCK = 2016

    fun handleSync(channelReestablish: ChannelReestablish, d: ChannelStateWithCommitments, keyManager: KeyManager, log: Logger): Pair<Commitments, List<ChannelAction>> {
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
                    val localPerCommitmentSecret = keyManager.commitmentSecret(d.commitments.localParams.channelKeys.shaSeed, d.commitments.localCommit.index - 1)
                    val localNextPerCommitmentPoint = keyManager.commitmentPoint(d.commitments.localParams.channelKeys.shaSeed, d.commitments.localCommit.index + 1)
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
