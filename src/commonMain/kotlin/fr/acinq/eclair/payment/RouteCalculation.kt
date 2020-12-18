package fr.acinq.eclair.payment

import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.Satoshi
import fr.acinq.eclair.MilliSatoshi
import fr.acinq.eclair.channel.ChannelState
import fr.acinq.eclair.channel.Normal
import fr.acinq.eclair.utils.Either
import fr.acinq.eclair.utils.eclairLogger
import fr.acinq.eclair.utils.getValue
import fr.acinq.eclair.utils.msat
import kotlin.native.concurrent.ThreadLocal

@ThreadLocal
object RouteCalculation {

    data class Route(val amount: MilliSatoshi, val channel: Normal)

    private val logger by eclairLogger()

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