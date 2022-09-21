package fr.acinq.lightning.blockchain.electrum

import fr.acinq.bitcoin.*
import fr.acinq.bitcoin.SigHash.SIGHASH_ALL
import fr.acinq.lightning.blockchain.*
import fr.acinq.lightning.io.TcpSocket
import fr.acinq.lightning.tests.bitcoind.BitcoindService
import fr.acinq.lightning.tests.utils.LightningTestSuite
import fr.acinq.lightning.tests.utils.runSuspendBlocking
import fr.acinq.lightning.tests.utils.runSuspendTest
import fr.acinq.lightning.utils.ServerAddress
import fr.acinq.lightning.utils.currentTimestampSeconds
import fr.acinq.lightning.utils.runTrying
import fr.acinq.lightning.utils.sat
import fr.acinq.secp256k1.Hex
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import org.kodein.log.LoggerFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class ElectrumWatcherIntegrationTest : LightningTestSuite() {

    private val bitcoincli = BitcoindService

    init {
        runSuspendBlocking {
            withTimeout(10.seconds) {
                while (runTrying { bitcoincli.getNetworkInfo() }.isFailure) {
                    delay(0.5.seconds)
                }
            }
        }
    }

    @Test
    fun `watch for confirmed transactions`() = runSuspendTest {
        val client = ElectrumClient(TcpSocket.Builder(), this, LoggerFactory.default).apply { connect(ServerAddress("localhost", 51001, TcpSocket.TLS.DISABLED)) }
        val watcher = ElectrumWatcher(client, this, LoggerFactory.default)

        val (address, _) = bitcoincli.getNewAddress()
        val tx = bitcoincli.sendToAddress(address, 1.0)

        val listener = watcher.openWatchNotificationsFlow()
        watcher.watch(
            WatchConfirmed(
                ByteVector32.Zeroes,
                tx.txid,
                tx.txOut[0].publicKeyScript,
                4,
                BITCOIN_FUNDING_DEPTHOK
            )
        )
        bitcoincli.generateBlocks(5)

        val confirmed = listener.first() as WatchEventConfirmed
        assertEquals(tx.txid, confirmed.tx.txid)

        watcher.stop()
        client.stop()
    }

    @Test
    fun `watch for confirmed transactions created while being offline`() = runSuspendTest {
        val client = ElectrumClient(TcpSocket.Builder(), this, LoggerFactory.default).apply { connect(ServerAddress("localhost", 51001, TcpSocket.TLS.DISABLED)) }
        val watcher = ElectrumWatcher(client, this, LoggerFactory.default)

        val (address, _) = bitcoincli.getNewAddress()
        val tx = bitcoincli.sendToAddress(address, 1.0)

        bitcoincli.generateBlocks(5)

        val listener = watcher.openWatchNotificationsFlow()
        watcher.watch(
            WatchConfirmed(
                ByteVector32.Zeroes,
                tx.txid,
                tx.txOut[0].publicKeyScript,
                4,
                BITCOIN_FUNDING_DEPTHOK
            )
        )

        val confirmed = listener.first() as WatchEventConfirmed
        assertEquals(tx.txid, confirmed.tx.txid)

        watcher.stop()
        client.stop()
    }

    @Test
    fun `watch for spent transactions`() = runSuspendTest {
        val client = ElectrumClient(TcpSocket.Builder(), this, LoggerFactory.default).apply { connect(ServerAddress("localhost", 51001, TcpSocket.TLS.DISABLED)) }
        val watcher = ElectrumWatcher(client, this, LoggerFactory.default)

        val (address, privateKey) = bitcoincli.getNewAddress()
        val tx = bitcoincli.sendToAddress(address, 1.0)

        // find the output for the address we generated and create a tx that spends it
        val pos = tx.txOut.indexOfFirst {
            it.publicKeyScript == Script.write(Script.pay2wpkh(privateKey.publicKey())).byteVector()
        }
        assertTrue(pos != -1)

        val tmp = Transaction(
            version = 2,
            txIn = listOf(TxIn(OutPoint(tx, pos.toLong()), signatureScript = emptyList(), sequence = TxIn.SEQUENCE_FINAL)),
            txOut = listOf(TxOut(tx.txOut[pos].amount - 1000.sat, publicKeyScript = Script.pay2wpkh(privateKey.publicKey()))),
            lockTime = 0
        )

        val sig = Transaction.signInput(
            tmp,
            0,
            Script.pay2pkh(privateKey.publicKey()),
            SIGHASH_ALL,
            tx.txOut[pos].amount,
            SigVersion.SIGVERSION_WITNESS_V0,
            privateKey
        ).byteVector()

        val spendingTx = tmp.updateWitness(0, ScriptWitness(listOf(sig, privateKey.publicKey().value)))
        Transaction.correctlySpends(spendingTx, listOf(tx), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)

        val listener = watcher.openWatchNotificationsFlow()
        watcher.watch(
            WatchSpent(
                ByteVector32.Zeroes,
                tx.txid,
                pos,
                tx.txOut[pos].publicKeyScript,
                BITCOIN_FUNDING_SPENT
            )
        )

        // send raw tx
        val sentTx = bitcoincli.sendRawTransaction(spendingTx)
        assertEquals(spendingTx, sentTx)
        bitcoincli.generateBlocks(2)

        val msg = listener.first() as WatchEventSpent
        assertEquals(spendingTx.txid, msg.tx.txid)

        watcher.stop()
        client.stop()
    }

    @Test
    fun `watch for spent transactions before client is connected`() = runSuspendTest {
        val client = ElectrumClient(TcpSocket.Builder(), this, LoggerFactory.default)
        val watcher = ElectrumWatcher(client, this, LoggerFactory.default)

        val (address, privateKey) = bitcoincli.getNewAddress()
        val tx = bitcoincli.sendToAddress(address, 1.0)

        // find the output for the address we generated and create a tx that spends it
        val pos = tx.txOut.indexOfFirst {
            it.publicKeyScript == Script.write(Script.pay2wpkh(privateKey.publicKey())).byteVector()
        }
        assertTrue(pos != -1)

        val tmp = Transaction(
            version = 2,
            txIn = listOf(TxIn(OutPoint(tx, pos.toLong()), signatureScript = emptyList(), sequence = TxIn.SEQUENCE_FINAL)),
            txOut = listOf(TxOut(tx.txOut[pos].amount - 1000.sat, publicKeyScript = Script.pay2wpkh(privateKey.publicKey()))),
            lockTime = 0
        )

        val sig = Transaction.signInput(
            tmp,
            0,
            Script.pay2pkh(privateKey.publicKey()),
            SIGHASH_ALL,
            tx.txOut[pos].amount,
            SigVersion.SIGVERSION_WITNESS_V0,
            privateKey
        ).byteVector()

        val spendingTx = tmp.updateWitness(0, ScriptWitness(listOf(sig, privateKey.publicKey().value)))
        Transaction.correctlySpends(spendingTx, listOf(tx), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)

        val listener = watcher.openWatchNotificationsFlow()
        watcher.watch(
            WatchSpent(
                ByteVector32.Zeroes,
                tx.txid,
                pos,
                tx.txOut[pos].publicKeyScript,
                BITCOIN_FUNDING_SPENT
            )
        )

        // send raw tx
        val sentTx = bitcoincli.sendRawTransaction(spendingTx)
        assertEquals(spendingTx, sentTx)
        bitcoincli.generateBlocks(2)

        client.connect(ServerAddress("localhost", 51001, TcpSocket.TLS.DISABLED))

        val msg = listener.first() as WatchEventSpent
        assertEquals(spendingTx.txid, msg.tx.txid)

        val watcherState = watcher.pstate as WatcherRunning
        assertTrue(watcherState.scriptHashSubscriptions.isNotEmpty())

        watcher.stop()
        client.stop()
    }

    @Test
    fun `watch for spent transactions while being offline`() = runSuspendTest {
        val client = ElectrumClient(TcpSocket.Builder(), this, LoggerFactory.default).apply { connect(ServerAddress("localhost", 51001, TcpSocket.TLS.DISABLED)) }
        val watcher = ElectrumWatcher(client, this, LoggerFactory.default)

        val (address, privateKey) = bitcoincli.getNewAddress()
        val tx = bitcoincli.sendToAddress(address, 1.0)

        // find the output for the address we generated and create a tx that spends it
        val pos = tx.txOut.indexOfFirst {
            it.publicKeyScript == Script.write(Script.pay2wpkh(privateKey.publicKey())).byteVector()
        }
        assertTrue(pos != -1)

        val spendingTx = kotlin.run {
            val tmp = Transaction(
                version = 2,
                txIn = listOf(TxIn(OutPoint(tx, pos.toLong()), signatureScript = emptyList(), sequence = TxIn.SEQUENCE_FINAL)),
                txOut = listOf(TxOut(tx.txOut[pos].amount - 1000.sat, publicKeyScript = Script.pay2wpkh(privateKey.publicKey()))),
                lockTime = 0
            )

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
        val sentTx = bitcoincli.sendRawTransaction(spendingTx)
        assertEquals(spendingTx, sentTx)
        bitcoincli.generateBlocks(2)

        val listener = watcher.openWatchNotificationsFlow()
        watcher.watch(
            WatchSpent(
                ByteVector32.Zeroes,
                tx.txid,
                pos,
                tx.txOut[pos].publicKeyScript,
                BITCOIN_FUNDING_SPENT
            )
        )

        val msg = listener.first() as WatchEventSpent
        assertEquals(spendingTx.txid, msg.tx.txid)

        watcher.stop()
        client.stop()
    }

    @Test
    fun `publish transactions with relative and absolute delays`() = runSuspendTest(timeout = 2.minutes) {
        val client = ElectrumClient(TcpSocket.Builder(), this, LoggerFactory.default).apply { connect(ServerAddress("localhost", 51001, TcpSocket.TLS.DISABLED)) }
        val watcher = ElectrumWatcher(client, this, LoggerFactory.default)
        val watcherNotifications = watcher.openWatchNotificationsFlow()

        suspend fun awaitForBlockCount(height: Int) {
            do {
                val count = bitcoincli.getBlockCount()
                delay(1.seconds)
            } while (count < height)
        }

        suspend fun checkIfExistsInMempool(tx: Transaction) {
            do {
                val mempool = bitcoincli.getRawMempool()
                if (mempool.isNotEmpty()) {
                    assertEquals(1, mempool.size)
                    assertEquals(tx.txid.toHex(), mempool.first())
                }
                delay(1.seconds)
            } while (mempool.isEmpty())
        }

        suspend fun checkIfMempoolIsEmpty() {
            val mempool = bitcoincli.getRawMempool()
            assertTrue { mempool.isEmpty() }
        }

        val initialBlockCount = bitcoincli.getBlockCount() // 150
        awaitForBlockCount(0)

        val (_, privateKey) = bitcoincli.getNewAddress()

        // tx1 has an absolute delay but no relative delay
        val fundTx = bitcoincli.fundTransaction(
            Transaction(
                version = 2,
                txIn = listOf(),
                txOut = listOf(TxOut(150000.sat, Script.pay2wpkh(privateKey.publicKey()))),
                lockTime = (initialBlockCount + 5).toLong()
            ), true, 250.sat
        )
        val tx1 = bitcoincli.signTransaction(fundTx)

        watcher.publish(tx1)

        bitcoincli.generateBlocks(4) // 154
        bitcoincli.getBlockCount()

        awaitForBlockCount(initialBlockCount + 4)
        checkIfMempoolIsEmpty()

        bitcoincli.generateBlocks(1) // 155
        bitcoincli.getBlockCount()
        checkIfExistsInMempool(tx1)

        // tx2 has a relative delay but no absolute delay
        val tx2 = bitcoincli.createSpendP2WPKH(
            parentTx = tx1,
            privateKey = privateKey,
            to = privateKey.publicKey(),
            fee = 10000.sat,
            sequence = 2,
            lockTime = 0
        )

        watcher.watch(WatchConfirmed(ByteVector32.Zeroes, tx1, 1, BITCOIN_FUNDING_DEPTHOK))
        watcher.publish(tx2)

        bitcoincli.generateBlocks(1) // 156
        bitcoincli.getBlockCount()

        val watchEvent1 = watcherNotifications.first()
        assertTrue(watchEvent1 is WatchEventConfirmed)
        assertEquals(tx1.txid, watchEvent1.tx.txid)

        bitcoincli.generateBlocks(2) // 158
        bitcoincli.getBlockCount()
        checkIfExistsInMempool(tx2)

        // tx3 has both relative and absolute delays
        val tx3 = bitcoincli.createSpendP2WPKH(
            parentTx = tx2,
            privateKey = privateKey,
            to = privateKey.publicKey(),
            10000.sat,
            sequence = 1,
            lockTime = (bitcoincli.getBlockCount() + 5).toLong()
        )

        watcher.watch(WatchConfirmed(ByteVector32.Zeroes, tx2, 1, BITCOIN_FUNDING_DEPTHOK))
        watcher.watch(WatchSpent(ByteVector32.Zeroes, tx2, 0, BITCOIN_FUNDING_SPENT))
        watcher.publish(tx3)

        bitcoincli.generateBlocks(1) // 159
        bitcoincli.getBlockCount()

        val watchEvent2 = watcherNotifications.first()
        assertTrue(watchEvent2 is WatchEventConfirmed)
        assertEquals(tx2.txid, watchEvent2.tx.txid)

        // after 1 block, the relative delay is elapsed, but not the absolute delay
        val currentBlock = bitcoincli.getBlockCount()
        bitcoincli.generateBlocks(1) // 160
        bitcoincli.getBlockCount()

        awaitForBlockCount(currentBlock + 1)
        checkIfMempoolIsEmpty()

        bitcoincli.generateBlocks(3) // 163
        bitcoincli.getBlockCount()

        val watchEvent3 = watcherNotifications.first()
        assertTrue(watchEvent3 is WatchEventSpent)
        assertEquals(tx3.txid, watchEvent3.tx.txid)
        checkIfExistsInMempool(tx3)

        watcher.stop()
        client.stop()
    }

    @Test
    fun `get transaction`() = runSuspendTest(timeout = 50.seconds) {
        // Run on a production server
        val electrumClient = ElectrumClient(TcpSocket.Builder(), this, LoggerFactory.default).apply { connect(ServerAddress("electrum.acinq.co", 50002, TcpSocket.TLS.UNSAFE_CERTIFICATES)) }
        val electrumWatcher = ElectrumWatcher(electrumClient, this, LoggerFactory.default)

        delay(1_000) // Wait for the electrum client to be ready

        // tx is in the blockchain
        val txid1 = ByteVector32(Hex.decode("c0b18008713360d7c30dae0940d88152a4bbb10faef5a69fefca5f7a7e1a06cc"))
        val txNotification = electrumWatcher.openTxNotificationsFlow()
        electrumWatcher.send(GetTxWithMetaEvent(GetTxWithMeta(ByteVector32.Zeroes, txid1)))
        val res1 = txNotification.first().second
        assertEquals(res1.txid, txid1)
        assertEquals(
            res1.tx_opt,
            Transaction.read("0100000001b5cbd7615a7494f60304695c180eb255113bd5effcf54aec6c7dfbca67f533a1010000006a473044022042115a5d1a489bbc9bd4348521b098025625c9b6c6474f84b96b11301da17a0602203ccb684b1d133ff87265a6017ef0fdd2d22dd6eef0725c57826f8aaadcc16d9d012103629aa3df53cad290078bbad26491f1e11f9c01697c65db0967561f6f142c993cffffffff02801015000000000017a914b8984d6344eed24689cdbc77adaf73c66c4fdd688734e9e818000000001976a91404607585722760691867b42d43701905736be47d88ac00000000")
        )
        assertTrue(res1.lastBlockTimestamp > currentTimestampSeconds() - 7200) // this server should be in sync

        // tx doesn't exist
        val txid2 = ByteVector32(Hex.decode("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"))
        electrumWatcher.send(GetTxWithMetaEvent(GetTxWithMeta(ByteVector32.Zeroes, txid2)))
        val res2 = txNotification.first().second
        assertEquals(res2.txid, txid2)
        assertNull(res2.tx_opt)
        assertTrue(res2.lastBlockTimestamp > currentTimestampSeconds() - 7200) // this server should be in sync

        electrumWatcher.stop()
        electrumClient.stop()
    }
}
