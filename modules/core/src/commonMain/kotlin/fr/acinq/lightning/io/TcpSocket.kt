package fr.acinq.lightning.io

import fr.acinq.lightning.io.TcpSocket.TLS
import fr.acinq.lightning.logging.LoggerFactory

interface TcpSocket {

    sealed class IOException(override val message: String, cause: Throwable? = null) : Exception(message, cause) {
        class ConnectionRefused(cause: Throwable? = null) : IOException("Connection refused", cause)
        class ConnectionClosed(cause: Throwable? = null) : IOException("Connection closed", cause)
        class Unknown(message: String?, cause: Throwable? = null) : IOException(message ?: "Unknown", cause)
    }

    suspend fun send(bytes: ByteArray?, offset: Int, length: Int, flush: Boolean = true)

    suspend fun receiveFully(buffer: ByteArray, offset: Int, length: Int)
    suspend fun receiveAvailable(buffer: ByteArray, offset: Int, length: Int): Int

    fun close()

    sealed class TLS {
        /** Used for Lightning connections */
        data object DISABLED : TLS()

        sealed class ENABLED : TLS()

        /** Used for Electrum servers when expecting a valid certificate */
        data class TRUSTED_CERTIFICATES(
            /**
             * Specify an expectedHostName when it's different than the `host` value you used
             * within TcpSocket.Builder.connect(). This may be the case when using Tor.
             */
            val expectedHostName: String? = null
        ) : ENABLED()

        /** Only used in unit tests */
        data object UNSAFE_CERTIFICATES : ENABLED()

        /**
         * Used for Electrum servers when expecting a specific public key
         * (for example self-signed certificates)
         */
        data class PINNED_PUBLIC_KEY(
            /**
             * DER-encoded publicKey as base64 string.
             * (I.e. same as PEM format, without BEGIN/END header/footer)
             */
            val pubKey: String
        ) : ENABLED() {
            override fun toString(): String {
                return "PINNED_PUBLIC_KEY(pubKey=${pubKey.take(64)}...}"
            }
        }
    }

    interface Builder {
        suspend fun connect(host: String, port: Int, tls: TLS, loggerFactory: LoggerFactory): TcpSocket

        companion object {
            operator fun invoke(): Builder = PlatformSocketBuilder
        }
    }
}

suspend fun TcpSocket.send(bytes: ByteArray, flush: Boolean = true) = send(bytes, 0, bytes.size, flush)
suspend fun TcpSocket.receiveFully(buffer: ByteArray) = receiveFully(buffer, 0, buffer.size)
suspend fun TcpSocket.receiveAvailable(buffer: ByteArray) = receiveAvailable(buffer, 0, buffer.size)

internal expect object PlatformSocketBuilder : TcpSocket.Builder {
    override suspend fun connect(host: String, port: Int, tls: TLS, loggerFactory: LoggerFactory): TcpSocket
}

suspend fun TcpSocket.receiveFully(size: Int): ByteArray = ByteArray(size).also { receiveFully(it) }
