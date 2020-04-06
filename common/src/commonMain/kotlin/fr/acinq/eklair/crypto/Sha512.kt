package fr.acinq.eklair.crypto

class Sha512: Digest {

  private val DIGEST_LENGTH = 64

  private val xBuf = ByteArray(8)
  private var xBufOff = 0

  private var byteCount1: Long = 0
  private var byteCount2: Long = 0

  private var H1:Long = 0
  private var H2:Long = 0
  private var H3:Long = 0
  private var H4:Long = 0
  private var H5:Long = 0
  private var H6:Long = 0
  private var H7:Long = 0
  private var H8:Long = 0

  private val W = LongArray(80)
  private var wOff = 0

  init {
    reset()
  }

  override fun getDigestSize(): Int {
    return DIGEST_LENGTH
  }

  override fun getAlgorithmName(): String {
    return "SHA-512"
  }

  override fun reset() {
    byteCount1 = 0
    byteCount2 = 0
    xBufOff = 0

    xBuf.fill(0.toByte())
    wOff = 0
    W.fill(0)

    H1 = 0x6a09e667f3bcc908L
    H2 = -0x4498517a7b3558c5L
    H3 = 0x3c6ef372fe94f82bL
    H4 = -0x5ab00ac5a0e2c90fL
    H5 = 0x510e527fade682d1L
    H6 = -0x64fa9773d4c193e1L
    H7 = 0x1f83d9abfb41bd6bL
    H8 = 0x5be0cd19137e2179L
  }

  override fun update(`in`: Byte) {
    xBuf[xBufOff++] = `in`
    if (xBufOff == xBuf.size) {
      processWord(xBuf, 0)
      xBufOff = 0
    }
    byteCount1++
  }

  override fun update(`in`: ByteArray, inOff: Int, len: Int) {
    // fill the current word
    var inOffset = inOff
    var length = len
    while (xBufOff != 0 && length > 0) {
      update(`in`[inOffset])
      inOffset++
      length--
    }

    // process whole words.
    while (length > xBuf.size) {
      processWord(`in`, inOffset)
      inOffset += xBuf.size
      length -= xBuf.size
      byteCount1 += xBuf.size.toLong()
    }

    // process the remainder.
    while (length > 0) {
      update(`in`[inOffset])
      inOffset++
      length--
    }
  }

  override fun doFinal(out: ByteArray, outOff: Int): Int {
    finish()
    unpackWord(H1, out, outOff)
    unpackWord(H2, out, outOff + 8)
    unpackWord(H3, out, outOff + 16)
    unpackWord(H4, out, outOff + 24)
    unpackWord(H5, out, outOff + 32)
    unpackWord(H6, out, outOff + 40)
    unpackWord(H7, out, outOff + 48)
    unpackWord(H8, out, outOff + 56)
    reset()
    return DIGEST_LENGTH
  }

  private fun finish() {
    adjustByteCounts()
    val lowBitLength = byteCount1 shl 3
    val hiBitLength = byteCount2

    // add the pad bytes.
    update(128.toByte())
    while (xBufOff != 0) update(0.toByte())

    processLength(lowBitLength, hiBitLength)
    processBlock()
  }

  private fun processWord(`in`: ByteArray, inOff: Int) {
    W[wOff++] = ((`in`[inOff].toInt() and 0xff).toLong() shl 56
        or ((`in`[inOff + 1].toInt() and 0xff).toLong() shl 48)
        or ((`in`[inOff + 2].toInt() and 0xff).toLong() shl 40)
        or ((`in`[inOff + 3].toInt() and 0xff).toLong() shl 32)
        or ((`in`[inOff + 4].toInt() and 0xff).toLong() shl 24)
        or ((`in`[inOff + 5].toInt() and 0xff).toLong() shl 16)
        or ((`in`[inOff + 6].toInt() and 0xff).toLong() shl 8)
        or (`in`[inOff + 7].toInt() and 0xff).toLong())
    if (wOff == 16) processBlock()
  }

  private fun unpackWord(word: Long, out: ByteArray, outOff: Int) {
    out[outOff] = (word ushr 56).toByte()
    out[outOff + 1] = (word ushr 48).toByte()
    out[outOff + 2] = (word ushr 40).toByte()
    out[outOff + 3] = (word ushr 32).toByte()
    out[outOff + 4] = (word ushr 24).toByte()
    out[outOff + 5] = (word ushr 16).toByte()
    out[outOff + 6] = (word ushr 8).toByte()
    out[outOff + 7] = word.toByte()
  }

  /**
   * adjust the byte counts so that byteCount2 represents the
   * upper long (less 3 bits) word of the byte count.
   */
  private fun adjustByteCounts() {
    if (byteCount1 > 0x1fffffffffffffffL) {
      byteCount2 += byteCount1 ushr 61
      byteCount1 = byteCount1 and 0x1fffffffffffffffL
    }
  }

  private fun processLength(lowW: Long, hiW: Long) {
    if (wOff > 14) {
      processBlock()
    }
    W[14] = hiW
    W[15] = lowW
  }

