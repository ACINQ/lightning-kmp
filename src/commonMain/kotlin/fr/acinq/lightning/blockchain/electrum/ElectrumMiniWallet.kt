package fr.acinq.lightning.blockchain.electrum

import fr.acinq.bitcoin.*
import fr.acinq.lightning.utils.Connection
import fr.acinq.lightning.utils.lightningLogger
import fr.acinq.lightning.utils.sat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlin.native.concurrent.ThreadLocal

data class WalletState(val pubKeyScripts: Map<ByteVector, Pair<List<UnspentItem>, PrivateKey?>>) {
    val utxos: List<UnspentItem> = pubKeyScripts.flatMap { it.value.first }
    val balance: Satoshi = utxos.sumOf { it.value }.sat

    fun spendable(): List<UnspentItem> {
        return pubKeyScripts
            .filter { it.value.second != null }
            .flatMap { it.value.first }
    }

    /** Sign the inputs owned by this wallet (only works for P2WPKH scripts) */
    fun sign(tx: Transaction): Transaction {
        return tx.txIn.foldIndexed(tx) { index, wipTx, txIn ->
            when (val utxo = spendable().find { it.outPoint == txIn.outPoint }) {
                is UnspentItem -> {
                    // we know this utxo and have the private key
                    val privateKey = pubKeyScripts.values.find { it.first.contains(utxo) }!!.second!! // TODO improve that
                    // mind this: the pubkey script used for signing is not the prevout pubscript (which is just a push
                    // of the pubkey hash), but the actual script that is evaluated by the script engine, in this case a PAY2PKH script
                    val publicKey = privateKey.publicKey()
                    val pubKeyScript = Script.pay2pkh(publicKey)
                    val sig = Transaction.signInput(
                        tx,
                        index,
                        pubKeyScript,
                        SigHash.SIGHASH_ALL,
                        utxo.value.sat,
                        SigVersion.SIGVERSION_WITNESS_V0,
                        privateKey
                    )
                    // update the signature for the corresponding input
                    val witness = ScriptWitness(listOf(sig.byteVector(), publicKey.value))
                    wipTx.updateWitness(index, witness)
                }
                else -> wipTx // we don't know how to sign this input
            }
        }
    }
}

private sealed interface WalletCommand {
    companion object {
        object ElectrumConnected : WalletCommand
        data class ElectrumNotification(val msg: ElectrumResponse) : WalletCommand
        data class AddAddress(val bitcoinAddress: String, val privateKey: PrivateKey?) : WalletCommand
        data class AddPubkeyScript(val pubkeyScript: ByteVector, val privateKey: PrivateKey?) : WalletCommand
    }
}


/**
 * A very simple wallet that only watches one address and publishes its utxos.
 */
class ElectrumMiniWallet(
    val chainHash: ByteVector32,
    private val client: ElectrumClient,
    private val scope: CoroutineScope
) : CoroutineScope by scope {

    // state flow with the current balance
    private val _walletStateFlow = MutableStateFlow(WalletState(emptyMap()))
    val walletStateFlow get() = _walletStateFlow.asStateFlow()

    // all currently watched script hashes and their corresponding pubkeyscript
    private var scriptHashes: Map<ByteVector32, ByteVector> = emptyMap()

    // the mailbox of this "actor"
    private val mailbox: Channel<WalletCommand> = Channel(Channel.BUFFERED)

    fun addAddress(bitcoinAddress: String, privateKey: PrivateKey? = null) {
        launch {
            mailbox.send(WalletCommand.Companion.AddAddress(bitcoinAddress, privateKey))
        }
    }

    fun addPubkeyScript(pubkeyScript: ByteVector, privateKey: PrivateKey? = null) {
        launch {
            mailbox.send(WalletCommand.Companion.AddPubkeyScript(pubkeyScript, privateKey))
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
                                scriptHashes[msg.scriptHash]?.let { pubkeyScript ->
                                    val newValue = _walletStateFlow.value.pubKeyScripts
                                        .getOrElse(pubkeyScript, defaultValue = { emptyList<UnspentItem>() to null }) // default value
                                        .let { v -> msg.unspents to v.second } // update the utxos, keep the private key
                                    val walletState = WalletState(_walletStateFlow.value.pubKeyScripts + (pubkeyScript to newValue))
                                    val bitcoinAddress = Bitcoin.addressFromPublicKeyScript(chainHash, pubkeyScript.toByteArray())
                                    logger.info { "${msg.unspents.size} utxo(s) for address=$bitcoinAddress pubkeyScript=$pubkeyScript, balance=${walletState.balance}" }
                                    msg.unspents.forEach { logger.debug { "utxo=${it.outPoint.txid}:${it.outPoint.index} amount=${it.value} sat" } }
                                    // publish the updated balance
                                    _walletStateFlow.value = walletState
                                }
                            }
                            else -> {} // ignore other electrum msgs
                        }
                    }
                    is WalletCommand.Companion.AddAddress -> {
                        logger.info { "adding new address=${it.bitcoinAddress}" }
                        scriptHashes = scriptHashes + subscribe(it.bitcoinAddress)
                    }
                    is WalletCommand.Companion.AddPubkeyScript -> {
                        logger.info { "adding new pubkeyScript=${it.pubkeyScript}" }
                        scriptHashes = scriptHashes + subscribe(it.pubkeyScript)
                    }
                }
            }
        }
    }

    private fun subscribe(bitcoinAddress: String): Pair<ByteVector32, ByteVector> {
        val pubkeyScript = ByteVector(Script.write(Bitcoin.addressToPublicKeyScript(chainHash, bitcoinAddress)))
        return subscribe(pubkeyScript)
    }

    private fun subscribe(pubkeyScript: ByteVector): Pair<ByteVector32, ByteVector> {
        val bitcoinAddress = Bitcoin.addressFromPublicKeyScript(chainHash, pubkeyScript.toByteArray())
        val scriptHash = ElectrumClient.computeScriptHash(pubkeyScript)
        logger.info { "subscribing to address=$bitcoinAddress pubkeyScript=$pubkeyScript scriptHash=$scriptHash" }
        client.sendElectrumRequest(ScriptHashSubscription(scriptHash))
        return scriptHash to pubkeyScript
    }

    @ThreadLocal
    companion object {
        val logger by lightningLogger<ElectrumMiniWallet>()
    }
}
