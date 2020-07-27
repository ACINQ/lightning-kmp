package fr.acinq.eklair.blockchain.electrum

import fr.acinq.bitcoin.*
import fr.acinq.bitcoin.SigHash.SIGHASH_ALL
import fr.acinq.eklair.blockchain.*
import fr.acinq.eklair.blockchain.bitcoind.BitcoindService
import fr.acinq.eklair.utils.sat
import fr.acinq.secp256k1.Hex
import io.ktor.util.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.TestCoroutineScope
import org.junit.FixMethodOrder
import org.junit.runners.MethodSorters
import kotlin.test.*
import kotlin.time.ExperimentalTime
import kotlin.time.milliseconds

@OptIn(ExperimentalCoroutinesApi::class, KtorExperimentalAPI::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class ElectrumWatcherIntegrationTest {

    private val testScope = TestCoroutineScope()
    private val bitcoinService = BitcoindService

    private lateinit var client : ElectrumClient
    private lateinit var watcher: ElectrumWatcher

    @BeforeTest fun before() {
        client = ElectrumClient("localhost", 51001, false, testScope).apply { start() }
        watcher = ElectrumWatcher(client, testScope).apply { start() }
    }

    @AfterTest fun after() {
        watcher.stop()
        client.stop()
    }

    @Test
    fun `01 watch for confirmed transactions`() = runBlocking {
        val (address,_) = bitcoinService.getNewAddress()
        val tx = bitcoinService.sendToAddress(address, 1.0)

        val listener = Channel<WatchEventConfirmed>()
        watcher.watch(WatchConfirmed(listener ,tx.txid, tx.txOut[0].publicKeyScript, 4, BITCOIN_FUNDING_DEPTHOK))
        bitcoinService.generateBlocks(5)

        withTimeout(20_000) {
            val msg = listener.receive()
            assertEquals(tx.txid, msg.tx.txid)
        }
    }

    @Test
    fun `02 watch for confirmed transactions created while being offline`() = runBlocking {
        val (address,_) = bitcoinService.getNewAddress()
        val tx = bitcoinService.sendToAddress(address, 1.0)

        bitcoinService.generateBlocks(5)

        val listener = Channel<WatchEventConfirmed>()
        watcher.watch(WatchConfirmed(listener ,tx.txid, tx.txOut[0].publicKeyScript, 4, BITCOIN_FUNDING_DEPTHOK))

        withTimeout(20_000) {
            val msg = listener.receive()
            assertEquals(tx.txid, msg.tx.txid)
        }
    }

    @Test
    fun `03 watch for spent transactions`() = runBlocking {
        val (address, privateKey) = bitcoinService.getNewAddress()
        val tx = bitcoinService.sendToAddress(address, 1.0)

        // find the output for the address we generated and create a tx that spends it
        val pos= tx.txOut.indexOfFirst {
            it.publicKeyScript == Script.write(Script.pay2wpkh(privateKey.publicKey())).byteVector()
        }
        assert(pos != -1)

        val spendingTx = kotlin.run {
            val tmp = Transaction(version = 2,
                txIn = listOf(TxIn(OutPoint(tx, pos.toLong()), signatureScript = emptyList(), sequence = TxIn.SEQUENCE_FINAL)),
                txOut = listOf(TxOut(tx.txOut[pos].amount - 1000.sat, publicKeyScript = Script.pay2wpkh(privateKey.publicKey()))),
                lockTime = 0)

            val sig = Transaction.signInput(
                tmp,
                0,
                Script.pay2pkh(privateKey.publicKey()),
                SIGHASH_ALL,
                tx.txOut[pos].amount,
                SigVersion.SIGVERSION_WITNESS_V0,
                privateKey
            ).byteVector()
            val signedTx = tmp.updateWitness(0, ScriptWitness(listOf(sig, privateKey.publicKey().value)))
            Transaction.correctlySpends(signedTx, listOf(tx), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
            signedTx
        }

        val listener = Channel<WatchEventSpent>()
        watcher.watch(WatchSpent(listener ,tx.txid, pos, tx.txOut[pos].publicKeyScript, BITCOIN_FUNDING_SPENT))

        // send raw tx
        val sentTx = bitcoinService.sendRawTransaction(spendingTx)
        assertEquals(spendingTx, sentTx)
        bitcoinService.generateBlocks(2)

//        withTimeout(20_000) {
            val msg = listener.receive()
            assertEquals(tx.txid, msg.tx.txid)
//        }
    }

    @Test
    fun `04 watch for spent transactions while being offline`() = runBlocking {
        val (address, privateKey) = bitcoinService.getNewAddress()
        val tx = bitcoinService.sendToAddress(address, 1.0)

        // find the output for the address we generated and create a tx that spends it
        val pos= tx.txOut.indexOfFirst {
            it.publicKeyScript == Script.write(Script.pay2wpkh(privateKey.publicKey())).byteVector()
        }
        assert(pos != -1)

        val spendingTx = kotlin.run {
            val tmp = Transaction(version = 2,
                txIn = listOf(TxIn(OutPoint(tx, pos.toLong()), signatureScript = emptyList(), sequence = TxIn.SEQUENCE_FINAL)),
                txOut = listOf(TxOut(tx.txOut[pos].amount - 1000.sat, publicKeyScript = Script.pay2wpkh(privateKey.publicKey()))),
                lockTime = 0)

            val sig = Transaction.signInput(
                tmp,
                0,
                Script.pay2pkh(privateKey.publicKey()),
                SIGHASH_ALL,
                tx.txOut[pos].amount,
                SigVersion.SIGVERSION_WITNESS_V0,
                privateKey
            ).byteVector()
            val signedTx = tmp.updateWitness(0, ScriptWitness(listOf(sig, privateKey.publicKey().value)))
            Transaction.correctlySpends(signedTx, listOf(tx), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
            signedTx
        }

        // send raw tx
        bitcoinService.sendRawTransaction(spendingTx)
        bitcoinService.generateBlocks(2)

        val listener = Channel<WatchEventSpent>()
        watcher.watch(WatchSpent(listener ,tx.txid, pos, tx.txOut[pos].publicKeyScript, BITCOIN_FUNDING_SPENT))

        withTimeout(20_000) {
            val msg = listener.receive()
            assertEquals(tx.txid, msg.tx.txid)
        }
    }

    @OptIn(ExperimentalTime::class)
    @Test
    fun `05 get transaction`() = runBlocking {
        // Run on a production server
        val electrumClient = ElectrumClient("electrum.acinq.co", 50002, true, testScope)
        val electrumWatcher = ElectrumWatcher(electrumClient, testScope)
        electrumClient.start(); electrumWatcher.start()

        delay(1_000) // Wait for the electrum client to be ready

        // tx is in the blockchain
        kotlin.run {
            val txid = ByteVector32(Hex.decode("c0b18008713360d7c30dae0940d88152a4bbb10faef5a69fefca5f7a7e1a06cc"))
            val listener = Channel<GetTxWithMetaResponse>()
            electrumWatcher.send(GetTxWithMetaEvent(txid, listener))
            val res = listener.receive()
            assertEquals(res.txid, txid)
            assertEquals(
                res.tx_opt,
                Transaction.read("0100000001b5cbd7615a7494f60304695c180eb255113bd5effcf54aec6c7dfbca67f533a1010000006a473044022042115a5d1a489bbc9bd4348521b098025625c9b6c6474f84b96b11301da17a0602203ccb684b1d133ff87265a6017ef0fdd2d22dd6eef0725c57826f8aaadcc16d9d012103629aa3df53cad290078bbad26491f1e11f9c01697c65db0967561f6f142c993cffffffff02801015000000000017a914b8984d6344eed24689cdbc77adaf73c66c4fdd688734e9e818000000001976a91404607585722760691867b42d43701905736be47d88ac00000000")
            )
            assert(res.lastBlockTimestamp > System.currentTimeMillis().milliseconds.inSeconds - 7200) // this server should be in sync
        }

        // tx doesn't exist
        kotlin.run {
            val txid = ByteVector32(Hex.decode("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"))
            val listener = Channel<GetTxWithMetaResponse>()
            electrumWatcher.send(GetTxWithMetaEvent(txid, listener))
            val res = listener.receive()
            assertEquals(res.txid, txid)
            assertNull(res.tx_opt)
            assert(res.lastBlockTimestamp > System.currentTimeMillis().milliseconds.inSeconds - 7200) // this server should be in sync
        }
        electrumWatcher.stop(); electrumClient.stop()
    }

    /*
    - OK:
        test("watch for confirmed transactions")
        test("watch for confirmed transactions when being offline")
        test("watch for spent transactions")
        test("generate unique dummy scids") => UnitTests
        test("get transaction")
    - TODO:
        test("watch for mempool transactions (txs in mempool before we set the watch)")
        test("watch for mempool transactions (txs not yet in the mempool when we set the watch)")
     */
}