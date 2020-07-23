package fr.acinq.eklair.blockchain.electrum

import fr.acinq.bitcoin.BlockHeader
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.Transaction
import fr.acinq.eklair.blockchain.*
import fr.acinq.eklair.blockchain.electrum.ElectrumClient.Companion.computeScriptHash
import fr.acinq.eklair.blockchain.electrum.ElectrumClient.Companion.logger
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.channels.produce
import org.kodein.log.LoggerFactory
import org.kodein.log.newLogger

private sealed class WatcherEvent
private object StartWatcher: WatcherEvent()
private class PublishEvent(val publishAsap: PublishAsap) : WatcherEvent()
private class ReceiveWatch(val watch: WatcherType) : WatcherEvent()
private class ReceivedMessage(val message: ElectrumMessage) : WatcherEvent()

private sealed class WatcherAction
private object RegisterToElectrumStatus : WatcherAction()
private object RegisterToHeaderNotification : WatcherAction()
private data class RegisterToScriptHashNotification(val scriptHash: ByteVector32) : WatcherAction()
private data class AskForScriptHashHistory(val scriptHash: ByteVector32) : WatcherAction()
private data class AskForTransaction(val txid: ByteVector32, val item: TransactionHistoryItem) : WatcherAction()
private data class AskForMerkle(val txId: ByteVector32, val txheight: Int, val tx: Transaction) : WatcherAction()
private data class NotifyWatchConfirmed(
    val listener: SendChannel<WatcherType>,
    val watchEventConfirmed: WatchEventConfirmed
) : WatcherAction()
private data class PublishAsapAction(val publishAsap: PublishAsap) : WatcherAction()

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
    abstract fun process(event: WatcherEvent): Pair<ElectrumWatcherState, List<WatcherAction>>
}
private object ElectrumWatcherDisconnected : ElectrumWatcherState() {
    override fun process(event: WatcherEvent): Pair<ElectrumWatcherState, List<WatcherAction>> =
        when(event) {
            is ReceivedMessage -> when (val message = event.message) {
                is ElectrumClientReady -> returnState(action = RegisterToHeaderNotification)
                is HeaderSubscriptionResponse -> ElectrumWatcherRunning(height = message.height, tip = message.header) to listOf() // TODO watches / publishQueue / getTxQueue?
                else -> returnState()
            }
            is StartWatcher -> returnState(action = RegisterToElectrumStatus)
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
    override fun process(event: WatcherEvent): Pair<ElectrumWatcherState, List<WatcherAction>> =
        when (event) {
            is ReceivedMessage -> {
                val message = event.message
                when {
                    message is HeaderSubscriptionResponse && message.header == tip -> returnState()
                    message is HeaderSubscriptionResponse -> {
                        val (newHeight, newTip) = message
                        logger.info { "new tip: ${newTip.blockId} $newHeight" }
                        val scriptHashesActions = watches.filterIsInstance<WatchConfirmed>().map {
                            val scriptHash = computeScriptHash(it.publicKeyScript)
                            AskForScriptHashHistory(scriptHash)
                        }

                        val toPublish = block2tx.filterKeys { it <= newHeight }
                        val publishAsapActions = toPublish.values.flatten().map { PublishAsapAction(PublishAsap(it)) }

                        newState {
                            state = copy(height = newHeight, tip = newTip, block2tx = block2tx - toPublish.keys)
                            actions = scriptHashesActions + publishAsapActions
                        }
                    }
                    message is ScriptHashSubscriptionResponse -> {
                        val (scriptHash, status) = message

                        newState {
                            state = copy(scriptHashStatus = scriptHashStatus + (scriptHash to status))
                            actions = buildList {
                                val s = scriptHashStatus[scriptHash]
                                when {
                                    s == status -> logger.info { "already have status=$status for scriptHash=$scriptHash" }
                                    status.isEmpty() -> logger.info { "empty status for scriptHash=$scriptHash" }
                                    else -> add(AskForScriptHashHistory(scriptHash))
                                }
                            }
                        }
                    }
                    message is GetScriptHashHistoryResponse -> {
                        // we retrieve the transaction before checking watches
                        // NB: height=-1 means that the tx is unconfirmed and at least one of its inputs is also unconfirmed.
                        // we need to take them into consideration if we want to handle unconfirmed txes (which is the case for turbo channels)
                        val getTransactionList = message.history.filter { it.height >= -1 }.map {
                            AskForTransaction(it.tx_hash, it)
                        }
                        returnState(actions = getTransactionList)
                    }
                    message is GetTransactionResponse -> {
                        val tx = message.tx
                        val item = message.contextOpt as? TransactionHistoryItem

                        // TODO WatchSpent

                        // WatchConfirmed
                        val watchConfirmedTriggered = mutableListOf<WatchConfirmed>()
                        val notifyWatchConfirmedList = mutableListOf<NotifyWatchConfirmed>()
                        val getMerkleList = mutableListOf<AskForMerkle>()
                        watches
                            .filterIsInstance<WatchConfirmed>()
                            .filter { it.txId == tx.txid }
                            .forEach { w ->
                                if (w.minDepth == 0L) {
                                    // special case for mempool watches (min depth = 0)
                                    val (dummyHeight, dummyTxIndex) = ElectrumWatcher.makeDummyShortChannelId(w.txId)
                                    notifyWatchConfirmedList.add(
                                        NotifyWatchConfirmed(
                                            w.listener,
                                            WatchEventConfirmed(BITCOIN_FUNDING_DEPTHOK, dummyHeight, dummyTxIndex, tx)
                                        )
                                    )
                                    watchConfirmedTriggered.add(w)
                                } else if (w.minDepth > 0L) {
                                    // min depth > 0 here
                                    item?.let {
                                        val txheight = item.height
                                        val confirmations = height - txheight + 1
                                        logger.info { "txid=${w.txId} was confirmed at height=$txheight and now has confirmations=$confirmations (currentHeight=$height)" }
                                        if (confirmations >= w.minDepth) {
                                            // we need to get the tx position in the block
                                            getMerkleList.add(AskForMerkle(w.txId, txheight, tx))
                                        }
                                    }
                                }
                            }

                        newState {
                            state = copy(watches = watches - watchConfirmedTriggered)
                            actions = notifyWatchConfirmedList + getMerkleList
                        }
                    }
                    message is GetMerkleResponse -> {
                        val (txid, _, txheight, pos, tx) = message
                        val confirmations = height - txheight + 1
                        val triggered = watches
                            .filterIsInstance<WatchConfirmed>()
                            .filter { it.txId == txid && confirmations >= it.minDepth }

                        val notifyWatchConfirmedList = tx?.let {
                             triggered.map { w ->
                                 logger.info { "txid=${w.txId} had confirmations=$confirmations in block=$txheight pos=$pos" }
                                NotifyWatchConfirmed(w.listener, WatchEventConfirmed(w.event, txheight, pos, tx))
                            }
                        } ?: emptyList()

                        newState {
                            state = copy(watches = watches - triggered)
                            actions = notifyWatchConfirmedList
                        }
                    }

                    else -> returnState()
                }
            }
            is ReceiveWatch -> when (val watch = event.watch) {
                is Watch -> when (watch) {
                    in watches -> returnState()
                    is WatchSpent -> {
                        val (txid, outputIndex, publicKeyScript, _) = watch
                        val scriptHash = computeScriptHash(publicKeyScript)
                        logger.info { "added watch-spent on output=$txid:$outputIndex scriptHash=$scriptHash" }
                        newState {
                            state = copy(watches = watches + watch)
                            actions = listOf(RegisterToScriptHashNotification(scriptHash))
                        }
                    }
                    is WatchConfirmed -> {
                        val (_, txid, publicKeyScript, _, _) = watch
                        val scriptHash = computeScriptHash(publicKeyScript)
                        logger.info { "added watch-confirmed on txid=$txid scriptHash=$scriptHash" }
                        newState {
                            state = copy(watches = watches + watch)
                            actions = listOf(RegisterToScriptHashNotification(scriptHash))
                        }
                    }
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
                case watch@WatchConfirmed(_, txid, publicKeyScript, _, _)
                case ElectrumClient.ScriptHashSubscriptionResponse(scriptHash, status)
                case ElectrumClient.GetScriptHashHistoryResponse(_, history)
            - TODO:
                case watch@WatchSpent(_, txid, outputIndex, publicKeyScript, _)
                case watch@WatchSpentBasic(_, txid, outputIndex, publicKeyScript, _)
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

private fun ElectrumWatcherState.unhandled(message: WatcherEvent) : Pair<ElectrumWatcherState, List<WatcherAction>> =
    when (message) {
        else -> error("The state $this cannot process the event $message")
    }

private class WatcherStateBuilder {
    var state: ElectrumWatcherState = ElectrumWatcherDisconnected
    var actions = emptyList<WatcherAction>()
    fun build() = state to actions
}
private fun newState(init: WatcherStateBuilder.() -> Unit) = WatcherStateBuilder().apply(init).build()
private fun newState(newState: ElectrumWatcherState) = WatcherStateBuilder().apply { state = newState }.build()

private fun ElectrumWatcherState.returnState(actions: List<WatcherAction> = emptyList()): Pair<ElectrumWatcherState, List<WatcherAction>> = this to actions
private fun ElectrumWatcherState.returnState(action: WatcherAction): Pair<ElectrumWatcherState, List<WatcherAction>> = this to listOf(action)
private fun ElectrumWatcherState.returnState(vararg actions: WatcherAction): Pair<ElectrumWatcherState, List<WatcherAction>> = this to listOf(*actions)

@OptIn(ExperimentalCoroutinesApi::class)
class ElectrumWatcher(val client: ElectrumClient, val scope: CoroutineScope): CoroutineScope by scope {

    private val eventChannel = Channel<WatcherEvent>(Channel.BUFFERED)
    private val messageChannel = Channel<ElectrumMessage>(Channel.BUFFERED)
    val watchChannel = Channel<WatcherType>(Channel.BUFFERED)

    private val input = scope.produce(capacity = Channel.BUFFERED) {
        launch { eventChannel.consumeEach { send(it) } }
        launch { messageChannel.consumeEach { send(it) } }
        launch { watchChannel.consumeEach { send(it) } }
    }

    private var state: ElectrumWatcherState = ElectrumWatcherDisconnected
        set(value) {
            if (value != field) logger.info { "Updated State: $field -> $value" }
            field = value
        }

    private suspend fun run() {
        input.consumeEach { input ->
            when(input) {
                is WatcherEvent -> {
                    logger.info { "Event received: $input" }

                    val (newState, actions) = state.process(input)
                    state = newState

                    actions.forEach { action ->
                        when (action) {
                            RegisterToElectrumStatus -> client.sendMessage(ElectrumStatusSubscription(messageChannel))
                            RegisterToHeaderNotification -> client.sendMessage(ElectrumHeaderSubscription(messageChannel))
                            is RegisterToScriptHashNotification -> client.sendMessage(
                                ElectrumSendRequest(ScriptHashSubscription(action.scriptHash), messageChannel)
                            )
                            is PublishAsapAction -> eventChannel.send(PublishEvent(action.publishAsap))
                            is AskForScriptHashHistory -> client.sendMessage(
                                ElectrumSendRequest(GetScriptHashHistory(action.scriptHash), messageChannel)
                            )
                            is AskForTransaction -> client.sendMessage(
                                ElectrumSendRequest(GetTransaction(action.txid, action.item), messageChannel)
                            )
                            is AskForMerkle -> client.sendMessage(
                                ElectrumSendRequest(GetMerkle(action.txId, action.txheight, action.tx), messageChannel)
                            )
                            is NotifyWatchConfirmed -> action.listener.send(action.watchEventConfirmed)
                        }
                    }
                }
                is Watch -> {
                    logger.info { "Watch received: $input" }
                    eventChannel.send(ReceiveWatch(input))
                }
                is ElectrumMessage -> {
                    logger.info { "Message received: $input" }
                    eventChannel.send(ReceivedMessage(input))
                }
            }
        }
    }

    fun start() {
        launch { run() }
        launch { eventChannel.send(StartWatcher) }
    }

    fun stop() {
        // TODO Unsubscribe from SM?
//        client.messageChannel.send(Unsubscribe(eventChannel))
        eventChannel.close()
    }

    companion object {
        private val logger = LoggerFactory.default.newLogger(ElectrumWatcher::class)
        internal fun makeDummyShortChannelId(txid: ByteVector32): Pair<Int, Int> {
            // we use a height of 0
            // - to make sure that the tx will be marked as "confirmed"
            // - to easily identify scids linked to 0-conf channels
            //
            // this gives us a probability of collisions of 0.1% for 5 0-conf channels and 1% for 20
            // collisions mean that users may temporarily see incorrect numbers for their 0-conf channels (until they've been confirmed)
            // if this ever becomes a problem we could just extract some bits for our dummy height instead of just returning 0
            val height = 0
            val txIndex = txid.slice(0, 16).toByteArray().sum()
            return height to txIndex
        }
    }
}