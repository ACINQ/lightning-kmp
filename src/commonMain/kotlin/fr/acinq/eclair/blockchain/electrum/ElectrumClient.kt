package fr.acinq.eclair.blockchain.electrum

import fr.acinq.bitcoin.BlockHeader
import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.Crypto
import fr.acinq.eclair.blockchain.electrum.ElectrumClient.Companion.version
import fr.acinq.eclair.io.TcpSocket
import fr.acinq.eclair.io.linesFlow
import fr.acinq.eclair.utils.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BroadcastChannel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.serialization.json.Json
import org.kodein.log.newLogger
import kotlin.time.ExperimentalTime
import kotlin.time.seconds


/*
    Events
 */
internal sealed class ClientEvent
internal data class Start(val serverAddress: ServerAddress) : ClientEvent()
internal object Connected : ClientEvent()
internal object Disconnected : ClientEvent()
internal data class ReceivedResponse(val response: Either<ElectrumResponse, JsonRPCResponse>) : ClientEvent()
internal data class SendElectrumApiCall(val electrumRequest: ElectrumRequest) : ClientEvent()
internal object AskForStatus : ClientEvent()
internal object AskForHeader : ClientEvent()

/*
    Actions
 */
internal sealed class ElectrumClientAction
internal data class ConnectionAttempt(val serverAddress: ServerAddress) : ElectrumClientAction()
internal data class SendRequest(val request: String) : ElectrumClientAction()
internal data class SendHeader(val height: Int, val blockHeader: BlockHeader) : ElectrumClientAction()
internal data class SendResponse(val response: ElectrumResponse) : ElectrumClientAction()
internal data class BroadcastStatus(val connection: Connection) : ElectrumClientAction()
internal object StartPing : ElectrumClientAction()
internal object Shutdown : ElectrumClientAction()

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

internal object WaitingForConnection : ClientState() {
    override fun process(event: ClientEvent): Pair<ClientState, List<ElectrumClientAction>> = when (event) {
        Connected -> newState {
            state = WaitingForVersion
            actions = listOf(StartPing, SendRequest(version.asJsonRPCRequest()))
        }
        else -> unhandled(event)
    }
}

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
                    actions = listOf(BroadcastStatus(Connection.CLOSED))
                }
                else -> returnState() // TODO handle error?
            }
        }
        else -> unhandled(event)
    }
}

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

internal data class ClientRunning(val height: Int, val tip: BlockHeader, val requests: MutableMap<Int, ElectrumRequest> = mutableMapOf()) : ClientState() {
    override fun process(event: ClientEvent): Pair<ClientState, List<ElectrumClientAction>> = when (event) {
        is AskForStatus -> returnState(BroadcastStatus(Connection.ESTABLISHED))
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
                requests[response.value.id]?.takeUnless { it is Ping }?.let { originRequest ->
                    returnState(SendResponse(parseJsonResponse(originRequest, response.value)))
                } ?: returnState()
            }
        }
        else -> unhandled(event)
    }

    private fun sendRequest(electrumRequest: ElectrumRequest): SendRequest {
        val newRequestId = requests.maxOfOrNull { it.key + 1 } ?: 0
        requests[newRequestId] = electrumRequest
        return SendRequest(electrumRequest.asJsonRPCRequest(newRequestId))
    }
}

internal object ClientClosed : ClientState() {
    override fun process(event: ClientEvent): Pair<ClientState, List<ElectrumClientAction>> =
        when (event) {
            is Start -> newState {
                state = WaitingForConnection
                actions = listOf(
                    BroadcastStatus(Connection.ESTABLISHING),
                    ConnectionAttempt(event.serverAddress)
                )
            }
            else -> unhandled(event)
        }
}

private fun ClientState.unhandled(event: ClientEvent): Pair<ClientState, List<ElectrumClientAction>> =
    when (event) {
        Disconnected -> newState {
            state = ClientClosed
            actions = listOf(BroadcastStatus(Connection.CLOSED), Shutdown)
        }
        AskForStatus, AskForHeader -> returnState() // TODO something else ?
        else -> error("The state $this cannot process the event $event")
    }

private class ClientStateBuilder {
    var state: ClientState = ClientClosed
    var actions = emptyList<ElectrumClientAction>()
    fun build() = state to actions
}

private fun newState(init: ClientStateBuilder.() -> Unit) = ClientStateBuilder().apply(init).build()

private fun ClientState.returnState(actions: List<ElectrumClientAction> = emptyList()): Pair<ClientState, List<ElectrumClientAction>> = this to actions
private fun ClientState.returnState(action: ElectrumClientAction): Pair<ClientState, List<ElectrumClientAction>> = this to listOf(action)

