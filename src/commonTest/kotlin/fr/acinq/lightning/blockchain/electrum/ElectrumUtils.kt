package fr.acinq.lightning.blockchain.electrum

import fr.acinq.lightning.io.TcpSocket
import fr.acinq.lightning.utils.Connection
import fr.acinq.lightning.utils.ServerAddress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import org.kodein.log.LoggerFactory

suspend fun connectToElectrumServer(scope: CoroutineScope, addr: ServerAddress): ElectrumClient {
    val client = ElectrumClient(TcpSocket.Builder(), scope, LoggerFactory.default).apply { connect(addr) }

    client.connectionState.first { it is Connection.CLOSED }
    client.connectionState.first { it is Connection.ESTABLISHING }
    client.connectionState.first { it is Connection.ESTABLISHED }

    return client
}

suspend fun CoroutineScope.connectToTestnetServer(): ElectrumClient =
    connectToElectrumServer(this, ServerAddress("testnet1.electrum.acinq.co", 51002, TcpSocket.TLS.UNSAFE_CERTIFICATES))

suspend fun CoroutineScope.connectToMainnetServer(): ElectrumClient =
    connectToElectrumServer(this, ServerAddress("electrum.acinq.co", 50002, TcpSocket.TLS.UNSAFE_CERTIFICATES))