package fr.acinq.eklair.wire

import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.io.Input
import fr.acinq.bitcoin.io.Output

/** Tlv types used inside Init messages. */
@kotlin.ExperimentalUnsignedTypes
sealed class InitTlv : Tlv {
    companion object {
        data class Networks(val chainHashes: List<ByteVector32>) : InitTlv(), LightningSerializable<Networks> {
            override fun serializer(): LightningSerializer<Networks> = Networks
            override val tag: ULong
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

                override val tag: ULong
                    get() = 1UL
            }
        }
    }
}
