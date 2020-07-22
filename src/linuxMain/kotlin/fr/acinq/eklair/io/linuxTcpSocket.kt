package fr.acinq.eklair.io

internal actual object PlatformSocketBuilder : TcpSocket.Builder {
    override suspend fun connect(host: String, port: Int, tls: Boolean): TcpSocket =
        error("Not implemented")
}
