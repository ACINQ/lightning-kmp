package fr.acinq.eklair.crypto

class Sha256 : Digest {
    private val DIGEST_LENGTH = 32

    private val xBuf = ByteArray(4)
    private var xBufOff = 0

    private var byteCount: Long = 0

    private var H1 = 0
    private var H2: Int = 0
    private var H3: Int = 0
    private var H4: Int = 0
    private var H5: Int = 0
    private var H6: Int = 0
    private var H7: Int = 0
    private var H8: Int = 0

    private val X = IntArray(64)
    private var xOff = 0

    /**
     * Standard constructor
     */
    init {
        reset()
    }

    override fun getAlgorithmName(): String {
        return "SHA-256"
    }

    override fun getDigestSize(): Int {
        return DIGEST_LENGTH
    }

    override fun update(`in`: Byte) {
        xBuf[xBufOff++] = `in`
        if (xBufOff == xBuf.size) {
            processWord(xBuf, 0)
            xBufOff = 0
        }
        byteCount++
    }

    override fun update(`in`: ByteArray, inOff: Int, len: Int) {
        var len = len
        len = kotlin.math.max(0, len)

        //
        // fill the current word
        //
        var i = 0
        if (xBufOff != 0) {
            while (i < len) {
                xBuf[xBufOff++] = `in`[inOff + i++]
                if (xBufOff == 4) {
                    processWord(xBuf, 0)
                    xBufOff = 0
                    break
                }
            }
        }

        //
        // process whole words.
        //
        val limit = (len - i and 3.inv()) + i
        while (i < limit) {
            processWord(`in`, inOff + i)
            i += 4
        }

        //
        // load in the remainder.
        //
        while (i < len) {
            xBuf[xBufOff++] = `in`[inOff + i++]
        }
        byteCount += len.toLong()
    }

    private fun processWord(`in`: ByteArray, inOff: Int) {
        // Note: Inlined for performance
//        X[xOff] = Pack.bigEndianToInt(in, inOff);
        var inOff = inOff
        var n: Int = (`in`[inOff].toInt() and 0xff) shl 24
        n = n or ((`in`[++inOff].toInt() and 0xff) shl 16)
        n = n or ((`in`[++inOff].toInt() and 0xff) shl 8)
        n = n or ((`in`[++inOff].toInt() and 0xff))
        X[xOff] = n
        if (++xOff == 16) {
            processBlock()
        }
    }

    private fun processLength(bitLength: Long) {
        if (xOff > 14) {
            processBlock()
        }
        X[14] = (bitLength ushr 32).toInt()
        X[15] = (bitLength and -0x1).toInt()
    }

    override fun doFinal(out: ByteArray, outOff: Int): Int {
        finish()
      Pack.intToBigEndian(H1, out, outOff)
      Pack.intToBigEndian(H2, out, outOff + 4)
      Pack.intToBigEndian(H3, out, outOff + 8)
      Pack.intToBigEndian(H4, out, outOff + 12)
      Pack.intToBigEndian(H5, out, outOff + 16)
      Pack.intToBigEndian(H6, out, outOff + 20)
      Pack.intToBigEndian(H7, out, outOff + 24)
      Pack.intToBigEndian(H8, out, outOff + 28)
        reset()
        return DIGEST_LENGTH
    }

    /**
     * reset the chaining variables
     */
    override fun reset() {
        byteCount = 0

        xBufOff = 0
        for (i in xBuf.indices) {
            xBuf[i] = 0
        }

        /* SHA-256 initial hash value
         * The first 32 bits of the fractional parts of the square roots
         * of the first eight prime numbers
         */
        H1 = 0x6a09e667
        H2 = -0x4498517b
        H3 = 0x3c6ef372
        H4 = -0x5ab00ac6
        H5 = 0x510e527f
        H6 = -0x64fa9774
        H7 = 0x1f83d9ab
        H8 = 0x5be0cd19
        xOff = 0
        for (i in X.indices) {
            X[i] = 0
        }
    }

    private fun finish() {
        val bitLength: Long = byteCount shl 3

        //
        // add the pad bytes.
        //
        update(128.toByte())
        while (xBufOff != 0) {
            update(0.toByte())
        }
        processLength(bitLength)
        processBlock()
    }

