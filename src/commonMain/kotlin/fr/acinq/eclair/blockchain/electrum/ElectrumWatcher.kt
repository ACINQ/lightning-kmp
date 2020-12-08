package fr.acinq.eclair.blockchain.electrum

import fr.acinq.bitcoin.BlockHeader
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.Transaction
import fr.acinq.bitcoin.TxIn
import fr.acinq.bitcoin.crypto.Pack
import fr.acinq.eclair.blockchain.*
import fr.acinq.eclair.blockchain.electrum.ElectrumClient.Companion.computeScriptHash
import fr.acinq.eclair.blockchain.electrum.ElectrumWatcher.Companion.logger
import fr.acinq.eclair.blockchain.electrum.ElectrumWatcher.Companion.makeDummyShortChannelId
import fr.acinq.eclair.blockchain.electrum.ElectrumWatcher.Companion.registerToScriptHash
import fr.acinq.eclair.transactions.Scripts
import fr.acinq.eclair.utils.Connection
import fr.acinq.eclair.utils.eclairLogger
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BroadcastChannel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.channels.produce
import kotlinx.coroutines.flow.collect
import kotlin.math.absoluteValue
import kotlin.math.max
import kotlin.native.concurrent.ThreadLocal

sealed class WatcherEvent
class PublishAsapEvent(val tx: Transaction) : WatcherEvent()
data class GetTxWithMetaEvent(val request: GetTxWithMeta) : WatcherEvent()
class ReceiveWatch(val watch: Watch) : WatcherEvent()
class ReceiveWatchEvent(val watchEvent: WatchEvent) : WatcherEvent()
class ReceivedMessage(val message: ElectrumMessage) : WatcherEvent()
class ClientStateUpdate(val connection: Connection) : WatcherEvent()

internal sealed class WatcherAction
private object AskForHeaderUpdate : WatcherAction()
internal data class RegisterToScriptHashNotification(val scriptHash: ByteVector32) : WatcherAction()
private data class AskForScriptHashHistory(val scriptHash: ByteVector32) : WatcherAction()
private data class AskForTransaction(val txid: ByteVector32, val contextOpt: Any? = null) : WatcherAction()
private data class AskForMerkle(val txId: ByteVector32, val txheight: Int, val tx: Transaction) : WatcherAction()
private data class NotifyWatch(val watchEvent: WatchEvent, val broadcastNotification: Boolean = true) : WatcherAction()
private data class NotifyTxWithMeta(val txWithMeta: GetTxWithMetaResponse) : WatcherAction()
private data class PublishAsapAction(val tx: Transaction) : WatcherAction()
private data class BroadcastTxAction(val tx: Transaction) : WatcherAction()

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
    val publishQueue: Set<PublishAsap> = setOf(),
    val block2tx: Map<Long, List<Transaction>> = mapOf(),
    val getTxQueue: List<GetTxWithMeta> = listOf()
) : WatcherState() {
    override fun process(event: WatcherEvent): Pair<WatcherState, List<WatcherAction>> =
        when (event) {
            is ClientStateUpdate -> {
                if (event.connection == Connection.ESTABLISHED) returnState(AskForHeaderUpdate)
                else returnState()
            }
            is ReceivedMessage -> when (val message = event.message) {
                is HeaderSubscriptionResponse -> {
                    newState {
                        state = (WatcherRunning(height = message.height, tip = message.header, block2tx = block2tx, watches = watches))
                        actions = buildList {
                            watches.mapNotNull { registerToScriptHash(it) }.forEach { add(it) }
                            publishQueue.forEach { add(PublishAsapAction(it.tx)) }
                            getTxQueue.forEach { add(AskForTransaction(it.txid, it.response)) }
                        }
                    }
                }
                else -> returnState()
            }
            is ReceiveWatch -> newState(copy(watches = watches + event.watch))
            is PublishAsapEvent -> {
                newState(copy(publishQueue = publishQueue + PublishAsap(event.tx)))
            }
            is GetTxWithMetaEvent -> {
                newState(copy(getTxQueue = getTxQueue + (event.request)))
            }
            else -> unhandled(event)
        }
}

