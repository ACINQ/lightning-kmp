package fr.acinq.lightning.serialization.v1

import fr.acinq.bitcoin.*
import fr.acinq.secp256k1.Hex
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.*
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder


internal abstract class AbstractStringKSerializer<T>(
    name: String,
    private val toString: (T) -> String,
    private val fromString: (String) -> T
) : KSerializer<T> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor(name, PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: T) {
        encoder.encodeString(toString(value))
    }

    override fun deserialize(decoder: Decoder): T {
        return fromString(decoder.decodeString())
    }
}

internal object ByteVectorKSerializer : AbstractStringKSerializer<ByteVector>("ByteVector", ByteVector::toHex, ::ByteVector)

internal object ByteVector32KSerializer : AbstractStringKSerializer<ByteVector32>("ByteVector32", ByteVector32::toHex, ::ByteVector32)

internal object ByteVector64KSerializer : AbstractStringKSerializer<ByteVector64>("ByteVector64", ByteVector64::toHex, ::ByteVector64)

internal object PrivateKeyKSerializer : AbstractStringKSerializer<PrivateKey>("PrivateKey", { it.value.toHex() }, { PrivateKey(ByteVector32(it)) })

internal object PublicKeyKSerializer : AbstractStringKSerializer<PublicKey>("PublicKey", { it.value.toHex() }, { PublicKey(ByteVector(it)) })

internal object SatoshiKSerializer : KSerializer<Satoshi> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("Satoshi", PrimitiveKind.LONG)

    override fun serialize(encoder: Encoder, value: Satoshi) {
        encoder.encodeLong(value.toLong())
    }

    override fun deserialize(decoder: Decoder): Satoshi {
        return Satoshi(decoder.decodeLong())
    }
}

internal abstract class AbstractBtcSerializableKSerializer<T : BtcSerializable<T>>(
    val name: String,
    val btcSerializer: BtcSerializer<T>
) : KSerializer<T> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor(name, PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: T) {
        encoder.encodeString(Hex.encode(btcSerializer.write(value)))
    }

    override fun deserialize(decoder: Decoder): T {
        return btcSerializer.read(Hex.decode(decoder.decodeString()))
    }
}

internal object BlockHeaderKSerializer : AbstractBtcSerializableKSerializer<BlockHeader>("BlockHeader", BlockHeader)

internal object OutPointKSerializer : AbstractBtcSerializableKSerializer<OutPoint>("OutPoint", OutPoint)

internal object ScriptWitnessKSerializer : AbstractBtcSerializableKSerializer<ScriptWitness>("ScriptWitness", ScriptWitness)

internal object TxInKSerializer : AbstractBtcSerializableKSerializer<TxIn>("TxIn", TxIn)

internal object TxOutKSerializer : AbstractBtcSerializableKSerializer<TxOut>("TxOut", TxOut)

internal object TransactionKSerializer : AbstractBtcSerializableKSerializer<Transaction>("Transaction", Transaction)


internal object ExtendedPrivateKeyKSerializer : KSerializer<DeterministicWallet.ExtendedPrivateKey> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("ExtendedPublicKey") {
        element("secretkeybytes", ByteVector32KSerializer.descriptor)
        element("chaincode", ByteVector32KSerializer.descriptor)
        element<Int>("depth")
        element("path", KeyPathKSerializer.descriptor)
        element<Long>("parent")
    }

    override fun serialize(encoder: Encoder, value: DeterministicWallet.ExtendedPrivateKey) {
        val compositeEncoder = encoder.beginStructure(ExtendedPublicKeyKSerializer.descriptor)
        compositeEncoder.encodeSerializableElement(ExtendedPublicKeyKSerializer.descriptor, 0, ByteVector32KSerializer, value.secretkeybytes)
        compositeEncoder.encodeSerializableElement(ExtendedPublicKeyKSerializer.descriptor, 1, ByteVector32KSerializer, value.chaincode)
        compositeEncoder.encodeIntElement(ExtendedPublicKeyKSerializer.descriptor, 2, value.depth)
        compositeEncoder.encodeSerializableElement(ExtendedPublicKeyKSerializer.descriptor, 3, KeyPathKSerializer, value.path)
        compositeEncoder.encodeLongElement(ExtendedPublicKeyKSerializer.descriptor, 4, value.parent)
        compositeEncoder.endStructure(ExtendedPublicKeyKSerializer.descriptor)
    }

    override fun deserialize(decoder: Decoder): DeterministicWallet.ExtendedPrivateKey {
        var secretkeybytes: ByteVector32? = null
        var chaincode: ByteVector32? = null
        var depth: Int? = null
        var path: KeyPath? = null
        var parent: Long? = null

        val compositeDecoder = decoder.beginStructure(ExtendedPublicKeyKSerializer.descriptor)
        loop@ while (true) {
            when (compositeDecoder.decodeElementIndex(ExtendedPublicKeyKSerializer.descriptor)) {
                CompositeDecoder.DECODE_DONE -> break@loop
                0 -> secretkeybytes = compositeDecoder.decodeSerializableElement(ExtendedPublicKeyKSerializer.descriptor, 0, ByteVector32KSerializer)
                1 -> chaincode = compositeDecoder.decodeSerializableElement(ExtendedPublicKeyKSerializer.descriptor, 1, ByteVector32KSerializer)
                2 -> depth = compositeDecoder.decodeIntElement(ExtendedPublicKeyKSerializer.descriptor, 2)
                3 -> path = compositeDecoder.decodeSerializableElement(ExtendedPublicKeyKSerializer.descriptor, 3, KeyPathKSerializer)
                4 -> parent = compositeDecoder.decodeLongElement(ExtendedPublicKeyKSerializer.descriptor, 4)
            }
        }
        compositeDecoder.endStructure(ExtendedPublicKeyKSerializer.descriptor)

        return DeterministicWallet.ExtendedPrivateKey(secretkeybytes!!, chaincode!!, depth!!, path!!, parent!!)
    }

}

