package fr.acinq.eclair.serialization

import fr.acinq.eclair.wire.Tlv
import fr.acinq.eclair.wire.UpdateMessage
import kotlinx.serialization.modules.SerializersModule


val eclairSerializersModule = SerializersModule {
    include(Serialization.serializersModule)
    include(Tlv.serializersModule)
    include(UpdateMessage.serializersModule)
}
