package fr.acinq.eclair.channel

import fr.acinq.bitcoin.*
import fr.acinq.eclair.*
import fr.acinq.eclair.blockchain.*
import fr.acinq.eclair.blockchain.fee.ConstantFeeEstimator
import fr.acinq.eclair.channel.Channel.ANNOUNCEMENTS_MINCONF
import fr.acinq.eclair.channel.Channel.handleSync
import fr.acinq.eclair.channel.ChannelVersion.Companion.USE_STATIC_REMOTEKEY_BIT
import fr.acinq.eclair.crypto.KeyManager
import fr.acinq.eclair.crypto.ShaChain
import fr.acinq.eclair.io.*
import fr.acinq.eclair.payment.relay.Origin
import fr.acinq.eclair.router.Announcements
import fr.acinq.eclair.transactions.CommitmentSpec
import fr.acinq.eclair.transactions.Scripts
import fr.acinq.eclair.transactions.Transactions
import fr.acinq.eclair.utils.*
import fr.acinq.eclair.wire.*
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass
import org.kodein.log.Logger
import org.kodein.log.LoggerFactory
import org.kodein.log.newLogger

/*
 * Channel is implemented as a finite state machine
 * Its main method is (State, Event) -> (State, List<Action>)
 */

/**
 * Channel Event (inputs to be fed to the state machine)
 */
@Serializable
sealed class ChannelEvent

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
) : ChannelEvent()

data class InitFundee(val temporaryChannelId: ByteVector32, val localParams: LocalParams, val remoteInit: Init) : ChannelEvent()
data class Restore(val state: ChannelState) : ChannelEvent()
data class MessageReceived(val message: LightningMessage) : ChannelEvent()
data class WatchReceived(val watch: WatchEvent) : ChannelEvent()
data class ExecuteCommand(val command: Command) : ChannelEvent()
data class MakeFundingTxResponse(val fundingTx: Transaction, val fundingTxOutputIndex: Int, val fee: Satoshi) : ChannelEvent()
data class NewBlock(val height: Int, val Header: BlockHeader) : ChannelEvent()
object Disconnected : ChannelEvent()
data class Connected(val localInit: Init, val remoteInit: Init) : ChannelEvent()

/**
 * Channel Actions (outputs produced by the state machine)
 */
sealed class ChannelAction
data class SendMessage(val message: LightningMessage) : ChannelAction()
data class SendWatch(val watch: Watch) : ChannelAction()
data class ProcessCommand(val command: Command) : ChannelAction()
data class ProcessAdd(val add: UpdateAddHtlc) : ChannelAction()
data class ProcessFail(val fail: UpdateFailHtlc) : ChannelAction()
data class ProcessFailMalformed(val fail: UpdateFailMalformedHtlc) : ChannelAction()
data class ProcessFulfill(val fulfill: UpdateFulfillHtlc) : ChannelAction()
data class StoreState(val data: ChannelState) : ChannelAction()
data class HtlcInfo(val channelId: ByteVector32, val commitmentNumber: Long, val paymentHash: ByteVector32, val cltvExpiry: CltvExpiry)
data class StoreHtlcInfos(val htlcs: List<HtlcInfo>) : ChannelAction()
data class HandleError(val error: Throwable) : ChannelAction()
data class MakeFundingTx(val pubkeyScript: ByteVector, val amount: Satoshi, val feeratePerKw: Long) : ChannelAction()
data class ChannelIdAssigned(val remoteNodeId: PublicKey, val temporaryChannelId: ByteVector32, val channelId: ByteVector32) : ChannelAction()
data class PublishTx(val tx: Transaction) : ChannelAction()
data class ChannelIdSwitch(val oldChannelId: ByteVector32, val newChannelId: ByteVector32) : ChannelAction()
data class SendToSelf(val message: LightningMessage) : ChannelAction()

/**
 * channel static parameters
 */
@Serializable
data class StaticParams(val nodeParams: NodeParams, @Serializable(with = PublicKeyKSerializer::class) val remoteNodeId: PublicKey)

/**
 * Channel state
 */
@Serializable
sealed class ChannelState {
    abstract val staticParams: StaticParams
    abstract val currentTip: Pair<Int, BlockHeader>
    val currentBlockHeight: Int get() = currentTip.first
    val keyManager: KeyManager get() = staticParams.nodeParams.keyManager
    val privateKey: PrivateKey get() = staticParams.nodeParams.keyManager.nodeKey.privateKey

    /**
     * @param event input event (for example, a message was received, a command was sent by the GUI/API, ...
     * @return a (new state, list of actions) pair
     */
    abstract fun process(event: ChannelEvent): Pair<ChannelState, List<ChannelAction>>

    /**
     * update outgoing messages to include an encrypted backup when necessary
     */
    fun updateActions(actions: List<ChannelAction>): List<ChannelAction> = when {
        this is HasCommitments && this.isZeroReserve() -> actions.map {
            when {
                it is SendMessage && it.message is FundingSigned -> it.copy(message = it.message.copy(channelData = Helpers.encrypt(staticParams.nodeParams.nodePrivateKey.value, this).toByteVector()))
                it is SendMessage && it.message is CommitSig -> it.copy(message = it.message.copy(channelData = Helpers.encrypt(staticParams.nodeParams.nodePrivateKey.value, this).toByteVector()))
                it is SendMessage && it.message is RevokeAndAck -> it.copy(message = it.message.copy(channelData = Helpers.encrypt(staticParams.nodeParams.nodePrivateKey.value, this).toByteVector()))
                it is SendMessage && it.message is ClosingSigned -> it.copy(message = it.message.copy(channelData = Helpers.encrypt(staticParams.nodeParams.nodePrivateKey.value, this).toByteVector()))
                else -> it
            }
        }
        else -> actions
    }

    internal fun handleFundingPublishFailed(): Pair<ChannelState, List<ChannelAction>> {
        require(this is HasCommitments) { "" } // TODO
        logger.error { "failed to publish funding tx" }
        val exc = ChannelFundingError(channelId)
        val error = Error(channelId, exc.message)
        // NB: we don't use the handleLocalError handler because it would result in the commit tx being published, which we don't want:
        // implementation *guarantees* that in case of BITCOIN_FUNDING_PUBLISH_FAILED, the funding tx hasn't and will never be published, so we can close the channel right away
        // TODO context.system.eventStream.publish(ChannelErrorOccurred(self, Helpers.getChannelId(stateData), remoteNodeId, stateData, LocalError(exc), isFatal = true))
        return newState {
            state = Closed(staticParams, currentTip)
            actions = listOf(SendMessage(error))
        }
    }

    internal fun handleFundingTimeout(): Pair<ChannelState, List<ChannelAction>> {
        require(this is HasCommitments) { "" } // TODO
        logger.warning { "funding tx hasn't been confirmed in time, cancelling channel delay=${fr.acinq.eclair.channel.Channel.FUNDING_TIMEOUT_FUNDEE}" }
        val exc = FundingTxTimedout(channelId)
        val error = Error(channelId, exc.message)
        // TODO context.system.eventStream.publish(ChannelErrorOccurred(self, Helpers.getChannelId(stateData), remoteNodeId, stateData, LocalError(exc), isFatal = true))
        return newState {
            state = Closed(staticParams, currentTip)
            actions = listOf(SendMessage(error))
        }
    }

    internal fun handleRemoteSpentCurrent(commitTx: Transaction): Pair<Closing, List<ChannelAction>> {
        require(this is HasCommitments) { "" } // TODO
        logger.warning { "they published their current commit in txid=${commitTx.txid}" }
        require(commitTx.txid == commitments.remoteCommit.txid) { "txid mismatch" }

        val onChainFeeConf = staticParams.nodeParams.onChainFeeConf
        val remoteCommitPublished = Helpers.Closing.claimRemoteCommitTxOutputs(keyManager, commitments, commitments.remoteCommit, commitTx, onChainFeeConf.feeEstimator, onChainFeeConf.feeTargets)

        val nextState = when(this) {
            is Closing -> copy(remoteCommitPublished = remoteCommitPublished)
            // TODO
            //  is Negotiating -> {}
            //  case negotiating: DATA_NEGOTIATING => DATA_CLOSING(d.commitments, fundingTx = None,
            //  waitingSince = now, negotiating.closingTxProposed.flatten.map(_.unsignedTx), remoteCommitPublished = Some(remoteCommitPublished))
            is WaitForFundingConfirmed -> Closing(
                staticParams = staticParams,
                currentTip = currentTip,
                commitments = commitments,
                fundingTx = fundingTx,
                waitingSince = currentTimestampMillis(),
                remoteCommitPublished = remoteCommitPublished
            )
            else -> Closing(
                staticParams = staticParams,
                currentTip = currentTip,
                commitments = commitments,
                fundingTx = null,
                waitingSince = currentTimestampMillis(),
                remoteCommitPublished = remoteCommitPublished
            )
        }

        return nextState to buildList {
            add(StoreState(nextState))
            addAll(doPublish(remoteCommitPublished, channelId))
        }
    }

