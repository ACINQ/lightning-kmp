package fr.acinq.lightning.serialization.v1

import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.Crypto
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.lightning.NodeParams
import fr.acinq.lightning.crypto.ChaCha20Poly1305
import fr.acinq.lightning.utils.toByteVector
import fr.acinq.lightning.wire.*
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.contextual
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass

object Serialization {
    /**
     * Versioned serialized data.
     *
     * @README DO NOT change the structure of this class !!
     *
     * If a new serialization format is added, just change the `version` field and update serialize()/deserialize() methods
     * @param version version of the serialization algorithm
     * @param data serialized data
     */
    @Serializable
    private data class SerializedData(val version: Int, @Serializable(with = ByteVectorKSerializer::class) val data: ByteVector)

    private val updateSerializersModule = SerializersModule {
        polymorphic(UpdateMessage::class) {
            subclass(UpdateAddHtlc.serializer())
            subclass(UpdateFailHtlc.serializer())
            subclass(UpdateFailMalformedHtlc.serializer())
            subclass(UpdateFee.serializer())
            subclass(UpdateFulfillHtlc.serializer())
        }
    }

    private val tlvSerializersModule = SerializersModule {
        polymorphic(Tlv::class) {
            subclass(ChannelTlv.UpfrontShutdownScriptTlv.serializer())
            subclass(ChannelTlv.ChannelVersionTlv.serializer())
            subclass(ChannelTlv.ChannelOriginTlv.serializer())
            subclass(InitTlv.Networks.serializer())
            subclass(OnionPaymentPayloadTlv.AmountToForward.serializer())
            subclass(OnionPaymentPayloadTlv.OutgoingCltv.serializer())
            subclass(OnionPaymentPayloadTlv.OutgoingChannelId.serializer())
            subclass(OnionPaymentPayloadTlv.PaymentData.serializer())
            subclass(OnionPaymentPayloadTlv.InvoiceFeatures.serializer())
            subclass(OnionPaymentPayloadTlv.OutgoingNodeId.serializer())
            subclass(OnionPaymentPayloadTlv.InvoiceRoutingInfo.serializer())
            subclass(OnionPaymentPayloadTlv.TrampolineOnion.serializer())
            subclass(GenericTlv.serializer())
        }
    }

    private val serializersModule = SerializersModule {
        polymorphic(ChannelStateWithCommitments::class) {
            subclass(Normal::class)
            subclass(WaitForFundingConfirmed::class)
            subclass(WaitForFundingLocked::class)
            subclass(WaitForRemotePublishFutureCommitment::class)
            subclass(ShuttingDown::class)
            subclass(Negotiating::class)
            subclass(Closing::class)
            subclass(Closed::class)
            subclass(ErrorInformationLeak::class)
        }
    }

    private val serializationModules = SerializersModule {
        include(tlvSerializersModule)
        include(updateSerializersModule)
        include(SerializersModule {
            contextual(ByteVector64KSerializer)
            contextual(ByteVector32KSerializer)
            contextual(ByteVectorKSerializer)
            contextual(SatoshiKSerializer)
            contextual(PrivateKeyKSerializer)
            contextual(PublicKeyKSerializer)
            contextual(OutPointKSerializer)
            contextual(TxInKSerializer)
            contextual(TxOutKSerializer)
            contextual(TransactionKSerializer)
            contextual(BlockHeaderKSerializer)
        })
    }

    // used by the "test node" JSON API
    val lightningSerializersModule = SerializersModule {
        include(serializersModule)
        include(serializationModules)
    }

    @OptIn(ExperimentalSerializationApi::class)
    val cbor = Cbor {
        serializersModule = serializationModules
    }

    @OptIn(ExperimentalSerializationApi::class)
    fun serialize(state: ChannelStateWithCommitments): ByteArray {
        val raw = cbor.encodeToByteArray(ChannelStateWithCommitments.serializer(), state)
        val versioned = SerializedData(version = 1, data = raw.toByteVector())
        return cbor.encodeToByteArray(SerializedData.serializer(), versioned)
    }

