@file:UseSerializers(
    ByteVector32Serializer::class,
    TxIdSerializer::class,
    SatoshiSerializer::class,
    MilliSatoshiSerializer::class,
    UUIDSerializer::class,
    OutpointSerializer::class,
)

package fr.acinq.lightning.db.types

import fr.acinq.lightning.MilliSatoshi
import fr.acinq.lightning.db.sqlite.serializers.*
import fr.acinq.lightning.utils.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers

@Serializable
internal sealed class IncomingLegacySwapInPayment {
    @Serializable
    data class V0(
        val id: UUID,
        val amountReceived: MilliSatoshi,
        val fees: MilliSatoshi,
        val address: String?,
        val createdAt: Long,
        val completedAt: Long?
    ) : IncomingLegacySwapInPayment()
}