package fr.acinq.eclair.payment

import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.PublicKey
import fr.acinq.bitcoin.Satoshi
import fr.acinq.eclair.*
import fr.acinq.eclair.channel.*
import fr.acinq.eclair.crypto.sphinx.FailurePacket
import fr.acinq.eclair.io.PeerEvent
import fr.acinq.eclair.io.SendPayment
import fr.acinq.eclair.io.WrappedChannelError
import fr.acinq.eclair.io.WrappedChannelEvent
import fr.acinq.eclair.router.ChannelHop
import fr.acinq.eclair.router.NodeHop
import fr.acinq.eclair.utils.*
import fr.acinq.eclair.wire.*

sealed class OutgoingPaymentFailure {

    enum class Reason {
        INVALID_PARAMETER, // e.g. (paymentAmount < 0), (recycled paymentId)
        NO_AVAILABLE_CHANNELS, // There are zero channels in Normal mode
        INSUFFICIENT_BALANCE_BASE, // Not enough capacity in channel(s) to support payment
        INSUFFICIENT_BALANCE_FEES, // Not enough capacity, after fees are taken into account
        CHANNEL_CAPS, // One or more channels has max/min htlc amounts preventing payment
        NO_ROUTE_TO_RECIPIENT, // Trampoline was unable to find an acceptable route
        UNKNOWN_ERROR // Some other error occurred
    }

    data class LocalFailure(val channelException: ChannelException) : OutgoingPaymentFailure()
    data class RemoteFailure(val failureMessage: FailureMessage) : OutgoingPaymentFailure()
    data class CommonFailure(val reason: Reason) : OutgoingPaymentFailure()
}

class OutgoingPaymentHandler(val nodeParams: NodeParams) {

    sealed class SendPaymentResult {

        // The `Progress` result is emitted throughout the payment attempt.
        // It includes information about the fees we're paying, which may increase as we re-try our payment.
        data class Progress(
            val payment: SendPayment,
            val trampolineFees: MilliSatoshi,
            val actions: List<PeerEvent>
        ) : SendPaymentResult()

        data class Failure(
            val payment: SendPayment,
            val reason: OutgoingPaymentFailure
        ) : SendPaymentResult()
    }

    sealed class ProcessFailureResult {

        data class Progress(
            val payment: SendPayment,
            val trampolineFees: MilliSatoshi,
            val actions: List<PeerEvent>
        ) : ProcessFailureResult()

        data class Failure(
            val payment: SendPayment,
            val reason: OutgoingPaymentFailure
        ) : ProcessFailureResult()

        object UnknownPaymentFailure : ProcessFailureResult()
    }

    sealed class ProcessFulfillResult {

        data class Progress(
            val payment: SendPayment,
            val trampolineFees: MilliSatoshi
        ) : ProcessFulfillResult()

        data class Failure(
            val payment: SendPayment,
            val reason: OutgoingPaymentFailure
        ) : ProcessFulfillResult()

        object UnknownPaymentFailure : ProcessFulfillResult()

        data class Success(
            val payment: SendPayment,
            val trampolineFees: MilliSatoshi
        ) : ProcessFulfillResult()
    }

    /**
     * When we send a trampoline payment, we start with a low fee.
     * If that fails, we increase the fee(s) and retry (up to a point).
     * This class encapsulates the increase that occurs at a particular retry.
     */
    data class TrampolineParams(
        val feeBaseSat: Satoshi,
        val feePercent: Float,
        val cltvExpiryDelta: CltvExpiryDelta
    ) {
        constructor(feeBaseSat: Long, feePercent: Float, cltvExpiryDelta: Int) :
                this(Satoshi(feeBaseSat), feePercent, CltvExpiryDelta(cltvExpiryDelta))

        companion object {

            // Todo: Fetch this from the server first, and have it passed into us somehow...
            val attempts = listOf(
                TrampolineParams(0, 0.0f, 576),
                TrampolineParams(1, 0.0001f, 576),
                TrampolineParams(3, 0.0001f, 576),
                TrampolineParams(5, 0.0005f, 576),
                TrampolineParams(5, 0.001f, 576),
                TrampolineParams(5, 0.0012f, 576)
            )

            fun get(failedAttempts: Int): TrampolineParams? {
                return if (failedAttempts < attempts.size) attempts[failedAttempts] else null
            }
        }
    }

