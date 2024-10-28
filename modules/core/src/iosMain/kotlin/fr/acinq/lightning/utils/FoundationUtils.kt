package fr.acinq.lightning.utils

import kotlinx.cinterop.*
import platform.Foundation.create
import platform.Foundation.NSData
import platform.posix.memcpy

@OptIn(ExperimentalForeignApi::class)
fun NSData.toByteArray(): ByteArray {
    val data = this
    return ByteArray(data.length.toInt()).apply {
        if (data.length > 0uL) {
            usePinned { pinned ->
                memcpy(pinned.addressOf(0), data.bytes, data.length)
            }
        }
    }
}

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
fun NSData.copyTo(buffer: ByteArray, offset: Int = 0) {
    if (offset + length.toInt() > buffer.size) {
        throw IllegalArgumentException(
            "offset($offset) + length(${length.toInt()}) > buffer.size(${buffer.size})"
        )
    }
    buffer.usePinned { pinned ->
        autoreleasepool {
            val src = this.bytes
            val len = this.length
            memcpy(pinned.addressOf(offset), src, len)
        }
        true
    }
}

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
fun ByteArray.toNSData(offset: Int, length: Int): NSData {
    if (offset + length > size) {
        throw IllegalArgumentException(
            "offset($offset) + length($length) > size($size)"
        )
    }
    if (length == 0) return NSData()
    val pinned = pin()
    return NSData.create(
        bytesNoCopy = pinned.addressOf(offset),
        length = length.toULong(),
        deallocator = { _, _ -> pinned.unpin() }
    )
}

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
fun ByteArray.toNSData(): NSData {
    if (isEmpty()) return NSData()
    val pinned = pin()
    return NSData.create(
        bytesNoCopy = pinned.addressOf(0),
        length = size.toULong(),
        deallocator = { _, _ -> pinned.unpin() }
    )
}