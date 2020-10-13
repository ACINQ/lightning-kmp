package fr.acinq.eclair.payment

import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.PublicKey
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
import kotlin.math.max


class OutgoingPaymentHandler(
    val nodeParams: NodeParams
) {

    enum class Status {
        INFLIGHT,
        SUCCEEDED,
        FAILED
    }

    data class ProcessResult(
        val paymentId: UUID,
        val invoice: PaymentRequest,
        val status: Status,
        val fees: MilliSatoshi, // we can inform user as payment fees increase during retries
        val actions: List<PeerEvent>
    )

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
     * Disambiguated return type for calculations.
     * (Returning a Pair, Triple, etc with matching types is a recipe for bugs.)
     */
    data class CalculatedAmounts(
        val trampolineAmount: MilliSatoshi, // Total amount going to trampoline, including all fees
        val channelFees: MilliSatoshi, // Base fees paid to trampoline, as per last ChannelUpdate
        val additionalFees: MilliSatoshi, // Amount of additional fees we could include, as requested
        val payeeAmount: MilliSatoshi // Total amount going to payee
    )

    /**
     * Encapsulates a current payment attempt (using trampoline), and occurring over a particular channel.
     * For a particular payment, there may be multiple parts going over different channels (mpp).
     * Each would be a different instance of this class.
     */
    data class TrampolinePaymentAttempt(
        val channelId: ByteVector32,
        val amount: MilliSatoshi,
        val nextAmount: MilliSatoshi,
        val cltvExpiryDelta: CltvExpiryDelta,
        val status: Status
    )

    /**
     * Represents the current state of a payment attempt.
     * This may include multiple TrampolinePaymentAttempt instances.
     *
     * For example:
     * - if we have 2 channels, with 1 BTC send capacity in each channel
     * - and we're trying to send 1.5 BTC to Bob
     * - then we'll need to split the payment across both channels
     * - so 2 payments, both going to the trampoline node (acinq server),
     *   with directions to forward payment to Bob
     */
    class PaymentAttempt {
        val paymentId: UUID
        val invoice: PaymentRequest
        val invoiceAmount: MilliSatoshi
        val failedAttempts: Int

        val trampolinePaymentSecret = Eclair.randomBytes32() // must be different from invoice.paymentSecret
        val parts = mutableListOf<TrampolinePaymentAttempt>()

        constructor(sendPayment: SendPayment) {
            paymentId = sendPayment.paymentId
            invoice = sendPayment.paymentRequest
            invoiceAmount = invoice.amount!! // sanity check already performed in processSendPayment
            failedAttempts = 0
        }

        private constructor(
            paymentId: UUID,
            invoice: PaymentRequest,
            invoiceAmount: MilliSatoshi,
            failedAttempts: Int
        ) {
            this.paymentId = paymentId
            this.invoice = invoice
            this.invoiceAmount = invoiceAmount
            this.failedAttempts = failedAttempts
        }

        fun incrementFailedAttempts(): PaymentAttempt {
            return PaymentAttempt(
                paymentId = this.paymentId,
                invoice = this.invoice,
                invoiceAmount = this.invoiceAmount,
                failedAttempts = this.failedAttempts + 1
            )
        }

        /**
         * Adds a corresponding TrampolinePaymentAttempt to the list, after calculating the fees.
         * If the availableForSend is too low to support a payment on this channel, returns null.
         */
        fun add(
            channelId: ByteVector32,
            channelUpdate: ChannelUpdate,
            targetAmount: MilliSatoshi,
            additionalFeesAmount: MilliSatoshi,
            availableForSend: MilliSatoshi,
            cltvExpiryDelta: CltvExpiryDelta
        ): Pair<TrampolinePaymentAttempt, CalculatedAmounts>? {

            val calculations = calculateAmounts(
                channelUpdate = channelUpdate,
                targetAmount = targetAmount,
                additionalFeesAmount = additionalFeesAmount,
                availableForSend = availableForSend
            ) ?: return null

            val part = TrampolinePaymentAttempt(
                channelId = channelId,
                amount = calculations.trampolineAmount,
                nextAmount = calculations.payeeAmount,
                cltvExpiryDelta = cltvExpiryDelta,
                status = Status.INFLIGHT
            )
            parts.add(part)
            return Pair(part, calculations)
        }

        /**
         * Marks the corresponding TrampolinePaymentAttempt as FAILED.
         */
        fun fail(channelId: ByteVector32): TrampolinePaymentAttempt? {

            val idx = parts.indexOfFirst { it.channelId == channelId && it.status == Status.INFLIGHT }
            if (idx < 0) {
                return null
            }

            val failedPart = parts[idx]
            val updatedPart = failedPart.copy(status = Status.FAILED)

            parts[idx] = updatedPart
            return updatedPart
        }

        /**
         * Marks the corresponding TrampolinePaymentAttempt as SUCCEEDED.
         */
        fun succeed(channelId: ByteVector32): TrampolinePaymentAttempt? {

            val idx = parts.indexOfFirst { it.channelId == channelId && it.status == Status.INFLIGHT }
            if (idx < 0) {
                return null
            }

            val part = parts[idx]
            val updatedPart = part.copy(status = Status.SUCCEEDED)

            parts[idx] = updatedPart
            return updatedPart
        }

        /**
         * The total amount we're paying. Can be fetched either including or excluding fees.
         */
        fun totalAmount(includingFees: Boolean = true): MilliSatoshi {
            return parts.filter {
                it.status != Status.FAILED
            }.map {
                if (includingFees) it.amount else it.nextAmount
            }.sum()
        }

        /**
         * The total amount of fees we're currently paying to the trampoline node(s).
         * This amount may be spread across multiple payments (across multiple channels).
         * This amount may increase if we receive `trampoline_fee_insufficient` error(s).
         */
        fun totalFees(): MilliSatoshi {
            return parts.filter {
                it.status != Status.FAILED
            }.map {
                it.amount - it.nextAmount
            }.sum()
        }

        fun hasInFlightPaymentAttempts(): Boolean {
            return parts.any { it.status == Status.INFLIGHT }
        }

        private fun calculateAmounts(
            channelUpdate: ChannelUpdate,
            targetAmount: MilliSatoshi,
            additionalFeesAmount: MilliSatoshi,
            availableForSend: MilliSatoshi
        ): CalculatedAmounts? {

            val maxToSend = channelUpdate.htlcMaximumMsat?.let {
                if (it < availableForSend) it else availableForSend
            } ?: availableForSend

            val channelFeeBase = channelUpdate.feeBaseMsat
            val channelFeePercent = channelUpdate.feeProportionalMillionths.toDouble() / 1_000_000.0

            var payeeAmount = targetAmount
            var channelFees = channelFeeBase + (payeeAmount * channelFeePercent)
            var additionalFees = additionalFeesAmount
            var trampolineAmount = payeeAmount + channelFees + additionalFees

            if (trampolineAmount > maxToSend) {
                // We don't have enough capacity to send everything that was requested.
                // So we need to calculate how much we can send.
                // The formula is:
                // X + channelFeeBase + (X * channelFeePercent) = availableForSend

                val maxAmountDouble = (maxToSend - channelFeeBase).msat.toDouble() / (channelFeePercent + 1.0)
                var maxAmount = MilliSatoshi(msat = ceil(maxAmountDouble).toLong()) // round up (might not fit)

                payeeAmount = maxAmount
                channelFees = channelFeeBase + (payeeAmount * channelFeePercent)
                additionalFees = MilliSatoshi(0)
                trampolineAmount = payeeAmount + channelFees + additionalFees

                if (trampolineAmount > maxToSend) {
                    maxAmount -= MilliSatoshi(1) // round down (didn't fit)

                    payeeAmount = maxAmount
                    channelFees = channelFeeBase + (payeeAmount * channelFeePercent)
                    trampolineAmount = payeeAmount + channelFees + additionalFees
                }

                // Now we know the maximum amount we can possibly send (maxing out the channel).
                // However, this may actually be more than the targetAmount.
                // For example:
                // - targetAmount = 100 msats
                // - additionalFeesAmount = 10,000 msats
                // - calculated maxAmount = 500 msats
                // So if this is the case, we can drop payee
                // i.e. additionalFeesAmount was large, and pushed us over maxToSend

                if (payeeAmount > targetAmount) {
                    payeeAmount = targetAmount
                    channelFees = channelFeeBase + (payeeAmount * channelFeePercent)
                    additionalFees = maxToSend - payeeAmount - channelFees // whatever will fit in available space
                    trampolineAmount = payeeAmount + channelFees + additionalFees
                }
            }

            val effectiveAmount = payeeAmount + additionalFees
            if (effectiveAmount <= MilliSatoshi(0)) {
                // We have only enough to pay the transaction fee.
                // This accomplishes nothing.
                return null
            }
            if (trampolineAmount < channelUpdate.htlcMinimumMsat) {
                // The amount isn't enough to satisfy the channel requirements.
                // This indicates we may need to rebalance our payment across the channels.
                return null
            }

            return CalculatedAmounts(trampolineAmount, channelFees, additionalFees, payeeAmount)
        }
    }

    private val logger = newEclairLogger()
    private val pending = mutableMapOf<UUID, PaymentAttempt>()

    fun processSendPayment(
        sendPayment: SendPayment,
        channels: Map<ByteVector32, ChannelState>,
        currentBlockHeight: Int
    ): ProcessResult {

        val failedResult = ProcessResult(
            paymentId = sendPayment.paymentId,
            invoice = sendPayment.paymentRequest,
            status = Status.FAILED,
            fees = MilliSatoshi(0),
            actions = listOf()
        )

        val invoiceAmount = sendPayment.paymentRequest.amount
        if (invoiceAmount == null || invoiceAmount.msat <= 0L) {
            logger.error { "payment request does not include a valid amount" }
            return failedResult
        }

        if (pending.containsKey(sendPayment.paymentId)) {
            logger.error { "contract violation: payment request is recycling uuid's" }
            return failedResult
        }

        val schedule = PaymentAdjustmentSchedule.get(0)!!
        val paymentAttempt = PaymentAttempt(sendPayment)

        return setupPaymentAttempt(paymentAttempt, schedule, channels, currentBlockHeight)
    }

    fun processFailure(
        event: ChannelAction, // ProcessFail || ProcessFailMalformed
        channels: Map<ByteVector32, ChannelState>,
        currentBlockHeight: Int
    ): ProcessResult? {

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
            else -> {
                require(false) { "event must be of type ProcessFail or ProcessFailMalformed" }
                return null
            }
        }

        val paymentAttempt = pending[paymentId]
        if (paymentAttempt == null) {
            logger.error { "ProcessFail.origin doesn't match any known paymentAttempt" }
            return null
        }

        // Mark the TrampolinePaymentAttempt.status as FAILED
        paymentAttempt.succeed(channelId)

        // After all the parts fail, we can start another paymentAttempt.
        if (paymentAttempt.hasInFlightPaymentAttempts()) {
            return null
        }

        val failedAttempts = paymentAttempt.failedAttempts + 1
        val schedule = PaymentAdjustmentSchedule.get(failedAttempts)

        if (schedule == null) {
            pending.remove(paymentAttempt.paymentId)
            return ProcessResult(
                paymentId = paymentAttempt.paymentId,
                invoice = paymentAttempt.invoice,
                status = Status.FAILED,
                fees = MilliSatoshi(0),
                actions = listOf()
            )
        }

        val newPaymentAttempt = paymentAttempt.incrementFailedAttempts()
        return setupPaymentAttempt(newPaymentAttempt, schedule, channels, currentBlockHeight)
    }

    fun processFulfill(
        event: ProcessFulfill
    ): ProcessResult? {

        val paymentAttempt = pending[event.paymentId]
        if (paymentAttempt == null) {
            logger.error { "ProcessFail.origin doesn't match any known paymentAttempt" }
            return null
        }

        // Mark the TrampolinePaymentAttempt.status as SUCCEEDED
        paymentAttempt.succeed(event.fulfill.channelId)

        // After all the parts succeed, we can announce success
        if (paymentAttempt.hasInFlightPaymentAttempts()) {
            return null
        }

        pending.remove(paymentAttempt.paymentId)
        return ProcessResult(
            paymentId = paymentAttempt.paymentId,
            invoice = paymentAttempt.invoice,
            status = Status.SUCCEEDED,
            fees = paymentAttempt.totalFees(),
            actions = listOf()
        )
    }

    private fun setupPaymentAttempt(
        paymentAttempt: PaymentAttempt,
        schedule: PaymentAdjustmentSchedule,
        channels: Map<ByteVector32, ChannelState>,
        currentBlockHeight: Int
    ): ProcessResult {

        // Try to create a set of payments to satisfy the payment request.
        // Each part will be a trampoline payment.
        // We may use multiple parts, depending on the capacity available in our channels.
        val actions = addTrampolinePaymentAttempts(paymentAttempt, schedule, channels, currentBlockHeight)
        if (actions.isEmpty()) {
            // We weren't able to make the payment.
            // We don't have enough capacity (either outright, or after fees are taken into account).
            return ProcessResult(
                paymentId = paymentAttempt.paymentId,
                invoice = paymentAttempt.invoice,
                status = Status.FAILED,
                fees = MilliSatoshi(0),
                actions = listOf()
            )
        }

        pending[paymentAttempt.paymentId] = paymentAttempt
        return ProcessResult(
            paymentId = paymentAttempt.paymentId,
            invoice = paymentAttempt.invoice,
            status = Status.INFLIGHT,
            fees = paymentAttempt.totalFees(),
            actions = actions
        )
    }

    private fun addTrampolinePaymentAttempts(
        paymentAttempt: PaymentAttempt,
        schedule: PaymentAdjustmentSchedule,
        channels: Map<ByteVector32, ChannelState>,
        currentBlockHeight: Int
    ): List<PeerEvent> {

        var availableChannels = channels.values.filterIsInstance<Normal>().toMutableList()

        // Calculating the `availableBalanceForSend` in each channel requires a bit of work.
        // And we need to reference this info repeatedly below, so we calculate it once here.
        val availableBalancesForSend = availableChannels.associateBy(keySelector = {
            it.channelId
        }, valueTransform = {
            it.commitments.availableBalanceForSend()
        })
        availableBalancesForSend.forEach {
            logger.info { "channel(${it.key}): available for send = ${it.value}" }
        }

        // Sort the channels by send capacity.
        // The channel with the highest/most availableForSend will be at the beginning of the array (index 0)
        val comparator =
            compareBy<Normal> { availableBalancesForSend[it.channelId]!! }.reversed().thenBy { it.shortChannelId }
        availableChannels.sortWith(comparator)

        // Phase 1: No mpp.
        // We're only sending over a single channel for right now.
        availableChannels = if (availableChannels.size > 0) mutableListOf(availableChannels[0]) else mutableListOf()

//      val totalAvailableForSend = availableBalancesForSend.values.sum()
        val totalAvailableForSend = availableChannels.map { availableBalancesForSend[it.channelId]!! }.sum()
        val invoiceAmount = paymentAttempt.invoiceAmount

        if (totalAvailableForSend < invoiceAmount) {
            logger.error {
                "insufficient capacity to send payment:" +
                    " invoiceAmount(${invoiceAmount})" +
                    " available(${totalAvailableForSend})"
            }
            return listOf()
        }

        // Our goal now is to split the payment across the channels.
        // The most optimal way to achieve this is by using the minimal number of channels.
        // This is because each channel charges a feeBase & feePercent.
        // Thus sending a payment (to the trampoline node) over 3 channels instead of 2 will result
        // in higher fees due to the additional feeBase payment.

        val additionalFeesAmount = schedule.feeBaseSat.toMilliSatoshi() + (invoiceAmount * schedule.feePercent)

        var remainingTargetAmount = invoiceAmount
        var remainingAdditionalFeesAmount = additionalFeesAmount

        for (channel in availableChannels) {
            if (remainingTargetAmount.msat == 0L && remainingAdditionalFeesAmount.msat == 0L) break // Done!

            val availableForSend = availableBalancesForSend[channel.channelId] ?: continue

            // The actual amount we can send via this channel is limited by:
            // - availableForSend (based on channel capacity, channel reserve fees, etc)
            // - the fees required by the trampoline node for routing
            val result = paymentAttempt.add(
                channelId = channel.channelId,
                channelUpdate = channel.channelUpdate,
                targetAmount = remainingTargetAmount,
                additionalFeesAmount = remainingAdditionalFeesAmount,
                availableForSend = availableForSend,
                cltvExpiryDelta = schedule.cltvExpiryDelta // this is wrong ?
            )
            if (result == null) {
                // Channel's availableForSend is so low that we can't use it for a payment here.
                continue
            }

            val (_, calculatedAmounts) = result

            remainingTargetAmount -= calculatedAmounts.payeeAmount
            remainingAdditionalFeesAmount -= calculatedAmounts.additionalFees
        }

        if (remainingTargetAmount.msat > 0L || remainingAdditionalFeesAmount.msat > 0L) {
            // We weren't able to make the payment.
            // We don't have enough capacity (once fees are taken into account).
            return listOf()
        }

        val actions = mutableListOf<PeerEvent>()
        paymentAttempt.parts.forEach { part: TrampolinePaymentAttempt ->
            val channel = channels[part.channelId] as Normal
            // actionify: part => (onion w/ trampoline) => CMD_ADD_HTLC => WrappedChannelEvent
            actions.add(actionify(channel, paymentAttempt, part, currentBlockHeight))
        }
        return actions
    }

    /**
     * Converts the TrampolinePaymentAttempt into an actionable PeerEvent.
     * The process involves:
     * - Creating the onion (with trampoline_onion component)
     * - Creating the CMD_ADD_HTLC
     * - Creating the WrappedChannelEvent
     */
    fun actionify(
        channel: Normal,
        paymentAttempt: PaymentAttempt,
        trampolinePaymentAttempt: TrampolinePaymentAttempt,
        currentBlockHeight: Int
    ): WrappedChannelEvent {

        val finalExpiryDelta = paymentAttempt.invoice.minFinalExpiryDelta
            ?: Channel.MIN_CLTV_EXPIRY_DELTA // default value if unspecified, as per Bolt 11
        val finalExpiry = finalExpiryDelta.toCltvExpiry(currentBlockHeight.toLong())

        val trampolineExpiryDelta = trampolinePaymentAttempt.cltvExpiryDelta + finalExpiryDelta
        val trampolineExpiry = trampolineExpiryDelta.toCltvExpiry(currentBlockHeight.toLong())

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
        // In practice, this scenario (payee supports trampoline but not mpp) is highly unlikely to ever occur.
        // Because the mpp spec was made official long before trampoline.
        // So we're simply going use a legacy trampoline in this case.

        val finalPayload = FinalPayload.createSinglePartPayload(
            amount = paymentAttempt.invoiceAmount,
            expiry = finalExpiry,
            paymentSecret = paymentAttempt.invoice.paymentSecret
        )
        val nodeHops = listOf(
            NodeHop(
                nodeId = channel.staticParams.nodeParams.nodeId, // us
                nextNodeId = channel.staticParams.remoteNodeId, // trampoline node (acinq)
                cltvExpiryDelta = trampolinePaymentAttempt.cltvExpiryDelta, // per node cltv
                fee = trampolinePaymentAttempt.nextAmount - trampolinePaymentAttempt.amount // per node fee
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
            amount = trampolinePaymentAttempt.amount,
            totalAmount = paymentAttempt.totalAmount(includingFees = true),
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