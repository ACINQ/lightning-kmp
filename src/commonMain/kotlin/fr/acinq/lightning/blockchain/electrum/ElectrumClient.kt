package fr.acinq.lightning.blockchain.electrum

import fr.acinq.bitcoin.BlockHeader
import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.Crypto
import fr.acinq.lightning.blockchain.electrum.ElectrumClient.Companion.logger
import fr.acinq.lightning.blockchain.electrum.ElectrumClient.Companion.version
import fr.acinq.lightning.io.TcpSocket
import fr.acinq.lightning.io.linesFlow
import fr.acinq.lightning.io.send
import fr.acinq.lightning.utils.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BroadcastChannel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.Channel.Factory.BUFFERED
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filter
import kotlinx.serialization.json.Json
import kotlin.native.concurrent.ThreadLocal
import kotlin.time.Duration
import kotlin.time.ExperimentalTime
import kotlin.time.seconds


/*
    Events
 */
internal sealed class ClientEvent
internal object Connected : ClientEvent()
internal object Disconnected : ClientEvent()
internal data class ReceivedResponse(val response: Either<ElectrumResponse, JsonRPCResponse>) : ClientEvent()
internal data class SendElectrumApiCall(val electrumRequest: ElectrumRequest) : ClientEvent()
internal object AskForHeader : ClientEvent()

/*
    Actions
 */
internal sealed class ElectrumClientAction
internal data class SendRequest(val request: String) : ElectrumClientAction()
internal data class SendHeader(val height: Int, val blockHeader: BlockHeader) : ElectrumClientAction()
internal data class SendResponse(val response: ElectrumResponse) : ElectrumClientAction()
internal data class BroadcastStatus(val connection: Connection) : ElectrumClientAction()

/**
 * [ElectrumClient] State
 *
 * +--> ClientClosed ----> WaitingForConnection ----+
 * |                                                |
 * |                                                |
 * +-- Running <-- WaitForTip <--- WaitForVersion --+
 *     ^     |
 *     +-----+
 *
 */
internal sealed class ClientState {
    abstract fun process(event: ClientEvent): Pair<ClientState, List<ElectrumClientAction>>
}

@ObsoleteCoroutinesApi
internal object WaitingForVersion : ClientState() {
    override fun process(event: ClientEvent): Pair<ClientState, List<ElectrumClientAction>> = when {
        event is ReceivedResponse && event.response is Either.Right -> {
            when (parseJsonResponse(version, event.response.value)) {
                is ServerVersionResponse -> newState {
                    state = WaitingForTip
                    actions = listOf(SendRequest(HeaderSubscription.asJsonRPCRequest()))
                }
                is ServerError -> newState {
                    state = ClientClosed
                    actions = listOf(BroadcastStatus(Connection.CLOSED(null)))
                }
                else -> returnState() // TODO handle error?
            }
        }
        else -> unhandled(event)
    }
}

@ObsoleteCoroutinesApi
internal object WaitingForTip : ClientState() {
    override fun process(event: ClientEvent): Pair<ClientState, List<ElectrumClientAction>> =
        when (event) {
            is ReceivedResponse -> {
                when (val rpcResponse = event.response) {
                    is Either.Right ->
                        when (val response = parseJsonResponse(HeaderSubscription, rpcResponse.value)) {
                            is HeaderSubscriptionResponse -> newState {
                                state = ClientRunning(height = response.height, tip = response.header)
                                actions = listOf(BroadcastStatus(Connection.ESTABLISHED), SendResponse(response))
                            }
                            else -> returnState()
                        }
                    else -> returnState()
                }
            }
            else -> unhandled(event)
        }
}

