package fr.acinq.lightning.serialization.channel

import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.Crypto
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.lightning.channel.states.*
import fr.acinq.lightning.crypto.ChaCha20Poly1305
import fr.acinq.lightning.logging.MDCLogger
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
    fun PersistedChannelState.Companion.from(key: PrivateKey, encryptedChannelData: EncryptedChannelData): Result<Serialization.DeserializationResult> {
        // we first assume that channel data is prefixed by 2 bytes of serialization meta-info
        return runCatching { decrypt(key.value, encryptedChannelData.data.drop(2).toByteArray()) }
            .recoverCatching { decrypt(key.value, encryptedChannelData.data.toByteArray()) }
            .map { Serialization.deserialize(it) }
    }

    /**
     * Convenience method that builds an [EncryptedPeerStorage] from a list of [PersistedChannelState]
     */
    fun EncryptedPeerStorage.Companion.from(key: PrivateKey, states: List<PersistedChannelState>, logger: MDCLogger? = null): EncryptedPeerStorage {
        val sortedChannelStates = states.filterNot { it is Closed }.sortedBy { channelPriority(it) }
        return encryptChannelStates(key, sortedChannelStates, logger)
    }

    private fun channelPriority(state: PersistedChannelState): Int = when (state) {
        is Closed -> 0
        is WaitForRemotePublishFutureCommitment -> 0
        is Closing -> 1
        is Negotiating -> 2
        is ShuttingDown -> 3
        is WaitForChannelReady -> 4
        is WaitForFundingConfirmed -> 5
        is LegacyWaitForFundingConfirmed -> 5
        is WaitForFundingSigned -> 6
        is LegacyWaitForFundingLocked -> 6
        is Normal -> 7
    }

    private fun encryptChannelStates(key: PrivateKey, states: List<PersistedChannelState>, logger: MDCLogger?): EncryptedPeerStorage {
        val (versionByte, bin) = Serialization.serializePeerStorage(states)
        val encrypted = encrypt(key.value, bin)
        val data = byteArrayOf(versionByte) + encrypted
        return when {
            data.size <= 65531 -> EncryptedPeerStorage(data.toByteVector())
            states.size > 1 -> {
                logger?.warning { "dropping c:${states[0].channelId} from peer storage as it does not fit" }
                EncryptedPeerStorage.from(key, states.drop(1))
            }
            else -> {
                logger?.warning { "empty peer storage" }
                EncryptedPeerStorage.empty
            }
        }
    }

    /**
     * Convenience method that decrypts and deserializes a list of [PersistedChannelState] from an [EncryptedPeerStorage]
     */
    fun PersistedChannelState.Companion.fromEncryptedPeerStorage(key: PrivateKey, encryptedPeerStorage: EncryptedPeerStorage): Result<Serialization.PeerStorageDeserializationResult> {
        // we first assume that data is prefixed by 1 byte of serialization meta-info
        return runCatching { decrypt(key.value, encryptedPeerStorage.data.drop(1).toByteArray()) }
            .map { Serialization.deserializePeerStorage(encryptedPeerStorage.data[0], it) }
    }
}