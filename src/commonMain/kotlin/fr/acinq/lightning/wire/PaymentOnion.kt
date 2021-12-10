package fr.acinq.lightning.wire

import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.PublicKey
import fr.acinq.bitcoin.io.ByteArrayInput
import fr.acinq.bitcoin.io.ByteArrayOutput
import fr.acinq.bitcoin.io.Input
import fr.acinq.bitcoin.io.Output
import fr.acinq.lightning.CltvExpiry
import fr.acinq.lightning.CltvExpiryDelta
import fr.acinq.lightning.MilliSatoshi
import fr.acinq.lightning.ShortChannelId
import fr.acinq.lightning.payment.PaymentRequest
import fr.acinq.lightning.utils.msat
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

@OptIn(ExperimentalUnsignedTypes::class)
@Serializable
sealed class OnionPaymentPayloadTlv : Tlv {
    /** Amount to forward to the next node. */
    @Serializable
    data class AmountToForward(val amount: MilliSatoshi) : OnionPaymentPayloadTlv() {
        override val tag: Long get() = AmountToForward.tag
        override fun write(out: Output) = LightningCodecs.writeTU64(amount.toLong(), out)

        companion object : TlvValueReader<AmountToForward> {
            const val tag: Long = 2
            override fun read(input: Input): AmountToForward = AmountToForward(MilliSatoshi(LightningCodecs.tu64(input)))
        }
    }

    /** CLTV value to use for the HTLC offered to the next node. */
    @Serializable
    data class OutgoingCltv(val cltv: CltvExpiry) : OnionPaymentPayloadTlv() {
        override val tag: Long get() = OutgoingCltv.tag
        override fun write(out: Output) = LightningCodecs.writeTU32(cltv.toLong().toInt(), out)

        companion object : TlvValueReader<OutgoingCltv> {
            const val tag: Long = 4
            override fun read(input: Input): OutgoingCltv = OutgoingCltv(CltvExpiry(LightningCodecs.tu32(input).toLong()))
        }
    }

    /** Id of the channel to use to forward a payment to the next node. */
    @Serializable
    data class OutgoingChannelId(val shortChannelId: ShortChannelId) : OnionPaymentPayloadTlv() {
        override val tag: Long get() = OutgoingChannelId.tag
        override fun write(out: Output) = LightningCodecs.writeU64(shortChannelId.toLong(), out)

        companion object : TlvValueReader<OutgoingChannelId> {
            const val tag: Long = 6
            override fun read(input: Input): OutgoingChannelId = OutgoingChannelId(ShortChannelId(LightningCodecs.u64(input)))
        }
    }

    /**
     * Bolt 11 payment details (only included for the last node).
     *
     * @param secret payment secret specified in the Bolt 11 invoice.
     * @param totalAmount total amount in multi-part payments. When missing, assumed to be equal to AmountToForward.
     */
    @Serializable
    data class PaymentData(@Contextual val secret: ByteVector32, val totalAmount: MilliSatoshi) : OnionPaymentPayloadTlv() {
        override val tag: Long get() = PaymentData.tag
        override fun write(out: Output) {
            LightningCodecs.writeBytes(secret, out)
            LightningCodecs.writeTU64(totalAmount.toLong(), out)
        }

        companion object : TlvValueReader<PaymentData> {
            const val tag: Long = 8
            override fun read(input: Input): PaymentData = PaymentData(ByteVector32(LightningCodecs.bytes(input, 32)), MilliSatoshi(LightningCodecs.tu64(input)))
        }
    }

    /**
     * When payment metadata is included in a Bolt 9 invoice, we should send it as-is to the recipient.
     * This lets recipients generate invoices without having to store anything on their side until the invoice is paid.
     */
    @Serializable
    data class PaymentMetadata(@Contextual val data: ByteVector) : OnionPaymentPayloadTlv() {
        override val tag: Long get() = PaymentMetadata.tag
        override fun write(out: Output) = LightningCodecs.writeBytes(data, out)

        companion object : TlvValueReader<PaymentMetadata> {
            const val tag: Long = 16
            override fun read(input: Input): PaymentMetadata = PaymentMetadata(ByteVector(LightningCodecs.bytes(input, input.availableBytes)))
        }
    }

    /**
     * Invoice feature bits. Only included for intermediate trampoline nodes when they should convert to a legacy payment
     * because the final recipient doesn't support trampoline.
     */
    @Serializable
    data class InvoiceFeatures(@Contextual val features: ByteVector) : OnionPaymentPayloadTlv() {
        override val tag: Long get() = InvoiceFeatures.tag
        override fun write(out: Output) = LightningCodecs.writeBytes(features, out)

