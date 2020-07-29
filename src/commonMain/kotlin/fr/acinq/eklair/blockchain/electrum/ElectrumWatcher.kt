package fr.acinq.eklair.blockchain.electrum

import fr.acinq.bitcoin.BlockHeader
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.Transaction
import fr.acinq.bitcoin.TxIn
import fr.acinq.bitcoin.crypto.Pack
import fr.acinq.eklair.blockchain.*
import fr.acinq.eklair.blockchain.electrum.ElectrumClient.Companion.computeScriptHash
import fr.acinq.eklair.blockchain.electrum.ElectrumWatcher.Companion.logger
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.channels.produce
import org.kodein.log.Logger
import org.kodein.log.LoggerFactory
import org.kodein.log.frontend.printFrontend
import org.kodein.log.frontend.simplePrintFrontend
import org.kodein.log.newLogger
import kotlin.math.absoluteValue

sealed class WatcherEvent
private object StartWatcher: WatcherEvent()
private class PublishEvent(val publishAsap: PublishAsap) : WatcherEvent()
class GetTxWithMetaEvent(val txid: ByteVector32, val listener: SendChannel<GetTxWithMetaResponse>) : WatcherEvent()
private class ReceiveWatch(val watch: Watch) : WatcherEvent()
private class ReceivedMessage(val message: ElectrumMessage) : WatcherEvent()

private sealed class WatcherAction
private object RegisterToElectrumStatus : WatcherAction()
private object RegisterToHeaderNotification : WatcherAction()
private data class RegisterToScriptHashNotification(val scriptHash: ByteVector32) : WatcherAction()
private data class AskForScriptHashHistory(val scriptHash: ByteVector32) : WatcherAction()
private data class AskForTransaction(val txid: ByteVector32, val contextOpt: Any? = null) : WatcherAction()
private data class AskForMerkle(val txId: ByteVector32, val txheight: Int, val tx: Transaction) : WatcherAction()
private data class NotifyWatchConfirmed(
    val listener: SendChannel<WatchEventConfirmed>,
    val watchEventConfirmed: WatchEventConfirmed
) : WatcherAction()
private data class NotifyWatchSpent(
    val listener: SendChannel<WatchEventSpent>,
    val watchEventSpent: WatchEventSpent
) : WatcherAction()
private data class NotifyTxWithMeta(
    val listener: SendChannel<GetTxWithMetaResponse>,
    val txWithMeta: GetTxWithMetaResponse
) : WatcherAction()
private data class RelayWatch(val watch: Watch) : WatcherAction()
private data class RelayGetTxWithMeta(val txid: ByteVector32, val listener: SendChannel<GetTxWithMetaResponse>) : WatcherAction()
private data class PublishAsapAction(val publishAsap: PublishAsap) : WatcherAction()

