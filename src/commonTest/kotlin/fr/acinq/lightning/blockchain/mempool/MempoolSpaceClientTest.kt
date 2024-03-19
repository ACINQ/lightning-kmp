package fr.acinq.lightning.blockchain.mempool

import fr.acinq.lightning.blockchain.mempool.MempoolSpaceClient.Companion.OfficialMempoolMainnet
import fr.acinq.lightning.blockchain.mempool.MempoolSpaceClient.Companion.OfficialMempoolTestnet
import fr.acinq.lightning.tests.utils.LightningTestSuite
import fr.acinq.lightning.tests.utils.runSuspendTest
import fr.acinq.lightning.tests.utils.testLoggerFactory
import kotlin.test.Test
import kotlin.test.assertNotNull

class MempoolSpaceClientTest : LightningTestSuite() {

    @Test
    fun `retrieve feerates -- testnet`() = runSuspendTest {
        val client = MempoolSpaceClient(mempoolUrl = OfficialMempoolTestnet, testLoggerFactory)
        val feerates = client.getFeerates()
        assertNotNull(feerates)
    }

    @Test
    fun `retrieve feerates -- mainnet`() = runSuspendTest {
        val client = MempoolSpaceClient(mempoolUrl = OfficialMempoolMainnet, testLoggerFactory)
        val feerates = client.getFeerates()
        assertNotNull(feerates)
    }
}