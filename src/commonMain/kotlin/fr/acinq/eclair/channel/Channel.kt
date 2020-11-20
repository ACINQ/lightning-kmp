package fr.acinq.eclair.channel

import fr.acinq.bitcoin.*
import fr.acinq.eclair.*
import fr.acinq.eclair.blockchain.*
import fr.acinq.eclair.blockchain.fee.OnChainFeerates
import fr.acinq.eclair.channel.Channel.ANNOUNCEMENTS_MINCONF
import fr.acinq.eclair.channel.Channel.MAX_NEGOTIATION_ITERATIONS
import fr.acinq.eclair.channel.Channel.handleSync
import fr.acinq.eclair.crypto.KeyManager
import fr.acinq.eclair.crypto.ShaChain
import fr.acinq.eclair.io.*
import fr.acinq.eclair.router.Announcements
import fr.acinq.eclair.transactions.CommitmentSpec
import fr.acinq.eclair.transactions.Scripts
import fr.acinq.eclair.transactions.Transactions
import fr.acinq.eclair.utils.*
import fr.acinq.eclair.wire.*
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass
import org.kodein.log.Logger

/*
 * Channel is implemented as a finite state machine
 * Its main method is (State, Event) -> (State, List<Action>)
 */

/** Channel Events (inputs to be fed to the state machine). */
@Serializable
sealed class ChannelEvent {
    @Serializable
    data class InitFunder(
        @Serializable(with = ByteVector32KSerializer::class) val temporaryChannelId: ByteVector32,
        @Serializable(with = SatoshiKSerializer::class) val fundingAmount: Satoshi,
        val pushAmount: MilliSatoshi,
        val initialFeeratePerKw: Long,
        val fundingTxFeeratePerKw: Long,
        val localParams: LocalParams,
        val remoteInit: Init,
        val channelFlags: Byte,
        val channelVersion: ChannelVersion
    ) : ChannelEvent() {
        init {
            require(channelVersion.hasStaticRemotekey) { "channel version $channelVersion is invalid (static_remote_key is not set)" }
            require(channelVersion.hasAnchorOutputs) { "invalid channel version $channelVersion (anchor_outputs is not set)" }
        }
    }

    data class InitFundee(val temporaryChannelId: ByteVector32, val localParams: LocalParams, val remoteInit: Init) : ChannelEvent()
    data class Restore(val state: ChannelState) : ChannelEvent()
    data class MessageReceived(val message: LightningMessage) : ChannelEvent()
    data class WatchReceived(val watch: WatchEvent) : ChannelEvent()
    data class ExecuteCommand(val command: Command) : ChannelEvent()
    data class MakeFundingTxResponse(val fundingTx: Transaction, val fundingTxOutputIndex: Int, val fee: Satoshi) : ChannelEvent()
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
        data class IdSwitch(val oldChannelId: ByteVector32, val newChannelId: ByteVector32) : ChannelId()
    }

    sealed class Blockchain : ChannelAction() {
        data class MakeFundingTx(val pubkeyScript: ByteVector, val amount: Satoshi, val feeratePerKw: Long) : Blockchain()
        data class SendWatch(val watch: Watch) : Blockchain()
        data class PublishTx(val tx: Transaction) : Blockchain()
    }

    sealed class Storage : ChannelAction() {
        data class StoreState(val data: ChannelStateWithCommitments) : Storage()
        data class HtlcInfo(val channelId: ByteVector32, val commitmentNumber: Long, val paymentHash: ByteVector32, val cltvExpiry: CltvExpiry)
        data class StoreHtlcInfos(val htlcs: List<HtlcInfo>) : Storage()
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
@Serializable
data class StaticParams(val nodeParams: NodeParams, @Serializable(with = PublicKeyKSerializer::class) val remoteNodeId: PublicKey)

/** Channel state. */
@Serializable
sealed class ChannelState {
    abstract val staticParams: StaticParams
    abstract val currentTip: Pair<Int, BlockHeader>
    abstract val currentOnChainFeerates: OnChainFeerates
    val currentBlockHeight: Int get() = currentTip.first
    val keyManager: KeyManager get() = staticParams.nodeParams.keyManager
    val privateKey: PrivateKey get() = staticParams.nodeParams.nodePrivateKey

    val logger by eclairLogger()

    /**
     * @param event input event (for example, a message was received, a command was sent by the GUI/API, etc)
     * @return a (new state, list of actions) pair
     */
    abstract fun processInternal(event: ChannelEvent): Pair<ChannelState, List<ChannelAction>>

    fun process(event: ChannelEvent): Pair<ChannelState, List<ChannelAction>> {
        return try {
            val (newState, actions) = processInternal(event)
            Pair(newState, newState.updateActions(actions))
        } catch (t: Throwable) {
            handleLocalError(event, t)
        }
    }

    abstract fun handleLocalError(event: ChannelEvent, t: Throwable): Pair<ChannelState, List<ChannelAction>>

    internal fun unhandled(event: ChannelEvent): Pair<ChannelState, List<ChannelAction>> {
        logger.warning { "unhandled event $event in state ${this::class}" }
        return Pair(this, listOf())
    }

    /** Update outgoing messages to include an encrypted backup when necessary. */
    private fun updateActions(actions: List<ChannelAction>): List<ChannelAction> = when {
        this is ChannelStateWithCommitments && this.isZeroReserve -> actions.map {
            when {
                it is ChannelAction.Message.Send && it.message is FundingSigned -> it.copy(message = it.message.copy(channelData = Helpers.encrypt(privateKey.value, this).toByteVector()))
                it is ChannelAction.Message.Send && it.message is CommitSig -> it.copy(message = it.message.copy(channelData = Helpers.encrypt(privateKey.value, this).toByteVector()))
                it is ChannelAction.Message.Send && it.message is RevokeAndAck -> it.copy(message = it.message.copy(channelData = Helpers.encrypt(privateKey.value, this).toByteVector()))
                it is ChannelAction.Message.Send && it.message is ClosingSigned -> it.copy(message = it.message.copy(channelData = Helpers.encrypt(privateKey.value, this).toByteVector()))
                else -> it
            }
        }
        else -> actions
    }

    internal fun handleCommandError(cmd: Command, error: ChannelException, channelUpdate: ChannelUpdate? = null): Pair<ChannelState, List<ChannelAction>> {
        logger.warning(error) { "processing $cmd in state $this failed" }
        return when (cmd) {
            is CMD_ADD_HTLC -> Pair(this, listOf(ChannelAction.ProcessCmdRes.AddFailed(cmd, error, channelUpdate)))
            else -> Pair(this, listOf(ChannelAction.ProcessCmdRes.NotExecuted(cmd, error)))
        }
    }

    internal fun handleFundingPublishFailed(): Pair<ChannelState, List<ChannelAction.Message.Send>> {
        require(this is ChannelStateWithCommitments) { "$this must be type of HasCommitments" }
        logger.error { "failed to publish funding tx" }
        val exc = ChannelFundingError(channelId)
        val error = Error(channelId, exc.message)
        // NB: we don't use the handleLocalError handler because it would result in the commit tx being published, which we don't want:
        // implementation *guarantees* that in case of BITCOIN_FUNDING_PUBLISH_FAILED, the funding tx hasn't and will never be published, so we can close the channel right away
        // TODO context.system.eventStream.publish(ChannelErrorOccurred(self, Helpers.getChannelId(stateData), remoteNodeId, stateData, LocalError(exc), isFatal = true))
        return Pair(Aborted(staticParams, currentTip, currentOnChainFeerates), listOf(ChannelAction.Message.Send(error)))
    }

    internal fun handleFundingTimeout(): Pair<ChannelState, List<ChannelAction.Message.Send>> {
        require(this is ChannelStateWithCommitments) { "$this must be type of HasCommitments" }
        logger.warning { "funding tx hasn't been confirmed in time, cancelling channel delay=${Channel.FUNDING_TIMEOUT_FUNDEE}" }
        val exc = FundingTxTimedout(channelId)
        val error = Error(channelId, exc.message)
        // TODO context.system.eventStream.publish(ChannelErrorOccurred(self, Helpers.getChannelId(stateData), remoteNodeId, stateData, LocalError(exc), isFatal = true))
        return Pair(Aborted(staticParams, currentTip, currentOnChainFeerates), listOf(ChannelAction.Message.Send(error)))
    }

    internal fun handleRemoteSpentCurrent(commitTx: Transaction): Pair<Closing, List<ChannelAction>> {
        require(this is ChannelStateWithCommitments) { "$this must be type of HasCommitments" }
        logger.warning { "they published their current commit in txid=${commitTx.txid}" }
        require(commitTx.txid == commitments.remoteCommit.txid) { "txid mismatch" }

        val remoteCommitPublished = Helpers.Closing.claimRemoteCommitTxOutputs(keyManager, commitments, commitments.remoteCommit, commitTx, currentOnChainFeerates)

        val nextState = when (this) {
            is Closing -> this.copy(remoteCommitPublished = remoteCommitPublished)
            is Negotiating -> Closing(
                staticParams = staticParams,
                currentTip = currentTip,
                currentOnChainFeerates = currentOnChainFeerates,
                commitments = commitments,
                fundingTx = null,
                waitingSince = currentTimestampMillis(),
                mutualCloseProposed = closingTxProposed.flatten().map { it.unsignedTx },
                remoteCommitPublished = remoteCommitPublished
            )
            is WaitForFundingConfirmed -> Closing(
                staticParams = staticParams,
                currentTip = currentTip,
                currentOnChainFeerates = currentOnChainFeerates,
                commitments = commitments,
                fundingTx = fundingTx,
                waitingSince = currentTimestampMillis(),
                remoteCommitPublished = remoteCommitPublished
            )
            else -> Closing(
                staticParams = staticParams,
                currentTip = currentTip,
                currentOnChainFeerates = currentOnChainFeerates,
                commitments = commitments,
                fundingTx = null,
                waitingSince = currentTimestampMillis(),
                remoteCommitPublished = remoteCommitPublished
            )
        }

        return Pair(nextState, buildList {
            add(ChannelAction.Storage.StoreState(nextState))
            addAll(remoteCommitPublished.doPublish(channelId, staticParams.nodeParams.minDepthBlocks.toLong()))
        })
    }

    internal fun handleRemoteSpentNext(commitTx: Transaction): Pair<ChannelState, List<ChannelAction>> {
        require(this is ChannelStateWithCommitments) { "$this must be type of HasCommitments" }
        logger.warning { "they published their next commit in txid=${commitTx.txid}" }
        require(commitments.remoteNextCommitInfo.isLeft) { "next remote commit must be defined" }
        val remoteCommit = commitments.remoteNextCommitInfo.left?.nextRemoteCommit
        require(remoteCommit != null) { "remote commit must not be null" }
        require(commitTx.txid == remoteCommit.txid) { "txid mismatch" }

        val remoteCommitPublished = Helpers.Closing.claimRemoteCommitTxOutputs(keyManager, commitments, remoteCommit, commitTx, currentOnChainFeerates)

        val nextState = when (this) {
            is Closing -> copy(nextRemoteCommitPublished = remoteCommitPublished)
            is Negotiating -> Closing(
                staticParams = staticParams,
                currentTip = currentTip,
                currentOnChainFeerates = currentOnChainFeerates,
                commitments = commitments,
                fundingTx = null,
                waitingSince = currentTimestampMillis(),
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
                waitingSince = currentTimestampMillis(),
                nextRemoteCommitPublished = remoteCommitPublished
            )
        }

        return Pair(nextState, buildList {
            add(ChannelAction.Storage.StoreState(nextState))
            addAll(remoteCommitPublished.doPublish(channelId, staticParams.nodeParams.minDepthBlocks.toLong()))
        })
    }

    internal fun handleRemoteSpentOther(tx: Transaction): Pair<ChannelState, List<ChannelAction>> {
        require(this is ChannelStateWithCommitments) { "$this must be type of HasCommitments" }
        logger.warning { "funding tx spent in txid=${tx.txid}" }

        return Helpers.Closing.claimRevokedRemoteCommitTxOutputs(
            keyManager,
            commitments,
            tx,
            currentOnChainFeerates
        )?.let { revokedCommitPublished ->
            logger.warning { "txid=${tx.txid} was a revoked commitment, publishing the penalty tx" }
            val exc = FundingTxSpent(channelId, tx)
            val error = Error(channelId, exc.message)

            val nextState = when (this) {
                is Closing -> if (this.revokedCommitPublished.contains(revokedCommitPublished)) this
                else copy(revokedCommitPublished = this.revokedCommitPublished + revokedCommitPublished)
                is Negotiating -> Closing(
                    staticParams = staticParams,
                    currentTip = currentTip,
                    currentOnChainFeerates = currentOnChainFeerates,
                    commitments = commitments,
                    fundingTx = null,
                    waitingSince = currentTimestampMillis(),
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
                    waitingSince = currentTimestampMillis(),
                    revokedCommitPublished = listOf(revokedCommitPublished)
                )
            }

            return Pair(nextState, buildList {
                add(ChannelAction.Storage.StoreState(nextState))
                addAll(revokedCommitPublished.doPublish(channelId, staticParams.nodeParams.minDepthBlocks.toLong()))
                add(ChannelAction.Message.Send(error))
            })

        } ?: run {
            // the published tx was neither their current commitment nor a revoked one
            logger.error { "couldn't identify txid=${tx.txid}, something very bad is going on!!!" }
            Pair(ErrorInformationLeak(staticParams, currentTip, currentOnChainFeerates, commitments), listOf())
        }
    }

    internal fun doPublish(tx: Transaction, channelId: ByteVector32): List<ChannelAction.Blockchain> = listOf(
        ChannelAction.Blockchain.PublishTx(tx),
        ChannelAction.Blockchain.SendWatch(WatchConfirmed(channelId, tx, staticParams.nodeParams.minDepthBlocks.toLong(), BITCOIN_TX_CONFIRMED(tx)))
    )

    fun handleRemoteError(e: Error): Pair<ChannelState, List<ChannelAction>> {
        // see BOLT 1: only print out data verbatim if is composed of printable ASCII characters
        logger.error { "peer send error: ascii='${e.toAscii()}' bin=${e.data.toHex()}" }
        // TODO context.system.eventStream.publish(ChannelErrorOccurred(self, Helpers.getChannelId(stateData), remoteNodeId, stateData, RemoteError(e), isFatal = true))

        return when {
            this is Closing -> Pair(this, listOf()) // nothing to do, there is already a spending tx published
            this is Negotiating && this.bestUnpublishedClosingTx != null -> {
                val nexState = Closing(
                    staticParams = staticParams,
                    currentTip = currentTip,
                    currentOnChainFeerates = currentOnChainFeerates,
                    commitments = commitments,
                    fundingTx = null,
                    waitingSince = currentTimestampMillis(),
                    mutualCloseProposed = closingTxProposed.flatten().map { it.unsignedTx },
                    mutualClosePublished = listOfNotNull(bestUnpublishedClosingTx)
                )

                Pair(nexState, buildList {
                    add(ChannelAction.Storage.StoreState(nexState))
                    addAll(doPublish(bestUnpublishedClosingTx, nexState.channelId))
                })
            }
            // NB: we publish the commitment even if we have nothing at stake (in a dataloss situation our peer will send us an error just for that)
            this is ChannelStateWithCommitments -> spendLocalCurrent()
            // when there is no commitment yet, we just go to CLOSED state in case an error occurs
            else -> Pair(Aborted(staticParams, currentTip, currentOnChainFeerates), listOf())
        }
    }

    internal fun ChannelStateWithCommitments.spendLocalCurrent(): Pair<ChannelState, List<ChannelAction>> {
        val outdatedCommitment = when (this) {
            is WaitForRemotePublishFutureComitment -> true
            is Closing -> this.futureRemoteCommitPublished != null
            else -> false
        }

        return if (outdatedCommitment) {
            logger.warning { "we have an outdated commitment: will not publish our local tx" }
            Pair(this as ChannelState, listOf())
        } else {
            val commitTx = commitments.localCommit.publishableTxs.commitTx.tx
            val localCommitPublished = Helpers.Closing.claimCurrentLocalCommitTxOutputs(
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
                    waitingSince = currentTimestampMillis(),
                    mutualCloseProposed = closingTxProposed.flatten().map { it.unsignedTx },
                    localCommitPublished = localCommitPublished
                )
                is WaitForFundingConfirmed -> Closing(
                    staticParams = staticParams, currentTip = currentTip,
                    currentOnChainFeerates = currentOnChainFeerates,
                    commitments = commitments,
                    fundingTx = fundingTx,
                    waitingSince = currentTimestampMillis(),
                    localCommitPublished = localCommitPublished
                )
                else -> Closing(
                    staticParams = staticParams, currentTip = currentTip,
                    currentOnChainFeerates = currentOnChainFeerates,
                    commitments = commitments,
                    fundingTx = null,
                    waitingSince = currentTimestampMillis(),
                    localCommitPublished = localCommitPublished
                )
            }

            Pair(nextState, buildList {
                add(ChannelAction.Storage.StoreState(nextState))
                addAll(localCommitPublished.doPublish(channelId, staticParams.nodeParams.minDepthBlocks.toLong()))
            })
        }
    }
}

