package fr.acinq.lightning.payment

import fr.acinq.bitcoin.Satoshi
import fr.acinq.lightning.LiquidityEvents
import fr.acinq.lightning.MilliSatoshi
import fr.acinq.lightning.logging.MDCLogger
import fr.acinq.lightning.utils.msat
import fr.acinq.lightning.utils.sat
import fr.acinq.lightning.utils.toMilliSatoshi


sealed class LiquidityPolicy {
    /** Never initiates swap-ins, never accept pay-to-open */
    data object Disable : LiquidityPolicy()

    /**
     * Allow automated liquidity managements, within relative and absolute fee limits. Both conditions must be met.
     * @param maxAbsoluteFee max absolute fee
     * @param maxRelativeFeeBasisPoints max relative fee (all included: service fee and mining fee) (1_000 bips = 10 %)
     * @param skipAbsoluteFeeCheck only applies for off-chain payments, being more lax may make sense when the sender doesn't retry payments
     * @param maxAllowedCredit if other checks fail, accept the payment and add the corresponding amount to fee credit up to this max value (only applies to offline payments, 0 sat to disable)
     */
    data class Auto(val maxAbsoluteFee: Satoshi, val maxRelativeFeeBasisPoints: Int, val skipAbsoluteFeeCheck: Boolean, val maxAllowedCredit: Satoshi) : LiquidityPolicy()

    /** Make decision for a particular liquidity event */
    fun maybeReject(amount: MilliSatoshi, fee: MilliSatoshi, source: LiquidityEvents.Source, logger: MDCLogger, currentFeeCredit: Satoshi): LiquidityEvents.Decision {
        return when (this) {
            is Disable -> LiquidityEvents.Decision.Rejected(amount, fee, source, LiquidityEvents.Decision.Rejected.Reason.PolicySetToDisabled)
            is Auto -> {
                val maxAbsoluteFee = if (skipAbsoluteFeeCheck && source == LiquidityEvents.Source.OffChainPayment) Long.MAX_VALUE.msat else this.maxAbsoluteFee.toMilliSatoshi()
                if (maxAllowedCredit == 0.sat || source == LiquidityEvents.Source.OnChainWallet) {
                    val maxRelativeFee = amount * maxRelativeFeeBasisPoints / 10_000
                    logger.info { "auto liquidity policy check: amount=$amount fee=$fee maxAbsoluteFee=$maxAbsoluteFee maxRelativeFee=$maxRelativeFee policy=$this" }
                    if (fee > maxRelativeFee) {
                        LiquidityEvents.Decision.Rejected(amount, fee, source, LiquidityEvents.Decision.Rejected.Reason.TooExpensive.OverRelativeFee(maxRelativeFeeBasisPoints))
                    } else if (fee > maxAbsoluteFee) {
                        LiquidityEvents.Decision.Rejected(amount, fee, source, LiquidityEvents.Decision.Rejected.Reason.TooExpensive.OverAbsoluteFee(this.maxAbsoluteFee))
                    } else LiquidityEvents.Decision.Accepted(amount, fee, source)
                } else {
                    logger.info { "fee-credit liquidity policy check: amount=$amount fee=$fee maxAbsoluteFee=$maxAbsoluteFee currentFeeCredit=$currentFeeCredit maxAllowedCredit=$maxAllowedCredit policy=$this" }
                    // NB: we do check the max absolute fee, but will never raise an explicit error for it, because the payment will either be added to fee credit or rejected due to exceeding the
                    // max allowed credit
                    if (fee <= maxAbsoluteFee && fee < (amount + currentFeeCredit.toMilliSatoshi())) {
                        LiquidityEvents.Decision.Accepted(amount, fee, source)
                    } else if ((amount + currentFeeCredit.toMilliSatoshi()) > maxAllowedCredit.toMilliSatoshi()) {
                        LiquidityEvents.Decision.Rejected(amount, fee, source, LiquidityEvents.Decision.Rejected.Reason.OverMaxCredit(maxAllowedCredit))
                    } else {
                        LiquidityEvents.Decision.AddedToFeeCredit(amount, fee, source)
                    }
                }
            }
        }
    }

}