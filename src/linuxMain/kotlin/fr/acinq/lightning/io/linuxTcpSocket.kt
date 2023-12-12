package fr.acinq.lightning.io

import co.touchlab.kermit.Logger

internal actual object PlatformSocketBuilder : TcpSocket.Builder {
    override suspend fun connect(host: String, port: Int, tls: TcpSocket.TLS, loggerFactory: Logger): TcpSocket =
        error("Not implemented")
}
