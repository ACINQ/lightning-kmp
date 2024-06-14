package fr.acinq.lightning.wire

import fr.acinq.bitcoin.*
import fr.acinq.bitcoin.io.ByteArrayInput
import fr.acinq.bitcoin.io.ByteArrayOutput
import fr.acinq.bitcoin.io.Input
import fr.acinq.bitcoin.io.Output
import fr.acinq.bitcoin.utils.Either
import fr.acinq.lightning.MilliSatoshi
import fr.acinq.lightning.blockchain.fee.FeeratePerKw
import fr.acinq.lightning.channel.ChannelException
import fr.acinq.lightning.channel.InvalidLiquidityAdsAmount
import fr.acinq.lightning.channel.InvalidLiquidityAdsSig
import fr.acinq.lightning.channel.MissingLiquidityAds
import fr.acinq.lightning.transactions.Transactions
import fr.acinq.lightning.utils.BitField
import fr.acinq.lightning.utils.sat

/**
 * Liquidity ads create a decentralized market for channel liquidity.
 * Nodes advertise fee rates for their available liquidity using the gossip protocol.
 * Other nodes can then pay the advertised rate to get inbound liquidity allocated towards them.
 */
object LiquidityAds {

    /**
     * @param miningFee we refund the liquidity provider for some of the fee they paid to miners for the underlying on-chain transaction.
     * @param serviceFee fee paid to the liquidity provider for the inbound liquidity.
     */
    data class LeaseFees(val miningFee: Satoshi, val serviceFee: Satoshi) {
        val total: Satoshi = miningFee + serviceFee
    }

    /** Fees paid for the funding transaction that provides liquidity. */
    data class FundingFee(val amount: MilliSatoshi, val fundingTxId: TxId)

    /**
     * Liquidity fees are computed based on multiple components.
     *
     * @param fundingWeight the seller will have to add inputs/outputs to the transaction and pay on-chain fees for them.
     * The buyer refunds those on-chain fees for the given vbytes.
     * @param leaseFeeBase flat fee that must be paid regardless of the amount contributed by the seller.
     * @param leaseFeeProportional proportional fee (expressed in basis points) based on the amount contributed by the seller.
     */
    data class LeaseRate(val fundingWeight: Int, val leaseFeeBase: Satoshi, val leaseFeeProportional: Int) {
        /** Fees paid by the liquidity buyer. */
        fun fees(feerate: FeeratePerKw, requestedAmount: Satoshi, contributedAmount: Satoshi): LeaseFees {
            val onChainFees = Transactions.weight2fee(feerate, fundingWeight)
            // If the seller adds more liquidity than requested, the buyer doesn't pay for that extra liquidity.
            val proportionalFee = requestedAmount.min(contributedAmount) * leaseFeeProportional / 10_000
            return LeaseFees(onChainFees, leaseFeeBase + proportionalFee)
        }
    }

    sealed class FundingLease {
        abstract fun fees(feerate: FeeratePerKw, requestedAmount: Satoshi, contributedAmount: Satoshi): LeaseFees

        /**
         * @param minAmount minimum amount that can be purchased at this [rate].
         * @param maxAmount maximum amount that can be purchased at this [rate].
         * @param rate lease rate.
         */
        data class Basic(val minAmount: Satoshi, val maxAmount: Satoshi, val rate: LeaseRate) : FundingLease() {
            override fun fees(feerate: FeeratePerKw, requestedAmount: Satoshi, contributedAmount: Satoshi): LeaseFees = rate.fees(feerate, requestedAmount, contributedAmount)

            fun encode(): ByteArray {
                val out = ByteArrayOutput()
                LightningCodecs.writeU32(minAmount.sat.toInt(), out)
                LightningCodecs.writeU32(maxAmount.sat.toInt(), out)
                LightningCodecs.writeU16(rate.fundingWeight, out)
                LightningCodecs.writeU16(rate.leaseFeeProportional, out)
                LightningCodecs.writeTU32(rate.leaseFeeBase.sat.toInt(), out)
                return out.toByteArray()
            }

            companion object {
                fun decode(bin: ByteArray): Basic {
                    val input = ByteArrayInput(bin)
                    return Basic(
                        minAmount = LightningCodecs.u32(input).sat,
                        maxAmount = LightningCodecs.u32(input).sat,
                        rate = LeaseRate(
                            fundingWeight = LightningCodecs.u16(input),
                            leaseFeeProportional = LightningCodecs.u16(input),
                            leaseFeeBase = LightningCodecs.tu32(input).sat
                        )
                    )
                }
            }
        }

