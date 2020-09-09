package fr.acinq.eclair.utils

import fr.acinq.bitcoin.updated

/**
 * Bit stream that can be written to and read at both ends (i.e. you can read from the end or the beginning of the stream)
 *
 *  TODO: either merge this with Bitfield or find a way to use Bech32 methods instead
 * @param bytes    bits packed as bytes, the last byte is padded with 0s
 * @param offstart offset at which the first bit is in the first byte
 * @param offend   offset at which the last bit is in the last byte
 */
data class BitStream(private var bytes: List<Byte>, private var offstart: Int, private var offend: Int) {

    constructor() : this(ArrayList(), 0, 0)

    fun clone() = BitStream(bytes.toList(), offstart, offend)

    // offstart: 0 1 2 3 4 5 6 7
    // offend: 7 6 5 4 3 2 1 0

    fun bitCount() = 8 * bytes.size - offstart - offend

    fun isEmpty() = bitCount() == 0

    /**
     * append a byte to a bitstream
     *
     * @param input byte to append
     * @return an updated bitstream
     */
    fun writeByte(input: Byte) {
        when(offend) {
            0 -> bytes += input
            else -> {
                val input1 = input.toInt() and 0xff
                val last = ((bytes.last().toInt() or (input1.ushr(8 - offend))) and 0xff).toByte()
                val next = ((input1 shl offend) and 0xff).toByte()
                bytes = bytes.dropLast(1)
                bytes = bytes + last + next
            }
        }
    }

    /**
     * append bytes to a bitstream
     *
     * @param input bytes to append
     * @return an udpdate bitstream
     */
    fun writeBytes(input: List<Byte>) {
         input.forEach { writeByte(it) }
    }

    /**
     * append a bit to a bistream
     *
     * @param bit bit to append
     * @return an update bitstream
     */
    fun writeBit(bit: Boolean) {
        when {
            offend == 0 && bit -> {
                bytes += (0x80.toByte())
                offend = 7
            }
            offend == 0 -> {
                bytes += (0x00.toByte())
                offend = 7
            }
            bit -> {
                bytes = bytes.updated(bytes.size - 1, (bytes.last() + (1 shl (offend - 1))).toByte())
                offend -= 1
            }
            else -> offend -= 1
        }
    }

    /**
     * append bits to a bistream
     *
     * @param input bits to append
     * @return an update bitstream
     */
    fun writeBits(input: List<Boolean>) {
        input.forEach { writeBit(it) }
    }

    /**
     * read the last bit from a bitstream
     *
     * @return a (stream, bit) pair where stream is an updated bitstream and bit is the last bit
     */
    fun popBit() : Boolean = when(offend) {
        7 -> {
            val result = lastBit()
            bytes = bytes.dropLast(1)
            offend = 0
            result
        }
        else -> {
            val result = lastBit()
            val shift = offend + 1
            val last = (bytes.last().toInt() ushr shift) shl shift
            bytes = bytes.updated(bytes.size - 1, last.toByte())
            offend += 1
            result
        }
    }

    /**
     * read the last byte from a bitstream
     *
     * @return a (stream, byte) pair where stream is an updated bitstream and byte is the last byte
     */
    fun popByte(): Byte = when(offend) {
        0 -> {
            val result = bytes.last()
            bytes = bytes.dropLast(1)
            result
        }
        else -> {
            val a = bytes[bytes.size - 2].toInt() and 0xff
            val b = bytes[bytes.size - 1].toInt() and 0xff
            val result = ((a shl (8 - offend)) or (b ushr offend)) and 0xff
            val a1 = (a ushr offend) shl offend
            bytes = bytes.dropLast(2)
            bytes += a1.toByte()
            result.toByte()
        }
    }

    fun popBytes(n: Int): List<Byte> {
        val result = ArrayList<Byte>()
        repeat(n) {
            result.add(popByte())
        }
        return result
    }

    /**
     * read the first bit from a bitstream
     *
     * @return
     */
    fun readBit() : Boolean = when(offstart) {
        7 -> {
            val result = firstBit()
            bytes = bytes.drop(1)
            offstart = 0
            result
        }
        else -> {
            val result = firstBit()
            offstart += 1
            result
        }
    }

    fun readBits(count: Int): List<Boolean> {
        val result = ArrayList<Boolean>()
        repeat(count) {
            result.add(readBit())
        }
        return result
    }

    /**
     * read the first byte from a bitstream
     *
     * @return
     */
    fun readByte(): Byte {
        val result = ((bytes[0].toInt() shl offstart) or (bytes[1].toInt() ushr (7 - offstart))) and 0xff
        bytes = bytes.drop(1)
        return result.toByte()
    }

    fun isSet(pos: Int): Boolean {
        val pos1 = pos + offstart
        return (bytes[pos1 / 8].toInt() and (1 shl (7 - (pos1.rem(8))))) != 0
    }

    fun firstBit(): Boolean = (bytes.first().toInt() and (1 shl (7 - offstart))) != 0

    fun lastBit(): Boolean = (bytes.last().toInt() and (1 shl offend)) != 0

    fun getBytes() = bytes.toByteArray()
}
