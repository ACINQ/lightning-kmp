package fr.acinq.lightning.io

import org.kodein.log.LoggerFactory

internal actual object PlatformSocketBuilder : TcpSocket.Builder {
    override suspend fun connect(host: String, port: Int, tls: TcpSocket.TLS, loggerFactory: LoggerFactory): TcpSocket =
        error("Not implemented")
}
