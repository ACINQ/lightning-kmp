package fr.acinq.lightning.wire

import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.PublicKey
import fr.acinq.bitcoin.io.ByteArrayInput
import fr.acinq.bitcoin.io.ByteArrayOutput
import fr.acinq.bitcoin.io.Input
import fr.acinq.bitcoin.io.Output
import fr.acinq.lightning.NodeId
import fr.acinq.lightning.crypto.RouteBlinding

sealed interface OnionMessagePayloadTlv : Tlv {
    /**
     * Onion messages may provide a reply path, allowing the recipient to send a message back to the original sender.
     * The reply path uses route blinding, which ensures that the sender doesn't leak its identity to the recipient.
     */
    data class ReplyPath(val blindedRoute: RouteBlinding.BlindedRoute) : OnionMessagePayloadTlv {
        override val tag: Long get() = ReplyPath.tag
        override fun write(out: Output) {
            blindedRoute.introductionNodeId.write(out)
            LightningCodecs.writeBytes(blindedRoute.blindingKey.value, out)
            LightningCodecs.writeByte(blindedRoute.blindedNodes.size, out)
            for (hop in blindedRoute.blindedNodes) {
                LightningCodecs.writeBytes(hop.blindedPublicKey.value, out)
                LightningCodecs.writeU16(hop.encryptedPayload.size(), out)
                LightningCodecs.writeBytes(hop.encryptedPayload, out)
            }
        }

        companion object : TlvValueReader<ReplyPath> {
            const val tag: Long = 2
            override fun read(input: Input): ReplyPath {
                val firstNodeId = NodeId.read(input)
                val blinding = PublicKey(LightningCodecs.bytes(input, 33))
                val numHops = LightningCodecs.byte(input)
                val path = (0 until numHops).map {
                    val blindedPublicKey = PublicKey(LightningCodecs.bytes(input, 33))
                    val encryptedPayload = ByteVector(LightningCodecs.bytes(input, LightningCodecs.u16(input)))
                    RouteBlinding.BlindedNode(blindedPublicKey, encryptedPayload)
                }
                return ReplyPath(RouteBlinding.BlindedRoute(firstNodeId, blinding, path))
            }
        }
    }

    /**
     * Onion messages always use route blinding, even in the forward direction.
     * This ensures that intermediate nodes can't know whether they're forwarding a message or its reply.
     * The sender must provide some encrypted data for each intermediate node which lets them locate the next node.
     */
    data class EncryptedData(val data: ByteVector) : OnionMessagePayloadTlv {
        override val tag: Long get() = EncryptedData.tag
        override fun write(out: Output) = LightningCodecs.writeBytes(data, out)

        companion object : TlvValueReader<EncryptedData> {
            const val tag: Long = 4
            override fun read(input: Input): EncryptedData =
                EncryptedData(ByteVector(LightningCodecs.bytes(input, input.availableBytes)))
        }
    }

    /**
     * In order to pay a Bolt 12 offer, we must send an onion message to request an invoice corresponding to that offer.
     * The creator of the offer will send us an invoice back through our blinded reply path.
     */
    data class InvoiceRequest(val tlvs: TlvStream<OfferTypes.InvoiceRequestTlv>) : OnionMessagePayloadTlv {
        override val tag: Long get() = InvoiceRequest.tag
        override fun write(out: Output) = OfferTypes.InvoiceRequest.tlvSerializer.write(tlvs, out)

        companion object : TlvValueReader<InvoiceRequest> {
            const val tag: Long = 64

            override fun read(input: Input): InvoiceRequest =
                InvoiceRequest(OfferTypes.InvoiceRequest.tlvSerializer.read(input))
        }
    }

    /**
     * When receiving an invoice request, we must send an onion message back containing an invoice corresponding to the
     * requested offer (if it was an offer we published).
     */
    data class Invoice(val tlvs: TlvStream<OfferTypes.InvoiceTlv>) : OnionMessagePayloadTlv {
        override val tag: Long get() = Invoice.tag
        override fun write(out: Output) = tlvSerializer.write(tlvs, out)

