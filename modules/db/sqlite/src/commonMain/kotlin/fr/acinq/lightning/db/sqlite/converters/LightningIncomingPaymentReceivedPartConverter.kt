package fr.acinq.lightning.db.sqlite.converters

import fr.acinq.lightning.db.types.IncomingLightningPayment
import fr.acinq.lightning.db.types.LiquidityAds

internal object LightningIncomingPaymentReceivedPartConverter : Converter<fr.acinq.lightning.db.LightningIncomingPayment.Received.Part, IncomingLightningPayment.Received.Part> {

    override fun toCoreType(o: IncomingLightningPayment.Received.Part): fr.acinq.lightning.db.LightningIncomingPayment.Received.Part = when (o) {
        is IncomingLightningPayment.Received.Part.Htlc.V0 -> fr.acinq.lightning.db.LightningIncomingPayment.Received.Part.Htlc(
            amountReceived = o.amountReceived,
            channelId = o.channelId,
            htlcId = o.htlcId,
            fundingFee = when (o.fundingFee) {
                null -> null
                is LiquidityAds.FundingFee.V0 -> fr.acinq.lightning.wire.LiquidityAds.FundingFee(o.fundingFee.amount, o.fundingFee.fundingTxId)
            }
        )
        is IncomingLightningPayment.Received.Part.FeeCredit.V0 -> fr.acinq.lightning.db.LightningIncomingPayment.Received.Part.FeeCredit(
            amountReceived = o.amountReceived
        )
    }

    override fun toDbType(o: fr.acinq.lightning.db.LightningIncomingPayment.Received.Part): IncomingLightningPayment.Received.Part = when (o) {
        is fr.acinq.lightning.db.LightningIncomingPayment.Received.Part.Htlc -> IncomingLightningPayment.Received.Part.Htlc.V0(
            amountReceived = o.amountReceived,
            channelId = o.channelId,
            htlcId = o.htlcId,
            fundingFee = o.fundingFee?.let { LiquidityAds.FundingFee.V0(it.amount, it.fundingTxId) }
        )
        is fr.acinq.lightning.db.LightningIncomingPayment.Received.Part.FeeCredit -> IncomingLightningPayment.Received.Part.FeeCredit.V0(
            amountReceived = o.amountReceived
        )
    }
}