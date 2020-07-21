package fr.acinq.eklair.blockchain.electrum

import fr.acinq.bitcoin.BlockHeader
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.Transaction
import fr.acinq.eklair.blockchain.*
import fr.acinq.eklair.blockchain.electrum.ElectrumClient.Companion.logger
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.channels.produce
import org.kodein.log.LoggerFactory
import org.kodein.log.newLogger

private sealed class ElectrumWatcherEvent : ElectrumMessage
private object WatcherStart: ElectrumWatcherEvent()
private object ClientReady
private class PublishEvent(val publishAsap: PublishAsap) : ElectrumWatcherEvent()
private class WatchTypeEvent(val watch: WatcherType) : ElectrumWatcherEvent()

private sealed class ElectrumWatcherAction
private object RegisterToStatusSubscription : ElectrumWatcherAction()
private object RegisterToHeaderSubscription : ElectrumWatcherAction()
private data class RegisterToScriptHashSubscription(val scriptHash: ByteVector32) : ElectrumWatcherAction()
private data class GetScriptHashHistoryAction(val scriptHash: ByteVector32) : ElectrumWatcherAction()
private data class PublishAsapAction(val publishAsap: PublishAsap) : ElectrumWatcherAction()

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
    // TODO change ElectrumMessage to ElectrumWatcherEvent
    abstract fun process(event: ElectrumMessage): Pair<ElectrumWatcherState, List<ElectrumWatcherAction>>
}
private object ElectrumWatcherDisconnected : ElectrumWatcherState() {
    override fun process(event: ElectrumMessage): Pair<ElectrumWatcherState, List<ElectrumWatcherAction>> =
        when(event) {
            is WatcherStart -> returnState(action = RegisterToStatusSubscription)
            is ElectrumClientRunning -> returnState(action = RegisterToHeaderSubscription)
            is HeaderSubscriptionResponse -> ElectrumWatcherRunning(height = event.height, tip = event.header) to listOf() // TODO watches / publishQueue / getTxQueue?
            else -> unhandled(event)
        }
}

private data class ElectrumWatcherRunning(
    val height: Int,
    val tip: BlockHeader,
    val watches: Set<Watch> = setOf(),
    val scriptHashStatus: Map<ByteVector32, String> = mapOf(),
    val block2tx: Map<Long, List<Transaction>> = mapOf(),
    val sent: ArrayDeque<Transaction> = ArrayDeque()
) : ElectrumWatcherState() {
    override fun process(event: ElectrumMessage): Pair<ElectrumWatcherState, List<ElectrumWatcherAction>> = when {
        event is HeaderSubscriptionResponse && event.header == tip -> returnState()
        event is HeaderSubscriptionResponse -> {
            val (newHeight, newTip) = event
            logger.info { "new tip: ${newTip.blockId} $newHeight" }
            val scriptHashesActions = watches.filterIsInstance<WatchConfirmed>().map {
                val scriptHash = ElectrumClient.computeScriptHash(it.publicKeyScript)
                GetScriptHashHistoryAction(scriptHash)
            }

            val toPublish = block2tx.filterKeys { it <= newHeight }
            val publishAsapActions = toPublish.values.flatten().map { PublishAsapAction(PublishAsap(it)) }

            newState {
                state = copy(height = newHeight, tip = newTip, block2tx = block2tx - toPublish.keys)
                actions = scriptHashesActions + publishAsapActions
            }
        }
        event is WatchTypeEvent -> when(val watch = event.watch) {
            is Watch -> when(watch) {
                in watches -> returnState()
                is WatchSpent -> {
                    val (txid, outputIndex, publicKeyScript, _) = watch
                    val scriptHash = ElectrumClient.computeScriptHash(publicKeyScript)
                    logger.info { "added watch-spent on output=$txid:$outputIndex scriptHash=$scriptHash" }
                    newState {
                        state = copy(watches = watches + watch)
                        actions = listOf(RegisterToScriptHashSubscription(scriptHash))
                    }
                }
                is WatchConfirmed -> TODO()
                else -> returnState()
            }
            is WatchEventSpent -> TODO()
            else -> returnState()
        }

        /*
        - OK:
            case ElectrumClient.HeaderSubscriptionResponse(newheight, newtip) if tip == newtip => ()
            case ElectrumClient.HeaderSubscriptionResponse(newheight, newtip)
            case watch: Watch if watches.contains(watch) => ()
        - TODO:
            case watch@WatchSpent(_, txid, outputIndex, publicKeyScript, _)
            case watch@WatchSpentBasic(_, txid, outputIndex, publicKeyScript, _)
            case watch@WatchConfirmed(_, txid, publicKeyScript, _, _)
            case ElectrumClient.ScriptHashSubscriptionResponse(scriptHash, status)
            case ElectrumClient.GetScriptHashHistoryResponse(_, history)
            case ElectrumClient.GetTransactionResponse(tx, Some(item: ElectrumClient.TransactionHistoryItem))
            case ElectrumClient.GetMerkleResponse(tx_hash, _, txheight, pos, Some(tx: Transaction))
            case ElectrumClient.GetTransactionResponse(tx, Some(origin: ActorRef))
            case ElectrumClient.ServerError(ElectrumClient.GetTransaction(txid, Some(origin: ActorRef)), _)
            case WatchEventConfirmed(BITCOIN_PARENT_TX_CONFIRMED(tx), blockHeight, _, _)
            case ElectrumClient.BroadcastTransactionResponse(tx, error_opt)
            case ElectrumClient.ElectrumDisconnected
            case PublishAsap(tx)
        - later:
            case Terminated(actor)
            case GetTxWithMeta(txid)
         */
        else -> unhandled(event)
    }
}

