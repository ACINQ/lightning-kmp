package fr.acinq.lightning.serialization.common.liquidityads

import fr.acinq.bitcoin.io.Input
import fr.acinq.lightning.serialization.InputExtensions.readByteVector32
import fr.acinq.lightning.serialization.InputExtensions.readCollection
import fr.acinq.lightning.serialization.InputExtensions.readNumber
import fr.acinq.lightning.serialization.InputExtensions.readTxId
import fr.acinq.lightning.utils.msat
import fr.acinq.lightning.utils.sat
import fr.acinq.lightning.wire.LiquidityAds

object Deserialization {

    private fun Input.readLiquidityFees(): LiquidityAds.Fees = LiquidityAds.Fees(miningFee = readNumber().sat, serviceFee = readNumber().sat)

    private fun Input.readLiquidityAdsPaymentDetails(): LiquidityAds.PaymentDetails = when (val discriminator = read()) {
        0x00 -> LiquidityAds.PaymentDetails.FromChannelBalance
        0x80 -> LiquidityAds.PaymentDetails.FromFutureHtlc(readCollection { readByteVector32() }.toList())
        0x81 -> LiquidityAds.PaymentDetails.FromFutureHtlcWithPreimage(readCollection { readByteVector32() }.toList())
        0x82 -> LiquidityAds.PaymentDetails.FromChannelBalanceForFutureHtlc(readCollection { readByteVector32() }.toList())
        else -> error("unknown discriminator $discriminator for class ${LiquidityAds.PaymentDetails::class}")
    }

    fun Input.readLiquidityPurchase(): LiquidityAds.Purchase = when (val discriminator = read()) {
        0x00 -> LiquidityAds.Purchase.Standard(
            amount = readNumber().sat,
            fees = readLiquidityFees(),
            paymentDetails = readLiquidityAdsPaymentDetails()
        )
        0x01 -> LiquidityAds.Purchase.WithFeeCredit(
            amount = readNumber().sat,
            fees = readLiquidityFees(),
            feeCreditUsed = readNumber().msat,
            paymentDetails = readLiquidityAdsPaymentDetails()
        )
        else -> error("unknown discriminator $discriminator for class ${LiquidityAds.Purchase::class}")
    }

    fun Input.readInboundLiquidityPurchase(): LiquidityAds.InboundLiquidityPurchase = LiquidityAds.InboundLiquidityPurchase(
        txId = readTxId(),
        miningFee = readNumber().sat,
        purchase = readLiquidityPurchase(),
    )
}