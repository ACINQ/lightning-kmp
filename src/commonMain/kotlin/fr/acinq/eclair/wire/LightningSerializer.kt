package fr.acinq.eclair.wire

import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.crypto.Pack
import fr.acinq.bitcoin.io.ByteArrayInput
import fr.acinq.bitcoin.io.ByteArrayOutput
import fr.acinq.bitcoin.io.Input
import fr.acinq.bitcoin.io.Output
import fr.acinq.eclair.utils.leftPaddedCopyOf
import fr.acinq.secp256k1.Hex
import kotlin.jvm.JvmStatic

@OptIn(ExperimentalUnsignedTypes::class)
abstract class LightningSerializer<T> {
    /**
     * read a message from a stream
     *
     * @param input input stream
     * @return a deserialized message
     */
    abstract fun read(input: Input): T

    abstract val tag: Long

    // set this to true if your serializer will handle reading/writing its own length
    // this should * never * be needed, unless you added a TLV extension that is not a real TLV but rather a TV, which would be a protocol violation
    open val willHandleLength: Boolean = false

    fun read(input: ByteArray): T = read(ByteArrayInput(input))

    /**
     * read a message from a hex string
     *
     * @param input message binary data in hex format
     * @return a deserialized message of type T
     */
    fun read(input: String): T = read(Hex.decode(input))

    /**
     * write a message to a stream
     *
     * @param message   message
     * @param out output stream
     */
    abstract fun write(message: T, out: Output)

    /**
     * write a message to a byte array
     *
     * @param message message
     * @return a serialized message
     */
    fun write(message: T): ByteArray {
        val out = ByteArrayOutput()
        write(message, out)
        return out.toByteArray()
    }

    open fun validate(message: T) {}

