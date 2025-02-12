package fr.acinq.lightning.blockchain.mempool

import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.TxId
import fr.acinq.lightning.Lightning.randomBytes32
import fr.acinq.lightning.blockchain.WatchConfirmed
import fr.acinq.lightning.blockchain.WatchConfirmedTriggered
import fr.acinq.lightning.blockchain.WatchSpent
import fr.acinq.lightning.blockchain.WatchSpentTriggered
import fr.acinq.lightning.blockchain.mempool.MempoolSpaceClient.Companion.OfficialMempoolTestnet4
import fr.acinq.lightning.tests.utils.LightningTestSuite
import fr.acinq.lightning.tests.utils.runSuspendTest
import fr.acinq.lightning.tests.utils.testLoggerFactory
import fr.acinq.lightning.utils.sat
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
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
            txId = TxId("002f3246fa012ded792050f4a834e2eccdd772e1c7dc3a6a88cd6acdcbb9a2b2"),
            outputIndex = 1,
            publicKeyScript = ByteVector.empty,
            event = WatchSpent.ChannelSpent(100_000.sat)
        )
        watcher.watch(watch)
        val event = assertIs<WatchSpentTriggered>(notifications.first())
        assertEquals(TxId("8bce727fe08fbb79f02682f71d0b1f33a79039cd7a70623a51945bf2dc86d77c"), event.spendingTx.txid)
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
            txId = TxId("8bce727fe08fbb79f02682f71d0b1f33a79039cd7a70623a51945bf2dc86d77c"),
            publicKeyScript = ByteVector.empty,
            event = WatchConfirmed.ChannelFundingDepthOk,
            minDepth = 5
        )
        watcher.watch(watch)
        val event = assertIs<WatchConfirmedTriggered>(notifications.first())
        assertEquals(TxId("8bce727fe08fbb79f02682f71d0b1f33a79039cd7a70623a51945bf2dc86d77c"), event.tx.txid)
    }
}