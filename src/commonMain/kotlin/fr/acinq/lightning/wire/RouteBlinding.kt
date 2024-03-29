package fr.acinq.lightning.wire

import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.PublicKey
import fr.acinq.bitcoin.io.ByteArrayInput
import fr.acinq.bitcoin.io.ByteArrayOutput
import fr.acinq.bitcoin.io.Input
import fr.acinq.bitcoin.io.Output
import fr.acinq.lightning.*

sealed class RouteBlindingEncryptedDataTlv : Tlv {
    /** Some padding can be added to ensure all payloads are the same size to improve privacy. */
    data class Padding(val dummy: ByteVector) : RouteBlindingEncryptedDataTlv() {
        override val tag: Long get() = Padding.tag
        override fun write(out: Output) = LightningCodecs.writeBytes(dummy, out)

        companion object : TlvValueReader<Padding> {
            const val tag: Long = 1
            override fun read(input: Input): Padding =
                Padding(ByteVector(LightningCodecs.bytes(input, input.availableBytes)))
        }
    }

    /** Id of the outgoing channel, used to identify the next node. */
    data class OutgoingChannelId(val shortChannelId: ShortChannelId) : RouteBlindingEncryptedDataTlv() {
        override val tag: Long get() = OutgoingChannelId.tag
        override fun write(out: Output) = LightningCodecs.writeInt64(shortChannelId.toLong(), out)

        companion object : TlvValueReader<OutgoingChannelId> {
            const val tag: Long = 2
            override fun read(input: Input): OutgoingChannelId =
                OutgoingChannelId(ShortChannelId(LightningCodecs.int64(input)))
        }
    }

    /** Id of the next node. */
    data class OutgoingNodeId(val nodeId: EncodedNodeId) : RouteBlindingEncryptedDataTlv() {
        override val tag: Long get() = OutgoingNodeId.tag
        override fun write(out: Output) = LightningCodecs.writeEncodedNodeId(nodeId, out)

        companion object : TlvValueReader<OutgoingNodeId> {
            const val tag: Long = 4
            override fun read(input: Input): OutgoingNodeId =
                OutgoingNodeId(LightningCodecs.encodedNodeId(input))
        }
    }

    /**
     * The final recipient may store some data in the encrypted payload for itself to avoid storing it locally.
     * It can for example put a payment_hash to verify that the route is used for the correct invoice.
     * It should use that field to detect when blinded routes are used outside of their intended use (malicious probing)
     * and react accordingly (ignore the message or send an error depending on the use-case).
     */
    data class PathId(val data: ByteVector) : RouteBlindingEncryptedDataTlv() {
        override val tag: Long get() = PathId.tag
        override fun write(out: Output) = LightningCodecs.writeBytes(data, out)

        companion object : TlvValueReader<PathId> {
            const val tag: Long = 6
            override fun read(input: Input): PathId =
                PathId(ByteVector(LightningCodecs.bytes(input, input.availableBytes)))
        }
    }

    /** Blinding override for the rest of the route. */
    data class NextBlinding(val blinding: PublicKey) : RouteBlindingEncryptedDataTlv() {
        override val tag: Long get() = NextBlinding.tag
        override fun write(out: Output) = LightningCodecs.writeBytes(blinding.value, out)

        companion object : TlvValueReader<NextBlinding> {
            const val tag: Long = 8
            override fun read(input: Input): NextBlinding =
                NextBlinding(PublicKey(LightningCodecs.bytes(input, 33)))
        }
    }

    /** Information for the relaying node to build the next HTLC. */
    data class PaymentRelay(val cltvExpiryDelta: CltvExpiryDelta, val feeProportionalMillionths: Long, val feeBase: MilliSatoshi) : RouteBlindingEncryptedDataTlv() {
        override val tag: Long get() = PaymentRelay.tag
        override fun write(out: Output) {
            LightningCodecs.writeU16(cltvExpiryDelta.toInt(), out)
            LightningCodecs.writeU32(feeProportionalMillionths.toInt(), out)
            LightningCodecs.writeTU32(feeBase.msat.toInt(), out)
        }

        companion object : TlvValueReader<PaymentRelay> {
            const val tag: Long = 10
            override fun read(input: Input): PaymentRelay {
                val cltvExpiryDelta = CltvExpiryDelta(LightningCodecs.u16(input))
                val feeProportionalMillionths = LightningCodecs.u32(input)
                val feeBase = MilliSatoshi(LightningCodecs.tu32(input).toLong())
                return PaymentRelay(cltvExpiryDelta, feeProportionalMillionths.toLong(), feeBase)
            }
        }
    }

