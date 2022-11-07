package fr.acinq.lightning.payment

import fr.acinq.bitcoin.ByteVector32
import fr.acinq.lightning.*
import fr.acinq.lightning.channel.*
import fr.acinq.lightning.crypto.sphinx.FailurePacket
import fr.acinq.lightning.crypto.sphinx.SharedSecrets
import fr.acinq.lightning.db.HopDesc
import fr.acinq.lightning.db.OutgoingPayment
import fr.acinq.lightning.db.OutgoingPaymentsDb
import fr.acinq.lightning.io.SendPayment
import fr.acinq.lightning.io.SendPaymentNormal
import fr.acinq.lightning.io.SendPaymentSwapOut
import fr.acinq.lightning.io.WrappedChannelCommand
import fr.acinq.lightning.router.ChannelHop
import fr.acinq.lightning.router.NodeHop
import fr.acinq.lightning.utils.*
import fr.acinq.lightning.wire.*
import org.kodein.log.Logger
import org.kodein.log.newLogger

class OutgoingPaymentHandler(val nodeParams: NodeParams, val walletParams: WalletParams, val db: OutgoingPaymentsDb) {

    interface SendPaymentResult
    interface ProcessFailureResult
    interface ProcessFulfillResult

    /** A payment attempt has been made: we provide information about the fees we're paying, which may increase as we re-try our payment. */
    data class Progress(val request: SendPayment, val fees: MilliSatoshi, val actions: List<WrappedChannelCommand>) : SendPaymentResult, ProcessFailureResult

    /** The payment could not be sent. */
    data class Failure(val request: SendPayment, val failure: OutgoingPaymentFailure) : SendPaymentResult, ProcessFailureResult

    /** The recipient released the preimage, but we are still waiting for some partial payments to settle. */
    data class PreimageReceived(val request: SendPayment, val preimage: ByteVector32) : ProcessFulfillResult

    /** The payment was successfully made. */
    data class Success(val request: SendPayment, val payment: OutgoingPayment, val preimage: ByteVector32) : ProcessFailureResult, ProcessFulfillResult

    private val logger = nodeParams.loggerFactory.newLogger(this::class)
    private val childToParentId = mutableMapOf<UUID, UUID>()
    private val pending = mutableMapOf<UUID, PaymentAttempt>()
    private val routeCalculation = RouteCalculation(nodeParams.loggerFactory)

    // NB: this function should only be used in tests.
    fun getPendingPayment(parentId: UUID): PaymentAttempt? = pending[parentId]

    private fun getPaymentAttempt(childId: UUID): PaymentAttempt? = childToParentId[childId]?.let { pending[it] }

    suspend fun sendPayment(request: SendPayment, channels: Map<ByteVector32, ChannelState>, currentBlockHeight: Int): SendPaymentResult {
        logger.info { "h:${request.paymentHash} p:${request.paymentId} sending ${request.amount} to ${request.recipient}" }
        if (request.amount <= 0.msat) {
            logger.warning { "h:${request.paymentHash} p:${request.paymentId} payment amount must be positive (${request.amount})" }
            return Failure(request, FinalFailure.InvalidPaymentAmount.toPaymentFailure())
        }
        if (!nodeParams.features.areSupported(Features(request.paymentRequest.features).invoiceFeatures())) {
            logger.warning { "h:${request.paymentHash} p:${request.paymentId} invoice contains mandatory features that we don't support" }
            return Failure(request, FinalFailure.FeaturesNotSupported.toPaymentFailure())
        }
        if (pending.containsKey(request.paymentId)) {
            logger.error { "h:${request.paymentHash} p:${request.paymentId} contract violation: caller is recycling uuid's" }
            return Failure(request, FinalFailure.InvalidPaymentId.toPaymentFailure())
        }
        if (db.listOutgoingPayments(request.paymentHash).find { it.status is OutgoingPayment.Status.Completed.Succeeded } != null) {
            logger.error { "h:${request.paymentHash} p:${request.paymentId} invoice has already been paid" }
            return Failure(request, FinalFailure.AlreadyPaid.toPaymentFailure())
        }
        val trampolineFees = request.trampolineFeesOverride ?: walletParams.trampolineFees
        val (trampolineAmount, trampolineExpiry, trampolinePacket) = createTrampolinePayload(request, trampolineFees.first(), currentBlockHeight)
        return when (val result = routeCalculation.findRoutes(request.paymentId, trampolineAmount, channels)) {
            is Either.Left -> {
                logger.warning { "h:${request.paymentHash} p:${request.paymentId} payment failed: ${result.value}" }
                db.addOutgoingPayment(OutgoingPayment(request.paymentId, request.amount, request.recipient, request.details))
                val finalFailure = result.value
                db.completeOutgoingPaymentOffchain(request.paymentId, finalFailure)
                Failure(request, finalFailure.toPaymentFailure())
            }
            is Either.Right -> {
                // We generate a random secret for this payment to avoid leaking the invoice secret to the trampoline node.
                val trampolinePaymentSecret = Lightning.randomBytes32()
                val trampolinePayload = PaymentAttempt.TrampolinePayload(trampolineAmount, trampolineExpiry, trampolinePaymentSecret, trampolinePacket)
                val childPayments = createChildPayments(request, result.value, trampolinePayload)
                db.addOutgoingPayment(OutgoingPayment(request.paymentId, request.amount, request.recipient, request.details, childPayments.map { it.first }, OutgoingPayment.Status.Pending))
                val payment = PaymentAttempt.PaymentInProgress(request, 0, trampolinePayload, childPayments.associate { it.first.id to Pair(it.first, it.second) }, setOf(), listOf())
                pending[request.paymentId] = payment
                Progress(request, payment.fees, childPayments.map { it.third })
            }
        }
    }

