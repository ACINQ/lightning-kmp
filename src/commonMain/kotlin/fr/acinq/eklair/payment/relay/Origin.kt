package fr.acinq.eklair.payment.relay

import fr.acinq.bitcoin.ByteVector32
import fr.acinq.eklair.MilliSatoshi
import fr.acinq.eklair.io.ByteVector32KSerializer
import fr.acinq.eklair.utils.UUID
import kotlinx.serialization.Serializable

@Serializable
sealed class Origin {
    /** Our node is the origin of the payment. */
    @Serializable
    data class Local(val id: UUID) : Origin() // we don't persist reference to local actors

    /** Our node forwarded a single incoming HTLC to an outgoing channel. */
    @Serializable
    data class Relayed(@Serializable(with = ByteVector32KSerializer::class) val originChannelId: ByteVector32, val originHtlcId: Long, val amountIn: MilliSatoshi, val amountOut: MilliSatoshi) : Origin()

    /**
     * Our node forwarded an incoming HTLC set to a remote outgoing node (potentially producing multiple downstream HTLCs).
     *
     * @param origins       origin channelIds and htlcIds.
     */
    @Serializable
    data class TrampolineRelayed(val origins: List<Pair<@Serializable(with = ByteVector32KSerializer::class) ByteVector32, Long>>) : Origin()

}
