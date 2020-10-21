package fr.acinq.eclair.io

import fr.acinq.eclair.utils.decodeToString
import fr.acinq.eclair.utils.splitByLines
import fr.acinq.eclair.utils.subArray
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow


@OptIn(ExperimentalStdlibApi::class)
interface TcpSocket {

    sealed class IOException(override val message: String, cause: Throwable? = null) : Exception(message, cause) {
        class ConnectionRefused(cause: Throwable? = null): IOException("Connection refused", cause)
        class ConnectionClosed(cause: Throwable? = null): IOException("Connection closed", cause)
        class Unknown(message: String?, cause: Throwable? = null): IOException(message ?: "Unknown", cause)
    }

    suspend fun send(bytes: ByteArray?, flush: Boolean = true)

    suspend fun receiveFully(buffer: ByteArray)
    suspend fun receiveAvailable(buffer: ByteArray): Int

    fun close()

    enum class TLS {
        SAFE, UNSAFE_CERTIFICATES
    }

    interface Builder {
        suspend fun connect(host: String, port: Int, tls: TLS? = null): TcpSocket

        companion object {
            operator fun invoke(): Builder = PlatformSocketBuilder
        }
    }
}

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