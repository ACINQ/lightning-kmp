package fr.acinq.lightning.blockchain.electrum

import fr.acinq.bitcoin.*
import fr.acinq.lightning.blockchain.fee.FeeratePerKw
import fr.acinq.lightning.io.TcpSocket
import fr.acinq.lightning.tests.utils.LightningTestSuite
import fr.acinq.lightning.tests.utils.runSuspendTest
import fr.acinq.lightning.utils.*
import fr.acinq.secp256k1.Hex
import io.ktor.utils.io.charsets.*
import io.ktor.utils.io.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.Channel.Factory.UNLIMITED
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.*
import org.kodein.log.LoggerFactory
import kotlin.test.*
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class ElectrumClientTest : LightningTestSuite() {
    // this is tx #2690 of block #500000
    private val referenceTx = Transaction.read(
        "0200000001983c5b32ced1de5ae97d3ce9b7436f8bb0487d15bf81e5cae97b1e238dc395c6000000006a47304402205957c75766e391350eba2c7b752f0056cb34b353648ecd0992a8a81fc9bcfe980220629c286592842d152cdde71177cd83086619744a533f262473298cacf60193500121021b8b51f74dbf0ac1e766d162c8707b5e8d89fc59da0796f3b4505e7c0fb4cf31feffffff0276bd0101000000001976a914219de672ba773aa0bc2e15cdd9d2e69b734138fa88ac3e692001000000001976a914301706dede031e9fb4b60836e073a4761855f6b188ac09a10700"
    )
    private val scriptHash = Crypto.sha256(referenceTx.txOut.first().publicKeyScript).toByteVector32().reversed()

    private fun runTest(test: suspend CoroutineScope.(ElectrumClient) -> Unit) = runSuspendTest(timeout = 15.seconds) {
        val client = connectToMainnetServer()
        client.connectionStatus.map { it.toConnectionState() }.first { it is Connection.ESTABLISHED }
        test(client)
    }

    @Test
    fun `connect to an electrumx mainnet server`() = runTest { client ->
        client.stop()
    }

    @Test
    fun `switch between electrumx servers`() = runTest { client ->
        // Gracefully disconnect from previous server.
        client.disconnect()
        client.connectionStatus.map { it.toConnectionState() }.first { it is Connection.CLOSED }
        // Try to connect to unreachable server.
        assertFalse(client.connect(ServerAddress("unknown.electrum.acinq.co", 51002, TcpSocket.TLS.UNSAFE_CERTIFICATES), TcpSocket.Builder(), timeout = 1.seconds))
        assertIs<ElectrumConnectionStatus.Closed>(client.connectionStatus.first())
        // Reconnect to previous valid server.
        assertTrue(client.connect(ElectrumMainnetServerAddress, TcpSocket.Builder()))
        val connected = client.connectionStatus.filterIsInstance<ElectrumConnectionStatus.Connected>().first()
        assertTrue(connected.height >= 798_000)
        assertEquals(ServerVersionResponse("\"ElectrumX 1.15.0\"", "\"1.4\""), connected.version)
    }

    @Test
    fun `timeout connecting to electrumx server`() = runTest { client ->
        client.disconnect()
        client.connectionStatus.map { it.toConnectionState() }.first { it is Connection.CLOSED }
        assertFalse(client.connect(ElectrumMainnetServerAddress, TcpSocket.Builder(), timeout = 1.milliseconds))
        client.connectionStatus.map { it.toConnectionState() }.first { it is Connection.CLOSED }
        client.stop()
    }

    @Test
    fun `estimate fees`() = runTest { client ->
        val feerate = client.estimateFees(3)
        assertNotNull(feerate)
        assertTrue(feerate >= FeeratePerKw.MinimumFeeratePerKw)
        client.stop()
    }

    @Test
    fun `get transaction`() = runTest { client ->
        val tx = client.getTx(referenceTx.txid)
        assertEquals(referenceTx, tx)
        client.stop()
    }

    @Test
    fun `get transaction -- not found`() = runTest { client ->
        val tx = client.getTx(ByteVector32.Zeroes)
        assertNull(tx)
        client.stop()
    }

    @Test
    fun `get header`() = runTest { client ->
        val header = client.getHeader(100000)
        assertNotNull(header)
        assertEquals(
            Hex.decode("000000000003ba27aa200b1cecaad478d2b00432346c3f1f3986da1afd33e506").byteVector32(),
            header.blockId
        )
        client.stop()
    }

    @Test
    fun `get header -- not found`() = runTest { client ->
        val header = client.getHeader(7_500_000)
        assertNull(header)
        client.stop()
    }

    @Test
    fun `get headers`() = runTest { client ->
        val start = (500000 / 2016) * 2016
        val headers = client.getHeaders(start, 2016)
        assertEquals(2016, headers.size)
        client.stop()
    }

    @Test
    fun `get headers -- not found`() = runTest { client ->
        val headers = client.getHeaders(7_500_000, 10)
        assertTrue(headers.isEmpty())
        client.stop()
    }

    @Test
    fun `get merkle tree`() = runTest { client ->
        val merkle = client.getMerkle(referenceTx.txid, 500000)
        assertNotNull(merkle)
        assertEquals(referenceTx.txid, merkle.txid)
        assertEquals(500000, merkle.block_height)
        assertEquals(2690, merkle.pos)
        assertEquals(
            Hex.decode("1f6231ed3de07345b607ec2a39b2d01bec2fe10dfb7f516ba4958a42691c9531").byteVector32(),
            merkle.root
        )
        client.stop()
    }

    @Test
    fun `header subscription`() = runTest { client ->
        val response = client.startHeaderSubscription()
        assertTrue(BlockHeader.checkProofOfWork(response.header))
        client.stop()
    }

    @Test
    fun `scripthash subscription`() = runTest { client ->
        val response = client.startScriptHashSubscription(scriptHash)
        assertNotEquals("", response.status)
        client.stop()
    }

    @Test
    fun `disconnect from slow servers on subscription attempts`() = runTest { client ->
        // Set a very small timeout that the server won't be able to honor.
        client.setRpcTimeout(0.milliseconds)
        val subscriptionJob = async { client.startHeaderSubscription() }
        // We automatically disconnect after timing out on the subscription request.
        client.connectionStatus.first { it is ElectrumConnectionStatus.Closed }
        // We reconnect to a healthy server.
        client.setRpcTimeout(5.seconds)
        client.connect(ElectrumMainnetServerAddress, TcpSocket.Builder())
        // The subscription call will automatically be retried.
        val response = subscriptionJob.await()
        assertTrue(BlockHeader.checkProofOfWork(response.header))
        client.stop()
    }

    @Test
    fun `get scripthash history`() = runTest { client ->
        val history = client.getScriptHashHistory(scriptHash)
        assertTrue { history.contains(TransactionHistoryItem(500000, referenceTx.txid)) }
        client.stop()
    }

    @Test
    fun `list script unspents`() = runTest { client ->
        val response = client.getScriptHashUnspents(scriptHash)
        assertTrue { response.isEmpty() }
        client.stop()
    }

    @Test
    fun `client multiplexing`() = runTest { client ->
        val txids = listOf(
            ByteVector32("c1e943938e0bf2e9e6feefe22af0466514a58e9f7ed0f7ada6fd8e6dbeca0742"),
            ByteVector32("2cf392ecf573a638f01f72c276c3b097d05eb58f39e165eacc91b8a8df09fbd8"),
            ByteVector32("149a098d6261b7f9359a572d797c4a41b62378836a14093912618b15644ba402"),
            ByteVector32("2dd9cb7bcebb74b02efc85570a462f22a54a613235bee11d0a2c791342a26007"),
            ByteVector32("71b3dbaca67e9f9189dad3617138c19725ab541ef0b49c05a94913e9f28e3f4e"),
            ByteVector32("21d2eb195736af2a40d42107e6abd59c97eb6cffd4a5a7a7709e86590ae61987"),
            ByteVector32("74d681e0e03bafa802c8aa084379aa98d9fcd632ddc2ed9782b586ec87451f20"),
            ByteVector32("563ea83f9641d37a36f9294d172fdb4fb86c19b0e9cac45e0b27610331138775"),
            ByteVector32("971af80218684017722429be08548d1f30a2f1f220abc064380cbca5cabf7623"),
            ByteVector32("b1ec9c44009147f3cee26caba45abec2610c74df9751fad14074119b5314da21")
        )

        // request txids in parallel
        val jobs = txids.map {
            launch {
                val tx = client.getTx(it)
                assertNotNull(tx)
                assertEquals(it, tx.txid)
            }
        }
        jobs.joinAll()
        client.stop()
    }

    @Test
    fun `get tx confirmations`() = runTest { client ->
        val blockHeight = 788_370
        assertEquals(10, client.getConfirmations(ByteVector32("f1c290880b6fc9355e4f1b1b7d13b9a15babbe096adaf13d01f3a56def793fd5"), blockHeight))
        assertNull(client.getConfirmations(ByteVector32("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"), blockHeight))
        client.stop()
    }

    @Test
    fun `TCP socket closed by remote`() = runSuspendTest {
        val socketBuilder = TestTcpSocketBuilder(TcpSocket.Builder())
        val client = ElectrumClient(this, LoggerFactory.default, pingInterval = 100.milliseconds)
        client.connect(ElectrumMainnetServerAddress, socketBuilder)
        client.connectionStatus.first { it is ElectrumConnectionStatus.Connected }
        delay(100.milliseconds)
        socketBuilder.socket!!.close()
        client.connectionStatus.first { it is ElectrumConnectionStatus.Closed }
        client.stop()
    }

    @Test
    fun `multiplex incoming messages from TCP socket`() = runTest {
        class DummySocket(val chunks: ReceiveChannel<ByteArray>) : TcpSocket {
            override suspend fun receiveAvailable(buffer: ByteArray, offset: Int, length: Int): Int {
                val chunk = chunks.receive()
                require(chunk.size <= length) { "invalid test data: chunk size is greater than buffer length (${chunk.size} > $length)" }
                val size = minOf(chunk.size, length)
                chunk.copyInto(buffer, 0, 0, size)
                return size
            }

            override suspend fun receiveFully(buffer: ByteArray, offset: Int, length: Int) = TODO("Not yet implemented")
            override suspend fun send(bytes: ByteArray?, offset: Int, length: Int, flush: Boolean) = TODO("Not yet implemented")
            override suspend fun startTls(tls: TcpSocket.TLS): TcpSocket = TODO("Not yet implemented")
            override fun close() = TODO("Not yet implemented")
        }

        // We read from the TCP sockets in chunks, which may:
        //  - split messages across chunks
        //  - split utf8 characters across chunks

        run {
            // In this test, we split a message across several chunks, and split a 2-bytes utf8 character across two chunks.
            val message = "Rappelez-vous l'objet que nous vîmes, mon âme, ce beau matin d'été si doux : au détour d'un sentier une charogne infâme, sur un lit semé de cailloux."
            val chunks = listOf(
                ByteVector("52617070656c657a2d766f7573206c276f626a657420717565206e6f75732076c3"), // this chunk ends with only the first half of the 'î' character
                ByteVector("ae6d65732c206d6f6e20c3a26d652c2063652062656175206d6174696e206427c3"),
                ByteVector("a974c3a920736920646f7578203a2061752064c3a9746f7572206427756e207365"),
                ByteVector("6e7469657220756e6520636861726f676e6520696e66c3a26d652c207375722075"),
                ByteVector("6e206c69742073656dc3a9206465206361696c6c6f75782e 0a"), // we append a newline character (0x0a) to mark the message's end
            )
            assertEquals((message + "\n").toByteArray(Charsets.UTF_8).byteVector(), chunks.reduce { acc, chunk -> acc + chunk })

            // One of the characters is split across two chunks:
            assertEquals(chunks.first().size() - 1, "Rappelez-vous l'objet que nous v".toByteArray(Charsets.UTF_8).size)
            assertEquals(chunks.first().size() + 1, "Rappelez-vous l'objet que nous vî".toByteArray(Charsets.UTF_8).size)
            assertEquals(chunks.first(), "Rappelez-vous l'objet que nous vî".toByteArray(Charsets.UTF_8).subArray(33).byteVector())

            val socketChan = Channel<ByteArray>(UNLIMITED)
            chunks.forEach { chunk -> socketChan.send(chunk.toByteArray()) }

            val messageFlow = linesFlow(DummySocket(socketChan), chunkSize = 33)
            assertEquals(message, messageFlow.first())
        }
        run {
            // In this test, each chunk contains more than one message, and the third message is split across chunks.
            val messages = listOf(
                "Les jambes en l'air, comme une femme lubrique,",
                "Brûlante et suant les poisons,",
                "Ouvrait d'une façon nonchalante et cynique",
                "Son ventre plein d'exhalaisons."
            )
            val chunks = listOf(
                ByteVector("4c6573206a616d62657320656e206c276169722c20636f6d6d6520756e652066656d6d65206c756272697175652c 0a 4272c3bb6c616e7465206574207375616e74206c657320706f69736f6e732c 0a 4f7576"),
                ByteVector("72616974206427756e65206661c3a76f6e206e6f6e6368616c616e74652065742063796e69717565 0a 536f6e2076656e74726520706c65696e206427657868616c6169736f6e732e 0a"),
            )
            assertEquals(
                messages.map { m -> (m + "\n").toByteArray(Charsets.UTF_8).byteVector() }.reduce { acc, chunk -> acc + chunk },
                chunks.reduce { acc, chunk -> acc + chunk }
            )

            val socketChan = Channel<ByteArray>(UNLIMITED)
            chunks.forEach { chunk -> socketChan.send(chunk.toByteArray()) }

            val messageFlow = linesFlow(DummySocket(socketChan), chunkSize = 82)
            assertEquals(messages, messageFlow.take(4).toList())
        }
        run {
            // In this test, the first chunk contains the whole message but the newline character is in the second chunk.
            val message = "Le soleil rayonnait sur cette pourriture, comme afin de la cuire à point"
            val chunks = listOf(
                ByteVector("4c6520736f6c65696c207261796f6e6e6169742073757220636574746520706f75727269747572652c20636f6d6d65206166696e206465206c6120637569726520c3a020706f696e74"),
                ByteVector("0a"),
            )
            assertEquals(message.toByteArray(Charsets.UTF_8).byteVector(), chunks.first())

            val socketChan = Channel<ByteArray>(UNLIMITED)
            chunks.forEach { chunk -> socketChan.send(chunk.toByteArray()) }

            val messageFlow = linesFlow(DummySocket(socketChan), chunkSize = 8192)
            assertEquals(message, messageFlow.first())
        }
    }

    companion object {
        /** Wrap a [TcpSocket.Builder] instance to provide access to the last connected socket. */
        class TestTcpSocketBuilder(private val builder: TcpSocket.Builder) : TcpSocket.Builder {
            var socket: TcpSocket? = null

            override suspend fun connect(host: String, port: Int, tls: TcpSocket.TLS, loggerFactory: LoggerFactory): TcpSocket {
                val actualSocket = builder.connect(host, port, tls, loggerFactory)
                socket = actualSocket
                return actualSocket
            }
        }
    }
}