        fun write(out: Output) = when (this) {
            is Basic -> {
                val encoded = encode()
                LightningCodecs.writeBigSize(0, out) // tag
                LightningCodecs.writeBigSize(encoded.size.toLong(), out) // length
                LightningCodecs.writeBytes(encoded, out)
            }
        }

        companion object {
            fun read(input: Input): FundingLease? {
                val tag = LightningCodecs.bigSize(input)
                // We always read the lease data, even if we don't support it: this lets us skip unknown lease types.
                val content = LightningCodecs.bytes(input, LightningCodecs.bigSize(input))
                return when (tag) {
                    0L -> Basic.decode(content)
                    else -> null
                }
            }
        }
    }

    sealed class FundingLeaseWitness {
        abstract val fundingScript: ByteVector

        /** The seller signs a witness that commits to the funding script. */
        data class Basic(override val fundingScript: ByteVector) : FundingLeaseWitness()

        fun signedData(): ByteArray {
            val tag = when (this) {
                is Basic -> "basic_funding_lease"
            }
            val tmp = ByteArrayOutput()
            write(tmp)
            return Crypto.sha256(tag.encodeToByteArray() + tmp.toByteArray())
        }

        fun write(out: Output) = when (this) {
            is Basic -> {
                LightningCodecs.writeBigSize(0, out) // tag
                LightningCodecs.writeBigSize(fundingScript.size().toLong(), out) // length
                LightningCodecs.writeBytes(fundingScript, out)
            }
        }

        companion object {
            fun read(input: Input): FundingLeaseWitness = when (val tag = LightningCodecs.bigSize(input)) {
                0L -> Basic(LightningCodecs.bytes(input, LightningCodecs.bigSize(input)).byteVector())
                else -> throw IllegalArgumentException("unknown funding lease witness (tag=$tag)")
            }
        }
    }

    /** The fees associated with a given [LeaseRate] can be paid using various options. */
    sealed class PaymentType {
        /** Fees are transferred from the buyer's channel balance to the seller's during the interactive-tx construction. */
        data object FromChannelBalance : PaymentType()
        /** Fees will be deducted from future HTLCs that will be relayed to the buyer. */
        data object FromFutureHtlc : PaymentType()
        /** Fees will be deducted from future HTLCs that will be relayed to the buyer, but the preimage is revealed immediately. */
        data object FromFutureHtlcWithPreimage : PaymentType()
        /** Fees will be taken from the buyer's current fee credit. */
        data object FromFeeCredit : PaymentType()
        /** Sellers may support unknown payment types, which we must ignore. */
        data class Unknown(val bitIndex: Int) : PaymentType()

        companion object {
            fun encode(paymentTypes: Set<PaymentType>): ByteArray {
                val bitIndices = paymentTypes.map {
                    when (it) {
                        is FromChannelBalance -> 0
                        is FromFutureHtlc -> 128
                        is FromFutureHtlcWithPreimage -> 129
                        is FromFeeCredit -> 130
                        is Unknown -> it.bitIndex
                    }
                }
                val bits = BitField.forAtMost(bitIndices.max() + 1)
                bitIndices.forEach { bits.setRight(it) }
                return bits.bytes
            }

            fun decode(bytes: ByteArray): Set<PaymentType> {
                return BitField.from(bytes).asRightSequence().withIndex().mapNotNull {
                    when {
                        it.value && it.index == 0 -> FromChannelBalance
                        it.value && it.index == 128 -> FromFutureHtlc
                        it.value && it.index == 129 -> FromFutureHtlcWithPreimage
                        it.value && it.index == 130 -> FromFeeCredit
                        it.value -> Unknown(it.index)
                        else -> null
                    }
                }.toSet()
            }
        }
    }

    /** When purchasing a [FundingLease], we provide payment details matching one of the [PaymentType] supported by the seller. */
    sealed class PaymentDetails {
        abstract val paymentType: PaymentType

