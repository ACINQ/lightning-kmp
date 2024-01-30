package fr.acinq.lightning.blockchain.electrum

import fr.acinq.bitcoin.MnemonicCode
import fr.acinq.lightning.NodeParams
import fr.acinq.lightning.crypto.LocalKeyManager
import fr.acinq.lightning.tests.TestConstants
import fr.acinq.lightning.tests.utils.LightningTestSuite
import fr.acinq.lightning.tests.utils.runSuspendTest
import fr.acinq.lightning.utils.toByteVector
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import org.kodein.log.LoggerFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

class SwapInWalletTestsCommon : LightningTestSuite() {

    @Test
    fun `swap-in wallet test`() = runSuspendTest(timeout = 15.seconds) {
        val mnemonics = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about".split(" ")
        val keyManager = LocalKeyManager(MnemonicCode.toSeed(mnemonics, "").toByteVector(), NodeParams.Chain.Testnet, TestConstants.aliceSwapInServerXpub)
        val client = connectToTestnetServer()
        val wallet = SwapInWallet(NodeParams.Chain.Testnet, keyManager.swapInOnChainWallet, client, this, LoggerFactory.default)

        // addresses 0 to 3 have funds on them, the current address is the 4th
        assertEquals(4, wallet.swapInAddressFlow.filterNotNull().first().second)
    }
}