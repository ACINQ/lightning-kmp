package fr.acinq.eclair.payment

import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.PublicKey
import fr.acinq.bitcoin.Satoshi
import fr.acinq.eclair.*
import fr.acinq.eclair.channel.*
import fr.acinq.eclair.io.PeerEvent
import fr.acinq.eclair.io.SendPayment
import fr.acinq.eclair.io.WrappedChannelEvent
import fr.acinq.eclair.payment.relay.Origin
import fr.acinq.eclair.router.ChannelHop
import fr.acinq.eclair.router.NodeHop
import fr.acinq.eclair.utils.*
import fr.acinq.eclair.wire.*
import kotlin.math.ceil
import kotlin.math.max


class PaymentLifecycle(
    val nodeParams: NodeParams
) {

    enum class ProcessedStatus {
        SENDING,
        SUCCEEDED,
        FAILED
    }

    data class ProcessResult(
        val paymentId: UUID,
        val status: ProcessedStatus,
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

    enum class TrampolinePaymentStatus {
        INFLIGHT,
        SUCCEEDED,
        FAILED
    }

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
        val status: TrampolinePaymentStatus = TrampolinePaymentStatus.INFLIGHT,
        val failedAttempts: Int = 0
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
    class PaymentAttempt(sendPayment: SendPayment) {
        val id: UUID = sendPayment.id
        val invoice: PaymentRequest = sendPayment.paymentRequest
        val invoiceAmount = invoice.amount!! // sanity check already performed in processSendPayment
        val trampolinePaymentSecret = Eclair.randomBytes32() // must be different from invoice.paymentSecret

        // This ivar doesn't need to be private.
        // But we're not directly accessing it outside the class, and the compiler is very opinionated...
        private val parts = mutableListOf<TrampolinePaymentAttempt>()

        /**
         * Adds a corresponding TrampolinePaymentAttempt to the list, after calculating the fees.
         * If the availableForSend is too low to support a payment on this channel, returns null.
         */
        fun add(
            channelId: ByteVector32,
            channelUpdate: ChannelUpdate,
            targetAmount: MilliSatoshi,
            availableForSend: MilliSatoshi
        ): TrampolinePaymentAttempt? {

            val schedule = PaymentAdjustmentSchedule.get(failedAttempts = 0)!!
            val calculations = calculateAmounts(channelUpdate, schedule, targetAmount, availableForSend) ?: return null
            val (amount, fees) = calculations
            val nextAmount = amount - fees
            val cltvExpiryDelta = schedule.cltvExpiryDelta

            val part = TrampolinePaymentAttempt(channelId, amount, nextAmount, cltvExpiryDelta)
            parts.add(part)
            return part
        }

        /**
         * Updates the corresponding TrampolinePaymentAttempt by increasing the fees according to the schedule.
         * If the availableForSend is too low to support a payment with the updated fees,
         * the TrampolinePaymentAttempt is marked as FAILED.
         */
        fun fail(
            channelId: ByteVector32,
            channelUpdate: ChannelUpdate,
            availableForSend: MilliSatoshi
        ): TrampolinePaymentAttempt? {

            val idx = parts.indexOfFirst { it.channelId == channelId && it.status == TrampolinePaymentStatus.INFLIGHT }
            if (idx < 0) {
                return null
            }

            val failedPart = parts[idx]
            val failedAttempts = failedPart.failedAttempts + 1
            val schedule = PaymentAdjustmentSchedule.get(failedAttempts)

            val recalculation = schedule?.let {
                val targetAmount = invoiceAmount - parts.filter {
                    (it.status != TrampolinePaymentStatus.FAILED) && (it != failedPart)
                }.map {
                    it.nextAmount
                }.sum()
                calculateAmounts(
                    channelUpdate = channelUpdate,
                    schedule = schedule,
                    targetAmount = targetAmount,
                    availableForSend = availableForSend
                )
            }

            val updatedPart = when {
                schedule == null || recalculation == null -> {
                    failedPart.copy(
                        amount = MilliSatoshi(0),
                        cltvExpiryDelta = CltvExpiryDelta(0),
                        status = TrampolinePaymentStatus.FAILED,
                        failedAttempts = failedAttempts
                    )
                }
                else -> {
                    val (newAmount, newFees) = recalculation
                    failedPart.copy(
                        amount = newAmount,
                        nextAmount = newAmount - newFees,
                        cltvExpiryDelta = schedule.cltvExpiryDelta,
                        failedAttempts = failedAttempts
                    )
                }
            }

            parts[idx] = updatedPart
            return updatedPart
        }

        /**
         * Marks the corresponding TrampolinePaymentAttempt as SUCCEEDED.
         */
        fun succeed(channelId: ByteVector32): TrampolinePaymentAttempt? {

            val idx = parts.indexOfFirst { it.channelId == channelId && it.status == TrampolinePaymentStatus.INFLIGHT }
            if (idx < 0) {
                return null
            }

            val part = parts[idx]
            val updatedPart = part.copy(status = TrampolinePaymentStatus.SUCCEEDED)

            parts[idx] = updatedPart
            return updatedPart
        }

        /**
         * The total amount we're paying. Can be fetched either including or excluding fees.
         */
        fun totalAmount(includingFees: Boolean = true): MilliSatoshi {
            return parts.filter {
                it.status != TrampolinePaymentStatus.FAILED
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
                it.status != TrampolinePaymentStatus.FAILED
            }.map {
                it.amount - it.nextAmount
            }.sum()
        }

        fun hasFailedPaymentAttempt(channelId: ByteVector32): Boolean {
            return parts.any { it.channelId == channelId && it.status == TrampolinePaymentStatus.FAILED }
        }

        fun hasInFlightPaymentAttempts(): Boolean {
            return parts.any { it.status == TrampolinePaymentStatus.INFLIGHT }
        }

        /**
         * Calculates the maximum amount we can actually send.
         * This may be less than the targetAmount, depending on the fees.
         *
         * @return A pair of (amount, fees), where amount is either the targetAmount,
         * or the max we can send given the current fee structure & availableForSend.
         * The fees indicate the amount of fees being consumed by this node.
         */
        private fun calculateAmounts(
            channelUpdate: ChannelUpdate,
            schedule: PaymentAdjustmentSchedule,
            targetAmount: MilliSatoshi,
            availableForSend: MilliSatoshi
        ): Pair<MilliSatoshi, MilliSatoshi>? {

            val maxToSend = channelUpdate.htlcMaximumMsat?.let {
                if (it < availableForSend) it else availableForSend
            } ?: availableForSend

            val channelFeeBase = channelUpdate.feeBaseMsat
            val channelPercent = channelUpdate.feeProportionalMillionths.toDouble() / 1_000_000.0

            val combinedBase = channelFeeBase + schedule.feeBaseSat.toMilliSatoshi()
            val combinedPercent = channelPercent + schedule.feePercent

            var fees = combinedBase + (targetAmount * combinedPercent)
            var amount = targetAmount + fees

            if (amount > maxToSend) {
                // We don't have enough capacity to send the targetAmount and pay the scheduled fees.
                // So we need to calculate how much we can pay, while maintaining the fee schedule.
                // The formula is:
                // X + combinedBased + (X * combinedPercent) = availableForSend

                val maxAmount = (maxToSend - combinedBase).msat.toDouble() / (combinedPercent + 1.0)
                var maxTargetAmount = MilliSatoshi(msat = ceil(maxAmount).toLong()) // round up (might not fit)

                fees = combinedBase + (maxTargetAmount * combinedPercent)
                amount = maxTargetAmount + fees

                if (amount > maxToSend) {
                    maxTargetAmount -= MilliSatoshi(1) // round down (didn't fit)
                    fees = combinedBase + (maxTargetAmount * combinedPercent)
                    amount = maxTargetAmount + fees
                }
            }

            val nonFeesAmount = amount - fees
            return if (nonFeesAmount <= MilliSatoshi(0) || amount < channelUpdate.htlcMinimumMsat) {
                null
            } else {
                Pair(amount, fees)
            }
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
            paymentId = sendPayment.id,
            status = ProcessedStatus.FAILED,
            fees = MilliSatoshi(0),
            actions = listOf()
        )

        val invoiceAmount = sendPayment.paymentRequest.amount
        if (invoiceAmount == null || invoiceAmount.msat <= 0L) {
            logger.error { "payment request does not include a valid amount" }
            return failedResult
        }

        if (pending.containsKey(sendPayment.id)) {
            logger.error { "contract violation: payment request is recycling uuid's" }
            return failedResult
        }

        val paymentAttempt = PaymentAttempt(sendPayment)

        // Try to create a set of payments to satisfy the payment request.
        // Each part will be a trampoline payment.
        // We may use multiple parts, depending on the capacity available in our channels.
        val actions = addTrampolinePaymentAttempts(paymentAttempt, invoiceAmount, channels, currentBlockHeight)

        if (actions.isEmpty()) {
            // We weren't able to make the payment.
            // We don't have enough capacity (either outright, or after fees are taken into account).
            return failedResult
        }

        pending[paymentAttempt.id] = paymentAttempt
        return ProcessResult(
            paymentId = paymentAttempt.id,
            status = ProcessedStatus.SENDING,
            fees = paymentAttempt.totalFees(),
            actions = actions
        )
    }

    fun processFailure(
        event: ChannelAction, // ProcessFail || ProcessFailMalformed
        channels: Map<ByteVector32, ChannelState>,
        currentBlockHeight: Int
    ): ProcessResult? {

        val origin: Origin?
        val channelId: ByteVector32
        when (event) {
            is ProcessFail -> {
                origin = event.origin
                channelId = event.fail.channelId
            }
            is ProcessFailMalformed -> {
                origin = event.origin
                channelId = event.fail.channelId
            }
            else -> {
                require(false) { "event must be of type ProcessFail or ProcessFailMalformed" }
                return null
            }
        }

        val paymentAttempt = if (origin is Origin.Local) pending[origin.id] else null
        if (paymentAttempt == null) {
            logger.error { "ProcessFail.origin doesn't match any known paymentAttempt" }
            return null
        }

        val channel = channels[channelId]
        if (channel !is Normal) {
            return null
        }

        // Increment the failedAttempts count for this particular trampolinePaymentAttempt,
        // and check to see if we can send a new payment with higher fees (on the same channel).
        val trampolinePaymentAttempt = paymentAttempt.fail(
            channelId = channelId,
            channelUpdate = channel.channelUpdate,
            availableForSend = channel.commitments.availableBalanceForSend()
        )

        // If trampolinePaymentAttempt is non-null, then we have a new payment we can make on this channel.
        if (trampolinePaymentAttempt != null && trampolinePaymentAttempt.status == TrampolinePaymentStatus.INFLIGHT) {
            // Fees have been increased. Send new payment.
            val action = actionify(channel, paymentAttempt, trampolinePaymentAttempt, currentBlockHeight)
            return ProcessResult(
                paymentId = paymentAttempt.id,
                status = ProcessedStatus.SENDING,
                fees = paymentAttempt.totalFees(),
                actions = listOf(action)
            )
        }

        // We've unfortunately run out of options on this channel.
        // However, we might be able to make the payment over other channels.
        // But before we try this, we want to wait until the other in-flight payments finish.
        // That way we'll have a clear understanding of our remaining capacity.
        if (paymentAttempt.hasInFlightPaymentAttempts()) {
            return null
        }

        val paidAmount = paymentAttempt.totalAmount(includingFees = false)
        val remainingAmount = paymentAttempt.invoiceAmount - paidAmount
        val actions = addTrampolinePaymentAttempts(paymentAttempt, remainingAmount, channels, currentBlockHeight)

        if (actions.isEmpty()) {
            // We lack the capacity to complete the payment (as a whole), including the required fees.
            pending.remove(paymentAttempt.id)
            return ProcessResult(
                paymentId = paymentAttempt.id,
                status = ProcessedStatus.FAILED,
                fees = paymentAttempt.totalFees(),
                actions = listOf()
            )
        }

        // We have one or more new trampoline payments to send.
        // The flow that caused us to arrive at this point:
        // - we calculated an original set of payments within processSendPayment
        // - a payment failed on channel X (marking that channel as dead - for this paymentAttempt)
        // - but there are other available channels we can still use to complete the payment
        return ProcessResult(
            paymentId = paymentAttempt.id,
            status = ProcessedStatus.SENDING,
            fees = paymentAttempt.totalFees(),
            actions = actions
        )
    }

    fun processFulfill(
        event: ProcessFulfill,
        channels: Map<ByteVector32, ChannelState>,
        currentBlockHeight: Int
    ): ProcessResult? {

        val paymentAttempt = if (event.origin is Origin.Local) pending[event.origin.id] else null
        if (paymentAttempt == null) {
            logger.error { "ProcessFail.origin doesn't match any known paymentAttempt" }
            return null
        }

        // Mark the TrampolinePaymentAttempt.status as SUCCEEDED
        paymentAttempt.succeed(event.fulfill.channelId)

        if (paymentAttempt.hasInFlightPaymentAttempts()) {
            // Still waiting for other payments to complete
            return null
        }

        val paidAmount = paymentAttempt.totalAmount(includingFees = false)
        if (paidAmount >= paymentAttempt.invoiceAmount) {
            // Paid in full !
            pending.remove(paymentAttempt.id)
            return ProcessResult(
                paymentId = paymentAttempt.id,
                status = ProcessedStatus.SUCCEEDED,
                fees = paymentAttempt.totalFees(),
                actions = listOf()
            )
        }

        val remainingAmount = paymentAttempt.invoiceAmount - paidAmount
        val actions = addTrampolinePaymentAttempts(paymentAttempt, remainingAmount, channels, currentBlockHeight)

        if (actions.isEmpty()) {
            // We lack the capacity to complete the payment (as a whole), including the required fees.
            pending.remove(paymentAttempt.id)
            return ProcessResult(
                paymentId = paymentAttempt.id,
                status = ProcessedStatus.FAILED,
                fees = paymentAttempt.totalFees(),
                actions = listOf()
            )
        }

        // We have one or more new trampoline payments to send.
        // The flow that caused us to arrive at this point:
        // - we calculated an original set of payments within processSendPayment
        // - one or more payments temporarily failed, requiring us to increase the fees
        // - the total amount being paid was forcibly decreased,
        //   in order to meet channel capacity restrictions & increased fee requirements
        // - thus when all in-flight payments succeeded, we still had a remaining balance obligation
        return ProcessResult(
            paymentId = paymentAttempt.id,
            status = ProcessedStatus.SENDING,
            fees = paymentAttempt.totalFees(),
            actions = actions
        )
    }

    private fun addTrampolinePaymentAttempts(
        paymentAttempt: PaymentAttempt,
        targetAmount: MilliSatoshi,
        channels: Map<ByteVector32, ChannelState>,
        currentBlockHeight: Int
    ): List<PeerEvent> {

        check(!paymentAttempt.hasInFlightPaymentAttempts()) {
            // We could theoretically support this if needed.
            // But first we need a way to differentiate between 2 TrampolinePaymentAttempt instances,
            // both of which are occurring over the same channelId.
            "Attempting to add new trampolinePaymentAttempt(s) while existing attempts are in-flight"
        }

        val availableChannels = channels.values.filterIsInstance<Normal>().filterNot {
            // exclude channel for which:
            // - we've already made a payment attempt on that channel
            // - the payment attempt failed (for whatever reason)
            paymentAttempt.hasFailedPaymentAttempt(it.channelId)
        }.toMutableList()

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

        val totalAvailableForSend = availableBalancesForSend.values.sum()

        if (totalAvailableForSend < targetAmount) {
            logger.error {
                "insufficient capacity to send payment:" +
                    " targetAmount($targetAmount)" +
                    " available(${totalAvailableForSend})"
            }
            return listOf()
        }

        // Sort the channels by send capacity.
        // The channel with the highest/most availableForSend will be at the beginning of the array (index 0)
        val comparator =
            compareBy<Normal> { availableBalancesForSend[it.channelId]!! }.reversed().thenBy { it.shortChannelId }
        availableChannels.sortWith(comparator)

        // Walk the channels, and try to create a set of htlc's.

        var remainingAmount = targetAmount
        val actions = mutableListOf<PeerEvent>()

        for (channel in availableChannels) {
            if (remainingAmount.msat == 0L) break // Done!

            val availableForSend = availableBalancesForSend[channel.channelId] ?: continue

            // The actual amount we can send via this channel is limited by:
            // - availableForSend (based on channel capacity, channel reserve fees, etc)
            // - the fees required by the trampoline node for routing
            val trampolinePaymentAttempt = paymentAttempt.add(
                channelId = channel.channelId,
                channelUpdate = channel.channelUpdate,
                targetAmount = remainingAmount,
                availableForSend = availableForSend
            )
            if (trampolinePaymentAttempt == null) {
                // Channel's availableForSend is so low that we can't use it for a payment here.
                continue
            }
            // trampolinePaymentAttempt.amount => amount being paid to trampoline node, including fees
            // trampolinePaymentAttempt.nextAmount => amount being paid to final recipient, via trampoline node
            remainingAmount -= trampolinePaymentAttempt.nextAmount

            // actionify: trampolinePaymentAttempt => (onion w/ trampoline) => CMD_ADD_HTLC => WrappedChannelEvent
            actions.add(actionify(channel, paymentAttempt, trampolinePaymentAttempt, currentBlockHeight))
        }

        if (remainingAmount.msat > 0L) {
            // We weren't able to make the payment.
            // We don't have enough capacity (once fees are taken into account).
            return listOf()
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
            ?: CltvExpiryDelta(18) // default value if unspecified, as per Bolt 11
        val finalExpiry = finalExpiryDelta.toCltvExpiry(currentBlockHeight.toLong())

        val trampolineExpiryDelta = trampolinePaymentAttempt.cltvExpiryDelta + finalExpiryDelta
        val trampolineExpiry = trampolineExpiryDelta.toCltvExpiry(currentBlockHeight.toLong())

        val features = paymentAttempt.invoice.features?.let { Features(it) } ?: Features(setOf())
        val recipientSupportsTrampoline = features.hasFeature(Feature.TrampolinePayment)

        val trampolinePayload: FinalPayload
        if (recipientSupportsTrampoline) {
            // Full trampoline! Full privacy!
            val nodes: List<PublicKey> = listOf(
                channel.staticParams.remoteNodeId, // trampoline node (acinq)
                paymentAttempt.invoice.nodeId, // final recipient
            )
            val payloads: List<PerHopPayload> = listOf(
                NodeRelayPayload.create(
                    amount = trampolinePaymentAttempt.amount, // includes trampoline fees
                    expiry = trampolineExpiry,
                    nextNodeId = nodes[0]
                ),
                NodeRelayPayload.create(
                    amount = trampolinePaymentAttempt.nextAmount,
                    expiry = finalExpiry,
                    nextNodeId = nodes[1]
                )
            )
            val trampolineOnion = OutgoingPacket.buildOnion(
                nodes = nodes,
                payloads = payloads,
                associatedData = paymentAttempt.invoice.paymentHash,
                payloadLength = OnionRoutingPacket.TrampolinePacketLength
            )
            trampolinePayload = FinalPayload.createTrampolinePayload(
                amount = trampolinePaymentAttempt.amount,
                totalAmount = paymentAttempt.invoiceAmount,
                expiry = trampolineExpiry,
                paymentSecret = paymentAttempt.trampolinePaymentSecret,
                trampolinePacket = trampolineOnion.packet
            )

        } else {
            // Legacy workaround.
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
            val finalPayload = FinalPayload.createSinglePartPayload(
                amount = paymentAttempt.invoiceAmount,
                expiry = finalExpiry,
                paymentSecret = paymentAttempt.invoice.paymentSecret
            )
            val (_, _, trampolineOnion) = OutgoingPacket.buildTrampolineToLegacyPacket(
                invoice = paymentAttempt.invoice,
                hops = nodeHops,
                finalPayload = finalPayload
            )
            trampolinePayload = FinalPayload.createTrampolinePayload(
                amount = trampolinePaymentAttempt.amount,
                totalAmount = paymentAttempt.invoiceAmount,
                expiry = trampolineExpiry,
                paymentSecret = paymentAttempt.trampolinePaymentSecret,
                trampolinePacket = trampolineOnion.packet
            )
        }

        val channelHops: List<ChannelHop> = listOf(
            ChannelHop(
                nodeId = channel.staticParams.nodeParams.nodeId, // us
                nextNodeId = channel.staticParams.remoteNodeId, // trampoline node (acinq)
                lastUpdate = channel.channelUpdate
            )
        )
        val (cmdAddHtlc, _) = OutgoingPacket.buildCommand(
            id = paymentAttempt.id,
            paymentHash = paymentAttempt.invoice.paymentHash,
            hops = channelHops,
            finalPayload = trampolinePayload
        )

        return WrappedChannelEvent(channelId = channel.channelId, channelEvent = ExecuteCommand(cmdAddHtlc))
    }
}