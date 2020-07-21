package fr.acinq.eklair.blockchain.electrum

import fr.acinq.bitcoin.BlockHeader
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.Transaction
import fr.acinq.eklair.blockchain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.channels.produce
import org.kodein.log.LoggerFactory
import org.kodein.log.newLogger

private sealed class WatcherEvent : ElectrumMessage
private object WatcherStart: WatcherEvent()
private class PublishEvent(val publishAsap: PublishAsap) : WatcherEvent()
private class WatchTypeEvent(val watch: WatcherType) : WatcherEvent()

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
private sealed class ElectrumWatcherState
private object ElectrumWatcherDisconnected : ElectrumWatcherState()
private data class ElectrumWatcherRunning(
    val height: Int,
    val tip: BlockHeader,
    val watches: Set<Watch> = setOf(),
    val scriptHashStatus: Map<ByteVector32, String> = mapOf(),
    val block2tx: Map<Long, List<Transaction>> = mapOf(),
    val sent: ArrayDeque<Transaction> = ArrayDeque()
) : ElectrumWatcherState()
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

    private suspend fun disconnected(message: ElectrumMessage) {
        require(state is ElectrumWatcherDisconnected)

        when(message) {
            is WatcherStart -> client.events.send(AddStatusSubscription(eventChannel))
            is ElectrumClientRunning -> client.events.send(AddHeaderSubscription(eventChannel))
            is HeaderSubscriptionResponse -> {
                state = ElectrumWatcherRunning(height = message.height, tip = message.header)
            }
//                    listOf() // TODO watches / publishQueue / getTxQueue?
            else -> unhandled(message)
        }
    }

    private suspend fun running(message: ElectrumMessage, currentState: ElectrumWatcherState) {
        require(currentState is ElectrumWatcherRunning)
//        val (height, tip, watches, scriptHashStatus, block2tx, _) = currentState

        when {
            message is HeaderSubscriptionResponse && message.header == currentState.tip -> {}
            message is HeaderSubscriptionResponse -> {
                val (newHeight, newTip) = message
                logger.info { "new tip: ${newTip.blockId} $newHeight" }
                currentState.watches.filterIsInstance<WatchConfirmed>().forEach {
                    val scriptHash = ElectrumClient.computeScriptHash(it.publicKeyScript)
                    val askingForAFriend = SendElectrumRequest(GetScriptHashHistory(scriptHash), eventChannel)
                    client.events.send(askingForAFriend)
                }
                val toPublish = currentState.block2tx.filterKeys { it <= newHeight }
                toPublish.values.flatten()
                    .forEach { eventChannel.send(PublishEvent(PublishAsap(it))) }

                state = currentState.copy(height = newHeight, tip = newTip, block2tx = currentState.block2tx - toPublish.keys)
            }
            message is WatchTypeEvent -> when(val watch = message.watch) {
                is Watch -> when(watch) {
                    in currentState.watches -> {}
                    is WatchSpent -> {
                        val (txid, outputIndex, publicKeyScript, _) = watch
                        val scriptHash = ElectrumClient.computeScriptHash(publicKeyScript)
                        logger.info { "added watch-spent on output=$txid:$outputIndex scriptHash=$scriptHash" }
                        client.events.send(SendElectrumRequest(ScriptHashSubscription(scriptHash), eventChannel))
                        // TODO watch over the channel actor
                        state = currentState.copy(watches = currentState.watches + watch)
                    }
                    is WatchConfirmed -> TODO()
                }
                is WatchEventSpent -> TODO()
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
            else -> unhandled(message)
        }
    }

    private fun unhandled(message: ElectrumMessage) {
        when(message) {
            is ElectrumClientClosed -> { state = ElectrumWatcherDisconnected } // TODO Broadcast disconnection?
            else -> error("The state $this cannot process the event $message")
        }
    }

    private suspend fun run() {
        input.consumeEach { message ->
            when(message) {
                is ElectrumMessage -> when(state) {
                    ElectrumWatcherDisconnected -> disconnected(message)
                    is ElectrumWatcherRunning -> running(message, state)
                }
                is WatcherType -> eventChannel.send(WatchTypeEvent(message))
            }
        }
    }

/*
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
                        }
                    }
                }
                is Watch -> {}
            }
        }
    }
*/

    suspend fun start() {
        coroutineScope {
            eventChannel.send(WatcherStart)
            launch { run() }
        }
    }

    suspend fun askForHeader() {
        client.events.send(SendElectrumRequest(GetHeader(100), eventChannel))
    }

    suspend fun stop() {
        client.events.send(Unsubscribe(eventChannel))
        eventChannel.close()
    }

    companion object {
        private val logger = LoggerFactory.default.newLogger(ElectrumWatcher::class)
    }
}