    @OptIn(ExperimentalSerializationApi::class)
    fun serialize(state: fr.acinq.lightning.channel.ChannelStateWithCommitments): ByteArray {
        return serialize(ChannelStateWithCommitments.import(state))
    }

    @OptIn(ExperimentalSerializationApi::class)
    fun deserialize(bin: ByteArray, nodeParams: NodeParams): fr.acinq.lightning.channel.ChannelStateWithCommitments {
        val versioned = cbor.decodeFromByteArray(SerializedData.serializer(), bin)
        return when (versioned.version) {
            1 -> cbor.decodeFromByteArray(ChannelStateWithCommitments.serializer(), versioned.data.toByteArray()).export(nodeParams)
            else -> error("unknown serialization version ${versioned.version}")
        }
    }

    @OptIn(ExperimentalSerializationApi::class)
    private fun deserialize(bin: ByteVector, nodeParams: NodeParams): fr.acinq.lightning.channel.ChannelStateWithCommitments = deserialize(bin.toByteArray(), nodeParams)

    @OptIn(ExperimentalSerializationApi::class)
    fun encrypt(key: ByteVector32, state: ChannelStateWithCommitments): EncryptedChannelData {
        val bin = serialize(state)
        // NB: there is a chance of collision here, due to how the nonce is calculated. Probability of collision is once every 2.2E19 times.
        // See https://en.wikipedia.org/wiki/Birthday_attack
        val nonce = Crypto.sha256(bin).take(12).toByteArray()
        val (ciphertext, tag) = ChaCha20Poly1305.encrypt(key.toByteArray(), nonce, bin, ByteArray(0))
        return EncryptedChannelData((ciphertext + nonce + tag).toByteVector())
    }

    @OptIn(ExperimentalSerializationApi::class)
    fun encrypt(key: ByteVector32, state: fr.acinq.lightning.channel.ChannelStateWithCommitments): EncryptedChannelData {
        val bin = serialize(state)
        // NB: there is a chance of collision here, due to how the nonce is calculated. Probability of collision is once every 2.2E19 times.
        // See https://en.wikipedia.org/wiki/Birthday_attack
        val nonce = Crypto.sha256(bin).take(12).toByteArray()
        val (ciphertext, tag) = ChaCha20Poly1305.encrypt(key.toByteArray(), nonce, bin, ByteArray(0))
        return EncryptedChannelData((ciphertext + nonce + tag).toByteVector())
    }

    fun encrypt(key: PrivateKey, state: fr.acinq.lightning.channel.ChannelStateWithCommitments): EncryptedChannelData = encrypt(key.value, state)

    @OptIn(ExperimentalSerializationApi::class)
    fun decrypt(key: ByteVector32, data: ByteArray, nodeParams: NodeParams): fr.acinq.lightning.channel.ChannelStateWithCommitments {
        // nonce is 12B, tag is 16B
        val ciphertext = data.dropLast(12 + 16)
        val nonce = data.takeLast(12 + 16).take(12)
        val tag = data.takeLast(16)
        val plaintext = ChaCha20Poly1305.decrypt(key.toByteArray(), nonce.toByteArray(), ciphertext.toByteArray(), ByteArray(0), tag.toByteArray())
        return deserialize(plaintext, nodeParams)
    }

    fun decrypt(key: PrivateKey, data: ByteArray, nodeParams: NodeParams): fr.acinq.lightning.channel.ChannelStateWithCommitments = decrypt(key.value, data, nodeParams)
    fun decrypt(key: PrivateKey, backup: EncryptedChannelData, nodeParams: NodeParams): fr.acinq.lightning.channel.ChannelStateWithCommitments = decrypt(key, backup.data.toByteArray(), nodeParams)
}