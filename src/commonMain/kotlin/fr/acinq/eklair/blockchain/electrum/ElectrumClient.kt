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

sealed class ElectrumClientEvent
object Connected : ElectrumClientEvent()
object Disconnected : ElectrumClientEvent()
data class ReceivedResponse(val response: Either<ElectrumResponse, JsonRPCResponse>) : ElectrumClientEvent()

data class SendElectrumRequest(val electrumRequest: ElectrumRequest, val requestor: SendChannel<ElectrumMessage>? = null) : ElectrumClientEvent()

data class AddStatusSubscription(val watcher: SendChannel<ElectrumMessage>) : ElectrumClientEvent()
data class RemoveStatusSubscription(val watcher: SendChannel<ElectrumMessage>) : ElectrumClientEvent()
data class AddHeaderSubscription(val watcher: SendChannel<ElectrumMessage>) : ElectrumClientEvent()
data class RemoveHeaderSubscription(val watcher: SendChannel<ElectrumMessage>) : ElectrumClientEvent()
data class AddScriptHashSubscription(val scriptHash: ByteVector32, val watcher: SendChannel<ElectrumMessage>) : ElectrumClientEvent()
//data class AddAddressSubscription(val address: String, val watcher: SendChannel<ElectrumMessage>) : ElectrumClientEvent()
data class Unsubscribe(val watcher: SendChannel<ElectrumMessage>): ElectrumClientEvent()

sealed class ElectrumClientAction
data class SendRequest(val request: String): ElectrumClientAction()
data class SendHeader(val height: Int, val blockHeader: BlockHeader, val requestor: SendChannel<ElectrumMessage>) : ElectrumClientAction()
data class SendResponse(val response: ElectrumResponse, val requestor: SendChannel<ElectrumMessage>? = null) : ElectrumClientAction()

data class BroadcastHeaderSubscription(val headerSubscriptionResponse: HeaderSubscriptionResponse): ElectrumClientAction()
data class BroadcastScriptHashSubscription(val response: ScriptHashSubscriptionResponse): ElectrumClientAction()
data class BroadcastState(val state: ElectrumClientState): ElectrumClientAction()

data class AddStatusListener(val listener: SendChannel<ElectrumMessage>) : ElectrumClientAction()
data class RemoveStatusListener(val listener: SendChannel<ElectrumMessage>) : ElectrumClientAction()
data class AddHeaderListener(val listener: SendChannel<ElectrumMessage>) : ElectrumClientAction()
data class AddScriptHashListener(val scriptHash: ByteVector32, val listener: SendChannel<ElectrumMessage>) : ElectrumClientAction()
data class RemoveHeaderListener(val listener: SendChannel<ElectrumMessage>) : ElectrumClientAction()
data class UnsubscribeAction(val listener: SendChannel<ElectrumMessage>) : ElectrumClientAction()

private object Restart : ElectrumClientAction()

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
private sealed class ClientState {
    /**
     * Unique ID to match response with origin request
     */
    private var currentRequestId = 0
    internal val requests: MutableMap<Int, Pair<ElectrumRequest, SendChannel<ElectrumMessage>?>> = mutableMapOf()

    internal fun sendRequest(electrumRequest: ElectrumRequest, requestor: SendChannel<ElectrumMessage>? = null): SendRequest {
        val newRequestId = currentRequestId++
        requests[newRequestId] = electrumRequest to requestor
        return SendRequest(electrumRequest.asJsonRPCRequest(newRequestId))
    }

    abstract fun process(event: ElectrumClientEvent): Pair<ClientState, List<ElectrumClientAction>>
}

private object WaitingForVersion : ClientState() {
    override fun process(event: ElectrumClientEvent): Pair<ClientState, List<ElectrumClientAction>> = when {
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
            event is AddStatusSubscription -> returnState(AddStatusListener(event.watcher))
            else -> unhandled(event)
        }
    }

private object WaitingForTip : ClientState() {
    override fun process(event: ElectrumClientEvent): Pair<ClientState, List<ElectrumClientAction>> =
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
            is AddStatusSubscription -> returnState(AddStatusListener(event.watcher))
            else -> unhandled(event)
        }
}

private data class ClientRunning(val height: Int, val tip: BlockHeader) : ClientState() {
   override fun process(event: ElectrumClientEvent): Pair<ClientState, List<ElectrumClientAction>> = when (event) {
       is AddStatusSubscription -> returnState(AddStatusListener(event.watcher), BroadcastState(ElectrumClientReady))
       is AddHeaderSubscription -> returnState(
           AddHeaderListener(event.watcher),
           SendHeader(height, tip, event.watcher)
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

private object ClientClosed : ClientState() {
    override fun process(event: ElectrumClientEvent): Pair<ClientState, List<ElectrumClientAction>> =
        when(event) {
            Connected -> newState {
                state = WaitingForVersion
                actions = listOf(SendRequest(version.asJsonRPCRequest()))
            }
            is AddStatusSubscription -> returnState(AddStatusListener(event.watcher))
            else -> unhandled(event)
        }
}

private fun ClientState.unhandled(event: ElectrumClientEvent) : Pair<ClientState, List<ElectrumClientAction>> =
    when (event) {
        is Unsubscribe -> returnState(UnsubscribeAction(event.watcher))
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

    private val eventChannel: Channel<ElectrumClientEvent> = Channel(Channel.BUFFERED)
    val messageChannel: Channel<ElectrumMessage> = Channel(Channel.BUFFERED)

    private val input = CoroutineScope(Dispatchers.Default).produce(capacity = Channel.BUFFERED) {
        launch { eventChannel.consumeEach { send(it) } }
        launch { messageChannel.consumeEach { send(it) } }
    }

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
        input.consumeEach { input ->
            logger.info { "Input received: $input" }

            when(input) {
                is ElectrumClientEvent -> process(input)
                is ElectrumMessage -> {
                    when(input) {
                        is ElectrumStatusSubscription -> eventChannel.send(AddStatusSubscription(input.listener))
                        is ElectrumHeaderSubscription -> eventChannel.send(AddHeaderSubscription(input.listener))
                        is ElectrumSendRequest -> eventChannel.send(SendElectrumRequest(input.electrumRequest, input.requestor))
                        is ElectrumResponse -> eventChannel.send(ReceivedResponse(Either.Left(input)))
                    }
                }
            }
        }
    }

    private suspend fun process(event: ElectrumClientEvent) {
        val (newState, actions) = state.process(event)
        state = newState

        actions.forEach { action ->
            logger.info { "Execute action: $action" }
            when (action) {
                is SendRequest -> socketOutputChannel.send(action.request)
                is SendHeader -> action.run {
                    requestor.send(HeaderSubscriptionResponse(height, blockHeader))
                }
                is SendResponse -> (action.requestor ?: messageChannel).send(action.response)
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
                is UnsubscribeAction -> {
                    statusSubscriptionList.remove(action.listener)
                    headerSubscriptionList.remove(action.listener)
                    scriptHashSubscriptionMap -= scriptHashSubscriptionMap.filterValues { it == action.listener }.keys
                }
                is Restart -> {
                    delay(1_000); eventChannel.send(Connected)
                }
            }
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