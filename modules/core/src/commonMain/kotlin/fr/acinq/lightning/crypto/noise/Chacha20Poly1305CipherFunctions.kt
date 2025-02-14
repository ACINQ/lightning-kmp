package fr.acinq.lightning.crypto.noise

import fr.acinq.lightning.crypto.ChaCha20Poly1305

expect object Chacha20Poly1305CipherFunctions : CipherFunctions {
    override fun name(): String
    override fun encrypt(k: ByteArray, n: Long, ad: ByteArray, plaintext: ByteArray): ByteArray
    override fun decrypt(k: ByteArray, n: Long, ad: ByteArray, ciphertextAndMac: ByteArray): ByteArray
}

/**
 * Default implementation for [Chacha20Poly1305CipherFunctions]. Can be used by modules by
 * defining a type alias.
 */
object Chacha20Poly1305CipherFunctionsDefault : CipherFunctions {
    override fun name(): String = "ChaChaPoly"

    // as specified in BOLT #8
    fun nonce(n: Long): ByteArray = ByteArray(4) + ChaCha20Poly1305.write64(n)

    // Encrypts plaintext using the cipher key k of 32 bytes and an 8-byte unsigned integer nonce n which must be unique.
    override fun encrypt(k: ByteArray, n: Long, ad: ByteArray, plaintext: ByteArray): ByteArray {
        val (ciphertext, mac) = ChaCha20Poly1305.encrypt(k, nonce(n), plaintext, ad)
        return ciphertext + mac
    }

    // Decrypts ciphertext using a cipher key k of 32 bytes, an 8-byte unsigned integer nonce n, and associated data ad.
    override fun decrypt(k: ByteArray, n: Long, ad: ByteArray, ciphertextAndMac: ByteArray): ByteArray {
        val ciphertext = ciphertextAndMac.dropLast(16).toByteArray()
        val mac = ciphertextAndMac.takeLast(16).toByteArray()
        return ChaCha20Poly1305.decrypt(k, nonce(n), ciphertext, ad, mac)
    }
}