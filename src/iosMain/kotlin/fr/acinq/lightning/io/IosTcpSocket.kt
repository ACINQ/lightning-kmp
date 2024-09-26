package fr.acinq.lightning.io

import fr.acinq.lightning.logging.LoggerFactory
import fr.acinq.lightning.utils.*
import kotlinx.cinterop.*
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.Foundation.NSData
import platform.posix.ECONNREFUSED
import platform.posix.ECONNRESET
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import swift.phoenix_crypto.NativeSocket
import swift.phoenix_crypto.NativeSocketError
import swift.phoenix_crypto.NativeSocketTLS

class IosTcpSocket @OptIn(ExperimentalForeignApi::class) constructor(private val socket: NativeSocket) : TcpSocket {

    @OptIn(ExperimentalForeignApi::class)
    override suspend fun send(
        bytes: ByteArray?,
        offset: Int,
        length: Int,
        flush: Boolean
    ): Unit = suspendCancellableCoroutine { continuation ->

        // @kotlinx.cinterop.ObjCMethod
        // public open external fun sendWithData(
        //   data: platform.Foundation.NSData,
        //   completion: (swift.phoenix_crypto.NativeSocketError?) -> kotlin.Unit
        // ): kotlin.Unit { /* compiled code */ }

        val data = bytes?.toNSData(offset = offset, length = length) ?: NSData()
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

    @OptIn(ExperimentalForeignApi::class)
    override suspend fun receiveAvailable(
        buffer: ByteArray,
        offset: Int,
        length: Int
    ): Int = suspendCancellableCoroutine { continuation ->

        // @kotlinx.cinterop.ObjCMethod
        // public open external fun receiveAvailableWithMaximumLength(
        //   maximumLength: platform.darwin.NSInteger /* = kotlin.Long */,
        //   success: (platform.Foundation.NSData?) -> kotlin.Unit,
        //   failure: (swift.phoenix_crypto.NativeSocketError?) -> kotlin.Unit
        // ): kotlin.Unit { /* compiled code */ }

        socket.receiveAvailableWithMaximumLength(
            maximumLength = length.convert(),
            success = { data ->
                data!!.copyTo(buffer, offset)
                continuation.resume(data.length.convert())
            },
            failure = { error ->
                continuation.resumeWithException(error!!.toIOException())
            }
        )
    }

    @OptIn(ExperimentalForeignApi::class)
    override suspend fun receiveFully(
        buffer: ByteArray,
        offset: Int,
        length: Int
    ): Unit = suspendCancellableCoroutine { continuation ->

        // @kotlinx.cinterop.ObjCMethod
        // public open external fun receiveFullyWithLength(
        //   length: platform.darwin.NSInteger /* = kotlin.Long */,
        //   success: (platform.Foundation.NSData?) -> kotlin.Unit,
        //   failure: (swift.phoenix_crypto.NativeSocketError?) -> kotlin.Unit
        // ): kotlin.Unit { /* compiled code */ }

        socket.receiveFullyWithLength(
            length = length.convert(),
            success = { data ->
                data!!.copyTo(buffer, offset)
                continuation.resume(Unit)
            },
            failure = { error ->
                continuation.resumeWithException(error!!.toIOException())
            }
        )
    }

    @OptIn(ExperimentalForeignApi::class)
    override suspend fun startTls(
        tls: TcpSocket.TLS
    ): TcpSocket = suspendCancellableCoroutine { continuation ->

        // @kotlinx.cinterop.ObjCMethod
        // public open external fun startTLSWithTls(
        //   tls: swift.phoenix_crypto.NativeSocketTLS,
        //   success: (swift.phoenix_crypto.NativeSocket?) -> kotlin.Unit,
        //   failure: (swift.phoenix_crypto.NativeSocketError?) -> kotlin.Unit
        // ): kotlin.Unit { /* compiled code */ }

        socket.startTLSWithTls(
            tls = tls.toNativeSocketTLS(),
            success = { newSocket ->
                continuation.resume(IosTcpSocket(newSocket!!))
            },
            failure = { error ->
                continuation.resumeWithException(error!!.toIOException())
            }
        )
    }

    @OptIn(ExperimentalForeignApi::class)
    override fun close() {

        // @kotlinx.cinterop.ObjCMethod
        // public open external fun close(): kotlin.Unit { /* compiled code */ }

        socket.close()
    }
}

internal actual object PlatformSocketBuilder : TcpSocket.Builder {

    @OptIn(ExperimentalUnsignedTypes::class, ExperimentalForeignApi::class)
    override suspend fun connect(
        host: String,
        port: Int,
        tls: TcpSocket.TLS,
        loggerFactory: LoggerFactory
    ): TcpSocket = suspendCancellableCoroutine { continuation ->

        // @kotlinx.cinterop.ObjCMethod
        // public open external fun connectWithHost(
        //   host: kotlin.String,
        //   port: platform.posix.uint16_t /* = kotlin.UShort */,
        //   tls: swift.phoenix_crypto.NativeSocketTLS,
        //   success: (swift.phoenix_crypto.NativeSocket?) -> kotlin.Unit,
        //   failure: (swift.phoenix_crypto.NativeSocketError?) -> kotlin.Unit
        // ): kotlin.Unit { /* compiled code */ }

        NativeSocket.connectWithHost(
            host = host,
            port = port.toUShort(),
            tls = tls.toNativeSocketTLS(),
            success = { socket ->
                continuation.resume(IosTcpSocket(socket!!))
            },
            failure = { error ->
                continuation.resumeWithException(error!!.toIOException())
            }
        )
    }
}

@OptIn(ExperimentalForeignApi::class)
fun TcpSocket.TLS.toNativeSocketTLS(): NativeSocketTLS {
    return when (this) {
        TcpSocket.TLS.DISABLED ->
            NativeSocketTLS.disabled()
        is TcpSocket.TLS.TRUSTED_CERTIFICATES ->
            NativeSocketTLS.trustedCertificates(this.expectedHostName)
        TcpSocket.TLS.UNSAFE_CERTIFICATES ->
            NativeSocketTLS.allowUnsafeCertificates()
        is TcpSocket.TLS.PINNED_PUBLIC_KEY ->
            NativeSocketTLS.pinnedPublicKey(this.pubKey)
    }
}

sealed class NativeSocketException : Exception() {
    data class POSIX(val errorCode: Int) : NativeSocketException()
    data class DNS(val errorType: Int) : NativeSocketException()
    data class TLS(val status: Int) : NativeSocketException()
}

@OptIn(ExperimentalForeignApi::class)
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
