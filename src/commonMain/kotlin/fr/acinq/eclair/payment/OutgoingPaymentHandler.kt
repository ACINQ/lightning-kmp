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

class OutgoingPaymentHandler(val nodeParams: NodeParams) {

    sealed class SendPaymentResult {
        /** A payment attempt has been made: we provide information about the fees we're paying, which may increase as we re-try our payment. */
        data class Progress(val payment: SendPayment, val trampolineFees: MilliSatoshi, val actions: List<PeerEvent>) : SendPaymentResult()

        /** The payment could not be sent. */
        data class Failure(val payment: SendPayment, val failure: OutgoingPaymentFailure) : SendPaymentResult()
    }

    sealed class ProcessFailureResult {
        /** The payment has been retried: we provide information about the updated fees we're paying. */
        data class Progress(val payment: SendPayment, val trampolineFees: MilliSatoshi, val actions: List<PeerEvent>) : ProcessFailureResult()

        /** The payment could not be sent. */
        data class Failure(val payment: SendPayment, val failure: OutgoingPaymentFailure) : ProcessFailureResult()

        /** The payment is unknown. */
        object UnknownPaymentFailure : ProcessFailureResult()
    }

    // Only those result types that can possibly be returned from `OutgoingPaymentHandler.processFulfill()`
    sealed class ProcessFulfillResult {
        /** The payment was successfully made. */
        data class Success(val payment: SendPayment, val trampolineFees: MilliSatoshi) : ProcessFulfillResult()

        /** The payment could not be sent. */
        data class Failure(val payment: SendPayment, val failure: OutgoingPaymentFailure) : ProcessFulfillResult()

        /** The payment is unknown. */
        object UnknownPaymentFailure : ProcessFulfillResult()
    }

    /**
     * When we send a trampoline payment, we start with a low fee.
     * If that fails, we increase the fee(s) and retry (up to a point).
     * This class encapsulates the increase that occurs at a particular retry.
     */
    data class TrampolineParams(val feeBaseSat: Satoshi, val feePercent: Float, val cltvExpiryDelta: CltvExpiryDelta) {
        constructor(feeBaseSat: Long, feePercent: Float, cltvExpiryDelta: Int) : this(Satoshi(feeBaseSat), feePercent, CltvExpiryDelta(cltvExpiryDelta))

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
        val fees: MilliSatoshi get() = trampolineParams.feeBaseSat.toMilliSatoshi() + (paymentAmount * trampolineParams.feePercent)

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

    fun sendPayment(sendPayment: SendPayment, channels: Map<ByteVector32, ChannelState>, currentBlockHeight: Int): SendPaymentResult {
        if (sendPayment.paymentAmount <= 0.msat) {
            logger.warning { "paymentAmount(${sendPayment.paymentAmount}) must be positive" }
            return SendPaymentResult.Failure(sendPayment, FailureReason.InvalidPaymentAmount.toPaymentFailure())
        }
        if (pending.containsKey(sendPayment.paymentId)) {
            logger.error { "contract violation: caller is recycling uuid's" }
            return SendPaymentResult.Failure(sendPayment, FailureReason.InvalidPaymentId.toPaymentFailure())
        }

        val paymentAttempt = PaymentAttempt(sendPayment)
        return when (val either = setupPaymentAttempt(paymentAttempt, channels, currentBlockHeight)) {
            is Either.Left -> SendPaymentResult.Failure(sendPayment, either.value)
            is Either.Right -> SendPaymentResult.Progress(paymentAttempt.sendPayment, paymentAttempt.fees, either.value)
        }
    }

    fun processLocalFailure(event: WrappedChannelError, channels: Map<ByteVector32, ChannelState>, currentBlockHeight: Int): ProcessFailureResult? {
        // We are looking for errors from the channel in response to our CMD_ADD_HTLC request
        val cmdAddHtlc = (event.trigger as? ExecuteCommand)?.command as? CMD_ADD_HTLC ?: return null

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
            is Either.Right -> ProcessFailureResult.Progress(paymentAttempt.sendPayment, paymentAttempt.fees, either.value)
        }
    }

    fun processRemoteFailure(event: ProcessRemoteFailure, channels: Map<ByteVector32, ChannelState>, currentBlockHeight: Int): ProcessFailureResult {
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
            return ProcessFailureResult.Failure(paymentAttempt.sendPayment, OutgoingPaymentFailure(FailureReason.UnknownError, paymentAttempt.allProblems()))
        }

        // increment failedAttempts, and jump to next TrampolineParams (if possible)
        val nextPaymentAttempt = paymentAttempt.nextPaymentAttempt()
        if (nextPaymentAttempt == null) {
            return ProcessFailureResult.Failure(paymentAttempt.sendPayment, OutgoingPaymentFailure(FailureReason.NoRouteToRecipient, paymentAttempt.allProblems()))
        }

