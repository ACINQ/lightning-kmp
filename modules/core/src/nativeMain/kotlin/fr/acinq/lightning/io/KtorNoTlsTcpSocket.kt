package fr.acinq.lightning.io

import fr.acinq.lightning.logging.LoggerFactory
import io.ktor.network.selector.*
import io.ktor.network.sockets.*
import io.ktor.utils.io.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.seconds

/**
 * Uses ktor native socket implementation, which does not support TLS.
 */
class KtorNoTlsTcpSocket(private val socket: Socket) : TcpSocket {

    private val connection = socket.connection()

    override suspend fun send(bytes: ByteArray?, offset: Int, length: Int, flush: Boolean) =
        withContext(Dispatchers.IO) {
            ensureActive()
            try {
                if (bytes != null) connection.output.writeFully(bytes, offset, length)
                if (flush) connection.output.flush()
            } catch (ex: ClosedSendChannelException) {
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

    override suspend fun startTls(tls: TcpSocket.TLS): TcpSocket = TODO("TLS not supported")

    override fun close() {
        // NB: this safely calls close(), wrapping it into a try/catch.
        socket.dispose()
    }

}

internal object KtorSocketBuilder : TcpSocket.Builder {
    override suspend fun connect(host: String, port: Int, tls: TcpSocket.TLS, loggerFactory: LoggerFactory): TcpSocket {
        return withContext(Dispatchers.IO) {
            var socket: Socket? = null
            try {
                socket = aSocket(SelectorManager(Dispatchers.IO)).tcp().connect(
                    host, port,
                    configure = {
                        keepAlive = true
                        socketTimeout = 15.seconds.inWholeMilliseconds
                        noDelay = true
                    }).let {
                    when (tls) {
                        is TcpSocket.TLS.DISABLED -> it
                        else -> TODO("TLS not supported")
                    }
                }
                KtorNoTlsTcpSocket(socket)
            } catch (e: Exception) {
                socket?.dispose()
                throw e
            }
        }
    }
}
