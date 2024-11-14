@file:UseSerializers(
    ByteVector32Serializer::class,
    MilliSatoshiSerializer::class,
    UUIDSerializer::class,
)

package fr.acinq.lightning.db.types

import fr.acinq.bitcoin.ByteVector32
import fr.acinq.lightning.MilliSatoshi
import fr.acinq.lightning.db.sqlite.serializers.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers

@Serializable
internal sealed class LightningIncomingPayment {

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
    sealed class Bolt11IncomingPayment : LightningIncomingPayment() {
        @Serializable
        data class V0(
            val preimage: ByteVector32,
            val paymentRequest: String,
            val received: Received?,
            val createdAt: Long
        ) : Bolt11IncomingPayment()
    }

    @Serializable
    sealed class Bolt12IncomingPayment : LightningIncomingPayment() {
        @Serializable
        data class V0(
            val preimage: ByteVector32,
            val metadata: OfferPaymentMetadata,
            val received: Received?,
            val createdAt: Long
        ) : Bolt12IncomingPayment()
    }
}