    enum class Status {
        INFLIGHT,
        SUCCEEDED,
        FAILED_RETRYABLE,
        FAILED_NOT_RETRYABLE,
    }

    /**
     * Represents a single htlc over a single channel.
     * For a particular payment, there may be multiple parts going over different channels (mpp).
     * Each would be a different instance of this class.
     */
    data class TrampolinePaymentPart(
        val channelId: ByteVector32,
        val amount: MilliSatoshi,
        val fees: MilliSatoshi,
        val secrets: List<Pair<ByteVector32, PublicKey>>,
        var status: Status
    ) {
        init {
            require(amount >= fees) { "amount is invalid" }
        }
    }

    /**
     * Represents the current state of a payment attempt.
     */
    class PaymentAttempt {
        val sendPayment: SendPayment
        val paymentId: UUID get() = sendPayment.paymentId
        val invoice: PaymentRequest get() = sendPayment.paymentRequest
        val paymentAmount: MilliSatoshi get() = sendPayment.paymentAmount

        val failedAttempts: Int
        val trampolineParams: TrampolineParams

        val parts = mutableListOf<TrampolinePaymentPart>() // always starts empty; populated via setupPaymentAttempt
        val failedChannelIds: MutableSet<ByteVector32>

        var localFailure: OutgoingPaymentFailure.LocalFailure? = null
        var remoteFailure: OutgoingPaymentFailure.RemoteFailure? = null // excluding trampoline_fee_insufficient

        val trampolinePaymentSecret = Eclair.randomBytes32() // must be different from invoice.paymentSecret
        var mppTotalAmount: MilliSatoshi = MilliSatoshi(0) // must match across all parts

        private constructor(
            sendPayment: SendPayment,
            failedAttempts: Int,
            trampolineParams: TrampolineParams,
            failedChannelIds: Set<ByteVector32>
        ) {
            this.sendPayment = sendPayment
            this.failedAttempts = failedAttempts
            this.trampolineParams = trampolineParams
            this.failedChannelIds = failedChannelIds.toMutableSet()
        }

        constructor(sendPayment: SendPayment) : this(
            sendPayment = sendPayment,
            failedAttempts = 0,
            trampolineParams = TrampolineParams.get(0)!!,
            failedChannelIds = mutableSetOf()
        )

        fun nextPaymentAttempt(): PaymentAttempt? {
            val newFailedAttempts = this.failedAttempts + 1
            val newTrampolineParams = TrampolineParams.get(newFailedAttempts)
            return if (newTrampolineParams == null) null else {
                PaymentAttempt(sendPayment, newFailedAttempts, newTrampolineParams, failedChannelIds)
            }
        }

        fun fail(channelId: ByteVector32, retryable: Boolean) {
            val idx = parts.indexOfFirst { it.channelId == channelId }
            if (idx >= 0) {
                parts[idx].status = if (retryable) Status.FAILED_RETRYABLE else Status.FAILED_NOT_RETRYABLE
            }
            if (!retryable) {
                failedChannelIds.add(channelId)
            }
        }

        fun succeed(channelId: ByteVector32) {
            val idx = parts.indexOfFirst { it.channelId == channelId }
            if (idx >= 0) {
                parts[idx].status = Status.SUCCEEDED
            }
        }

        /**
         * The minimum amount of fees required, according to the paymentAmount & trampolineParams.
         * Actual fees paid may be higher, depending on how we need to split the payment across channels.
         */
        fun minFees(): MilliSatoshi {
            return trampolineParams.feeBaseSat.toMilliSatoshi() + (paymentAmount * trampolineParams.feePercent)
        }

