package fr.acinq.lightning.wire

import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.PublicKey
import fr.acinq.bitcoin.io.ByteArrayInput
import fr.acinq.bitcoin.io.ByteArrayOutput
import fr.acinq.bitcoin.io.Input
import fr.acinq.bitcoin.io.Output
import fr.acinq.lightning.crypto.RouteBlinding


sealed class OnionMessagePayloadTlv : Tlv {
    /**
     * Onion messages may provide a reply path, allowing the recipient to send a message back to the original sender.
     * The reply path uses route blinding, which ensures that the sender doesn't leak its identity to the recipient.
     */
    data class ReplyPath(val blindedRoute: RouteBlinding.BlindedRoute) : OnionMessagePayloadTlv() {
        override val tag: Long get() = ReplyPath.tag
        override fun write(out: Output) {
            LightningCodecs.writeBytes(blindedRoute.introductionNodeId.value, out)
            LightningCodecs.writeBytes(blindedRoute.blindingKey.value, out)
            for (hop in blindedRoute.blindedNodes) {
                LightningCodecs.writeBytes(hop.blindedPublicKey.value, out)
                LightningCodecs.writeU16(hop.encryptedPayload.size(), out)
                LightningCodecs.writeBytes(hop.encryptedPayload, out)
            }
        }

        companion object : TlvValueReader<ReplyPath> {
            const val tag: Long = 2
            override fun read(input: Input): ReplyPath {
                val firstNodeId = PublicKey(LightningCodecs.bytes(input, 33))
                val blinding = PublicKey(LightningCodecs.bytes(input, 33))
                val path = sequence {
                    while (input.availableBytes > 0) {
                        val blindedPublicKey = PublicKey(LightningCodecs.bytes(input, 33))
                        val encryptedPayload = ByteVector(LightningCodecs.bytes(input, LightningCodecs.u16(input)))
                        yield(RouteBlinding.BlindedNode(blindedPublicKey, encryptedPayload))
                    }
                }.toList()
                return ReplyPath(RouteBlinding.BlindedRoute(firstNodeId, blinding, path))
            }
        }
    }

    /**
     * Onion messages always use route blinding, even in the forward direction.
     * This ensures that intermediate nodes can't know whether they're forwarding a message or its reply.
     * The sender must provide some encrypted data for each intermediate node which lets them locate the next node.
     */
    data class EncryptedData(val data: ByteVector) : OnionMessagePayloadTlv() {
        override val tag: Long get() = EncryptedData.tag
        override fun write(out: Output) = LightningCodecs.writeBytes(data, out)

        companion object : TlvValueReader<EncryptedData> {
            const val tag: Long = 4
            override fun read(input: Input): EncryptedData =
                EncryptedData(ByteVector(LightningCodecs.bytes(input, input.availableBytes)))
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