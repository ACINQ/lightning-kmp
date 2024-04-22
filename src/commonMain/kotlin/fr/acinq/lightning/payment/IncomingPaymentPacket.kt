package fr.acinq.lightning.payment

import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.bitcoin.PublicKey
import fr.acinq.bitcoin.utils.Either
import fr.acinq.bitcoin.utils.flatMap
import fr.acinq.lightning.Features
import fr.acinq.lightning.crypto.RouteBlinding
import fr.acinq.lightning.crypto.sphinx.Sphinx
import fr.acinq.lightning.crypto.sphinx.Sphinx.hash
import fr.acinq.lightning.wire.*

object IncomingPaymentPacket {

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
    fun decrypt(add: UpdateAddHtlc, privateKey: PrivateKey): Either<FailureMessage, PaymentOnion.FinalPayload> {
        return decryptOnion(add.paymentHash, add.onionRoutingPacket, privateKey, add.blinding).flatMap { outer ->
            when (outer) {
                is PaymentOnion.FinalPayload.Standard ->
                    when (val trampolineOnion = outer.records.get<OnionPaymentPayloadTlv.TrampolineOnion>()) {
                        null -> validate(add, outer)
                        else -> {
                            when (val inner = decryptOnion(add.paymentHash, trampolineOnion.packet, privateKey, null)) {
                                is Either.Left -> Either.Left(inner.value)
                                is Either.Right -> when (val innerPayload = inner.value) {
                                    is PaymentOnion.FinalPayload.Standard -> validate(add, outer, innerPayload)
                                    // Blinded trampoline paths are not supported.
                                    is PaymentOnion.FinalPayload.Blinded -> Either.Left(InvalidOnionPayload(0, 0))
                                }
                            }
                        }
                    }
                is PaymentOnion.FinalPayload.Blinded -> validate(add, outer)
            }
        }
    }

    fun decryptOnion(paymentHash: ByteVector32, packet: OnionRoutingPacket, privateKey: PrivateKey, blinding: PublicKey?): Either<FailureMessage, PaymentOnion.FinalPayload> {
        val onionDecryptionKey = blinding?.let { RouteBlinding.derivePrivateKey(privateKey, it) } ?: privateKey
        return Sphinx.peel(onionDecryptionKey, paymentHash, packet).flatMap { decrypted ->
            when {
                !decrypted.isLastPacket -> Either.Left(UnknownNextPeer)
                else -> PaymentOnion.PerHopPayload.read(decrypted.payload.toByteArray()).flatMap { tlvs ->
                    when (val encryptedRecipientData = tlvs.get<OnionPaymentPayloadTlv.EncryptedRecipientData>()?.data) {
                        null -> when {
                            blinding != null -> Either.Left(InvalidOnionBlinding(hash(packet)))
                            tlvs.get<OnionPaymentPayloadTlv.BlindingPoint>() != null -> Either.Left(InvalidOnionBlinding(hash(packet)))
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

    private fun validate(add: UpdateAddHtlc, payload: PaymentOnion.FinalPayload.Standard): Either<FailureMessage, PaymentOnion.FinalPayload> {
        return when {
            add.amountMsat < payload.amount -> Either.Left(FinalIncorrectHtlcAmount(add.amountMsat))
            add.cltvExpiry < payload.expiry -> Either.Left(FinalIncorrectCltvExpiry(add.cltvExpiry))
            else -> Either.Right(payload)
        }
    }

    private fun validate(add: UpdateAddHtlc, payload: PaymentOnion.FinalPayload.Blinded): Either<FailureMessage, PaymentOnion.FinalPayload> {
        return when {
            payload.recipientData.paymentConstraints?.let { add.amountMsat < it.minAmount } == true -> Either.Left(InvalidOnionBlinding(hash(add.onionRoutingPacket)))
            payload.recipientData.paymentConstraints?.let { it.maxCltvExpiry < add.cltvExpiry } == true -> Either.Left(InvalidOnionBlinding(hash(add.onionRoutingPacket)))
            // We currently don't set the allowed_features field in our invoices.
            !Features.areCompatible(Features.empty, payload.recipientData.allowedFeatures) -> Either.Left(InvalidOnionBlinding(hash(add.onionRoutingPacket)))
            add.amountMsat < payload.amount -> Either.Left(InvalidOnionBlinding(hash(add.onionRoutingPacket)))
            add.cltvExpiry < payload.expiry -> Either.Left(InvalidOnionBlinding(hash(add.onionRoutingPacket)))
            else -> Either.Right(payload)
        }
    }

    private fun validate(add: UpdateAddHtlc, outerPayload: PaymentOnion.FinalPayload.Standard, innerPayload: PaymentOnion.FinalPayload.Standard): Either<FailureMessage, PaymentOnion.FinalPayload> {
        return when {
            add.amountMsat < outerPayload.amount -> Either.Left(FinalIncorrectHtlcAmount(add.amountMsat))
            add.cltvExpiry < outerPayload.expiry -> Either.Left(FinalIncorrectCltvExpiry(add.cltvExpiry))
            // previous trampoline didn't forward the right expiry
            outerPayload.expiry != innerPayload.expiry -> Either.Left(FinalIncorrectCltvExpiry(add.cltvExpiry))
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