private data class WatcherRunning(
    val height: Int,
    val tip: BlockHeader,
    val watches: Set<Watch> = setOf(),
    val scriptHashStatus: Map<ByteVector32, String> = mapOf(),
    val block2tx: Map<Long, List<Transaction>> = mapOf(),
    val sent: Set<Transaction> = setOf()
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
                        val broadcastTxActions = toPublish.values.flatten().map { BroadcastTxAction(it) }

                        newState {
                            state = copy(
                                height = newHeight,
                                tip = newTip,
                                block2tx = block2tx - toPublish.keys,
                                sent = sent + toPublish.values.flatten()
                            )
                            actions = scriptHashesActions + broadcastTxActions
                        }
                    }
                    message is ScriptHashSubscriptionResponse -> {
                        val (scriptHash, status) = message

                        val existingStatus = scriptHashStatus[scriptHash]

                        newState {
                            state = copy(scriptHashStatus = scriptHashStatus + (scriptHash to status))
                            actions = buildList {
                                when {
                                    existingStatus == status -> logger.debug { "already have status=$status for scriptHash=$scriptHash" }
                                    status.isEmpty() -> logger.debug { "empty status for scriptHash=$scriptHash" }
                                    else -> {
                                        logger.debug { "scriptHash=$scriptHash at height=$height" }
                                        add(AskForScriptHashHistory(scriptHash))
                                    }
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
                                            .map { w ->
                                                logger.info { "output ${w.txId}:${w.outputIndex} spent by transaction ${tx.txid}" }
                                                NotifyWatch(
                                                    WatchEventSpent(w.channelId, w.event, tx)
                                                )
                                            }
                                    }

                                // WatchConfirmed
                                val watchConfirmedTriggered = mutableListOf<WatchConfirmed>()
                                val notifyWatchConfirmedList = mutableListOf<NotifyWatch>()
                                val getMerkleList = mutableListOf<AskForMerkle>()
                                watches.filterIsInstance<WatchConfirmed>()
                                    .filter { it.txId == tx.txid }
                                    .forEach { w ->
                                        if (w.event is BITCOIN_FUNDING_DEPTHOK && w.minDepth == 0L) {
                                            // special case for mempool watches (min depth = 0)
                                            val (dummyHeight, dummyTxIndex) = makeDummyShortChannelId(w.txId)
                                            notifyWatchConfirmedList.add(
                                                NotifyWatch(
                                                    watchEvent = WatchEventConfirmed(
                                                        w.channelId,
                                                        BITCOIN_FUNDING_DEPTHOK,
                                                        dummyHeight,
                                                        dummyTxIndex,
                                                        tx
                                                    ),
                                                    broadcastNotification = w.channelNotification
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

                                newState {
                                    // NB: WatchSpent are permanent because we need to detect multiple spending of the funding tx
                                    // They are never cleaned up but it is not a big deal for now (1 channel == 1 watch)
                                    state = copy(watches = watches - watchConfirmedTriggered)
                                    actions = notifyWatchSpentList + notifyWatchConfirmedList + getMerkleList
                                }
                            }
                            is GetTxWithMeta -> {
                                message.contextOpt.response.complete(
                                    GetTxWithMetaResponse(
                                        message.tx.txid,
                                        message.tx,
                                        tip.time
                                    )
                                )
                                returnState()
                            }
                            else -> returnState()
                        }
                    }
                    message is BroadcastTransactionResponse -> {
                        val (tx, errorOpt) = message
                        when {
                            errorOpt == null -> {
                                logger.info { "broadcast succeeded for txid=${tx.txid} tx=$tx" }
                            }
                            errorOpt.message.contains("transaction already in block chain") -> {
                                logger.info { "broadcast ignored for txid=${tx.txid} tx=$tx (tx was already in blockchain)" }
                            }
                            else -> logger.error { "broadcast failed for txid=${tx.txid} tx=$tx with error=$errorOpt" }
                        }
                        newState(copy(sent = sent - tx))
                    }
                    message is ServerError && message.request is GetTransaction && message.request.contextOpt is GetTxWithMeta -> {
                        message.request.contextOpt.response.complete(
                            GetTxWithMetaResponse(
                                message.request.txid,
                                null,
                                tip.time
                            )
                        )
                        returnState()
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
                                NotifyWatch(
                                    watchEvent = WatchEventConfirmed(w.channelId, w.event, txheight, pos, tx),
                                    broadcastNotification = w.channelNotification
                                )
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
            is GetTxWithMetaEvent -> returnState(AskForTransaction(event.request.txid, event.request))
            is PublishAsapEvent -> {
                val tx = event.tx
                val blockCount = height
                val cltvTimeout = Scripts.cltvTimeout(tx)
                val csvTimeout = Scripts.csvTimeout(tx)

                when {
                    csvTimeout > 0 -> {
                        require(tx.txIn.size == 1) { "watcher only supports tx with 1 input, this tx has ${tx.txIn.size} inputs" }
                        val parentTxid = tx.txIn[0].outPoint.txid
                        logger.info { "txid=${tx.txid} has a relative timeout of $csvTimeout blocks, watching parenttxid=$parentTxid tx=$tx" }
                        val parentPublicKeyScript = WatchConfirmed.extractPublicKeyScript(tx.txIn.first().witness)
                        setupWatch(
                            watch = WatchConfirmed(
                                ByteVector32.Zeroes, parentTxid,
                                parentPublicKeyScript, 1, BITCOIN_PARENT_TX_CONFIRMED(tx),
                                channelNotification = false
                            )
                        )
                    }
                    cltvTimeout > blockCount -> {
                        logger.info { "delaying publication of txid=${tx.txid} until block=$cltvTimeout (curblock=$blockCount)" }
                        val updatedBlock2tx = block2tx.toMutableMap()
                        updatedBlock2tx[cltvTimeout] = block2tx.getOrElse(cltvTimeout) { emptyList() } + tx
                        newState(copy(block2tx = updatedBlock2tx))
                    }
                    else -> {
                        logger.info { "publishing tx=[${tx.txid} / $tx]" }
                        newState {
                            state = copy(sent = sent + tx)
                            actions = listOf(BroadcastTxAction(tx))
                        }
                    }
                }
            }
            is ReceiveWatch -> setupWatch(event.watch)
            is ReceiveWatchEvent -> when (val watchEvent = event.watchEvent) {
                is WatchEventConfirmed -> {
                    if (event.watchEvent.event !is BITCOIN_PARENT_TX_CONFIRMED) returnState()
                    else {
                        val bitcoinEvent = event.watchEvent.event as BITCOIN_PARENT_TX_CONFIRMED
                        val tx = bitcoinEvent.childTx
                        val blockHeight = watchEvent.blockHeight
                        logger.info { "parent tx of txid=${tx.txid} has been confirmed" }
                        val blockCount = height
                        val cltvTimeout = Scripts.cltvTimeout(tx)
                        val csvTimeout = Scripts.csvTimeout(tx)
                        val absTimeout = max(blockHeight + csvTimeout, cltvTimeout)
                        if (absTimeout > blockCount) {
                            logger.info { "delaying publication of txid=${tx.txid} until block=$absTimeout (curblock=$blockCount)" }
                            val updatedBlock2tx = block2tx.toMutableMap()
                            updatedBlock2tx[absTimeout] = block2tx.getOrElse(absTimeout) { emptyList() } + tx
                            newState(copy(block2tx = updatedBlock2tx))
                        } else {
                            logger.info { "publishing tx=[${tx.txid} / $tx]" }
                            newState {
                                state = copy(sent = sent + tx)
                                actions = listOf(BroadcastTxAction(tx))
                            }
                        }
                    }
                }
                else -> unhandled(event)
            }
            is ClientStateUpdate -> {
                if (event.connection == Connection.CLOSED) newState(
                    WatcherDisconnected(
                        watches = watches,
                        publishQueue = sent.map { PublishAsap(it) }.toSet(),
                        block2tx = block2tx
                    )
                ) else returnState()
            }
        }

    private fun setupWatch(watch: Watch) = when (watch) {
        is WatchLost -> returnState() // ignore WatchLost for now
        in watches -> returnState()
        else -> newState {
            state = copy(watches = watches + watch)
            actions = registerToScriptHash(watch)?.let { this.actions + it } ?: actions
        }
    }
}

private fun WatcherState.unhandled(message: WatcherEvent): Pair<WatcherState, List<WatcherAction>> =
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

@OptIn(ExperimentalCoroutinesApi::class)
class ElectrumWatcher(val client: ElectrumClient, val scope: CoroutineScope) : CoroutineScope by scope {
    private val notificationsChannel = BroadcastChannel<WatchEvent>(Channel.BUFFERED)
    fun openNotificationsSubscription() = notificationsChannel.openSubscription()

    private val eventChannel = Channel<WatcherEvent>(Channel.BUFFERED)

    private val clientNotificationsSubscription = client.openNotificationsSubscription()

    private val input = produce(capacity = Channel.BUFFERED) {
        launch { eventChannel.consumeEach { send(it) } }
        launch {
            client.connectionState.collect {
                eventChannel.send(ClientStateUpdate(it))
            }
        }
        launch {
            clientNotificationsSubscription.consumeEach {
                eventChannel.send(ReceivedMessage(it))
            }
        }
    }

    private var state: WatcherState = WatcherDisconnected()

    private var runJob: Job? = null

    init {
        logger.info { "Init Electrum Watcher" }
        runJob = launch { run() }
        launch { eventChannel.send(ClientStateUpdate(client.connectionState.value)) }
    }

    private suspend fun run() {
        input.consumeEach { input ->

            val (newState, actions) = state.process(input)
            state = newState

            actions.forEach { action ->
                yield()
                when (action) {
                    is AskForHeaderUpdate -> client.sendMessage(AskForHeaderSubscriptionUpdate)
                    is RegisterToScriptHashNotification -> client.sendElectrumRequest(
                        ScriptHashSubscription(action.scriptHash)
                    )
                    is PublishAsapAction -> eventChannel.send(PublishAsapEvent(action.tx))
                    is BroadcastTxAction -> client.sendElectrumRequest(BroadcastTransaction(action.tx))
                    is AskForScriptHashHistory -> client.sendElectrumRequest(
                        GetScriptHashHistory(action.scriptHash)
                    )
                    is AskForTransaction -> client.sendElectrumRequest(
                        GetTransaction(action.txid, action.contextOpt)
                    )
                    is AskForMerkle -> client.sendElectrumRequest(
                        GetMerkle(action.txId, action.txheight, action.tx)
                    )
                    is NotifyWatch -> {
                        if (action.broadcastNotification)
                            notificationsChannel.send(action.watchEvent)
                        else
                            eventChannel.send(ReceiveWatchEvent(action.watchEvent))
                    }
                    is NotifyTxWithMeta -> TODO("implement this")
                }
            }
        }
    }

    fun send(message: WatcherEvent) {
        launch { eventChannel.send(message) }
    }

    fun publish(tx: Transaction) {
        launch {
            eventChannel.send(PublishAsapEvent(tx))
        }
    }

    fun watch(watch: Watch) {
        launch {
            eventChannel.send(ReceiveWatch(watch))
        }
    }

    fun stop() {
        logger.info { "Stop Electrum Watcher" }
        // Cancel subscriptions
        clientNotificationsSubscription.cancel()
        // Cancel event consumer
        runJob?.cancel()
        // Cancel broadcast channel
        notificationsChannel.cancel()
        // Cancel event channels
        eventChannel.cancel()
    }

    @ThreadLocal
    companion object {
        val logger by eclairLogger<ElectrumWatcher>()

        internal fun registerToScriptHash(watch: Watch): WatcherAction? = when (watch) {
            is WatchSpent -> {
                val (_, txid, outputIndex, publicKeyScript, _) = watch
                val scriptHash = computeScriptHash(publicKeyScript)
                logger.info { "added watch-spent on output=$txid:$outputIndex scriptHash=$scriptHash" }
                RegisterToScriptHashNotification(scriptHash)
            }
            is WatchConfirmed -> {
                val (_, txid, publicKeyScript, _, _) = watch
                val scriptHash = computeScriptHash(publicKeyScript)
                logger.info { "added watch-confirmed on txid=$txid scriptHash=$scriptHash" }
                RegisterToScriptHashNotification(scriptHash)
            }
            else -> null
        }


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
