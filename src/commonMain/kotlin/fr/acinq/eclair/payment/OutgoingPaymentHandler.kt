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
import fr.acinq.eclair.utils.*
import fr.acinq.eclair.wire.*
import kotlin.math.ceil


class OutgoingPaymentHandler(
    val nodeParams: NodeParams
) {

    enum class FailureReason {
        INVALID_PARAMETER, // e.g. (paymentAmount < 0), (recycled paymentId)
        PAYMENT_AMOUNT_TOO_SMALL, // paymentAmount < invoiceAmount
        PAYMENT_AMOUNT_TOO_BIG, // paymentAmount > invoiceAmount * 2 (bolt 4)
        NO_AVAILABLE_CHANNELS, // There are zero channels in Normal mode
        INSUFFICIENT_CAPACITY_BASE, // Not enough capacity in channel(s) to support paymentAmount
        INSUFFICIENT_CAPACITY_FEES, // Not enough capacity in channel(s) after accounting for fees
        CHANNEL_CAP_RESTRICTION, // e.g. htlcMaximumAmount, maxHtlcValueInFlight, maxAcceptedHtlcs
    }

    sealed class Result {

        // The `Progress` result is emitted throughout the payment attempt.
        // It includes information about the fees we're paying, which may increase as we re-try our payment.
        data class Progress(
            val payment: SendPayment,
            val trampolineFees: MilliSatoshi,
            val actions: List<PeerEvent>
        ): Result()

        data class Success(
            val payment: SendPayment,
            val trampolineFees: MilliSatoshi
        ): Result()

        data class Failure(
            val payment: SendPayment,
            val reason: FailureReason
        ): Result()

        object FailureUnknownPayment: Result()
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
    data class PaymentAdjustmentSchedule(
        val feeBaseSat: Satoshi,
        val feePercent: Double,
        val cltvExpiryDelta: CltvExpiryDelta
    ) {
        constructor(feeBaseSat: Long, feePercent: Double, cltvExpiryDelta: Int) :
            this(Satoshi(feeBaseSat), feePercent, CltvExpiryDelta(cltvExpiryDelta))

        companion object {

            // Todo: Fetch this from the server first, and have it passed into us somehow...
            fun get(failedAttempts: Int): PaymentAdjustmentSchedule? {
                return when (failedAttempts) {
                    0 -> PaymentAdjustmentSchedule(0, 0.0, 576)
                    1 -> PaymentAdjustmentSchedule(1, 0.0001, 576)
                    2 -> PaymentAdjustmentSchedule(3, 0.0001, 576)
                    3 -> PaymentAdjustmentSchedule(5, 0.0005, 576)
                    4 -> PaymentAdjustmentSchedule(5, 0.001, 576)
                    5 -> PaymentAdjustmentSchedule(5, 0.0012, 576)
                    else -> null
                }
            }

            fun all(): List<PaymentAdjustmentSchedule> {
                var results = mutableListOf<PaymentAdjustmentSchedule>()
                var failedAttempts = 0
                var schedule = get(failedAttempts)
                while (schedule != null) {
                    results.add(schedule)
                    schedule = get(++failedAttempts)
                }
                return results
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
         * The total amount we're paying. Can be fetched either including or excluding fees.
         */
        fun totalAmount(includingFees: Boolean = true): MilliSatoshi {
            return parts.map {
                if (includingFees) it.amount else (it.amount - it.trampolineFees)
            }.sum()
        }

        /**
         * The total amount of fees we're paying to the trampoline node.
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
    ): Result {

        if (sendPayment.paymentAmount <= 0.msat) {
            logger.warning { "paymentAmount(${sendPayment.paymentAmount}) must be positive" }
            return Result.Failure(sendPayment, FailureReason.INVALID_PARAMETER)
        }
        if (pending.containsKey(sendPayment.paymentId)) {
            logger.error { "contract violation: caller is recycling uuid's" }
            return Result.Failure(sendPayment, FailureReason.INVALID_PARAMETER)
        }

        val invoiceAmount = sendPayment.paymentRequest.amount
        if (invoiceAmount != null) {
            // if invoiceAmount is non-null, it must be positive, as per spec (& enforced in PaymentRequest.init)
            if (sendPayment.paymentAmount < invoiceAmount) {
                logger.warning { "paymentAmount(${sendPayment.paymentAmount}) must be at least invoiceAmount(${invoiceAmount})" }
                return Result.Failure(sendPayment, FailureReason.PAYMENT_AMOUNT_TOO_SMALL)
            }
            if (sendPayment.paymentAmount > invoiceAmount * 2) {
                logger.warning { "paymentAmount(${sendPayment.paymentAmount}) cannot exceed invoiceAmount(${invoiceAmount}) * 2" }
                return Result.Failure(sendPayment, FailureReason.PAYMENT_AMOUNT_TOO_BIG)
            }
        }

        val failedAttempts = 0
        val schedule = PaymentAdjustmentSchedule.get(failedAttempts)!!
        val paymentAttempt = PaymentAttempt(sendPayment, failedAttempts)

        return setupPaymentAttempt(paymentAttempt, schedule, channels, currentBlockHeight)
    }

    fun processFailure(
        event: ProcessFailure, // ProcessFail || ProcessFailMalformed
        channels: Map<ByteVector32, ChannelState>,
        currentBlockHeight: Int
    ): Result {

        val paymentId: UUID
        val channelId: ByteVector32
        when (event) {
            is ProcessFail -> {
                paymentId = event.paymentId
                channelId = event.fail.channelId
            }
            is ProcessFailMalformed -> {
                paymentId = event.paymentId
                channelId = event.fail.channelId
            }
        }

        val paymentAttempt = pending[paymentId]
        if (paymentAttempt == null) {
            logger.error { "ProcessFailure.origin doesn't match any known paymentAttempt" }
            return Result.FailureUnknownPayment
        }

        // Mark the TrampolinePaymentPart.status as FAILED
        paymentAttempt.fail(channelId)

        // Now that all the parts have failed, we can try to start another paymentAttempt.
        pending.remove(paymentAttempt.paymentId)

        val failedAttempts = paymentAttempt.failedAttempts + 1
        val schedule = PaymentAdjustmentSchedule.get(failedAttempts)
        if (schedule == null) {
            return Result.Failure(paymentAttempt.sendPayment, FailureReason.INSUFFICIENT_CAPACITY_FEES)
        }

        val newPaymentAttempt = PaymentAttempt(paymentAttempt.sendPayment, failedAttempts)
        return setupPaymentAttempt(newPaymentAttempt, schedule, channels, currentBlockHeight)
    }

    fun processFulfill(
        event: ProcessFulfill
    ): Result {

        val paymentAttempt = pending[event.paymentId]
        if (paymentAttempt == null) {
            logger.error { "ProcessFail.origin doesn't match any known paymentAttempt" }
            return Result.FailureUnknownPayment
        }

        // Mark the TrampolinePaymentPart.status as SUCCEEDED
        paymentAttempt.succeed(event.fulfill.channelId)

        // Now that all the parts have succeeded, we can announce success
        pending.remove(paymentAttempt.paymentId)
        return Result.Success(
            payment = paymentAttempt.sendPayment,
            trampolineFees = paymentAttempt.totalFees()
        )
    }

    private fun setupPaymentAttempt(
        paymentAttempt: PaymentAttempt,
        schedule: PaymentAdjustmentSchedule,
        channels: Map<ByteVector32, ChannelState>,
        currentBlockHeight: Int
    ): Result {

        val availableChannels = channels.values.filterIsInstance<Normal>().toMutableList()
        if (availableChannels.size == 0) {
            return Result.Failure(paymentAttempt.sendPayment, FailureReason.NO_AVAILABLE_CHANNELS)
        }

        // Calculating the `availableBalanceForSend` in each channel requires a bit of work.
        // And we need to reference this info repeatedly below, so we calculate it once here.
        val availableBalancesForSend = availableChannels.associateBy(keySelector = {
            it.channelId
        }, valueTransform = {
            it.commitments.availableBalanceForSend()
        })

        // Sort the channels by send capacity.
        // The channel with the highest/most availableForSend will be at the beginning of the array (index 0)
        availableChannels.sortWith(compareBy<Normal> {
            availableBalancesForSend[it.channelId]!!
        }.reversed().thenBy {
            // If multiple channels have the same balance, we use shortChannelId to sort deterministically
            it.shortChannelId
        })

        availableBalancesForSend.forEach {
            logger.info { "channel(${it.key}): available for send = ${it.value}" }
        }

        // Phase 1: No mpp.
        // We're only sending over a single channel for right now.

        val selectedChannel = availableChannels[0] // already tested for empty array above
        val availableForSend = availableBalancesForSend[selectedChannel.channelId]!!

        val recipientAmount = paymentAttempt.paymentAmount
        val trampolineFees = schedule.feeBaseSat.toMilliSatoshi() + (recipientAmount * schedule.feePercent)

        if ((recipientAmount + trampolineFees) > availableForSend) {
            logger.error {
                "insufficient capacity to send payment:" +
                    " attempted(${recipientAmount + trampolineFees})" +
                    " available(${availableForSend})"
            }
            // The average user (non-bitcoin-pro) is accustomed to the credit card model,
            // where the merchant pays fees, but the customer does not.
            // Receiving a generic "insufficient capacity" error message could easily be confusing
            // if the base capacity exists. Thus differentiating these cases could prove useful for the UI.
            return if (recipientAmount > availableForSend) {
                Result.Failure(paymentAttempt.sendPayment, FailureReason.INSUFFICIENT_CAPACITY_BASE)
            } else {
                Result.Failure(paymentAttempt.sendPayment, FailureReason.INSUFFICIENT_CAPACITY_FEES)
            }
        }

        // Check for channel cap restrictions

        selectedChannel.channelUpdate.htlcMinimumMsat?.let { htlcMinimumMsat ->
            if ((recipientAmount + trampolineFees) < htlcMinimumMsat) {
                logger.error {
                    "payment is below channel's htlcMinimumMsat:" +
                            " attempted(${recipientAmount + trampolineFees})" +
                            " htlcMinimumMsat(${htlcMinimumMsat})"
                }
                return@setupPaymentAttempt Result.Failure(paymentAttempt.sendPayment, FailureReason.CHANNEL_CAP_RESTRICTION)
            }
        }

        selectedChannel.channelUpdate.htlcMaximumMsat?.let { htlcMaximumMsat ->
            if ((recipientAmount + trampolineFees) > htlcMaximumMsat) {
                logger.error {
                    "payment is above channel's htlcMaximumMsat:" +
                        " attempted(${recipientAmount + trampolineFees})" +
                        " htlcMaximumMsat(${htlcMaximumMsat})"
                }
                return@setupPaymentAttempt Result.Failure(paymentAttempt.sendPayment, FailureReason.CHANNEL_CAP_RESTRICTION)
            }
        }

        val part = TrampolinePaymentPart(
            channelId = selectedChannel.channelId,
            amount = recipientAmount + trampolineFees,
            trampolineFees = trampolineFees,
            cltvExpiryDelta = schedule.cltvExpiryDelta,
            status = Status.INFLIGHT
        )
        paymentAttempt.parts.add(part)

        val actions = listOf<PeerEvent>(
            // actionify: part => (onion w/ trampoline) => CMD_ADD_HTLC => WrappedChannelEvent
            actionify(selectedChannel, paymentAttempt, part, currentBlockHeight)
        )

        pending[paymentAttempt.paymentId] = paymentAttempt
        return Result.Progress(
            payment = paymentAttempt.sendPayment,
            trampolineFees = paymentAttempt.totalFees(),
            actions = actions
        )
    }

    /**
     * Converts the TrampolinePaymentPart into an actionable PeerEvent.
     * The process involves:
     * - Creating the onion (with trampoline_onion component)
     * - Creating the CMD_ADD_HTLC
     * - Creating the WrappedChannelEvent
     */
    fun actionify(
        channel: Normal,
        paymentAttempt: PaymentAttempt,
        part: TrampolinePaymentPart,
        currentBlockHeight: Int
    ): WrappedChannelEvent {

        val finalExpiryDelta = paymentAttempt.invoice.minFinalExpiryDelta
            ?: Channel.MIN_CLTV_EXPIRY_DELTA // default value if unspecified, as per Bolt 11
        val finalExpiry = (part.cltvExpiryDelta + finalExpiryDelta).toCltvExpiry(currentBlockHeight.toLong())

        val features = paymentAttempt.invoice.features?.let { Features(it) } ?: Features(setOf())
        val recipientSupportsMpp = features.hasFeature(Feature.BasicMultiPartPayment)
        val recipientSupportsTrampoline = features.hasFeature(Feature.TrampolinePayment)

        val paymentSecret = paymentAttempt.invoice.paymentSecret

        // The matrix of possibilities:
        //                          supportsMpp:YES   | supportsMpp:NO
        // ----------------------------------------------------------------
        // supportsTrampoline:YES | Full trampoline!  | ????              |
        // ---------------------------------------------------------------
        // supportsTrampoline:NO  | Legacy trampoline | Legacy trampoline |
        //
        // It's theoretically possible that a client supports trampoline, but not mpp.
        // We could optimize for this, and attempt to send a full payment over a single channel using trampoline.
        // But if we don't have enough capacity in a single channel, the legacy trampoline is our only fallback.
        //
        // In practice, this scenario is highly unlikely to ever occur,
        // because the mpp spec was made official long before trampoline.
        // So we're always just use a legacy trampoline in this case.

        val finalPayload = FinalPayload.createSinglePartPayload(
            amount = paymentAttempt.paymentAmount,
            expiry = finalExpiry,
            paymentSecret = paymentAttempt.invoice.paymentSecret
        )
        val nodeHops = listOf(
            NodeHop(
                nodeId = channel.staticParams.nodeParams.nodeId, // us
                nextNodeId = channel.staticParams.remoteNodeId, // trampoline node (acinq)
                cltvExpiryDelta = part.cltvExpiryDelta, // per node cltv
                fee = part.trampolineFees // per node fee
            ),
            NodeHop(
                nodeId = channel.staticParams.remoteNodeId, // trampoline node (acinq)
                nextNodeId = paymentAttempt.invoice.nodeId, // final recipient
                cltvExpiryDelta = finalExpiryDelta, // per node cltv
                fee = MilliSatoshi(0) // per node fee
            )
        )

        val trampolineOnion: PacketAndSecrets
        if (recipientSupportsTrampoline && recipientSupportsMpp && paymentSecret != null) {
            // Full trampoline! Full privacy!
            val triple = OutgoingPacket.buildPacket(
                paymentHash = paymentAttempt.invoice.paymentHash,
                hops = nodeHops,
                finalPayload = finalPayload,
                payloadLength = OnionRoutingPacket.TrampolinePacketLength
            )
            trampolineOnion = triple.third
        } else {
            // Legacy workaround
            var triple = OutgoingPacket.buildTrampolineToLegacyPacket(
                invoice = paymentAttempt.invoice,
                hops = nodeHops,
                finalPayload = finalPayload
            )
            trampolineOnion = triple.third
        }

        val trampolinePayload = FinalPayload.createTrampolinePayload(
            amount = part.amount,
            totalAmount = paymentAttempt.totalAmount(includingFees = true),
            expiry = part.cltvExpiryDelta.toCltvExpiry(currentBlockHeight.toLong()),
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