package fr.acinq.lightning.serialization

import fr.acinq.lightning.channel.ChannelStateWithCommitments
import fr.acinq.lightning.serialization.v4.Deserialization.fromBinV4
import fr.acinq.lightning.serialization.v4.Serialization.toBinV4
import fr.acinq.lightning.utils.runTrying

object Serialization {

    fun serialize(state: ChannelStateWithCommitments): ByteArray {
        return state.toBinV4()
    }

    fun deserialize(bin: ByteArray): ChannelStateWithCommitments {
        return runTrying {
            bin.fromBinV4()
        }.recoverWith {
            runTrying { fr.acinq.lightning.serialization.v3.Serialization.deserialize(bin) }
        }.recoverWith {
            runTrying { fr.acinq.lightning.serialization.v2.Serialization.deserialize(bin) }
        }.get()
    }

}