package fr.acinq.lightning.io

import fr.acinq.lightning.io.rustls.KtorTlsTcpSocket
import fr.acinq.lightning.io.rustls.RustlsClientConfig
import fr.acinq.lightning.logging.LoggerFactory
import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.Socket
import io.ktor.network.sockets.aSocket
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import kotlin.io.encoding.Base64
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalForeignApi::class)
internal actual object PlatformSocketBuilder : TcpSocket.Builder {

    private val Selector = SelectorManager(Dispatchers.IO)

    actual override suspend fun connect(host: String, port: Int, tls: TcpSocket.TLS, loggerFactory: LoggerFactory): TcpSocket {
        return withContext(Dispatchers.IO) {
            var socket: Socket? = null
            try {
                socket = aSocket(Selector).tcp().connect(
                    host, port,
                    configure = {
                        keepAlive = true
                        socketTimeout = 15.seconds.inWholeMilliseconds
                        noDelay = true
                    })
                when (tls) {
                    is TcpSocket.TLS.DISABLED -> KtorNoTlsTcpSocket(socket)
                    is TcpSocket.TLS.ENABLED -> {
                        val config = when (tls) {
                            is TcpSocket.TLS.TRUSTED_CERTIFICATES -> RustlsClientConfig.build(caBundlePath = null, dangerousSkipCertVerification = false)
                            is TcpSocket.TLS.UNSAFE_CERTIFICATES -> RustlsClientConfig.build(caBundlePath = null, dangerousSkipCertVerification = true)
                            is TcpSocket.TLS.PINNED_PUBLIC_KEY -> RustlsClientConfig.build(pinnedPublicKey = decodePinnedPublicKey(tls.pubKey))
                        }
                        val conn = config.newConnection(host)
                        val tls = KtorTlsTcpSocket(conn, socket)
                        tls.handshake()
                        tls
                    }
                }
            } catch (e: Exception) {
                socket?.dispose()
                throw e
            }
        }
    }

    /**
     * Decode the [TcpSocket.TLS.PINNED_PUBLIC_KEY] `pubKey` (base64 of a DER-encoded
     * SubjectPublicKeyInfo, i.e. PEM body without the BEGIN/END armor) into raw DER bytes.
     * Whitespace (including embedded line breaks) is tolerated.
     */
    private fun decodePinnedPublicKey(pubKey: String): ByteArray = Base64.decode(pubKey.filterNot { it.isWhitespace() })
}