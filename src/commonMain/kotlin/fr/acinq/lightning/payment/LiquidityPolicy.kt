package fr.acinq.lightning.payment

import fr.acinq.bitcoin.Satoshi
import fr.acinq.lightning.LiquidityEvents
import fr.acinq.lightning.MilliSatoshi
import fr.acinq.lightning.utils.MDCLogger
import fr.acinq.lightning.utils.toMilliSatoshi


sealed class LiquidityPolicy {
    /** Never initiates swap-ins, never accept pay-to-open */
    object Disable : LiquidityPolicy()

    /**
     * Allow automated liquidity managements, within relative and absolute fee limits. Both conditions must be met.
     * @param maxAbsoluteFee max absolute fee
     * @param maxRelativeFeeBasisPoints max relative fee (all included: service fee and mining fee) (1_000 bips = 10 %)
     * @param skipAbsoluteFeeCheck useful for pay-to-open, being more lax may make sense when the sender doesn't retry payments
     */
    data class Auto(val maxAbsoluteFee: Satoshi, val maxRelativeFeeBasisPoints: Int, val skipAbsoluteFeeCheck: Boolean) : LiquidityPolicy() {
        /** Maximum fee that we are willing to pay for a particular amount */
        fun maxFee(amount: MilliSatoshi) =
            if (skipAbsoluteFeeCheck) {
                amount * maxRelativeFeeBasisPoints / 10_000
            } else {
                maxAbsoluteFee.toMilliSatoshi().min(amount * maxRelativeFeeBasisPoints / 10_000)
            }
    }

    /** Make decision for a particular liquidity event */
    fun maybeReject(amount: MilliSatoshi, fee: MilliSatoshi, source: LiquidityEvents.Source, logger: MDCLogger): LiquidityEvents.Rejected? {
        return when (this) {
            is Disable -> LiquidityEvents.Rejected.Reason.PolicySetToDisabled
            is Auto -> {
                val maxAllowedFee = maxFee(amount)
                logger.info { "liquidity policy check: fee=$fee maxAllowedFee=$maxAllowedFee policy=$this" }
                if (fee > maxAllowedFee) {
                    LiquidityEvents.Rejected.Reason.TooExpensive(maxAllowed = maxAllowedFee, actual = fee)
                } else null
            }
        }?.let { reason -> LiquidityEvents.Rejected(amount, fee, source, reason) }
    }

}