        /**
         * Returns the total amount of fees being paid.
         *
         * NB #1: This may be more than what the trampolineParams specifies (but never less).
         * This occurs when we have to split the payment over several channels,
         * and the various `htlcMinimumMsat` configurations require us to overpay by a bit.
         *
         * NB #2: There's no need to consider the status of a part here.
         * A paymentAttempt is constructed with a fixed set of parts.
         * Either all parts succeed, or the payment attempt fails.
         * And when the payment attempt fails, we throw away the previous set of parts,
         * and construct a new set of parts.
         */
        fun fees(): MilliSatoshi {
            return parts.map { it.fees }.sum()
        }

        fun hasInFlightParts(): Boolean {
            return parts.any { it.status == Status.INFLIGHT }
        }

        fun getError(reason: OutgoingPaymentFailure.Reason): OutgoingPaymentFailure {
            return localFailure ?: remoteFailure ?: OutgoingPaymentFailure.CommonFailure(reason)
        }
    }

    private val logger = newEclairLogger()
    private val pending = mutableMapOf<UUID, PaymentAttempt>()

    fun pendingPaymentAttempt(paymentId: UUID): PaymentAttempt? = pending[paymentId]

    fun sendPayment(
        sendPayment: SendPayment,
        channels: Map<ByteVector32, ChannelState>,
        currentBlockHeight: Int
    ): SendPaymentResult {

        if (sendPayment.paymentAmount <= 0.msat) {
            logger.warning { "paymentAmount(${sendPayment.paymentAmount}) must be positive" }
            return SendPaymentResult.Failure(
                payment = sendPayment,
                reason = OutgoingPaymentFailure.CommonFailure(OutgoingPaymentFailure.Reason.INVALID_PARAMETER)
            )
        }
        if (pending.containsKey(sendPayment.paymentId)) {
            logger.error { "contract violation: caller is recycling uuid's" }
            return SendPaymentResult.Failure(
                payment = sendPayment,
                reason = OutgoingPaymentFailure.CommonFailure(OutgoingPaymentFailure.Reason.INVALID_PARAMETER))
        }

        val paymentAttempt = PaymentAttempt(sendPayment)
        return when (val either = setupPaymentAttempt(paymentAttempt, channels, currentBlockHeight)) {
            is Either.Left -> SendPaymentResult.Failure(sendPayment, either.value)
            is Either.Right -> SendPaymentResult.Progress(
                payment = paymentAttempt.sendPayment,
                trampolineFees = paymentAttempt.fees(),
                actions = either.value
            )
        }
    }

    /**
     * This class generates CMD_ADD_HTLC commands.
     * It's the channel's job to convert those commands into actual UpdateAddHtlc messages.
     * If that command fails, we receive news via this function.
     */
    fun processLocalFailure(
        event: WrappedChannelError,
        channels: Map<ByteVector32, ChannelState>,
        currentBlockHeight: Int
    ): ProcessFailureResult? {

        // We are looking for errors from the channel in response to our CMD_ADD_HTLC request
        val cmdAddHtlc = (event.trigger as? ExecuteCommand)?.command as? CMD_ADD_HTLC
        if (cmdAddHtlc == null) {
            return null
        }

        val paymentAttempt = pending[cmdAddHtlc.paymentId]
        if (paymentAttempt == null) {
            logger.error { "paymentId doesn't match any known PaymentAttempt" }
            return ProcessFailureResult.UnknownPaymentFailure
        }

        if (event.error is ChannelException) {
            paymentAttempt.localFailure = OutgoingPaymentFailure.LocalFailure(event.error)
        }

        val failedIdx = paymentAttempt.parts.indexOfFirst {
            (it.status == Status.INFLIGHT) && (it.channelId == event.channelId)
        }
        if (failedIdx < 0) {
            logger.error { "event doesn't match any known TrampolinePaymentPart" }
            return ProcessFailureResult.UnknownPaymentFailure
        }
        val failedPart = paymentAttempt.parts.removeAt(failedIdx)
        failedPart.status = Status.FAILED_NOT_RETRYABLE
        paymentAttempt.failedChannelIds.add(failedPart.channelId) // don't retry on channel
        pending.remove(paymentAttempt.paymentId)

        return when (val either = setupPaymentAttempt(paymentAttempt, channels, currentBlockHeight, failedPart)) {
            is Either.Left -> ProcessFailureResult.Failure(paymentAttempt.sendPayment, either.value)
            is Either.Right -> ProcessFailureResult.Progress(
                payment = paymentAttempt.sendPayment,
                trampolineFees = paymentAttempt.fees(),
                actions = either.value
            )
        }
    }