@ObsoleteCoroutinesApi
internal data class ClientRunning(
    val height: Int,
    val tip: BlockHeader,
    val requests: MutableMap<Int, ElectrumRequest> = mutableMapOf(),
    val responseTimestamps: MutableMap<Int, Long?> = mutableMapOf()
) : ClientState() {

    override fun process(event: ClientEvent): Pair<ClientState, List<ElectrumClientAction>> = when (event) {
        is AskForHeader -> returnState(SendHeader(height, tip))
        is SendElectrumApiCall -> returnState(sendRequest(event.electrumRequest))
        is ReceivedResponse -> when (val response = event.response) {
            is Either.Left -> when (val electrumResponse = response.value) {
                is HeaderSubscriptionResponse -> newState {
                    state = copy(height = electrumResponse.height, tip = electrumResponse.header)
                    actions = listOf(SendResponse(electrumResponse))
                }
                is ScriptHashSubscriptionResponse -> returnState(SendResponse(electrumResponse))
                else -> returnState()
            }
            is Either.Right -> {
                requests[response.value.id]?.let { request ->
                    responseTimestamps[response.value.id!!] = currentTimestampMillis()
                    if (request !is Ping) {
                        returnState(SendResponse(parseJsonResponse(request, response.value)))
                    } else {
                        returnState()
                    }
                } ?: returnState()
            }
        }
        else -> unhandled(event)
    }

    private fun sendRequest(electrumRequest: ElectrumRequest): SendRequest {
        val newRequestId = requests.maxOfOrNull { it.key + 1 } ?: 0
        requests[newRequestId] = electrumRequest
        responseTimestamps[newRequestId] = null
        return SendRequest(electrumRequest.asJsonRPCRequest(newRequestId))
    }
}

@ObsoleteCoroutinesApi
internal object ClientClosed : ClientState() {
    override fun process(event: ClientEvent): Pair<ClientState, List<ElectrumClientAction>> =
        when (event) {
            Connected -> newState {
                state = WaitingForVersion
                actions = listOf(SendRequest(version.asJsonRPCRequest()))
            }
            else -> unhandled(event)
        }
}

@ObsoleteCoroutinesApi
private fun ClientState.unhandled(event: ClientEvent): Pair<ClientState, List<ElectrumClientAction>> =
    when (event) {
        Disconnected -> ClientClosed to emptyList()
        AskForHeader -> returnState() // TODO something else ?
        else -> {
            logger.warning { "cannot process event ${event::class} in state ${this::class}" }
            returnState()
        }
    }

@ObsoleteCoroutinesApi
private class ClientStateBuilder {
    var state: ClientState = ClientClosed
    var actions = emptyList<ElectrumClientAction>()
    fun build() = state to actions
}

@ObsoleteCoroutinesApi
private fun newState(init: ClientStateBuilder.() -> Unit) = ClientStateBuilder().apply(init).build()

private fun ClientState.returnState(actions: List<ElectrumClientAction> = emptyList()): Pair<ClientState, List<ElectrumClientAction>> = this to actions
private fun ClientState.returnState(action: ElectrumClientAction): Pair<ClientState, List<ElectrumClientAction>> = this to listOf(action)

data class RequestResponseTimestamp(
    val id: Int,
    val request: ElectrumRequest,
    val lastResponseTimestamp: Long?
)

