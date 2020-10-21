package fr.acinq.eclair

import fr.acinq.bitcoin.Satoshi
import kotlinx.serialization.Serializable

/**
 * One MilliSatoshi is a thousand of a Satoshi, the smallest unit usable in bitcoin
 */
@Serializable
data class MilliSatoshi(val msat: Long) : Comparable<MilliSatoshi> {

    constructor(sat : Satoshi) : this(sat.toLong() * 1000)

    // @formatter:off
    operator fun plus(other: MilliSatoshi) = MilliSatoshi(msat + other.msat)
    operator fun minus(other: MilliSatoshi) = MilliSatoshi(msat - other.msat)
    operator fun times(m: Long) = MilliSatoshi(msat * m)
    operator fun times(m: Double) = MilliSatoshi((msat * m).toLong())
    operator fun times(m: Float) = MilliSatoshi((msat * m).toLong())
    operator fun div(d: Long) = MilliSatoshi(msat / d)
    operator fun unaryMinus() = MilliSatoshi(-msat)

    override fun compareTo(other: MilliSatoshi): Int = msat.compareTo(other.msat)
    // Since BtcAmount is a sealed trait that MilliSatoshi cannot extend, we need to redefine comparison operators.

    fun truncateToSatoshi() = Satoshi(msat / 1000)
    fun toLong(): Long = msat
    @ExperimentalUnsignedTypes
    fun toULong(): ULong = msat.toULong()
    override fun toString() = "$msat msat"
    // @formatter:on
}