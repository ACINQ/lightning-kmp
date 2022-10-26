package fr.acinq.lightning.serialization.v3

import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.crypto.Pack
import fr.acinq.bitcoin.io.ByteArrayInput
import fr.acinq.bitcoin.io.ByteArrayOutput
import fr.acinq.bitcoin.io.readNBytes
import fr.acinq.lightning.wire.*
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.AbstractDecoder
import kotlinx.serialization.encoding.AbstractEncoder
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.encoding.CompositeEncoder
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.contextual
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass

object Serialization {
    private const val versionMagic = 3

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
            subclass(ChannelTlv.ChannelTypeTlv.serializer())
            subclass(ChannelTlv.ChannelOriginTlv.serializer())
            subclass(ChannelTlv.PushAmountTlv.serializer())
            subclass(ChannelReadyTlv.ShortChannelIdTlv.serializer())
            subclass(ClosingSignedTlv.FeeRange.serializer())
            subclass(InitTlv.Networks.serializer())
            subclass(InitTlv.PhoenixAndroidLegacyNodeId.serializer())
            subclass(OnionPaymentPayloadTlv.AmountToForward.serializer())
            subclass(OnionPaymentPayloadTlv.OutgoingCltv.serializer())
            subclass(OnionPaymentPayloadTlv.OutgoingChannelId.serializer())
            subclass(OnionPaymentPayloadTlv.PaymentData.serializer())
            subclass(OnionPaymentPayloadTlv.PaymentMetadata.serializer())
            subclass(OnionPaymentPayloadTlv.InvoiceFeatures.serializer())
            subclass(OnionPaymentPayloadTlv.OutgoingNodeId.serializer())
            subclass(OnionPaymentPayloadTlv.InvoiceRoutingInfo.serializer())
            subclass(OnionPaymentPayloadTlv.TrampolineOnion.serializer())
            subclass(GenericTlv.serializer())
        }
    }

    private val serializationModules = SerializersModule {
        include(tlvSerializersModule)
        include(updateSerializersModule)
        include(SerializersModule {
            contextual(ByteVector64KSerializer)
            contextual(ByteVector32KSerializer)
            contextual(ByteVectorKSerializer)
            contextual(ScriptWitnessKSerializer)
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
    internal val lightningSerializersModule = SerializersModule {
        include(serializationModules)
    }

    @OptIn(ExperimentalSerializationApi::class)
    fun deserialize(bin: ByteArray): fr.acinq.lightning.channel.ChannelStateWithCommitments {
        val input = ByteArrayInput(bin)
        val decoder = DataInputDecoder(input)
        val versioned = decoder.decodeSerializableValue(SerializedData.serializer())
        return when (versioned.version) {
            versionMagic -> {
                val input1 = ByteArrayInput(versioned.data.toByteArray())
                val decoder1 = DataInputDecoder(input1)
                decoder1.decodeSerializableValue(ChannelStateWithCommitments.serializer()).export()
            }
            else -> error("unknown serialization version ${versioned.version}")
        }
    }

    @OptIn(ExperimentalSerializationApi::class)
    class DataOutputEncoder(val output: ByteArrayOutput) : AbstractEncoder() {
        override val serializersModule: SerializersModule = serializationModules
        override fun encodeBoolean(value: Boolean) = output.write(if (value) 1 else 0)
        override fun encodeByte(value: Byte) = output.write(value.toInt())
        override fun encodeShort(value: Short) = output.write(Pack.writeInt16BE(value))
        override fun encodeInt(value: Int) = output.write(Pack.writeInt32BE(value))
        override fun encodeLong(value: Long) = output.write(Pack.writeInt64BE(value))
        override fun encodeFloat(value: Float) {
            TODO()
        }

        override fun encodeDouble(value: Double) {
            TODO()
        }

        override fun encodeChar(value: Char) = output.write(value.code)
        override fun encodeString(value: String) {
            val bytes = value.encodeToByteArray()
            encodeInt(bytes.size)
            output.write(bytes)
        }

        override fun encodeEnum(enumDescriptor: SerialDescriptor, index: Int) = output.write(index)
        override fun beginCollection(descriptor: SerialDescriptor, collectionSize: Int): CompositeEncoder {
            encodeInt(collectionSize)
            return this
        }

        override fun encodeNull() = encodeBoolean(false)
        override fun encodeNotNullMark() = encodeBoolean(true)
    }

    @OptIn(ExperimentalSerializationApi::class)
    @ExperimentalSerializationApi
    class DataInputDecoder(val input: ByteArrayInput, var elementsCount: Int = 0) : AbstractDecoder() {
        private var elementIndex = 0
        override val serializersModule: SerializersModule = serializationModules
        override fun decodeBoolean(): Boolean = input.read() != 0
        override fun decodeByte(): Byte = input.read().toByte()
        override fun decodeShort(): Short = Pack.int16BE(input.readNBytes(2)!!)
        override fun decodeInt(): Int = Pack.int32BE(input.readNBytes(4)!!)
        override fun decodeLong(): Long = Pack.int64BE(input.readNBytes(8)!!)
        override fun decodeFloat(): Float = TODO()
        override fun decodeDouble(): Double = TODO()
        override fun decodeChar(): Char = input.read().toChar()
        override fun decodeString(): String {
            val len = decodeInt()
            require(len <= input.availableBytes)
            return input.readNBytes(len)!!.decodeToString()
        }

        override fun decodeEnum(enumDescriptor: SerialDescriptor): Int = input.read()
        override fun decodeElementIndex(descriptor: SerialDescriptor): Int {
            if (elementIndex == elementsCount) return CompositeDecoder.DECODE_DONE
            return elementIndex++
        }

        override fun beginStructure(descriptor: SerialDescriptor): CompositeDecoder = DataInputDecoder(input, descriptor.elementsCount)
        override fun decodeSequentially(): Boolean = true
        override fun decodeCollectionSize(descriptor: SerialDescriptor): Int = decodeInt().also {
            require(it <= input.availableBytes)
            elementsCount = it
        }

        override fun decodeNotNullMark(): Boolean = decodeBoolean()
    }
}