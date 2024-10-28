package fr.acinq.lightning.serialization

import fr.acinq.bitcoin.crypto.Pack
import fr.acinq.lightning.channel.states.PersistedChannelState

object Serialization {

    fun serialize(state: PersistedChannelState): ByteArray {
        return fr.acinq.lightning.serialization.v4.Serialization.serialize(state)
    }

    fun deserialize(bin: ByteArray): DeserializationResult {
        return when {
            // v4 uses a 1-byte version discriminator
            bin[0].toInt() == 4 -> DeserializationResult.Success(fr.acinq.lightning.serialization.v4.Deserialization.deserialize(bin))
            // v2/v3 use a 4-bytes version discriminator
            Pack.int32BE(bin) == 3 -> DeserializationResult.Success(fr.acinq.lightning.serialization.v3.Serialization.deserialize(bin))
            Pack.int32BE(bin) == 2 -> DeserializationResult.Success(fr.acinq.lightning.serialization.v2.Serialization.deserialize(bin))
            else -> DeserializationResult.UnknownVersion(bin[0].toInt())
        }
    }

    sealed class DeserializationResult {
        data class Success(val state: PersistedChannelState) : DeserializationResult()
        data class UnknownVersion(val version: Int) : DeserializationResult()
    }

}