package fr.acinq.eclair

import kotlinx.serialization.Serializable

@Serializable
data class ShortChannelId(private val id: Long) : Comparable<ShortChannelId> {

    fun toLong(): Long = id

    fun blockHeight(): Int = ((id ushr 40) and 0xFFFFFF).toInt()

    fun txIndex(): Int = ((id ushr 16) and 0xFFFFFF).toInt()

    fun outputIndex(): Int = (id and 0xFFFF).toInt()

    fun coordinates(): TxCoordinates = TxCoordinates(blockHeight(), txIndex(), outputIndex())

    override fun toString(): String = "${blockHeight()}x${txIndex()}x${outputIndex()}"

    // we use an unsigned long comparison here
    override fun compareTo(other: ShortChannelId): Int = (this.id + Long.MIN_VALUE).compareTo(other.id + Long.MIN_VALUE)

    companion object {
        operator fun invoke(s: String): ShortChannelId {
            val list = s.split("x")
            if (list.size == 3) {
                val (blockHeight, txIndex, outputIndex) = list
                return ShortChannelId(toShortId(blockHeight.toInt(), txIndex.toInt(), outputIndex.toInt()))
            }
            throw IllegalArgumentException("Invalid short channel id: $s")
        }

        operator fun invoke(blockHeight: Int, txIndex: Int, outputIndex: Int): ShortChannelId = ShortChannelId(toShortId(blockHeight, txIndex, outputIndex))

        fun toShortId(blockHeight: Int, txIndex: Int, outputIndex: Int): Long = ((blockHeight.toLong() and 0xFFFFFFL) shl 40) or ((txIndex.toLong() and 0xFFFFFFL) shl 16) or (outputIndex.toLong() and 0xFFFFL)

    }
}

data class TxCoordinates(val blockHeight: Int, val txIndex: Int, val outputIndex: Int)