        companion object : TlvValueReader<Invoice> {
            const val tag: Long = 66

            val tlvSerializer = TlvStreamSerializer(
                true, @Suppress("UNCHECKED_CAST") mapOf(
                    // Invoice request part that must be copy-pasted from above
                    OfferTypes.InvoiceRequestMetadata.tag to OfferTypes.InvoiceRequestMetadata.Companion as TlvValueReader<OfferTypes.InvoiceTlv>,
                    OfferTypes.OfferChains.tag to OfferTypes.OfferChains.Companion as TlvValueReader<OfferTypes.InvoiceTlv>,
                    OfferTypes.OfferMetadata.tag to OfferTypes.OfferMetadata.Companion as TlvValueReader<OfferTypes.InvoiceTlv>,
                    OfferTypes.OfferCurrency.tag to OfferTypes.OfferCurrency.Companion as TlvValueReader<OfferTypes.InvoiceTlv>,
                    OfferTypes.OfferAmount.tag to OfferTypes.OfferAmount.Companion as TlvValueReader<OfferTypes.InvoiceTlv>,
                    OfferTypes.OfferDescription.tag to OfferTypes.OfferDescription.Companion as TlvValueReader<OfferTypes.InvoiceTlv>,
                    OfferTypes.OfferFeatures.tag to OfferTypes.OfferFeatures.Companion as TlvValueReader<OfferTypes.InvoiceTlv>,
                    OfferTypes.OfferAbsoluteExpiry.tag to OfferTypes.OfferAbsoluteExpiry.Companion as TlvValueReader<OfferTypes.InvoiceTlv>,
                    OfferTypes.OfferPaths.tag to OfferTypes.OfferPaths.Companion as TlvValueReader<OfferTypes.InvoiceTlv>,
                    OfferTypes.OfferIssuer.tag to OfferTypes.OfferIssuer.Companion as TlvValueReader<OfferTypes.InvoiceTlv>,
                    OfferTypes.OfferQuantityMax.tag to OfferTypes.OfferQuantityMax.Companion as TlvValueReader<OfferTypes.InvoiceTlv>,
                    OfferTypes.OfferNodeId.tag to OfferTypes.OfferNodeId.Companion as TlvValueReader<OfferTypes.InvoiceTlv>,
                    OfferTypes.InvoiceRequestChain.tag to OfferTypes.InvoiceRequestChain.Companion as TlvValueReader<OfferTypes.InvoiceTlv>,
                    OfferTypes.InvoiceRequestAmount.tag to OfferTypes.InvoiceRequestAmount.Companion as TlvValueReader<OfferTypes.InvoiceTlv>,
                    OfferTypes.InvoiceRequestFeatures.tag to OfferTypes.InvoiceRequestFeatures.Companion as TlvValueReader<OfferTypes.InvoiceTlv>,
                    OfferTypes.InvoiceRequestQuantity.tag to OfferTypes.InvoiceRequestQuantity.Companion as TlvValueReader<OfferTypes.InvoiceTlv>,
                    OfferTypes.InvoiceRequestPayerId.tag to OfferTypes.InvoiceRequestPayerId.Companion as TlvValueReader<OfferTypes.InvoiceTlv>,
                    OfferTypes.InvoiceRequestPayerNote.tag to OfferTypes.InvoiceRequestPayerNote.Companion as TlvValueReader<OfferTypes.InvoiceTlv>,
                    // Invoice part
                    OfferTypes.InvoicePaths.tag to OfferTypes.InvoicePaths.Companion as TlvValueReader<OfferTypes.InvoiceTlv>,
                    OfferTypes.InvoiceBlindedPay.tag to OfferTypes.InvoiceBlindedPay.Companion as TlvValueReader<OfferTypes.InvoiceTlv>,
                    OfferTypes.InvoiceCreatedAt.tag to OfferTypes.InvoiceCreatedAt.Companion as TlvValueReader<OfferTypes.InvoiceTlv>,
                    OfferTypes.InvoiceRelativeExpiry.tag to OfferTypes.InvoiceRelativeExpiry.Companion as TlvValueReader<OfferTypes.InvoiceTlv>,
                    OfferTypes.InvoicePaymentHash.tag to OfferTypes.InvoicePaymentHash.Companion as TlvValueReader<OfferTypes.InvoiceTlv>,
                    OfferTypes.InvoiceAmount.tag to OfferTypes.InvoiceAmount.Companion as TlvValueReader<OfferTypes.InvoiceTlv>,
                    OfferTypes.InvoiceFallbacks.tag to OfferTypes.InvoiceFallbacks.Companion as TlvValueReader<OfferTypes.InvoiceTlv>,
                    OfferTypes.InvoiceFeatures.tag to OfferTypes.InvoiceFeatures.Companion as TlvValueReader<OfferTypes.InvoiceTlv>,
                    OfferTypes.InvoiceNodeId.tag to OfferTypes.InvoiceNodeId.Companion as TlvValueReader<OfferTypes.InvoiceTlv>,
                    OfferTypes.Signature.tag to OfferTypes.Signature.Companion as TlvValueReader<OfferTypes.InvoiceTlv>,
                )
            )

            override fun read(input: Input): Invoice =
                Invoice(tlvSerializer.read(input))
        }
    }

