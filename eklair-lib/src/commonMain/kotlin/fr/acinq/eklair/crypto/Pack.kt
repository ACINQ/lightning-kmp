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

}