@OptIn(ExperimentalCoroutinesApi::class, ExperimentalTime::class)
class ElectrumClient(
    private val socketBuilder: TcpSocket.Builder,
    scope: CoroutineScope
) : CoroutineScope by scope {

    private lateinit var socket: TcpSocket
    private val json = Json { ignoreUnknownKeys = true }

    private val eventChannel: Channel<ClientEvent> = Channel(Channel.BUFFERED)

    private val _connectionState = MutableStateFlow(Connection.CLOSED)
    val connectionState: StateFlow<Connection> get() = _connectionState

    private val notificationsChannel = BroadcastChannel<ElectrumMessage>(Channel.BUFFERED)

    fun openNotificationsSubscription() = notificationsChannel.openSubscription()

    private var state: ClientState = ClientClosed
        set(value) {
            if (value != field) logger.info {
                """Updated State: 
                |prev: $field
                |new:  $value""".trimMargin()
            }
            field = value
        }

    var runJob: Job? = null

    init {
        logger.info { "Init Electrum Client" }
        runJob = launch { run() }
    }

    private suspend fun run() {
        eventChannel.consumeEach { event ->

            val (newState, actions) = state.process(event)
            state = newState

            actions.forEach { action ->
                yield()
                when (action) {
                    is ConnectionAttempt -> connectionJob = establishConnection(action.serverAddress)
                    is SendRequest -> send(action.request.encodeToByteArray())
                    is SendHeader -> notificationsChannel.send(HeaderSubscriptionResponse(action.height, action.blockHeader))
                    is SendResponse -> notificationsChannel.send(action.response)
                    is BroadcastStatus -> _connectionState.value = action.connection
                    StartPing -> pingJob = pingScheduler()
                    is Shutdown -> closeConnection()
                }
            }
        }
    }

    fun connect(serverAddress: ServerAddress) {
        if (state == ClientClosed) launch { eventChannel.send(Start(serverAddress)) }
        else logger.warning { "ElectrumClient is already running $this" }
    }

    fun disconnect() {
        launch { eventChannel.send(Disconnected) }
    }

    private var connectionJob: Job? = null
    private fun establishConnection(serverAddress: ServerAddress) = launch {
        try {
            val (host, port, tls) = serverAddress
            logger.info { "Attempt connection to electrumx instance [host=$host, port=$port, tls=$tls]" }
            socket = socketBuilder.connect(host, port, tls)
            logger.info { "Connected to electrumx instance" }
            eventChannel.send(Connected)
            socket.linesFlow().collect {
                val electrumResponse = json.decodeFromString(ElectrumResponseDeserializer, it)
                eventChannel.send(ReceivedResponse(electrumResponse))
            }
        } catch (ex: TcpSocket.IOException) {
            logger.warning { ex.message }
            eventChannel.send(Disconnected)
        }
    }

    private fun closeConnection() {
        pingJob?.cancel()
        connectionJob?.cancel()
        if (this::socket.isInitialized) socket.close()
    }

    private suspend fun send(message: ByteArray) {
        try {
            socket.send(message)
        } catch (ex: TcpSocket.IOException) {
            logger.warning { ex.message }
        }
    }

    fun sendElectrumRequest(request: ElectrumRequest): Unit = sendMessage(SendElectrumRequest(request))

    fun sendMessage(message: ElectrumMessage) {
        launch {
            when (message) {
                is AskForStatusUpdate -> eventChannel.send(AskForStatus)
                is AskForHeaderSubscriptionUpdate -> eventChannel.send(AskForHeader)
                is SendElectrumRequest -> eventChannel.send(SendElectrumApiCall(message.electrumRequest))
                else -> error("sendMessage does not support message: $message")
            }
        }
    }

    private var pingJob: Job? = null
    private fun pingScheduler() = launch {
        while (isActive) {
            delay(30.seconds)
            eventChannel.send(SendElectrumApiCall(Ping))
        }
    }

    fun stop() {
        logger.info { "Stop Electrum Client" }
        closeConnection()
        // Cancel event consumer
        runJob?.cancel()
        // Cancel broadcast channels
        notificationsChannel.cancel()
        // Cancel event channel
        eventChannel.cancel()
    }

    companion object {
        const val ELECTRUM_CLIENT_NAME = "3.3.6"
        const val ELECTRUM_PROTOCOL_VERSION = "1.4"
        val logger by newEclairLogger<ElectrumClient>()
        val version = ServerVersion()
        internal fun computeScriptHash(publicKeyScript: ByteVector): ByteVector32 = Crypto.sha256(publicKeyScript).toByteVector32().reversed()
    }
}
