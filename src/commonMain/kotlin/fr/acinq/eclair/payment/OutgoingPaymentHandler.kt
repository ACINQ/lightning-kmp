package fr.acinq.eclair.payment

import fr.acinq.bitcoin.ByteVector32
import fr.acinq.eclair.*
import fr.acinq.eclair.channel.*
import fr.acinq.eclair.crypto.sphinx.FailurePacket
import fr.acinq.eclair.crypto.sphinx.SharedSecrets
import fr.acinq.eclair.db.HopDesc
import fr.acinq.eclair.db.OutgoingPayment
import fr.acinq.eclair.db.OutgoingPaymentStatus
import fr.acinq.eclair.io.SendPayment
import fr.acinq.eclair.io.WrappedChannelEvent
import fr.acinq.eclair.router.ChannelHop
import fr.acinq.eclair.router.NodeHop
import fr.acinq.eclair.utils.*
import fr.acinq.eclair.wire.*
import org.kodein.log.Logger

class OutgoingPaymentHandler(val nodeParams: NodeParams, private val trampolineParams: RouteCalculation.TrampolineParams) {

    interface SendPaymentResult
    interface ProcessFailureResult
    interface ProcessFulfillResult

    /** A payment attempt has been made: we provide information about the fees we're paying, which may increase as we re-try our payment. */
    data class Progress(val payment: SendPayment, val fees: MilliSatoshi, val actions: List<WrappedChannelEvent>) : SendPaymentResult, ProcessFailureResult

    /** The payment could not be sent. */
    data class Failure(val payment: SendPayment, val failure: OutgoingPaymentFailure) : SendPaymentResult, ProcessFailureResult

    /** The recipient released the preimage, but we are still waiting for some partial payments to settle. */
    data class PreimageReceived(val payment: SendPayment, val preimage: ByteVector32) : ProcessFulfillResult

    /** The payment was successfully made. */
    data class Success(val payment: SendPayment, val preimage: ByteVector32, val fees: MilliSatoshi) : ProcessFailureResult, ProcessFulfillResult

    /** The payment is unknown. */
    object UnknownPayment : ProcessFailureResult, ProcessFulfillResult

    private val logger by eclairLogger()
    private val childToParentId = mutableMapOf<UUID, UUID>()
    private val pending = mutableMapOf<UUID, PaymentAttempt>()

    // NB: this function should only be used in tests.
    fun getPendingPayment(parentId: UUID): PaymentAttempt? = pending[parentId]

    private fun getPaymentAttempt(childId: UUID): PaymentAttempt? = childToParentId[childId]?.let { pending[it] }

    fun sendPayment(request: SendPayment, channels: Map<ByteVector32, ChannelState>, currentBlockHeight: Int): SendPaymentResult {
        logger.info { "h:${request.paymentHash} p:${request.paymentId} sending ${request.paymentAmount} to ${request.recipientNodeId}" }
        if (request.paymentAmount <= 0.msat) {
            logger.warning { "h:${request.paymentHash} p:${request.paymentId} payment amount must be positive (${request.paymentAmount})" }
            return Failure(request, FinalFailure.InvalidPaymentAmount.toPaymentFailure())
        }
        if (pending.containsKey(request.paymentId)) {
            logger.error { "h:${request.paymentHash} p:${request.paymentId} contract violation: caller is recycling uuid's" }
            return Failure(request, FinalFailure.InvalidPaymentId.toPaymentFailure())
        }

        val (trampolineAmount, trampolineExpiry, trampolinePacket) = createTrampolinePayload(request, trampolineParams.attempts.first(), currentBlockHeight)
        return when (val result = RouteCalculation.findRoutes(trampolineAmount, channels)) {
            is Either.Left -> {
                logger.warning { "h:${request.paymentHash} p:${request.paymentId} payment failed: ${result.value.message}" }
                // TODO: store failure in DB
                Failure(request, result.value.toPaymentFailure())
            }
            is Either.Right -> {
                // We generate a random secret for this payment to avoid leaking the invoice secret to the trampoline node.
                val trampolinePaymentSecret = Eclair.randomBytes32()
                val trampolinePayload = PaymentAttempt.TrampolinePayload(trampolineAmount, trampolineExpiry, trampolinePaymentSecret, trampolinePacket)
                val childPayments = createChildPayments(request, result.value, trampolinePayload)
                val payment = PaymentAttempt.PaymentInProgress(request, 0, trampolinePayload, childPayments.map { it.first.id to Pair(it.first, it.second) }.toMap(), setOf(), listOf())
                pending[request.paymentId] = payment
                Progress(request, payment.fees, childPayments.map { it.third })
            }
        }
    }

