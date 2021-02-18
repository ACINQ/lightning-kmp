package fr.acinq.eclair.wire

import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.io.Input
import fr.acinq.bitcoin.io.Output
import fr.acinq.eclair.channel.ChannelOrigin
import fr.acinq.eclair.channel.ChannelVersion
import fr.acinq.eclair.serialization.ByteVectorKSerializer
import fr.acinq.eclair.utils.BitField
import kotlinx.serialization.Serializable

@OptIn(ExperimentalUnsignedTypes::class)
@Serializable
sealed class ChannelTlv : Tlv {
    /** Commitment to where the funds will go in case of a mutual close, which remote node will enforce in case we're compromised. */
    @Serializable
    data class UpfrontShutdownScript(@Serializable(with = ByteVectorKSerializer::class) val scriptPubkey: ByteVector) : ChannelTlv() {
        val isEmpty: Boolean get() = scriptPubkey.isEmpty()

        override val tag: Long get() = UpfrontShutdownScript.tag

        override fun write(out: Output) {
            LightningCodecs.writeBytes(scriptPubkey, out)
        }

        companion object : TlvValueReader<UpfrontShutdownScript> {
            const val tag: Long = 0

            override fun read(input: Input): UpfrontShutdownScript {
                val len = input.availableBytes
                val script = LightningCodecs.bytes(input, len)
                return UpfrontShutdownScript(ByteVector(script))
            }
        }
    }

    @Serializable
    data class ChannelVersionTlv(val channelVersion: ChannelVersion) : ChannelTlv() {
        override val tag: Long get() = ChannelVersionTlv.tag

        override fun write(out: Output) {
            LightningCodecs.writeBytes(channelVersion.bits.bytes, out)
        }

        companion object : TlvValueReader<ChannelVersionTlv> {
            const val tag: Long = 0x47000001

            override fun read(input: Input): ChannelVersionTlv {
                val len = input.availableBytes
                val buffer = LightningCodecs.bytes(input, len)
                return ChannelVersionTlv(ChannelVersion(BitField.from(buffer)))
            }
        }
    }

    @Serializable
    data class ChannelOriginTlv(val channelOrigin: ChannelOrigin) : ChannelTlv() {
        override val tag: Long get() = ChannelOriginTlv.tag

        override fun write(out: Output) {
            TODO("Not implemented (not needed)")
        }

        companion object : TlvValueReader<ChannelOriginTlv> {
            const val tag: Long = 0x47000003

            override fun read(input: Input): ChannelOriginTlv {
                val origin = when(LightningCodecs.u16(input)) {
                    1 -> ChannelOrigin.PayToOpenOrigin(ByteVector32(LightningCodecs.bytes(input, 32)))
                    2 -> {
                        val len = LightningCodecs.bigSize(input)
                        ChannelOrigin.SwapInOrigin(LightningCodecs.bytes(input, len).decodeToString())
                    }
                    else -> TODO("Unsupported channel origin discriminator")
                }
                return ChannelOriginTlv(origin)
            }
        }
    }
}
