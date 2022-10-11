package fr.acinq.lightning.blockchain.electrum

import fr.acinq.bitcoin.BlockHeader
import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.Crypto
import fr.acinq.lightning.blockchain.electrum.ElectrumClient.Companion.version
import fr.acinq.lightning.blockchain.electrum.ElectrumClientAction.*
import fr.acinq.lightning.blockchain.electrum.ElectrumClientCommand.*
import fr.acinq.lightning.io.TcpSocket
import fr.acinq.lightning.io.linesFlow
import fr.acinq.lightning.io.send
import fr.acinq.lightning.utils.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.Channel.Factory.BUFFERED
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.Json
import org.kodein.log.Logger
import org.kodein.log.LoggerFactory
import org.kodein.log.newLogger
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration.Companion.seconds


/** Commands */
sealed interface ElectrumClientCommand {
    object Connected : ElectrumClientCommand
    object Disconnected : ElectrumClientCommand
    data class AskForHeader(val callerId: Int) : ElectrumClientCommand
    data class ReceivedElectrumResponse(val response: Either<ElectrumResponse, JsonRPCResponse>) : ElectrumClientCommand
    data class SendElectrumRequest(val callerId: Int, val electrumRequest: ElectrumRequest) : ElectrumClientCommand
}

/** Actions */
sealed interface ElectrumClientAction {
    data class SendHeader(val callerId: Int, val height: Int, val blockHeader: BlockHeader) : ElectrumClientAction
    data class SendRequest(val request: String) : ElectrumClientAction
    data class SendResponse(val callerId: Int?, val response: ElectrumResponse) : ElectrumClientAction
    data class BroadcastStatus(val connection: Connection) : ElectrumClientAction
}

/**
 * [ElectrumClient] State
 *
 *  ClientClosed ----> WaitingForVersion
 *       ^                     |
 *       |                     |
 *       |                     v
 *    Running <--------- WaitingForTip
 *
 */
internal sealed class ClientState {
    abstract fun process(cmd: ElectrumClientCommand, logger: Logger): Pair<ClientState, List<ElectrumClientAction>>
}

internal object WaitingForVersion : ClientState() {
    override fun process(cmd: ElectrumClientCommand, logger: Logger): Pair<ClientState, List<ElectrumClientAction>> = when {
        cmd is ReceivedElectrumResponse && cmd.response is Either.Right ->
            when (val msg = parseJsonResponse(version, cmd.response.value)) {
                is ServerVersionResponse -> {
                    logger.info { "connected to electrum server name=${msg.clientName} version=${msg.protocolVersion}" }
                    newState {
                        state = WaitingForTip
                        actions = listOf(SendRequest(HeaderSubscription.asJsonRPCRequest()))
                    }
                }

                is ServerError -> newState {
                    state = ClientClosed
                    actions = listOf(BroadcastStatus(Connection.CLOSED(null)))
                }

                else -> returnState() // TODO handle error?
            }

        else -> unhandled(cmd, logger)
    }
}

internal object WaitingForTip : ClientState() {
    override fun process(cmd: ElectrumClientCommand, logger: Logger): Pair<ClientState, List<ElectrumClientAction>> =
        when (cmd) {
            is ReceivedElectrumResponse -> {
                when (val rpcResponse = cmd.response) {
                    is Either.Right ->
                        when (val response = parseJsonResponse(HeaderSubscription, rpcResponse.value)) {
                            is HeaderSubscriptionResponse -> newState {
                                state = ClientRunning(height = response.blockHeight, tip = response.header)
                                actions = listOf(BroadcastStatus(Connection.ESTABLISHED), SendResponse(callerId = null, response))
                            }

                            else -> returnState()
                        }

                    else -> returnState()
                }
            }

            else -> unhandled(cmd, logger)
        }
}