    private fun createChildPayments(request: SendPayment, routes: List<RouteCalculation.Route>, trampolinePayload: PaymentAttempt.TrampolinePayload): List<Triple<OutgoingPayment, SharedSecrets, WrappedChannelEvent>> {
        val childPayments = routes.map { createOutgoingPayment(request, it, trampolinePayload) }
        childToParentId.putAll(childPayments.map { it.first.id to it.first.parentId })
        childPayments.forEach { logger.info { "h:${request.paymentHash} p:${request.paymentId} i:${it.first.id} sending ${it.first.amount} to channel ${it.third.channelId}" } }
        return childPayments
    }

    fun processAddFailure(channelId: ByteVector32, event: HandleCommandFailed, channels: Map<ByteVector32, ChannelState>): ProcessFailureResult? {
        // We are looking for errors from the channel in response to our CMD_ADD_HTLC request
        val add = event.cmd as? CMD_ADD_HTLC ?: return null
        val payment = getPaymentAttempt(add.paymentId)
        if (payment == null) {
            logger.error { "h:${add.paymentHash} i:${add.paymentId} paymentId doesn't match any known payment attempt" }
            // TODO: check in the DB if we have a matching payment: if so, mark it failed
            return UnknownPayment
        }

        logger.debug { "h:${add.paymentHash} p:${payment.request.paymentId} i:${add.paymentId} could not send HTLC: ${event.error?.message}" }
        // TODO: update payment status in DB

        val (updated, result) = when (payment) {
            is PaymentAttempt.PaymentInProgress -> {
                val ignore = payment.ignore + channelId // we ignore the failing channel in retries
                val failure: List<Either<ChannelException, FailureMessage>> = if (event.error is ChannelException) listOf(Either.Left(event.error)) else listOf()
                when (val routes = RouteCalculation.findRoutes(add.amount, channels - ignore)) {
                    is Either.Left -> PaymentAttempt.PaymentAborted(payment.request, routes.value, payment.pending, payment.failures).failChild(add.paymentId, failure, logger)
                    is Either.Right -> {
                        val newPayments = createChildPayments(payment.request, routes.value, payment.trampolinePayload)
                        val updatedPayments = payment.pending - add.paymentId + newPayments.map { it.first.id to Pair(it.first, it.second) }
                        val updated = payment.copy(ignore = ignore, failures = payment.failures + failure, pending = updatedPayments)
                        val result = Progress(payment.request, updated.fees, newPayments.map { it.third })
                        Pair(updated, result)
                    }
                }
            }
            is PaymentAttempt.PaymentAborted -> payment.failChild(add.paymentId, if (event.error is ChannelException) listOf(Either.Left(event.error)) else listOf(), logger)
            is PaymentAttempt.PaymentSucceeded -> payment.failChild(add.paymentId, logger)
        }

        updateGlobalState(add.paymentId, updated)

        return result
    }