    fun processRemoteFailure(
        event: ProcessRemoteFailure,
        channels: Map<ByteVector32, ChannelState>,
        currentBlockHeight: Int
    ): ProcessFailureResult {

        val paymentAttempt = pending[event.paymentId]
        if (paymentAttempt == null) {
            logger.error { "paymentId doesn't match any known PaymentAttempt" }
            return ProcessFailureResult.UnknownPaymentFailure
        }

        // There are only two retriable remote failures: trampoline_fee_insufficient or trampoline_expiry_too_soon.
        // Otherwise we must fail the payment.
        val retryable = when (event) {
            is ProcessFail -> {
                val part = paymentAttempt.parts.firstOrNull {
                    (it.status == Status.INFLIGHT) && (it.channelId == event.channelId)
                }
                if (part != null) {
                    when (val decrypted = FailurePacket.decrypt(event.fail.reason.toByteArray(), part.secrets)) {
                        is Try.Success -> {
                            val failureMsg = decrypted.result.failureMessage
                            if (failureMsg == TrampolineFeeInsufficient || failureMsg == TrampolineExpiryTooSoon) {
                                true
                            } else {
                                paymentAttempt.remoteFailure = OutgoingPaymentFailure.RemoteFailure(failureMsg)
                                false
                            }
                        }
                        is Try.Failure -> false
                    }
                } else {
                    false
                }
            }
            is ProcessFailMalformed -> false
        }

        // Mark part as failed (either failed_retryable or failed_not_retryable)
        paymentAttempt.fail(event.channelId, retryable)

        // Wait until all the parts have finished
        if (paymentAttempt.hasInFlightParts()) {
            return ProcessFailureResult.Progress(
                payment = paymentAttempt.sendPayment,
                trampolineFees = paymentAttempt.fees(),
                actions = listOf()
            )
        }
        pending.remove(paymentAttempt.paymentId)

        // If we received trampoline specific error codes for all parts,
        // we can retry the payment (with higher fees).
        if (paymentAttempt.parts.any { it.status != Status.FAILED_RETRYABLE }) {
            return ProcessFailureResult.Failure(
                payment = paymentAttempt.sendPayment,
                reason = paymentAttempt.getError(OutgoingPaymentFailure.Reason.UNKNOWN_ERROR) // localError ?: remoteError
            )
        }

        // increment failedAttempts, and jump to next TrampolineParams (if possible)
        val nextPaymentAttempt = paymentAttempt.nextPaymentAttempt()
        if (nextPaymentAttempt == null) {
            return ProcessFailureResult.Failure(
                payment = paymentAttempt.sendPayment,
                reason = OutgoingPaymentFailure.CommonFailure(OutgoingPaymentFailure.Reason.NO_ROUTE_TO_RECIPIENT)
            )
        }

        return when (val either = setupPaymentAttempt(nextPaymentAttempt, channels, currentBlockHeight)) {
            is Either.Left -> ProcessFailureResult.Failure(nextPaymentAttempt.sendPayment, either.value)
            is Either.Right -> ProcessFailureResult.Progress(
                payment = nextPaymentAttempt.sendPayment,
                trampolineFees = nextPaymentAttempt.fees(),
                actions = either.value
            )
        }
    }

