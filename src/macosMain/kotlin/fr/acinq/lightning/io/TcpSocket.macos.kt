package fr.acinq.lightning.io

import fr.acinq.lightning.logging.LoggerFactory

internal actual object PlatformSocketBuilder : TcpSocket.Builder {
    override suspend fun connect(host: String, port: Int, tls: TcpSocket.TLS, loggerFactory: LoggerFactory): TcpSocket =
        error("Not implemented")
}