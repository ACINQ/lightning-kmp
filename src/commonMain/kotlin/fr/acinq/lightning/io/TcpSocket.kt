package fr.acinq.lightning.io

import fr.acinq.lightning.utils.decodeToString
import fr.acinq.lightning.utils.splitByLines
import fr.acinq.lightning.utils.subArray
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow


@OptIn(ExperimentalStdlibApi::class)
interface TcpSocket {

    sealed class IOException(override val message: String, cause: Throwable? = null) : Exception(message, cause) {
        class ConnectionRefused(cause: Throwable? = null) : IOException("Connection refused", cause)
        class ConnectionClosed(cause: Throwable? = null) : IOException("Connection closed", cause)
        class Unknown(message: String?, cause: Throwable? = null) : IOException(message ?: "Unknown", cause)
    }

    suspend fun send(bytes: ByteArray?, offset: Int, length: Int, flush: Boolean = true)

    suspend fun receiveFully(buffer: ByteArray, offset: Int, length: Int)
    suspend fun receiveAvailable(buffer: ByteArray, offset: Int, length: Int): Int

    suspend fun startTls(tls: TLS): TcpSocket

    fun close()

    sealed class TLS {
        /** Used for Lightning connections */
        object DISABLED : TLS()

        /** Used for Electrum servers when expecting a valid certificate */
        data class TRUSTED_CERTIFICATES(
            /**
             * Specify an expectedHostName when it's different than the `host` value you used
             * within TcpSocket.Builder.connect(). This may be the case when using Tor.
             */
            val expectedHostName: String? = null
        ) : TLS()

        /** Only used in unit tests */
        object UNSAFE_CERTIFICATES : TLS()

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
        ) : TLS()
    }

    interface Builder {
        suspend fun connect(host: String, port: Int, tls: TLS): TcpSocket

        companion object {
            operator fun invoke(): Builder = PlatformSocketBuilder
        }
    }
}

suspend fun TcpSocket.send(bytes: ByteArray, flush: Boolean = true) = send(bytes, 0, bytes.size, flush)
suspend fun TcpSocket.receiveFully(buffer: ByteArray) = receiveFully(buffer, 0, buffer.size)
suspend fun TcpSocket.receiveAvailable(buffer: ByteArray) = receiveAvailable(buffer, 0, buffer.size)

internal expect object PlatformSocketBuilder : TcpSocket.Builder

suspend fun TcpSocket.receiveFully(size: Int): ByteArray =
    ByteArray(size).also { receiveFully(it) }

fun TcpSocket.linesFlow(): Flow<String> =
    flow {
        val buffer = ByteArray(8192)
        while (true) {
            val size = receiveAvailable(buffer)
            emit(buffer.subArray(size))
        }
    }
        .decodeToString()
        .splitByLines()