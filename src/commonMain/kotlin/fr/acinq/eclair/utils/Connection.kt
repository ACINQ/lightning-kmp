package fr.acinq.eclair.utils

import fr.acinq.eclair.io.TcpSocket

enum class Connection { CLOSED, ESTABLISHING, ESTABLISHED }
data class ServerAddress(val host: String, val port: Int, val tls: TcpSocket.TLS? = null)
