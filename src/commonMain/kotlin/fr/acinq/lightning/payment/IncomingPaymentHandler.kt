package fr.acinq.lightning.payment

import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.Crypto
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.bitcoin.utils.Either
import fr.acinq.lightning.*
import fr.acinq.lightning.Lightning.randomBytes32
import fr.acinq.lightning.LiquidityEvents
import fr.acinq.lightning.MilliSatoshi
import fr.acinq.lightning.NodeParams
import fr.acinq.lightning.blockchain.fee.FeeratePerKw
import fr.acinq.lightning.channel.ChannelAction
import fr.acinq.lightning.channel.ChannelCommand
import fr.acinq.lightning.channel.Origin
import fr.acinq.lightning.db.IncomingPayment
import fr.acinq.lightning.db.IncomingPaymentsDb
import fr.acinq.lightning.io.OpenOrSplicePayment
import fr.acinq.lightning.io.PeerCommand
import fr.acinq.lightning.io.WrappedChannelCommand
import fr.acinq.lightning.logging.MDCLogger
import fr.acinq.lightning.logging.mdc
import fr.acinq.lightning.utils.*
import fr.acinq.lightning.wire.*

sealed class PaymentPart {
    abstract val amount: MilliSatoshi
    abstract val totalAmount: MilliSatoshi
    abstract val paymentHash: ByteVector32
    abstract val finalPayload: PaymentOnion.FinalPayload
}

data class HtlcPart(val htlc: UpdateAddHtlc, override val finalPayload: PaymentOnion.FinalPayload) : PaymentPart() {
    override val amount: MilliSatoshi = htlc.amountMsat
    override val totalAmount: MilliSatoshi = finalPayload.totalAmount
    override val paymentHash: ByteVector32 = htlc.paymentHash
    override fun toString(): String = "htlc(channelId=${htlc.channelId},id=${htlc.id})"
}

data class OnTheFlyFundingPart(val htlc: MaybeAddHtlc, override val finalPayload: PaymentOnion.FinalPayload) : PaymentPart() {
    override val amount: MilliSatoshi = htlc.amount
    override val totalAmount: MilliSatoshi = finalPayload.totalAmount
    override val paymentHash: ByteVector32 = htlc.paymentHash
    override fun toString(): String = "maybe-htlc(amount=${htlc.amount})"
}

class IncomingPaymentHandler(val nodeParams: NodeParams, val db: IncomingPaymentsDb, val leaseRates: List<LiquidityAds.BoundedLeaseRate>) {

    sealed class ProcessAddResult {
        abstract val actions: List<PeerCommand>

        data class Accepted(override val actions: List<PeerCommand>, val incomingPayment: IncomingPayment, val received: IncomingPayment.Received) : ProcessAddResult()
        data class Rejected(override val actions: List<PeerCommand>, val incomingPayment: IncomingPayment?) : ProcessAddResult()
        data class Pending(val incomingPayment: IncomingPayment, val pendingPayment: PendingPayment) : ProcessAddResult() {
            override val actions: List<PeerCommand> = listOf()
        }
    }

    /**
     * We support receiving multipart payments, where an incoming payment will be split across multiple partial payments.
     * When we receive the first part, we need to start a timer: if we don't receive the rest of the payment before that
     * timer expires, we should fail the pending parts to avoid locking up liquidity and allow the sender to retry.
     *
     * @param parts partial payments received.
     * @param totalAmount total amount that should be received.
     * @param startedAtSeconds time at which we received the first partial payment (in seconds).
     */
    data class PendingPayment(val parts: Set<PaymentPart>, val totalAmount: MilliSatoshi, val startedAtSeconds: Long) {
        constructor(firstPart: PaymentPart) : this(setOf(firstPart), firstPart.totalAmount, currentTimestampSeconds())

        val amountReceived: MilliSatoshi = parts.map { it.amount }.sum()

        fun add(part: PaymentPart): PendingPayment = copy(parts = parts + part)
    }

