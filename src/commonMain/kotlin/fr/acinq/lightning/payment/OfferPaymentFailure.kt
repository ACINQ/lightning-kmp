package fr.acinq.lightning.payment

import fr.acinq.lightning.wire.OfferTypes

sealed class OfferPaymentFailure {
    data object NoResponse : OfferPaymentFailure()
    data class InvalidResponse(val request: OfferTypes.InvoiceRequest) : OfferPaymentFailure()
    data class InvoiceError(val request: OfferTypes.InvoiceRequest, val error: OfferTypes.InvoiceError) : OfferPaymentFailure()
    data class InvalidInvoice(val request: OfferTypes.InvoiceRequest, val invoice: Bolt12Invoice) : OfferPaymentFailure()
}