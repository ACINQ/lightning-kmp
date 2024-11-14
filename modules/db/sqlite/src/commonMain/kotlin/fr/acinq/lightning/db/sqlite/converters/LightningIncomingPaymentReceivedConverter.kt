package fr.acinq.lightning.db.sqlite.converters

import fr.acinq.lightning.db.types.IncomingLightningPayment

internal object LightningIncomingPaymentReceivedConverter : Converter<fr.acinq.lightning.db.LightningIncomingPayment.Received, IncomingLightningPayment.Received> {

    override fun toCoreType(o: IncomingLightningPayment.Received): fr.acinq.lightning.db.LightningIncomingPayment.Received = when (o) {
        is IncomingLightningPayment.Received.V0 -> fr.acinq.lightning.db.LightningIncomingPayment.Received(
            parts = o.parts.map { LightningIncomingPaymentReceivedPartConverter.toCoreType(it) },
            receivedAt = o.receivedAt
        )
    }

    override fun toDbType(o: fr.acinq.lightning.db.LightningIncomingPayment.Received): IncomingLightningPayment.Received =
        IncomingLightningPayment.Received.V0(
            parts = o.parts.map { LightningIncomingPaymentReceivedPartConverter.toDbType(it) },
            receivedAt = o.receivedAt
        )
}