package fr.acinq.eklair.wire

import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.Hex
import fr.acinq.bitcoin.crypto.Pack
import fr.acinq.bitcoin.io.ByteArrayInput
import fr.acinq.bitcoin.io.ByteArrayOutput
import fr.acinq.bitcoin.io.Input
import fr.acinq.bitcoin.io.Output
import kotlin.jvm.JvmStatic

abstract class LightningSerializer<T> {
    /**
     * read a message from a stream
     *
     * @param in input stream
     * @return a deserialized message
     */
    abstract fun read(input: Input): T

    fun read(input: ByteArray): T = read(ByteArrayInput(input))

    /**
     * read a message from a hex string
     *
     * @param in message binary data in hex format
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
        fun byte(input: Input): Int = input.read()

        @JvmStatic
        fun writeByte(input: Int, out: Output): Unit = out.write(input and 0xff)

        fun u16(input: Input): Int {
            val bin = ByteArray(2)
            input.read(bin, 0, bin.size)
            return Pack.uint16BE(bin, 0)
        }

        fun writeU16(input: Int, output: Output) = output.write(Pack.writeUint16BE(input))

        fun u32(input: Input): Int {
            val bin = ByteArray(4)
            input.read(bin, 0, bin.size)
            return Pack.uint32BE(bin, 0)
        }

        fun writeU32(input: Int, output: Output) = output.write(Pack.writeUint32BE(input))


        fun u64(input: Input): Long {
            val bin = ByteArray(8)
            input.read(bin, 0, bin.size)
            return Pack.uint64BE(bin, 0)
        }

        fun writeU64(input: Long, output: Output) = output.write(Pack.writeUint64BE(input))

        @JvmStatic
        fun bigSize(input: Input): Long {
            val first = input.read()
            return when {
                first < 0xfd -> first.toLong()
                first == 0xfd -> u16(input).toLong()
                first == 0xfe -> u32(input).toLong()
                first == 0xff -> u64(input)
                else -> {
                    throw IllegalArgumentException("invalid first byte $first for varint type")
                }
            }
        }

        @JvmStatic
        fun writeBigSize(input: Long, out: Output): Unit {
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
                require(count >= size)
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

        fun <T> writeCollection(seq: List<T>, output: Output, writer: LightningSerializer<T>): Unit =
            writeCollection(seq, output, writer::write)

        fun <T> writeCollection(seq: List<T>, output: Output, writer: (T, Output) -> Unit): Unit {
            writeBigSize(seq.size.toLong(), output)
            seq.forEach { writer.invoke(it, output) }
        }
    }
}

interface LightningSerializable<T> {
    fun serializer(): LightningSerializer<T>
}