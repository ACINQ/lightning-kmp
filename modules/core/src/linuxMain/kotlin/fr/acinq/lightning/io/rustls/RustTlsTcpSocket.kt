package fr.acinq.lightning.io.rustls

import io.ktor.network.sockets.Socket
import io.ktor.network.sockets.openReadChannel
import io.ktor.network.sockets.openWriteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.readAvailable
import io.ktor.utils.io.writeFully
import io.ktor.utils.io.ClosedWriteChannelException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.concurrent.AtomicInt
import kotlin.coroutines.coroutineContext
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
 * Safe to use from several coroutines: `ElectrumClient` writes requests from one coroutine while
 * reading responses in another. Three locks are involved, and they are always taken in this order:
 *
 *  1. [readMutex] / [writeMutex] — one reader and one writer at a time, as required by the Ktor
 *     byte channels and by the [ioIn] / [ioOut] staging buffers. They are disjoint, so a slow read
 *     never blocks a write.
 *  2. [connMutex] — exclusive access to the `rustls_connection`. rustls-ffi takes `&mut Connection`,
 *     so concurrent calls are a data race, not merely a lost update.
 *
 * [connMutex] is never held across socket I/O: only across the (non-suspending) rustls calls.
 */
@OptIn(ExperimentalForeignApi::class)
class RustTlsTcpSocket(
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

    /** 0 = open, 1 = closed. */
    private val closed = AtomicInt(0)

    /** Number of coroutines currently inside [useConnection], i.e. potentially using [conn]. */
    private val inFlight = AtomicInt(0)

    /** 0 = native resources still owned, 1 = already released. */
    private val released = AtomicInt(0)

    /** Exclusive access to [conn]. Held only across rustls calls, never across socket I/O. */
    private val connMutex = Mutex()

    /** Single-writer access to [ioOut], [outBytes] and [writeChannel]. */
    private val writeMutex = Mutex()

    /** Single-reader access to [ioIn], [inBytes] and [readChannel]. */
    private val readMutex = Mutex()

    /** Drive the TLS handshake to completion, exchanging records over the socket. */
    suspend fun handshake() = useConnection {
        while (connMutex.withLock { rustls_connection_is_handshaking(conn) }) {
            flushOutgoing()
            if (!connMutex.withLock { rustls_connection_is_handshaking(conn) }) break
            if (connMutex.withLock { rustls_connection_wants_read(conn) }) {
                if (!feedIncoming()) throw TcpSocket.IOException.ConnectionClosed()
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
        if (closed.value == 1) return
        withContext(Dispatchers.IO) {
            runCatching {
                useConnection {
                    connMutex.withLock { rustls_connection_send_close_notify(conn) }
                    flushOutgoing()
                }
            }
            close()
        }
    }

    override suspend fun send(bytes: ByteArray?, offset: Int, length: Int, flush: Boolean) {
        if (bytes == null || bytes.isEmpty()) return
        withContext(Dispatchers.IO) {
            ensureActive()
            tryIo { sendInternal(bytes, offset, length) }
        }
    }

    private suspend fun sendInternal(bytes: ByteArray, offset: Int, length: Int) = useConnection {
        writeMutex.withLock {
            var sent = 0
            var stalled = false
            bytes.usePinned { pinned ->
                while (sent < length) {
                    coroutineContext.ensureActive()
                    val written = connMutex.withLock {
                        memScoped {
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
                    }
                    sent += written
                    flushOutgoingLocked()
                    // rustls accepts no plaintext once its outgoing buffer is full; the flush above
                    // is what normally drains it. If a whole iteration writes nothing even after
                    // flushing, we are not going to make progress and must not spin on it.
                    if (written == 0) {
                        if (stalled) throw TcpSocket.IOException.Unknown("TLS send stalled: rustls accepted no plaintext after flushing")
                        stalled = true
                    } else {
                        stalled = false
                    }
                }
            }
        }
    }

    override suspend fun receiveFully(buffer: ByteArray, offset: Int, length: Int) {
        withContext(Dispatchers.IO) {
            ensureActive()
            tryIo {
                var received = 0
                while (received < length) {
                    val read = receiveAvailableInternal(buffer, offset + received, length - received)
                    // [receiveAvailableInternal] either makes progress or throws. A non-positive value
                    // would loop forever here, and would also make the next iteration pass rustls a
                    // negative offset into `buffer`, i.e. an out-of-bounds pointer.
                    check(read > 0) { "receiveAvailable returned $read" }
                    received += read
                }
            }
        }
    }

    /**
     * @return the number of plaintext bytes read, always strictly positive.
     * @throws TcpSocket.IOException.ConnectionClosed when the peer closed the connection, either
     * cleanly (TLS close_notify) or abruptly (socket EOF).
     */
    override suspend fun receiveAvailable(buffer: ByteArray, offset: Int, length: Int): Int =
        withContext(Dispatchers.IO) {
            ensureActive()
            tryIo { receiveAvailableInternal(buffer, offset, length) }
        }

    private suspend fun receiveAvailableInternal(buffer: ByteArray, offset: Int, length: Int): Int =
        useConnection { readMutex.withLock { receiveAvailableLocked(buffer, offset, length) } }

    /** Caller must hold [readMutex]. */
    private suspend fun receiveAvailableLocked(buffer: ByteArray, offset: Int, length: Int): Int {
        while (true) {
            coroutineContext.ensureActive()
            // Decrypt buffered application data straight into the caller's buffer,
            // capped at `length` so rustls can't overrun it.
            val n = connMutex.withLock {
                buffer.usePinned { pinned ->
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
            }
            when (n) {
                // Callers (e.g. `linesFlow`) loop until we throw: returning an EOF marker instead
                // would silently turn that loop into a busy-wait.
                NEED_MORE_TLS -> if (!feedIncomingLocked()) throw TcpSocket.IOException.ConnectionClosed() // socket EOF
                0 -> throw TcpSocket.IOException.ConnectionClosed() // clean TLS EOF (peer sent close_notify)
                else -> return n
            }
        }
    }

    /**
     * Release the socket and all native rustls resources (without a clean close_notify).
     *
     * Safe to call while other coroutines are reading from or writing to this socket: the native
     * resources are only freed once none of them is using [conn] any more.
     */
    override fun close() {
        if (!closed.compareAndSet(0, 1)) return
        // Closing the socket makes any suspended read/write on the Ktor channels fail, so in-flight
        // users unwind promptly rather than keeping the native resources alive indefinitely.
        socket.close()
        releaseIfIdle()
    }

    /**
     * Free the rustls connection and the pinned staging buffers, but only once the socket is closed
     * and no coroutine is inside [useConnection].
     *
     * The ordering is what makes this safe: [useConnection] increments [inFlight] *before* reading
     * [closed], and [close] writes [closed] *before* [releaseIfIdle] reads [inFlight]. So if we
     * observe `inFlight == 0` here, any caller arriving afterwards is guaranteed to observe
     * `closed == 1` and to bail out before touching [conn].
     */
    private fun releaseIfIdle() {
        if (closed.value == 1 && inFlight.value == 0 && released.compareAndSet(0, 1)) {
            rustls_connection_free(conn)
            inPin.unpin()
            outPin.unpin()
            nativeHeap.free(ioIn)
            nativeHeap.free(ioOut)
        }
    }

    /** Run [action] with [conn] and the staging buffers kept alive, or throw if we are closed. */
    private suspend fun <R> useConnection(action: suspend () -> R): R {
        inFlight.incrementAndGet()
        try {
            if (closed.value == 1) throw TcpSocket.IOException.ConnectionClosed()
            return action()
        } finally {
            inFlight.decrementAndGet()
            releaseIfIdle()
        }
    }

    // --- internal plumbing -------------------------------------------------

    /** Push all pending outgoing TLS records from rustls to the socket. */
    private suspend fun flushOutgoing() = writeMutex.withLock { flushOutgoingLocked() }

    /** Caller must hold [writeMutex]. */
    private suspend fun flushOutgoingLocked() {
        while (true) {
            coroutineContext.ensureActive()
            val len = connMutex.withLock {
                if (!rustls_connection_wants_write(conn)) {
                    0
                } else {
                    ioOut.len = 0u
                    val rc = memScoped {
                        val outN = alloc<size_tVar>()
                        rustls_connection_write_tls(conn, writeTlsCallback, ioOut.ptr, outN.ptr)
                    }
                    if (rc != 0) error("write_tls bridge failed with io result $rc")
                    ioOut.len.toInt()
                }
            }
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
    private suspend fun feedIncoming(): Boolean = readMutex.withLock { feedIncomingLocked() }

    /** Caller must hold [readMutex]. */
    private suspend fun feedIncomingLocked(): Boolean {
        // Refill staging from the socket only once the previous chunk is fully consumed.
        if (ioIn.pos >= ioIn.len) {
            val read = readChannel.readAvailable(inBytes, 0, inBytes.size)
            if (read <= 0) return false // -1 = EOF
            ioIn.len = read.convert()
            ioIn.pos = 0u
        }
        connMutex.withLock {
            val rc = memScoped {
                val outN = alloc<size_tVar>()
                rustls_connection_read_tls(conn, readTlsCallback, ioIn.ptr, outN.ptr)
            }
            if (rc != 0) error("read_tls bridge failed with io result $rc")
            rustlsCheck(rustls_connection_process_new_packets(conn))
        }
        return true
    }

    /**
     * Map everything this class can throw onto the [TcpSocket.IOException] hierarchy callers expect,
     * mirroring [fr.acinq.lightning.io.KtorNoTlsTcpSocket]. Cancellation must propagate untouched.
     */
    private inline fun <R> tryIo(io: () -> R): R {
        try {
            return io()
        } catch (ex: TcpSocket.IOException) {
            throw ex
        } catch (ex: CancellationException) {
            throw ex
        } catch (ex: ClosedReceiveChannelException) {
            throw TcpSocket.IOException.ConnectionClosed(ex)
        } catch (ex: ClosedWriteChannelException) {
            throw TcpSocket.IOException.ConnectionClosed(ex)
        } catch (ex: ClosedSendChannelException) {
            throw TcpSocket.IOException.ConnectionClosed(ex)
        } catch (ex: Throwable) {
            throw TcpSocket.IOException.Unknown(ex.message, ex)
        }
    }
}
