package fr.acinq.lightning.blockchain.fee

import fr.acinq.bitcoin.Satoshi
import fr.acinq.lightning.utils.sat

interface FeeEstimator {
    fun getFeerate(target: Int): FeeratePerKw
}

/**
 * Preferences regarding on-chain feerates that will be applied to various transactions.
 *
 * @param fundingFeerate feerate used for funding transactions, including splices (typically configured by the user, based on their preference).
 * @param mutualCloseFeerate feerate used in mutual close scenarios (typically configured by the user, based on their preference).
 * @param claimMainFeerate feerate used to claim our main output when a channel is force-closed (typically configured by the user, based on their preference).
 * @param fastFeerate feerate used to claim outputs quickly to avoid loss of funds: this one should not be set by the user (we should look at current on-chain fees).
 */
data class OnChainFeerates(val fundingFeerate: FeeratePerKw, val mutualCloseFeerate: FeeratePerKw, val claimMainFeerate: FeeratePerKw, val fastFeerate: FeeratePerKw)

data class FeerateTolerance(val ratioLow: Double, val ratioHigh: Double)

data class OnChainFeeConf(val closeOnOfflineMismatch: Boolean, val updateFeeMinDiffRatio: Double, val feerateTolerance: FeerateTolerance)

/** Fee rate in satoshi-per-bytes. */
data class FeeratePerByte(val feerate: Satoshi) {
    constructor(feeratePerKw: FeeratePerKw) : this(FeeratePerKB(feeratePerKw).feerate / 1000)

    override fun toString(): String = "$feerate/byte"
}

/** Fee rate in satoshi-per-kilo-bytes (1 kB = 1000 bytes). */
data class FeeratePerKB(val feerate: Satoshi) : Comparable<FeeratePerKB> {
    constructor(feeratePerByte: FeeratePerByte) : this(feeratePerByte.feerate * 1000)
    constructor(feeratePerKw: FeeratePerKw) : this(feeratePerKw.feerate * 4)

    override fun compareTo(other: FeeratePerKB): Int = feerate.compareTo(other.feerate)
    fun max(other: FeeratePerKB): FeeratePerKB = if (this > other) this else other
    fun min(other: FeeratePerKB): FeeratePerKB = if (this < other) this else other
    fun toLong(): Long = feerate.toLong()

    override fun toString(): String = "$feerate/kB"
}

/** Fee rate in satoshi-per-kilo-weight. */
data class FeeratePerKw(val feerate: Satoshi) : Comparable<FeeratePerKw> {
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

    override fun toString(): String = "$feerate/kw"

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

        /**
         * Since we're using anchor outputs, we don't need to constantly adjust the feerate of the commitment tx to match current on-chain feerates.
         * We can instead set it to a low-enough value, that still ensures the transaction will relay through the bitcoin network, and then use CPFP to make it confirm.
         * TODO: we should regularly get fee estimations from various sources to ensure this default value remains relay-able, and otherwise raise it dynamically.
         */
        val CommitmentFeerate = FeeratePerKw(FeeratePerByte(20.sat))
    }
}
