package fr.acinq.lightning.blockchain.electrum

import fr.acinq.bitcoin.*
import fr.acinq.lightning.utils.Connection
import fr.acinq.lightning.utils.sum
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.launch
import org.kodein.log.Logger
import org.kodein.log.LoggerFactory
import org.kodein.log.newLogger

data class WalletState(val addresses: Map<String, List<UnspentItem>>, val parentTxs: Map<ByteVector32, Transaction>) {
    /** Electrum sends parent txs separately from utxo outpoints, this boolean indicates when the wallet is consistent */
    val consistent: Boolean = addresses.flatMap { it.value }.all { parentTxs.containsKey(it.txid) }
    val utxos: List<Utxo> = addresses
        .flatMap { it.value }
        .filter { parentTxs.containsKey(it.txid) }
        .map { Utxo(parentTxs[it.txid]!!, it.outputIndex, it.blockHeight) }

    // Note that we wait for one more confirmation than the LSP to make sure they accept our inputs.
    val deeplyConfirmedUtxos: List<Utxo> = utxos.filter { it.blockHeight > SwapInConfirmations }
    val weaklyConfirmedUtxos: List<Utxo> = utxos.filter { it.blockHeight in 1..SwapInConfirmations }
    val unconfirmedUtxos: List<Utxo> = utxos.filter { it.blockHeight == 0L }

    val deeplyConfirmedBalance = deeplyConfirmedUtxos.map { it.amount }.sum()
    val weaklyConfirmedBalance = weaklyConfirmedUtxos.map { it.amount }.sum()
    val unconfirmedBalance = unconfirmedUtxos.map { it.amount }.sum()
    val totalBalance = deeplyConfirmedBalance + weaklyConfirmedBalance + unconfirmedBalance

    fun minus(reserved: Set<Utxo>): WalletState {
        val reservedIds = reserved.map {
            UnspentItemId(it.previousTx.txid, it.outputIndex)
        }.toSet()
        return copy(addresses = addresses.mapValues {
            it.value.filter { item ->
                !reservedIds.contains(UnspentItemId(item.txid, item.outputIndex))
            }
        })
    }

    data class Utxo(val previousTx: Transaction, val outputIndex: Int, val blockHeight: Long) {
        val outPoint = OutPoint(previousTx, outputIndex.toLong())
        val amount = previousTx.txOut[outputIndex].amount
    }

    data class UnspentItemId(val txid: ByteVector32, val outputIndex: Int)

    companion object {
        val empty: WalletState = WalletState(emptyMap(), emptyMap())

        /** We wait for enough confirmations before using our wallet inputs in funding transactions. */
        const val SwapInConfirmations = 6
    }
}

private sealed interface WalletCommand {
    companion object {
        object ElectrumConnected : WalletCommand
        data class ElectrumNotification(val msg: ElectrumResponse) : WalletCommand
        data class AddAddress(val bitcoinAddress: String) : WalletCommand
    }
}

/**
 * A very simple wallet that only watches one address and publishes its utxos.
 */
class ElectrumMiniWallet(
    val chainHash: ByteVector32,
    private val client: IElectrumClient,
    private val scope: CoroutineScope,
    loggerFactory: LoggerFactory,
    private val name: String = ""
) : CoroutineScope by scope {

    private val logger = loggerFactory.newLogger(this::class)
    fun Logger.mdcinfo(msgCreator: () -> String) {
        log(
            level = Logger.Level.INFO,
            meta = mapOf(
                "wallet" to name,
                "deeplyConfirmed" to walletStateFlow.value.deeplyConfirmedBalance,
                "weaklyConfirmed" to walletStateFlow.value.weaklyConfirmedBalance,
                "unconfirmed" to walletStateFlow.value.unconfirmedBalance
            ),
            msgCreator = msgCreator
        )
    }

    // state flow with the current balance
    private val _walletStateFlow = MutableStateFlow(WalletState(emptyMap(), emptyMap()))
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

    private val job: Job

    init {
        suspend fun WalletState.processSubscriptionResponse(msg: ScriptHashSubscriptionResponse): WalletState {
            val bitcoinAddress = scriptHashes[msg.scriptHash]
            return when {
                bitcoinAddress == null || msg.status.isEmpty() -> this
                else -> {
                    val unspents = client.getScriptHashUnspents(msg.scriptHash)
                    val newUtxos = unspents.minus((_walletStateFlow.value.addresses[bitcoinAddress] ?: emptyList()).toSet())
                    // request new parent txs
                    val parentTxs = newUtxos.map { utxo ->
                        val tx = client.getTx(utxo.txid)
                        logger.mdcinfo { "received parent transaction with txid=${tx.txid}" }
                        tx
                    }
                    val nextWalletState = this.copy(addresses = this.addresses + (bitcoinAddress to unspents), parentTxs = this.parentTxs + parentTxs.associateBy { it.txid })
                    logger.mdcinfo { "${unspents.size} utxo(s) for address=$bitcoinAddress balance=${nextWalletState.totalBalance}" }
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
        suspend fun subscribe(bitcoinAddress: String): Pair<ByteVector32, String> {
            val pubkeyScript = ByteVector(Script.write(Bitcoin.addressToPublicKeyScript(chainHash, bitcoinAddress)))
            val scriptHash = ElectrumClient.computeScriptHash(pubkeyScript)
            kotlin.runCatching { client.startScriptHashSubscription(scriptHash) }
                .map { response ->
                    logger.info { "subscribed to address=$bitcoinAddress pubkeyScript=$pubkeyScript scriptHash=$scriptHash" }
                    _walletStateFlow.value = _walletStateFlow.value.processSubscriptionResponse(response)
                }
            return scriptHash to bitcoinAddress
        }

        job = launch {
            launch {
                // listen to connection events
                client.connectionState.filterIsInstance<Connection.ESTABLISHED>().collect { mailbox.send(WalletCommand.Companion.ElectrumConnected) }
            }
            launch {
                // listen to subscriptions events
                client.notifications.collect { mailbox.send(WalletCommand.Companion.ElectrumNotification(it)) }
            }
            launch {
                mailbox.consumeAsFlow().collect {
                    when (it) {
                        is WalletCommand.Companion.ElectrumConnected -> {
                            logger.mdcinfo { "electrum connected" }
                            scriptHashes.values.forEach { scriptHash -> subscribe(scriptHash) }
                        }

                        is WalletCommand.Companion.ElectrumNotification -> {
                            if (it.msg is ScriptHashSubscriptionResponse) {
                                _walletStateFlow.value = _walletStateFlow.value.processSubscriptionResponse(it.msg)
                            }
                        }

                        is WalletCommand.Companion.AddAddress -> {
                            logger.mdcinfo { "adding new address=${it.bitcoinAddress}" }
                            val (scriptHash, address) = subscribe(it.bitcoinAddress)
                            scriptHashes = scriptHashes + (scriptHash to address)
                        }
                    }
                }
            }
        }
    }

    fun stop() = job.cancel()
}