    private fun createChildPayments(request: SendPayment, routes: List<RouteCalculation.Route>, trampolinePayload: PaymentAttempt.TrampolinePayload): List<Triple<OutgoingPayment.LightningPart, SharedSecrets, WrappedChannelCommand>> {
        val childPayments = routes.map { createOutgoingPart(request, it, trampolinePayload) }
        childToParentId.putAll(childPayments.map { it.first.id to request.paymentId })
        childPayments.forEach { logger.info { "h:${request.paymentHash} p:${request.paymentId} i:${it.first.id} sending ${it.first.amount} to channel ${it.third.channelId}" } }
        return childPayments
    }

    suspend fun processAddFailed(channelId: ByteVector32, event: ChannelAction.ProcessCmdRes.AddFailed, channels: Map<ByteVector32, ChannelState>): ProcessFailureResult? {
        val add = event.cmd
        val payment = getPaymentAttempt(add.paymentId) ?: return processPostRestartFailure(add.paymentId, Either.Left(event.error))

        logger.debug { "h:${add.paymentHash} p:${payment.request.paymentId} i:${add.paymentId} could not send HTLC: ${event.error.message}" }
        db.completeOutgoingLightningPart(add.paymentId, Either.Left(event.error))

        val (updated, result) = when (payment) {
            is PaymentAttempt.PaymentInProgress -> {
                val ignore = payment.ignore + channelId // we ignore the failing channel in retries
                when (val routes = routeCalculation.findRoutes(payment.request.paymentId, add.amount, channels - ignore)) {
                    is Either.Left -> PaymentAttempt.PaymentAborted(payment.request, routes.value, payment.pending, payment.failures).failChild(add.paymentId, Either.Left(event.error), db, logger)
                    is Either.Right -> {
                        val newPayments = createChildPayments(payment.request, routes.value, payment.trampolinePayload)
                        db.addOutgoingLightningParts(payment.request.paymentId, newPayments.map { it.first })
                        val updatedPayments = payment.pending - add.paymentId + newPayments.map { it.first.id to Pair(it.first, it.second) }
                        val updated = payment.copy(ignore = ignore, failures = payment.failures + Either.Left(event.error), pending = updatedPayments)
                        val result = Progress(payment.request, updated.fees, newPayments.map { it.third })
                        Pair(updated, result)
                    }
                }
            }
            is PaymentAttempt.PaymentAborted -> payment.failChild(add.paymentId, Either.Left(event.error), db, logger)
            is PaymentAttempt.PaymentSucceeded -> payment.failChild(add.paymentId, db, logger)
        }

        updateGlobalState(add.paymentId, updated)

        return result
    }

