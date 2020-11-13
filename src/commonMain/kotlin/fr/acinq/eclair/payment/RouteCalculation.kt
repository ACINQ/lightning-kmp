package fr.acinq.eclair.payment

import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.PublicKey
import fr.acinq.bitcoin.Satoshi
import fr.acinq.eclair.CltvExpiryDelta
import fr.acinq.eclair.MilliSatoshi
import fr.acinq.eclair.channel.ChannelState
import fr.acinq.eclair.channel.Normal
import fr.acinq.eclair.utils.*

object RouteCalculation {

    data class TrampolineParams(val nodeId: PublicKey, val attempts: List<TrampolineFees>)

    /**
     * When we send a trampoline payment, we start with a low fee.
     * If that fails, we increase the fee(s) and retry (up to a point).
     * This class encapsulates the fees and expiry to use at a particular retry.
     */
    data class TrampolineFees(val feeBase: Satoshi, val feePercent: Float, val cltvExpiryDelta: CltvExpiryDelta) {
        fun calculateFees(recipientAmount: MilliSatoshi): MilliSatoshi = feeBase.toMilliSatoshi() + (recipientAmount * feePercent)
    }

    // TODO: fetch this from the server, and have it passed to the OutgoingPaymentHandler
    val defaultTrampolineFees = listOf(
        TrampolineFees(0.sat, 0.0f, CltvExpiryDelta(576)),
        TrampolineFees(1.sat, 0.0001f, CltvExpiryDelta(576)),
        TrampolineFees(3.sat, 0.0001f, CltvExpiryDelta(576)),
        TrampolineFees(5.sat, 0.0005f, CltvExpiryDelta(576)),
        TrampolineFees(5.sat, 0.001f, CltvExpiryDelta(576)),
        TrampolineFees(5.sat, 0.0012f, CltvExpiryDelta(576))
    )

    data class Route(val amount: MilliSatoshi, val channel: Normal)

    private val logger = newEclairLogger()

    fun findRoutes(amount: MilliSatoshi, channels: Map<ByteVector32, ChannelState>): Either<FinalFailure, List<Route>> {
        data class ChannelBalance(val c: Normal) {
            val balance: MilliSatoshi = c.commitments.availableBalanceForSend()
            val capacity: Satoshi = c.commitments.commitInput.txOut.amount
        }

        val sortedChannels = channels.values.filterIsInstance<Normal>().map { ChannelBalance(it) }.sortedBy { it.balance }.reversed()
        if (sortedChannels.isEmpty()) {
            logger.warning { "no available channels" }
            return Either.Left(FinalFailure.NoAvailableChannels)
        }

        val filteredChannels = sortedChannels.filter { it.balance >= it.c.channelUpdate.htlcMinimumMsat }
        var remaining = amount
        val routes = mutableListOf<Route>()
        for (channel in filteredChannels) {
            val toSend = channel.balance.min(remaining)
            routes.add(Route(toSend, channel.c))
            remaining -= toSend
            if (remaining == 0.msat) {
                break
            }
        }

        return if (remaining > 0.msat) {
            logger.info { "insufficient balance: ${sortedChannels.joinToString { "${it.c.shortChannelId}->${it.balance}/${it.capacity}" }}" }
            Either.Left(FinalFailure.InsufficientBalance)
        } else {
            logger.info { "routes found: ${routes.map { "${it.channel.shortChannelId}->${it.amount}" }}" }
            Either.Right(routes)
        }
    }

}