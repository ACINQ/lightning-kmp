package fr.acinq.lightning.blockchain.electrum

import fr.acinq.bitcoin.BlockHeader
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.Transaction
import fr.acinq.bitcoin.TxIn
import fr.acinq.lightning.blockchain.*
import fr.acinq.lightning.blockchain.electrum.ElectrumClient.Companion.computeScriptHash
import fr.acinq.lightning.blockchain.electrum.ElectrumWatcher.Companion.logger
import fr.acinq.lightning.blockchain.electrum.ElectrumWatcher.Companion.registerToScriptHash
import fr.acinq.lightning.transactions.Scripts
import fr.acinq.lightning.utils.Connection
import fr.acinq.lightning.utils.currentTimestampMillis
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.channels.produce
import kotlinx.coroutines.flow.*
import org.kodein.log.Logger
import org.kodein.log.LoggerFactory
import org.kodein.log.newLogger
import kotlin.math.max

sealed class WatcherEvent
class PublishAsapEvent(val tx: Transaction) : WatcherEvent()
data class GetTxWithMetaEvent(val request: GetTxWithMeta) : WatcherEvent()
class ReceiveWatch(val watch: Watch) : WatcherEvent()
class ReceiveWatchEvent(val watchEvent: WatchEvent) : WatcherEvent()
class ReceivedMessage(val message: ElectrumMessage) : WatcherEvent()
class ClientStateUpdate(val connection: Connection) : WatcherEvent()

sealed class NotifyEvent
class NotifyWatchEvent(val watchEvent: WatchEvent) : NotifyEvent()
class NotifyTxEvent(val channelId: ByteVector32, val txWithMeta: GetTxWithMetaResponse) : NotifyEvent()
class NotifyUpToDateEvent(val millis: Long) : NotifyEvent()

internal sealed class WatcherAction
private object AskForHeaderUpdate : WatcherAction()
internal data class RegisterToScriptHashNotification(val scriptHash: ByteVector32) : WatcherAction()
private data class AskForScriptHashHistory(val scriptHash: ByteVector32) : WatcherAction()
private data class AskForTransaction(val txid: ByteVector32, val contextOpt: Any? = null) : WatcherAction()
private data class AskForMerkle(val txId: ByteVector32, val txheight: Int, val tx: Transaction) : WatcherAction()
private data class NotifyWatch(val watchEvent: WatchEvent, val broadcastNotification: Boolean = true) : WatcherAction()
private data class NotifyTxWithMeta(val channelId: ByteVector32, val txWithMeta: GetTxWithMetaResponse) : WatcherAction()
private data class PublishAsapAction(val tx: Transaction) : WatcherAction()
private data class BroadcastTxAction(val tx: Transaction) : WatcherAction()

