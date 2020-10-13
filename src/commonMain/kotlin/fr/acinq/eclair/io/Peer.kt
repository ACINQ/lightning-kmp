package fr.acinq.eclair.io

import fr.acinq.bitcoin.*
import fr.acinq.eclair.*
import fr.acinq.eclair.blockchain.WatchEvent
import fr.acinq.eclair.blockchain.electrum.AskForHeaderSubscriptionUpdate
import fr.acinq.eclair.blockchain.electrum.AskForStatusUpdate
import fr.acinq.eclair.blockchain.electrum.ElectrumWatcher
import fr.acinq.eclair.blockchain.electrum.HeaderSubscriptionResponse
import fr.acinq.eclair.channel.*
import fr.acinq.eclair.crypto.noise.*
import fr.acinq.eclair.db.ChannelsDb
import fr.acinq.eclair.payment.*
import fr.acinq.eclair.router.ChannelHop
import fr.acinq.eclair.utils.*
import fr.acinq.eclair.wire.*
import fr.acinq.secp256k1.Hex
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BroadcastChannel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ConflatedBroadcastChannel
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterIsInstance
import org.kodein.log.Logger
import org.kodein.log.LoggerFactory

sealed class PeerEvent
data class BytesReceived(val data: ByteArray) : PeerEvent()
data class WatchReceived(val watch: WatchEvent) : PeerEvent()
data class ReceivePayment(val paymentPreimage: ByteVector32, val amount: MilliSatoshi?, val expiry: CltvExpiry, val description: String) : PeerEvent() {
    val paymentHash = Crypto.sha256(paymentPreimage).toByteVector32()
}

data class SendPayment(val paymentId: UUID, val paymentRequest: PaymentRequest) : PeerEvent()
data class WrappedChannelEvent(val channelId: ByteVector32, val channelEvent: ChannelEvent) : PeerEvent()
object CheckPaymentsTimeout: PeerEvent()

