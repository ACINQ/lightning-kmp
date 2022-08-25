package fr.acinq.lightning.blockchain.electrum

import fr.acinq.bitcoin.*
import fr.acinq.lightning.utils.Connection
import fr.acinq.lightning.utils.lightningLogger
import fr.acinq.lightning.utils.sat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlin.native.concurrent.ThreadLocal

data class WalletState(val utxos: List<UnspentItem>) {
    val balance: Satoshi = utxos.map { it.value }.sum().sat
}

/**
 * A very simple wallet that only watches one address and publishes its utxos.
 */
class ElectrumMiniWallet(
    val bitcoinAddress: String,
    val chainHash: ByteVector32,
    private val client: ElectrumClient,
    private val scope: CoroutineScope
) : CoroutineScope by scope {

    // state flow with the current balance
    private val _walletStateFlow = MutableStateFlow<WalletState?>(null)
    val walletStateFlow get() = _walletStateFlow.asStateFlow()

    private val pubkeyScript = ByteVector(Script.write(Bitcoin.addressToPublicKeyScript(chainHash, bitcoinAddress)))
    private val scriptHash = ElectrumClient.computeScriptHash(pubkeyScript)

    init {
        launch {
            // listen to connection events
            client.connectionState.collect {
                when (it) {
                    is Connection.ESTABLISHING -> {}
                    is Connection.ESTABLISHED -> {
                        // electrum is connected, we register to changes to our address
                        logger.info { "electrum connected, registering to address=$bitcoinAddress scriptHash=$scriptHash" }
                        client.sendElectrumRequest(ScriptHashSubscription(scriptHash))
                    }
                    is Connection.CLOSED -> {}
                }
            }
        }
        launch {
            // listen to subscriptions events
            // NB: we ignore responses for unknown script_hashes (electrum client doesn't maintain a list of subscribers so we receive all subscriptions)
            client.notifications.collect {
                when (it) {
                    is ScriptHashSubscriptionResponse -> {
                        if (it.scriptHash == scriptHash && it.status.isNotEmpty()) {
                            logger.info { "non-empty status for address=$bitcoinAddress, requesting utxos" }
                            client.sendElectrumRequest(ScriptHashListUnspent(it.scriptHash))
                        }
                    }
                    is ScriptHashListUnspentResponse -> {
                        if (it.scriptHash == scriptHash) {
                            val walletState = WalletState(it.unspents)
                            logger.info { "${it.unspents.size} utxo(s) for address=$bitcoinAddress, balance=${walletState.balance}" }
                            it.unspents.forEach { logger.debug { "utxo=${it.outPoint.txid}:${it.outPoint.index} amount=${it.value} sat" } }
                            // publish the updated balance
                            _walletStateFlow.value = walletState
                        }
                    }
                    else -> {}
                }
            }
        }
    }

    @ThreadLocal
    companion object {
        val logger by lightningLogger<ElectrumMiniWallet>()
    }
}
