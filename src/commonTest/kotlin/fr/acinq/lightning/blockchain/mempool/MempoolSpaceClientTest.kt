package fr.acinq.lightning.blockchain.mempool

import fr.acinq.bitcoin.TxId
import fr.acinq.lightning.blockchain.mempool.MempoolSpaceClient.Companion.OfficialMempoolMainnet
import fr.acinq.lightning.blockchain.mempool.MempoolSpaceClient.Companion.OfficialMempoolTestnet
import fr.acinq.lightning.tests.utils.LightningTestSuite
import fr.acinq.lightning.tests.utils.runSuspendTest
import fr.acinq.lightning.tests.utils.testLoggerFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class MempoolSpaceClientTest : LightningTestSuite() {

    @Test
    fun `retrieve feerates`() = runSuspendTest {
        val client = MempoolSpaceClient(mempoolUrl = OfficialMempoolMainnet, testLoggerFactory)
        val feerates = client.getFeerates()
        assertNotNull(feerates)
    }

    @Test
    fun `get tx confirmations -- testnet`() = runSuspendTest {
        val client = MempoolSpaceClient(mempoolUrl = OfficialMempoolTestnet, testLoggerFactory)
        val res = client.getTransactionMerkleProof(TxId("5693d68997abfacb65cc7e6019e8ed2edb3f9f2260ae8d221e8726b8fe870ae0"))
        assertNotNull(res)
        assertEquals(2_582_676, res.block_height)
    }

    @Test
    fun `get tx confirmations -- mainnet`() = runSuspendTest {
        val client = MempoolSpaceClient(mempoolUrl = OfficialMempoolMainnet, testLoggerFactory)
        val res = client.getTransactionMerkleProof(TxId("8a391488ed266191e3243ea7ac55d358080a3434865b14994747f6cdca38b640"))
        assertNotNull(res)
        assertEquals(835_800, res.block_height)
    }
}