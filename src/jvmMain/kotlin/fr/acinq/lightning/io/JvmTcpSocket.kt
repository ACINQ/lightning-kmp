package fr.acinq.lightning.io

import fr.acinq.lightning.utils.lightningLogger
import io.ktor.network.selector.*
import io.ktor.network.sockets.*
import io.ktor.network.tls.*
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.withContext
import java.net.ConnectException
import java.net.SocketException
import java.security.KeyFactory
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.security.spec.X509EncodedKeySpec
import java.util.*
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager


class JvmTcpSocket(val socket: Socket) : TcpSocket {
    private val connection = socket.connection()

    override suspend fun send(bytes: ByteArray?, offset: Int, length: Int, flush: Boolean) =
        withContext(Dispatchers.IO) {
            try {
                if (bytes != null) connection.output.writeFully(bytes, offset, length)
                if (flush) connection.output.flush()
            } catch (ex: java.io.IOException) {
                throw TcpSocket.IOException.ConnectionClosed(ex)
            } catch (ex: Throwable) {
                throw TcpSocket.IOException.Unknown(ex.message, ex)
            }
        }

    private inline fun <R> tryReceive(receive: () -> R): R {
        try {
            return receive()
        } catch (ex: ClosedReceiveChannelException) {
            throw TcpSocket.IOException.ConnectionClosed(ex)
        } catch (ex: SocketException) {
            throw TcpSocket.IOException.ConnectionClosed(ex)
        } catch (ex: Throwable) {
            throw TcpSocket.IOException.Unknown(ex.message, ex)
        }
    }

    private suspend fun <R> receive(read: suspend () -> R): R =
        withContext(Dispatchers.IO) {
            tryReceive { read() }
        }

    override suspend fun receiveFully(buffer: ByteArray, offset: Int, length: Int): Unit {
        receive { connection.input.readFully(buffer, offset, length) }
    }

    override suspend fun receiveAvailable(buffer: ByteArray, offset: Int, length: Int): Int {
        return tryReceive { connection.input.readAvailable(buffer, offset, length) }
            .takeUnless { it == -1 } ?: throw TcpSocket.IOException.ConnectionClosed()
    }

    override suspend fun startTls(tls: TcpSocket.TLS): TcpSocket = try {
        JvmTcpSocket(when (tls) {
            TcpSocket.TLS.TRUSTED_CERTIFICATES -> connection.tls(Dispatchers.IO)
            TcpSocket.TLS.UNSAFE_CERTIFICATES -> connection.tls(Dispatchers.IO) {
                logger.warning { "using unsafe TLS!" }
                trustManager = unsafeX509TrustManager()
            }
            is TcpSocket.TLS.PINNED_PUBLIC_KEY -> {
                connection.tls(Dispatchers.IO, tlsConfigForPinnedCert(tls.pubKey))
            }
            else -> socket
        })
    } catch (e: Exception) {
        throw when (e) {
            is ConnectException -> TcpSocket.IOException.ConnectionRefused()
            is SocketException -> TcpSocket.IOException.Unknown(e.message)
            else -> e
        }
    }

    override fun close() {
        socket.close()
    }

    companion object {
        private val logger by lightningLogger<JvmTcpSocket>()

        fun unsafeX509TrustManager() = object : X509TrustManager {
            override fun checkClientTrusted(p0: Array<out X509Certificate>?, p1: String?) {}
            override fun checkServerTrusted(p0: Array<out X509Certificate>?, p1: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate>? = null
        }

        fun buildPublicKey(encodedKey: ByteArray): java.security.PublicKey {
            val spec = X509EncodedKeySpec(encodedKey)
            val algorithms = listOf("RSA", "EC", "DiffieHellman", "DSA", "RSASSA-PSS", "XDH", "X25519", "X448")
            algorithms.map {
                try {
                    return KeyFactory.getInstance(it).generatePublic(spec)
                } catch (e: Exception) {
                    logger.debug { "key does not use $it algorithm" }
                }
            }
            throw IllegalArgumentException("unsupported key's algorithm, only $algorithms")
        }

        fun tlsConfigForPinnedCert(pinnedPubkey: String): TLSConfig = TLSConfigBuilder().apply {
            // build a default X509 trust manager.
            val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())!!
            factory.init(null as KeyStore?)
            val defaultX509TrustManager = factory.trustManagers!!.filterIsInstance<X509TrustManager>().first()

            // create a new trust manager that always accepts certificates for the pinned public key, or falls back to standard procedure.
            trustManager = object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                    defaultX509TrustManager.checkClientTrusted(chain, authType)
                }

                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                    val serverKey = try {
                        buildPublicKey(chain?.asList()?.firstOrNull()?.publicKey?.encoded ?: throw RuntimeException("certificate missing"))
                    } catch (e: Exception) {
                        logger.error(e) { "failed to read server's pubkey=${pinnedPubkey}" }
                        throw e
                    }

                    val pinnedKey = try {
                        buildPublicKey(Base64.getDecoder().decode(pinnedPubkey))
                    } catch (e: Exception) {
                        logger.error(e) { "failed to read pinned pubkey=${pinnedPubkey}" }
                        throw e
                    }

                    if (serverKey == pinnedKey) {
                        logger.info { "successfully checked server's certificate against pinned pubkey" }
                    } else {
                        logger.warning { "server's certificate does not match pinned pubkey, fallback to default check" }
                        throw TcpSocket.IOException.ConnectionClosed(CertificateException("certificate does not match pinned key"))
                    }
                }

                override fun getAcceptedIssuers(): Array<X509Certificate> = defaultX509TrustManager.acceptedIssuers
            }
        }.build()
    }
}

internal actual object PlatformSocketBuilder : TcpSocket.Builder {

    private val selectorManager = ActorSelectorManager(Dispatchers.IO)
    private val logger by lightningLogger<PlatformSocketBuilder>()

    override suspend fun connect(host: String, port: Int, tls: TcpSocket.TLS): TcpSocket {
        return withContext(Dispatchers.IO) {
            try {
                val socket = aSocket(selectorManager).tcp().connect(host, port).let { socket ->
                    when (tls) {
                        TcpSocket.TLS.TRUSTED_CERTIFICATES -> socket.tls(Dispatchers.IO)
                        TcpSocket.TLS.UNSAFE_CERTIFICATES -> socket.tls(Dispatchers.IO) {
                            logger.warning { "using unsafe TLS!" }
                            trustManager = JvmTcpSocket.unsafeX509TrustManager()
                        }
                        is TcpSocket.TLS.PINNED_PUBLIC_KEY -> {
                            logger.info { "using certificate pinning for connections with $host" }
                            socket.tls(Dispatchers.IO, JvmTcpSocket.tlsConfigForPinnedCert(tls.pubKey))
                        }
                        else -> socket
                    }
                }
                JvmTcpSocket(socket)
            } catch (e: Exception) {
                throw when (e) {
                    is ConnectException -> TcpSocket.IOException.ConnectionRefused()
                    is SocketException -> TcpSocket.IOException.Unknown(e.message)
                    else -> e
                }
            }
        }
    }
}
