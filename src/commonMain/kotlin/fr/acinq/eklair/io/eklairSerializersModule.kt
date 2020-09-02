package fr.acinq.eklair.io

import fr.acinq.eklair.channel.HasCommitments
import fr.acinq.eklair.crypto.KeyManager
import fr.acinq.eklair.wire.Tlv
import fr.acinq.eklair.wire.UpdateMessage
import kotlinx.serialization.modules.SerializersModule


val eklairSerializersModule = SerializersModule {
    include(HasCommitments.serializersModule)
    include(KeyManager.serializersModule)
    include(Tlv.serializersModule)
    include(UpdateMessage.serializersModule)
}