    suspend fun processAddSettled(channelId: ByteVector32, event: ChannelAction.ProcessCmdRes.AddSettledFail, channels: Map<ByteVector32, ChannelState>, currentBlockHeight: Int): ProcessFailureResult? {
        val payment = getPaymentAttempt(event.paymentId) ?: return processPostRestartFailure(event.paymentId, Either.Right(UnknownFailureMessage(0)))

        val failure: FailureMessage = when (event.result) {
            is ChannelAction.HtlcResult.Fail.RemoteFail -> when (val part = payment.pending[event.paymentId]) {
                null -> UnknownFailureMessage(0)
                else -> when (val decrypted = FailurePacket.decrypt(event.result.fail.reason.toByteArray(), part.second)) {
                    is Try.Failure -> UnknownFailureMessage(1)
                    is Try.Success -> decrypted.result.failureMessage
                }
            }
            else -> UnknownFailureMessage(FailureMessage.BADONION)
        }

        logger.debug { "h:${payment.request.paymentHash} p:${payment.request.paymentId} i:${event.paymentId} HTLC failed: ${failure.message}" }
        db.completeOutgoingLightningPart(event.paymentId, Either.Right(failure))

        val (updated, result) = when (payment) {
            is PaymentAttempt.PaymentInProgress -> {
                val trampolineFees = payment.request.trampolineFeesOverride ?: walletParams.trampolineFees
                val finalError = when {
                    trampolineFees.size <= payment.attemptNumber + 1 -> FinalFailure.RetryExhausted
                    failure == UnknownNextPeer -> FinalFailure.RecipientUnreachable
                    failure != TrampolineExpiryTooSoon && failure != TrampolineFeeInsufficient -> FinalFailure.UnknownError // non-retriable error
                    else -> null
                }
                if (finalError != null) {
                    PaymentAttempt.PaymentAborted(payment.request, finalError, payment.pending, listOf()).failChild(event.paymentId, Either.Right(failure), db, logger)
                } else {
                    // The trampoline node is asking us to retry the payment with more fees.
                    logger.debug { "h:${payment.request.paymentHash} p:${payment.request.paymentId} i:${event.paymentId} c:$channelId child payment failed because of fees" }
                    val updated = payment.copy(pending = payment.pending - event.paymentId)
                    if (updated.pending.isNotEmpty()) {
                        // We wait for all pending HTLCs to be settled before retrying.
                        // NB: we don't update failures here to avoid duplicate trampoline errors
                        Pair(updated, null)
                    } else {
                        val nextFees = trampolineFees[payment.attemptNumber + 1]
                        logger.info { "h:${payment.request.paymentHash} p:${payment.request.paymentId} retrying payment with higher fees (base=${nextFees.feeBase}, proportional=${nextFees.feeProportional})..." }
                        val (trampolineAmount, trampolineExpiry, trampolinePacket) = createTrampolinePayload(payment.request, nextFees, currentBlockHeight)
                        when (val routes = routeCalculation.findRoutes(payment.request.paymentId, trampolineAmount, channels)) {
                            is Either.Left -> {
                                logger.warning { "h:${payment.request.paymentHash} p:${payment.request.paymentId} payment failed: ${routes.value}" }
                                val aborted = PaymentAttempt.PaymentAborted(payment.request, routes.value, mapOf(), payment.failures + Either.Right(failure))
                                val result = Failure(payment.request, OutgoingPaymentFailure(aborted.reason, aborted.failures))
                                db.completeOutgoingPaymentOffchain(payment.request.paymentId, result.failure.reason)
                                Pair(aborted, result)
                            }
                            is Either.Right -> {
                                // We generate a random secret for this payment to avoid leaking the invoice secret to the trampoline node.
                                val trampolinePaymentSecret = Lightning.randomBytes32()
                                val trampolinePayload = PaymentAttempt.TrampolinePayload(trampolineAmount, trampolineExpiry, trampolinePaymentSecret, trampolinePacket)
                                val childPayments = createChildPayments(payment.request, routes.value, trampolinePayload)
                                db.addOutgoingLightningParts(payment.request.paymentId, childPayments.map { it.first })
                                val newAttempt = PaymentAttempt.PaymentInProgress(
                                    payment.request,
                                    payment.attemptNumber + 1,
                                    trampolinePayload,
                                    childPayments.associate { it.first.id to Pair(it.first, it.second) },
                                    setOf(), // we reset ignored channels
                                    payment.failures + Either.Right(failure)
                                )
                                val result = Progress(newAttempt.request, newAttempt.fees, childPayments.map { it.third })
                                Pair(newAttempt, result)
                            }
                        }
                    }
                }
            }
            is PaymentAttempt.PaymentAborted -> payment.failChild(event.paymentId, Either.Right(failure), db, logger)
            is PaymentAttempt.PaymentSucceeded -> payment.failChild(event.paymentId, db, logger)
        }

        updateGlobalState(event.paymentId, updated)

        return result
    }