    fun processRemoteFailure(event: ProcessRemoteFailure, channels: Map<ByteVector32, ChannelState>, currentBlockHeight: Int): ProcessFailureResult? {
        val payment = getPaymentAttempt(event.paymentId)
        if (payment == null) {
            logger.error { "i:${event.paymentId} paymentId doesn't match any known payment attempt" }
            // TODO: check in the DB if we have a matching payment: if so, mark it failed
            return UnknownPayment
        }

        val decryptedFailure = when (event) {
            is ProcessFailMalformed -> null
            is ProcessFail -> when (val part = payment.pending[event.paymentId]) {
                null -> null
                else -> when (val decrypted = FailurePacket.decrypt(event.fail.reason.toByteArray(), part.second)) {
                    is Try.Failure -> null
                    is Try.Success -> decrypted.result.failureMessage
                }
            }
        }

        logger.debug { "h:${payment.request.paymentHash} p:${payment.request.paymentId} i:${event.paymentId} HTLC failed: ${decryptedFailure?.message}" }
        // TODO: update payment status in DB

        val (updated, result) = when (payment) {
            is PaymentAttempt.PaymentInProgress -> {
                val failure: List<Either<ChannelException, FailureMessage>> = decryptedFailure?.let { listOf(Either.Right(it)) } ?: listOf()
                val finalError = when {
                    trampolineParams.attempts.size <= payment.attemptNumber + 1 -> FinalFailure.RetryExhausted
                    decryptedFailure != TrampolineExpiryTooSoon && decryptedFailure != TrampolineFeeInsufficient -> FinalFailure.UnknownError // non-retriable error
                    else -> null
                }
                if (finalError != null) {
                    PaymentAttempt.PaymentAborted(payment.request, finalError, payment.pending, listOf()).failChild(event.paymentId, failure, logger)
                } else {
                    // The trampoline node is asking us to retry the payment with more fees.
                    logger.debug { "h:${payment.request.paymentHash} p:${payment.request.paymentId} i:${event.paymentId} c:${event.channelId} child payment failed because of fees" }
                    val updated = payment.copy(pending = payment.pending - event.paymentId)
                    if (updated.pending.isNotEmpty()) {
                        // We wait for all pending HTLCs to be settled before retrying.
                        // NB: we don't update failures here to avoid duplicate trampoline errors
                        Pair(updated, null)
                    } else {
                        val trampolineFees = trampolineParams.attempts[payment.attemptNumber + 1]
                        logger.info { "h:${payment.request.paymentHash} p:${payment.request.paymentId} retrying payment with higher fees (base=${trampolineFees.feeBase}, percent=${trampolineFees.feePercent})..." }
                        val (trampolineAmount, trampolineExpiry, trampolinePacket) = createTrampolinePayload(payment.request, trampolineFees, currentBlockHeight)
                        when (val routes = RouteCalculation.findRoutes(trampolineAmount, channels)) {
                            is Either.Left -> {
                                logger.warning { "h:${payment.request.paymentHash} p:${payment.request.paymentId} payment failed: ${routes.value.message}" }
                                val aborted = PaymentAttempt.PaymentAborted(payment.request, routes.value, mapOf(), payment.failures + failure)
                                val result = Failure(payment.request, OutgoingPaymentFailure(aborted.reason, aborted.failures))
                                Pair(aborted, result)
                            }
                            is Either.Right -> {
                                // We generate a random secret for this payment to avoid leaking the invoice secret to the trampoline node.
                                val trampolinePaymentSecret = Eclair.randomBytes32()
                                val trampolinePayload = PaymentAttempt.TrampolinePayload(trampolineAmount, trampolineExpiry, trampolinePaymentSecret, trampolinePacket)
                                val childPayments = createChildPayments(payment.request, routes.value, trampolinePayload)
                                val newAttempt = PaymentAttempt.PaymentInProgress(
                                    payment.request,
                                    payment.attemptNumber + 1,
                                    trampolinePayload,
                                    childPayments.map { it.first.id to Pair(it.first, it.second) }.toMap(),
                                    setOf(), // we reset ignored channels
                                    payment.failures + failure
                                )
                                val result = Progress(newAttempt.request, newAttempt.fees, childPayments.map { it.third })
                                Pair(newAttempt, result)
                            }
                        }
                    }
                }
            }
            is PaymentAttempt.PaymentAborted -> payment.failChild(event.paymentId, decryptedFailure?.let { listOf(Either.Right(it)) } ?: listOf(), logger)
            is PaymentAttempt.PaymentSucceeded -> payment.failChild(event.paymentId, logger)
        }

        updateGlobalState(event.paymentId, updated)

        return result
    }

    fun processFulfill(event: ProcessFulfill): ProcessFulfillResult {
        val payment = getPaymentAttempt(event.paymentId)
        if (payment == null) {
            logger.error { "i:${event.paymentId} paymentId doesn't match any known payment attempt" }
            // TODO: check in the DB if we have a matching payment: if so, mark it fulfilled
            return UnknownPayment
        }

        logger.debug { "h:${payment.request.paymentHash} p:${payment.request.paymentId} i:${event.paymentId} HTLC fulfilled" }
        // TODO: update payment status in DB
        val part = payment.pending[event.paymentId]?.first

        val updated = when (payment) {
            is PaymentAttempt.PaymentInProgress -> PaymentAttempt.PaymentSucceeded(payment.request, event.fulfill.paymentPreimage, part?.let { listOf(it) } ?: listOf(), payment.pending - event.paymentId)
            is PaymentAttempt.PaymentSucceeded -> payment.copy(pending = payment.pending - event.paymentId, parts = part?.let { payment.parts + it } ?: payment.parts)
            is PaymentAttempt.PaymentAborted -> {
                // The recipient released the preimage without receiving the full payment amount.
                // This is a spec violation and is too bad for them, we obtained a proof of payment without paying the full amount.
                logger.warning { "payment succeeded after partial failure: we may have paid less than the full amount" }
                PaymentAttempt.PaymentSucceeded(payment.request, event.fulfill.paymentPreimage, part?.let { listOf(it) } ?: listOf(), payment.pending - event.paymentId)
            }
        }

        updateGlobalState(event.paymentId, updated)

        return if (updated.isComplete()) {
            logger.info { "h:${payment.request.paymentHash} p:${payment.request.paymentId} payment successfully sent (fees=${updated.fees})" }
            Success(payment.request, event.fulfill.paymentPreimage, updated.fees)
        } else {
            PreimageReceived(payment.request, event.fulfill.paymentPreimage)
        }
    }

