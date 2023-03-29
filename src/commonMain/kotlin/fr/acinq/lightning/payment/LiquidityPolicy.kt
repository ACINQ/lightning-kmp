package fr.acinq.lightning.payment

import fr.acinq.bitcoin.Satoshi
import fr.acinq.lightning.LiquidityEvents
import fr.acinq.lightning.MilliSatoshi
import fr.acinq.lightning.utils.toMilliSatoshi


sealed class LiquidityPolicy {
    /** Never initiates swap-ins, never accept pay-to-open */
    object Disable : LiquidityPolicy()

    /**
     * Allow automated liquidity managements, within fee limits
     * @param maxFeeBasisPoints the max total acceptable fee (all included: service fee and mining fee) (100 bips = 1 %)
     * @param maxFeeFloor as long as fee is below this amount, it's okay (whatever the percentage is)
     */
    data class Auto(val maxFeeBasisPoints: Int, val maxFeeFloor: Satoshi) : LiquidityPolicy() {
        /** Maximum fee that we are willing to pay for a particular amount */
        fun maxFee(amount: MilliSatoshi) = (amount * maxFeeBasisPoints / 10_000).max(maxFeeFloor.toMilliSatoshi())
    }

    /** Make decision for a particular liquidity event */
    fun maybeReject(amount: MilliSatoshi, fee: MilliSatoshi, source: LiquidityEvents.Source): LiquidityEvents.Rejected? {
        return when (this) {
            is Disable -> LiquidityEvents.Rejected.Reason.PolicySetToDisabled
            is Auto -> {
                val maxAllowedFee = maxFee(amount)
                if (fee > maxAllowedFee) {
                    LiquidityEvents.Rejected.Reason.TooExpensive(maxAllowed = maxAllowedFee, actual = fee)
                } else null
            }
        }?.let { reason -> LiquidityEvents.Rejected(amount, fee, source, reason) }
    }

}