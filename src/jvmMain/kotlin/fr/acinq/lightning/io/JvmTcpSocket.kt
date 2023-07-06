package fr.acinq.lightning.io

import io.ktor.network.selector.*
import io.ktor.network.sockets.*
import io.ktor.network.tls.*
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.kodein.log.Logger
import org.kodein.log.LoggerFactory
import org.kodein.log.newLogger
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

class JvmTcpSocket(val socket: Socket, val loggerFactory: LoggerFactory) : TcpSocket {

    private val logger = loggerFactory.newLogger(this::class)

    private val connection = socket.connection()

    override suspend fun send(bytes: ByteArray?, offset: Int, length: Int, flush: Boolean) =
        withContext(Dispatchers.IO) {
            try {
                ensureActive()
                if (bytes != null) connection.output.writeFully(bytes, offset, length)
                if (flush) connection.output.flush()
            } catch (ex: ClosedSendChannelException) {
                throw TcpSocket.IOException.ConnectionClosed(ex)
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
        } catch (ex: java.io.IOException) {
            throw TcpSocket.IOException.ConnectionClosed(ex)
        } catch (ex: Throwable) {
            throw TcpSocket.IOException.Unknown(ex.message, ex)
        }
    }

    private suspend fun <R> receive(read: suspend () -> R): R =
        withContext(Dispatchers.IO) {
            ensureActive()
            tryReceive { read() }
        }

    override suspend fun receiveFully(buffer: ByteArray, offset: Int, length: Int) {
        receive { connection.input.readFully(buffer, offset, length) }
    }

    override suspend fun receiveAvailable(buffer: ByteArray, offset: Int, length: Int): Int {
        return receive { connection.input.readAvailable(buffer, offset, length) }
            .takeUnless { it == -1 } ?: throw TcpSocket.IOException.ConnectionClosed()
    }

    override suspend fun startTls(tls: TcpSocket.TLS): TcpSocket = try {
        when (tls) {
            is TcpSocket.TLS.TRUSTED_CERTIFICATES -> JvmTcpSocket(connection.tls(tlsContext(logger)), loggerFactory)
            TcpSocket.TLS.UNSAFE_CERTIFICATES -> JvmTcpSocket(connection.tls(tlsContext(logger)) {
                logger.warning { "using unsafe TLS!" }
                trustManager = unsafeX509TrustManager()
            }, loggerFactory)
            is TcpSocket.TLS.PINNED_PUBLIC_KEY -> {
                JvmTcpSocket(connection.tls(tlsContext(logger), tlsConfigForPinnedCert(tls.pubKey, logger)), loggerFactory)
            }
            TcpSocket.TLS.DISABLED -> this
        }
    } catch (e: Exception) {
        throw when (e) {
            is ConnectException -> TcpSocket.IOException.ConnectionRefused(e)
            is SocketException -> TcpSocket.IOException.Unknown(e.message, e)
            else -> e
        }
    }

    override fun close() {
        socket.close()
    }

    companion object {

        fun unsafeX509TrustManager() = object : X509TrustManager {
            override fun checkClientTrusted(p0: Array<out X509Certificate>?, p1: String?) {}
            override fun checkServerTrusted(p0: Array<out X509Certificate>?, p1: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate>? = null
        }

        fun buildPublicKey(encodedKey: ByteArray, logger: Logger): java.security.PublicKey {
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

        fun tlsConfigForPinnedCert(pinnedPubkey: String, logger: Logger): TLSConfig = TLSConfigBuilder().apply {
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
                        buildPublicKey(chain?.asList()?.firstOrNull()?.publicKey?.encoded ?: throw RuntimeException("certificate missing"), logger)
                    } catch (e: Exception) {
                        logger.error(e) { "failed to read server's pubkey=${pinnedPubkey}" }
                        throw e
                    }

                    val pinnedKey = try {
                        buildPublicKey(Base64.getDecoder().decode(pinnedPubkey), logger)
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
    override suspend fun connect(host: String, port: Int, tls: TcpSocket.TLS, loggerFactory: LoggerFactory): TcpSocket {
        val logger = loggerFactory.newLogger(this::class)
        return withContext(Dispatchers.IO) {
            try {
                val socket = aSocket(SelectorManager(Dispatchers.IO)).tcp().connect(host, port).let { socket ->
                    when (tls) {
                        is TcpSocket.TLS.TRUSTED_CERTIFICATES -> socket.tls(tlsContext(logger))
                        TcpSocket.TLS.UNSAFE_CERTIFICATES -> socket.tls(tlsContext(logger)) {
                            logger.warning { "using unsafe TLS!" }
                            trustManager = JvmTcpSocket.unsafeX509TrustManager()
                        }
                        is TcpSocket.TLS.PINNED_PUBLIC_KEY -> {
                            logger.info { "using certificate pinning for connections with $host" }
                            socket.tls(tlsContext(logger), JvmTcpSocket.tlsConfigForPinnedCert(tls.pubKey, logger))
                        }
                        else -> socket
                    }
                }
                JvmTcpSocket(socket, loggerFactory)
            } catch (e: Exception) {
                throw when (e) {
                    is ConnectException -> TcpSocket.IOException.ConnectionRefused(e)
                    is SocketException -> TcpSocket.IOException.Unknown(e.message, e)
                    else -> e
                }
            }
        }
    }
}

fun tlsExceptionHandler(logger: Logger) = CoroutineExceptionHandler { _, throwable -> logger.error(throwable) { "TLS socket error: " } }

/**
 * Using the IO dispatcher is a good practice, but the TLS internal coroutines sometimes throw exceptions
 * that are fatal on Android and aren't caught in the parent context, so we catch them explicitly.
 */
fun tlsContext(logger: Logger) = Dispatchers.IO + tlsExceptionHandler(logger)