    private fun processBlock() {
        //
        // expand 16 word block into 64 word blocks.
        //
        for (t in 16..63) {
            X[t] = Theta1(X[t - 2]) + X[t - 7] + Theta0(
              X[t - 15]
            ) + X[t - 16]
        }

        //
        // set up working variables.
        //
        var a = H1
        var b: Int = H2
        var c: Int = H3
        var d: Int = H4
        var e: Int = H5
        var f: Int = H6
        var g: Int = H7
        var h: Int = H8
        var t = 0
        for (i in 0..7) {
            // t = 8 * i
            h += Sum1(e) + Ch(
              e,
              f,
              g
            ) + K[t] + X[t]
            d += h
            h += Sum0(a) + Maj(
              a,
              b,
              c
            )
            ++t

            // t = 8 * i + 1
            g += Sum1(d) + Ch(
              d,
              e,
              f
            ) + K[t] + X[t]
            c += g
            g += Sum0(h) + Maj(
              h,
              a,
              b
            )
            ++t

            // t = 8 * i + 2
            f += Sum1(c) + Ch(
              c,
              d,
              e
            ) + K[t] + X[t]
            b += f
            f += Sum0(g) + Maj(
              g,
              h,
              a
            )
            ++t

            // t = 8 * i + 3
            e += Sum1(b) + Ch(
              b,
              c,
              d
            ) + K[t] + X[t]
            a += e
            e += Sum0(f) + Maj(
              f,
              g,
              h
            )
            ++t

            // t = 8 * i + 4
            d += Sum1(a) + Ch(
              a,
              b,
              c
            ) + K[t] + X[t]
            h += d
            d += Sum0(e) + Maj(
              e,
              f,
              g
            )
            ++t

            // t = 8 * i + 5
            c += Sum1(h) + Ch(
              h,
              a,
              b
            ) + K[t] + X[t]
            g += c
            c += Sum0(d) + Maj(
              d,
              e,
              f
            )
            ++t

            // t = 8 * i + 6
            b += Sum1(g) + Ch(
              g,
              h,
              a
            ) + K[t] + X[t]
            f += b
            b += Sum0(c) + Maj(
              c,
              d,
              e
            )
            ++t

            // t = 8 * i + 7
            a += Sum1(f) + Ch(
              f,
              g,
              h
            ) + K[t] + X[t]
            e += a
            a += Sum0(b) + Maj(
              b,
              c,
              d
            )
            ++t
        }
        H1 += a
        H2 += b
        H3 += c
        H4 += d
        H5 += e
        H6 += f
        H7 += g
        H8 += h

        //
        // reset the offset and clean out the word buffer.
        //
        xOff = 0
        for (i in 0..15) {
            X[i] = 0
        }
    }


    companion object {
        fun hash(input: ByteArray, offset: Int, len: Int): ByteArray {
            val sha256 = Sha256()
            sha256.update(input, offset, len)
            val output = ByteArray(32)
            sha256.doFinal(output, 0)
            return output
        }

        fun hash(input: ByteArray): ByteArray =
          hash(input, 0, input.size)

        /* SHA-256 functions */
        private fun Ch(x: Int, y: Int, z: Int): Int {
            return x and y xor (x.inv() and z)
//        return z ^ (x & (y ^ z));
        }

        private fun Maj(x: Int, y: Int, z: Int): Int {
//        return (x & y) ^ (x & z) ^ (y & z);
            return x and y or (z and (x xor y))
        }

        private fun Sum0(x: Int): Int {
            return x ushr 2 or (x shl 30) xor (x ushr 13 or (x shl 19)) xor (x ushr 22 or (x shl 10))
        }

        private fun Sum1(x: Int): Int {
            return x ushr 6 or (x shl 26) xor (x ushr 11 or (x shl 21)) xor (x ushr 25 or (x shl 7))
        }

        private fun Theta0(x: Int): Int {
            return x ushr 7 or (x shl 25) xor (x ushr 18 or (x shl 14)) xor (x ushr 3)
        }

        private fun Theta1(x: Int): Int {
            return x ushr 17 or (x shl 15) xor (x ushr 19 or (x shl 13)) xor (x ushr 10)
        }

        /* SHA-256 Constants
         * (represent the first 32 bits of the fractional parts of the
         * cube roots of the first sixty-four prime numbers)
         */
        val K = intArrayOf(
                0x428a2f98, 0x71374491, -0x4a3f0431, -0x164a245b, 0x3956c25b, 0x59f111f1, -0x6dc07d5c, -0x54e3a12b,
                -0x27f85568, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, -0x7f214e02, -0x6423f959, -0x3e640e8c,
                -0x1b64963f, -0x1041b87a, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
                -0x67c1aeae, -0x57ce3993, -0x4ffcd838, -0x40a68039, -0x391ff40d, -0x2a586eb9, 0x06ca6351, 0x14292967,
                0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, -0x7e3d36d2, -0x6d8dd37b,
                -0x5d40175f, -0x57e599b5, -0x3db47490, -0x3893ae5d, -0x2e6d17e7, -0x2966f9dc, -0xbf1ca7b, 0x106aa070,
                0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
                0x748f82ee, 0x78a5636f, -0x7b3787ec, -0x7338fdf8, -0x6f410006, -0x5baf9315, -0x41065c09, -0x398e870e
        )
    }
}