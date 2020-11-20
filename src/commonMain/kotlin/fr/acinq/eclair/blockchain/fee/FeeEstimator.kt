package fr.acinq.eclair.blockchain.fee

import fr.acinq.bitcoin.Satoshi
import fr.acinq.eclair.io.SatoshiKSerializer
import fr.acinq.eclair.utils.sat
import kotlinx.serialization.Serializable

interface FeeEstimator {
    fun getFeeratePerKb(target: Int): Long
    fun getFeeratePerKw(target: Int): Long
}

interface FeeProvider {
    fun getFeerates(): FeeratesPerKB
}

@Serializable
data class OnChainFeerates(val fundingFeerate: FeeratePerKw, val commitmentFeerate: FeeratePerKw, val mutualCloseFeerate: FeeratePerKw, val claimMainFeerate: FeeratePerKw, val fastFeerate: FeeratePerKw)

@Serializable
data class OnChainFeeConf(val maxFeerateMismatch: Double, val closeOnOfflineMismatch: Boolean, val updateFeeMinDiffRatio: Double)

@Serializable
/** Fee rate in satoshi-per-bytes. */
data class FeeratePerByte(@Serializable(with = SatoshiKSerializer::class) val feerate: Satoshi) {
    constructor(feeratePerKw: FeeratePerKw) : this(FeeratePerKB(feeratePerKw).feerate / 1000)
}

@Serializable
/** Fee rate in satoshi-per-kilo-bytes (1 kB = 1000 bytes). */
data class FeeratePerKB(@Serializable(with = SatoshiKSerializer::class) val feerate: Satoshi) : Comparable<FeeratePerKB> {
    constructor(feeratePerByte: FeeratePerByte) : this(feeratePerByte.feerate * 1000)
    constructor(feeratePerKw: FeeratePerKw) : this(feeratePerKw.feerate * 4)

    override fun compareTo(other: FeeratePerKB): Int = feerate.compareTo(other.feerate)
    fun max(other: FeeratePerKB): FeeratePerKB = if (this > other) this else other
    fun min(other: FeeratePerKB): FeeratePerKB = if (this < other) this else other
    fun toLong(): Long = feerate.toLong()
}

@Serializable
/** Fee rate in satoshi-per-kilo-weight. */
data class FeeratePerKw(@Serializable(with = SatoshiKSerializer::class) val feerate: Satoshi) : Comparable<FeeratePerKw> {
    constructor(feeratePerKB: FeeratePerKB) : this(MinimumFeeratePerKw.feerate.max(feeratePerKB.feerate / 4))
    constructor(feeratePerByte: FeeratePerByte) : this(FeeratePerKB(feeratePerByte))

    override fun compareTo(other: FeeratePerKw): Int = feerate.compareTo(other.feerate)
    fun max(other: FeeratePerKw): FeeratePerKw = if (this > other) this else other
    fun min(other: FeeratePerKw): FeeratePerKw = if (this < other) this else other
    operator fun plus(other: FeeratePerKw): FeeratePerKw = FeeratePerKw(feerate + other.feerate)
    operator fun times(d: Double): FeeratePerKw = FeeratePerKw(feerate * d)
    operator fun times(l: Long): FeeratePerKw = FeeratePerKw(feerate * l)
    operator fun div(l: Long): FeeratePerKw = FeeratePerKw(feerate / l)
    fun toLong(): Long = feerate.toLong()

