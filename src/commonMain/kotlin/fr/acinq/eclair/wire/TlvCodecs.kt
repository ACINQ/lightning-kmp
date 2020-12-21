package fr.acinq.eclair.wire

import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.io.ByteArrayInput
import fr.acinq.bitcoin.io.ByteArrayOutput
import fr.acinq.bitcoin.io.Input
import fr.acinq.bitcoin.io.Output
import fr.acinq.eclair.serialization.ByteVectorKSerializer
import fr.acinq.secp256k1.Hex
import kotlinx.serialization.Serializable
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass

@OptIn(ExperimentalUnsignedTypes::class)
interface Tlv {
    /**
     * TLV fields start with a tag that uniquely identifies the type of field within a specific namespace (usually a lightning message).
     * See https://github.com/lightningnetwork/lightning-rfc/blob/master/01-messaging.md#type-length-value-format.
     */
    val tag: Long

    fun write(out: Output)

    fun write(): ByteArray {
        val out = ByteArrayOutput()
        write(out)
        return out.toByteArray()
    }

    companion object {
        val serializersModule = SerializersModule {
            polymorphic(Tlv::class) {
                subclass(ChannelTlv.UpfrontShutdownScript.serializer())
                subclass(ChannelTlv.ChannelVersionTlv.serializer())
                subclass(InitTlv.Networks.serializer())
                subclass(OnionTlv.AmountToForward.serializer())
                subclass(OnionTlv.OutgoingCltv.serializer())
                subclass(OnionTlv.OutgoingChannelId.serializer())
                subclass(OnionTlv.PaymentData.serializer())
                subclass(OnionTlv.InvoiceFeatures.serializer())
                subclass(OnionTlv.OutgoingNodeId.serializer())
                subclass(OnionTlv.InvoiceRoutingInfo.serializer())
                subclass(OnionTlv.TrampolineOnion.serializer())
                subclass(GenericTlv.serializer())
            }
        }
    }
}

interface TlvValueReader<T : Tlv> {
    fun read(input: Input): T
    fun read(bytes: ByteArray): T = read(ByteArrayInput(bytes))
    fun read(hex: String): T = read(Hex.decode(hex))
}

/**
 * Generic tlv type we fallback to if we don't understand the incoming tlv.
 *
 * @param tag tlv tag.
 * @param value tlv value (length is implicit, and encoded as a varint).
 */
@OptIn(ExperimentalUnsignedTypes::class)
@Serializable
data class GenericTlv(override val tag: Long, @Serializable(with = ByteVectorKSerializer::class) val value: ByteVector) : Tlv {
    init {
        require(tag.rem(2L) != 0L) { "unknown even tag ($tag) " }
    }

    override fun write(out: Output) = LightningCodecs.writeBytes(value, out)
}

/**
 * @param lengthPrefixed if true, the first bytes contain the total length of the serialized stream.
 * @param readers custom readers that will be used to read tlv values.
 */
@OptIn(ExperimentalUnsignedTypes::class)
class TlvStreamSerializer<T : Tlv>(private val lengthPrefixed: Boolean, private val readers: Map<Long, TlvValueReader<T>>) {
    /**
     * @param input input stream
     * @return a tlv stream. For each tlv read from the stream:
     *  - if there is a reader for the tlv tag, we use it to decode the tlv value and add it to the stream's record
     *  - otherwise we add the raw tlv to the stream's unknown tlvs as a GenericTlv
     */
    fun read(input: Input): TlvStream<T> = if (lengthPrefixed) {
        val len = LightningCodecs.bigSize(input)
        readTlvs(ByteArrayInput(LightningCodecs.bytes(input, len)))
    } else {
        readTlvs(input)
    }

    fun read(bytes: ByteArray): TlvStream<T> = read(ByteArrayInput(bytes))

    private fun readTlvs(input: Input): TlvStream<T> {
        val records = ArrayList<T>()
        val unknown = ArrayList<GenericTlv>()
        var previousTag: Long? = null
        while (input.availableBytes > 0) {
            val tag = LightningCodecs.bigSize(input)
            if (previousTag != null) {
                require(tag > previousTag) { "tlv stream is not sorted by tags" }
            }
            previousTag = tag

            val reader = readers[tag]
            val length = if (tag == ChannelTlv.ChannelVersionTlvLegacy.tag) 4 else LightningCodecs.bigSize(input)
            val data = LightningCodecs.bytes(input, length)
            reader?.let {
                records.add(reader.read(data))
            } ?: run {
                unknown.add(GenericTlv(tag, ByteVector(data)))
            }
        }
        return TlvStream(records.toList(), unknown.toList())
    }

    /**
     * @param message TLV stream
     * @param out output stream to write the TLV stream to
     */
    fun write(message: TlvStream<T>, out: Output) = if (lengthPrefixed) {
        val tmp = ByteArrayOutput()
        writeTlvs(message, tmp)
        val b = tmp.toByteArray()
        LightningCodecs.writeBigSize(b.size.toLong(), out)
        LightningCodecs.writeBytes(b, out)
    } else {
        writeTlvs(message, out)
    }

    fun write(message: TlvStream<T>): ByteArray {
        val out = ByteArrayOutput()
        write(message, out)
        return out.toByteArray()
    }

    private fun writeTlvs(message: TlvStream<T>, out: Output) {
        val map = ArrayList<Pair<Long, ByteArray>>()
        // first, serialize all tlv fields
        message.records.forEach { map.add(Pair(it.tag, it.write())) }
        message.unknown.forEach { map.add(Pair(it.tag, it.write())) }

        // then sort by tag as per the BOLTs before writing them
        map.sortedBy { it.first }.forEach {
            LightningCodecs.writeBigSize(it.first, out)
            LightningCodecs.writeBigSize(it.second.size.toLong(), out)
            LightningCodecs.writeBytes(it.second, out)
        }
    }
}

/**
 * A tlv stream is a collection of tlv records.
 * A tlv stream is constrained to a specific tlv namespace that dictates how to parse the tlv records.
 * That namespace is provided by a trait extending the top-level tlv trait.
 *
 * @param records known tlv records.
 * @param unknown unknown tlv records.
 * @tparam T the stream namespace is a trait extending the top-level tlv trait.
 */
@Serializable
data class TlvStream<T : Tlv>(val records: List<T>, val unknown: List<GenericTlv> = listOf()) {
    init {
        val tags = records.map { it.tag } + unknown.map { it.tag }
        require(tags.size == tags.toSet().size) { "tlv stream contains duplicate tags" }
    }

    /**
     * @tparam R input type parameter, must be a subtype of the main TLV type
     * @return the tlv record of type that matches the input type parameter if any (there can be at most one, since BOLTs specify that tlv records are supposed to be unique)
     */
    inline fun <reified R : T> get(): R? {
        for (r in records) {
            if (r is R) return r
        }
        return null
    }

    companion object {
        fun <T : Tlv> empty() = TlvStream(listOf<T>(), listOf())
    }
}
