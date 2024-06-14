package fr.acinq.lightning.payment

import fr.acinq.lightning.wire.OfferTypes

/**
 * Failures occurring when fetching an invoice to pay an offer
 */
sealed class Bolt12InvoiceRequestFailure {
    // @formatter:off
    data class NoResponse(val request: OfferTypes.InvoiceRequest) : Bolt12InvoiceRequestFailure() { override fun toString(): String = "no response to the invoice request" }
    data class MalformedResponse(val request: OfferTypes.InvoiceRequest, val failure: Bolt12Invoice.Companion.Bolt12ParsingResult.Failure.Malformed) : Bolt12InvoiceRequestFailure() { override fun toString(): String = "recipient returned an invalid response to the invoice request" }
    data class ErrorFromRecipient(val request: OfferTypes.InvoiceRequest, val failure: Bolt12Invoice.Companion.Bolt12ParsingResult.Failure.RecipientError) : Bolt12InvoiceRequestFailure() { override fun toString(): String = "recipient responded to the invoice request with an error" }
    data class InvoiceMismatch(val request: OfferTypes.InvoiceRequest, val reason: String) : Bolt12InvoiceRequestFailure() { override fun toString(): String = "recipient returned an invoice that does not match the request" }
    // @formatter:on
}