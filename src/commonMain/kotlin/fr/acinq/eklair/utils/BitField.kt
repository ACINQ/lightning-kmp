package fr.acinq.eklair.utils

import fr.acinq.secp256k1.Hex
import kotlin.experimental.and
import kotlin.experimental.or

class BitField private constructor(val bytes: ByteArray) {
    constructor(sizeBytes: Int) : this(ByteArray(sizeBytes))

    val bitCount: Int get() = bytes.size * 8

    val byteSize: Int get() = bytes.size

    private fun rightToLeft(index: Int) = bitCount - 1 - index

    private fun checkIndex(index: Int) {
        if (index < 0 || index / 8 >= bytes.size)
            throw NoSuchElementException("invalid index: $index of ${bytes.size * 8}")
    }

    fun getLeft(index: Int): Boolean {
        checkIndex(index)
        val byteIndex = index / 8
        val bitIndex = index % 8
        return (bytes[byteIndex].toInt() and (0x80 ushr bitIndex) != 0)
    }

    fun getRight(index: Int): Boolean = getLeft(rightToLeft(index))

    fun setLeft(index: Int, value: Boolean) {
        if (value) setLeft(index) else clearLeft(index)
    }

    fun setRight(index: Int, value: Boolean) = setLeft(rightToLeft(index), value)

    fun setLeft(index: Int) {
        checkIndex(index)
        val byteIndex = index / 8
        val bitIndex = index % 8
        bytes[byteIndex] = bytes[byteIndex] or (0x80 ushr bitIndex).toByte()
    }

    fun setRight(index: Int) = setLeft(rightToLeft(index))

    fun clearLeft(index: Int) {
        checkIndex(index)
        val byteIndex = index / 8
        val bitIndex = index % 8
        bytes[byteIndex] = bytes[byteIndex] and (0xFF7F ushr bitIndex).toByte()
    }

    fun clearRight(index: Int) = clearLeft(rightToLeft(index))

    infix fun or(other: BitField): BitField = BitField(bytes or other.bytes)
    infix fun and(other: BitField): BitField = BitField(bytes and other.bytes)
    infix fun xor(other: BitField): BitField = BitField(bytes xor other.bytes)

    val indices: IntRange get() = 0 until bitCount

    fun reversed(): BitField = BitField(bytes.size).also { new ->
        indices.forEach { i -> new.setLeft(i, getRight(i)) }
    }

    override fun toString(): String = "0x" + Hex.encode(bytes)

    fun toBinaryString() = indices.map { if (getLeft(it)) '1' else '0' } .joinToString("")

    fun asLeftSequence(): Sequence<Boolean> = sequence {
        repeat(bitCount) { yield(getLeft(it)) }
    }

    fun asRightSequence(): Sequence<Boolean> = sequence {
        repeat(bitCount) { yield(getRight(it)) }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is BitField) return false
        return bytes.contentEquals(other.bytes)
    }

    override fun hashCode(): Int = bytes.contentHashCode()

    companion object {
        fun from(array: ByteArray): BitField = BitField(array.copyOf())

        fun fromBin(str: String): BitField = str.trim().let { bin ->
            forAtMost(bin.length).apply {
                bin.reversed().forEachIndexed { i, c ->
                    when (c) {
                        '0' -> {}
                        '1' -> setRight(i)
                        else -> error("Invalid character. Only '0' and '1' are allowed.")
                    }
                }
            }
        }

        fun forAtMost(bitCount: Int): BitField =
            if (bitCount <= 0) BitField(ByteArray(0))
            else BitField(ByteArray((bitCount - 1) / 8 + 1))
    }
}
