package fr.acinq.lightning.db.converters

import fr.acinq.lightning.db.types.*

object IncomingPaymentConverter : Converter<fr.acinq.lightning.db.IncomingPayment, IncomingPayment> {
    override fun toCoreType(o: IncomingPayment): fr.acinq.lightning.db.IncomingPayment {
        return when (o) {
            is IncomingLightningPayment -> IncomingLightningPaymentConverter.toCoreType(o)
            is IncomingOnChainPayment -> IncomingOnChainPaymentConverter.toCoreType(o)
            is IncomingLegacyPayToOpenPayment -> IncomingLegacyPayToOpenPaymentConverter.toCoreType(o)
            is IncomingLegacySwapInPayment -> TODO()
        }
    }

    override fun toDbType(o: fr.acinq.lightning.db.IncomingPayment): IncomingPayment {
        @Suppress("DEPRECATION")
        return when (o) {
            is fr.acinq.lightning.db.LightningIncomingPayment -> IncomingLightningPaymentConverter.toDbType(o)
            is fr.acinq.lightning.db.OnChainIncomingPayment -> IncomingOnChainPaymentConverter.toDbType(o)
            is fr.acinq.lightning.db.LegacyPayToOpenIncomingPayment -> IncomingLegacyPayToOpenPaymentConverter.toDbType(o)
            is fr.acinq.lightning.db.LegacySwapInIncomingPayment -> TODO()
        }
    }
}