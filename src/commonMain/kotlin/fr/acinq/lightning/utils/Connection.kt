package fr.acinq.lightning.utils

import fr.acinq.lightning.io.TcpSocket

sealed class Connection {
    data class CLOSED(val reason: TcpSocket.IOException?): Connection()
    data object ESTABLISHING: Connection()
    data object ESTABLISHED: Connection()
}

data class ServerAddress(val host: String, val port: Int, val tls: TcpSocket.TLS)