    @ExperimentalStdlibApi
    companion object {
        @JvmStatic
        private fun readStrict(input: Input): Int = if (input.availableBytes == 0) {
            throw IllegalArgumentException("unexpected EOF")
        } else {
            input.read()
        }

        @JvmStatic
        private fun readStrict(input: Input, b: ByteArray, length: Int) = if (input.availableBytes < length) {
            throw IllegalArgumentException("unexpected EOF")
        } else {
            input.read(b, 0, length)
        }

        @JvmStatic
        fun byte(input: Input): Int = readStrict(input)

        @JvmStatic
        fun writeByte(input: Int, out: Output): Unit = out.write(input and 0xff)

        @JvmStatic
        fun u16(input: Input): Int {
            val bin = ByteArray(2)
            readStrict(input, bin, bin.size)
            return Pack.int16BE(bin, 0).toUShort().toInt()
        }

        @JvmStatic
        fun writeU16(input: Int, output: Output) = output.write(Pack.writeInt16BE(input.toShort()))

        // Todo: should be unsigned
        @JvmStatic
        fun tu16(input: Input): Int {
            require(input.availableBytes <= 2) { "truncated integer is too long" }
            return tu64(input).toInt()
        }

        @JvmStatic
        fun writeTU16(input: Int, output: Output) {
            val tmpOut = ByteArrayOutput()
            writeU16(input, tmpOut)
            writeBytes(tmpOut.toByteArray().takeLast(truncatedLength(input.toLong())).toByteArray(), output)
        }

        // Todo: should be unsigned
        @JvmStatic
        fun u32(input: Input): Int {
            val bin = ByteArray(4)
            readStrict(input, bin, bin.size)
            return Pack.int32BE(bin, 0)
        }

        // Todo: should be unsigned
        @JvmStatic
        fun writeU32(input: Int, output: Output) = output.write(Pack.writeInt32BE(input))

        // Todo: should be unsigned
        @JvmStatic
        fun tu32(input: Input): Int {
            require(input.availableBytes <= 4) { "truncated integer is too long" }
            return tu64(input).toInt()
        }

        @JvmStatic
        fun writeTU32(input: Int, output: Output) {
            val tmpOut = ByteArrayOutput()
            writeU32(input, tmpOut)
            writeBytes(tmpOut.toByteArray().takeLast(truncatedLength(input.toLong())).toByteArray(), output)
        }

        // Todo: should be unsigned
        @JvmStatic
        fun u64(input: Input): Long {
            val bin = ByteArray(8)
            readStrict(input, bin, bin.size)
            return Pack.int64BE(bin, 0)
        }

        // Todo: should be unsigned
        @JvmStatic
        fun writeU64(input: Long, output: Output) = output.write(Pack.writeInt64BE(input))

        /**
         * Compute the length a truncated integer should have, depending on its value.
         * See https://github.com/lightningnetwork/lightning-rfc/blob/master/01-messaging.md#fundamental-types
         */
        private fun truncatedLength(input: Long): Int = when {
            input < 0x01 -> 0
            input < 0x0100 -> 1
            input < 0x010000 -> 2
            input < 0x01000000 -> 3
            input < 0x0100000000L -> 4
            input < 0x010000000000L -> 5
            input < 0x01000000000000L -> 6
            input < 0x0100000000000000L -> 7
            else -> 8
        }

        // Todo: should be unsigned
        @JvmStatic
        fun tu64(input: Input): Long {
            require(input.availableBytes <= 8) { "truncated integer is too long" }
            val bin = ByteArray(input.availableBytes)
            input.read(bin, 0, bin.size)
            val l = Pack.int64BE(bin.leftPaddedCopyOf(8))
            require(bin.size == truncatedLength(l)) { "truncated integer is not minimally-encoded" }
            return l
        }

        @JvmStatic
        fun writeTU64(input: Long, output: Output) {
            val tmpOut = ByteArrayOutput()
            writeU64(input, tmpOut)
            writeBytes(tmpOut.toByteArray().takeLast(truncatedLength(input)).toByteArray(), output)
        }

        @JvmStatic
        fun bigSize(input: Input): Long {
            val first = readStrict(input)
            return when {
                first < 0xfd -> first.toLong()
                first == 0xfd -> {
                    val l = u16(input).toLong()
                    require(l >= 0xfd) { "non-canonical encoding for varint $l" }
                    l
                }
                first == 0xfe -> {
                    val l = u32(input).toUInt().toLong()
                    require(l >= 0x10000) { "non-canonical encoding for varint $l" }
                    l
                }
                first == 0xff -> {
                    val l = u64(input).toULong()
                    require(l >= 0x100000000U) { "non-canonical encoding for varint $l" }
                    l.toLong()
                }
                else -> throw IllegalArgumentException("invalid first byte $first for varint type")
            }
        }

        @JvmStatic
        fun writeBigSize(input: Long, out: Output) {
            when {
                input < 0xfdL -> writeByte(input.toInt(), out)
                input < 0x10000 -> {
                    out.write(0xfd)
                    writeU16(input.toInt(), out)
                }
                input < 0x100000000 -> {
                    out.write(0xfe)
                    writeU32(input.toInt(), out)
                }
                else -> {
                    out.write(0xff)
                    writeU64(input, out)
                }
            }
        }

        @JvmStatic
        fun bytes(input: Input, size: Int): ByteArray {
            val blob = ByteArray(size)
            if (size > 0) {
                val count = input.read(blob, 0, blob.size)
                require(count >= size) { "unexpected EOF" }
            }
            return blob
        }

        @JvmStatic
        fun bytes(input: Input, size: Long): ByteArray = bytes(input, size.toInt())

        @JvmStatic
        fun writeBytes(input: ByteArray, out: Output): Unit = out.write(input)

        @JvmStatic
        fun writeBytes(input: ByteVector, out: Output): Unit = writeBytes(input.toByteArray(), out)

        @JvmStatic
        fun writeBytes(input: ByteVector32, out: Output): Unit = writeBytes(input.toByteArray(), out)

        @JvmStatic
        fun script(input: Input): ByteArray {
            val length = bigSize(input) // read size
            return bytes(input, length) // read bytes
        }

        fun <T> readCollection(input: Input, reader: LightningSerializer<T>, maxElement: Int?): List<T> =
            readCollection(input, reader::read, maxElement)

        fun <T> readCollection(input: Input, reader: (Input) -> T, maxElement: Int?): List<T> {
            val count = bigSize(input)
            if (maxElement != null) require(count <= maxElement) { "invalid length" }
            val items = mutableListOf<T>()
            for (i in 1..count) {
                items += reader(input)
            }
            return items.toList()
        }

        fun <T> readCollection(input: Input, reader: LightningSerializer<T>): List<T> =
            readCollection(input, reader, null)

        fun <T> writeCollection(seq: List<T>, output: Output, writer: LightningSerializer<T>) =
            writeCollection(seq, output, writer::write)

        fun <T> writeCollection(seq: List<T>, output: Output, writer: (T, Output) -> Unit) {
            writeBigSize(seq.size.toLong(), output)
            seq.forEach { writer.invoke(it, output) }
        }
    }
}

@OptIn(ExperimentalUnsignedTypes::class)
interface LightningSerializable<T> {
    fun serializer(): LightningSerializer<T>

    val tag: Long
        get() = serializer().tag
}