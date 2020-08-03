package fr.acinq.eklair.blockchain.electrum

import fr.acinq.bitcoin.BlockHeader
import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.Crypto
import fr.acinq.eklair.blockchain.electrum.ElectrumClient.Companion.version
import fr.acinq.eklair.io.TcpSocket
import fr.acinq.eklair.io.linesFlow
import fr.acinq.eklair.utils.Either
import fr.acinq.eklair.utils.JsonRPCResponse
import fr.acinq.eklair.utils.toByteVector32
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.flow.collect
import kotlinx.serialization.json.Json
import org.kodein.log.frontend.simplePrintFrontend
import org.kodein.log.*


/*
    Events
 */
internal sealed class ClientEvent
internal object Start : ClientEvent()
internal object Connected : ClientEvent()
internal object Disconnected : ClientEvent()
internal data class ReceivedResponse(val response: Either<ElectrumResponse, JsonRPCResponse>) : ClientEvent()
internal data class SendElectrumApiCall(val electrumRequest: ElectrumRequest, val requestor: SendChannel<ElectrumMessage>? = null) : ClientEvent()
internal data class RegisterStatusListener(val listener: SendChannel<ElectrumMessage>) : ClientEvent()
internal data class RegisterHeaderNotificationListener(val listener: SendChannel<ElectrumMessage>) : ClientEvent()
internal data class RegisterScriptHashNotificationListener(val scriptHash: ByteVector32, val listener: SendChannel<ElectrumMessage>) : ClientEvent()
internal data class UnregisterListener(val listener: SendChannel<ElectrumMessage>): ClientEvent()

/*
    Actions
 */
internal sealed class ElectrumClientAction
internal object ConnectionAttempt : ElectrumClientAction()
internal data class SendRequest(val request: String): ElectrumClientAction()
internal data class SendHeader(val height: Int, val blockHeader: BlockHeader, val requestor: SendChannel<ElectrumMessage>) : ElectrumClientAction()
internal data class SendResponse(val response: ElectrumResponse, val requestor: SendChannel<ElectrumMessage>? = null) : ElectrumClientAction()
internal data class BroadcastHeaderSubscription(val headerSubscriptionResponse: HeaderSubscriptionResponse): ElectrumClientAction()
internal data class BroadcastScriptHashSubscription(val response: ScriptHashSubscriptionResponse): ElectrumClientAction()
internal data class BroadcastStatus(val state: ElectrumClientState): ElectrumClientAction()
internal data class AddStatusListener(val listener: SendChannel<ElectrumMessage>) : ElectrumClientAction()
internal data class AddHeaderListener(val listener: SendChannel<ElectrumMessage>) : ElectrumClientAction()
internal data class AddScriptHashListener(val scriptHash: ByteVector32, val listener: SendChannel<ElectrumMessage>) : ElectrumClientAction()
internal data class RemoveStatusListener(val listener: SendChannel<ElectrumMessage>) : ElectrumClientAction()
internal data class RemoveHeaderListener(val listener: SendChannel<ElectrumMessage>) : ElectrumClientAction()
internal data class RemoveScriptHashListener(val listener: SendChannel<ElectrumMessage>) : ElectrumClientAction()
internal object StartPing : ElectrumClientAction()
internal object Shutdown : ElectrumClientAction()
internal object Restart : ElectrumClientAction()

