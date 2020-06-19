package fr.acinq.eklair.wire

import fr.acinq.bitcoin.ByteVector
import fr.acinq.eklair.channel.ChannelVersion

sealed class ChannelTlv : Tlv {
    companion object {
        /** Commitment to where the funds will go in case of a mutual close, which remote node will enforce in case we're compromised. */
        data class UpfrontShutdownScript(val script: ByteVector) : ChannelTlv() {
            val isEmpty: Boolean = script.isEmpty()
            override val tag: ULong
                get() = 0UL
        }

        data class ChannelVersionTlv(val channelVersion: ChannelVersion) : ChannelTlv() {
            override val tag: ULong
                get() = 0x47000000UL
        }
    }
}
