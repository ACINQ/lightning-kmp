package fr.acinq.lightning.blockchain.mempool

import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.Transaction
import fr.acinq.bitcoin.TxId
import fr.acinq.lightning.Lightning.randomBytes32
import fr.acinq.lightning.blockchain.*
import fr.acinq.lightning.tests.utils.LightningTestSuite
import fr.acinq.lightning.tests.utils.runSuspendTest
import fr.acinq.lightning.tests.utils.testLoggerFactory
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class MempoolSpaceWatcherTest : LightningTestSuite() {

    @Test
    fun `publish a transaction`() = runSuspendTest {
        val client = MempoolSpaceClient(mempoolHost = "mempool.space/testnet", testLoggerFactory)
        val watcher = MempoolSpaceWatcher(client, scope = this, testLoggerFactory)
        val tx =
            Transaction.read("0200000000010146c398e70cceaf9d8f734e603bc53e4c4c0605ab46cb1b5807a62c90f5aed50d0100000000feffffff023c0fc10c010000001600145033f65b590f2065fe55414213f1d25ab20b6c4f487d1700000000001600144b812d5ef41fc433654d186463d41b458821ff740247304402202438dc18801919baa64eb18f7e925ab6acdedc3751ea58ea164a26723b79fd39022060b46c1d277714c640cdc8512c36c862ffc646e7ff62438ef5cc847a5990bbf801210247b49d9e6b0089a1663668829e573c629c936eb430c043af9634aa57cf97a33cbee81f00")
        watcher.publish(tx)
    }

    @Test
    fun `watch-spent on a transaction`() = runSuspendTest {
        val client = MempoolSpaceClient(mempoolHost = "mempool.space/testnet", testLoggerFactory)
        val watcher = MempoolSpaceWatcher(client, scope = this, testLoggerFactory)

        val notifications = watcher.openWatchNotificationsFlow()

        val watch = WatchSpent(
            channelId = randomBytes32(),
            txId = TxId("b97167ea09da62daaa1d3198460fc4c204a553cb3e5c80ab48f5b75a870f15c5"),
            outputIndex = 0,
            publicKeyScript = ByteVector.empty,
            event = BITCOIN_FUNDING_SPENT
        )
        watcher.watch(watch)
        val event = assertIs<WatchEventSpent>(notifications.first())
        assertEquals(TxId("5693d68997abfacb65cc7e6019e8ed2edb3f9f2260ae8d221e8726b8fe870ae0"), event.tx.txid)
        delay(2.seconds)
    }

    @Test
    fun `watch-confirmed on a transaction`() = runSuspendTest(15.minutes) {
        val client = MempoolSpaceClient(mempoolHost = "mempool.space/testnet", testLoggerFactory)
        val watcher = MempoolSpaceWatcher(client, scope = this, testLoggerFactory)

        val notifications = watcher.openWatchNotificationsFlow()

        val watch = WatchConfirmed(
            channelId = randomBytes32(),
            txId = TxId("5693d68997abfacb65cc7e6019e8ed2edb3f9f2260ae8d221e8726b8fe870ae0"),
            publicKeyScript = ByteVector.empty,
            event = BITCOIN_FUNDING_DEPTHOK,
            minDepth = 5,

        )
        watcher.watch(watch)
        val event = assertIs<WatchEventConfirmed>(notifications.first())
        assertEquals(TxId("5693d68997abfacb65cc7e6019e8ed2edb3f9f2260ae8d221e8726b8fe870ae0"), event.tx.txid)
    }
}