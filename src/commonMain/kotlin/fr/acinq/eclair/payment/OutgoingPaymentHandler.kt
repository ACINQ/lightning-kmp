package fr.acinq.eclair.payment

import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.PublicKey
import fr.acinq.bitcoin.Satoshi
import fr.acinq.eclair.*
import fr.acinq.eclair.channel.*
import fr.acinq.eclair.crypto.sphinx.FailurePacket
import fr.acinq.eclair.crypto.sphinx.PacketAndSecrets
import fr.acinq.eclair.io.PeerEvent
import fr.acinq.eclair.io.SendPayment
import fr.acinq.eclair.io.WrappedChannelError
import fr.acinq.eclair.io.WrappedChannelEvent
import fr.acinq.eclair.router.ChannelHop
import fr.acinq.eclair.router.NodeHop
import fr.acinq.eclair.utils.*
import fr.acinq.eclair.wire.FinalPayload
import fr.acinq.eclair.wire.OnionRoutingPacket
import fr.acinq.eclair.wire.TrampolineExpiryTooSoon
import fr.acinq.eclair.wire.TrampolineFeeInsufficient

class OutgoingPaymentHandler(val nodeParams: NodeParams) {

    enum class FailureReason {
        INVALID_PARAMETER, // e.g. (paymentAmount < 0), (recycled paymentId)
        NO_AVAILABLE_CHANNELS, // There are zero channels in Normal mode
        INSUFFICIENT_BALANCE, // Not enough capacity in channel(s) to support payment
        NO_ROUTE_TO_RECIPIENT, // Trampoline was unable to find an acceptable route
    }

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
            val reason: FailureReason
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
            val reason: FailureReason
        ) : ProcessFailureResult()

        object UnknownPaymentFailure : ProcessFailureResult()
    }

    sealed class ProcessFulfillResult {

        data class Success(
            val payment: SendPayment,
            val trampolineFees: MilliSatoshi
        ) : ProcessFulfillResult()

        data class Failure(
            val payment: SendPayment,
            val reason: FailureReason
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
        val secrets: List<Pair<ByteVector32, PublicKey>>
    ) {
        init {
            require(amount >= trampolineFees) { "amount is invalid" }
        }
    }

    /**
     * Represents the current state of a payment attempt.
     */
    data class PaymentAttempt(
        val sendPayment: SendPayment,
        val trampolineParams: TrampolineParams,
        val failedAttempts: Int
    ) {
        val trampolinePaymentSecret = Eclair.randomBytes32() // must be different from invoice.paymentSecret
        val parts = mutableListOf<TrampolinePaymentPart>()
        val failedChannelIds = mutableSetOf<ByteVector32>()

        val paymentId: UUID = sendPayment.paymentId
        val invoice: PaymentRequest = sendPayment.paymentRequest
        val paymentAmount: MilliSatoshi = sendPayment.paymentAmount

        /**
         * Removes the corresponding TrampolinePaymentPart, and adds the channelId to the failed list.
         */
        fun fail(channelId: ByteVector32) {

            val idx = parts.indexOfFirst { it.channelId == channelId }
            if (idx >= 0) {
                parts.removeAt(idx)
            }

            failedChannelIds.add(channelId)
        }

        fun totalFees(): MilliSatoshi {
            return trampolineParams.feeBaseSat.toMilliSatoshi() + (paymentAmount * trampolineParams.feePercent)
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
            return SendPaymentResult.Failure(sendPayment, FailureReason.INVALID_PARAMETER)
        }
        if (pending.containsKey(sendPayment.paymentId)) {
            logger.error { "contract violation: caller is recycling uuid's" }
            return SendPaymentResult.Failure(sendPayment, FailureReason.INVALID_PARAMETER)
        }

        val failedAttempts = 0
        val params = TrampolineParams.get(failedAttempts)!!
        val paymentAttempt = PaymentAttempt(sendPayment, params, failedAttempts)

        return when (val either = setupPaymentAttempt(paymentAttempt, channels, currentBlockHeight)) {
            is Either.Left -> SendPaymentResult.Failure(sendPayment, either.value)
            is Either.Right -> SendPaymentResult.Progress(
                payment = paymentAttempt.sendPayment,
                trampolineFees = paymentAttempt.totalFees(),
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
            logger.error { "CMD_ADD_HTLC.paymentId doesn't match any known paymentAttempt" }
            return ProcessFailureResult.UnknownPaymentFailure
        }

        // Mark the channel as failed
        paymentAttempt.fail(event.channelId)

        return when (val either = setupPaymentAttempt(paymentAttempt, channels, currentBlockHeight)) {
            is Either.Left -> ProcessFailureResult.Failure(paymentAttempt.sendPayment, either.value)
            is Either.Right -> ProcessFailureResult.Progress(
                payment = paymentAttempt.sendPayment,
                trampolineFees = paymentAttempt.totalFees(),
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
            logger.error { "ProcessFailure.paymentId doesn't match any known paymentAttempt" }
            return ProcessFailureResult.UnknownPaymentFailure
        }

        // What is the failure reason ?
        // - is trampoline_fee_insufficient || is trampoline_expiry_too_soon -> next trampolineParams
        // - else -> mark channel as failed (without increasing fees)

        var isChannelFailure = true
        val part = paymentAttempt.parts.firstOrNull { it.channelId == event.channelId }
        if (part != null && event is ProcessFail) {

            val decrypted = FailurePacket.decrypt(event.fail.reason.toByteArray(), part.secrets)
            val failureMessage = when (decrypted) {
                is Try.Failure -> null
                is Try.Success -> decrypted.result.failureMessage
            }
            isChannelFailure = when (failureMessage) {
                is TrampolineFeeInsufficient -> false
                is TrampolineExpiryTooSoon -> false
                else -> true
            }
        }

        var nextPaymentAttempt: PaymentAttempt? = null
        if (isChannelFailure) {

            paymentAttempt.fail(event.channelId) // mark channel as failed
            nextPaymentAttempt = paymentAttempt // and try again with same fee structure

        } else {

            val failedAttempts = paymentAttempt.failedAttempts + 1
            val trampolineParams = TrampolineParams.get(failedAttempts) // increase fee structure
            if (trampolineParams != null) {
                nextPaymentAttempt = paymentAttempt.copy(
                    trampolineParams = trampolineParams,
                    failedAttempts = failedAttempts
                )
            }
        }

        pending.remove(paymentAttempt.paymentId)
        if (nextPaymentAttempt == null) {
            return ProcessFailureResult.Failure(paymentAttempt.sendPayment, FailureReason.NO_ROUTE_TO_RECIPIENT)
        }

        return when (val either = setupPaymentAttempt(nextPaymentAttempt, channels, currentBlockHeight)) {
            is Either.Left -> ProcessFailureResult.Failure(nextPaymentAttempt.sendPayment, either.value)
            is Either.Right -> ProcessFailureResult.Progress(
                payment = nextPaymentAttempt.sendPayment,
                trampolineFees = nextPaymentAttempt.totalFees(),
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

        pending.remove(paymentAttempt.paymentId)
        return ProcessFulfillResult.Success(
            payment = paymentAttempt.sendPayment,
            trampolineFees = paymentAttempt.totalFees()
        )
    }

    private fun setupPaymentAttempt(
        paymentAttempt: PaymentAttempt,
        channels: Map<ByteVector32, ChannelState>,
        currentBlockHeight: Int
    ): Either<FailureReason, List<PeerEvent>> {

        val recipientAmount = paymentAttempt.paymentAmount
        val trampolineFees = paymentAttempt.totalFees()

        var availableChannels = channels.values.filterIsInstance<Normal>().filterNot {
            paymentAttempt.failedChannelIds.contains(it.channelId)
        }.filter {
            var result = true
            if ((recipientAmount + trampolineFees) < it.channelUpdate.htlcMinimumMsat) {
                result = false
            }
            it.channelUpdate.htlcMaximumMsat?.let { htlcMaximumMsat ->
                if ((recipientAmount + trampolineFees) > htlcMaximumMsat) {
                    result = false
                }
            }
            result
        }
        if (availableChannels.isEmpty()) {
            return Either.Left(FailureReason.NO_AVAILABLE_CHANNELS)
        }

        // Calculating the `availableBalanceForSend` in each channel requires a bit of work.
        // And we need to reference this info repeatedly below, so we calculate it once here.
        val channelBalances = availableChannels.associateBy(keySelector = {
            it.channelId
        }, valueTransform = {
            it.commitments.availableBalanceForSend()
        })

        // Sort the channels by send capacity.
        // The channel with the highest/most availableForSend will be at the beginning of the array (index 0)

        availableChannels = availableChannels.sortedWith(compareBy<Normal> {
            channelBalances[it.channelId]!!
        }.reversed().thenBy {
            // If multiple channels have the same balance, we use shortChannelId to sort deterministically
            it.shortChannelId
        })

        channelBalances.forEach {
            logger.info { "channel(${it.key}): available for send = ${it.value}" }
        }

        // Phase 1: No mpp.
        // We're only sending over a single channel for right now.

        val selectedChannel = availableChannels[0] // already tested for empty array above
        val availableForSend = channelBalances[selectedChannel.channelId]!!

        if ((recipientAmount + trampolineFees) > availableForSend) {
            logger.error {
                "insufficient capacity to send payment:" +
                        " attempted(${recipientAmount + trampolineFees})" +
                        " available(${availableForSend})"
            }
            return Either.Left(FailureReason.INSUFFICIENT_BALANCE)
        }

        val actions = listOf<PeerEvent>(
            // createHtlc: part => (onion w/ trampoline) => CMD_ADD_HTLC => WrappedChannelEvent
            createHtlc(selectedChannel, paymentAttempt, currentBlockHeight)
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
                fee = paymentAttempt.totalFees()
            )
        )

        val trampolineAmount: MilliSatoshi
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
            trampolineAmount = triple.first
            trampolineExpiry = triple.second
            trampolineOnion = triple.third
        } else {
            // Legacy workaround
            throw RuntimeException("Not implemented")
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
            trampolineFees = paymentAttempt.totalFees(),
            cltvExpiryDelta = paymentAttempt.trampolineParams.cltvExpiryDelta,
            secrets = secrets
        )
        paymentAttempt.parts.add(part)

        return WrappedChannelEvent(channelId = channel.channelId, channelEvent = ExecuteCommand(cmdAddHtlc))
    }
}