@Serializable
sealed class ChannelStateWithCommitments : ChannelState() {
    abstract val commitments: Commitments
    val channelId: ByteVector32 get() = commitments.channelId
    val isFunder: Boolean get() = commitments.localParams.isFunder
    val isZeroReserve: Boolean get() = commitments.isZeroReserve

    abstract fun updateCommitments(input: Commitments): ChannelStateWithCommitments

    companion object {
        val serializersModule = SerializersModule {
            polymorphic(ChannelStateWithCommitments::class) {
                subclass(Normal::class)
                subclass(WaitForFundingConfirmed::class)
                subclass(WaitForFundingLocked::class)
                subclass(WaitForRemotePublishFutureComitment::class)
                subclass(ShuttingDown::class)
                subclass(Negotiating::class)
                subclass(Closing::class)
            }
        }

        private val serializationModules = SerializersModule {
            include(Tlv.serializersModule)
            include(KeyManager.serializersModule)
            include(UpdateMessage.serializersModule)
        }

        @OptIn(ExperimentalSerializationApi::class)
        private val cbor = Cbor {
            serializersModule = serializationModules
        }

        @OptIn(ExperimentalSerializationApi::class)
        fun serialize(state: ChannelStateWithCommitments): ByteArray {
            return cbor.encodeToByteArray(ChannelState.serializer(), state as ChannelState)
        }

        @OptIn(ExperimentalSerializationApi::class)
        fun deserialize(bin: ByteArray): ChannelStateWithCommitments {
            return cbor.decodeFromByteArray<ChannelState>(bin) as ChannelStateWithCommitments
        }

        @OptIn(ExperimentalSerializationApi::class)
        fun deserialize(bin: ByteVector): ChannelStateWithCommitments = deserialize(bin.toByteArray())
    }
}

@Serializable
data class WaitForInit(override val staticParams: StaticParams, override val currentTip: Pair<Int, @Serializable(with = BlockHeaderKSerializer::class) BlockHeader>, override val currentOnChainFeerates: OnChainFeerates) : ChannelState() {
    override fun processInternal(event: ChannelEvent): Pair<ChannelState, List<ChannelAction>> {
        return when {
            event is ChannelEvent.InitFundee -> {
                val nextState = WaitForOpenChannel(staticParams, currentTip, currentOnChainFeerates, event.temporaryChannelId, event.localParams, event.remoteInit)
                Pair(nextState, listOf())
            }
            event is ChannelEvent.InitFunder -> {
                val fundingPubKey = keyManager.fundingPublicKey(event.localParams.fundingKeyPath).publicKey
                val channelKeyPath = keyManager.channelKeyPath(event.localParams, event.channelVersion)
                val paymentBasepoint = event.localParams.walletStaticPaymentBasepoint
                val open = OpenChannel(
                    staticParams.nodeParams.chainHash,
                    temporaryChannelId = event.temporaryChannelId,
                    fundingSatoshis = event.fundingAmount,
                    pushMsat = event.pushAmount,
                    dustLimitSatoshis = event.localParams.dustLimit,
                    maxHtlcValueInFlightMsat = event.localParams.maxHtlcValueInFlightMsat,
                    channelReserveSatoshis = event.localParams.channelReserve,
                    htlcMinimumMsat = event.localParams.htlcMinimum,
                    feeratePerKw = event.initialFeeratePerKw,
                    toSelfDelay = event.localParams.toSelfDelay,
                    maxAcceptedHtlcs = event.localParams.maxAcceptedHtlcs,
                    fundingPubkey = fundingPubKey,
                    revocationBasepoint = keyManager.revocationPoint(channelKeyPath).publicKey,
                    paymentBasepoint = paymentBasepoint,
                    delayedPaymentBasepoint = keyManager.delayedPaymentPoint(channelKeyPath).publicKey,
                    htlcBasepoint = keyManager.htlcPoint(channelKeyPath).publicKey,
                    firstPerCommitmentPoint = keyManager.commitmentPoint(channelKeyPath, 0),
                    channelFlags = event.channelFlags,
                    // In order to allow TLV extensions and keep backwards-compatibility, we include an empty upfront_shutdown_script.
                    // See https://github.com/lightningnetwork/lightning-rfc/pull/714.
                    tlvStream = TlvStream(
                        if (event.channelVersion.isSet(ChannelVersion.ZERO_RESERVE_BIT)) {
                            listOf(ChannelTlv.UpfrontShutdownScript(ByteVector.empty), ChannelTlv.ChannelVersionTlv(event.channelVersion))
                        } else {
                            listOf(ChannelTlv.UpfrontShutdownScript(ByteVector.empty))
                        }
                    )
                )
                val nextState = WaitForAcceptChannel(staticParams, currentTip, currentOnChainFeerates, event, open)
                Pair(nextState, listOf(ChannelAction.Message.Send(open)))
            }
            event is ChannelEvent.Restore && event.state is Closing && event.state.commitments.nothingAtStake() -> {
                logger.info { "we have nothing at stake, going straight to CLOSED" }
                Pair(Closed(event.state), listOf())
            }
            event is ChannelEvent.Restore && event.state is Closing -> {
                val closingType = event.state.closingTypeAlreadyKnown()
                logger.info { "channel is closing (closing type = ${closingType ?: "unknown yet"}" }
                // if the closing type is known:
                // - there is no need to watch the funding tx because it has already been spent and the spending tx has
                //   already reached mindepth
                // - there is no need to attempt to publish transactions for other type of closes
                when (closingType) {
                    is MutualClose -> {
                        Pair(event.state, doPublish(closingType.tx, event.state.channelId))
                    }
                    is LocalClose -> {
                        val actions = closingType.localCommitPublished.doPublish(event.state.channelId, event.state.staticParams.nodeParams.minDepthBlocks.toLong())
                        Pair(event.state, actions)
                    }
                    is RemoteClose -> {
                        val actions = closingType.remoteCommitPublished.doPublish(event.state.channelId, event.state.staticParams.nodeParams.minDepthBlocks.toLong())
                        Pair(event.state, actions)
                    }
                    is RevokedClose -> {
                        val actions = closingType.revokedCommitPublished.doPublish(event.state.channelId, event.state.staticParams.nodeParams.minDepthBlocks.toLong())
                        Pair(event.state, actions)
                    }
                    is RecoveryClose -> {
                        val actions = closingType.remoteCommitPublished.doPublish(event.state.channelId, event.state.staticParams.nodeParams.minDepthBlocks.toLong())
                        Pair(event.state, actions)
                    }
                    null -> {
                        // in all other cases we need to be ready for any type of closing
                        val commitments = event.state.commitments
                        val actions = mutableListOf<ChannelAction>(
                            ChannelAction.Blockchain.SendWatch(
                                WatchSpent(
                                    event.state.channelId,
                                    commitments.commitInput.outPoint.txid,
                                    commitments.commitInput.outPoint.index.toInt(),
                                    commitments.commitInput.txOut.publicKeyScript,
                                    BITCOIN_FUNDING_SPENT
                                )
                            ),
                            //SendWatch(WatchLost(event.state.channelId, commitments.commitInput.outPoint.txid, event.state.staticParams.nodeParams.minDepthBlocks.toLong(), BITCOIN_FUNDING_LOST))
                        )
                        val minDepth = event.state.staticParams.nodeParams.minDepthBlocks.toLong()
                        event.state.mutualClosePublished.forEach { actions.addAll(doPublish(it, event.state.channelId)) }
                        event.state.localCommitPublished?.run { actions.addAll(doPublish(event.state.channelId, minDepth)) }
                        event.state.remoteCommitPublished?.run { actions.addAll(doPublish(event.state.channelId, minDepth)) }
                        event.state.nextRemoteCommitPublished?.run { actions.addAll(doPublish(event.state.channelId, minDepth)) }
                        event.state.revokedCommitPublished.forEach { actions.addAll(it.doPublish(event.state.channelId, minDepth)) }
                        event.state.futureRemoteCommitPublished?.run { actions.addAll(doPublish(event.state.channelId, minDepth)) }
                        // if commitment number is zero, we also need to make sure that the funding tx has been published
                        @Suppress("ControlFlowWithEmptyBody")
                        if (commitments.localCommit.index == 0L && commitments.remoteCommit.index == 0L) {
                            // TODO ask watcher for the funding tx
                        }
                        Pair(event.state, actions)
                    }
                }
            }
            event is ChannelEvent.Restore && event.state is ChannelStateWithCommitments -> {
                logger.info { "restoring channel channelId=${event.state.channelId}" }
                val watchSpent = WatchSpent(
                    event.state.channelId,
                    event.state.commitments.commitInput.outPoint.txid,
                    event.state.commitments.commitInput.outPoint.index.toInt(),
                    event.state.commitments.commitInput.txOut.publicKeyScript,
                    BITCOIN_FUNDING_SPENT
                )
                val watchConfirmed = WatchConfirmed(
                    event.state.channelId,
                    event.state.commitments.commitInput.outPoint.txid,
                    event.state.commitments.commitInput.txOut.publicKeyScript,
                    staticParams.nodeParams.minDepthBlocks.toLong(),
                    BITCOIN_FUNDING_DEPTHOK
                )
                val actions = listOf(ChannelAction.Blockchain.SendWatch(watchSpent), ChannelAction.Blockchain.SendWatch(watchConfirmed))
                // TODO: ask watcher for the funding tx when restoring WaitForFundingConfirmed
                Pair(Offline(event.state), actions)
            }
            event is ChannelEvent.NewBlock -> Pair(this.copy(currentTip = Pair(event.height, event.Header)), listOf())
            event is ChannelEvent.SetOnChainFeerates -> Pair(this.copy(currentOnChainFeerates = event.feerates), listOf())
            event is ChannelEvent.ExecuteCommand && event.command is CMD_CLOSE -> Pair(Aborted(staticParams, currentTip, currentOnChainFeerates), listOf())
            else -> unhandled(event)
        }
    }

    override fun handleLocalError(event: ChannelEvent, t: Throwable): Pair<ChannelState, List<ChannelAction>> {
        logger.error(t) { "error on event $event in state ${this::class}" }
        return Pair(this, listOf(ChannelAction.ProcessLocalError(t, event)))
    }
}

@Serializable
data class Offline(val state: ChannelStateWithCommitments) : ChannelStateWithCommitments() {
    override val staticParams: StaticParams get() = state.staticParams
    override val currentTip: Pair<Int, BlockHeader> get() = state.currentTip
    override val currentOnChainFeerates: OnChainFeerates get() = state.currentOnChainFeerates
    override val commitments: Commitments get() = state.commitments

    override fun updateCommitments(input: Commitments): ChannelStateWithCommitments {
        return Offline(state.updateCommitments(input))
    }

    override fun processInternal(event: ChannelEvent): Pair<ChannelState, List<ChannelAction>> {
        logger.warning { "offline processing $event" }
        return when (event) {
            is ChannelEvent.Connected -> {
                when {
                    state is WaitForRemotePublishFutureComitment -> {
                        // they already proved that we have an outdated commitment
                        // there isn't much to do except asking them again to publish their current commitment by sending an error
                        val exc = PleasePublishYourCommitment(state.channelId)
                        val error = Error(state.channelId, exc.message)
                        val nextState = state.updateCommitments(state.commitments.updateFeatures(event.localInit, event.remoteInit))
                        Pair(nextState, listOf(ChannelAction.Message.Send(error)))
                    }
                    state.isZeroReserve -> {
                        logger.info { "syncing $state, waiting fo their channelReestablish message" }
                        val nextState = state.updateCommitments(state.commitments.updateFeatures(event.localInit, event.remoteInit))
                        Pair(Syncing(nextState, true), listOf())
                    }
                    else -> {
                        val yourLastPerCommitmentSecret = state.commitments.remotePerCommitmentSecrets.lastIndex?.let { state.commitments.remotePerCommitmentSecrets.getHash(it) } ?: ByteVector32.Zeroes
                        val channelKeyPath = keyManager.channelKeyPath(state.commitments.localParams, state.commitments.channelVersion)
                        val myCurrentPerCommitmentPoint = keyManager.commitmentPoint(channelKeyPath, state.commitments.localCommit.index)

                        val channelReestablish = ChannelReestablish(
                            channelId = state.channelId,
                            nextLocalCommitmentNumber = state.commitments.localCommit.index + 1,
                            nextRemoteRevocationNumber = state.commitments.remoteCommit.index,
                            yourLastCommitmentSecret = PrivateKey(yourLastPerCommitmentSecret),
                            myCurrentPerCommitmentPoint = myCurrentPerCommitmentPoint,
                            state.commitments.remoteChannelData
                        )
                        logger.info { "syncing $state" }
                        val nextState = state.updateCommitments(state.commitments.updateFeatures(event.localInit, event.remoteInit))
                        Pair(Syncing(nextState, false), listOf(ChannelAction.Message.Send(channelReestablish)))
                    }
                }
            }
            is ChannelEvent.WatchReceived -> {
                val watch = event.watch
                when {
                    watch is WatchEventSpent && state is Negotiating && state.closingTxProposed.flatten().map { it.unsignedTx.txid }.contains(watch.tx.txid) -> {
                        logger.info { "closing tx published: closingTxId=${watch.tx.txid}" }
                        val nextState = Closing(
                            staticParams,
                            currentTip,
                            currentOnChainFeerates,
                            state.commitments,
                            null,
                            currentTimestampMillis(),
                            state.closingTxProposed.flatten().map { it.unsignedTx },
                            listOf(watch.tx)
                        )
                        val actions = listOf(
                            ChannelAction.Storage.StoreState(nextState),
                            ChannelAction.Blockchain.PublishTx(watch.tx),
                            ChannelAction.Blockchain.SendWatch(WatchConfirmed(state.channelId, watch.tx, staticParams.nodeParams.minDepthBlocks.toLong(), BITCOIN_TX_CONFIRMED(watch.tx)))
                        )
                        Pair(nextState, actions)
                    }
                    watch is WatchEventSpent && watch.tx.txid == state.commitments.remoteCommit.txid -> {
                        state.handleRemoteSpentCurrent(watch.tx)
                    }
                    watch is WatchEventSpent && watch.tx.txid == state.commitments.remoteNextCommitInfo.left?.nextRemoteCommit?.txid -> {
                        state.handleRemoteSpentNext(watch.tx)
                    }
                    watch is WatchEventSpent && state is WaitForRemotePublishFutureComitment -> {
                        state.handleRemoteSpentFuture(watch.tx)
                    }
                    watch is WatchEventSpent -> {
                        state.handleRemoteSpentOther(watch.tx)
                    }
                    watch is WatchEventConfirmed && (watch.event is BITCOIN_FUNDING_DEPTHOK || watch.event is BITCOIN_FUNDING_DEEPLYBURIED) -> {
                        // just ignore this, we will put a new watch when we reconnect, and we'll be notified again
                        Pair(this, listOf())
                    }
                    else -> unhandled(event)
                }
            }
            is ChannelEvent.NewBlock -> {
                // TODO: is this the right thing to do ?
                val (newState, _) = state.process(event)
                Pair(Offline(newState as ChannelStateWithCommitments), listOf())
            }
            else -> unhandled(event)
        }
    }

    override fun handleLocalError(event: ChannelEvent, t: Throwable): Pair<ChannelState, List<ChannelAction>> {
        logger.error(t) { "error on event $event in state ${this::class}" }
        return Pair(this, listOf(ChannelAction.ProcessLocalError(t, event)))
    }
}

/**
 * waitForTheirReestablishMessage == true means that we want to wait until we've received their channel_reestablish message before
 * we send ours (for example, to extract encrypted backup data from extra fields)
 * waitForTheirReestablishMessage == false means that we've already sent our channel_reestablish message
 */
@Serializable
data class Syncing(val state: ChannelStateWithCommitments, val waitForTheirReestablishMessage: Boolean) : ChannelStateWithCommitments() {
    override val staticParams: StaticParams get() = state.staticParams
    override val currentTip: Pair<Int, BlockHeader> get() = state.currentTip
    override val currentOnChainFeerates: OnChainFeerates get() = state.currentOnChainFeerates
    override val commitments: Commitments get() = state.commitments

    override fun updateCommitments(input: Commitments): ChannelStateWithCommitments {
        return Syncing(state.updateCommitments(input), waitForTheirReestablishMessage)
    }

