package fr.acinq.eclair.channel

import fr.acinq.bitcoin.*
import fr.acinq.eclair.*
import fr.acinq.eclair.blockchain.*
import fr.acinq.eclair.blockchain.fee.ConstantFeeEstimator
import fr.acinq.eclair.blockchain.fee.OnchainFeerates
import fr.acinq.eclair.channel.Channel.ANNOUNCEMENTS_MINCONF
import fr.acinq.eclair.channel.Channel.MAX_NEGOTIATION_ITERATIONS
import fr.acinq.eclair.channel.Channel.handleSync
import fr.acinq.eclair.channel.Channel.publishActions
import fr.acinq.eclair.channel.ChannelVersion.Companion.USE_STATIC_REMOTEKEY_BIT
import fr.acinq.eclair.crypto.KeyManager
import fr.acinq.eclair.crypto.ShaChain
import fr.acinq.eclair.io.*
import fr.acinq.eclair.router.Announcements
import fr.acinq.eclair.transactions.CommitmentSpec
import fr.acinq.eclair.transactions.Scripts
import fr.acinq.eclair.transactions.Transactions
import fr.acinq.eclair.utils.*
import fr.acinq.eclair.wire.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass
import org.kodein.log.Logger
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
data class HandleCommandFailed(val cmd: Command, val error: Throwable?) : ChannelAction()
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
    abstract val currentOnchainFeerates: OnchainFeerates
    val currentBlockHeight: Int get() = currentTip.first
    val keyManager: KeyManager get() = staticParams.nodeParams.keyManager
    val privateKey: PrivateKey get() = staticParams.nodeParams.keyManager.nodeKey.privateKey

    /**
     * @param event input event (for example, a message was received, a command was sent by the GUI/API, ...
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

    /**
     * update outgoing messages to include an encrypted backup when necessary
     */
    private fun updateActions(actions: List<ChannelAction>): List<ChannelAction> = when {
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

    internal fun handleCommandError(cmd: Command, error: Throwable): Pair<ChannelState, List<ChannelAction>> {
        logger.warning(error) { "processing $cmd in state $this failed" }
        return Pair(this, listOf(HandleCommandFailed(cmd, error)))
    }

    @Transient
    val logger = EclairLoggerFactory.newLogger<ChannelState>()
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
            }
        }

        private val serializationModules = SerializersModule {
            include(Tlv.serializersModule)
            include(KeyManager.serializersModule)
            include(UpdateMessage.serializersModule)
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
data class WaitForInit(override val staticParams: StaticParams, override val currentTip: Pair<Int, @Serializable(with = BlockHeaderKSerializer::class) BlockHeader>, override val currentOnchainFeerates: OnchainFeerates) : ChannelState() {
    override fun processInternal(event: ChannelEvent): Pair<ChannelState, List<ChannelAction>> {
        return when {
            event is InitFundee -> {
                val nextState = WaitForOpenChannel(staticParams, currentTip, currentOnchainFeerates, event.temporaryChannelId, event.localParams, event.remoteInit)
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
                val nextState = WaitForAcceptChannel(staticParams, currentTip, currentOnchainFeerates, event, open)
                Pair(nextState, listOf(SendMessage(open)))
            }
            event is Restore && event.state is Closing && event.state.commitments.nothingAtStake() -> {
                logger.info { "we have nothing at stake, going straight to CLOSED" }
                Pair(Closed(event.state), listOf())
            }
            event is Restore && event.state is Closing -> {
                val closingType = event.state.closingTypeAlreadyKnown()
                logger.info { "channel is closing (closing type = ${closingType ?: "unknown yet"}" }
                when (closingType) {
                    is MutualClose -> {
                        Pair(event.state, publishActions(closingType.tx, event.state.channelId, event.state.staticParams.nodeParams.minDepthBlocks.toLong()))
                    }
                    null -> {
                        val commitments = event.state.commitments
                        var actions = listOf<ChannelAction>(
                            SendWatch(WatchSpent(event.state.channelId, commitments.commitInput.outPoint.txid, commitments.commitInput.outPoint.index.toInt(), commitments.commitInput.txOut.publicKeyScript, BITCOIN_FUNDING_SPENT)),
                            //SendWatch(WatchLost(event.state.channelId, commitments.commitInput.outPoint.txid, event.state.staticParams.nodeParams.minDepthBlocks.toLong(), BITCOIN_FUNDING_LOST))
                        )
                        actions = actions + event.state.mutualClosePublished.map { publishActions(it, event.state.channelId, event.state.staticParams.nodeParams.minDepthBlocks.toLong()) }.flatten()
                        Pair(event.state, actions)
                    }
                    else -> {
                        // TODO: handle other closing types
                        unhandled(event)
                    }
                }
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
                Pair(Offline(event.state), actions)
            }
            event is NewBlock -> Pair(this.copy(currentTip = Pair(event.height, event.Header)), listOf())
            event is ExecuteCommand && event.command is CMD_CLOSE -> Pair(Aborted(staticParams, currentTip, currentOnchainFeerates), listOf())
            else -> unhandled(event)
        }
    }

    override fun handleLocalError(event: ChannelEvent, t: Throwable): Pair<ChannelState, List<ChannelAction>> {
        logger.error(t) { "error on event $event in state ${this::class}" }
        return Pair(this, listOf(HandleError(t)))
    }
}

@Serializable
data class Offline(val state: ChannelState) : ChannelState() {
    override val staticParams: StaticParams
        get() = state.staticParams
    override val currentTip: Pair<Int, BlockHeader>
        get() = state.currentTip
    override val currentOnchainFeerates: OnchainFeerates
        get() = state.currentOnchainFeerates

    override fun processInternal(event: ChannelEvent): Pair<ChannelState, List<ChannelAction>> {
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
                        Pair(nextState, listOf(SendMessage(error)))
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
                        Pair(Syncing(nextState, false), listOf(SendMessage(channelReestablish)))
                    }
                    else -> unhandled(event)
                }
            }
            event is NewBlock -> {
                // TODO: is this the right thing to do ?
                val (newState, actions) = state.process(event)
                Pair(Offline(newState), listOf())
            }
            else -> unhandled(event)
        }
    }

    override fun handleLocalError(event: ChannelEvent, t: Throwable): Pair<ChannelState, List<ChannelAction>> {
        logger.error(t) { "error on event $event in state ${this::class}" }
        return Pair(this, listOf(HandleError(t)))
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
    override val currentOnchainFeerates: OnchainFeerates
        get() = state.currentOnchainFeerates

    override fun processInternal(event: ChannelEvent): Pair<ChannelState, List<ChannelAction>> {
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
                        val (nextState1, actions1) = Syncing(nextState as ChannelState, waitForTheirReestablishMessage = false).processInternal(event)
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
                                    val nextState = WaitForRemotePublishFutureComitment(staticParams, state.currentTip, state.currentOnchainFeerates, state.commitments, event.message)
                                    val actions = listOf(
                                        StoreState(nextState),
                                        SendMessage(error)
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
                                val nextState = WaitForRemotePublishFutureComitment(staticParams, state.currentTip, state.currentOnchainFeerates, state.commitments, event.message)
                                val actions = listOf(
                                    StoreState(nextState),
                                    SendMessage(error)
                                )
                                Pair(nextState, actions)
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
                            currentOnchainFeerates.mutualCloseFeeratePerKw
                        )
                        val closingTxProposed1 = state.closingTxProposed + listOf(listOf(ClosingTxProposed(closingTx.tx, closingSigned)))
                        val nextState = state.copy(closingTxProposed = closingTxProposed1)
                        val actions = listOf(StoreState(nextState), SendMessage(state.localShutdown), SendMessage(closingSigned))
                        return Pair(nextState, actions)
                    }
                    state is Negotiating -> {
                        // we start a new round of negotiation
                        val closingTxProposed1 = if (state.closingTxProposed.last().isEmpty()) state.closingTxProposed else state.closingTxProposed + listOf(listOf())
                        val nextState = state.copy(closingTxProposed = closingTxProposed1)
                        val actions = listOf(StoreState(nextState), SendMessage(state.localShutdown))
                        return Pair(nextState, actions)
                    }
                    else -> unhandled(event)
                }
            event is NewBlock -> {
                // TODO: is this the right thing to do ?
                val (newState, actions) = state.process(event)
                Pair(Syncing(newState, waitForTheirReestablishMessage), listOf())
            }
            else -> unhandled(event)
        }
    }

    override fun handleLocalError(event: ChannelEvent, t: Throwable): Pair<ChannelState, List<ChannelAction>> {
        logger.error(t) { "error on event $event in state ${this::class}" }
        return Pair(this, listOf(HandleError(t)))
    }

}