    private suspend fun processPostRestartFailure(partId: UUID, failure: Either<ChannelException, FailureMessage>): ProcessFailureResult? {
        when (val payment = db.getOutgoingPaymentFromPartId(partId)) {
            null -> {
                logger.error { "i:$partId paymentId doesn't match any known payment attempt" }
                return null
            }
            else -> {
                logger.debug { "h:${payment.paymentHash} p:${payment.id} i:$partId could not send HTLC (wallet restart): ${failure.fold({ it.message }, { it.message })}" }
                db.completeOutgoingLightningPart(partId, failure)
                val parts = payment.parts.filterIsInstance<OutgoingPayment.LightningPart>()
                val hasMorePendingParts = parts.any { it.status == OutgoingPayment.LightningPart.Status.Pending && it.id != partId }
                return if (!hasMorePendingParts) {
                    logger.warning { "h:${payment.paymentHash} p:${payment.id} payment failed: ${FinalFailure.WalletRestarted}" }
                    db.completeOutgoingPaymentOffchain(payment.id, FinalFailure.WalletRestarted)
                    val request = when (payment.details) {
                        is OutgoingPayment.Details.Normal -> SendPaymentNormal(payment.id, payment.recipientAmount, payment.recipient, payment.details)
                        is OutgoingPayment.Details.SwapOut -> SendPaymentSwapOut(payment.id, payment.recipientAmount, payment.recipient, payment.details)
                        else -> {
                            logger.debug { "h:${payment.paymentHash} p:${payment.id} i:$partId cannot recreate send-payment-request failure from db data with details=${payment.details}" }
                            return null
                        }
                    }
                    Failure(
                        request = request,
                        failure = OutgoingPaymentFailure(
                            reason = FinalFailure.WalletRestarted,
                            failures = parts.map { it.status }.filterIsInstance<OutgoingPayment.LightningPart.Status.Failed>() + OutgoingPaymentFailure.convertFailure(failure)
                        )
                    )
                } else {
                    null
                }
            }
        }
    }

    suspend fun processAddSettled(event: ChannelAction.ProcessCmdRes.AddSettledFulfill): ProcessFulfillResult? {
        val preimage = event.result.paymentPreimage
        val payment = getPaymentAttempt(event.paymentId) ?: return processPostRestartFulfill(event.paymentId, preimage)

        logger.debug { "h:${payment.request.paymentHash} p:${payment.request.paymentId} i:${event.paymentId} HTLC fulfilled" }
        val part = payment.pending[event.paymentId]?.first?.copy(status = OutgoingPayment.LightningPart.Status.Succeeded(preimage))
        db.completeOutgoingLightningPart(event.paymentId, preimage)

        val updated = when (payment) {
            is PaymentAttempt.PaymentInProgress -> PaymentAttempt.PaymentSucceeded(payment.request, preimage, part?.let { listOf(it) } ?: listOf(), payment.pending - event.paymentId)
            is PaymentAttempt.PaymentSucceeded -> payment.copy(pending = payment.pending - event.paymentId, parts = part?.let { payment.parts + it } ?: payment.parts)
            is PaymentAttempt.PaymentAborted -> {
                // The recipient released the preimage without receiving the full payment amount.
                // This is a spec violation and is too bad for them, we obtained a proof of payment without paying the full amount.
                logger.warning { "h:${payment.request.paymentHash} p:${payment.request.paymentId} i:${event.paymentId} payment succeeded after partial failure: we may have paid less than the full amount" }
                PaymentAttempt.PaymentSucceeded(payment.request, preimage, part?.let { listOf(it) } ?: listOf(), payment.pending - event.paymentId)
            }
        }

        updateGlobalState(event.paymentId, updated)

        return if (updated.isComplete()) {
            logger.info { "h:${payment.request.paymentHash} p:${payment.request.paymentId} payment successfully sent (fees=${updated.fees})" }
            db.completeOutgoingPaymentOffchain(payment.request.paymentId, preimage)
            val r = payment.request
            Success(r, OutgoingPayment(r.paymentId, r.amount, r.recipient, r.details, updated.parts, OutgoingPayment.Status.Completed.Succeeded.OffChain(preimage)), preimage)
        } else {
            PreimageReceived(payment.request, preimage)
        }
    }

