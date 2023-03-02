package fr.acinq.lightning.serialization

import fr.acinq.bitcoin.crypto.Pack
import fr.acinq.lightning.channel.ChannelStateWithCommitments

object Serialization {

    fun serialize(state: ChannelStateWithCommitments): ByteArray {
        return fr.acinq.lightning.serialization.v4.Serialization.serialize(state)
    }

    fun deserialize(bin: ByteArray): ChannelStateWithCommitments {
        return when {
            // v4 uses a 1-byte version discriminator
            bin[0].toInt() == 4 -> fr.acinq.lightning.serialization.v4.Deserialization.deserialize(bin)
            // v2/v3 use a 4-bytes version discriminator
            Pack.int32BE(bin) == 3 -> fr.acinq.lightning.serialization.v3.Serialization.deserialize(bin)
            Pack.int32BE(bin) == 2 -> fr.acinq.lightning.serialization.v2.Serialization.deserialize(bin)
            else -> error("unknown serialization version")
        }
    }

}