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
import fr.acinq.lightning.utils.toByteVector
import fr.acinq.lightning.utils.toByteVector32
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

@Serializable
data class OnionRoutingPacket(
    val version: Int,
    @Contextual val publicKey: ByteVector,
    @Contextual val payload: ByteVector,
    @Contextual val hmac: ByteVector32
) {
    companion object {
        const val PaymentPacketLength = 1300
        const val TrampolinePacketLength = 400
    }
}

/**
 * @param payloadLength length of the onion-encrypted payload.
 */
@OptIn(ExperimentalUnsignedTypes::class)
class OnionRoutingPacketSerializer(private val payloadLength: Int) {
    fun read(input: Input): OnionRoutingPacket {
        return OnionRoutingPacket(
            LightningCodecs.byte(input),
            LightningCodecs.bytes(input, 33).toByteVector(),
            LightningCodecs.bytes(input, payloadLength).toByteVector(),
            LightningCodecs.bytes(input, 32).toByteVector32()
        )
    }

    fun read(bytes: ByteArray): OnionRoutingPacket = read(ByteArrayInput(bytes))

    fun write(message: OnionRoutingPacket, out: Output) {
        LightningCodecs.writeByte(message.version, out)
        LightningCodecs.writeBytes(message.publicKey, out)
        LightningCodecs.writeBytes(message.payload, out)
        LightningCodecs.writeBytes(message.hmac, out)
    }

    fun write(message: OnionRoutingPacket): ByteArray {
        val out = ByteArrayOutput()
        write(message, out)
        return out.toByteArray()
    }
}

@OptIn(ExperimentalUnsignedTypes::class)
@Serializable
sealed class OnionTlv : Tlv {
    /** Amount to forward to the next node. */
    @Serializable
    data class AmountToForward(val amount: MilliSatoshi) : OnionTlv() {
        override val tag: Long get() = AmountToForward.tag
        override fun write(out: Output) = LightningCodecs.writeTU64(amount.toLong(), out)

        companion object : TlvValueReader<AmountToForward> {
            const val tag: Long = 2
            override fun read(input: Input): AmountToForward = AmountToForward(MilliSatoshi(LightningCodecs.tu64(input)))
        }
    }

    /** CLTV value to use for the HTLC offered to the next node. */
    @Serializable
    data class OutgoingCltv(val cltv: CltvExpiry) : OnionTlv() {
        override val tag: Long get() = OutgoingCltv.tag
        override fun write(out: Output) = LightningCodecs.writeTU32(cltv.toLong().toInt(), out)

        companion object : TlvValueReader<OutgoingCltv> {
            const val tag: Long = 4
            override fun read(input: Input): OutgoingCltv = OutgoingCltv(CltvExpiry(LightningCodecs.tu32(input).toLong()))
        }
    }

    /** Id of the channel to use to forward a payment to the next node. */
    @Serializable
    data class OutgoingChannelId(val shortChannelId: ShortChannelId) : OnionTlv() {
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
    data class PaymentData(@Contextual val secret: ByteVector32, val totalAmount: MilliSatoshi) : OnionTlv() {
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
     * Invoice feature bits. Only included for intermediate trampoline nodes when they should convert to a legacy payment
     * because the final recipient doesn't support trampoline.
     */
    @Serializable
    data class InvoiceFeatures(@Contextual val features: ByteVector) : OnionTlv() {
        override val tag: Long get() = InvoiceFeatures.tag
        override fun write(out: Output) = LightningCodecs.writeBytes(features, out)

        companion object : TlvValueReader<InvoiceFeatures> {
            const val tag: Long = 66097
            override fun read(input: Input): InvoiceFeatures = InvoiceFeatures(ByteVector(LightningCodecs.bytes(input, input.availableBytes)))
        }
    }

    /** Id of the next node. */
    @Serializable
    data class OutgoingNodeId(@Contextual val nodeId: PublicKey) : OnionTlv() {
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
    data class InvoiceRoutingInfo(val extraHops: List<List<PaymentRequest.TaggedField.ExtraHop>>) : OnionTlv() {
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
    data class TrampolineOnion(val packet: OnionRoutingPacket) : OnionTlv() {
        override val tag: Long get() = TrampolineOnion.tag
        override fun write(out: Output) = OnionRoutingPacketSerializer(OnionRoutingPacket.TrampolinePacketLength).write(packet, out)

