package fr.acinq.lightning.payment

import fr.acinq.bitcoin.Satoshi
import fr.acinq.lightning.LiquidityEvents
import fr.acinq.lightning.MilliSatoshi
import fr.acinq.lightning.logging.MDCLogger
import fr.acinq.lightning.utils.msat
import fr.acinq.lightning.utils.sat
import fr.acinq.lightning.utils.toMilliSatoshi

sealed class LiquidityPolicy {
    /** Never initiates swap-ins, never accept on-the-fly funding requests. */
    data object Disable : LiquidityPolicy()

    /**
     * Allow automated liquidity management, within relative and absolute fee limits. Both conditions must be met.
     *
     * @param inboundLiquidityTarget amount of inbound liquidity the buyer would like to purchase whenever necessary (can be set to null to disable)
     * @param maxAbsoluteFee max absolute fee
     * @param maxRelativeFeeBasisPoints max relative fee (all included: service fee and mining fee) (1_000 bips = 10 %)
     * @param skipAbsoluteFeeCheck only applies for off-chain payments, being more lax may make sense when the sender doesn't retry payments
     */
    data class Auto(val inboundLiquidityTarget: Satoshi?, val maxAbsoluteFee: Satoshi, val maxRelativeFeeBasisPoints: Int, val skipAbsoluteFeeCheck: Boolean) : LiquidityPolicy()

    /** Make a decision for a particular liquidity event. */
    fun maybeReject(amount: MilliSatoshi, fee: MilliSatoshi, source: LiquidityEvents.Source, logger: MDCLogger): LiquidityEvents.Rejected? {
        return when (this) {
            is Disable -> LiquidityEvents.Rejected.Reason.PolicySetToDisabled
            is Auto -> {
                val maxAbsoluteFee = if (skipAbsoluteFeeCheck && source == LiquidityEvents.Source.OffChainPayment) Long.MAX_VALUE.msat else this.maxAbsoluteFee.toMilliSatoshi()
                val maxRelativeFee = amount * maxRelativeFeeBasisPoints / 10_000
                logger.info { "liquidity policy check: fee=$fee maxAbsoluteFee=$maxAbsoluteFee maxRelativeFee=$maxRelativeFee policy=$this" }
                when {
                    fee > maxRelativeFee -> LiquidityEvents.Rejected.Reason.TooExpensive.OverRelativeFee(maxRelativeFeeBasisPoints)
                    fee > maxAbsoluteFee -> LiquidityEvents.Rejected.Reason.TooExpensive.OverAbsoluteFee(this.maxAbsoluteFee)
                    else -> null // accept
                }
            }
        }?.let { reason -> LiquidityEvents.Rejected(amount, fee, source, reason) }
    }

    companion object {
        /**
         * We usually need our peer to contribute to channel funding, because they must have enough funds to pay the commitment fees.
         * When we don't have an inbound liquidity target set, we use the following default amount.
         */
        val minInboundLiquidityTarget: Satoshi = 100_000.sat
    }
}