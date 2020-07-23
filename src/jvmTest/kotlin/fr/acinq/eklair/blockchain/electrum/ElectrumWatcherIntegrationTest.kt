package fr.acinq.eklair.blockchain.electrum

import fr.acinq.eklair.blockchain.BITCOIN_FUNDING_DEPTHOK
import fr.acinq.eklair.blockchain.WatchConfirmed
import fr.acinq.eklair.blockchain.WatchEventConfirmed
import fr.acinq.eklair.blockchain.WatcherType
import fr.acinq.eklair.blockchain.bitcoind.*
import io.ktor.util.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.TestCoroutineScope
import kotlinx.coroutines.test.runBlockingTest
import org.kodein.log.LoggerFactory
import org.kodein.log.newLogger
import java.nio.file.WatchEvent
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class, KtorExperimentalAPI::class)
class ElectrumWatcherIntegrationTest {

    private val testScope = TestCoroutineScope()
    private val bitcoinService = BitcoindService
    private lateinit var client : ElectrumClient
    private lateinit var watcher: ElectrumWatcher

    @BeforeTest fun before() {
        client = ElectrumClient("localhost", 51001, false, testScope)
        watcher = ElectrumWatcher(client, testScope)
        client.start()
        watcher.start()
    }
    @AfterTest fun after() {
        client.stop()
    }

    @Test
    fun `watch for confirmed transactions`() = runBlocking {
        client.start()
        watcher.start()

        val address = bitcoinService.getNewAddress()
        val tx = bitcoinService.sendToAddress(address, 1.0)

        val listener = Channel<WatcherType>()
        watcher.watchChannel.send(WatchConfirmed(listener ,tx.txid, tx.txOut[0].publicKeyScript, 4, BITCOIN_FUNDING_DEPTHOK))
        bitcoinService.generateBlocks(6)

        withTimeout(20_000) {
            val msg = listener.receive()
            assertTrue { msg is WatchEventConfirmed }
            assertEquals(tx.txid, (msg as WatchEventConfirmed).tx.txid)
        }
    }

    /*
    test("watch for confirmed transactions") {
    val probe = TestProbe()
    val blockCount = new AtomicLong()
    val electrumClient = system.actorOf(Props(new ElectrumClientPool(blockCount, Set(electrumAddress))))
    val watcher = system.actorOf(Props(new ElectrumWatcher(blockCount, electrumClient)))

    val (address, _) = getNewAddress(bitcoincli)
    val tx = sendToAddress(bitcoincli, address, 1.0)

    val listener = TestProbe()
    probe.send(watcher, WatchConfirmed(listener.ref, tx.txid, tx.txOut(0).publicKeyScript, 4, BITCOIN_FUNDING_DEPTHOK))
    generateBlocks(bitcoincli, 5)
    val confirmed = listener.expectMsgType[WatchEventConfirmed](20 seconds)
    assert(confirmed.tx.txid === tx.txid)
    system.stop(watcher)
  }
     */
}