package fr.acinq.lightning.blockchain.mempool

import fr.acinq.bitcoin.Transaction
import fr.acinq.lightning.blockchain.*
import fr.acinq.lightning.logging.LoggerFactory
import fr.acinq.lightning.logging.debug
import fr.acinq.lightning.logging.info
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

class MempoolSpaceWatcher(val client: MempoolSpaceClient, val scope: CoroutineScope, loggerFactory: LoggerFactory, val pollingInterval: Duration = 10.minutes) : IWatcher {

    private val logger = loggerFactory.newLogger(this::class)
    private val mailbox = Channel<Watch>(Channel.BUFFERED)

    private val _notificationsFlow = MutableSharedFlow<WatchTriggered>(replay = 0, extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.SUSPEND)
    override fun openWatchNotificationsFlow(): Flow<WatchTriggered> = _notificationsFlow.asSharedFlow()

    init {
        scope.launch {
            mailbox.consumeAsFlow().collect { watch ->
                when (watch) {
                    is WatchSpent -> scope.launch {
                        logger.info { "add watch-spent on ${watch.txId}:${watch.outputIndex}" }
                        val spendingTxs = mutableSetOf<Transaction>()
                        while (true) {
                            when (val spendingTx = client.getOutspend(watch.txId, watch.outputIndex)) {
                                null -> delay(pollingInterval)
                                else -> {
                                    // There may be multiple txs spending the same outpoint, due to RBFs, etc. We notify
                                    // each of them once.
                                    if (!spendingTxs.contains(spendingTx)) {
                                        logger.info { "${watch.txId}:${watch.outputIndex} was spent by txId=${spendingTx.txid}" }
                                        _notificationsFlow.emit(WatchSpentTriggered(watch.channelId, watch.event, spendingTx))
                                        spendingTxs.add(spendingTx)
                                    }
                                    // We keep watching for spending transactions until one of them confirms
                                    if ((client.getConfirmations(spendingTx.txid) ?: 0) > 3) {
                                        logger.info { "transaction txId=${spendingTx.txid} spending ${watch.txId}:${watch.outputIndex} has confirmed" }
                                        break
                                    }
                                }
                            }
                        }
                        logger.debug { "terminating watch-spent on ${watch.txId}:${watch.outputIndex}" }
                    }
                    is WatchConfirmed -> scope.launch {
                        logger.info { "add watch-confirmed on ${watch.txId}" }
                        while (true) {
                            val merkleProof = client.getTransactionMerkleProof(watch.txId)
                            val currentBlockHeight = client.getBlockTipHeight()
                            when {
                                merkleProof == null || currentBlockHeight == null -> {}
                                else -> {
                                    val confirmations = (currentBlockHeight - merkleProof.block_height + 1)
                                    logger.info { "${watch.txId} has $confirmations/${watch.minDepth} confirmations" }
                                    when {
                                        confirmations < watch.minDepth -> {}
                                        else -> {
                                            val tx = client.getTransaction(watch.txId)
                                            when {
                                                tx == null -> {}
                                                else -> {
                                                    logger.info { "${watch.txId} has reached min depth" }
                                                    _notificationsFlow.emit(WatchConfirmedTriggered(watch.channelId, watch.event, merkleProof.block_height, merkleProof.pos, tx))
                                                    break
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                            delay(pollingInterval)
                        }
                        logger.debug { "terminating watch-confirmed on ${watch.txId}" }
                    }
                }
            }
        }
    }

    override suspend fun watch(watch: Watch) {
        mailbox.send(watch)
    }

    override suspend fun publish(tx: Transaction) {
        client.publish(tx)
    }
}