package fr.acinq.eklair.utils

import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.ByteVector32
import kotlin.experimental.and
import kotlin.experimental.or
import kotlin.experimental.xor

fun ByteArray.leftPaddedCopyOf(n: Int): ByteArray {
    if (size >= n) return copyOf()

    val ret = ByteArray(n)
    val pad = n - size
    repeat (size) { ret[pad + it] = this[it] }

    return ret
}

private fun ByteArray.checkSizeEquals(other: ByteArray) {
    check(size == other.size) { "Byte arrays have different sizes (this: $size, other: ${other.size})" }
}

infix fun ByteArray.or(other: ByteArray): ByteArray {
    checkSizeEquals(other)
    return ByteArray(size) { this[it] or other[it] }
}

infix fun ByteArray.and(other: ByteArray): ByteArray {
    checkSizeEquals(other)
    return ByteArray(size) { this[it] and other[it] }
}

infix fun ByteArray.xor(other: ByteArray): ByteArray {
    checkSizeEquals(other)
    return ByteArray(size) { this[it] xor other[it] }
}

fun ByteArray.toByteVector() = ByteVector(this)

fun ByteArray.toByteVector32() = ByteVector32(this)

private val emptyByteArray = ByteArray(0)
fun ByteArray.subArray(newSize: Int): ByteArray {
    require(size >= 0)
    if (size == 0) return emptyByteArray
    require(newSize <= size)
    if (newSize == size) return this
    return copyOf(newSize)
}

infix fun ByteArray.concat(append: ByteArray): ByteArray {
    if (this.isEmpty()) return append
    if (append.isEmpty()) return this
    return this + append
}
