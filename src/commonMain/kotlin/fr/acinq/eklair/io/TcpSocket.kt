package fr.acinq.eklair.io

import fr.acinq.eklair.utils.decodeToString
import fr.acinq.eklair.utils.splitByLines
import fr.acinq.eklair.utils.subArray
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.produce
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.isActive


@OptIn(ExperimentalStdlibApi::class)
interface TcpSocket {

    sealed class IOException(message: String?) : Error(message) {
        object ConnectionRefused: IOException("Connection refused")
        object ConnectionClosed: IOException("Connection closed")
        object NoNetwork: IOException("No network available")
        class Unknown(message: String?): IOException(message)
    }

    suspend fun send(bytes: ByteArray?, flush: Boolean = true)

    suspend fun receiveFully(buffer: ByteArray)
    suspend fun receiveAvailable(buffer: ByteArray): Int

    fun close()

    fun interface Builder {
        suspend fun connect(host: String, port: Int, tls: Boolean): TcpSocket

        companion object {
            operator fun invoke(): Builder = PlatformSocketBuilder
        }
    }
}

internal expect object PlatformSocketBuilder : TcpSocket.Builder

suspend fun TcpSocket.receiveFully(size: Int): ByteArray =
    ByteArray(size).also { receiveFully(it) }

@OptIn(ExperimentalCoroutinesApi::class)
fun TcpSocket.receiveChannel(scope: CoroutineScope, maxChunkSize: Int = 8192) : ReceiveChannel<ByteArray> =
    scope.produce {
        val buffer = ByteArray(maxChunkSize)
        while (isActive) {
            val size = receiveAvailable(buffer)
            send(buffer.subArray(size))
        }
    }

fun TcpSocket.linesFlow(scope: CoroutineScope) =
    receiveChannel(scope).consumeAsFlow().decodeToString().splitByLines()
