package fr.acinq.eclair.io

import fr.acinq.bitcoin.*
import fr.acinq.eclair.*
import fr.acinq.eclair.blockchain.WatchEvent
import fr.acinq.eclair.blockchain.electrum.AskForHeaderSubscriptionUpdate
import fr.acinq.eclair.blockchain.electrum.AskForStatusUpdate
import fr.acinq.eclair.blockchain.electrum.ElectrumWatcher
import fr.acinq.eclair.blockchain.electrum.HeaderSubscriptionResponse
import fr.acinq.eclair.blockchain.fee.FeeratePerByte
import fr.acinq.eclair.blockchain.fee.FeeratePerKw
import fr.acinq.eclair.blockchain.fee.OnChainFeerates
import fr.acinq.eclair.channel.*
import fr.acinq.eclair.crypto.noise.*
import fr.acinq.eclair.db.Databases
import fr.acinq.eclair.db.IncomingPayment
import fr.acinq.eclair.db.OutgoingPayment
import fr.acinq.eclair.payment.*
import fr.acinq.eclair.utils.*
import fr.acinq.eclair.wire.*
import fr.acinq.secp256k1.Hex
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BroadcastChannel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.Channel.Factory.BUFFERED
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.flow.*
import kotlin.math.min
import kotlin.time.ExperimentalTime
import kotlin.time.seconds

sealed class PeerEvent
data class BytesReceived(val data: ByteArray) : PeerEvent()
data class WatchReceived(val watch: WatchEvent) : PeerEvent()
data class WrappedChannelEvent(val channelId: ByteVector32, val channelEvent: ChannelEvent) : PeerEvent()
object Connected : PeerEvent()
object Disconnected : PeerEvent()

sealed class PaymentEvent : PeerEvent()
data class ReceivePayment(val paymentPreimage: ByteVector32, val amount: MilliSatoshi?, val description: String, val result: CompletableDeferred<PaymentRequest>) : PaymentEvent()
object CheckPaymentsTimeout : PaymentEvent()
data class PayToOpenResponseEvent(val payToOpenResponse: PayToOpenResponse) : PeerEvent()
data class SendPayment(val paymentId: UUID, val amount: MilliSatoshi, val recipient: PublicKey, val details: OutgoingPayment.Details.Normal) : PaymentEvent() {
    val paymentHash: ByteVector32 = details.paymentHash
}

sealed class PeerListenerEvent
data class PaymentRequestGenerated(val receivePayment: ReceivePayment, val request: String) : PeerListenerEvent()
data class PaymentReceived(val incomingPayment: IncomingPayment) : PeerListenerEvent()
data class PaymentProgress(val request: SendPayment, val fees: MilliSatoshi) : PeerListenerEvent()
data class PaymentNotSent(val request: SendPayment, val reason: OutgoingPaymentFailure) : PeerListenerEvent()
data class PaymentSent(val request: SendPayment, val payment: OutgoingPayment) : PeerListenerEvent()

