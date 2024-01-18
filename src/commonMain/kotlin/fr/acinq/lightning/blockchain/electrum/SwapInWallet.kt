package fr.acinq.lightning.blockchain.electrum

import fr.acinq.lightning.NodeParams
import fr.acinq.lightning.blockchain.electrum.WalletState.Companion.indexOrNull
import fr.acinq.lightning.crypto.KeyManager
import fr.acinq.lightning.logging.LoggerFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class SwapInWallet(
    chain: NodeParams.Chain,
    swapInKeys: KeyManager.SwapInOnChainKeys,
    electrum: IElectrumClient,
    addressGenerationWindow: Int,
    scope: CoroutineScope,
    loggerFactory: LoggerFactory
) {
    private val logger = loggerFactory.newLogger(this::class)

    val wallet = ElectrumMiniWallet(chain.chainHash, electrum, scope, logger)

    val legacySwapInAddress: String = swapInKeys.legacySwapInProtocol.address(chain)
        .also { wallet.addAddress(it) }
    val swapInAddressFlow = MutableStateFlow<Pair<String, Int>?>(null)
        .also { wallet.addAddressGenerator(generator = { index -> swapInKeys.getSwapInProtocol(index).address(chain) }, window = addressGenerationWindow) }

    init {
        scope.launch {
            // address rotation
            wallet.walletStateFlow.map { it ->
                // take the first unused address with the lowest index
                it.addresses
                    .filter { it.value.utxos.isEmpty() }
                    .mapNotNull { (key, value) -> value.meta.indexOrNull?.let { key to it } }
                    .minByOrNull { it.second }
            }
                .filterNotNull()
                .distinctUntilChanged()
                .collect { (address, index) ->
                    logger.info { "setting current swap-in address=$address index=$index" }
                    swapInAddressFlow.emit(address to index)
                }
        }
    }

}