/**
 * [ElectrumClient] State
 *
 * +--> Disconnected -> WaitForVersion ----+
 * |    ^          |                       |
 * |    +-Restart<-+                       |
 * |                                       |
 * +------ Running <----- WaitForTip <-----+
 *         ^     |
 *         +-----+
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
        is RegisterStatusListener -> returnState(AddStatusListener(event.listener))
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
                        actions = listOf(BroadcastStatus(ElectrumClientClosed), Restart)
                    }
                    else -> returnState() // TODO handle error?
                }
            }
            event is RegisterStatusListener -> returnState(AddStatusListener(event.listener))
            else -> unhandled(event)
        }
    }

internal object WaitingForTip : ClientState() {
    override fun process(event: ClientEvent): Pair<ClientState, List<ElectrumClientAction>> =
        when(event) {
            is ReceivedResponse -> {
                when (val rpcResponse = event.response) {
                    is Either.Right ->
                        when (val response = parseJsonResponse(HeaderSubscription, rpcResponse.value)) {
                            is HeaderSubscriptionResponse -> newState {
                                state = ClientRunning(height = response.height, tip = response.header)
                                actions = listOf(BroadcastStatus(ElectrumClientReady), BroadcastHeaderSubscription(response))
                            }
                            else -> returnState()
                        }
                    else -> returnState()
                }
            }
            is RegisterStatusListener -> returnState(AddStatusListener(event.listener))
            else -> unhandled(event)
        }
}

internal data class ClientRunning(val height: Int, val tip: BlockHeader) : ClientState() {
    /**
     * Unique ID to match response with origin request
     */
    private var currentRequestId = 0
    val requests: MutableMap<Int, Pair<ElectrumRequest, SendChannel<ElectrumMessage>?>> = mutableMapOf()

   override fun process(event: ClientEvent): Pair<ClientState, List<ElectrumClientAction>> = when (event) {
       is RegisterStatusListener -> returnState(AddStatusListener(event.listener), BroadcastStatus(ElectrumClientReady))
       is RegisterHeaderNotificationListener -> returnState(
           AddHeaderListener(event.listener),
           SendHeader(height, tip, event.listener)
       )
       is ElectrumRequest -> returnState(sendRequest(event))
       is SendElectrumApiCall -> returnState(buildList {
           add(sendRequest(event.electrumRequest, event.requestor))
           if (event.electrumRequest is ScriptHashSubscription && event.requestor != null)
               add(AddScriptHashListener(event.electrumRequest.scriptHash, event.requestor))
       })
       is ReceivedResponse -> when (val response = event.response) {
           is Either.Left -> when (val electrumResponse = response.value) {
               is HeaderSubscriptionResponse -> newState {
                   state = copy(height = electrumResponse.height, tip = electrumResponse.header)
                   actions = listOf(BroadcastHeaderSubscription(electrumResponse))
               }
               is ScriptHashSubscriptionResponse -> returnState(BroadcastScriptHashSubscription(electrumResponse))
               else -> returnState()
           }
           is Either.Right -> {
               returnState(
                   buildList {
                       requests[response.value.id]?.let { (originRequest, requestor) ->
                           add(SendResponse(parseJsonResponse(originRequest, response.value), requestor))
                       }
                   }
               )
           }
       }
       else -> unhandled(event)
   }

    private fun sendRequest(electrumRequest: ElectrumRequest, requestor: SendChannel<ElectrumMessage>? = null): SendRequest {
        val newRequestId = currentRequestId++
        requests[newRequestId] = electrumRequest to requestor
        return SendRequest(electrumRequest.asJsonRPCRequest(newRequestId))
    }
}

internal object ClientClosed : ClientState() {
    override fun process(event: ClientEvent): Pair<ClientState, List<ElectrumClientAction>> =
        when(event) {
            Start -> newState {
                state = WaitingForConnection
                actions = listOf(ConnectionAttempt)
            }
            is RegisterStatusListener -> returnState(AddStatusListener(event.listener))
            else -> unhandled(event)
        }
}

private fun ClientState.unhandled(event: ClientEvent) : Pair<ClientState, List<ElectrumClientAction>> =
    when (event) {
        is UnregisterListener -> event.listener.let {
            returnState(RemoveStatusListener(it), RemoveHeaderListener(it), RemoveScriptHashListener(it))
        }
        Disconnected -> newState {
            state = ClientClosed
            actions = listOf(BroadcastStatus(ElectrumClientClosed), Shutdown, Restart)
        }
        else -> error("The state $this cannot process the event $event")
    }

private class ClientStateBuilder {
    var state: ClientState = ClientClosed
    var actions = emptyList<ElectrumClientAction>()
    fun build() = state to actions
}
private fun newState(init: ClientStateBuilder.() -> Unit) = ClientStateBuilder().apply(init).build()
private fun newState(newState: ClientState) = ClientStateBuilder().apply { state = newState }.build()

private fun ClientState.returnState(actions: List<ElectrumClientAction> = emptyList()): Pair<ClientState, List<ElectrumClientAction>> = this to actions
private fun ClientState.returnState(action: ElectrumClientAction): Pair<ClientState, List<ElectrumClientAction>> = this to listOf(action)
private fun ClientState.returnState(vararg actions: ElectrumClientAction): Pair<ClientState, List<ElectrumClientAction>> = this to listOf(*actions)

