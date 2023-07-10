package fr.acinq.lightning.blockchain.electrum

import fr.acinq.bitcoin.*
import fr.acinq.lightning.blockchain.fee.FeeratePerKw
import fr.acinq.lightning.io.TcpSocket
import fr.acinq.lightning.tests.utils.LightningTestSuite
import fr.acinq.lightning.tests.utils.runSuspendTest
import fr.acinq.lightning.utils.Connection
import fr.acinq.lightning.utils.linesFlow
import fr.acinq.lightning.utils.subArray
import fr.acinq.lightning.utils.toByteVector32
import fr.acinq.secp256k1.Hex
import io.ktor.utils.io.charsets.*
import io.ktor.utils.io.core.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.Channel.Factory.UNLIMITED
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds

class ElectrumClientTest : LightningTestSuite() {
    // this is tx #2690 of block #500000
    private val referenceTx = Transaction.read(
        "0200000001983c5b32ced1de5ae97d3ce9b7436f8bb0487d15bf81e5cae97b1e238dc395c6000000006a47304402205957c75766e391350eba2c7b752f0056cb34b353648ecd0992a8a81fc9bcfe980220629c286592842d152cdde71177cd83086619744a533f262473298cacf60193500121021b8b51f74dbf0ac1e766d162c8707b5e8d89fc59da0796f3b4505e7c0fb4cf31feffffff0276bd0101000000001976a914219de672ba773aa0bc2e15cdd9d2e69b734138fa88ac3e692001000000001976a914301706dede031e9fb4b60836e073a4761855f6b188ac09a10700"
    )
    private val scriptHash = Crypto.sha256(referenceTx.txOut.first().publicKeyScript).toByteVector32().reversed()
    private val height = 500000
    private val position = 2690
    private val merkleProof = listOf(
        Hex.decode("b500cd85cd6c7e0e570b82728dd516646536a477b61cc82056505d84a5820dc3"),
        Hex.decode("c98798c2e576566a92b23d2405f59d95c506966a6e26fecfb356d6447a199546"),
        Hex.decode("930d95c428546812fd11f8242904a9a1ba05d2140cd3a83be0e2ed794821c9ec"),
        Hex.decode("90c97965b12f4262fe9bf95bc37ff7d6362902745eaa822ecf0cf85801fa8b48"),
        Hex.decode("23792d51fddd6e439ed4c92ad9f19a9b73fc9d5c52bdd69039be70ad6619a1aa"),
        Hex.decode("4b73075f29a0abdcec2c83c2cfafc5f304d2c19dcacb50a88a023df725468760"),
        Hex.decode("f80225a32a5ce4ef0703822c6aa29692431a816dec77d9b1baa5b09c3ba29bfb"),
        Hex.decode("4858ac33f2022383d3b4dd674666a0880557d02a155073be93231a02ecbb81f4"),
        Hex.decode("eb5b142030ed4e0b55a8ba5a7b5b783a0a24e0c2fd67c1cfa2f7b308db00c38a"),
        Hex.decode("86858812c3837d209110f7ea79de485abdfd22039467a8aa15a8d85856ee7d30"),
        Hex.decode("de20eb85f2e9ad525a6fb5c618682b6bdce2fa83df836a698f31575c4e5b3d38"),
        Hex.decode("98bd1048e04ff1b0af5856d9890cd708d8d67ad6f3a01f777130fbc16810eeb3")
    ).map { it.toByteVector32() }

    private fun runTest(test: suspend CoroutineScope.(ElectrumClient) -> Unit) = runSuspendTest(timeout = 15.seconds) {
        val client = connectToMainnetServer()

        client.connectionState.first { it is Connection.CLOSED }
        client.connectionState.first { it is Connection.ESTABLISHING }
        client.connectionState.first { it is Connection.ESTABLISHED }

        test(client)
    }

    @Test
    fun `connect to an electrumx mainnet server`() = runTest { client ->
        client.stop()
    }

    @Test
    fun `estimate fees`() = runTest { client ->
        val response = client.estimateFees(3)
        assertTrue { response.feerate!! >= FeeratePerKw.MinimumFeeratePerKw }
        client.stop()
    }

    @Test
    fun `get transaction id from position`() = runTest { client ->
        val response = client.rpcCall<GetTransactionIdFromPositionResponse>(GetTransactionIdFromPosition(height, position))
        assertEquals(GetTransactionIdFromPositionResponse(referenceTx.txid, height, position), response)
        client.stop()
    }

    @Test
    fun `get transaction id from position with merkle proof`() = runTest { client ->
        val response = client.rpcCall<GetTransactionIdFromPositionResponse>(GetTransactionIdFromPosition(height, position, merkle = true))
        assertEquals(GetTransactionIdFromPositionResponse(referenceTx.txid, height, position, merkleProof), response)
        client.stop()
    }

    @Test
    fun `get transaction`() = runTest { client ->
        val tx = client.getTx(referenceTx.txid)
        assertEquals(referenceTx, tx)
        client.stop()
    }

    @Test
    fun `get header`() = runTest { client ->
        val response = client.rpcCall<GetHeaderResponse>(GetHeader(100000))
        assertEquals(
            Hex.decode("000000000003ba27aa200b1cecaad478d2b00432346c3f1f3986da1afd33e506").byteVector32(),
            response.header.blockId
        )
        client.stop()
    }

    @Test
    fun `get headers`() = runTest { client ->
        val start = (500000 / 2016) * 2016
        val response = client.rpcCall<GetHeadersResponse>(GetHeaders(start, 2016))
        assertEquals(start, response.start_height)
        assertEquals(2016, response.headers.size)
        client.stop()
    }

    @Test
    fun `get merkle tree`() = runTest { client ->
        val merkle = client.getMerkle(referenceTx.txid, 500000)

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
        require(BlockHeader.checkProofOfWork(response.header))
        client.stop()
    }

    @Test
    fun `scripthash subscription`() = runTest { client ->
        val response = client.startScriptHashSubscription(scriptHash)
        assertNotEquals("", response.status)
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
                assertEquals(it, tx.txid)
            }
        }
        jobs.joinAll()
        client.stop()
    }

    @Test
    fun `get tx confirmations`() = runTest { client ->

        assertTrue(client.getConfirmations(ByteVector32("f1c290880b6fc9355e4f1b1b7d13b9a15babbe096adaf13d01f3a56def793fd5"))!! > 0)
        assertNull(client.getConfirmations(ByteVector32("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")))

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
}
