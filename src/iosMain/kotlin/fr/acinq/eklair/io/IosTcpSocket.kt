package fr.acinq.eklair.io

import fr.acinq.eklair.io.ios_network_framework.nw_k_connection_receive
import fr.acinq.eklair.io.ios_network_framework.nw_k_parameters_create_secure_tcp
import fr.acinq.eklair.io.ios_network_framework.nw_k_parameters_create_secure_tcp_custom
import kotlinx.cinterop.*
import org.kodein.log.Logger
import org.kodein.log.LoggerFactory
import org.kodein.log.newLogger
import platform.Network.*
import platform.Security.sec_protocol_options_set_verify_block
import platform.darwin.dispatch_data_apply
import platform.darwin.dispatch_data_create
import platform.darwin.dispatch_data_get_size
import platform.darwin.dispatch_get_main_queue
import platform.posix.ECONNREFUSED
import platform.posix.ECONNRESET
import platform.posix.memcpy
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

@OptIn(ExperimentalUnsignedTypes::class)
class IosTcpSocket(private val connection: nw_connection_t) : TcpSocket {
    override suspend fun send(bytes: ByteArray?, flush: Boolean): Unit =
        suspendCoroutine { continuation ->
            val pinned = bytes?.pin()
            val data = pinned?.let { dispatch_data_create(pinned.addressOf(0), bytes.size.convert(), dispatch_get_main_queue(), ({})) }
            nw_connection_send(connection, data, null, flush) { error ->
                pinned?.unpin()
                if (error != null) continuation.resumeWithException(error.toIOException())
                else continuation.resume(Unit)
            }
        }

    private suspend fun receive(buffer: ByteArray, min: Int): Int =
        suspendCoroutine { continuation ->
            nw_k_connection_receive(connection, min.convert(), buffer.size.convert()) { data, isComplete, error ->
                when {
                    error != null -> continuation.resumeWithException(error.toIOException())
                    data != null -> {
                        buffer.usePinned { pinned ->
                            dispatch_data_apply(data) { _, offset, src, size ->
                                memcpy(pinned.addressOf(offset.convert()), src, size)
                                true
                            }
                        }
                        continuation.resume(dispatch_data_get_size(data).convert())
                    }
                    isComplete -> continuation.resumeWithException(TcpSocket.IOException.ConnectionClosed())
                }
            }
        }

    override suspend fun receiveFully(buffer: ByteArray) { receive(buffer, buffer.size) }


    override suspend fun receiveAvailable(buffer: ByteArray): Int = receive(buffer, 0)

    override fun close() {
        nw_connection_cancel(connection)
    }
}

internal actual object PlatformSocketBuilder : TcpSocket.Builder {
    override suspend fun connect(host: String, port: Int, tls: TcpSocket.TLS?): TcpSocket =
        suspendCoroutine { continuation ->
            val endpoint = nw_endpoint_create_host(host, port.toString())

            val parameters =
                when (tls) {
                    null -> nw_k_parameters_create_secure_tcp(false)
                    TcpSocket.TLS.SAFE -> nw_k_parameters_create_secure_tcp(true)
                    TcpSocket.TLS.UNSAFE_CERTIFICATES -> nw_k_parameters_create_secure_tcp_custom {
                        LoggerFactory.default.newLogger(IosTcpSocket::class).warning { "Using unsafe TLS!" }
                        val sec_options = nw_tls_copy_sec_protocol_options(it)
                        sec_protocol_options_set_verify_block(sec_options, { _, _, handler ->
                            handler!!(true)
                        }, dispatch_get_main_queue())
                    }
                }

            val connection = nw_connection_create(endpoint, parameters)

            nw_connection_set_queue(connection, dispatch_get_main_queue())
            var called = false
            nw_connection_set_state_changed_handler(connection) { state, error ->
                when (state) {
                    nw_connection_state_ready -> if (!called) {
                        called = true
                        continuation.resume(IosTcpSocket(connection))
                    }
                    nw_connection_state_failed -> if (!called) {
                        called = true
                        continuation.resumeWithException(error.toIOException())
                    }
                }
            }
            nw_connection_start(connection)
        }
}

private fun nw_error_t.toIOException(): TcpSocket.IOException =
    when (nw_error_get_error_domain(this)) {
        nw_error_domain_posix -> when (nw_error_get_error_code(this)) {
            ECONNREFUSED -> TcpSocket.IOException.ConnectionRefused()
            ECONNRESET -> TcpSocket.IOException.ConnectionClosed()
            else -> TcpSocket.IOException.Unknown(this?.debugDescription)
        }
        nw_error_domain_dns -> TcpSocket.IOException.Unknown("DNS: ${this?.debugDescription}")
        nw_error_domain_tls -> TcpSocket.IOException.Unknown("TLS: ${this?.debugDescription}")
        else -> TcpSocket.IOException.Unknown("Unknown: ${this?.debugDescription}")
    }
