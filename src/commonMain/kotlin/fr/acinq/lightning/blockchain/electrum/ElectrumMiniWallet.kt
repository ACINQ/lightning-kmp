package fr.acinq.lightning.blockchain.electrum

import co.touchlab.kermit.Logger
import fr.acinq.bitcoin.*
import fr.acinq.lightning.SwapInParams
import fr.acinq.lightning.blockchain.fee.FeeratePerKw
import fr.acinq.lightning.crypto.KeyManager
import fr.acinq.lightning.logging.debug
import fr.acinq.lightning.logging.info
import fr.acinq.lightning.utils.sat
import fr.acinq.lightning.utils.sum
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.launch

data class WalletState(val addresses: Map<String, AddressState>) {
    val utxos: List<Utxo> = addresses.flatMap { it.value.utxos }
    val totalBalance = utxos.map { it.amount }.sum()
    val lastDerivedAddress: Pair<String, AddressMeta.Derived>? = addresses
        .mapNotNull { entry -> (entry.value.meta as? AddressMeta.Derived)?.let { entry.key to it } }
        .maxByOrNull { it.second.index }

    fun withoutReservedUtxos(reserved: Set<OutPoint>): WalletState {
        return copy(addresses = addresses.mapValues {
            it.value.copy(utxos = it.value.utxos.filter { item -> !reserved.contains(item.outPoint) })
        })
    }

    fun withConfirmations(currentBlockHeight: Int, swapInParams: SwapInParams): WalletWithConfirmations = WalletWithConfirmations(
        swapInParams = swapInParams, currentBlockHeight = currentBlockHeight, all = utxos,
    )

    data class Utxo(val txId: TxId, val outputIndex: Int, val blockHeight: Long, val previousTx: Transaction, val addressMeta: AddressMeta) {
        val outPoint = OutPoint(previousTx, outputIndex.toLong())
        val txOut = previousTx.txOut[outputIndex]
        val amount = txOut.amount
    }

    data class AddressState(val meta: AddressMeta, val alreadyUsed: Boolean, val utxos: List<Utxo>)

    sealed class AddressMeta {
        data object Single : AddressMeta()
        data class Derived(val index: Int) : AddressMeta()

        val indexOrNull: Int?
            get() = when (this) {
                is Single -> null
                is Derived -> this.index
            }
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

        /** Builds a transaction spending all expired utxos and computes the mining fee. The transaction is fully signed but not published. */
        fun spendExpiredSwapIn(swapInKeys: KeyManager.SwapInOnChainKeys, scriptPubKey: ByteVector, feerate: FeeratePerKw): Pair<Transaction, Satoshi>? {
            val utxos = readyForRefund.map {
                KeyManager.SwapInOnChainKeys.SwapInUtxo(
                    txOut = it.txOut,
                    outPoint = it.outPoint,
                    addressIndex = it.addressMeta.indexOrNull
                )
            }
            val tx = swapInKeys.createRecoveryTransaction(utxos, scriptPubKey, feerate)
            return tx?.let {
                val fee = utxos.map { it.txOut.amount }.sum() - tx.txOut.map { it.amount }.sum()
                tx to fee
            }
        }
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
        data class AddAddressGenerator(val generator: AddressGenerator) : WalletCommand

        class AddressGenerator(val generateAddress: (Int) -> String)
    }
}

/**
 * A very simple wallet that only watches one address and publishes its utxos.
 */
