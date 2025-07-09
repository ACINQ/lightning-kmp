package fr.acinq.lightning.serialization.channel

import fr.acinq.bitcoin.crypto.Pack
import fr.acinq.lightning.channel.states.PersistedChannelState

object Serialization {

    fun serialize(state: PersistedChannelState): ByteArray {
        return fr.acinq.lightning.serialization.channel.v4.Serialization.serialize(state)
    }

    fun serializePeerStorage(states: List<PersistedChannelState>): Pair<Byte, ByteArray> {
        return Pair(4, fr.acinq.lightning.serialization.channel.v4.Serialization.serializePeerStorage(states))
    }

    fun deserialize(bin: ByteArray): DeserializationResult {
        return when {
            // v4 uses a 1-byte version discriminator
            bin[0].toInt() == 4 -> DeserializationResult.Success(fr.acinq.lightning.serialization.channel.v4.Deserialization.deserialize(bin))
            // v2/v3 used a 4-bytes version discriminator and are now unsupported
            Pack.int32BE(bin) == 3 -> DeserializationResult.UnknownVersion(3)
            Pack.int32BE(bin) == 2 -> DeserializationResult.UnknownVersion(2)
            else -> DeserializationResult.UnknownVersion(bin[0].toInt())
        }
    }

    fun deserializePeerStorage(versionByte: Byte, bin: ByteArray): PeerStorageDeserializationResult {
        return when(versionByte.toInt()) {
            4 -> PeerStorageDeserializationResult.Success(fr.acinq.lightning.serialization.channel.v4.Deserialization.deserializePeerStorage(bin))
            else -> PeerStorageDeserializationResult.UnknownVersion(versionByte.toInt())
        }
    }

    sealed class DeserializationResult {
        data class Success(val state: PersistedChannelState) : DeserializationResult()
        data class UnknownVersion(val version: Int) : DeserializationResult()
    }

    sealed class PeerStorageDeserializationResult {
        data class Success(val states: List<PersistedChannelState>) : PeerStorageDeserializationResult()
        data class UnknownVersion(val version: Int) : PeerStorageDeserializationResult()

    }
}