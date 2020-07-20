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

sealed class ElectrumEvent : ActorMessage
object Connected : ElectrumEvent()
object Disconnected : ElectrumEvent()
data class ReceivedResponse(val response: Either<ElectrumResponse, JsonRPCResponse>) : ElectrumEvent()

data class AddStatusSubscription(val actorChannel: SendChannel<ActorMessage>) : ElectrumEvent()
data class RemoveStatusSubscription(val actorChannel: SendChannel<ActorMessage>) : ElectrumEvent()
data class AddHeaderSubscription(val actorChannel: SendChannel<ActorMessage>) : ElectrumEvent()
data class RemoveHeaderSubscription(val actorChannel: SendChannel<ActorMessage>) : ElectrumEvent()
data class Unsubscribe(val actorChannel: SendChannel<ActorMessage>): ElectrumEvent()

sealed class Action
data class SendRequest(val request: String): Action()
data class BroadcastHeaderSubscription(val headerSubscriptionResponse: HeaderSubscriptionResponse): Action()
data class BroadcastState(val state: ElectrumState): Action()

data class SendHeader(val actorChannel: SendChannel<ActorMessage>, val height: Int, val blockHeader: BlockHeader?) : Action()

data class AddStatusListener(val actorChannel: SendChannel<ActorMessage>) : Action()
data class RemoveStatusListener(val actorChannel: SendChannel<ActorMessage>) : Action()
data class AddHeaderListener(val actorChannel: SendChannel<ActorMessage>) : Action()
data class RemoveHeaderListener(val actorChannel: SendChannel<ActorMessage>) : Action()
data class UnsubscribeAction(val actorChannel: SendChannel<ActorMessage>) : Action()

private object Restart : Action()

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
sealed class ElectrumState : ActorMessage {
    abstract fun process(event: ActorMessage): Pair<ElectrumState, List<Action>>
}

private object WaitingForVersion : ElectrumState() {
    override fun process(event: ActorMessage): Pair<ElectrumState, List<Action>> = when {
            event is ReceivedResponse && event.response is Either.Right -> {
                val electrumResponse = parseJsonResponse(version, event.response.value)
                logger.info { "Response received: $electrumResponse" }
                when (electrumResponse) {
                    is ServerVersionResponse -> {
                        WaitingForTip to listOf(SendRequest(HeaderSubscription.asJsonRPCRequest()))
                    }
                    else -> Closed to listOf(Restart)
                }
            }
            event is AddStatusSubscription -> this to listOf(AddStatusListener(event.actorChannel))
            else -> unhandledEvent(event)
        }
    }

private object WaitingForTip : ElectrumState() {
    override fun process(event: ActorMessage): Pair<ElectrumState, List<Action>> =
        when(event) {
            is ReceivedResponse -> {
                when (val rpcResponse = event.response) {
                    is Either.Right ->
                        when (val response = parseJsonResponse(HeaderSubscription, rpcResponse.value)) {
                            is HeaderSubscriptionResponse -> {
                                val state = Running(height = response.height, tip = response.header)
                                state to listOf(BroadcastState(state), BroadcastHeaderSubscription(response))
                            }
                            else -> Closed to listOf(BroadcastState(Closed), Restart)
                        }
                    else -> this to emptyList()
                }
            }
            is AddStatusSubscription -> this to listOf(AddStatusListener(event.actorChannel))
            else -> unhandledEvent(event)
        }
}

data class Running(val height: Int, val tip: BlockHeader, private val requests: Map<String, Pair<ElectrumRequest, SendChannel<ActorMessage>>> = mapOf()) : ElectrumState() {
    /**
     * Unique ID to match response with origin request
     */
    private var requestId = 0

    override fun process(event: ActorMessage): Pair<ElectrumState, List<Action>> = when {
        event is AddStatusSubscription -> this to listOf(AddStatusListener(event.actorChannel), BroadcastState(this))
        event is AddHeaderSubscription -> this to listOf(AddHeaderListener(event.actorChannel), SendHeader(event.actorChannel, height, tip))
        event is ReceivedResponse
                && event.response is Either.Left -> when(val electrumResponse = event.response.value) {
                    is HeaderSubscriptionResponse -> {
                        this.copy(
                            height = electrumResponse.height,
                            tip = electrumResponse.header
                        ) to listOf(BroadcastHeaderSubscription(electrumResponse))
                    }
                    else -> self()
                }
        else -> fallback(event)
    }
}

object Closed : ElectrumState() {
    override fun process(event: ActorMessage): Pair<ElectrumState, List<Action>> =
        when(event) {
            Connected -> {
                WaitingForVersion to listOf(SendRequest(version.asJsonRPCRequest()))
            }
            is AddStatusSubscription -> this to listOf(AddStatusListener(event.actorChannel))
            else -> unhandledEvent(event)
        }
}

private fun ElectrumState.unhandledEvent(event: ActorMessage): Nothing = error("The state $this cannot process the event $event")
private fun ElectrumState.fallback(event: ActorMessage) : Pair<ElectrumState, List<Action>> = when(event) {
    is Unsubscribe -> this to listOf(UnsubscribeAction(event.actorChannel))
    else -> self()
}
private fun ElectrumState.self(): Pair<ElectrumState, List<Action>> = this to emptyList()

@OptIn(ExperimentalCoroutinesApi::class)
class ElectrumClient(
    // TODO to be internalized with socket implementation
    private val socketInputChannel: Channel<Either<ElectrumResponse, JsonRPCResponse>> = Channel(),
    // TODO to be internalized with socket implementation
    private val socketOutputChannel: SendChannel<String>) {

    private val eventChannel: Channel<ActorMessage> = Channel(Channel.BUFFERED)

    private val addressSubscriptionMap: MutableMap<String, SendChannel<ActorMessage>> = mutableMapOf()
    private val scriptHashSubscriptionMap: MutableMap<ByteVector32, SendChannel<ActorMessage>> = mutableMapOf()
    private val statusSubscriptionList: MutableList<SendChannel<ActorMessage>> = mutableListOf()
    private val headerSubscriptionList: MutableList<SendChannel<ActorMessage>> = mutableListOf()

    private var state: ElectrumState = Closed


    suspend fun send(message: ActorMessage) {
        eventChannel.send(message)
    }

    suspend fun start() {
        coroutineScope {
            launch { run() }
            launch { listenToSocketInput() }
            launch { send(Connected) }
        }
    }

    private suspend fun run() {
        eventChannel.consumeEach { message ->
            logger.info { "Message received: $message" }

            val (newState, actions) = state.process(message)

            if (newState != state)
                logger.info { "Updated State: $state -> $newState" }
            state = newState

            actions.forEach { action ->
                logger.info { action.toString() }
                when (action) {
                    is SendRequest -> socketOutputChannel.send(action.request)
                    is SendHeader -> action.blockHeader?.let { tip ->
                        action.actorChannel.send(HeaderSubscriptionResponse(action.height, tip))
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
                        delay(1_000); eventChannel.send(Connected)
                    }
                }
            }
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
    }
}