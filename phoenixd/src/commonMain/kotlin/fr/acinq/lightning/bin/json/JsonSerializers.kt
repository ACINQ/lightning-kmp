@file:UseSerializers(
    // This is used by Kotlin at compile time to resolve serializers (defined in this file)
    // in order to build serializers for other classes (also defined in this file).
    // If we used @Serializable annotations directly on the actual classes, Kotlin would be
    // able to resolve serializers by itself. It is verbose, but it allows us to contain
    // serialization code in this file.
    JsonSerializers.SatoshiSerializer::class,
)

package fr.acinq.lightning.bin.json

import fr.acinq.bitcoin.Satoshi
import fr.acinq.lightning.json.JsonSerializers
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers


@Serializable
data class Balance(val amount: Satoshi)