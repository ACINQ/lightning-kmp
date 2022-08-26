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

data class WalletState(val addresses: Map<String, List<UnspentItem>>) {
    val utxos: List<UnspentItem> = addresses.flatMap { it.value }
    val balance: Satoshi = utxos.sumOf { it.value }.sat
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
    private val client: ElectrumClient,
    private val scope: CoroutineScope
) : CoroutineScope by scope {

    // state flow with the current balance
    private val _walletStateFlow = MutableStateFlow(WalletState(emptyMap()))
    val walletStateFlow get() = _walletStateFlow.asStateFlow()

    // all currently watched bitcoin addresses and their corresponding script hash
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
                                scriptHashes[msg.scriptHash]?.let { bitcoinAddress ->
                                    println("state1=${_walletStateFlow.value}")
                                    val walletState = WalletState(_walletStateFlow.value.addresses + (bitcoinAddress to msg.unspents))
                                    println("state2=${walletState}")
                                    logger.info { "${msg.unspents.size} utxo(s) for address=$bitcoinAddress, balance=${walletState.balance}" }
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
                }
            }
        }
    }

    private fun subscribe(bitcoinAddress: String): Pair<ByteVector32, String> {
        val pubkeyScript = ByteVector(Script.write(Bitcoin.addressToPublicKeyScript(chainHash, bitcoinAddress)))
        val scriptHash = ElectrumClient.computeScriptHash(pubkeyScript)
        logger.info { "subscribing to address=$bitcoinAddress scriptHash=$scriptHash" }
        client.sendElectrumRequest(ScriptHashSubscription(scriptHash))
        return scriptHash to bitcoinAddress
    }

    @ThreadLocal
    companion object {
        val logger by lightningLogger<ElectrumMiniWallet>()
    }
}
