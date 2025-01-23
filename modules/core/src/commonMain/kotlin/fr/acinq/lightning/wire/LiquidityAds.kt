package fr.acinq.lightning.wire

import fr.acinq.bitcoin.*
import fr.acinq.bitcoin.io.ByteArrayOutput
import fr.acinq.bitcoin.io.Input
import fr.acinq.bitcoin.io.Output
import fr.acinq.bitcoin.utils.Either
import fr.acinq.lightning.MilliSatoshi
import fr.acinq.lightning.blockchain.fee.FeeratePerKw
import fr.acinq.lightning.channel.*
import fr.acinq.lightning.transactions.Transactions
import fr.acinq.lightning.utils.BitField
import fr.acinq.lightning.utils.msat
import fr.acinq.lightning.utils.sat
import fr.acinq.lightning.utils.toMilliSatoshi

/**
 * Liquidity ads create a decentralized market for channel liquidity.
 * Nodes advertise funding rates for their available liquidity using the gossip protocol.
 * Other nodes can then pay the advertised rate to get inbound liquidity allocated towards them.
 */
object LiquidityAds {

    /**
     * @param miningFee we refund the liquidity provider for some of the fee they paid to miners for the underlying on-chain transaction.
     * @param serviceFee fee paid to the liquidity provider for the inbound liquidity.
     */
    data class Fees(val miningFee: Satoshi, val serviceFee: Satoshi) {
        val total: Satoshi = miningFee + serviceFee
    }

    /** Fees paid for the funding transaction that provides liquidity. */
    data class FundingFee(val amount: MilliSatoshi, val fundingTxId: TxId)

    /**
     * Rate at which a liquidity seller sells its liquidity.
     * Liquidity fees are computed based on multiple components.
     *
     * @param minAmount minimum amount that can be purchased at this rate.
     * @param maxAmount maximum amount that can be purchased at this rate.
     * @param fundingWeight the seller will have to add inputs/outputs to the transaction and pay on-chain fees for them.
     * The buyer refunds those on-chain fees for the given vbytes.
     * @param feeProportional proportional fee (expressed in basis points) based on the amount contributed by the seller.
     * @param feeBase flat fee that must be paid regardless of the amount contributed by the seller.
     * @param channelCreationFee flat fee that must be paid when a new channel is created.
     */
    data class FundingRate(val minAmount: Satoshi, val maxAmount: Satoshi, val fundingWeight: Int, val feeProportional: Int, val feeBase: Satoshi, val channelCreationFee: Satoshi) {
        /** Fees paid by the liquidity buyer. */
        fun fees(feerate: FeeratePerKw, requestedAmount: Satoshi, contributedAmount: Satoshi, isChannelCreation: Boolean): Fees {
            val onChainFees = Transactions.weight2fee(feerate, fundingWeight)
            // If the seller adds more liquidity than requested, the buyer doesn't pay for that extra liquidity.
            val proportionalFee = requestedAmount.min(contributedAmount) * feeProportional / 10_000
            val flatFee = if (isChannelCreation) channelCreationFee + feeBase else feeBase
            return Fees(onChainFees, flatFee + proportionalFee)
        }

        /** When liquidity is purchased, the seller provides a signature of the funding rate and funding script. */
        fun signedData(fundingScript: ByteVector): ByteArray {
            // We use a tagged hash to ensure that our signature cannot be reused in a different context.
            val tag = "liquidity_ads_purchase"
            val tmp = ByteArrayOutput()
            write(tmp)
            return Crypto.sha256(tag.encodeToByteArray() + tmp.toByteArray() + fundingScript.toByteArray())
        }

        fun write(out: Output) {
            LightningCodecs.writeU32(minAmount.sat.toInt(), out)
            LightningCodecs.writeU32(maxAmount.sat.toInt(), out)
            LightningCodecs.writeU16(fundingWeight, out)
            LightningCodecs.writeU16(feeProportional, out)
            LightningCodecs.writeU32(feeBase.sat.toInt(), out)
            LightningCodecs.writeU32(channelCreationFee.sat.toInt(), out)
        }

        companion object {
            fun read(input: Input): FundingRate = FundingRate(
                minAmount = LightningCodecs.u32(input).sat,
                maxAmount = LightningCodecs.u32(input).sat,
                fundingWeight = LightningCodecs.u16(input),
                feeProportional = LightningCodecs.u16(input),
                feeBase = LightningCodecs.u32(input).sat,
                channelCreationFee = LightningCodecs.u32(input).sat,
            )
        }
    }

