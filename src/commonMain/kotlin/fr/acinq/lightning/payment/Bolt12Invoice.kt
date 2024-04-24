package fr.acinq.lightning.payment

import fr.acinq.bitcoin.*
import fr.acinq.bitcoin.utils.Either
import fr.acinq.bitcoin.utils.Try
import fr.acinq.bitcoin.utils.runTrying
import fr.acinq.lightning.Feature
import fr.acinq.lightning.FeatureSupport
import fr.acinq.lightning.Features
import fr.acinq.lightning.MilliSatoshi
import fr.acinq.lightning.utils.currentTimestampSeconds
import fr.acinq.lightning.wire.*
import fr.acinq.lightning.wire.OfferTypes.ContactInfo.BlindedPath
import fr.acinq.lightning.wire.OfferTypes.FallbackAddress
import fr.acinq.lightning.wire.OfferTypes.InvoiceAmount
import fr.acinq.lightning.wire.OfferTypes.InvoiceBlindedPay
import fr.acinq.lightning.wire.OfferTypes.InvoiceCreatedAt
import fr.acinq.lightning.wire.OfferTypes.InvoiceFallbacks
import fr.acinq.lightning.wire.OfferTypes.InvoiceFeatures
import fr.acinq.lightning.wire.OfferTypes.InvoiceNodeId
import fr.acinq.lightning.wire.OfferTypes.InvoicePaths
import fr.acinq.lightning.wire.OfferTypes.InvoicePaymentHash
import fr.acinq.lightning.wire.OfferTypes.InvoiceRelativeExpiry
import fr.acinq.lightning.wire.OfferTypes.InvoiceRequest
import fr.acinq.lightning.wire.OfferTypes.InvoiceTlv
import fr.acinq.lightning.wire.OfferTypes.PaymentInfo
import fr.acinq.lightning.wire.OfferTypes.Signature
import fr.acinq.lightning.wire.OfferTypes.filterInvoiceRequestFields
import fr.acinq.lightning.wire.OfferTypes.removeSignature
import fr.acinq.lightning.wire.OfferTypes.rootHash
import fr.acinq.lightning.wire.OfferTypes.signSchnorr
import fr.acinq.lightning.wire.OfferTypes.verifySchnorr

data class Bolt12Invoice(val records: TlvStream<InvoiceTlv>) : PaymentRequest() {
    val invoiceRequest: InvoiceRequest = InvoiceRequest.validate(filterInvoiceRequestFields(records)).right!!

    override val amount: MilliSatoshi? = records.get<InvoiceAmount>()?.amount
    override val nodeId: PublicKey = records.get<InvoiceNodeId>()!!.nodeId
    override val paymentHash: ByteVector32 = records.get<InvoicePaymentHash>()!!.hash
    val description: String? = invoiceRequest.offer.description
    val createdAtSeconds: Long = records.get<InvoiceCreatedAt>()!!.timestampSeconds
    val relativeExpirySeconds: Long = records.get<InvoiceRelativeExpiry>()?.seconds ?: DEFAULT_EXPIRY_SECONDS

    // We add invoice features that are implicitly required for Bolt 12 (the spec doesn't allow explicitly setting them).
    override val features: Features =
        (records.get<InvoiceFeatures>()?.features?.invoiceFeatures() ?: Features.empty).let {
            it.copy(activated = it.activated + (Feature.VariableLengthOnion to FeatureSupport.Mandatory) + (Feature.RouteBlinding to FeatureSupport.Mandatory))
        }

    val blindedPaths: List<PaymentBlindedContactInfo> = records.get<InvoicePaths>()!!.paths.zip(records.get<InvoiceBlindedPay>()!!.paymentInfos).map { PaymentBlindedContactInfo(it.first, it.second) }
    val fallbacks: List<FallbackAddress>? = records.get<InvoiceFallbacks>()?.addresses
    val signature: ByteVector64 = records.get<Signature>()!!.signature

    override fun isExpired(currentTimestampSeconds: Long): Boolean = createdAtSeconds + relativeExpirySeconds <= currentTimestampSeconds

    // It is assumed that the request is valid for this offer.
    fun validateFor(request: InvoiceRequest): Either<String, Unit> =
        if (invoiceRequest.unsigned() != request.unsigned()) {
            Either.Left("Invoice does not match request")
        } else if (nodeId != invoiceRequest.offer.nodeId) {
            Either.Left("Wrong node id")
        } else if (isExpired()) {
            Either.Left("Invoice expired")
        } else if (request.amount != null && amount != null && request.amount != amount) {
            Either.Left("Incompatible amount")
        } else if (!Features.areCompatible(request.features, features.bolt12Features())) {
            Either.Left("Incompatible features")
        } else if (!checkSignature()) {
            Either.Left("Invalid signature")
        } else {
            Either.Right(Unit)
        }

