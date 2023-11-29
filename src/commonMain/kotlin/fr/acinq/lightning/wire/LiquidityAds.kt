package fr.acinq.lightning.wire

import fr.acinq.bitcoin.*
import fr.acinq.bitcoin.io.ByteArrayOutput
import fr.acinq.bitcoin.io.Input
import fr.acinq.bitcoin.io.Output
import fr.acinq.lightning.MilliSatoshi
import fr.acinq.lightning.blockchain.fee.FeeratePerKw
import fr.acinq.lightning.channel.ChannelException
import fr.acinq.lightning.channel.InvalidLiquidityAdsSig
import fr.acinq.lightning.channel.LiquidityRatesRejected
import fr.acinq.lightning.channel.MissingLiquidityAds
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

        fun signLease(nodeKey: PrivateKey, localFundingPubKey: PublicKey, requestFunds: ChannelTlv.RequestFunds): ChannelTlv.WillFund {
            val witness = LeaseWitness(localFundingPubKey, requestFunds.leaseExpiry, requestFunds.leaseDuration, maxRelayFeeProportional, maxRelayFeeBase)
            val sig = witness.sign(nodeKey)
            return ChannelTlv.WillFund(sig, this)
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

    /** Request inbound liquidity from a remote peer that supports liquidity ads. */
    data class RequestRemoteFunding(val fundingAmount: Satoshi, val maxFee: Satoshi, val leaseStart: Int, val leaseDuration: Int) {
        private val leaseExpiry: Int = leaseStart + leaseDuration
        val requestFunds: ChannelTlv.RequestFunds = ChannelTlv.RequestFunds(fundingAmount, leaseExpiry, leaseDuration)

        fun validateLeaseRates(
            remoteNodeId: PublicKey,
            channelId: ByteVector32,
            remoteFundingPubKey: PublicKey,
            remoteFundingAmount: Satoshi,
            fundingFeerate: FeeratePerKw,
            willFund: ChannelTlv.WillFund?
        ): Either<ChannelException, Lease> {
            return when (willFund) {
                // If the remote peer doesn't want to provide inbound liquidity, we immediately fail the attempt.
                // The user should retry this funding attempt without requesting inbound liquidity.
                null -> Either.Left(MissingLiquidityAds(channelId))
                else -> {
                    val witness = LeaseWitness(remoteFundingPubKey, leaseExpiry, leaseDuration, willFund.leaseRates.maxRelayFeeProportional, willFund.leaseRates.maxRelayFeeBase)
                    val fees = willFund.leaseRates.fees(fundingFeerate, fundingAmount, remoteFundingAmount)
                    return if (!LeaseWitness.verify(remoteNodeId, willFund.sig, witness)) {
                        Either.Left(InvalidLiquidityAdsSig(channelId))
                    } else if (remoteFundingAmount <= 0.sat) {
                        Either.Left(LiquidityRatesRejected(channelId))
                    } else if (maxFee < fees) {
                        Either.Left(LiquidityRatesRejected(channelId))
                    } else {
                        val leaseAmount = fundingAmount.min(remoteFundingAmount)
                        Either.Right(Lease(leaseAmount, fees, willFund.sig, witness))
                    }
                }
            }
        }
    }

    fun validateLeaseRates(
        remoteNodeId: PublicKey,
        channelId: ByteVector32,
        remoteFundingPubKey: PublicKey,
        remoteFundingAmount: Satoshi,
        fundingFeerate: FeeratePerKw,
        willFund: ChannelTlv.WillFund?,
        request: RequestRemoteFunding?
    ): Either<ChannelException, Lease?> {
        return when (request) {
            null -> Either.Right(null)
            else -> request.validateLeaseRates(remoteNodeId, channelId, remoteFundingPubKey, remoteFundingAmount, fundingFeerate, willFund)
        }
    }

    /**
     * Once a liquidity ads has been paid, we should keep track of the lease, and check that our peer doesn't raise their
     * routing fees above the values they signed up for.
     */
    data class Lease(val amount: Satoshi, val fees: Satoshi, val sellerSig: ByteVector64, val witness: LeaseWitness) {
        val expiry: Int = witness.leaseEnd
    }

    /** The seller signs the lease parameters: if they cheat, the buyer can use that signature to prove they cheated. */
    data class LeaseWitness(val fundingPubKey: PublicKey, val leaseEnd: Int, val leaseDuration: Int, val maxRelayFeeProportional: Int, val maxRelayFeeBase: MilliSatoshi) {
        fun sign(nodeKey: PrivateKey): ByteVector64 = Crypto.sign(Crypto.sha256(encode()), nodeKey)

        fun encode(): ByteArray {
            val out = ByteArrayOutput()
            LightningCodecs.writeBytes("option_will_fund".encodeToByteArray(), out)
            LightningCodecs.writeBytes(fundingPubKey.value, out)
            LightningCodecs.writeU32(leaseEnd, out)
            LightningCodecs.writeU32(leaseDuration, out)
            LightningCodecs.writeU16(maxRelayFeeProportional, out)
            LightningCodecs.writeU32(maxRelayFeeBase.msat.toInt(), out)
            return out.toByteArray()
        }

        companion object {
            fun verify(nodeId: PublicKey, sig: ByteVector64, witness: LeaseWitness): Boolean = Crypto.verifySignature(Crypto.sha256(witness.encode()), sig, nodeId)
        }
    }

}