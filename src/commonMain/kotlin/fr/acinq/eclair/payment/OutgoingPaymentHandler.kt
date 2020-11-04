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

data class OutgoingPaymentFailure(
    val reason: Reason,
    val message: String,
    val problems: List<Either<ChannelException, FailureMessage>>
) {
    // Simple error types designed for general users.
    // Anything non-simple goes into the OTHER_ERROR category, and we rely on the problems list for details.
    enum class Reason {
        INVALID_PARAMETER, // e.g. (paymentAmount < 0), (recycled paymentId)
        NO_AVAILABLE_CHANNELS, // Zero channels in Normal mode (excluding failed channels)
        INSUFFICIENT_BALANCE_BASE, // Not enough capacity in channel(s) to support payment
        INSUFFICIENT_BALANCE_FEES, // Not enough capacity, after fees are taken into account
        NO_ROUTE_TO_RECIPIENT, // Trampoline was unable to find an acceptable route
        OTHER_ERROR, // Some other error occurred
    }

    companion object {
        fun make(
            reason: Reason,
            problems: List<Either<ChannelException, FailureMessage>>
        ): OutgoingPaymentFailure {
            val message = when (reason) {
                Reason.INVALID_PARAMETER -> "Invalid parameter"
                Reason.NO_AVAILABLE_CHANNELS -> "No channels available to send payment. " +
                        "Check internet connection or ensure you have an available balance."
                Reason.INSUFFICIENT_BALANCE_BASE -> "Not enough funds in wallet to afford payment"
                Reason.INSUFFICIENT_BALANCE_FEES -> "Not enough funds in wallet, after fees are taken into account."
                Reason.NO_ROUTE_TO_RECIPIENT -> "Unable to route payment to recipient"
                Reason.OTHER_ERROR -> "Unknown error occurred"
            }
            return OutgoingPaymentFailure(reason, message, problems)
        }
    }

    object InvalidParameter {
        fun paymentAmount() = OutgoingPaymentFailure(
            reason = Reason.INVALID_PARAMETER,
            message = "Invalid parameter: payment amount must be positive",
            problems = listOf()
        )

        fun paymentId() = OutgoingPaymentFailure(
            reason = Reason.INVALID_PARAMETER,
            message = "Invalid parameter: paymentId must be unique",
            problems = listOf()
        )
    }

    /**
     * A simple summary of the problem, designed for general users.
     */
    @Suppress("unused")
    fun basicSummary(): String {
        // We strive to provide a message that would lead a general user toward solving their own problem.
        // And we must not assume any knowledge of the lightning protocol.
        //
        // A concrete example:
        // - the user has 2 channels
        // - one channel fails with HtlcValueTooHighInFlight
        // - the remaining channel contains an insufficient balance to make the payment
        //
        // So the UI should say:
        // - basic = Not enough funds in wallet ...
        // - details = HtlcValueTooHighInFlight ...
        //
        // The general user will see "not enough funds in wallet", and some gobbledygook.
        // It may be a bit confusing, but it leads them toward the solution
        // => add funds, retry payment, success!

        fun isRouteError(problem: Either<ChannelException, FailureMessage>) = when (problem) {
            is Either.Left -> false
            is Either.Right -> when (problem.value) {
                is UnknownNextPeer -> true
                is TemporaryChannelFailure -> true
                is PermanentChannelFailure -> true
                is TemporaryNodeFailure -> true
                is PermanentNodeFailure -> true
                else -> false
            }
        }

        if (reason == Reason.OTHER_ERROR) {
            if (problems.any { isRouteError(it) }) {
                return "Unable to route payment to recipient"
            }
            if (problems.any { (it is Either.Right) && (it.value is IncorrectOrUnknownPaymentDetails) }) {
                return "Payment rejected by the recipient. " +
                        "This usually occurs when the invoice has already been paid or when it contains an expiration date, " +
                        "and you attempted to send a payment after the expiration."
            }
        }

        return message
    }

    /**
     * A detailed summary of the ChannelExceptions and/or FailureMessages that occurred.
     * This is targeted at users with technical knowledge of the lightning protocol.
     */
    @Suppress("unused")
    fun detailedSummary(): String {
        var msg = ""

        problems.forEachIndexed { index, problem ->
            msg += "$index: ${problem.fold({ ex -> ex.message }, { f -> f.message })}\n"
        }

        return msg
    }
}

