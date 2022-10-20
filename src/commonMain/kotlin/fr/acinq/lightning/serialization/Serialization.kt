package fr.acinq.lightning.serialization

import fr.acinq.lightning.channel.ChannelContext
import fr.acinq.lightning.channel.ChannelStateWithCommitments
import fr.acinq.lightning.utils.runTrying

object Serialization {

    fun serialize(ctx: ChannelContext, state: ChannelStateWithCommitments): ByteArray {
        return fr.acinq.lightning.serialization.v3.Serialization.serialize(ctx, state)
    }

    fun deserialize(bin: ByteArray): ChannelStateWithCommitments {
        return runTrying {
            fr.acinq.lightning.serialization.v3.Serialization.deserialize(bin)
        }.recoverWith {
            runTrying { fr.acinq.lightning.serialization.v2.Serialization.deserialize(bin) }
        }.get()
    }

}