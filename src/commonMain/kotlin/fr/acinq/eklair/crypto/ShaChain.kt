package fr.acinq.eklair.crypto

import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.Crypto.sha256
import fr.acinq.eklair.io.ByteVector32KSerializer
import fr.acinq.eklair.utils.startsWith
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.descriptors.element
import kotlinx.serialization.descriptors.mapSerialDescriptor
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlin.experimental.xor


// TODO: should replace 0xffffffffffffffffL (-1) by 0xffffffffffffffL and 63 by 47.
@Serializable(with = ShaChain.Serializer::class)
data class ShaChain(val knownHashes: Map<List<Boolean>, ByteVector32>, val lastIndex: Long? = null) {

    fun addHash(hash: ByteVector32, index: Long): ShaChain {
        lastIndex?.let { require(index == it - 1L) }
        return addHash(this, hash, moves(index)).copy(lastIndex = index)
    }

    fun getHash(index: List<Boolean>): ByteVector32? =
        knownHashes.keys.find { key -> index.startsWith(key) } ?. let { key ->
            val root = Node(knownHashes[key]!!, key.size, null)
            derive(root, index.drop(key.size)).value
        }

    fun getHash(index: Long): ByteVector32? =
        if (lastIndex == null || lastIndex > index) null
        else getHash(moves(index))

    fun asSequence(): Sequence<ByteVector32> {
        if (lastIndex == null) return emptySequence()
        return sequence {
            var pos: Long = lastIndex
            while (pos >= lastIndex && pos <= -1 /*0xffffffffffffffffL*/) {
                yield(getHash(pos)!!)
                ++pos
            }
        }
    }

    override fun toString() = "ShaChain(lastIndex = $lastIndex)"

    data class Node(val value: ByteVector32, val height: Int, val parent: Node?)

    companion object {

        fun flip(input: ByteVector32, index: Int): ByteVector32 = ByteVector32(input.update(index / 8, (input[index / 8] xor (1 shl (index % 8)).toByte())))

        /**
         *
         * @param index 64-bit integer
         * @return a binary representation of index as a sequence of 64 booleans. Each bool represents a move down the tree
         */
        fun moves(index: Long): List<Boolean> =
            (63 downTo 0).map { (index and (1L shl it)) != 0L }

        /**
         *
         * @param node      initial node
         * @param direction false means left, true means right
         * @return the child of our node in the specified direction
         */
        fun derive(node: Node, direction: Boolean) =
            if (direction) {
//                val flipped = flip(node.value, 63 - node.height)
//                val sha = ByteVector32(sha256(flipped))
                Node(ByteVector32(sha256(flip(node.value, 63 - node.height))), node.height + 1, node)
            }
            else Node(node.value, node.height + 1, node)

        fun derive(node: Node, directions: List<Boolean>): Node = directions.fold(node) { n, d -> derive(n, d) }

        fun derive(node: Node, directions: Long): Node = derive(node, moves(directions))

        fun shaChainFromSeed(hash: ByteVector32, index: Long) = derive(Node(hash, 0, null), index).value

        val empty = ShaChain(emptyMap())

        val init = empty

        tailrec fun addHash(receiver: ShaChain, hash: ByteVector32, index: List<Boolean>): ShaChain =
            if (index.last()) {
                ShaChain(receiver.knownHashes + mapOf(index to hash))
            } else {
                val parentIndex = index.dropLast(1)
                // hashes are supposed to be received in reverse order so we already have parent :+ true
                // which we should be able to recompute (it's a left node so its hash is the same as its parent's hash)
                require(receiver.getHash(parentIndex + true) == derive(Node(hash, parentIndex.size, null), true).value) { "invalid hash" }
                val nodes1 = receiver.knownHashes
                    .minus<List<Boolean>, ByteVector32>(parentIndex + false)
                    .minus<List<Boolean>, ByteVector32>(parentIndex + true)
                addHash(receiver.copy(knownHashes = nodes1), hash, parentIndex)
            }

    }

    object Serializer : KSerializer<ShaChain> {
        @OptIn(ExperimentalSerializationApi::class)
        override val descriptor: SerialDescriptor = buildClassSerialDescriptor("ShaChain") {
            element("knownHashes", mapSerialDescriptor(String.serializer().descriptor, ByteVector32KSerializer.descriptor))
            element<Long>("lastIndex", isOptional = true)
        }

        private fun List<Boolean>.toBinaryString(): String = this.map { if (it) '1' else '0' } .joinToString(separator = "")
        private fun String.toBooleanList(): List<Boolean> = this.map { it == '1' }

        private val mapSerializer = MapSerializer(String.serializer(), ByteVector32KSerializer)

        override fun serialize(encoder: Encoder, value: ShaChain) {
            val compositeEncoder = encoder.beginStructure(descriptor)
            compositeEncoder.encodeSerializableElement(descriptor, 0, mapSerializer, value.knownHashes.mapKeys { it.key.toBinaryString() })
            if (value.lastIndex != null) compositeEncoder.encodeLongElement(descriptor, 1, value.lastIndex)
            compositeEncoder.endStructure(descriptor)
        }

        override fun deserialize(decoder: Decoder): ShaChain {
            var knownHashes: Map<List<Boolean>, ByteVector32>? = null
            var lastIndex: Long? = null

            val compositeDecoder = decoder.beginStructure(descriptor)
            loop@ while (true) {
                when (compositeDecoder.decodeElementIndex(descriptor)) {
                    CompositeDecoder.DECODE_DONE -> break@loop
                    0 -> knownHashes = compositeDecoder.decodeSerializableElement(descriptor, 0, mapSerializer).mapKeys { it.key.toBooleanList() }
                    1 -> lastIndex = compositeDecoder.decodeLongElement(descriptor, 1)
                }
            }
            compositeDecoder.endStructure(descriptor)

            return ShaChain(
                knownHashes ?: error("No knownHashes in structure"),
                lastIndex
            )
        }

    }
}