/**
 * [WatcherState] State
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
private sealed class WatcherState {
    abstract fun process(event: WatcherEvent): Pair<WatcherState, List<WatcherAction>>
}
private data class WatcherDisconnected(
    val watches: Set<Watch> = setOf(),
    val publishQueue: ArrayDeque<PublishAsap> = ArrayDeque(),
    val block2tx: Map<Long, List<Transaction>> = mapOf(),
    val getTxQueue: ArrayDeque<Pair<GetTxWithMeta, SendChannel<GetTxWithMetaResponse>>> = ArrayDeque()
) : WatcherState() {
    override fun process(event: WatcherEvent): Pair<WatcherState, List<WatcherAction>> =
        when(event) {
            is StartWatcher -> returnState(action = RegisterToElectrumStatus)
            is ReceivedMessage -> when (val message = event.message) {
                is ElectrumClientReady -> returnState(action = RegisterToHeaderNotification)
                is HeaderSubscriptionResponse -> {
                    // TODO actions for watches / publishQueue / getTxQueue?
                    newState {
                        state = (WatcherRunning(height = message.height, tip = message.header, block2tx = block2tx))
                        actions = buildList {
                            watches.forEach { add(RelayWatch(it)) }
                            publishQueue.forEach { add(PublishAsapAction(it)) }
                            getTxQueue.forEach { add(RelayGetTxWithMeta(it.first.txid, it.second)) }
                        }
                    }
                }
                else -> returnState()
            }
            is ReceiveWatch -> newState(copy(watches = watches + event.watch))
            is PublishEvent -> {
                publishQueue.add(event.publishAsap)
                returnState()
            }
            is GetTxWithMetaEvent -> {
                getTxQueue.add(GetTxWithMeta(event.txid) to event.listener)
                returnState()
            }
        }
}

private data class WatcherRunning(
    val height: Int,
    val tip: BlockHeader,
    val watches: Set<Watch> = setOf(),
    val scriptHashStatus: Map<ByteVector32, String> = mapOf(),
    val block2tx: Map<Long, List<Transaction>> = mapOf(),
    val sent: ArrayDeque<Transaction> = ArrayDeque()
) : WatcherState() {
    override fun process(event: WatcherEvent): Pair<WatcherState, List<WatcherAction>> =
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
                        when (message.contextOpt) {
                            is TransactionHistoryItem -> {
                                val tx = message.tx
                                val item = message.contextOpt

                                // WatchSpent
                                val notifyWatchSpentList = tx.txIn.map(TxIn::outPoint)
                                    .flatMap { outPoint ->
                                        watches
                                            .filterIsInstance<WatchSpent>()
                                            .filter { it.txId == outPoint.txid && it.outputIndex == outPoint.index.toInt() }
                                            .map {
                                                logger.info { "output ${it.txId}:${it.outputIndex} spent by transaction ${tx.txid}" }
                                                NotifyWatchSpent(it.listener, WatchEventSpent(ByteVector32.Zeroes, it.event, tx))
                                            }
                                    }

                                // WatchConfirmed
                                val watchConfirmedTriggered = mutableListOf<WatchConfirmed>()
                                val notifyWatchConfirmedList = mutableListOf<NotifyWatchConfirmed>()
                                val getMerkleList = mutableListOf<AskForMerkle>()
                                watches.filterIsInstance<WatchConfirmed>()
                                    .filter { it.txId == tx.txid }
                                    .forEach { w ->
                                        if (w.minDepth == 0L) {
                                            // special case for mempool watches (min depth = 0)
                                            val (dummyHeight, dummyTxIndex) = ElectrumWatcher.makeDummyShortChannelId(w.txId)
                                            notifyWatchConfirmedList.add(
                                                NotifyWatchConfirmed(
                                                    w.listener,
                                                    WatchEventConfirmed(
                                                        ByteVector32.Zeroes, // TODO!
                                                        BITCOIN_FUNDING_DEPTHOK,
                                                        dummyHeight,
                                                        dummyTxIndex,
                                                        tx
                                                    )
                                                )
                                            )
                                            watchConfirmedTriggered.add(w)
                                        } else if (w.minDepth > 0L) {
                                            // min depth > 0 here
                                            val txheight = item.height
                                            val confirmations = height - txheight + 1
                                            logger.info { "txid=${w.txId} was confirmed at height=$txheight and now has confirmations=$confirmations (currentHeight=$height)" }
                                            if (confirmations >= w.minDepth) {
                                                // we need to get the tx position in the block
                                                getMerkleList.add(AskForMerkle(w.txId, txheight, tx))
                                            }
                                        }
                                    }

                                logger.info { """Tx notification for ${tx.txid}
                                    |txIn: ${tx.txIn}
                                    |txOut: ${tx.txIn}
                                    |watches: $watches
                                    |NotifyWatchSpentList: $notifyWatchSpentList
                                    |NotifyWatchConfirmedList: $notifyWatchConfirmedList
                                """.trimMargin() }

                                newState {
                                    // NB: WatchSpent are permanent because we need to detect multiple spending of the funding tx
                                    // They are never cleaned up but it is not a big deal for now (1 channel == 1 watch)
                                    state = copy(watches = watches - watchConfirmedTriggered)
                                    actions = notifyWatchSpentList + notifyWatchConfirmedList + getMerkleList
                                }
                            }
                            is Channel<*> -> {
                                val tx = message.tx

                                @Suppress("UNCHECKED_CAST")
                                val listener = message.contextOpt as SendChannel<GetTxWithMetaResponse>

                                returnState(
                                    NotifyTxWithMeta(
                                        listener,
                                        GetTxWithMetaResponse(tx.txid, tx, tip.time)
                                    )
                                )
                            }
                            else -> returnState()
                        }
                    }
                    message is ServerError && message.request is GetTransaction && message.request.contextOpt is Channel<*> -> {
                        @Suppress("UNCHECKED_CAST")
                        returnState(
                            NotifyTxWithMeta(
                                message.request.contextOpt as Channel<GetTxWithMetaResponse>,
                                GetTxWithMetaResponse(message.request.txid, null, tip.time)
                            )
                        )
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
                                NotifyWatchConfirmed(w.listener, WatchEventConfirmed(ByteVector32.Zeroes, w.event, txheight, pos, tx))
                            }
                        } ?: emptyList()

                        newState {
                            state = copy(watches = watches - triggered)
                            actions = notifyWatchConfirmedList
                        }
                    }
                    message is ElectrumClientClosed -> newState(WatcherDisconnected(
                        watches = watches,
                        publishQueue = ArrayDeque(sent.map { PublishAsap(it) }),
                        block2tx = block2tx
                    ))
                    else -> returnState()
                }
            }
            is GetTxWithMetaEvent -> returnState(AskForTransaction(event.txid, event.listener))
            is ReceiveWatch -> when (val watch = event.watch) {
                in watches -> returnState()
                is WatchSpent -> {
                    val (_, _, txid, outputIndex, publicKeyScript, _) = watch
                    val scriptHash = computeScriptHash(publicKeyScript)
                    logger.info { "added watch-spent on output=$txid:$outputIndex scriptHash=$scriptHash" }
                    newState {
                        state = copy(watches = watches + watch)
                        actions = listOf(RegisterToScriptHashNotification(scriptHash))
                    }
                }
                is WatchConfirmed -> {
                    val (_, _, txid, publicKeyScript, _, _) = watch
                    val scriptHash = computeScriptHash(publicKeyScript)
                    logger.info { "added watch-confirmed on txid=$txid scriptHash=$scriptHash" }
                    newState {
                        state = copy(watches = watches + watch)
                        actions = listOf(RegisterToScriptHashNotification(scriptHash))
                    }
                }
                else -> returnState()
            }
            else -> unhandled(event)
        }
    /*
    - OK:
        case ElectrumClient.HeaderSubscriptionResponse(newheight, newtip) if tip == newtip => ()
        case ElectrumClient.HeaderSubscriptionResponse(newheight, newtip)
        case watch: Watch if watches.contains(watch) => ()
        case watch@WatchConfirmed(_, txid, publicKeyScript, _, _)
        case watch@WatchSpent(_, txid, outputIndex, publicKeyScript, _)
        case watch@WatchSpentBasic(_, txid, outputIndex, publicKeyScript, _)
        case ElectrumClient.ScriptHashSubscriptionResponse(scriptHash, status)
        case ElectrumClient.GetScriptHashHistoryResponse(_, history)
        case ElectrumClient.GetTransactionResponse(tx, Some(item: ElectrumClient.TransactionHistoryItem))
        case ElectrumClient.GetTransactionResponse(tx, Some(origin: ActorRef))
        case ElectrumClient.ServerError(ElectrumClient.GetTransaction(txid, Some(origin: ActorRef)), _)
        case ElectrumClient.GetMerkleResponse(tx_hash, _, txheight, pos, Some(tx: Transaction))
        case GetTxWithMeta(txid)
        case ElectrumClient.ElectrumDisconnected
    - TODO:
        case WatchEventConfirmed(BITCOIN_PARENT_TX_CONFIRMED(tx), blockHeight, _, _)
        case ElectrumClient.BroadcastTransactionResponse(tx, error_opt)
        case PublishAsap(tx)
    - later:
        case Terminated(actor)
     */
}

