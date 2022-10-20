package fr.acinq.lightning.serialization

import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.Crypto
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.lightning.channel.ChannelContext
import fr.acinq.lightning.channel.ChannelStateWithCommitments
import fr.acinq.lightning.crypto.ChaCha20Poly1305
import fr.acinq.lightning.utils.toByteVector
import fr.acinq.lightning.wire.EncryptedChannelData

/**
 * Utility methods to encrypt/decrypt serialized channel data
 */
object Encryption {

    private fun encrypt(key: ByteVector32, bin: ByteArray): ByteArray {
        // NB: there is a chance of collision here, due to how the nonce is calculated. Probability of collision is once every 2.2E19 times.
        // See https://en.wikipedia.org/wiki/Birthday_attack
        val nonce = Crypto.sha256(bin).take(12).toByteArray()
        val (ciphertext, tag) = ChaCha20Poly1305.encrypt(key.toByteArray(), nonce, bin, ByteArray(0))
        return ciphertext + nonce + tag
    }

    private fun decrypt(key: ByteVector32, data: ByteArray): ByteArray {
        // nonce is 12B, tag is 16B
        val ciphertext = data.dropLast(12 + 16)
        val nonce = data.takeLast(12 + 16).take(12)
        val tag = data.takeLast(16)
        val plaintext = ChaCha20Poly1305.decrypt(key.toByteArray(), nonce.toByteArray(), ciphertext.toByteArray(), ByteArray(0), tag.toByteArray())
        return plaintext
    }

    /**
     * Convenience method that builds an [EncryptedChannelData] from a [ChannelStateWithCommitments]
     */
    fun EncryptedChannelData.Companion.from(key: PrivateKey, ctx: ChannelContext, state: ChannelStateWithCommitments): EncryptedChannelData {
        val bin = Serialization.serialize(ctx, state)
        val encrypted = encrypt(key.value, bin)
        return EncryptedChannelData(encrypted.toByteVector())
    }

    /**
     * Convenience method that decrypts and deserializes a [ChannelStateWithCommitments] from an [EncryptedChannelData]
     */
    fun ChannelStateWithCommitments.Companion.from(key: PrivateKey, encryptedChannelData: EncryptedChannelData): ChannelStateWithCommitments {
        val decrypted = decrypt(key.value, encryptedChannelData.data.toByteArray())
        val state = Serialization.deserialize(decrypted)
        return state
    }

}