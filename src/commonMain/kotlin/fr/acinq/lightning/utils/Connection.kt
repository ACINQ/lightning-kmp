package fr.acinq.lightning.utils

import fr.acinq.lightning.io.TcpSocket

enum class Connection { CLOSED, ESTABLISHING, ESTABLISHED }
data class ServerAddress(val host: String, val port: Int, val tls: TcpSocket.TLS? = null)
