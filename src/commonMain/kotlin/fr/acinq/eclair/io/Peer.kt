package fr.acinq.eclair.io

import fr.acinq.bitcoin.*
import fr.acinq.eclair.*
import fr.acinq.eclair.blockchain.WatchEvent
import fr.acinq.eclair.blockchain.electrum.AskForHeaderSubscriptionUpdate
import fr.acinq.eclair.blockchain.electrum.AskForStatusUpdate
import fr.acinq.eclair.blockchain.electrum.ElectrumWatcher
import fr.acinq.eclair.blockchain.electrum.HeaderSubscriptionResponse
import fr.acinq.eclair.blockchain.fee.OnchainFeerates
import fr.acinq.eclair.channel.*
import fr.acinq.eclair.crypto.noise.*
import fr.acinq.eclair.db.ChannelsDb
import fr.acinq.eclair.payment.*
import fr.acinq.eclair.utils.*
import fr.acinq.eclair.wire.*
import fr.acinq.secp256k1.Hex
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BroadcastChannel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.Channel.Factory.BUFFERED
import kotlinx.coroutines.channels.ConflatedBroadcastChannel
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.flow.*

sealed class PeerEvent
data class BytesReceived(val data: ByteArray) : PeerEvent()
data class WatchReceived(val watch: WatchEvent) : PeerEvent()
data class ReceivePayment(val paymentPreimage: ByteVector32, val amount: MilliSatoshi?, val description: String, val result: CompletableDeferred<PaymentRequest>) : PeerEvent()

data class SendPayment(val paymentId: UUID, val paymentRequest: PaymentRequest, val paymentAmount: MilliSatoshi) : PeerEvent()
data class WrappedChannelEvent(val channelId: ByteVector32, val channelEvent: ChannelEvent) : PeerEvent()
data class WrappedChannelError(val channelId: ByteVector32, val error: Throwable, val trigger: ChannelEvent) : PeerEvent()
object CheckPaymentsTimeout : PeerEvent()
data class ListChannels(val channels: CompletableDeferred<List<ChannelState>>) : PeerEvent()
data class Getchannel(val channelId: ByteVector32, val channel: CompletableDeferred<ChannelState?>) : PeerEvent()
data class PayToOpenResult(val payToOpenResponse: PayToOpenResponse) : PeerEvent()

sealed class PeerListenerEvent
data class PaymentRequestGenerated(val receivePayment: ReceivePayment, val request: String) : PeerListenerEvent()
data class PaymentReceived(val incomingPayment: IncomingPayment) : PeerListenerEvent()
data class PaymentProgress(val payment: SendPayment, val fees: MilliSatoshi) : PeerListenerEvent()
data class PaymentSent(val payment: SendPayment, val fees: MilliSatoshi) : PeerListenerEvent()
data class PaymentNotSent(val payment: SendPayment, val reason: OutgoingPaymentFailure) : PeerListenerEvent()