    private val logger = MDCLogger(nodeParams.loggerFactory.newLogger(this::class))
    private val pending = mutableMapOf<ByteVector32, PendingPayment>()
    private val privateKey = nodeParams.nodePrivateKey

    suspend fun createInvoice(
        paymentPreimage: ByteVector32,
        amount: MilliSatoshi?,
        description: Either<String, ByteVector32>,
        extraHops: List<List<Bolt11Invoice.TaggedField.ExtraHop>>,
        expirySeconds: Long? = null,
        timestampSeconds: Long = currentTimestampSeconds()
    ): Bolt11Invoice {
        val paymentHash = Crypto.sha256(paymentPreimage).toByteVector32()
        logger.debug(mapOf("paymentHash" to paymentHash)) { "using routing hints $extraHops" }
        val pr = Bolt11Invoice.create(
            nodeParams.chainHash,
            amount,
            paymentHash,
            nodeParams.nodePrivateKey,
            description,
            Bolt11Invoice.DEFAULT_MIN_FINAL_EXPIRY_DELTA,
            nodeParams.features.invoiceFeatures(),
            randomBytes32(),
            // We always include a payment metadata in our invoices, which lets us test whether senders support it
            ByteVector("2a"),
            expirySeconds,
            extraHops,
            timestampSeconds
        )
        logger.info(mapOf("paymentHash" to paymentHash)) { "generated payment request ${pr.write()}" }
        db.addIncomingPayment(paymentPreimage, IncomingPayment.Origin.Invoice(pr))
        return pr
    }

    /**
     * Save the "received-with" details of an incoming amount.
     *
     * - for an off-chain origin, the payment already exists and we only add a received-with.
     * - for a swap-in origin, a new incoming payment must be created with a random payment hash.
     */
    suspend fun process(channelId: ByteVector32, action: ChannelAction.Storage.StoreIncomingPayment) {
        val receivedWith = when (action) {
            is ChannelAction.Storage.StoreIncomingPayment.ViaNewChannel ->
                IncomingPayment.ReceivedWith.OnChainIncomingPayment.Received.NewChannel(
                    amount = action.amount,
                    serviceFee = action.serviceFee,
                    miningFee = action.miningFee,
                    channelId = channelId,
                    txId = action.txId,
                    confirmedAt = null,
                    lockedAt = null,
                )
            is ChannelAction.Storage.StoreIncomingPayment.ViaSpliceIn ->
                IncomingPayment.ReceivedWith.OnChainIncomingPayment.Received.SpliceIn(
                    amount = action.amount,
                    serviceFee = action.serviceFee,
                    miningFee = action.miningFee,
                    channelId = channelId,
                    txId = action.txId,
                    confirmedAt = null,
                    lockedAt = null,
                )
            is ChannelAction.Storage.StoreIncomingPayment.Cancelled -> {
                logger.warning { "channelId:$channelId on-the-fly funding cancelled by our peer, payment may be partially received" }
                val event = LiquidityEvents.Rejected(action.origin.amount, action.origin.fees.total.toMilliSatoshi(), LiquidityEvents.Source.OffChainPayment, LiquidityEvents.Rejected.Reason.ChannelFundingCancelled(action.origin.paymentHash))
                nodeParams._nodeEvents.emit(event)
                IncomingPayment.ReceivedWith.OnChainIncomingPayment.Cancelled(action.origin.amount)
            }
        }
        when (val origin = action.origin) {
            is Origin.OffChainPayment -> {
                // There already is a corresponding invoice in the db.
                db.receivePayment(origin.paymentHash, listOf(receivedWith))
                nodeParams._nodeEvents.emit(PaymentEvents.PaymentReceived(origin.paymentHash, listOf(receivedWith)))
            }
            else -> {
                // This is a swap, there was no pre-existing invoice, we need to create a fake one.
                val incomingPayment = db.addIncomingPayment(preimage = randomBytes32(), origin = IncomingPayment.Origin.OnChain(action.txId, action.localInputs))
                db.receivePayment(incomingPayment.paymentHash, listOf(receivedWith))
                nodeParams._nodeEvents.emit(PaymentEvents.PaymentReceived(incomingPayment.paymentHash, listOf(receivedWith)))
            }
        }
    }