@Serializable
data class WaitForRemotePublishFutureComitment(
    override val staticParams: StaticParams,
    override val currentTip: Pair<Int, @Serializable(with = BlockHeaderKSerializer::class) BlockHeader>,
    override val currentOnchainFeerates: OnchainFeerates,
    override val commitments: Commitments,
    val channelReestablish: ChannelReestablish
) : ChannelState(), HasCommitments {
    override fun updateCommitments(input: Commitments): HasCommitments = this.copy(commitments = input)

    override fun processInternal(event: ChannelEvent): Pair<ChannelState, List<ChannelAction>> {
        TODO("Not yet implemented")
    }

    override fun handleLocalError(event: ChannelEvent, t: Throwable): Pair<ChannelState, List<ChannelAction>> {
        logger.error(t) { "error on event $event in state ${this::class}" }
        val error = Error(channelId, t.message)
        return Pair(Aborted(staticParams, currentTip, currentOnchainFeerates), listOf(SendMessage(error)))
    }
}

@Serializable
data class WaitForOpenChannel(
    override val staticParams: StaticParams,
    override val currentTip: Pair<Int, @Serializable(with = BlockHeaderKSerializer::class) BlockHeader>,
    override val currentOnchainFeerates: OnchainFeerates,
    @Serializable(with = ByteVector32KSerializer::class) val temporaryChannelId: ByteVector32,
    val localParams: LocalParams,
    val remoteInit: Init
) : ChannelState() {
    override fun processInternal(event: ChannelEvent): Pair<ChannelState, List<ChannelAction>> {
        return when {
            event is MessageReceived ->
                when (event.message) {
                    is OpenChannel -> {
                        var channelVersion = event.message.channelVersion ?: ChannelVersion.STANDARD
                        try {
                            Helpers.validateParamsFundee(staticParams.nodeParams, event.message, channelVersion, currentOnchainFeerates.commitmentFeeratePerKw)
                        } catch (e: Throwable) {
                            logger.error(e) { "invalid ${event.message} in state $this" }
                            return Pair(Aborted(staticParams, currentTip, currentOnchainFeerates), listOf(SendMessage(Error(temporaryChannelId, e.message))))
                        }
                        val fundingPubkey = keyManager.fundingPublicKey(localParams.fundingKeyPath).publicKey
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
                            currentOnchainFeerates,
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
                    is Error -> {
                        logger.error { "peer send error: ascii=${event.message.toAscii()} bin=${event.message.data.toHex()}" }
                        return Pair(Aborted(staticParams, currentTip, currentOnchainFeerates), listOf())
                    }
                    else -> unhandled(event)
                }
            event is ExecuteCommand && event.command is CMD_CLOSE -> Pair(Aborted(staticParams, currentTip, currentOnchainFeerates), listOf())
            event is NewBlock -> Pair(this.copy(currentTip = Pair(event.height, event.Header)), listOf())
            else -> unhandled(event)
        }
    }

    override fun handleLocalError(event: ChannelEvent, t: Throwable): Pair<ChannelState, List<ChannelAction>> {
        logger.error(t) { "error on event $event in state ${this::class}" }
        val error = Error(temporaryChannelId, t.message)
        return Pair(Aborted(staticParams, currentTip, currentOnchainFeerates), listOf(SendMessage(error)))
    }
}

@Serializable
data class WaitForFundingCreated(
    override val staticParams: StaticParams,
    override val currentTip: Pair<Int, @Serializable(with = BlockHeaderKSerializer::class) BlockHeader>,
    override val currentOnchainFeerates: OnchainFeerates,
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
            event is MessageReceived ->
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
                                    payments = mapOf(),
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
                                val nextState = WaitForFundingConfirmed(staticParams, currentTip, currentOnchainFeerates, commitments, null, currentTimestampMillis() / 1000, null, Either.Right(fundingSigned))
                                val actions = listOf(SendWatch(watchSpent), SendWatch(watchConfirmed), SendMessage(fundingSigned), ChannelIdSwitch(temporaryChannelId, channelId), StoreState(nextState))
                                Pair(nextState, actions)
                            }
                        }
                    }
                    is Error -> {
                        logger.error { "peer send error: ascii=${event.message.toAscii()} bin=${event.message.data.toHex()}" }
                        return Pair(Aborted(staticParams, currentTip, currentOnchainFeerates), listOf())
                    }
                    else -> unhandled(event)
                }
            event is ExecuteCommand && event.command is CMD_CLOSE -> Pair(Aborted(staticParams, currentTip, currentOnchainFeerates), listOf())
            event is NewBlock -> Pair(this.copy(currentTip = Pair(event.height, event.Header)), listOf())
            else -> unhandled(event)
        }
    }

    override fun handleLocalError(event: ChannelEvent, t: Throwable): Pair<ChannelState, List<ChannelAction>> {
        logger.error(t) { "error on event $event in state ${this::class}" }
        val error = Error(temporaryChannelId, t.message)
        return Pair(Aborted(staticParams, currentTip, currentOnchainFeerates), listOf(SendMessage(error)))
    }
}

