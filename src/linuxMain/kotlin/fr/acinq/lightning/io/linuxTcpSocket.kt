package fr.acinq.lightning.io

import co.touchlab.kermit.Logger
import fr.acinq.lightning.logging.LoggerFactory
import fr.acinq.lightning.logging.error
import io.ktor.network.selector.*
import io.ktor.network.sockets.*
import io.ktor.network.tls.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.channels.ClosedSendChannelException

class LinuxTcpSocket(val socket: Socket, val loggerFactory: LoggerFactory) : TcpSocket {

    private val logger = loggerFactory.newLogger(this::class)

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

    override suspend fun startTls(tls: TcpSocket.TLS): TcpSocket = TODO()

    override fun close() {
        // NB: this safely calls close(), wrapping it into a try/catch.
        socket.dispose()
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
                        else -> socket
                    }
                }
                LinuxTcpSocket(socket, loggerFactory)
            } catch (e: Exception) {
                throw e
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