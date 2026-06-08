package fr.acinq.lightning.blockchain.electrum

import fr.acinq.bitcoin.*
import fr.acinq.lightning.blockchain.fee.FeeratePerKw
import fr.acinq.lightning.io.TcpSocket
import fr.acinq.lightning.logging.*
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
import kotlin.test.*
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class ElectrumClientTest : LightningTestSuite() {
    // this is tx #2690 of block #500000
    private val referenceTx = Transaction.read(
        "0100000000011500472e0b21ebc120e7c7981c15a6226dda4b302c78a367564ae8f4c62096c5250000000000ffffffffc86b43808c79f061f4ce715506eb7de84d003f37b0ae7f33ffa523c076a2bce00000000000ffffffffd476c0d8775aa7e3e1fd496cee350640f66471400c7fe8bb92b7e0f8a2e93f940400000000ffffffffd476c0d8775aa7e3e1fd496cee350640f66471400c7fe8bb92b7e0f8a2e93f940500000000ffffffff364f60ec53ff4b345ecaf95b12733823bb57306bc9879c1dd8936a3f8fc0a7710400000000ffffffff364f60ec53ff4b345ecaf95b12733823bb57306bc9879c1dd8936a3f8fc0a7710500000000ffffffff364f60ec53ff4b345ecaf95b12733823bb57306bc9879c1dd8936a3f8fc0a7710a00000000ffffffff364f60ec53ff4b345ecaf95b12733823bb57306bc9879c1dd8936a3f8fc0a7710b00000000ffffffff364f60ec53ff4b345ecaf95b12733823bb57306bc9879c1dd8936a3f8fc0a7710c00000000ffffffffa43c01a9bf4452b7c8d8975ef90eb98a456056922b7789a134e6a436777c00250300000000ffffffffd1e0e3fe3377bf86df09f879d355ab6bd3da059732194e4aba5baaf82d90637d0000000000ffffffffd1e0e3fe3377bf86df09f879d355ab6bd3da059732194e4aba5baaf82d90637d0a00000000ffffffff364f60ec53ff4b345ecaf95b12733823bb57306bc9879c1dd8936a3f8fc0a7711100000000ffffffffd5b8df2731a7662ce3f22797adbfd6b30205439a1dea5c1730c39b50b958315e1200000000ffffffffd476c0d8775aa7e3e1fd496cee350640f66471400c7fe8bb92b7e0f8a2e93f940800000000ffffffff364f60ec53ff4b345ecaf95b12733823bb57306bc9879c1dd8936a3f8fc0a7711500000000ffffffffc86b43808c79f061f4ce715506eb7de84d003f37b0ae7f33ffa523c076a2bce00f00000000ffffffff364f60ec53ff4b345ecaf95b12733823bb57306bc9879c1dd8936a3f8fc0a7711900000000ffffffff364f60ec53ff4b345ecaf95b12733823bb57306bc9879c1dd8936a3f8fc0a7711a00000000ffffffff364f60ec53ff4b345ecaf95b12733823bb57306bc9879c1dd8936a3f8fc0a7711c00000000ffffffffd476c0d8775aa7e3e1fd496cee350640f66471400c7fe8bb92b7e0f8a2e93f941000000000ffffffff1bd353180000000000225120093605515f02079fb011a448034c51f90727df8ebcbeaa25e6c6223e8e6c314cd3531800000000002251202e432d8d424629cbc96acb29ec4b16bfa2042f43f0b37dcbaf4225a2e1a559d8d35318000000000022512041015e8382a0bc2c14ae5e395b1a2735be2740d3a18b538152ee552fd21de253d3531800000000002251206277014344ae6ac3d98cbec4bef8a5af58d1246e83adf845ac51f11bb5d2f42bd3531800000000002251209ef193279d7d070089fd36984fd04352cb189b48deeb1a6191a55bd228af930ed353180000000000225120a697009844cb2faca2d8f741b84c1e1e33a90865811ddf969e6678208f739d69d353180000000000225120cf6ae656457494f2adf1677344d6dbd961abb03f15e2f9c02fd485bb280b4ec5d353180000000000225120d34c046d374537e6f92ee4cc0a6e16caa8cd04e7b699d9abd3334a94aa2da82bd353180000000000225120dd68536a5aac426fbe4068a2d7abeb3446a67ef7a4d961453a0c790b32bd4318d353180000000000225120df6865da6cd4a1f22ef9284868101bac4635d3212723c6a9fe97867f8226c480d353180000000000225120f29a2ed1b6601c6d345bad70eaf7abfc8606eb4c8e474aa3815fd99b0dc0b5d1e23710000000000022512014b051bee84ea80afc617e9f482881681e2bd4ee955500571a29961b7bd1c910e23710000000000022512045621946ac962ad93b2d6e5350f63125ec8f30503dd9cc908ecbd11a8ac31ec3e2371000000000002251205d074809a1d838c10d264ae14dd9d5a51c8e0850f57021464529990a68dd9261f66705000000000022512079982bab3b447a88b226e2c3b8a697fff230ed1e9817eb18be23642ce21ba664f6670500000000002251209c92a4eb074ba061916b760b24a14f16ca575fb2be7521c3e9b51d60e9eabfa80000040000000000225120bb96acda351fe249482bccc09c237610941d4a17239905c4bb122ebd6672881c0000040000000000225120ccac80b77001266d9873365c9b677154d7303a3aa9fc67d98ab4a071624516d00000040000000000225120def8a047d7274f0f9f8278f9d7633770f1c3372f83a0df45b73504ca9324efc4a08601000000000022512019d031a31abb14d86a8d80fe9dc9f9f59a06f32f5b8b1167c0c4090b2a63bbc4a08601000000000022512071d79ee1b348f5050932f610aa8fa3b70b4d6c1e15b9fb68471e844ad875c5bea086010000000000225120766f90001961842d93d098e2476fb4f72b71298e1ae680b5a407325c9efcbfbd00000100000000001600146419c7c91b1c60d4534c0cf0afd59edabb44869a00000100000000002251206f707d0909674e29519e631bc5921dd7f8690d00cafd3c70be8f299940dcd9db0000010000000000225120ad4594e6dc5ecd4d220b9699c2bbeae4b4fcff3816d81868a7bf21112421adfd20b5000000000000225120629f22fa8092def6642cd2b705d56aeb7b5c89730325c3dfde9698a032503bdf398b0000000000001600143232324aaced7b4397ead69dd4e732845398f9b602473044022069a9e3007dd7a153f7f8398ac0bd339d26ac040bb075cae02a80ca8de4ad531d02204e18286461860ff5d3a55b3874fb1689c75649bc7c70e52fe256908fa245c7dc0121025ebc07367becfb50577b77de5b21757a54340397b7b41afd38fe4ec4e9c0ec8a02473044022068f97825e019398f3ca8288cd945e37d733bde691835d409dec24de632d03fa402206d3454db7dd37ce628bee834fa85c33e3bb02a2c3d80a005c53480736cae72a401210369a4ed845679fc4be9b757d9384bc5614eded402809bb288a33544e4dbfcb0100247304402205fe3c8089f2354c8699327b3cb406f21c7bb26bb492edaaa1dff1cf22e7fef6702206ec78082ddbf2e5dcbe4428789e734b7fd1ff3989b2a5a0193216fe22e00e94701210316810f5bcadb58d3b87a7cd410ab2e7eb9ae4e5502553e68c24484288d21f16b0247304402206d578f67aeed5cb3b61f5d4ca3c7087c6ee2b9f307f947c47bde5ac580df5aa70220305dbbffd4a7d38d7b7cdbae74b645c7c8b2c308aa36fa5735b69d1d8c875cc4012103f794893e9780c4f8ec2a6b7383439fe0fbb7d16b5206ff88160dec8dfaaa1a5a0141179f675d34e6253278c3d19777902ccf370659fdc8fc69c7b6515e36a894929dc61440e7335a6dc68a5d0f425995cf8ecc93af94aa48145f9c31161c423d1faf01014183b46f255a23530e81f5a1a0b20b61b93277f6e0d5746f953d7fdef134fe52daad6778bd321bafa1d42e8c8ebf25f881583dbc231ca733977e4c0ff6544e75a4010141ce58d0aaa3119141457f58060e965989d124fb3bd05834bf0fe34492e65473f5128a5d9fb34f2e420274da4aa6c9dd33cada874ac7b231b2e0491843e75a74fc010141c2a20360a40fb8e37cb5f340c7c2dcdf5de20fa66033081c386e93f085518ea25a350c96129300d25e6a01cda5af6c1a72259929a6854c3741e3e4a680f7d3210101417449a347625b85068390ab09a606f4b6d236181d398d53f0513216e663ebcdb8ee7ff227a5e617e41419778c074ff94564bcadb20b789895f1e16582a2f1aabb0101413e77b80f8eb4b9835781be3270c5b1db5f6cb1a1d934c1f2a3a8851775db9c6690e4de96e6277f722fac0976c67459e93b90dd1f0b0572ea302149b7e30555c901014191b505e449af535ce250b96220c04c68aefa2ceb13c8aafab410d8afd5352435ff08ff6de9a34c37263908251f79e0df5b31a9b227abd58e58249924bd011cc7010141492e7813a805b0d752a313953361b2efeb46ed783e334a049073226a56979671030fdd87f9225f05b789d8d8b0f90bc915b2f11d8d87d7953156daa662f1c849010141db7c070be026dae43f1400ee1f7f226e1145a08da4a19a402cd6a998fed91dda6c72d4ededadc094239046b6e4b5a2da8136fdb5992396f2628ebbc882eb36db010141da3441c6b3b081139bb1bd929026d8b1f5182049247e85ff5afca5357d8ef9ff6386ef5eedec64b3d2d4ba35b6de72f73f76861ea3ab10cf14105972b28ba0b70101417e683d6d8882883724cf28bf7f6d4bc9d854e49da3729cde673dfcd31879c795d76741bdc024d76240a3760b6a85ecc50aded5417adb4d2a963a9f0a76d78e200102473044022032f7b27614c0dafc4dc79c2d5adc05eb79649731c3828dc23ad3719ef305a14802200439ab48eac881765314fe8f2ed659efce34a5bf22a5ea8471616d42870819eb01210237139c8ed165506c56ab20b06e650d00da63091a33ad2b5d18ff0155591b3d680141b578506a0f75ff2372db3d93797db7ae22f664d748b122d610af5850235ca05c3a7a221c989ff6818ff24f8f2291b52e9c355b0fe7f8550904d9f7032299f041010247304402207ecb51b507928d71308bfc8cd97c5932bef48e292afc8f124581faff4cb48c2702206c1281082c9aa307ff7eda2ecfaf3442cb091812b61265b43863c185bd40161701210262b0f42d367b28408a5dfa779883dd09a685216b86089ce3c0925f4eab7d7c1402473044022054a42051d35f2d848e3586b420e42e7023d060817c5158507cfadd46970683f7022071b72e8ee1bbe9ed549f1b5a855568f598f180e1c0503574124cfbdbdda9b299012103d11217424cb89da8d951691fda0092e27f627630e42975fa18481de3260a2c14014120a0786f484e0454f21572d31da6f36554bea7d721a46a415ce2d2ceac3d421d8b27b44d8eaafe4ccacf9b5bccadbbbe4ca15b5fa5cf9aaf407837b82cbe91eb010247304402200c09e387d9ae236dc5a5398ba2dfce4d077cfc5394365d54722ee1f3740a5e290220686a5f9697d643e00eccdc81e5fe8a58e818e042bec82176a725be7a5ac62ead01210292f453026068d7c31fdbeee4dac135c7e9e3e6e6a2fc4d0bcb0074a1a136239f00000000"
    )
    private val scriptHash = Crypto.sha256(referenceTx.txOut.first().publicKeyScript).toByteVector32().reversed()

    private fun runTest(test: suspend CoroutineScope.(ElectrumClient) -> Unit) = runSuspendTest(timeout = 15.seconds) {
        val client = connectToTestnet4Server()
        client.connectionStatus.map { it.toConnectionState() }.first { it is Connection.ESTABLISHED }
        test(client)
    }

    @Test
    fun `connect to an electrumx mainnet server`() = runTest { client ->
        client.stop()
    }

    @Ignore
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
    fun `status should be empty for unused addresses`() = runTest { client ->
        val address = "bc1qzp2r7k62chyyq7g8ppw2dxp2lcrt629ym0swqy"
        val scriptHash = ElectrumClient.computeScriptHash(Script.write(Bitcoin.addressToPublicKeyScript(Block.LivenetGenesisBlock.hash, address).right!!).byteVector())
        val status = client.startScriptHashSubscription(scriptHash)
        assertNull(status.status)
    }

    @Test
    fun `get transaction`() = runTest { client ->
        val tx = client.getTx(referenceTx.txid)
        assertEquals(referenceTx, tx)
        client.stop()
    }

    @Test
    fun `get transaction -- not found`() = runTest { client ->
        val tx = client.getTx(TxId(ByteVector32.Zeroes))
        assertNull(tx)
        client.stop()
    }

    @Test
    fun `get header`() = runTest { client ->
        val header = client.getHeader(100000)
        assertNotNull(header)
        assertEquals(
            BlockId(Hex.decode("0000000000524911745ab6eee9348bca9843c2c2b1b27eada246e3dc2f80b6b1")),
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
        val start = 100
        val headers = client.getHeaders(start, 10)
        assertEquals(10, headers.size)
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
        val merkle = client.getMerkle(referenceTx.txid, 130003)
        assertNotNull(merkle)
        assertEquals(referenceTx.txid, merkle.txid)
        assertEquals(130003, merkle.blockHeight)
        assertEquals(1, merkle.pos)
        assertEquals(
            Hex.decode("8b4ad6ed31ca4be23e47770bfad09cf944aeb61349d03e3fa61f615388550f62").byteVector32(),
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

    @Ignore
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
        assertTrue(history.contains(TransactionHistoryItem(130_003, referenceTx.txid)))
        client.stop()
    }

    @Test
    fun `list script unspents`() = runTest { client ->
        val response = client.getScriptHashUnspents(scriptHash)
        assertTrue(response.isEmpty())
        client.stop()
    }

    @Test
    fun `client multiplexing`() = runTest { client ->
        val txids = listOf(
            TxId("a1182c6745b46383c7291a00869f7dd3a7b98fb6ca1289888e924417d83b3ac7"),
            TxId("efebdd5da7056f0efa00fd2666f264c6e0064d88c9ff416418e5e5b5470a5c91"),
            TxId("8c6bfc4dc922831c04377181bb23b9457a0abbcea475a00d6ea6336f037833be"),
            TxId("e47586124f9e45b638b98c1128f41b353993ceb558d20c634a2ce3fdff491cbd"),
            TxId("2cf454658855d3aaf64c580df7291174c1aa63498077029f50a95b9b2f576ebf"),
            TxId("725192b1a4273e0fc2bcb19ace17f7509381e5b1c545ef2282f62ce66cdbfe1a"),
            TxId("e05b81054f8400be61445282334c677af45af8c8dc949cf4af489e8bd2969f34"),
            TxId("8dafdb5a812e3ac410c3abe307f41b0cb1cc6b14ee00f937af2aeceb44dd4e36"),
            TxId("bd022a16e75a872f05190fb7d6e43c5e43b03de0bb8392f6fe9f0a9256c56c74"),
            TxId("8b6c3e89bec4683d9d7a404409423fc5c6608eee5289acc0d4e0ec9b444b32b1"),
            TxId("60f3c026ad3ec87c1ef8fc03e565a255f56830609418abac07703cf029802cb6"),
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
        val confirmedAt = 130_002
        val currentBlockHeight = when (val status = client.connectionStatus.value) {
            is ElectrumConnectionStatus.Connected -> status.height
            else -> null
        }!!
        assertEquals(currentBlockHeight - confirmedAt, client.getConfirmations(referenceTx.txid))
        assertNull(client.getConfirmations(TxId("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")))
        client.stop()
    }

    @Ignore
    fun `TCP socket closed by remote`() = runSuspendTest {
        val socketBuilder = TestTcpSocketBuilder(TcpSocket.Builder())
        val client = ElectrumClient(this, loggerFactory, pingInterval = 100.milliseconds)
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
