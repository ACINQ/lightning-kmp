package fr.acinq.eklair.blockchain.electrum

import fr.acinq.bitcoin.BlockHeader
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.kodein.log.LoggerFactory
import org.kodein.log.newLogger

private sealed class WatcherEvent : ActorMessage
private object WatcherConnected : WatcherEvent()
private object HeaderSubscriptionEvent: WatcherEvent()

private sealed class WatcherAction
private object WatchHeaders : WatcherAction()

/**
 * [WatcherState] State
 *
 *                   ElectrumClient
 *                         ^
 *                         |
 *  -> Start               | statusSubscription()
 *      |                  | headerSubscription()
 *      +                  |
 * Disconnected <-----> Running
 * ^          |         ^     |
 * +----------+         +-----+
 *
 */
private sealed class WatcherState {
    abstract fun process(event: ActorMessage): Pair<WatcherState, List<WatcherAction>>
}
private object WatcherDisconnectedState : WatcherState() {
    override fun process(event: ActorMessage): Pair<WatcherState, List<WatcherAction>> =
        when(event) {
            is Running -> this to listOf(WatchHeaders)
            is HeaderSubscriptionResponse -> WatcherRunningState(height = event.height, tip = event.header) to
                    listOf() // TODO watches / publishQueue / getTxQueue?
            else -> unhandledEvent(event)
        }
}
private data class WatcherRunningState(val height: Int, val tip: BlockHeader) : WatcherState() {
    override fun process(event: ActorMessage): Pair<WatcherState, List<WatcherAction>> = when {
        event is HeaderSubscriptionResponse && event.header == tip -> self()
        event is HeaderSubscriptionResponse -> {

            this.copy(height = event.height, tip = event.header) to listOf() // TODO
        }
        else -> unhandledEvent(event)
    }
}

private fun WatcherState.unhandledEvent(event: ActorMessage): Nothing = error("The state $this cannot process the event $event")
private fun WatcherState.self(): Pair<WatcherState, List<WatcherAction>> = this to emptyList()

@OptIn(ExperimentalCoroutinesApi::class)
class ElectrumWatcher(
    val electrumClient: ElectrumClient
) {

    private val eventChannel = Channel<ActorMessage>(Channel.BUFFERED)
    private var state: WatcherState = WatcherDisconnectedState

    private suspend fun run() {
        eventChannel.consumeEach { message ->
            logger.info { "Message received: $message" }

            val (newState, actions) = state.process(message)

            if (newState != state)
                logger.info { "Updated State: $state -> $newState" }
            state = newState

            actions.forEach { action ->
                when (action) {
                    WatchHeaders -> electrumClient.send(AddHeaderSubscription(eventChannel))
                }
            }
        }
    }

    suspend fun start() {
        coroutineScope {
            electrumClient.send(AddStatusSubscription(eventChannel))
            launch { run() }
        }
    }

    suspend fun stop() {
        eventChannel.close()
        electrumClient.send(Unsubscribe(eventChannel))
    }


    companion object {
        private val logger = LoggerFactory.default.newLogger(ElectrumWatcher::class)
    }
}