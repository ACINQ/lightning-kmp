package fr.acinq.lightning.blockchain.mempool

import fr.acinq.bitcoin.TxId
import fr.acinq.lightning.blockchain.mempool.MempoolSpaceClient.Companion.OfficialMempoolMainnet
import fr.acinq.lightning.blockchain.mempool.MempoolSpaceClient.Companion.OfficialMempoolTestnet
import fr.acinq.lightning.tests.utils.LightningTestSuite
import fr.acinq.lightning.tests.utils.runSuspendTest
import fr.acinq.lightning.tests.utils.testLoggerFactory
import kotlin.test.*

@Ignore
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

    @Test
    fun `get tx -- testnet`() = runSuspendTest {
        val client = MempoolSpaceClient(mempoolUrl = OfficialMempoolTestnet, testLoggerFactory)
        val res = client.getTransaction(TxId("5693d68997abfacb65cc7e6019e8ed2edb3f9f2260ae8d221e8726b8fe870ae0"))
        assertNotNull(res)
        assertEquals(TxId("5693d68997abfacb65cc7e6019e8ed2edb3f9f2260ae8d221e8726b8fe870ae0"), res.txid)
    }

    @Test
    fun `get tx -- mainnet`() = runSuspendTest {
        val client = MempoolSpaceClient(mempoolUrl = OfficialMempoolMainnet, testLoggerFactory)
        val res = client.getTransaction(TxId("8a391488ed266191e3243ea7ac55d358080a3434865b14994747f6cdca38b640"))
        assertNotNull(res)
        assertEquals(TxId("8a391488ed266191e3243ea7ac55d358080a3434865b14994747f6cdca38b640"), res.txid)
    }

    @Test
    fun `get tx confirmations -- testnet`() = runSuspendTest {
        val client = MempoolSpaceClient(mempoolUrl = OfficialMempoolTestnet, testLoggerFactory)
        val res = client.getConfirmations(TxId("5693d68997abfacb65cc7e6019e8ed2edb3f9f2260ae8d221e8726b8fe870ae0"))
        assertNotNull(res)
        assertTrue(res > 0)
    }

    @Test
    fun `get tx confirmations -- mainnet`() = runSuspendTest {
        val client = MempoolSpaceClient(mempoolUrl = OfficialMempoolMainnet, testLoggerFactory)
        val res = client.getConfirmations(TxId("8a391488ed266191e3243ea7ac55d358080a3434865b14994747f6cdca38b640"))
        assertNotNull(res)
        assertTrue(res > 0)
    }

    @Test
    fun `get spending tx -- testnet`() = runSuspendTest {
        val client = MempoolSpaceClient(mempoolUrl = OfficialMempoolTestnet, testLoggerFactory)
        val res = client.getOutspend(TxId("b97167ea09da62daaa1d3198460fc4c204a553cb3e5c80ab48f5b75a870f15c5"), 0)
        assertNotNull(res)
        assertEquals(TxId("5693d68997abfacb65cc7e6019e8ed2edb3f9f2260ae8d221e8726b8fe870ae0"), res.txid)
    }

    @Test
    fun `get spending tx -- mainnet`() = runSuspendTest {
        val client = MempoolSpaceClient(mempoolUrl = OfficialMempoolMainnet, testLoggerFactory)
        val res = client.getOutspend(TxId("308c09d986000be7f05ba776b38204317bf928b70db65bf175af7f2036951649"), 3)
        assertNotNull(res)
        assertEquals(TxId("8a391488ed266191e3243ea7ac55d358080a3434865b14994747f6cdca38b640"), res.txid)
    }
}