    private suspend fun processPostRestartFulfill(partId: UUID, preimage: ByteVector32): ProcessFulfillResult? {
        when (val payment = db.getOutgoingPaymentFromPartId(partId)) {
            null -> {
                logger.error { "i:$partId paymentId doesn't match any known payment attempt" }
                return null
            }
            else -> {
                logger.debug { "h:${payment.paymentHash} p:${payment.id} i:$partId HTLC succeeded (wallet restart): $preimage" }
                db.completeOutgoingLightningPart(partId, preimage)
                // We try to re-create the request from what we have in the DB.
                val request = when (payment.details) {
                    is OutgoingPayment.Details.Normal -> SendPaymentNormal(payment.id, payment.recipientAmount, payment.recipient, payment.details)
                    is OutgoingPayment.Details.SwapOut -> SendPaymentSwapOut(payment.id, payment.recipientAmount, payment.recipient, payment.details)
                    else -> {
                        logger.warning { "h:${payment.paymentHash} p:${payment.id} i:$partId cannot recreate send-payment-request fulfill from db data with details=${payment.details}" }
                        return null
                    }
                }
                val hasMorePendingParts = payment.parts.filterIsInstance<OutgoingPayment.LightningPart>().any { it.status == OutgoingPayment.LightningPart.Status.Pending && it.id != partId }
                return if (!hasMorePendingParts) {
                    logger.info { "h:${payment.paymentHash} p:${payment.id} payment successfully sent (wallet restart)" }
                    db.completeOutgoingPaymentOffchain(payment.id, preimage)
                    val succeeded = db.getOutgoingPayment(payment.id)!! //  NB: we reload the payment to ensure all parts status are updated
                    Success(request, succeeded, preimage)
                } else {
                    PreimageReceived(request, preimage)
                }
            }
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

    private fun createOutgoingPart(request: SendPayment, route: RouteCalculation.Route, trampolinePayload: PaymentAttempt.TrampolinePayload): Triple<OutgoingPayment.LightningPart, SharedSecrets, WrappedChannelCommand> {
        val childId = UUID.randomUUID()
        val outgoingPayment = OutgoingPayment.LightningPart(
            id = childId,
            amount = route.amount,
            route = listOf(HopDesc(nodeParams.nodeId, route.channel.commitments.remoteParams.nodeId, route.channel.shortChannelId), HopDesc(route.channel.commitments.remoteParams.nodeId, request.recipient)),
            status = OutgoingPayment.LightningPart.Status.Pending
        )
        val channelHops: List<ChannelHop> = listOf(ChannelHop(nodeParams.nodeId, route.channel.commitments.remoteParams.nodeId, route.channel.channelUpdate))
        val (add, secrets) = OutgoingPaymentPacket.buildCommand(childId, request.paymentHash, channelHops, trampolinePayload.createFinalPayload(route.amount))
        return Triple(outgoingPayment, secrets, WrappedChannelCommand(route.channel.channelId, ChannelCommand.ExecuteCommand(add)))
    }

    private fun createTrampolinePayload(request: SendPayment, fees: TrampolineFees, currentBlockHeight: Int): Triple<MilliSatoshi, CltvExpiry, OnionRoutingPacket> {
        // We are either directly paying our peer (the trampoline node) or a remote node via our peer (using trampoline).
        val trampolineRoute = when (request.recipient) {
            walletParams.trampolineNode.id -> listOf(
                NodeHop(nodeParams.nodeId, request.recipient, /* ignored */ CltvExpiryDelta(0), /* ignored */ 0.msat)
            )
            else -> listOf(
                NodeHop(nodeParams.nodeId, walletParams.trampolineNode.id, /* ignored */ CltvExpiryDelta(0), /* ignored */ 0.msat),
                NodeHop(walletParams.trampolineNode.id, request.recipient, fees.cltvExpiryDelta, fees.calculateFees(request.amount))
            )
        }

        val finalExpiryDelta = request.paymentRequest.minFinalExpiryDelta ?: Channel.MIN_CLTV_EXPIRY_DELTA
        val finalExpiry = finalExpiryDelta.toCltvExpiry(currentBlockHeight.toLong())
        val finalPayload = PaymentOnion.FinalPayload.createSinglePartPayload(request.amount, finalExpiry, request.paymentRequest.paymentSecret, request.paymentRequest.paymentMetadata)

        val invoiceFeatures = Features(request.paymentRequest.features)
        val (trampolineAmount, trampolineExpiry, trampolineOnion) = if (invoiceFeatures.hasFeature(Feature.TrampolinePayment) || invoiceFeatures.hasFeature(Feature.ExperimentalTrampolinePayment)) {
            OutgoingPaymentPacket.buildPacket(request.paymentHash, trampolineRoute, finalPayload, OnionRoutingPacket.TrampolinePacketLength)
        } else {
            OutgoingPaymentPacket.buildTrampolineToLegacyPacket(request.paymentRequest, trampolineRoute, finalPayload)
        }
        return Triple(trampolineAmount, trampolineExpiry, trampolineOnion.packet)
    }

    sealed class PaymentAttempt {
        abstract val request: SendPayment
        abstract val pending: Map<UUID, Pair<OutgoingPayment.LightningPart, SharedSecrets>>
        abstract val fees: MilliSatoshi

        fun isComplete(): Boolean = pending.isEmpty()

        /**
         * @param totalAmount total amount that the trampoline node should receive.
         * @param expiry expiry at the trampoline node.
         * @param paymentSecret trampoline payment secret (should be different from the invoice payment secret).
         * @param packet trampoline onion packet.
         */
        data class TrampolinePayload(val totalAmount: MilliSatoshi, val expiry: CltvExpiry, val paymentSecret: ByteVector32, val packet: OnionRoutingPacket) {
            fun createFinalPayload(partialAmount: MilliSatoshi): PaymentOnion.FinalPayload = PaymentOnion.FinalPayload.createTrampolinePayload(partialAmount, totalAmount, expiry, paymentSecret, packet)
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
            override val pending: Map<UUID, Pair<OutgoingPayment.LightningPart, SharedSecrets>>,
            val ignore: Set<ByteVector32>,
            val failures: List<Either<ChannelException, FailureMessage>>
        ) : PaymentAttempt() {
            override val fees: MilliSatoshi = pending.values.map { it.first.amount }.sum() - request.amount
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
            override val pending: Map<UUID, Pair<OutgoingPayment.LightningPart, SharedSecrets>>,
            val failures: List<Either<ChannelException, FailureMessage>>
        ) : PaymentAttempt() {
            override val fees: MilliSatoshi = 0.msat

            suspend fun failChild(childId: UUID, failure: Either<ChannelException, FailureMessage>, db: OutgoingPaymentsDb, logger: Logger): Pair<PaymentAborted, ProcessFailureResult?> {
                val updated = copy(pending = pending - childId, failures = failures + failure)
                val result = if (updated.isComplete()) {
                    logger.warning { "h:${request.paymentHash} p:${request.paymentId} payment failed: ${updated.reason}" }
                    db.completeOutgoingPaymentOffchain(request.paymentId, updated.reason)
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
            val parts: List<OutgoingPayment.LightningPart>,
            override val pending: Map<UUID, Pair<OutgoingPayment.LightningPart, SharedSecrets>>
        ) : PaymentAttempt() {
            override val fees: MilliSatoshi = parts.map { it.amount }.sum() + pending.values.map { it.first.amount }.sum() - request.amount

            // The recipient released the preimage without receiving the full payment amount.
            // This is a spec violation and is too bad for them, we obtained a proof of payment without paying the full amount.
            suspend fun failChild(childId: UUID, db: OutgoingPaymentsDb, logger: Logger): Pair<PaymentSucceeded, Success?> {
                logger.warning { "h:${request.paymentHash} p:${request.paymentId} partial payment failure after fulfill: we may have paid less than the full amount" }
                val updated = copy(pending = pending - childId)
                val result = if (updated.isComplete()) {
                    logger.info { "h:${request.paymentHash} p:${request.paymentId} payment successfully sent (fees=${updated.fees})" }
                    db.completeOutgoingPaymentOffchain(request.paymentId, preimage)
                    Success(request, OutgoingPayment(request.paymentId, request.amount, request.recipient, request.details, parts, OutgoingPayment.Status.Completed.Succeeded.OffChain(preimage)), preimage)
                } else {
                    null
                }
                return Pair(updated, result)
            }
        }
    }

}
