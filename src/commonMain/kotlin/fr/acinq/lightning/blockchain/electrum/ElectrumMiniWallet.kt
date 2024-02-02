package fr.acinq.lightning.blockchain.electrum

import fr.acinq.bitcoin.*
import fr.acinq.lightning.SwapInParams
import fr.acinq.lightning.logging.*
import fr.acinq.lightning.utils.sum
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.launch

data class WalletState(val addresses: Map<String, List<Utxo>>) {
    val utxos: List<Utxo> = addresses.flatMap { it.value }
    val totalBalance = utxos.map { it.amount }.sum()

    fun withoutReservedUtxos(reserved: Set<OutPoint>): WalletState {
        return copy(addresses = addresses.mapValues {
            it.value.filter { item -> !reserved.contains(item.outPoint) }
        })
    }

    fun withConfirmations(currentBlockHeight: Int, swapInParams: SwapInParams): WalletWithConfirmations = WalletWithConfirmations(
        swapInParams = swapInParams, currentBlockHeight = currentBlockHeight, all = utxos,
    )

    data class Utxo(val txId: TxId, val outputIndex: Int, val blockHeight: Long, val previousTx: Transaction) {
        val outPoint = OutPoint(previousTx, outputIndex.toLong())
        val amount = previousTx.txOut[outputIndex].amount
    }

    /**
     * The utxos of a wallet may be discriminated against their number of confirmations. Typically, this is used in the
     * context of a funding, which should happen only after a given depth.
     *
     * @param swapInParams confirmation parameters for swaps.
     * @param currentBlockHeight the current block height as seen by the wallet.
     * @param all all the utxos of the wallet
     */
    data class WalletWithConfirmations(val swapInParams: SwapInParams, val currentBlockHeight: Int, val all: List<Utxo>) {
        /** Unconfirmed utxos that shouldn't be used yet. */
        val unconfirmed by lazy { all.filter { it.blockHeight == 0L } }

        /** Confirmed utxos that need more confirmations to be used. */
        val weaklyConfirmed by lazy { all.filter { it.blockHeight > 0 && confirmationsNeeded(it) > 0 && confirmations(it) < swapInParams.maxConfirmations } }

        /** Confirmed utxos that are safe to use. */
        val deeplyConfirmed by lazy { all.filter { it.blockHeight > 0 && confirmationsNeeded(it) == 0 && confirmations(it) < swapInParams.maxConfirmations } }

        /** Confirmed utxos that cannot be used anymore because we're too close to the refund delay. */
        val lockedUntilRefund by lazy { all.filter { swapInParams.maxConfirmations <= confirmations(it) && confirmations(it) < swapInParams.refundDelay } }

        /** Confirmed utxos that cannot be used anymore but can be refunded. */
        val readyForRefund by lazy { all.filter { swapInParams.refundDelay <= confirmations(it) } }

        /** Returns the number of confirmations an utxo has. */
        fun confirmations(utxo: Utxo): Int = when (val confirmedAt = utxo.blockHeight.toInt()) {
            0 -> 0
            // we add 1 because if a tx is confirmed at current block height, it is considered to have one confirmation
            else -> currentBlockHeight - confirmedAt + 1
        }

        /** Returns the number of confirmations an utxo must reach to be considered deeply confirmed, 0 if deep enough. */
        fun confirmationsNeeded(utxo: Utxo): Int = when (utxo.blockHeight.toInt()) {
            0 -> swapInParams.minConfirmations
            else -> swapInParams.minConfirmations - confirmations(utxo)
        }.coerceAtLeast(0)
    }

    companion object {
        val empty: WalletState = WalletState(emptyMap())
    }
}

val List<WalletState.Utxo>.balance get() = this.map { it.amount }.sum()

private sealed interface WalletCommand {
    companion object {
        data object ElectrumConnected : WalletCommand
        data class ElectrumNotification(val msg: ElectrumResponse) : WalletCommand
        data class AddAddress(val bitcoinAddress: String) : WalletCommand
    }
}

/**
 * A very simple wallet that only watches one address and publishes its utxos.
 */
