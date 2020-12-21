package fr.acinq.eclair.wire

import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.PublicKey
import fr.acinq.bitcoin.io.ByteArrayInput
import fr.acinq.bitcoin.io.ByteArrayOutput
import fr.acinq.bitcoin.io.Input
import fr.acinq.bitcoin.io.Output
import fr.acinq.eclair.CltvExpiry
import fr.acinq.eclair.CltvExpiryDelta
import fr.acinq.eclair.MilliSatoshi
import fr.acinq.eclair.ShortChannelId
import fr.acinq.eclair.serialization.ByteVector32KSerializer
import fr.acinq.eclair.serialization.ByteVectorKSerializer
import fr.acinq.eclair.serialization.PublicKeyKSerializer
import fr.acinq.eclair.payment.PaymentRequest
import fr.acinq.eclair.utils.msat
import fr.acinq.eclair.utils.toByteVector
import fr.acinq.eclair.utils.toByteVector32
import kotlinx.serialization.Serializable

@Serializable
data class OnionRoutingPacket(
    val version: Int,
    @Serializable(with = ByteVectorKSerializer::class) val publicKey: ByteVector,
    @Serializable(with = ByteVectorKSerializer::class) val payload: ByteVector,
    @Serializable(with = ByteVector32KSerializer::class) val hmac: ByteVector32
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
    data class PaymentData(@Serializable(with = ByteVector32KSerializer::class) val secret: ByteVector32, val totalAmount: MilliSatoshi) : OnionTlv() {
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
    data class InvoiceFeatures(@Serializable(with = ByteVectorKSerializer::class) val features: ByteVector) : OnionTlv() {
        override val tag: Long get() = InvoiceFeatures.tag
        override fun write(out: Output) = LightningCodecs.writeBytes(features, out)

        companion object : TlvValueReader<InvoiceFeatures> {
            const val tag: Long = 66097
            override fun read(input: Input): InvoiceFeatures = InvoiceFeatures(ByteVector(LightningCodecs.bytes(input, input.availableBytes)))
        }
    }

    /** Id of the next node. */
    @Serializable
    data class OutgoingNodeId(@Serializable(with = PublicKeyKSerializer::class) val nodeId: PublicKey) : OnionTlv() {
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

sealed class FinalPayload : PerHopPayload() {
    abstract val amount: MilliSatoshi
    abstract val expiry: CltvExpiry
    abstract val paymentSecret: ByteVector32?
    abstract val totalAmount: MilliSatoshi

    companion object : PerHopPayloadReader<FinalPayload> {
        override fun read(input: Input): FinalPayload {
            val bin = LightningCodecs.bytes(input, input.availableBytes)
            return when (bin.first()) {
                0.toByte() -> FinalLegacyPayload.read(ByteArrayInput(bin))
                else -> FinalTlvPayload.read(bin)
            }
        }

        fun createSinglePartPayload(amount: MilliSatoshi, expiry: CltvExpiry, paymentSecret: ByteVector32? = null, userCustomTlvs: List<GenericTlv> = listOf()): FinalPayload {
            return when {
                paymentSecret == null && userCustomTlvs.isNotEmpty() -> FinalTlvPayload(TlvStream(listOf(OnionTlv.AmountToForward(amount), OnionTlv.OutgoingCltv(expiry)), userCustomTlvs))
                paymentSecret == null -> FinalLegacyPayload(amount, expiry)
                else -> FinalTlvPayload(TlvStream(listOf(OnionTlv.AmountToForward(amount), OnionTlv.OutgoingCltv(expiry), OnionTlv.PaymentData(paymentSecret, amount)), userCustomTlvs))
            }
        }

        fun createMultiPartPayload(amount: MilliSatoshi, totalAmount: MilliSatoshi, expiry: CltvExpiry, paymentSecret: ByteVector32, additionalTlvs: List<OnionTlv> = listOf(), userCustomTlvs: List<GenericTlv> = listOf()): FinalPayload =
            FinalTlvPayload(TlvStream(listOf(OnionTlv.AmountToForward(amount), OnionTlv.OutgoingCltv(expiry), OnionTlv.PaymentData(paymentSecret, totalAmount)) + additionalTlvs, userCustomTlvs))

        /** Create a trampoline outer payload. */
        fun createTrampolinePayload(amount: MilliSatoshi, totalAmount: MilliSatoshi, expiry: CltvExpiry, paymentSecret: ByteVector32, trampolinePacket: OnionRoutingPacket): FinalPayload =
            FinalTlvPayload(TlvStream(listOf(OnionTlv.AmountToForward(amount), OnionTlv.OutgoingCltv(expiry), OnionTlv.PaymentData(paymentSecret, totalAmount), OnionTlv.TrampolineOnion(trampolinePacket))))
    }
}

data class FinalLegacyPayload(override val amount: MilliSatoshi, override val expiry: CltvExpiry) : FinalPayload() {
    override val paymentSecret: ByteVector32? = null
    override val totalAmount = amount

    override fun write(out: Output) {
        LightningCodecs.writeByte(0, out) // realm is always 0
        LightningCodecs.writeBytes(ByteArray(8), out)
        LightningCodecs.writeU64(amount.toLong(), out)
        LightningCodecs.writeU32(expiry.toLong().toInt(), out)
        LightningCodecs.writeBytes(ByteArray(12), out)
    }

    companion object : PerHopPayloadReader<FinalLegacyPayload> {
        override fun read(input: Input): FinalLegacyPayload {
            val realm = LightningCodecs.byte(input)
            require(realm == 0) { "invalid realm: $realm" }
            LightningCodecs.bytes(input, 8)
            val amount = MilliSatoshi(LightningCodecs.u64(input))
            val expiry = CltvExpiry(LightningCodecs.u32(input).toLong())
            LightningCodecs.bytes(input, 12)
            return FinalLegacyPayload(amount, expiry)
        }
    }
}

data class RelayLegacyPayload(val outgoingChannelId: ShortChannelId, val amountToForward: MilliSatoshi, val outgoingCltv: CltvExpiry) : PerHopPayload() {
    override fun write(out: Output) {
        LightningCodecs.writeByte(0, out) // realm is always 0
        LightningCodecs.writeU64(outgoingChannelId.toLong(), out)
        LightningCodecs.writeU64(amountToForward.toLong(), out)
        LightningCodecs.writeU32(outgoingCltv.toLong().toInt(), out)
        LightningCodecs.writeBytes(ByteArray(12), out)
    }

    companion object : PerHopPayloadReader<RelayLegacyPayload> {
        override fun read(input: Input): RelayLegacyPayload {
            val realm = LightningCodecs.byte(input)
            require(realm == 0) { "invalid realm: $realm" }
            val shortChannelId = ShortChannelId(LightningCodecs.u64(input))
            val amount = MilliSatoshi(LightningCodecs.u64(input))
            val expiry = CltvExpiry(LightningCodecs.u32(input).toLong())
            LightningCodecs.bytes(input, 12)
            return RelayLegacyPayload(shortChannelId, amount, expiry)
        }
    }
}

@OptIn(ExperimentalUnsignedTypes::class)
data class FinalTlvPayload(val records: TlvStream<OnionTlv>) : FinalPayload() {
    override val amount = records.get<OnionTlv.AmountToForward>()!!.amount
    override val expiry = records.get<OnionTlv.OutgoingCltv>()!!.cltv
    override val paymentSecret = records.get<OnionTlv.PaymentData>()?.secret
    override val totalAmount = run {
        val total = records.get<OnionTlv.PaymentData>()?.totalAmount
        if (total != null && total > 0.msat) total else amount
    }

    override fun write(out: Output) = tlvSerializer.write(records, out)

    companion object : PerHopPayloadReader<FinalTlvPayload> {
        override fun read(input: Input): FinalTlvPayload = FinalTlvPayload(tlvSerializer.read(input))
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
    val paymentSecret = records.get<OnionTlv.PaymentData>()?.secret
    val invoiceFeatures = records.get<OnionTlv.InvoiceFeatures>()?.features
    val invoiceRoutingInfo = records.get<OnionTlv.InvoiceRoutingInfo>()?.extraHops

    override fun write(out: Output) = tlvSerializer.write(records, out)

    companion object : PerHopPayloadReader<NodeRelayPayload> {
        override fun read(input: Input): NodeRelayPayload = NodeRelayPayload(tlvSerializer.read(input))

        fun create(amount: MilliSatoshi, expiry: CltvExpiry, nextNodeId: PublicKey) = NodeRelayPayload(TlvStream(listOf(OnionTlv.AmountToForward(amount), OnionTlv.OutgoingCltv(expiry), OnionTlv.OutgoingNodeId(nextNodeId))))

        /** Create a trampoline inner payload instructing the trampoline node to relay via a non-trampoline payment. */
        fun createNodeRelayToNonTrampolinePayload(amount: MilliSatoshi, totalAmount: MilliSatoshi, expiry: CltvExpiry, targetNodeId: PublicKey, invoice: PaymentRequest): NodeRelayPayload {
            val tlvs = mutableListOf(
                OnionTlv.AmountToForward(amount),
                OnionTlv.OutgoingCltv(expiry),
                OnionTlv.OutgoingNodeId(targetNodeId),
                OnionTlv.InvoiceFeatures(invoice.features ?: ByteVector.empty),
                OnionTlv.InvoiceRoutingInfo(invoice.routingInfo.map { it.hints })
            )
            if (invoice.paymentSecret != null) {
                tlvs.add(OnionTlv.PaymentData(invoice.paymentSecret, totalAmount))
            }
            return NodeRelayPayload(TlvStream(tlvs))
        }
    }
}
