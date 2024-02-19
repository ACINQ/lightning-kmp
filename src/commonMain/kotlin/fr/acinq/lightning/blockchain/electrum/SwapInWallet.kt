package fr.acinq.lightning.blockchain.electrum

import fr.acinq.bitcoin.Bitcoin
import fr.acinq.lightning.crypto.KeyManager
import fr.acinq.lightning.logging.LoggerFactory
import fr.acinq.lightning.logging.info
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class SwapInWallet(
    chain: Bitcoin.Chain,
    swapInKeys: KeyManager.SwapInOnChainKeys,
    electrum: IElectrumClient,
    scope: CoroutineScope,
    loggerFactory: LoggerFactory
) {
    private val logger = loggerFactory.newLogger(this::class)

    val wallet = ElectrumMiniWallet(chain.chainHash, electrum, scope, logger)

    val legacySwapInAddress: String = swapInKeys.legacySwapInProtocol.address(chain)
        .also { wallet.addAddress(it) }
    val swapInAddressFlow = MutableStateFlow<Pair<String, Int>?>(null)
        .also { wallet.addAddressGenerator(generator = { index -> swapInKeys.getSwapInProtocol(index).address(chain) }) }

    init {
        scope.launch {
            // address rotation
            wallet.walletStateFlow
                .map { it.lastDerivedAddress }
                .filterNotNull()
                .distinctUntilChanged()
                .collect { (address, derived) ->
                    logger.info { "setting current swap-in address=$address index=${derived.index}" }
                    swapInAddressFlow.emit(address to derived.index)
                }
        }
    }

}