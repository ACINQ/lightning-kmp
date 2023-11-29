package fr.acinq.lightning.wire

import fr.acinq.bitcoin.Satoshi
import fr.acinq.bitcoin.io.Input
import fr.acinq.bitcoin.io.Output
import fr.acinq.lightning.MilliSatoshi
import fr.acinq.lightning.blockchain.fee.FeeratePerKw
import fr.acinq.lightning.transactions.Transactions
import fr.acinq.lightning.utils.msat
import fr.acinq.lightning.utils.sat

/**
 * Liquidity ads create a decentralized market for channel liquidity.
 * Nodes advertise fee rates for their available liquidity using the gossip protocol.
 * Other nodes can then pay the advertised rate to get inbound liquidity allocated towards them.
 */
object LiquidityAds {

    /**
     * Liquidity is leased using the following rates:
     *
     *  - the buyer pays [[leaseFeeBase]] regardless of the amount contributed by the seller
     *  - the buyer pays [[leaseFeeProportional]] (expressed in basis points) of the amount contributed by the seller
     *  - the buyer refunds the on-chain fees for up to [[fundingWeight]] of the utxos contributed by the seller
     *
     * The seller promises that their relay fees towards the buyer will never exceed [[maxRelayFeeBase]] and [[maxRelayFeeProportional]].
     * This cannot be enforced, but if the buyer notices the seller cheating, they should blacklist them and can prove
     * that they misbehaved.
     */
    data class LeaseRates(val fundingWeight: Int, val leaseFeeProportional: Int, val maxRelayFeeProportional: Int, val leaseFeeBase: Satoshi, val maxRelayFeeBase: MilliSatoshi) {
        val maxRelayFeeProportionalMillionths: Long = maxRelayFeeProportional.toLong() * 100

        /**
         * Fees paid by the liquidity buyer: the resulting amount must be added to the seller's output in the corresponding
         * commitment transaction.
         */
        fun fees(feerate: FeeratePerKw, requestedAmount: Satoshi, contributedAmount: Satoshi): Satoshi {
            val onChainFees = Transactions.weight2fee(feerate, fundingWeight)
            // If the seller adds more liquidity than requested, the buyer doesn't pay for that extra liquidity.
            val proportionalFee = requestedAmount.min(contributedAmount) * leaseFeeProportional / 10_000
            return leaseFeeBase + proportionalFee + onChainFees
        }

        fun write(out: Output) {
            LightningCodecs.writeU16(fundingWeight, out)
            LightningCodecs.writeU16(leaseFeeProportional, out)
            LightningCodecs.writeU16(maxRelayFeeProportional, out)
            LightningCodecs.writeU32(leaseFeeBase.sat.toInt(), out)
            LightningCodecs.writeTU32(maxRelayFeeBase.msat.toInt(), out)
        }

        companion object {
            fun read(input: Input): LeaseRates = LeaseRates(
                fundingWeight = LightningCodecs.u16(input),
                leaseFeeProportional = LightningCodecs.u16(input),
                maxRelayFeeProportional = LightningCodecs.u16(input),
                leaseFeeBase = LightningCodecs.u32(input).sat,
                maxRelayFeeBase = LightningCodecs.tu32(input).msat,
            )
        }
    }

}