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
import fr.acinq.bitcoin.Satoshi
import fr.acinq.bitcoin.TxId
import fr.acinq.lightning.MilliSatoshi
import fr.acinq.lightning.db.serializers.ByteVector32Serializer
import fr.acinq.lightning.db.serializers.TxIdSerializer
import fr.acinq.lightning.db.serializers.*
import fr.acinq.lightning.payment.Bolt11Invoice
import fr.acinq.lightning.payment.OfferPaymentMetadata
import fr.acinq.lightning.utils.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers

@Serializable
internal sealed class IncomingLegacyPayToOpenPayment {
    @Serializable
    data class V0(
        val id: UUID,
        val amountReceived: MilliSatoshi,
        val fees: MilliSatoshi,
        val channelId: ByteVector32,
        val paymentPreimage: ByteVector32,
        val origin: Origin,
        val parts: List<Part>,
        val createdAt: Long,
        val completedAt: Long?
    ) : IncomingLegacyPayToOpenPayment() {
        @Serializable
        sealed class Origin {
            data class Invoice(val paymentRequest: Bolt11Invoice) : Origin()
            data class Offer(val metadata: OfferPaymentMetadata) : Origin()
        }

        @Serializable
        sealed class Part {
            data class Lightning(val amount: MilliSatoshi, val channelId: ByteVector32, val htlcId: Long) : Part()
            data class OnChain(val amount: MilliSatoshi, val serviceFee: MilliSatoshi, val miningFee: Satoshi, val channelId: ByteVector32, val txId: TxId, val confirmedAt: Long?, val lockedAt: Long?) : Part()
        }
    }
}