class OutgoingPaymentHandler(val nodeParams: NodeParams) {

    // Only those result types that can possibly be returned from `OutgoingPaymentHandler.sendPayment()`
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
            val failure: OutgoingPaymentFailure
        ) : SendPaymentResult()
    }

    // Only those result types that can possibly be returned from `OutgoingPaymentHandler.processXFailure()`
    sealed class ProcessFailureResult {

        data class Progress(
            val payment: SendPayment,
            val trampolineFees: MilliSatoshi,
            val actions: List<PeerEvent>
        ) : ProcessFailureResult()

        data class Failure(
            val payment: SendPayment,
            val failure: OutgoingPaymentFailure
        ) : ProcessFailureResult()

        object UnknownPaymentFailure : ProcessFailureResult()
    }

    // Only those result types that can possibly be returned from `OutgoingPaymentHandler.processFulfill()`
    sealed class ProcessFulfillResult {

        data class Success(
            val payment: SendPayment,
            val trampolineFees: MilliSatoshi
        ) : ProcessFulfillResult()

        data class Failure(
            val payment: SendPayment,
            val failure: OutgoingPaymentFailure
        ) : ProcessFulfillResult()

        object UnknownPaymentFailure : ProcessFulfillResult()
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
        val secrets: List<Pair<ByteVector32, PublicKey>>
    ) {
        init {
            require(amount >= trampolineFees) { "amount is invalid" }
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

        // These always start empty on new paymentAttempt; not carried over from previous paymentAttempt
        val parts = mutableListOf<TrampolinePaymentPart>()
        val problems = mutableListOf<Either<ChannelException, FailureMessage>>()

        // These are carried over from previous paymentAttempt
        val failedChannelIds: MutableSet<ByteVector32>
        val failedChannelProblems: MutableList<Either<ChannelException, FailureMessage>>

        val trampolinePaymentSecret = Eclair.randomBytes32() // must be different from invoice.paymentSecret
        val fees: MilliSatoshi
            get() = trampolineParams.feeBaseSat.toMilliSatoshi() + (paymentAmount * trampolineParams.feePercent)

        private constructor(
            sendPayment: SendPayment,
            failedAttempts: Int,
            trampolineParams: TrampolineParams,
            failedChannelIds: Set<ByteVector32>,
            failedChannelProblems: List<Either<ChannelException, FailureMessage>>
        ) {
            this.sendPayment = sendPayment
            this.failedAttempts = failedAttempts
            this.trampolineParams = trampolineParams
            this.failedChannelIds = failedChannelIds.toMutableSet()
            this.failedChannelProblems = failedChannelProblems.toMutableList()
        }

        // Starting fresh, with zero failedAttempts, and zero problems
        constructor(sendPayment: SendPayment) : this(
            sendPayment = sendPayment,
            failedAttempts = 0,
            trampolineParams = TrampolineParams.get(0)!!,
            failedChannelIds = mutableSetOf(),
            failedChannelProblems = mutableListOf()
        )

        // Retrying attempt: incrementing failedAttempts/trampolineParams, and maintaining list of failed channels.
        fun nextPaymentAttempt(): PaymentAttempt? {
            val newFailedAttempts = this.failedAttempts + 1
            val newTrampolineParams = TrampolineParams.get(newFailedAttempts)
            return if (newTrampolineParams == null) null else {
                PaymentAttempt(
                    sendPayment, newFailedAttempts, newTrampolineParams,
                    failedChannelIds, failedChannelProblems
                )
            }
        }

        fun allProblems() = failedChannelProblems + problems
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
                failure = OutgoingPaymentFailure.InvalidParameter.paymentAmount()
            )
        }
        if (pending.containsKey(sendPayment.paymentId)) {
            logger.error { "contract violation: caller is recycling uuid's" }
            return SendPaymentResult.Failure(
                payment = sendPayment,
                failure = OutgoingPaymentFailure.InvalidParameter.paymentId()
            )
        }

        val paymentAttempt = PaymentAttempt(sendPayment)
        return when (val either = setupPaymentAttempt(paymentAttempt, channels, currentBlockHeight)) {
            is Either.Left -> SendPaymentResult.Failure(sendPayment, either.value)
            is Either.Right -> SendPaymentResult.Progress(
                payment = paymentAttempt.sendPayment,
                trampolineFees = paymentAttempt.fees,
                actions = either.value
            )
        }
    }

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
            logger.error { "paymentId doesn't match any known payment attempt" }
            return ProcessFailureResult.UnknownPaymentFailure
        }

        // Mark channelID as failed - we won't retry on this channel (for this paymentAttempt)
        paymentAttempt.failedChannelIds.add(event.channelId)

        if (event.error is ChannelException) {
            paymentAttempt.failedChannelProblems.add(Either.Left(event.error))
        }

        val idx = paymentAttempt.parts.indexOfFirst { it.channelId == event.channelId }
        if (idx >= 0) {
            paymentAttempt.parts.removeAt(idx)
        }

        pending.remove(paymentAttempt.paymentId)

        return when (val either = setupPaymentAttempt(paymentAttempt, channels, currentBlockHeight)) {
            is Either.Left -> ProcessFailureResult.Failure(paymentAttempt.sendPayment, either.value)
            is Either.Right -> ProcessFailureResult.Progress(
                payment = paymentAttempt.sendPayment,
                trampolineFees = paymentAttempt.fees,
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
            logger.error { "paymentId doesn't match any known payment attempt" }
            return ProcessFailureResult.UnknownPaymentFailure
        }

        // There are only two retriable remote failures: trampoline_fee_insufficient or trampoline_expiry_too_soon.
        // Otherwise we must fail the payment.
        val shouldRetry = when (event) {
            is ProcessFail -> {
                val part = paymentAttempt.parts.firstOrNull { it.channelId == event.channelId }
                if (part != null) {
                    when (val decrypted = FailurePacket.decrypt(event.fail.reason.toByteArray(), part.secrets)) {
                        is Try.Success -> {
                            val failureMsg = decrypted.result.failureMessage
                            paymentAttempt.problems.add(Either.Right(failureMsg))
                            (failureMsg == TrampolineFeeInsufficient || failureMsg == TrampolineExpiryTooSoon)
                        }
                        is Try.Failure -> false
                    }
                } else {
                    false
                }
            }
            is ProcessFailMalformed -> false
        }

        pending.remove(paymentAttempt.paymentId)

        if (!shouldRetry) {
            return ProcessFailureResult.Failure(
                payment = paymentAttempt.sendPayment,
                failure = OutgoingPaymentFailure.make(
                    reason = OutgoingPaymentFailure.Reason.OTHER_ERROR,
                    problems = paymentAttempt.allProblems()
                )
            )
        }

        // increment failedAttempts, and jump to next TrampolineParams (if possible)
        val nextPaymentAttempt = paymentAttempt.nextPaymentAttempt()
        if (nextPaymentAttempt == null) {
            return ProcessFailureResult.Failure(
                payment = paymentAttempt.sendPayment,
                failure = OutgoingPaymentFailure.make(
                    reason = OutgoingPaymentFailure.Reason.NO_ROUTE_TO_RECIPIENT,
                    problems = paymentAttempt.allProblems()
                )
            )
        }

        return when (val either = setupPaymentAttempt(nextPaymentAttempt, channels, currentBlockHeight)) {
            is Either.Left -> ProcessFailureResult.Failure(nextPaymentAttempt.sendPayment, either.value)
            is Either.Right -> ProcessFailureResult.Progress(
                payment = nextPaymentAttempt.sendPayment,
                trampolineFees = nextPaymentAttempt.fees,
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

        pending.remove(paymentAttempt.paymentId)
        return ProcessFulfillResult.Success(
            payment = paymentAttempt.sendPayment,
            trampolineFees = paymentAttempt.fees
        )
    }

    private fun setupPaymentAttempt(
        paymentAttempt: PaymentAttempt,
        channels: Map<ByteVector32, ChannelState>,
        currentBlockHeight: Int
    ): Either<OutgoingPaymentFailure, List<PeerEvent>> {

        // Channels have a balance & capacity, but they also have other limits.
        // And these other limits affect a channel's ability to send a payment.
        data class ChannelState(
            val availableForSend: MilliSatoshi,
            val htlcMinimumMsat: MilliSatoshi
        )

        val sortedChannels = channels.values.filterIsInstance<Normal>().filterNot {
            // exclude any channels that have previously failed
            paymentAttempt.failedChannelIds.contains(it.channelId)
        }.map {
            Pair(
                it, ChannelState(
                    availableForSend = it.commitments.availableBalanceForSend(),
                    htlcMinimumMsat = it.commitments.remoteParams.htlcMinimum
                )
            )
        }.sortedBy {
            it.second.availableForSend
        }.reversed()

        if (sortedChannels.isEmpty()) {
            return Either.Left(
                OutgoingPaymentFailure.make(
                    reason = OutgoingPaymentFailure.Reason.NO_AVAILABLE_CHANNELS,
                    problems = paymentAttempt.allProblems()
                )
            )
        }

        val totalAvailableForSend = sortedChannels.map { it.second.availableForSend }.sum()
        if (paymentAttempt.paymentAmount > totalAvailableForSend) {
            return Either.Left(
                OutgoingPaymentFailure.make(
                    reason = OutgoingPaymentFailure.Reason.INSUFFICIENT_BALANCE_BASE,
                    problems = paymentAttempt.allProblems()
                )
            )
        }
        if (paymentAttempt.paymentAmount + paymentAttempt.fees > totalAvailableForSend) {
            return Either.Left(
                OutgoingPaymentFailure.make(
                    reason = OutgoingPaymentFailure.Reason.INSUFFICIENT_BALANCE_FEES,
                    problems = paymentAttempt.allProblems()
                )
            )
        }

        // Phase 1: No mpp.
        // We're only sending over a single channel for right now.

        val amountToSend = paymentAttempt.paymentAmount + paymentAttempt.fees

        val filteredChannels = sortedChannels.filter {
            amountToSend >= it.second.htlcMinimumMsat
        }
        if (filteredChannels.isEmpty()) {
            val closest = sortedChannels.maxByOrNull { it.second.htlcMinimumMsat }!!
            val channelException = HtlcValueTooSmall(
                channelId = closest.first.channelId,
                minimum = closest.second.htlcMinimumMsat,
                actual = amountToSend
            )
            paymentAttempt.problems.add(Either.Left(channelException))
            return Either.Left(
                OutgoingPaymentFailure.make(
                    reason = OutgoingPaymentFailure.Reason.OTHER_ERROR,
                    problems = paymentAttempt.allProblems()
                )
            )
        }

        val selectedChannel = filteredChannels.first()
        val actions = listOf<PeerEvent>(
            // createHtlc: part => (onion w/ trampoline) => CMD_ADD_HTLC => WrappedChannelEvent
            createHtlc(selectedChannel.first, paymentAttempt, currentBlockHeight)
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
    private fun createHtlc(
        channel: Normal,
        paymentAttempt: PaymentAttempt,
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
                fee = paymentAttempt.fees
            )
        )

        val (trampolineAmount, trampolineExpiry, trampolineOnion) = if (recipientSupportsTrampoline) {
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
            amount = trampolineAmount,
            totalAmount = trampolineAmount,
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
            amount = trampolineAmount,
            trampolineFees = paymentAttempt.fees,
            cltvExpiryDelta = paymentAttempt.trampolineParams.cltvExpiryDelta,
            secrets = secrets
        )
        paymentAttempt.parts.add(part)

        return WrappedChannelEvent(channelId = channel.channelId, channelEvent = ExecuteCommand(cmdAddHtlc))
    }
}
