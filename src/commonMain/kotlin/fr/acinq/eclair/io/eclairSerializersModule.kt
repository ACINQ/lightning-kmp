package fr.acinq.eclair.io

import fr.acinq.eclair.channel.ChannelStateWithCommitments
import fr.acinq.eclair.crypto.KeyManager
import fr.acinq.eclair.wire.Tlv
import fr.acinq.eclair.wire.UpdateMessage
import kotlinx.serialization.modules.SerializersModule


val eclairSerializersModule = SerializersModule {
    include(ChannelStateWithCommitments.serializersModule)
    include(KeyManager.serializersModule)
    include(Tlv.serializersModule)
    include(UpdateMessage.serializersModule)
}