private fun ElectrumWatcherState.unhandled(message: ElectrumMessage) : Pair<ElectrumWatcherState, List<ElectrumWatcherAction>> =
    when (message) {
        else -> error("The state $this cannot process the event $message")
    }

private class WatcherStateBuilder {
    var state: ElectrumWatcherState = ElectrumWatcherDisconnected
    var actions = emptyList<ElectrumWatcherAction>()
    fun build() = state to actions
}
private fun newState(init: WatcherStateBuilder.() -> Unit) = WatcherStateBuilder().apply(init).build()
private fun newState(newState: ElectrumWatcherState) = WatcherStateBuilder().apply { state = newState }.build()

private fun ElectrumWatcherState.returnState(actions: List<ElectrumWatcherAction> = emptyList()): Pair<ElectrumWatcherState, List<ElectrumWatcherAction>> = this to actions
private fun ElectrumWatcherState.returnState(action: ElectrumWatcherAction): Pair<ElectrumWatcherState, List<ElectrumWatcherAction>> = this to listOf(action)
private fun ElectrumWatcherState.returnState(vararg actions: ElectrumWatcherAction): Pair<ElectrumWatcherState, List<ElectrumWatcherAction>> = this to listOf(*actions)

@OptIn(ExperimentalCoroutinesApi::class)
class ElectrumWatcher(val client: ElectrumClient) {

    private val eventChannel = Channel<ElectrumMessage>(Channel.BUFFERED)
    val watchChannel = Channel<WatcherType>(Channel.BUFFERED)

    private val input = CoroutineScope(Dispatchers.Default).produce {
        launch { eventChannel.consumeEach { send(it) } }
        launch { watchChannel.consumeEach { send(it) } }
    }

    private var state: ElectrumWatcherState = ElectrumWatcherDisconnected
        set(value) {
            if (value != field) logger.info { "Updated State: $field -> $value" }
            field = value
        }

    private suspend fun run() {
        input.consumeEach {
            when(val message = it) {
                is ElectrumMessage -> {
                    logger.info { "Message received: $message" }
                    val (newState, actions) = state.process(message)

                    if (newState != state)
                        logger.info { "Updated State: $state -> $newState" }
                    state = newState

                    actions.forEach { action ->
                        when (action) {
                            RegisterToStatusSubscription -> client.events.send(AddStatusSubscription(eventChannel))
                            RegisterToHeaderSubscription -> client.events.send(AddHeaderSubscription(eventChannel))
                            is PublishAsapAction -> eventChannel.send(PublishEvent(action.publishAsap))
                            is GetScriptHashHistoryAction -> client.events.send(
                                SendElectrumRequest(GetScriptHashHistory(action.scriptHash), eventChannel)
                            )
                            is RegisterToScriptHashSubscription -> client.events.send(
                                SendElectrumRequest(ScriptHashSubscription(action.scriptHash), eventChannel)
                            )
                        }
                    }
                }
                is Watch -> eventChannel.send(WatchTypeEvent(message))
            }
        }
    }

    suspend fun start() {
        coroutineScope {
            eventChannel.send(WatcherStart)
            launch { run() }
        }
    }

    suspend fun stop() {
        // TODO Unsubscribe from SM?
        client.events.send(Unsubscribe(eventChannel))
        eventChannel.close()
    }

    companion object {
        private val logger = LoggerFactory.default.newLogger(ElectrumWatcher::class)
    }
}