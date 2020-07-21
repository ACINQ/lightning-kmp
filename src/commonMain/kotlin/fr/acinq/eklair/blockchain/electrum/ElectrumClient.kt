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

sealed class ElectrumClientEvent : ElectrumMessage
object Connected : ElectrumClientEvent()
object Disconnected : ElectrumClientEvent()
data class ReceivedResponse(val response: Either<ElectrumResponse, JsonRPCResponse>) : ElectrumClientEvent()

data class SendElectrumRequest(val electrumRequest: ElectrumRequest, val requestor: SendChannel<ElectrumMessage>) : ElectrumClientEvent()

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
//data class AddAddressListener(val address: String, val listener: SendChannel<ElectrumMessage>) : ElectrumClientAction()
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
sealed class ElectrumClientState : ElectrumMessage {
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

    abstract fun process(message: ElectrumMessage): Pair<ElectrumClientState, List<ElectrumClientAction>>
}

object WaitingForVersion : ElectrumClientState() {
    override fun process(message: ElectrumMessage): Pair<ElectrumClientState, List<ElectrumClientAction>> = when {
            message is ReceivedResponse && message.response is Either.Right -> {
                val electrumResponse = parseJsonResponse(version, message.response.value)
                logger.info { "Response received: $electrumResponse" }
                when (electrumResponse) {
                    is ServerVersionResponse -> {
                        WaitingForTip to listOf(sendRequest(HeaderSubscription))
                    }
                    else -> ElectrumClientClosed to listOf(Restart)
                }
            }
            message is AddStatusSubscription -> this to listOf(AddStatusListener(message.watcher))
            else -> unhandled(message)
        }
    }

object WaitingForTip : ElectrumClientState() {
    override fun process(message: ElectrumMessage): Pair<ElectrumClientState, List<ElectrumClientAction>> =
        when(message) {
            is ReceivedResponse -> {
                when (val rpcResponse = message.response) {
                    is Either.Right ->
                        when (val response = parseJsonResponse(HeaderSubscription, rpcResponse.value)) {
                            is HeaderSubscriptionResponse -> {
                                val state = ElectrumClientRunning(height = response.height, tip = response.header)
                                state to listOf(BroadcastState(state), BroadcastHeaderSubscription(response))
                            }
                            else -> ElectrumClientClosed to listOf(BroadcastState(ElectrumClientClosed), Restart)
                        }
                    else -> this to emptyList()
                }
            }
            is AddStatusSubscription -> this to listOf(AddStatusListener(message.watcher))
            else -> unhandled(message)
        }
}

