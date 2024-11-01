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
import fr.acinq.lightning.db.sqlite.serializers.*
import fr.acinq.lightning.utils.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers

/**
 * 1) Add Vx subclass
 * 2) copy class definition from core
 * 3) remove overrides
 * 4) add a converter from/to core type
 */
@Serializable
internal sealed class InboundLiquidityOutgoingPayment {
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
    ) : InboundLiquidityOutgoingPayment()
}