    private fun updateGlobalState(processedChildId: UUID, updatedPayment: PaymentAttempt) {
        childToParentId.remove(processedChildId)
        if (updatedPayment.isComplete()) {
            pending.remove(updatedPayment.request.paymentId)
        } else {
            pending[updatedPayment.request.paymentId] = updatedPayment
        }
    }

    private fun createOutgoingPayment(request: SendPayment, route: RouteCalculation.Route, trampolinePayload: PaymentAttempt.TrampolinePayload): Triple<OutgoingPayment, SharedSecrets, WrappedChannelEvent> {
        val childId = UUID.randomUUID()
        val outgoingPayment = OutgoingPayment(
            childId,
            request.paymentId,
            request.paymentHash,
            route.amount,
            request.paymentAmount,
            request.recipientNodeId,
            listOf(HopDesc(nodeParams.nodeId, route.channel.staticParams.remoteNodeId, route.channel.shortChannelId), HopDesc(route.channel.staticParams.remoteNodeId, request.recipientNodeId)),
            currentTimestampMillis(),
            request.paymentRequest,
            OutgoingPaymentStatus.Pending
        )
        // TODO: store outgoingPayment in DB

        val channelHops: List<ChannelHop> = listOf(ChannelHop(nodeParams.nodeId, route.channel.staticParams.remoteNodeId, route.channel.channelUpdate))
        val (add, secrets) = OutgoingPacket.buildCommand(childId, request.paymentHash, channelHops, trampolinePayload.createFinalPayload(route.amount))
        return Triple(outgoingPayment, secrets, WrappedChannelEvent(route.channel.channelId, ExecuteCommand(add)))
    }

    private fun createTrampolinePayload(request: SendPayment, fees: RouteCalculation.TrampolineFees, currentBlockHeight: Int): Triple<MilliSatoshi, CltvExpiry, OnionRoutingPacket> {
        val trampolineRoute = listOf(
            NodeHop(nodeParams.nodeId, trampolineParams.nodeId, /* ignored */ CltvExpiryDelta(0), /* ignored */ 0.msat),
            NodeHop(trampolineParams.nodeId, request.recipientNodeId, fees.cltvExpiryDelta, fees.calculateFees(request.paymentAmount))
        )

        val finalExpiryDelta = request.paymentRequest.minFinalExpiryDelta ?: Channel.MIN_CLTV_EXPIRY_DELTA
        val finalExpiry = finalExpiryDelta.toCltvExpiry(currentBlockHeight.toLong())
        val finalPayload = FinalPayload.createSinglePartPayload(request.paymentAmount, finalExpiry, request.paymentRequest.paymentSecret)

        val invoiceFeatures = request.paymentRequest.features?.let { Features(it) } ?: Features(setOf())
        val (trampolineAmount, trampolineExpiry, trampolineOnion) = if (invoiceFeatures.hasFeature(Feature.TrampolinePayment)) {
            OutgoingPacket.buildPacket(request.paymentHash, trampolineRoute, finalPayload, OnionRoutingPacket.TrampolinePacketLength)
        } else {
            OutgoingPacket.buildTrampolineToLegacyPacket(request.paymentRequest, trampolineRoute, finalPayload)
        }
        return Triple(trampolineAmount, trampolineExpiry, trampolineOnion.packet)
    }

    sealed class PaymentAttempt {
        abstract val request: SendPayment
        abstract val pending: Map<UUID, Pair<OutgoingPayment, SharedSecrets>>
        abstract val fees: MilliSatoshi

        fun isComplete(): Boolean = pending.isEmpty()

