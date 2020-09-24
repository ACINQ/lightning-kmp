package fr.acinq.eclair.wire

import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.io.Input
import fr.acinq.bitcoin.io.Output
import fr.acinq.eclair.channel.ChannelVersion
import fr.acinq.eclair.io.ByteVectorKSerializer
import fr.acinq.eclair.utils.BitField
import kotlinx.serialization.Serializable

@OptIn(ExperimentalUnsignedTypes::class)
@Serializable
sealed class ChannelTlv : Tlv {
    /** Commitment to where the funds will go in case of a mutual close, which remote node will enforce in case we're compromised. */
    @Serializable
    data class UpfrontShutdownScript(@Serializable(with = ByteVectorKSerializer::class) val scriptPubkey: ByteVector) : ChannelTlv(), LightningSerializable<UpfrontShutdownScript> {
        override fun serializer(): LightningSerializer<UpfrontShutdownScript> = UpfrontShutdownScript

        val isEmpty: Boolean get() = scriptPubkey.isEmpty()
        override val tag: Long
            get() = serializer().tag

        companion object : LightningSerializer<UpfrontShutdownScript>() {
            override val tag: Long
                get() = 0L

            override fun read(input: Input): UpfrontShutdownScript {
                val len = input.availableBytes
                val script = bytes(input, len)
                return UpfrontShutdownScript(ByteVector(script))
            }

            override fun write(message: UpfrontShutdownScript, out: Output) {
                writeBytes(message.scriptPubkey, out)
            }
        }
    }

    @Serializable
    data class ChannelVersionTlvLegacy(val channelVersion: ChannelVersion) : ChannelTlv(), LightningSerializable<ChannelVersionTlvLegacy> {
        override fun serializer(): LightningSerializer<ChannelVersionTlvLegacy> = ChannelVersionTlvLegacy
        override val tag: Long
            get() = serializer().tag

        companion object: LightningSerializer<ChannelVersionTlvLegacy>() {
            override val tag: Long
                get() = 0x47000000L

            // ChannelVersion is not a real TLV, it's missing a L field
            override val willHandleLength = true

            override fun read(input: Input): ChannelVersionTlvLegacy {
                val len = 4 // len is missing, value is always 4 bytes
                val buffer = bytes(input, len)
                return ChannelVersionTlvLegacy(ChannelVersion(BitField.from(buffer)))
            }

            override fun write(message: ChannelVersionTlvLegacy, out: Output) {
                writeBigSize(4L, out)
                writeBytes(message.channelVersion.bits.bytes, out)
            }
        }
    }

    @Serializable
    data class ChannelVersionTlv(val channelVersion: ChannelVersion) : ChannelTlv(), LightningSerializable<ChannelVersionTlv> {
        override fun serializer(): LightningSerializer<ChannelVersionTlv> = ChannelVersionTlv
        override val tag: Long
            get() = serializer().tag

        companion object: LightningSerializer<ChannelVersionTlv>() {
            override val tag: Long
                get() = 0x47000001L

            override val willHandleLength = false

            override fun read(input: Input): ChannelVersionTlv {
                val len = input.availableBytes
                val buffer = bytes(input, len)
                return ChannelVersionTlv(ChannelVersion(BitField.from(buffer)))
            }

            override fun write(message: ChannelVersionTlv, out: Output) {
                out.write(message.channelVersion.bits.bytes)
            }
        }
    }
}
