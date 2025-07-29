package fr.acinq.lightning.wire

import fr.acinq.bitcoin.*
import fr.acinq.bitcoin.crypto.Pack
import fr.acinq.bitcoin.io.ByteArrayOutput
import fr.acinq.bitcoin.io.Input
import fr.acinq.bitcoin.io.Output
import fr.acinq.lightning.EncodedNodeId
import fr.acinq.lightning.ShortChannelId
import fr.acinq.lightning.utils.leftPaddedCopyOf
import kotlin.jvm.JvmStatic

@ExperimentalStdlibApi
object LightningCodecs {

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

    @JvmStatic
    fun int64(input: Input): Long {
        val bin = ByteArray(8)
        readStrict(input, bin, bin.size)
        return Pack.int64BE(bin, 0)
    }

    @JvmStatic
    fun writeInt64(input: Long, output: Output) = output.write(Pack.writeInt64BE(input))

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

    private fun writeBigSize(input: ULong, out: Output) {
        when {
            input < 0xfdu -> writeByte(input.toInt(), out)
            input < 0x10000u -> {
                out.write(0xfd)
                writeU16(input.toInt(), out)
            }
            input < 0x100000000u -> {
                out.write(0xfe)
                writeU32(input.toInt(), out)
            }
            else -> {
                out.write(0xff)
                writeU64(input.toLong(), out)
            }
        }
    }

    /** the varint codec only works on positive values, this is for backward compat */
    fun writeBigSize(input: Long, out: Output) = writeBigSize(input.toULong(), out)

    @JvmStatic
    fun bytes(input: Input, size: Int): ByteArray {
        require(size <= input.availableBytes) { "cannot read $size bytes from stream containing only ${input.availableBytes} bytes" }
        val blob = ByteArray(size)
        if (size > 0) input.read(blob, 0, blob.size)
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
    fun txId(input: Input): TxId = TxId(bytes(input, 32))

    @JvmStatic
    fun writeTxId(input: TxId, out: Output): Unit = writeBytes(input.value.toByteArray(), out)

    @JvmStatic
    fun txHash(input: Input): TxHash = TxHash(bytes(input, 32))

    @JvmStatic
    fun writeTxHash(input: TxHash, out: Output): Unit = writeBytes(input.value.toByteArray(), out)

    @JvmStatic
    fun script(input: Input): ByteArray {
        val length = bigSize(input)
        return bytes(input, length)
    }

    fun encodedNodeId(input: Input): EncodedNodeId {
        return when (val firstByte = byte(input)) {
            0, 1 -> {
                val isNode1 = firstByte == 0
                val scid = ShortChannelId(int64(input))
                EncodedNodeId.ShortChannelIdDir(isNode1, scid)
            }
            2, 3 -> {
                val publicKey = PublicKey(ByteArray(1) { firstByte.toByte() } + bytes(input, 32))
                EncodedNodeId.WithPublicKey.Plain(publicKey)
            }
            4, 5 -> {
                val publicKey = PublicKey(ByteArray(1) { (firstByte - 2).toByte() } + bytes(input, 32))
                EncodedNodeId.WithPublicKey.Wallet(publicKey)
            }
            else -> throw IllegalArgumentException("unexpected first byte: $firstByte")
        }
    }

    fun writeEncodedNodeId(input: EncodedNodeId, out: Output): Unit = when (input) {
        is EncodedNodeId.WithPublicKey.Plain -> writeBytes(input.publicKey.value, out)
        is EncodedNodeId.ShortChannelIdDir -> {
            writeByte(if (input.isNode1) 0 else 1, out)
            writeInt64(input.scid.toLong(), out)
        }
        is EncodedNodeId.WithPublicKey.Wallet -> {
            val firstByte = input.publicKey.value[0]
            writeByte(firstByte + 2, out)
            writeBytes(input.publicKey.value.drop(1), out)
        }
    }

}
