package fr.acinq.eklair

import kotlinx.serialization.Serializable

/**
 * Bitcoin scripts (in particular HTLCs) need an absolute block expiry (greater than the current block count) to work
 * with OP_CLTV.
 *
 * @param underlying the absolute cltv expiry value (current block count + some delta).
 */
@Serializable
data class CltvExpiry(private val underlying: Long) : Comparable<CltvExpiry> {
    // @formatter:off
    operator fun plus(d: CltvExpiryDelta): CltvExpiry = CltvExpiry(underlying + d.toInt())
    operator fun minus(d: CltvExpiryDelta): CltvExpiry = CltvExpiry(underlying - d.toInt())
    operator fun minus(other: CltvExpiry): CltvExpiryDelta = CltvExpiryDelta((underlying - other.underlying).toInt())
    override fun compareTo(other: CltvExpiry): Int = underlying.compareTo(other.underlying)
    fun toLong(): Long = underlying
    // @formatter:on
}

/**
 * Channels advertise a cltv expiry delta that should be used when routing through them.
 * This value needs to be converted to a [[fr.acinq.eclair.CltvExpiry]] to be used in OP_CLTV.
 *
 * CltvExpiryDelta can also be used when working with OP_CSV which is by design a delta.
 *
 * @param underlying the cltv expiry delta value.
 */
@Serializable
data class CltvExpiryDelta(private val underlying: Int) : Comparable<CltvExpiryDelta> {

    /**
     * Adds the current block height to the given delta to obtain an absolute expiry.
     */
    fun toCltvExpiry(blockHeight: Long) = CltvExpiry(blockHeight + underlying)

    // @formatter:off
    operator fun plus(other: Int): CltvExpiryDelta = CltvExpiryDelta(underlying + other)
    operator fun plus(other: CltvExpiryDelta): CltvExpiryDelta = CltvExpiryDelta(underlying + other.underlying)
    operator fun minus(other: CltvExpiryDelta): CltvExpiryDelta = CltvExpiryDelta(underlying - other.underlying)
    override fun compareTo(other: CltvExpiryDelta): Int = underlying.compareTo(other.underlying)
    fun toInt(): Int = underlying
    fun toLong(): Long = underlying.toLong()
    // @formatter:on

}
