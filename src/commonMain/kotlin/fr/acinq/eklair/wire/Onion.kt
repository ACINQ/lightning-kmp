package fr.acinq.eklair.wire

import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.PublicKey
import fr.acinq.eklair.CltvExpiry
import fr.acinq.eklair.MilliSatoshi
import fr.acinq.eklair.ShortChannelId
import fr.acinq.eklair.crypto.Sphinx
import fr.acinq.eklair.crypto.sphinx.PacketAndSecrets
import fr.acinq.eklair.payment.PaymentRequest
import fr.acinq.eklair.utils.msat

data class OnionRoutingPacket(val version: Int, val publicKey: ByteVector, val payload: ByteVector, val hmac: ByteVector32)

@OptIn(kotlin.ExperimentalUnsignedTypes::class)
sealed class OnionTlv : Tlv {
    /** Amount to forward to the next node. */
    data class AmountToForward(val amount: MilliSatoshi): OnionTlv() {
        override val tag: ULong
            get() = 2UL
    }

    /** CLTV value to use for the HTLC offered to the next node. */
    data class OutgoingCltv(val cltv: CltvExpiry): OnionTlv() {
        override val tag: ULong
            get() = 4UL
    }

    /** Id of the channel to use to forward a payment to the next node. */
    data class OutgoingChannelId(val shortChannelId: ShortChannelId): OnionTlv() {
        override val tag: ULong
            get() = 6UL
    }

    /**
     * Bolt 11 payment details (only included for the last node).
     *
     * @param secret      payment secret specified in the Bolt 11 invoice.
     * @param totalAmount total amount in multi-part payments. When missing, assumed to be equal to AmountToForward.
     */
    data class PaymentData(val secret: ByteVector32, val totalAmount: MilliSatoshi): OnionTlv() {
        override val tag: ULong
            get() = 8UL
    }

    /**
     * Invoice feature bits. Only included for intermediate trampoline nodes when they should convert to a legacy payment
     * because the final recipient doesn't support trampoline.
     */
    data class InvoiceFeatures(val features: ByteVector): OnionTlv() {
        override val tag: ULong
            get() = 66097UL
    }

    /** Id of the next node. */
    data class OutgoingNodeId(val nodeId: PublicKey): OnionTlv() {
        override val tag: ULong
            get() = 66098UL
    }

    /**
     * Invoice routing hints. Only included for intermediate trampoline nodes when they should convert to a legacy payment
     * because the final recipient doesn't support trampoline.
     */
    data class InvoiceRoutingInfo(val extraHops: List<List<PaymentRequest.Companion.TaggedField.ExtraHop>>): OnionTlv() {
        override val tag: ULong
            get() = 66099UL
    }

    /** An encrypted trampoline onion packet. */
    data class TrampolineOnion(val packet: OnionRoutingPacket): OnionTlv() {
        override val tag: ULong
            get() = 66100UL
    }
}

sealed class PacketType {
    abstract fun buildOnion(nodes: List<PublicKey>, payloads: List<PerHopPayload>, associatedData: ByteVector32): PacketAndSecrets
}
sealed class PaymentPacket: PacketType()
sealed class TrampolinePacket: PacketType()

sealed class PerHopPayload

sealed class FinalPayload : PerHopPayload() {
    abstract val amount: MilliSatoshi
    abstract val expiry: CltvExpiry
    abstract val paymentSecret: ByteVector32?
    abstract val totalAmount: MilliSatoshi

}

data class FinalLegacyPayload(override val amount: MilliSatoshi, override val expiry: CltvExpiry) : FinalPayload() {
    override val paymentSecret = null
    override val totalAmount = amount
}

@OptIn(kotlin.ExperimentalUnsignedTypes::class)
data class FinalTlvPayload(val records: TlvStream<OnionTlv>) : FinalPayload() {
    override val amount = records.get<OnionTlv.AmountToForward>()!!.amount
    override val expiry = records.get< OnionTlv.OutgoingCltv>()!!.cltv
    override val paymentSecret = records.get<OnionTlv.PaymentData>()?.secret
    override val totalAmount = records.get<OnionTlv.PaymentData>()?.totalAmount ?: amount
}