internal object ExtendedPublicKeyKSerializer : KSerializer<DeterministicWallet.ExtendedPublicKey> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("ExtendedPublicKey") {
        element("publickeybytes", ByteVectorKSerializer.descriptor)
        element("chaincode", ByteVector32KSerializer.descriptor)
        element<Int>("depth")
        element("path", KeyPathKSerializer.descriptor)
        element<Long>("parent")
    }

    override fun serialize(encoder: Encoder, value: DeterministicWallet.ExtendedPublicKey) {
        val compositeEncoder = encoder.beginStructure(descriptor)
        compositeEncoder.encodeSerializableElement(descriptor, 0, ByteVectorKSerializer, value.publickeybytes)
        compositeEncoder.encodeSerializableElement(descriptor, 1, ByteVector32KSerializer, value.chaincode)
        compositeEncoder.encodeIntElement(descriptor, 2, value.depth)
        compositeEncoder.encodeSerializableElement(descriptor, 3, KeyPathKSerializer, value.path)
        compositeEncoder.encodeLongElement(descriptor, 4, value.parent)
        compositeEncoder.endStructure(descriptor)
    }

    override fun deserialize(decoder: Decoder): DeterministicWallet.ExtendedPublicKey {
        var publickeybytes: ByteVector? = null
        var chaincode: ByteVector32? = null
        var depth: Int? = null
        var path: KeyPath? = null
        var parent: Long? = null

        val compositeDecoder = decoder.beginStructure(descriptor)
        loop@ while (true) {
            when (compositeDecoder.decodeElementIndex(descriptor)) {
                CompositeDecoder.DECODE_DONE -> break@loop
                0 -> publickeybytes = compositeDecoder.decodeSerializableElement(descriptor, 0, ByteVectorKSerializer)
                1 -> chaincode = compositeDecoder.decodeSerializableElement(descriptor, 1, ByteVector32KSerializer)
                2 -> depth = compositeDecoder.decodeIntElement(descriptor, 2)
                3 -> path = compositeDecoder.decodeSerializableElement(descriptor, 3, KeyPathKSerializer)
                4 -> parent = compositeDecoder.decodeLongElement(descriptor, 4)
            }
        }
        compositeDecoder.endStructure(descriptor)

        return DeterministicWallet.ExtendedPublicKey(publickeybytes!!, chaincode!!, depth!!, path!!, parent!!)
    }

}

internal object KeyPathKSerializer : KSerializer<KeyPath> {
    private val listSerializer = ListSerializer(Long.serializer())

    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("KeyPath") {
        element("path", listSerializer.descriptor)
    }

    override fun serialize(encoder: Encoder, value: KeyPath) {
        val compositeEncoder = encoder.beginStructure(ExtendedPublicKeyKSerializer.descriptor)
        compositeEncoder.encodeSerializableElement(descriptor, 0, listSerializer, value.path)
        compositeEncoder.endStructure(descriptor)
    }

    override fun deserialize(decoder: Decoder): KeyPath {
        val compositeDecoder = decoder.beginStructure(ExtendedPublicKeyKSerializer.descriptor)
        require(compositeDecoder.decodeElementIndex(descriptor) == 0)
        val path = compositeDecoder.decodeSerializableElement(descriptor, 0, listSerializer)
        compositeDecoder.endStructure(descriptor)
        return KeyPath(path)
    }
}
