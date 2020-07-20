package fr.acinq.eklair.blockchain.electrum

import fr.acinq.bitcoin.BlockHeader
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.kodein.log.LoggerFactory
import org.kodein.log.newLogger

private sealed class WatcherEvent : ElectrumMessage
private object WatcherStart: WatcherEvent()

private sealed class WatcherAction
private object RegisterToStatusSubscription : WatcherAction()
private object RegisterToHeaderSubscription : WatcherAction()

/**
 * [ElectrumWatcherState] State
 *
 *                   ElectrumClient
 *                         ^
 *                         |
 *  -> Start               | subscriptions:
 *      |                  | - status
 *      |                  | - header
 *      |                  | - addresses
 *      |                  | - scriptHashes
 *      +                  |
 * Disconnected <-----> Running
 * ^          |         ^     |
 * +----------+         +-----+
 *
 */
private sealed class ElectrumWatcherState {
    abstract fun process(event: ElectrumMessage): Pair<ElectrumWatcherState, List<WatcherAction>>
}
private object ElectrumWatcherDisconnected : ElectrumWatcherState() {
    override fun process(event: ElectrumMessage): Pair<ElectrumWatcherState, List<WatcherAction>> =
        when(event) {
            is WatcherStart -> this to listOf(RegisterToStatusSubscription)
            is ElectrumClientRunning -> this to listOf(RegisterToHeaderSubscription)
            is HeaderSubscriptionResponse -> ElectrumWatcherRunning(height = event.height, tip = event.header) to
                    listOf() // TODO watches / publishQueue / getTxQueue?
            else -> unhandled(event)
        }
}
private data class ElectrumWatcherRunning(val height: Int, val tip: BlockHeader) : ElectrumWatcherState() {
    override fun process(event: ElectrumMessage): Pair<ElectrumWatcherState, List<WatcherAction>> = when {
        event is HeaderSubscriptionResponse && event.header == tip -> self()
        event is HeaderSubscriptionResponse -> this.copy(height = event.height, tip = event.header) to listOf() // TODO
        event is ElectrumClientClosed -> self()
        else -> unhandled(event)
    }
}

private fun ElectrumWatcherState.unhandled(event: ElectrumMessage):Pair<ElectrumWatcherState, List<WatcherAction>> =
    when (event) {
        is ElectrumClientClosed -> ElectrumWatcherDisconnected to emptyList()
        else -> error("The state $this cannot process the event $event")
    }
private fun ElectrumWatcherState.self(): Pair<ElectrumWatcherState, List<WatcherAction>> = this to emptyList()

@OptIn(ExperimentalCoroutinesApi::class)
class ElectrumWatcher(
    val client: ElectrumClient
) {

    private val events = Channel<ElectrumMessage>(Channel.BUFFERED)
    private var state: ElectrumWatcherState = ElectrumWatcherDisconnected

    private suspend fun run() {
        events.consumeEach { message ->
            logger.info { "Message received: $message" }
            val (newState, actions) = state.process(message)

            if (newState != state)
                logger.info { "Updated State: $state -> $newState" }
            state = newState

            actions.forEach { action ->
                when (action) {
                    RegisterToStatusSubscription -> client.events.send(AddStatusSubscription(events))
                    RegisterToHeaderSubscription -> client.events.send(AddHeaderSubscription(events))
                }
            }
        }
    }

    suspend fun start() {
        coroutineScope {
            events.send(WatcherStart)
            launch { run() }
        }
    }

    suspend fun askForHeader() {
        client.events.send(SendElectrumRequest(GetHeader(100), events))
    }

    suspend fun stop() {
        client.events.send(Unsubscribe(events))
        events.close()
    }

    companion object {
        private val logger = LoggerFactory.default.newLogger(ElectrumWatcher::class)
    }
}