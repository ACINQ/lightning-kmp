package fr.acinq.lightning.payment

import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.bitcoin.PublicKey
import fr.acinq.bitcoin.utils.Either
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
        return when (val decrypted = decryptOnion(add.paymentHash, add.onionRoutingPacket, privateKey, add.blinding)) {
            is Either.Left -> Either.Left(decrypted.value)
            is Either.Right -> when (val outer = decrypted.value) {
                is PaymentOnion.FinalPayload.Standard ->
                    when (val trampolineOnion = outer.records.get<OnionPaymentPayloadTlv.TrampolineOnion>()) {
                        null -> validate(add, outer)
                        else -> {
                            when (val inner = decryptOnion(add.paymentHash, trampolineOnion.packet, privateKey, null)) {
                                is Either.Left -> Either.Left(inner.value)
                                is Either.Right -> when (val innerPayload = inner.value) {
                                    is PaymentOnion.FinalPayload.Standard -> validate(add, outer, innerPayload)
                                    // Blinded trampoline paths are not supported.
                                    is PaymentOnion.FinalPayload.Blinded -> Either.Left(InvalidOnionPayload(0U, 0))
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
        when (val decrypted = Sphinx.peel(onionDecryptionKey, paymentHash, packet)) {
            is Either.Left -> return Either.Left(decrypted.value)
            is Either.Right -> {
                if (!decrypted.value.isLastPacket) {
                    return Either.Left(UnknownNextPeer)
                } else {
                    val tlvs = try {
                        PaymentOnion.PerHopPayload.tlvSerializer.read(decrypted.value.payload.toByteArray())
                    } catch (_: Throwable) {
                        return Either.Left(InvalidOnionPayload(0U, 0))
                    }
                    val encryptedRecipientData = tlvs.get<OnionPaymentPayloadTlv.EncryptedRecipientData>()?.data
                    val innerBlinding = tlvs.get<OnionPaymentPayloadTlv.BlindingPoint>()?.publicKey
                    return if (encryptedRecipientData == null) {
                        if (blinding != null || innerBlinding != null) {
                            Either.Left(InvalidOnionBlinding(hash(packet)))
                        } else {
                            try {
                                Either.Right(PaymentOnion.FinalPayload.Standard(tlvs))
                            } catch (_: Throwable) {
                                Either.Left(InvalidOnionPayload(0U, 0))
                            }
                        }
                    } else {
                        if ((blinding == null) == (innerBlinding == null)) { // exactly one of them must be non-null
                            Either.Left(InvalidOnionBlinding(hash(packet)))
                        } else {
                            try {
                                val (decryptedRecipientData, _) = RouteBlinding.decryptPayload(privateKey, blinding ?: innerBlinding!!, encryptedRecipientData)
                                val recipientData = RouteBlindingEncryptedData.tlvSerializer.read(decryptedRecipientData.toByteArray())
                                Either.Right(PaymentOnion.FinalPayload.Blinded(tlvs, recipientData))
                            } catch (_: Throwable) {
                                Either.Left(InvalidOnionBlinding(hash(packet)))
                            }
                        }
                    }
                }
            }
        }
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