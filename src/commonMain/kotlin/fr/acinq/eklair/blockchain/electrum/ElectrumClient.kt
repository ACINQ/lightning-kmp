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

data class AddStatusSubscription(val watcher: SendChannel<ElectrumMessage>) : ElectrumClientEvent()
data class RemoveStatusSubscription(val watcher: SendChannel<ElectrumMessage>) : ElectrumClientEvent()
data class AddHeaderSubscription(val watcher: SendChannel<ElectrumMessage>) : ElectrumClientEvent()
data class RemoveHeaderSubscription(val watcher: SendChannel<ElectrumMessage>) : ElectrumClientEvent()
data class Unsubscribe(val watcher: SendChannel<ElectrumMessage>): ElectrumClientEvent()

sealed class ElectrumClientAction
data class SendRequest(val request: String): ElectrumClientAction()

data class SendHeader(val channel: SendChannel<ElectrumMessage>, val height: Int, val blockHeader: BlockHeader) : ElectrumClientAction()
data class BroadcastHeaderSubscription(val headerSubscriptionResponse: HeaderSubscriptionResponse): ElectrumClientAction()
data class BroadcastState(val state: ElectrumClientState): ElectrumClientAction()

data class AddStatusListener(val actorChannel: SendChannel<ElectrumMessage>) : ElectrumClientAction()
data class RemoveStatusListener(val actorChannel: SendChannel<ElectrumMessage>) : ElectrumClientAction()
data class AddHeaderListener(val actorChannel: SendChannel<ElectrumMessage>) : ElectrumClientAction()
data class RemoveHeaderListener(val actorChannel: SendChannel<ElectrumMessage>) : ElectrumClientAction()
data class UnsubscribeAction(val actorChannel: SendChannel<ElectrumMessage>) : ElectrumClientAction()

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
    abstract fun process(message: ElectrumMessage): Pair<ElectrumClientState, List<ElectrumClientAction>>
}

object WaitingForVersion : ElectrumClientState() {
    override fun process(message: ElectrumMessage): Pair<ElectrumClientState, List<ElectrumClientAction>> = when {
            message is ReceivedResponse && message.response is Either.Right -> {
                val electrumResponse = parseJsonResponse(version, message.response.value)
                logger.info { "Response received: $electrumResponse" }
                when (electrumResponse) {
                    is ServerVersionResponse -> {
                        WaitingForTip to listOf(SendRequest(HeaderSubscription.asJsonRPCRequest()))
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

data class ElectrumClientRunning(val height: Int, val tip: BlockHeader, private val requests: Map<String, Pair<ElectrumRequest, SendChannel<ElectrumMessage>>> = mapOf()) : ElectrumClientState() {
    /**
     * Unique ID to match response with origin request
     */
    private var requestId = 0

    override fun process(message: ElectrumMessage): Pair<ElectrumClientState, List<ElectrumClientAction>> = when {
        message is AddStatusSubscription -> this to listOf(AddStatusListener(message.watcher), BroadcastState(this))
        message is AddHeaderSubscription -> this to listOf(AddHeaderListener(message.watcher), SendHeader(message.watcher, height, tip))
        message is ReceivedResponse
                && message.response is Either.Left -> when(val electrumResponse = message.response.value) {
                    is HeaderSubscriptionResponse -> {
                        this.copy(
                            height = electrumResponse.height,
                            tip = electrumResponse.header
                        ) to listOf(BroadcastHeaderSubscription(electrumResponse))
                    }
                    else -> self()
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

private fun ElectrumClientState.unhandled(event: ElectrumMessage) : Pair<ElectrumClientState, List<ElectrumClientAction>> = when(event) {
    is Unsubscribe -> this to listOf(UnsubscribeAction(event.watcher))
    else -> error("The state $this cannot process the event $event")
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
                        channel.send(HeaderSubscriptionResponse(height, blockHeader))
                    }
                    is BroadcastHeaderSubscription -> headerSubscriptionList.forEach { channel ->
                        if (channel.isClosedForSend)
                            headerSubscriptionList.remove(channel)
                        else
                            channel.send(action.headerSubscriptionResponse)
                    }
                    is BroadcastState -> statusSubscriptionList.forEach { it.send(action.state) }
                    is AddStatusListener -> statusSubscriptionList.add(action.actorChannel)
                    is AddHeaderListener -> headerSubscriptionList.add(action.actorChannel)
                    is RemoveStatusListener -> statusSubscriptionList.remove(action.actorChannel)
                    is RemoveHeaderListener -> headerSubscriptionList.remove(action.actorChannel)
                    is UnsubscribeAction -> {
                        statusSubscriptionList.remove(action.actorChannel)
                        headerSubscriptionList.remove(action.actorChannel)
                    }
                    is Restart -> {
                        delay(1_000); events.send(Connected)
                    }
                }
            }
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