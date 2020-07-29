package fr.acinq.eklair.blockchain.electrum

import fr.acinq.bitcoin.Crypto
import fr.acinq.bitcoin.Transaction
import fr.acinq.eklair.utils.runTest
import fr.acinq.eklair.utils.toByteVector32
import fr.acinq.secp256k1.Hex
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class ElectrumClientIntegrationTest {
    // this is tx #2690 of block #500000
    val referenceTx = Transaction.read("0200000001983c5b32ced1de5ae97d3ce9b7436f8bb0487d15bf81e5cae97b1e238dc395c6000000006a47304402205957c75766e391350eba2c7b752f0056cb34b353648ecd0992a8a81fc9bcfe980220629c286592842d152cdde71177cd83086619744a533f262473298cacf60193500121021b8b51f74dbf0ac1e766d162c8707b5e8d89fc59da0796f3b4505e7c0fb4cf31feffffff0276bd0101000000001976a914219de672ba773aa0bc2e15cdd9d2e69b734138fa88ac3e692001000000001976a914301706dede031e9fb4b60836e073a4761855f6b188ac09a10700")
    val scriptHash = Crypto.sha256(referenceTx.txOut.first().publicKeyScript).toByteVector32().reversed()
    val height = 500000
    val position = 2690
    val merkleProof = listOf(
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
        Hex.decode("98bd1048e04ff1b0af5856d9890cd708d8d67ad6f3a01f777130fbc16810eeb3"))
        .map { it.toByteVector32() }


    @Test
    fun `connect to an electrumx mainnet server`() = runTest {
        println("create client")
        val client = ElectrumClient("localhost", 51001, false, this).apply { start() }

        println("create channel")
        val channel = Channel<ElectrumMessage>()
        println("send message to client")
        client.sendMessage(ElectrumStatusSubscription(channel))

        withTimeout(15_000) {
            println("waiting for response")
            val msg = channel.receive()
            println("msg is $msg")
            assertEquals(ElectrumClientReady, msg)
        }

        client.stop()
    }

    @Test
    fun `get transaction id from position`() {TODO() }
    @Test
    fun `get transaction id from position with merkle proof`() {TODO() }
    @Test
    fun `get transaction`() {TODO() }
    @Test
    fun `get header`() {TODO() }
    @Test
    fun `get headers`() {TODO() }
    @Test
    fun `get merkle tree`() {TODO() }
    @Test
    fun `header subscription`() {TODO() }
    @Test
    fun `scripthash subscription`() {TODO() }
    @Test
    fun `get scripthash history`() {TODO() }
    @Test
    fun `list script unspents`() {TODO() }
}