    /** The fees associated with a given [FundingRate] can be paid using various options. */
    sealed class PaymentType {
        /** Fees are transferred from the buyer's channel balance to the seller's during the interactive-tx construction. */
        data object FromChannelBalance : PaymentType()
        /** Fees will be deducted from future HTLCs that will be relayed to the buyer. */
        data object FromFutureHtlc : PaymentType()
        /** Fees will be deducted from future HTLCs that will be relayed to the buyer, but the preimage is revealed immediately. */
        data object FromFutureHtlcWithPreimage : PaymentType()
        /** Similar to [FromChannelBalance] but expects HTLCs to be relayed after funding. */
        data object FromChannelBalanceForFutureHtlc : PaymentType()
        /** Sellers may support unknown payment types, which we must ignore. */
        data class Unknown(val bitIndex: Int) : PaymentType()

        companion object {
            fun encode(paymentTypes: Set<PaymentType>): ByteArray {
                val bitIndices = paymentTypes.map {
                    when (it) {
                        is FromChannelBalance -> 0
                        is FromFutureHtlc -> 128
                        is FromFutureHtlcWithPreimage -> 129
                        is FromChannelBalanceForFutureHtlc -> 130
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
                        it.value && it.index == 130 -> FromChannelBalanceForFutureHtlc
                        it.value -> Unknown(it.index)
                        else -> null
                    }
                }.toSet()
            }
        }
    }

    /** When purchasing liquidity, we provide payment details matching one of the [PaymentType]s supported by the seller. */
    sealed class PaymentDetails {
        abstract val paymentType: PaymentType

