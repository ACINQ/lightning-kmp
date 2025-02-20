package fr.acinq.lightning.io

import co.touchlab.kermit.Logger
import fr.acinq.lightning.logging.*
import io.ktor.network.selector.*
import io.ktor.network.sockets.*
import io.ktor.network.tls.*
import io.ktor.utils.io.*
import kotlinx.coroutines.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.channels.ClosedSendChannelException
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
            ensureActive()
            try {
                if (bytes != null) connection.output.writeFully(bytes, offset, length)
                if (flush) connection.output.flush()
            } catch (ex: ClosedSendChannelException) {
                throw TcpSocket.IOException.ConnectionClosed(ex)
            } catch (ex: java.io.IOException) {
                throw TcpSocket.IOException.ConnectionClosed(ex)
            } catch (ex: CancellationException) {
                throw ex
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
        } catch (ex: CancellationException) {
            throw ex
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

    override fun close() {
        // NB: this safely calls close(), wrapping it into a try/catch.
        socket.dispose()
    }

    companion object {

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

        private fun tlsConfigForPinnedCert(pinnedPubkey: String, logger: Logger): TLSConfig = TLSConfigBuilder().apply {
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

        private fun tlsConfigForUnsafeCertificates(): TLSConfig = TLSConfigBuilder().apply {
            trustManager = object : X509TrustManager {
                override fun checkClientTrusted(p0: Array<out X509Certificate>?, p1: String?) {}
                override fun checkServerTrusted(p0: Array<out X509Certificate>?, p1: String?) {}
                override fun getAcceptedIssuers(): Array<X509Certificate>? = null
            }
        }.build()

        fun buildTlsConfigFor(host: String, tls: TcpSocket.TLS.ENABLED, logger: Logger) = when (tls) {
            is TcpSocket.TLS.TRUSTED_CERTIFICATES -> TLSConfigBuilder().build()
            is TcpSocket.TLS.PINNED_PUBLIC_KEY -> {
                logger.warning { "using unsafe TLS for connection with $host" }
                tlsConfigForPinnedCert(tls.pubKey, logger)
            }
            is TcpSocket.TLS.UNSAFE_CERTIFICATES -> {
                logger.info { "using certificate pinning for connections with $host" }
                tlsConfigForUnsafeCertificates()
            }
        }

    }
}

internal actual object PlatformSocketBuilder : TcpSocket.Builder {
    actual override suspend fun connect(host: String, port: Int, tls: TcpSocket.TLS, loggerFactory: LoggerFactory): TcpSocket {
        val logger = loggerFactory.newLogger(this::class)
        return withContext(Dispatchers.IO) {
            var socket: Socket? = null
            try {
                socket = aSocket(SelectorManager(Dispatchers.IO)).tcp().connect(host, port)
                    .let {
                        when (tls) {
                            is TcpSocket.TLS.DISABLED -> it
                            is TcpSocket.TLS.ENABLED -> it.tls(tlsContext(logger), JvmTcpSocket.buildTlsConfigFor(host, tls, logger))
                        }
                    }
                JvmTcpSocket(socket)
            } catch (e: Exception) {
                socket?.dispose()
                throw when (e) {
                    is ConnectException -> TcpSocket.IOException.ConnectionRefused(e)
                    is SocketException -> TcpSocket.IOException.Unknown(e.message, e)
                    else -> e
                }
            }
        }
    }
}

/**
 * The TLS internal coroutines are launched in a background scope that doesn't let us do fine-grained supervision.
 * They may throw exceptions when the socket is remotely closed, which crashes the application on Android.
 * This should be fixed by https://github.com/ktorio/ktor/pull/3690, but for now we need to explicitly handle exceptions.
 */
fun tlsContext(logger: Logger) = Dispatchers.IO + CoroutineExceptionHandler { _, throwable -> logger.error(throwable) { "TLS socket error: " } }