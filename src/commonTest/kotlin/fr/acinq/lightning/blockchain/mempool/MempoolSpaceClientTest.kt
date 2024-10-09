package fr.acinq.lightning.blockchain.mempool

import fr.acinq.bitcoin.TxId
import fr.acinq.lightning.blockchain.mempool.MempoolSpaceClient.Companion.OfficialMempoolMainnet
import fr.acinq.lightning.blockchain.mempool.MempoolSpaceClient.Companion.OfficialMempoolTestnet4
import fr.acinq.lightning.tests.utils.LightningTestSuite
import fr.acinq.lightning.tests.utils.runSuspendTest
import fr.acinq.lightning.tests.utils.testLoggerFactory
import kotlin.test.*

class MempoolSpaceClientTest : LightningTestSuite() {

    @Test
    fun `retrieve feerates -- testnet4`() = runSuspendTest {
        val client = MempoolSpaceClient(mempoolUrl = OfficialMempoolTestnet4, testLoggerFactory)
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
    fun `get tx -- testnet4`() = runSuspendTest {
        val client = MempoolSpaceClient(mempoolUrl = OfficialMempoolTestnet4, testLoggerFactory)
        val res = client.getTransaction(TxId("002f3246fa012ded792050f4a834e2eccdd772e1c7dc3a6a88cd6acdcbb9a2b2"))
        assertNotNull(res)
        assertEquals(TxId("002f3246fa012ded792050f4a834e2eccdd772e1c7dc3a6a88cd6acdcbb9a2b2"), res.txid)
    }

    @Test
    fun `get tx -- mainnet`() = runSuspendTest {
        val client = MempoolSpaceClient(mempoolUrl = OfficialMempoolMainnet, testLoggerFactory)
        val res = client.getTransaction(TxId("8a391488ed266191e3243ea7ac55d358080a3434865b14994747f6cdca38b640"))
        assertNotNull(res)
        assertEquals(TxId("8a391488ed266191e3243ea7ac55d358080a3434865b14994747f6cdca38b640"), res.txid)
    }

    @Test
    fun `get tx confirmations -- testnet4`() = runSuspendTest {
        val client = MempoolSpaceClient(mempoolUrl = OfficialMempoolTestnet4, testLoggerFactory)
        val res = client.getConfirmations(TxId("002f3246fa012ded792050f4a834e2eccdd772e1c7dc3a6a88cd6acdcbb9a2b2"))
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
    fun `get spending tx -- testnet4`() = runSuspendTest {
        val client = MempoolSpaceClient(mempoolUrl = OfficialMempoolTestnet4, testLoggerFactory)
        val res = client.getOutspend(TxId("002f3246fa012ded792050f4a834e2eccdd772e1c7dc3a6a88cd6acdcbb9a2b2"), 1)
        assertNotNull(res)
        assertEquals(TxId("8bce727fe08fbb79f02682f71d0b1f33a79039cd7a70623a51945bf2dc86d77c"), res.txid)
    }

    @Test
    fun `get spending tx -- mainnet4`() = runSuspendTest {
        val client = MempoolSpaceClient(mempoolUrl = OfficialMempoolMainnet, testLoggerFactory)
        val res = client.getOutspend(TxId("308c09d986000be7f05ba776b38204317bf928b70db65bf175af7f2036951649"), 3)
        assertNotNull(res)
        assertEquals(TxId("8a391488ed266191e3243ea7ac55d358080a3434865b14994747f6cdca38b640"), res.txid)
    }
}