    internal fun handleRemoteSpentNext(commitTx: Transaction): Pair<ChannelState, List<ChannelAction>> {
        require(this is HasCommitments) { "" } // TODO
        logger.warning { "they published their next commit in txid=${commitTx.txid}" }
        require(commitments.remoteNextCommitInfo.isLeft) { "next remote commit must be defined" }
        val remoteCommit = commitments.remoteNextCommitInfo.left?.nextRemoteCommit
        require(remoteCommit != null) { "remote commit must not be null" }
        require(commitTx.txid == remoteCommit.txid) { "txid mismatch" }

        val onChainFeeConf = staticParams.nodeParams.onChainFeeConf
        val remoteCommitPublished = Helpers.Closing.claimRemoteCommitTxOutputs(keyManager, commitments, remoteCommit, commitTx, onChainFeeConf.feeEstimator, onChainFeeConf.feeTargets)

        val nextState = when(this) {
            is Closing -> copy(remoteCommitPublished = remoteCommitPublished)
            // TODO
            //  is Negotiating -> {}
            // NB: if there is a next commitment, we can't be in WaitForFundingConfirmed so we don't have the case where fundingTx is defined
            else -> Closing(
                staticParams = staticParams,
                currentTip = currentTip,
                commitments = commitments,
                fundingTx = null,
                waitingSince = currentTimestampMillis(),
                remoteCommitPublished = remoteCommitPublished
            )
        }

        return nextState to buildList {
            add(StoreState(nextState))
            addAll(doPublish(remoteCommitPublished, channelId))
        }
    }

    internal fun handleRemoteSpentOther(tx: Transaction): Pair<ChannelState, List<ChannelAction>> {
        require(this is HasCommitments) { "" } // TODO
        logger.warning { "funding tx spent in txid=${tx.txid}" }

        val onChainFeeConf = staticParams.nodeParams.onChainFeeConf

        return Helpers.Closing.claimRevokedRemoteCommitTxOutputs(
            keyManager,
            commitments,
            tx,
            onChainFeeConf.feeEstimator,
            onChainFeeConf.feeTargets
        )?.let { revokedCommitPublished ->
            logger.warning { "txid=${tx.txid} was a revoked commitment, publishing the penalty tx" }
            val exc = FundingTxSpent(channelId, tx)
            val error = Error(channelId, exc.message)

            val nextState = when(this) {
                is Closing -> copy(revokedCommitPublished = this.revokedCommitPublished + revokedCommitPublished)
                // TODO
                //  is Negotiating -> {}
                // NB: if there is a next commitment, we can't be in WaitForFundingConfirmed so we don't have the case where fundingTx is defined
                else -> Closing(
                    staticParams = staticParams,
                    currentTip = currentTip,
                    commitments = commitments,
                    fundingTx = null,
                    waitingSince = currentTimestampMillis(),
                    revokedCommitPublished = listOf(revokedCommitPublished)
                )
            }

            nextState to buildList {
                add(StoreState(nextState))
                addAll(doPublish(revokedCommitPublished, channelId))
                add(SendMessage(error))
            }

        } ?: kotlin.run {
            // the published tx was neither their current commitment nor a revoked one
            logger.error { "couldn't identify txid=${tx.txid}, something very bad is going on!!!" }
            newState(ErrorInformationLeak(staticParams, currentTip, commitments))
        }
    }

    internal fun doPublish(localCommitPublished: LocalCommitPublished, channelId: ByteVector32): List<ChannelAction> {
        val (commitTx, claimMainDelayedOutputTx, claimHtlcSuccessTxs, claimHtlcTimeoutTxs, claimHtlcDelayedTxs, irrevocablySpent) = localCommitPublished

        val publishQueue = buildList {
            add(commitTx)
            claimMainDelayedOutputTx?.let { add(it) }
            addAll(claimHtlcSuccessTxs)
            addAll(claimHtlcTimeoutTxs)
            addAll(claimHtlcDelayedTxs)
        }
        val publishList = publishIfNeeded(publishQueue, irrevocablySpent)

        // we watch:
        // - the commitment tx itself, so that we can handle the case where we don't have any outputs
        // - 'final txes' that send funds to our wallet and that spend outputs that only us control
        val watchConfirmedQueue = buildList {
            add(commitTx)
            claimMainDelayedOutputTx?.let { add(it) }
            addAll(claimHtlcDelayedTxs)
        }
        val watchConfirmedList= watchConfirmedIfNeeded(watchConfirmedQueue, irrevocablySpent, channelId)

        // we watch outputs of the commitment tx that both parties may spend
        val watchSpentQueue = buildList {
            addAll(claimHtlcSuccessTxs)
            addAll(claimHtlcTimeoutTxs)
        }
        val watchSpentList = watchSpentIfNeeded(commitTx, watchSpentQueue, irrevocablySpent, channelId)

        return buildList {
            addAll(publishList)
            addAll(watchConfirmedList)
            addAll(watchSpentList)
        }
    }

    internal fun doPublish(remoteCommitPublished: RemoteCommitPublished, channelId: ByteVector32): List<ChannelAction> {
        val (commitTx, claimMainOutputTx, claimHtlcSuccessTxs, claimHtlcTimeoutTxs, irrevocablySpent) = remoteCommitPublished
        val publishQueue = buildList {
            claimMainOutputTx?.let { add(it) }
            addAll(claimHtlcSuccessTxs)
            addAll(claimHtlcTimeoutTxs)
        }

        val publishList = publishIfNeeded(publishQueue, irrevocablySpent)

        // we watch:
        // - the commitment tx itself, so that we can handle the case where we don't have any outputs
        // - 'final txes' that send funds to our wallet and that spend outputs that only us control
        val watchConfirmedQueue = buildList {
            add(commitTx)
            claimMainOutputTx?.let { add(it) }
        }
        val watchEventConfirmedList = watchConfirmedIfNeeded(watchConfirmedQueue, irrevocablySpent, channelId)

        // we watch outputs of the commitment tx that both parties may spend
        val watchSpentQueue = buildList {
            addAll(claimHtlcTimeoutTxs)
            addAll(claimHtlcSuccessTxs)
        }
        val watchEventSpentList = watchSpentIfNeeded(commitTx, watchSpentQueue, irrevocablySpent, channelId)

        return buildList {
            addAll(publishList)
            addAll(watchEventConfirmedList)
            addAll(watchEventSpentList)
        }
    }

    private fun doPublish(revokedCommitPublished: RevokedCommitPublished, channelId: ByteVector32): List<ChannelAction> {
        val (commitTx, claimMainOutputTx, mainPenaltyTx, htlcPenaltyTxs, claimHtlcDelayedPenaltyTxs, irrevocablySpent) = revokedCommitPublished

        val publishQueue = buildList{
            claimMainOutputTx?.let { add(it) }
            mainPenaltyTx?.let { add(it) }
            addAll(htlcPenaltyTxs)
            addAll(claimHtlcDelayedPenaltyTxs)
        }
        val publishList = publishIfNeeded(publishQueue, irrevocablySpent)

        // we watch:
        // - the commitment tx itself, so that we can handle the case where we don't have any outputs
        // - 'final txes' that send funds to our wallet and that spend outputs that only us control
        val watchConfirmedQueue = buildList {
            add(commitTx)
            claimMainOutputTx?.let { add(it) }
        }
        val watchEventConfirmedList = watchConfirmedIfNeeded(watchConfirmedQueue, irrevocablySpent, channelId)


        // we watch outputs of the commitment tx that both parties may spend
        val watchSpentQueue = buildList {
            mainPenaltyTx?.let { add(it) }
            addAll(htlcPenaltyTxs)
        }
        val watchEventSpentList = watchSpentIfNeeded(commitTx, watchSpentQueue, irrevocablySpent, channelId)

        return buildList {
            addAll(publishList)
            addAll(watchEventConfirmedList)
            addAll(watchEventSpentList)
        }
    }

    /**
     * This helper method will publish txes only if they haven't yet reached minDepth
     */
    private fun publishIfNeeded(txes: List<Transaction>, irrevocablySpent: Map<OutPoint, ByteVector32>): List<PublishTx> {
        val (skip, process) = txes.partition { Helpers.Closing.inputsAlreadySpent(it, irrevocablySpent) }
        skip.forEach { tx ->
            logger.info { "no need to republish txid=${tx.txid}, it has already been confirmed" }
        }
        return process.map { tx ->
            logger.info { "publishing txid=${tx.txid}" }
            PublishTx(tx)
        }
    }

    /**
     * This helper method will watch txes only if they haven't yet reached minDepth
     */
    private fun watchConfirmedIfNeeded(txes: List<Transaction>, irrevocablySpent: Map<OutPoint, ByteVector32>, channelId: ByteVector32): List<SendWatch> {
        val (skip, process) = txes.partition { Helpers.Closing.inputsAlreadySpent(it, irrevocablySpent) }
        skip.forEach { tx ->
            logger.info { "no need to watch txid=${tx.txid}, it has already been confirmed" }
        }
        return process.map { tx ->
            SendWatch(
                WatchConfirmed(channelId, tx, staticParams.nodeParams.minDepthBlocks.toLong(), BITCOIN_TX_CONFIRMED(tx))
            )
        }
    }

    /**
     * This helper method will watch txes only if the utxo they spend hasn't already been irrevocably spent
     */
    private fun watchSpentIfNeeded(parentTx: Transaction, txes: List<Transaction>, irrevocablySpent: Map<OutPoint, ByteVector32>, channelId: ByteVector32): List<SendWatch> {
        val (skip, process) = txes.partition { Helpers.Closing.inputsAlreadySpent(it, irrevocablySpent) }
        skip.forEach {tx ->
            logger.info { "no need to watch txid=${tx.txid}, it has already been confirmed" }
        }
        return process.map {
            SendWatch(
                WatchSpent(channelId, parentTx, it.txIn.first().outPoint.index.toInt(), BITCOIN_OUTPUT_SPENT)
            )
        }
    }

