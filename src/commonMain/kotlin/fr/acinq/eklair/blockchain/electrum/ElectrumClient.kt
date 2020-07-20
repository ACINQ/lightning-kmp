package fr.acinq.eklair.blockchain.electrum

import fr.acinq.bitcoin.BlockHeader
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.eklair.blockchain.electrum.ElectrumClient.Companion.logger
import fr.acinq.eklair.blockchain.electrum.ElectrumClient.Companion.version
import fr.acinq.eklair.utils.Either
import fr.acinq.eklair.utils.JsonRPCResponse
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
data class Unsubscribe(val watcher: SendChannel<ElectrumMessage>): ElectrumClientEvent()

sealed class ElectrumClientAction
data class SendRequest(val request: String): ElectrumClientAction()
data class SendHeader(val height: Int, val blockHeader: BlockHeader, val requestor: SendChannel<ElectrumMessage>) : ElectrumClientAction()
data class SendResponse(val response: ElectrumResponse, val requestor: SendChannel<ElectrumMessage>? = null) : ElectrumClientAction()

data class BroadcastHeaderSubscription(val headerSubscriptionResponse: HeaderSubscriptionResponse): ElectrumClientAction()
data class BroadcastState(val state: ElectrumClientState): ElectrumClientAction()

data class AddStatusListener(val listener: SendChannel<ElectrumMessage>) : ElectrumClientAction()
data class RemoveStatusListener(val listener: SendChannel<ElectrumMessage>) : ElectrumClientAction()
data class AddHeaderListener(val listener: SendChannel<ElectrumMessage>) : ElectrumClientAction()
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
       is AddStatusSubscription -> this to listOf(AddStatusListener(message.watcher), BroadcastState(this))
       is AddHeaderSubscription -> this to listOf(AddHeaderListener(message.watcher), SendHeader(
           height,
           tip,
           message.watcher
       ))
       is ElectrumRequest -> this to listOf(sendRequest(message))
       is SendElectrumRequest -> this to listOf(sendRequest(message.electrumRequest, message.requestor))
       is ReceivedResponse -> when (val response = message.response) {
           is Either.Left -> when (val electrumResponse = response.value) {
               is HeaderSubscriptionResponse -> {
                   this.copy(
                       height = electrumResponse.height,
                       tip = electrumResponse.header
                   ) to listOf(BroadcastHeaderSubscription(electrumResponse))
               }
               else -> self()
           }
           is Either.Right -> {
               this to buildList {
                   requests[response.value.id]?.let { (originRequest, requestor) ->
                       add(SendResponse(parseJsonResponse(originRequest, response.value), requestor))
                   }
               }
           }
       }
       else -> unhandled(message)
   }
}

internal object ElectrumClientClosed : ElectrumClientState() {
    override fun process(message: ElectrumMessage): Pair<ElectrumClientState, List<ElectrumClientAction>> =
        when(message) {
            Connected -> {
                WaitingForVersion to listOf(SendRequest(version.asJsonRPCRequest()))
            }
            is AddStatusSubscription -> this to listOf(AddStatusListener(message.watcher))
            else -> unhandled(message)
        }
}

private fun ElectrumClientState.unhandled(message: ElectrumMessage) : Pair<ElectrumClientState, List<ElectrumClientAction>> =
    when (message) {
        is Unsubscribe -> this to listOf(UnsubscribeAction(message.watcher))
        is Ping -> this to listOf(sendRequest(Ping))
        is PingResponse -> self()
        else -> error("The state $this cannot process the event $message")
    }
private fun ElectrumClientState.self(): Pair<ElectrumClientState, List<ElectrumClientAction>> = this to emptyList()

@OptIn(ExperimentalCoroutinesApi::class)
class ElectrumClient(
    // TODO to be internalized with socket implementation
    private val socketInputChannel: Channel<Either<ElectrumResponse, JsonRPCResponse>> = Channel(),
    // TODO to be internalized with socket implementation
    private val socketOutputChannel: SendChannel<String>) {

    val events: Channel<ElectrumMessage> = Channel(Channel.BUFFERED)

    private val addressSubscriptionMap: MutableMap<String, SendChannel<ElectrumMessage>> = mutableMapOf()
    private val scriptHashSubscriptionMap: MutableMap<ByteVector32, SendChannel<ElectrumMessage>> = mutableMapOf()
    private val statusSubscriptionList: MutableList<SendChannel<ElectrumMessage>> = mutableListOf()
    private val headerSubscriptionList: MutableList<SendChannel<ElectrumMessage>> = mutableListOf()

    private var state: ElectrumClientState = ElectrumClientClosed

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

            if (newState != state)
                logger.info { "Updated State: $state -> $newState" }
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
                        if (channel.isClosedForSend)
                            headerSubscriptionList.remove(channel)
                        else
                            channel.send(action.headerSubscriptionResponse)
                    }
                    is BroadcastState -> statusSubscriptionList.forEach { it.send(action.state) }
                    is AddStatusListener -> statusSubscriptionList.add(action.listener)
                    is AddHeaderListener -> headerSubscriptionList.add(action.listener)
                    is RemoveStatusListener -> statusSubscriptionList.remove(action.listener)
                    is RemoveHeaderListener -> headerSubscriptionList.remove(action.listener)
                    is UnsubscribeAction -> {
                        statusSubscriptionList.remove(action.listener)
                        headerSubscriptionList.remove(action.listener)
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
    }
}