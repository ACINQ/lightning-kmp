package fr.acinq.lightning.payment

import fr.acinq.bitcoin.*
import fr.acinq.bitcoin.utils.Either
import fr.acinq.bitcoin.utils.flatMap
import fr.acinq.lightning.CltvExpiry
import fr.acinq.lightning.Features
import fr.acinq.lightning.MilliSatoshi
import fr.acinq.lightning.crypto.RouteBlinding
import fr.acinq.lightning.crypto.sphinx.Sphinx
import fr.acinq.lightning.crypto.sphinx.Sphinx.hash
import fr.acinq.lightning.utils.msat
import fr.acinq.lightning.wire.*
import io.ktor.utils.io.core.*

object IncomingPaymentPacket {

    /** Decrypt the onion packet of a received htlc. */
    fun decrypt(add: UpdateAddHtlc, privateKey: PrivateKey): Either<FailureMessage, PaymentOnion.FinalPayload> = decrypt(Either.Right(add), privateKey)

    /** Decrypt the onion packet of a received on-the-fly funding request. */
    fun decrypt(add: WillAddHtlc, privateKey: PrivateKey): Either<FailureMessage, PaymentOnion.FinalPayload> = decrypt(Either.Left(add), privateKey)

    /**
     * Decrypt the onion packet of a received htlc. We expect to be the final recipient, and we validate that the HTLC
     * fields match the onion fields (this prevents intermediate nodes from sending an invalid amount or expiry).
     *
     * @param add incoming htlc.
     * @param privateKey this node's private key.
     * @return either:
     *  - a decrypted and valid onion final payload
     *  - or a Bolt4 failure message that can be returned to the sender if the HTLC is invalid
     */
    fun decrypt(add: Either<WillAddHtlc, UpdateAddHtlc>, privateKey: PrivateKey): Either<FailureMessage, PaymentOnion.FinalPayload> {
        // The previous node may forward a smaller amount than expected to cover liquidity fees.
        // But the amount used for validation should take this funding fee into account.
        // We will verify later in the IncomingPaymentHandler whether the funding fee is valid or not.
        val htlcAmount = add.fold({ it.amount }, { it.amountMsat + (it.fundingFee?.amount ?: 0.msat) })
        val htlcExpiry = add.fold({ it.expiry }, { it.cltvExpiry })
        val paymentHash = add.fold({ it.paymentHash }, { it.paymentHash })
        val blinding = add.fold({ it.blinding }, { it.blinding })
        val onion = add.fold({ it.finalPacket }, { it.onionRoutingPacket })
        return decryptOnion(paymentHash, onion, privateKey, blinding).flatMap { outer ->
            when (outer) {
                is PaymentOnion.FinalPayload.Standard -> {
                    when (val trampolineOnion = outer.records.get<OnionPaymentPayloadTlv.TrampolineOnion>()) {
                        null -> validate(htlcAmount, htlcExpiry, outer)
                        else -> {
                            when (val inner = decryptOnion(paymentHash, trampolineOnion.packet, privateKey, null)) {
                                is Either.Left -> Either.Left(inner.value)
                                is Either.Right -> when (val innerPayload = inner.value) {
                                    is PaymentOnion.FinalPayload.Standard -> validate(htlcAmount, htlcExpiry, outer, innerPayload)
                                    // Blinded trampoline paths are not supported.
                                    is PaymentOnion.FinalPayload.Blinded -> Either.Left(InvalidOnionPayload(0, 0))
                                    is PaymentOnion.FinalPayload.TrampolineBlinded -> Either.Left(InvalidOnionPayload(0, 0))
                                }
                            }
                        }
                    }
                }
                is PaymentOnion.FinalPayload.Blinded -> {
                    when (val trampolineOnion = outer.records.get<OnionPaymentPayloadTlv.TrampolineOnion>()) {
                        null -> validate(htlcAmount, htlcExpiry, onion, outer, innerPayload = null)
                        else -> {
                            val associatedData = when (val metadata = OfferPaymentMetadata.fromPathId(privateKey.publicKey(), outer.pathId)) {
                                null -> paymentHash
                                else -> {
                                    val onionDecryptionKey = blinding?.let { RouteBlinding.derivePrivateKey(privateKey, it) } ?: privateKey
                                    blindedTrampolineAssociatedData(paymentHash, onionDecryptionKey, metadata.payerKey)
                                }
                            }
                            when (val inner = decryptOnion(associatedData, trampolineOnion.packet, privateKey, blinding)) {
                                is Either.Left -> Either.Left(inner.value)
                                is Either.Right -> when (val innerPayload = inner.value) {
                                    is PaymentOnion.FinalPayload.TrampolineBlinded -> validate(htlcAmount, htlcExpiry, onion, outer, innerPayload)
                                    is PaymentOnion.FinalPayload.Blinded -> Either.Left(InvalidOnionPayload(0, 0))
                                    is PaymentOnion.FinalPayload.Standard -> Either.Left(InvalidOnionPayload(0, 0))
                                }
                            }
                        }
                    }
                }
                is PaymentOnion.FinalPayload.TrampolineBlinded -> Either.Left(InvalidOnionPayload(OnionPaymentPayloadTlv.PaymentData.tag, 0))
            }
        }
    }

