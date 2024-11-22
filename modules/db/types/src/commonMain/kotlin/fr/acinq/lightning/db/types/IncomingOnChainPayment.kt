@file:UseSerializers(
    ByteVector32Serializer::class,
    TxIdSerializer::class,
    SatoshiSerializer::class,
    MilliSatoshiSerializer::class,
    UUIDSerializer::class,
    OutpointSerializer::class,
)

package fr.acinq.lightning.db.types

import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.OutPoint
import fr.acinq.bitcoin.Satoshi
import fr.acinq.bitcoin.TxId
import fr.acinq.lightning.MilliSatoshi
import fr.acinq.lightning.db.serializers.ByteVector32Serializer
import fr.acinq.lightning.db.serializers.TxIdSerializer
import fr.acinq.lightning.db.serializers.*
import fr.acinq.lightning.utils.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers

@Serializable
internal sealed class IncomingOnChainPayment : IncomingPayment() {
    @Serializable
    sealed class NewChannelIncomingPayment : IncomingOnChainPayment() {
        @Serializable
        data class V0(
            val id: UUID,
            val amountReceived: MilliSatoshi,
            val serviceFee: MilliSatoshi,
            val miningFee: Satoshi,
            val channelId: ByteVector32,
            val txId: TxId,
            val localInputs: Set<OutPoint>,
            val createdAt: Long,
            val confirmedAt: Long?,
            val lockedAt: Long?
        ) : NewChannelIncomingPayment()
    }

    @Serializable
    sealed class SpliceInIncomingPayment : IncomingOnChainPayment() {
        @Serializable
        data class V0(
            val id: UUID,
            val amountReceived: MilliSatoshi,
            val miningFee: Satoshi,
            val channelId: ByteVector32,
            val txId: TxId,
            val localInputs: Set<OutPoint>,
            val createdAt: Long,
            val confirmedAt: Long?,
            val lockedAt: Long?
        ) : SpliceInIncomingPayment()
    }
}