    override fun processInternal(event: ChannelEvent): Pair<ChannelState, List<ChannelAction>> {
        logger.warning { "syncing processing $event" }
        return when {
            event is ChannelEvent.MessageReceived && event.message is ChannelReestablish ->
                when {
                    waitForTheirReestablishMessage -> {
                        val nextState = if (!event.message.channelData.isEmpty()) {
                            logger.info { "channel_reestablish includes a peer backup" }
                            when (val decrypted = runTrying { Helpers.decrypt(state.staticParams.nodeParams.nodePrivateKey, event.message.channelData) }) {
                                is Try.Success -> {
                                    if (decrypted.get().commitments.isMoreRecent(state.commitments)) {
                                        logger.warning { "they have a more recent commitment, using it instead" }
                                        decrypted.get()
                                    } else {
                                        logger.info { "ignoring their older backup" }
                                        state
                                    }
                                }
                                is Try.Failure -> {
                                    logger.error(decrypted.error) { "ignoring unreadable channel data for channelId=${state.channelId}" }
                                    state
                                }
                            }
                        } else state

                        val yourLastPerCommitmentSecret = nextState.commitments.remotePerCommitmentSecrets.lastIndex?.let { nextState.commitments.remotePerCommitmentSecrets.getHash(it) } ?: ByteVector32.Zeroes
                        val channelKeyPath = keyManager.channelKeyPath(state.commitments.localParams, nextState.commitments.channelVersion)
                        val myCurrentPerCommitmentPoint = keyManager.commitmentPoint(channelKeyPath, nextState.commitments.localCommit.index)

                        val channelReestablish = ChannelReestablish(
                            channelId = nextState.channelId,
                            nextLocalCommitmentNumber = nextState.commitments.localCommit.index + 1,
                            nextRemoteRevocationNumber = nextState.commitments.remoteCommit.index,
                            yourLastCommitmentSecret = PrivateKey(yourLastPerCommitmentSecret),
                            myCurrentPerCommitmentPoint = myCurrentPerCommitmentPoint,
                            nextState.commitments.remoteChannelData
                        )
                        val actions = listOf<ChannelAction>(ChannelAction.Message.Send(channelReestablish))
                        // now apply their reestablish message to the restored state
                        val (nextState1, actions1) = Syncing(nextState, waitForTheirReestablishMessage = false).processInternal(event)
                        Pair(nextState1, actions + actions1)
                    }
                    state is WaitForFundingConfirmed -> {
                        val minDepth = if (state.commitments.localParams.isFunder) {
                            staticParams.nodeParams.minDepthBlocks
                        } else {
                            // when we're fundee we scale the min_depth confirmations depending on the funding amount
                            Helpers.minDepthForFunding(staticParams.nodeParams, state.commitments.commitInput.txOut.amount)
                        }
                        // we put back the watch (operation is idempotent) because the event may have been fired while we were in OFFLINE
                        val watchConfirmed = WatchConfirmed(
                            state.channelId,
                            state.commitments.commitInput.outPoint.txid,
                            state.commitments.commitInput.txOut.publicKeyScript,
                            minDepth.toLong(),
                            BITCOIN_FUNDING_DEPTHOK
                        )
                        val actions = listOf(ChannelAction.Blockchain.SendWatch(watchConfirmed))
                        Pair(state, actions)
                    }
                    state is WaitForFundingLocked -> {
                        logger.debug { "re-sending fundingLocked" }
                        val channelKeyPath = keyManager.channelKeyPath(state.commitments.localParams, state.commitments.channelVersion)
                        val nextPerCommitmentPoint = keyManager.commitmentPoint(channelKeyPath, 1)
                        val fundingLocked = FundingLocked(state.commitments.channelId, nextPerCommitmentPoint)
                        val actions = listOf(ChannelAction.Message.Send(fundingLocked))
                        Pair(state, actions)
                    }
                    state is Normal -> {
                        val channelKeyPath = keyManager.channelKeyPath(state.commitments.localParams, state.commitments.channelVersion)
                        when {
                            !Helpers.checkLocalCommit(state.commitments, event.message.nextRemoteRevocationNumber) -> {
                                // if next_remote_revocation_number is greater than our local commitment index, it means that either we are using an outdated commitment, or they are lying
                                // but first we need to make sure that the last per_commitment_secret that they claim to have received from us is correct for that next_remote_revocation_number minus 1
                                if (keyManager.commitmentSecret(channelKeyPath, event.message.nextRemoteRevocationNumber - 1) == event.message.yourLastCommitmentSecret) {
                                    logger.warning { "counterparty proved that we have an outdated (revoked) local commitment!!! ourCommitmentNumber=${state.commitments.localCommit.index} theirCommitmentNumber=${event.message.nextRemoteRevocationNumber}" }
                                    // their data checks out, we indeed seem to be using an old revoked commitment, and must absolutely *NOT* publish it, because that would be a cheating attempt and they
                                    // would punish us by taking all the funds in the channel
                                    val exc = PleasePublishYourCommitment(state.channelId)
                                    val error = Error(state.channelId, exc.message?.encodeToByteArray()?.toByteVector() ?: ByteVector.empty)
                                    val nextState = WaitForRemotePublishFutureComitment(staticParams, state.currentTip, state.currentOnChainFeerates, state.commitments, event.message)
                                    val actions = listOf(
                                        ChannelAction.Storage.StoreState(nextState),
                                        ChannelAction.Message.Send(error)
                                    )
                                    Pair(nextState, actions)
                                } else {
                                    // they lied! the last per_commitment_secret they claimed to have received from us is invalid
                                    logger.warning { "they lied! the last per_commitment_secret they claimed to have received from us is invalid" }
                                    //throw InvalidRevokedCommitProof(state.channelId, state.commitments.localCommit.index, nextRemoteRevocationNumber, yourLastPerCommitmentSecret)
                                    Pair(this, listOf())
                                }
                            }
                            !Helpers.checkRemoteCommit(state.commitments, event.message.nextLocalCommitmentNumber) -> {
                                // if next_local_commit_number is more than one more our remote commitment index, it means that either we are using an outdated commitment, or they are lying
                                logger.warning { "counterparty says that they have a more recent commitment than the one we know of!!! ourCommitmentNumber=${state.commitments.remoteNextCommitInfo.left?.nextRemoteCommit?.index ?: state.commitments.remoteCommit.index} theirCommitmentNumber=${event.message.nextLocalCommitmentNumber}" }
                                // there is no way to make sure that they are saying the truth, the best thing to do is ask them to publish their commitment right now
                                // maybe they will publish their commitment, in that case we need to remember their commitment point in order to be able to claim our outputs
                                // not that if they don't comply, we could publish our own commitment (it is not stale, otherwise we would be in the case above)
                                val exc = PleasePublishYourCommitment(state.channelId)
                                val error = Error(state.channelId, exc.message?.encodeToByteArray()?.toByteVector() ?: ByteVector.empty)
                                val nextState = WaitForRemotePublishFutureComitment(staticParams, state.currentTip, state.currentOnChainFeerates, state.commitments, event.message)
                                val actions = listOf(
                                    ChannelAction.Storage.StoreState(nextState),
                                    ChannelAction.Message.Send(error)
                                )
                                Pair(nextState, actions)
                            }
                            else -> {
                                // normal case, our data is up-to-date
                                val actions = ArrayList<ChannelAction>()
                                if (event.message.nextLocalCommitmentNumber == 1L && state.commitments.localCommit.index == 0L) {
                                    // If next_local_commitment_number is 1 in both the channel_reestablish it sent and received, then the node MUST retransmit funding_locked, otherwise it MUST NOT
                                    logger.debug { "re-sending fundingLocked" }
                                    val nextPerCommitmentPoint = keyManager.commitmentPoint(channelKeyPath, 1)
                                    val fundingLocked = FundingLocked(state.commitments.channelId, nextPerCommitmentPoint)
                                    actions.add(ChannelAction.Message.Send(fundingLocked))
                                }
                                val (commitments1, sendQueue1) = handleSync(event.message, state, keyManager, logger)
                                actions.addAll(sendQueue1)

                                // BOLT 2: A node if it has sent a previous shutdown MUST retransmit shutdown.
                                state.localShutdown?.let {
                                    logger.debug { "re-sending localShutdown" }
                                    actions.add(ChannelAction.Message.Send(it))
                                }

                                if (!state.buried) {
                                    // even if we were just disconnected/reconnected, we need to put back the watch because the event may have been
                                    // fired while we were in OFFLINE (if not, the operation is idempotent anyway)
                                    val watchConfirmed = WatchConfirmed(
                                        state.channelId,
                                        state.commitments.commitInput.outPoint.txid,
                                        state.commitments.commitInput.txOut.publicKeyScript,
                                        ANNOUNCEMENTS_MINCONF.toLong(),
                                        BITCOIN_FUNDING_DEEPLYBURIED
                                    )
                                    actions.add(ChannelAction.Blockchain.SendWatch(watchConfirmed))
                                }

                                // TODO: update fees if needed
                                logger.info { "switching to $state" }
                                Pair(state.copy(commitments = commitments1), actions)
                            }
                        }
                    }
                    // BOLT 2: A node if it has sent a previous shutdown MUST retransmit shutdown.
                    // negotiation restarts from the beginning, and is initialized by the funder
                    // note: in any case we still need to keep all previously sent closing_signed, because they may publish one of them
                    state is Negotiating && state.commitments.localParams.isFunder -> {
                        // we could use the last closing_signed we sent, but network fees may have changed while we were offline so it is better to restart from scratch
                        val (closingTx, closingSigned) = Helpers.Closing.makeFirstClosingTx(
                            keyManager,
                            state.commitments,
                            state.localShutdown.scriptPubKey.toByteArray(),
                            state.remoteShutdown.scriptPubKey.toByteArray(),
                            currentOnChainFeerates.mutualCloseFeeratePerKw
                        )
                        val closingTxProposed1 = state.closingTxProposed + listOf(listOf(ClosingTxProposed(closingTx.tx, closingSigned)))
                        val nextState = state.copy(closingTxProposed = closingTxProposed1)
                        val actions = listOf(
                            ChannelAction.Storage.StoreState(nextState),
                            ChannelAction.Message.Send(state.localShutdown),
                            ChannelAction.Message.Send(closingSigned)
                        )
                        return Pair(nextState, actions)
                    }
                    state is Negotiating -> {
                        // we start a new round of negotiation
                        val closingTxProposed1 = if (state.closingTxProposed.last().isEmpty()) state.closingTxProposed else state.closingTxProposed + listOf(listOf())
                        val nextState = state.copy(closingTxProposed = closingTxProposed1)
                        val actions = listOf(
                            ChannelAction.Storage.StoreState(nextState),
                            ChannelAction.Message.Send(state.localShutdown)
                        )
                        return Pair(nextState, actions)
                    }
                    else -> unhandled(event)
                }
            event is ChannelEvent.NewBlock -> {
                // TODO: is this the right thing to do ?
                val (newState, _) = state.process(event)
                Pair(Syncing(newState as ChannelStateWithCommitments, waitForTheirReestablishMessage), listOf())
            }
            else -> unhandled(event)
        }
    }

    override fun handleLocalError(event: ChannelEvent, t: Throwable): Pair<ChannelState, List<ChannelAction>> {
        logger.error(t) { "error on event $event in state ${this::class}" }
        return Pair(this, listOf(ChannelAction.ProcessLocalError(t, event)))
    }

}

@Serializable
data class WaitForRemotePublishFutureComitment(
    override val staticParams: StaticParams,
    override val currentTip: Pair<Int, @Serializable(with = BlockHeaderKSerializer::class) BlockHeader>,
    override val currentOnChainFeerates: OnChainFeerates,
    override val commitments: Commitments,
    val remoteChannelReestablish: ChannelReestablish
) : ChannelStateWithCommitments() {
    override fun updateCommitments(input: Commitments): ChannelStateWithCommitments = this.copy(commitments = input)

    override fun processInternal(event: ChannelEvent): Pair<ChannelState, List<ChannelAction>> {
        return when {
            event is ChannelEvent.WatchReceived && event.watch is WatchEventSpent && event.watch.event is BITCOIN_FUNDING_SPENT -> handleRemoteSpentFuture(event.watch.tx)
            else -> unhandled(event)
        }
    }

    override fun handleLocalError(event: ChannelEvent, t: Throwable): Pair<ChannelState, List<ChannelAction>> {
        logger.error(t) { "error on event $event in state ${this::class}" }
        val error = Error(channelId, t.message)
        return Pair(Aborted(staticParams, currentTip, currentOnChainFeerates), listOf(ChannelAction.Message.Send(error)))
    }

    internal fun handleRemoteSpentFuture(tx: Transaction): Pair<ChannelState, List<ChannelAction>> {
        logger.warning { "they published their future commit (because we asked them to) in txid=${tx.txid}" }
        return if (commitments.channelVersion.paysDirectlyToWallet) {
            val remoteCommitPublished = RemoteCommitPublished(tx, null, listOf(), listOf(), mapOf())
            val nextState = Closing(
                staticParams = staticParams,
                currentTip = currentTip,
                commitments = commitments,
                currentOnChainFeerates = currentOnChainFeerates,
                fundingTx = null,
                waitingSince = currentTimestampMillis(),
                remoteCommitPublished = remoteCommitPublished
            )
            Pair(nextState, listOf(ChannelAction.Storage.StoreState(nextState)))
        } else {
            val remotePerCommitmentPoint = remoteChannelReestablish.myCurrentPerCommitmentPoint
            val remoteCommitPublished = Helpers.Closing.claimRemoteCommitMainOutput(
                keyManager,
                commitments,
                remotePerCommitmentPoint,
                tx,
                currentOnChainFeerates.claimMainFeeratePerKw
            )
            val nextState = Closing(
                staticParams = staticParams,
                currentTip = currentTip,
                commitments = commitments,
                currentOnChainFeerates = currentOnChainFeerates,
                fundingTx = null,
                waitingSince = currentTimestampMillis(),
                futureRemoteCommitPublished = remoteCommitPublished
            )
            val actions = mutableListOf<ChannelAction>(ChannelAction.Storage.StoreState(nextState))
            actions.addAll(remoteCommitPublished.doPublish(channelId, staticParams.nodeParams.minDepthBlocks.toLong()))
            Pair(nextState, actions)
        }
    }
}

@Serializable
data class WaitForOpenChannel(
    override val staticParams: StaticParams,
    override val currentTip: Pair<Int, @Serializable(with = BlockHeaderKSerializer::class) BlockHeader>,
    override val currentOnChainFeerates: OnChainFeerates,
    @Serializable(with = ByteVector32KSerializer::class) val temporaryChannelId: ByteVector32,
    val localParams: LocalParams,
    val remoteInit: Init
) : ChannelState() {
    override fun processInternal(event: ChannelEvent): Pair<ChannelState, List<ChannelAction>> {
        return when {
            event is ChannelEvent.MessageReceived ->
                when (event.message) {
                    is OpenChannel -> {
                        require(Features.canUseFeature(localParams.features, Features.invoke(remoteInit.features), Feature.StaticRemoteKey)) {
                            "static_remote_key is not set"
                        }
                        val channelVersion = event.message.channelVersion ?: ChannelVersion.STANDARD
                        require(channelVersion.hasStaticRemotekey) { "invalid channel version $channelVersion (static_remote_key is not set)" }
                        require(channelVersion.hasAnchorOutputs) { "invalid channel version $channelVersion (anchor_outputs is not set)" }
                        when (val err = Helpers.validateParamsFundee(staticParams.nodeParams, event.message, channelVersion, currentOnChainFeerates.commitmentFeeratePerKw)) {
                            is Either.Left -> {
                                logger.error(err.value) { "invalid ${event.message} in state $this" }
                                return Pair(Aborted(staticParams, currentTip, currentOnChainFeerates), listOf(ChannelAction.Message.Send(Error(temporaryChannelId, err.value.message))))
                            }
                        }

                        val fundingPubkey = keyManager.fundingPublicKey(localParams.fundingKeyPath).publicKey
                        val channelKeyPath = keyManager.channelKeyPath(localParams, channelVersion)
                        // TODO: maybe also check uniqueness of temporary channel id
                        val minimumDepth = Helpers.minDepthForFunding(staticParams.nodeParams, event.message.fundingSatoshis)
                        val paymentBasepoint = localParams.walletStaticPaymentBasepoint
                        val accept = AcceptChannel(
                            temporaryChannelId = event.message.temporaryChannelId,
                            dustLimitSatoshis = localParams.dustLimit,
                            maxHtlcValueInFlightMsat = localParams.maxHtlcValueInFlightMsat,
                            channelReserveSatoshis = localParams.channelReserve,
                            minimumDepth = minimumDepth.toLong(),
                            htlcMinimumMsat = localParams.htlcMinimum,
                            toSelfDelay = localParams.toSelfDelay,
                            maxAcceptedHtlcs = localParams.maxAcceptedHtlcs,
                            fundingPubkey = fundingPubkey,
                            revocationBasepoint = keyManager.revocationPoint(channelKeyPath).publicKey,
                            paymentBasepoint = paymentBasepoint,
                            delayedPaymentBasepoint = keyManager.delayedPaymentPoint(channelKeyPath).publicKey,
                            htlcBasepoint = keyManager.htlcPoint(channelKeyPath).publicKey,
                            firstPerCommitmentPoint = keyManager.commitmentPoint(channelKeyPath, 0),
                            // In order to allow TLV extensions and keep backwards-compatibility, we include an empty upfront_shutdown_script.
                            // See https://github.com/lightningnetwork/lightning-rfc/pull/714.
                            tlvStream = TlvStream(listOf(ChannelTlv.UpfrontShutdownScript(ByteVector.empty)))
                        )
                        val remoteParams = RemoteParams(
                            nodeId = staticParams.remoteNodeId,
                            dustLimit = event.message.dustLimitSatoshis,
                            maxHtlcValueInFlightMsat = event.message.maxHtlcValueInFlightMsat,
                            channelReserve = event.message.channelReserveSatoshis, // remote requires local to keep this much satoshis as direct payment
                            htlcMinimum = event.message.htlcMinimumMsat,
                            toSelfDelay = event.message.toSelfDelay,
                            maxAcceptedHtlcs = event.message.maxAcceptedHtlcs,
                            fundingPubKey = event.message.fundingPubkey,
                            revocationBasepoint = event.message.revocationBasepoint,
                            paymentBasepoint = event.message.paymentBasepoint,
                            delayedPaymentBasepoint = event.message.delayedPaymentBasepoint,
                            htlcBasepoint = event.message.htlcBasepoint,
                            features = Features.invoke(remoteInit.features)
                        )
                        val nextState = WaitForFundingCreated(
                            staticParams,
                            currentTip,
                            currentOnChainFeerates,
                            event.message.temporaryChannelId,
                            localParams,
                            remoteParams,
                            event.message.fundingSatoshis,
                            event.message.pushMsat,
                            event.message.feeratePerKw,
                            event.message.firstPerCommitmentPoint,
                            event.message.channelFlags,
                            channelVersion,
                            accept
                        )
                        Pair(nextState, listOf(ChannelAction.Message.Send(accept)))
                    }
                    is Error -> {
                        logger.error { "peer send error: ascii=${event.message.toAscii()} bin=${event.message.data.toHex()}" }
                        return Pair(Aborted(staticParams, currentTip, currentOnChainFeerates), listOf())
                    }
                    else -> unhandled(event)
                }
            event is ChannelEvent.ExecuteCommand && event.command is CMD_CLOSE -> Pair(Aborted(staticParams, currentTip, currentOnChainFeerates), listOf())
            event is ChannelEvent.NewBlock -> Pair(this.copy(currentTip = Pair(event.height, event.Header)), listOf())
            event is ChannelEvent.SetOnChainFeerates -> Pair(this.copy(currentOnChainFeerates = event.feerates), listOf())
            else -> unhandled(event)
        }
    }

    override fun handleLocalError(event: ChannelEvent, t: Throwable): Pair<ChannelState, List<ChannelAction>> {
        logger.error(t) { "error on event $event in state ${this::class}" }
        val error = Error(temporaryChannelId, t.message)
        return Pair(Aborted(staticParams, currentTip, currentOnChainFeerates), listOf(ChannelAction.Message.Send(error)))
    }
}

