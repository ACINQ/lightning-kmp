package fr.acinq.lightning.serialization

import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.Crypto
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.lightning.channel.PersistedChannelState
import fr.acinq.lightning.crypto.ChaCha20Poly1305
import fr.acinq.lightning.utils.runTrying
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
        return ChaCha20Poly1305.decrypt(key.toByteArray(), nonce.toByteArray(), ciphertext.toByteArray(), ByteArray(0), tag.toByteArray())
    }

    /**
     * Convenience method that builds an [EncryptedChannelData] from a [PersistedChannelState]
     */
    fun EncryptedChannelData.Companion.from(key: PrivateKey, state: PersistedChannelState): EncryptedChannelData {
        val bin = Serialization.serialize(state)
        val encrypted = encrypt(key.value, bin)
        // we copy the first 2 bytes as meta-info on the serialization version
        val data = bin.copyOfRange(0, 2) + encrypted
        return EncryptedChannelData(data.toByteVector())
    }

    /**
     * Convenience method that decrypts and deserializes a [PersistedChannelState] from an [EncryptedChannelData]
     */
    fun PersistedChannelState.Companion.from(key: PrivateKey, encryptedChannelData: EncryptedChannelData): PersistedChannelState {
        // we first assume that channel data is prefixed by 2 bytes of serialization meta-info
        val decrypted = runTrying { decrypt(key.value, encryptedChannelData.data.drop(2).toByteArray()) }
            .recoverWith { runTrying { decrypt(key.value, encryptedChannelData.data.toByteArray()) } }
            .get()
        return Serialization.deserialize(decrypted)
    }

}