    internal fun feePaid(fee: Satoshi, tx: Transaction, desc: String, channelId: ByteVector32) {
        runTrying { // this may fail with an NPE in tests because context has been cleaned up, but it's not a big deal
            logger.info { "paid feeSatoshi=${fee.toLong()} for txid=${tx.txid} desc=$desc" }
            // TODO context.system.eventStream.publish(NetworkFeePaid(self, remoteNodeId, channelId, tx, fee, desc))
        }
    }

    fun handleRemoteError(e: Error): Pair<ChannelState, List<ChannelAction>> {
        // see BOLT 1: only print out data verbatim if is composed of printable ASCII characters
        logger.error { "peer sent error: ascii='${e.toAscii()}' bin=${e.data.toHex()}" }
        // TODO context.system.eventStream.publish(ChannelErrorOccurred(self, Helpers.getChannelId(stateData), remoteNodeId, stateData, RemoteError(e), isFatal = true))

        return when(this) {
            is Closing -> stay // nothing to do, there is already a spending tx published
//          TODO // if we were in the process of closing and already received a closing sig from the counterparty, it's always better to use that
//           is Negotiating -> handleMutualClose(bestUnpublishedClosingTx, Either.Left(negotiating))
            // NB: we publish the commitment even if we have nothing at stake (in a dataloss situation our peer will send us an error just for that)
            is HasCommitments -> spendLocalCurrent()
            // when there is no commitment yet, we just go to CLOSED state in case an error occurs
            else -> newState(Closed(staticParams, currentTip))
        }
    }

    private fun HasCommitments.spendLocalCurrent(): Pair<ChannelState, List<ChannelAction>> {
        val outdatedCommitment = when(this) {
            is WaitForRemotePublishFutureComitment -> true
            is Closing -> this.futureRemoteCommitPublished != null
            else -> false
        }

        return if (outdatedCommitment) {
            logger.warning { "we have an outdated commitment: will not publish our local tx" }
            stay
        } else {
            val commitTx = commitments.localCommit.publishableTxs.commitTx.tx
            val localCommitPublished = Helpers.Closing.claimCurrentLocalCommitTxOutputs(
                keyManager,
                commitments,
                commitTx,
                staticParams.nodeParams.onChainFeeConf.feeEstimator,
                staticParams.nodeParams.onChainFeeConf.feeTargets
            )
            val nextState = when(this) {
                is Closing -> copy(localCommitPublished = localCommitPublished)
//                is Negotiating -> TODO("DATA_CLOSING(d.commitments, fundingTx = None, waitingSince = now, negotiating.closingTxProposed.flatten.map(_.unsignedTx), localCommitPublished = Some(localCommitPublished))")
                is WaitForFundingConfirmed -> Closing(
                    staticParams = staticParams, currentTip = currentTip,
                    commitments = commitments,
                    fundingTx = fundingTx,
                    waitingSince = currentTimestampMillis(),
                    localCommitPublished = localCommitPublished
                )
                else -> Closing(
                    staticParams = staticParams, currentTip = currentTip,
                    commitments = commitments,
                    fundingTx = null,
                    waitingSince = currentTimestampMillis(),
                    localCommitPublished = localCommitPublished
                )
            }

            nextState to buildList {
                add(StoreState(nextState))
                addAll(doPublish(localCommitPublished, channelId))
            }
        }
    }

    @Transient
    val logger = EclairLoggerFactory.newLogger<Channel<*>>()
}

interface HasCommitments {
    val commitments: Commitments
    val channelId: ByteVector32
        get() = commitments.channelId

    fun isZeroReserve(): Boolean = commitments.isZeroReserve

    fun isFunder(): Boolean = commitments.localParams.isFunder

    fun updateCommitments(input: Commitments): HasCommitments

    companion object {
        val serializersModule = SerializersModule {
            polymorphic(HasCommitments::class) {
                subclass(Normal::class)
                subclass(WaitForFundingConfirmed::class)
                subclass(WaitForFundingLocked::class)
                subclass(WaitForRemotePublishFutureComitment::class)
                subclass(Closing::class)
            }
        }

        private val serializationModules = SerializersModule {
            include(Tlv.serializersModule)
            include(KeyManager.serializersModule)
            include(UpdateMessage.serializersModule)
            include(ConstantFeeEstimator.testSerializersModule)
        }

        private val cbor = Cbor {
            serializersModule = serializationModules
        }

        fun serialize(state: HasCommitments): ByteArray {
            return cbor.encodeToByteArray(ChannelState.serializer(), state as ChannelState)
        }

        fun deserialize(bin: ByteArray): HasCommitments {
            return cbor.decodeFromByteArray<ChannelState>(bin) as HasCommitments
        }

        fun deserialize(bin: ByteVector): HasCommitments = deserialize(bin.toByteArray())
    }
}

@Serializable
data class WaitForInit(override val staticParams: StaticParams, override val currentTip: Pair<Int, @Serializable(with = BlockHeaderKSerializer::class) BlockHeader>) : ChannelState() {
    override fun process(event: ChannelEvent): Pair<ChannelState, List<ChannelAction>> {
        return when {
            event is InitFundee -> {
                val nextState = WaitForOpenChannel(staticParams, currentTip, event.temporaryChannelId, event.localParams, event.remoteInit)
                Pair(nextState, listOf())
            }
            event is InitFunder -> {
                val fundingPubKey = keyManager.fundingPublicKey(event.localParams.fundingKeyPath).publicKey
                val channelKeyPath = keyManager.channelKeyPath(event.localParams, event.channelVersion)
                val paymentBasepoint = if (event.channelVersion.isSet(USE_STATIC_REMOTEKEY_BIT)) {
                    event.localParams.localPaymentBasepoint!!
                } else {
                    keyManager.paymentPoint(channelKeyPath).publicKey
                }
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
                val nextState = WaitForAcceptChannel(staticParams, currentTip, event, open)
                Pair(nextState, listOf(SendMessage(open)))
            }
            event is Restore && event.state is HasCommitments -> {
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
                val actions = listOf(SendWatch(watchSpent), SendWatch(watchConfirmed))
                // TODO: ask watcher for the funding tx when restoring WaitForFundingConfirmed
                Pair(Offline(event.state), event.state.updateActions(actions))
            }
            event is NewBlock -> Pair(this.copy(currentTip = Pair(event.height, event.Header)), listOf())
            else -> {
                logger.warning { "unhandled event $event ins state ${this::class}" }
                Pair(this, listOf())
            }
        }
    }
}

@Serializable
data class Offline(val state: ChannelState) : ChannelState() {
    override val staticParams: StaticParams
        get() = state.staticParams
    override val currentTip: Pair<Int, BlockHeader>
        get() = state.currentTip

    override fun process(event: ChannelEvent): Pair<ChannelState, List<ChannelAction>> {
        logger.warning { "offline processing $event" }
        return when {
            event is Connected -> {
                when {
                    state is WaitForRemotePublishFutureComitment -> {
                        // they already proved that we have an outdated commitment
                        // there isn't much to do except asking them again to publish their current commitment by sending an error
                        val exc = PleasePublishYourCommitment(state.channelId)
                        val error = Error(state.channelId, exc.message)
                        val nextState = state.updateCommitments(state.commitments.updateFeatures(event.localInit, event.remoteInit)) as ChannelState
                        Pair(
                            nextState,
                            nextState.updateActions(listOf(SendMessage(error)))
                        )
                    }
                    state is HasCommitments && state.isZeroReserve() -> {
                        logger.info { "syncing $state, waiting fo their channelReestablish message" }
                        val nextState = state.updateCommitments(state.commitments.updateFeatures(event.localInit, event.remoteInit)) as ChannelState
                        Pair(Syncing(nextState, true), listOf())
                    }
                    state is HasCommitments -> {
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
                        val nextState = state.updateCommitments(state.commitments.updateFeatures(event.localInit, event.remoteInit)) as ChannelState
                        Pair(
                            Syncing(nextState, false),
                            nextState.updateActions(listOf(SendMessage(channelReestablish)))
                        )
                    }
                    else -> {
                        logger.warning { "unhandled event $event in state ${this::class}" }
                        Pair(this, listOf())
                    }
                }
            }
            event is NewBlock -> {
                // TODO: is this the right thing to do ?
                val (newState, actions) = state.process(event)
                Pair(Offline(newState), listOf())
            }
            else -> {
                logger.warning { "unhandled event $event in state ${this::class}" }
                Pair(this, listOf())
            }
        }
    }
}

/**
 * waitForTheirReestablishMessage == true means that we want to wait until we've received their channel_reestablish message before
 * we send ours (for example, to extract encrypted backup data from extra fields)
 * waitForTheirReestablishMessage == false means that we've already sent our channel_reestablish message
 */
@Serializable
data class Syncing(val state: ChannelState, val waitForTheirReestablishMessage: Boolean) : ChannelState() {
    override val staticParams: StaticParams
        get() = state.staticParams
    override val currentTip: Pair<Int, BlockHeader>
        get() = state.currentTip