@Serializable
data class WaitForFundingCreated(
    override val staticParams: StaticParams,
    override val currentTip: Pair<Int, @Serializable(with = BlockHeaderKSerializer::class) BlockHeader>,
    override val currentOnChainFeerates: OnChainFeerates,
    @Serializable(with = ByteVector32KSerializer::class) val temporaryChannelId: ByteVector32,
    val localParams: LocalParams,
    val remoteParams: RemoteParams,
    @Serializable(with = SatoshiKSerializer::class) val fundingAmount: Satoshi,
    val pushAmount: MilliSatoshi,
    val initialFeeratePerKw: Long,
    @Serializable(with = PublicKeyKSerializer::class) val remoteFirstPerCommitmentPoint: PublicKey,
    val channelFlags: Byte,
    val channelVersion: ChannelVersion,
    val lastSent: AcceptChannel
) : ChannelState() {
    override fun processInternal(event: ChannelEvent): Pair<ChannelState, List<ChannelAction>> {
        return when {
            event is ChannelEvent.MessageReceived ->
                when (event.message) {
                    is FundingCreated -> {
                        // they fund the channel with their funding tx, so the money is theirs (but we are paid pushMsat)
                        val firstCommitTxRes = Helpers.Funding.makeFirstCommitTxs(
                            keyManager,
                            channelVersion,
                            temporaryChannelId,
                            localParams,
                            remoteParams,
                            fundingAmount,
                            pushAmount,
                            initialFeeratePerKw,
                            event.message.fundingTxid,
                            event.message.fundingOutputIndex,
                            remoteFirstPerCommitmentPoint
                        )
                        when (firstCommitTxRes) {
                            is Either.Left -> {
                                logger.error(firstCommitTxRes.value) { "cannot create first commit tx" }
                                handleLocalError(event, firstCommitTxRes.value)
                            }
                            is Either.Right -> {
                                val firstCommitTx = firstCommitTxRes.value
                                // check remote signature validity
                                val fundingPubKey = keyManager.fundingPublicKey(localParams.fundingKeyPath)
                                val localSigOfLocalTx = keyManager.sign(firstCommitTx.localCommitTx, fundingPubKey)
                                val signedLocalCommitTx = Transactions.addSigs(
                                    firstCommitTx.localCommitTx,
                                    fundingPubKey.publicKey,
                                    remoteParams.fundingPubKey,
                                    localSigOfLocalTx,
                                    event.message.signature
                                )
                                when (val result = Transactions.checkSpendable(signedLocalCommitTx)) {
                                    is Try.Failure -> {
                                        logger.error(result.error) { "their first commit sig is not valid for ${firstCommitTx.localCommitTx.tx}" }
                                        handleLocalError(event, result.error)
                                    }
                                    is Try.Success -> {
                                        val localSigOfRemoteTx = keyManager.sign(firstCommitTx.remoteCommitTx, fundingPubKey)
                                        val channelId = Eclair.toLongId(event.message.fundingTxid, event.message.fundingOutputIndex)
                                        // watch the funding tx transaction
                                        val commitInput = firstCommitTx.localCommitTx.input
                                        val fundingSigned = FundingSigned(channelId, localSigOfRemoteTx)
                                        val commitments = Commitments(
                                            channelVersion,
                                            localParams,
                                            remoteParams,
                                            channelFlags,
                                            LocalCommit(0, firstCommitTx.localSpec, PublishableTxs(signedLocalCommitTx, listOf())),
                                            RemoteCommit(0, firstCommitTx.remoteSpec, firstCommitTx.remoteCommitTx.tx.txid, remoteFirstPerCommitmentPoint),
                                            LocalChanges(listOf(), listOf(), listOf()),
                                            RemoteChanges(listOf(), listOf(), listOf()),
                                            localNextHtlcId = 0L,
                                            remoteNextHtlcId = 0L,
                                            payments = mapOf(),
                                            remoteNextCommitInfo = Either.Right(Eclair.randomKey().publicKey()), // we will receive their next per-commitment point in the next message, so we temporarily put a random byte array
                                            commitInput,
                                            ShaChain.init,
                                            channelId = channelId
                                        )
                                        //context.system.eventStream.publish(ChannelIdAssigned(self, remoteNodeId, temporaryChannelId, channelId))
                                        //context.system.eventStream.publish(ChannelSignatureReceived(self, commitments))
                                        // NB: we don't send a ChannelSignatureSent for the first commit
                                        logger.info { "waiting for them to publish the funding tx for channelId=$channelId fundingTxid=${commitInput.outPoint.txid}" }
                                        // phoenix channels have a zero mindepth for funding tx
                                        val fundingMinDepth = if (commitments.isZeroReserve) 0 else Helpers.minDepthForFunding(staticParams.nodeParams, fundingAmount)
                                        logger.info { "$channelId will wait for $fundingMinDepth confirmations" }
                                        // TODO: should we wait for an acknowledgment from the watcher?
                                        val watchSpent = WatchSpent(channelId, commitInput.outPoint.txid, commitInput.outPoint.index.toInt(), commitments.commitInput.txOut.publicKeyScript, BITCOIN_FUNDING_SPENT)
                                        val watchConfirmed = WatchConfirmed(channelId, commitInput.outPoint.txid, commitments.commitInput.txOut.publicKeyScript, fundingMinDepth.toLong(), BITCOIN_FUNDING_DEPTHOK)
                                        val nextState = WaitForFundingConfirmed(staticParams, currentTip, currentOnChainFeerates, commitments, null, currentTimestampMillis() / 1000, null, Either.Right(fundingSigned))
                                        val actions = listOf(
                                            ChannelAction.Blockchain.SendWatch(watchSpent),
                                            ChannelAction.Blockchain.SendWatch(watchConfirmed),
                                            ChannelAction.Message.Send(fundingSigned),
                                            ChannelAction.ChannelId.IdSwitch(temporaryChannelId, channelId),
                                            ChannelAction.Storage.StoreState(nextState)
                                        )
                                        Pair(nextState, actions)
                                    }
                                }
                            }
                        }
                    }
                    is Error -> {
                        logger.error { "peer send error: ascii=${event.message.toAscii()} bin=${event.message.data.toHex()}" }
                        return Pair(Aborted(staticParams, currentTip, currentOnChainFeerates), listOf())
                    }
                    else -> unhandled(event)
                }
            event is ChannelEvent.ExecuteCommand && event.command is CMD_CLOSE -> Pair(Aborted(staticParams, currentTip, currentOnChainFeerates), listOf())
            event is ChannelEvent.NewBlock -> Pair(this.copy(currentTip = Pair(event.height, event.Header)), listOf())
            event is ChannelEvent.SetOnChainFeerates -> Pair(this.copy(currentOnChainFeerates = event.feerates), listOf())
            else -> unhandled(event)
        }
    }

    override fun handleLocalError(event: ChannelEvent, t: Throwable): Pair<ChannelState, List<ChannelAction>> {
        logger.error(t) { "error on event $event in state ${this::class}" }
        val error = Error(temporaryChannelId, t.message)
        return Pair(Aborted(staticParams, currentTip, currentOnChainFeerates), listOf(ChannelAction.Message.Send(error)))
    }
}

@Serializable
data class WaitForAcceptChannel(
    override val staticParams: StaticParams,
    override val currentTip: Pair<Int, @Serializable(with = BlockHeaderKSerializer::class) BlockHeader>,
    override val currentOnChainFeerates: OnChainFeerates,
    val initFunder: ChannelEvent.InitFunder,
    val lastSent: OpenChannel
) : ChannelState() {
    override fun processInternal(event: ChannelEvent): Pair<ChannelState, List<ChannelAction>> {
        return when {
            event is ChannelEvent.MessageReceived && event.message is AcceptChannel -> {
                when (val err = Helpers.validateParamsFunder(staticParams.nodeParams, lastSent, event.message)) {
                    is Either.Left -> {
                        logger.error(err.value) { "invalid ${event.message} in state $this" }
                        return Pair(Aborted(staticParams, currentTip, currentOnChainFeerates), listOf(ChannelAction.Message.Send(Error(initFunder.temporaryChannelId, err.value.message))))
                    }
                }
                // TODO: check equality of temporaryChannelId? or should be done upstream
                val remoteParams = RemoteParams(
                    nodeId = staticParams.remoteNodeId,
                    dustLimit = event.message.dustLimitSatoshis,
                    maxHtlcValueInFlightMsat = event.message.maxHtlcValueInFlightMsat,
                    channelReserve = event.message.channelReserveSatoshis, // remote requires local to keep this much satoshis as direct payment
                    htlcMinimum = event.message.htlcMinimumMsat,
                    toSelfDelay = event.message.toSelfDelay,
                    maxAcceptedHtlcs = event.message.maxAcceptedHtlcs,
                    fundingPubKey = event.message.fundingPubkey,
                    revocationBasepoint = event.message.revocationBasepoint,
                    paymentBasepoint = event.message.paymentBasepoint,
                    delayedPaymentBasepoint = event.message.delayedPaymentBasepoint,
                    htlcBasepoint = event.message.htlcBasepoint,
                    features = Features(initFunder.remoteInit.features)
                )
                logger.debug { "remote params: $remoteParams" }
                val localFundingPubkey = keyManager.fundingPublicKey(initFunder.localParams.fundingKeyPath)
                val fundingPubkeyScript = ByteVector(Script.write(Script.pay2wsh(Scripts.multiSig2of2(localFundingPubkey.publicKey, remoteParams.fundingPubKey))))
                val makeFundingTx = ChannelAction.Blockchain.MakeFundingTx(fundingPubkeyScript, initFunder.fundingAmount, initFunder.fundingTxFeeratePerKw)
                val nextState = WaitForFundingInternal(
                    staticParams,
                    currentTip,
                    currentOnChainFeerates,
                    initFunder.temporaryChannelId,
                    initFunder.localParams,
                    remoteParams,
                    initFunder.fundingAmount,
                    initFunder.pushAmount,
                    initFunder.initialFeeratePerKw,
                    event.message.firstPerCommitmentPoint,
                    initFunder.channelVersion,
                    lastSent
                )
                Pair(nextState, listOf(makeFundingTx))
            }
            event is ChannelEvent.MessageReceived && event.message is Error -> {
                logger.error { "peer send error: ascii=${event.message.toAscii()} bin=${event.message.data.toHex()}" }
                Pair(Aborted(staticParams, currentTip, currentOnChainFeerates), listOf())
            }
            event is ChannelEvent.ExecuteCommand && event.command is CMD_CLOSE -> Pair(Aborted(staticParams, currentTip, currentOnChainFeerates), listOf())
            event is ChannelEvent.NewBlock -> Pair(this.copy(currentTip = Pair(event.height, event.Header)), listOf())
            event is ChannelEvent.SetOnChainFeerates -> Pair(this.copy(currentOnChainFeerates = event.feerates), listOf())
            else -> unhandled(event)
        }
    }

    override fun handleLocalError(event: ChannelEvent, t: Throwable): Pair<ChannelState, List<ChannelAction>> {
        logger.error(t) { "error on event $event in state ${this::class}" }
        val error = Error(initFunder.temporaryChannelId, t.message)
        return Pair(Aborted(staticParams, currentTip, currentOnChainFeerates), listOf(ChannelAction.Message.Send(error)))
    }
}

@Serializable
data class WaitForFundingInternal(
    override val staticParams: StaticParams,
    override val currentTip: Pair<Int, @Serializable(with = BlockHeaderKSerializer::class) BlockHeader>,
    override val currentOnChainFeerates: OnChainFeerates,
    @Serializable(with = ByteVector32KSerializer::class) val temporaryChannelId: ByteVector32,
    val localParams: LocalParams,
    val remoteParams: RemoteParams,
    @Serializable(with = SatoshiKSerializer::class) val fundingAmount: Satoshi,
    val pushAmount: MilliSatoshi,
    val initialFeeratePerKw: Long,
    @Serializable(with = PublicKeyKSerializer::class) val remoteFirstPerCommitmentPoint: PublicKey,
    val channelVersion: ChannelVersion,
    val lastSent: OpenChannel
) : ChannelState() {
    override fun processInternal(event: ChannelEvent): Pair<ChannelState, List<ChannelAction>> {
        return when {
            event is ChannelEvent.MakeFundingTxResponse -> {
                // let's create the first commitment tx that spends the yet uncommitted funding tx
                val firstCommitTxRes = Helpers.Funding.makeFirstCommitTxs(
                    keyManager,
                    channelVersion,
                    temporaryChannelId,
                    localParams,
                    remoteParams,
                    fundingAmount,
                    pushAmount,
                    initialFeeratePerKw,
                    event.fundingTx.hash,
                    event.fundingTxOutputIndex,
                    remoteFirstPerCommitmentPoint
                )
                when (firstCommitTxRes) {
                    is Either.Left -> {
                        logger.error(firstCommitTxRes.value) { "cannot create first commit tx" }
                        handleLocalError(event, firstCommitTxRes.value)
                    }
                    is Either.Right -> {
                        val firstCommitTx = firstCommitTxRes.value
                        require(event.fundingTx.txOut[event.fundingTxOutputIndex].publicKeyScript == firstCommitTx.localCommitTx.input.txOut.publicKeyScript) { "pubkey script mismatch!" }
                        val localSigOfRemoteTx = keyManager.sign(firstCommitTx.remoteCommitTx, keyManager.fundingPublicKey(localParams.fundingKeyPath))
                        // signature of their initial commitment tx that pays remote pushMsat
                        val fundingCreated = FundingCreated(
                            temporaryChannelId = temporaryChannelId,
                            fundingTxid = event.fundingTx.hash,
                            fundingOutputIndex = event.fundingTxOutputIndex,
                            signature = localSigOfRemoteTx
                        )
                        val channelId = Eclair.toLongId(event.fundingTx.hash, event.fundingTxOutputIndex)
                        val channelIdAssigned = ChannelAction.ChannelId.IdAssigned(staticParams.remoteNodeId, temporaryChannelId, channelId) // we notify the peer asap so it knows how to route messages
                        //context.system.eventStream.publish(ChannelIdAssigned(self, remoteNodeId, temporaryChannelId, channelId))
                        // NB: we don't send a ChannelSignatureSent for the first commit
                        val nextState = WaitForFundingSigned(
                            staticParams,
                            currentTip,
                            currentOnChainFeerates,
                            channelId,
                            localParams,
                            remoteParams,
                            event.fundingTx,
                            event.fee,
                            firstCommitTx.localSpec,
                            firstCommitTx.localCommitTx,
                            RemoteCommit(0, firstCommitTx.remoteSpec, firstCommitTx.remoteCommitTx.tx.txid, remoteFirstPerCommitmentPoint),
                            lastSent.channelFlags,
                            channelVersion,
                            fundingCreated
                        )
                        Pair(nextState, listOf(channelIdAssigned, ChannelAction.Message.Send(fundingCreated)))
                    }
                }
            }
            event is ChannelEvent.MessageReceived && event.message is Error -> {
                logger.error { "peer send error: ascii=${event.message.toAscii()} bin=${event.message.data.toHex()}" }
                Pair(Aborted(staticParams, currentTip, currentOnChainFeerates), listOf())
            }
            event is ChannelEvent.ExecuteCommand && event.command is CMD_CLOSE -> Pair(Aborted(staticParams, currentTip, currentOnChainFeerates), listOf())
            event is ChannelEvent.NewBlock -> Pair(this.copy(currentTip = Pair(event.height, event.Header)), listOf())
            event is ChannelEvent.SetOnChainFeerates -> Pair(this.copy(currentOnChainFeerates = event.feerates), listOf())
            else -> unhandled(event)
        }
    }

    override fun handleLocalError(event: ChannelEvent, t: Throwable): Pair<ChannelState, List<ChannelAction>> {
        logger.error(t) { "error on event $event in state ${this::class}" }
        val error = Error(temporaryChannelId, t.message)
        return Pair(Aborted(staticParams, currentTip, currentOnChainFeerates), listOf(ChannelAction.Message.Send(error)))
    }
}