@Serializable
data class WaitForAcceptChannel(
    override val staticParams: StaticParams,
    override val currentTip: Pair<Int, @Serializable(with = BlockHeaderKSerializer::class) BlockHeader>,
    override val currentOnchainFeerates: OnchainFeerates,
    val initFunder: InitFunder,
    val lastSent: OpenChannel
) :
    ChannelState() {
    override fun processInternal(event: ChannelEvent): Pair<ChannelState, List<ChannelAction>> {
        return when {
            event is MessageReceived && event.message is AcceptChannel -> {
                try {
                    Helpers.validateParamsFunder(staticParams.nodeParams, lastSent, event.message)
                } catch (e: Throwable) {
                    logger.error(e) { "invalid ${event.message} in state $this" }
                    return Pair(Aborted(staticParams, currentTip, currentOnchainFeerates), listOf(SendMessage(Error(initFunder.temporaryChannelId, e.message))))
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
                logger.verbose { "remote params: $remoteParams" }
                val localFundingPubkey = keyManager.fundingPublicKey(initFunder.localParams.fundingKeyPath)
                val fundingPubkeyScript = ByteVector(Script.write(Script.pay2wsh(Scripts.multiSig2of2(localFundingPubkey.publicKey, remoteParams.fundingPubKey))))
                val makeFundingTx = MakeFundingTx(fundingPubkeyScript, initFunder.fundingAmount, initFunder.fundingTxFeeratePerKw)
                val nextState = WaitForFundingInternal(
                    staticParams,
                    currentTip,
                    currentOnchainFeerates,
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
            event is MessageReceived && event.message is Error -> {
                logger.error { "peer send error: ascii=${event.message.toAscii()} bin=${event.message.data.toHex()}" }
                Pair(Aborted(staticParams, currentTip, currentOnchainFeerates), listOf())
            }
            event is ExecuteCommand && event.command is CMD_CLOSE -> Pair(Aborted(staticParams, currentTip, currentOnchainFeerates ), listOf())
            event is NewBlock -> Pair(this.copy(currentTip = Pair(event.height, event.Header)), listOf())
            else -> unhandled(event)
        }
    }

    override fun handleLocalError(event: ChannelEvent, t: Throwable): Pair<ChannelState, List<ChannelAction>> {
        logger.error(t) { "error on event $event in state ${this::class}" }
        val error = Error(initFunder.temporaryChannelId, t.message)
        return Pair(Aborted(staticParams, currentTip, currentOnchainFeerates), listOf(SendMessage(error)))
    }
}

@Serializable
data class WaitForFundingInternal(
    override val staticParams: StaticParams,
    override val currentTip: Pair<Int, @Serializable(with = BlockHeaderKSerializer::class) BlockHeader>,
    override val currentOnchainFeerates: OnchainFeerates,
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
            event is MakeFundingTxResponse -> {
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
                    currentOnchainFeerates,
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
            event is MessageReceived && event.message is Error -> {
                logger.error { "peer send error: ascii=${event.message.toAscii()} bin=${event.message.data.toHex()}" }
                Pair(Aborted(staticParams, currentTip, currentOnchainFeerates), listOf())
            }
            event is ExecuteCommand && event.command is CMD_CLOSE -> Pair(Aborted(staticParams, currentTip, currentOnchainFeerates ), listOf())
            event is NewBlock -> Pair(this.copy(currentTip = Pair(event.height, event.Header)), listOf())
            else -> unhandled(event)
        }
    }

    override fun handleLocalError(event: ChannelEvent, t: Throwable): Pair<ChannelState, List<ChannelAction>> {
        logger.error(t) { "error on event $event in state ${this::class}" }
        val error = Error(temporaryChannelId, t.message)
        return Pair(Aborted(staticParams, currentTip, currentOnchainFeerates), listOf(SendMessage(error)))
    }
}

@Serializable
data class WaitForFundingSigned(
    override val staticParams: StaticParams,
    override val currentTip: Pair<Int, @Serializable(with = BlockHeaderKSerializer::class) BlockHeader>,
    override val currentOnchainFeerates: OnchainFeerates,
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
                            payments = mapOf(),
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

                        val nextState = WaitForFundingConfirmed(staticParams, currentTip, currentOnchainFeerates, commitments, fundingTx, now, null, Either.Left(lastSent))

                        Pair(nextState, listOf(SendWatch(watchSpent), SendWatch(watchConfirmed), StoreState(nextState), publishTx))
                    }
                }
            }
            event is MessageReceived && event.message is Error -> {
                logger.error { "peer send error: ascii=${event.message.toAscii()} bin=${event.message.data.toHex()}" }
                Pair(Aborted(staticParams, currentTip, currentOnchainFeerates ), listOf())
            }
            event is ExecuteCommand && event.command is CMD_CLOSE -> Pair(Aborted(staticParams, currentTip, currentOnchainFeerates ), listOf())
            event is NewBlock -> Pair(this.copy(currentTip = Pair(event.height, event.Header)), listOf())
            else -> unhandled(event)
        }
    }

    override fun handleLocalError(event: ChannelEvent, t: Throwable): Pair<ChannelState, List<ChannelAction>> {
        logger.error(t) { "error on event $event in state ${this::class}" }
        val error = Error(channelId, t.message)
        return Pair(Aborted(staticParams, currentTip, currentOnchainFeerates ), listOf(SendMessage(error)))
    }
}

