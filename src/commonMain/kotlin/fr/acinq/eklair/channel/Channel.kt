package fr.acinq.eklair.channel

import fr.acinq.bitcoin.*
import fr.acinq.eklair.*
import fr.acinq.eklair.blockchain.*
import fr.acinq.eklair.channel.Channel.ANNOUNCEMENTS_MINCONF
import fr.acinq.eklair.channel.Channel.handleSync
import fr.acinq.eklair.channel.ChannelVersion.Companion.USE_STATIC_REMOTEKEY_BIT
import fr.acinq.eklair.crypto.KeyManager
import fr.acinq.eklair.crypto.ShaChain
import fr.acinq.eklair.io.*
import fr.acinq.eklair.router.Announcements
import fr.acinq.eklair.transactions.CommitmentSpec
import fr.acinq.eklair.transactions.Scripts
import fr.acinq.eklair.transactions.Transactions
import fr.acinq.eklair.utils.*
import fr.acinq.eklair.wire.*
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import org.kodein.log.Logger
import org.kodein.log.LoggerFactory

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

    /**
     * @param event input event (for example, a message was received, a command was sent by the GUI/API, ...
     * @return a (new state, list of actions) pair
     */
    abstract fun process(event: ChannelEvent): Pair<ChannelState, List<ChannelAction>>

    @Transient
    val logger = LoggerFactory.default.newLogger(Logger.Tag(Channel::class))
}

interface HasCommitments {
    val commitments: Commitments
    val channelId: ByteVector32
        get() = commitments.channelId
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
                Pair(Offline(event.state), actions)
            }
            event is NewBlock -> Pair(this.copy(currentTip = Pair(event.height, event.Header)), listOf())
            else -> {
                logger.warning { "unhandled event $event ins state ${this::class}" }
                Pair(this, listOf())
            }
        }
    }
}

/**
 * this class is not serializable because it is not meant to be persisted
 */
data class Offline(val state: ChannelState) : ChannelState() {
    override val staticParams: StaticParams
        get() = state.staticParams
    override val currentTip: Pair<Int, BlockHeader>
        get() = state.currentTip

    override fun process(event: ChannelEvent): Pair<ChannelState, List<ChannelAction>> {
        return when {
            event is Connected && state is HasCommitments -> {
                val yourLastPerCommitmentSecret = state.commitments.remotePerCommitmentSecrets.lastIndex?.let { state.commitments.remotePerCommitmentSecrets.getHash(it) } ?: ByteVector32.Zeroes
                val channelKeyPath = keyManager.channelKeyPath(state.commitments.localParams, state.commitments.channelVersion)
                val myCurrentPerCommitmentPoint = keyManager.commitmentPoint(channelKeyPath, state.commitments.localCommit.index)

                val channelReestablish = ChannelReestablish(
                    channelId = state.channelId,
                    nextLocalCommitmentNumber = state.commitments.localCommit.index + 1,
                    nextRemoteRevocationNumber = state.commitments.remoteCommit.index,
                    yourLastCommitmentSecret = PrivateKey(yourLastPerCommitmentSecret),
                    myCurrentPerCommitmentPoint = myCurrentPerCommitmentPoint
                )

                // TODO: update local/remote connection-local global/local features
                Pair(Syncing(state), listOf(SendMessage(channelReestablish)))
            }
            event is NewBlock -> {
                // TODO: is this the right thing to do ?
                val (newState, actions) = state.process(event)
                Pair(newState, listOf())
            }
            else -> {
                logger.warning { "unhandled event $event ins state ${this::class}" }
                Pair(this, listOf())
            }
        }
    }
}

/**
 * this class is not serializable because it is not meant to be persisted
 */
data class Syncing(val state: ChannelState) : ChannelState() {
    override val staticParams: StaticParams
        get() = state.staticParams
    override val currentTip: Pair<Int, BlockHeader>
        get() = state.currentTip

    override fun process(event: ChannelEvent): Pair<ChannelState, List<ChannelAction>> {
        return when {
            event is MessageReceived && event.message is ChannelReestablish ->
                when {
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
                                val nextState = WaitForRemotePublishFutureComitment(staticParams, state.currentTip, state.commitments, event.message)
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
                                Pair(state.copy(commitments = commitments1), actions)
                            }
                        }
                    }
                    else -> {
                        logger.warning { "unhandled event $event ins state ${this::class}" }
                        Pair(this, listOf())
                    }
                }
            event is NewBlock -> {
                // TODO: is this the right thing to do ?
                val (newState, actions) = state.process(event)
                Pair(Syncing(newState), listOf())
            }
            else -> {
                logger.warning { "unhandled event $event ins state ${this::class}" }
                Pair(this, listOf())
            }
        }
    }
}

