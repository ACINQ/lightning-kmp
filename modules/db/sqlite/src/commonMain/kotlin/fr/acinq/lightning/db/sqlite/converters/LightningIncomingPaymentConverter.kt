package fr.acinq.lightning.db.sqlite.converters

import fr.acinq.lightning.db.types.IncomingLightningPayment
import fr.acinq.lightning.db.types.OfferPaymentMetadata
import fr.acinq.lightning.payment.Bolt11Invoice

internal object LightningIncomingPaymentConverter : Converter<fr.acinq.lightning.db.LightningIncomingPayment, IncomingLightningPayment> {

    override fun toCoreType(o: IncomingLightningPayment): fr.acinq.lightning.db.LightningIncomingPayment = when (o) {
        is IncomingLightningPayment.IncomingBolt11Payment.V0 -> fr.acinq.lightning.db.Bolt11IncomingPayment(
            preimage = o.preimage,
            paymentRequest = Bolt11Invoice.read(o.paymentRequest).get(),
            received = o.received?.let { LightningIncomingPaymentReceivedConverter.toCoreType(it) },
            createdAt = o.createdAt
        )
        is IncomingLightningPayment.IncomingBolt12Payment.V0 -> fr.acinq.lightning.db.Bolt12IncomingPayment(
            preimage = o.preimage,
            metadata = when (o.metadata) {
                is OfferPaymentMetadata.V1 -> fr.acinq.lightning.payment.OfferPaymentMetadata.V1(
                    offerId = o.metadata.offerId,
                    amount = o.metadata.amount,
                    preimage = o.metadata.preimage,
                    payerKey = o.metadata.payerKey,
                    payerNote = o.metadata.payerNote,
                    quantity = o.metadata.quantity,
                    createdAtMillis = o.metadata.createdAtMillis
                )
            },
            received = o.received?.let { LightningIncomingPaymentReceivedConverter.toCoreType(it) },
            createdAt = o.createdAt
        )
    }

    override fun toDbType(o: fr.acinq.lightning.db.LightningIncomingPayment): IncomingLightningPayment = when (o) {
        is fr.acinq.lightning.db.Bolt11IncomingPayment -> IncomingLightningPayment.IncomingBolt11Payment.V0(
            preimage = o.paymentPreimage,
            paymentRequest = o.paymentRequest.write(),
            received = o.received?.let { LightningIncomingPaymentReceivedConverter.toDbType(it) },
            createdAt = o.createdAt
        )
        is fr.acinq.lightning.db.Bolt12IncomingPayment -> IncomingLightningPayment.IncomingBolt12Payment.V0(
            preimage = o.paymentPreimage,
            metadata = when (val metadata = o.metadata) {
                is fr.acinq.lightning.payment.OfferPaymentMetadata.V1 -> OfferPaymentMetadata.V1(
                    offerId = metadata.offerId,
                    amount = metadata.amount,
                    preimage = metadata.preimage,
                    payerKey = metadata.payerKey,
                    payerNote = metadata.payerNote,
                    quantity = metadata.quantity,
                    createdAtMillis = metadata.createdAtMillis
                )
            },
            received = o.received?.let { LightningIncomingPaymentReceivedConverter.toDbType(it) },
            createdAt = o.createdAt
        )
    }
}