package fr.acinq.lightning.wire

import fr.acinq.bitcoin.*
import fr.acinq.bitcoin.io.ByteArrayOutput
import fr.acinq.bitcoin.io.Input
import fr.acinq.bitcoin.io.Output
import fr.acinq.lightning.MilliSatoshi
import fr.acinq.lightning.blockchain.fee.FeeratePerKw
import fr.acinq.lightning.channel.*
import fr.acinq.lightning.transactions.Transactions
import fr.acinq.lightning.utils.Either
import fr.acinq.lightning.utils.msat
import fr.acinq.lightning.utils.sat

/**
 * Liquidity ads create a decentralized market for channel liquidity.
 * Nodes advertise fee rates for their available liquidity using the gossip protocol.
 * Other nodes can then pay the advertised rate to get inbound liquidity allocated towards them.
 */
object LiquidityAds {

    /**
     * @param miningFee fee paid to miners for the underlying on-chain transaction.
     * @param serviceFee fee paid to the liquidity provider for the inbound liquidity.
     */
    data class LeaseFees(val miningFee: Satoshi, val serviceFee: Satoshi) {
        val total: Satoshi = miningFee + serviceFee
    }

    /**
     * Liquidity is leased using the following rates:
     *
     *  - the buyer pays [leaseFeeBase] regardless of the amount contributed by the seller
     *  - the buyer pays [leaseFeeProportional] (expressed in basis points) of the amount contributed by the seller
     *  - the seller will have to add inputs/outputs to the transaction and pay on-chain fees for them, but the buyer
     *    refunds on-chain fees for [fundingWeight] vbytes
     *
     * The seller promises that their relay fees towards the buyer will never exceed [maxRelayFeeBase] and [maxRelayFeeProportional].
     * This cannot be enforced, but if the buyer notices the seller cheating, they should blacklist them and can prove
     * that they misbehaved using the seller's signature of the [LeaseWitness].
     */
    data class LeaseRate(val leaseDuration: Int, val fundingWeight: Int, val leaseFeeProportional: Int, val leaseFeeBase: Satoshi, val maxRelayFeeProportional: Int, val maxRelayFeeBase: MilliSatoshi) {
        /**
         * Fees paid by the liquidity buyer: the resulting amount must be added to the seller's output in the corresponding
         * commitment transaction.
         */
        fun fees(feerate: FeeratePerKw, requestedAmount: Satoshi, contributedAmount: Satoshi): LeaseFees {
            val onChainFees = Transactions.weight2fee(feerate, fundingWeight)
            // If the seller adds more liquidity than requested, the buyer doesn't pay for that extra liquidity.
            val proportionalFee = requestedAmount.min(contributedAmount) * leaseFeeProportional / 10_000
            return LeaseFees(onChainFees, leaseFeeBase + proportionalFee)
        }

        fun signLease(nodeKey: PrivateKey, fundingScript: ByteVector, requestFunds: ChannelTlv.RequestFunds): ChannelTlv.WillFund {
            val witness = LeaseWitness(fundingScript, requestFunds.leaseDuration, requestFunds.leaseExpiry, maxRelayFeeProportional, maxRelayFeeBase)
            val sig = witness.sign(nodeKey)
            return ChannelTlv.WillFund(sig, fundingWeight, leaseFeeProportional, leaseFeeBase, maxRelayFeeProportional, maxRelayFeeBase)
        }

        fun write(out: Output) {
            LightningCodecs.writeU16(leaseDuration, out)
            LightningCodecs.writeU16(fundingWeight, out)
            LightningCodecs.writeU16(leaseFeeProportional, out)
            LightningCodecs.writeU32(leaseFeeBase.sat.toInt(), out)
            LightningCodecs.writeU16(maxRelayFeeProportional, out)
            LightningCodecs.writeU32(maxRelayFeeBase.msat.toInt(), out)
        }

        companion object {
            fun read(input: Input): LeaseRate = LeaseRate(
                leaseDuration = LightningCodecs.u16(input),
                fundingWeight = LightningCodecs.u16(input),
                leaseFeeProportional = LightningCodecs.u16(input),
                leaseFeeBase = LightningCodecs.u32(input).sat,
                maxRelayFeeProportional = LightningCodecs.u16(input),
                maxRelayFeeBase = LightningCodecs.u32(input).msat,
            )
        }
    }