        // @formatter:off
        data object FromChannelBalance : PaymentDetails() { override val paymentType: PaymentType = PaymentType.FromChannelBalance }
        data class FromFutureHtlc(val paymentHashes: List<ByteVector32>) : PaymentDetails() { override val paymentType: PaymentType = PaymentType.FromFutureHtlc }
        data class FromFutureHtlcWithPreimage(val preimages: List<ByteVector32>) : PaymentDetails() { override val paymentType: PaymentType = PaymentType.FromFutureHtlcWithPreimage }
        data object FromFeeCredit : PaymentDetails() { override val paymentType: PaymentType = PaymentType.FromFeeCredit }
        // @formatter:on

        fun write(out: Output) = when (this) {
            is FromChannelBalance -> {
                LightningCodecs.writeBigSize(0, out) // tag
                LightningCodecs.writeBigSize(0, out) // length
            }
            is FromFutureHtlc -> {
                LightningCodecs.writeBigSize(128, out) // tag
                LightningCodecs.writeBigSize(32 * paymentHashes.size.toLong(), out) // length
                paymentHashes.forEach { LightningCodecs.writeBytes(it, out) }
            }
            is FromFutureHtlcWithPreimage -> {
                LightningCodecs.writeBigSize(129, out) // tag
                LightningCodecs.writeBigSize(32 * preimages.size.toLong(), out) // length
                preimages.forEach { LightningCodecs.writeBytes(it, out) }
            }
            is FromFeeCredit -> {
                LightningCodecs.writeBigSize(130, out) // tag
                LightningCodecs.writeBigSize(0, out) // length
            }
        }

        companion object {
            fun read(input: Input): PaymentDetails = when (val tag = LightningCodecs.bigSize(input)) {
                0L -> {
                    require(LightningCodecs.bigSize(input) == 0L) { "invalid length for from_channel_balance payment details" }
                    FromChannelBalance
                }
                128L -> {
                    val count = LightningCodecs.bigSize(input) / 32
                    val paymentHashes = (0 until count).map { LightningCodecs.bytes(input, 32).byteVector32() }
                    FromFutureHtlc(paymentHashes)
                }
                129L -> {
                    val count = LightningCodecs.bigSize(input) / 32
                    val preimages = (0 until count).map { LightningCodecs.bytes(input, 32).byteVector32() }
                    FromFutureHtlcWithPreimage(preimages)
                }
                130L -> {
                    require(LightningCodecs.bigSize(input) == 0L) { "invalid length for from_fee_credit payment details" }
                    FromFeeCredit
                }
                else -> throw IllegalArgumentException("unknown payment details (tag=$tag)")
            }
        }
    }

    /** Sellers offer various rates and payment options. */
    data class WillFundRates(val fundingRates: List<FundingLease>, val paymentTypes: Set<PaymentType>) {
        fun validateRequest(nodeKey: PrivateKey, fundingScript: ByteVector, fundingFeerate: FeeratePerKw, request: RequestFunds): WillFundLease? {
            val paymentTypeOk = paymentTypes.contains(request.paymentDetails.paymentType)
            val leaseOk = fundingRates.contains(request.fundingLease)
            val amountOk = when (val lease = request.fundingLease) {
                is FundingLease.Basic -> lease.minAmount <= request.requestedAmount && request.requestedAmount <= lease.maxAmount
            }
            return when {
                paymentTypeOk && leaseOk && amountOk -> {
                    val witness = when (request.fundingLease) {
                        is FundingLease.Basic -> FundingLeaseWitness.Basic(fundingScript)
                    }
                    val sig = Crypto.sign(witness.signedData(), nodeKey)
                    val lease = Lease(request.requestedAmount, request.fees(fundingFeerate), request.paymentDetails, sig, witness)
                    WillFundLease(WillFund(witness, sig), lease)
                }
                else -> null
            }
        }

        fun findLease(requestedAmount: Satoshi): FundingLease? {
            return fundingRates
                .filterIsInstance<FundingLease.Basic>()
                .firstOrNull { it.minAmount <= requestedAmount && requestedAmount <= it.maxAmount }
        }

        fun write(out: Output) {
            LightningCodecs.writeU16(fundingRates.size, out)
            fundingRates.forEach { it.write(out) }
            val encoded = PaymentType.encode(paymentTypes)
            LightningCodecs.writeU16(encoded.size, out)
            LightningCodecs.writeBytes(encoded, out)
        }