    fun processFulfill(
        event: ProcessFulfill
    ): ProcessFulfillResult {

        val paymentAttempt = pending[event.paymentId]
        if (paymentAttempt == null) {
            logger.error { "paymentId doesn't match any known payment attempt" }
            return ProcessFulfillResult.UnknownPaymentFailure
        }

        // Mark part as succeeded
        paymentAttempt.succeed(event.fulfill.channelId)

        // Wait until all the parts have finished
        if (paymentAttempt.hasInFlightParts()) {
            return ProcessFulfillResult.Progress(
                payment = paymentAttempt.sendPayment,
                trampolineFees = paymentAttempt.fees()
            )
        }
        pending.remove(paymentAttempt.paymentId)

        // If all parts were fulfilled, the payment was successful
        return when (paymentAttempt.parts.all { it.status == Status.SUCCEEDED }) {
            true -> {
                ProcessFulfillResult.Success(
                    payment = paymentAttempt.sendPayment,
                    trampolineFees = paymentAttempt.fees()
                )
            }
            false -> {
                ProcessFulfillResult.Failure(
                    payment = paymentAttempt.sendPayment,
                    reason = paymentAttempt.getError(OutgoingPaymentFailure.Reason.UNKNOWN_ERROR)
                )
            }
        }
    }

    private fun setupPaymentAttempt(
        paymentAttempt: PaymentAttempt,
        channels: Map<ByteVector32, ChannelState>,
        currentBlockHeight: Int,
        replacePart: TrampolinePaymentPart? = null
    ): Either<OutgoingPaymentFailure, List<PeerEvent>> {

        // This function is called under 2 slightly different circumstances:
        // - after a fresh paymentAttempt has been created, and we need to create all the parts
        // - after a part has received a local error, and we need to replace that part
        val isInitialPopulation = paymentAttempt.parts.isEmpty()

        // Channels have a balance & capacity, but they also have other limits.
        // And these other limits affect a channel's ability to send a payment.
        data class ChannelState(
            val availableForSend: MilliSatoshi,
            val htlcMinimumMsat: MilliSatoshi,
            val htlcMaximumMsat: MilliSatoshi?,
            var amountToSend: MilliSatoshi = 0.msat
        )

        val inUseChannelIds = paymentAttempt.parts.map { it.channelId }.toSet()

        val sortedChannels = channels.values.filterIsInstance<Normal>().filterNot {
            // exclude any channels that have previously failed, or are already in use (for this payment)
            paymentAttempt.failedChannelIds.contains(it.channelId) || inUseChannelIds.contains(it.channelId)
        }.map {
            Pair(it, ChannelState(
                availableForSend = it.commitments.availableBalanceForSend(),
                htlcMinimumMsat = it.channelUpdate.htlcMinimumMsat,
                htlcMaximumMsat = it.channelUpdate.htlcMaximumMsat
            ))
        }.sortedBy {
            it.second.availableForSend
        }.reversed()

        if (sortedChannels.isEmpty()) {
            return Either.Left(paymentAttempt.getError(OutgoingPaymentFailure.Reason.NO_AVAILABLE_CHANNELS))
        }

        if (isInitialPopulation) {
            val totalAvailableForSend = sortedChannels.map { it.second.availableForSend }.sum()
            if (paymentAttempt.paymentAmount > totalAvailableForSend) {
                return Either.Left(paymentAttempt.getError(OutgoingPaymentFailure.Reason.INSUFFICIENT_BALANCE_BASE))
            }
            if (paymentAttempt.paymentAmount + paymentAttempt.minFees() > totalAvailableForSend) {
                return Either.Left(paymentAttempt.getError(OutgoingPaymentFailure.Reason.INSUFFICIENT_BALANCE_FEES))
            }
        }

        val targetAmount = if (isInitialPopulation) {
            paymentAttempt.paymentAmount + paymentAttempt.minFees()
        } else {
            require(replacePart != null)
            replacePart.amount
        }

        var remainingAmount = targetAmount
        for ((_, state) in sortedChannels) {

            var amount = state.availableForSend.coerceAtMost(remainingAmount)
            state.htlcMaximumMsat?.let { htlcMaximumMsat ->
                amount = amount.coerceAtMost(htlcMaximumMsat)
            }
            amount = amount.coerceAtLeast(state.htlcMinimumMsat)

            state.amountToSend = amount
            remainingAmount -= state.amountToSend
            if (remainingAmount <= 0.msat) {
                break
            }
        }

        if (remainingAmount > 0.msat) {
            return Either.Left(paymentAttempt.getError(OutgoingPaymentFailure.Reason.CHANNEL_CAPS))
        }

        // BOLT 11 specifies that the receiver:
        // - SHOULD fail the entire HTLC set if total_amount is not the same for all HTLCs in the set
        //
        // So when replacing a part, we have to be careful.
        // Because there's a risk the total amount could change.
        // For example:
        // - initial htlc set = [A@10.msat, B@5.msat]
        // - htlc is sent over channel A
        // - local failure on channel B
        // - replacing B = [A@10.msat, C@6.msat] (due to C.htlcMinimumMsat)
        //
        if (isInitialPopulation) {
            paymentAttempt.mppTotalAmount = sortedChannels.map { it.second.amountToSend }.sum()
        }

        var remainingFees = if (replacePart != null) replacePart.fees else paymentAttempt.minFees()

        val actions = sortedChannels.mapNotNull { (channel, state) ->
            if (state.amountToSend == 0.msat) null else {
                val partAmount = state.amountToSend
                val partFees = partAmount.coerceAtMost(remainingFees)
                remainingFees -= partFees
                createHtlc(paymentAttempt, channel,
                    totalTrampolineAmount = paymentAttempt.mppTotalAmount,
                    partAmount = partAmount,
                    partFees = partFees,
                    currentBlockHeight)
            }
        }

        pending[paymentAttempt.paymentId] = paymentAttempt
        return Either.Right(actions)
    }

