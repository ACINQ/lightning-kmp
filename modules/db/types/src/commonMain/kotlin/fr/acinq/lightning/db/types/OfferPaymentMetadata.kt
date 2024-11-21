@file:UseSerializers(
    ByteVector32Serializer::class,
    MilliSatoshiSerializer::class,
    PublicKeySerializer::class,
)

package fr.acinq.lightning.db.types

import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.PublicKey
import fr.acinq.lightning.MilliSatoshi
import fr.acinq.lightning.db.serializers.ByteVector32Serializer
import fr.acinq.lightning.db.serializers.PublicKeySerializer
import fr.acinq.lightning.db.serializers.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers

@Serializable
internal sealed class OfferPaymentMetadata {
    /** The corresponding core type is already versioned */
    @Serializable
    data class V1(
        val offerId: ByteVector32,
        val amount: MilliSatoshi,
        val preimage: ByteVector32,
        val payerKey: PublicKey,
        val payerNote: String?,
        val quantity: Long,
        val createdAtMillis: Long
    ) : OfferPaymentMetadata()
}