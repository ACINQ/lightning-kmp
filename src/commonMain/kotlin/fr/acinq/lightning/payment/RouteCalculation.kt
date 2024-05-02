package fr.acinq.lightning.payment

import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.Satoshi
import fr.acinq.bitcoin.utils.Either
import fr.acinq.lightning.MilliSatoshi
import fr.acinq.lightning.channel.states.*
import fr.acinq.lightning.logging.LoggerFactory
import fr.acinq.lightning.logging.MDCLogger
import fr.acinq.lightning.utils.UUID
import fr.acinq.lightning.utils.msat
import fr.acinq.lightning.utils.sum

class RouteCalculation(loggerFactory: LoggerFactory) {

    private val logger = loggerFactory.newLogger(this::class)

    data class Route(val amount: MilliSatoshi, val channel: Normal)

    data class ChannelBalance(val c: Normal) {
        val balance: MilliSatoshi = c.commitments.availableBalanceForSend()
        val capacity: Satoshi = c.commitments.latest.fundingAmount
    }

    fun findRoutes(paymentId: UUID, amount: MilliSatoshi, channels: Map<ByteVector32, ChannelState>): Either<FinalFailure, List<Route>> {
        val logger = MDCLogger(logger, staticMdc = mapOf("paymentId" to paymentId, "amount" to amount))

        val sortedChannels = channels.values.filterIsInstance<Normal>().map { ChannelBalance(it) }.sortedBy { it.balance }.reversed()
        if (sortedChannels.isEmpty()) {
            val failure = when {
                channels.values.any { it is Syncing || it is Offline } -> FinalFailure.ChannelNotConnected
                channels.values.any { it is WaitForOpenChannel || it is WaitForAcceptChannel || it is WaitForFundingCreated || it is WaitForFundingSigned || it is WaitForFundingConfirmed || it is WaitForChannelReady } -> FinalFailure.ChannelOpening
                channels.values.any { it is ShuttingDown || it is Negotiating || it is Closing || it is WaitForRemotePublishFutureCommitment } -> FinalFailure.ChannelClosing
                // This may happen if adding an HTLC failed because we hit channel limits (e.g. max-accepted-htlcs) and we're retrying with this channel filtered out.
                else -> FinalFailure.NoAvailableChannels
            }
            logger.warning { "no available channels: $failure" }
            return Either.Left(failure)
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
            Either.Left(FinalFailure.InsufficientBalance(filteredChannels.map { it.balance }.sum()))
        } else {
            logger.info { "routes found: ${routes.map { "${it.channel.shortChannelId}->${it.amount}" }}" }
            Either.Right(routes)
        }
    }

}