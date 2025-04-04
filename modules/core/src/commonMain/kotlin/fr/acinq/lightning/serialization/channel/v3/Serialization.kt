package fr.acinq.lightning.serialization.v3

import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.crypto.Pack
import fr.acinq.bitcoin.io.ByteArrayInput
import fr.acinq.bitcoin.io.readNBytes
import fr.acinq.lightning.channel.states.PersistedChannelState
import fr.acinq.lightning.wire.*
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.AbstractDecoder
import kotlinx.serialization.encoding.CompositeDecoder
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
            subclass(UpdateAddHtlcSerializer)
            subclass(UpdateFailHtlcSerializer)
            subclass(UpdateFailMalformedHtlcSerializer)
            subclass(UpdateFeeSerializer)
            subclass(UpdateFulfillHtlcSerializer)
        }
    }

    private val tlvSerializersModule = SerializersModule {
        polymorphic(Tlv::class) {
            subclass(ChannelReadyTlvShortChannelIdTlvSerializer)
            subclass(ClosingSignedTlvFeeRangeSerializer)
            subclass(GenericTlvSerializer)
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
            contextual(EncryptedChannelDataSerializer)
        })
    }

    // used by the "test node" JSON API
    internal val lightningSerializersModule = SerializersModule {
        include(serializationModules)
    }

    @OptIn(ExperimentalSerializationApi::class)
    fun deserialize(bin: ByteArray): PersistedChannelState {
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