package fr.acinq.lightning.utils

import fr.acinq.lightning.channel.PersistedChannelState
import fr.acinq.lightning.serialization.Serialization

val Serialization.DeserializationResult.value: PersistedChannelState
    get() = (this as Serialization.DeserializationResult.Success).state