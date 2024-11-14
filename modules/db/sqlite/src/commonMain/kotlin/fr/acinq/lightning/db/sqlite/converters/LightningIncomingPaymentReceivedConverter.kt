package fr.acinq.lightning.db.sqlite.converters

import fr.acinq.lightning.db.types.LightningIncomingPayment

internal object LightningIncomingPaymentReceivedConverter : Converter<fr.acinq.lightning.db.LightningIncomingPayment.Received, LightningIncomingPayment.Received> {

    override fun toCoreType(o: LightningIncomingPayment.Received): fr.acinq.lightning.db.LightningIncomingPayment.Received = when (o) {
        is LightningIncomingPayment.Received.V0 -> fr.acinq.lightning.db.LightningIncomingPayment.Received(
            parts = o.parts.map { LightningIncomingPaymentReceivedPartConverter.toCoreType(it) },
            receivedAt = o.receivedAt
        )
    }

    override fun toDbType(o: fr.acinq.lightning.db.LightningIncomingPayment.Received): LightningIncomingPayment.Received =
        LightningIncomingPayment.Received.V0(
            parts = o.parts.map { LightningIncomingPaymentReceivedPartConverter.toDbType(it) },
            receivedAt = o.receivedAt
        )
}