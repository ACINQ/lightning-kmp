package fr.acinq.eclair.crypto.noise

import fr.acinq.eclair.crypto.ChaCha20Poly1305

object Chacha20Poly1305CipherFunctions : CipherFunctions {
    override fun name() = "ChaChaPoly"

    // as specified in BOLT #8
    fun nonce(n: Long): ByteArray = ByteArray(4) + ChaCha20Poly1305.write64(n)

    // Encrypts plaintext using the cipher key k of 32 bytes and an 8-byte unsigned integer nonce n which must be unique.
    override fun encrypt(k: ByteArray, n: Long, ad: ByteArray, plaintext: ByteArray): ByteArray {
        val (ciphertext, mac) = ChaCha20Poly1305.encrypt(k,
          nonce(n), plaintext, ad)
        return ciphertext + mac
    }

    // Decrypts ciphertext using a cipher key k of 32 bytes, an 8-byte unsigned integer nonce n, and associated data ad.
    @Suppress("PARAMETER_NAME_CHANGED_ON_OVERRIDE")
    override fun decrypt(k: ByteArray, n: Long, ad: ByteArray, ciphertextAndMac: ByteArray): ByteArray {
        val ciphertext = ciphertextAndMac.dropLast(16).toByteArray()
        val mac = ciphertextAndMac.takeLast(16).toByteArray()
        return ChaCha20Poly1305.decrypt(k,
          nonce(n), ciphertext, ad, mac)
    }
}