        return when (val either = setupPaymentAttempt(nextPaymentAttempt, channels, currentBlockHeight)) {
            is Either.Left -> ProcessFailureResult.Failure(nextPaymentAttempt.sendPayment, either.value)
            is Either.Right -> ProcessFailureResult.Progress(nextPaymentAttempt.sendPayment, nextPaymentAttempt.fees, either.value)
        }
    }

    fun processFulfill(event: ProcessFulfill): ProcessFulfillResult {
        val paymentAttempt = pending[event.paymentId]
        if (paymentAttempt == null) {
            logger.error { "paymentId doesn't match any known payment attempt" }
            return ProcessFulfillResult.UnknownPaymentFailure
        }

        pending.remove(paymentAttempt.paymentId)
        return ProcessFulfillResult.Success(paymentAttempt.sendPayment, paymentAttempt.fees)
    }

    private fun setupPaymentAttempt(paymentAttempt: PaymentAttempt, channels: Map<ByteVector32, ChannelState>, currentBlockHeight: Int): Either<OutgoingPaymentFailure, List<PeerEvent>> {
        // Channels have a balance & capacity, but they also have other limits.
        // And these other limits affect a channel's ability to send a payment.
        data class ChannelState(val availableForSend: MilliSatoshi, val htlcMinimumMsat: MilliSatoshi)

        val sortedChannels = channels.values.filterIsInstance<Normal>().filterNot {
            // exclude any channels that have previously failed
            paymentAttempt.failedChannelIds.contains(it.channelId)
        }.map {
            Pair(it, ChannelState(it.commitments.availableBalanceForSend(), it.commitments.remoteParams.htlcMinimum))
        }.sortedBy {
            it.second.availableForSend
        }.reversed()

        if (sortedChannels.isEmpty()) {
            return Either.Left(OutgoingPaymentFailure(FailureReason.NoAvailableChannels, paymentAttempt.allProblems()))
        }

        val totalAvailableForSend = sortedChannels.map { it.second.availableForSend }.sum()
        if (paymentAttempt.paymentAmount > totalAvailableForSend) {
            return Either.Left(OutgoingPaymentFailure(FailureReason.InsufficientBalanceBase, paymentAttempt.allProblems()))
        }
        if (paymentAttempt.paymentAmount + paymentAttempt.fees > totalAvailableForSend) {
            return Either.Left(OutgoingPaymentFailure(FailureReason.InsufficientBalanceFees, paymentAttempt.allProblems()))
        }

        // Phase 1: No mpp.
        // We're only sending over a single channel for right now.

        val amountToSend = paymentAttempt.paymentAmount + paymentAttempt.fees

        val filteredChannels = sortedChannels.filter {
            amountToSend >= it.second.htlcMinimumMsat
        }
        if (filteredChannels.isEmpty()) {
            val closest = sortedChannels.maxByOrNull { it.second.htlcMinimumMsat }!!
            val channelException = HtlcValueTooSmall(closest.first.channelId, closest.second.htlcMinimumMsat, amountToSend)
            paymentAttempt.problems.add(Either.Left(channelException))
            return Either.Left(OutgoingPaymentFailure(FailureReason.UnknownError, paymentAttempt.allProblems()))
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
    private fun createHtlc(channel: Normal, paymentAttempt: PaymentAttempt, currentBlockHeight: Int): WrappedChannelEvent {
        val finalExpiryDelta = paymentAttempt.invoice.minFinalExpiryDelta ?: Channel.MIN_CLTV_EXPIRY_DELTA // default value if unspecified, as per Bolt 11
        val finalExpiry = finalExpiryDelta.toCltvExpiry(currentBlockHeight.toLong())

        val features = paymentAttempt.invoice.features?.let { Features(it) } ?: Features(setOf())
        val recipientSupportsTrampoline = features.hasFeature(Feature.TrampolinePayment)

        val finalPayload = FinalPayload.createSinglePartPayload(paymentAttempt.paymentAmount, finalExpiry, paymentAttempt.invoice.paymentSecret)
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
            OutgoingPacket.buildPacket(paymentAttempt.invoice.paymentHash, nodeHops, finalPayload, OnionRoutingPacket.TrampolinePacketLength)
        } else {
            // Legacy workaround
            OutgoingPacket.buildTrampolineToLegacyPacket(paymentAttempt.invoice, nodeHops, finalPayload)
        }

        val trampolinePayload = FinalPayload.createTrampolinePayload(trampolineAmount, trampolineAmount, trampolineExpiry, paymentAttempt.trampolinePaymentSecret, trampolineOnion.packet)

        val channelHops: List<ChannelHop> = listOf(
            ChannelHop(
                nodeId = channel.staticParams.nodeParams.nodeId, // us
                nextNodeId = channel.staticParams.remoteNodeId, // trampoline node (acinq)
                lastUpdate = channel.channelUpdate
            )
        )
        val (cmdAddHtlc, secrets) = OutgoingPacket.buildCommand(paymentAttempt.paymentId, paymentAttempt.invoice.paymentHash, channelHops, trampolinePayload)
        val part = TrampolinePaymentPart(channel.channelId, trampolineAmount, paymentAttempt.fees, paymentAttempt.trampolineParams.cltvExpiryDelta, secrets)
        paymentAttempt.parts.add(part)

        return WrappedChannelEvent(channel.channelId, ExecuteCommand(cmdAddHtlc))
    }
}