        // @formatter:off
        data object FromChannelBalance : PaymentDetails() { override val paymentType: PaymentType = PaymentType.FromChannelBalance }
        data class FromFutureHtlc(val paymentHashes: List<ByteVector32>) : PaymentDetails() { override val paymentType: PaymentType = PaymentType.FromFutureHtlc }
        data class FromFutureHtlcWithPreimage(val preimages: List<ByteVector32>) : PaymentDetails() { override val paymentType: PaymentType = PaymentType.FromFutureHtlcWithPreimage }
        data class FromChannelBalanceForFutureHtlc(val paymentHashes: List<ByteVector32>) : PaymentDetails() { override val paymentType: PaymentType = PaymentType.FromChannelBalanceForFutureHtlc }
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
            is FromChannelBalanceForFutureHtlc -> {
                LightningCodecs.writeBigSize(130, out) // tag
                LightningCodecs.writeBigSize(32 * paymentHashes.size.toLong(), out) // length
                paymentHashes.forEach { LightningCodecs.writeBytes(it, out) }
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
                    val count = LightningCodecs.bigSize(input) / 32
                    val paymentHashes = (0 until count).map { LightningCodecs.bytes(input, 32).byteVector32() }
                    FromChannelBalanceForFutureHtlc(paymentHashes)
                }
                else -> throw IllegalArgumentException("unknown payment details (tag=$tag)")
            }
        }
    }

    /** Sellers offer various rates and payment options. */
    data class WillFundRates(val fundingRates: List<FundingRate>, val paymentTypes: Set<PaymentType>) {
        fun validateRequest(nodeKey: PrivateKey, fundingScript: ByteVector, fundingFeerate: FeeratePerKw, request: RequestFunding, isChannelCreation: Boolean, feeCreditUsed: MilliSatoshi): WillFundPurchase? {
            val paymentTypeOk = paymentTypes.contains(request.paymentDetails.paymentType)
            val rateOk = fundingRates.contains(request.fundingRate)
            val amountOk = request.fundingRate.minAmount <= request.requestedAmount && request.requestedAmount <= request.fundingRate.maxAmount
            return when {
                paymentTypeOk && rateOk && amountOk -> {
                    val sig = Crypto.sign(request.fundingRate.signedData(fundingScript), nodeKey)
                    val purchase = when (feeCreditUsed) {
                        0.msat -> Purchase.Standard(request.requestedAmount, request.fees(fundingFeerate, isChannelCreation), request.paymentDetails)
                        else -> Purchase.WithFeeCredit(request.requestedAmount, request.fees(fundingFeerate, isChannelCreation), feeCreditUsed, request.paymentDetails)
                    }
                    WillFundPurchase(WillFund(request.fundingRate, fundingScript, sig), purchase)
                }
                else -> null
            }
        }

        fun findRate(requestedAmount: Satoshi): FundingRate? {
            return fundingRates.firstOrNull { it.minAmount <= requestedAmount && requestedAmount <= it.maxAmount }
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
                val fundingRates = (0 until fundingRatesCount).mapNotNull { FundingRate.read(input) }
                val paymentTypes = PaymentType.decode(LightningCodecs.bytes(input, LightningCodecs.u16(input)))
                return WillFundRates(fundingRates, paymentTypes)
            }
        }
    }

    /** Provide inbound liquidity to a remote peer that wants to purchase liquidity. */
    data class WillFund(val fundingRate: FundingRate, val fundingScript: ByteVector, val signature: ByteVector64) {
        fun write(out: Output) {
            fundingRate.write(out)
            LightningCodecs.writeU16(fundingScript.size(), out)
            LightningCodecs.writeBytes(fundingScript, out)
            LightningCodecs.writeBytes(signature, out)
        }

        companion object {
            fun read(input: Input): WillFund = WillFund(
                fundingRate = FundingRate.read(input),
                fundingScript = LightningCodecs.bytes(input, LightningCodecs.u16(input)).byteVector(),
                signature = LightningCodecs.bytes(input, 64).byteVector64(),
            )
        }
    }

    /** Request inbound liquidity from a remote peer that supports liquidity ads. */
    data class RequestFunding(val requestedAmount: Satoshi, val fundingRate: FundingRate, val paymentDetails: PaymentDetails) {
        fun fees(feerate: FeeratePerKw, isChannelCreation: Boolean): Fees = fundingRate.fees(feerate, requestedAmount, requestedAmount, isChannelCreation)

        fun validateRemoteFunding(
            remoteNodeId: PublicKey,
            channelId: ByteVector32,
            fundingScript: ByteVector,
            remoteFundingAmount: Satoshi,
            fundingFeerate: FeeratePerKw,
            isChannelCreation: Boolean,
            feeCreditUsed: MilliSatoshi,
            willFund: WillFund?
        ): Either<ChannelException, Purchase> {
            return when (willFund) {
                // If the remote peer doesn't want to provide inbound liquidity, we immediately fail the attempt.
                // The user should retry this funding attempt without requesting inbound liquidity.
                null -> Either.Left(MissingLiquidityAds(channelId))
                else -> when {
                    // Note that we use fundingRate instead of willFund.fundingRate: this way we verify that the funding rates match.
                    !Crypto.verifySignature(fundingRate.signedData(fundingScript), willFund.signature, remoteNodeId) -> Either.Left(InvalidLiquidityAdsSig(channelId))
                    remoteFundingAmount < requestedAmount -> Either.Left(InvalidLiquidityAdsAmount(channelId, remoteFundingAmount, requestedAmount))
                    willFund.fundingRate != fundingRate -> Either.Left(InvalidLiquidityAdsRate(channelId))
                    else -> {
                        val purchasedAmount = requestedAmount.min(remoteFundingAmount)
                        val fees = fundingRate.fees(fundingFeerate, requestedAmount, remoteFundingAmount, isChannelCreation)
                        when (feeCreditUsed) {
                            0.msat -> Either.Right(Purchase.Standard(purchasedAmount, fees, paymentDetails))
                            else -> Either.Right(Purchase.WithFeeCredit(purchasedAmount, fees, feeCreditUsed, paymentDetails))
                        }
                    }
                }
            }
        }

        fun write(out: Output) {
            LightningCodecs.writeU64(requestedAmount.toLong(), out)
            fundingRate.write(out)
            paymentDetails.write(out)
        }

        companion object {
            fun chooseRate(requestedAmount: Satoshi, paymentDetails: PaymentDetails, rates: WillFundRates): RequestFunding? = when {
                rates.paymentTypes.contains(paymentDetails.paymentType) -> rates.findRate(requestedAmount)?.let { RequestFunding(requestedAmount, it, paymentDetails) }
                else -> null
            }

            fun read(input: Input): RequestFunding = RequestFunding(
                requestedAmount = LightningCodecs.u64(input).sat,
                fundingRate = FundingRate.read(input),
                paymentDetails = PaymentDetails.read(input),
            )
        }
    }

    fun validateRemoteFunding(
        request: RequestFunding?,
        remoteNodeId: PublicKey,
        channelId: ByteVector32,
        fundingScript: ByteVector,
        remoteFundingAmount: Satoshi,
        fundingFeerate: FeeratePerKw,
        isChannelCreation: Boolean,
        feeCreditUsed: MilliSatoshi,
        willFund: WillFund?,
    ): Either<ChannelException, Purchase?> {
        return when (request) {
            null -> Either.Right(null)
            else -> request.validateRemoteFunding(remoteNodeId, channelId, fundingScript, remoteFundingAmount, fundingFeerate, isChannelCreation, feeCreditUsed, willFund)
        }
    }

    /** Once a liquidity ads has been purchased, we keep track of the fees paid and the payment details. */
    sealed class Purchase {
        abstract val amount: Satoshi
        abstract val fees: Fees
        abstract val paymentDetails: PaymentDetails

        data class Standard(override val amount: Satoshi, override val fees: Fees, override val paymentDetails: PaymentDetails) : Purchase()
        /** The liquidity purchase was paid (partially or entirely) using [fr.acinq.lightning.Feature.FundingFeeCredit]. */
        data class WithFeeCredit(override val amount: Satoshi, override val fees: Fees, val feeCreditUsed: MilliSatoshi, override val paymentDetails: PaymentDetails) : Purchase()
    }

    data class WillFundPurchase(val willFund: WillFund, val purchase: Purchase)

    /**
     * @param txId txId of the transaction including this [purchase].
     * @param miningFee total mining fees paid for this transaction, including the fees from the [purchase].
     */
    data class InboundLiquidityPurchase(val txId: TxId, val miningFee: Satoshi, val purchase: Purchase) {
        /** Total funding fee that must be deduced from HTLCs if [isPaidByFutureHtlcs] is true. */
        val fundingFee: FundingFee = when (purchase.paymentDetails) {
            is PaymentDetails.FromChannelBalance -> FundingFee(0.msat, txId)
            is PaymentDetails.FromChannelBalanceForFutureHtlc -> FundingFee(0.msat, txId)
            is PaymentDetails.FromFutureHtlc -> FundingFee(purchase.fees.total.toMilliSatoshi(), txId)
            is PaymentDetails.FromFutureHtlcWithPreimage -> FundingFee(purchase.fees.total.toMilliSatoshi(), txId)
        }

        /** Returns true if this liquidity was automatically purchased using on-the-fly-funding. */
        val isOnTheFlyFunding = when (purchase.paymentDetails) {
            is PaymentDetails.FromChannelBalance -> false
            is PaymentDetails.FromChannelBalanceForFutureHtlc -> true
            is PaymentDetails.FromFutureHtlc -> true
            is PaymentDetails.FromFutureHtlcWithPreimage -> true
        }

        /**
         * Returns true if the liquidity fees are paid by HTLCs received after the liquidity purchase.
         * Note that even in that case, the mining fee may also be partially paid from the channel balance.
         * The [feePaidFromChannelBalance] and [feePaidFromFutureHtlc] should be used for a detailed summary.
         *
         * Wallets should avoid displaying the corresponding [fr.acinq.lightning.db.LiquidityPurchasePayment]
         * which will be redundant with the [fr.acinq.lightning.db.LightningIncomingPayment].
         */
        val isPaidByFutureHtlcs = when (purchase.paymentDetails) {
            is PaymentDetails.FromChannelBalance -> false
            is PaymentDetails.FromChannelBalanceForFutureHtlc -> false
            is PaymentDetails.FromFutureHtlc -> true
            is PaymentDetails.FromFutureHtlcWithPreimage -> true
        }

        /**
         * Even in the "from future htlc" case, we may partially pay the mining fee from our channel balance if we're splicing
         * an existing channel, because we must pay for the weight of the shared input and output of the transaction. The rest
         * of the mining fee will be refunded to the LSP by being deducted from the HTLCs we will receive, and the corresponding
         * amount will be found in the [purchase].
         *
         * The liquidity purchase will be followed by one or several HTLCs with a non-null [LiquidityAds.FundingFee]
         * where [LiquidityAds.FundingFee.fundingTxId] matches this [InboundLiquidityPurchase.txId].
         * The sum of [LiquidityAds.FundingFee.amount] will match [InboundLiquidityPurchase.feePaidFromFutureHtlc].
         */
        val feePaidFromChannelBalance = when (purchase.paymentDetails) {
            is PaymentDetails.FromChannelBalance -> ChannelManagementFees(miningFee = miningFee, serviceFee = purchase.fees.serviceFee)
            is PaymentDetails.FromChannelBalanceForFutureHtlc -> ChannelManagementFees(miningFee = miningFee, serviceFee = purchase.fees.serviceFee)
            is PaymentDetails.FromFutureHtlc -> ChannelManagementFees(miningFee = miningFee - purchase.fees.miningFee, serviceFee = 0.sat)
            is PaymentDetails.FromFutureHtlcWithPreimage -> ChannelManagementFees(miningFee = miningFee - purchase.fees.miningFee, serviceFee = 0.sat)
        }

        /** Fee deduced from incoming HTLCs. */
        val feePaidFromFutureHtlc = when (purchase.paymentDetails) {
            is PaymentDetails.FromChannelBalance -> ChannelManagementFees(miningFee = 0.sat, serviceFee = 0.sat)
            is PaymentDetails.FromChannelBalanceForFutureHtlc -> ChannelManagementFees(miningFee = 0.sat, serviceFee = 0.sat)
            is PaymentDetails.FromFutureHtlc -> ChannelManagementFees(miningFee = purchase.fees.miningFee, serviceFee = purchase.fees.serviceFee)
            is PaymentDetails.FromFutureHtlcWithPreimage -> ChannelManagementFees(miningFee = purchase.fees.miningFee, serviceFee = purchase.fees.serviceFee)
        }

        /** Fee credit consumed for this purchase (which may or may not fully cover the liquidity fee). */
        val feeCreditUsed = when (purchase) {
            is Purchase.WithFeeCredit -> purchase.feeCreditUsed
            else -> 0.msat
        }
    }

}