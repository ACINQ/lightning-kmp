package fr.acinq.eclair

import fr.acinq.bitcoin.Satoshi
import kotlinx.serialization.Serializable
import kotlin.math.ceil
import kotlin.math.ulp

/**
 * One MilliSatoshi is a thousand of a Satoshi, the smallest unit usable in bitcoin
 */
@Serializable
data class MilliSatoshi(val msat: Long) : Comparable<MilliSatoshi> {

    constructor(sat: Satoshi) : this(sat.toLong() * 1000)

    // @formatter:off
    operator fun plus(other: MilliSatoshi) = MilliSatoshi(msat + other.msat)
    operator fun minus(other: MilliSatoshi) = MilliSatoshi(msat - other.msat)
    operator fun div(d: Long) = MilliSatoshi(msat / d)
    operator fun unaryMinus() = MilliSatoshi(-msat)
    operator fun times(m: Long) = MilliSatoshi(msat * m)
    operator fun times(m: Double): MilliSatoshi {
        // Credit:
        // https://levelup.gitconnected.com/double-equality-in-kotlin-f99392cba0e4
        val rawResult: Double = msat * m
        val upResult: Double = ceil(rawResult)

        val delta = rawResult.ulp * 4 // steps

        val shouldRoundUp = (upResult - rawResult) < delta
        val fixedResult: Double = if (shouldRoundUp) upResult else rawResult
        return MilliSatoshi(fixedResult.toLong())
    }

    override fun compareTo(other: MilliSatoshi): Int = msat.compareTo(other.msat)
    // Since BtcAmount is a sealed trait that MilliSatoshi cannot extend, we need to redefine comparison operators.

    fun truncateToSatoshi() = Satoshi(msat / 1000)
    fun toLong(): Long = msat
    @ExperimentalUnsignedTypes
    fun toULong(): ULong = msat.toULong()
    override fun toString() = "$msat msat"
    // @formatter:on
}