private fun WatcherState.unhandled(message: WatcherEvent) : Pair<WatcherState, List<WatcherAction>> =
        error("The state $this cannot process the event $message")

private class WatcherStateBuilder {
    var state: WatcherState = WatcherDisconnected()
    var actions = emptyList<WatcherAction>()
    fun build() = state to actions
}
private fun newState(init: WatcherStateBuilder.() -> Unit) = WatcherStateBuilder().apply(init).build()
private fun newState(newState: WatcherState) = WatcherStateBuilder().apply { state = newState }.build()

private fun WatcherState.returnState(actions: List<WatcherAction> = emptyList()): Pair<WatcherState, List<WatcherAction>> = this to actions
private fun WatcherState.returnState(action: WatcherAction): Pair<WatcherState, List<WatcherAction>> = this to listOf(action)
private fun WatcherState.returnState(vararg actions: WatcherAction): Pair<WatcherState, List<WatcherAction>> = this to listOf(*actions)

@OptIn(ExperimentalCoroutinesApi::class)
class ElectrumWatcher(val client: ElectrumClient, val scope: CoroutineScope): CoroutineScope by scope {

    private val eventChannel = Channel<WatcherEvent>(Channel.BUFFERED)
    private val watchChannel = Channel<Watch>(Channel.BUFFERED)
    private val electrumMessageChannel = Channel<ElectrumMessage>(Channel.BUFFERED)