        companion object : TlvValueReader<TrampolineOnion> {
            const val tag: Long = 66100
            override fun read(input: Input): TrampolineOnion = TrampolineOnion(OnionRoutingPacketSerializer(OnionRoutingPacket.TrampolinePacketLength).read(input))
        }
    }
}

sealed class PerHopPayload {

    abstract fun write(out: Output)

    fun write(): ByteArray {
        val out = ByteArrayOutput()
        write(out)
        return out.toByteArray()
    }

    companion object {
        val tlvSerializer = TlvStreamSerializer(
            true,
            @Suppress("UNCHECKED_CAST")
            mapOf(
                OnionTlv.AmountToForward.tag to OnionTlv.AmountToForward.Companion as TlvValueReader<OnionTlv>,
                OnionTlv.OutgoingCltv.tag to OnionTlv.OutgoingCltv.Companion as TlvValueReader<OnionTlv>,
                OnionTlv.OutgoingChannelId.tag to OnionTlv.OutgoingChannelId.Companion as TlvValueReader<OnionTlv>,
                OnionTlv.PaymentData.tag to OnionTlv.PaymentData.Companion as TlvValueReader<OnionTlv>,
                OnionTlv.InvoiceFeatures.tag to OnionTlv.InvoiceFeatures.Companion as TlvValueReader<OnionTlv>,
                OnionTlv.OutgoingNodeId.tag to OnionTlv.OutgoingNodeId.Companion as TlvValueReader<OnionTlv>,
                OnionTlv.InvoiceRoutingInfo.tag to OnionTlv.InvoiceRoutingInfo.Companion as TlvValueReader<OnionTlv>,
                OnionTlv.TrampolineOnion.tag to OnionTlv.TrampolineOnion.Companion as TlvValueReader<OnionTlv>,
            )
        )
    }
}

interface PerHopPayloadReader<T : PerHopPayload> {
    fun read(input: Input): T
    fun read(bytes: ByteArray): T = read(ByteArrayInput(bytes))
}

data class FinalPayload(val records: TlvStream<OnionTlv>) : PerHopPayload() {
    val amount = records.get<OnionTlv.AmountToForward>()!!.amount
    val expiry = records.get<OnionTlv.OutgoingCltv>()!!.cltv
    val paymentSecret = records.get<OnionTlv.PaymentData>()!!.secret
    val totalAmount = run {
        val total = records.get<OnionTlv.PaymentData>()!!.totalAmount
        if (total > 0.msat) total else amount
    }

    override fun write(out: Output) = tlvSerializer.write(records, out)

    companion object : PerHopPayloadReader<FinalPayload> {
        override fun read(input: Input): FinalPayload = FinalPayload(tlvSerializer.read(input))

        /** Create a single-part payment (total amount sent at once). */
        fun createSinglePartPayload(amount: MilliSatoshi, expiry: CltvExpiry, paymentSecret: ByteVector32, userCustomTlvs: List<GenericTlv> = listOf()): FinalPayload =
            FinalPayload(TlvStream(listOf(OnionTlv.AmountToForward(amount), OnionTlv.OutgoingCltv(expiry), OnionTlv.PaymentData(paymentSecret, amount)), userCustomTlvs))

        /** Create a partial payment (total amount split between multiple payments). */
        fun createMultiPartPayload(amount: MilliSatoshi, totalAmount: MilliSatoshi, expiry: CltvExpiry, paymentSecret: ByteVector32, additionalTlvs: List<OnionTlv> = listOf(), userCustomTlvs: List<GenericTlv> = listOf()): FinalPayload =
            FinalPayload(TlvStream(listOf(OnionTlv.AmountToForward(amount), OnionTlv.OutgoingCltv(expiry), OnionTlv.PaymentData(paymentSecret, totalAmount)) + additionalTlvs, userCustomTlvs))

        /** Create a trampoline outer payload. */
        fun createTrampolinePayload(amount: MilliSatoshi, totalAmount: MilliSatoshi, expiry: CltvExpiry, paymentSecret: ByteVector32, trampolinePacket: OnionRoutingPacket): FinalPayload =
            FinalPayload(TlvStream(listOf(OnionTlv.AmountToForward(amount), OnionTlv.OutgoingCltv(expiry), OnionTlv.PaymentData(paymentSecret, totalAmount), OnionTlv.TrampolineOnion(trampolinePacket))))
    }
}

data class ChannelRelayPayload(val records: TlvStream<OnionTlv>) : PerHopPayload() {
    val amountToForward = records.get<OnionTlv.AmountToForward>()!!.amount
    val outgoingCltv = records.get<OnionTlv.OutgoingCltv>()!!.cltv
    val outgoingChannelId = records.get<OnionTlv.OutgoingChannelId>()!!.shortChannelId

