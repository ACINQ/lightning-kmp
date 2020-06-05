package fr.acinq.eklair

import fr.acinq.bitcoin.Satoshi

/**
 * One MilliSatoshi is a thousand of a Satoshi, the smallest unit usable in bitcoin
 */
data class MilliSatoshi(private val underlying: Long) : Comparable<MilliSatoshi> {

    // @formatter:off
    operator fun plus(other: MilliSatoshi) = MilliSatoshi(underlying + other.underlying)
    operator fun minus(other: MilliSatoshi) = MilliSatoshi(underlying - other.underlying)
    operator fun times(m: Long) = MilliSatoshi(underlying * m)
    operator fun times(m: Double) = MilliSatoshi((underlying * m).toLong())
    operator fun div(d: Long) = MilliSatoshi(underlying / d)
    operator fun unaryMinus() = MilliSatoshi(-underlying)

    override fun compareTo(other: MilliSatoshi): Int = underlying.compareTo(other.underlying)
    // Since BtcAmount is a sealed trait that MilliSatoshi cannot extend, we need to redefine comparison operators.

    // We provide asymmetric min/max functions to provide more control on the return type.
    fun max(other: MilliSatoshi): MilliSatoshi = if (this > other) this else other
    fun min(other: MilliSatoshi): MilliSatoshi = if (this < other) this else other

    fun truncateToSatoshi() = Satoshi(underlying / 1000)
    fun toLong(): Long = underlying
    override fun toString() = "$underlying msat"
    // @formatter:on
}