@Serializable
data class WaitForFundingSigned(
    override val staticParams: StaticParams,
    override val currentTip: Pair<Int, @Serializable(with = BlockHeaderKSerializer::class) BlockHeader>,
    override val currentOnChainFeerates: OnChainFeerates,
    @Serializable(with = ByteVector32KSerializer::class) val channelId: ByteVector32,
    val localParams: LocalParams,
    val remoteParams: RemoteParams,
    @Serializable(with = TransactionKSerializer::class) val fundingTx: Transaction,
    @Serializable(with = SatoshiKSerializer::class) val fundingTxFee: Satoshi,
    val localSpec: CommitmentSpec,
    val localCommitTx: Transactions.TransactionWithInputInfo.CommitTx,
    val remoteCommit: RemoteCommit,
    val channelFlags: Byte,
    val channelVersion: ChannelVersion,
    val lastSent: FundingCreated
) : ChannelState() {
    override fun processInternal(event: ChannelEvent): Pair<ChannelState, List<ChannelAction>> {
        return when {
            event is ChannelEvent.MessageReceived && event.message is FundingSigned -> {
                // we make sure that their sig checks out and that our first commit tx is spendable
                val fundingPubKey = keyManager.fundingPublicKey(localParams.fundingKeyPath)
                val localSigOfLocalTx = keyManager.sign(localCommitTx, fundingPubKey)
                val signedLocalCommitTx = Transactions.addSigs(localCommitTx, fundingPubKey.publicKey, remoteParams.fundingPubKey, localSigOfLocalTx, event.message.signature)
                when (Transactions.checkSpendable(signedLocalCommitTx)) {
                    is Try.Failure -> handleLocalError(event, InvalidCommitmentSignature(channelId, signedLocalCommitTx.tx))
                    is Try.Success -> {
                        val commitInput = localCommitTx.input
                        val commitments = Commitments(
                            channelVersion, localParams, remoteParams, channelFlags,
                            LocalCommit(0, localSpec, PublishableTxs(signedLocalCommitTx, listOf())), remoteCommit,
                            LocalChanges(listOf(), listOf(), listOf()), RemoteChanges(listOf(), listOf(), listOf()),
                            localNextHtlcId = 0L, remoteNextHtlcId = 0L,
                            payments = mapOf(),
                            remoteNextCommitInfo = Either.Right(Eclair.randomKey().publicKey()), // we will receive their next per-commitment point in the next message, so we temporarily put a random byte array
                            commitInput, ShaChain.init, channelId, event.message.channelData
                        )
                        logger.info { "publishing funding tx for channelId=$channelId fundingTxid=${commitInput.outPoint.txid}" }
                        val watchSpent = WatchSpent(
                            this.channelId,
                            commitments.commitInput.outPoint.txid,
                            commitments.commitInput.outPoint.index.toInt(),
                            commitments.commitInput.txOut.publicKeyScript,
                            BITCOIN_FUNDING_SPENT
                        ) // TODO: should we wait for an acknowledgment from the watcher?
                        // phoenix channels have a zero mindepth for funding tx
                        val minDepthBlocks = if (commitments.channelVersion.isSet(ChannelVersion.ZERO_RESERVE_BIT)) 0 else staticParams.nodeParams.minDepthBlocks
                        val watchConfirmed = WatchConfirmed(
                            this.channelId,
                            commitments.commitInput.outPoint.txid,
                            commitments.commitInput.txOut.publicKeyScript,
                            minDepthBlocks.toLong(),
                            BITCOIN_FUNDING_DEPTHOK
                        )
                        logger.info { "committing txid=${fundingTx.txid}" }

                        val nextState = WaitForFundingConfirmed(staticParams, currentTip, currentOnChainFeerates, commitments, fundingTx, currentTimestampMillis(), null, Either.Left(lastSent))
                        val actions = listOf(
                            ChannelAction.Blockchain.SendWatch(watchSpent),
                            ChannelAction.Blockchain.SendWatch(watchConfirmed),
                            ChannelAction.Storage.StoreState(nextState),
                            // we will publish the funding tx only after the channel state has been written to disk because we want to
                            // make sure we first persist the commitment that returns back the funds to us in case of problem
                            ChannelAction.Blockchain.PublishTx(fundingTx)
                        )
                        Pair(nextState, actions)
                    }
                }
            }
            event is ChannelEvent.MessageReceived && event.message is Error -> {
                logger.error { "peer send error: ascii=${event.message.toAscii()} bin=${event.message.data.toHex()}" }
                Pair(Aborted(staticParams, currentTip, currentOnChainFeerates), listOf())
            }
            event is ChannelEvent.ExecuteCommand && event.command is CMD_CLOSE -> Pair(Aborted(staticParams, currentTip, currentOnChainFeerates), listOf())
            event is ChannelEvent.NewBlock -> Pair(this.copy(currentTip = Pair(event.height, event.Header)), listOf())
            event is ChannelEvent.SetOnChainFeerates -> Pair(this.copy(currentOnChainFeerates = event.feerates), listOf())
            else -> unhandled(event)
        }
    }

    override fun handleLocalError(event: ChannelEvent, t: Throwable): Pair<ChannelState, List<ChannelAction>> {
        logger.error(t) { "error on event $event in state ${this::class}" }
        val error = Error(channelId, t.message)
        return Pair(Aborted(staticParams, currentTip, currentOnChainFeerates), listOf(ChannelAction.Message.Send(error)))
    }
}

@Serializable
data class WaitForFundingConfirmed(
    override val staticParams: StaticParams,
    override val currentTip: Pair<Int, @Serializable(with = BlockHeaderKSerializer::class) BlockHeader>,
    override val currentOnChainFeerates: OnChainFeerates,
    override val commitments: Commitments,
    @Serializable(with = TransactionKSerializer::class) val fundingTx: Transaction?,
    val waitingSince: Long, // how long have we been waiting for the funding tx to confirm
    val deferred: FundingLocked?,
    val lastSent: Either<FundingCreated, FundingSigned>
) : ChannelStateWithCommitments() {
    override fun updateCommitments(input: Commitments): ChannelStateWithCommitments = this.copy(commitments = input)

    override fun processInternal(event: ChannelEvent): Pair<ChannelState, List<ChannelAction>> {
        return when (event) {
            is ChannelEvent.MessageReceived -> when (event.message) {
                is FundingLocked -> Pair(this.copy(deferred = event.message), listOf())
                is Error -> handleRemoteError(event.message)
                else -> Pair(this, listOf())
            }
            is ChannelEvent.WatchReceived ->
                when (event.watch) {
                    is WatchEventConfirmed -> {
                        val result = runTrying {
                            Transaction.correctlySpends(
                                commitments.localCommit.publishableTxs.commitTx.tx,
                                listOf(event.watch.tx),
                                ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS
                            )
                        }
                        if (result is Try.Failure) {
                            logger.error { "funding tx verification failed: ${result.error}" }
                            return handleLocalError(event, InvalidCommitmentSignature(channelId, event.watch.tx))
                        }
                        val watchLost = WatchLost(this.channelId, commitments.commitInput.outPoint.txid, staticParams.nodeParams.minDepthBlocks.toLong(), BITCOIN_FUNDING_LOST)
                        val channelKeyPath = keyManager.channelKeyPath(commitments.localParams, commitments.channelVersion)
                        val nextPerCommitmentPoint = keyManager.commitmentPoint(channelKeyPath, 1)
                        val fundingLocked = FundingLocked(commitments.channelId, nextPerCommitmentPoint)
                        // this is the temporary channel id that we will use in our channel_update message, the goal is to be able to use our channel
                        // as soon as it reaches NORMAL state, and before it is announced on the network
                        // (this id might be updated when the funding tx gets deeply buried, if there was a reorg in the meantime)
                        val blockHeight = event.watch.blockHeight
                        val txIndex = event.watch.txIndex
                        val shortChannelId = ShortChannelId(blockHeight, txIndex, commitments.commitInput.outPoint.index.toInt())
                        val nextState = WaitForFundingLocked(staticParams, currentTip, currentOnChainFeerates, commitments, shortChannelId, fundingLocked)
                        val actions = listOf(
                            ChannelAction.Blockchain.SendWatch(watchLost),
                            ChannelAction.Message.Send(fundingLocked),
                            ChannelAction.Storage.StoreState(nextState)
                        )
                        if (deferred != null) {
                            logger.info { "FundingLocked has already been received" }
                            val resultPair = nextState.process(ChannelEvent.MessageReceived(deferred))
                            Pair(resultPair.first, actions + resultPair.second)
                        } else {
                            Pair(nextState, actions)
                        }
                    }
                    is WatchEventSpent -> when (event.watch.tx.txid) {
                        commitments.remoteCommit.txid -> handleRemoteSpentCurrent(event.watch.tx)
                        else -> TODO("handle information leak")
                    }
                    else -> unhandled(event)
                }
            is ChannelEvent.NewBlock -> Pair(this.copy(currentTip = Pair(event.height, event.Header)), listOf())
            is ChannelEvent.SetOnChainFeerates -> Pair(this.copy(currentOnChainFeerates = event.feerates), listOf())
            is ChannelEvent.Disconnected -> Pair(Offline(this), listOf())
            else -> unhandled(event)
        }
    }

    override fun handleLocalError(event: ChannelEvent, t: Throwable): Pair<ChannelState, List<ChannelAction>> {
        logger.error(t) { "error on event $event in state ${this::class}" }
        val error = Error(channelId, t.message)
        return Pair(Aborted(staticParams, currentTip, currentOnChainFeerates), listOf(ChannelAction.Message.Send(error)))
    }
}

@Serializable
data class WaitForFundingLocked(
    override val staticParams: StaticParams,
    override val currentTip: Pair<Int, @Serializable(with = BlockHeaderKSerializer::class) BlockHeader>,
    override val currentOnChainFeerates: OnChainFeerates,
    override val commitments: Commitments,
    val shortChannelId: ShortChannelId,
    val lastSent: FundingLocked
) : ChannelStateWithCommitments() {
    override fun updateCommitments(input: Commitments): ChannelStateWithCommitments = this.copy(commitments = input)

    override fun processInternal(event: ChannelEvent): Pair<ChannelState, List<ChannelAction>> {
        return when (event) {
            is ChannelEvent.MessageReceived -> when (event.message) {
                is FundingLocked -> {
                    // used to get the final shortChannelId, used in announcements (if minDepth >= ANNOUNCEMENTS_MINCONF this event will fire instantly)
                    val watchConfirmed = WatchConfirmed(
                        this.channelId,
                        commitments.commitInput.outPoint.txid,
                        commitments.commitInput.txOut.publicKeyScript,
                        ANNOUNCEMENTS_MINCONF.toLong(),
                        BITCOIN_FUNDING_DEEPLYBURIED
                    )
                    // we create a channel_update early so that we can use it to send payments through this channel, but it won't be propagated to other nodes since the channel is not yet announced
                    val initialChannelUpdate = Announcements.makeChannelUpdate(
                        staticParams.nodeParams.chainHash,
                        staticParams.nodeParams.nodePrivateKey,
                        staticParams.remoteNodeId,
                        shortChannelId,
                        staticParams.nodeParams.expiryDeltaBlocks,
                        commitments.remoteParams.htlcMinimum,
                        staticParams.nodeParams.feeBase,
                        staticParams.nodeParams.feeProportionalMillionth.toLong(),
                        commitments.localCommit.spec.totalFunds,
                        enable = Helpers.aboveReserve(commitments)
                    )
                    // we need to periodically re-send channel updates, otherwise channel will be considered stale and get pruned by network
                    // TODO: context.system.scheduler.schedule(initialDelay = REFRESH_CHANNEL_UPDATE_INTERVAL, interval = REFRESH_CHANNEL_UPDATE_INTERVAL, receiver = self, message = BroadcastChannelUpdate(PeriodicRefresh))
                    val nextState = Normal(
                        staticParams,
                        currentTip,
                        currentOnChainFeerates,
                        commitments.copy(remoteNextCommitInfo = Either.Right(event.message.nextPerCommitmentPoint)),
                        shortChannelId,
                        buried = false,
                        null,
                        initialChannelUpdate,
                        null,
                        null
                    )
                    val actions = listOf(
                        ChannelAction.Blockchain.SendWatch(watchConfirmed),
                        ChannelAction.Storage.StoreState(nextState)
                    )
                    Pair(nextState, actions)
                }
                is Error -> handleRemoteError(event.message)
                else -> unhandled(event)
            }
            is ChannelEvent.NewBlock -> Pair(this.copy(currentTip = Pair(event.height, event.Header)), listOf())
            is ChannelEvent.SetOnChainFeerates -> Pair(this.copy(currentOnChainFeerates = event.feerates), listOf())
            is ChannelEvent.Disconnected -> Pair(Offline(this), listOf())
            else -> unhandled(event)
        }
    }

    override fun handleLocalError(event: ChannelEvent, t: Throwable): Pair<ChannelState, List<ChannelAction>> {
        logger.error(t) { "error on event $event in state ${this::class}" }
        val error = Error(channelId, t.message)
        return Pair(Aborted(staticParams, currentTip, currentOnChainFeerates), listOf(ChannelAction.Message.Send(error)))
    }
}

