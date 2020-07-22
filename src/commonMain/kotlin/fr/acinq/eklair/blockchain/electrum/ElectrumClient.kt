package fr.acinq.eklair.blockchain.electrum

import fr.acinq.bitcoin.BlockHeader
import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.Crypto
import fr.acinq.eklair.blockchain.electrum.ElectrumClient.Companion.logger
import fr.acinq.eklair.blockchain.electrum.ElectrumClient.Companion.version
import fr.acinq.eklair.utils.Either
import fr.acinq.eklair.utils.JsonRPCResponse
import fr.acinq.eklair.utils.toByteVector32
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.*
import org.kodein.log.LoggerFactory
import org.kodein.log.newLogger

/*
    Events
 */
internal sealed class ClientEvent
internal object Connected : ClientEvent()
internal object Disconnected : ClientEvent()
internal data class ReceivedResponse(val response: Either<ElectrumResponse, JsonRPCResponse>) : ClientEvent()
internal data class SendElectrumRequest(val electrumRequest: ElectrumRequest, val requestor: SendChannel<ElectrumMessage>? = null) : ClientEvent()
internal data class RegisterStatusListener(val listener: SendChannel<ElectrumMessage>) : ClientEvent()
internal data class RegisterHeaderNotificationListener(val listener: SendChannel<ElectrumMessage>) : ClientEvent()
internal data class RegisterScriptHashNotificationListener(val scriptHash: ByteVector32, val listener: SendChannel<ElectrumMessage>) : ClientEvent()
internal data class UnregisterListener(val listener: SendChannel<ElectrumMessage>): ClientEvent()

/*
    Actions
 */
internal sealed class ElectrumClientAction
internal data class SendRequest(val request: String): ElectrumClientAction()
internal data class SendHeader(val height: Int, val blockHeader: BlockHeader, val requestor: SendChannel<ElectrumMessage>) : ElectrumClientAction()
internal data class SendResponse(val response: ElectrumResponse, val requestor: SendChannel<ElectrumMessage>? = null) : ElectrumClientAction()
internal data class BroadcastHeaderSubscription(val headerSubscriptionResponse: HeaderSubscriptionResponse): ElectrumClientAction()
internal data class BroadcastScriptHashSubscription(val response: ScriptHashSubscriptionResponse): ElectrumClientAction()
internal data class BroadcastState(val state: ElectrumClientState): ElectrumClientAction()
internal data class AddStatusListener(val listener: SendChannel<ElectrumMessage>) : ElectrumClientAction()
internal data class AddHeaderListener(val listener: SendChannel<ElectrumMessage>) : ElectrumClientAction()
internal data class AddScriptHashListener(val scriptHash: ByteVector32, val listener: SendChannel<ElectrumMessage>) : ElectrumClientAction()
internal data class RemoveStatusListener(val listener: SendChannel<ElectrumMessage>) : ElectrumClientAction()
internal data class RemoveHeaderListener(val listener: SendChannel<ElectrumMessage>) : ElectrumClientAction()
internal data class RemoveScriptHashListener(val listener: SendChannel<ElectrumMessage>) : ElectrumClientAction()
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
    /**
     * Unique ID to match response with origin request
     */
    private var currentRequestId = 0
    val requests: MutableMap<Int, Pair<ElectrumRequest, SendChannel<ElectrumMessage>?>> = mutableMapOf()

    fun sendRequest(electrumRequest: ElectrumRequest, requestor: SendChannel<ElectrumMessage>? = null): SendRequest {
        val newRequestId = currentRequestId++
        requests[newRequestId] = electrumRequest to requestor
        return SendRequest(electrumRequest.asJsonRPCRequest(newRequestId))
    }

    abstract fun process(event: ClientEvent): Pair<ClientState, List<ElectrumClientAction>>
}