@OptIn(ExperimentalStdlibApi::class, ExperimentalCoroutinesApi::class)
class Peer(
    val socketBuilder: TcpSocket.Builder,
    val nodeParams: NodeParams,
    val remoteNodeId: PublicKey,
    val watcher: ElectrumWatcher,
    val channelsDb: ChannelsDb,
    scope: CoroutineScope
) : CoroutineScope by scope {
    companion object {
        private const val prefix: Byte = 0x00
        private val prologue = "lightning".encodeToByteArray()
    }

    private val input = Channel<PeerEvent>(BUFFERED)
    private val output = Channel<ByteArray>(BUFFERED)

    private val logger = newEclairLogger()

    private val channelsChannel = ConflatedBroadcastChannel<Map<ByteVector32, ChannelState>>(HashMap())

    private val _connectionState = MutableStateFlow(Connection.CLOSED)

    private val listenerEventChannel = BroadcastChannel<PeerListenerEvent>(BUFFERED)

    // channels map, indexed by channel id
    // note that a channel starts with a temporary id then switches to its final id once the funding tx is known
    private var channels by channelsChannel

    // encapsulates logic for validating incoming payments
    private val incomingPaymentHandler = IncomingPaymentHandler(nodeParams)

    // encapsulates logic for sending payments
    private val outgoingPaymentHandler = OutgoingPaymentHandler(nodeParams)

    private val features = Features(
        setOf(
            ActivatedFeature(Feature.OptionDataLossProtect, FeatureSupport.Optional),
            ActivatedFeature(Feature.VariableLengthOnion, FeatureSupport.Optional),
            ActivatedFeature(Feature.StaticRemoteKey, FeatureSupport.Optional),
            ActivatedFeature(Feature.PaymentSecret, FeatureSupport.Optional),
            ActivatedFeature(Feature.BasicMultiPartPayment, FeatureSupport.Optional),
            ActivatedFeature(Feature.Wumbo, FeatureSupport.Optional),
        )
    )

    private val ourInit = Init(features.toByteArray().toByteVector())
    private var theirInit: Init? = null
    private var currentTip: Pair<Int, BlockHeader> = Pair(0, Block.RegtestGenesisBlock.header)
    private var onchainFeerates = OnchainFeerates(750, 750, 750, 750, 750)

    init {
        val electrumNotificationsChannel = watcher.client.openNotificationsSubscription()
        launch {
            electrumNotificationsChannel.consumeAsFlow().filterIsInstance<HeaderSubscriptionResponse>()
                .collect { msg ->
                    currentTip = msg.height to msg.header
                    send(WrappedChannelEvent(ByteVector32.Zeroes, NewBlock(msg.height, msg.header)))
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
                input.send(WrappedChannelEvent(it.channelId, fr.acinq.eclair.channel.WatchReceived(it)))
            }
        }
        launch {
            // we don't restore closed channels
            channelsDb.listLocalChannels().filterNot { it is Closed }.forEach {
                logger.info { "restoring $it" }
                val state = WaitForInit(StaticParams(nodeParams, remoteNodeId), currentTip, onchainFeerates)
                val (state1, actions) = state.process(Restore(it as ChannelState))
                processActions(it.channelId, actions)
                channels = channels + (it.channelId to state1)
            }
            logger.info { "restored channels: $channels" }
            run()
        }

        watcher.client.sendMessage(AskForStatusUpdate)
    }

    fun connect(address: String, port: Int) {
        launch {
            logger.info { "connecting to {$remoteNodeId}@{$address}" }
            _connectionState.value = Connection.ESTABLISHING
            val socket = try {
                socketBuilder.connect(address, port)
            } catch (ex: TcpSocket.IOException) {
                logger.warning { ex.message }
                _connectionState.value = Connection.CLOSED
                return@launch
            }
            val priv = nodeParams.keyManager.nodeKey.privateKey
            val pub = priv.publicKey()
            val keyPair = Pair(pub.value.toByteArray(), priv.value.toByteArray())
            val (enc, dec, ck) = try {
                handshake(
                    keyPair,
                    remoteNodeId.value.toByteArray(),
                    { s -> socket.receiveFully(s) },
                    { b -> socket.send(b) })
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
            logger.info { "sending init ${LightningMessage.encode(ourInit)!!}" }
            send(LightningMessage.encode(ourInit)!!)

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

    val connectionState: StateFlow<Connection> get() = _connectionState
    fun openChannelsSubscription() = channelsChannel.openSubscription()
    fun openListenerEventSubscription() = listenerEventChannel.openSubscription()

    private suspend fun sendToPeer(msg: LightningMessage) {
        val encoded = LightningMessage.encode(msg)
        encoded?.let { bin ->
            logger.info { "sending ${msg} encoded as ${Hex.encode(bin)}" }
            output.send(bin)
        }
    }

    private suspend fun processActions(channelId: ByteVector32, actions: List<ChannelAction>) {
        var actualChannelId: ByteVector32 = channelId
        actions.forEach { action ->
            when {
                action is SendMessage && _connectionState.value == Connection.ESTABLISHED -> sendToPeer(action.message)
                // sometimes channel actions include "self" command (such as CMD_SIGN)
                action is SendToSelf -> input.send(WrappedChannelEvent(actualChannelId, ExecuteCommand(action.command)))
                action is ProcessLocalFailure -> input.send(WrappedChannelError(actualChannelId, action.error, action.trigger))
                action is SendWatch -> watcher.watch(action.watch)
                action is PublishTx -> watcher.publish(action.tx)
                action is ProcessAdd -> processIncomingPayment(Either.Right(action.add))
                action is ProcessRemoteFailure -> {
                    val result = outgoingPaymentHandler.processRemoteFailure(action, channels, currentTip.first)
                    when (result) {
                        is OutgoingPaymentHandler.ProcessFailureResult.Progress -> {
                            listenerEventChannel.send(PaymentProgress(result.payment, result.trampolineFees))
                            for (subaction in result.actions) {
                                input.send(subaction)
                            }
                        }
                        is OutgoingPaymentHandler.ProcessFailureResult.Failure -> {
                            listenerEventChannel.send(PaymentNotSent(result.payment, result.failure))
                        }
                        is OutgoingPaymentHandler.ProcessFailureResult.UnknownPaymentFailure -> {
                            logger.error { "UnknownPaymentFailure" }
                        }
                    }
                }
                action is ProcessFulfill -> {
                    val result = outgoingPaymentHandler.processFulfill(action)
                    when (result) {
                        is OutgoingPaymentHandler.ProcessFulfillResult.Success -> {
                            listenerEventChannel.send(PaymentSent(result.payment, result.trampolineFees))
                        }
                        is OutgoingPaymentHandler.ProcessFulfillResult.Failure -> {
                            listenerEventChannel.send(PaymentNotSent(result.payment, result.failure))
                        }
                        is OutgoingPaymentHandler.ProcessFulfillResult.UnknownPaymentFailure -> {
                            logger.error { "UnknownPaymentFailure" }
                        }
                    }
                }
                action is StoreState -> {
                    logger.info { "storing state for channelId=$actualChannelId data=${action.data}" }
                    channelsDb.addOrUpdateChannel(action.data)
                }
                action is ChannelIdSwitch -> {
                    logger.info { "switching channel id from ${action.oldChannelId} to ${action.newChannelId}" }
                    actualChannelId = action.newChannelId
                    channels[action.oldChannelId]?.let {
                        channels = channels + (action.newChannelId to it)
                    }
                }
                else -> {
                    logger.warning { "unhandled action : $action" }
                    Unit
                }
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
        for (subaction in result.actions) {
            input.send(subaction)
        }
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
                logger.verbose { "received $msg" }
                when {
                    msg is Init -> {
                        logger.info { "received $msg" }
                        logger.info { "peer is using features ${Features(msg.features)}" }
                        theirInit = msg
                        _connectionState.value = Connection.ESTABLISHED
                        logger.info { "before channels: $channels" }
                        channels = channels.mapValues { entry ->
                            val (state1, actions) = entry.value.process(Connected(ourInit, theirInit!!))
                            processActions(entry.key, actions)
                            state1
                        }
                        logger.info { "after channels: $channels" }
                    }
                    msg is Ping -> {
                        val pong = Pong(ByteVector(ByteArray(msg.pongLength)))
                        output.send(LightningMessage.encode(pong)!!)
                    }
                    msg is Pong -> {
                    }
                    msg is Error && msg.channelId == ByteVector32.Zeroes -> {
                        logger.error { "connection error, failing all channels: ${msg.toAscii()}" }
                    }
                    msg is OpenChannel && theirInit == null -> {
                        logger.error { "they sent open_channel before init" }
                    }
                    msg is OpenChannel && channels.containsKey(msg.temporaryChannelId) -> {
                        logger.warning { "ignoring open_channel with duplicate temporaryChannelId=${msg.temporaryChannelId}" }
                    }
                    msg is OpenChannel -> {
                        val fundingKeyPath = KeyPath("/1/2/3")
                        val fundingPubkey = nodeParams.keyManager.fundingPublicKey(fundingKeyPath)
                        val (closingPubkey, closingPubkeyScript) = nodeParams.keyManager.closingPubkeyScript(fundingPubkey.publicKey)
                        val walletStaticPaymentBasepoint: PublicKey? = if (Features.canUseFeature(features, Features(theirInit!!.features), Feature.StaticRemoteKey)) {
                            closingPubkey
                        } else null
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
                            onchainFeerates
                        )
                        val (state1, actions1) = state.process(
                            InitFundee(
                                msg.temporaryChannelId,
                                localParams,
                                theirInit!!
                            )
                        )
                        val (state2, actions2) = state1.process(MessageReceived(msg))
                        processActions(msg.temporaryChannelId, actions1 + actions2)
                        channels = channels + (msg.temporaryChannelId to state2)
                        logger.info { "new state for ${msg.temporaryChannelId}: $state2" }
                    }
                    msg is ChannelReestablish && !channels.containsKey(msg.channelId) -> {
                        if (msg.channelData.isEmpty()) {
                            sendToPeer(Error(msg.channelId, "unknown channel"))
                        } else {
                            when (val decrypted = runTrying { Helpers.decrypt(nodeParams.nodePrivateKey, msg.channelData) }) {
                                is Try.Success -> {
                                    logger.warning { "restoring channelId=${msg.channelId} from peer backup" }
                                    val backup = decrypted.result
                                    val state = WaitForInit(StaticParams(nodeParams, remoteNodeId), currentTip, onchainFeerates)
                                    val event1 = Restore(backup as ChannelState)
                                    val (state1, actions1) = state.process(event1)
                                    processActions(msg.channelId, actions1)

                                    val event2 = Connected(ourInit, theirInit!!)
                                    val (state2, actions2) = state1.process(event2)
                                    processActions(msg.channelId, actions2)

                                    val event3 = MessageReceived(msg)
                                    val (state3, actions3) = state2.process(event3)
                                    processActions(msg.channelId, actions3)
                                    channels = channels + (msg.channelId to state3)
                                }
                                is Try.Failure -> {
                                    logger.error(decrypted.error) { "failed to restore channelId=${msg.channelId}" }
                                }
                            }
                        }
                    }
                    msg is HasTemporaryChannelId && !channels.containsKey(msg.temporaryChannelId) -> {
                        logger.error { "received $msg for unknown temporary channel ${msg.temporaryChannelId}" }
                        sendToPeer(Error(msg.temporaryChannelId, "unknown channel"))
                    }
                    msg is HasTemporaryChannelId -> {
                        logger.info { "received $msg for temporary channel ${msg.temporaryChannelId}" }
                        val state = channels[msg.temporaryChannelId]!!
                        val event1 = MessageReceived(msg)
                        val (state1, actions) = state.process(event1)
                        channels = channels + (msg.temporaryChannelId to state1)
                        logger.info { "channel ${msg.temporaryChannelId} new state $state1" }
                        processActions(msg.temporaryChannelId, actions)
                        actions.filterIsInstance<ChannelIdSwitch>().forEach {
                            logger.info { "id switch from ${it.oldChannelId} to ${it.newChannelId}" }
                            channels = channels - it.oldChannelId + (it.newChannelId to state1)
                        }
                    }
                    msg is HasChannelId && !channels.containsKey(msg.channelId) -> {
                        logger.error { "received $msg for unknown channel ${msg.channelId}" }
                        sendToPeer(Error(msg.channelId, "unknown channel"))
                    }
                    msg is HasChannelId -> {
                        logger.info { "received $msg for channel ${msg.channelId}" }
                        val state = channels[msg.channelId]!!
                        val event1 = MessageReceived(msg)
                        val (state1, actions) = state.process(event1)
                        channels = channels + (msg.channelId to state1)
                        logger.info { "channel ${msg.channelId} new state $state1" }
                        processActions(msg.channelId, actions)
                    }
                    msg is PayToOpenRequest -> {
                        logger.info { "received pay-to-open request" }
                        processIncomingPayment(Either.Left(msg))
                    }
                    else -> logger.warning { "received unhandled message ${Hex.encode(event.data)}" }
                }
            } // event is ByteReceived
            event is WatchReceived && !channels.containsKey(event.watch.channelId) -> {
                logger.error { "received watch event ${event.watch} for unknown channel ${event.watch.channelId}}" }
            }
            event is WatchReceived -> {
                val state = channels[event.watch.channelId]!!
                val event1 = fr.acinq.eclair.channel.WatchReceived(event.watch)
                val (state1, actions) = state.process(event1)
                processActions(event.watch.channelId, actions)
                channels = channels + (event.watch.channelId to state1)
                logger.info { "channel ${event.watch.channelId} new state $state1" }
            } // event is WatchReceived
            //
            // receive payments
            //
            event is ReceivePayment -> {
                logger.info { "creating invoice $event" }
                val pr = incomingPaymentHandler.createInvoice(event.paymentPreimage, event.amount, event.description)
                logger.info { "payment request ${pr.write()}" }
                listenerEventChannel.send(PaymentRequestGenerated(event, pr.write()))
                event.result.complete(pr)
            }
            //
            // send payments
            //
            event is SendPayment -> {
                val result = outgoingPaymentHandler.sendPayment(event, channels, currentTip.first)
                when (result) {
                    is OutgoingPaymentHandler.SendPaymentResult.Progress -> {
                        listenerEventChannel.send(PaymentProgress(result.payment, result.trampolineFees))
                        for (action in result.actions) {
                            input.send(action)
                        }
                    }
                    is OutgoingPaymentHandler.SendPaymentResult.Failure -> {
                        listenerEventChannel.send(PaymentNotSent(result.payment, result.failure))
                    }
                }
            }
            event is WrappedChannelEvent && event.channelId == ByteVector32.Zeroes -> {
                // this is for all channels
                channels.forEach { (key, value) ->
                    val (state1, actions) = value.process(event.channelEvent)
                    processActions(key, actions)
                    channels = channels + (key to state1)
                }
            }
            event is WrappedChannelEvent && !channels.containsKey(event.channelId) -> {
                logger.error { "received ${event.channelEvent} for a unknown channel ${event.channelId}" }
            }
            event is WrappedChannelEvent -> {
                val state = channels[event.channelId]!!
                val (state1, actions) = state.process(event.channelEvent)
                processActions(event.channelId, actions)
                channels = channels + (event.channelId to state1)
            }
            event is WrappedChannelError -> {
                val result = outgoingPaymentHandler.processLocalFailure(event, channels, currentTip.first)
                when (result) {
                    is OutgoingPaymentHandler.ProcessFailureResult.Progress -> {
                        listenerEventChannel.send(PaymentProgress(result.payment, result.trampolineFees))
                        for (action in result.actions) {
                            input.send(action)
                        }
                    }
                    is OutgoingPaymentHandler.ProcessFailureResult.Failure -> {
                        listenerEventChannel.send(PaymentNotSent(result.payment, result.failure))
                    }
                    is OutgoingPaymentHandler.ProcessFailureResult.UnknownPaymentFailure -> {
                        logger.error { "UnknownPaymentFailure" }
                    }
                    null -> {
                        // error that didn't affect an outgoing payment
                    }
                }
            }
            event is PayToOpenResult -> {
                logger.info { "sending pay-to-open response" }
                sendToPeer(event.payToOpenResponse)
            }
            event is CheckPaymentsTimeout -> {
                val actions = incomingPaymentHandler.checkPaymentsTimeout(currentTimestampSeconds())
                actions.forEach { input.send(it) }
            }
            event is ListChannels -> {
                event.channels.complete(channels.values.toList())
            }
            event is Getchannel -> {
                event.channel.complete(channels[event.channelId])
            }
        }
    }
}