@Serializable
data class Normal(
    override val staticParams: StaticParams,
    override val currentTip: Pair<Int, @Serializable(with = BlockHeaderKSerializer::class) BlockHeader>,
    override val currentOnChainFeerates: OnChainFeerates,
    override val commitments: Commitments,
    val shortChannelId: ShortChannelId,
    val buried: Boolean,
    val channelAnnouncement: ChannelAnnouncement?,
    val channelUpdate: ChannelUpdate,
    val localShutdown: Shutdown?,
    val remoteShutdown: Shutdown?
) : ChannelStateWithCommitments() {
    override fun updateCommitments(input: Commitments): ChannelStateWithCommitments = this.copy(commitments = input)

    override fun processInternal(event: ChannelEvent): Pair<ChannelState, List<ChannelAction>> {
        return when (event) {
            is ChannelEvent.ExecuteCommand -> {
                when (event.command) {
                    is CMD_ADD_HTLC -> {
                        if (localShutdown != null || remoteShutdown != null) {
                            // note: spec would allow us to keep sending new htlcs after having received their shutdown (and not sent ours)
                            // but we want to converge as fast as possible and they would probably not route them anyway
                            val error = NoMoreHtlcsClosingInProgress(channelId)
                            return handleCommandError(event.command, error, channelUpdate)
                        }
                        handleCommandResult(event.command, commitments.sendAdd(event.command, event.command.paymentId, currentBlockHeight.toLong()), event.command.commit)
                    }
                    is CMD_FULFILL_HTLC -> handleCommandResult(event.command, commitments.sendFulfill(event.command), event.command.commit)
                    is CMD_FAIL_HTLC -> handleCommandResult(event.command, commitments.sendFail(event.command, this.privateKey), event.command.commit)
                    is CMD_FAIL_MALFORMED_HTLC -> handleCommandResult(event.command, commitments.sendFailMalformed(event.command), event.command.commit)
                    is CMD_UPDATE_FEE -> handleCommandResult(event.command, commitments.sendFee(event.command), event.command.commit)
                    is CMD_SIGN -> when {
                        !commitments.localHasChanges() -> {
                            logger.warning { "no changes to sign" }
                            Pair(this, listOf())
                        }
                        commitments.remoteNextCommitInfo is Either.Left -> {
                            val commitments1 = commitments.copy(remoteNextCommitInfo = Either.Left(commitments.remoteNextCommitInfo.left!!.copy(reSignAsap = true)))
                            Pair(this.copy(commitments = commitments1), listOf())
                        }
                        else -> when (val result = commitments.sendCommit(keyManager, logger)) {
                            is Either.Left -> handleCommandError(event.command, result.value, channelUpdate)
                            is Either.Right -> {
                                val commitments1 = result.value.first
                                val nextRemoteCommit = commitments1.remoteNextCommitInfo.left!!.nextRemoteCommit
                                val nextCommitNumber = nextRemoteCommit.index
                                // we persist htlc data in order to be able to claim htlc outputs in case a revoked tx is published by our
                                // counterparty, so only htlcs above remote's dust_limit matter
                                val trimmedHtlcs = Transactions.trimOfferedHtlcs(commitments.remoteParams.dustLimit, nextRemoteCommit.spec) + Transactions.trimReceivedHtlcs(commitments.remoteParams.dustLimit, nextRemoteCommit.spec)
                                val htlcInfos = trimmedHtlcs.map { it.add }.map {
                                    logger.info { "adding paymentHash=${it.paymentHash} cltvExpiry=${it.cltvExpiry} to htlcs db for commitNumber=$nextCommitNumber" }
                                    ChannelAction.Storage.HtlcInfo(channelId, nextCommitNumber, it.paymentHash, it.cltvExpiry)
                                }
                                val nextState = this.copy(commitments = result.value.first)
                                val actions = listOf(
                                    ChannelAction.Storage.StoreHtlcInfos(htlcInfos),
                                    ChannelAction.Storage.StoreState(nextState),
                                    ChannelAction.Message.Send(result.value.second)
                                )
                                Pair(nextState, actions)
                            }
                        }
                    }
                    is CMD_CLOSE -> {
                        val localScriptPubkey = event.command.scriptPubKey ?: commitments.localParams.defaultFinalScriptPubKey
                        when {
                            this.localShutdown != null -> handleCommandError(event.command, ClosingAlreadyInProgress(channelId), channelUpdate)
                            this.commitments.localHasUnsignedOutgoingHtlcs() -> handleCommandError(event.command, CannotCloseWithUnsignedOutgoingHtlcs(channelId), channelUpdate)
                            !Helpers.Closing.isValidFinalScriptPubkey(localScriptPubkey) -> handleCommandError(event.command, InvalidFinalScript(channelId), channelUpdate)
                            else -> {
                                val shutdown = Shutdown(channelId, localScriptPubkey)
                                val newState = this.copy(localShutdown = shutdown)
                                val actions = listOf(ChannelAction.Storage.StoreState(newState), ChannelAction.Message.Send(shutdown))
                                Pair(newState, actions)
                            }
                        }
                    }
                    else -> unhandled(event)
                }
            }
            is ChannelEvent.MessageReceived -> {
                when (event.message) {
                    is UpdateAddHtlc -> when (val result = commitments.receiveAdd(event.message)) {
                        is Either.Left -> handleLocalError(event, result.value)
                        is Either.Right -> {
                            val newState = this.copy(commitments = result.value)
                            Pair(newState, listOf())
                        }
                    }
                    is UpdateFulfillHtlc -> when (val result = commitments.receiveFulfill(event.message)) {
                        is Either.Left -> handleLocalError(event, result.value)
                        is Either.Right -> {
                            val (commitments1, paymentId, add) = result.value
                            val htlcResult = ChannelAction.HtlcResult.Fulfill.RemoteFulfill(event.message)
                            Pair(this.copy(commitments = commitments1), listOf(ChannelAction.ProcessCmdRes.AddSettledFulfill(paymentId, add, htlcResult)))
                        }
                    }
                    is UpdateFailHtlc -> when (val result = commitments.receiveFail(event.message)) {
                        is Either.Left -> handleLocalError(event, result.value)
                        is Either.Right -> Pair(this.copy(commitments = result.value.first), listOf())
                    }
                    is UpdateFailMalformedHtlc -> when (val result = commitments.receiveFailMalformed(event.message)) {
                        is Either.Left -> handleLocalError(event, result.value)
                        is Either.Right -> Pair(this.copy(commitments = result.value.first), listOf())
                    }
                    is UpdateFee -> when (val result = commitments.receiveFee(this.currentOnChainFeerates.commitmentFeeratePerKw, event.message, staticParams.nodeParams.onChainFeeConf.maxFeerateMismatch)) {
                        is Either.Left -> handleLocalError(event, result.value)
                        is Either.Right -> Pair(this.copy(commitments = result.value), listOf())
                    }
                    is CommitSig -> when (val result = commitments.receiveCommit(event.message, keyManager, logger)) {
                        is Either.Left -> handleLocalError(event, result.value)
                        is Either.Right -> {
                            val nextState = this.copy(commitments = result.value.first)
                            val actions = mutableListOf<ChannelAction>()
                            actions.add(ChannelAction.Message.Send(result.value.second))
                            actions.add(ChannelAction.Storage.StoreState(nextState))
                            if (result.value.first.localHasChanges()) {
                                actions.add(ChannelAction.Message.SendToSelf(CMD_SIGN))
                            }
                            Pair(nextState, actions)
                        }
                    }
                    is RevokeAndAck -> when (val result = commitments.receiveRevocation(event.message)) {
                        is Either.Left -> handleLocalError(event, result.value)
                        is Either.Right -> {
                            // TODO: handle shutdown
                            val nextState = this.copy(commitments = result.value.first)
                            val actions = mutableListOf<ChannelAction>(ChannelAction.Storage.StoreState(nextState))
                            actions.addAll(result.value.second)
                            if (result.value.first.localHasChanges() && commitments.remoteNextCommitInfo.left?.reSignAsap == true) {
                                actions.add(ChannelAction.Message.SendToSelf(CMD_SIGN))
                            }
                            Pair(nextState, actions)
                        }
                    }
                    is Shutdown -> {
                        // they have pending unsigned htlcs         => they violated the spec, close the channel
                        // they don't have pending unsigned htlcs
                        //    we have pending unsigned htlcs
                        //      we already sent a shutdown message  => spec violation (we can't send htlcs after having sent shutdown)
                        //      we did not send a shutdown message
                        //        we are ready to sign              => we stop sending further htlcs, we initiate a signature
                        //        we are waiting for a rev          => we stop sending further htlcs, we wait for their revocation, will resign immediately after, and then we will send our shutdown message
                        //    we have no pending unsigned htlcs
                        //      we already sent a shutdown message
                        //        there are pending signed htlcs    => send our shutdown message, go to SHUTDOWN
                        //        there are no htlcs                => send our shutdown message, go to NEGOTIATING
                        //      we did not send a shutdown message
                        //        there are pending signed htlcs    => go to SHUTDOWN
                        //        there are no htlcs                => go to NEGOTIATING
                        when {
                            !Helpers.Closing.isValidFinalScriptPubkey(event.message.scriptPubKey) -> handleLocalError(event, InvalidFinalScript(channelId))
                            commitments.remoteHasUnsignedOutgoingHtlcs() -> handleLocalError(event, CannotCloseWithUnsignedOutgoingHtlcs(channelId))
                            commitments.localHasUnsignedOutgoingHtlcs() -> {
                                require(localShutdown == null) { "can't have pending unsigned outgoing htlcs after having sent Shutdown" }
                                when (commitments.remoteNextCommitInfo) {
                                    is Either.Left -> {
                                        // yes, let's just schedule a new signature ASAP, which will include all pending unsigned htlcs
                                        val commitments1 = commitments.copy(remoteNextCommitInfo = Either.Left(commitments.remoteNextCommitInfo.value.copy(reSignAsap = true)))
                                        val newState = this.copy(commitments = commitments1, remoteShutdown = event.message)
                                        Pair(newState, listOf())
                                    }
                                    is Either.Right -> {
                                        // no, let's sign right away
                                        val newState = this.copy(remoteShutdown = event.message, commitments = commitments.copy(remoteChannelData = event.message.channelData))
                                        Pair(newState, listOf(ChannelAction.Message.SendToSelf(CMD_SIGN)))
                                    }
                                }
                            }
                            else -> {
                                // so we don't have any unsigned outgoing htlcs
                                val actions = mutableListOf<ChannelAction>()
                                val localShutdown = this.localShutdown ?: Shutdown(channelId, commitments.localParams.defaultFinalScriptPubKey)
                                if (this.localShutdown == null) actions.add(ChannelAction.Message.Send(localShutdown))
                                val commitments1 = commitments.copy(remoteChannelData = event.message.channelData)
                                when {
                                    commitments1.hasNoPendingHtlcs() && commitments1.localParams.isFunder -> {
                                        val (closingTx, closingSigned) = Helpers.Closing.makeFirstClosingTx(
                                            keyManager,
                                            commitments1,
                                            localShutdown.scriptPubKey.toByteArray(),
                                            event.message.scriptPubKey.toByteArray(),
                                            currentOnChainFeerates.commitmentFeeratePerKw,
                                        )
                                        val nextState = Negotiating(
                                            staticParams,
                                            currentTip,
                                            currentOnChainFeerates,
                                            commitments1,
                                            localShutdown,
                                            event.message,
                                            listOf(listOf(ClosingTxProposed(closingTx.tx, closingSigned))),
                                            bestUnpublishedClosingTx = null
                                        )
                                        actions.addAll(listOf(ChannelAction.Storage.StoreState(nextState), ChannelAction.Message.Send(closingSigned)))
                                        Pair(nextState, actions)
                                    }
                                    commitments1.hasNoPendingHtlcs() -> {
                                        val nextState = Negotiating(staticParams, currentTip, currentOnChainFeerates, commitments1, localShutdown, event.message, closingTxProposed = listOf(listOf()), bestUnpublishedClosingTx = null)
                                        actions.add(ChannelAction.Storage.StoreState(nextState))
                                        Pair(nextState, actions)
                                    }
                                    else -> {
                                        // there are some pending signed htlcs, we need to fail/fulfill them
                                        val nextState = ShuttingDown(staticParams, currentTip, currentOnChainFeerates, commitments1, localShutdown, event.message)
                                        actions.add(ChannelAction.Storage.StoreState(nextState))
                                        Pair(nextState, actions)
                                    }
                                }
                            }
                        }
                    }
                    is Error -> handleRemoteError(event.message)
                    else -> unhandled(event)
                }
            }
            is ChannelEvent.NewBlock -> {
                logger.info { "new tip ${event.height} ${event.Header}" }
                val newState = this.copy(currentTip = Pair(event.height, event.Header))
                Pair(newState, listOf())
            }
            is ChannelEvent.SetOnChainFeerates -> {
                logger.info { "using on-chain fee rates ${event.feerates}" }
                Pair(this.copy(currentOnChainFeerates = event.feerates), listOf())
            }
            is ChannelEvent.Disconnected -> Pair(Offline(this), listOf())
            is ChannelEvent.WatchReceived -> when (val watch = event.watch) {
                is WatchEventSpent -> when {
                    watch.tx.txid == commitments.remoteCommit.txid -> handleRemoteSpentCurrent(watch.tx)
                    commitments.remoteNextCommitInfo.left?.nextRemoteCommit?.txid == watch.tx.txid -> handleRemoteSpentNext(watch.tx)
                    else -> handleRemoteSpentOther(watch.tx)
                }
                else -> unhandled(event)
            }
            else -> unhandled(event)
        }
    }

    override fun handleLocalError(event: ChannelEvent, t: Throwable): Pair<ChannelState, List<ChannelAction>> {
        logger.error(t) { "error on event $event in state ${this::class}" }
        val error = Error(channelId, t.message)
        return when {
            commitments.nothingAtStake() -> Pair(Aborted(staticParams, currentTip, currentOnChainFeerates), listOf(ChannelAction.Message.Send(error)))
            else -> spendLocalCurrent().run { copy(second = second + ChannelAction.Message.Send(error)) }
        }
    }

    private fun handleCommandResult(command: Command, result: Either<ChannelException, Pair<Commitments, LightningMessage>>, commit: Boolean): Pair<ChannelState, List<ChannelAction>> {
        return when (result) {
            is Either.Left -> handleCommandError(command, result.value, channelUpdate)
            is Either.Right -> {
                val (commitments1, message) = result.value
                val actions = mutableListOf<ChannelAction>(ChannelAction.Message.Send(message))
                if (commit) {
                    actions.add(ChannelAction.Message.SendToSelf(CMD_SIGN))
                }
                Pair(this.copy(commitments = commitments1), actions)
            }
        }
    }
}

@Serializable
data class ShuttingDown(
    override val staticParams: StaticParams,
    override val currentTip: Pair<Int, @Serializable(with = BlockHeaderKSerializer::class) BlockHeader>,
    override val currentOnChainFeerates: OnChainFeerates,
    override val commitments: Commitments,
    val localShutdown: Shutdown,
    val remoteShutdown: Shutdown
) : ChannelStateWithCommitments() {
    override fun updateCommitments(input: Commitments): ChannelStateWithCommitments = this.copy(commitments = input)

    override fun processInternal(event: ChannelEvent): Pair<ChannelState, List<ChannelAction>> {
        return when (event) {
            is ChannelEvent.MessageReceived -> {
                when (event.message) {
                    is UpdateFulfillHtlc -> when (val result = commitments.receiveFulfill(event.message)) {
                        is Either.Left -> handleLocalError(event, result.value)
                        is Either.Right -> {
                            val (commitments1, paymentId, add) = result.value
                            val htlcResult = ChannelAction.HtlcResult.Fulfill.RemoteFulfill(event.message)
                            Pair(this.copy(commitments = commitments1), listOf(ChannelAction.ProcessCmdRes.AddSettledFulfill(paymentId, add, htlcResult)))
                        }
                    }
                    is UpdateFailHtlc -> when (val result = commitments.receiveFail(event.message)) {
                        is Either.Left -> handleLocalError(event, result.value)
                        is Either.Right -> Pair(this.copy(commitments = result.value.first), listOf())
                    }
                    is UpdateFailMalformedHtlc -> when (val result = commitments.receiveFailMalformed(event.message)) {
                        is Either.Left -> handleLocalError(event, result.value)
                        is Either.Right -> Pair(this.copy(commitments = result.value.first), listOf())
                    }
                    is UpdateFee -> when (val result = commitments.receiveFee(this.currentOnChainFeerates.commitmentFeeratePerKw, event.message, staticParams.nodeParams.onChainFeeConf.maxFeerateMismatch)) {
                        is Either.Left -> handleLocalError(event, result.value)
                        is Either.Right -> Pair(this.copy(commitments = result.value), listOf())
                    }
                    is CommitSig -> when (val result = commitments.receiveCommit(event.message, keyManager, logger)) {
                        is Either.Left -> handleLocalError(event, result.value)
                        is Either.Right -> {
                            val (commitments1, revocation) = result.value
                            when {
                                commitments1.hasNoPendingHtlcs() && commitments1.localParams.isFunder -> {
                                    val (closingTx, closingSigned) = Helpers.Closing.makeFirstClosingTx(
                                        keyManager,
                                        commitments1,
                                        localShutdown.scriptPubKey.toByteArray(),
                                        remoteShutdown.scriptPubKey.toByteArray(),
                                        currentOnChainFeerates.mutualCloseFeeratePerKw
                                    )
                                    val nextState = Negotiating(
                                        staticParams,
                                        currentTip,
                                        currentOnChainFeerates,
                                        commitments1,
                                        localShutdown,
                                        remoteShutdown,
                                        listOf(listOf(ClosingTxProposed(closingTx.tx, closingSigned))),
                                        bestUnpublishedClosingTx = null
                                    )
                                    val actions = listOf(
                                        ChannelAction.Storage.StoreState(nextState),
                                        ChannelAction.Message.Send(revocation),
                                        ChannelAction.Message.Send(closingSigned)
                                    )
                                    Pair(nextState, actions)
                                }
                                commitments1.hasNoPendingHtlcs() -> {
                                    val nextState = Negotiating(staticParams, currentTip, currentOnChainFeerates, commitments1, localShutdown, remoteShutdown, closingTxProposed = listOf(listOf()), bestUnpublishedClosingTx = null)
                                    val actions = listOf(ChannelAction.Storage.StoreState(nextState), ChannelAction.Message.Send(revocation))
                                    Pair(nextState, actions)
                                }
                                else -> {
                                    val nextState = this.copy(commitments = commitments1)
                                    val actions = mutableListOf(ChannelAction.Storage.StoreState(nextState), ChannelAction.Message.Send(revocation))
                                    if (commitments1.localHasChanges()) {
                                        // if we have newly acknowledged changes let's sign them
                                        actions.add(ChannelAction.Message.SendToSelf(CMD_SIGN))
                                    }
                                    Pair(nextState, actions)
                                }
                            }
                        }
                    }
                    is RevokeAndAck -> when (val result = commitments.receiveRevocation(event.message)) {
                        is Either.Left -> handleLocalError(event, result.value)
                        is Either.Right -> {
                            val (commitments1, actions) = result.value
                            val actions1 = actions.toMutableList()
                            when {
                                commitments1.hasNoPendingHtlcs() && commitments1.localParams.isFunder -> {
                                    val (closingTx, closingSigned) = Helpers.Closing.makeFirstClosingTx(
                                        keyManager,
                                        commitments1,
                                        localShutdown.scriptPubKey.toByteArray(),
                                        remoteShutdown.scriptPubKey.toByteArray(),
                                        currentOnChainFeerates.mutualCloseFeeratePerKw
                                    )
                                    val nextState = Negotiating(
                                        staticParams,
                                        currentTip,
                                        currentOnChainFeerates,
                                        commitments1,
                                        localShutdown,
                                        remoteShutdown,
                                        listOf(listOf(ClosingTxProposed(closingTx.tx, closingSigned))),
                                        bestUnpublishedClosingTx = null
                                    )
                                    actions1.addAll(listOf(ChannelAction.Storage.StoreState(nextState), ChannelAction.Message.Send(closingSigned)))
                                    Pair(nextState, actions1)
                                }
                                commitments1.hasNoPendingHtlcs() -> {
                                    val nextState = Negotiating(staticParams, currentTip, currentOnChainFeerates, commitments1, localShutdown, remoteShutdown, closingTxProposed = listOf(listOf()), bestUnpublishedClosingTx = null)
                                    actions1.add(ChannelAction.Storage.StoreState(nextState))
                                    Pair(nextState, actions1)
                                }
                                else -> {
                                    val nextState = this.copy(commitments = commitments1)
                                    actions1.add(ChannelAction.Storage.StoreState(nextState))
                                    if (commitments1.localHasChanges() && commitments1.remoteNextCommitInfo.isLeft && commitments1.remoteNextCommitInfo.left!!.reSignAsap) {
                                        actions1.add(ChannelAction.Message.SendToSelf(CMD_SIGN))
                                    }
                                    Pair(nextState, actions1)
                                }
                            }
                        }
                    }
                    is Error -> {
                        TODO("handle remote errors")
                    }
                    else -> unhandled(event)
                }
            }
            is ChannelEvent.ExecuteCommand -> when {
                event.command is CMD_ADD_HTLC -> {
                    logger.info { "rejecting htlc request in state=$this" }
                    // we don't provide a channel_update: this will be a permanent channel failure
                    handleCommandError(event.command, ChannelUnavailable(channelId))
                }
                event.command is CMD_SIGN && !commitments.localHasChanges() -> {
                    logger.debug { "ignoring CMD_SIGN (nothing to sign)" }
                    Pair(this, listOf())
                }
                event.command is CMD_SIGN && commitments.remoteNextCommitInfo.isLeft -> {
                    logger.debug { "already in the process of signing, will sign again as soon as possible" }
                    Pair(this.copy(commitments = this.commitments.copy(remoteNextCommitInfo = Either.Left(this.commitments.remoteNextCommitInfo.left!!.copy(reSignAsap = true)))), listOf())
                }
                event.command is CMD_SIGN -> when (val result = commitments.sendCommit(keyManager, logger)) {
                    is Either.Left -> handleCommandError(event.command, result.value)
                    is Either.Right -> {
                        val commitments1 = result.value.first
                        val nextState = this.copy(commitments = commitments1)
                        val actions = listOf(ChannelAction.Storage.StoreState(nextState), ChannelAction.Message.Send(result.value.second))
                        Pair(nextState, actions)
                    }
                }
                event.command is CMD_FULFILL_HTLC -> handleCommandResult(event.command, commitments.sendFulfill(event.command), event.command.commit)
                event.command is CMD_FAIL_HTLC -> handleCommandResult(event.command, commitments.sendFail(event.command, staticParams.nodeParams.nodePrivateKey), event.command.commit)
                event.command is CMD_FAIL_MALFORMED_HTLC -> handleCommandResult(event.command, commitments.sendFailMalformed(event.command), event.command.commit)
                event.command is CMD_UPDATE_FEE -> handleCommandResult(event.command, commitments.sendFee(event.command), event.command.commit)
                event.command is CMD_CLOSE -> handleCommandError(event.command, ClosingAlreadyInProgress(channelId))
                else -> unhandled(event)
            }
            is ChannelEvent.NewBlock -> Pair(this.copy(currentTip = Pair(event.height, event.Header)), listOf())
            is ChannelEvent.SetOnChainFeerates -> Pair(this.copy(currentOnChainFeerates = event.feerates), listOf())
            else -> unhandled(event)
        }
    }

    override fun handleLocalError(event: ChannelEvent, t: Throwable): Pair<ChannelState, List<ChannelAction>> {
        TODO("Not yet implemented")
    }

    private fun handleCommandResult(command: Command, result: Either<ChannelException, Pair<Commitments, LightningMessage>>, commit: Boolean): Pair<ChannelState, List<ChannelAction>> {
        return when (result) {
            is Either.Left -> handleCommandError(command, result.value)
            is Either.Right -> {
                val (commitments1, message) = result.value
                val actions = mutableListOf<ChannelAction>(ChannelAction.Message.Send(message))
                if (commit) {
                    actions.add(ChannelAction.Message.SendToSelf(CMD_SIGN))
                }
                Pair(this.copy(commitments = commitments1), actions)
            }
        }
    }
}