class ElectrumMiniWallet(
    val chainHash: BlockHash,
    private val client: IElectrumClient,
    private val scope: CoroutineScope,
    loggerFactory: LoggerFactory,
    private val name: String = ""
) : CoroutineScope by scope {

    private val logger = MDCLogger(loggerFactory.newLogger(this::class))
    private fun mdc(): Map<String, Any> {
        return mapOf(
            "wallet" to name,
            "utxos" to walletStateFlow.value.utxos.size,
            "balance" to walletStateFlow.value.totalBalance
        )
    }

    // state flow with the current balance
    private val _walletStateFlow = MutableStateFlow(WalletState(emptyMap()))
    val walletStateFlow get() = _walletStateFlow.asStateFlow()

    // all currently watched script hashes and their corresponding bitcoin address
    private var scriptHashes: Map<ByteVector32, String> = emptyMap()

    // the mailbox of this "actor"
    private val mailbox: Channel<WalletCommand> = Channel(Channel.BUFFERED)

    fun addAddress(bitcoinAddress: String) {
        launch {
            mailbox.send(WalletCommand.Companion.AddAddress(bitcoinAddress))
        }
    }

    /** This function should only be used in tests, to test the wallet notification flow. */
    fun setWalletState(walletState: WalletState) {
        launch {
            _walletStateFlow.value = walletState
        }
    }

    private val job: Job

    init {
        suspend fun WalletState.processSubscriptionResponse(msg: ScriptHashSubscriptionResponse): WalletState {
            val bitcoinAddress = scriptHashes[msg.scriptHash]
            return when {
                bitcoinAddress == null -> {
                    // this should never happen
                    logger.error { "received subscription response for script hash ${msg.scriptHash} that does not match any address" }
                    this
                }
                msg.status == null -> this.copy(addresses = this.addresses + (bitcoinAddress to listOf()))
                else -> {
                    val unspents = client.getScriptHashUnspents(msg.scriptHash)
                    val previouslysKnownTxs = (_walletStateFlow.value.addresses[bitcoinAddress] ?: emptyList()).map { it.txId to it.previousTx }.toMap()
                    val utxos = unspents
                        .mapNotNull { item -> (previouslysKnownTxs[item.txid] ?: client.getTx(item.txid))?.let { item to it } } // only retrieve txs from electrum if necessary and ignore the utxo if the parent tx cannot be retrieved
                        .map { (item, previousTx) -> WalletState.Utxo(item.txid, item.outputIndex, item.blockHeight, previousTx) }
                    val nextWalletState = this.copy(addresses = this.addresses + (bitcoinAddress to utxos))
                    logger.info(mdc()) { "${unspents.size} utxo(s) for address=$bitcoinAddress balance=${nextWalletState.totalBalance}" }
                    unspents.forEach { logger.debug { "utxo=${it.outPoint.txid}:${it.outPoint.index} amount=${it.value} sat" } }
                    nextWalletState
                }
            }
        }

        /**
         * Attempts to subscribe to changes affecting a given bitcoin address.
         * Depending on the status of the electrum connection, the subscription may or may not be sent to a server.
         * It is the responsibility of the caller to resubscribe on reconnection.
         */
        suspend fun subscribe(scriptHash: ByteVector32, bitcoinAddress: String) {
            kotlin.runCatching { client.startScriptHashSubscription(scriptHash) }.map { response ->
                logger.info { "subscribed to address=$bitcoinAddress scriptHash=$scriptHash" }
                _walletStateFlow.value = _walletStateFlow.value.processSubscriptionResponse(response)
            }
        }

        fun computeScriptHash(bitcoinAddress: String): ByteVector32? {
            return when (val result = Bitcoin.addressToPublicKeyScript(chainHash, bitcoinAddress)) {
                is AddressToPublicKeyScriptResult.Failure -> {
                    logger.error { "cannot subscribe to $bitcoinAddress ($result)" }
                    null
                }

                is AddressToPublicKeyScriptResult.Success -> {
                    val pubkeyScript = ByteVector(Script.write(result.script))
                    return ElectrumClient.computeScriptHash(pubkeyScript)
                }
            }
        }

        job = launch {
            launch {
                // listen to connection events
                client.connectionStatus.filterIsInstance<ElectrumConnectionStatus.Connected>().collect { mailbox.send(WalletCommand.Companion.ElectrumConnected) }
            }
            launch {
                // listen to subscriptions events
                client.notifications.collect { mailbox.send(WalletCommand.Companion.ElectrumNotification(it)) }
            }
            launch {
                mailbox.consumeAsFlow().collect {
                    when (it) {
                        is WalletCommand.Companion.ElectrumConnected -> {
                            logger.info(mdc()) { "electrum connected" }
                            scriptHashes.forEach { (scriptHash, address) -> subscribe(scriptHash, address) }
                        }

                        is WalletCommand.Companion.ElectrumNotification -> {
                            if (it.msg is ScriptHashSubscriptionResponse) {
                                _walletStateFlow.value = _walletStateFlow.value.processSubscriptionResponse(it.msg)
                            }
                        }

                        is WalletCommand.Companion.AddAddress -> {
                            computeScriptHash(it.bitcoinAddress)?.let { scriptHash ->
                                if (!scriptHashes.containsKey(scriptHash)) {
                                    logger.info(mdc()) { "adding new address=${it.bitcoinAddress}" }
                                    scriptHashes = scriptHashes + (scriptHash to it.bitcoinAddress)
                                    subscribe(scriptHash, it.bitcoinAddress)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    fun stop() = job.cancel()
}
