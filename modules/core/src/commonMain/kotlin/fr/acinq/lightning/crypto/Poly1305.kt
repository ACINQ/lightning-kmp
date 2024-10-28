package fr.acinq.lightning.crypto

import fr.acinq.bitcoin.crypto.Pack

/**
 * Poly1305 message authentication code, designed by D. J. Bernstein.
 *
 *
 * Poly1305 computes a 128-bit (16 bytes) authenticator, using a 128 bit nonce and a 256 bit key
 * consisting of a 128 bit key applied to an underlying cipher, and a 128 bit key (with 106
 * effective key bits) used in the authenticator.
 *
 *
 * The polynomial calculation in this implementation is adapted from the public domain [poly1305-donna-unrolled](https://github.com/floodyberry/poly1305-donna) C implementation
 * by Andrew M (@floodyberry).
 * @see Poly1305KeyGenerator
 */
class Poly1305 {
    private val singleByte = ByteArray(1)
    // Initialised state
    /** Polynomial key  */
    private var r0 = 0
    private var r1 = 0
    private var r2 = 0
    private var r3 = 0
    private var r4 = 0

    /** Precomputed 5 * r[1..4]  */
    private var s1 = 0
    private var s2 = 0
    private var s3 = 0
    private var s4 = 0

    /** Encrypted nonce  */
    private var k0 = 0
    private var k1 = 0
    private var k2 = 0
    private var k3 = 0
    // Accumulating state
    /** Current block of buffered input  */
    private val currentBlock = ByteArray(BLOCK_SIZE)

    /** Current offset in input buffer  */
    private var currentBlockOffset = 0

    /** Polynomial accumulator  */
    private var h0 = 0
    private var h1 = 0
    private var h2 = 0
    private var h3 = 0
    private var h4 = 0

    fun setKey(key: ByteArray) {
        require(key.size == 32) { "Poly1305 key must be 256 bits." }

        // Extract r portion of key (and "clamp" the values)
        val t0 = Pack.int32LE(key, 0)
        val t1 = Pack.int32LE(key, 4)
        val t2 = Pack.int32LE(key, 8)
        val t3 = Pack.int32LE(key, 12)

        // NOTE: The masks perform the key "clamping" implicitly
        r0 = t0 and 0x03FFFFFF
        r1 = t0 ushr 26 or (t1 shl 6) and 0x03FFFF03
        r2 = t1 ushr 20 or (t2 shl 12) and 0x03FFC0FF
        r3 = t2 ushr 14 or (t3 shl 18) and 0x03F03FFF
        r4 = t3 ushr 8 and 0x000FFFFF

        // Precompute multipliers
        s1 = r1 * 5
        s2 = r2 * 5
        s3 = r3 * 5
        s4 = r4 * 5
        val kBytes: ByteArray
        val kOff: Int
        kBytes = key
        kOff = BLOCK_SIZE
        k0 = Pack.int32LE(kBytes, kOff + 0)
        k1 = Pack.int32LE(kBytes, kOff + 4)
        k2 = Pack.int32LE(kBytes, kOff + 8)
        k3 = Pack.int32LE(kBytes, kOff + 12)
    }

    fun getAlgorithmName(): String = "Poly1305"

    fun getMacSize(): Int = BLOCK_SIZE

    fun update(`in`: Byte) {
        singleByte[0] = `in`
        update(singleByte, 0, 1)
    }

    fun update(`in`: ByteArray, inOff: Int, len: Int) {
        var copied = 0
        while (len > copied) {
            if (currentBlockOffset == BLOCK_SIZE) {
                processBlock()
                currentBlockOffset = 0
            }
            val toCopy = kotlin.math.min(len - copied, BLOCK_SIZE - currentBlockOffset)
            `in`.copyInto(currentBlock, currentBlockOffset, copied + inOff, copied + inOff + toCopy)
            //System.arraycopy(`in`, copied + inOff, currentBlock, currentBlockOffset, toCopy)
            copied += toCopy
            currentBlockOffset += toCopy
        }
    }

