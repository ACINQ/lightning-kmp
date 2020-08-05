package fr.acinq.eklair.wire

import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.io.ByteArrayInput
import fr.acinq.bitcoin.io.Input
import fr.acinq.bitcoin.io.Output
import kotlinx.serialization.Serializable
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass

@OptIn(ExperimentalUnsignedTypes::class)
interface Tlv {
    val tag: Long

    companion object {
        val serializationModule = SerializersModule {
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

/**
 * Generic tlv type we fallback to if we don't understand the incoming tlv.
 *
 * @param tag   tlv tag.
 * @param value tlv value (length is implicit, and encoded as a varint).
 */
@OptIn(ExperimentalUnsignedTypes::class)
@Serializable
data class GenericTlv(override val tag: Long, val value: ByteVector) : Tlv, LightningSerializable<GenericTlv> {
    init {
        require(tag.rem(2L) != 0L) { "unknown even tag ($tag) " }
    }

    override fun serializer(): LightningSerializer<GenericTlv> = GenericTlv

    companion object : LightningSerializer<GenericTlv>() {
        override fun read(input: Input): GenericTlv {
            val tag = bigSize(input)
            val length = bigSize(input)
            val value = bytes(input, length)
            return GenericTlv(tag, ByteVector(value))
        }

        override fun write(message: GenericTlv, out: Output) {
            writeBigSize(message.tag.toLong(), out)
            writeBigSize(message.value.size().toLong(), out)
            writeBytes(message.value, out)
        }

        override val tag: Long
            get() = 0L
    }
}

/**
 * @param serializers custom serializers. The will be used to decode TLV values (and not the entire TLV including its tag and length)
 */
@OptIn(ExperimentalUnsignedTypes::class)
class TlvStreamSerializer<T : Tlv>(val serializers: Map<Long, LightningSerializer<T>>) : LightningSerializer<TlvStream<T>>() {
    override val tag: Long
        get() = 0L

    /**
     * @param input input stream
     * @return a TLV stream. For each TLV read from the stream:
     * - if there is a serializer for the TLV's tag, we use it to decode the TLV value and add it to the stream's record
     * - otherwise we add the raw TLV to the stream's unknown TLVs as a GenericTLV
     */
    override fun read(input: Input): TlvStream<T> {
        val records = ArrayList<T>()
        val unknown = ArrayList<GenericTlv>()
        while (input.availableBytes > 0) {
            val tag = bigSize(input)
            val length = bigSize(input)
            val data = bytes(input, length)
            val dataStream = ByteArrayInput(data)
            val serializer = serializers[tag]
            serializer
                ?.let { records.add(serializer.read(dataStream)) }
                ?: unknown.add(GenericTlv(tag, ByteVector(data)))
        }
        return TlvStream(records.toList(), unknown.toList())
    }

    /**
     * @param message TLV stream
     * @param out output stream to write the TLV stream to
     */
    override fun write(message: TlvStream<T>, out: Output) {
        val map = ArrayList<Pair<Long, ByteArray>>()
        // first, serialize all TLVs
        message.records.forEach { map.add(Pair(it.tag, serializers[it.tag]!!.write(it))) }
        message.unknown.forEach { map.add(Pair(it.tag, it.value.toByteArray())) }

        // then sort by tag as per the BOLTs
        val map1 = map.sortedBy { it.first }

        // then write the results
        map1.forEach {
            writeBigSize(it.first.toLong(), out)
            writeBigSize(it.second.size.toLong(), out)
            writeBytes(it.second, out)
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
        val tags = records.map { it.tag }
        require(tags.size == tags.toSet().size) { "tlvstream contains duplicate tags" }
    }

    inline fun <reified R : T> get(): R? {
        for (r in records) {
            if (r is R) return r
        }
        return null
    }

    /**
     *
     * @tparam R input type parameter, must be a subtype of the main TLV type
     * @return the TLV record of type that matches the input type parameter if any (there can be at most one, since BOLTs specify
     *         that TLV records are supposed to be unique)
     */
    companion object {
        fun <T : Tlv> empty() = TlvStream<T>(listOf<T>(), listOf<GenericTlv>())
    }
}
