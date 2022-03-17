package fr.acinq.lightning.io

import fr.acinq.lightning.utils.*
import fr.acinq.lightning.utils.lightningLogger
import kotlinx.cinterop.*
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.Foundation.NSData
import platform.Network.*
import platform.posix.ECONNREFUSED
import platform.posix.ECONNRESET
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.native.concurrent.ThreadLocal
import swift.phoenix_crypto.NativeSocket
import swift.phoenix_crypto.NativeSocketError
import swift.phoenix_crypto.NativeSocketTLS

@OptIn(ExperimentalUnsignedTypes::class)
class IosTcpSocket(private val socket: NativeSocket) : TcpSocket {

    override suspend fun send(
        bytes: ByteArray?,
        flush: Boolean
    ): Unit = suspendCancellableCoroutine { continuation ->

        // @kotlinx.cinterop.ObjCMethod
        // public open external fun sendWithData(
        //   data: platform.Foundation.NSData,
        //   completion: (swift.phoenix_crypto.NativeSocketError?) -> kotlin.Unit
        // ): kotlin.Unit { /* compiled code */ }

        val data = bytes?.let { it.toNSData() } ?: NSData()
        socket.sendWithData(
            data = data,
            completion = { error ->
                if (error != null) {
                    continuation.resumeWithException(error.toIOException())
                } else {
                    continuation.resume(Unit)
                }
            }
        )
    }

    override suspend fun receiveAvailable(
        buffer: ByteArray
    ): Int = suspendCancellableCoroutine { continuation ->

        // @kotlinx.cinterop.ObjCMethod
        // public open external fun receiveAvailableWithMaximumLength(
        //   maximumLength: platform.darwin.NSInteger /* = kotlin.Long */,
        //   success: (platform.Foundation.NSData?) -> kotlin.Unit,
        //   failure: (swift.phoenix_crypto.NativeSocketError?) -> kotlin.Unit
        // ): kotlin.Unit { /* compiled code */ }

        socket.receiveAvailableWithMaximumLength(
            maximumLength = buffer.size.convert(),
            success = { data ->
                data!!.copyTo(buffer)
                continuation.resume(data.length.convert())
            },
            failure = { error ->
                continuation.resumeWithException(error!!.toIOException())
            }
        )
    }

    override suspend fun receiveFully(
        buffer: ByteArray
    ): Unit = suspendCancellableCoroutine { continuation ->

        // @kotlinx.cinterop.ObjCMethod
        // public open external fun receiveFullyWithLength(
        //   length: platform.darwin.NSInteger /* = kotlin.Long */,
        //   success: (platform.Foundation.NSData?) -> kotlin.Unit,
        //   failure: (swift.phoenix_crypto.NativeSocketError?) -> kotlin.Unit
        // ): kotlin.Unit { /* compiled code */ }

        socket.receiveFullyWithLength(
            length = buffer.size.convert(),
            success = { data ->
                data!!.copyTo(buffer)
                continuation.resume(Unit)
            },
            failure = { error ->
                continuation.resumeWithException(error!!.toIOException())
            }
        )
    }

    override fun close(): Unit {

        // @kotlinx.cinterop.ObjCMethod
        // public open external fun close(): kotlin.Unit { /* compiled code */ }

        socket.close()
    }
}

@ThreadLocal
internal actual object PlatformSocketBuilder : TcpSocket.Builder {
    private val logger by lightningLogger<IosTcpSocket>()

    @OptIn(ExperimentalUnsignedTypes::class)
    override suspend fun connect(
        host: String,
        port: Int,
        tls: TcpSocket.TLS?
    ): TcpSocket = suspendCancellableCoroutine { continuation ->

        // @kotlinx.cinterop.ObjCMethod
        // public open external fun connectWithHost(
        //   host: kotlin.String,
        //   port: platform.posix.uint16_t /* = kotlin.UShort */,
        //   tls: swift.phoenix_crypto.NativeSocketTLS,
        //   success: (swift.phoenix_crypto.NativeSocket?) -> kotlin.Unit,
        //   failure: (swift.phoenix_crypto.NativeSocketError?) -> kotlin.Unit
        // ): kotlin.Unit { /* compiled code */ }

        val tlsOptions = when (tls) {
            TcpSocket.TLS.SAFE ->
                NativeSocketTLS(disabled = false, allowUntrustedCerts = false)
            TcpSocket.TLS.UNSAFE_CERTIFICATES ->
                NativeSocketTLS(disabled = false, allowUntrustedCerts = true)
            else ->
                NativeSocketTLS(disabled = true, allowUntrustedCerts = false)
        }

        NativeSocket.connectWithHost(
            host = host,
            port = port.toUShort(),
            tls = tlsOptions,
            success = { socket ->
                continuation.resume(IosTcpSocket(socket!!))
            },
            failure = { error ->
                continuation.resumeWithException(error!!.toIOException())
            }
        )
    }
}

private fun NativeSocketError.toIOException(): TcpSocket.IOException =
    when {
        isPosixErrorCode() -> when (posixErrorCode()) {
            ECONNREFUSED -> TcpSocket.IOException.ConnectionRefused()
            ECONNRESET -> TcpSocket.IOException.ConnectionClosed()
            else -> TcpSocket.IOException.Unknown(errorDescription())
        }
        isDnsServiceErrorType() ->
            TcpSocket.IOException.Unknown("DNS: ${errorDescription()}")
        isTlsStatus() ->
            TcpSocket.IOException.Unknown("TLS: ${errorDescription()}")
        else ->
            TcpSocket.IOException.Unknown("???: ${errorDescription()}")
    }
