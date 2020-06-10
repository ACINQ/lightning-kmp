package fr.acinq.eklair.utils

import fr.acinq.eklair.Hex
import kotlin.experimental.and
import kotlin.experimental.or
import kotlin.experimental.xor

class BitSet private constructor(val bytes: ByteArray) {
    constructor(sizeBytes: Int) : this(ByteArray(sizeBytes))

    val bitCount: Int get() = bytes.size * 8

    val byteSize: Int get() = bytes.size

    private fun checkIndex(index: Int) {
        if (index / 8 >= bytes.size)
            throw NoSuchElementException("invalid index: $index of ${bytes.size * 8}")
    }

    operator fun get(index: Int): Boolean {
        checkIndex(index)
        val byteIndex = index / 8
        val bitIndex = index % 8
        return (bytes[byteIndex].toInt() and (0x80 ushr bitIndex) != 0)
    }

    operator fun set(index: Int, value: Boolean) {
        if (value) set(index) else clear(index)
    }

    fun set(index: Int) {
        checkIndex(index)
        val byteIndex = index / 8
        val bitIndex = index % 8
        bytes[byteIndex] = bytes[byteIndex] or (0x80 ushr bitIndex).toByte()
    }

    fun clear(index: Int) {
        checkIndex(index)
        val byteIndex = index / 8
        val bitIndex = index % 8
        bytes[byteIndex] = bytes[byteIndex] and (0xFF7F ushr bitIndex).toByte()
    }

    infix fun or(other: BitSet): BitSet = BitSet(bytes or other.bytes)
    infix fun and(other: BitSet): BitSet = BitSet(bytes and other.bytes)
    infix fun xor(other: BitSet): BitSet = BitSet(bytes xor other.bytes)

    val indices: IntRange get() = 0 until bitCount

    fun reversed(): BitSet = BitSet(bytes.size).also { new ->
        indices.forEach { i -> new.set(i, get(bitCount - 1 - i)) }
    }

    override fun toString(): String = "0x" + Hex.encode(bytes)
    fun toBinaryString() = indices.map { if (get(it)) '1' else '0' } .joinToString("")



    fun asSequence(): Sequence<Boolean> = sequence {
        repeat(bitCount) { yield(get(it)) }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is BitSet) return false
        return bytes.contentEquals(other.bytes)
    }

    override fun hashCode(): Int = bytes.contentHashCode()

    companion object {
        fun from(array: ByteArray): BitSet = BitSet(array.copyOf())
        fun fromBin(str: String): BitSet = str.trim().let { bin ->
            forAtMost(bin.length).apply {
                val pad = if (bin.length % 8 == 0) 0 else 8 - bin.length % 8
                bin.forEachIndexed { i, c ->
                    when (c) {
                        '0' -> {}
                        '1' -> set(pad + i)
                        else -> error("Invalid character. Only '0' and '1' are allowed.")
                    }
                }
            }
        }
        fun forAtMost(bitCount: Int): BitSet =
            if (bitCount <= 0) BitSet(ByteArray(0))
            else BitSet(ByteArray((bitCount - 1) / 8 + 1))
    }
}
