package fr.acinq.lightning.payment

import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.lightning.crypto.sphinx.Sphinx
import fr.acinq.lightning.utils.Either
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
        return when (val decrypted = decryptOnion(add.paymentHash, add.onionRoutingPacket, OnionRoutingPacket.PaymentPacketLength, privateKey)) {
            is Either.Left -> Either.Left(decrypted.value)
            is Either.Right -> {
                val outer = decrypted.value
                when (val trampolineOnion = outer.records.get<OnionPaymentPayloadTlv.TrampolineOnion>()) {
                    null -> validate(add, outer)
                    else -> {
                        when (val inner = decryptOnion(add.paymentHash, trampolineOnion.packet, OnionRoutingPacket.TrampolinePacketLength, privateKey)) {
                            is Either.Left -> Either.Left(inner.value)
                            is Either.Right -> validate(add, outer, inner.value)
                        }
                    }
                }
            }
        }
    }

    fun decryptOnion(paymentHash: ByteVector32, packet: OnionRoutingPacket, packetLength: Int, privateKey: PrivateKey): Either<FailureMessage, PaymentOnion.FinalPayload> {
        return when (val decrypted = Sphinx.peel(privateKey, paymentHash, packet, packetLength)) {
            is Either.Left -> Either.Left(decrypted.value)
            is Either.Right -> run {
                if (!decrypted.value.isLastPacket) {
                    Either.Left(UnknownNextPeer)
                } else {
                    try {
                        Either.Right(PaymentOnion.FinalPayload.read(decrypted.value.payload.toByteArray()))
                    } catch (_: Throwable) {
                        Either.Left(InvalidOnionPayload(0U, 0))
                    }
                }
            }
        }
    }

    private fun validate(add: UpdateAddHtlc, payload: PaymentOnion.FinalPayload): Either<FailureMessage, PaymentOnion.FinalPayload> {
        return when {
            add.amountMsat < payload.amount -> Either.Left(FinalIncorrectHtlcAmount(add.amountMsat))
            add.cltvExpiry < payload.expiry -> Either.Left(FinalIncorrectCltvExpiry(add.cltvExpiry))
            else -> Either.Right(payload)
        }
    }

    private fun validate(add: UpdateAddHtlc, outerPayload: PaymentOnion.FinalPayload, innerPayload: PaymentOnion.FinalPayload): Either<FailureMessage, PaymentOnion.FinalPayload> {
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
                Either.Right(PaymentOnion.FinalPayload.createMultiPartPayload(outerPayload.amount, innerPayload.totalAmount, outerPayload.expiry, innerPayload.paymentSecret, innerPayload.paymentMetadata))
            }
        }
    }

}