    private fun decryptOnion(associatedData: ByteVector32, packet: OnionRoutingPacket, privateKey: PrivateKey, blinding: PublicKey?): Either<FailureMessage, PaymentOnion.FinalPayload> {
        val onionDecryptionKey = blinding?.let { RouteBlinding.derivePrivateKey(privateKey, it) } ?: privateKey
        return Sphinx.peel(onionDecryptionKey, associatedData, packet).flatMap { decrypted ->
            when {
                !decrypted.isLastPacket -> Either.Left(UnknownNextPeer)
                else -> PaymentOnion.PerHopPayload.read(decrypted.payload.toByteArray()).flatMap { tlvs ->
                    when (val encryptedRecipientData = tlvs.get<OnionPaymentPayloadTlv.EncryptedRecipientData>()?.data) {
                        null -> when {
                            tlvs.get<OnionPaymentPayloadTlv.BlindingPoint>() != null -> Either.Left(InvalidOnionBlinding(hash(packet)))
                            blinding != null -> PaymentOnion.FinalPayload.TrampolineBlinded.read(decrypted.payload)
                            else -> PaymentOnion.FinalPayload.Standard.read(decrypted.payload)
                        }
                        else -> when {
                            // We're never the introduction node of a blinded path, since we don't have public channels.
                            tlvs.get<OnionPaymentPayloadTlv.BlindingPoint>() != null -> Either.Left(InvalidOnionBlinding(hash(packet)))
                            // We must receive the blinding point to be able to decrypt the blinded path data.
                            blinding == null -> Either.Left(InvalidOnionBlinding(hash(packet)))
                            else -> when (val payload = decryptRecipientData(privateKey, blinding, encryptedRecipientData, tlvs)) {
                                is Either.Left -> Either.Left(InvalidOnionBlinding(hash(packet)))
                                is Either.Right -> Either.Right(payload.value)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun decryptRecipientData(privateKey: PrivateKey, blinding: PublicKey, data: ByteVector, tlvs: TlvStream<OnionPaymentPayloadTlv>): Either<InvalidTlvPayload, PaymentOnion.FinalPayload.Blinded> {
        return RouteBlinding.decryptPayload(privateKey, blinding, data)
            .flatMap { (decryptedRecipientData, _) -> RouteBlindingEncryptedData.read(decryptedRecipientData.toByteArray()) }
            .flatMap { blindedTlvs -> PaymentOnion.FinalPayload.Blinded.validate(tlvs, blindedTlvs) }
    }

    /**
     * When we're using trampoline with Bolt 12, we expect the payer to include a trampoline payload.
     * However, the trampoline node could replace it with a trampoline onion they created.
     * To avoid that, we use a shared secret based on the [OfferTypes.InvoiceRequest] to authenticate the payload.
     */
    private fun blindedTrampolineAssociatedData(paymentHash: ByteVector32, onionDecryptionKey: PrivateKey, payerId: PublicKey): ByteVector32 {
        val invReqSharedSecret = (payerId * onionDecryptionKey).value.toByteArray()
        return Crypto.sha256("blinded_trampoline_payment".toByteArray() + paymentHash.toByteArray() + invReqSharedSecret).byteVector32()
    }

    private fun validate(htlcAmount: MilliSatoshi, htlcExpiry: CltvExpiry, payload: PaymentOnion.FinalPayload.Standard): Either<FailureMessage, PaymentOnion.FinalPayload> {
        return when {
            htlcAmount < payload.amount -> Either.Left(FinalIncorrectHtlcAmount(htlcAmount))
            htlcExpiry < payload.expiry -> Either.Left(FinalIncorrectCltvExpiry(htlcExpiry))
            else -> Either.Right(payload)
        }
    }

    private fun validate(
        htlcAmount: MilliSatoshi,
        htlcExpiry: CltvExpiry,
        onion: OnionRoutingPacket,
        outerPayload: PaymentOnion.FinalPayload.Blinded,
        innerPayload: PaymentOnion.FinalPayload.TrampolineBlinded?
    ): Either<FailureMessage, PaymentOnion.FinalPayload> {
        val minAmount = listOfNotNull(outerPayload.amount, innerPayload?.amount).max()
        val minExpiry = listOfNotNull(outerPayload.expiry, innerPayload?.expiry).max()
        return when {
            outerPayload.recipientData.paymentConstraints?.let { htlcAmount < it.minAmount } == true -> Either.Left(InvalidOnionBlinding(hash(onion)))
            outerPayload.recipientData.paymentConstraints?.let { it.maxCltvExpiry < htlcExpiry } == true -> Either.Left(InvalidOnionBlinding(hash(onion)))
            // We currently don't set the allowed_features field in our invoices.
            !Features.areCompatible(Features.empty, outerPayload.recipientData.allowedFeatures) -> Either.Left(InvalidOnionBlinding(hash(onion)))
            htlcAmount < minAmount -> Either.Left(InvalidOnionBlinding(hash(onion)))
            htlcExpiry < minExpiry -> Either.Left(InvalidOnionBlinding(hash(onion)))
            else -> Either.Right(outerPayload)
        }
    }

    private fun validate(htlcAmount: MilliSatoshi, htlcExpiry: CltvExpiry, outerPayload: PaymentOnion.FinalPayload.Standard, innerPayload: PaymentOnion.FinalPayload.Standard): Either<FailureMessage, PaymentOnion.FinalPayload> {
        return when {
            htlcAmount < outerPayload.amount -> Either.Left(FinalIncorrectHtlcAmount(htlcAmount))
            htlcExpiry < outerPayload.expiry -> Either.Left(FinalIncorrectCltvExpiry(htlcExpiry))
            // previous trampoline didn't forward the right expiry
            outerPayload.expiry != innerPayload.expiry -> Either.Left(FinalIncorrectCltvExpiry(htlcExpiry))
            // previous trampoline didn't forward the right amount
            outerPayload.totalAmount != innerPayload.amount -> Either.Left(FinalIncorrectHtlcAmount(outerPayload.totalAmount))
            else -> {
                // We merge contents from the outer and inner payloads.
                // We must use the inner payload's total amount and payment secret because the payment may be split between multiple trampoline payments (#reckless).
                Either.Right(PaymentOnion.FinalPayload.Standard.createMultiPartPayload(outerPayload.amount, innerPayload.totalAmount, outerPayload.expiry, innerPayload.paymentSecret, innerPayload.paymentMetadata))
            }
        }
    }

}