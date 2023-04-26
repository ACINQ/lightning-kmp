package fr.acinq.lightning.payment

import fr.acinq.bitcoin.Satoshi
import fr.acinq.lightning.LiquidityEvents
import fr.acinq.lightning.MilliSatoshi
import fr.acinq.lightning.utils.LoggingContext
import fr.acinq.lightning.utils.MDCLogger
import fr.acinq.lightning.utils.toMilliSatoshi


sealed class LiquidityPolicy {
    /** Never initiates swap-ins, never accept pay-to-open */
    object Disable : LiquidityPolicy()

    /**
     * Allow automated liquidity managements, within relative and absolute fee limits. Both conditions must be met.
     * @param maxAbsoluteFee max absolute fee
     * @param maxRelativeFeeBasisPoints max relative fee (all included: service fee and mining fee) (1_000 bips = 10 %)
     */
    data class Auto(val maxAbsoluteFee: Satoshi, val maxRelativeFeeBasisPoints: Int, ) : LiquidityPolicy() {
        /** Maximum fee that we are willing to pay for a particular amount */
        fun maxFee(amount: MilliSatoshi) = maxAbsoluteFee.toMilliSatoshi().min(amount * maxRelativeFeeBasisPoints / 10_000)
    }

    /** Make decision for a particular liquidity event */
    fun maybeReject(amount: MilliSatoshi, fee: MilliSatoshi, source: LiquidityEvents.Source, logger: MDCLogger): LiquidityEvents.Rejected? {
        return when (this@LiquidityPolicy) {
            is Disable -> LiquidityEvents.Rejected.Reason.PolicySetToDisabled
            is Auto -> {
                val maxAllowedFee = maxFee(amount)
                logger.info { "liquity policy check: fee=$fee maxAllowedFee=$maxAllowedFee policy=${this@LiquidityPolicy}" }
                if (fee > maxAllowedFee) {
                    LiquidityEvents.Rejected.Reason.TooExpensive(maxAllowed = maxAllowedFee, actual = fee)
                } else null
            }
        }?.let { reason -> LiquidityEvents.Rejected(amount, fee, source, reason) }
    }

}