package fr.acinq.eklair.crypto

/**
 * ChaCha20Poly1305 AEAD (Authenticated Encryption with Additional Data) algorithm
 * see https://tools.ietf.org/html/rfc7539#section-2.5
 *
 * This what we should be using (see BOLT #8)
 */
object ChaCha20Poly1305 {

    abstract class ChaCha20Poly1305Error(msg: String) : RuntimeException(msg)
    object InvalidMac : ChaCha20Poly1305Error("invalid mac")
    object DecryptionError : ChaCha20Poly1305Error("decryption error")
    object EncryptionError : ChaCha20Poly1305Error("encryption error")
    object InvalidCounter : ChaCha20Poly1305Error("chacha20 counter must be 0 or 1")

    /**
     *
     * @param key       32 bytes encryption key
     * @param nonce     12 bytes nonce
     * @param plaintext plain text
     * @param aad       additional authentication data. can be empty
     * @return a (ciphertext, mac) tuple
     */
    fun encrypt(key: ByteArray, nonce: ByteArray, plaintext: ByteArray, aad: ByteArray): Pair<ByteArray, ByteArray> {
        val polykey = ChaCha20.encrypt(ByteArray(32), key, nonce)
        val ciphertext = ChaCha20.encrypt(plaintext, key, nonce, 1)
        val tag = Poly1305.mac(polykey, aad, pad16(aad), ciphertext, pad16(ciphertext), write64(aad.size), write64(ciphertext.size))
        return Pair(ciphertext, tag)
    }

    /**
     *
     * @param key        32 bytes decryption key
     * @param nonce      12 bytes nonce
     * @param ciphertext ciphertext
     * @param aad        additional authentication data. can be empty
     * @param mac        authentication mac
     * @return the decrypted plaintext if the mac is valid.
     */
    fun decrypt(key: ByteArray, nonce: ByteArray, ciphertext: ByteArray, aad: ByteArray, mac: ByteArray): ByteArray {
        val polykey = ChaCha20.encrypt(ByteArray(32), key, nonce)
        val tag = Poly1305.mac(polykey, aad, pad16(aad), ciphertext, pad16(ciphertext), write64(aad.size), write64(ciphertext.size))
        if (!tag.contentEquals(mac)) throw InvalidMac
        val plaintext = ChaCha20.decrypt(ciphertext, key, nonce, 1)
        return plaintext
    }

    fun pad16(data: ByteArray): ByteArray = if (data.size % 16 == 0) ByteArray(0) else ByteArray(16 - (data.size % 16))

    fun write64(input: Long): ByteArray {
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

    fun write64(input: Int): ByteArray = write64(input.toLong())
}
