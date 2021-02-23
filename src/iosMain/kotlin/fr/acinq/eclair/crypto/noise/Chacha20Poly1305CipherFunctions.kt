package fr.acinq.eclair.crypto.noise

import swift.phoenix_crypto.*
import fr.acinq.eclair.crypto.ChaCha20Poly1305
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.NSMutableData
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
    val size = this.size.toULong()
    val mData = NSMutableData()
    mData.setLength(size)
    if (size > 0uL) {
        this.usePinned { pinned ->
            memcpy(mData.mutableBytes, pinned.addressOf(0), size)
        }
    }
    val data = mData.copy() as NSData
    return data
}

actual object Chacha20Poly1305CipherFunctions : CipherFunctions {
    override fun name() = "ChaChaPoly"

    // as specified in BOLT #8
    fun nonce(n: Long): ByteArray = ByteArray(4) + ChaCha20Poly1305.write64(n)

    // Encrypts plaintext using the cipher key k of 32 bytes and an 8-byte unsigned integer nonce n which must be unique.
    override fun encrypt(k: ByteArray, n: Long, ad: ByteArray, plaintext: ByteArray): ByteArray {

        val ciphertextAndMac = NativeChaChaPoly.chachapoly_encryptWithKey(
            key = k.toNSData(),
            nonce = nonce(n).toNSData(),
            authenticatedData = ad.toNSData(),
            plaintext = plaintext.toNSData()
        )
        return ciphertextAndMac.toByteArray()
    }

    // Decrypts ciphertext using a cipher key k of 32 bytes, an 8-byte unsigned integer nonce n, and associated data ad.
    @Suppress("PARAMETER_NAME_CHANGED_ON_OVERRIDE")
    override fun decrypt(k: ByteArray, n: Long, ad: ByteArray, ciphertextAndMac: ByteArray): ByteArray {

        val plaintext = NativeChaChaPoly.chachapoly_decryptWithKey(
            key = k.toNSData(),
            nonce = nonce(n).toNSData(),
            authenticatedData = ad.toNSData(),
            ciphertextAndTag = ciphertextAndMac.toNSData()
        )
        return plaintext.toByteArray()
    }
}