sealed class PeerListenerEvent
data class PaymentRequestGenerated(val receivePayment: ReceivePayment, val request: String) : PeerListenerEvent()
data class PaymentReceived(val incomingPayment: IncomingPayment) : PeerListenerEvent()
data class SendingPayment(val paymentId: UUID, val paymentRequest: PaymentRequest) : PeerListenerEvent()
data class PaymentSent(val paymentId: UUID, val paymentRequest: PaymentRequest) : PeerListenerEvent()

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
        private val prefix: Byte = 0x00
        private val prologue = "lightning".encodeToByteArray()
    }

    private val input = Channel<PeerEvent>(10)
    private val output = Channel<ByteArray>(3)

    private val logger = newEclairLogger()

    private val channelsChannel = ConflatedBroadcastChannel<Map<ByteVector32, ChannelState>>(HashMap())

    private val connectedChannel = ConflatedBroadcastChannel(Connection.CLOSED)
    private val listenerEventChannel = BroadcastChannel<PeerListenerEvent>(Channel.BUFFERED)

    // channels map, indexed by channel id
    // note that a channel starts with a temporary id then switches to its final id once the funding tx is known
    private var channels by channelsChannel
    private var connected by connectedChannel

    // pending incoming payments, indexed by payment hash
    private val pendingIncomingPayments: HashMap<ByteVector32, IncomingPayment> = HashMap()

    // pending outgoing payments, indexed by payment hash
    private val pendingOutgoingPayments: HashMap<ByteVector32, SendPayment> = HashMap()

    // encapsulates logic for validating payments
    private val paymentHandler = PaymentHandler(nodeParams)

    // encapsulates logic for sending payments
    private val paymentLifecycle = PaymentLifecycle(nodeParams)

    private val features = Features(
        setOf(
            ActivatedFeature(Feature.OptionDataLossProtect, FeatureSupport.Optional),
            ActivatedFeature(Feature.VariableLengthOnion, FeatureSupport.Optional),
            ActivatedFeature(Feature.PaymentSecret, FeatureSupport.Optional),
        )
    )
    private val ourInit = Init(features.toByteArray().toByteVector())
    private var theirInit: Init? = null
    private var currentTip: Pair<Int, BlockHeader> = Pair(0, Block.RegtestGenesisBlock.header)

    init {
        val electrumConnectedChannel = watcher.client.openConnectedSubscription()
        val electrumNotificationsChannel = watcher.client.openNotificationsSubscription()
        launch {
            electrumNotificationsChannel.consumeAsFlow().filterIsInstance<HeaderSubscriptionResponse>()
                .collect { msg ->
                    currentTip = msg.height to msg.header
                    send(WrappedChannelEvent(ByteVector32.Zeroes, NewBlock(msg.height, msg.header)))
                }
        }
        launch {
            electrumConnectedChannel.consumeAsFlow().filter { it == Connection.ESTABLISHED }.collect {
                watcher.client.sendMessage(AskForHeaderSubscriptionUpdate)
            }
        }
        launch {
            channelsDb.listLocalChannels().forEach {
                logger.info { "restoring $it" }
                val state = WaitForInit(StaticParams(nodeParams, remoteNodeId), currentTip)
                val (state1, actions) = state.process(Restore(it as ChannelState))
                send(actions)
                channels = channels + (it.channelId to state1)
            }
            logger.info { "restored channels: $channels" }
        }

        launch { run() }

        watcher.client.sendMessage(AskForStatusUpdate)
    }

    fun connect(address: String, port: Int) {
        launch {
            logger.info { "connecting to {$remoteNodeId}@{$address}" }
            connected = Connection.ESTABLISHING
            val socket = try {
                socketBuilder.connect(address, port)
            } catch (ex: TcpSocket.IOException) {
                logger.warning { ex.message }
                connected = Connection.CLOSED
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
                connected = Connection.CLOSED
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
                    connected = Connection.CLOSED
                }
            }

            suspend fun respond() {
                for (msg in output) {
                    send(msg)
                }
            }

            coroutineScope {
                launch {
                    val sub = watcher.openNotificationsSubscription()
                    sub.consumeEach {
                        logger.info { "notification: $it" }
                        input.send(WrappedChannelEvent(it.channelId, fr.acinq.eclair.channel.WatchReceived(it)))
                    }
                }
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

    fun openChannelsSubscription() = channelsChannel.openSubscription()
    fun openConnectedSubscription() = connectedChannel.openSubscription()
    fun openListenerEventSubscription() = listenerEventChannel.openSubscription()

    private suspend fun send(actions: List<ChannelAction>) {
        actions.forEach {
            when {
                it is SendMessage -> {
                    val encoded = LightningMessage.encode(it.message)
                    encoded?.let { bin ->
                        logger.info { "sending ${it.message} encoded as ${Hex.encode(bin)}" }
                        output.send(bin)
                    }
                }
                it is SendWatch -> watcher.watch(it.watch)
                else -> Unit
            }
        }
    }

    /**
     * sometimes channel actions include "self" command (such as CMD_SIGN)
     */
    private suspend fun sendToSelf(channelId: ByteVector32, actions: List<ChannelAction>) {
        actions.filterIsInstance<ProcessCommand>().forEach { input.send(WrappedChannelEvent(channelId, ExecuteCommand(it.command))) }
    }

    private suspend fun store(actions: List<ChannelAction>) {
        val actions1 = actions.filterIsInstance<StoreState>()
        if (actions1.isEmpty()) return
        val state = actions1.last().data
        logger.info { "storing $state" }
        channelsDb.addOrUpdateChannel(state as HasCommitments)
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
                logger.info { "received $msg" }
                when {
                    msg is Init -> {
                        logger.info { "received $msg" }
                        theirInit = msg
                        connected = Connection.ESTABLISHED
                        logger.info { "before channels: $channels" }
                        channels = channels.mapValues { entry ->
                            val (state1, actions) = entry.value.process(Connected(ourInit, theirInit!!))
                            send(actions)
                            state1
                        }
                        logger.info { "after channels: $channels" }
                    }
                    msg is Ping -> {
                        logger.info { "received $msg" }
                        val pong = Pong(ByteVector(ByteArray(msg.pongLength)))
                        output.send(LightningMessage.encode(pong)!!)

                    }
                    msg is Pong -> {
                        logger.info { "received $msg" }
                    }
                    msg is Error && msg.channelId == ByteVector32.Zeroes -> {
                        logger.error { "connection error, failing all channels: ${msg.toAscii()}" }
                    }
                    msg is OpenChannel -> {
                        val localParams = LocalParams(
                            nodeParams.nodeId,
                            KeyPath("/1/2/3"),
                            nodeParams.dustLimit,
                            nodeParams.maxHtlcValueInFlightMsat,
                            Satoshi(600),
                            nodeParams.htlcMinimum,
                            nodeParams.toRemoteDelayBlocks,
                            nodeParams.maxAcceptedHtlcs,
                            false,
                            ByteVector.empty,
                            PrivateKey(ByteVector32("0101010101010101010101010101010101010101010101010101010101010101")).publicKey(),
                            Features(
                                setOf(
                                    ActivatedFeature(Feature.OptionDataLossProtect, FeatureSupport.Mandatory),
                                    ActivatedFeature(Feature.VariableLengthOnion, FeatureSupport.Optional),
                                    ActivatedFeature(Feature.StaticRemoteKey, FeatureSupport.Optional),
                                    ActivatedFeature(Feature.PaymentSecret, FeatureSupport.Optional),
                                    ActivatedFeature(Feature.BasicMultiPartPayment, FeatureSupport.Optional),
                                    ActivatedFeature(Feature.Wumbo, FeatureSupport.Optional),
                                    ActivatedFeature(Feature.TrampolinePayment, FeatureSupport.Optional),
                                )
                            )
                        )
                        val state = WaitForInit(
                            StaticParams(nodeParams, remoteNodeId),
                            Pair(0, Block.RegtestGenesisBlock.header)
                        )
                        val (state1, actions1) = state.process(
                            InitFundee(
                                msg.temporaryChannelId,
                                localParams,
                                theirInit!!
                            )
                        )
                        val (state2, actions2) = state1.process(MessageReceived(msg))
                        send(actions1 + actions2)
                        channels = channels + (msg.temporaryChannelId to state2)
                        logger.info { "new state for ${msg.temporaryChannelId}: $state2" }
                    }
                    msg is ChannelReestablish && !channels.containsKey(msg.channelId) -> {
                        if (msg.channelData.isEmpty()) {
                            send(listOf(SendMessage(Error(msg.channelId, "unknown channel"))))
                        } else {
                            when (val decrypted = runTrying { Helpers.decrypt(nodeParams.nodePrivateKey, msg.channelData) }) {
                                is Try.Success -> {
                                    logger.warning { "restoring channelId=${msg.channelId} from peer backup" }
                                    val backup = decrypted.result
                                    val state = WaitForInit(StaticParams(nodeParams, remoteNodeId), currentTip)
                                    val (state1, actions1) = state.process(Restore(backup as ChannelState))
                                    send(actions1)
                                    store(actions1)
                                    sendToSelf(msg.channelId, actions1)

                                    val (state2, actions2) = state1.process(Connected(ourInit, theirInit!!))
                                    send(actions2)
                                    store(actions2)
                                    sendToSelf(msg.channelId, actions2)

                                    val (state3, actions3) = state2.process(MessageReceived(msg))
                                    send(actions3)
                                    store(actions3)
                                    sendToSelf(msg.channelId, actions3)
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
                        send(listOf(SendMessage(Error(msg.temporaryChannelId, "unknown channel"))))
                    }
                    msg is HasTemporaryChannelId -> {
                        logger.info { "received $msg for temporary channel ${msg.temporaryChannelId}" }
                        val state = channels[msg.temporaryChannelId]!!
                        val (state1, actions) = state.process(MessageReceived(msg))
                        channels = channels + (msg.temporaryChannelId to state1)
                        logger.info { "channel ${msg.temporaryChannelId} new state $state1" }
                        send(actions)
                        store(actions)
                        sendToSelf(msg.temporaryChannelId, actions)
                        actions.filterIsInstance<ChannelIdSwitch>().forEach {
                            logger.info { "id switch from ${it.oldChannelId} to ${it.newChannelId}" }
                            channels = channels - it.oldChannelId + (it.newChannelId to state1)
                        }
                    }
                    msg is HasChannelId && !channels.containsKey(msg.channelId) -> {
                        logger.error { "received $msg for unknown channel ${msg.channelId}" }
                        send(listOf(SendMessage(Error(msg.channelId, "unknown channel"))))
                    }
                    msg is HasChannelId -> {
                        logger.info { "received $msg for channel ${msg.channelId}" }
                        val state = channels[msg.channelId]!!
                        val (state1, actions) = state.process(MessageReceived(msg))
                        channels = channels + (msg.channelId to state1)
                        logger.info { "channel ${msg.channelId} new state $state1" }
                        send(actions)
                        store(actions)
                        sendToSelf(msg.channelId, actions)
                        actions.forEach {
                            when {
                                it is ProcessAdd -> {
                                    val htlc = it.add
                                    val incomingPayment = pendingIncomingPayments[htlc.paymentHash]

                                    val result = paymentHandler.processAdd(htlc, incomingPayment, state1.currentBlockHeight)

                                    if (result.status == PaymentHandler.ProcessedStatus.ACCEPTED ||
                                        result.status == PaymentHandler.ProcessedStatus.REJECTED) {
                                        pendingIncomingPayments.remove(htlc.paymentHash)
                                    }
                                    if (result.status == PaymentHandler.ProcessedStatus.ACCEPTED) {
                                        listenerEventChannel.send(PaymentReceived(incomingPayment!!))
                                    }
                                    for (action in result.actions) {
                                        input.send(action)
                                    }
                                }
                                it is ProcessFail || it is ProcessFailMalformed -> {
                                    paymentLifecycle.processFailure(it, channels, currentTip.first)?.let { result ->

                                        if (result.status == PaymentLifecycle.Status.FAILED) {
                                        //  listenerEventChannel.send(PaymentNotSent(result.id))
                                        }
                                        result.actions.forEach { input.send(it) }
                                    }
                                }
                                it is ProcessFulfill -> {
                                    paymentLifecycle.processFulfill(it)?.let { result ->

                                        if (result.status == PaymentLifecycle.Status.SUCCEEDED) {
                                        //  listenerEventChannel.send(PaymentSent(result.id, result.invoice))
                                        }
                                        result.actions.forEach { input.send(it) }
                                    }
                                }
                            }
                        }
                    }
                    else -> logger.warning { "received unhandled message ${Hex.encode(event.data)}" }
                }
            } // event is ByteReceived
            event is WatchReceived && !channels.containsKey(event.watch.channelId) -> {
                logger.error { "received watch event ${event.watch} for unknown channel ${event.watch.channelId}}" }
            }
            event is WatchReceived -> {
                val state = channels[event.watch.channelId]!!
                val (state1, actions) = state.process(fr.acinq.eclair.channel.WatchReceived(event.watch))
                send(actions)
                store(actions)
                sendToSelf(event.watch.channelId, actions)
                channels = channels + (event.watch.channelId to state1)
                logger.info { "channel ${event.watch.channelId} new state $state1" }
            } // event is WatchReceived
            //
            // receive payments
            //
            event is ReceivePayment -> {
                logger.info { "expecting to receive $event for payment hash ${event.paymentHash}" }
                val invoiceFeatures = mutableSetOf(ActivatedFeature(Feature.VariableLengthOnion, FeatureSupport.Optional), ActivatedFeature(Feature.PaymentSecret, FeatureSupport.Mandatory))
                if (nodeParams.features.hasFeature(Feature.BasicMultiPartPayment)) {
                    invoiceFeatures.add(ActivatedFeature(Feature.BasicMultiPartPayment, FeatureSupport.Optional))
                }
                val pr = PaymentRequest.create(nodeParams.chainHash, event.amount, event.paymentHash, nodeParams.nodePrivateKey, event.description, PaymentRequest.DEFAULT_MIN_FINAL_EXPIRY_DELTA, Features(invoiceFeatures))
                logger.info { "payment request ${pr.write()}" }
                pendingIncomingPayments[event.paymentHash] = IncomingPayment(pr, event.paymentPreimage)
                listenerEventChannel.send(PaymentRequestGenerated(event, pr.write()))
            }
            //
            // send payments
            //
            event is SendPayment -> {
                val result = paymentLifecycle.processSendPayment(event, channels, currentTip.first)

                if (result.status == PaymentLifecycle.Status.INFLIGHT) {
                    // I don't think `pendingOutgoingPayments` is needed anymore...
                    pendingOutgoingPayments[event.paymentRequest.paymentHash] = event
                    listenerEventChannel.send(SendingPayment(event.paymentId, event.paymentRequest))
                }
                for (action in result.actions) {
                    input.send(action)
                }
            }
            event is WrappedChannelEvent && event.channelId == ByteVector32.Zeroes -> {
                // this is for all channels
                channels.forEach { (key, value) ->
                    val (state1, actions) = value.process(event.channelEvent)
                    send(actions)
                    store(actions)
                    sendToSelf(key, actions)
                    channels = channels + (key to state1)
                }
            }
            event is WrappedChannelEvent && !channels.containsKey(event.channelId) -> {
                logger.error { "received ${event.channelEvent} for a unknown channel ${event.channelId}" }
            }
            event is WrappedChannelEvent -> {
                val state = channels[event.channelId]!!
                val (state1, actions) = state.process(event.channelEvent)
                channels = channels + (event.channelId to state1)
                send(actions)
                store(actions)
                sendToSelf(event.channelId, actions)
            }
            event is CheckPaymentsTimeout -> {
                val actions = paymentHandler.checkPaymentsTimeout(currentTimestampSeconds())
                actions.forEach { input.send(it) }
            }
        }
    }
}