@Serializable
data class WaitForFundingConfirmed(
    override val staticParams: StaticParams,
    override val currentTip: Pair<Int, @Serializable(with = BlockHeaderKSerializer::class) BlockHeader>,
    override val currentOnchainFeerates: OnchainFeerates,
    override val commitments: Commitments,
    @Serializable(with = TransactionKSerializer::class) val fundingTx: Transaction?,
    val waitingSince: Long, // how long have we been waiting for the funding tx to confirm
    val deferred: FundingLocked?,
    val lastSent: Either<FundingCreated, FundingSigned>
) : ChannelState(), HasCommitments {
    override fun updateCommitments(input: Commitments): HasCommitments = this.copy(commitments = input)

    override fun processInternal(event: ChannelEvent): Pair<ChannelState, List<ChannelAction>> {
        return when (event) {
            is MessageReceived ->
                when (event.message) {
                    is FundingLocked -> Pair(this.copy(deferred = event.message), listOf())
                    else -> Pair(this, listOf())
                }
            is WatchReceived ->
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
                            if (staticParams.nodeParams.chainHash == Block.RegtestGenesisBlock.hash) {
                                logger.error { "ignoring this error on regtest" }
                                return handleLocalError(event, InvalidCommitmentSignature(channelId, event.watch.tx))
                            } else {
                                return handleLocalError(event, InvalidCommitmentSignature(channelId, event.watch.tx))
                            }
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
                        val nextState = WaitForFundingLocked(staticParams, currentTip, currentOnchainFeerates, commitments, shortChannelId, fundingLocked)
                        val actions = listOf(SendWatch(watchLost), SendMessage(fundingLocked), StoreState(nextState))
                        if (deferred != null) {
                            logger.info { "FundingLocked has already been received" }
                            val resultPair = nextState.process(MessageReceived(deferred))
                            Pair(resultPair.first, actions + resultPair.second)
                        } else {
                            Pair(nextState, actions)
                        }
                    }
                    is WatchEventSpent -> {
                        // TODO: handle funding tx spent
                        Pair(this, listOf())
                    }
                    else -> unhandled(event)
                }
            is NewBlock -> Pair(this.copy(currentTip = Pair(event.height, event.Header)), listOf())
            is Disconnected -> Pair(Offline(this), listOf())
            else -> unhandled(event)
        }
    }

    override fun handleLocalError(event: ChannelEvent, t: Throwable): Pair<ChannelState, List<ChannelAction>> {
        logger.error(t) { "error on event $event in state ${this::class}" }
        val error = Error(channelId, t.message)
        return Pair(Aborted(staticParams, currentTip, currentOnchainFeerates), listOf(SendMessage(error)))
    }
}

@Serializable
data class WaitForFundingLocked(
    override val staticParams: StaticParams,
    override val currentTip: Pair<Int, @Serializable(with = BlockHeaderKSerializer::class) BlockHeader>,
    override val currentOnchainFeerates: OnchainFeerates,
    override val commitments: Commitments,
    val shortChannelId: ShortChannelId,
    val lastSent: FundingLocked
) : ChannelState(), HasCommitments {
    override fun updateCommitments(input: Commitments): HasCommitments = this.copy(commitments = input)
    override fun processInternal(event: ChannelEvent): Pair<ChannelState, List<ChannelAction>> {
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
                            currentOnchainFeerates,
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
                    else -> unhandled(event)
                }
            is NewBlock -> Pair(this.copy(currentTip = Pair(event.height, event.Header)), listOf())
            is Disconnected -> Pair(Offline(this), listOf())
            else -> unhandled(event)
        }
    }

    override fun handleLocalError(event: ChannelEvent, t: Throwable): Pair<ChannelState, List<ChannelAction>> {
        logger.error(t) { "error on event $event in state ${this::class}" }
        val error = Error(channelId, t.message)
        return Pair(Aborted(staticParams, currentTip, currentOnchainFeerates), listOf(SendMessage(error)))
    }
}


@Serializable
data class Normal(
    override val staticParams: StaticParams,
    override val currentTip: Pair<Int, @Serializable(with = BlockHeaderKSerializer::class) BlockHeader>,
    override val currentOnchainFeerates: OnchainFeerates,
    override val commitments: Commitments,
    val shortChannelId: ShortChannelId,
    val buried: Boolean,
    val channelAnnouncement: ChannelAnnouncement?,
    val channelUpdate: ChannelUpdate,
    val localShutdown: Shutdown?,
    val remoteShutdown: Shutdown?
) : ChannelState(), HasCommitments {
    override fun updateCommitments(input: Commitments): HasCommitments = this.copy(commitments = input)
    override fun processInternal(event: ChannelEvent): Pair<ChannelState, List<ChannelAction>> {
        return when (event) {
            is ExecuteCommand -> {
                when (event.command) {
                    is CMD_ADD_HTLC -> {
                        // TODO: handle shutdown in progress
                        when (val result = commitments.sendAdd(event.command, event.command.paymentId, currentBlockHeight.toLong())) {
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
                                        handleLocalError(event, result.error)
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
                                        Pair(nextState, actions)
                                    }
                                }
                            }
                        }
                    }
                    is CMD_CLOSE -> {
                        val localScriptPubkey = event.command.scriptPubKey ?: commitments.localParams.defaultFinalScriptPubKey
                        when {
                            this.localShutdown != null -> handleCommandError(event.command, ClosingAlreadyInProgress(channelId))
                            this.commitments.localHasUnsignedOutgoingHtlcs() -> handleCommandError(event.command, CannotCloseWithUnsignedOutgoingHtlcs(channelId))
                            !Helpers.Closing.isValidFinalScriptPubkey(localScriptPubkey) -> handleCommandError(event.command, InvalidFinalScript(channelId))
                            else -> {
                                val shutdown = Shutdown(channelId, localScriptPubkey)
                                val newState = this.copy(localShutdown = shutdown)
                                val actions = listOf(StoreState(newState), SendMessage(shutdown))
                                Pair(newState, actions)
                            }
                        }
                    }
                    else -> unhandled(event)
                }
            }
            is MessageReceived -> {
                when (event.message) {
                    is UpdateAddHtlc -> {
                        val htlc = event.message
                        when (val result = commitments.receiveAdd(htlc)) {
                            is Try.Failure -> handleLocalError(event, result.error)
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
                            is Try.Failure -> handleLocalError(event, result.error)
                            is Try.Success -> Pair(this.copy(commitments = result.result.first), listOf(ProcessFulfill(event.message)))
                        }
                    }
                    is UpdateFailHtlc -> {
                        // README: we don't relay payments, so we don't need to send failures upstream
                        when (val result = commitments.receiveFail(event.message)) {
                            is Try.Failure -> handleLocalError(event, result.error)
                            is Try.Success -> Pair(this.copy(commitments = result.result.first), listOf())
                        }
                    }
                    is CommitSig -> {
                        // README: we don't relay payments, so we don't need to send failures upstream
                        when (val result = commitments.receiveCommit(event.message, keyManager, logger)) {
                            is Try.Failure -> handleLocalError(event, result.error)
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
                                Pair(nextState, actions)
                            }
                        }
                    }
                    is RevokeAndAck -> {
                        when (val result = commitments.receiveRevocation(event.message)) {
                            is Try.Failure -> handleLocalError(event, result.error)
                            is Try.Success -> {
                                // TODO: handle shutdown
                                val nextState = this.copy(commitments = result.result.first)
                                var actions = listOf<ChannelAction>(StoreState(nextState)) + result.result.second
                                if (result.result.first.localHasChanges() && commitments.remoteNextCommitInfo.left?.reSignAsap == true) {
                                    actions += listOf<ChannelAction>(ProcessCommand(CMD_SIGN))
                                }
                                Pair(nextState, actions)
                            }
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
                            !Helpers.Closing.isValidFinalScriptPubkey(event.message.scriptPubKey) -> {
                                handleLocalError(event, InvalidFinalScript(channelId))
                            }
                            commitments.remoteHasUnsignedOutgoingHtlcs() -> {
                                handleLocalError(event, CannotCloseWithUnsignedOutgoingHtlcs(channelId))
                            }
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
                                        val newState = this.copy(remoteShutdown = remoteShutdown, commitments = commitments.copy(remoteChannelData = event.message.channelData))
                                        Pair(newState, listOf(ProcessCommand(CMD_SIGN)))
                                    }
                                }
                            }
                            else -> {
                                // so we don't have any unsigned outgoing htlcs
                                var actions = listOf<ChannelAction>()
                                val localShutdown = this.localShutdown ?: Shutdown(channelId, commitments.localParams.defaultFinalScriptPubKey)
                                if (this.localShutdown == null) actions = actions + listOf(SendMessage(localShutdown))
                                val commitments1 = commitments.copy(remoteChannelData = event.message.channelData)

                                when {
                                    commitments1.hasNoPendingHtlcs() && commitments1.localParams.isFunder -> {
                                        val (closingTx, closingSigned) = Helpers.Closing.makeFirstClosingTx(
                                            keyManager,
                                            commitments1,
                                            localShutdown.scriptPubKey.toByteArray(),
                                            event.message.scriptPubKey.toByteArray(),
                                            currentOnchainFeerates.commitmentFeeratePerKw,
                                        )
                                        val nextState = Negotiating(
                                            staticParams,
                                            currentTip,
                                            currentOnchainFeerates,
                                            commitments1,
                                            localShutdown,
                                            event.message,
                                            listOf(listOf(ClosingTxProposed(closingTx.tx, closingSigned))),
                                            bestUnpublishedClosingTx = null
                                        )
                                        actions = actions + listOf(StoreState(nextState), SendMessage(closingSigned))
                                        Pair(nextState, actions)
                                    }
                                    commitments1.hasNoPendingHtlcs() -> {
                                        val nextState = Negotiating(staticParams, currentTip, currentOnchainFeerates, commitments1, localShutdown, event.message, closingTxProposed = listOf(listOf()), bestUnpublishedClosingTx = null)
                                        actions = actions + listOf(StoreState(nextState))
                                        Pair(nextState, actions)
                                    }
                                    else -> {
                                        // there are some pending signed htlcs, we need to fail/fulfill them
                                        val nextState = ShuttingDown(staticParams, currentTip, currentOnchainFeerates, commitments1, localShutdown, event.message)
                                        actions = listOf(StoreState(nextState)) + actions
                                        Pair(nextState, actions)
                                    }
                                }
                            }
                        }
                    }
                    else -> unhandled(event)
                }
            }
            is NewBlock -> {
                logger.info { "new tip ${event.height} ${event.Header}" }
                val newState = this.copy(currentTip = Pair(event.height, event.Header))
                Pair(newState, listOf())
            }
            is Disconnected -> Pair(Offline(this), listOf())
            else -> unhandled(event)
        }
    }

    override fun handleLocalError(event: ChannelEvent, t: Throwable): Pair<ChannelState, List<ChannelAction>> {
        logger.error(t) { "error on event $event in state ${this::class}" }
        val error = Error(channelId, t.message)
        // TODO: this is wrong, we need to publish and spend our local commit tx and transition to Closing state
        return Pair(Aborted(staticParams, currentTip, currentOnchainFeerates ), listOf(SendMessage(error)))
    }
}