@OptIn(ExperimentalStdlibApi::class, ExperimentalCoroutinesApi::class, ExperimentalTime::class)
class Peer(
    val socketBuilder: TcpSocket.Builder,
    val nodeParams: NodeParams,
    val watcher: ElectrumWatcher,
    val db: Databases,
    scope: CoroutineScope
) : CoroutineScope by scope {
    companion object {
        private const val prefix: Byte = 0x00
        private val prologue = "lightning".encodeToByteArray()
    }

    public val remoteNodeId: PublicKey = nodeParams.trampolineNode.id

    private val input = Channel<PeerEvent>(BUFFERED)
    private val output = Channel<ByteArray>(BUFFERED)

    private val logger by eclairLogger()

    private val _channelsFlow = MutableStateFlow<Map<ByteVector32, ChannelState>>(HashMap())
    private var _channels by _channelsFlow

    // channels map, indexed by channel id
    // note that a channel starts with a temporary id then switches to its final id once the funding tx is known
    val channels: Map<ByteVector32, ChannelState> get() = _channelsFlow.value
    val channelsFlow: StateFlow<Map<ByteVector32, ChannelState>> get() = _channelsFlow

    private val _connectionState = MutableStateFlow(Connection.CLOSED)
    val connectionState: StateFlow<Connection> get() = _connectionState

    private val listenerEventChannel = BroadcastChannel<PeerListenerEvent>(BUFFERED)

    // encapsulates logic for validating incoming payments
    private val incomingPaymentHandler = IncomingPaymentHandler(nodeParams, db.payments)

    // encapsulates logic for sending payments
    private val outgoingPaymentHandler = OutgoingPaymentHandler(nodeParams, db.payments, RouteCalculation.TrampolineParams(remoteNodeId, RouteCalculation.defaultTrampolineFees))

    private val features = nodeParams.features

    private val ourInit = Init(features.toByteArray().toByteVector())
    private var theirInit: Init? = null
    private var currentTip: Pair<Int, BlockHeader> = Pair(0, Block.RegtestGenesisBlock.header)

    // TODO: connect to fee providers (can we get fee estimation from electrum?)
    private var onChainFeerates = OnChainFeerates(
        mutualCloseFeerate = FeeratePerKw(FeeratePerByte(20.sat)),
        claimMainFeerate = FeeratePerKw(FeeratePerByte(20.sat)),
        fastFeerate = FeeratePerKw(FeeratePerByte(50.sat))
    )

    init {
        val electrumNotificationsChannel = watcher.client.openNotificationsSubscription()
        launch {
            electrumNotificationsChannel.consumeAsFlow().filterIsInstance<HeaderSubscriptionResponse>()
                .collect { msg ->
                    currentTip = msg.height to msg.header
                    send(WrappedChannelEvent(ByteVector32.Zeroes, ChannelEvent.NewBlock(msg.height, msg.header)))
                }
        }
        launch {
            watcher.client.connectionState.filter { it == Connection.ESTABLISHED }.collect {
                watcher.client.sendMessage(AskForHeaderSubscriptionUpdate)
            }
        }
        launch {
            val sub = watcher.openNotificationsSubscription()
            sub.consumeEach {
                logger.info { "notification: $it" }
                input.send(WrappedChannelEvent(it.channelId, ChannelEvent.WatchReceived(it)))
            }
        }
        launch {
            // we don't restore closed channels
            db.channels.listLocalChannels().filterNot { it is Closed }.forEach {
                logger.info { "restoring $it" }
                val state = WaitForInit(StaticParams(nodeParams, remoteNodeId), currentTip, onChainFeerates)
                val (state1, actions) = state.process(ChannelEvent.Restore(it as ChannelState))
                processActions(it.channelId, actions)
                _channels = _channels + (it.channelId to state1)
            }
            logger.info { "restored channels: $_channels" }
            run()
        }

        /*
            Handle connection changes:
                - CLOSED
                    + Move all relevant channels to Offline
                    + Retry connection periodically
                - ESTABLISHED
                    + Try to reestablish all Offline channels
         */
        launch {
            var previousState = connectionState.value
            var delay = 0.5
            connectionState.filter { it != previousState }.collect {
                when (it) {
                    Connection.CLOSED -> {
                        if (previousState == Connection.ESTABLISHED) send(Disconnected)
                        delay(delay.seconds); delay = min((delay * 2), 30.0)
                        connect()
                    }
                    Connection.ESTABLISHED -> {
                        logger.info { "connected to {$remoteNodeId}@{${nodeParams.trampolineNode.host}}" }
                        send(Connected)
                        delay = 0.5
                    }
                    else -> Unit
                }

                previousState = it
            }
        }

        watcher.client.sendMessage(AskForStatusUpdate)
    }

    fun connect() {
        launch {
            logger.info { "connecting to {$remoteNodeId}@{${nodeParams.trampolineNode.host}}" }
            _connectionState.value = Connection.ESTABLISHING
            val socket = try {
                socketBuilder.connect(nodeParams.trampolineNode.host, nodeParams.trampolineNode.port)
            } catch (ex: TcpSocket.IOException) {
                logger.warning { ex.message }
                _connectionState.value = Connection.CLOSED
                return@launch
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
                logger.warning { ex.message }
                _connectionState.value = Connection.CLOSED
                return@launch
            }
            val session = LightningSession(enc, dec, ck)

            suspend fun receive(): ByteArray {
                return session.receive { size -> socket.receiveFully(size) }
            }

            suspend fun send(message: ByteArray) {
                try {
                    session.send(message) { data, flush -> socket.send(data, flush) }
                } catch (ex: TcpSocket.IOException) {
                    logger.warning { ex.message }
                }
            }
            logger.info { "sending init $ourInit" }
            send(LightningMessage.encode(ourInit) ?: error("cannot serialize $ourInit"))

            suspend fun doPing() {
                val ping = Hex.decode("0012000a0004deadbeef")
                while (isActive) {
                    delay(30000)
                    send(ping)
                }
            }

            suspend fun checkPaymentsTimeout() {
                while (isActive) {
                    delay(timeMillis = 30_000)
                    input.send(CheckPaymentsTimeout)
                }
            }

            suspend fun listen() {
                try {
                    while (isActive) {
                        val received = receive()
                        input.send(BytesReceived(received))
                    }
                } catch (ex: TcpSocket.IOException) {
                    logger.warning { ex.message }
                } finally {
                    _connectionState.value = Connection.CLOSED
                }
            }

            suspend fun respond() {
                for (msg in output) {
                    send(msg)
                }
            }

            coroutineScope {
                launch { doPing() }
                launch { checkPaymentsTimeout() }
                launch { respond() }

                listen()
                cancel()
            }
        }
    }

    suspend fun send(event: PeerEvent) {
        input.send(event)
    }

    fun openListenerEventSubscription() = listenerEventChannel.openSubscription()

    private suspend fun sendToPeer(msg: LightningMessage) {
        val encoded = LightningMessage.encode(msg)
        encoded?.let { bin ->
            logger.info { "sending $msg encoded as ${Hex.encode(bin)}" }
            output.send(bin)
        }
    }

    private suspend fun processActions(channelId: ByteVector32, actions: List<ChannelAction>) {
        var actualChannelId: ByteVector32 = channelId
        actions.forEach { action ->
            when {
                action is ChannelAction.Message.Send && _connectionState.value == Connection.ESTABLISHED -> sendToPeer(action.message)
                // sometimes channel actions include "self" command (such as CMD_SIGN)
                action is ChannelAction.Message.SendToSelf -> input.send(WrappedChannelEvent(actualChannelId, ChannelEvent.ExecuteCommand(action.command)))
                action is ChannelAction.Blockchain.SendWatch -> watcher.watch(action.watch)
                action is ChannelAction.Blockchain.PublishTx -> watcher.publish(action.tx)
                action is ChannelAction.ProcessIncomingHtlc -> processIncomingPayment(Either.Right(action.add))
                action is ChannelAction.ProcessCmdRes.NotExecuted -> logger.warning(action.t) { "command not executed in channel $actualChannelId" }
                action is ChannelAction.ProcessCmdRes.AddFailed -> {
                    when (val result = outgoingPaymentHandler.processAddFailed(actualChannelId, action, _channels)) {
                        is OutgoingPaymentHandler.Progress -> {
                            listenerEventChannel.send(PaymentProgress(result.request, result.fees))
                            result.actions.forEach { input.send(it) }
                        }
                        is OutgoingPaymentHandler.Failure -> listenerEventChannel.send(PaymentNotSent(result.request, result.failure))
                        null -> logger.debug { "non-final error, more partial payments are still pending: $actualChannelId->${action.error.message}" }
                    }
                }
                action is ChannelAction.ProcessCmdRes.AddSettledFail -> {
                    when (val result = outgoingPaymentHandler.processAddSettled(actualChannelId, action, _channels, currentTip.first)) {
                        is OutgoingPaymentHandler.Progress -> {
                            listenerEventChannel.send(PaymentProgress(result.request, result.fees))
                            result.actions.forEach { input.send(it) }
                        }
                        is OutgoingPaymentHandler.Success -> listenerEventChannel.send(PaymentSent(result.request, result.payment))
                        is OutgoingPaymentHandler.Failure -> listenerEventChannel.send(PaymentNotSent(result.request, result.failure))
                        null -> logger.debug { "non-final error, more partial payments are still pending: $actualChannelId->${action.result}" }
                    }
                }
                action is ChannelAction.ProcessCmdRes.AddSettledFulfill -> {
                    when (val result = outgoingPaymentHandler.processAddSettled(action)) {
                        is OutgoingPaymentHandler.Success -> listenerEventChannel.send(PaymentSent(result.request, result.payment))
                        is OutgoingPaymentHandler.PreimageReceived -> logger.debug { "payment preimage received: ${result.request.paymentId}->${result.preimage}" }
                        null -> logger.debug { "unknown payment" }
                    }
                }
                action is ChannelAction.Storage.StoreState -> {
                    logger.info { "storing state for channelId=$actualChannelId data=${action.data}" }
                    db.channels.addOrUpdateChannel(action.data)
                }
                action is ChannelAction.ChannelId.IdSwitch -> {
                    logger.info { "switching channel id from ${action.oldChannelId} to ${action.newChannelId}" }
                    actualChannelId = action.newChannelId
                    _channels[action.oldChannelId]?.let { _channels = _channels + (action.newChannelId to it) }
                }
                action is ChannelAction.ProcessLocalError -> logger.error(action.error) { "error in channel $actualChannelId" }
                else -> logger.warning { "unhandled action: $actualChannelId->$action" }
            }
        }
    }

    private suspend fun processIncomingPayment(item: Either<PayToOpenRequest, UpdateAddHtlc>) {
        val currentBlockHeight = currentTip.first
        val result = when (item) {
            is Either.Right -> incomingPaymentHandler.process(item.value, currentBlockHeight)
            is Either.Left -> incomingPaymentHandler.process(item.value, currentBlockHeight)
        }
        if (result.status == IncomingPaymentHandler.Status.ACCEPTED && result.incomingPayment != null) {
            listenerEventChannel.send(PaymentReceived(result.incomingPayment))
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

    private suspend fun processEvent(event: PeerEvent) {
        when {
            event is BytesReceived -> {
                val msg = LightningMessage.decode(event.data)
                when {
                    msg is Init -> {
                        logger.info { "received $msg" }
                        val theirFeatures = Features(msg.features)
                        logger.info { "peer is using features $theirFeatures" }
                        when (val error = Features.validateFeatureGraph(features)) {
                            is Features.Companion.FeatureException -> {
                                logger.error(error)
                                // TODO: disconnect peer
                            }
                            null -> {
                                theirInit = msg
                                _connectionState.value = Connection.ESTABLISHED
                                logger.info { "before channels: $_channels" }
                                _channels = _channels.mapValues { entry ->
                                    val (state1, actions) = entry.value.process(ChannelEvent.Connected(ourInit, theirInit!!))
                                    processActions(entry.key, actions)
                                    state1
                                }
                                logger.info { "after channels: $_channels" }
                            }
                        }
                    }
                    msg is Ping -> {
                        val pong = Pong(ByteVector(ByteArray(msg.pongLength)))
                        output.send(LightningMessage.encode(pong)!!)
                    }
                    msg is Pong -> {
                        logger.debug { "received pong" }
                    }
                    msg is Error && msg.channelId == ByteVector32.Zeroes -> {
                        logger.error { "connection error, failing all channels: ${msg.toAscii()}" }
                    }
                    msg is OpenChannel && theirInit == null -> {
                        logger.error { "they sent open_channel before init" }
                    }
                    msg is OpenChannel && _channels.containsKey(msg.temporaryChannelId) -> {
                        logger.warning { "ignoring open_channel with duplicate temporaryChannelId=${msg.temporaryChannelId}" }
                    }
                    msg is OpenChannel -> {
                        val fundingKeyPath = KeyPath("/1/2/3")
                        val fundingPubkey = nodeParams.keyManager.fundingPublicKey(fundingKeyPath)
                        val (closingPubkey, closingPubkeyScript) = nodeParams.keyManager.closingPubkeyScript(fundingPubkey.publicKey)
                        val walletStaticPaymentBasepoint: PublicKey = closingPubkey
                        val localParams = LocalParams(
                            nodeParams.nodeId,
                            fundingKeyPath,
                            nodeParams.dustLimit,
                            nodeParams.maxHtlcValueInFlightMsat,
                            Satoshi(600),
                            nodeParams.htlcMinimum,
                            nodeParams.toRemoteDelayBlocks,
                            nodeParams.maxAcceptedHtlcs,
                            false,
                            closingPubkeyScript.toByteVector(),
                            walletStaticPaymentBasepoint,
                            features
                        )
                        val state = WaitForInit(
                            StaticParams(nodeParams, remoteNodeId),
                            currentTip,
                            onChainFeerates
                        )
                        val (state1, actions1) = state.process(ChannelEvent.InitFundee(msg.temporaryChannelId, localParams, theirInit!!))
                        val (state2, actions2) = state1.process(ChannelEvent.MessageReceived(msg))
                        processActions(msg.temporaryChannelId, actions1 + actions2)
                        _channels = _channels + (msg.temporaryChannelId to state2)
                        logger.info { "new state for ${msg.temporaryChannelId}: $state2" }
                    }
                    msg is ChannelReestablish && !_channels.containsKey(msg.channelId) -> {
                        if (msg.channelData.isEmpty()) {
                            sendToPeer(Error(msg.channelId, "unknown channel"))
                        } else {
                            when (val decrypted = runTrying { Helpers.decrypt(nodeParams.nodePrivateKey, msg.channelData) }) {
                                is Try.Success -> {
                                    logger.warning { "restoring channelId=${msg.channelId} from peer backup" }
                                    val backup = decrypted.result
                                    val state = WaitForInit(StaticParams(nodeParams, remoteNodeId), currentTip, onChainFeerates)
                                    val event1 = ChannelEvent.Restore(backup as ChannelState)
                                    val (state1, actions1) = state.process(event1)
                                    processActions(msg.channelId, actions1)

                                    val event2 = ChannelEvent.Connected(ourInit, theirInit!!)
                                    val (state2, actions2) = state1.process(event2)
                                    processActions(msg.channelId, actions2)

                                    val event3 = ChannelEvent.MessageReceived(msg)
                                    val (state3, actions3) = state2.process(event3)
                                    processActions(msg.channelId, actions3)
                                    _channels = _channels + (msg.channelId to state3)
                                }
                                is Try.Failure -> {
                                    logger.error(decrypted.error) { "failed to restore channelId=${msg.channelId}" }
                                }
                            }
                        }
                    }
                    msg is HasTemporaryChannelId && !_channels.containsKey(msg.temporaryChannelId) -> {
                        logger.error { "received $msg for unknown temporary channel ${msg.temporaryChannelId}" }
                        sendToPeer(Error(msg.temporaryChannelId, "unknown channel"))
                    }
                    msg is HasTemporaryChannelId -> {
                        logger.info { "received $msg for temporary channel ${msg.temporaryChannelId}" }
                        val state = _channels[msg.temporaryChannelId] ?: error("channel ${msg.temporaryChannelId} not found")
                        val event1 = ChannelEvent.MessageReceived(msg)
                        val (state1, actions) = state.process(event1)
                        _channels = _channels + (msg.temporaryChannelId to state1)
                        logger.info { "channel ${msg.temporaryChannelId} new state $state1" }
                        processActions(msg.temporaryChannelId, actions)
                        actions.filterIsInstance<ChannelAction.ChannelId.IdSwitch>().forEach {
                            logger.info { "id switch from ${it.oldChannelId} to ${it.newChannelId}" }
                            _channels = _channels - it.oldChannelId + (it.newChannelId to state1)
                        }
                    }
                    msg is HasChannelId && !_channels.containsKey(msg.channelId) -> {
                        logger.error { "received $msg for unknown channel ${msg.channelId}" }
                        sendToPeer(Error(msg.channelId, "unknown channel"))
                    }
                    msg is HasChannelId -> {
                        logger.info { "received $msg for channel ${msg.channelId}" }
                        val state = _channels[msg.channelId] ?: error("channel ${msg.channelId} not found")
                        val event1 = ChannelEvent.MessageReceived(msg)
                        val (state1, actions) = state.process(event1)
                        _channels = _channels + (msg.channelId to state1)
                        logger.info { "channel ${msg.channelId} new state $state1" }
                        processActions(msg.channelId, actions)
                    }
                    msg is PayToOpenRequest -> {
                        logger.info { "received pay-to-open request" }
                        processIncomingPayment(Either.Left(msg))
                    }
                    else -> logger.warning { "received unhandled message ${Hex.encode(event.data)}" }
                }
            }
            event is WatchReceived && !_channels.containsKey(event.watch.channelId) -> {
                logger.error { "received watch event ${event.watch} for unknown channel ${event.watch.channelId}}" }
            }
            event is WatchReceived -> {
                val state = _channels[event.watch.channelId] ?: error("channel ${event.watch.channelId} not found")
                val event1 = ChannelEvent.WatchReceived(event.watch)
                val (state1, actions) = state.process(event1)
                processActions(event.watch.channelId, actions)
                _channels = _channels + (event.watch.channelId to state1)
                logger.info { "channel ${event.watch.channelId} new state $state1" }
            }
            event is ReceivePayment -> {
                logger.info { "creating invoice $event" }
                val pr = incomingPaymentHandler.createInvoice(event.paymentPreimage, event.amount, event.description)
                logger.info { "payment request ${pr.write()}" }
                listenerEventChannel.send(PaymentRequestGenerated(event, pr.write()))
                event.result.complete(pr)
            }
            event is PayToOpenResponseEvent -> {
                logger.info { "sending pay-to-open response" }
                sendToPeer(event.payToOpenResponse)
            }
            event is SendPayment -> {
                when (val result = outgoingPaymentHandler.sendPayment(event, _channels, currentTip.first)) {
                    is OutgoingPaymentHandler.Progress -> {
                        listenerEventChannel.send(PaymentProgress(result.request, result.fees))
                        result.actions.forEach { input.send(it) }
                    }
                    is OutgoingPaymentHandler.Failure -> listenerEventChannel.send(PaymentNotSent(result.request, result.failure))
                }
            }
            event is CheckPaymentsTimeout -> {
                val actions = incomingPaymentHandler.checkPaymentsTimeout(currentTimestampSeconds())
                actions.forEach { input.send(it) }
            }
            event is WrappedChannelEvent && event.channelId == ByteVector32.Zeroes -> {
                // this is for all channels
                _channels.forEach { (key, value) ->
                    val (state1, actions) = value.process(event.channelEvent)
                    processActions(key, actions)
                    _channels = _channels + (key to state1)
                }
            }
            event is WrappedChannelEvent -> when (val state = _channels[event.channelId]) {
                null -> logger.error { "received ${event.channelEvent} for a unknown channel ${event.channelId}" }
                else -> {
                    val (state1, actions) = state.process(event.channelEvent)
                    processActions(event.channelId, actions)
                    _channels = _channels + (event.channelId to state1)
                }
            }
            event is Connected -> {
                // We try to reestablish the Offline channels
                _channels.filter { it.value is Offline }.forEach { (key, value) ->
                    val (state1, actions) = value.process(ChannelEvent.Connected(ourInit, theirInit!!))
                    processActions(key, actions)
                    _channels = _channels + (key to state1)
                }
            }
            event is Disconnected -> {
                // We set all relevant channels as Offline
                logger.warning { "Set channels as Offline." }
                _channels.forEach { (key, value) ->
                    val (state1, actions) = value.process(ChannelEvent.Disconnected)
                    processActions(key, actions)
                    _channels = _channels + (key to state1)
                }
            }
        }
    }
}
