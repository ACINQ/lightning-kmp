package fr.acinq.lightning

import fr.acinq.bitcoin.PublicKey
import fr.acinq.bitcoin.io.Input
import fr.acinq.bitcoin.io.Output
import fr.acinq.lightning.wire.LightningCodecs

sealed interface NodeId {
    fun write(out: Output)

    data class Standard(val publicKey: PublicKey) : NodeId {
        override fun write(out: Output) {
            LightningCodecs.writeBytes(publicKey.value, out)
        }
    }

    data class ShortChannelIdDir(val isNode1: Boolean, val scid: ShortChannelId) : NodeId {
        override fun write(out: Output) {
            LightningCodecs.writeByte(if (isNode1) 0 else 1, out)
            LightningCodecs.writeInt64(scid.toLong(), out)
        }
    }

    companion object {
        fun read(input: Input): NodeId {
            val firstByte = LightningCodecs.byte(input)
            if (firstByte == 0 || firstByte == 1) {
                val isNode1 = firstByte == 0
                val scid = ShortChannelId(LightningCodecs.int64(input))
                return ShortChannelIdDir(isNode1, scid)
            } else if (firstByte == 2 || firstByte == 3) {
                val publicKey = PublicKey(ByteArray(1) { firstByte.toByte() } + LightningCodecs.bytes(input, 32))
                return Standard(publicKey)
            } else {
                throw IllegalArgumentException("unexpected first byte: $firstByte")
            }
        }

        operator fun invoke(publicKey: PublicKey): NodeId = Standard(publicKey)
    }
}
