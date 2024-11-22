package fr.acinq.lightning.db.converters

import fr.acinq.lightning.db.LightningIncomingPayment
import fr.acinq.lightning.db.types.IncomingLightningPayment
import fr.acinq.lightning.payment.Bolt11Invoice

internal object IncomingLightningPaymentConverter : Converter<LightningIncomingPayment, IncomingLightningPayment> {

    override fun toCoreType(o: IncomingLightningPayment): fr.acinq.lightning.db.LightningIncomingPayment = when (o) {
        is IncomingLightningPayment.IncomingBolt11Payment.V0 -> fr.acinq.lightning.db.Bolt11IncomingPayment(
            preimage = o.preimage,
            paymentRequest = Bolt11Invoice.read(o.paymentRequest).get(),
            received = o.received?.let { IncomingLightningPaymentReceivedConverter.toCoreType(it) },
            createdAt = o.createdAt
        )
        is IncomingLightningPayment.IncomingBolt12Payment.V0 -> fr.acinq.lightning.db.Bolt12IncomingPayment(
            preimage = o.preimage,
            metadata = fr.acinq.lightning.payment.OfferPaymentMetadata.decode(o.encodedMetadata),
            received = o.received?.let { IncomingLightningPaymentReceivedConverter.toCoreType(it) },
            createdAt = o.createdAt
        )
    }

    override fun toDbType(o: fr.acinq.lightning.db.LightningIncomingPayment): IncomingLightningPayment = when (o) {
        is fr.acinq.lightning.db.Bolt11IncomingPayment -> IncomingLightningPayment.IncomingBolt11Payment.V0(
            preimage = o.paymentPreimage,
            paymentRequest = o.paymentRequest.write(),
            received = o.received?.let { IncomingLightningPaymentReceivedConverter.toDbType(it) },
            createdAt = o.createdAt
        )
        is fr.acinq.lightning.db.Bolt12IncomingPayment -> IncomingLightningPayment.IncomingBolt12Payment.V0(
            preimage = o.paymentPreimage,
            encodedMetadata = o.metadata.encode(),
            received = o.received?.let { IncomingLightningPaymentReceivedConverter.toDbType(it) },
            createdAt = o.createdAt
        )
    }
}