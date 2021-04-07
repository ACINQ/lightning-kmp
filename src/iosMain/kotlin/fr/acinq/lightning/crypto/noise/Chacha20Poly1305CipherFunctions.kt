package fr.acinq.lightning.crypto.noise

import swift.phoenix_crypto.*
import fr.acinq.lightning.crypto.ChaCha20Poly1305
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.autoreleasepool
import kotlinx.cinterop.pin
import kotlinx.cinterop.usePinned
import platform.Foundation.create
import platform.Foundation.NSData
import platform.posix.memcpy

fun NSData.toByteArray(): ByteArray {
    val data = this
    return ByteArray(data.length.toInt()).apply {
        if (data.length > 0uL) {
            usePinned { pinned ->
                memcpy(pinned.addressOf(0), data.bytes, data.length)
            }
        }
    }
}

fun ByteArray.toNSData(): NSData {
    if (isEmpty()) return NSData()
    val pinned = pin()
    return NSData.create(
        bytesNoCopy = pinned.addressOf(0),
        length = size.toULong(),
        deallocator = { _, _ -> pinned.unpin() }
    )
}


actual object Chacha20Poly1305CipherFunctions : CipherFunctions {
    override fun name() = "ChaChaPoly"

    // as specified in BOLT #8
    fun nonce(n: Long): ByteArray = ByteArray(4) + ChaCha20Poly1305.write64(n)

    // Encrypts plaintext using the cipher key k of 32 bytes and an 8-byte unsigned integer nonce n which must be unique.
    override fun encrypt(k: ByteArray, n: Long, ad: ByteArray, plaintext: ByteArray): ByteArray {
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
    @Suppress("PARAMETER_NAME_CHANGED_ON_OVERRIDE")
    override fun decrypt(k: ByteArray, n: Long, ad: ByteArray, ciphertextAndMac: ByteArray): ByteArray {
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
