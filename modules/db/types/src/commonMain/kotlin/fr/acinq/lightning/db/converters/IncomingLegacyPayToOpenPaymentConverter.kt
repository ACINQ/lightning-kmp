package fr.acinq.lightning.db.converters

import fr.acinq.lightning.db.types.IncomingLegacyPayToOpenPayment

@Suppress("DEPRECATION")
internal object IncomingLegacyPayToOpenPaymentConverter : Converter<fr.acinq.lightning.db.LegacyPayToOpenIncomingPayment, IncomingLegacyPayToOpenPayment> {

    override fun toCoreType(o: IncomingLegacyPayToOpenPayment): fr.acinq.lightning.db.LegacyPayToOpenIncomingPayment = when (o) {
        is IncomingLegacyPayToOpenPayment.V0 -> fr.acinq.lightning.db.LegacyPayToOpenIncomingPayment(
            paymentPreimage = o.paymentPreimage,
            origin = when (val origin = o.origin) {
                is IncomingLegacyPayToOpenPayment.V0.Origin.Invoice -> fr.acinq.lightning.db.LegacyPayToOpenIncomingPayment.Origin.Invoice(origin.paymentRequest)
                is IncomingLegacyPayToOpenPayment.V0.Origin.Offer -> fr.acinq.lightning.db.LegacyPayToOpenIncomingPayment.Origin.Offer(origin.metadata)
            },
            parts = o.parts.map { part ->
                when (part) {
                    is IncomingLegacyPayToOpenPayment.V0.Part.Lightning -> fr.acinq.lightning.db.LegacyPayToOpenIncomingPayment.Part.Lightning(
                        amountReceived = part.amountReceived,
                        channelId = part.channelId,
                        htlcId = part.htlcId
                    )
                    is IncomingLegacyPayToOpenPayment.V0.Part.OnChain -> fr.acinq.lightning.db.LegacyPayToOpenIncomingPayment.Part.OnChain(
                        amountReceived = part.amountReceived,
                        serviceFee = part.serviceFee,
                        miningFee = part.miningFee,
                        channelId = part.channelId,
                        txId = part.txId,
                        confirmedAt = part.confirmedAt,
                        lockedAt = part.lockedAt
                    )
                }
            },
            createdAt = o.createdAt,
            completedAt = o.completedAt
        )
    }

    override fun toDbType(o: fr.acinq.lightning.db.LegacyPayToOpenIncomingPayment): IncomingLegacyPayToOpenPayment =
        IncomingLegacyPayToOpenPayment.V0(
            paymentPreimage = o.paymentPreimage,
            origin = when (val origin = o.origin) {
                is fr.acinq.lightning.db.LegacyPayToOpenIncomingPayment.Origin.Invoice -> IncomingLegacyPayToOpenPayment.V0.Origin.Invoice(origin.paymentRequest)
                is fr.acinq.lightning.db.LegacyPayToOpenIncomingPayment.Origin.Offer -> IncomingLegacyPayToOpenPayment.V0.Origin.Offer(origin.metadata)
            },
            parts = o.parts.map { part ->
                when (part) {
                    is fr.acinq.lightning.db.LegacyPayToOpenIncomingPayment.Part.Lightning -> IncomingLegacyPayToOpenPayment.V0.Part.Lightning(
                        amountReceived = part.amountReceived,
                        channelId = part.channelId,
                        htlcId = part.htlcId
                    )
                    is fr.acinq.lightning.db.LegacyPayToOpenIncomingPayment.Part.OnChain -> IncomingLegacyPayToOpenPayment.V0.Part.OnChain(
                        amountReceived = part.amountReceived,
                        serviceFee = part.serviceFee,
                        miningFee = part.miningFee,
                        channelId = part.channelId,
                        txId = part.txId,
                        confirmedAt = part.confirmedAt,
                        lockedAt = part.lockedAt
                    )
                }
            },
            createdAt = o.createdAt,
            completedAt = o.completedAt
        )
}