@Serializable
data class ShuttingDown(
    override val staticParams: StaticParams,
    override val currentTip: Pair<Int, @Serializable(with = BlockHeaderKSerializer::class) BlockHeader>,
    override val currentOnchainFeerates: OnchainFeerates,
    override val commitments: Commitments,
    val localShutdown: Shutdown,
    val remoteShutdown: Shutdown
) : ChannelState(), HasCommitments {
    override fun updateCommitments(input: Commitments): HasCommitments = this.copy(commitments = input)

    override fun processInternal(event: ChannelEvent): Pair<ChannelState, List<ChannelAction>> {
        return when (event) {
            is MessageReceived -> {
                when (event.message) {
                    is UpdateFulfillHtlc -> {
                        when (val result = commitments.receiveFulfill(event.message)) {
                            is Try.Failure -> handleLocalError(event, result.error)
                            is Try.Success -> Pair(this.copy(commitments = result.result.first), listOf(ProcessFulfill(event.message)))
                        }
                    }
                    is UpdateFailHtlc -> {
                        when (val result = commitments.receiveFail(event.message)) {
                            is Try.Failure -> handleLocalError(event, result.error)
                            is Try.Success -> Pair(this.copy(commitments = result.result.first), listOf())
                        }
                    }
                    is CommitSig -> {
                        when (val result = commitments.receiveCommit(event.message, keyManager, logger)) {
                            is Try.Failure -> handleLocalError(event, result.error)
                            is Try.Success -> {
                                val (commitments1, revocation) = result.get()
                                when {
                                    commitments1.hasNoPendingHtlcs() && commitments1.localParams.isFunder -> {
                                        val (closingTx, closingSigned) = Helpers.Closing.makeFirstClosingTx(
                                            keyManager,
                                            commitments1,
                                            localShutdown.scriptPubKey.toByteArray(),
                                            remoteShutdown.scriptPubKey.toByteArray(),
                                            currentOnchainFeerates.mutualCloseFeeratePerKw
                                        )
                                        val nextState = Negotiating(
                                            staticParams,
                                            currentTip,
                                            currentOnchainFeerates,
                                            commitments1,
                                            localShutdown,
                                            remoteShutdown,
                                            listOf(listOf(ClosingTxProposed(closingTx.tx, closingSigned))),
                                            bestUnpublishedClosingTx = null
                                        )
                                        val actions = listOf(StoreState(nextState), SendMessage(revocation), SendMessage(closingSigned))
                                        Pair(nextState, actions)
                                    }
                                    commitments1.hasNoPendingHtlcs() -> {
                                        val nextState = Negotiating(staticParams, currentTip, currentOnchainFeerates, commitments1, localShutdown, remoteShutdown, closingTxProposed = listOf(listOf()), bestUnpublishedClosingTx = null)
                                        val actions = listOf(StoreState(nextState), SendMessage(revocation))
                                        Pair(nextState, actions)
                                    }
                                    else -> {
                                        val nextState = this.copy(commitments = commitments1)
                                        var actions = listOf(StoreState(nextState), SendMessage(revocation))
                                        if (commitments1.localHasChanges()) {
                                            // if we have newly acknowledged changes let's sign them
                                            actions = actions + listOf(ProcessCommand(CMD_SIGN))
                                        }
                                        Pair(nextState, actions)
                                    }
                                }
                            }
                        }
                    }
                    is RevokeAndAck -> {
                        when (val result = commitments.receiveRevocation(event.message)) {
                            is Try.Failure -> handleLocalError(event, result.error)
                            is Try.Success -> {
                                val (commitments1, actions) = result.get()
                                when {
                                    commitments1.hasNoPendingHtlcs() && commitments1.localParams.isFunder -> {
                                        val (closingTx, closingSigned) = Helpers.Closing.makeFirstClosingTx(
                                            keyManager,
                                            commitments1,
                                            localShutdown.scriptPubKey.toByteArray(),
                                            remoteShutdown.scriptPubKey.toByteArray(),
                                            currentOnchainFeerates.mutualCloseFeeratePerKw
                                        )
                                        val nextState = Negotiating(
                                            staticParams,
                                            currentTip,
                                            currentOnchainFeerates,
                                            commitments1,
                                            localShutdown,
                                            remoteShutdown,
                                            listOf(listOf(ClosingTxProposed(closingTx.tx, closingSigned))),
                                            bestUnpublishedClosingTx = null
                                        )
                                        val actions1 = actions + listOf(StoreState(nextState), SendMessage(closingSigned))
                                        Pair(nextState, actions1)
                                    }
                                    commitments1.hasNoPendingHtlcs() -> {
                                        val nextState = Negotiating(staticParams, currentTip, currentOnchainFeerates, commitments1, localShutdown, remoteShutdown, closingTxProposed = listOf(listOf()), bestUnpublishedClosingTx = null)
                                        val actions1 = actions + listOf(StoreState(nextState))
                                        Pair(nextState, actions1)
                                    }
                                    else -> {
                                        val nextState = this.copy(commitments = commitments1)
                                        var actions1 = actions + listOf(StoreState(nextState))
                                        if (commitments1.localHasChanges() && commitments1.remoteNextCommitInfo.isLeft && commitments1.remoteNextCommitInfo.left!!.reSignAsap) {
                                            actions1 = actions1 + listOf(ProcessCommand(CMD_SIGN))
                                        }
                                        Pair(nextState, actions1)
                                    }
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
            is ExecuteCommand -> {
                when {
                    event.command is CMD_ADD_HTLC -> {
                        logger.info { "rejecting htlc request in state=$this" }
                        val error = ChannelUnavailable(channelId)
                        // we don't provide a channel_update: this will be a permanent channel failure
                        handleCommandError(event.command, AddHtlcFailed(channelId, event.command.paymentHash, error, event.command.paymentId, null, event.command))
                    }
                    event.command is CMD_SIGN && !commitments.localHasChanges() -> {
                        logger.verbose { "ignoring CMD_SIGN (nothing to sign)" }
                        Pair(this, listOf())
                    }
                    event.command is CMD_SIGN && commitments.remoteNextCommitInfo.isLeft -> {
                        logger.verbose { "already in the process of signing, will sign again as soon as possible" }
                        Pair(this.copy(commitments = this.commitments.copy(remoteNextCommitInfo = Either.Left(this.commitments.remoteNextCommitInfo.left!!.copy(reSignAsap = true)))), listOf())
                    }
                    event.command is CMD_SIGN ->
                        when (val result = commitments.sendCommit(keyManager, logger)) {
                            is Try.Failure -> {
                                handleLocalError(event, result.error)
                            }
                            is Try.Success -> {
                                val commitments1 = result.result.first
                                val nextState = this.copy(commitments = commitments1)
                                val actions = listOf(StoreState(nextState), SendMessage(result.result.second))
                                Pair(nextState, actions)
                            }
                        }
                    event.command is CMD_FULFILL_HTLC -> {
                        when (val result = commitments.sendFulfill(event.command)) {
                            is Try.Failure -> handleLocalError(event, result.error)
                            is Try.Success -> {
                                var actions = listOf<ChannelAction>()
                                if (event.command.commit) {
                                    actions = actions + listOf(ProcessCommand(CMD_SIGN))
                                }
                                val commitments1 = result.get().first
                                val fulfill = result.get().second
                                Pair(this.updateCommitments(commitments1) as ShuttingDown, actions + listOf(SendMessage(fulfill)))
                            }
                        }
                    }
                    event.command is CMD_FAIL_HTLC -> {
                        when (val result = commitments.sendFail(event.command, staticParams.nodeParams.nodePrivateKey)) {
                            is Try.Failure -> handleLocalError(event, result.error)
                            is Try.Success -> {
                                var actions = listOf<ChannelAction>()
                                if (event.command.commit) {
                                    actions = actions + listOf(ProcessCommand(CMD_SIGN))
                                }
                                val commitments1 = result.get().first
                                val fail = result.get().second
                                Pair(this.copy(commitments = commitments1), actions + listOf(SendMessage(fail)))
                            }
                        }
                    }
                    event.command is CMD_CLOSE -> {
                        Pair(this, listOf(HandleCommandFailed(event.command, ClosingAlreadyInProgress(channelId))))
                    }
                    else -> unhandled(event)
                }
            }
            is NewBlock -> Pair(this.copy(currentTip = Pair(event.height, event.Header)), listOf())
            else -> unhandled(event)
        }
    }

    override fun handleLocalError(event: ChannelEvent, t: Throwable): Pair<ChannelState, List<ChannelAction>> {
        TODO("Not yet implemented")
    }
}

@Serializable
data class Negotiating(
    override val staticParams: StaticParams,
    override val currentTip: Pair<Int, @Serializable(with = BlockHeaderKSerializer::class) BlockHeader>,
    override val currentOnchainFeerates: OnchainFeerates,
    override val commitments: Commitments,
    val localShutdown: Shutdown,
    val remoteShutdown: Shutdown,
    val closingTxProposed: List<List<ClosingTxProposed>>, // one list for every negotiation (there can be several in case of disconnection)
    @Serializable(with = TransactionKSerializer::class) val bestUnpublishedClosingTx: Transaction?
) : ChannelState(), HasCommitments {
    override fun updateCommitments(input: Commitments): HasCommitments = this.copy(commitments = input)

    init {
        require(closingTxProposed.isNotEmpty()) { "there must always be a list for the current negotiation" }
        require(!commitments.localParams.isFunder || !closingTxProposed.any { it.isEmpty() }) { "funder must have at least one closing signature for every negotation attempt because it initiates the closing" }
    }

    override fun processInternal(event: ChannelEvent): Pair<ChannelState, List<ChannelAction>> {
        return when {
            event is MessageReceived && event.message is ClosingSigned -> {
                logger.info { "received closingFeeSatoshis=${event.message.feeSatoshis}" }
                val result = Helpers.Closing.checkClosingSignature(keyManager, commitments, localShutdown.scriptPubKey.toByteArray(), remoteShutdown.scriptPubKey.toByteArray(), event.message.feeSatoshis, event.message.signature)
                val lastLocalClosingFee = closingTxProposed.last().lastOrNull()?.localClosingSigned?.feeSatoshis
                when {
                    result is Try.Success && lastLocalClosingFee == event.message.feeSatoshis || closingTxProposed.flatten().size >= MAX_NEGOTIATION_ITERATIONS -> {
                        // we close when we converge or when there were too many iterations
                        val signedClosingTx = result.get()
                        logger.info { "closing tx published: closingTxId=${signedClosingTx.txid}" }
                        val nextState = Closing(
                            staticParams,
                            currentTip,
                            currentOnchainFeerates,
                            commitments,
                            null,
                            currentTimestampSeconds(),
                            this.closingTxProposed.flatten().map { it.unsignedTx },
                            listOf(signedClosingTx)
                        )
                        val actions = listOf(StoreState(nextState), PublishTx(signedClosingTx), SendWatch(WatchConfirmed(channelId, signedClosingTx, staticParams.nodeParams.minDepthBlocks.toLong(), BITCOIN_TX_CONFIRMED(signedClosingTx))))
                        Pair(nextState, actions)
                    }
                    result is Try.Success -> {
                        val signedClosingTx = result.get()
                        val nextClosingFee = Helpers.Closing.nextClosingFee(
                            lastLocalClosingFee ?: Helpers.Closing.firstClosingFee(
                                commitments,
                                localShutdown.scriptPubKey.toByteArray(),
                                remoteShutdown.scriptPubKey.toByteArray(),
                                currentOnchainFeerates.mutualCloseFeeratePerKw
                            ),
                            event.message.feeSatoshis
                        )
                        when {
                            lastLocalClosingFee == nextClosingFee -> {
                                // next computed fee is the same than the one we previously sent (probably because of rounding), let's close now
                                val nextState = Closing(
                                    staticParams,
                                    currentTip,
                                    currentOnchainFeerates,
                                    commitments,
                                    null,
                                    currentTimestampSeconds(),
                                    this.closingTxProposed.flatten().map { it.unsignedTx },
                                    listOf(signedClosingTx)
                                )
                                val actions =
                                    listOf(StoreState(nextState), PublishTx(signedClosingTx), SendWatch(WatchConfirmed(channelId, signedClosingTx, staticParams.nodeParams.minDepthBlocks.toLong(), BITCOIN_TX_CONFIRMED(signedClosingTx))))
                                logger.info { "closing tx published: closingTxId=${signedClosingTx.txid}" }
                                Pair(nextState, actions)
                            }
                            nextClosingFee == event.message.feeSatoshis -> {
                                // we have converged!
                                val (closingTx, closingSigned) = Helpers.Closing.makeClosingTx(keyManager, commitments, localShutdown.scriptPubKey.toByteArray(), remoteShutdown.scriptPubKey.toByteArray(), nextClosingFee)
                                val nextState = Closing(
                                    staticParams,
                                    currentTip,
                                    currentOnchainFeerates,
                                    commitments,
                                    null,
                                    currentTimestampSeconds(),
                                    this.closingTxProposed.flatten().map { it.unsignedTx } + listOf(closingTx.tx),
                                    listOf(closingTx.tx)
                                )
                                val actions = listOf(
                                    StoreState(nextState),
                                    PublishTx(closingTx.tx),
                                    SendWatch(WatchConfirmed(channelId, signedClosingTx, staticParams.nodeParams.minDepthBlocks.toLong(), BITCOIN_TX_CONFIRMED(signedClosingTx))),
                                    SendMessage(closingSigned)
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
                                val actions = listOf(StoreState(nextState), SendMessage(closingSigned))
                                Pair(nextState, actions)
                            }
                        }
                    }
                    else -> {
                        handleLocalError(event, (result as Try.Failure).error)
                    }
                }
            }
            event is WatchReceived && event.watch is WatchEventSpent && event.watch.event is BITCOIN_FUNDING_SPENT && closingTxProposed.flatten().map { it.unsignedTx.txid }.contains(event.watch.tx.txid) -> {
                // they can publish a closing tx with any sig we sent them, even if we are not done negotiating
                logger.info { "closing tx published: closingTxId=${event.watch.tx.txid}" }
                val nextState = Closing(
                    staticParams,
                    currentTip,
                    currentOnchainFeerates,
                    commitments,
                    null,
                    currentTimestampSeconds(),
                    this.closingTxProposed.flatten().map { it.unsignedTx },
                    listOf(event.watch.tx)
                )
                val actions = listOf(StoreState(nextState), PublishTx(event.watch.tx), SendWatch(WatchConfirmed(channelId, event.watch.tx, staticParams.nodeParams.minDepthBlocks.toLong(), BITCOIN_TX_CONFIRMED(event.watch.tx))))
                Pair(nextState, actions)
            }
            event is NewBlock -> Pair(this.copy(currentTip = Pair(event.height, event.Header)), listOf())
            else -> unhandled(event)
        }
    }

    override fun handleLocalError(event: ChannelEvent, t: Throwable): Pair<ChannelState, List<ChannelAction>> {
        logger.error(t) { "error on event $event in state ${this::class}" }
        if (commitments.nothingAtStake()) {
            return Pair(Aborted(staticParams, currentTip, currentOnchainFeerates), listOf())
        }
        if (bestUnpublishedClosingTx != null) {
            // if we were in the process of closing and already received a closing sig from the counterparty, it's always better to use that
            val nextState = Closing(
                staticParams,
                currentTip,
                currentOnchainFeerates,
                commitments,
                null,
                currentTimestampSeconds(),
                this.closingTxProposed.flatten().map { it.unsignedTx } + listOf(bestUnpublishedClosingTx),
                listOf(bestUnpublishedClosingTx)
            )
            val actions = listOf(
                StoreState(nextState),
                PublishTx(bestUnpublishedClosingTx),
                SendWatch(WatchConfirmed(channelId, bestUnpublishedClosingTx, staticParams.nodeParams.minDepthBlocks.toLong(), BITCOIN_TX_CONFIRMED(bestUnpublishedClosingTx)))
            )
            return Pair(nextState, actions)
        }
        // TODO: this is wrong, we need to publish and spend our local commit tx and transition to Closing state
        return Pair(this, listOf())
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
    override val currentOnchainFeerates: OnchainFeerates,
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

    private val spendingTxes: List<Transaction> by lazy {
        mutualClosePublished + revokedCommitPublished.map { it.commitTx } +
                listOfNotNull(
                    localCommitPublished?.commitTx,
                    remoteCommitPublished?.commitTx,
                    nextRemoteCommitPublished?.commitTx,
                    futureRemoteCommitPublished?.commitTx
                )
    }

    override fun updateCommitments(input: Commitments): HasCommitments = this.copy(commitments = input)

    override fun processInternal(event: ChannelEvent): Pair<ChannelState, List<ChannelAction>> {
        return when {
            event is WatchReceived && event.watch is WatchEventSpent && event.watch.event is BITCOIN_FUNDING_SPENT -> {
                when {
                    mutualClosePublished.contains(event.watch.tx) -> {
                        // we already know about this tx, probably because we have published it ourselves after successful negotiation
                        Pair(this, listOf())
                    }
                    mutualCloseProposed.contains(event.watch.tx) -> {
                        // at any time they can publish a closing tx with any sig we sent them
                        val nextState = this.copy(mutualClosePublished = this.mutualClosePublished + listOf(event.watch.tx))
                        val actions = listOf(StoreState(nextState), PublishTx(event.watch.tx))
                        Pair(nextState, actions)
                    }
                    localCommitPublished?.commitTx == event.watch.tx || remoteCommitPublished?.commitTx == event.watch.tx || nextRemoteCommitPublished?.commitTx == event.watch.tx || futureRemoteCommitPublished?.commitTx == event.watch.tx -> {
                        // this is because WatchSpent watches never expire and we are notified multiple times
                        Pair(this, listOf())
                    }
                    else -> {
                        TODO("handle this: remote node published their commit tx or a revoked commit tx")
                    }
                }
            }
            event is WatchReceived && event.watch is WatchEventSpent && event.watch.event is BITCOIN_OUTPUT_SPENT -> {
                // one of the outputs of the local/remote/revoked commit was spent
                // we just put a watch to be notified when it is confirmed
                val actions = listOf(SendWatch(WatchConfirmed(channelId, event.watch.tx, staticParams.nodeParams.minDepthBlocks.toLong(), BITCOIN_TX_CONFIRMED(event.watch.tx))))
                logger.info { "processing BITCOIN_OUTPUT_SPENT with txid=${event.watch.tx.txid} tx=${event.watch.tx}" }
                // TODO: claim revoked HTLC outputs (if any)
                Pair(this, actions)
            }
            event is WatchReceived && event.watch is WatchEventConfirmed && event.watch.event is BITCOIN_TX_CONFIRMED -> {
                logger.info { "txid=${event.watch.tx.txid} has reached mindepth, updating closing state" }
                when (val closingType = isClosed(event.watch.tx)) {
                    null -> {
                        val nextState = this.copy(
                            localCommitPublished = this.localCommitPublished?.update(event.watch.tx),
                            remoteCommitPublished = this.remoteCommitPublished?.update(event.watch.tx),
                            nextRemoteCommitPublished = this.nextRemoteCommitPublished?.update(event.watch.tx),
                            futureRemoteCommitPublished = this.futureRemoteCommitPublished?.update(event.watch.tx),
                            revokedCommitPublished = this.revokedCommitPublished.map { it.update(event.watch.tx) }
                        )
                        Pair(nextState, listOf(StoreState(nextState)))
                    }
                    else -> {
                        logger.info { "channel $channelId is now closed" }
                        if (closingType !is MutualClose) {
                            logger.info { "last known remoteChannelData=${commitments.remoteChannelData}" }
                        }
                        val nextState = Closed(this)
                        Pair(nextState, listOf(StoreState(nextState)))
                    }
                }
            }
            event is MessageReceived && event.message is ChannelReestablish -> {
                // they haven't detected that we were closing and are trying to reestablish a connection
                // we give them one of the published txes as a hint
                // note spendingTx != Nil (that's a requirement of DATA_CLOSING)
                val exc = FundingTxSpent(channelId, spendingTxes.first())
                val error = Error(channelId, exc.message)
                Pair(this, listOf(SendMessage(error)))
            }
            event is MessageReceived && event.message is Error -> {
                logger.error { "peer send error: ascii=${event.message.toAscii()} bin=${event.message.data.toHex()}" }
                // nothing to do, there is already a spending tx published
                Pair(this, listOf())
            }
            event is ExecuteCommand && event.command is CMD_CLOSE -> handleCommandError(event.command, ClosingAlreadyInProgress(channelId))
            event is ExecuteCommand && event.command is CMD_ADD_HTLC -> {
                logger.info { "rejecting htlc request in state=$this" }
                val error = ChannelUnavailable(channelId)
                // we don't provide a channel_update: this will be a permanent channel failure
                handleCommandError(event.command, AddHtlcFailed(channelId, event.command.paymentHash, error, Origin.Local(event.command.id), null, event.command))
            }
            event is ExecuteCommand && event.command is CMD_FULFILL_HTLC -> {
                when(val result = commitments.sendFulfill(event.command)) {
                    is Try.Success -> {
                        logger.info {"got valid payment preimage, recalculating transactions to redeem the corresponding htlc on-chain" }
                        // TODO: update and republish HTLC transactions
                        val nextState = this.copy(commitments = result.get().first)
                        Pair(nextState, listOf(StoreState(nextState)))
                    }
                    is Try.Failure -> {
                        handleCommandError(event.command, result.error)
                    }
                }
            }
            event is NewBlock -> Pair(this.copy(currentTip = Pair(event.height, event.Header)), listOf())
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
}

/**
 * Channel is closed i.t its funding tx has been spent and the spending transactions have been confirmed, it can be forgotten
 */
@Serializable
data class Closed(val state: Closing) : ChannelState(), HasCommitments {
    override val staticParams: StaticParams
        get() = state.staticParams
    override val currentTip: Pair<Int, BlockHeader>
        get() = state.currentTip
    override val currentOnchainFeerates: OnchainFeerates
        get() = state.currentOnchainFeerates
    override val commitments: Commitments
        get() = state.commitments

    override fun updateCommitments(input: Commitments): HasCommitments {
        return this.copy(state.updateCommitments(input) as Closing)
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
data class Aborted(override val staticParams: StaticParams, override val currentTip: Pair<Int, @Serializable(with = BlockHeaderKSerializer::class) BlockHeader>, override val currentOnchainFeerates: OnchainFeerates) : ChannelState() {
    override fun processInternal(event: ChannelEvent): Pair<ChannelState, List<ChannelAction>> {
        return Pair(this, listOf())
    }

    override fun handleLocalError(event: ChannelEvent, t: Throwable): Pair<ChannelState, List<ChannelAction>> {
        return Pair(this, listOf())
    }
}

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

    fun publishActions(tx: Transaction, channelId: ByteVector32, minDepth: Long): List<ChannelAction> = listOf(
        PublishTx(tx),
        SendWatch(WatchConfirmed(channelId, tx, minDepth, BITCOIN_TX_CONFIRMED(tx)))
    )
}