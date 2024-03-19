package fr.acinq.lightning.blockchain.mempool

import fr.acinq.lightning.tests.utils.LightningTestSuite
import fr.acinq.lightning.tests.utils.runSuspendTest
import fr.acinq.lightning.tests.utils.testLoggerFactory
import kotlin.test.Test
import kotlin.test.assertNotNull

class MempoolSpaceClientTest : LightningTestSuite() {

    @Test
    fun `retrieve feerates`() = runSuspendTest {
        val client = MempoolSpaceClient(mempoolHost = "mempool.space", testLoggerFactory)
        val feerates = client.getFeerates()
        assertNotNull(feerates)
    }
}