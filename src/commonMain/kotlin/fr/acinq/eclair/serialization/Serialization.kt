package fr.acinq.eclair.serialization

import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.Crypto
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.eclair.NodeParams
import fr.acinq.eclair.crypto.ChaCha20Poly1305
import fr.acinq.eclair.serialization.v1.*
import fr.acinq.eclair.utils.toByteVector
import fr.acinq.eclair.wire.Tlv
import fr.acinq.eclair.wire.UpdateMessage
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass


object Serialization {
    /**
     * Versioned serialized data.
     *
     * @README DO NOT change the structure of this class !!
     *
     * If a new serialization format is added, just change the `version` field and update serialize()/deserialized() methods
     * @param version version of the serialization algorithm
     * @param data serialized data
     */
    @Serializable
    private data class SerializedData(val version: Int, @Serializable(with = ByteVectorKSerializer::class) val data: ByteVector)

    val serializersModule = SerializersModule {
        polymorphic(ChannelStateWithCommitments::class) {
            subclass(Normal::class)
            subclass(WaitForFundingConfirmed::class)
            subclass(WaitForFundingLocked::class)
            subclass(WaitForRemotePublishFutureCommitment::class)
            subclass(ShuttingDown::class)
            subclass(Negotiating::class)
            subclass(Closing::class)
        }
    }

    private val serializationModules = SerializersModule {
        include(Tlv.serializersModule)
        include(UpdateMessage.serializersModule)
    }

    @OptIn(ExperimentalSerializationApi::class)
    private val cbor = Cbor {
        serializersModule = serializationModules
    }

    @OptIn(ExperimentalSerializationApi::class)
    private fun serialize(state: ChannelStateWithCommitments): ByteArray {
        val raw = cbor.encodeToByteArray(ChannelStateWithCommitments.serializer(), state)
        val versioned = SerializedData(version = 1, data = raw.toByteVector())
        return cbor.encodeToByteArray(SerializedData.serializer(), versioned)
    }

    @OptIn(ExperimentalSerializationApi::class)
    fun serialize(state: fr.acinq.eclair.channel.ChannelStateWithCommitments): ByteArray {
        return serialize(ChannelStateWithCommitments.import(state))
    }

    @OptIn(ExperimentalSerializationApi::class)
    fun deserialize(bin: ByteArray, nodeParams: NodeParams): fr.acinq.eclair.channel.ChannelStateWithCommitments {
        val versioned = cbor.decodeFromByteArray(SerializedData.serializer(), bin)
        return when(versioned.version) {
            1 -> cbor.decodeFromByteArray(ChannelStateWithCommitments.serializer(), versioned.data.toByteArray()).export(nodeParams)
            else -> error("unknown serialization version ${versioned.version}")
        }
    }

    @OptIn(ExperimentalSerializationApi::class)
    private fun deserialize(bin: ByteVector, nodeParams: NodeParams): fr.acinq.eclair.channel.ChannelStateWithCommitments = deserialize(bin.toByteArray(), nodeParams)

    @OptIn(ExperimentalSerializationApi::class)
    fun encrypt(key: ByteVector32, state: ChannelStateWithCommitments): ByteArray {
        val bin = serialize(state)
        // NB: there is a chance of collision here, due to how the nonce is calculated. Probability of collision is once every 2.2E19 times.
        // See https://en.wikipedia.org/wiki/Birthday_attack
        val nonce = Crypto.sha256(bin).take(12).toByteArray()
        val (ciphertext, tag) = ChaCha20Poly1305.encrypt(key.toByteArray(), nonce, bin, ByteArray(0))
        return ciphertext + nonce + tag
    }

    @OptIn(ExperimentalSerializationApi::class)
    fun encrypt(key: ByteVector32, state: fr.acinq.eclair.channel.ChannelStateWithCommitments): ByteArray {
        val bin = serialize(state)
        // NB: there is a chance of collision here, due to how the nonce is calculated. Probability of collision is once every 2.2E19 times.
        // See https://en.wikipedia.org/wiki/Birthday_attack
        val nonce = Crypto.sha256(bin).take(12).toByteArray()
        val (ciphertext, tag) = ChaCha20Poly1305.encrypt(key.toByteArray(), nonce, bin, ByteArray(0))
        return ciphertext + nonce + tag
    }

    fun encrypt(key: PrivateKey, state: fr.acinq.eclair.channel.ChannelStateWithCommitments): ByteArray = encrypt(key.value, state)

    @OptIn(ExperimentalSerializationApi::class)
    fun decrypt(key: ByteVector32, data: ByteArray, nodeParams: NodeParams): fr.acinq.eclair.channel.ChannelStateWithCommitments {
        // nonce is 12B, tag is 16B
        val ciphertext = data.dropLast(12 + 16)
        val nonce = data.takeLast(12 + 16).take(12)
        val tag = data.takeLast(16)
        val plaintext = ChaCha20Poly1305.decrypt(key.toByteArray(), nonce.toByteArray(), ciphertext.toByteArray(), ByteArray(0), tag.toByteArray())
        return deserialize(plaintext, nodeParams)
    }

    fun decrypt(key: PrivateKey, data: ByteArray, nodeParams: NodeParams): fr.acinq.eclair.channel.ChannelStateWithCommitments = decrypt(key.value, data, nodeParams)

    fun decrypt(key: PrivateKey, data: ByteVector, nodeParams: NodeParams): fr.acinq.eclair.channel.ChannelStateWithCommitments = decrypt(key, data.toByteArray(), nodeParams)
}