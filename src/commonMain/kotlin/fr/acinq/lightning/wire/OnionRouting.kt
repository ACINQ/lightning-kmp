package fr.acinq.lightning.wire

import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.io.ByteArrayInput
import fr.acinq.bitcoin.io.ByteArrayOutput
import fr.acinq.bitcoin.io.Input
import fr.acinq.bitcoin.io.Output
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