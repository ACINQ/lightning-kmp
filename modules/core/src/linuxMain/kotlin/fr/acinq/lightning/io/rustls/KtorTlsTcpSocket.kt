package fr.acinq.lightning.io.rustls

import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.Socket
import io.ktor.network.sockets.aSocket
import io.ktor.network.sockets.openReadChannel
import io.ktor.network.sockets.openWriteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.readAvailable
import io.ktor.utils.io.writeFully
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.Pinned
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocPointerTo
import kotlinx.cinterop.convert
import kotlinx.cinterop.free
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.pin
import kotlinx.cinterop.plus
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.posix.memcpy
import platform.posix.size_t
import platform.posix.size_tVar
import cnames.structs.rustls_connection
import fr.acinq.lightning.io.TcpSocket
import rustls.cinterop.RUSTLS_RESULT_OK
import rustls.cinterop.RUSTLS_RESULT_PLAINTEXT_EMPTY
import rustls.cinterop.rustls_bridge_io
import rustls.cinterop.rustls_connection_free
import rustls.cinterop.rustls_connection_get_alpn_protocol
import rustls.cinterop.rustls_connection_get_negotiated_ciphersuite_name
import rustls.cinterop.rustls_connection_get_protocol_version
import rustls.cinterop.rustls_connection_is_handshaking
import rustls.cinterop.rustls_connection_process_new_packets
import rustls.cinterop.rustls_connection_read
import rustls.cinterop.rustls_connection_read_tls
import rustls.cinterop.rustls_connection_send_close_notify
import rustls.cinterop.rustls_connection_wants_read
import rustls.cinterop.rustls_connection_wants_write
import rustls.cinterop.rustls_connection_write
import rustls.cinterop.rustls_connection_write_tls

private const val BUFFER_SIZE = 32 * 1024

/** Sentinel returned by readPlaintext() meaning "rustls needs more ciphertext fed in". */
private const val NEED_MORE_TLS = -1

/**
 * write_tls callback: rustls hands us ciphertext to send. We do NOT touch the
 * network here (it would mean a suspending call from a C callback); we only
 * append the bytes into the staging buffer carried in `userdata`. The Kotlin
 * caller flushes that buffer to the Ktor socket afterwards.
 */
@OptIn(ExperimentalForeignApi::class)
private val writeTlsCallback = staticCFunction {
        userdata: COpaquePointer?, buf: CPointer<UByteVar>?, n: size_t, outN: CPointer<size_tVar>? ->
    val io = userdata!!.reinterpret<rustls_bridge_io>().pointed
    val space = io.cap - io.len
    val toCopy = if (n < space) n else space
    if (toCopy > 0u) memcpy(io.buf!! + io.len.toLong(), buf, toCopy)
    io.len += toCopy
    outN!!.pointed.value = toCopy
    0 // rustls_io_result: success
}

/**
 * read_tls callback: rustls asks for more ciphertext from the network. We hand
 * it bytes the Kotlin caller already read from the socket into the staging
 * buffer carried in `userdata`.
 */
@OptIn(ExperimentalForeignApi::class)
private val readTlsCallback = staticCFunction {
        userdata: COpaquePointer?, buf: CPointer<UByteVar>?, n: size_t, outN: CPointer<size_tVar>? ->
    val io = userdata!!.reinterpret<rustls_bridge_io>().pointed
    val available = io.len - io.pos
    val toCopy = if (n < available) n else available
    if (toCopy > 0u) memcpy(buf, io.buf!! + io.pos.toLong(), toCopy)
    io.pos += toCopy
    outN!!.pointed.value = toCopy
    0 // rustls_io_result: success
}

/**
 * A TLS connection to a server, layered over a raw Ktor TCP [Socket]. rustls runs
 * the TLS state machine; this class pumps ciphertext between rustls and the socket
 * and exposes plaintext [write]/[read].
 *
 * Not safe for concurrent use; drive it from a single coroutine.
 */