    override fun process(event: ChannelEvent): Pair<ChannelState, List<ChannelAction>> {
        logger.warning { "syncing processing $event" }
        return when {
            event is MessageReceived && event.message is ChannelReestablish ->
                when {
                    waitForTheirReestablishMessage -> {
                        if (state !is HasCommitments) {
                            logger.error { "waiting for their channel_reestablish message in a state  that is not valid" }
                            return Pair(state, listOf())
                        }
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
                        val actions = listOf<ChannelAction>(
                            SendMessage(channelReestablish)
                        )
                        // now apply their reestablish message to the restored state
                        val (nextState1, actions1) = Syncing(nextState as ChannelState, waitForTheirReestablishMessage = false).process(event)
                        Pair(nextState1, updateActions(actions + actions1))
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
                            staticParams.nodeParams.minDepthBlocks.toLong(),
                            BITCOIN_FUNDING_DEPTHOK
                        )
                        val actions = listOf(SendWatch(watchConfirmed))
                        Pair(state, actions)
                    }
                    state is WaitForFundingLocked -> {
                        logger.verbose { "re-sending fundingLocked" }
                        val channelKeyPath = keyManager.channelKeyPath(state.commitments.localParams, state.commitments.channelVersion)
                        val nextPerCommitmentPoint = keyManager.commitmentPoint(channelKeyPath, 1)
                        val fundingLocked = FundingLocked(state.commitments.channelId, nextPerCommitmentPoint)
                        val actions = listOf(SendMessage(fundingLocked))
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
                                    val nextState = WaitForRemotePublishFutureComitment(staticParams, state.currentTip, state.commitments, event.message)
                                    val actions = listOf(
                                        StoreState(nextState),
                                        SendMessage(error)
                                    )
                                    Pair(nextState, nextState.updateActions(actions))
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
                                val nextState = WaitForRemotePublishFutureComitment(staticParams, state.currentTip, state.commitments, event.message)
                                val actions = listOf(
                                    StoreState(nextState),
                                    SendMessage(error)
                                )
                                Pair(nextState, nextState.updateActions(actions))
                            }
                            else -> {
                                // normal case, our data is up-to-date
                                val actions = ArrayList<ChannelAction>()
                                if (event.message.nextLocalCommitmentNumber == 1L && state.commitments.localCommit.index == 0L) {
                                    // If next_local_commitment_number is 1 in both the channel_reestablish it sent and received, then the node MUST retransmit funding_locked, otherwise it MUST NOT
                                    logger.verbose { "re-sending fundingLocked" }
                                    val nextPerCommitmentPoint = keyManager.commitmentPoint(channelKeyPath, 1)
                                    val fundingLocked = FundingLocked(state.commitments.channelId, nextPerCommitmentPoint)
                                    actions.add(SendMessage(fundingLocked))
                                }
                                val (commitments1, sendQueue1) = handleSync(event.message, state, keyManager, logger)
                                actions.addAll(sendQueue1)

                                // BOLT 2: A node if it has sent a previous shutdown MUST retransmit shutdown.
                                state.localShutdown?.let {
                                    logger.verbose { "re-sending localShutdown" }
                                    actions.add(SendMessage(it))
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
                                    actions.add(SendWatch(watchConfirmed))
                                }

                                // TODO: update fees if needed
                                logger.info { "switching to $state" }
                                Pair(state.copy(commitments = commitments1), actions)
                            }
                        }
                    }
                    else -> {
                        logger.warning { "unhandled event $event in state ${this::class}" }
                        Pair(this, listOf())
                    }
                }
            event is NewBlock -> {
                // TODO: is this the right thing to do ?
                val (newState, actions) = state.process(event)
                Pair(Syncing(newState, waitForTheirReestablishMessage), listOf())
            }
            else -> {
                logger.warning { "unhandled event $event in state ${this::class}" }
                Pair(this, listOf())
            }
        }
    }
}

@Serializable
data class WaitForRemotePublishFutureComitment(
    override val staticParams: StaticParams,
    override val currentTip: Pair<Int, @Serializable(with = BlockHeaderKSerializer::class) BlockHeader>,
    override val commitments: Commitments,
    val channelReestablish: ChannelReestablish
) : ChannelState(), HasCommitments {
    override fun updateCommitments(input: Commitments): HasCommitments = this.copy(commitments = input)

    override fun process(event: ChannelEvent): Pair<ChannelState, List<ChannelAction>> {
        TODO("Not yet implemented")
    }
}

@Serializable
data class WaitForOpenChannel(
    override val staticParams: StaticParams,
    override val currentTip: Pair<Int, @Serializable(with = BlockHeaderKSerializer::class) BlockHeader>,
    @Serializable(with = ByteVector32KSerializer::class) val temporaryChannelId: ByteVector32,
    val localParams: LocalParams,
    val remoteInit: Init
) : ChannelState() {
    override fun process(event: ChannelEvent): Pair<ChannelState, List<ChannelAction>> {
        return when (event) {
            is MessageReceived ->
                when (event.message) {
                    is OpenChannel -> {
                        val fundingPubkey = keyManager.fundingPublicKey(localParams.fundingKeyPath).publicKey
                        var channelVersion = event.message.channelVersion ?: ChannelVersion.STANDARD
                        if (Features.canUseFeature(
                                localParams.features,
                                Features.invoke(remoteInit.features),
                                Feature.StaticRemoteKey
                            )
                        ) {
                            channelVersion = channelVersion or ChannelVersion.STATIC_REMOTEKEY
                        }
                        val channelKeyPath = keyManager.channelKeyPath(localParams, channelVersion)
                        // TODO: maybe also check uniqueness of temporary channel id
                        val minimumDepth = Helpers.minDepthForFunding(staticParams.nodeParams, event.message.fundingSatoshis)
                        val paymentBasepoint = if (channelVersion.isSet(USE_STATIC_REMOTEKEY_BIT)) {
                            localParams.localPaymentBasepoint!!
                        } else {
                            keyManager.paymentPoint(channelKeyPath).publicKey
                        }
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
                            maxHtlcValueInFlightMsat = event.message.maxHtlcValueInFlightMsat.toLong(),
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

                        Pair(nextState, listOf(SendMessage(accept)))
                    }
                    is Error -> handleRemoteError(event.message)
                    else -> {
                        logger.warning { "unhandled event $event in state ${this::class}" }
                        Pair(this, listOf())
                    }
                }
            is NewBlock -> Pair(this.copy(currentTip = Pair(event.height, event.Header)), listOf())
            else -> {
                logger.warning { "unhandled event $event in state ${this::class}" }
                Pair(this, listOf())
            }
        }
    }
}

@Serializable
data class WaitForFundingCreated(
    override val staticParams: StaticParams,
    override val currentTip: Pair<Int, @Serializable(with = BlockHeaderKSerializer::class) BlockHeader>,
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
    override fun process(event: ChannelEvent): Pair<ChannelState, List<ChannelAction>> {
        return when (event) {
            is MessageReceived ->
                when (event.message) {
                    is FundingCreated -> {
                        // they fund the channel with their funding tx, so the money is theirs (but we are paid pushMsat)
                        val firstCommitTx = Helpers.Funding.makeFirstCommitTxs(
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
                                // TODO: implement error handling
                                logger.error(result.error) { "their first commit sig is not valid for ${firstCommitTx.localCommitTx.tx}" }
                                Pair(this, listOf())
                            }
                            is Try.Success -> {
                                val localSigOfRemoteTx = keyManager.sign(firstCommitTx.remoteCommitTx, fundingPubKey)
                                val channelId = Eclair.toLongId(event.message.fundingTxid, event.message.fundingOutputIndex)
                                // watch the funding tx transaction
                                val commitInput = firstCommitTx.localCommitTx.input
                                val fundingSigned = FundingSigned(
                                    channelId = channelId,
                                    signature = localSigOfRemoteTx
                                )
                                val commitments = Commitments(
                                    channelVersion,
                                    localParams,
                                    remoteParams,
                                    channelFlags,
                                    LocalCommit(0, firstCommitTx.localSpec, PublishableTxs(signedLocalCommitTx, listOf())),
                                    RemoteCommit(
                                        0,
                                        firstCommitTx.remoteSpec,
                                        firstCommitTx.remoteCommitTx.tx.txid,
                                        remoteFirstPerCommitmentPoint
                                    ),
                                    LocalChanges(listOf(), listOf(), listOf()),
                                    RemoteChanges(listOf(), listOf(), listOf()),
                                    localNextHtlcId = 0L,
                                    remoteNextHtlcId = 0L,
                                    originChannels = mapOf(),
                                    remoteNextCommitInfo = Either.Right(Eclair.randomKey().publicKey()), // we will receive their next per-commitment point in the next message, so we temporarily put a random byte array,
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
                                val watchSpent = WatchSpent(
                                    channelId,
                                    commitInput.outPoint.txid,
                                    commitInput.outPoint.index.toInt(),
                                    commitments.commitInput.txOut.publicKeyScript,
                                    BITCOIN_FUNDING_SPENT
                                ) // TODO: should we wait for an acknowledgment from the watcher?
                                val watchConfirmed = WatchConfirmed(
                                    channelId,
                                    commitInput.outPoint.txid,
                                    commitments.commitInput.txOut.publicKeyScript,
                                    fundingMinDepth.toLong(),
                                    BITCOIN_FUNDING_DEPTHOK
                                )
                                val nextState = WaitForFundingConfirmed(staticParams, currentTip, commitments, null, currentTimestampMillis() / 1000, null, Either.Right(fundingSigned))
                                val actions = listOf(SendWatch(watchSpent), SendWatch(watchConfirmed), SendMessage(fundingSigned), ChannelIdSwitch(temporaryChannelId, channelId), StoreState(nextState))
                                Pair(nextState, nextState.updateActions(actions))
                            }
                        }
                    }
                    is Error -> handleRemoteError(event.message)
                    else -> {
                        logger.warning { "unhandled message ${event.message} in state ${this::class}" }
                        Pair(this, listOf())
                    }
                }
            is NewBlock -> Pair(this.copy(currentTip = Pair(event.height, event.Header)), listOf())
            else -> {
                logger.warning { "unhandled event $event in state ${this::class}" }
                Pair(this, listOf())
            }
        }
    }

}

