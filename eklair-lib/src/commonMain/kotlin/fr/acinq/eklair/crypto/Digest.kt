package fr.acinq.eklair.crypto

interface Digest {
    /**
     * return the algorithm name
     *
     * @return the algorithm name
     */
    fun getAlgorithmName(): String

    /**
     * return the size, in bytes, of the digest produced by this message digest.
     *
     * @return the size, in bytes, of the digest produced by this message digest.
     */
    fun getDigestSize(): Int

    /**
     * update the message digest with a single byte.
     *
     * @param in the input byte to be entered.
     */
    fun update(`in`: Byte)

    /**
     * update the message digest with a block of bytes.
     *
     * @param in the byte array containing the data.
     * @param inOff the offset into the byte array where the data starts.
     * @param len the length of the data.
     */
    fun update(`in`: ByteArray, inOff: Int, len: Int)

    /**
     * close the digest, producing the final digest value. The doFinal
     * call leaves the digest reset.
     *
     * @param out the array the digest is to be copied into.
     * @param outOff the offset into the out array the digest is to start at.
     */
    fun doFinal(out: ByteArray, outOff: Int): Int

    /**
     * reset the digest back to it's initial state.
     */
    fun reset()
}