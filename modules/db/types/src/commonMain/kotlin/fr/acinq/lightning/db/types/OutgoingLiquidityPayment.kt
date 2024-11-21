@file:UseSerializers(
    ByteVectorSerializer::class,
    ByteVector32Serializer::class,
    ByteVector64Serializer::class,
    SatoshiSerializer::class,
    MilliSatoshiSerializer::class,
    UUIDSerializer::class,
    TxIdSerializer::class,
)

package fr.acinq.lightning.db.types

import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.Satoshi
import fr.acinq.bitcoin.TxId
import fr.acinq.lightning.db.serializers.ByteVector32Serializer
import fr.acinq.lightning.db.serializers.ByteVector64Serializer
import fr.acinq.lightning.db.serializers.ByteVectorSerializer
import fr.acinq.lightning.db.serializers.TxIdSerializer
import fr.acinq.lightning.db.serializers.*
import fr.acinq.lightning.utils.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers

/**
 * 1) copy class definition from core
 * 2) change visibility to internal and move contents to a Vx subclass
 * 3) remove overrides, visibility modifiers, default values, functions
 * 4) add a converter from/to core type
 */
@Serializable
internal sealed class OutgoingLiquidityPayment {
    @Serializable
    data class V0(
        val id: UUID,
        val channelId: ByteVector32,
        val txId: TxId,
        val localMiningFees: Satoshi,
        val purchase: LiquidityAds.Purchase,
        val createdAt: Long,
        val confirmedAt: Long?,
        val lockedAt: Long?,
    ) : OutgoingLiquidityPayment()
}