        companion object {
            fun read(input: Input): WillFundRates {
                val fundingRatesCount = LightningCodecs.u16(input)
                val fundingRates = (0 until fundingRatesCount).mapNotNull { FundingLease.read(input) }
                val paymentTypes = PaymentType.decode(LightningCodecs.bytes(input, LightningCodecs.u16(input)))
                return WillFundRates(fundingRates, paymentTypes)
            }
        }
    }

    /** Provide inbound liquidity to a remote peer that wants to purchase a liquidity ads. */
    data class WillFund(val leaseWitness: FundingLeaseWitness, val signature: ByteVector64) {
        fun write(out: Output) {
            leaseWitness.write(out)
            LightningCodecs.writeBytes(signature, out)
        }

        companion object {
            fun read(input: Input): WillFund = WillFund(
                leaseWitness = FundingLeaseWitness.read(input),
                signature = LightningCodecs.bytes(input, 64).byteVector64(),
            )
        }
    }

    /** Request inbound liquidity from a remote peer that supports liquidity ads. */
    data class RequestFunds(val requestedAmount: Satoshi, val fundingLease: FundingLease, val paymentDetails: PaymentDetails) {
        fun fees(feerate: FeeratePerKw): LeaseFees = fundingLease.fees(feerate, requestedAmount, requestedAmount)

        fun validateLease(
            remoteNodeId: PublicKey,
            channelId: ByteVector32,
            fundingScript: ByteVector,
            remoteFundingAmount: Satoshi,
            fundingFeerate: FeeratePerKw,
            willFund: WillFund?
        ): Either<ChannelException, Lease> {
            return when (willFund) {
                // If the remote peer doesn't want to provide inbound liquidity, we immediately fail the attempt.
                // The user should retry this funding attempt without requesting inbound liquidity.
                null -> Either.Left(MissingLiquidityAds(channelId))
                else -> when {
                    willFund.leaseWitness.fundingScript != fundingScript -> Either.Left(InvalidLiquidityAdsSig(channelId))
                    !Crypto.verifySignature(willFund.leaseWitness.signedData(), willFund.signature, remoteNodeId) -> Either.Left(InvalidLiquidityAdsSig(channelId))
                    remoteFundingAmount < requestedAmount -> Either.Left(InvalidLiquidityAdsAmount(channelId, remoteFundingAmount, requestedAmount))
                    else -> {
                        val leaseAmount = requestedAmount.min(remoteFundingAmount)
                        val leaseFees = fundingLease.fees(fundingFeerate, requestedAmount, remoteFundingAmount)
                        Either.Right(Lease(leaseAmount, leaseFees, paymentDetails, willFund.signature, willFund.leaseWitness))
                    }
                }
            }
        }

        fun write(out: Output) {
            LightningCodecs.writeU64(requestedAmount.toLong(), out)
            fundingLease.write(out)
            paymentDetails.write(out)
        }

        companion object {
            fun chooseLease(requestedAmount: Satoshi, paymentDetails: PaymentDetails, rates: WillFundRates): RequestFunds? = when {
                rates.paymentTypes.contains(paymentDetails.paymentType) -> rates.findLease(requestedAmount)?.let { RequestFunds(requestedAmount, it, paymentDetails) }
                else -> null
            }

            fun read(input: Input): RequestFunds = RequestFunds(
                requestedAmount = LightningCodecs.u64(input).sat,
                fundingLease = FundingLease.read(input) ?: throw IllegalArgumentException("unknown funding lease type"),
                paymentDetails = PaymentDetails.read(input),
            )
        }
    }

    fun validateLease(
        request: RequestFunds?,
        remoteNodeId: PublicKey,
        channelId: ByteVector32,
        fundingScript: ByteVector,
        remoteFundingAmount: Satoshi,
        fundingFeerate: FeeratePerKw,
        willFund: WillFund?,
    ): Either<ChannelException, Lease?> {
        return when (request) {
            null -> Either.Right(null)
            else -> request.validateLease(remoteNodeId, channelId, fundingScript, remoteFundingAmount, fundingFeerate, willFund)
        }
    }

    /** Once a liquidity ads has been paid, we keep track of the fees paid and the seller signature. */
    data class Lease(val amount: Satoshi, val fees: LeaseFees, val paymentDetails: PaymentDetails, val sellerSig: ByteVector64, val witness: FundingLeaseWitness)

    data class WillFundLease(val willFund: WillFund, val lease: Lease)

}