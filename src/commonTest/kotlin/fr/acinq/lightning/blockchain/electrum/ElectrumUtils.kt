package fr.acinq.lightning.blockchain.electrum

import fr.acinq.lightning.io.TcpSocket
import fr.acinq.lightning.utils.ServerAddress
import kotlinx.coroutines.CoroutineScope
import org.kodein.log.LoggerFactory

fun CoroutineScope.connectToElectrumServer(addr: ServerAddress): ElectrumClient =
    ElectrumClient(TcpSocket.Builder(), this, LoggerFactory.default).apply { connect(addr) }

fun CoroutineScope.connectToTestnetServer(): ElectrumClient =
    connectToElectrumServer(ServerAddress("testnet1.electrum.acinq.co", 51002, TcpSocket.TLS.UNSAFE_CERTIFICATES))

fun CoroutineScope.connectToMainnetServer(): ElectrumClient =
    connectToElectrumServer(ServerAddress("electrum.acinq.co", 50002, TcpSocket.TLS.UNSAFE_CERTIFICATES))