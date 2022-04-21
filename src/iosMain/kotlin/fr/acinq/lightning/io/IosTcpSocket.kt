package fr.acinq.lightning.io

import fr.acinq.lightning.utils.*
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

        val data = bytes?.toNSData() ?: NSData()
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

    override fun close() {

        // @kotlinx.cinterop.ObjCMethod
        // public open external fun close(): kotlin.Unit { /* compiled code */ }

        socket.close()
    }
}

@ThreadLocal
internal actual object PlatformSocketBuilder : TcpSocket.Builder {
//  private val logger by lightningLogger<IosTcpSocket>()

    @OptIn(ExperimentalUnsignedTypes::class)
    override suspend fun connect(
        host: String,
        port: Int,
        tls: TcpSocket.TLS
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
            TcpSocket.TLS.DISABLED ->
                NativeSocketTLS.disabled()
            TcpSocket.TLS.TRUSTED_CERTIFICATES ->
                NativeSocketTLS.trustedCertificates()
            TcpSocket.TLS.UNSAFE_CERTIFICATES ->
                NativeSocketTLS.allowUnsafeCertificates()
            is TcpSocket.TLS.PINNED_PUBLIC_KEY ->
                NativeSocketTLS.pinnedPublicKey(tls.pubKey)
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

sealed class NativeSocketException: Exception() {
    data class POSIX(val errorCode: Int): NativeSocketException()
    data class DNS(val errorType: Int): NativeSocketException()
    data class TLS(val status: Int): NativeSocketException()
}

private fun NativeSocketError.toIOException(): TcpSocket.IOException {
    return when {
        isPosixErrorCode() -> {
            val cause = NativeSocketException.POSIX(posixErrorCode())
            when (posixErrorCode()) {
                ECONNREFUSED -> TcpSocket.IOException.ConnectionRefused(cause)
                ECONNRESET -> TcpSocket.IOException.ConnectionClosed(cause)
                else -> TcpSocket.IOException.Unknown("POSIX: ${errorDescription()}", cause)
            }
        }
        isDnsServiceErrorType() -> {
            val cause = NativeSocketException.DNS(dnsServiceErrorType())
            TcpSocket.IOException.Unknown("DNS: ${errorDescription()}", cause)
        }
        isTlsStatus() -> {
            val cause = NativeSocketException.TLS(tlsStatus())
            TcpSocket.IOException.Unknown("TLS: ${errorDescription()}", cause)
        }
        else -> {
            TcpSocket.IOException.Unknown("???: ${errorDescription()}")
        }
    }
}