        /**
         * @param totalAmount total amount that the trampoline node should receive.
         * @param expiry expiry at the trampoline node.
         * @param paymentSecret trampoline payment secret (should be different from the invoice payment secret).
         * @param packet trampoline onion packet.
         */
        data class TrampolinePayload(val totalAmount: MilliSatoshi, val expiry: CltvExpiry, val paymentSecret: ByteVector32, val packet: OnionRoutingPacket) {
            fun createFinalPayload(partialAmount: MilliSatoshi): FinalPayload = FinalPayload.createTrampolinePayload(partialAmount, totalAmount, expiry, paymentSecret, packet)
        }

        /**
         * While a payment is in progress, we listen to child payments failures.
         * When we receive failures, we retry the failed amount with different routes/fees.
         *
         * @param request payment request containing the total amount to send.
         * @param attemptNumber number of failed previous payment attempts.
         * @param trampolinePayload trampoline payload for the current payment attempt.
         * @param pending pending child payments (HTLCs were sent, we are waiting for a fulfill or a failure).
         * @param ignore channels that should be ignored (previously returned an error).
         * @param failures previous child payment failures.
         */
        data class PaymentInProgress(
            override val request: SendPayment,
            val attemptNumber: Int,
            val trampolinePayload: TrampolinePayload,
            override val pending: Map<UUID, Pair<OutgoingPayment, SharedSecrets>>,
            val ignore: Set<ByteVector32>,
            val failures: List<Either<ChannelException, FailureMessage>>
        ) : PaymentAttempt() {
            override val fees: MilliSatoshi = pending.values.map { it.first.amount }.sum() - request.paymentAmount
        }

        /**
         * When we exhaust our retry attempts without success or encounter a non-recoverable error, we abort the payment.
         * Once we're in that state, we wait for all the pending child payments to settle.
         *
         * @param request payment request containing the total amount to send.
         * @param reason failure reason.
         * @param pending pending child payments (we are waiting for them to be failed downstream).
         * @param failures child payment failures.
         */
        data class PaymentAborted(
            override val request: SendPayment,
            val reason: FinalFailure,
            override val pending: Map<UUID, Pair<OutgoingPayment, SharedSecrets>>,
            val failures: List<Either<ChannelException, FailureMessage>>
        ) : PaymentAttempt() {
            override val fees: MilliSatoshi = 0.msat

            fun failChild(childId: UUID, failure: List<Either<ChannelException, FailureMessage>>, logger: Logger): Pair<PaymentAborted, ProcessFailureResult?> {
                val updated = copy(pending = pending - childId, failures = failures + failure)
                val result = if (updated.isComplete()) {
                    logger.warning { "h:${request.paymentHash} p:${request.paymentId} payment failed: ${updated.reason.message}" }
                    Failure(request, OutgoingPaymentFailure(updated.reason, updated.failures))
                } else {
                    null
                }
                return Pair(updated, result)
            }
        }

        /**
         * Once we receive a first fulfill for a child payment, we can consider that the whole payment succeeded (because we
         * received the payment preimage that we can use as a proof of payment).
         * Once we're in that state, we wait for all the pending child payments to fulfill.
         *
         * @param request payment request containing the total amount to send.
         * @param preimage payment preimage.
         * @param parts fulfilled child payments.
         * @param pending pending child payments (we are waiting for them to be fulfilled downstream).
         */
        data class PaymentSucceeded(
            override val request: SendPayment,
            val preimage: ByteVector32,
            val parts: List<OutgoingPayment>,
            override val pending: Map<UUID, Pair<OutgoingPayment, SharedSecrets>>
        ) : PaymentAttempt() {
            override val fees: MilliSatoshi = parts.map { it.amount }.sum() + pending.values.map { it.first.amount }.sum() - request.paymentAmount

            // The recipient released the preimage without receiving the full payment amount.
            // This is a spec violation and is too bad for them, we obtained a proof of payment without paying the full amount.
            fun failChild(childId: UUID, logger: Logger): Pair<PaymentSucceeded, Success?> {
                logger.warning { "h:${request.paymentHash} p:${request.paymentId} partial payment failure after fulfill: we may have paid less than the full amount" }
                val updated = copy(pending = pending - childId)
                val result = if (updated.isComplete()) {
                    logger.info { "h:${request.paymentHash} p:${request.paymentId} payment successfully sent (fees=${updated.fees})" }
                    Success(request, preimage, updated.fees)
                } else {
                    null
                }
                return Pair(updated, result)
            }
        }
    }

}