    /**
     * Process an incoming htlc.
     * Before calling this, the htlc must be committed and ack-ed by both sides.
     *
     * @return A result that indicates whether or not the packet was accepted,
     * rejected, or still pending (as the case may be for multipart payments).
     * Also includes the list of actions to be queued.
     */
    suspend fun process(htlc: UpdateAddHtlc, currentBlockHeight: Int, currentFeerate: FeeratePerKw): ProcessAddResult {
        // Security note:
        // There are several checks we could perform before decrypting the onion.
        // However an error message here would differ from an error message below,
        // as we don't know the `onion.totalAmount` yet.
        // So to prevent any kind of information leakage, we always peel the onion first.
        return when (val res = toPaymentPart(privateKey, htlc)) {
            is Either.Left -> res.value
            is Either.Right -> processPaymentPart(res.value, currentBlockHeight, currentFeerate)
        }
    }

    /**
     * Process an incoming on-the-fly funding request.
     * This is very similar to the processing of an htlc.
     */
    suspend fun process(htlc: MaybeAddHtlc, currentBlockHeight: Int, currentFeerate: FeeratePerKw): ProcessAddResult {
        return when (val res = toPaymentPart(privateKey, htlc, logger)) {
            is Either.Left -> res.value
            is Either.Right -> processPaymentPart(res.value, currentBlockHeight, currentFeerate)
        }
    }

