@file:UseSerializers(
    ByteVectorSerializer::class,
    ByteVector32Serializer::class,
    MilliSatoshiSerializer::class,
    UUIDSerializer::class,
)

package fr.acinq.lightning.db.types

import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.lightning.MilliSatoshi
import fr.acinq.lightning.db.serializers.ByteVector32Serializer
import fr.acinq.lightning.db.serializers.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers

@Serializable
internal sealed class IncomingLightningPayment : IncomingPayment() {

    @Serializable
    sealed class Received {
        @Serializable
        data class V0(val parts: List<Part>, val receivedAt: Long) : Received()

        @Serializable
        sealed class Part {
            @Serializable
            sealed class Htlc : Part() {
                @Serializable
                data class V0(val amountReceived: MilliSatoshi, val channelId: ByteVector32, val htlcId: Long, val fundingFee: LiquidityAds.FundingFee?) : Htlc()
            }

            @Serializable
            sealed class FeeCredit : Part() {
                @Serializable
                data class V0(val amountReceived: MilliSatoshi) : FeeCredit()
            }
        }
    }

    @Serializable
    sealed class IncomingBolt11Payment : IncomingLightningPayment() {
        @Serializable
        data class V0(
            val preimage: ByteVector32,
            val paymentRequest: String,
            val received: Received?,
            val createdAt: Long
        ) : IncomingBolt11Payment()
    }

    @Serializable
    sealed class IncomingBolt12Payment : IncomingLightningPayment() {
        @Serializable
        data class V0(
            val preimage: ByteVector32,
            val encodedMetadata: ByteVector,
            val received: Received?,
            val createdAt: Long
        ) : IncomingBolt12Payment()
    }
}