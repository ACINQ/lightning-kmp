package fr.acinq.eclair.wire

import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.io.Input
import fr.acinq.bitcoin.io.Output
import fr.acinq.eclair.io.ByteVector32KSerializer
import kotlinx.serialization.Serializable

/** Tlv types used inside Init messages. */
@OptIn(ExperimentalUnsignedTypes::class)
@Serializable
sealed class InitTlv : Tlv {
    @Serializable
    data class Networks(val chainHashes: List<@Serializable(with = ByteVector32KSerializer::class) ByteVector32>) : InitTlv(), LightningSerializable<Networks> {
        override fun serializer(): LightningSerializer<Networks> = Networks
        override val tag: Long
            get() = serializer().tag

        companion object : LightningSerializer<Networks>() {
            override fun read(input: Input): Networks {
                val networks = ArrayList<ByteVector32>()
                // README: it's up to the caller to make sure that the input stream will EOF when there are no more hashes to read !
                while (input.availableBytes > 0) {
                    networks.add(ByteVector32(bytes(input, 32)))
                }
                return Networks(networks.toList())
            }

            override fun write(message: Networks, out: Output) {
                message.chainHashes.forEach { writeBytes(it, out) }
            }

            override val tag: Long
                get() = 1L
        }
    }
}