data class WaitForRemotePublishFutureComitment(
    override val staticParams: StaticParams,
    override val currentTip: Pair<Int, @Serializable(with = BlockHeaderKSerializer::class) BlockHeader>,
    override val commitments: Commitments,
    val channelReestablish: ChannelReestablish
) : ChannelState(), HasCommitments {

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
                        var channelVersion = Helpers.getChannelVersion(event.message)
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
                    else -> {
                        logger.warning { "unhandled event $event ins state ${this::class}" }
                        Pair(this, listOf())
                    }
                }
            is NewBlock -> Pair(this.copy(currentTip = Pair(event.height, event.Header)), listOf())
            else -> {
                logger.warning { "unhandled event $event ins state ${this::class}" }
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
                        val result = Transactions.checkSpendable(signedLocalCommitTx)
                        if (result.isFailure) {
                            // TODO: implement error handling
                            logger.error { "their first commit sig is not valid for ${firstCommitTx.localCommitTx.tx}" }
                            Pair(this, listOf())
                        } else {
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
                            val fundingMinDepth =
                                if (commitments.channelVersion.isSet(ChannelVersion.ZERO_RESERVE_BIT)) 0 else Helpers.minDepthForFunding(
                                    staticParams.nodeParams,
                                    fundingAmount
                                )
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
                            Pair(nextState, actions)
                        }
                    }
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
                val result = fr.acinq.eklair.utils.runTrying {
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

    override fun process(event: ChannelEvent): Pair<ChannelState, List<ChannelAction>> {
        return when (event) {
            is MessageReceived ->
                when (event.message) {
                    is FundingLocked -> Pair(this.copy(deferred = event.message), listOf())
                    else -> Pair(this, listOf())
                }
            is WatchReceived ->
                when (event.watch) {
                    is WatchEventConfirmed -> {
                        val result = fr.acinq.eklair.utils.runTrying {
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
                        val blockHeight = event.watch.blockHeight
                        val txIndex = event.watch.txIndex
                        val shortChannelId = ShortChannelId(blockHeight, txIndex, commitments.commitInput.outPoint.index.toInt())
                        val nextState = WaitForFundingLocked(staticParams, currentTip, commitments, shortChannelId, fundingLocked)
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
                    else -> Pair(this, listOf())
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
                            staticParams.nodeParams.privateKey,
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
    override fun process(event: ChannelEvent): Pair<ChannelState, List<ChannelAction>> {
        return when (event) {
            is ExecuteCommand -> {
                when (event.command) {
                    is CMD_ADD_HTLC -> {
                        // TODO: handle shutdown in progress
                        when (val result = commitments.sendAdd(event.command, Helpers.origin(event.command), currentBlockHeight.toLong())) {
                            is Try.Failure -> {
                                Pair(this, listOf(HandleError(result.error)))
                            }
                            is Try.Success -> {
                                val newState = this.copy(commitments = result.result.first)
                                var actions = listOf<ChannelAction>(SendMessage(result.result.second))
                                if (event.command.commit) {
                                    actions += listOf<ChannelAction>(ProcessCommand(CMD_SIGN))
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
                                    actions += listOf<ChannelAction>(ProcessCommand(CMD_SIGN))
                                }
                                Pair(newState, actions)
                            }
                        }
                    }
                    is CMD_FAIL_HTLC -> {
                        when (val result = commitments.sendFail(event.command, staticParams.nodeParams.privateKey)) {
                            is Try.Failure -> {
                                Pair(this, listOf(HandleError(result.error)))
                            }
                            is Try.Success -> {
                                val newState = this.copy(commitments = result.result.first)
                                var actions = listOf<ChannelAction>(SendMessage(result.result.second))
                                if (event.command.commit) {
                                    actions += listOf<ChannelAction>(ProcessCommand(CMD_SIGN))
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
                                        val newState = this.copy(commitments = result.result.first)
                                        val actions = listOf(StoreHtlcInfos(htlcInfos), StoreState(newState), SendMessage(result.result.second))
                                        Pair(newState, actions)
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
                        when (val result = commitments.receiveAdd(event.message)) {
                            is Try.Failure -> Pair(this, listOf(HandleError(result.error)))
                            is Try.Success -> Pair(this.copy(commitments = result.result), listOf())
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
                                var actions = listOf<ChannelAction>(SendMessage(result.result.second))
                                if (result.result.first.localHasChanges()) {
                                    actions += listOf<ChannelAction>(ProcessCommand(CMD_SIGN))
                                }
                                Pair(this.copy(commitments = result.result.first), actions)
                            }
                        }
                    }
                    is RevokeAndAck -> {
                        when (val result = commitments.receiveRevocation(event.message)) {
                            is Try.Failure -> Pair(this, listOf(HandleError(result.error))) // TODO: handle invalid sig!!
                            is Try.Success -> {
                                // TODO: handle shutdown
                                val newState = this.copy(commitments = result.result.first)
                                var actions = listOf<ChannelAction>(StoreState(newState)) + result.result.second
                                if (result.result.first.localHasChanges() && commitments.remoteNextCommitInfo.left?.reSignAsap == true) {
                                    actions += listOf<ChannelAction>(ProcessCommand(CMD_SIGN))
                                }
                                Pair(newState, actions)
                            }
                        }
                    }
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
            else -> {
                logger.warning { "unhandled event $event in state ${this::class}" }
                Pair(this, listOf())
            }
        }
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
            remoteNextHtlcId = d.commitments.remoteNextHtlcId - d.commitments.remoteChanges.proposed.filterIsInstance<UpdateAddHtlc>().size)
        log.verbose { "localNextHtlcId=${d.commitments.localNextHtlcId}->${commitments1.localNextHtlcId}" }
        log.verbose { "remoteNextHtlcId=${d.commitments.remoteNextHtlcId}->${commitments1.remoteNextHtlcId}" }

        fun resendRevocation(): Unit {
            // let's see the state of remote sigs
            if (commitments1.localCommit.index == channelReestablish.nextRemoteRevocationNumber) {
                // nothing to do
            } else if (commitments1.localCommit.index == channelReestablish.nextRemoteRevocationNumber + 1) {
                // our last revocation got lost, let's resend it
                log.verbose {  "re-sending last revocation" }
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