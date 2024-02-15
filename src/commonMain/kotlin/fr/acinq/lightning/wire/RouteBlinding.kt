package fr.acinq.lightning.wire

import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.PublicKey
import fr.acinq.bitcoin.io.ByteArrayInput
import fr.acinq.bitcoin.io.ByteArrayOutput
import fr.acinq.bitcoin.io.Input
import fr.acinq.bitcoin.io.Output
import fr.acinq.lightning.EncodedNodeId


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
}

data class RouteBlindingEncryptedData(val records: TlvStream<RouteBlindingEncryptedDataTlv>) {
    val nextNodeId = records.get<RouteBlindingEncryptedDataTlv.OutgoingNodeId>()?.nodeId
    val pathId = records.get<RouteBlindingEncryptedDataTlv.PathId>()?.data
    val nextBlindingOverride = records.get<RouteBlindingEncryptedDataTlv.NextBlinding>()?.blinding

    fun write(out: Output) = tlvSerializer.write(records, out)

    fun write(): ByteArray {
        val out = ByteArrayOutput()
        write(out)
        return out.toByteArray()
    }

    companion object {
        val tlvSerializer = TlvStreamSerializer(
            false, @Suppress("UNCHECKED_CAST") mapOf(
                RouteBlindingEncryptedDataTlv.Padding.tag to RouteBlindingEncryptedDataTlv.Padding.Companion as TlvValueReader<RouteBlindingEncryptedDataTlv>,
                RouteBlindingEncryptedDataTlv.OutgoingNodeId.tag to RouteBlindingEncryptedDataTlv.OutgoingNodeId.Companion as TlvValueReader<RouteBlindingEncryptedDataTlv>,
                RouteBlindingEncryptedDataTlv.PathId.tag to RouteBlindingEncryptedDataTlv.PathId.Companion as TlvValueReader<RouteBlindingEncryptedDataTlv>,
                RouteBlindingEncryptedDataTlv.NextBlinding.tag to RouteBlindingEncryptedDataTlv.NextBlinding.Companion as TlvValueReader<RouteBlindingEncryptedDataTlv>,
            )
        )

        fun read(input: Input): RouteBlindingEncryptedData = RouteBlindingEncryptedData(tlvSerializer.read(input))

        fun read(bytes: ByteArray): RouteBlindingEncryptedData = read(ByteArrayInput(bytes))
    }
}