@OptIn(ExperimentalForeignApi::class)
class KtorTlsTcpSocket (
    private val conn: CPointer<rustls_connection>,
    private val socket: Socket,
) : AutoCloseable, TcpSocket {

    private val readChannel: ByteReadChannel = socket.openReadChannel()
    private val writeChannel: ByteWriteChannel = socket.openWriteChannel(autoFlush = false)

    // Staging buffers shared with the C callbacks. Pinned for the connection's lifetime.
    private val outBytes = ByteArray(BUFFER_SIZE)
    private val outPin: Pinned<ByteArray> = outBytes.pin()
    private val ioOut = nativeHeap.alloc<rustls_bridge_io>().apply {
        buf = outPin.addressOf(0).reinterpret()
        cap = BUFFER_SIZE.convert()
        len = 0u
        pos = 0u
    }

    private val inBytes = ByteArray(BUFFER_SIZE)
    private val inPin: Pinned<ByteArray> = inBytes.pin()
    private val ioIn = nativeHeap.alloc<rustls_bridge_io>().apply {
        buf = inPin.addressOf(0).reinterpret()
        cap = BUFFER_SIZE.convert()
        len = 0u
        pos = 0u
    }

    private var closed = false

    /** Drive the TLS handshake to completion, exchanging records over the socket. */
    suspend fun handshake() {
        while (rustls_connection_is_handshaking(conn)) {
            flushOutgoing()
            if (!rustls_connection_is_handshaking(conn)) break
            if (rustls_connection_wants_read(conn)) {
                if (!feedIncoming()) error("peer closed the connection during the TLS handshake")
            }
        }
        // Flush any final handshake / session-ticket records.
        flushOutgoing()
    }

    /** Negotiated TLS protocol version number (RFC value), or 0 if not yet known. */
    fun protocolVersion(): Int = rustls_connection_get_protocol_version(conn).toInt()

    /** Negotiated cipher suite name, or "" if not yet known. */
    fun cipherSuite(): String = rustls_connection_get_negotiated_ciphersuite_name(conn).toKString()

    /** ALPN protocol negotiated with the peer, or `null` if none. */
    fun alpnProtocol(): String? = memScoped {
        val out = allocPointerTo<UByteVar>()
        val outLen = alloc<size_tVar>()
        rustls_connection_get_alpn_protocol(conn, out.ptr, outLen.ptr)
        val ptr = out.value ?: return@memScoped null
        val len = outLen.value.toInt()
        if (len == 0) null else ptr.readBytes(len).decodeToString()
    }

    /**
     * Send a TLS close_notify alert (a clean shutdown of the TLS stream) and flush it,
     * then release all resources. Prefer this over [close] when you can suspend.
     */
    suspend fun closeNotify() {
        if (closed) return
        rustls_connection_send_close_notify(conn)
        runCatching { flushOutgoing() }
        close()
    }

    override suspend fun send(bytes: ByteArray?, offset: Int, length: Int, flush: Boolean) {
        if (bytes == null || bytes.isEmpty()) return
        checkOpen()
        var sent = 0
        bytes.usePinned { pinned ->
            while (sent < length) {
                val written = memScoped {
                    val outN = alloc<size_tVar>()
                    rustlsCheck(
                        rustls_connection_write(
                            conn,
                            (pinned.addressOf(offset + sent).reinterpret<UByteVar>()),
                            (length - sent).convert(),
                            outN.ptr,
                        )
                    )
                    outN.value.toInt()
                }
                sent += written
                flushOutgoing()
            }
        }
    }

    override suspend fun receiveFully(buffer: ByteArray, offset: Int, length: Int) {
        var received = 0
        while (received < length) {
            received += receiveAvailable(buffer, offset + received, length - received)
        }
    }

    override suspend fun receiveAvailable(buffer: ByteArray, offset: Int, length: Int): Int {
        checkOpen()
        while (true) {
            // Decrypt buffered application data straight into the caller's buffer,
            // capped at `length` so rustls can't overrun it.
            val n = buffer.usePinned { pinned ->
                memScoped {
                    val outN = alloc<size_tVar>()
                    when (val r = rustls_connection_read(
                        conn, pinned.addressOf(offset).reinterpret<UByteVar>(), length.convert(), outN.ptr,
                    )) {
                        RUSTLS_RESULT_OK -> outN.value.toInt()        // 0 => clean EOF
                        RUSTLS_RESULT_PLAINTEXT_EMPTY -> NEED_MORE_TLS // nothing buffered yet
                        else -> throw RustlsException(r)
                    }
                }
            }
            when (n) {
                NEED_MORE_TLS -> if (!feedIncoming()) return -1 // socket EOF
                0 -> return 0 // clean TLS EOF (peer sent close_notify)
                else -> return n
            }
        }
    }

    /** Release the socket and all native rustls resources (without a clean close_notify). */
    override fun close() {
        if (closed) return
        closed = true
        socket.close()
        rustls_connection_free(conn)
        inPin.unpin()
        outPin.unpin()
        nativeHeap.free(ioIn)
        nativeHeap.free(ioOut)
    }

    // --- internal plumbing -------------------------------------------------

    /** Push all pending outgoing TLS records from rustls to the socket. */
    private suspend fun flushOutgoing() {
        while (rustls_connection_wants_write(conn)) {
            ioOut.len = 0u
            val rc = memScoped {
                val outN = alloc<size_tVar>()
                rustls_connection_write_tls(conn, writeTlsCallback, ioOut.ptr, outN.ptr)
            }
            if (rc != 0) error("write_tls bridge failed with io result $rc")
            val len = ioOut.len.toInt()
            if (len == 0) break
            writeChannel.writeFully(outBytes, 0, len)
            writeChannel.flush()
        }
    }

    /**
     * Feed one batch of ciphertext to rustls and let it decrypt.
     *
     * This is deliberately incremental: it hands rustls at most one `read_tls` worth
     * of bytes, then decrypts. Callers (handshake / read) loop, reading any resulting
     * plaintext between calls. Feeding a whole socket buffer in a tight loop instead
     * would overrun rustls' input — once it has plaintext ready to deliver it stops
     * wanting reads and the next `read_tls` fails. The staging buffer ([ioIn]) is
     * preserved across calls so leftover ciphertext is consumed before more is read.
     *
     * @return false if the socket reached end-of-stream with nothing left to feed.
     */
    private suspend fun feedIncoming(): Boolean {
        // Refill staging from the socket only once the previous chunk is fully consumed.
        if (ioIn.pos >= ioIn.len) {
            val read = readChannel.readAvailable(inBytes, 0, inBytes.size)
            if (read <= 0) return false // -1 = EOF
            ioIn.len = read.convert()
            ioIn.pos = 0u
        }
        val rc = memScoped {
            val outN = alloc<size_tVar>()
            rustls_connection_read_tls(conn, readTlsCallback, ioIn.ptr, outN.ptr)
        }
        if (rc != 0) error("read_tls bridge failed with io result $rc")
        rustlsCheck(rustls_connection_process_new_packets(conn))
        return true
    }

    private fun checkOpen() {
        check(!closed) { "TlsClientConnection is closed" }
    }
}
