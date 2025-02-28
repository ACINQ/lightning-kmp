package fr.acinq.lightning.wire

import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.io.ByteArrayInput
import fr.acinq.bitcoin.io.ByteArrayOutput
import fr.acinq.bitcoin.io.Input
import fr.acinq.bitcoin.io.Output
import fr.acinq.secp256k1.Hex

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
}

interface TlvValueReader<T : Tlv> {
    fun read(input: Input): T
    fun read(bytes: ByteArray): T = read(ByteArrayInput(bytes))
    fun read(hex: String): T = read(Hex.decode(hex))
}

// @formatter:off
sealed class InvalidTlvPayload { abstract val tag: Long }
data class CannotDecodeTlv(override val tag: Long) : InvalidTlvPayload() { override fun toString(): String = "cannot decode tlv (tag=$tag)" }
data class MissingRequiredTlv(override val tag: Long) : InvalidTlvPayload() { override fun toString(): String = "missing required tlv (tag=$tag)" }
data class ForbiddenTlv(override val tag: Long) : InvalidTlvPayload() { override fun toString(): String = "forbidden tlv (tag=$tag)" }
// @formatter:on

/**
 * Generic tlv type we fallback to if we don't understand the incoming tlv.
 *
 * @param tag tlv tag.
 * @param value tlv value (length is implicit, and encoded as a varint).
 */
data class GenericTlv(override val tag: Long, val value: ByteVector) : Tlv {
    init {
        require(tag.rem(2L) != 0L) { "unknown even tag ($tag) " }
    }

    override fun write(out: Output) = LightningCodecs.writeBytes(value, out)
}

/**
 * @param lengthPrefixed if true, the first bytes contain the total length of the serialized stream.
 * @param readers custom readers that will be used to read tlv values.
 */
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
            val length = LightningCodecs.bigSize(input)
            val data = LightningCodecs.bytes(input, length)
            reader?.let {
                records.add(reader.read(data))
            } ?: run {
                unknown.add(GenericTlv(tag, ByteVector(data)))
            }
        }
        return TlvStream(records.toSet(), unknown.toSet())
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
data class TlvStream<T : Tlv>(val records: Set<T>, val unknown: Set<GenericTlv> = setOf()) {
    constructor(vararg records: T) : this(records.toSet())

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

    /**
     * Add a record to the tlv stream.
     * Only one record of each type is allowed, so this replaced the previous record of the same type.
     */
    inline fun <reified R : T> addOrUpdate(r: R): TlvStream<T> {
        var found = false
        val updated = records.map {
            if (it is R) {
                found = true
                r
            } else {
                it
            }
        }.toSet()
        return if (found) {
            copy(records = updated)
        } else {
            copy(records = updated + r)
        }
    }

    inline fun <reified R : T> remove(): TlvStream<T> {
        return copy(records = records.filter { it !is R }.toSet())
    }

    companion object {
        fun <T : Tlv> empty() = TlvStream(setOf<T>(), setOf())
    }
}