    fun checkSignature(): Boolean =
        verifySchnorr(signatureTag, rootHash(removeSignature(records)), signature, nodeId)

    override fun toString(): String {
        val data = OfferTypes.Invoice.tlvSerializer.write(records)
        return Bech32.encodeBytes(hrp, data, Bech32.Encoding.Beck32WithoutChecksum)
    }

    override fun write(): String = toString()

    companion object {
        val hrp = "lni"
        val signatureTag: ByteVector = ByteVector(("lightning" + "invoice" + "signature").encodeToByteArray())
        val DEFAULT_EXPIRY_SECONDS: Long = 7200

        data class PaymentBlindedContactInfo(val route: BlindedPath, val paymentInfo: PaymentInfo)

        /**
         * Creates an invoice for a given offer and invoice request.
         *
         * @param request  the request this invoice responds to
         * @param preimage the preimage to use for the payment
         * @param nodeKey  the key that was used to generate the offer, may be different from our public nodeId if we're hiding behind a blinded route
         * @param features invoice features
         * @param paths    the blinded paths to use to pay the invoice
         */
        operator fun invoke(
            request: InvoiceRequest,
            preimage: ByteVector32,
            nodeKey: PrivateKey,
            invoiceExpirySeconds: Long,
            features: Features,
            paths: List<PaymentBlindedContactInfo>,
            additionalTlvs: Set<InvoiceTlv> = setOf(),
            customTlvs: Set<GenericTlv> = setOf()
        ): Bolt12Invoice {
            require(request.amount != null || request.offer.amount != null)
            val amount = request.amount ?: (request.offer.amount!! * request.quantity)
            val tlvs: Set<InvoiceTlv> = removeSignature(request.records).records + setOfNotNull(
                InvoicePaths(paths.map { it.route }),
                InvoiceBlindedPay(paths.map { it.paymentInfo }),
                InvoiceCreatedAt(currentTimestampSeconds()),
                InvoiceRelativeExpiry(invoiceExpirySeconds),
                InvoicePaymentHash(ByteVector32(Crypto.sha256(preimage))),
                InvoiceAmount(amount),
                if (features != Features.empty) InvoiceFeatures(features) else null,
                InvoiceNodeId(nodeKey.publicKey()),
            ) + additionalTlvs
            val signature = signSchnorr(
                signatureTag,
                rootHash(TlvStream(tlvs, request.records.unknown + customTlvs)),
                nodeKey
            )
            return Bolt12Invoice(TlvStream(tlvs + Signature(signature), request.records.unknown + customTlvs))
        }

        fun validate(records: TlvStream<InvoiceTlv>): Either<InvalidTlvPayload, Bolt12Invoice> {
            when (val invoiceRequest = InvoiceRequest.validate(filterInvoiceRequestFields(records))) {
                is Either.Left -> return Either.Left(invoiceRequest.value)
                is Either.Right -> {}
            }
            if (records.get<InvoiceAmount>() == null) return Either.Left(MissingRequiredTlv(170))
            if (records.get<InvoicePaths>()?.paths?.isEmpty() != false) return Either.Left(MissingRequiredTlv(160))
            if (records.get<InvoiceBlindedPay>()?.paymentInfos?.size != records.get<InvoicePaths>()?.paths?.size) return Either.Left(MissingRequiredTlv(162))
            if (records.get<InvoiceNodeId>() == null) return Either.Left(MissingRequiredTlv(176))
            if (records.get<InvoiceCreatedAt>() == null) return Either.Left(MissingRequiredTlv(164))
            if (records.get<InvoicePaymentHash>() == null) return Either.Left(MissingRequiredTlv(168))
            if (records.get<Signature>() == null) return Either.Left(MissingRequiredTlv(240))
            return Either.Right(Bolt12Invoice(records))
        }

        fun fromString(input: String): Try<Bolt12Invoice> = runTrying {
            val (prefix, encoded, encoding) = Bech32.decodeBytes(input.lowercase(), true)
            require(prefix == hrp)
            require(encoding == Bech32.Encoding.Beck32WithoutChecksum)
            val tlvs = OfferTypes.Invoice.tlvSerializer.read(encoded)
            when (val invoice = validate(tlvs)) {
                is Either.Left -> throw IllegalArgumentException(invoice.value.toString())
                is Either.Right -> invoice.value
            }
        }
    }
}