internal object WaitingForVersion : ClientState() {
    override fun process(event: ClientEvent): Pair<ClientState, List<ElectrumClientAction>> = when {
            event is ReceivedResponse && event.response is Either.Right -> {
                val electrumResponse = parseJsonResponse(version, event.response.value)
                logger.info { "Response received: $electrumResponse" }
                when (electrumResponse) {
                    is ServerVersionResponse -> newState {
                        state = WaitingForTip
                        actions = listOf(sendRequest(HeaderSubscription))
                    }
                    is ServerError -> newState {
                        state = ClientClosed
                        actions = listOf(BroadcastState(ElectrumClientClosed), Restart)
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
                                actions = listOf(BroadcastState(ElectrumClientReady), BroadcastHeaderSubscription(response))
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
   override fun process(event: ClientEvent): Pair<ClientState, List<ElectrumClientAction>> = when (event) {
       is RegisterStatusListener -> returnState(AddStatusListener(event.listener), BroadcastState(ElectrumClientReady))
       is RegisterHeaderNotificationListener -> returnState(
           AddHeaderListener(event.listener),
           SendHeader(height, tip, event.listener)
       )
       is ElectrumRequest -> returnState(sendRequest(event))
       is SendElectrumRequest -> returnState(buildList {
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

       /*
        - OK:
        - TODO:
            case AddStatusListener(actor)
            case HeaderSubscription(actor)
            case request: Request
            case Right(json: JsonRPCResponse)
            case Left(response: HeaderSubscriptionResponse)
            case Left(response: AddressSubscriptionResponse)
            case Left(response: ScriptHashSubscriptionResponse)
            case HeaderSubscriptionResponse(height, newtip)
        */

       else -> unhandled(event)
   }
}

internal object ClientClosed : ClientState() {
    override fun process(event: ClientEvent): Pair<ClientState, List<ElectrumClientAction>> =
        when(event) {
            Connected -> newState {
                state = WaitingForVersion
                actions = listOf(SendRequest(version.asJsonRPCRequest()))
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
        Disconnected -> newState(ClientClosed)
        else -> returnState() // error("The state $this cannot process the event $event")
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
    // TODO to be internalized with socket implementation
    private val socketInputChannel: Channel<Either<ElectrumResponse, JsonRPCResponse>> = Channel(),
    // TODO to be internalized with socket implementation
    private val socketOutputChannel: SendChannel<String>) {

    private val eventChannel: Channel<ClientEvent> = Channel(Channel.BUFFERED)

    // TODO not needed? private val addressSubscriptionMap: MutableMap<String, Set<SendChannel<ElectrumMessage>>> = mutableMapOf()
    private val scriptHashSubscriptionMap: MutableMap<ByteVector32, Set<SendChannel<ElectrumMessage>>> = mutableMapOf()
    private val statusSubscriptionList: MutableSet<SendChannel<ElectrumMessage>> = mutableSetOf()
    private val headerSubscriptionList: MutableSet<SendChannel<ElectrumMessage>> = mutableSetOf()

    private var state: ClientState = ClientClosed
        set(value) {
            if (value != field) logger.info { "Updated State: $field -> $value" }
            field = value
        }

    suspend fun start() {
        coroutineScope {
            launch { run() }
            launch { listenToSocketInput() }
            launch { eventChannel.send(Connected) }
            launch { pingPeriodically() }
        }
    }

    private suspend fun run() {
        eventChannel.consumeEach { event ->
            logger.info { "Event received: $event" }

            val (newState, actions) = state.process(event)
            state = newState

            actions.forEach { action ->
                logger.info { "Execute action: $action" }
                when (action) {
                    is SendRequest -> socketOutputChannel.send(action.request)
                    is SendHeader -> action.run {
                        requestor.send(HeaderSubscriptionResponse(height, blockHeader))
                    }
                    is SendResponse -> action.requestor?.send(action.response) ?: sendMessage(action.response)
                    is BroadcastHeaderSubscription -> headerSubscriptionList.forEach { channel ->
                        channel.send(action.headerSubscriptionResponse)
                    }
                    is BroadcastScriptHashSubscription -> scriptHashSubscriptionMap[action.response.scriptHash]?.forEach { channel ->
                        channel.send(action.response)
                    }
                    is BroadcastState ->
                        statusSubscriptionList.forEach { it.send(action.state as ElectrumMessage) }
                    is AddStatusListener ->
                        statusSubscriptionList.add(action.listener)
                    is AddHeaderListener -> headerSubscriptionList.add(action.listener)
                    is AddScriptHashListener -> {
                        scriptHashSubscriptionMap[action.scriptHash] =
                            scriptHashSubscriptionMap.getOrElse(action.scriptHash, { setOf() }) + action.listener
                    }
                    is RemoveStatusListener -> statusSubscriptionList.remove(action.listener)
                    is RemoveHeaderListener -> headerSubscriptionList.remove(action.listener)
                    is RemoveScriptHashListener -> scriptHashSubscriptionMap -= scriptHashSubscriptionMap.filterValues { it == action.listener }.keys
                    is Restart -> {
                        delay(1_000); eventChannel.send(Connected)
                    }
                }
            }
        }
    }

    suspend fun sendMessage(message: ElectrumMessage) {
        when(message) {
            is ElectrumStatusSubscription -> eventChannel.send(RegisterStatusListener(message.listener))
            is ElectrumHeaderSubscription -> eventChannel.send(RegisterHeaderNotificationListener(message.listener))
            is ElectrumSendRequest -> eventChannel.send(SendElectrumRequest(message.electrumRequest, message.requestor))
            is ElectrumResponse -> eventChannel.send(ReceivedResponse(Either.Left(message)))
            else -> logger.info { "ElectrumClient does not handle messages like: $message" }
        }
    }

    private suspend fun pingPeriodically() {
        while(true) {
            logger.info { "Ping Electrum Server" }
            delay(30_000)
            eventChannel.send(SendElectrumRequest(Ping))
        }
    }

    private suspend fun listenToSocketInput() {
        socketInputChannel.consumeEach {
            logger.info { "Electrum response received: $it" }
            eventChannel.send(ReceivedResponse(it))
        }
    }

    companion object {
        const val ELECTRUM_CLIENT_NAME = "3.3.6"
        const val ELECTRUM_PROTOCOL_VERSION = "1.4"
        val version = ServerVersion()
        val logger = LoggerFactory.default.newLogger(ElectrumClient::class)
        internal fun computeScriptHash(publicKeyScript: ByteVector): ByteVector32 = Crypto.sha256(publicKeyScript).toByteVector32().reversed()
    }
}