data class ElectrumClientRunning(val height: Int, val tip: BlockHeader) : ElectrumClientState() {
   override fun process(message: ElectrumMessage): Pair<ElectrumClientState, List<ElectrumClientAction>> = when (message) {
       is AddStatusSubscription -> returnState(AddStatusListener(message.watcher), BroadcastState(this))
       is AddHeaderSubscription -> returnState(AddHeaderListener(message.watcher), SendHeader(
           height,
           tip,
           message.watcher
       ))
       is ElectrumRequest -> returnState(sendRequest(message))
       is SendElectrumRequest -> returnState(buildList {
           add(sendRequest(message.electrumRequest, message.requestor))
           if (message.electrumRequest is ScriptHashSubscription)
               add(AddScriptHashListener(message.electrumRequest.scriptHash, message.requestor))
       })
       is ReceivedResponse -> when (val response = message.response) {
           is Either.Left -> when (val electrumResponse = response.value) {
               is HeaderSubscriptionResponse -> {
                   newState {
                       state = copy(height = electrumResponse.height, tip = electrumResponse.header)
                       actions = listOf(BroadcastHeaderSubscription(electrumResponse))
                   }
               }
               is ScriptHashSubscriptionResponse -> {
                   returnState(BroadcastScriptHashSubscription(electrumResponse))
               }
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
            case AddStatusListener(actor)
            case HeaderSubscription(actor)
            case request: Request
            case Right(json: JsonRPCResponse)
            case Left(response: HeaderSubscriptionResponse)
            case Left(response: AddressSubscriptionResponse)
            case Left(response: ScriptHashSubscriptionResponse)
            case HeaderSubscriptionResponse(height, newtip)
        */

       else -> unhandled(message)
   }
}

internal object ElectrumClientClosed : ElectrumClientState() {
    override fun process(message: ElectrumMessage): Pair<ElectrumClientState, List<ElectrumClientAction>> =
        when(message) {
            Connected -> {
                newState {
                    state = WaitingForVersion
                    actions = listOf(SendRequest(version.asJsonRPCRequest()))
                }
            }
            is AddStatusSubscription -> returnState(AddStatusListener(message.watcher))
            else -> unhandled(message)
        }
}

private fun ElectrumClientState.unhandled(message: ElectrumMessage) : Pair<ElectrumClientState, List<ElectrumClientAction>> =
    when (message) {
        is Unsubscribe -> returnState(UnsubscribeAction(message.watcher))
        is Ping -> returnState(sendRequest(Ping))
        is PingResponse -> returnState()
        else -> error("The state $this cannot process the event $message")
    }

private class ClientStateBuilder {
    var state: ElectrumClientState = ElectrumClientClosed
    var actions = emptyList<ElectrumClientAction>()
    fun build() = state to actions
}
private fun newState(init: ClientStateBuilder.() -> Unit) = ClientStateBuilder().apply(init).build()
private fun newState(newState: ElectrumClientState) = ClientStateBuilder().apply { state = newState }.build()

private fun ElectrumClientState.returnState(actions: List<ElectrumClientAction> = emptyList()): Pair<ElectrumClientState, List<ElectrumClientAction>> = this to actions
private fun ElectrumClientState.returnState(action: ElectrumClientAction): Pair<ElectrumClientState, List<ElectrumClientAction>> = this to listOf(action)
private fun ElectrumClientState.returnState(vararg actions: ElectrumClientAction): Pair<ElectrumClientState, List<ElectrumClientAction>> = this to listOf(*actions)

@OptIn(ExperimentalCoroutinesApi::class)
class ElectrumClient(
    // TODO to be internalized with socket implementation
    private val socketInputChannel: Channel<Either<ElectrumResponse, JsonRPCResponse>> = Channel(),
    // TODO to be internalized with socket implementation
    private val socketOutputChannel: SendChannel<String>) {

    val events: Channel<ElectrumMessage> = Channel(Channel.BUFFERED)

    // TODO not needed? private val addressSubscriptionMap: MutableMap<String, Set<SendChannel<ElectrumMessage>>> = mutableMapOf()
    private val scriptHashSubscriptionMap: MutableMap<ByteVector32, Set<SendChannel<ElectrumMessage>>> = mutableMapOf()
    private val statusSubscriptionList: MutableSet<SendChannel<ElectrumMessage>> = mutableSetOf()
    private val headerSubscriptionList: MutableSet<SendChannel<ElectrumMessage>> = mutableSetOf()

    private var state: ElectrumClientState = ElectrumClientClosed
        set(value) {
            if (value != field) logger.info { "Updated State: $field -> $value" }
            field = value
        }

    suspend fun start() {
        coroutineScope {
            launch { run() }
            launch { listenToSocketInput() }
            launch { events.send(Connected) }
            launch { pingPeriodically() }
        }
    }

    private suspend fun run() {
        events.consumeEach { message ->
            logger.info { "Message received: $message" }

            val (newState, actions) = state.process(message)
            state = newState

            actions.forEach { action ->
                logger.info { action.toString() }
                when (action) {
                    is SendRequest -> socketOutputChannel.send(action.request)
                    is SendHeader -> action.run {
                        requestor.send(HeaderSubscriptionResponse(height, blockHeader))
                    }
                    is SendResponse -> (action.requestor ?: events).send(action.response)
                    is BroadcastHeaderSubscription -> headerSubscriptionList.forEach { channel ->
                        channel.send(action.headerSubscriptionResponse)
                    }
                    is BroadcastScriptHashSubscription -> scriptHashSubscriptionMap[action.response.scriptHash]?.forEach { channel ->
                        channel.send(action.response)
                    }
                    is BroadcastState -> statusSubscriptionList.forEach { it.send(action.state) }
                    is AddStatusListener -> statusSubscriptionList.add(action.listener)
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
                        delay(1_000); events.send(Connected)
                    }
                }
            }
        }
    }

    private suspend fun pingPeriodically() {
        while(true) {
            logger.info { "Ping Electrum Server" }
            delay(30_000)
            events.send(Ping)
        }
    }

    private suspend fun listenToSocketInput() {
        socketInputChannel.consumeEach {
            logger.info { "Electrum response received: $it" }
            events.send(ReceivedResponse(it))
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