package fr.acinq.eklair.wire

import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.io.Input
import fr.acinq.bitcoin.io.Output
import fr.acinq.eklair.channel.ChannelVersion
import fr.acinq.eklair.utils.BitField

sealed class ChannelTlv : Tlv {
    companion object {
        /** Commitment to where the funds will go in case of a mutual close, which remote node will enforce in case we're compromised. */
        data class UpfrontShutdownScript(val scriptPubkey: ByteVector) : ChannelTlv(), LightningSerializable<UpfrontShutdownScript> {
            override fun serializer(): LightningSerializer<UpfrontShutdownScript> = UpfrontShutdownScript

            val isEmpty: Boolean = scriptPubkey.isEmpty()
            override val tag: ULong
                get() = serializer().tag

            companion object : LightningSerializer<UpfrontShutdownScript>() {
                override val tag: ULong
                    get() = 0UL

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

        data class ChannelVersionTlv(val channelVersion: ChannelVersion) : ChannelTlv(), LightningSerializable<ChannelVersionTlv> {
            override fun serializer(): LightningSerializer<ChannelVersionTlv> = ChannelVersionTlv
            override val tag: ULong
                get() = serializer().tag

            companion object: LightningSerializer<ChannelVersionTlv>() {
                override val tag: ULong
                    get() = 0x47000000UL

                override fun read(input: Input): ChannelVersionTlv {
                    val len = bigSize(input)
                    val buffer = bytes(input, len)
                    return ChannelVersionTlv(ChannelVersion(BitField.from(buffer)))
                }

                override fun write(message: ChannelVersionTlv, out: Output) {
                    TODO()
                }
            }
        }
    }
}
