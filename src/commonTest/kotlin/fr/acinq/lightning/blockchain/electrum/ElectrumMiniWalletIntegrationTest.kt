package fr.acinq.lightning.blockchain.electrum

import fr.acinq.bitcoin.Block
import fr.acinq.lightning.io.TcpSocket
import fr.acinq.lightning.tests.utils.LightningTestSuite
import fr.acinq.lightning.tests.utils.runSuspendTest
import fr.acinq.lightning.utils.Connection
import fr.acinq.lightning.utils.ServerAddress
import fr.acinq.lightning.utils.sat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlin.test.Test
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
    fun `query address with no utxos`() = runSuspendTest(timeout = Duration.seconds(15)) {
        val client = connectToMainnetServer()
        val wallet = ElectrumMiniWallet("bc1qyjmhaptq78vh5j7tnzu7ujayd8sftjahphxppz", Block.LivenetGenesisBlock.hash, client, this)

        val walletState = wallet.walletStateFlow.filterIsInstance<WalletState>().first()
        assertEquals(0, walletState.utxos.size)
        assertEquals(0.sat, walletState.balance)

        client.stop()
    }

    @Test
    fun `query address with existing utxos`() = runSuspendTest(timeout = Duration.seconds(15)) {
        val client = connectToMainnetServer()
        val wallet = ElectrumMiniWallet("14xb2HATmkBzrHf4CR2hZczEtjYpTh92d2", Block.LivenetGenesisBlock.hash, client, this)

        val walletState = wallet.walletStateFlow.filterIsInstance<WalletState>().first()
        assertEquals(6, walletState.utxos.size)
        assertEquals(30_000_000.sat, walletState.balance)

        client.stop()
    }
}
