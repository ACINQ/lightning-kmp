package fr.acinq.lightning.serialization

import fr.acinq.lightning.channel.PersistedChannelState
import fr.acinq.lightning.serialization.v4.Deserialization.fromBinV4
import fr.acinq.lightning.serialization.v4.Serialization.serialize
import fr.acinq.lightning.utils.runTrying

class SerializedChannelState(val versionByte: Byte, val stateByte: Byte, val data: ByteArray)

object Serialization {

    fun serialize(state: PersistedChannelState): SerializedChannelState {
        return state.serialize()
    }

    fun deserialize(bin: ByteArray): PersistedChannelState {
        return runTrying {
            bin.fromBinV4()
        }.recoverWith {
            runTrying { fr.acinq.lightning.serialization.v3.Serialization.deserialize(bin) }
        }.recoverWith {
            runTrying { fr.acinq.lightning.serialization.v2.Serialization.deserialize(bin) }
        }.get()
    }

}