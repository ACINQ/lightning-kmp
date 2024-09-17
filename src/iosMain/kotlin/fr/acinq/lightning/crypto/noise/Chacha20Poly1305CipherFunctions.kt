package fr.acinq.lightning.crypto.noise

import swift.phoenix_crypto.*
import fr.acinq.lightning.crypto.ChaCha20Poly1305
import fr.acinq.lightning.utils.*
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.autoreleasepool


actual object Chacha20Poly1305CipherFunctions : CipherFunctions {
    actual override fun name() = "ChaChaPoly"

    // as specified in BOLT #8
    fun nonce(n: Long): ByteArray = ByteArray(4) + ChaCha20Poly1305.write64(n)

    // Encrypts plaintext using the cipher key k of 32 bytes and an 8-byte unsigned integer nonce n which must be unique.
    @OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
    actual override fun encrypt(k: ByteArray, n: Long, ad: ByteArray, plaintext: ByteArray): ByteArray {
        autoreleasepool {
            val ciphertextAndMac = NativeChaChaPoly.chachapoly_encryptWithKey(
                key = k.toNSData(),
                nonce = nonce(n).toNSData(),
                authenticatedData = ad.toNSData(),
                plaintext = plaintext.toNSData()
            )
            return ciphertextAndMac.toByteArray()
        }
    }


    // Decrypts ciphertext using a cipher key k of 32 bytes, an 8-byte unsigned integer nonce n, and associated data ad.
    @OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
    @Suppress("PARAMETER_NAME_CHANGED_ON_OVERRIDE")
    actual override fun decrypt(k: ByteArray, n: Long, ad: ByteArray, ciphertextAndMac: ByteArray): ByteArray {
        autoreleasepool {
            val plaintext = NativeChaChaPoly.chachapoly_decryptWithKey(
                key = k.toNSData(),
                nonce = nonce(n).toNSData(),
                authenticatedData = ad.toNSData(),
                ciphertextAndTag = ciphertextAndMac.toNSData()
            )
            return plaintext.toByteArray()
        }
    }
}