/**
 * [WatcherState] State
 *
 *                   ElectrumClient
 *                         ^
 *                         |
 *  -> Connect             | subscriptions:
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
internal sealed class WatcherState {
    abstract fun process(event: WatcherEvent, logger: Logger): Pair<WatcherState, List<WatcherAction>>
}

private data class WatcherDisconnected(
    val watches: Set<Watch> = setOf(),
    val publishQueue: Set<PublishAsap> = setOf(),
    val block2tx: Map<Long, List<Transaction>> = mapOf(),
    val getTxQueue: List<GetTxWithMeta> = listOf()
) : WatcherState() {
    override fun process(event: WatcherEvent, logger: Logger): Pair<WatcherState, List<WatcherAction>> =
        when (event) {
            is ClientStateUpdate -> {
                if (event.connection == Connection.ESTABLISHED) returnState(AskForHeaderUpdate)
                else returnState()
            }
            is ReceivedMessage -> when (val message = event.message) {
                is HeaderSubscriptionResponse -> {
                    newState {
                        actions = buildList {
                            watches.mapNotNull { registerToScriptHash(it, logger) }.forEach { add(it) }
                            publishQueue.forEach { add(PublishAsapAction(it.tx)) }
                            getTxQueue.forEach { add(AskForTransaction(it.txid, it.channelId)) }
                        }
                        state = WatcherRunning(
                            height = message.blockHeight,
                            tip = message.header,
                            block2tx = block2tx,
                            watches = watches,
                            scriptHashSubscriptions = actions.filterIsInstance<RegisterToScriptHashNotification>().map { it.scriptHash }.toSet()
                        )
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

internal data class WatcherRunning(
    val height: Int,
    val tip: BlockHeader,
    val watches: Set<Watch> = setOf(),
    val scriptHashStatus: Map<ByteVector32, String> = mapOf(),
    val scriptHashSubscriptions: Set<ByteVector32> = setOf(),
    val block2tx: Map<Long, List<Transaction>> = mapOf(),
    val sent: Set<Transaction> = setOf()
) : WatcherState() {
    override fun process(event: WatcherEvent, logger: Logger): Pair<WatcherState, List<WatcherAction>> =
        when (event) {
            is ReceivedMessage -> {
                val message = event.message
                when {
                    message is HeaderSubscriptionResponse && message.header == tip -> returnState()
                    message is HeaderSubscriptionResponse -> {
                        val (newHeight, newTip) = message
                        logger.info { "new tip: ${newTip.blockId}->$newHeight" }
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
                        if (scriptHashSubscriptions.contains(message.scriptHash)) {
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
                        } else {
                            // ignore responses for unknown script_hashes (electrum client doesn't maintain a list of subscribers so we receive all subscriptions)
                            returnState()
                        }
                    }
                    message is GetScriptHashHistoryResponse -> {
                        // we retrieve the transaction before checking watches
                        // NB: height=-1 means that the tx is unconfirmed and at least one of its inputs is also unconfirmed.
                        // we need to take them into consideration if we want to handle unconfirmed txes (which is the case for turbo channels)
                        val getTransactionList = message.history.filter { it.blockHeight >= -1 }.map {
                            AskForTransaction(it.txid, it)
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
                                                NotifyWatch(WatchEventSpent(w.channelId, w.event, tx))
                                            }
                                    }

                                // WatchConfirmed
                                val watchConfirmedTriggered = mutableListOf<WatchConfirmed>()
                                val notifyWatchConfirmedList = mutableListOf<NotifyWatch>()
                                val getMerkleList = mutableListOf<AskForMerkle>()
                                watches.filterIsInstance<WatchConfirmed>()
                                    .filter { it.txId == tx.txid }
                                    .forEach { w ->
                                        if (item.blockHeight > 0) {
                                            val confirmations = height - item.blockHeight + 1
                                            logger.info { "txid=${w.txId} was confirmed at height=${item.blockHeight} and now has confirmations=$confirmations (currentHeight=$height)" }
                                            if (confirmations >= w.minDepth) {
                                                // we need to get the tx position in the block
                                                getMerkleList.add(AskForMerkle(w.txId, item.blockHeight, tx))
                                            }
                                        }
                                    }

                                newState {
                                    // NB: WatchSpent are permanent because we need to detect multiple spending of the funding tx
                                    // They are never cleaned up but it is not a big deal for now (1 channel == 1 watch)
                                    state = copy(watches = watches - watchConfirmedTriggered.toSet())
                                    actions = notifyWatchSpentList + notifyWatchConfirmedList + getMerkleList
                                }
                            }
                            is GetTxWithMeta -> {
                                val getTxWithMeta = GetTxWithMetaResponse(message.tx.txid, message.tx, tip.time)
                                returnState(action = NotifyTxWithMeta(message.contextOpt.channelId, getTxWithMeta))
                            }
                            else -> returnState()
                        }
                    }
                    message is BroadcastTransactionResponse -> {
                        val (tx, errorOpt) = message
                        when {
                            errorOpt == null -> logger.info { "broadcast succeeded for txid=${tx.txid} tx=$tx" }
                            errorOpt.message.contains("transaction already in block chain") -> logger.info { "broadcast ignored for txid=${tx.txid} tx=$tx (tx was already in blockchain)" }
                            else -> logger.error { "broadcast failed for txid=${tx.txid} tx=$tx with error=$errorOpt" }
                        }
                        newState(copy(sent = sent - tx))
                    }
                    message is ServerError && message.request is GetTransaction && message.request.contextOpt is GetTxWithMeta -> {
                        val getTxWithMeta = GetTxWithMetaResponse(message.request.txid, null, tip.time)
                        returnState(action = NotifyTxWithMeta(message.request.contextOpt.channelId, getTxWithMeta))
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
                            state = copy(watches = watches - triggered.toSet())
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
                            ),
                            logger = logger
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
            is ReceiveWatch -> setupWatch(event.watch, logger)
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
                if (event.connection is Connection.CLOSED) newState(
                    WatcherDisconnected(
                        watches = watches,
                        publishQueue = sent.map { PublishAsap(it) }.toSet(),
                        block2tx = block2tx
                    )
                ) else returnState()
            }
        }

    private fun setupWatch(watch: Watch, logger: Logger) = when (watch) {
        in watches -> returnState()
        else -> {
            val action = registerToScriptHash(watch, logger)
            newState {
                state = copy(
                    watches = watches + watch,
                    scriptHashSubscriptions = scriptHashSubscriptions + action.scriptHash
                )
                actions = if (scriptHashSubscriptions.contains(action.scriptHash)) {
                    // We've already registered to subscriptions from this scriptHash.
                    // Both WatchSpent & WatchConfirmed can be translated into the exact same
                    // electrum request, so we filter the duplicates here.
                    actions
                } else {
                    actions + action
                }
            }
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
class ElectrumWatcher(
    val client: ElectrumClient,
    val scope: CoroutineScope,
    loggerFactory: LoggerFactory
) : CoroutineScope by scope {

    private val logger = loggerFactory.newLogger(this::class)

    private val _notificationsFlow = MutableSharedFlow<NotifyEvent>(replay = 0, extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.SUSPEND)
    val notificationsFlow: SharedFlow<NotifyEvent> = _notificationsFlow

    fun openWatchNotificationsFlow(): Flow<WatchEvent> =
        _notificationsFlow.mapNotNull {
            when (it) {
                is NotifyWatchEvent -> it.watchEvent
                else -> null
            }
        }

    fun openTxNotificationsFlow(): Flow<Pair<ByteVector32, GetTxWithMetaResponse>> =
        _notificationsFlow.mapNotNull {
            when (it) {
                is NotifyTxEvent -> Pair(it.channelId, it.txWithMeta)
                else -> null
            }
        }

    fun openUpToDateFlow(): Flow<Long> = _notificationsFlow.mapNotNull {
        when (it) {
            is NotifyUpToDateEvent -> it.millis
            else -> null
        }
    }

    private val eventChannel = Channel<WatcherEvent>(Channel.BUFFERED)

    private val input = produce(capacity = Channel.BUFFERED) {
        launch {
            eventChannel.consumeEach { send(it) }
        }
        launch {
            client.connectionState.collect {
                eventChannel.send(ClientStateUpdate(it))
            }
        }
        launch {
            client.notifications.collect {
                eventChannel.send(ReceivedMessage(it))
            }
        }
    }

    private var state: WatcherState = WatcherDisconnected()
    internal val pstate get() = state

    private var runJob: Job? = null
    private var timerJob: Job? = null

    init {
        logger.info { "initializing electrum watcher" }
        runJob = launch { run() }
    }

    private suspend fun run() {
        input.consumeEach { input ->

            val (newState, actions) = state.process(input, logger)
            val oldState = state
            state = newState

            if (oldState !is WatcherRunning && newState is WatcherRunning) {
                startTimer()
            } else if (oldState is WatcherRunning && newState !is WatcherRunning) {
                stopTimer()
            }

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
                            _notificationsFlow.emit(NotifyWatchEvent(action.watchEvent))
                        else
                            eventChannel.send(ReceiveWatchEvent(action.watchEvent))
                    }
                    is NotifyTxWithMeta -> {
                        _notificationsFlow.emit(NotifyTxEvent(action.channelId, action.txWithMeta))
                    }
                }
            }
        }
    }

    private fun startTimer() {
        if (timerJob != null) return

        val timeMillis: Long = 2L * 1_000 // fire timer every 2 seconds
        timerJob = launch {
            delay(timeMillis)
            while (isActive) {
                checkIsUpToDate()
                delay(timeMillis)
            }
        }
    }

    private fun stopTimer() {
        timerJob?.cancel()
        timerJob = null
    }

    private suspend fun checkIsUpToDate() {

        // Get a list of timestamps from the client for all outgoing requests.
        // This returns an array of `RequestResponseTimestamp` instances, which includes:
        // - id: Int
        // - request: ElectrumRequest
        // - lastResponseTimestamp: Long?
        //
        // If lastResponseTimestamp is null, we have an outstanding request pending.
        //
        val info = client.requestResponseTimestamps()
        if (info == null) {
            // Client isn't connected (i.e. client.state !is ClientRunning)
            return
        }

        // We want to emit an "up-to-date" notification to the client.
        // This is used during WatchTower operation, where the client connects to electrum
        // in order to ensure the acinq server didn't publish an old commit (i.e. attempt to cheat).
        //
        // So we want to emit this message when we're fairly confident the electrum server
        // has delivered all the pending information we need. Here's how we do it:
        // - we track the last response time (timestamp in millis) per request
        // - we filter the list to only include those operations related to the WatchTower
        // - we wait until all such responses have arrived, and are older than 5 seconds

        val now = currentTimestampMillis()
        val isUpToDate = info.filter {
            when (it.request) {
                is ScriptHashSubscription -> true
                is GetScriptHashHistory -> true
                is GetTransaction -> true
                is GetMerkle -> true
                is BroadcastTransaction -> true
                else -> false
            }
        }.all {
            it.lastResponseTimestamp?.let {
                now - it > 5_000
            } ?: false
        }

        if (isUpToDate) {
            logger.info { "Watcher is up-to-date" }
            _notificationsFlow.emit(NotifyUpToDateEvent(now))
            stopTimer()
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
        logger.info { "electrum watcher stopping" }
        // Cancel event consumer
        runJob?.cancel()
        // Cancel up-to-date timer
        stopTimer()
        // Cancel event channels
        eventChannel.cancel()
    }

    companion object {

        internal fun registerToScriptHash(watch: Watch, logger: Logger): RegisterToScriptHashNotification = when (watch) {
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
        }
    }
}