    /** Constraints for the relaying node to enforce to prevent probing. */
    data class PaymentConstraints(val maxCltvExpiry: CltvExpiry, val minAmount: MilliSatoshi) : RouteBlindingEncryptedDataTlv() {
        override val tag: Long get() = PaymentConstraints.tag
        override fun write(out: Output) {
            LightningCodecs.writeU32(maxCltvExpiry.toLong().toInt(), out)
            LightningCodecs.writeTU64(minAmount.msat, out)
        }

        companion object : TlvValueReader<PaymentConstraints> {
            const val tag: Long = 12
            override fun read(input: Input): PaymentConstraints {
                val maxCltvExpiry = CltvExpiry(LightningCodecs.u32(input).toLong())
                val minAmount = MilliSatoshi(LightningCodecs.tu64(input))
                return PaymentConstraints(maxCltvExpiry, minAmount)
            }
        }
    }

    /**
     * Blinded routes constrain the features that can be used by relaying nodes to prevent probing.
     * Without this mechanism nodes supporting features that aren't widely supported could easily be identified.
     */
    data class AllowedFeatures(val features: Features) : RouteBlindingEncryptedDataTlv() {
        override val tag: Long get() = AllowedFeatures.tag
        override fun write(out: Output) = LightningCodecs.writeBytes(features.toByteArray(), out)

        companion object : TlvValueReader<AllowedFeatures> {
            const val tag: Long = 14
            override fun read(input: Input): AllowedFeatures =
                AllowedFeatures(Features(LightningCodecs.bytes(input, input.availableBytes)))
        }
    }
}

data class RouteBlindingEncryptedData(val records: TlvStream<RouteBlindingEncryptedDataTlv>) {
    val nextNodeId = records.get<RouteBlindingEncryptedDataTlv.OutgoingNodeId>()?.nodeId
    val outgoingChannelId = records.get<RouteBlindingEncryptedDataTlv.OutgoingChannelId>()?.shortChannelId
    val pathId = records.get<RouteBlindingEncryptedDataTlv.PathId>()?.data
    val nextBlindingOverride = records.get<RouteBlindingEncryptedDataTlv.NextBlinding>()?.blinding

    fun write(out: Output) = tlvSerializer.write(records, out)

    fun write(): ByteArray {
        val out = ByteArrayOutput()
        write(out)
        return out.toByteArray()
    }

    companion object {
        private val tlvSerializer = TlvStreamSerializer(
            false, @Suppress("UNCHECKED_CAST") mapOf(
                RouteBlindingEncryptedDataTlv.Padding.tag to RouteBlindingEncryptedDataTlv.Padding as TlvValueReader<RouteBlindingEncryptedDataTlv>,
                RouteBlindingEncryptedDataTlv.OutgoingChannelId.tag to RouteBlindingEncryptedDataTlv.OutgoingChannelId as TlvValueReader<RouteBlindingEncryptedDataTlv>,
                RouteBlindingEncryptedDataTlv.OutgoingNodeId.tag to RouteBlindingEncryptedDataTlv.OutgoingNodeId as TlvValueReader<RouteBlindingEncryptedDataTlv>,
                RouteBlindingEncryptedDataTlv.PathId.tag to RouteBlindingEncryptedDataTlv.PathId as TlvValueReader<RouteBlindingEncryptedDataTlv>,
                RouteBlindingEncryptedDataTlv.NextBlinding.tag to RouteBlindingEncryptedDataTlv.NextBlinding as TlvValueReader<RouteBlindingEncryptedDataTlv>,
                RouteBlindingEncryptedDataTlv.PaymentRelay.tag to RouteBlindingEncryptedDataTlv.PaymentRelay as TlvValueReader<RouteBlindingEncryptedDataTlv>,
                RouteBlindingEncryptedDataTlv.PaymentConstraints.tag to RouteBlindingEncryptedDataTlv.PaymentConstraints as TlvValueReader<RouteBlindingEncryptedDataTlv>,
                RouteBlindingEncryptedDataTlv.AllowedFeatures.tag to RouteBlindingEncryptedDataTlv.AllowedFeatures as TlvValueReader<RouteBlindingEncryptedDataTlv>,
            )
        )

        fun read(input: Input): RouteBlindingEncryptedData = RouteBlindingEncryptedData(tlvSerializer.read(input))

        fun read(bytes: ByteArray): RouteBlindingEncryptedData = read(ByteArrayInput(bytes))
    }
}