class ElectrumMiniWallet(
    val chainHash: BlockHash,
    private val client: IElectrumClient,
    private val scope: CoroutineScope,
    private val logger: Logger
) : CoroutineScope by scope {

    // state flow with the current balance
    private val _walletStateFlow = MutableStateFlow(WalletState(emptyMap()))
    val walletStateFlow get() = _walletStateFlow.asStateFlow()

    // generator, if used
    private var addressGenerator: WalletCommand.Companion.AddressGenerator? = null

    // all current meta associated to each address
    private var addressMetas: Map<String, WalletState.AddressMeta> = emptyMap()

    // all currently watched script hashes and their corresponding bitcoin address
    private var scriptHashes: Map<ByteVector32, String> = emptyMap()

    // the mailbox of this "actor"
    private val mailbox: Channel<WalletCommand> = Channel(Channel.BUFFERED)

    fun addAddress(bitcoinAddress: String) {
        launch {
            mailbox.send(WalletCommand.Companion.AddAddress(bitcoinAddress))
        }
    }

    fun addAddressGenerator(generator: (Int) -> String) {
        launch {
            mailbox.send(WalletCommand.Companion.AddAddressGenerator(WalletCommand.Companion.AddressGenerator(generator)))
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
            val addressMeta = bitcoinAddress?.let { addressMetas[it] }
            return when {
                bitcoinAddress == null || addressMeta == null -> {
                    // this will happen because multiple wallets may be sharing the same Electrum connection (e.g. swap-in and final wallet)
                    logger.debug { "received subscription response for script hash ${msg.scriptHash} that does not match any address" }
                    this
                }
                msg.status == null -> {
                    logger.info { "address=$bitcoinAddress index=${addressMeta.indexOrNull ?: "n/a"} utxos=(unused)" }
                    this.copy(addresses = this.addresses + (bitcoinAddress to WalletState.AddressState(addressMeta, alreadyUsed = false, utxos = listOf())))
                }
                else -> {
                    val unspents = client.getScriptHashUnspents(msg.scriptHash)
                    val previouslysKnownTxs = (this.addresses[bitcoinAddress]?.utxos ?: emptyList()).associate { it.txId to it.previousTx }
                    val utxos = unspents
                        .mapNotNull { item -> (previouslysKnownTxs[item.txid] ?: client.getTx(item.txid))?.let { item to it } } // only retrieve txs from electrum if necessary and ignore the utxo if the parent tx cannot be retrieved
                        .map { (item, previousTx) -> WalletState.Utxo(item.txid, item.outputIndex, item.blockHeight, previousTx, addressMeta) }
                    val nextAddressState = WalletState.AddressState(addressMeta, alreadyUsed = true, utxos)
                    val nextWalletState = this.copy(addresses = this.addresses + (bitcoinAddress to nextAddressState))
                    logger.info { "address=$bitcoinAddress index=${addressMeta.indexOrNull ?: "n/a"} utxos=${unspents.size} amount=${unspents.sumOf { it.value }.sat}" }
                    unspents.forEach { logger.debug { "utxo=${it.outPoint.txid}:${it.outPoint.index} amount=${it.value} sat" } }
                    return nextWalletState
                }
            }
        }

        /**
         * Attempts to subscribe to changes affecting a given bitcoin address.
         * Depending on the status of the electrum connection, the subscription may or may not be sent to a server.
         * It is the responsibility of the caller to resubscribe on reconnection.
         */
        suspend fun WalletState.subscribe(scriptHash: ByteVector32, bitcoinAddress: String): WalletState {
            val response = client.startScriptHashSubscription(scriptHash)
            logger.debug { "subscribed to address=$bitcoinAddress scriptHash=$scriptHash" }
            return processSubscriptionResponse(response)
        }

        fun computeScriptHash(bitcoinAddress: String): ByteVector32? {
            return Bitcoin.addressToPublicKeyScript(chainHash, bitcoinAddress)
                .map { ElectrumClient.computeScriptHash(Script.write(it).byteVector()) }
                .right
        }

        suspend fun WalletState.addAddress(bitcoinAddress: String, meta: WalletState.AddressMeta): WalletState {
            return computeScriptHash(bitcoinAddress)?.let { scriptHash ->
                if (!scriptHashes.containsKey(scriptHash)) {
                    logger.debug { "adding new address=${bitcoinAddress} index=${meta.indexOrNull ?: "n/a"}" }
                    scriptHashes = scriptHashes + (scriptHash to bitcoinAddress)
                    addressMetas = addressMetas + (bitcoinAddress to meta)
                    subscribe(scriptHash, bitcoinAddress)
                } else this
            } ?: this
        }

        suspend fun WalletState.addAddress(generator: WalletCommand.Companion.AddressGenerator, addressIndex: Int): WalletState {
            return this.addAddress(generator.generateAddress(addressIndex), WalletState.AddressMeta.Derived(addressIndex))
        }

        suspend fun WalletState.maybeGenerateNext(generator: WalletCommand.Companion.AddressGenerator): WalletState {
            val lastDerivedAddressState = this.lastDerivedAddress?.let { this.addresses[it.first] }
            return when {
                lastDerivedAddressState == null -> this.addAddress(generator, 0).maybeGenerateNext(generator) // there is no existing derived address: initialization
                lastDerivedAddressState.alreadyUsed -> this.addAddress(generator, lastDerivedAddressState.meta.indexOrNull!! + 1).maybeGenerateNext(generator) // most recent derived address is used, need to generate a new one
                else -> this // nothing to do
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
                            logger.info { "electrum connected" }
                            val walletState1 = scriptHashes
                                .toList()
                                .fold(_walletStateFlow.value) { walletState, (scriptHash, address) ->
                                    walletState.subscribe(scriptHash, address)
                                }
                            val walletState2 = addressGenerator?.let { gen -> walletState1.maybeGenerateNext(gen) } ?: walletState1
                            _walletStateFlow.value = walletState2

                        }

                        is WalletCommand.Companion.ElectrumNotification -> {
                            if (it.msg is ScriptHashSubscriptionResponse) {
                                val walletState1 = _walletStateFlow.value.processSubscriptionResponse(it.msg)
                                val walletState2 = addressGenerator?.let { gen -> walletState1.maybeGenerateNext(gen) } ?: walletState1
                                _walletStateFlow.value = walletState2
                            }
                        }

                        is WalletCommand.Companion.AddAddress -> {
                            _walletStateFlow.value = _walletStateFlow.value.addAddress(it.bitcoinAddress, WalletState.AddressMeta.Single)
                        }
                        is WalletCommand.Companion.AddAddressGenerator -> {
                            if (addressGenerator == null) {
                                logger.info { "adding new address generator" }
                                addressGenerator = it.generator
                                _walletStateFlow.value = _walletStateFlow.value.maybeGenerateNext(it.generator)
                            }
                        }
                    }
                }
            }
        }
    }

    fun stop() = job.cancel()
}
