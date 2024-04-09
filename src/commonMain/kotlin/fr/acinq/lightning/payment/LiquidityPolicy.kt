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
     * @param maxMiningFee max mining fee
     * @param maxRelativeFeeBasisPoints max relative fee (all included: service fee and mining fee) (1_000 bips = 10 %)
     * @param skipMiningFeeCheck only applies for off-chain payments, being more lax may make sense when the sender doesn't retry payments
     * @param maxAllowedCredit if other checks fail, accept the payment and add the corresponding amount to fee credit up to this max value (only applies to offline payments, 0 sat to disable)
     */
    data class Auto(val maxMiningFee: Satoshi, val maxRelativeFeeBasisPoints: Int, val skipMiningFeeCheck: Boolean, val maxAllowedCredit: Satoshi) : LiquidityPolicy()

    /** Make decision for a particular liquidity event */
    fun maybeReject(amount: MilliSatoshi, fee: MilliSatoshi, source: LiquidityEvents.Source, logger: MDCLogger, currentFeeCredit: Satoshi, liquidity: Satoshi = 0.sat): LiquidityEvents.Decision {
        val serviceFee = when (source) {
            LiquidityEvents.Source.OnChainWallet -> 0.msat // no service fee for swap-ins
            LiquidityEvents.Source.OffChainPayment -> (amount + liquidity.toMilliSatoshi()) * 0.01 // 1% service fee
        }
        val miningFee = fee - serviceFee
        return when (this) {
            is Disable -> LiquidityEvents.Decision.Rejected(amount, fee, source, LiquidityEvents.Decision.Rejected.Reason.PolicySetToDisabled)
            is Auto -> {
                val maxMiningFee = if (skipMiningFeeCheck && source == LiquidityEvents.Source.OffChainPayment) Long.MAX_VALUE.msat else this.maxMiningFee.toMilliSatoshi()
                if (maxAllowedCredit == 0.sat || source == LiquidityEvents.Source.OnChainWallet) {
                    val maxRelativeFee = amount * maxRelativeFeeBasisPoints / 10_000
                    logger.info { "auto liquidity policy check: amount=$amount fee=$fee (miningFee=$miningFee serviceFee=$serviceFee) maxMiningFee=$maxMiningFee maxRelativeFee=$maxRelativeFee policy=$this" }
                    if (fee > maxRelativeFee) {
                        LiquidityEvents.Decision.Rejected(amount, fee, source, LiquidityEvents.Decision.Rejected.Reason.TooExpensive.OverRelativeFee(maxRelativeFeeBasisPoints))
                    } else if (miningFee > maxMiningFee) {
                        LiquidityEvents.Decision.Rejected(amount, fee, source, LiquidityEvents.Decision.Rejected.Reason.TooExpensive.OverMaxMiningFee(this.maxMiningFee))
                    } else LiquidityEvents.Decision.Accepted(amount, fee, source)
                } else {
                    logger.info { "fee-credit liquidity policy check: amount=$amount fee=$fee (miningFee=$miningFee serviceFee=$serviceFee) maxMiningFee=$maxMiningFee currentFeeCredit=$currentFeeCredit maxAllowedCredit=$maxAllowedCredit policy=$this" }
                    // NB: we do check the max mining fee, but will never raise an explicit error for it, because the payment will either be added to fee credit or rejected due to exceeding the
                    // max allowed credit
                    if (miningFee <= maxMiningFee && fee < (amount + currentFeeCredit.toMilliSatoshi())) {
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