package fr.acinq.lightning.blockchain.mempool

import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.Transaction
import fr.acinq.bitcoin.TxId
import fr.acinq.lightning.Lightning.randomBytes32
import fr.acinq.lightning.blockchain.*
import fr.acinq.lightning.blockchain.mempool.MempoolSpaceClient.Companion.OfficialMempoolTestnet4
import fr.acinq.lightning.tests.utils.LightningTestSuite
import fr.acinq.lightning.tests.utils.runSuspendTest
import fr.acinq.lightning.tests.utils.testLoggerFactory
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.seconds

class MempoolSpaceWatcherTest : LightningTestSuite() {

    @Test
    fun `watch-spent on a transaction`() = runSuspendTest {
        val client = MempoolSpaceClient(mempoolUrl = OfficialMempoolTestnet4, testLoggerFactory)
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
        // Right after checking whether the watched utxo is spent, a 2nd call is made by the watcher
        // to find out whether the spending tx is confirmed, and the watch can be cleaned up. We give
        // some time for that call to complete, in order to prevent a coroutine cancellation stack trace.
        delay(2.seconds)
    }

    @Test
    fun `watch-confirmed on a transaction`() = runSuspendTest {
        val client = MempoolSpaceClient(mempoolUrl = OfficialMempoolTestnet4, testLoggerFactory)
        val watcher = MempoolSpaceWatcher(client, scope = this, testLoggerFactory)

        val notifications = watcher.openWatchNotificationsFlow()

        val watch = WatchConfirmed(
            channelId = randomBytes32(),
            txId = TxId("5693d68997abfacb65cc7e6019e8ed2edb3f9f2260ae8d221e8726b8fe870ae0"),
            publicKeyScript = ByteVector.empty,
            event = BITCOIN_FUNDING_DEPTHOK,
            minDepth = 5
        )
        watcher.watch(watch)
        val event = assertIs<WatchEventConfirmed>(notifications.first())
        assertEquals(TxId("5693d68997abfacb65cc7e6019e8ed2edb3f9f2260ae8d221e8726b8fe870ae0"), event.tx.txid)
    }
}