    /**
     * Creates the TrampolinePaymentPart, and adds it to the given paymentAttempt.
     * Then creates the corresponding actionable PeerEvent.
     * This process involves:
     * - Creating the onion (with trampoline_onion component)
     * - Creating the CMD_ADD_HTLC
     * - Creating the WrappedChannelEvent
     */
    private fun createHtlc(
        paymentAttempt: PaymentAttempt,
        channel: Normal,
        totalTrampolineAmount: MilliSatoshi,
        partAmount: MilliSatoshi,
        partFees: MilliSatoshi,
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
                cltvExpiryDelta = paymentAttempt.trampolineParams.cltvExpiryDelta,
                fee = partFees
            )
        )

        val (_, trampolineExpiry, trampolineOnion) = if (recipientSupportsTrampoline) {
            // Full trampoline! Full privacy!
            OutgoingPacket.buildPacket(
                paymentHash = paymentAttempt.invoice.paymentHash,
                hops = nodeHops,
                finalPayload = finalPayload,
                payloadLength = OnionRoutingPacket.TrampolinePacketLength
            )
        } else {
            // Legacy workaround
            OutgoingPacket.buildTrampolineToLegacyPacket(
                invoice = paymentAttempt.invoice,
                hops = nodeHops,
                finalPayload = finalPayload
            )
        }

        val trampolinePayload = FinalPayload.createTrampolinePayload(
            amount = partAmount,
            totalAmount = totalTrampolineAmount, // across all parts
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
        val (cmdAddHtlc, secrets) = OutgoingPacket.buildCommand(
            paymentId = paymentAttempt.paymentId,
            paymentHash = paymentAttempt.invoice.paymentHash,
            hops = channelHops,
            finalPayload = trampolinePayload
        )

        val part = TrampolinePaymentPart(
            channelId = channel.channelId,
            amount = partAmount,
            fees = partFees,
            secrets = secrets,
            status = Status.INFLIGHT
        )
        paymentAttempt.parts.add(part)

        return WrappedChannelEvent(channelId = channel.channelId, channelEvent = ExecuteCommand(cmdAddHtlc))
    }
}