  private fun processBlock() {
    adjustByteCounts()

    // expand 16 word block into 80 word blocks.
    for (t in 16..79) W[t] = Sigma1(W[t - 2]) + W[t - 7] + Sigma0(W[t - 15]) + W[t - 16]

    var a = H1
    var b: Long = H2
    var c: Long = H3
    var d: Long = H4
    var e: Long = H5
    var f: Long = H6
    var g: Long = H7
    var h: Long = H8
    for (t in 0..79) {
      val T1: Long = h + Sum1(e) + Ch(e, f, g) + K[t] + W[t]
      val T2: Long = Sum0(a) + Maj(a, b, c)
      h = g
      g = f
      f = e
      e = d + T1
      d = c
      c = b
      b = a
      a = T1 + T2
    }
    H1 += a
    H2 += b
    H3 += c
    H4 += d
    H5 += e
    H6 += f
    H7 += g
    H8 += h

    // reset the offset and clean out the word buffer.
    wOff = 0
    for (i in W.indices) W[i] = 0
  }

  private fun rotateRight(x: Long, n: Int): Long {
    return x ushr n or (x shl 64 - n)
  }

  /* sha-384 and sha-512 functions (as for sha-256 but for longs) */
  private fun Ch(x: Long, y: Long, z: Long): Long {
    return x and y xor (x.inv() and z)
  }

  private fun Maj(x: Long, y: Long, z: Long): Long {
    return x and y xor (x and z) xor (y and z)
  }

  private fun Sum0(x: Long): Long {
    return rotateRight(x, 28) xor rotateRight(x, 34) xor rotateRight(x, 39)
  }

  private fun Sum1(x: Long): Long {
    return rotateRight(x, 14) xor rotateRight(x, 18) xor rotateRight(x, 41)
  }

  private fun Sigma0(x: Long): Long {
    return rotateRight(x, 1) xor rotateRight(x, 8) xor (x ushr 7)
  }

  private fun Sigma1(x: Long): Long {
    return rotateRight(x, 19) xor rotateRight(x, 61) xor (x ushr 6)
  }

  companion object {

    fun hash(input: ByteArray, offset: Int, len: Int): ByteArray {
      val sha512 = Sha512()
      sha512.update(input, offset, len)
      val output = ByteArray(64)
      sha512.doFinal(output, 0)
      return output
    }

    fun hash(input: ByteArray): ByteArray =
      hash(input, 0, input.size)

    val K = longArrayOf(
        0x428a2f98d728ae22L, 0x7137449123ef65cdL, -0x4a3f043013b2c4d1L, -0x164a245a7e762444L,
        0x3956c25bf348b538L, 0x59f111f1b605d019L, -0x6dc07d5b50e6b065L, -0x54e3a12a25927ee8L,
        -0x27f855675cfcfdbeL, 0x12835b0145706fbeL, 0x243185be4ee4b28cL, 0x550c7dc3d5ffb4e2L,
        0x72be5d74f27b896fL, -0x7f214e01c4e9694fL, -0x6423f958da38edcbL, -0x3e640e8b3096d96cL,
        -0x1b64963e610eb52eL, -0x1041b879c7b0da1dL, 0x0fc19dc68b8cd5b5L, 0x240ca1cc77ac9c65L,
        0x2de92c6f592b0275L, 0x4a7484aa6ea6e483L, 0x5cb0a9dcbd41fbd4L, 0x76f988da831153b5L,
        -0x67c1aead11992055L, -0x57ce3992d24bcdf0L, -0x4ffcd8376704dec1L, -0x40a680384110f11cL,
        -0x391ff40cc257703eL, -0x2a586eb86cf558dbL, 0x06ca6351e003826fL, 0x142929670a0e6e70L,
        0x27b70a8546d22ffcL, 0x2e1b21385c26c926L, 0x4d2c6dfc5ac42aedL, 0x53380d139d95b3dfL,
        0x650a73548baf63deL, 0x766a0abb3c77b2a8L, -0x7e3d36d1b812511aL, -0x6d8dd37aeb7dcac5L,
        -0x5d40175eb30efc9cL, -0x57e599b443bdcfffL, -0x3db4748f2f07686fL, -0x3893ae5cf9ab41d0L,
        -0x2e6d17e62910ade8L, -0x2966f9dbaa9a56f0L, -0xbf1ca7aa88edfd6L, 0x106aa07032bbd1b8L,
        0x19a4c116b8d2d0c8L, 0x1e376c085141ab53L, 0x2748774cdf8eeb99L, 0x34b0bcb5e19b48a8L,
        0x391c0cb3c5c95a63L, 0x4ed8aa4ae3418acbL, 0x5b9cca4f7763e373L, 0x682e6ff3d6b2b8a3L,
        0x748f82ee5defb2fcL, 0x78a5636f43172f60L, -0x7b3787eb5e0f548eL, -0x7338fdf7e59bc614L,
        -0x6f410005dc9ce1d8L, -0x5baf9314217d4217L, -0x41065c084d3986ebL, -0x398e870d1c8dacd5L,
        -0x35d8c13115d99e64L, -0x2e794738de3f3df9L, -0x15258229321f14e2L, -0xa82b08011912e88L,
        0x06f067aa72176fbaL, 0x0a637dc5a2c898a6L, 0x113f9804bef90daeL, 0x1b710b35131c471bL,
        0x28db77f523047d84L, 0x32caab7b40c72493L, 0x3c9ebe0a15c9bebcL, 0x431d67c49c100d4cL,
        0x4cc5d4becb3e42b6L, 0x597f299cfc657e2aL, 0x5fcb6fab3ad6faecL, 0x6c44198c4a475817L
    )
  }

}