    /** Main payment processing, that handles payment parts. */
    private suspend fun processPaymentPart(paymentPart: PaymentPart, currentBlockHeight: Int, currentFeerate: FeeratePerKw): ProcessAddResult {
        val logger = MDCLogger(logger.logger, staticMdc = paymentPart.mdc())
        when (paymentPart) {
            is HtlcPart -> logger.info { "processing htlc part amount=${paymentPart.htlc.amountMsat} expiry=${paymentPart.htlc.cltvExpiry}" }
            is OnTheFlyFundingPart -> logger.info { "processing on-the-fly funding part amount=${paymentPart.htlc.amount} expiry=${paymentPart.htlc.expiry}" }
        }
        return when (val validationResult = validatePaymentPart(paymentPart, currentBlockHeight)) {
            is Either.Left -> validationResult.value
            is Either.Right -> {
                val incomingPayment = validationResult.value
                if (incomingPayment.received != null) {
                    return when (paymentPart) {
                        is HtlcPart -> {
                            // The invoice for this payment hash has already been paid. Two possible scenarios:
                            //
                            // 1) The htlc is a local replay emitted by a channel, but it has already been set as paid in the database.
                            //    This can happen when the wallet is stopped before the commands to fulfill htlcs have reached the channel. When the wallet restarts,
                            //    the channel will ask the handler to reprocess the htlc. So the htlc must be fulfilled again but the
                            //    payments database does not need to be updated.
                            //
                            // 2) This is a new htlc. This can happen when a sender pays an already paid invoice. In that case the
                            //    htlc can be safely rejected.
                            val htlcsMapInDb = incomingPayment.received.receivedWith.filterIsInstance<IncomingPayment.ReceivedWith.LightningPayment>().map { it.channelId to it.htlcId }
                            if (htlcsMapInDb.contains(paymentPart.htlc.channelId to paymentPart.htlc.id)) {
                                logger.info { "accepting local replay of htlc=${paymentPart.htlc.id} on channel=${paymentPart.htlc.channelId}" }
                                val action = WrappedChannelCommand(paymentPart.htlc.channelId, ChannelCommand.Htlc.Settlement.Fulfill(paymentPart.htlc.id, incomingPayment.preimage, true))
                                ProcessAddResult.Accepted(listOf(action), incomingPayment, incomingPayment.received)
                            } else {
                                logger.info { "rejecting htlc part for an invoice that has already been paid" }
                                val action = actionForFailureMessage(IncorrectOrUnknownPaymentDetails(paymentPart.totalAmount, currentBlockHeight.toLong()), paymentPart.htlc)
                                ProcessAddResult.Rejected(listOf(action), incomingPayment)
                            }
                        }
                        is OnTheFlyFundingPart -> {
                            logger.info { "ignoring on-the-fly funding part for an invoice that has already been paid" }
                            ProcessAddResult.Rejected(listOf(), incomingPayment)
                        }
                    }
                } else {
                    val payment = pending[paymentPart.paymentHash]?.add(paymentPart) ?: PendingPayment(paymentPart)
                    when {
                        paymentPart.totalAmount != payment.totalAmount -> {
                            // Bolt 04:
                            // - SHOULD fail the entire HTLC set if `total_msat` is not the same for all HTLCs in the set.
                            logger.warning { "invalid total_amount_msat: ${paymentPart.totalAmount}, expected ${payment.totalAmount}" }
                            val actions = payment.parts.filterIsInstance<HtlcPart>().map { actionForFailureMessage(IncorrectOrUnknownPaymentDetails(it.totalAmount, currentBlockHeight.toLong()), it.htlc) }
                            pending.remove(paymentPart.paymentHash)
                            return ProcessAddResult.Rejected(actions, incomingPayment)
                        }
                        payment.amountReceived < payment.totalAmount -> {
                            // Still waiting for more payments.
                            pending[paymentPart.paymentHash] = payment
                            return ProcessAddResult.Pending(incomingPayment, payment)
                        }
                        else -> {
                            val htlcParts = payment.parts.filterIsInstance<HtlcPart>()
                            val onTheFlyFundingParts = payment.parts.filterIsInstance<OnTheFlyFundingPart>()
                            val onTheFlyAmount = onTheFlyFundingParts.map { it.amount }.sum()
                            val rejected = when {
                                onTheFlyFundingParts.isNotEmpty() -> {
                                    val policy = nodeParams.liquidityPolicy.value
                                    val fees = when (policy) {
                                        is LiquidityPolicy.Disable -> 0.msat
                                        is LiquidityPolicy.Auto -> {
                                            val requestedAmount = policy.inboundLiquidityTarget ?: LiquidityPolicy.minInboundLiquidityTarget
                                            val leaseRate = LiquidityAds.chooseLeaseRate(requestedAmount, leaseRates)
                                            leaseRate.fees(currentFeerate, requestedAmount, requestedAmount).total.toMilliSatoshi()
                                        }
                                    }
                                    when {
                                        // We shouldn't initiate an on-the-fly funding if the remaining amount is too low to pay the fees.
                                        onTheFlyAmount < fees * 2 -> LiquidityEvents.Rejected(payment.amountReceived, fees, LiquidityEvents.Source.OffChainPayment, LiquidityEvents.Rejected.Reason.MissingOffChainAmountTooLow(onTheFlyAmount))
                                        // We consider the total amount received (not only the on-the-fly funding parts) to evaluate our relative fee policy.
                                        // A side effect is that if a large payment is only missing a small amount to be complete, we may still create a funding transaction for it.
                                        // This makes sense, as the user likely wants to receive this large payment, and will obtain inbound liquidity for future payments.
                                        else -> policy.maybeReject(payment.amountReceived, fees, LiquidityEvents.Source.OffChainPayment, logger)
                                    }
                                }
                                else -> null
                            }
                            when (rejected) {
                                is LiquidityEvents.Rejected -> {
                                    logger.info { "rejecting on-the-fly funding: reason=${rejected.reason}" }
                                    nodeParams._nodeEvents.emit(rejected)
                                    pending.remove(paymentPart.paymentHash)
                                    val actions = htlcParts.map { actionForFailureMessage(TemporaryNodeFailure, it.htlc) }
                                    ProcessAddResult.Rejected(actions, incomingPayment)
                                }
                                else -> {
                                    when (val paymentMetadata = paymentPart.finalPayload.paymentMetadata) {
                                        null -> logger.info { "payment received (${payment.amountReceived}) without payment metadata" }
                                        else -> logger.info { "payment received (${payment.amountReceived}) with payment metadata ($paymentMetadata)" }
                                    }
                                    // When the payment involves on-the-fly funding, we reveal the preimage to our peer and trust them to fund a channel accordingly.
                                    // We consider the payment received, but we can only fill the DB with the htlc parts.
                                    // The on-the-fly funding part will be updated once the corresponding channel or splice completes.
                                    val receivedWith = buildList {
                                        htlcParts.forEach { part -> add(IncomingPayment.ReceivedWith.LightningPayment(part.amount, part.htlc.channelId, part.htlc.id)) }
                                        if (onTheFlyFundingParts.isNotEmpty()) add(IncomingPayment.ReceivedWith.OnChainIncomingPayment.Pending(onTheFlyAmount))
                                    }
                                    val actions = buildList {
                                        // If an on-the-fly funding is required, we first ask our peer to initiate the funding process.
                                        // This ensures they get the preimage as soon as possible and can record how much they owe us in case we disconnect.
                                        if (onTheFlyFundingParts.isNotEmpty()) {
                                            add(OpenOrSplicePayment(onTheFlyAmount, incomingPayment.preimage))
                                        }
                                        htlcParts.forEach { part ->
                                            val cmd = ChannelCommand.Htlc.Settlement.Fulfill(part.htlc.id, incomingPayment.preimage, true)
                                            add(WrappedChannelCommand(part.htlc.channelId, cmd))
                                        }
                                    }
                                    // We can remove that payment from our in-memory state and store it in our DB.
                                    pending.remove(paymentPart.paymentHash)
                                    val received = IncomingPayment.Received(receivedWith = receivedWith)
                                    db.receivePayment(paymentPart.paymentHash, received.receivedWith)
                                    nodeParams._nodeEvents.emit(PaymentEvents.PaymentReceived(paymentPart.paymentHash, received.receivedWith))
                                    // Now that the payment is stored in our DB, we fulfill it.
                                    // If we disconnect before completing those actions, we will read from the DB and retry when reconnecting.
                                    ProcessAddResult.Accepted(actions, incomingPayment.copy(received = received), received)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private suspend fun validatePaymentPart(paymentPart: PaymentPart, currentBlockHeight: Int): Either<ProcessAddResult.Rejected, IncomingPayment> {
        val logger = MDCLogger(logger.logger, staticMdc = paymentPart.mdc())
        val incomingPayment = db.getIncomingPayment(paymentPart.paymentHash)
        return when {
            incomingPayment == null -> {
                logger.warning { "payment for which we don't have a preimage" }
                Either.Left(rejectPaymentPart(paymentPart, null, currentBlockHeight))
            }
            // Payments are rejected for expired invoices UNLESS invoice has already been paid
            // We must accept payments for already paid invoices, because it could be the channel replaying HTLCs that we already fulfilled
            incomingPayment.isExpired() && incomingPayment.received == null -> {
                logger.warning { "the invoice is expired" }
                Either.Left(rejectPaymentPart(paymentPart, incomingPayment, currentBlockHeight))
            }
            incomingPayment.origin !is IncomingPayment.Origin.Invoice -> {
                logger.warning { "unsupported payment type: ${incomingPayment.origin::class}" }
                Either.Left(rejectPaymentPart(paymentPart, incomingPayment, currentBlockHeight))
            }
            incomingPayment.origin.paymentRequest.paymentSecret != paymentPart.finalPayload.paymentSecret -> {
                // BOLT 04:
                // - if the payment_secret doesn't match the expected value for that payment_hash,
                //   or the payment_secret is required and is not present:
                //   - MUST fail the HTLC.
                //   - MUST return an incorrect_or_unknown_payment_details error.
                //
                //   Related: https://github.com/lightningnetwork/lightning-rfc/pull/671
                //
                // NB: We always include a paymentSecret, and mark the feature as mandatory.
                logger.warning { "payment with invalid paymentSecret (${paymentPart.finalPayload.paymentSecret})" }
                Either.Left(rejectPaymentPart(paymentPart, incomingPayment, currentBlockHeight))
            }
            incomingPayment.origin.paymentRequest.amount != null && paymentPart.totalAmount < incomingPayment.origin.paymentRequest.amount -> {
                // BOLT 04:
                // - if the amount paid is less than the amount expected:
                //   - MUST fail the HTLC.
                //   - MUST return an incorrect_or_unknown_payment_details error.
                logger.warning { "invalid amount (underpayment): ${paymentPart.totalAmount}, expected: ${incomingPayment.origin.paymentRequest.amount}" }
                Either.Left(rejectPaymentPart(paymentPart, incomingPayment, currentBlockHeight))
            }
            incomingPayment.origin.paymentRequest.amount != null && paymentPart.totalAmount > incomingPayment.origin.paymentRequest.amount * 2 -> {
                // BOLT 04:
                // - if the amount paid is more than twice the amount expected:
                //   - SHOULD fail the HTLC.
                //   - SHOULD return an incorrect_or_unknown_payment_details error.
                //
                //   Note: this allows the origin node to reduce information leakage by altering
                //   the amount while not allowing for accidental gross overpayment.
                logger.warning { "invalid amount (overpayment): ${paymentPart.totalAmount}, expected: ${incomingPayment.origin.paymentRequest.amount}" }
                Either.Left(rejectPaymentPart(paymentPart, incomingPayment, currentBlockHeight))
            }
            paymentPart is HtlcPart && paymentPart.htlc.cltvExpiry < minFinalCltvExpiry(incomingPayment.origin.paymentRequest, currentBlockHeight) -> {
                logger.warning { "payment with expiry too small: ${paymentPart.htlc.cltvExpiry}, min is ${minFinalCltvExpiry(incomingPayment.origin.paymentRequest, currentBlockHeight)}" }
                Either.Left(rejectPaymentPart(paymentPart, incomingPayment, currentBlockHeight))
            }
            else -> Either.Right(incomingPayment)
        }
    }

    fun checkPaymentsTimeout(currentTimestampSeconds: Long): List<PeerCommand> {
        val actions = mutableListOf<PeerCommand>()
        val keysToRemove = mutableSetOf<ByteVector32>()
        // BOLT 04:
        // - MUST fail all HTLCs in the HTLC set after some reasonable timeout.
        //   - SHOULD wait for at least 60 seconds after the initial HTLC.
        //   - SHOULD use mpp_timeout for the failure message.
        pending.forEach { (paymentHash, payment) ->
            val isExpired = currentTimestampSeconds > (payment.startedAtSeconds + nodeParams.mppAggregationWindow.inWholeSeconds)
            if (isExpired) {
                keysToRemove += paymentHash
                payment.parts.forEach { part ->
                    when (part) {
                        is HtlcPart -> actions += actionForFailureMessage(PaymentTimeout, part.htlc)
                        is OnTheFlyFundingPart -> {} // we don't need to notify our peer, they will automatically fail HTLCs after a delay
                    }
                }
            }
        }
        pending.minusAssign(keysToRemove)
        return actions
    }

    /**
     * Purge all expired unpaid normal payments with creation times in the given time range.
     *
     * @param fromCreatedAt from absolute time in milliseconds since UNIX epoch when the payment request was generated.
     * @param toCreatedAt to absolute time in milliseconds since UNIX epoch when the payment request was generated.
     * @return number of invoices purged
     */
    suspend fun purgeExpiredPayments(fromCreatedAt: Long = 0, toCreatedAt: Long = currentTimestampMillis()): Int {
        return db.listExpiredPayments(fromCreatedAt, toCreatedAt).count {
            logger.info { "purging unpaid expired payment for paymentHash=${it.paymentHash} from DB" }
            db.removeIncomingPayment(it.paymentHash)
        }
    }

    /**
     * If we are disconnected, we must forget pending payment parts.
     * On-the-fly funding requests will be forgotten by our peer, so we need to do the same otherwise we may accept outdated ones.
     * Offered HTLCs that haven't been resolved will be re-processed when we reconnect.
     */
    fun purgePendingPayments() {
        pending.forEach { (paymentHash, pending) -> logger.info { "purging pending incoming payments for paymentHash=$paymentHash: ${pending.parts.joinToString(", ") { it.toString() }}" } }
        pending.clear()
    }

    companion object {
        /** Convert an incoming htlc to a payment part abstraction. Payment parts are then summed together to reach the full payment amount. */
        private fun toPaymentPart(privateKey: PrivateKey, htlc: UpdateAddHtlc): Either<ProcessAddResult.Rejected, HtlcPart> {
            // NB: IncomingPacket.decrypt does additional validation on top of IncomingPacket.decryptOnion
            return when (val decrypted = IncomingPaymentPacket.decrypt(htlc, privateKey)) {
                is Either.Left -> { // Unable to decrypt onion
                    val failureMsg = decrypted.value
                    val action = actionForFailureMessage(failureMsg, htlc)
                    Either.Left(ProcessAddResult.Rejected(listOf(action), null))
                }
                is Either.Right -> Either.Right(HtlcPart(htlc, decrypted.value))
            }
        }

        /**
         * Convert a incoming on-the-fly funding request to a payment part abstraction.
         * This is very similar to the processing of a htlc, except that we only have a packet, to decrypt into a final payload.
         */
        private fun toPaymentPart(privateKey: PrivateKey, htlc: MaybeAddHtlc, logger: MDCLogger): Either<ProcessAddResult.Rejected, OnTheFlyFundingPart> {
            return when (val decrypted = IncomingPaymentPacket.decryptOnion(htlc.paymentHash, htlc.finalPacket, privateKey)) {
                is Either.Left -> {
                    // We simply ignore invalid maybe_add_htlc messages.
                    logger.warning { "could not decrypt maybe_add_htlc: ${decrypted.value.message}" }
                    Either.Left(ProcessAddResult.Rejected(listOf(), null))
                }
                is Either.Right -> Either.Right(OnTheFlyFundingPart(htlc, decrypted.value))
            }
        }

        private fun rejectPaymentPart(paymentPart: PaymentPart, incomingPayment: IncomingPayment?, currentBlockHeight: Int): ProcessAddResult.Rejected {
            val failureMsg = IncorrectOrUnknownPaymentDetails(paymentPart.totalAmount, currentBlockHeight.toLong())
            return when (paymentPart) {
                is HtlcPart -> ProcessAddResult.Rejected(listOf(actionForFailureMessage(failureMsg, paymentPart.htlc)), incomingPayment)
                is OnTheFlyFundingPart -> ProcessAddResult.Rejected(listOf(), incomingPayment)
            }
        }

        private fun actionForFailureMessage(msg: FailureMessage, htlc: UpdateAddHtlc, commit: Boolean = true): WrappedChannelCommand {
            val cmd: ChannelCommand.Htlc.Settlement = when (msg) {
                is BadOnion -> ChannelCommand.Htlc.Settlement.FailMalformed(htlc.id, msg.onionHash, msg.code, commit)
                else -> ChannelCommand.Htlc.Settlement.Fail(htlc.id, ChannelCommand.Htlc.Settlement.Fail.Reason.Failure(msg), commit)
            }
            return WrappedChannelCommand(htlc.channelId, cmd)
        }

        private fun minFinalCltvExpiry(paymentRequest: Bolt11Invoice, currentBlockHeight: Int): CltvExpiry {
            val minFinalCltvExpiryDelta = paymentRequest.minFinalExpiryDelta ?: Bolt11Invoice.DEFAULT_MIN_FINAL_EXPIRY_DELTA
            return minFinalCltvExpiryDelta.toCltvExpiry(currentBlockHeight.toLong())
        }
    }

}