@OptIn(ExperimentalCoroutinesApi::class)
class ElectrumClient(
    private val host: String,
    private val port: Int,
    private val tls: Boolean,
    scope: CoroutineScope
) : CoroutineScope by scope {

    private lateinit var socket: TcpSocket
    private val json = Json { ignoreUnknownKeys = true }

    private val eventChannel: Channel<ClientEvent> = Channel(Channel.BUFFERED)

    private val scriptHashSubscriptionMap: MutableMap<ByteVector32, Set<SendChannel<ElectrumMessage>>> = mutableMapOf()
    private val statusSubscriptionList: MutableSet<SendChannel<ElectrumMessage>> = mutableSetOf()
    private val headerSubscriptionList: MutableSet<SendChannel<ElectrumMessage>> = mutableSetOf()

    private var state: ClientState = ClientClosed
        set(value) {
            if (value != field) logger.info { "Updated State: $field -> $value" }
            field = value
        }

    fun start() {
        logger.info { "Start Electrum Client" }
        launch { run() }
        launch { eventChannel.send(Start) }
    }

    private suspend fun run() {
        eventChannel.consumeEach { event ->
            logger.info { "Event received: $event" }

            val (newState, actions) = state.process(event)
            state = newState

            actions.forEach { action ->
                logger.info { "Execute action: $action" }
                when (action) {
                    is ConnectionAttempt -> connectionJob = connect()
                    is SendRequest -> socket.send(action.request.encodeToByteArray())
                    is SendHeader -> action.requestor.send(HeaderSubscriptionResponse(action.height, action.blockHeader))
                    is SendResponse -> action.requestor?.send(action.response) ?: sendMessage(action.response)
                    is BroadcastHeaderSubscription -> headerSubscriptionList.forEach { channel ->
                        channel.send(action.headerSubscriptionResponse)
                    }
                    is BroadcastScriptHashSubscription ->
                        scriptHashSubscriptionMap[action.response.scriptHash]?.forEach { channel ->
                        channel.send(action.response)
                    }
                    is BroadcastStatus -> {
                        logger.info { "BroadcastStatus to $statusSubscriptionList" }
                        statusSubscriptionList.forEach {
                            logger.info { "BroadcastStatus to $it" }
                            it.send(action.state)
                        }
                    }
                    is AddStatusListener -> statusSubscriptionList.add(action.listener)
                    is AddHeaderListener -> headerSubscriptionList.add(action.listener)
                    is AddScriptHashListener -> {
                        scriptHashSubscriptionMap[action.scriptHash] =
                            scriptHashSubscriptionMap.getOrElse(action.scriptHash, { setOf() }) + action.listener
                    }
                    is RemoveStatusListener -> statusSubscriptionList.remove(action.listener)
                    is RemoveHeaderListener -> headerSubscriptionList.remove(action.listener)
                    is RemoveScriptHashListener -> scriptHashSubscriptionMap -= scriptHashSubscriptionMap.filterValues { it == action.listener }.keys
                    StartPing -> pingJob = pingScheduler()
                    is Shutdown -> disconnect()
                    is Restart -> {
                        delay(1_000); eventChannel.send(Start)
                    }
                }
            }
        }
    }

    private var connectionJob: Job? = null
    private fun connect() = launch {
        try {
            logger.info { "Attempt connection to electrumx instance [host=$host, port=$port, tls=$tls]" }
            socket = TcpSocket.Builder().connect(host, port, tls)
            logger.info { "Connected to electrumx instance" }
            eventChannel.send(Connected)
            socket.linesFlow(this).collect {
                logger.info { "Electrum response received: $it" }
                val electrumResponse = json.decodeFromString(ElectrumResponseDeserializer, it)
                eventChannel.send(ReceivedResponse(electrumResponse))
            }
        } catch (ex: TcpSocket.IOException) {
            logger.error(ex)
            eventChannel.send(Disconnected)
        }
    }

    private fun disconnect() {
        pingJob?.cancel()
        connectionJob?.cancel()
        if (this::socket.isInitialized) socket.close()
    }

    fun sendElectrumRequest(request: ElectrumRequest, requestor: SendChannel<ElectrumMessage>?): Unit =
        sendMessage(SendElectrumRequest(request, requestor))

    fun sendMessage(message: ElectrumMessage) {
         launch {
             when (message) {
                 is ElectrumStatusSubscription -> eventChannel.send(RegisterStatusListener(message.listener))
                 is ElectrumHeaderSubscription -> eventChannel.send(RegisterHeaderNotificationListener(message.listener))
                 is SendElectrumRequest -> eventChannel.send(SendElectrumApiCall(message.electrumRequest, message.requestor))
                 is ElectrumResponse -> eventChannel.send(ReceivedResponse(Either.Left(message)))
                 else -> logger.info { "ElectrumClient does not handle messages like: $message" }
             }
         }
    }

    private var pingJob: Job? = null
    private fun pingScheduler() = launch {
        while (true) {
            delay(30_000)
            logger.info { "Ping Electrum Server" }
            eventChannel.send(SendElectrumApiCall(Ping))
        }
    }

    fun stop() {
        logger.info { "Stop Electrum Client" }
        disconnect()
        eventChannel.close()
    }

    companion object {
        const val ELECTRUM_CLIENT_NAME = "3.3.6"
        const val ELECTRUM_PROTOCOL_VERSION = "1.4"
        val logger = LoggerFactory(simplePrintFrontend).newLogger<ElectrumClient>()
        val version = ServerVersion()
        internal fun computeScriptHash(publicKeyScript: ByteVector): ByteVector32 = Crypto.sha256(publicKeyScript).toByteVector32().reversed()
    }
}