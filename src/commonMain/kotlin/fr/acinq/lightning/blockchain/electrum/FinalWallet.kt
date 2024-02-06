package fr.acinq.lightning.blockchain.electrum

import fr.acinq.lightning.NodeParams
import fr.acinq.lightning.crypto.KeyManager
import fr.acinq.lightning.logging.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.launch

class FinalWallet(
    chain: NodeParams.Chain,
    finalWalletKeys: KeyManager.Bip84OnChainKeys,
    electrum: IElectrumClient,
    scope: CoroutineScope,
    loggerFactory: LoggerFactory
) {
    private val logger = loggerFactory.newLogger(this::class)

    val wallet = ElectrumMiniWallet(chain.chainHash, electrum, scope, logger)
    val finalAddress: String = finalWalletKeys.address(addressIndex = 0L).also { wallet.addAddress(it) }

    init {
        scope.launch {
            wallet.walletStateFlow
                .distinctUntilChangedBy { it.totalBalance }
                .collect { wallet ->
                    logger.info { "${wallet.totalBalance} available on final wallet with ${wallet.utxos.size} utxos" }
                }
        }
    }
}