@Serializable
data class Negotiating(
    override val staticParams: StaticParams,
    override val currentTip: Pair<Int, @Serializable(with = BlockHeaderKSerializer::class) BlockHeader>,
    override val currentOnChainFeerates: OnChainFeerates,
    override val commitments: Commitments,
    val localShutdown: Shutdown,
    val remoteShutdown: Shutdown,
    val closingTxProposed: List<List<ClosingTxProposed>>, // one list for every negotiation (there can be several in case of disconnection)
    @Serializable(with = TransactionKSerializer::class) val bestUnpublishedClosingTx: Transaction?
) : ChannelStateWithCommitments() {
    init {
        require(closingTxProposed.isNotEmpty()) { "there must always be a list for the current negotiation" }
        require(!commitments.localParams.isFunder || !closingTxProposed.any { it.isEmpty() }) { "funder must have at least one closing signature for every negotation attempt because it initiates the closing" }
    }

    override fun updateCommitments(input: Commitments): ChannelStateWithCommitments = this.copy(commitments = input)

    override fun processInternal(event: ChannelEvent): Pair<ChannelState, List<ChannelAction>> {
        return when {
            event is ChannelEvent.MessageReceived && event.message is ClosingSigned -> {
                logger.info { "received closingFeeSatoshis=${event.message.feeSatoshis}" }
                val result = Helpers.Closing.checkClosingSignature(keyManager, commitments, localShutdown.scriptPubKey.toByteArray(), remoteShutdown.scriptPubKey.toByteArray(), event.message.feeSatoshis, event.message.signature)
                val lastLocalClosingFee = closingTxProposed.last().lastOrNull()?.localClosingSigned?.feeSatoshis
                when {
                    result is Either.Right && (lastLocalClosingFee == event.message.feeSatoshis || closingTxProposed.flatten().size >= MAX_NEGOTIATION_ITERATIONS) -> {
                        // we close when we converge or when there were too many iterations
                        val signedClosingTx = result.value
                        logger.info { "closing tx published: closingTxId=${signedClosingTx.txid}" }
                        val nextState = Closing(
                            staticParams,
                            currentTip,
                            currentOnChainFeerates,
                            commitments,
                            null,
                            currentTimestampMillis(),
                            this.closingTxProposed.flatten().map { it.unsignedTx },
                            listOf(signedClosingTx)
                        )
                        val actions = listOf(
                            ChannelAction.Storage.StoreState(nextState),
                            ChannelAction.Blockchain.PublishTx(signedClosingTx),
                            ChannelAction.Blockchain.SendWatch(WatchConfirmed(channelId, signedClosingTx, staticParams.nodeParams.minDepthBlocks.toLong(), BITCOIN_TX_CONFIRMED(signedClosingTx)))
                        )
                        Pair(nextState, actions)
                    }
                    result is Either.Right -> {
                        val signedClosingTx = result.value
                        val nextClosingFee = Helpers.Closing.nextClosingFee(
                            lastLocalClosingFee ?: Helpers.Closing.firstClosingFee(commitments, localShutdown.scriptPubKey.toByteArray(), remoteShutdown.scriptPubKey.toByteArray(), currentOnChainFeerates.mutualCloseFeeratePerKw),
                            event.message.feeSatoshis
                        )
                        when {
                            lastLocalClosingFee == nextClosingFee -> {
                                // next computed fee is the same than the one we previously sent (probably because of rounding), let's close now
                                val nextState = Closing(
                                    staticParams,
                                    currentTip,
                                    currentOnChainFeerates,
                                    commitments,
                                    null,
                                    currentTimestampMillis(),
                                    this.closingTxProposed.flatten().map { it.unsignedTx },
                                    listOf(signedClosingTx)
                                )
                                val actions = listOf(
                                    ChannelAction.Storage.StoreState(nextState),
                                    ChannelAction.Blockchain.PublishTx(signedClosingTx),
                                    ChannelAction.Blockchain.SendWatch(WatchConfirmed(channelId, signedClosingTx, staticParams.nodeParams.minDepthBlocks.toLong(), BITCOIN_TX_CONFIRMED(signedClosingTx)))
                                )
                                logger.info { "closing tx published: closingTxId=${signedClosingTx.txid}" }
                                Pair(nextState, actions)
                            }
                            nextClosingFee == event.message.feeSatoshis -> {
                                // we have converged!
                                val (closingTx, closingSigned) = Helpers.Closing.makeClosingTx(keyManager, commitments, localShutdown.scriptPubKey.toByteArray(), remoteShutdown.scriptPubKey.toByteArray(), nextClosingFee)
                                val nextState = Closing(
                                    staticParams,
                                    currentTip,
                                    currentOnChainFeerates,
                                    commitments,
                                    null,
                                    currentTimestampMillis(),
                                    this.closingTxProposed.flatten().map { it.unsignedTx } + listOf(closingTx.tx),
                                    listOf(closingTx.tx)
                                )
                                val actions = listOf(
                                    ChannelAction.Storage.StoreState(nextState),
                                    ChannelAction.Blockchain.PublishTx(closingTx.tx),
                                    ChannelAction.Blockchain.SendWatch(WatchConfirmed(channelId, signedClosingTx, staticParams.nodeParams.minDepthBlocks.toLong(), BITCOIN_TX_CONFIRMED(signedClosingTx))),
                                    ChannelAction.Message.Send(closingSigned)
                                )
                                logger.info { "closing tx published: closingTxId=${closingTx.tx.txid}" }
                                Pair(nextState, actions)
                            }
                            else -> {
                                val (closingTx, closingSigned) = Helpers.Closing.makeClosingTx(keyManager, commitments, localShutdown.scriptPubKey.toByteArray(), remoteShutdown.scriptPubKey.toByteArray(), nextClosingFee)
                                logger.info { "proposing closingFeeSatoshis=$closingSigned.feeSatoshis" }
                                val closingProposed1 = closingTxProposed.updated(
                                    closingTxProposed.lastIndex,
                                    closingTxProposed.last() + listOf(ClosingTxProposed(closingTx.tx, closingSigned))
                                )
                                val nextState = this.copy(
                                    commitments = commitments.copy(remoteChannelData = event.message.channelData),
                                    closingTxProposed = closingProposed1,
                                    bestUnpublishedClosingTx = closingTx.tx
                                )
                                val actions = listOf(ChannelAction.Storage.StoreState(nextState), ChannelAction.Message.Send(closingSigned))
                                Pair(nextState, actions)
                            }
                        }
                    }
                    else -> handleLocalError(event, result.left!!)
                }
            }
            event is ChannelEvent.MessageReceived && event.message is Error -> handleRemoteError(event.message)
            event is ChannelEvent.WatchReceived && event.watch is WatchEventSpent && event.watch.event is BITCOIN_FUNDING_SPENT && closingTxProposed.flatten().map { it.unsignedTx.txid }.contains(event.watch.tx.txid) -> {
                // they can publish a closing tx with any sig we sent them, even if we are not done negotiating
                logger.info { "closing tx published: closingTxId=${event.watch.tx.txid}" }
                val nextState = Closing(
                    staticParams,
                    currentTip,
                    currentOnChainFeerates,
                    commitments,
                    null,
                    currentTimestampMillis(),
                    this.closingTxProposed.flatten().map { it.unsignedTx },
                    listOf(event.watch.tx)
                )
                val actions = listOf(
                    ChannelAction.Storage.StoreState(nextState),
                    ChannelAction.Blockchain.PublishTx(event.watch.tx),
                    ChannelAction.Blockchain.SendWatch(WatchConfirmed(channelId, event.watch.tx, staticParams.nodeParams.minDepthBlocks.toLong(), BITCOIN_TX_CONFIRMED(event.watch.tx)))
                )
                Pair(nextState, actions)
            }
            event is ChannelEvent.NewBlock -> Pair(this.copy(currentTip = Pair(event.height, event.Header)), listOf())
            event is ChannelEvent.SetOnChainFeerates -> Pair(this.copy(currentOnChainFeerates = event.feerates), listOf())
            else -> unhandled(event)
        }
    }

    override fun handleLocalError(event: ChannelEvent, t: Throwable): Pair<ChannelState, List<ChannelAction>> {
        logger.error(t) { "error on event $event in state ${this::class}" }
        val error = Error(channelId, t.message)
        return when {
            commitments.nothingAtStake() -> Pair(Aborted(staticParams, currentTip, currentOnChainFeerates), listOf(ChannelAction.Message.Send(error)))
            bestUnpublishedClosingTx != null -> {
                // if we were in the process of closing and already received a closing sig from the counterparty, it's always better to use that
                val nextState = Closing(
                    staticParams,
                    currentTip,
                    currentOnChainFeerates,
                    commitments,
                    null,
                    currentTimestampMillis(),
                    this.closingTxProposed.flatten().map { it.unsignedTx } + listOf(bestUnpublishedClosingTx),
                    listOf(bestUnpublishedClosingTx)
                )
                val actions = listOf(
                    ChannelAction.Storage.StoreState(nextState),
                    ChannelAction.Blockchain.PublishTx(bestUnpublishedClosingTx),
                    ChannelAction.Blockchain.SendWatch(WatchConfirmed(channelId, bestUnpublishedClosingTx, staticParams.nodeParams.minDepthBlocks.toLong(), BITCOIN_TX_CONFIRMED(bestUnpublishedClosingTx)))
                )
                Pair(nextState, actions)
            }
            else -> spendLocalCurrent().run { copy(second = second + ChannelAction.Message.Send(error)) }
        }
    }
}

sealed class ClosingType
data class MutualClose(val tx: Transaction) : ClosingType()
data class LocalClose(val localCommit: LocalCommit, val localCommitPublished: LocalCommitPublished) : ClosingType()

sealed class RemoteClose : ClosingType() {
    abstract val remoteCommit: RemoteCommit
    abstract val remoteCommitPublished: RemoteCommitPublished
}

data class CurrentRemoteClose(override val remoteCommit: RemoteCommit, override val remoteCommitPublished: RemoteCommitPublished) : RemoteClose()
data class NextRemoteClose(override val remoteCommit: RemoteCommit, override val remoteCommitPublished: RemoteCommitPublished) : RemoteClose()

data class RecoveryClose(val remoteCommitPublished: RemoteCommitPublished) : ClosingType()
data class RevokedClose(val revokedCommitPublished: RevokedCommitPublished) : ClosingType()