    companion object {
        /**
         * Minimum relay fee rate in satoshi per kilo-vbyte (taken from Bitcoin Core).
         * Note that Bitcoin Core uses a *virtual size* and not the actual size in bytes: see [[MinimumFeeratePerKw]] below.
         */
        const val MinimumRelayFeeRate = 1000

        /**
         * Why 253 and not 250 since feerate-per-kw should be feerate-per-kvb / 4 and the minimum relay fee rate is
         * 1000 satoshi/kvb (see [[MinimumRelayFeeRate]])?
         *
         * Because Bitcoin Core uses neither the actual tx size in bytes nor the tx weight to check fees, but a "virtual size"
         * which is (3 + weight) / 4.
         * So we want:
         * fee > 1000 * virtual size
         * feerate-per-kw * weight > 1000 * (3 + weight / 4)
         * feerate-per-kw > 250 + 3000 / (4 * weight)
         *
         * With a conservative minimum weight of 400, assuming the result of the division may be rounded up and using strict
         * inequality to err on the side of safety, we get:
         * feerate-per-kw > 252
         * hence feerate-per-kw >= 253
         */
        val MinimumFeeratePerKw = FeeratePerKw(253.sat)
    }
}

/** Fee rates in satoshi-per-kilo-bytes (1 kb = 1000 bytes). */
data class FeeratesPerKB(
    val block1: FeeratePerKB,
    val blocks2: FeeratePerKB,
    val blocks6: FeeratePerKB,
    val blocks12: FeeratePerKB,
    val blocks36: FeeratePerKB,
    val blocks72: FeeratePerKB,
    val blocks144: FeeratePerKB,
    val blocks1008: FeeratePerKB
) {
    init {
        require(block1.feerate > 0.sat && blocks2.feerate > 0.sat && blocks6.feerate > 0.sat && blocks12.feerate > 0.sat && blocks36.feerate > 0.sat && blocks72.feerate > 0.sat && blocks144.feerate > 0.sat && blocks1008.feerate > 0.sat) { "all feerates must be strictly greater than 0" }
    }

    fun feePerBlock(target: Int): FeeratePerKB = when {
        target == 1 -> block1
        target == 2 -> blocks2
        target <= 6 -> blocks6
        target <= 12 -> blocks12
        target <= 36 -> blocks36
        target <= 72 -> blocks72
        target <= 144 -> blocks144
        else -> blocks1008
    }
}

/** Fee rates in satoshi/kw (1 kw = 1000 weight units). */
data class FeeratesPerKw(
    val block1: FeeratePerKw,
    val blocks2: FeeratePerKw,
    val blocks6: FeeratePerKw,
    val blocks12: FeeratePerKw,
    val blocks36: FeeratePerKw,
    val blocks72: FeeratePerKw,
    val blocks144: FeeratePerKw,
    val blocks1008: FeeratePerKw
) {
    init {
        require(block1.feerate > 0.sat && blocks2.feerate > 0.sat && blocks6.feerate > 0.sat && blocks12.feerate > 0.sat && blocks36.feerate > 0.sat && blocks72.feerate > 0.sat && blocks144.feerate > 0.sat && blocks1008.feerate > 0.sat) { "all feerates must be strictly greater than 0" }
    }

    constructor(feerates: FeeratesPerKB) : this(
        FeeratePerKw(feerates.block1),
        FeeratePerKw(feerates.blocks2),
        FeeratePerKw(feerates.blocks6),
        FeeratePerKw(feerates.blocks12),
        FeeratePerKw(feerates.blocks36),
        FeeratePerKw(feerates.blocks72),
        FeeratePerKw(feerates.blocks144),
        FeeratePerKw(feerates.blocks1008)
    )

    fun feePerBlock(target: Int): FeeratePerKw = when {
        target == 1 -> block1
        target == 2 -> blocks2
        target <= 6 -> blocks6
        target <= 12 -> blocks12
        target <= 36 -> blocks36
        target <= 72 -> blocks72
        target <= 144 -> blocks144
        else -> blocks1008
    }

    companion object {
        /** Used in tests. */
        fun single(feeratePerKw: FeeratePerKw): FeeratesPerKw = FeeratesPerKw(feeratePerKw, feeratePerKw, feeratePerKw, feeratePerKw, feeratePerKw, feeratePerKw, feeratePerKw, feeratePerKw)
    }
}