@Serializable
data class WaitForAcceptChannel(override val staticParams: StaticParams, override val currentTip: Pair<Int, @Serializable(with = BlockHeaderKSerializer::class) BlockHeader>, val initFunder: InitFunder, val lastSent: OpenChannel) :
    ChannelState() {
    override fun process(event: ChannelEvent): Pair<ChannelState, List<ChannelAction>> {
        return when {
            event is MessageReceived && event.message is AcceptChannel -> {
                val result = fr.acinq.eclair.utils.runTrying {
                    Helpers.validateParamsFunder(staticParams.nodeParams, lastSent, event.message)
                }
                when (result) {
                    is Try.Failure -> Pair(this, listOf(HandleError(result.error)))
                    is Try.Success -> {
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
                        logger.verbose { "remote params: $remoteParams" }
                        val localFundingPubkey = keyManager.fundingPublicKey(initFunder.localParams.fundingKeyPath)
                        val fundingPubkeyScript = ByteVector(Script.write(Script.pay2wsh(Scripts.multiSig2of2(localFundingPubkey.publicKey, remoteParams.fundingPubKey))))
                        val makeFundingTx = MakeFundingTx(fundingPubkeyScript, initFunder.fundingAmount, initFunder.fundingTxFeeratePerKw)
                        val nextState = WaitForFundingInternal(
                            staticParams,
                            currentTip,
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
                }
            }
            event is NewBlock -> Pair(this.copy(currentTip = Pair(event.height, event.Header)), listOf())
            else -> Pair(this, listOf())
        }
    }
}

@Serializable
data class WaitForFundingInternal(
    override val staticParams: StaticParams,
    override val currentTip: Pair<Int, @Serializable(with = BlockHeaderKSerializer::class) BlockHeader>,
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
    override fun process(event: ChannelEvent): Pair<ChannelState, List<ChannelAction>> {
        return when (event) {
            is MakeFundingTxResponse -> {
                // let's create the first commitment tx that spends the yet uncommitted funding tx
                val firstCommitTx = Helpers.Funding.makeFirstCommitTxs(
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
                val channelIdAssigned = ChannelIdAssigned(staticParams.remoteNodeId, temporaryChannelId, channelId) // we notify the peer asap so it knows how to route messages
                //context.system.eventStream.publish(ChannelIdAssigned(self, remoteNodeId, temporaryChannelId, channelId))
                // NB: we don't send a ChannelSignatureSent for the first commit
                val nextState = WaitForFundingSigned(
                    staticParams,
                    currentTip,
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
                Pair(nextState, listOf(channelIdAssigned, SendMessage(fundingCreated)))
            }
            is NewBlock -> Pair(this.copy(currentTip = Pair(event.height, event.Header)), listOf())
            else -> {
                logger.warning { "unhandled event $event in state ${this::class}" }
                Pair(this, listOf())
            }
        }
    }
}

@Serializable
data class WaitForFundingSigned(
    override val staticParams: StaticParams,
    override val currentTip: Pair<Int, @Serializable(with = BlockHeaderKSerializer::class) BlockHeader>,
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
    override fun process(event: ChannelEvent): Pair<ChannelState, List<ChannelAction>> {
        return when {
            event is MessageReceived && event.message is FundingSigned -> {
                // we make sure that their sig checks out and that our first commit tx is spendable
                val fundingPubKey = keyManager.fundingPublicKey(localParams.fundingKeyPath)
                val localSigOfLocalTx = keyManager.sign(localCommitTx, fundingPubKey)
                val signedLocalCommitTx = Transactions.addSigs(localCommitTx, fundingPubKey.publicKey, remoteParams.fundingPubKey, localSigOfLocalTx, event.message.signature)
                val result = Transactions.checkSpendable(signedLocalCommitTx)
                when (result) {
                    is Try.Failure -> {
                        Pair(this, listOf(HandleError(result.error)))
                    }
                    is Try.Success -> {
                        val commitInput = localCommitTx.input
                        val commitments = Commitments(
                            channelVersion, localParams, remoteParams, channelFlags,
                            LocalCommit(0, localSpec, PublishableTxs(signedLocalCommitTx, listOf())), remoteCommit,
                            LocalChanges(listOf(), listOf(), listOf()), RemoteChanges(listOf(), listOf(), listOf()),
                            localNextHtlcId = 0L, remoteNextHtlcId = 0L,
                            originChannels = mapOf(),
                            remoteNextCommitInfo = Either.Right(Eclair.randomKey().publicKey()), // we will receive their next per-commitment point in the next message, so we temporarily put a random byte array
                            commitInput, ShaChain.init, channelId, event.message.channelData
                        )
                        val now = currentTimestampSeconds()
                        // TODO context.system.eventStream.publish(ChannelSignatureReceived(self, commitments))
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

                        // we will publish the funding tx only after the channel state has been written to disk because we want to
                        // make sure we first persist the commitment that returns back the funds to us in case of problem
                        val publishTx = PublishTx(fundingTx)

                        val nextState = WaitForFundingConfirmed(staticParams, currentTip, commitments, fundingTx, now, null, Either.Left(lastSent))

                        Pair(nextState, listOf(SendWatch(watchSpent), SendWatch(watchConfirmed), StoreState(nextState), publishTx))
                    }
                }
            }
            event is NewBlock -> Pair(this.copy(currentTip = Pair(event.height, event.Header)), listOf())
            else -> {
                logger.warning { "unhandled event $event in state ${this::class}" }
                Pair(this, listOf())
            }
        }
    }
}

@Serializable
data class WaitForFundingConfirmed(
    override val staticParams: StaticParams,
    override val currentTip: Pair<Int, @Serializable(with = BlockHeaderKSerializer::class) BlockHeader>,
    override val commitments: Commitments,
    @Serializable(with = TransactionKSerializer::class) val fundingTx: Transaction?,
    val waitingSince: Long, // how long have we been waiting for the funding tx to confirm
    val deferred: FundingLocked?,
    val lastSent: Either<FundingCreated, FundingSigned>
) : ChannelState(), HasCommitments {
    override fun updateCommitments(input: Commitments): HasCommitments = this.copy(commitments = input)

    override fun process(event: ChannelEvent): Pair<ChannelState, List<ChannelAction>> {
        return when (event) {
            is MessageReceived ->
                when (event.message) {
                    is FundingLocked -> Pair(this.copy(deferred = event.message), listOf())
                    is Error -> handleRemoteError(event.message)
                    else -> Pair(this, listOf())
                }
            is WatchReceived -> when (val watch = event.watch) {
                    is WatchEventConfirmed -> {
                        val result = fr.acinq.eclair.utils.runTrying {
                            Transaction.correctlySpends(
                                commitments.localCommit.publishableTxs.commitTx.tx,
                                listOf(watch.tx),
                                ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS
                            )
                        }
                        if (result is Try.Failure) {
                            logger.error { "funding tx verification failed: ${result.error}" }
                            if (staticParams.nodeParams.chainHash == Block.RegtestGenesisBlock.hash) {
                                logger.error { "ignoring this error on regtest" }
                            } else {
                                return Pair(this, listOf(HandleError(result.error)))
                            }
                        }
                        val watchLost = WatchLost(this.channelId, commitments.commitInput.outPoint.txid, staticParams.nodeParams.minDepthBlocks.toLong(), BITCOIN_FUNDING_LOST)
                        val channelKeyPath = keyManager.channelKeyPath(commitments.localParams, commitments.channelVersion)
                        val nextPerCommitmentPoint = keyManager.commitmentPoint(channelKeyPath, 1)
                        val fundingLocked = FundingLocked(commitments.channelId, nextPerCommitmentPoint)
                        // this is the temporary channel id that we will use in our channel_update message, the goal is to be able to use our channel
                        // as soon as it reaches NORMAL state, and before it is announced on the network
                        // (this id might be updated when the funding tx gets deeply buried, if there was a reorg in the meantime)
                        val blockHeight = watch.blockHeight
                        val txIndex = watch.txIndex
                        val shortChannelId = ShortChannelId(blockHeight, txIndex, commitments.commitInput.outPoint.index.toInt())
                        val nextState = WaitForFundingLocked(staticParams, currentTip, commitments, shortChannelId, fundingLocked)
                        val actions = listOf(SendWatch(watchLost), SendMessage(fundingLocked), StoreState(nextState))
                        if (deferred != null) {
                            logger.info { "FundingLocked has already been received" }
                            val resultPair = nextState.process(MessageReceived(deferred))
                            Pair(resultPair.first, actions + resultPair.second)
                        } else {
                            Pair(nextState, nextState.updateActions(actions))
                        }
                    }
                    is WatchEventSpent -> when (watch.tx.txid) {
                        commitments.remoteCommit.txid -> handleRemoteSpentCurrent(watch.tx)
                        else -> TODO("handle information leak")
                    }
                    else -> stay
                }
            is NewBlock -> Pair(this.copy(currentTip = Pair(event.height, event.Header)), listOf())
            is Disconnected -> Pair(Offline(this), listOf())
            else -> {
                logger.warning { "unhandled event $event in state ${this::class}" }
                Pair(this, listOf())
            }
        }
    }
}

@Serializable
data class WaitForFundingLocked(
    override val staticParams: StaticParams,
    override val currentTip: Pair<Int, @Serializable(with = BlockHeaderKSerializer::class) BlockHeader>,
    override val commitments: Commitments,
    val shortChannelId: ShortChannelId,
    val lastSent: FundingLocked
) : ChannelState(), HasCommitments {
    override fun updateCommitments(input: Commitments): HasCommitments = this.copy(commitments = input)
    override fun process(event: ChannelEvent): Pair<ChannelState, List<ChannelAction>> {
        return when (event) {
            is MessageReceived ->
                when (event.message) {
                    is FundingLocked -> {
                        // used to get the final shortChannelId, used in announcements (if minDepth >= ANNOUNCEMENTS_MINCONF this event will fire instantly)
                        val watchConfirmed = WatchConfirmed(
                            this.channelId,
                            commitments.commitInput.outPoint.txid,
                            commitments.commitInput.txOut.publicKeyScript,
                            ANNOUNCEMENTS_MINCONF.toLong(),
                            BITCOIN_FUNDING_DEEPLYBURIED
                        )
                        // TODO: context.system.eventStream.publish(ShortChannelIdAssigned(self, commitments.channelId, shortChannelId, None))
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
                            commitments.copy(remoteNextCommitInfo = Either.Right(event.message.nextPerCommitmentPoint)),
                            shortChannelId,
                            buried = false,
                            null,
                            initialChannelUpdate,
                            null,
                            null
                        )
                        Pair(nextState, listOf(SendWatch(watchConfirmed), StoreState(nextState)))
                    }
                    is Error -> handleRemoteError(event.message)
                    else -> {
                        logger.warning { "unhandled event $event in state ${this::class}" }
                        Pair(this, listOf())
                    }
                }
            is NewBlock -> Pair(this.copy(currentTip = Pair(event.height, event.Header)), listOf())
            is Disconnected -> Pair(Offline(this), listOf())
            else -> {
                logger.warning { "unhandled event $event in state ${this::class}" }
                Pair(this, listOf())
            }
        }
    }
}


@Serializable
data class Normal(
    override val staticParams: StaticParams,
    override val currentTip: Pair<Int, @Serializable(with = BlockHeaderKSerializer::class) BlockHeader>,
    override val commitments: Commitments,
    val shortChannelId: ShortChannelId,
    val buried: Boolean,
    val channelAnnouncement: ChannelAnnouncement?,
    val channelUpdate: ChannelUpdate,
    val localShutdown: Shutdown?,
    val remoteShutdown: Shutdown?
) : ChannelState(), HasCommitments {
    override fun updateCommitments(input: Commitments): HasCommitments = this.copy(commitments = input)
    override fun process(event: ChannelEvent): Pair<ChannelState, List<ChannelAction>> {
        return when (event) {
            is ExecuteCommand -> {
                when (event.command) {
                    is CMD_ADD_HTLC -> {
                        // TODO: handle shutdown in progress
                        when (val result = commitments.sendAdd(event.command, Origin.Local(event.command.id), currentBlockHeight.toLong())) {
                            is Try.Failure -> {
                                Pair(this, listOf(HandleError(result.error)))
                            }
                            is Try.Success -> {
                                val newState = this.copy(commitments = result.result.first)
                                var actions = listOf<ChannelAction>(SendMessage(result.result.second))
                                if (event.command.commit) {
                                    actions += ProcessCommand(CMD_SIGN)
                                }
                                Pair(newState, actions)
                            }
                        }
                    }
                    is CMD_FULFILL_HTLC -> {
                        when (val result = commitments.sendFulfill(event.command)) {
                            is Try.Failure -> {
                                Pair(this, listOf(HandleError(result.error)))
                            }
                            is Try.Success -> {
                                val newState = this.copy(commitments = result.result.first)
                                var actions = listOf<ChannelAction>(SendMessage(result.result.second))
                                if (event.command.commit) {
                                    actions += ProcessCommand(CMD_SIGN)
                                }
                                Pair(newState, actions)
                            }
                        }
                    }
                    is CMD_FAIL_HTLC -> {
                        when (val result = commitments.sendFail(event.command, staticParams.nodeParams.nodePrivateKey)) {
                            is Try.Failure -> {
                                Pair(this, listOf(HandleError(result.error)))
                            }
                            is Try.Success -> {
                                val newState = this.copy(commitments = result.result.first)
                                var actions = listOf<ChannelAction>(SendMessage(result.result.second))
                                if (event.command.commit) {
                                    actions += ProcessCommand(CMD_SIGN)
                                }
                                Pair(newState, actions)
                            }
                        }
                    }
                    is CMD_SIGN -> {
                        when {
                            !commitments.localHasChanges() -> {
                                logger.warning { "no changes to sign" }
                                Pair(this, listOf())
                            }
                            commitments.remoteNextCommitInfo is Either.Left -> {
                                val commitments1 = commitments.copy(remoteNextCommitInfo = Either.Left(commitments.remoteNextCommitInfo.left!!.copy(reSignAsap = true)))
                                Pair(this.copy(commitments = commitments1), listOf())
                            }
                            else -> {
                                when (val result = commitments.sendCommit(keyManager, logger)) {
                                    is Try.Failure -> {
                                        Pair(this, listOf(HandleError(result.error)))
                                    }
                                    is Try.Success -> {
                                        val commitments1 = result.result.first
                                        val nextRemoteCommit = commitments1.remoteNextCommitInfo.left!!.nextRemoteCommit
                                        val nextCommitNumber = nextRemoteCommit.index
                                        // we persist htlc data in order to be able to claim htlc outputs in case a revoked tx is published by our
                                        // counterparty, so only htlcs above remote's dust_limit matter
                                        val trimmedHtlcs = Transactions.trimOfferedHtlcs(commitments.remoteParams.dustLimit, nextRemoteCommit.spec) + Transactions.trimReceivedHtlcs(commitments.remoteParams.dustLimit, nextRemoteCommit.spec)
                                        val htlcInfos = trimmedHtlcs.map { it.add }.map {
                                            logger.info { "adding paymentHash=${it.paymentHash} cltvExpiry=${it.cltvExpiry} to htlcs db for commitNumber=$nextCommitNumber" }
                                            HtlcInfo(channelId, nextCommitNumber, it.paymentHash, it.cltvExpiry)
                                        }
                                        val nextState = this.copy(commitments = result.result.first)
                                        val actions = listOf(StoreHtlcInfos(htlcInfos), StoreState(nextState), SendMessage(result.result.second))
                                        Pair(nextState, nextState.updateActions(actions))
                                    }
                                }
                            }
                        }
                    }
                    else -> {
                        logger.warning { "unhandled event $event in state ${this::class}" }
                        Pair(this, listOf())
                    }
                }
            }
            is MessageReceived -> {
                when (event.message) {
                    is UpdateAddHtlc -> {
                        val htlc = event.message
                        when (val result = commitments.receiveAdd(htlc)) {
                            is Try.Failure -> Pair(this, listOf(HandleError(result.error)))
                            is Try.Success -> {
                                val newState = this.copy(commitments = result.result)
                                var actions = listOf<ChannelAction>()
                                Pair(newState, actions)
                            }
                        }
                    }
                    is UpdateFulfillHtlc -> {
                        // README: we consider that a payment is fulfilled as soon as we have the preimage (we don't wait for a commit signature)
                        when (val result = commitments.receiveFulfill(event.message)) {
                            is Try.Failure -> Pair(this, listOf(HandleError(result.error)))
                            is Try.Success -> Pair(this.copy(commitments = result.result.first), listOf(ProcessFulfill(event.message)))
                        }
                    }
                    is UpdateFailHtlc -> {
                        // README: we don't relay payments, so we don't need to send failures upstream
                        when (val result = commitments.receiveFail(event.message)) {
                            is Try.Failure -> Pair(this, listOf(HandleError(result.error)))
                            is Try.Success -> Pair(this.copy(commitments = result.result.first), listOf())
                        }
                    }
                    is CommitSig -> {
                        // README: we don't relay payments, so we don't need to send failures upstream
                        when (val result = commitments.receiveCommit(event.message, keyManager, logger)) {
                            is Try.Failure -> Pair(this, listOf(HandleError(result.error))) // TODO: handle invalid sig!!
                            is Try.Success -> {
                                if (result.result.first.availableBalanceForSend() != commitments.availableBalanceForSend()) {
                                    // TODO: publish "balance updated" event
                                }
                                val nextState = this.copy(commitments = result.result.first)
                                var actions = listOf<ChannelAction>(
                                    SendMessage(result.result.second),
                                    StoreState(nextState)
                                )
                                if (result.result.first.localHasChanges()) {
                                    actions += listOf<ChannelAction>(ProcessCommand(CMD_SIGN))
                                }
                                Pair(nextState, nextState.updateActions(actions))
                            }
                        }
                    }
                    is RevokeAndAck -> {
                        when (val result = commitments.receiveRevocation(event.message)) {
                            is Try.Failure -> Pair(this, listOf(HandleError(result.error))) // TODO: handle invalid sig!!
                            is Try.Success -> {
                                // TODO: handle shutdown
                                val nextState = this.copy(commitments = result.result.first)
                                var actions = listOf<ChannelAction>(StoreState(nextState)) + result.result.second
                                if (result.result.first.localHasChanges() && commitments.remoteNextCommitInfo.left?.reSignAsap == true) {
                                    actions += listOf<ChannelAction>(ProcessCommand(CMD_SIGN))
                                }
                                Pair(nextState, nextState.updateActions(actions))
                            }
                        }
                    }
                    is Error -> handleRemoteError(event.message)
                    else -> {
                        logger.warning { "unhandled event $event in state ${this::class}" }
                        Pair(this, listOf())
                    }
                }
            }
            is NewBlock -> {
                logger.info { "new tip ${event.height} ${event.Header}" }
                val newState = this.copy(currentTip = Pair(event.height, event.Header))
                Pair(newState, listOf())
            }
            is Disconnected -> Pair(Offline(this), listOf())
            is WatchReceived -> when (val watch = event.watch) {
                is WatchEventSpent -> when {
                    watch.tx.txid == commitments.remoteCommit.txid -> handleRemoteSpentCurrent(watch.tx)
                    commitments.remoteNextCommitInfo.left?.nextRemoteCommit?.txid == watch.tx.txid -> handleRemoteSpentNext(watch.tx)
                    else -> handleRemoteSpentOther(watch.tx)
                }
                else -> stay
            }
            else -> {
                logger.warning { "unhandled event $event in state ${this::class}" }
                stay
            }
        }
    }
}

@Serializable
data class Closing(
    override val staticParams: StaticParams,
    override val currentTip: Pair<Int, @Serializable(with = BlockHeaderKSerializer::class) BlockHeader>,
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
) : ChannelState(), HasCommitments {

    private val spendingTxes : List<Transaction> by lazy {
        mutualClosePublished + revokedCommitPublished.map { it.commitTx } +
                listOfNotNull(localCommitPublished?.commitTx,
                    remoteCommitPublished?.commitTx,
                    nextRemoteCommitPublished?.commitTx,
                    futureRemoteCommitPublished?.commitTx)
    }

    override fun process(event: ChannelEvent): Pair<ChannelState, List<ChannelAction>> {
        return when (event) {
            is ExecuteCommand -> when (event.command) {
                is CMD_FULFILL_HTLC -> {
                    when (val result = commitments.sendFulfill(event.command)) {
                        is Try.Success -> {
                            logger.info { "got valid payment preimage, recalculating transactions to redeem the corresponding htlc on-chain" }
                            val commitments1 = result.result.first
                            val localCommitPublished1 = localCommitPublished?.let {
                                Helpers.Closing.claimCurrentLocalCommitTxOutputs(
                                    keyManager, commitments1, it.commitTx,
                                    staticParams.nodeParams.onChainFeeConf.feeEstimator,
                                    staticParams.nodeParams.onChainFeeConf.feeTargets
                                )
                            }
                            val remoteCommitPublished1 = remoteCommitPublished?.let {
                                Helpers.Closing.claimRemoteCommitTxOutputs(
                                    keyManager, commitments1, commitments1.remoteCommit, it.commitTx,
                                    staticParams.nodeParams.onChainFeeConf.feeEstimator,
                                    staticParams.nodeParams.onChainFeeConf.feeTargets
                                )
                            }
                            val nextRemoteCommitPublished1 = nextRemoteCommitPublished?.let {
                                val remoteCommit = commitments1.remoteNextCommitInfo.left?.nextRemoteCommit ?: error("next remote commit must be defined")
                                Helpers.Closing.claimRemoteCommitTxOutputs(
                                    keyManager, commitments1, remoteCommit, remoteCommitPublished?.commitTx ?: error("remote commit must be defined"),
                                    staticParams.nodeParams.onChainFeeConf.feeEstimator,
                                    staticParams.nodeParams.onChainFeeConf.feeTargets
                                )
                            }

                            val republishList = buildList {
                                localCommitPublished1?.let { addAll(doPublish(it, channelId)) }
                                remoteCommitPublished1?.let { addAll(doPublish(it, channelId)) }
                                nextRemoteCommitPublished1?.let { addAll(doPublish(it, channelId)) }
                            }

                            val nextState = copy(
                                commitments = commitments1,
                                localCommitPublished = localCommitPublished1,
                                remoteCommitPublished = remoteCommitPublished1,
                                nextRemoteCommitPublished = nextRemoteCommitPublished1
                            )

                            newState {
                                state = nextState
                                actions = buildList {
                                    add(StoreState(nextState))
                                    addAll(republishList)
                                }
                            }
                        }
                        is Try.Failure -> returnState(HandleError(result.error))
                    }
                }
                is CMD_CLOSE -> TODO("handleCommandError")
                else -> stay
            }
            is MessageReceived -> when (val message = event.message) {
                is ChannelReestablish -> {
                    // they haven't detected that we were closing and are trying to reestablish a connection
                    // we give them one of the published txes as a hint
                    // note spendingTx != Nil (that's a requirement of DATA_CLOSING)
                    val exc = FundingTxSpent(channelId, spendingTxes.first())
                    val error = Error(channelId, exc.message)
                    returnState(SendMessage(error))
                }
                is Error -> handleRemoteError(message)
                else -> stay
            }
            is WatchReceived -> when (val watch = event.watch) {
                is WatchEventSpent -> when (val bitcoinEvent = watch.event) {
                    BITCOIN_FUNDING_SPENT -> {
                        when {
                            // we already know about this tx, probably because we have published it ourselves after successful negotiation
                            mutualClosePublished.map { it.txid }.contains(watch.tx.txid) -> stay
                            // at any time they can publish a closing tx with any sig we sent them
                            mutualCloseProposed.map { it.txid }.contains(watch.tx.txid) ->
                                TODO("handleMutualClose(tx, Either.Right(d))")
                            // this is because WatchSpent watches never expire and we are notified multiple times
                            localCommitPublished?.commitTx?.txid == watch.tx.txid -> stay
                            // this is because WatchSpent watches never expire and we are notified multiple times
                            remoteCommitPublished?.commitTx?.txid == watch.tx.txid -> stay
                            // this is because WatchSpent watches never expire and we are notified multiple times
                            nextRemoteCommitPublished?.commitTx?.txid == watch.tx.txid -> stay
                            // this is because WatchSpent watches never expire and we are notified multiple times
                            futureRemoteCommitPublished?.commitTx?.txid == watch.tx.txid -> stay
                            // counterparty may attempt to spend its last commit tx at any time
                            watch.tx.txid == commitments.remoteCommit.txid -> handleRemoteSpentCurrent(watch.tx)
                            // counterparty may attempt to spend its last commit tx at any time
                            commitments.remoteNextCommitInfo.left?.nextRemoteCommit?.txid == watch.tx.txid -> handleRemoteSpentNext(watch.tx)
                            // counterparty may attempt to spend a revoked commit tx at any time
                            else -> handleRemoteSpentOther(watch.tx)
                        }
                    }
                    BITCOIN_OUTPUT_SPENT -> {
                        // when a remote or local commitment tx containing outgoing htlcs is published on the network,
                        // we watch it in order to extract payment preimage if funds are pulled by the counterparty
                        // we can then use these preimages to fulfill origin htlcs
                        logger.info { "processing BITCOIN_OUTPUT_SPENT with txid=${watch.tx.txid} tx=$watch.tx" }
                        val revokeCommitPublishActions = mutableListOf<ChannelAction>()

                        val revokedCommitPublished1 = revokedCommitPublished.map { rev ->
                            val (newRevokeCommitPublished, tx) = Helpers.Closing.claimRevokedHtlcTxOutputs(
                                keyManager,
                                commitments,
                                rev,
                                watch.tx,
                                staticParams.nodeParams.onChainFeeConf.feeEstimator
                            )

                            tx?.let {
                                revokeCommitPublishActions += PublishTx(it)
                                revokeCommitPublishActions += SendWatch(
                                    WatchSpent(
                                        channelId,
                                        it,
                                        it.txIn.first().outPoint.index.toInt(),
                                        BITCOIN_OUTPUT_SPENT
                                    )
                                )
                            }

                            newRevokeCommitPublished
                        }

                        val nextState = copy(revokedCommitPublished = revokedCommitPublished1)
                        nextState to buildList {
                            add(StoreState(nextState))
                            // one of the outputs of the local/remote/revoked commit was spent
                            // we just put a watch to be notified when it is confirmed
                            add(
                                SendWatch(
                                    WatchConfirmed(
                                        channelId,
                                        watch.tx,
                                        staticParams.nodeParams.minDepthBlocks.toLong(),
                                        BITCOIN_TX_CONFIRMED(watch.tx)
                                    )
                                )
                            )
                            addAll(revokeCommitPublishActions)
                        }
                    }
                    is BITCOIN_TX_CONFIRMED -> {
                        logger.info { "txid=${bitcoinEvent.tx.txid} has reached mindepth, updating closing state" }
                        // first we check if this tx belongs to one of the current local/remote commits, update it and update the channel data
                        val d1 = copy(
                            localCommitPublished = localCommitPublished?.let {
                                Helpers.Closing.updateLocalCommitPublished(it, bitcoinEvent.tx)
                            },
                            remoteCommitPublished = remoteCommitPublished?.let {
                                Helpers.Closing.updateRemoteCommitPublished(it, bitcoinEvent.tx)
                            },
                            nextRemoteCommitPublished = nextRemoteCommitPublished?.let {
                                Helpers.Closing.updateRemoteCommitPublished(it, bitcoinEvent.tx)
                            },
                            futureRemoteCommitPublished = futureRemoteCommitPublished?.let {
                                Helpers.Closing.updateRemoteCommitPublished(it, bitcoinEvent.tx)
                            },
                            revokedCommitPublished = revokedCommitPublished.map {
                                Helpers.Closing.updateRevokedCommitPublished(it, bitcoinEvent.tx)
                            }
                        )

                        // if the local commitment tx just got confirmed, let's send an event telling when we will get the main output refund
                        if (d1.localCommitPublished?.commitTx?.txid == bitcoinEvent.tx.txid) {
                            // TODO
//                              context.system.eventStream.publish(
//                                LocalCommitConfirmed(
//                                    self,
//                                    remoteNodeId,
//                                    d.channelId,
//                                    blockHeight + d.commitments.remoteParams.toSelfDelay.toInt
//                                )
//                              )
                        }
                        // for our outgoing payments, let's send events if we know that they will settle on chain
                        Helpers.Closing.onchainOutgoingHtlcs(commitments.localCommit,
                            commitments.remoteCommit,
                            commitments.remoteNextCommitInfo.left?.nextRemoteCommit,
                            bitcoinEvent.tx)
                            .forEach { add ->
                                (commitments.originChannels[add.id] as? Origin.Local)?.let {
                                    // TODO context.system.eventStream.publish(PaymentSettlingOnChain(id, amount = add.amountMsat, add.paymentHash))
                                }
                            }

                        // and we also send events related to fee
                        Helpers.Closing.networkFeePaid(bitcoinEvent.tx, d1)?.let { (fee, desc) -> feePaid(fee, bitcoinEvent.tx, desc, channelId) }

                        // then let's see if any of the possible close scenarii can be considered done

                        // finally, if one of the unilateral closes is done, we move to CLOSED state, otherwise we stay (note that we don't store the state)
                        when (Helpers.Closing.isClosed(d1, bitcoinEvent.tx)) {
                            null -> newState {
                                state = d1
                                actions = listOf(StoreState(d1))
                            }
                            else -> {
                                val nextState = Closed(staticParams, currentTip)
                                newState {
                                    state = nextState
                                    actions = listOf(StoreState(nextState))
                                }
                            }
                        }
                    }
                    else -> stay
                }
                else -> stay
            }
            is Disconnected -> stay  // we don't really care at this point
            else -> stay

            /* TODO
                [ ] BITCOIN_FUNDING_PUBLISH_FAILED -> handleFundingPublishFailed()
                [ ] BITCOIN_FUNDING_TIMEOUT -> handleFundingTimeout()
                [ ] case Event(getTxResponse: GetTxWithMetaResponse, d: DATA_CLOSING) if getTxResponse.txid == d.commitments.commitInput.outPoint.txid => handleGetFundingTx(getTxResponse, d.waitingSince, d.fundingTx)
                 */
        }
    }

    override fun updateCommitments(input: Commitments): HasCommitments {
        TODO("Not yet implemented")
    }
}

/**
 * Channel is closed i.t its funding tx has been spent and the spending transactions have been confirmed, it can be forgotten
 */
@Serializable
data class Closed(override val staticParams: StaticParams, override val currentTip: Pair<Int, @Serializable(with = BlockHeaderKSerializer::class) BlockHeader>) : ChannelState() {
    override fun process(event: ChannelEvent): Pair<ChannelState, List<ChannelAction>> {
        TODO("Not yet implemented")
    }
}

@Serializable
data class ErrorInformationLeak(
    override val staticParams: StaticParams,
    override val currentTip: Pair<Int, @Serializable(with = BlockHeaderKSerializer::class) BlockHeader>,
    override val commitments: Commitments
) : ChannelState(), HasCommitments {
    override fun process(event: ChannelEvent): Pair<ChannelState, List<ChannelAction>> {
        TODO("implement this")
    }

    override fun updateCommitments(input: Commitments): HasCommitments {
        TODO("Not yet implemented")
    }
}


// Return ChannelState utils
private class ChannelStateBuilder {
    lateinit var state: ChannelState
    var actions = emptyList<ChannelAction>()
    fun build() = state to state.updateActions(actions)
}
private fun newState(init: ChannelStateBuilder.() -> Unit) = ChannelStateBuilder().apply(init).build()
private fun newState(newState: ChannelState) = ChannelStateBuilder().apply { state = newState }.build()

private val ChannelState.stay: Pair<ChannelState, List<ChannelAction>> get() = this to emptyList()
private fun ChannelState.returnState(vararg actions: ChannelAction): Pair<ChannelState, List<ChannelAction>> = this to this.updateActions(actions.toList())

object Channel {
    // see https://github.com/lightningnetwork/lightning-rfc/blob/master/07-routing-gossip.md#requirements
    val ANNOUNCEMENTS_MINCONF = 6

    // https://github.com/lightningnetwork/lightning-rfc/blob/master/02-peer-protocol.md#requirements
    val MAX_FUNDING = 10.btc
    val MAX_ACCEPTED_HTLCS = 483

    // we don't want the counterparty to use a dust limit lower than that, because they wouldn't only hurt themselves we may need them to publish their commit tx in certain cases (backup/restore)
    val MIN_DUSTLIMIT = 546.sat

    // we won't exchange more than this many signatures when negotiating the closing fee
    val MAX_NEGOTIATION_ITERATIONS = 20

    // this is defined in BOLT 11
    val MIN_CLTV_EXPIRY_DELTA = CltvExpiryDelta(9)
    val MAX_CLTV_EXPIRY_DELTA = CltvExpiryDelta(7 * 144) // one week

    // since BOLT 1.1, there is a max value for the refund delay of the main commitment tx
    val MAX_TO_SELF_DELAY = CltvExpiryDelta(2016)

    // as a fundee, we will wait that much time for the funding tx to confirm (funder will rely on the funding tx being double-spent)
    val FUNDING_TIMEOUT_FUNDEE = 5 * 24 * 3600 // 5 days, in seconds

    fun handleSync(channelReestablish: ChannelReestablish, d: HasCommitments, keyManager: KeyManager, log: Logger): Pair<Commitments, List<ChannelAction>> {
        val sendQueue = ArrayList<ChannelAction>()
        // first we clean up unacknowledged updates
        log.verbose { "discarding proposed OUT: ${d.commitments.localChanges.proposed}" }
        log.verbose { "discarding proposed IN: ${d.commitments.remoteChanges.proposed}" }
        val commitments1 = d.commitments.copy(
            localChanges = d.commitments.localChanges.copy(proposed = emptyList()),
            remoteChanges = d.commitments.remoteChanges.copy(proposed = emptyList()),
            localNextHtlcId = d.commitments.localNextHtlcId - d.commitments.localChanges.proposed.filterIsInstance<UpdateAddHtlc>().size,
            remoteNextHtlcId = d.commitments.remoteNextHtlcId - d.commitments.remoteChanges.proposed.filterIsInstance<UpdateAddHtlc>().size
        )
        log.verbose { "localNextHtlcId=${d.commitments.localNextHtlcId}->${commitments1.localNextHtlcId}" }
        log.verbose { "remoteNextHtlcId=${d.commitments.remoteNextHtlcId}->${commitments1.remoteNextHtlcId}" }

        fun resendRevocation(): Unit {
            // let's see the state of remote sigs
            if (commitments1.localCommit.index == channelReestablish.nextRemoteRevocationNumber) {
                // nothing to do
            } else if (commitments1.localCommit.index == channelReestablish.nextRemoteRevocationNumber + 1) {
                // our last revocation got lost, let's resend it
                log.verbose { "re-sending last revocation" }
                val channelKeyPath = keyManager.channelKeyPath(d.commitments.localParams, d.commitments.channelVersion)
                val localPerCommitmentSecret = keyManager.commitmentSecret(channelKeyPath, d.commitments.localCommit.index - 1)
                val localNextPerCommitmentPoint = keyManager.commitmentPoint(channelKeyPath, d.commitments.localCommit.index + 1)
                val revocation = RevokeAndAck(
                    channelId = commitments1.channelId,
                    perCommitmentSecret = localPerCommitmentSecret,
                    nextPerCommitmentPoint = localNextPerCommitmentPoint
                )
                sendQueue.add(SendMessage(revocation))
            } else throw RevocationSyncError(d.channelId)
        }

        when {
            commitments1.remoteNextCommitInfo.isLeft && commitments1.remoteNextCommitInfo.left!!.nextRemoteCommit.index + 1 == channelReestablish.nextLocalCommitmentNumber -> {
                // we had sent a new sig and were waiting for their revocation
                // they had received the new sig but their revocation was lost during the disconnection
                // they will send us the revocation, nothing to do here
                log.verbose { "waiting for them to re-send their last revocation" }
                resendRevocation()
            }
            commitments1.remoteNextCommitInfo.isLeft && commitments1.remoteNextCommitInfo.left!!.nextRemoteCommit.index == channelReestablish.nextLocalCommitmentNumber -> {
                // we had sent a new sig and were waiting for their revocation
                // they didn't receive the new sig because of the disconnection
                // we just resend the same updates and the same sig

                val revWasSentLast = commitments1.localCommit.index > commitments1.remoteNextCommitInfo.left!!.sentAfterLocalCommitIndex
                if (!revWasSentLast) resendRevocation()

                log.verbose { "re-sending previously local signed changes: ${commitments1.localChanges.signed}" }
                commitments1.localChanges.signed.forEach { sendQueue.add(SendMessage(it)) }
                log.verbose { "re-sending the exact same previous sig" }
                sendQueue.add(SendMessage(commitments1.remoteNextCommitInfo.left!!.sent))
                if (revWasSentLast) resendRevocation()
            }
        }

        if (commitments1.remoteNextCommitInfo.isLeft) {
            // we expect them to (re-)send the revocation immediately
            // TODO: set a timer and wait for their revocation
        }

        if (commitments1.localHasChanges()) {
            sendQueue.add(ProcessCommand(CMD_SIGN))
        }

        return Pair(commitments1, sendQueue)
    }
}
