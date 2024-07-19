package fr.acinq.lightning.payment

import fr.acinq.bitcoin.Crypto
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.bitcoin.PublicKey
import fr.acinq.lightning.NodeParams
import fr.acinq.lightning.wire.OfferTypes
import io.ktor.utils.io.core.*

/**
 * We may want to reveal our identity when paying Bolt 12 offers from our trusted contacts.
 * We also want to be able to identify payments from our trusted contacts if they choose to reveal themselves.
 *
 * @param offer Bolt 12 offer used by that contact.
 * @param payerIds list of payer_id that this contact may use in their [OfferTypes.InvoiceRequest] when they want to reveal their identity.
 */
data class TrustedContact(val offer: OfferTypes.Offer, val payerIds: List<PublicKey>) {
    /**
     * Derive a deterministic payer_key to pay our trusted contact's offer (used in our [OfferTypes.InvoiceRequest]).
     * This payer_key is unique to this contact and only lets them identify us if they know our local offer.
     *
     * @param localOffer local offer that our contact may have stored in their contact list (see [NodeParams.defaultOffer]).
     */
    fun deterministicPayerKey(localOffer: OfferTypes.OfferAndKey): PrivateKey = localOffer.privateKey * deriveTweak(offer)

    /** Return true if this payer_id matches this contact. */
    fun isPayer(payerId: PublicKey): Boolean = payerIds.contains(payerId)

    /** Return true if the [invoiceRequest] comes from this contact. */
    fun isPayer(invoiceRequest: OfferTypes.InvoiceRequest): Boolean = isPayer(invoiceRequest.payerId)

    companion object {
        fun create(localOffer: OfferTypes.OfferAndKey, remoteOffer: OfferTypes.Offer): TrustedContact {
            // We derive the payer_ids that this contact may use when paying our local offer.
            // If they use one of those payer_ids, we'll be able to identify that the payment came from them.
            val payerIds = remoteOffer.contactNodeIds.map { nodeId -> nodeId * deriveTweak(localOffer.offer) }
            return TrustedContact(remoteOffer, payerIds)
        }

        private fun deriveTweak(paidOffer: OfferTypes.Offer): PrivateKey {
            // Note that we use a tagged hash to ensure this tweak only applies to the contact feature.
            return PrivateKey(Crypto.sha256("blip42_bolt12_contacts".toByteArray() + paidOffer.offerId.toByteArray()))
        }
    }
}