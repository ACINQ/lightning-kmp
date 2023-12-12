package fr.acinq.lightning.payment

import co.touchlab.kermit.Logger
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.Satoshi
import fr.acinq.lightning.MilliSatoshi
import fr.acinq.lightning.channel.states.ChannelState
import fr.acinq.lightning.channel.states.Normal
import fr.acinq.lightning.logging.*
import fr.acinq.lightning.utils.Either
import fr.acinq.lightning.utils.UUID
import fr.acinq.lightning.utils.msat

class RouteCalculation(loggerFactory: Logger) {

    private val logger = loggerFactory.appendingTag("RouteCalculation")

    data class Route(val amount: MilliSatoshi, val channel: Normal)

    fun findRoutes(paymentId: UUID, amount: MilliSatoshi, channels: Map<ByteVector32, ChannelState>): Either<FinalFailure, List<Route>> {
        val logger = MDCLogger(logger, staticMdc = mapOf("paymentId" to paymentId, "amount" to amount))

        data class ChannelBalance(val c: Normal) {
            val balance: MilliSatoshi = c.commitments.availableBalanceForSend()
            val capacity: Satoshi = c.commitments.latest.fundingAmount
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