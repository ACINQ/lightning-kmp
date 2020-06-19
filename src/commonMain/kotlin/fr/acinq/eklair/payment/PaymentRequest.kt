package fr.acinq.eklair.payment

import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.PublicKey
import fr.acinq.eklair.CltvExpiryDelta
import fr.acinq.eklair.MilliSatoshi
import fr.acinq.eklair.ShortChannelId

data class PaymentRequest(val prefix: String, val amount: MilliSatoshi?, val timestamp: Long, val nodeId: PublicKey, val tags: List<TaggedField>, val signature: ByteVector) {
    companion object {
        sealed class TaggedField {
            /**
             * Extra hop contained in RoutingInfoTag
             *
             * @param nodeId                    start of the channel
             * @param shortChannelId            channel id
             * @param feeBase                   node fixed fee
             * @param feeProportionalMillionths node proportional fee
             * @param cltvExpiryDelta           node cltv expiry delta
             */
            data class ExtraHop(val nodeId: PublicKey, val shortChannelId: ShortChannelId, val feeBase: MilliSatoshi, val feeProportionalMillionths: Long, val cltvExpiryDelta: CltvExpiryDelta)

            /**
             * Routing Info
             *
             * @param path one or more entries containing extra routing information for a private route
             */
            data class RoutingInfo(val path: List<ExtraHop>) : TaggedField()
        }
    }
}