package fr.acinq.lightning.wire

import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.PublicKey
import fr.acinq.bitcoin.io.Input
import fr.acinq.bitcoin.io.Output
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

/** Tlv types used inside Init messages. */
@OptIn(ExperimentalUnsignedTypes::class)
@Serializable
sealed class InitTlv : Tlv {
    @Serializable
    data class Networks(val chainHashes: List<@Contextual ByteVector32>) : InitTlv() {
        override val tag: Long get() = Networks.tag

        override fun write(out: Output) {
            chainHashes.forEach { LightningCodecs.writeBytes(it, out) }
        }

        companion object : TlvValueReader<Networks> {
            const val tag: Long = 1

            override fun read(input: Input): Networks {
                val networks = ArrayList<ByteVector32>()
                // README: it's up to the caller to make sure that the input stream will EOF when there are no more hashes to read!
                while (input.availableBytes > 0) {
                    networks.add(ByteVector32(LightningCodecs.bytes(input, 32)))
                }
                return Networks(networks.toList())
            }
        }
    }

    @Serializable
    data class PhoenixAndroidLegacyNodeId(@Contextual val legacyNodeId: PublicKey) : InitTlv() {
        override val tag: Long get() = PhoenixAndroidLegacyNodeId.tag

        override fun write(out: Output) {
            LightningCodecs.writeBytes(legacyNodeId.value, out)
        }

        companion object : TlvValueReader<PhoenixAndroidLegacyNodeId> {
            const val tag: Long = 0x47020001

            override fun read(input: Input): PhoenixAndroidLegacyNodeId {
                val legacyNodeId = PublicKey(LightningCodecs.bytes(input, 33))
                return PhoenixAndroidLegacyNodeId(legacyNodeId)
            }
        }
    }
}
