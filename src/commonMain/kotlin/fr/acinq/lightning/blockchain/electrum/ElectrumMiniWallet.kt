package fr.acinq.lightning.blockchain.electrum

import fr.acinq.bitcoin.*
import fr.acinq.lightning.crypto.KeyManager
import fr.acinq.lightning.utils.Connection
import fr.acinq.lightning.utils.sat
import fr.acinq.lightning.utils.sum
import fr.acinq.lightning.utils.toByteVector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.launch
import org.kodein.log.LoggerFactory
import org.kodein.log.newLogger

data class WalletState(val addresses: Map<String, List<UnspentItem>>, val parentTxs: Map<ByteVector32, Transaction>) {
    val utxos: List<UnspentItem> = addresses.flatMap { it.value }
    val balance: Satoshi = utxos.sumOf { it.value }.sat

    data class Utxo(val previousTx: Transaction, val outputIndex: Int, val blockHeight: Long) {
        val outPoint = OutPoint(previousTx, outputIndex.toLong())
        val amount = previousTx.txOut[outputIndex].amount
    }

    val spendableUtxos: List<Utxo> = utxos
        .filter { parentTxs.containsKey(it.txid) }
        .map { Utxo(parentTxs[it.txid]!!, it.outputIndex, it.blockHeight) }
    val spendableBalance = spendableUtxos.map { it.amount }.sum()

    companion object {
        val empty: WalletState = WalletState(emptyMap(), emptyMap())

        /** Sign the inputs owned by this wallet (only works for P2WPKH scripts) */
        fun sign(privKeyResolver: KeyResolver, tx: Transaction, parentTxs: Map<ByteVector32, Transaction>): Transaction =
            tx.txIn
                .foldIndexed(tx) { index, wipTx, txIn ->
                    val txOut = parentTxs[txIn.outPoint.txid]?.txOut?.getOrNull(txIn.outPoint.index.toInt())
                    signInput(privKeyResolver, wipTx, index, txOut).first
                }

        fun signInput(privKeyResolver: KeyResolver, tx: Transaction, index: Int, parentTxOut: TxOut?): Pair<Transaction, ScriptWitness?> {
            val witness = parentTxOut
                ?.let { privKeyResolver(it.publicKeyScript) }
                ?.let { privateKey ->
                    // mind this: the pubkey script used for signing is not the prevout pubscript (which is just a push
                    // of the pubkey hash), but the actual script that is evaluated by the script engine, in this case a PAY2PKH script
                    val publicKey = privateKey.publicKey()
                    val pubKeyScript = Script.pay2pkh(publicKey)
                    val sig = Transaction.signInput(
                        tx,
                        index,
                        pubKeyScript,
                        SigHash.SIGHASH_ALL,
                        parentTxOut.amount,
                        SigVersion.SIGVERSION_WITNESS_V0,
                        privateKey
                    )
                    Script.witnessPay2wpkh(publicKey, sig.byteVector())
                }
            return when (witness) {
                is ScriptWitness -> Pair(tx.updateWitness(index, witness), witness)
                else -> Pair(tx, null)
            }
        }

        /**
         * Guesses the private key corresponding to this script, assuming this is a p2wpkh owned by us.
         */
        fun script2PrivateKey(keyManager: KeyManager, publicKeyScript: ByteVector): PrivateKey? {
            val priv = keyManager.bip84PrivateKey(account = 1, addressIndex = 0)
            val script = Script.write(Script.pay2wpkh(priv.publicKey())).toByteVector()
            return if (script == publicKeyScript) priv else null
        }

        fun keyManagerResolver(keyManager: KeyManager): KeyResolver = { b: ByteVector -> script2PrivateKey(keyManager, b) }

        fun singleKeyResolver(privateKey: PrivateKey): KeyResolver = { _: ByteVector -> privateKey }
    }
}

typealias KeyResolver = (ByteVector) -> PrivateKey?

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
    private val client: ElectrumClient,
    private val scope: CoroutineScope,
    loggerFactory: LoggerFactory
) : CoroutineScope by scope {

    private val logger = loggerFactory.newLogger(this::class)

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

    init {
        launch {
            // listen to connection events
            client.connectionState
                .filterIsInstance<Connection.ESTABLISHED>()
                .collect { mailbox.send(WalletCommand.Companion.ElectrumConnected) }
        }
        launch {
            // listen to subscriptions events
            client.notifications
                .filterIsInstance<ElectrumResponse>()
                .collect { mailbox.send(WalletCommand.Companion.ElectrumNotification(it)) }
        }

        launch {
            mailbox.consumeAsFlow().collect {
                when (it) {
                    is WalletCommand.Companion.ElectrumConnected -> {
                        logger.info { "electrum connected" }
                        scriptHashes.values.forEach { subscribe(it) }
                    }

                    is WalletCommand.Companion.ElectrumNotification -> {
                        // NB: we ignore responses for unknown script_hashes (electrum client doesn't maintain a list of subscribers so we receive all subscriptions)
                        when (val msg = it.msg) {
                            is ScriptHashSubscriptionResponse -> {
                                scriptHashes[msg.scriptHash]?.let { bitcoinAddress ->
                                    if (msg.status.isNotEmpty()) {
                                        logger.info { "non-empty status for address=$bitcoinAddress, requesting utxos" }
                                        client.sendElectrumRequest(ScriptHashListUnspent(msg.scriptHash))
                                    }
                                }
                            }

                            is ScriptHashListUnspentResponse -> {
                                scriptHashes[msg.scriptHash]?.let { address ->
                                    val newUtxos = msg.unspents.minus((_walletStateFlow.value.addresses[address] ?: emptyList()).toSet())
                                    // request new parent txs
                                    newUtxos.forEach { utxo -> client.sendElectrumRequest(GetTransaction(utxo.txid)) }
                                    val walletState = _walletStateFlow.value.copy(addresses = _walletStateFlow.value.addresses + (address to msg.unspents))
                                    logger.info { "${msg.unspents.size} utxo(s) for address=$address balance=${walletState.balance}" }
                                    msg.unspents.forEach { logger.debug { "utxo=${it.outPoint.txid}:${it.outPoint.index} amount=${it.value} sat" } }
                                    // publish the updated balance
                                    _walletStateFlow.value = walletState
                                }
                            }

                            is GetTransactionResponse -> {
                                val walletState = _walletStateFlow.value.copy(parentTxs = _walletStateFlow.value.parentTxs + (msg.tx.txid to msg.tx))
                                logger.info { "received parent transaction with txid=${msg.tx.txid}" }
                                _walletStateFlow.value = walletState
                            }

                            else -> {} // ignore other electrum msgs
                        }
                    }

                    is WalletCommand.Companion.AddAddress -> {
                        logger.info { "adding new address=${it.bitcoinAddress}" }
                        scriptHashes = scriptHashes + subscribe(it.bitcoinAddress)
                    }
                }
            }
        }
    }

    private fun subscribe(bitcoinAddress: String): Pair<ByteVector32, String> {
        val pubkeyScript = ByteVector(Script.write(Bitcoin.addressToPublicKeyScript(chainHash, bitcoinAddress)))
        val scriptHash = ElectrumClient.computeScriptHash(pubkeyScript)
        logger.info { "subscribing to address=$bitcoinAddress pubkeyScript=$pubkeyScript scriptHash=$scriptHash" }
        client.sendElectrumRequest(ScriptHashSubscription(scriptHash))
        return scriptHash to bitcoinAddress
    }
}