        companion object : TlvValueReader<InvoiceFeatures> {
            const val tag: Long = 66097
            override fun read(input: Input): InvoiceFeatures = InvoiceFeatures(ByteVector(LightningCodecs.bytes(input, input.availableBytes)))
        }
    }

    /** Id of the next node. */
    @Serializable
    data class OutgoingNodeId(@Contextual val nodeId: PublicKey) : OnionPaymentPayloadTlv() {
        override val tag: Long get() = OutgoingNodeId.tag
        override fun write(out: Output) = LightningCodecs.writeBytes(nodeId.value, out)

        companion object : TlvValueReader<OutgoingNodeId> {
            const val tag: Long = 66098
            override fun read(input: Input): OutgoingNodeId = OutgoingNodeId(PublicKey(LightningCodecs.bytes(input, 33)))
        }
    }

    /**
     * Invoice routing hints. Only included for intermediate trampoline nodes when they should convert to a legacy payment
     * because the final recipient doesn't support trampoline.
     */
    @Serializable
    data class InvoiceRoutingInfo(val extraHops: List<List<PaymentRequest.TaggedField.ExtraHop>>) : OnionPaymentPayloadTlv() {
        override val tag: Long get() = InvoiceRoutingInfo.tag
        override fun write(out: Output) {
            for (routeHint in extraHops) {
                LightningCodecs.writeByte(routeHint.size, out)
                routeHint.map {
                    LightningCodecs.writeBytes(it.nodeId.value, out)
                    LightningCodecs.writeU64(it.shortChannelId.toLong(), out)
                    LightningCodecs.writeU32(it.feeBase.toLong().toInt(), out)
                    LightningCodecs.writeU32(it.feeProportionalMillionths.toInt(), out)
                    LightningCodecs.writeU16(it.cltvExpiryDelta.toInt(), out)
                }
            }
        }

        companion object : TlvValueReader<InvoiceRoutingInfo> {
            const val tag: Long = 66099
            override fun read(input: Input): InvoiceRoutingInfo {
                val extraHops = mutableListOf<List<PaymentRequest.TaggedField.ExtraHop>>()
                while (input.availableBytes > 0) {
                    val hopCount = LightningCodecs.byte(input)
                    val extraHop = (0 until hopCount).map {
                        PaymentRequest.TaggedField.ExtraHop(
                            PublicKey(LightningCodecs.bytes(input, 33)),
                            ShortChannelId(LightningCodecs.u64(input)),
                            MilliSatoshi(LightningCodecs.u32(input).toLong()),
                            LightningCodecs.u32(input).toLong(),
                            CltvExpiryDelta(LightningCodecs.u16(input))
                        )
                    }
                    extraHops.add(extraHop)
                }
                return InvoiceRoutingInfo(extraHops)
            }
        }
    }

    /** An encrypted trampoline onion packet. */
    @Serializable
    data class TrampolineOnion(val packet: OnionRoutingPacket) : OnionPaymentPayloadTlv() {
        override val tag: Long get() = TrampolineOnion.tag
        override fun write(out: Output) = OnionRoutingPacketSerializer(OnionRoutingPacket.TrampolinePacketLength).write(packet, out)

        companion object : TlvValueReader<TrampolineOnion> {
            const val tag: Long = 66100
            override fun read(input: Input): TrampolineOnion = TrampolineOnion(OnionRoutingPacketSerializer(OnionRoutingPacket.TrampolinePacketLength).read(input))
        }
    }
}

object PaymentOnion {

    sealed class PerHopPayload {

        abstract fun write(out: Output)

        fun write(): ByteArray {
            val out = ByteArrayOutput()
            write(out)
            return out.toByteArray()
        }

        companion object {
            val tlvSerializer = TlvStreamSerializer(
                true, @Suppress("UNCHECKED_CAST") mapOf(
                    OnionPaymentPayloadTlv.AmountToForward.tag to OnionPaymentPayloadTlv.AmountToForward.Companion as TlvValueReader<OnionPaymentPayloadTlv>,
                    OnionPaymentPayloadTlv.OutgoingCltv.tag to OnionPaymentPayloadTlv.OutgoingCltv.Companion as TlvValueReader<OnionPaymentPayloadTlv>,
                    OnionPaymentPayloadTlv.OutgoingChannelId.tag to OnionPaymentPayloadTlv.OutgoingChannelId.Companion as TlvValueReader<OnionPaymentPayloadTlv>,
                    OnionPaymentPayloadTlv.PaymentData.tag to OnionPaymentPayloadTlv.PaymentData.Companion as TlvValueReader<OnionPaymentPayloadTlv>,
                    OnionPaymentPayloadTlv.PaymentMetadata.tag to OnionPaymentPayloadTlv.PaymentMetadata.Companion as TlvValueReader<OnionPaymentPayloadTlv>,
                    OnionPaymentPayloadTlv.InvoiceFeatures.tag to OnionPaymentPayloadTlv.InvoiceFeatures.Companion as TlvValueReader<OnionPaymentPayloadTlv>,
                    OnionPaymentPayloadTlv.OutgoingNodeId.tag to OnionPaymentPayloadTlv.OutgoingNodeId.Companion as TlvValueReader<OnionPaymentPayloadTlv>,
                    OnionPaymentPayloadTlv.InvoiceRoutingInfo.tag to OnionPaymentPayloadTlv.InvoiceRoutingInfo.Companion as TlvValueReader<OnionPaymentPayloadTlv>,
                    OnionPaymentPayloadTlv.TrampolineOnion.tag to OnionPaymentPayloadTlv.TrampolineOnion.Companion as TlvValueReader<OnionPaymentPayloadTlv>,
                )
            )
        }
    }

