package fr.acinq.lightning.io.rustls

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CValue
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.useContents
import kotlinx.cinterop.value
import platform.posix.size_tVar
import rustls.cinterop.RUSTLS_RESULT_OK
import rustls.cinterop.rustls_error
import rustls.cinterop.rustls_result
import rustls.cinterop.rustls_str
import rustls.cinterop.rustls_version

/**
 * Idiomatic entry points and helpers around the rustls-ffi C API.
 *
 * `rustls_result` is just a `UInt` error code in the generated bindings; the
 * `RUSTLS_RESULT_*` constants are plain `UInt`s.
 */
@OptIn(ExperimentalForeignApi::class)
object Rustls {
    /** The underlying rustls / rustls-ffi version string. */
    fun version(): String = rustls_version().toKString()
}

/** Thrown when a rustls-ffi call returns a non-OK [rustls_result]. */
@OptIn(ExperimentalForeignApi::class)
class RustlsException(val result: rustls_result, message: String) : Exception(message) {
    constructor(result: rustls_result) : this(result, "rustls error $result: ${errorString(result)}")

    companion object {
        fun errorString(result: rustls_result): String = memScoped {
            val buf = allocArray<ByteVar>(256)
            val outN = alloc<size_tVar>()
            rustls_error(result, buf, 256u, outN.ptr)
            buf.readBytes(outN.value.toInt()).decodeToString()
        }
    }
}

/** Throw [RustlsException] unless [result] is `RUSTLS_RESULT_OK`. */
@OptIn(ExperimentalForeignApi::class)
internal fun rustlsCheck(result: rustls_result) {
    if (result != RUSTLS_RESULT_OK) throw RustlsException(result)
}

/** Decode a borrowed, non-NUL-terminated `rustls_str` into a Kotlin String. */
@OptIn(ExperimentalForeignApi::class)
internal fun CValue<rustls_str>.toKString(): String = useContents {
    val ptr = data ?: return@useContents ""
    ptr.readBytes(len.toInt()).decodeToString()
}
