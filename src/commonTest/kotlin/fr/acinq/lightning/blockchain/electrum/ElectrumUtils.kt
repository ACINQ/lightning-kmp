package fr.acinq.lightning.blockchain.electrum

import fr.acinq.lightning.io.TcpSocket
import fr.acinq.lightning.tests.utils.testLoggerFactory
import fr.acinq.lightning.utils.ServerAddress
import kotlinx.coroutines.CoroutineScope

val ElectrumTestnetServerAddress = ServerAddress("testnet1.electrum.acinq.co", 51002, TcpSocket.TLS.UNSAFE_CERTIFICATES)
val ElectrumMainnetServerAddress = ServerAddress("electrum.acinq.co", 50002, TcpSocket.TLS.UNSAFE_CERTIFICATES)

suspend fun connectToElectrumServer(scope: CoroutineScope, addr: ServerAddress): ElectrumClient =
    ElectrumClient(scope, testLoggerFactory).apply { connect(addr, TcpSocket.Builder()) }

suspend fun CoroutineScope.connectToTestnetServer(): ElectrumClient = connectToElectrumServer(this, ElectrumTestnetServerAddress)

suspend fun CoroutineScope.connectToMainnetServer(): ElectrumClient = connectToElectrumServer(this, ElectrumMainnetServerAddress)