internal data class ClientRunning(
    val height: Int,
    val tip: BlockHeader,
    val requests: MutableMap<Int, SendElectrumRequest> = mutableMapOf(),
    val responseTimestamps: MutableMap<Int, Long?> = mutableMapOf()
) : ClientState() {

    override fun process(cmd: ElectrumClientCommand, logger: Logger): Pair<ClientState, List<ElectrumClientAction>> = when (cmd) {
        is AskForHeader -> returnState(SendHeader(cmd.callerId, height, tip))
        is SendElectrumRequest -> returnState(sendRequest(cmd))
        is ReceivedElectrumResponse -> when (val response = cmd.response) {
            is Either.Left -> when (val electrumResponse = response.value) {
                is HeaderSubscriptionResponse -> newState {
                    state = copy(height = electrumResponse.blockHeight, tip = electrumResponse.header)
                    actions = listOf(SendResponse(callerId = null, electrumResponse))
                }

                is ScriptHashSubscriptionResponse -> returnState(SendResponse(callerId = null, electrumResponse))
                else -> returnState()
            }

            is Either.Right -> {
                requests[response.value.id]?.let { reqCmd ->
                    responseTimestamps[response.value.id!!] = currentTimestampMillis()
                    if (reqCmd.electrumRequest !is Ping) {
                        returnState(SendResponse(reqCmd.callerId, parseJsonResponse(reqCmd.electrumRequest, response.value)))
                    } else {
                        returnState()
                    }
                } ?: returnState()
            }
        }

        else -> unhandled(cmd, logger)
    }

    private fun sendRequest(cmd: SendElectrumRequest): SendRequest {
        val newRequestId = requests.maxOfOrNull { it.key + 1 } ?: 0
        requests[newRequestId] = cmd
        responseTimestamps[newRequestId] = null
        return SendRequest(cmd.electrumRequest.asJsonRPCRequest(newRequestId))
    }
}

internal object ClientClosed : ClientState() {
    override fun process(cmd: ElectrumClientCommand, logger: Logger): Pair<ClientState, List<ElectrumClientAction>> =
        when (cmd) {
            Connected -> newState {
                state = WaitingForVersion
                actions = listOf(SendRequest(version.asJsonRPCRequest()))
            }

            else -> unhandled(cmd, logger)
        }
}

private fun ClientState.unhandled(event: ElectrumClientCommand, logger: Logger): Pair<ClientState, List<ElectrumClientAction>> =
    when (event) {
        Disconnected -> ClientClosed to emptyList()
        is AskForHeader -> returnState() // TODO something else ?
        else -> {
            logger.warning { "cannot process event ${event::class} in state ${this::class}" }
            returnState()
        }
    }

private class ClientStateBuilder {
    var state: ClientState = ClientClosed
    var actions = emptyList<ElectrumClientAction>()
    fun build() = state to actions
}

private fun newState(init: ClientStateBuilder.() -> Unit) = ClientStateBuilder().apply(init).build()

private fun ClientState.returnState(actions: List<ElectrumClientAction> = emptyList()): Pair<ClientState, List<ElectrumClientAction>> = this to actions
private fun ClientState.returnState(action: ElectrumClientAction): Pair<ClientState, List<ElectrumClientAction>> = returnState(listOf(action))

data class RequestResponseTimestamp(
    val id: Int,
    val request: ElectrumRequest,
    val lastResponseTimestamp: Long?
)

