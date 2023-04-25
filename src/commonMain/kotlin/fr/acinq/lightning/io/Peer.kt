package fr.acinq.lightning.io

import fr.acinq.bitcoin.*
import fr.acinq.lightning.*
import fr.acinq.lightning.Lightning.randomKeyPath
import fr.acinq.lightning.blockchain.WatchEvent
import fr.acinq.lightning.blockchain.electrum.*
import fr.acinq.lightning.blockchain.fee.FeeratePerByte
import fr.acinq.lightning.blockchain.fee.FeeratePerKw
import fr.acinq.lightning.blockchain.fee.OnChainFeerates
import fr.acinq.lightning.channel.*
import fr.acinq.lightning.crypto.noise.*
import fr.acinq.lightning.db.Databases
import fr.acinq.lightning.db.IncomingPayment
import fr.acinq.lightning.db.LightningOutgoingPayment
import fr.acinq.lightning.db.OutgoingPayment
import fr.acinq.lightning.payment.IncomingPaymentHandler
import fr.acinq.lightning.payment.OutgoingPaymentFailure
import fr.acinq.lightning.payment.OutgoingPaymentHandler
import fr.acinq.lightning.payment.PaymentRequest
import fr.acinq.lightning.serialization.Encryption.from
import fr.acinq.lightning.transactions.Transactions
import fr.acinq.lightning.utils.*
import fr.acinq.lightning.wire.*
import fr.acinq.lightning.wire.Ping
import fr.acinq.secp256k1.Hex
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.Channel.Factory.BUFFERED
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.*
import org.kodein.log.newLogger
import kotlin.time.Duration.Companion.seconds

sealed class PeerCommand

/**
 * Try to open a channel, consuming all the spendable utxos in the wallet state provided.
 * @param maxFeeBasisPoints the max total acceptable fee (all included: service fee and mining fee)
 * @param maxFeeFloor as long as fee is below this amount, it's okay (whatever the percentage is)
 */
data class RequestChannelOpen(val requestId: ByteVector32, val wallet: WalletState, val maxFeeBasisPoints: Int, val maxFeeFloor: Satoshi) : PeerCommand() {
    /** maximum fee that we are willing to pay for this channel */
    val maxFee = (wallet.confirmedBalance * maxFeeBasisPoints / 10_000).max(maxFeeFloor)
}

/** Open a channel, consuming all the spendable utxos in the wallet state provided. */
data class OpenChannel(
    val fundingAmount: Satoshi,
    val pushAmount: MilliSatoshi,
    val wallet: WalletState,
    val commitTxFeerate: FeeratePerKw,
    val fundingTxFeerate: FeeratePerKw,
    val channelFlags: Byte,
    val channelType: ChannelType.SupportedChannelType
) : PeerCommand()

data class BytesReceived(val data: ByteArray) : PeerCommand()
data class WatchReceived(val watch: WatchEvent) : PeerCommand()
data class WrappedChannelCommand(val channelId: ByteVector32, val channelCommand: ChannelCommand) : PeerCommand()
object Disconnected : PeerCommand()

sealed class PaymentCommand : PeerCommand()
data class ReceivePayment(val paymentPreimage: ByteVector32, val amount: MilliSatoshi?, val description: Either<String, ByteVector32>, val expirySeconds: Long? = null, val result: CompletableDeferred<PaymentRequest>) : PaymentCommand()
private object CheckPaymentsTimeout : PaymentCommand()
data class PayToOpenResponseCommand(val payToOpenResponse: PayToOpenResponse) : PeerCommand()
data class SendPayment(val paymentId: UUID, val amount: MilliSatoshi, val recipient: PublicKey, val paymentRequest: PaymentRequest, val trampolineFeesOverride: List<TrampolineFees>? = null) : PaymentCommand() {
    val paymentHash: ByteVector32 = paymentRequest.paymentHash
}
data class PurgeExpiredPayments(val fromCreatedAt: Long, val toCreatedAt: Long) : PaymentCommand()

sealed class PeerEvent
data class PaymentRequestGenerated(val receivePayment: ReceivePayment, val request: String) : PeerEvent()
data class PaymentReceived(val incomingPayment: IncomingPayment, val received: IncomingPayment.Received) : PeerEvent()
data class PaymentProgress(val request: SendPayment, val fees: MilliSatoshi) : PeerEvent()
data class PaymentNotSent(val request: SendPayment, val reason: OutgoingPaymentFailure) : PeerEvent()
data class PaymentSent(val request: SendPayment, val payment: OutgoingPayment) : PeerEvent()
data class ChannelClosing(val channelId: ByteVector32) : PeerEvent()

/**
 * Useful to handle transparent migration on Phoenix Android between eclair-core and lightning-kmp.
 */
data class PhoenixAndroidLegacyInfoEvent(val info: PhoenixAndroidLegacyInfo) : PeerEvent()

/**
 * The peer we establish a connection to. This object contains the TCP socket, a flow of the channels with that peer, and watches
 * the events on those channels and processes the relevant actions. The dialogue with the peer is done in coroutines.
 *
 * @param nodeParams Low level, Lightning related parameters that our node will use in relation to this Peer.
 * @param walletParams High level parameters for our node. It especially contains the Peer's [NodeUri].
 * @param watcher Watches events from the Electrum client and publishes transactions and events.
 * @param db Wraps the various databases persisting the channels and payments data related to the Peer.
 * @param socketBuilder Builds the TCP socket used to connect to the Peer.
 * @param initTlvStream Optional stream of TLV for the [Init] message we send to this Peer after connection. Empty by default.
 */
