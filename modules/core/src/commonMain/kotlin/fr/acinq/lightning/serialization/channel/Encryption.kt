package fr.acinq.lightning.serialization.channel

import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.Crypto
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.lightning.channel.states.PersistedChannelState
import fr.acinq.lightning.crypto.ChaCha20Poly1305
import fr.acinq.lightning.utils.toByteVector
import fr.acinq.lightning.wire.EncryptedChannelData
import fr.acinq.lightning.wire.EncryptedPeerStorage

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
     * Convenience method that decrypts and deserializes a [PersistedChannelState] from an [EncryptedChannelData]
     */
    fun PersistedChannelState.Companion.from(key: PrivateKey, encryptedChannelData: EncryptedChannelData): Result<Serialization.DeserializationResult> {
        // we first assume that channel data is prefixed by 2 bytes of serialization meta-info
        return runCatching { decrypt(key.value, encryptedChannelData.data.drop(2).toByteArray()) }
            .recoverCatching { decrypt(key.value, encryptedChannelData.data.toByteArray()) }
            .map { Serialization.deserialize(it) }
    }

    /**
     * Convenience method that builds an [EncryptedPeerStorage] from a list of [PersistedChannelState]
     */
    fun EncryptedPeerStorage.Companion.from(key: PrivateKey, states: List<PersistedChannelState>): EncryptedPeerStorage {
        val bin = Serialization.serializePeerStorage(states)
        val encrypted = encrypt(key.value, bin)
        // we copy the first byte as meta-info on the serialization version
        val data = bin.copyOfRange(0, 1) + encrypted
        return EncryptedPeerStorage(data.toByteVector())
    }

    /**
     * Convenience method that decrypts and deserializes a list of [PersistedChannelState] from an [EncryptedPeerStorage]
     */
    fun PersistedChannelState.Companion.fromEncryptedPeerStorage(key: PrivateKey, encryptedPeerStorage: EncryptedPeerStorage): Result<Serialization.PeerStorageDeserializationResult> {
        // we first assume that data is prefixed by 1 byte of serialization meta-info
        return runCatching { decrypt(key.value, encryptedPeerStorage.data.drop(1).toByteArray()) }
            .recoverCatching { decrypt(key.value, encryptedPeerStorage.data.toByteArray()) }
            .map { Serialization.deserializePeerStorage(it) }
    }
}