@OptIn(ExperimentalCoroutinesApi::class)
class ElectrumClient(
    socketBuilder: TcpSocket.Builder?,
    scope: CoroutineScope,
    private val loggerFactory: LoggerFactory
) : CoroutineScope by scope {

    private val logger = loggerFactory.newLogger(this::class)

    var socketBuilder: TcpSocket.Builder? = socketBuilder
        set(value) {
            logger.debug { "swap socket builder=$value" }
            field = value
        }

    private val json = Json { ignoreUnknownKeys = true }

    // mailbox of this "actor" (responses from the server will be sent to it, along with other commands)
    private val mailbox: Channel<ElectrumClientCommand> = Channel(BUFFERED)

    // channel to send requests to the server
    private var output: Channel<ByteArray> = Channel(BUFFERED)

    // connection status to the server
    private val _connectionState = MutableStateFlow<Connection>(Connection.CLOSED(null))
    val connectionState: StateFlow<Connection> get() = _connectionState.asStateFlow()

    // subscriptions notifications (headers, script_hashes, etc.)
    private val _notifications = MutableSharedFlow<ElectrumClientNotification>(replay = 0, extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.SUSPEND)

    private var state: ClientState = ClientClosed

    private var runJob: Job? = null

    init {
        logger.info { "initializing electrum client" }
        launch {
            var previousState = _connectionState.value
            _connectionState.filter { it != previousState }.collect {
                logger.info { "connection state changed: $it" }
                if (it is Connection.CLOSED) mailbox.send(Disconnected)
                previousState = it
            }
        }
        runJob = launch { run() }
    }

    private suspend fun run() {
        mailbox.consumeEach { cmd ->

            logger.debug { "processing command $cmd in state ${state::class}" }

            val (newState, actions) = state.process(cmd, logger)
            state = newState

            actions.forEach { action ->
                yield()
                when (action) {
                    is SendRequest -> if (!output.isClosedForSend) output.send(action.request.encodeToByteArray())
                    is SendHeader -> _notifications.emit(ElectrumClientNotification(action.callerId, HeaderSubscriptionResponse(action.height, action.blockHeader)))
                    is SendResponse -> _notifications.emit(ElectrumClientNotification(action.callerId, action.response))
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
        _connectionState.value = Connection.CLOSED(null)
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
            socketBuilder?.connect(host, port, tls, loggerFactory) ?: error("socket builder is null.")
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
        mailbox.send(Connected)

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
                delay(30.seconds)
                send(Ping.asJsonRPCRequest(-1).encodeToByteArray())
            }
        }

        suspend fun respond() {
            // Reset the output channel to avoid sending obsolete messages
            output = Channel(BUFFERED)
            for (msg in output) {
                send(msg)
            }
        }

        suspend fun listen() {
            try {
                socket.linesFlow().collect {
                    val electrumResponse = json.decodeFromString(ElectrumResponseDeserializer, it)
                    mailbox.send(ReceivedElectrumResponse(electrumResponse))
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

    private val callerIdGenerator: AtomicReference<Int> = AtomicReference(0)

    /**
     * A simple layer that decorates calls to [ElectrumClient] with a unique identifier,
     * so we can do multiplexing on the same connection to an Electrum server.
     */
    inner class Caller() {

        private val id = callerIdGenerator.getAndUpdate { i -> i + 1 }

        val connectionState: StateFlow<Connection> get() = this@ElectrumClient.connectionState

        val notifications: Flow<ElectrumResponse>
            get() = _notifications.asSharedFlow()
                .filter { it.callerId == null || it.callerId == id }
                .map { it.msg }

        fun askCurrentHeader() {
            launch {
                mailbox.send(AskForHeader(id))
            }
        }

        fun sendElectrumRequest(request: ElectrumRequest) {
            launch {
                mailbox.send(SendElectrumRequest(id, request))
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
                    RequestResponseTimestamp(id, request.electrumRequest, lastResponseTimestamp)
                }
            }
        }
    }

    /**
     * Events sent to consumers.
     * @param callerId identifies a given consumer. A null value means that the event will be
     * sent to all consumers
     */
    private inner class ElectrumClientNotification(val callerId: Int?, val msg: ElectrumResponse)


    fun stop() {
        logger.info { "electrum client stopping" }
        // NB: disconnecting cancels the output channel
        disconnect()
        // Cancel event consumer
        runJob?.cancel()
        // Cancel event channel
        mailbox.cancel()
    }

    companion object {
        const val ELECTRUM_CLIENT_NAME = "3.3.6"
        const val ELECTRUM_PROTOCOL_VERSION = "1.4"
        val version = ServerVersion()
        internal fun computeScriptHash(publicKeyScript: ByteVector): ByteVector32 = Crypto.sha256(publicKeyScript).toByteVector32().reversed()
    }
}
