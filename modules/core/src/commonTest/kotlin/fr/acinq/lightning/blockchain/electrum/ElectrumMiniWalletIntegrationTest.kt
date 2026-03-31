package fr.acinq.lightning.blockchain.electrum

import fr.acinq.bitcoin.*
import fr.acinq.bitcoin.utils.runTrying
import fr.acinq.lightning.SwapInParams
import fr.acinq.lightning.blockchain.fee.FeeratePerKw
import fr.acinq.lightning.crypto.LocalKeyManager
import fr.acinq.lightning.io.TcpSocket
import fr.acinq.lightning.io.TcpSocket.Builder.Companion.invoke
import fr.acinq.lightning.tests.TestConstants
import fr.acinq.lightning.tests.bitcoind.BitcoindService
import fr.acinq.lightning.tests.utils.LightningTestSuite
import fr.acinq.lightning.tests.utils.readEnvironmentVariable
import fr.acinq.lightning.tests.utils.runSuspendBlocking
import fr.acinq.lightning.tests.utils.runSuspendTest
import fr.acinq.lightning.utils.ServerAddress
import fr.acinq.lightning.utils.sat
import fr.acinq.lightning.utils.toByteVector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

class ElectrumMiniWalletIntegrationTest : LightningTestSuite() {

    private val logger = loggerFactory.newLogger(this::class)
    private val bitcoincli = BitcoindService
    private val mnemonics = "bullet umbrella fringe token whip negative menu drill solid keep vacuum prepare".split(" ")
    private val keyManager = LocalKeyManager(MnemonicCode.toSeed(mnemonics, "").toByteVector(), Chain.Regtest, TestConstants.aliceSwapInServerXpub)

    init {
        runSuspendBlocking {
            withTimeout(10.seconds) {
                while (runTrying { bitcoincli.getNetworkInfo() }.isFailure) {
                    delay(0.5.seconds)
                }
            }
            val address = bitcoincli.getNewAddress()
            val address0 = keyManager.swapInOnChainWallet.getSwapInProtocol(0).address(Chain.Regtest)
            bitcoincli.sendToAddress(address0, 1.0)
            val address2 = keyManager.swapInOnChainWallet.getSwapInProtocol(2).address(Chain.Regtest)
            bitcoincli.sendToAddress(address2, 1.0)
        }
    }


    @OptIn(FlowPreview::class)
    @Test
    fun `derived addresses with gaps`() = runSuspendTest(timeout = 15.seconds) {
        val chain = Chain.Regtest
        val client = ElectrumClient(this, loggerFactory).apply { connect(ServerAddress("localhost", 51001, TcpSocket.TLS.DISABLED), TcpSocket.Builder()) }
        val wallet = ElectrumMiniWallet(chain.chainHash, client, this, logger)

        wallet.addAddressGenerator(generator = { index -> keyManager.swapInOnChainWallet.getSwapInProtocol(index).address(chain) })

        // This wallet has:
        // index=0: 10000 sat + 11000 sat
        // index=1: nothing
        // index=2: 12000 sat
        // index=3: nothing <-- will stop there

        val walletState = wallet.walletStateFlow.debounce(5.seconds).first()
        assertEquals(1, walletState.firstUnusedDerivedAddress?.second?.index)
        assertEquals(3, walletState.lastDerivedAddress?.second?.index)
    }

    @OptIn(FlowPreview::class)
    @Test
    fun `derived addresses with gaps and no look-ahead`() = runSuspendTest(timeout = 15.seconds) {
        val chain = Chain.Regtest
        val client = ElectrumClient(this, loggerFactory).apply { connect(ServerAddress("localhost", 51001, TcpSocket.TLS.DISABLED), TcpSocket.Builder()) }
        val wallet = ElectrumMiniWallet(chain.chainHash, client, this, logger, lookAhead = 1u)
        wallet.addAddressGenerator(generator = { index -> keyManager.swapInOnChainWallet.getSwapInProtocol(index).address(chain) })

        // This wallet has:
        // index=0: 10000 sat + 11000 sat
        // index=1: nothing <-- will stop there
        // index=2: 12000 sat
        // index=3: nothing

        val walletState = wallet.walletStateFlow.debounce(5.seconds).first()
        assertEquals(1, walletState.firstUnusedDerivedAddress?.second?.index)
        assertEquals(1, walletState.lastDerivedAddress?.second?.index)
    }
}
