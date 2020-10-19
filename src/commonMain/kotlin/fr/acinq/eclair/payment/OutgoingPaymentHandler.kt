package fr.acinq.eclair.payment

import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.Satoshi
import fr.acinq.eclair.*
import fr.acinq.eclair.channel.*
import fr.acinq.eclair.crypto.sphinx.PacketAndSecrets
import fr.acinq.eclair.io.PeerEvent
import fr.acinq.eclair.io.SendPayment
import fr.acinq.eclair.io.WrappedChannelEvent
import fr.acinq.eclair.router.ChannelHop
import fr.acinq.eclair.router.NodeHop
import fr.acinq.eclair.transactions.CommitmentSpec
import fr.acinq.eclair.transactions.incomings
import fr.acinq.eclair.utils.*
import fr.acinq.eclair.wire.FinalPayload
import fr.acinq.eclair.wire.OnionRoutingPacket


class OutgoingPaymentHandler(
    val nodeParams: NodeParams
) {

    enum class FailureReason {
        INVALID_PARAMETER, // e.g. (paymentAmount < 0), (recycled paymentId)
        NO_AVAILABLE_CHANNELS, // There are zero channels in Normal mode
        INSUFFICIENT_BALANCE, // Not enough capacity in channel(s) to support payment
        CHANNEL_CAP_RESTRICTION, // e.g. htlcMaximumAmount, maxHtlcValueInFlight, maxAcceptedHtlcs
        NO_ROUTE_TO_RECIPIENT // trampoline was unable to find an acceptable route
    }

    sealed class SendPaymentResult {

        // The `Progress` result is emitted throughout the payment attempt.
        // It includes information about the fees we're paying, which may increase as we re-try our payment.
        data class Progress(
            val payment: SendPayment,
            val trampolineFees: MilliSatoshi,
            val actions: List<PeerEvent>
        ): SendPaymentResult()

        data class Failure(
            val payment: SendPayment,
            val reason: FailureReason
        ): SendPaymentResult()
    }

    sealed class ProcessFailureResult {

        data class Progress(
            val payment: SendPayment,
            val trampolineFees: MilliSatoshi,
            val actions: List<PeerEvent>
        ): ProcessFailureResult()

        data class Failure(
            val payment: SendPayment,
            val reason: FailureReason
        ): ProcessFailureResult()

        object UnknownPaymentFailure: ProcessFailureResult()
    }

    sealed class ProcessFulfillResult {

        data class Success(
            val payment: SendPayment,
            val trampolineFees: MilliSatoshi
        ): ProcessFulfillResult()

        data class Failure(
            val payment: SendPayment,
            val reason: FailureReason
        ): ProcessFulfillResult()

        object UnknownPaymentFailure: ProcessFulfillResult()
    }

    enum class Status {
        INFLIGHT,
        SUCCEEDED,
        FAILED
    }

    /**
     * When we send a trampoline payment, we start with a low fee.
     * If that fails, we increase the fee(s) and retry (up to a point).
     * This class encapsulates the increase that occurs at a particular retry.
     */
    data class TrampolineParams(
        val feeBaseSat: Satoshi,
        val feePercent: Double,
        val cltvExpiryDelta: CltvExpiryDelta
    ) {
        constructor(feeBaseSat: Long, feePercent: Double, cltvExpiryDelta: Int) :
            this(Satoshi(feeBaseSat), feePercent, CltvExpiryDelta(cltvExpiryDelta))

        companion object {

            // Todo: Fetch this from the server first, and have it passed into us somehow...
            val attempts = listOf(
                TrampolineParams(0, 0.0, 576),
                TrampolineParams(1, 0.0001, 576),
                TrampolineParams(3, 0.0001, 576),
                TrampolineParams(5, 0.0005, 576),
                TrampolineParams(5, 0.001, 576),
                TrampolineParams(5, 0.0012, 576)
            )

            fun get(failedAttempts: Int): TrampolineParams? {
                return if (failedAttempts < attempts.size) attempts[failedAttempts] else null
            }
        }
    }

    /**
     * Represents a single htlc over a single channel.
     * For a particular payment, there may be multiple parts going over different channels (mpp).
     * Each would be a different instance of this class.
     */
    data class TrampolinePaymentPart(
        val channelId: ByteVector32,
        val amount: MilliSatoshi,
        val trampolineFees: MilliSatoshi,
        val cltvExpiryDelta: CltvExpiryDelta,
        val status: Status
    ) {
        init {
            require(amount > trampolineFees) { "amount is invalid" }
        }
    }

    /**
     * Represents the current state of a payment attempt.
     */
    data class PaymentAttempt(
        val sendPayment: SendPayment,
        val failedAttempts: Int
    ) {
        val trampolinePaymentSecret = Eclair.randomBytes32() // must be different from invoice.paymentSecret
        val parts = mutableListOf<TrampolinePaymentPart>()

        val paymentId: UUID = sendPayment.paymentId
        val invoice: PaymentRequest = sendPayment.paymentRequest
        val paymentAmount: MilliSatoshi = sendPayment.paymentAmount

        /**
         * Marks the corresponding TrampolinePaymentPart as FAILED.
         */
        fun fail(channelId: ByteVector32) {

            val idx = parts.indexOfFirst { it.channelId == channelId && it.status == Status.INFLIGHT }
            if (idx < 0) return

            val failedPart = parts[idx]
            parts[idx] = failedPart.copy(status = Status.FAILED)
        }

        /**
         * Marks the corresponding TrampolinePaymentPart as SUCCEEDED.
         */
        fun succeed(channelId: ByteVector32) {

            val idx = parts.indexOfFirst { it.channelId == channelId && it.status == Status.INFLIGHT }
            if (idx < 0) return

            val part = parts[idx]
            parts[idx] = part.copy(status = Status.SUCCEEDED)
        }

        /**
         * The total amount we're paying to the trampoline node (including paymentAmount + fees).
         */
        fun totalTrampolineAmount(): MilliSatoshi {
            return parts.map { it.amount }.sum()
        }

        /**
         * The total amount of fees we're paying.
         * This includes fees to the trampoline node, and fees to other unknown channels along the way.
         */
        fun totalFees(): MilliSatoshi {
            return parts.map {
                it.trampolineFees
            }.sum()
        }
    }

    private val logger = newEclairLogger()
    private val pending = mutableMapOf<UUID, PaymentAttempt>()

    fun processSendPayment(
        sendPayment: SendPayment,
        channels: Map<ByteVector32, ChannelState>,
        currentBlockHeight: Int
    ): SendPaymentResult {

        if (sendPayment.paymentAmount <= 0.msat) {
            logger.warning { "paymentAmount(${sendPayment.paymentAmount}) must be positive" }
            return SendPaymentResult.Failure(sendPayment, FailureReason.INVALID_PARAMETER)
        }
        if (pending.containsKey(sendPayment.paymentId)) {
            logger.error { "contract violation: caller is recycling uuid's" }
            return SendPaymentResult.Failure(sendPayment, FailureReason.INVALID_PARAMETER)
        }

        val failedAttempts = 0
        val params = TrampolineParams.get(failedAttempts)!!
        val paymentAttempt = PaymentAttempt(sendPayment, failedAttempts)

        return when (val either = setupPaymentAttempt(paymentAttempt, params, channels, currentBlockHeight)) {
            is Either.Left -> SendPaymentResult.Failure(sendPayment, either.value)
            is Either.Right -> SendPaymentResult.Progress(
                payment = paymentAttempt.sendPayment,
                trampolineFees = paymentAttempt.totalFees(),
                actions = either.value
            )
        }
    }

    fun processFailure(
        event: ProcessFailure, // ProcessFail || ProcessFailMalformed
        channels: Map<ByteVector32, ChannelState>,
        currentBlockHeight: Int
    ): ProcessFailureResult {

        val paymentAttempt = pending[event.paymentId]
        if (paymentAttempt == null) {
            logger.error { "ProcessFailure.paymentId doesn't match any known paymentAttempt" }
            return ProcessFailureResult.UnknownPaymentFailure
        }

        // Mark the TrampolinePaymentPart.status as FAILED
        paymentAttempt.fail(event.channelId)

        // Now that all the parts have failed, we can try to start another paymentAttempt.
        pending.remove(paymentAttempt.paymentId)

        val failedAttempts = paymentAttempt.failedAttempts + 1
        val params = TrampolineParams.get(failedAttempts)
        if (params == null) {
            return ProcessFailureResult.Failure(paymentAttempt.sendPayment, FailureReason.NO_ROUTE_TO_RECIPIENT)
        }

        val newPaymentAttempt = PaymentAttempt(paymentAttempt.sendPayment, failedAttempts)
        return when (val either = setupPaymentAttempt(newPaymentAttempt, params, channels, currentBlockHeight)) {
            is Either.Left -> ProcessFailureResult.Failure(paymentAttempt.sendPayment, either.value)
            is Either.Right -> ProcessFailureResult.Progress(
                payment = newPaymentAttempt.sendPayment,
                trampolineFees = newPaymentAttempt.totalFees(),
                actions = either.value
            )
        }
    }

    fun processFulfill(
        event: ProcessFulfill
    ): ProcessFulfillResult {

        val paymentAttempt = pending[event.paymentId]
        if (paymentAttempt == null) {
            logger.error { "ProcessFail.origin doesn't match any known paymentAttempt" }
            return ProcessFulfillResult.UnknownPaymentFailure
        }

        // Mark the TrampolinePaymentPart.status as SUCCEEDED
        paymentAttempt.succeed(event.fulfill.channelId)

        // Now that all the parts have succeeded, we can announce success
        pending.remove(paymentAttempt.paymentId)
        return ProcessFulfillResult.Success(
            payment = paymentAttempt.sendPayment,
            trampolineFees = paymentAttempt.totalFees()
        )
    }

    private fun setupPaymentAttempt(
        paymentAttempt: PaymentAttempt,
        params: TrampolineParams,
        channels: Map<ByteVector32, ChannelState>,
        currentBlockHeight: Int
    ): Either<FailureReason, List<PeerEvent>> {

        val availableChannels = channels.values.filterIsInstance<Normal>().toMutableList()
        if (availableChannels.size == 0) {
            return Either.Left(FailureReason.NO_AVAILABLE_CHANNELS)
        }

        // Channels have a capacity, but they also have other "caps" (hard limits).
        // And these other limits affect a channel's effective capacity for sending.
        data class ChannelCapacity(
            val availableBalanceForSend: MilliSatoshi,
            val maxHtlcValue: MilliSatoshi,
            val currentHtlcValue: MilliSatoshi,
            val maxHtlcsCount: Int,
            val currentHtlcsCount: Int
        ) {
            fun effectiveAvailableBalanceForSend(): MilliSatoshi {
                if (currentHtlcsCount >= maxHtlcsCount) {
                    return MilliSatoshi(0)
                }
                val limit = maxHtlcValue - currentHtlcValue
                return availableBalanceForSend.coerceAtMost(limit)
            }
            fun hasCap(): Boolean {
                return effectiveAvailableBalanceForSend() < availableBalanceForSend
            }
        }

        // Calculating the `availableBalanceForSend` in each channel requires a bit of work.
        // And we need to reference this info repeatedly below, so we calculate it once here.
        val channelCapacities = availableChannels.associateBy(keySelector = {
            it.channelId
        }, valueTransform = {
            val availableBalanceForSend = it.commitments.availableBalanceForSend()

            val currentHtlcsCount: Int
            val currentHtlcValue: MilliSatoshi
            it.commitments.run {
                // we need to base the next current commitment on the last sig we sent,
                // even if we didn't yet receive their revocation
                val remoteCommit = when (remoteNextCommitInfo) {
                    is Either.Left -> remoteNextCommitInfo.value.nextRemoteCommit
                    is Either.Right -> remoteCommit
                }
                val reduced = CommitmentSpec.reduce(remoteCommit.spec, remoteChanges.acked, localChanges.proposed)
                // the HTLC we are about to create is outgoing, but from their point of view it is incoming
                val outgoingHtlcs = reduced.htlcs.incomings()

                currentHtlcsCount = outgoingHtlcs.size
                currentHtlcValue = outgoingHtlcs.map { it.amountMsat }.sum()
            }

            ChannelCapacity(
                availableBalanceForSend = availableBalanceForSend,
                maxHtlcsCount = it.staticParams.nodeParams.maxAcceptedHtlcs,
                maxHtlcValue = MilliSatoshi(it.staticParams.nodeParams.maxHtlcValueInFlightMsat),
                currentHtlcsCount = currentHtlcsCount,
                currentHtlcValue = currentHtlcValue
            )
        })

        // Sort the channels by send capacity.
        // The channel with the highest/most availableForSend will be at the beginning of the array (index 0)
        availableChannels.sortWith(compareBy<Normal> {
            channelCapacities[it.channelId]!!.effectiveAvailableBalanceForSend()
        }.reversed().thenBy {
            // If multiple channels have the same balance, we use shortChannelId to sort deterministically
            it.shortChannelId
        })

        channelCapacities.forEach {
            logger.info { "channel(${it.key}): available for send = ${it.value.availableBalanceForSend}" }
        }

        // Phase 1: No mpp.
        // We're only sending over a single channel for right now.

        val selectedChannel = availableChannels[0] // already tested for empty array above
        val channelCapacity = channelCapacities[selectedChannel.channelId]!!

        val recipientAmount = paymentAttempt.paymentAmount
        val trampolineFees = params.feeBaseSat.toMilliSatoshi() + (recipientAmount * params.feePercent)

        if ((recipientAmount + trampolineFees) > channelCapacity.availableBalanceForSend) {
            logger.error {
                "insufficient capacity to send payment:" +
                        " attempted(${recipientAmount + trampolineFees})" +
                        " available(${channelCapacity.availableBalanceForSend})"
            }
            return Either.Left(FailureReason.INSUFFICIENT_BALANCE)
        }

        // Check for channel cap restrictions

        if ((recipientAmount + trampolineFees) > channelCapacity.effectiveAvailableBalanceForSend()) {
            logger.error {
                "insufficient capacity to send payment:" +
                        " attempted(${recipientAmount + trampolineFees})" +
                        " available(${channelCapacity.availableBalanceForSend})" +
                        " cappedAvailable(${channelCapacity.effectiveAvailableBalanceForSend()})"
            }
            return Either.Left(FailureReason.CHANNEL_CAP_RESTRICTION)
        }

        selectedChannel.channelUpdate.htlcMinimumMsat.let { htlcMinimumMsat ->
            if ((recipientAmount + trampolineFees) < htlcMinimumMsat) {
                logger.error {
                    "payment is below channel's htlcMinimumMsat:" +
                            " attempted(${recipientAmount + trampolineFees})" +
                            " htlcMinimumMsat(${htlcMinimumMsat})"
                }
                return@setupPaymentAttempt Either.Left(FailureReason.CHANNEL_CAP_RESTRICTION)
            }
        }

        selectedChannel.channelUpdate.htlcMaximumMsat?.let { htlcMaximumMsat ->
            if ((recipientAmount + trampolineFees) > htlcMaximumMsat) {
                logger.error {
                    "payment is above channel's htlcMaximumMsat:" +
                        " attempted(${recipientAmount + trampolineFees})" +
                        " htlcMaximumMsat(${htlcMaximumMsat})"
                }
                return@setupPaymentAttempt Either.Left(FailureReason.CHANNEL_CAP_RESTRICTION)
            }
        }

        val part = TrampolinePaymentPart(
            channelId = selectedChannel.channelId,
            amount = recipientAmount + trampolineFees,
            trampolineFees = trampolineFees,
            cltvExpiryDelta = params.cltvExpiryDelta,
            status = Status.INFLIGHT
        )
        paymentAttempt.parts.add(part)

        val actions = listOf<PeerEvent>(
            // createHtlc: part => (onion w/ trampoline) => CMD_ADD_HTLC => WrappedChannelEvent
            createHtlc(selectedChannel, paymentAttempt, part, currentBlockHeight)
        )

        pending[paymentAttempt.paymentId] = paymentAttempt
        return Either.Right(actions)
    }

    /**
     * Converts the TrampolinePaymentPart into an actionable PeerEvent.
     * The process involves:
     * - Creating the onion (with trampoline_onion component)
     * - Creating the CMD_ADD_HTLC
     * - Creating the WrappedChannelEvent
     */
    fun createHtlc(
        channel: Normal,
        paymentAttempt: PaymentAttempt,
        part: TrampolinePaymentPart,
        currentBlockHeight: Int
    ): WrappedChannelEvent {

        val finalExpiryDelta = paymentAttempt.invoice.minFinalExpiryDelta
            ?: Channel.MIN_CLTV_EXPIRY_DELTA // default value if unspecified, as per Bolt 11
        val finalExpiry = finalExpiryDelta.toCltvExpiry(currentBlockHeight.toLong())

        val features = paymentAttempt.invoice.features?.let { Features(it) } ?: Features(setOf())
        val recipientSupportsTrampoline = features.hasFeature(Feature.TrampolinePayment)

        val finalPayload = FinalPayload.createSinglePartPayload(
            amount = paymentAttempt.paymentAmount,
            expiry = finalExpiry,
            paymentSecret = paymentAttempt.invoice.paymentSecret
        )
        val nodeHops = listOf(
            NodeHop(
                nodeId = channel.staticParams.nodeParams.nodeId, // us
                nextNodeId = channel.staticParams.remoteNodeId, // trampoline node (acinq)
                cltvExpiryDelta = CltvExpiryDelta(0), // ignored
                fee = MilliSatoshi(0) // ignored
            ),
            NodeHop(
                nodeId = channel.staticParams.remoteNodeId, // trampoline node (acinq)
                nextNodeId = paymentAttempt.invoice.nodeId, // final recipient
                cltvExpiryDelta = part.cltvExpiryDelta,
                fee = part.trampolineFees
            )
        )

        val trampolineExpiry: CltvExpiry
        val trampolineOnion: PacketAndSecrets
        if (recipientSupportsTrampoline) {
            // Full trampoline! Full privacy!
            val triple = OutgoingPacket.buildPacket(
                paymentHash = paymentAttempt.invoice.paymentHash,
                hops = nodeHops,
                finalPayload = finalPayload,
                payloadLength = OnionRoutingPacket.TrampolinePacketLength
            )
            trampolineExpiry = triple.second
            trampolineOnion = triple.third
        } else {
            // Legacy workaround
            throw RuntimeException("Not implemented")
        }

        val trampolinePayload = FinalPayload.createTrampolinePayload(
            amount = part.amount,
            totalAmount = paymentAttempt.totalTrampolineAmount(),
            expiry = trampolineExpiry,
            paymentSecret = paymentAttempt.trampolinePaymentSecret,
            trampolinePacket = trampolineOnion.packet
        )

        val channelHops: List<ChannelHop> = listOf(
            ChannelHop(
                nodeId = channel.staticParams.nodeParams.nodeId, // us
                nextNodeId = channel.staticParams.remoteNodeId, // trampoline node (acinq)
                lastUpdate = channel.channelUpdate
            )
        )
        val (cmdAddHtlc, _) = OutgoingPacket.buildCommand(
            paymentId = paymentAttempt.paymentId,
            paymentHash = paymentAttempt.invoice.paymentHash,
            hops = channelHops,
            finalPayload = trampolinePayload
        )

        return WrappedChannelEvent(channelId = channel.channelId, channelEvent = ExecuteCommand(cmdAddHtlc))
    }
}