    override fun write(out: Output) = tlvSerializer.write(records, out)

    companion object : PerHopPayloadReader<ChannelRelayPayload> {
        override fun read(input: Input): ChannelRelayPayload = ChannelRelayPayload(tlvSerializer.read(input))

        fun create(outgoingChannelId: ShortChannelId, amountToForward: MilliSatoshi, outgoingCltv: CltvExpiry): ChannelRelayPayload =
            ChannelRelayPayload(TlvStream(listOf(OnionTlv.AmountToForward(amountToForward), OnionTlv.OutgoingCltv(outgoingCltv), OnionTlv.OutgoingChannelId(outgoingChannelId))))
    }
}

data class NodeRelayPayload(val records: TlvStream<OnionTlv>) : PerHopPayload() {
    val amountToForward = records.get<OnionTlv.AmountToForward>()!!.amount
    val outgoingCltv = records.get<OnionTlv.OutgoingCltv>()!!.cltv
    val outgoingNodeId = records.get<OnionTlv.OutgoingNodeId>()!!.nodeId
    val totalAmount = run {
        val paymentData = records.get<OnionTlv.PaymentData>()
        when {
            paymentData == null -> amountToForward
            paymentData.totalAmount == MilliSatoshi(0) -> amountToForward
            else -> paymentData.totalAmount
        }
    }

    // NB: the following fields are only included in the trampoline-to-legacy case.
    val paymentSecret = records.get<OnionTlv.PaymentData>()?.secret
    val invoiceFeatures = records.get<OnionTlv.InvoiceFeatures>()?.features
    val invoiceRoutingInfo = records.get<OnionTlv.InvoiceRoutingInfo>()?.extraHops

    override fun write(out: Output) = tlvSerializer.write(records, out)

    companion object : PerHopPayloadReader<NodeRelayPayload> {
        override fun read(input: Input): NodeRelayPayload = NodeRelayPayload(tlvSerializer.read(input))

        fun create(amount: MilliSatoshi, expiry: CltvExpiry, nextNodeId: PublicKey) = NodeRelayPayload(TlvStream(listOf(OnionTlv.AmountToForward(amount), OnionTlv.OutgoingCltv(expiry), OnionTlv.OutgoingNodeId(nextNodeId))))

        /** Create a trampoline inner payload instructing the trampoline node to relay via a non-trampoline payment. */
        fun createNodeRelayToNonTrampolinePayload(amount: MilliSatoshi, totalAmount: MilliSatoshi, expiry: CltvExpiry, targetNodeId: PublicKey, invoice: PaymentRequest): NodeRelayPayload {
            // NB: we limit the number of routing hints to ensure we don't overflow the onion.
            // A better solution is to provide the routing hints outside the onion (in the `update_add_htlc` tlv stream).
            val prunedRoutingHints = invoice.routingInfo.shuffled().fold(listOf<PaymentRequest.TaggedField.RoutingInfo>()) { previous, current ->
                if (previous.flatMap { it.hints }.size + current.hints.size <= 4) {
                    previous + current
                } else {
                    previous
                }
            }.map { it.hints }
            return NodeRelayPayload(
                TlvStream(
                    listOf(
                        OnionTlv.AmountToForward(amount),
                        OnionTlv.OutgoingCltv(expiry),
                        OnionTlv.OutgoingNodeId(targetNodeId),
                        OnionTlv.PaymentData(invoice.paymentSecret, totalAmount),
                        OnionTlv.InvoiceFeatures(invoice.features),
                        OnionTlv.InvoiceRoutingInfo(prunedRoutingHints)
                    )
                )
            )
        }

    }

}
