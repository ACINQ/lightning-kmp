package fr.acinq.lightning.db.converters

import fr.acinq.lightning.db.InboundLiquidityOutgoingPayment
import fr.acinq.lightning.db.converters.Converter
import fr.acinq.lightning.db.types.OutgoingLiquidityPayment
import fr.acinq.lightning.db.types.LiquidityAds.Purchase.Companion.toCoreType
import fr.acinq.lightning.db.types.LiquidityAds.Purchase.Companion.toDbType

internal object InboundLiquidityOutgoingPaymentConverter : Converter<InboundLiquidityOutgoingPayment, OutgoingLiquidityPayment> {

    override fun toCoreType(o: OutgoingLiquidityPayment): fr.acinq.lightning.db.InboundLiquidityOutgoingPayment = when (o) {
        is OutgoingLiquidityPayment.V0 -> fr.acinq.lightning.db.InboundLiquidityOutgoingPayment(
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

    override fun toDbType(o: fr.acinq.lightning.db.InboundLiquidityOutgoingPayment): OutgoingLiquidityPayment =
        OutgoingLiquidityPayment.V0(
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