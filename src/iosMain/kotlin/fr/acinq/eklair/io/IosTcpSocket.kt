package fr.acinq.eklair.io

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.pin
import platform.Network.nw_connection_t
import fr.acinq.eklair.io.ios_ssf.*
import platform.Network.nw_error_get_error_code
import platform.Network.nw_error_get_error_domain
import platform.Network.nw_error_t
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

@OptIn(ExperimentalUnsignedTypes::class)
class IosTcpSocket(private val connection: nw_connection_t) : TcpSocket {
    override suspend fun send(bytes: ByteArray?, flush: Boolean): Unit =
        suspendCoroutine { continuation ->
            val data = bytes?.pin()
            ssf_send(connection, data?.addressOf(0), (bytes?.size ?: 0).convert(), flush) { error ->
                data?.unpin()
                if (error != null) continuation.resumeWithException(error.toIOException())
                else continuation.resume(Unit)
            }
        }

    override suspend fun receiveFully(buffer: ByteArray): Unit =
        suspendCoroutine { continuation ->
            val data = buffer.pin()
            ssf_receive(connection, data.addressOf(0), buffer.size.convert(), buffer.size.convert()) { _, _, error ->
                data.unpin()
                if (error != null) continuation.resumeWithException(error.toIOException())
                else continuation.resume(Unit)
            }
        }

    override suspend fun receiveAvailable(buffer: ByteArray): Int =
        suspendCoroutine { continuation ->
            val data = buffer.pin()
            ssf_receive(connection, data.addressOf(0), 0, buffer.size.convert()) { isComplete, size, error ->
                data.unpin()
                if (error != null) continuation.resumeWithException(error.toIOException())
                else if (size.toInt() == 0 && isComplete) continuation.resumeWithException(TcpSocket.IOException.ConnectionClosed)
                else continuation.resume(size.toInt())
            }
        }

    override fun close() {
        ssf_cancel(connection);
    }
}

internal actual object PlatformSocketBuilder : TcpSocket.Builder {
    override suspend fun connect(host: String, port: Int, tls: Boolean): TcpSocket =
        suspendCoroutine { continuation ->
            println("CONNECTING")
            ssf_connect(host, port.toString(), tls) { connection, error ->
                println("CONNECTED")
                when {
                    error != null -> continuation.resumeWithException(error.toIOException())
                    connection != null -> continuation.resume(IosTcpSocket(connection))
                }
            }
        }
}

private fun nw_error_t.toIOException(): TcpSocket.IOException =
    when (nw_error_get_error_domain(this)) {
        platform.Network.nw_error_domain_posix -> when (nw_error_get_error_code(this)) {
            platform.posix.ECONNREFUSED -> TcpSocket.IOException.ConnectionRefused
            platform.posix.ECONNRESET -> TcpSocket.IOException.ConnectionClosed
            else -> TcpSocket.IOException.Unknown(this?.debugDescription)
        }
        else -> TcpSocket.IOException.Unknown(this?.debugDescription)
    }
