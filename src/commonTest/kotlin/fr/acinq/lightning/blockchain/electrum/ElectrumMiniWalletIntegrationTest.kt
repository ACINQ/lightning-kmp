package fr.acinq.lightning.blockchain.electrum

import fr.acinq.bitcoin.Bitcoin
import fr.acinq.bitcoin.Block
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.Transaction
import fr.acinq.lightning.Lightning.randomKey
import fr.acinq.lightning.io.TcpSocket
import fr.acinq.lightning.tests.utils.LightningTestSuite
import fr.acinq.lightning.tests.utils.runSuspendTest
import fr.acinq.lightning.utils.Connection
import fr.acinq.lightning.utils.ServerAddress
import fr.acinq.lightning.utils.sat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.time.Duration
import kotlin.time.ExperimentalTime

@OptIn(ExperimentalTime::class)
class ElectrumMiniWalletIntegrationTest : LightningTestSuite() {

    private suspend fun CoroutineScope.connectToMainnetServer(): ElectrumClient {
        val client =
            ElectrumClient(TcpSocket.Builder(), this).apply { connect(ServerAddress("electrum.acinq.co", 50002, TcpSocket.TLS.UNSAFE_CERTIFICATES)) }

        client.connectionState.first { it is Connection.CLOSED }
        client.connectionState.first { it is Connection.ESTABLISHING }
        client.connectionState.first { it is Connection.ESTABLISHED }

        return client
    }

    @Test
    fun `connect to an electrumx mainnet server`() = runSuspendTest(timeout = Duration.seconds(15)) { connectToMainnetServer().stop() }

    @Test
    fun `single address with no utxos`() = runSuspendTest(timeout = Duration.seconds(15)) {
        val client = connectToMainnetServer()
        val wallet = ElectrumMiniWallet(Block.LivenetGenesisBlock.hash, client, this)
        wallet.addWatchOnlyAddress("bc1qyjmhaptq78vh5j7tnzu7ujayd8sftjahphxppz")

        val walletState = wallet.walletStateFlow
            .filter { it.addresses.size == 1 }
            .first()

        assertEquals(0, walletState.utxos.size)
        assertEquals(0.sat, walletState.balance)

        client.stop()
    }

    @Test
    fun `single address with existing utxos`() = runSuspendTest(timeout = Duration.seconds(15)) {
        val client = connectToMainnetServer()
        val wallet = ElectrumMiniWallet(Block.LivenetGenesisBlock.hash, client, this)
        wallet.addWatchOnlyAddress("14xb2HATmkBzrHf4CR2hZczEtjYpTh92d2")

        val walletState = wallet.walletStateFlow
            .filter { it.addresses.size == 1 }
            .first()

        assertEquals(6, walletState.utxos.size)
        assertEquals(30_000_000.sat, walletState.balance)

        client.stop()
    }

    @Test
    fun `multiple addresses`() = runSuspendTest(timeout = Duration.seconds(15)) {
        val client = connectToMainnetServer()
        val wallet = ElectrumMiniWallet(Block.LivenetGenesisBlock.hash, client, this)
        wallet.addAddress("16MmJT8VqW465GEyckWae547jKVfMB14P8")
        wallet.addAddress("14xb2HATmkBzrHf4CR2hZczEtjYpTh92d2", randomKey())
        wallet.addAddress("1NHFyu1uJ1UoDjtPjqZ4Et3wNCyMGCJ1qV", randomKey())

        val walletState = wallet.walletStateFlow
            .filter { it.parentTxs.size == 11 }
            .first()

        // this has been checked on the blockchain
        assertEquals(4 + 6 + 1, walletState.utxos.size)
        assertEquals(72_000_000.sat + 30_000_000.sat + 2_000_000.sat, walletState.balance)
        assertEquals(7, walletState.spendable().size)
        // make sure txid is correct has electrum api is confusing
        walletState.parentTxs.forEach { assertEquals(it.key, it.value.txid) }
        assertContains(
            walletState.spendable(),
            WalletState.Utxo(
                previousTx = Transaction.read("0100000001758713310361270b5ec4cae9b0196cb84fdb2f174d29f9367ad341963fa83e56010000008b483045022100d7b8759aeffe9d829a5df062420eb25017d7341244e49cfede16136a0c0b8dd2022031b42048e66b1f82f7fa99a22954e2709269838ef587c20118e493ced0d63e21014104b9251638d1475b9c62e1cf03129c835bcd5ab843aa0016412e8b39e3f8f7188d3b59023bce2002a2e409ea070c7070392b65d9ae8c8631ae2672a8fbb4f62bbdffffffff02404b4c00000000001976a9143675767783fdf1922f57ab4bb783f3a88dfa609488ac404b4c00000000001976a9142b6ba7c9d796b75eef7942fc9288edd37c32f5c388ac00000000"),
                outputIndex = 1,
                blockHeight = 100_003
            )
        )

        assertEquals(
            expected = setOf(
                Triple("14xb2HATmkBzrHf4CR2hZczEtjYpTh92d2", ByteVector32.fromValidHex("71b3dbaca67e9f9189dad3617138c19725ab541ef0b49c05a94913e9f28e3f4e") to 0, 5_000_000.sat),
                Triple("14xb2HATmkBzrHf4CR2hZczEtjYpTh92d2", ByteVector32.fromValidHex("21d2eb195736af2a40d42107e6abd59c97eb6cffd4a5a7a7709e86590ae61987") to 0, 5_000_000.sat),
                Triple("14xb2HATmkBzrHf4CR2hZczEtjYpTh92d2", ByteVector32.fromValidHex("74d681e0e03bafa802c8aa084379aa98d9fcd632ddc2ed9782b586ec87451f20") to 1, 5_000_000.sat),
                Triple("14xb2HATmkBzrHf4CR2hZczEtjYpTh92d2", ByteVector32.fromValidHex("563ea83f9641d37a36f9294d172fdb4fb86c19b0e9cac45e0b27610331138775") to 0, 5_000_000.sat),
                Triple("14xb2HATmkBzrHf4CR2hZczEtjYpTh92d2", ByteVector32.fromValidHex("971af80218684017722429be08548d1f30a2f1f220abc064380cbca5cabf7623") to 1, 5_000_000.sat),
                Triple("14xb2HATmkBzrHf4CR2hZczEtjYpTh92d2", ByteVector32.fromValidHex("b1ec9c44009147f3cee26caba45abec2610c74df9751fad14074119b5314da21") to 0, 5_000_000.sat),
                Triple("1NHFyu1uJ1UoDjtPjqZ4Et3wNCyMGCJ1qV", ByteVector32.fromValidHex("602839d82ac6c9aafd1a20fff5b23e11a99271e7cc238d2e48b352219b2b87ab") to 1, 2_000_000.sat),
            ),
            actual = walletState.spendable().map {
                val txOut = it.previousTx.txOut[it.outputIndex]
                val address = Bitcoin.addressFromPublicKeyScript(Block.LivenetGenesisBlock.hash, txOut.publicKeyScript.toByteArray())
                Triple(address, it.previousTx.txid to it.outputIndex, txOut.amount)
            }.toSet()
        )

        client.stop()
    }
}