@OptIn(ExperimentalStdlibApi::class)
class Peer(
    val nodeParams: NodeParams,
    val walletParams: WalletParams,
    val watcher: ElectrumWatcher,
    val db: Databases,
    socketBuilder: TcpSocket.Builder?,
    scope: CoroutineScope,
    private val initTlvStream: TlvStream<InitTlv> = TlvStream.empty()
) : CoroutineScope by scope {
    companion object {
        private const val prefix: Byte = 0x00
        private val prologue = "lightning".encodeToByteArray()

        /** Account number for the final wallet derivation path. */
        val finalWalletAccount = 0L
        /** Account number for the swap-in wallet derivation path. */
        val swapInWalletAccount = 1L
    }

    var socketBuilder: TcpSocket.Builder? = socketBuilder
        set(value) {
            logger.debug { "swap socket builder=$value" }
            field = value
        }

    val remoteNodeId: PublicKey = walletParams.trampolineNode.id

    private val input = Channel<PeerCommand>(BUFFERED)
    private var output = Channel<ByteArray>(BUFFERED)
    val outputLightningMessages: ReceiveChannel<ByteArray> get() = output

    private val logger = MDCLogger(nodeParams.loggerFactory.newLogger(this::class), staticMdc = mapOf("remoteNodeId" to remoteNodeId))

    // The channels map, as initially loaded from the database at "boot" (on Peer.init).
    // As the channelsFlow is unavailable until the electrum connection is up-and-running,
    // this may provide useful information for the UI.
    private val _bootChannelsFlow = MutableStateFlow<Map<ByteVector32, ChannelState>?>(null)
    val bootChannelsFlow: StateFlow<Map<ByteVector32, ChannelState>?> get() = _bootChannelsFlow

    // channels map, indexed by channel id
    // note that a channel starts with a temporary id then switches to its final id once accepted
    private val _channelsFlow = MutableStateFlow<Map<ByteVector32, ChannelState>>(HashMap())
    val channelsFlow: StateFlow<Map<ByteVector32, ChannelState>> get() = _channelsFlow

    private var _channels by _channelsFlow
    val channels: Map<ByteVector32, ChannelState> get() = _channelsFlow.value

    // pending requests asking our peer to open a channel to us
    private var channelRequests: Map<ByteVector32, RequestChannelOpen> = HashMap()

    private val _connectionState = MutableStateFlow<Connection>(Connection.CLOSED(null))
    val connectionState: StateFlow<Connection> get() = _connectionState

    private val _eventsFlow = MutableSharedFlow<PeerEvent>(replay = 0, extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.SUSPEND)
    val eventsFlow: SharedFlow<PeerEvent> get() = _eventsFlow.asSharedFlow()

    // encapsulates logic for validating incoming payments
    private val incomingPaymentHandler = IncomingPaymentHandler(nodeParams, walletParams, db.payments)

    // encapsulates logic for sending payments
    private val outgoingPaymentHandler = OutgoingPaymentHandler(nodeParams, walletParams, db.payments)

    private val features = nodeParams.features

    private val ourInit = Init(features.initFeatures().toByteArray().toByteVector(), initTlvStream)
    private var theirInit: Init? = null

    val currentTipFlow = MutableStateFlow<Pair<Int, BlockHeader>?>(null)
    val onChainFeeratesFlow = MutableStateFlow<OnChainFeerates?>(null)

    private val _channelLogger = nodeParams.loggerFactory.newLogger(ChannelState::class)
    private suspend fun ChannelState.process(cmd: ChannelCommand): Pair<ChannelState, List<ChannelAction>> {
        val state = this
        val ctx = ChannelContext(
            StaticParams(nodeParams, remoteNodeId),
            currentTipFlow.filterNotNull().first().first,
            onChainFeeratesFlow.filterNotNull().first(),
            logger = MDCLogger(
                logger = _channelLogger,
                staticMdc = mapOf("remoteNodeId" to remoteNodeId) + state.mdc()
            )
        )
        return state.run { ctx.process(cmd) }
            .also { (state1, _) ->
                if (state1::class != state::class) {
                    ctx.logger.info { "${state::class.simpleName} -> ${state1::class.simpleName}" }
                }
            }
    }

    val finalWallet = ElectrumMiniWallet(nodeParams.chainHash, watcher.client, scope, nodeParams.loggerFactory, name = "final")
    val finalAddress: String = nodeParams.keyManager.bip84Address(account = Peer.finalWalletAccount, addressIndex = 0L).also { finalWallet.addAddress(it) }

    val swapInWallet = ElectrumMiniWallet(nodeParams.chainHash, watcher.client, scope, nodeParams.loggerFactory, name = "swap-in")
    val swapInAddress: String = nodeParams.keyManager.bip84Address(account = Peer.swapInWalletAccount, addressIndex = 0L).also { swapInWallet.addAddress(it) }

    init {
        launch {
            watcher.client.notifications.filterIsInstance<HeaderSubscriptionResponse>()
                .collect { msg ->
                    currentTipFlow.value = msg.blockHeight to msg.header
                }
        }
        launch {
            watcher.client.connectionState.filter { it == Connection.ESTABLISHED }.collect {
                // onchain fees are retrieved punctually, when electrum status moves to Connection.ESTABLISHED
                // since the application is not running most of the time, and when it is, it will be only for a few minutes, this is good enough.
                // (for a node that is online most of the time things would be different and we would need to re-evaluate onchain fee estimates on a regular basis)
                updateEstimateFees()
            }
        }
        launch {
            watcher.openWatchNotificationsFlow().collect {
                logger.debug { "notification: $it" }
                input.send(WrappedChannelCommand(it.channelId, ChannelCommand.WatchReceived(it)))
            }
        }
        launch {
            finalWallet.walletStateFlow
                .distinctUntilChangedBy { Pair(it.unconfirmedBalance, it.confirmedBalance) }
                .collect { wallet ->
                    logger.info { "${wallet.totalBalance} available on final wallet (${wallet.unconfirmedBalance} unconfirmed)" }
                }
        }
        launch {
            swapInWallet.walletStateFlow
                .filter { it.consistent }
                .fold(emptySet<WalletState.Utxo>()) { reservedUtxos, wallet ->
                    // reservedUtxos are part of a previously issued RequestChannelOpen command
                    val availableWallet = wallet.minus(reservedUtxos)
                    val balance = availableWallet.confirmedBalance
                    logger.info { "swap-in wallet balance: $balance, ${availableWallet.unconfirmedBalance} unconfirmed" }
                    if (balance >= 10_000.sat) {
                        logger.info { "swap-in wallet: requesting channel using confirmed balance: $balance" }
                        input.send(RequestChannelOpen(Lightning.randomBytes32(), availableWallet, maxFeeBasisPoints = 100, maxFeeFloor = 3_000.sat)) // 100 bips = 1 %
                        reservedUtxos.union(availableWallet.confirmedUtxos)
                    } else {
                        reservedUtxos
                    }.intersect(wallet.utxos) // drop utxos no longer in wallet
                }
        }
        launch {
            // we don't restore closed channels
            val bootChannels = db.channels.listLocalChannels().filterNot { it is Closed }
            _bootChannelsFlow.value = bootChannels.map { it.channelId to it }.toMap()
            val channelIds = bootChannels.map {
                logger.info { "restoring channel ${it.channelId} from local storage" }
                val state = WaitForInit
                val (state1, actions) = state.process(ChannelCommand.Restore(it))
                processActions(it.channelId, actions)
                _channels = _channels + (it.channelId to state1)
                it.channelId
            }
            logger.info { "restored ${channelIds.size} channels" }
            launch {
                // If we have some htlcs that have timed out, we may need to close channels to ensure we don't lose funds.
                // But maybe we were offline for too long and it is why our peer couldn't settle these htlcs in time.
                // We give them a bit of time after we reconnect to send us their latest htlc updates.
                delay(timeMillis = nodeParams.checkHtlcTimeoutAfterStartupDelaySeconds.toLong() * 1000)
                logger.info { "checking for timed out htlcs for channels: ${channelIds.joinToString(", ")}" }
                channelIds.forEach { input.send(WrappedChannelCommand(it, ChannelCommand.CheckHtlcTimeout)) }
            }
            run()
        }
        launch {
            var previousState = connectionState.value
            connectionState.filter { it != previousState }.collect {
                logger.info { "connection state changed: ${it::class.simpleName}" }
                if (it is Connection.CLOSED) input.send(Disconnected)
                previousState = it
            }
        }

    }

    @OptIn(FlowPreview::class)
    private suspend fun updateEstimateFees() {
        watcher.client.connectionState.filter { it == Connection.ESTABLISHED }.first()
        val sortedFees = listOf(
            watcher.client.estimateFees(2),
            watcher.client.estimateFees(6),
            watcher.client.estimateFees(18),
            watcher.client.estimateFees(144),
        )
        logger.info { "on-chain fees: $sortedFees" }
        // TODO: If some feerates are null, we may implement a retry
        onChainFeeratesFlow.value = OnChainFeerates(
            fundingFeerate = sortedFees[3].feerate ?: FeeratePerKw(FeeratePerByte(2.sat)),
            mutualCloseFeerate = sortedFees[2].feerate ?: FeeratePerKw(FeeratePerByte(10.sat)),
            claimMainFeerate = sortedFees[1].feerate ?: FeeratePerKw(FeeratePerByte(20.sat)),
            fastFeerate = sortedFees[0].feerate ?: FeeratePerKw(FeeratePerByte(50.sat))
        )
    }

    fun connect() {
        if (connectionState.value is Connection.CLOSED) establishConnection()
        else logger.warning { "Peer is already connecting / connected" }
    }

    fun disconnect() {
        if (this::socket.isInitialized) socket.close()
        _connectionState.value = Connection.CLOSED(null)
        output.close()
    }

    // Warning : lateinit vars have to be used AFTER their init to avoid any crashes
    //
    // This shouldn't be used outside the establishedConnection() function
    // Except from the disconnect() one that check if the lateinit var has been initialized
    private lateinit var socket: TcpSocket
    private fun establishConnection() = launch {
        logger.info { "connecting to ${walletParams.trampolineNode.host}" }
        _connectionState.value = Connection.ESTABLISHING
        socket = try {
            socketBuilder?.connect(
                host = walletParams.trampolineNode.host,
                port = walletParams.trampolineNode.port,
                tls = TcpSocket.TLS.DISABLED,
                loggerFactory = nodeParams.loggerFactory
            ) ?: error("socket builder is null.")
        } catch (ex: Throwable) {
            logger.warning { "TCP connect: ${ex.message}" }
            val ioException = when (ex) {
                is TcpSocket.IOException -> ex
                else -> TcpSocket.IOException.ConnectionRefused(ex)
            }
            _connectionState.value = Connection.CLOSED(ioException)
            return@launch
        }

        fun closeSocket(ex: TcpSocket.IOException?) {
            if (_connectionState.value is Connection.CLOSED) return
            logger.warning { "closing TCP socket." }
            socket.close()
            _connectionState.value = Connection.CLOSED(ex)
            cancel()
        }

        val priv = nodeParams.nodePrivateKey
        val pub = priv.publicKey()
        val keyPair = Pair(pub.value.toByteArray(), priv.value.toByteArray())
        val (enc, dec, ck) = try {
            handshake(
                keyPair,
                remoteNodeId.value.toByteArray(),
                { s -> socket.receiveFully(s) },
                { b -> socket.send(b) }
            )
        } catch (ex: TcpSocket.IOException) {
            logger.warning { "TCP handshake: ${ex.message}" }
            closeSocket(ex)
            return@launch
        }
        val session = LightningSession(enc, dec, ck)

        suspend fun send(message: ByteArray) {
            try {
                session.send(message) { data, flush -> socket.send(data, flush) }
            } catch (ex: TcpSocket.IOException) {
                logger.warning { "TCP send: ${ex.message}" }
                closeSocket(ex)
            }
        }

        logger.info { "sending init $ourInit" }
        send(LightningMessage.encode(ourInit))

        suspend fun doPing() {
            val ping = Ping(10, ByteVector("deadbeef"))
            while (isActive) {
                delay(30.seconds)
                sendToPeer(ping)
            }
        }

        suspend fun checkPaymentsTimeout() {
            while (isActive) {
                delay(10.seconds) // we schedule a check every 10 seconds
                input.send(CheckPaymentsTimeout)
            }
        }

        suspend fun listen() {
            try {
                while (isActive) {
                    val received = session.receive { size -> socket.receiveFully(size) }
                    input.send(BytesReceived(received))
                }
                closeSocket(null)
            } catch (ex: TcpSocket.IOException) {
                logger.warning { "TCP receive: ${ex.message}" }
                closeSocket(ex)
            }
        }

        suspend fun respond() {
            // Reset the output channel to avoid sending obsolete messages
            output = Channel(BUFFERED)
            for (msg in output) send(msg)
        }

        launch { doPing() }
        launch { checkPaymentsTimeout() }
        launch { respond() }

        listen() // This suspends until the coroutines is cancelled or the socket is closed
    }

    suspend fun send(cmd: PeerCommand) {
        input.send(cmd)
    }

    /**
     * Do a splice out using any suitable channel
     * @return  [Command.Splice.Companion.Result] if a splice was attempted, or {null} if no suitable
     *          channel was found
     */
    suspend fun spliceOut(amount: Satoshi, scriptPubKey: ByteVector, feeratePerKw: FeeratePerKw): Command.Splice.Response? {
        return channels.values
            .filterIsInstance<Normal>()
            .firstOrNull { it.commitments.availableBalanceForSend() > amount }
            ?.let { channel ->
                val spliceCommand = Command.Splice.Request(
                    replyTo = CompletableDeferred(),
                    spliceIn = null,
                    spliceOut = Command.Splice.Request.SpliceOut(amount, scriptPubKey),
                    feerate = feeratePerKw
                )
                send(WrappedChannelCommand(channel.channelId, ChannelCommand.ExecuteCommand(spliceCommand)))
                spliceCommand.replyTo.await()
            }
    }

    suspend fun createInvoice(paymentPreimage: ByteVector32, amount: MilliSatoshi?, description: Either<String, ByteVector32>, expirySeconds: Long? = null) {
        val command = ReceivePayment(
            paymentPreimage = paymentPreimage,
            amount = amount,
            description = description,
            expirySeconds = expirySeconds,
            result = CompletableDeferred())
        command.result.await()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun sendToPeer(msg: LightningMessage) {
        val encoded = LightningMessage.encode(msg)
        // Avoids polluting the logs with pings/pongs
        if (msg !is Ping && msg !is Pong) logger.info { "sending $msg" }
        if (!output.isClosedForSend) output.send(encoded)
    }

    // The (node_id, fcm_token) tuple only needs to be registered once.
    // And after that, only if the tuple changes (e.g. different fcm_token).
    fun registerFcmToken(token: String?) {
        launch {
            sendToPeer(if (token == null) UnsetFCMToken else FCMToken(token))
        }
    }

    private suspend fun processActions(channelId: ByteVector32, actions: List<ChannelAction>) {
        // we peek into the actions to see if the id of the channel is going to change, but we're not processing it yet
        val actualChannelId = actions.filterIsInstance<ChannelAction.ChannelId.IdAssigned>().firstOrNull()?.channelId ?: channelId
        logger.withMDC(mapOf("channelId" to actualChannelId)) { logger ->
            actions.forEach { action ->
                when {
                    action is ChannelAction.Message.Send && _connectionState.value == Connection.ESTABLISHED -> sendToPeer(action.message)
                    // sometimes channel actions include "self" command (such as CMD_SIGN)
                    action is ChannelAction.Message.SendToSelf -> input.send(WrappedChannelCommand(actualChannelId, ChannelCommand.ExecuteCommand(action.command)))
                    action is ChannelAction.Blockchain.SendWatch -> watcher.watch(action.watch)
                    action is ChannelAction.Blockchain.PublishTx -> watcher.publish(action.tx)
                    action is ChannelAction.ProcessIncomingHtlc -> processIncomingPayment(Either.Right(action.add))
                    action is ChannelAction.ProcessCmdRes.NotExecuted -> logger.warning(action.t) { "command not executed" }
                    action is ChannelAction.ProcessCmdRes.AddFailed -> {
                        when (val result = outgoingPaymentHandler.processAddFailed(actualChannelId, action, _channels)) {
                            is OutgoingPaymentHandler.Progress -> {
                                _eventsFlow.emit(PaymentProgress(result.request, result.fees))
                                result.actions.forEach { input.send(it) }
                            }

                            is OutgoingPaymentHandler.Failure -> _eventsFlow.emit(PaymentNotSent(result.request, result.failure))
                            null -> logger.debug { "non-final error, more partial payments are still pending: ${action.error.message}" }
                        }
                    }

                    action is ChannelAction.ProcessCmdRes.AddSettledFail -> {
                        val currentTip = currentTipFlow.filterNotNull().first()
                        when (val result = outgoingPaymentHandler.processAddSettled(actualChannelId, action, _channels, currentTip.first)) {
                            is OutgoingPaymentHandler.Progress -> {
                                _eventsFlow.emit(PaymentProgress(result.request, result.fees))
                                result.actions.forEach { input.send(it) }
                            }

                            is OutgoingPaymentHandler.Success -> _eventsFlow.emit(PaymentSent(result.request, result.payment))
                            is OutgoingPaymentHandler.Failure -> _eventsFlow.emit(PaymentNotSent(result.request, result.failure))
                            null -> logger.debug { "non-final error, more partial payments are still pending: ${action.result}" }
                        }
                    }

                    action is ChannelAction.ProcessCmdRes.AddSettledFulfill -> {
                        when (val result = outgoingPaymentHandler.processAddSettled(action)) {
                            is OutgoingPaymentHandler.Success -> _eventsFlow.emit(PaymentSent(result.request, result.payment))
                            is OutgoingPaymentHandler.PreimageReceived -> logger.debug(mapOf("paymentId" to result.request.paymentId)) { "payment preimage received: ${result.preimage}" }
                            null -> logger.debug { "unknown payment" }
                        }
                    }

                    action is ChannelAction.Storage.StoreState -> {
                        logger.info { "storing state=${action.data::class.simpleName}" }
                        db.channels.addOrUpdateChannel(action.data)
                    }

                    action is ChannelAction.Storage.StoreHtlcInfos -> {
                        action.htlcs.forEach { db.channels.addHtlcInfo(actualChannelId, it.commitmentNumber, it.paymentHash, it.cltvExpiry) }
                    }

                    action is ChannelAction.Storage.StoreIncomingAmount -> {
                        logger.info { "storing incoming amount=${action.amount} with origin=${action.origin}" }
                        incomingPaymentHandler.process(actualChannelId, action)
                    }

                    action is ChannelAction.Storage.GetHtlcInfos -> {
                        val htlcInfos = db.channels.listHtlcInfos(actualChannelId, action.commitmentNumber).map { ChannelAction.Storage.HtlcInfo(actualChannelId, action.commitmentNumber, it.first, it.second) }
                        input.send(WrappedChannelCommand(actualChannelId, ChannelCommand.GetHtlcInfosResponse(action.revokedCommitTxId, htlcInfos)))
                    }

                    action is ChannelAction.Storage.StoreChannelClosing -> {
                        val dbId = UUID.fromBytes(channelId.take(16).toByteArray())
                        val recipient = if (action.isSentToDefaultAddress) nodeParams.nodeId else PublicKey.Generator
                        val payment = LightningOutgoingPayment(
                            id = dbId,
                            recipientAmount = action.amount,
                            recipient = recipient,
                            details = LightningOutgoingPayment.Details.ChannelClosing(
                                channelId = channelId,
                                closingAddress = action.closingAddress,
                                isSentToDefaultAddress = action.isSentToDefaultAddress
                            ),
                            parts = emptyList(),
                            status = LightningOutgoingPayment.Status.Pending
                        )
                        db.payments.addOutgoingPayment(payment)
                        _eventsFlow.emit(ChannelClosing(channelId))
                    }

                    action is ChannelAction.Storage.StoreChannelClosed -> {
                        val dbId = UUID.fromBytes(channelId.take(16).toByteArray())
                        db.payments.completeOutgoingPaymentForClosing(id = dbId, parts = action.closingTxs, completedAt = currentTimestampMillis())
                        _eventsFlow.emit(ChannelClosing(channelId))
                    }

                    action is ChannelAction.ChannelId.IdAssigned -> {
                        logger.info { "switching channel id from ${action.temporaryChannelId} to ${action.channelId}" }
                        _channels[action.temporaryChannelId]?.let { _channels = _channels + (action.channelId to it) - action.temporaryChannelId }
                    }

                    action is ChannelAction.ProcessLocalError -> logger.error(action.error) { "error in channel $actualChannelId" }

                    action is ChannelAction.EmitEvent -> nodeParams._nodeEvents.emit(action.event)
                }
            }
        }
    }

    private suspend fun processIncomingPayment(item: Either<PayToOpenRequest, UpdateAddHtlc>) {
        val currentBlockHeight = currentTipFlow.filterNotNull().first().first
        val result = when (item) {
            is Either.Right -> incomingPaymentHandler.process(item.value, currentBlockHeight)
            is Either.Left -> incomingPaymentHandler.process(item.value, currentBlockHeight)
        }
        when (result) {
            is IncomingPaymentHandler.ProcessAddResult.Accepted -> _eventsFlow.emit(PaymentReceived(result.incomingPayment, result.received))
            else -> Unit
        }
        result.actions.forEach { input.send(it) }
    }

    private suspend fun handshake(
        ourKeys: Pair<ByteArray, ByteArray>,
        theirPubkey: ByteArray,
        r: suspend (Int) -> ByteArray,
        w: suspend (ByteArray) -> Unit
    ): Triple<CipherState, CipherState, ByteArray> {

        /**
         * See BOLT #8: during the handshake phase we are expecting 3 messages of 50, 50 and 66 bytes (including the prefix)
         *
         * @param reader handshake state reader
         * @return the size of the message the reader is expecting
         */
        fun expectedLength(reader: HandshakeStateReader): Int = when (reader.messages.size) {
            3, 2 -> 50
            1 -> 66
            else -> throw RuntimeException("invalid state")
        }

        val writer = makeWriter(ourKeys, theirPubkey)
        val (state1, message, _) = writer.write(ByteArray(0))
        w(byteArrayOf(prefix) + message)

        val payload = r(expectedLength(state1))
        require(payload[0] == prefix)

        val (writer1, _, _) = state1.read(payload.drop(1).toByteArray())
        val (_, message1, foo) = writer1.write(ByteArray(0))
        val (enc, dec, ck) = foo!!
        w(byteArrayOf(prefix) + message1)
        return Triple(enc, dec, ck)
    }

    private fun makeWriter(localStatic: Pair<ByteArray, ByteArray>, remoteStatic: ByteArray) = HandshakeState.initializeWriter(
        handshakePatternXK, prologue,
        localStatic, Pair(ByteArray(0), ByteArray(0)), remoteStatic, ByteArray(0),
        Secp256k1DHFunctions, Chacha20Poly1305CipherFunctions, SHA256HashFunctions
    )

    private fun makeReader(localStatic: Pair<ByteArray, ByteArray>) = HandshakeState.initializeReader(
        handshakePatternXK, prologue,
        localStatic, Pair(ByteArray(0), ByteArray(0)), ByteArray(0), ByteArray(0),
        Secp256k1DHFunctions, Chacha20Poly1305CipherFunctions, SHA256HashFunctions
    )

    private suspend fun run() {
        logger.info { "peer is active" }
        for (event in input) {
            processEvent(event)
        }
    }

    private suspend fun processEvent(cmd: PeerCommand) {
        when {
            cmd is BytesReceived -> {
                val msg = try {
                    LightningMessage.decode(cmd.data)
                } catch (e: Throwable) {
                    logger.warning { "cannot deserialized message: ${cmd.data.byteVector().toHex()}" }
                    return
                }
                logger.withMDC(msg.mdc()) { logger ->
                    msg.let { if (it !is Ping && it !is Pong) logger.info { "received $it" } }
                    when {
                        msg is UnknownMessage -> {
                            logger.warning { "unhandled code=${msg.type}, cannot decode input=${Hex.encode(cmd.data)}" }
                        }

                        msg is Init -> {
                            val theirFeatures = Features(msg.features)
                            logger.info { "peer is using features $theirFeatures" }
                            when (val error = Features.validateFeatureGraph(features)) {
                                is Features.Companion.FeatureException -> {
                                    logger.error(error) { "feature validation error" }
                                    // TODO: disconnect peer
                                }

                                else -> {
                                    theirInit = msg
                                    _connectionState.value = Connection.ESTABLISHED
                                    _channels = _channels.mapValues { entry ->
                                        val (state1, actions) = entry.value.process(ChannelCommand.Connected(ourInit, theirInit!!))
                                        processActions(entry.key, actions)
                                        state1
                                    }
                                }
                            }
                        }

                        msg is Ping -> {
                            val pong = Pong(ByteVector(ByteArray(msg.pongLength)))
                            sendToPeer(pong)
                        }

                        msg is Pong -> {
                            logger.debug { "received pong" }
                        }

                        msg is Warning -> {
                            // NB: we don't forward warnings to the channel because it shouldn't take any automatic action,
                            // these warnings are meant for humans.
                            logger.warning { "peer sent warning: ${msg.toAscii()}" }
                        }

                        msg is Error && msg.channelId == ByteVector32.Zeroes -> {
                            logger.error { "connection error: ${msg.toAscii()}" }
                        }

                        msg is PleaseOpenChannelRejected -> {
                            when (val request = channelRequests[msg.requestId]) {
                                is RequestChannelOpen -> {
                                    logger.error { "peer rejected channel with failure=${msg.failure} (request=$request)" }
                                    nodeParams._nodeEvents.emit(SwapInEvents.Rejected(msg.requestId, msg.failure, msg.expectedFees))
                                    channelRequests = channelRequests - msg.requestId
                                }

                                else -> {}
                            }
                        }

                        msg is OpenDualFundedChannel && theirInit == null -> {
                            logger.error { "they sent open_channel before init" }
                        }

                        msg is OpenDualFundedChannel && _channels.containsKey(msg.temporaryChannelId) -> {
                            logger.warning { "ignoring open_channel with duplicate temporaryChannelId=${msg.temporaryChannelId}" }
                        }

                        msg is OpenDualFundedChannel -> {
                            val (wallet, fundingAmount, pushAmount) = when (val origin = msg.origin) {
                                is ChannelOrigin.PleaseOpenChannelOrigin -> when (val request = channelRequests[origin.requestId]) {
                                    is RequestChannelOpen -> {
                                        // Let's verify that the fee is indeed below our max (a honest LSP would not even try)
                                        val totalFee = origin.serviceFee.truncateToSatoshi() + origin.fundingFee
                                        if (totalFee > request.maxFee) {
                                            logger.warning { "n:$remoteNodeId c:${msg.temporaryChannelId} rejecting open_channel2: fee is too high (max=${request.maxFee} actual=${totalFee})" }
                                            sendToPeer(Error(msg.temporaryChannelId, "channel opening fee too high"))
                                            return@withMDC
                                        }
                                        // We have to pay the fees for our inputs, so we deduce them from our funding amount.
                                        val fundingAmount = request.wallet.confirmedBalance - origin.fundingFee
                                        nodeParams._nodeEvents.emit(SwapInEvents.Accepted(request.requestId, serviceFee = origin.serviceFee, fundingFee = origin.fundingFee))
                                        Triple(request.wallet, fundingAmount, origin.serviceFee)
                                    }

                                    else -> {
                                        logger.warning { "n:$remoteNodeId c:${msg.temporaryChannelId} rejecting open_channel2: cannot find channel request with requestId=${origin.requestId}" }
                                        sendToPeer(Error(msg.temporaryChannelId, "no corresponding channel request"))
                                        return@withMDC
                                    }
                                }

                                else -> Triple(WalletState.empty, 0.sat, 0.msat)
                            }
                            if (fundingAmount.toMilliSatoshi() < pushAmount) {
                                logger.warning { "rejecting open_channel2 with invalid funding and push amounts ($fundingAmount < $pushAmount)" }
                                sendToPeer(Error(msg.temporaryChannelId, InvalidPushAmount(msg.temporaryChannelId, pushAmount, fundingAmount.toMilliSatoshi()).message))
                            } else {
                                val fundingKeyPath = randomKeyPath(4)
                                val fundingPubkey = nodeParams.keyManager.fundingPublicKey(fundingKeyPath)
                                val (_, closingPubkeyScript) = nodeParams.keyManager.closingPubkeyScript(fundingPubkey.publicKey)
                                val localParams = LocalParams(
                                    nodeParams.nodeId,
                                    fundingKeyPath,
                                    nodeParams.dustLimit,
                                    nodeParams.maxHtlcValueInFlightMsat,
                                    nodeParams.htlcMinimum,
                                    nodeParams.toRemoteDelayBlocks,
                                    nodeParams.maxAcceptedHtlcs,
                                    false,
                                    closingPubkeyScript.toByteVector(),
                                    features
                                )
                                val state = WaitForInit
                                val channelConfig = ChannelConfig.standard
                                val (state1, actions1) = state.process(ChannelCommand.InitNonInitiator(msg.temporaryChannelId, fundingAmount, pushAmount, wallet, localParams, channelConfig, theirInit!!))
                                val (state2, actions2) = state1.process(ChannelCommand.MessageReceived(msg))
                                _channels = _channels + (msg.temporaryChannelId to state2)
                                when (val origin = msg.origin) {
                                    is ChannelOrigin.PleaseOpenChannelOrigin -> channelRequests = channelRequests - origin.requestId
                                    else -> Unit
                                }
                                processActions(msg.temporaryChannelId, actions1 + actions2)
                            }
                        }

                        msg is ChannelReestablish && !_channels.containsKey(msg.channelId) -> {
                            if (msg.channelData.isEmpty()) {
                                sendToPeer(Error(msg.channelId, "unknown channel"))
                            } else {
                                when (val decrypted = runTrying { PersistedChannelState.from(nodeParams.nodePrivateKey, msg.channelData) }) {
                                    is Try.Success -> {
                                        logger.warning { "restoring channel ${msg.channelId} from peer backup" }
                                        val backup = decrypted.result
                                        val state = WaitForInit
                                        val event1 = ChannelCommand.Restore(backup)
                                        val (state1, actions1) = state.process(event1)
                                        processActions(msg.channelId, actions1)

                                        val event2 = ChannelCommand.Connected(ourInit, theirInit!!)
                                        val (state2, actions2) = state1.process(event2)
                                        processActions(msg.channelId, actions2)

                                        val event3 = ChannelCommand.MessageReceived(msg)
                                        val (state3, actions3) = state2.process(event3)
                                        processActions(msg.channelId, actions3)
                                        _channels = _channels + (msg.channelId to state3)
                                    }

                                    is Try.Failure -> {
                                        logger.error(decrypted.error) { "failed to restore channel ${msg.channelId} from peer backup" }
                                        sendToPeer(Error(msg.channelId, "unknown channel"))
                                    }
                                }
                            }
                        }

                        msg is HasTemporaryChannelId && !_channels.containsKey(msg.temporaryChannelId) -> {
                            logger.error { "received ${msg::class.simpleName} for unknown temporary channel ${msg.temporaryChannelId}" }
                            sendToPeer(Error(msg.temporaryChannelId, "unknown channel"))
                        }

                        msg is HasTemporaryChannelId -> {
                            logger.info { "received ${msg::class.simpleName} for temporary channel ${msg.temporaryChannelId}" }
                            val state = _channels[msg.temporaryChannelId] ?: error("channel ${msg.temporaryChannelId} not found")
                            val event1 = ChannelCommand.MessageReceived(msg)
                            val (state1, actions) = state.process(event1)
                            _channels = _channels + (msg.temporaryChannelId to state1)
                            processActions(msg.temporaryChannelId, actions)
                        }

                        msg is HasChannelId && !_channels.containsKey(msg.channelId) -> {
                            logger.error { "received ${msg::class.simpleName} for unknown channel ${msg.channelId}" }
                            sendToPeer(Error(msg.channelId, "unknown channel"))
                        }

                        msg is HasChannelId -> {
                            logger.info { "received ${msg::class.simpleName} for channel ${msg.channelId}" }
                            val state = _channels[msg.channelId] ?: error("channel ${msg.channelId} not found")
                            val event1 = ChannelCommand.MessageReceived(msg)
                            val (state1, actions) = state.process(event1)
                            processActions(msg.channelId, actions)
                            _channels = _channels + (msg.channelId to state1)
                        }

                        msg is ChannelUpdate -> {
                            logger.info { "received ${msg::class.simpleName} for channel ${msg.shortChannelId}" }
                            _channels.values.filterIsInstance<Normal>().find { it.shortChannelId == msg.shortChannelId }?.let { state ->
                                val event1 = ChannelCommand.MessageReceived(msg)
                                val (state1, actions) = state.process(event1)
                                processActions(state.channelId, actions)
                                _channels = _channels + (state.channelId to state1)
                            }
                        }

                        msg is PayToOpenRequest -> {
                            logger.info { "received ${msg::class.simpleName}" }
                            processIncomingPayment(Either.Left(msg))
                        }

                        msg is PhoenixAndroidLegacyInfo -> {
                            logger.info { "received ${msg::class.simpleName} hasChannels=${msg.hasChannels}" }
                            _eventsFlow.emit(PhoenixAndroidLegacyInfoEvent(msg))
                        }

                        msg is OnionMessage -> {
                            logger.info { "received ${msg::class.simpleName}" }
                            // TODO: process onion message
                        }

                        else -> logger.warning { "received unhandled message ${Hex.encode(cmd.data)}" }
                    }
                }
            }

            cmd is WatchReceived && !_channels.containsKey(cmd.watch.channelId) -> {
                logger.error { "received watch event ${cmd.watch} for unknown channel ${cmd.watch.channelId}}" }
            }

            cmd is WatchReceived -> {
                val state = _channels[cmd.watch.channelId] ?: error("channel ${cmd.watch.channelId} not found")
                val event1 = ChannelCommand.WatchReceived(cmd.watch)
                val (state1, actions) = state.process(event1)
                processActions(cmd.watch.channelId, actions)
                _channels = _channels + (cmd.watch.channelId to state1)
            }

            cmd is RequestChannelOpen -> {
                val balance = cmd.wallet.confirmedBalance
                when (val channel = channels.values.firstOrNull { it is Normal }) {
                    is ChannelStateWithCommitments -> {
                        val feerate = onChainFeeratesFlow.filterNotNull().first().fundingFeerate
                        logger.info { "requesting splice-in using confirmed balance=$balance feerate=$feerate" }

                        val spliceCommand = Command.Splice.Request(
                            replyTo = CompletableDeferred(),
                            spliceIn = Command.Splice.Request.SpliceIn(cmd.wallet),
                            spliceOut = null,
                            feerate = feerate
                        )
                        input.send(WrappedChannelCommand(channel.channelId, ChannelCommand.ExecuteCommand(spliceCommand)))
                    }
                    else -> {
                        if (channels.values.all { it is ShuttingDown || it is Negotiating || it is Closing || it is Closed || it is ErrorInformationLeak || it is Aborted }) {
                            // Either there are no channels, or they will never be suitable for a splice-in: we request a new channel
                            val utxos = cmd.wallet.confirmedUtxos
                            // Grand parents are supplied as a proof of migration
                            val grandParents = utxos.map { utxo -> utxo.previousTx.txIn.map { txIn -> txIn.outPoint } }.flatten()
                            val pleaseOpenChannel = PleaseOpenChannel(
                                nodeParams.chainHash,
                                cmd.requestId,
                                balance,
                                utxos.size,
                                utxos.size * Transactions.p2wpkhInputWeight,
                                TlvStream(PleaseOpenChannelTlv.MaxFees(cmd.maxFeeBasisPoints, cmd.maxFeeFloor), PleaseOpenChannelTlv.GrandParents(grandParents))
                            )
                            logger.info { "sending please_open_channel with ${utxos.size} utxos (amount = ${balance})" }
                            sendToPeer(pleaseOpenChannel)
                            nodeParams._nodeEvents.emit(SwapInEvents.Requested(pleaseOpenChannel))
                            channelRequests = channelRequests + (pleaseOpenChannel.requestId to cmd)
                        } else {
                            // There are existing channels but not immediately usable (e.g. creating, disconnected), we don't do anything yet
                            logger.info { "ignoring channel request, existing channels are not ready for splice-in: ${channels.values.map { it::class.simpleName }}" }
                        }
                    }
                }
            }

            cmd is OpenChannel -> {
                val fundingKeyPath = randomKeyPath(4)
                val fundingPubkey = nodeParams.keyManager.fundingPublicKey(fundingKeyPath)
                val (_, closingPubkeyScript) = nodeParams.keyManager.closingPubkeyScript(fundingPubkey.publicKey)
                val localParams = LocalParams(
                    nodeParams.nodeId,
                    fundingKeyPath,
                    nodeParams.dustLimit,
                    nodeParams.maxHtlcValueInFlightMsat,
                    nodeParams.htlcMinimum,
                    nodeParams.toRemoteDelayBlocks,
                    nodeParams.maxAcceptedHtlcs,
                    true,
                    closingPubkeyScript.toByteVector(),
                    features
                )
                val state = WaitForInit
                val (state1, actions1) = state.process(
                    ChannelCommand.InitInitiator(
                        cmd.fundingAmount,
                        cmd.pushAmount,
                        cmd.wallet,
                        cmd.commitTxFeerate,
                        cmd.fundingTxFeerate,
                        localParams,
                        theirInit!!,
                        cmd.channelFlags,
                        ChannelConfig.standard,
                        cmd.channelType
                    )
                )
                val msg = actions1.filterIsInstance<ChannelAction.Message.Send>().map { it.message }.filterIsInstance<OpenDualFundedChannel>().first()
                _channels = _channels + (msg.temporaryChannelId to state1)
                processActions(msg.temporaryChannelId, actions1)
            }

            cmd is ReceivePayment -> {
                // we add one extra hop which uses a virtual channel with a "peer id", using the highest remote fees and expiry across all
                // channels to maximize the likelihood of success on the first payment attempt
                val remoteChannelUpdates = _channels.values.mapNotNull { channelState ->
                    when (channelState) {
                        is Normal -> channelState.remoteChannelUpdate
                        is Offline -> (channelState.state as? Normal)?.remoteChannelUpdate
                        is Syncing -> (channelState.state as? Normal)?.remoteChannelUpdate
                        else -> null
                    }
                }
                val extraHops = listOf(
                    listOf(
                        PaymentRequest.TaggedField.ExtraHop(
                            nodeId = walletParams.trampolineNode.id,
                            shortChannelId = ShortChannelId.peerId(nodeParams.nodeId),
                            feeBase = remoteChannelUpdates.maxOfOrNull { it.feeBaseMsat } ?: walletParams.invoiceDefaultRoutingFees.feeBase,
                            feeProportionalMillionths = remoteChannelUpdates.maxOfOrNull { it.feeProportionalMillionths } ?: walletParams.invoiceDefaultRoutingFees.feeProportional,
                            cltvExpiryDelta = remoteChannelUpdates.maxOfOrNull { it.cltvExpiryDelta } ?: walletParams.invoiceDefaultRoutingFees.cltvExpiryDelta
                        )
                    )
                )
                val pr = incomingPaymentHandler.createInvoice(cmd.paymentPreimage, cmd.amount, cmd.description, extraHops, cmd.expirySeconds)
                _eventsFlow.emit(PaymentRequestGenerated(cmd, pr.write()))
                cmd.result.complete(pr)
            }

            cmd is PayToOpenResponseCommand -> {
                logger.info { "sending ${cmd.payToOpenResponse::class.simpleName}" }
                sendToPeer(cmd.payToOpenResponse)
            }

            cmd is SendPayment -> {
                val currentTip = currentTipFlow.filterNotNull().first()
                when (val result = outgoingPaymentHandler.sendPayment(cmd, _channels, currentTip.first)) {
                    is OutgoingPaymentHandler.Progress -> {
                        _eventsFlow.emit(PaymentProgress(result.request, result.fees))
                        result.actions.forEach { input.send(it) }
                    }

                    is OutgoingPaymentHandler.Failure -> _eventsFlow.emit(PaymentNotSent(result.request, result.failure))
                }
            }

            cmd is PurgeExpiredPayments -> {
                incomingPaymentHandler.purgeExpiredPayments(cmd.fromCreatedAt, cmd.toCreatedAt)
            }

            cmd is CheckPaymentsTimeout -> {
                val actions = incomingPaymentHandler.checkPaymentsTimeout(currentTimestampSeconds())
                actions.forEach { input.send(it) }
            }

            cmd is WrappedChannelCommand && cmd.channelId == ByteVector32.Zeroes -> {
                // this is for all channels
                _channels.forEach { (key, value) ->
                    val (state1, actions) = value.process(cmd.channelCommand)
                    processActions(key, actions)
                    _channels = _channels + (key to state1)
                }
            }

            cmd is WrappedChannelCommand -> when (val state = _channels[cmd.channelId]) {
                null -> logger.error { "received ${cmd.channelCommand::class.simpleName} for an unknown channel ${cmd.channelId}" }
                else -> {
                    val (state1, actions) = state.process(cmd.channelCommand)
                    processActions(cmd.channelId, actions)
                    _channels = _channels + (cmd.channelId to state1)
                }
            }

            cmd is Disconnected -> {
                logger.warning { "disconnecting channels" }
                _channels.forEach { (key, value) ->
                    val (state1, actions) = value.process(ChannelCommand.Disconnected)
                    processActions(key, actions)
                    _channels = _channels + (key to state1)
                }
            }
        }
    }
}
