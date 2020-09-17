package fr.acinq.eclair.io

import fr.acinq.eclair.channel.HasCommitments
import fr.acinq.eclair.crypto.KeyManager
import fr.acinq.eclair.wire.Tlv
import fr.acinq.eclair.wire.UpdateMessage
import kotlinx.serialization.modules.SerializersModule


val eclairSerializersModule = SerializersModule {
    include(HasCommitments.serializersModule)
    include(KeyManager.serializersModule)
    include(Tlv.serializersModule)
    include(UpdateMessage.serializersModule)
}
