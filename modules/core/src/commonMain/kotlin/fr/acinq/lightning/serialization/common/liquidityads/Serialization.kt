package fr.acinq.lightning.serialization.common.liquidityads

import fr.acinq.bitcoin.io.Output
import fr.acinq.lightning.serialization.OutputExtensions.writeByteVector32
import fr.acinq.lightning.serialization.OutputExtensions.writeCollection
import fr.acinq.lightning.serialization.OutputExtensions.writeNumber
import fr.acinq.lightning.serialization.OutputExtensions.writeTxId
import fr.acinq.lightning.wire.LiquidityAds

object Serialization {

    private fun Output.writeLiquidityFees(fees: LiquidityAds.Fees) {
        writeNumber(fees.miningFee.toLong())
        writeNumber(fees.serviceFee.toLong())
    }

    private fun Output.writeLiquidityAdsPaymentDetails(paymentDetails: LiquidityAds.PaymentDetails) {
        when (paymentDetails) {
            is LiquidityAds.PaymentDetails.FromChannelBalance -> write(0x00)
            is LiquidityAds.PaymentDetails.FromFutureHtlc -> {
                write(0x80)
                writeCollection(paymentDetails.paymentHashes) { writeByteVector32(it) }
            }
            is LiquidityAds.PaymentDetails.FromFutureHtlcWithPreimage -> {
                write(0x81)
                writeCollection(paymentDetails.preimages) { writeByteVector32(it) }
            }
            is LiquidityAds.PaymentDetails.FromChannelBalanceForFutureHtlc -> {
                write(0x82)
                writeCollection(paymentDetails.paymentHashes) { writeByteVector32(it) }
            }
        }
    }

    fun Output.writeLiquidityPurchase(purchase: LiquidityAds.Purchase) {
        when (purchase) {
            is LiquidityAds.Purchase.Standard -> {
                write(0x00) // discriminator
                writeNumber(purchase.amount.toLong())
                writeLiquidityFees(purchase.fees)
                writeLiquidityAdsPaymentDetails(purchase.paymentDetails)
            }
            is LiquidityAds.Purchase.WithFeeCredit -> {
                write(0x01) // discriminator
                writeNumber(purchase.amount.toLong())
                writeLiquidityFees(purchase.fees)
                writeNumber(purchase.feeCreditUsed.toLong())
                writeLiquidityAdsPaymentDetails(purchase.paymentDetails)
            }
        }
    }

    fun Output.writeLiquidityTransactionDetails(details: LiquidityAds.LiquidityTransactionDetails) {
        writeTxId(details.txId)
        writeNumber(details.miningFee.toLong())
        writeLiquidityPurchase(details.purchase)
    }
}