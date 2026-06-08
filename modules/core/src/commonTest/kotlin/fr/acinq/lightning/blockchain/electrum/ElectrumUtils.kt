package fr.acinq.lightning.blockchain.electrum

import fr.acinq.lightning.io.TcpSocket
import fr.acinq.lightning.tests.utils.testLoggerFactory
import fr.acinq.lightning.utils.ServerAddress
import kotlinx.coroutines.CoroutineScope

val ElectrumTestnet3ServerAddress = ServerAddress("electrum.blockstream.info", 60002, TcpSocket.TLS.UNSAFE_CERTIFICATES)
val ElectrumTestnet4ServerAddress = ServerAddress("188.165.245.48", 50001, TcpSocket.TLS.DISABLED)
val ElectrumMainnetServerAddress = ServerAddress("electrum.acinq.co", 50002, TcpSocket.TLS.UNSAFE_CERTIFICATES)
val ElectrumRegtestServerAddress = ServerAddress("localhost", 50001, TcpSocket.TLS.DISABLED)

suspend fun connectToElectrumServer(scope: CoroutineScope, addr: ServerAddress): ElectrumClient =
    ElectrumClient(scope, testLoggerFactory).apply { connect(addr, TcpSocket.Builder()) }

suspend fun CoroutineScope.connectToTestnet3Server(): ElectrumClient = connectToElectrumServer(this, ElectrumTestnet3ServerAddress)
suspend fun CoroutineScope.connectToTestnet4Server(): ElectrumClient = connectToElectrumServer(this, ElectrumTestnet4ServerAddress)

suspend fun CoroutineScope.connectToMainnetServer(): ElectrumClient = connectToElectrumServer(this, ElectrumMainnetServerAddress)
suspend fun CoroutineScope.connectToRegtestServer(): ElectrumClient = connectToElectrumServer(this, ElectrumRegtestServerAddress)