@Serializable
data class Closing(
    override val staticParams: StaticParams,
    override val currentTip: Pair<Int, @Serializable(with = BlockHeaderKSerializer::class) BlockHeader>,
    override val currentOnChainFeerates: OnChainFeerates,
    override val commitments: Commitments,
    @Serializable(with = TransactionKSerializer::class) val fundingTx: Transaction?, // this will be non-empty if we are funder and we got in closing while waiting for our own tx to be published
    val waitingSince: Long, // how long since we initiated the closing
    val mutualCloseProposed: List<@Serializable(with = TransactionKSerializer::class) Transaction> = emptyList(), // all exchanged closing sigs are flattened, we use this only to keep track of what publishable tx they have
    val mutualClosePublished: List<@Serializable(with = TransactionKSerializer::class) Transaction> = emptyList(),
    val localCommitPublished: LocalCommitPublished? = null,
    val remoteCommitPublished: RemoteCommitPublished? = null,
    val nextRemoteCommitPublished: RemoteCommitPublished? = null,
    val futureRemoteCommitPublished: RemoteCommitPublished? = null,
    val revokedCommitPublished: List<RevokedCommitPublished> = emptyList()
) : ChannelStateWithCommitments() {

    private val spendingTxs: List<Transaction> by lazy {
        mutualClosePublished + revokedCommitPublished.map { it.commitTx } +
                listOfNotNull(
                    localCommitPublished?.commitTx,
                    remoteCommitPublished?.commitTx,
                    nextRemoteCommitPublished?.commitTx,
                    futureRemoteCommitPublished?.commitTx
                )
    }

    override fun updateCommitments(input: Commitments): ChannelStateWithCommitments = this.copy(commitments = input)

    override fun processInternal(event: ChannelEvent): Pair<ChannelState, List<ChannelAction>> {
        return when (event) {
            is ChannelEvent.WatchReceived -> {
                val watch = event.watch
                when {
                    watch is WatchEventSpent && watch.event is BITCOIN_FUNDING_SPENT -> when {
                        mutualClosePublished.contains(watch.tx) -> {
                            // we already know about this tx, probably because we have published it ourselves after successful negotiation
                            Pair(this, listOf())
                        }
                        mutualCloseProposed.contains(watch.tx) -> {
                            // at any time they can publish a closing tx with any sig we sent them
                            val nextState = this.copy(mutualClosePublished = this.mutualClosePublished + listOf(watch.tx))
                            val actions = listOf(ChannelAction.Storage.StoreState(nextState), ChannelAction.Blockchain.PublishTx(watch.tx))
                            Pair(nextState, actions)
                        }
                        localCommitPublished?.commitTx == watch.tx || remoteCommitPublished?.commitTx == watch.tx || nextRemoteCommitPublished?.commitTx == watch.tx || futureRemoteCommitPublished?.commitTx == watch.tx -> {
                            // this is because WatchSpent watches never expire and we are notified multiple times
                            Pair(this, listOf())
                        }
                        watch.tx.txid == commitments.remoteCommit.txid -> {
                            // counterparty may attempt to spend its last commit tx at any time
                            handleRemoteSpentCurrent(watch.tx)
                        }
                        watch.tx.txid == commitments.remoteNextCommitInfo.left?.nextRemoteCommit?.txid -> {
                            // counterparty may attempt to spend its next commit tx at any time
                            handleRemoteSpentNext(watch.tx)
                        }
                        else -> {
                            // counterparty may attempt to spend a revoked commit tx at any time
                            handleRemoteSpentOther(watch.tx)
                        }
                    }
                    watch is WatchEventSpent && watch.event is BITCOIN_OUTPUT_SPENT -> {
                        // when a remote or local commitment tx containing outgoing htlcs is published on the network,
                        // we watch it in order to extract payment preimage if funds are pulled by the counterparty
                        // we can then use these preimages to fulfill origin htlcs
                        logger.info { "processing BITCOIN_OUTPUT_SPENT with txid=${watch.tx.txid} tx=$watch.tx" }
                        val revokeCommitPublishActions = mutableListOf<ChannelAction>()
                        val revokedCommitPublished1 = revokedCommitPublished.map { rev ->
                            val (newRevokeCommitPublished, tx) = Helpers.Closing.claimRevokedHtlcTxOutputs(keyManager, commitments, rev, watch.tx, currentOnChainFeerates)
                            tx?.let {
                                revokeCommitPublishActions += ChannelAction.Blockchain.PublishTx(it)
                                revokeCommitPublishActions += ChannelAction.Blockchain.SendWatch(WatchSpent(channelId, it, it.txIn.first().outPoint.index.toInt(), BITCOIN_OUTPUT_SPENT))
                            }
                            newRevokeCommitPublished
                        }

                        val nextState = copy(revokedCommitPublished = revokedCommitPublished1)
                        Pair(nextState, buildList {
                            add(ChannelAction.Storage.StoreState(nextState))
                            // one of the outputs of the local/remote/revoked commit was spent
                            // we just put a watch to be notified when it is confirmed
                            add(ChannelAction.Blockchain.SendWatch(WatchConfirmed(channelId, watch.tx, staticParams.nodeParams.minDepthBlocks.toLong(), BITCOIN_TX_CONFIRMED(watch.tx))))
                            addAll(revokeCommitPublishActions)
                        })
                    }
                    watch is WatchEventConfirmed && watch.event is BITCOIN_TX_CONFIRMED -> {
                        logger.info { "txid=${watch.tx.txid} has reached mindepth, updating closing state" }
                        val closing1 = this.copy(
                            localCommitPublished = this.localCommitPublished?.update(watch.tx),
                            remoteCommitPublished = this.remoteCommitPublished?.update(watch.tx),
                            nextRemoteCommitPublished = this.nextRemoteCommitPublished?.update(watch.tx),
                            futureRemoteCommitPublished = this.futureRemoteCommitPublished?.update(watch.tx),
                            revokedCommitPublished = this.revokedCommitPublished.map { it.update(watch.tx) }
                        )
                        when (val closingType = closing1.isClosed(watch.tx)) {
                            null -> Pair(closing1, listOf(ChannelAction.Storage.StoreState(closing1)))
                            else -> {
                                logger.info { "channel $channelId is now closed" }
                                if (closingType !is MutualClose) {
                                    logger.info { "last known remoteChannelData=${commitments.remoteChannelData}" }
                                }
                                val nextState = Closed(closing1)
                                Pair(nextState, listOf(ChannelAction.Storage.StoreState(nextState)))
                            }
                        }
                    }
                    else -> unhandled(event)
                }
            }
            is ChannelEvent.MessageReceived -> when (event.message) {
                is ChannelReestablish -> {
                    // they haven't detected that we were closing and are trying to reestablish a connection
                    // we give them one of the published txes as a hint
                    // note spendingTx != Nil (that's a requirement of DATA_CLOSING)
                    val exc = FundingTxSpent(channelId, spendingTxs.first())
                    val error = Error(channelId, exc.message)
                    Pair(this, listOf(ChannelAction.Message.Send(error)))
                }
                is Error -> {
                    logger.error { "peer send error: ascii=${event.message.toAscii()} bin=${event.message.data.toHex()}" }
                    // nothing to do, there is already a spending tx published
                    Pair(this, listOf())
                }
                else -> unhandled(event)
            }
            is ChannelEvent.ExecuteCommand -> when (event.command) {
                is CMD_CLOSE -> handleCommandError(event.command, ClosingAlreadyInProgress(channelId))
                is CMD_ADD_HTLC -> {
                    logger.info { "rejecting htlc request in state=$this" }
                    // we don't provide a channel_update: this will be a permanent channel failure
                    handleCommandError(event.command, ChannelUnavailable(channelId))
                }
                is CMD_FULFILL_HTLC -> when (val result = commitments.sendFulfill(event.command)) {
                    is Either.Right -> {
                        logger.info { "got valid payment preimage, recalculating transactions to redeem the corresponding htlc on-chain" }
                        val commitments1 = result.value.first
                        val localCommitPublished1 = localCommitPublished?.let {
                            Helpers.Closing.claimCurrentLocalCommitTxOutputs(keyManager, commitments1, it.commitTx, currentOnChainFeerates)
                        }
                        val remoteCommitPublished1 = remoteCommitPublished?.let {
                            Helpers.Closing.claimRemoteCommitTxOutputs(keyManager, commitments1, commitments1.remoteCommit, it.commitTx, currentOnChainFeerates)
                        }
                        val nextRemoteCommitPublished1 = nextRemoteCommitPublished?.let {
                            val remoteCommit = commitments1.remoteNextCommitInfo.left?.nextRemoteCommit ?: error("next remote commit must be defined")
                            Helpers.Closing.claimRemoteCommitTxOutputs(keyManager, commitments1, remoteCommit, it.commitTx, currentOnChainFeerates)
                        }
                        val republishList = buildList {
                            val minDepth = staticParams.nodeParams.minDepthBlocks.toLong()
                            localCommitPublished1?.run { addAll(doPublish(channelId, minDepth)) }
                            remoteCommitPublished1?.run { addAll(doPublish(channelId, minDepth)) }
                            nextRemoteCommitPublished1?.run { addAll(doPublish(channelId, minDepth)) }
                        }
                        val nextState = copy(
                            commitments = commitments1,
                            localCommitPublished = localCommitPublished1,
                            remoteCommitPublished = remoteCommitPublished1,
                            nextRemoteCommitPublished = nextRemoteCommitPublished1
                        )
                        val actions = buildList {
                            add(ChannelAction.Storage.StoreState(nextState))
                            addAll(republishList)
                        }
                        Pair(nextState, actions)
                    }
                    is Either.Left -> handleCommandError(event.command, result.value)
                }
                else -> unhandled(event)
            }
            is ChannelEvent.NewBlock -> Pair(this.copy(currentTip = Pair(event.height, event.Header)), listOf())
            is ChannelEvent.SetOnChainFeerates -> Pair(this.copy(currentOnChainFeerates = event.feerates), listOf())
            else -> unhandled(event)
        }
    }

    override fun handleLocalError(event: ChannelEvent, t: Throwable): Pair<ChannelState, List<ChannelAction>> {
        logger.error(t) { "error processing $event in state $this" }
        // TODO: is it the right thing to do ?
        return Pair(this, listOf())
    }

    /**
     * Checks if a channel is closed (i.e. its closing tx has been confirmed)
     *
     * @param additionalConfirmedTx additional confirmed transaction; we need this for the mutual close scenario
     *                                  because we don't store the closing tx in the channel state
     * @return the channel closing type, if applicable
     */
    private fun isClosed(additionalConfirmedTx: Transaction?): ClosingType? {
        return when {
            additionalConfirmedTx?.let { mutualClosePublished.contains(it) } ?: false -> MutualClose(additionalConfirmedTx!!)
            localCommitPublished?.isDone() ?: false -> LocalClose(commitments.localCommit, localCommitPublished!!)
            remoteCommitPublished?.isDone() ?: false -> CurrentRemoteClose(commitments.remoteCommit, remoteCommitPublished!!)
            nextRemoteCommitPublished?.isDone() ?: false -> NextRemoteClose(commitments.remoteNextCommitInfo.left!!.nextRemoteCommit, nextRemoteCommitPublished!!)
            futureRemoteCommitPublished?.isDone() ?: false -> RecoveryClose(futureRemoteCommitPublished!!)
            revokedCommitPublished.any { it.done() } -> RevokedClose(revokedCommitPublished.first { it.done() })
            else -> null
        }
    }

    fun closingTypeAlreadyKnown(): ClosingType? {
        return when {
            localCommitPublished?.isConfirmed() ?: false -> LocalClose(commitments.localCommit, localCommitPublished!!)
            remoteCommitPublished?.isConfirmed() ?: false -> CurrentRemoteClose(commitments.remoteCommit, remoteCommitPublished!!)
            nextRemoteCommitPublished?.isConfirmed() ?: false -> NextRemoteClose(commitments.remoteNextCommitInfo.left!!.nextRemoteCommit, nextRemoteCommitPublished!!)
            futureRemoteCommitPublished?.isConfirmed() ?: false -> RecoveryClose(futureRemoteCommitPublished!!)
            revokedCommitPublished.any { it.irrevocablySpent.values.contains(it.commitTx.txid) } -> RevokedClose(revokedCommitPublished.first { it.irrevocablySpent.values.contains(it.commitTx.txid) })
            else -> null
        }
    }

    /**
     * This helper function returns the fee paid by the given transaction.
     *
     * It relies on the current channel data to find the parent tx and compute the fee, and also provides a description.
     *
     * @param tx a tx for which we want to compute the fee
     * @return if the parent tx is found, a tuple (fee, description)
     */
    fun networkFeePaid(tx: Transaction): Pair<Satoshi, String>? {
        // only funder pays the fee
        if (!commitments.localParams.isFunder) return null

        // we build a map with all known txes (that's not particularly efficient, but it doesn't really matter)
        val txs = buildList {
            mutualClosePublished.map { it to "mutual" }.forEach { add(it) }
            localCommitPublished?.let { localCommitPublished ->
                add(localCommitPublished.commitTx to "local-commit")
                localCommitPublished.claimMainDelayedOutputTx?.let { add(it to "local-main-delayed") }
                localCommitPublished.htlcSuccessTxs.forEach { add(it to "local-htlc-success") }
                localCommitPublished.htlcTimeoutTxs.forEach { add(it to "local-htlc-timeout") }
                localCommitPublished.claimHtlcDelayedTxs.forEach { add(it to "local-htlc-delayed") }
            }
            remoteCommitPublished?.let { remoteCommitPublished ->
                add(remoteCommitPublished.commitTx to "remote-commit")
                remoteCommitPublished.claimMainOutputTx?.let { add(it to "remote-main") }
                remoteCommitPublished.claimHtlcSuccessTxs.forEach { add(it to "remote-htlc-success") }
                remoteCommitPublished.claimHtlcTimeoutTxs.forEach { add(it to "remote-htlc-timeout") }
            }
            nextRemoteCommitPublished?.let { nextRemoteCommitPublished ->
                add(nextRemoteCommitPublished.commitTx to "remote-commit")
                nextRemoteCommitPublished.claimMainOutputTx?.let { add(it to "remote-main") }
                nextRemoteCommitPublished.claimHtlcSuccessTxs.forEach { add(it to "remote-htlc-success") }
                nextRemoteCommitPublished.claimHtlcTimeoutTxs.forEach { add(it to "remote-htlc-timeout") }
            }
            revokedCommitPublished.forEach { revokedCommitPublished ->
                add(revokedCommitPublished.commitTx to "revoked-commit")
                revokedCommitPublished.claimMainOutputTx?.let { add(it to "revoked-main") }
                revokedCommitPublished.mainPenaltyTx?.let { add(it to "revoked-main-penalty") }
                revokedCommitPublished.htlcPenaltyTxs.forEach { add(it to "revoked-htlc-penalty") }
                revokedCommitPublished.claimHtlcDelayedPenaltyTxs.forEach { add(it to "revoked-htlc-penalty-delayed") }
            }
        }.map { (tx, desc) ->
            // will allow easy lookup of parent transaction
            tx.txid to (tx to desc)
        }.toMap()

        fun fee(child: Transaction): Satoshi? {
            require(child.txIn.size == 1) { "transaction must have exactly one input" }
            val outPoint = child.txIn.first().outPoint
            val parentTxOut = if (outPoint == commitments.commitInput.outPoint) {
                commitments.commitInput.txOut
            } else {
                txs[outPoint.txid]?.let { (parent, _) -> parent.txOut[outPoint.index.toInt()] }
            }
            return parentTxOut?.let { txOut -> txOut.amount - child.txOut.map { it.amount }.sum() }
        }

        return txs[tx.txid]?.let { (_, desc) -> fee(tx)?.let { it to desc } }
    }
}

/**
 * Channel is closed i.t its funding tx has been spent and the spending transactions have been confirmed, it can be forgotten
 */
@Serializable
data class Closed(val state: Closing) : ChannelStateWithCommitments() {
    override val staticParams: StaticParams get() = state.staticParams
    override val currentTip: Pair<Int, BlockHeader> get() = state.currentTip
    override val currentOnChainFeerates: OnChainFeerates get() = state.currentOnChainFeerates
    override val commitments: Commitments get() = state.commitments

    override fun updateCommitments(input: Commitments): ChannelStateWithCommitments {
        return this.copy(state = state.updateCommitments(input) as Closing)
    }

    override fun processInternal(event: ChannelEvent): Pair<ChannelState, List<ChannelAction>> {
        return Pair(this, listOf())
    }

    override fun handleLocalError(event: ChannelEvent, t: Throwable): Pair<ChannelState, List<ChannelAction>> {
        logger.error(t) { "error on event $event in state ${this::class}" }
        return Pair(this, listOf())
    }
}

/**
 * Channel has been aborted before it was funded (because we did not receive a FundingCreated or FundingSigned message for example)
 */
@Serializable
data class Aborted(override val staticParams: StaticParams, override val currentTip: Pair<Int, @Serializable(with = BlockHeaderKSerializer::class) BlockHeader>, override val currentOnChainFeerates: OnChainFeerates) : ChannelState() {
    override fun processInternal(event: ChannelEvent): Pair<ChannelState, List<ChannelAction>> {
        return Pair(this, listOf())
    }

    override fun handleLocalError(event: ChannelEvent, t: Throwable): Pair<ChannelState, List<ChannelAction>> {
        return Pair(this, listOf())
    }
}

@Serializable
data class ErrorInformationLeak(
    override val staticParams: StaticParams,
    override val currentTip: Pair<Int, @Serializable(with = BlockHeaderKSerializer::class) BlockHeader>,
    override val currentOnChainFeerates: OnChainFeerates,
    override val commitments: Commitments
) : ChannelStateWithCommitments() {
    override fun processInternal(event: ChannelEvent): Pair<ChannelState, List<ChannelAction>> {
        TODO("implement this")
    }

    override fun updateCommitments(input: Commitments): ChannelStateWithCommitments {
        TODO("Not yet implemented")
    }

    override fun handleLocalError(event: ChannelEvent, t: Throwable): Pair<ChannelState, List<ChannelAction>> {
        TODO("Not yet implemented")
    }
}

object Channel {
    // see https://github.com/lightningnetwork/lightning-rfc/blob/master/07-routing-gossip.md#requirements
    const val ANNOUNCEMENTS_MINCONF = 6

    // https://github.com/lightningnetwork/lightning-rfc/blob/master/02-peer-protocol.md#requirements
    val MAX_FUNDING = 10.btc
    const val MAX_ACCEPTED_HTLCS = 483

    // we don't want the counterparty to use a dust limit lower than that, because they wouldn't only hurt themselves we may need them to publish their commit tx in certain cases (backup/restore)
    val MIN_DUSTLIMIT = 546.sat

    // we won't exchange more than this many signatures when negotiating the closing fee
    const val MAX_NEGOTIATION_ITERATIONS = 20

    // this is defined in BOLT 11
    val MIN_CLTV_EXPIRY_DELTA = CltvExpiryDelta(18)
    val MAX_CLTV_EXPIRY_DELTA = CltvExpiryDelta(7 * 144) // one week

    // since BOLT 1.1, there is a max value for the refund delay of the main commitment tx
    val MAX_TO_SELF_DELAY = CltvExpiryDelta(2016)

    // as a fundee, we will wait that much time for the funding tx to confirm (funder will rely on the funding tx being double-spent)
    const val FUNDING_TIMEOUT_FUNDEE = 5 * 24 * 3600 // 5 days, in seconds

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
                    val channelKeyPath = keyManager.channelKeyPath(d.commitments.localParams, d.commitments.channelVersion)
                    val localPerCommitmentSecret = keyManager.commitmentSecret(channelKeyPath, d.commitments.localCommit.index - 1)
                    val localNextPerCommitmentPoint = keyManager.commitmentPoint(channelKeyPath, d.commitments.localCommit.index + 1)
                    val revocation = RevokeAndAck(
                        channelId = commitments1.channelId,
                        perCommitmentSecret = localPerCommitmentSecret,
                        nextPerCommitmentPoint = localNextPerCommitmentPoint
                    )
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
        }

        @Suppress("ControlFlowWithEmptyBody")
        if (commitments1.remoteNextCommitInfo.isLeft) {
            // we expect them to (re-)send the revocation immediately
            // TODO: set a timer and wait for their revocation
        }

        if (commitments1.localHasChanges()) {
            sendQueue.add(ChannelAction.Message.SendToSelf(CMD_SIGN))
        }

        return Pair(commitments1, sendQueue)
    }
}
