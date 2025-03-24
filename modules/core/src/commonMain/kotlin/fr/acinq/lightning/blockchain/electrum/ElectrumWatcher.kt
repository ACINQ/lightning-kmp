package fr.acinq.lightning.blockchain.electrum

import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.Transaction
import fr.acinq.lightning.blockchain.*
import fr.acinq.lightning.logging.LoggerFactory
import fr.acinq.lightning.logging.debug
import fr.acinq.lightning.logging.info
import fr.acinq.lightning.transactions.Scripts
import fr.acinq.lightning.utils.currentTimestampMillis
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlin.math.max

class ElectrumWatcher(val client: IElectrumClient, val scope: CoroutineScope, loggerFactory: LoggerFactory) : IWatcher, CoroutineScope by scope {

    private val logger = loggerFactory.newLogger(this::class)
    private val mailbox = Channel<WatcherCommand>(Channel.BUFFERED)

    private val _notificationsFlow = MutableSharedFlow<WatchTriggered>(replay = 0, extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.SUSPEND)
    override fun openWatchNotificationsFlow(): Flow<WatchTriggered> = _notificationsFlow.asSharedFlow()

    // this is used by a Swift watch-tower module in the Phoenix iOS app to tell when the watcher is up-to-date
    // the value that is emitted in the time elapsed (in milliseconds) since the watcher is ready and idle
    private val _uptodateFlow = MutableSharedFlow<Long>(replay = 0, extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.SUSPEND)
    fun openUpToDateFlow(): Flow<Long> = _uptodateFlow.asSharedFlow()

    override suspend fun watch(watch: Watch) {
        mailbox.send(WatcherCommand.AddWatch(watch))
    }

    override suspend fun publish(tx: Transaction) {
        mailbox.send(WatcherCommand.Publish(tx))
    }

    private sealed interface WatcherCommand {
        data class AddWatch(val watch: Watch) : WatcherCommand
        data class ProcessNotification(val notification: ElectrumResponse) : WatcherCommand
        data class ProcessConnectionStatus(val status: ElectrumConnectionStatus) : WatcherCommand
        data class Publish(val tx: Transaction) : WatcherCommand
        data object NotifyIfReady : WatcherCommand
    }

    private data class State(
        val height: Int, // current block height. 0 means that we're not connected
        val watches: Set<Watch> = setOf(),
        val scriptHashStatus: Map<ByteVector32, String> = mapOf(),
        val scriptHashSubscriptions: Set<ByteVector32> = setOf(),
        val publishQueue: Set<Transaction> = setOf(),
        val block2tx: Map<Long, Set<Transaction>> = mapOf(),
        val sent: Set<Transaction> = setOf(),
        val idleSince: Long? = null
    ) {
        val isConnected = height != 0
    }

    private var state = State(0)

    private var runJob: Job? = null
    private var timerJob: Job? = null

