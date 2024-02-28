@file:UseSerializers(
    // This is used by Kotlin at compile time to resolve serializers (defined in this file)
    // in order to build serializers for other classes (also defined in this file).
    // If we used @Serializable annotations directly on the actual classes, Kotlin would be
    // able to resolve serializers by itself. It is verbose, but it allows us to contain
    // serialization code in this file.
    JsonSerializers.SatoshiSerializer::class,
    JsonSerializers.MilliSatoshiSerializer::class,
    JsonSerializers.ByteVector32Serializer::class,
)

package fr.acinq.lightning.bin.json

import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.Satoshi
import fr.acinq.lightning.MilliSatoshi
import fr.acinq.lightning.json.JsonSerializers
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers

@Serializable
data class Balance(val amount: Satoshi)

@Serializable
data class PaymentReceived(@SerialName("amountMsat") val amount: MilliSatoshi, val paymentHash: ByteVector32) {
    constructor(event: fr.acinq.lightning.io.PaymentReceived) : this(event.received.amount, event.incomingPayment.paymentHash)
}

@Serializable
data class GeneratedInvoice(@SerialName("amountMsat") val amount: MilliSatoshi?, val paymentHash: ByteVector32, val serialized: String)