    private val input = scope.produce(capacity = Channel.BUFFERED) {
        launch { eventChannel.consumeEach { send(it) } }
        launch { watchChannel.consumeEach { send(it) } }
        launch { electrumMessageChannel.consumeEach { send(it) } }
    }

    private var state: WatcherState = WatcherDisconnected()
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

                    logger.info { "Execute actions: $actions" }
                    actions.forEach { action ->
                        when (action) {
                            RegisterToElectrumStatus -> client.sendMessage(ElectrumStatusSubscription(electrumMessageChannel))
                            RegisterToHeaderNotification -> client.sendMessage(ElectrumHeaderSubscription(electrumMessageChannel))
                            is RegisterToScriptHashNotification -> client.sendMessage(
                                SendElectrumRequest(ScriptHashSubscription(action.scriptHash), electrumMessageChannel)
                            )
                            is PublishAsapAction -> eventChannel.send(PublishEvent(action.publishAsap))
                            is AskForScriptHashHistory -> client.sendMessage(
                                SendElectrumRequest(GetScriptHashHistory(action.scriptHash), electrumMessageChannel)
                            )
                            is AskForTransaction -> client.sendMessage(
                                SendElectrumRequest(GetTransaction(action.txid, action.contextOpt), electrumMessageChannel)
                            )
                            is AskForMerkle -> client.sendMessage(
                                SendElectrumRequest(GetMerkle(action.txId, action.txheight, action.tx), electrumMessageChannel)
                            )
                            is NotifyWatchConfirmed -> action.listener.send(action.watchEventConfirmed)
                            is NotifyWatchSpent -> action.listener.send(action.watchEventSpent)
                            is NotifyTxWithMeta -> action.listener.send(action.txWithMeta)
                            is RelayWatch -> eventChannel.send(ReceiveWatch(action.watch))
                            is RelayGetTxWithMeta -> eventChannel.send(GetTxWithMetaEvent(action.txid, action.listener))
                        }
                    }
                }
                is Watch -> {
                    logger.info { "Watch received: $input" }
                    if (!eventChannel.isClosedForSend) eventChannel.send(ReceiveWatch(input))
                }
                is ElectrumMessage -> {
                    logger.info { "Electrum message received: $input" }
                    if (!eventChannel.isClosedForSend) eventChannel.send(ReceivedMessage(input))
                }
            }
        }
    }

    fun send(message: WatcherEvent) {
        launch { eventChannel.send(message) }
    }

    fun watch(watch: Watch) {
        launch { watchChannel.send(watch) }
    }

    fun start() {
        launch { run() }
        launch { eventChannel.send(StartWatcher) }
    }

    fun stop() {
//        client.sendMessage(UnsubscribeListener(electrumMessageChannel))// TODO?
        electrumMessageChannel.close()
        watchChannel.close()
        eventChannel.close()
        input.cancel()
    }

    companion object {
        val logger = LoggerFactory(simplePrintFrontend).newLogger(ElectrumWatcher::class)
        internal fun makeDummyShortChannelId(txid: ByteVector32): Pair<Int, Int> {
            // we use a height of 0
            // - to make sure that the tx will be marked as "confirmed"
            // - to easily identify scids linked to 0-conf channels
            //
            // this gives us a probability of collisions of 0.1% for 5 0-conf channels and 1% for 20
            // collisions mean that users may temporarily see incorrect numbers for their 0-conf channels (until they've been confirmed)
            // if this ever becomes a problem we could just extract some bits for our dummy height instead of just returning 0
            val height = 0
            val txIndex = Pack.int32BE(txid.slice(0, 16).toByteArray()).absoluteValue
            return height to txIndex
        }
    }
}