    init {
        logger.info { "initializing electrum watcher" }

        suspend fun processScripHashHistory(history: List<TransactionHistoryItem>) = runCatching {
            val txs = history.filter { it.blockHeight >= -1 }.mapNotNull { client.getTx(it.txid) }

            // WatchSpent
            txs.forEach { tx ->
                val outpoints = tx.txIn.map { it.outPoint }
                outpoints.forEach { outPoint ->
                    state.watches
                        .filterIsInstance<WatchSpent>()
                        .filter { it.txId == outPoint.txid && it.outputIndex == outPoint.index.toInt() }
                        .map { w ->
                            logger.info { "output ${w.txId}:${w.outputIndex} spent by transaction ${tx.txid}" }
                            _notificationsFlow.emit(WatchSpentTriggered(w.channelId, w.event, tx))
                        }
                }
            }

            // WatchConfirmed
            val txMap = txs.associateBy { it.txid }
            history.filter { it.blockHeight > 0 }.forEach { item ->
                val triggered = state.watches
                    .filterIsInstance<WatchConfirmed>()
                    .filter { it.txId == item.txid }
                    .filter { state.height - item.blockHeight + 1 >= it.minDepth }
                triggered.forEach { w ->
                    client.getMerkle(w.txId, item.blockHeight)?.let { merkle ->
                        val confirmations = state.height - merkle.blockHeight + 1
                        logger.info { "txid=${w.txId} had confirmations=$confirmations in block=${merkle.blockHeight} pos=${merkle.pos}" }
                        _notificationsFlow.emit(WatchConfirmedTriggered(w.channelId, w.event, merkle.blockHeight, merkle.pos, txMap[w.txId]!!))

                        // check whether we have transactions to publish
                        when (val event = w.event) {
                            is WatchConfirmed.ParentTxConfirmed -> {
                                val tx = event.childTx
                                logger.info { "parent tx of txid=${tx.txid} has been confirmed" }
                                val cltvTimeout = Scripts.cltvTimeout(tx)
                                val csvTimeout = Scripts.csvTimeout(tx)
                                val absTimeout = max(merkle.blockHeight + csvTimeout, cltvTimeout)
                                state = if (absTimeout > state.height) {
                                    logger.info { "delaying publication of txid=${tx.txid} until block=$absTimeout (curblock=${state.height})" }
                                    val block2tx = state.block2tx + (absTimeout to state.block2tx.getOrElse(absTimeout) { setOf() } + tx)
                                    state.copy(block2tx = block2tx)
                                } else {
                                    client.broadcastTransaction(tx)
                                    state.copy(sent = state.sent + tx)
                                }
                            }
                            else -> {}
                        }
                    }
                }
                state = state.copy(watches = state.watches - triggered.toSet())
            }
        }

        suspend fun processScripHashSubscriptionResponse(response: ScriptHashSubscriptionResponse) = runCatching {
            val existingStatus = state.scriptHashStatus[response.scriptHash]
            if (response.status != null && response.status != existingStatus) {
                state = state.copy(scriptHashStatus = state.scriptHashStatus + (response.scriptHash to response.status))
                val history = client.getScriptHashHistory(response.scriptHash)
                processScripHashHistory(history)
                state = state.copy(idleSince = currentTimestampMillis())
            }
        }

        suspend fun addWatch(watch: Watch) {
            val scriptHash = when (watch) {
                is WatchSpent -> {
                    val (_, txid, outputIndex, publicKeyScript, _) = watch
                    val scriptHash = ElectrumClient.computeScriptHash(publicKeyScript)
                    logger.info { "added watch-spent on output=$txid:$outputIndex scriptHash=$scriptHash event=${watch.event}" }
                    scriptHash
                }

                is WatchConfirmed -> {
                    val (_, txid, publicKeyScript, _) = watch
                    val scriptHash = ElectrumClient.computeScriptHash(publicKeyScript)
                    logger.info { "added watch-confirmed on txid=$txid scriptHash=$scriptHash event=${watch.event}" }
                    scriptHash
                }
            }
            state = state.copy(
                watches = state.watches + watch, scriptHashSubscriptions = state.scriptHashSubscriptions + scriptHash
            )
            if (state.isConnected) {
                val response = client.startScriptHashSubscription(scriptHash)
                processScripHashSubscriptionResponse(response)
            }
        }

        fun startTimer() {
            if (timerJob != null) return

            val timeMillis: Long = 2L * 1_000 // fire timer every 2 seconds
            timerJob = launch {
                delay(timeMillis)
                while (isActive) {
                    mailbox.send(WatcherCommand.NotifyIfReady)
                    delay(timeMillis)
                }
            }
        }

        fun stopTimer() {
            timerJob?.cancel()
            timerJob = null
        }

        runJob = launch {
            mailbox.consumeAsFlow().collect { cmd ->
                when (cmd) {
                    is WatcherCommand.ProcessConnectionStatus -> {
                        when (cmd.status) {
                            is ElectrumConnectionStatus.Connecting -> {}

                            is ElectrumConnectionStatus.Connected -> {
                                state = state.copy(height = cmd.status.height)
                                // reset all subscriptions
                                state = state.copy(scriptHashSubscriptions = setOf(), scriptHashStatus = mapOf())
                                state.watches.forEach { addWatch(it) }

                                // handle pending publish commands
                                state.publishQueue.forEach { publish(it) }
                                state = state.copy(publishQueue = setOf())
                                startTimer()
                            }

                            is ElectrumConnectionStatus.Closed -> {
                                state = state.copy(height = 0, scriptHashSubscriptions = setOf(), scriptHashStatus = mapOf(), idleSince = null)
                                stopTimer()
                            }
                        }
                    }

                    is WatcherCommand.ProcessNotification -> {
                        when (cmd.notification) {
                            is ScriptHashSubscriptionResponse -> {
                                processScripHashSubscriptionResponse(cmd.notification)
                            }

                            is HeaderSubscriptionResponse -> {
                                logger.info { "got new tip ${cmd.notification}" }
                                state = state.copy(height = cmd.notification.blockHeight)

                                state.watches.filterIsInstance<WatchConfirmed>().forEach { watch ->
                                    val scriptHash = ElectrumClient.computeScriptHash(watch.publicKeyScript)
                                    val history = client.getScriptHashHistory(scriptHash)
                                    processScripHashHistory(history)
                                }

                                val toPublish = state.block2tx.filterKeys { it <= cmd.notification.blockHeight }
                                val txs = toPublish.values.flatten()
                                txs.forEach {
                                    logger.info { "publishing tx ${it.txid}" }
                                    client.broadcastTransaction(it)
                                }
                                state = state.copy(block2tx = state.block2tx - toPublish.keys, sent = state.sent + txs, idleSince = currentTimestampMillis())
                                logger.debug { "Watcher has processed new tip" }
                            }

                            else -> {}
                        }
                    }

                    is WatcherCommand.AddWatch -> addWatch(cmd.watch)

                    is WatcherCommand.Publish -> {
                        if (!state.isConnected) {
                            state = state.copy(publishQueue = state.publishQueue + cmd.tx)
                        } else {
                            val tx = cmd.tx
                            val blockCount = state.height
                            val cltvTimeout = Scripts.cltvTimeout(tx)
                            val csvTimeout = Scripts.csvTimeout(tx)
                            when {
                                csvTimeout > 0 -> {
                                    require(tx.txIn.size == 1) { "watcher only supports tx with 1 input, this tx has ${tx.txIn.size} inputs" }
                                    val parentTxid = tx.txIn[0].outPoint.txid
                                    logger.info { "txid=${tx.txid} has a relative timeout of $csvTimeout blocks, watching parenttxid=$parentTxid tx=$tx" }
                                    val parentPublicKeyScript = WatchConfirmed.extractPublicKeyScript(tx.txIn.first().witness)
                                    addWatch(WatchConfirmed(ByteVector32.Zeroes, parentTxid, parentPublicKeyScript, csvTimeout.toInt(), WatchConfirmed.ParentTxConfirmed(tx)))
                                }

                                cltvTimeout > blockCount -> {
                                    logger.info { "delaying publication of txid=${tx.txid} until block=$cltvTimeout (curblock=$blockCount)" }
                                    val block2tx = state.block2tx + (cltvTimeout to state.block2tx.getOrElse(cltvTimeout) { setOf() } + tx)
                                    state = state.copy(block2tx = block2tx)
                                }

                                else -> {
                                    logger.info { "publishing tx=[${tx.txid} / $tx]" }
                                    client.broadcastTransaction(tx)
                                    state = state.copy(sent = state.sent + tx)
                                }
                            }
                        }
                    }

                    is WatcherCommand.NotifyIfReady -> {
                        if (state.isConnected) {
                            state.idleSince?.let {
                                val now = currentTimestampMillis()
                                if (now > it + 5000) {
                                    // no requests in progress and watcher has been idle for more than 5s
                                    _uptodateFlow.emit(now)
                                }
                            }
                        }
                    }
                }
            }
        }

        launch {
            client.notifications.collect {
                mailbox.send(WatcherCommand.ProcessNotification(it))
            }
        }

        launch {
            client.connectionStatus.collect {
                mailbox.send(WatcherCommand.ProcessConnectionStatus(it))
            }
        }
    }

    fun stop() {
        logger.info { "electrum watcher stopping" }
        // Cancel event consumer
        runJob?.cancel()
        // Cancel up-to-date timer
        // Cancel event channels
        mailbox.cancel()
    }
}