    interface PerHopPayloadReader<T : PerHopPayload> {
        fun read(input: Input): T
        fun read(bytes: ByteArray): T = read(ByteArrayInput(bytes))
    }

    data class FinalPayload(val records: TlvStream<OnionPaymentPayloadTlv>) : PerHopPayload() {
        val amount = records.get<OnionPaymentPayloadTlv.AmountToForward>()!!.amount
        val expiry = records.get<OnionPaymentPayloadTlv.OutgoingCltv>()!!.cltv
        val paymentSecret = records.get<OnionPaymentPayloadTlv.PaymentData>()!!.secret
        val totalAmount = run {
            val total = records.get<OnionPaymentPayloadTlv.PaymentData>()!!.totalAmount
            if (total > 0.msat) total else amount
        }
        val paymentMetadata = records.get<OnionPaymentPayloadTlv.PaymentMetadata>()?.data

        override fun write(out: Output) = tlvSerializer.write(records, out)

        companion object : PerHopPayloadReader<FinalPayload> {
            override fun read(input: Input): FinalPayload = FinalPayload(tlvSerializer.read(input))

            /** Create a single-part payment (total amount sent at once). */
            fun createSinglePartPayload(amount: MilliSatoshi, expiry: CltvExpiry, paymentSecret: ByteVector32, paymentMetadata: ByteVector?, userCustomTlvs: List<GenericTlv> = listOf()): FinalPayload {
                val tlvs = buildList {
                    add(OnionPaymentPayloadTlv.AmountToForward(amount))
                    add(OnionPaymentPayloadTlv.OutgoingCltv(expiry))
                    add(OnionPaymentPayloadTlv.PaymentData(paymentSecret, amount))
                    paymentMetadata?.let { add(OnionPaymentPayloadTlv.PaymentMetadata(it)) }
                }
                return FinalPayload(TlvStream(tlvs, userCustomTlvs))
            }

            /** Create a partial payment (total amount split between multiple payments). */
            fun createMultiPartPayload(
                amount: MilliSatoshi,
                totalAmount: MilliSatoshi,
                expiry: CltvExpiry,
                paymentSecret: ByteVector32,
                paymentMetadata: ByteVector?,
                additionalTlvs: List<OnionPaymentPayloadTlv> = listOf(),
                userCustomTlvs: List<GenericTlv> = listOf()
            ): FinalPayload {
                val tlvs = buildList {
                    add(OnionPaymentPayloadTlv.AmountToForward(amount))
                    add(OnionPaymentPayloadTlv.OutgoingCltv(expiry))
                    add(OnionPaymentPayloadTlv.PaymentData(paymentSecret, totalAmount))
                    paymentMetadata?.let { add(OnionPaymentPayloadTlv.PaymentMetadata(it)) }
                    addAll(additionalTlvs)
                }
                return FinalPayload(TlvStream(tlvs, userCustomTlvs))
            }

            /** Create a trampoline outer payload. */
            fun createTrampolinePayload(amount: MilliSatoshi, totalAmount: MilliSatoshi, expiry: CltvExpiry, paymentSecret: ByteVector32, trampolinePacket: OnionRoutingPacket): FinalPayload {
                val tlvs = buildList {
                    add(OnionPaymentPayloadTlv.AmountToForward(amount))
                    add(OnionPaymentPayloadTlv.OutgoingCltv(expiry))
                    add(OnionPaymentPayloadTlv.PaymentData(paymentSecret, totalAmount))
                    add(OnionPaymentPayloadTlv.TrampolineOnion(trampolinePacket))
                }
                return FinalPayload(TlvStream(tlvs))
            }
        }
    }