    /**
     * This message may be used when we receive an invalid invoice or invoice request.
     * It contains information helping senders figure out why their message was invalid.
     */
    data class InvoiceError(val tlvs: TlvStream<OfferTypes.InvoiceErrorTlv>) : OnionMessagePayloadTlv {
        override val tag: Long get() = InvoiceError.tag
        override fun write(out: Output) = tlvSerializer.write(tlvs, out)

        companion object : TlvValueReader<InvoiceError> {
            const val tag: Long = 68

            val tlvSerializer = TlvStreamSerializer(
                true, @Suppress("UNCHECKED_CAST") mapOf(
                    OfferTypes.ErroneousField.tag to OfferTypes.ErroneousField.Companion as TlvValueReader<OfferTypes.InvoiceErrorTlv>,
                    OfferTypes.SuggestedValue.tag to OfferTypes.SuggestedValue.Companion as TlvValueReader<OfferTypes.InvoiceErrorTlv>,
                    OfferTypes.Error.tag to OfferTypes.Error.Companion as TlvValueReader<OfferTypes.InvoiceErrorTlv>,
                )
            )

            override fun read(input: Input): InvoiceError =
                InvoiceError(tlvSerializer.read(input))
        }
    }
}

data class MessageOnion(val records: TlvStream<OnionMessagePayloadTlv>) {
    val replyPath = records.get<OnionMessagePayloadTlv.ReplyPath>()?.blindedRoute
    val encryptedData = records.get<OnionMessagePayloadTlv.EncryptedData>()!!.data

    fun write(out: Output) = tlvSerializer.write(records, out)

    fun write(): ByteArray {
        val out = ByteArrayOutput()
        write(out)
        return out.toByteArray()
    }

    companion object {
        val tlvSerializer = TlvStreamSerializer(
            true, @Suppress("UNCHECKED_CAST") mapOf(
                OnionMessagePayloadTlv.ReplyPath.tag to OnionMessagePayloadTlv.ReplyPath.Companion as TlvValueReader<OnionMessagePayloadTlv>,
                OnionMessagePayloadTlv.EncryptedData.tag to OnionMessagePayloadTlv.EncryptedData.Companion as TlvValueReader<OnionMessagePayloadTlv>,
            )
        )

        fun read(input: Input): MessageOnion = MessageOnion(tlvSerializer.read(input))

        fun read(bytes: ByteArray): MessageOnion = read(ByteArrayInput(bytes))
    }
}