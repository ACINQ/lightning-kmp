package fr.acinq.lightning.blockchain.electrum

import co.touchlab.kermit.Logger
import fr.acinq.bitcoin.*
import fr.acinq.lightning.SwapInParams
import fr.acinq.lightning.blockchain.electrum.WalletState.Companion.indexOrNull
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

        data class AddressState(val meta: AddressMeta, val utxos: List<Utxo>)

        sealed class AddressMeta {
            data object Single : AddressMeta()
            data class Derived(val index: Int) : AddressMeta()
        }

        val AddressMeta.indexOrNull: Int?
            get() = when (this) {
                is AddressMeta.Single -> null
                is AddressMeta.Derived -> this.index
            }
    }
}

val List<WalletState.Utxo>.balance get() = this.map { it.amount }.sum()

private sealed interface WalletCommand {
    companion object {
        data object ElectrumConnected : WalletCommand
        data class ElectrumNotification(val msg: ElectrumResponse) : WalletCommand
        data class AddAddress(val bitcoinAddress: String, val meta: WalletState.Companion.AddressMeta) : WalletCommand
        data class AddAddressGenerator(val generator: AddressGenerator) : WalletCommand
        data class GenerateAddress(val index: Int) : WalletCommand

        class AddressGenerator(val generateAddress: (Int) -> String, val window: Int)
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
    private var addressGenerator: Pair<WalletCommand.Companion.AddressGenerator, Int>? = null

    // all current meta associated to each address
    private var addressMetas: Map<String, WalletState.Companion.AddressMeta> = emptyMap()

    // all currently watched script hashes and their corresponding bitcoin address
    private var scriptHashes: Map<ByteVector32, String> = emptyMap()

    // the mailbox of this "actor"
    private val mailbox: Channel<WalletCommand> = Channel(Channel.BUFFERED)

    fun addAddress(bitcoinAddress: String) {
        launch {
            mailbox.send(WalletCommand.Companion.AddAddress(bitcoinAddress, WalletState.Companion.AddressMeta.Single))
        }
    }

    fun addAddressGenerator(generator: (Int) -> String, window: Int) {
        launch {
            mailbox.send(WalletCommand.Companion.AddAddressGenerator(WalletCommand.Companion.AddressGenerator(generator, window)))
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
                    // this should never happen
                    logger.error { "received subscription response for script hash ${msg.scriptHash} that does not match any address" }
                    this
                }
                msg.status == null -> this.copy(addresses = this.addresses + (bitcoinAddress to WalletState.Companion.AddressState(addressMeta, listOf())))
                else -> {
                    val unspents = client.getScriptHashUnspents(msg.scriptHash)
                    val previouslysKnownTxs = (_walletStateFlow.value.addresses[bitcoinAddress]?.utxos ?: emptyList()).map { it.txId to it.previousTx }.toMap()
                    val utxos = unspents
                        .mapNotNull { item -> (previouslysKnownTxs[item.txid] ?: client.getTx(item.txid))?.let { item to it } } // only retrieve txs from electrum if necessary and ignore the utxo if the parent tx cannot be retrieved
                        .map { (item, previousTx) -> WalletState.Utxo(item.txid, item.outputIndex, item.blockHeight, previousTx, addressMeta) }
                    val nextAddressState = WalletState.Companion.AddressState(addressMeta, utxos)
                    val nextWalletState = this.copy(addresses = this.addresses + (bitcoinAddress to nextAddressState))
                    logger.info { "${unspents.size} utxo(s) for address=$bitcoinAddress index=${addressMeta.indexOrNull ?: "n/a"} balance=${nextWalletState.totalBalance}" }
                    unspents.forEach { logger.debug { "utxo=${it.outPoint.txid}:${it.outPoint.index} amount=${it.value} sat" } }
                    when (val generator = addressGenerator) {
                        null -> {}
                        else -> if (addressMeta.indexOrNull == generator.second - 1 && nextAddressState.utxos.isNotEmpty()) {
                            logger.info { "requesting generation of next sequence of addresses" }
                            // if the window = 10 then the first series of address is 0 to 9 and addressGenerator.second = 10
                            // then when address 9 has utxos, we request generation up until (and excluding) index 10 + 10 = 20, this will generate addresses 10 to 19
                            mailbox.send(WalletCommand.Companion.GenerateAddress(addressMeta.indexOrNull!! + 1 + generator.first.window))
                        }
                    }
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
            return Bitcoin.addressToPublicKeyScript(chainHash, bitcoinAddress)
                .map { ElectrumClient.computeScriptHash(Script.write(it).byteVector()) }
                .right
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
                                    logger.info { "adding new address=${it.bitcoinAddress} index=${it.meta.indexOrNull ?: "n/a"}" }
                                    scriptHashes = scriptHashes + (scriptHash to it.bitcoinAddress)
                                    addressMetas = addressMetas + (it.bitcoinAddress to it.meta)
                                    subscribe(scriptHash, it.bitcoinAddress)
                                }
                            }
                        }
                        is WalletCommand.Companion.AddAddressGenerator -> {
                            if (addressGenerator == null) {
                                logger.info { "adding new address generator" }
                                addressGenerator = it.generator to 0
                                mailbox.send(WalletCommand.Companion.GenerateAddress(it.generator.window))
                            }
                        }
                        is WalletCommand.Companion.GenerateAddress -> {
                            addressGenerator = addressGenerator?.let { (generator, currentIndex) ->
                                logger.info { "generating addresses with index $currentIndex to ${it.index - 1}" }
                                (currentIndex until it.index).forEach { addressIndex ->
                                    mailbox.send(WalletCommand.Companion.AddAddress(generator.generateAddress(addressIndex), WalletState.Companion.AddressMeta.Derived(addressIndex)))
                                }
                                generator to maxOf(currentIndex, it.index)
                            }
                        }

                    }
                }
            }
        }
    }

    fun stop() = job.cancel()
}
