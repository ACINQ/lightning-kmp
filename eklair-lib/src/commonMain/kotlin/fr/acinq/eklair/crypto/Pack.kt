package fr.acinq.eklair.crypto

object Pack {
    fun littleEndianToInt(bs: ByteArray, off: Int): Int {
        var off = off
        var n: Int = bs[off].toInt() and 0xff
        n = n or (bs[++off].toInt() and 0xff shl 8)
        n = n or (bs[++off].toInt() and 0xff shl 16)
        n = n or (bs[++off].toInt() shl 24)
        return n
    }

    fun intToLittleEndian(n: Int, bs: ByteArray, off: Int) {
        var off = off
        bs[off] = n.toByte()
        bs[++off] = (n ushr 8).toByte()
        bs[++off] = (n ushr 16).toByte()
        bs[++off] = (n ushr 24).toByte()
    }

    fun intToBigEndian(n: Int, bs: ByteArray, off: Int) {
        var off = off
        bs[off] = (n ushr 24).toByte()
        bs[++off] = (n ushr 16).toByte()
        bs[++off] = (n ushr 8).toByte()
        bs[++off] = n.toByte()
    }

    fun uint16(input: ByteArray, offset: Int): Int = (input[0].toInt() and 0xff) shl 8 or (input[1].toInt() and 0xff)

    fun write16(input: Int) : ByteArray {
        val output = ByteArray(2)
        output[0] = ((input ushr 8) and 0xff).toByte()
        output[1] = ((input ushr 0) and 0xff).toByte()
        return output
    }

    fun write16LE(input: Int) : ByteArray {
        val output = ByteArray(2)
        output[0] = ((input ushr 0) and 0xff).toByte()
        output[1] = ((input ushr 8) and 0xff).toByte()
        return output
    }

    fun write64(input: Long) : ByteArray {
        val output = ByteArray(8)
        output[0] = ((input ushr 0) and 0xff).toByte()
        output[1] = ((input ushr 8) and 0xff).toByte()
        output[2] = ((input ushr 16) and 0xff).toByte()
        output[3] = ((input ushr 24) and 0xff).toByte()
        output[4] = ((input ushr 32) and 0xff).toByte()
        output[5] = ((input ushr 40) and 0xff).toByte()
        output[6] = ((input ushr 48) and 0xff).toByte()
        output[7] = ((input ushr 56) and 0xff).toByte()
        return output
    }

    fun write64(input: Int) : ByteArray = write64(input.toLong())
}