    data class ChannelRelayPayload(val records: TlvStream<OnionPaymentPayloadTlv>) : PerHopPayload() {
        val amountToForward = records.get<OnionPaymentPayloadTlv.AmountToForward>()!!.amount
        val outgoingCltv = records.get<OnionPaymentPayloadTlv.OutgoingCltv>()!!.cltv
        val outgoingChannelId = records.get<OnionPaymentPayloadTlv.OutgoingChannelId>()!!.shortChannelId

        override fun write(out: Output) = tlvSerializer.write(records, out)

        companion object : PerHopPayloadReader<ChannelRelayPayload> {
            override fun read(input: Input): ChannelRelayPayload = ChannelRelayPayload(tlvSerializer.read(input))

            fun create(outgoingChannelId: ShortChannelId, amountToForward: MilliSatoshi, outgoingCltv: CltvExpiry): ChannelRelayPayload =
                ChannelRelayPayload(TlvStream(listOf(OnionPaymentPayloadTlv.AmountToForward(amountToForward), OnionPaymentPayloadTlv.OutgoingCltv(outgoingCltv), OnionPaymentPayloadTlv.OutgoingChannelId(outgoingChannelId))))
        }
    }

    data class NodeRelayPayload(val records: TlvStream<OnionPaymentPayloadTlv>) : PerHopPayload() {
        val amountToForward = records.get<OnionPaymentPayloadTlv.AmountToForward>()!!.amount
        val outgoingCltv = records.get<OnionPaymentPayloadTlv.OutgoingCltv>()!!.cltv
        val outgoingNodeId = records.get<OnionPaymentPayloadTlv.OutgoingNodeId>()!!.nodeId
        val totalAmount = run {
            val paymentData = records.get<OnionPaymentPayloadTlv.PaymentData>()
            when {
                paymentData == null -> amountToForward
                paymentData.totalAmount == MilliSatoshi(0) -> amountToForward
                else -> paymentData.totalAmount
            }
        }

        // NB: the following fields are only included in the trampoline-to-legacy case.
        val paymentSecret = records.get<OnionPaymentPayloadTlv.PaymentData>()?.secret
        val paymentMetadata = records.get<OnionPaymentPayloadTlv.PaymentMetadata>()?.data
        val invoiceFeatures = records.get<OnionPaymentPayloadTlv.InvoiceFeatures>()?.features
        val invoiceRoutingInfo = records.get<OnionPaymentPayloadTlv.InvoiceRoutingInfo>()?.extraHops

        override fun write(out: Output) = tlvSerializer.write(records, out)

        companion object : PerHopPayloadReader<NodeRelayPayload> {
            override fun read(input: Input): NodeRelayPayload = NodeRelayPayload(tlvSerializer.read(input))

            fun create(amount: MilliSatoshi, expiry: CltvExpiry, nextNodeId: PublicKey) =
                NodeRelayPayload(TlvStream(listOf(OnionPaymentPayloadTlv.AmountToForward(amount), OnionPaymentPayloadTlv.OutgoingCltv(expiry), OnionPaymentPayloadTlv.OutgoingNodeId(nextNodeId))))

            /** Create a trampoline inner payload instructing the trampoline node to relay via a non-trampoline payment. */
            fun createNodeRelayToNonTrampolinePayload(amount: MilliSatoshi, totalAmount: MilliSatoshi, expiry: CltvExpiry, targetNodeId: PublicKey, invoice: PaymentRequest): NodeRelayPayload {
                // NB: we limit the number of routing hints to ensure we don't overflow the onion.
                // A better solution is to provide the routing hints outside the onion (in the `update_add_htlc` tlv stream).
                val prunedRoutingHints = invoice.routingInfo.shuffled().fold(listOf<PaymentRequest.TaggedField.RoutingInfo>()) { previous, current ->
                    if (previous.flatMap { it.hints }.size + current.hints.size <= 3) {
                        previous + current
                    } else {
                        previous
                    }
                }.map { it.hints }
                return NodeRelayPayload(
                    TlvStream(
                        buildList {
                            add(OnionPaymentPayloadTlv.AmountToForward(amount))
                            add(OnionPaymentPayloadTlv.OutgoingCltv(expiry))
                            add(OnionPaymentPayloadTlv.OutgoingNodeId(targetNodeId))
                            add(OnionPaymentPayloadTlv.PaymentData(invoice.paymentSecret, totalAmount))
                            invoice.paymentMetadata?.let { add(OnionPaymentPayloadTlv.PaymentMetadata(it)) }
                            add(OnionPaymentPayloadTlv.InvoiceFeatures(invoice.features))
                            add(OnionPaymentPayloadTlv.InvoiceRoutingInfo(prunedRoutingHints))
                        }
                    )
                )
            }
        }
    }

}
