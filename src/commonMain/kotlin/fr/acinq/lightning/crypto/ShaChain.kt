package fr.acinq.lightning.crypto

import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.Crypto.sha256
import fr.acinq.lightning.utils.startsWith
import kotlin.experimental.xor


// TODO: should replace 0xffffffffffffffffL (-1) by 0xffffffffffffffL and 63 by 47.
data class ShaChain(val knownHashes: Map<List<Boolean>, ByteVector32>, val lastIndex: Long? = null) {

    fun addHash(hash: ByteVector32, index: Long): ShaChain {
        lastIndex?.let { require(index == it - 1L) }
        return addHash(this, hash, moves(index)).copy(lastIndex = index)
    }

    fun getHash(index: List<Boolean>): ByteVector32? =
        knownHashes.keys.find { key -> index.startsWith(key) }?.let { key ->
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
            } else Node(node.value, node.height + 1, node)

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
}