    /** Request inbound liquidity from a remote peer that supports liquidity ads. */
    data class RequestRemoteFunding(val fundingAmount: Satoshi, val leaseStart: Int, val rate: LeaseRate) {
        private val leaseExpiry: Int = leaseStart + rate.leaseDuration
        val requestFunds: ChannelTlv.RequestFunds = ChannelTlv.RequestFunds(fundingAmount, rate.leaseDuration, leaseExpiry)

        fun validateLease(
            remoteNodeId: PublicKey,
            channelId: ByteVector32,
            fundingScript: ByteVector,
            remoteFundingAmount: Satoshi,
            fundingFeerate: FeeratePerKw,
            willFund: ChannelTlv.WillFund?
        ): Either<ChannelException, Lease> {
            return when (willFund) {
                // If the remote peer doesn't want to provide inbound liquidity, we immediately fail the attempt.
                // The user should retry this funding attempt without requesting inbound liquidity.
                null -> Either.Left(MissingLiquidityAds(channelId))
                else -> {
                    val witness = LeaseWitness(fundingScript, rate.leaseDuration, leaseExpiry, willFund.maxRelayFeeProportional, willFund.maxRelayFeeBase)
                    return if (!witness.verify(remoteNodeId, willFund.sig)) {
                        Either.Left(InvalidLiquidityAdsSig(channelId))
                    } else if (remoteFundingAmount < fundingAmount) {
                        Either.Left(InvalidLiquidityAdsAmount(channelId, remoteFundingAmount, fundingAmount))
                    } else if (willFund.leaseRate(rate.leaseDuration) != rate) {
                        Either.Left(InvalidLiquidityRates(channelId))
                    } else {
                        val leaseAmount = fundingAmount.min(remoteFundingAmount)
                        val leaseFees = rate.fees(fundingFeerate, fundingAmount, remoteFundingAmount)
                        Either.Right(Lease(leaseAmount, leaseFees, willFund.sig, witness))
                    }
                }
            }
        }
    }

    fun validateLease(
        request: RequestRemoteFunding?,
        remoteNodeId: PublicKey,
        channelId: ByteVector32,
        fundingScript: ByteVector,
        remoteFundingAmount: Satoshi,
        fundingFeerate: FeeratePerKw,
        willFund: ChannelTlv.WillFund?,
    ): Either<ChannelException, Lease?> {
        return when (request) {
            null -> Either.Right(null)
            else -> request.validateLease(remoteNodeId, channelId, fundingScript, remoteFundingAmount, fundingFeerate, willFund)
        }
    }

    /**
     * Once a liquidity ads has been paid, we should keep track of the lease, and check that our peer doesn't raise their
     * routing fees above the values they signed up for.
     */
    data class Lease(val amount: Satoshi, val fees: LeaseFees, val sellerSig: ByteVector64, val witness: LeaseWitness) {
        val start: Int = witness.leaseEnd - witness.leaseDuration
        val expiry: Int = witness.leaseEnd
    }

    /** The seller signs the lease parameters: if they cheat, the buyer can use that signature to prove they cheated. */
    data class LeaseWitness(val fundingScript: ByteVector, val leaseDuration: Int, val leaseEnd: Int, val maxRelayFeeProportional: Int, val maxRelayFeeBase: MilliSatoshi) {
        fun sign(nodeKey: PrivateKey): ByteVector64 = Crypto.sign(Crypto.sha256(encode()), nodeKey)

        fun verify(nodeId: PublicKey, sig: ByteVector64): Boolean = Crypto.verifySignature(Crypto.sha256(encode()), sig, nodeId)

        fun encode(): ByteArray {
            val out = ByteArrayOutput()
            LightningCodecs.writeBytes("option_will_fund".encodeToByteArray(), out)
            LightningCodecs.writeU16(fundingScript.size(), out)
            LightningCodecs.writeBytes(fundingScript, out)
            LightningCodecs.writeU16(leaseDuration, out)
            LightningCodecs.writeU32(leaseEnd, out)
            LightningCodecs.writeU16(maxRelayFeeProportional, out)
            LightningCodecs.writeU32(maxRelayFeeBase.msat.toInt(), out)
            return out.toByteArray()
        }
    }

}