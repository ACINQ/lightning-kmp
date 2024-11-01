package fr.acinq.lightning.db.sqlite.converters

import fr.acinq.lightning.db.types.InboundLiquidityOutgoingPayment
import fr.acinq.lightning.db.types.LiquidityAds.Purchase.Companion.toCoreType
import fr.acinq.lightning.db.types.LiquidityAds.Purchase.Companion.toDbType

internal class InboundLiquidityOutgoingPaymentConverter : Converter<fr.acinq.lightning.db.InboundLiquidityOutgoingPayment, InboundLiquidityOutgoingPayment> {

    override fun toCoreType(o: InboundLiquidityOutgoingPayment): fr.acinq.lightning.db.InboundLiquidityOutgoingPayment = when (o) {
        is InboundLiquidityOutgoingPayment.V0 -> fr.acinq.lightning.db.InboundLiquidityOutgoingPayment(
            id = o.id,
            channelId = o.channelId,
            txId = o.txId,
            localMiningFees = o.localMiningFees,
            purchase = o.purchase.toCoreType(),
            createdAt = o.createdAt,
            confirmedAt = o.confirmedAt,
            lockedAt = o.lockedAt,
        )
    }

    override fun toDbType(o: fr.acinq.lightning.db.InboundLiquidityOutgoingPayment): InboundLiquidityOutgoingPayment =
        InboundLiquidityOutgoingPayment.V0(
            id = o.id,
            channelId = o.channelId,
            txId = o.txId,
            localMiningFees = o.localMiningFees,
            purchase = o.purchase.toDbType(),
            createdAt = o.createdAt,
            confirmedAt = o.confirmedAt,
            lockedAt = o.lockedAt,
        )
}