@OptIn(ExperimentalCoroutinesApi::class, ExperimentalTime::class, ObsoleteCoroutinesApi::class)
class ElectrumClient(
    socketBuilder: TcpSocket.Builder?,
    scope: CoroutineScope
) : CoroutineScope by scope {

    var socketBuilder: TcpSocket.Builder? = socketBuilder
        set(value) {
            logger.debug { "swap socket builder=$value" }
            field = value
        }

    private val json = Json { ignoreUnknownKeys = true }

    private val eventChannel: Channel<ClientEvent> = Channel(BUFFERED)
    private var output: Channel<ByteArray> = Channel(BUFFERED)

    private val _connectionState = MutableStateFlow<Connection>(Connection.CLOSED(null))
    val connectionState: StateFlow<Connection> get() = _connectionState

    private val notificationsChannel = BroadcastChannel<ElectrumMessage>(BUFFERED)

    fun openNotificationsSubscription() = notificationsChannel.openSubscription()

    private var state: ClientState = ClientClosed

    private var runJob: Job? = null

    init {
        logger.info { "initializing electrum client" }
        launch {
            var previousState = connectionState.value
            connectionState.filter { it != previousState }.collect {
                logger.info { "connection state changed: $it" }
                if (it is Connection.CLOSED) eventChannel.send(Disconnected)
                previousState = it
            }
        }
        runJob = launch { run() }
    }

    private suspend fun run() {
        eventChannel.consumeEach { event ->

            val (newState, actions) = state.process(event)
            state = newState

            actions.forEach { action ->
                yield()
                when (action) {
                    is SendRequest -> if(!output.isClosedForSend) output.send(action.request.encodeToByteArray())
                    is SendHeader -> notificationsChannel.send(HeaderSubscriptionResponse(action.height, action.blockHeader))
                    is SendResponse -> notificationsChannel.send(action.response)
                    is BroadcastStatus -> if (_connectionState.value != action.connection) _connectionState.value = action.connection
                }
            }
        }
    }

    fun connect(serverAddress: ServerAddress) {
        if (_connectionState.value is Connection.CLOSED) establishConnection(serverAddress)
        else logger.warning { "electrum client is already running" }
    }

    fun disconnect() {
        if (this::socket.isInitialized) socket.close()
        output.close()
    }

    // Warning : lateinit vars have to be used AFTER their init to avoid any crashes
    //
    // This shouldn't be used outside the establishedConnection() function
    // Except from the disconnect() one that check if the lateinit var has been initialized
    private lateinit var socket: TcpSocket
    private fun establishConnection(serverAddress: ServerAddress) = launch {
        _connectionState.value = Connection.ESTABLISHING
        socket = try {
            val (host, port, tls) = serverAddress
            logger.info { "attempting connection to electrumx instance [host=$host, port=$port, tls=$tls]" }
            socketBuilder?.connect(host, port, tls) ?: error("socket builder is null.")
        } catch (ex: Throwable) {
            logger.warning { "TCP connect: ${ex.message}" }
            val ioException = when (ex) {
                is TcpSocket.IOException -> ex
                else -> TcpSocket.IOException.ConnectionRefused(ex)
            }
            _connectionState.value = Connection.CLOSED(ioException)
            return@launch
        }

        logger.info { "connected to electrumx instance" }
        eventChannel.send(Connected)

        fun closeSocket(ex: TcpSocket.IOException?) {
            if (_connectionState.value is Connection.CLOSED) return
            logger.warning { "closing TCP socket." }
            socket.close()
            _connectionState.value = Connection.CLOSED(ex)
            cancel()
        }

        suspend fun send(message: ByteArray) {
            try {
                socket.send(message)
            } catch (ex: TcpSocket.IOException) {
                logger.warning { "TCP send: ${ex.message}" }
                closeSocket(ex)
            }
        }

        suspend fun ping() {
            while (isActive) {
                delay(Duration.seconds(30))
                send(Ping.asJsonRPCRequest(-1).encodeToByteArray())
            }
        }

        suspend fun respond() {
            // Reset the output channel to avoid sending obsolete messages
            output = Channel(BUFFERED)
            for (msg in output) { send(msg) }
        }

        suspend fun listen() {
            try {
                socket.linesFlow().collect {
                    val electrumResponse = json.decodeFromString(ElectrumResponseDeserializer, it)
                    eventChannel.send(ReceivedResponse(electrumResponse))
                }
                closeSocket(null)
            } catch (ex: TcpSocket.IOException) {
                logger.warning { "TCP receive: ${ex.message}" }
                closeSocket(ex)
            }
        }

        launch { ping() }
        launch { respond() }

        listen() // This suspends until the coroutines is cancelled or the socket is closed
    }

    fun sendElectrumRequest(request: ElectrumRequest): Unit = sendMessage(SendElectrumRequest(request))

    fun sendMessage(message: ElectrumMessage) {
        launch {
            when (message) {
                is AskForHeaderSubscriptionUpdate -> eventChannel.send(AskForHeader)
                is SendElectrumRequest -> eventChannel.send(SendElectrumApiCall(message.electrumRequest))
                else -> logger.warning{ "sendMessage does not support message: ${message::class}" }
            }
        }
    }

    /**
     * Returns a list of requests, and when the most recent corresponding response was received.
     */
    fun requestResponseTimestamps(): List<RequestResponseTimestamp>? {
        val state = state
        if (state !is ClientRunning) {
            return null
        }
        return state.requests.mapNotNull { (id, request) ->
            state.responseTimestamps[id]?.let { lastResponseTimestamp ->
                RequestResponseTimestamp(id, request, lastResponseTimestamp)
            }
        }
    }

    fun stop() {
        logger.info { "electrum client stopping" }
        disconnect()
        // Cancel event consumer
        runJob?.cancel()
        // Cancel broadcast channels
        notificationsChannel.cancel()
        // Cancel event channel
        eventChannel.cancel()
    }

    @ThreadLocal
    companion object {
        const val ELECTRUM_CLIENT_NAME = "3.3.6"
        const val ELECTRUM_PROTOCOL_VERSION = "1.4"
        val logger by lightningLogger<ElectrumClient>()
        val version = ServerVersion()
        internal fun computeScriptHash(publicKeyScript: ByteVector): ByteVector32 = Crypto.sha256(publicKeyScript).toByteVector32().reversed()
    }
}