    private fun processBlock() {
        if (currentBlockOffset < BLOCK_SIZE) {
            currentBlock[currentBlockOffset] = 1
            for (i in currentBlockOffset + 1 until BLOCK_SIZE) {
                currentBlock[i] = 0
            }
        }
        val t0 = 0xffffffffL and Pack.int32LE(currentBlock, 0).toLong()
        val t1 = 0xffffffffL and Pack.int32LE(currentBlock, 4).toLong()
        val t2 = 0xffffffffL and Pack.int32LE(currentBlock, 8).toLong()
        val t3 = 0xffffffffL and Pack.int32LE(currentBlock, 12).toLong()
        h0 += (t0 and 0x3ffffffL).toInt()
        h1 += (t1 shl 32 or t0 ushr 26 and 0x3ffffffL).toInt()
        h2 += (t2 shl 32 or t1 ushr 20 and 0x3ffffffL).toInt()
        h3 += (t3 shl 32 or t2 ushr 14 and 0x3ffffffL).toInt()
        h4 += (t3 ushr 8).toInt()
        if (currentBlockOffset == BLOCK_SIZE) {
            h4 += 1 shl 24
        }
        val tp0: Long = mul32x32_64(
            h0,
            r0
        ) + mul32x32_64(
            h1,
            s4
        ) + mul32x32_64(
            h2,
            s3
        ) + mul32x32_64(
            h3,
            s2
        ) + mul32x32_64(h4, s1)
        var tp1: Long = mul32x32_64(
            h0,
            r1
        ) + mul32x32_64(
            h1,
            r0
        ) + mul32x32_64(
            h2,
            s4
        ) + mul32x32_64(
            h3,
            s3
        ) + mul32x32_64(h4, s2)
        var tp2: Long = mul32x32_64(
            h0,
            r2
        ) + mul32x32_64(
            h1,
            r1
        ) + mul32x32_64(
            h2,
            r0
        ) + mul32x32_64(
            h3,
            s4
        ) + mul32x32_64(h4, s3)
        var tp3: Long = mul32x32_64(
            h0,
            r3
        ) + mul32x32_64(
            h1,
            r2
        ) + mul32x32_64(
            h2,
            r1
        ) + mul32x32_64(
            h3,
            r0
        ) + mul32x32_64(h4, s4)
        var tp4: Long = mul32x32_64(
            h0,
            r4
        ) + mul32x32_64(
            h1,
            r3
        ) + mul32x32_64(
            h2,
            r2
        ) + mul32x32_64(
            h3,
            r1
        ) + mul32x32_64(h4, r0)
        h0 = tp0.toInt() and 0x3ffffff
        tp1 += tp0 ushr 26
        h1 = tp1.toInt() and 0x3ffffff
        tp2 += tp1 ushr 26
        h2 = tp2.toInt() and 0x3ffffff
        tp3 += tp2 ushr 26
        h3 = tp3.toInt() and 0x3ffffff
        tp4 += tp3 ushr 26
        h4 = tp4.toInt() and 0x3ffffff
        h0 += ((tp4 ushr 26) * 5).toInt()
        h1 += h0 ushr 26
        h0 = h0 and 0x3ffffff
    }

    fun doFinal(out: ByteArray, outOff: Int): Int {
        require(outOff + BLOCK_SIZE <= out.size)
        if (currentBlockOffset > 0) {
            // Process padded final block
            processBlock()
        }
        h1 += h0 ushr 26
        h0 = h0 and 0x3ffffff
        h2 += h1 ushr 26
        h1 = h1 and 0x3ffffff
        h3 += h2 ushr 26
        h2 = h2 and 0x3ffffff
        h4 += h3 ushr 26
        h3 = h3 and 0x3ffffff
        h0 += (h4 ushr 26) * 5
        h4 = h4 and 0x3ffffff
        h1 += h0 ushr 26
        h0 = h0 and 0x3ffffff
        var g0: Int
        var g1: Int
        var g2: Int
        var g3: Int
        val g4: Int
        var b: Int
        g0 = h0 + 5
        b = g0 ushr 26
        g0 = g0 and 0x3ffffff
        g1 = h1 + b
        b = g1 ushr 26
        g1 = g1 and 0x3ffffff
        g2 = h2 + b
        b = g2 ushr 26
        g2 = g2 and 0x3ffffff
        g3 = h3 + b
        b = g3 ushr 26
        g3 = g3 and 0x3ffffff
        g4 = h4 + b - (1 shl 26)
        b = (g4 ushr 31) - 1
        val nb = b.inv()
        h0 = h0 and nb or (g0 and b)
        h1 = h1 and nb or (g1 and b)
        h2 = h2 and nb or (g2 and b)
        h3 = h3 and nb or (g3 and b)
        h4 = h4 and nb or (g4 and b)
        val f0: Long
        var f1: Long
        var f2: Long
        var f3: Long
        f0 = ((h0 or (h1 shl 26)).toLong() and 0xffffffffL) + (0xffffffffL and k0.toLong())
        f1 = ((h1 ushr 6 or (h2 shl 20)).toLong() and 0xffffffffL) + (0xffffffffL and k1.toLong())
        f2 = ((h2 ushr 12 or (h3 shl 14)).toLong() and 0xffffffffL) + (0xffffffffL and k2.toLong())
        f3 = ((h3 ushr 18 or (h4 shl 8)).toLong() and 0xffffffffL) + (0xffffffffL and k3.toLong())
        Pack.writeInt32LE(f0.toInt(), out, outOff)
        f1 += f0 ushr 32
        Pack.writeInt32LE(f1.toInt(), out, outOff + 4)
        f2 += f1 ushr 32
        Pack.writeInt32LE(f2.toInt(), out, outOff + 8)
        f3 += f2 ushr 32
        Pack.writeInt32LE(f3.toInt(), out, outOff + 12)
        reset()
        return BLOCK_SIZE
    }

    fun reset() {
        currentBlockOffset = 0
        h4 = 0
        h3 = h4
        h2 = h3
        h1 = h2
        h0 = h1
    }

    companion object {
        private const val BLOCK_SIZE = 16
        private fun mul32x32_64(i1: Int, i2: Int): Long {
            return ((i1.toLong() and 0xFFFFFFFFL) * i2).toLong()
        }

        /**
         *
         * @param key   input key
         * @param datas input data
         * @return a 16 byte authentication tag
         */
        fun mac(key: ByteArray, vararg datas: ByteArray): ByteArray {
            val out = ByteArray(16)
            val poly = Poly1305()
            poly.setKey(key)
            datas.forEach { data -> poly.update(data, 0, data.size) }
            poly.doFinal(out, 0)
            return out
        }
    }
}