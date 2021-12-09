package fr.acinq.lightning.payment

import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.Crypto
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.lightning.CltvExpiry
import fr.acinq.lightning.Lightning.randomBytes32
import fr.acinq.lightning.MilliSatoshi
import fr.acinq.lightning.NodeParams
import fr.acinq.lightning.WalletParams
import fr.acinq.lightning.channel.*
import fr.acinq.lightning.db.IncomingPayment
import fr.acinq.lightning.db.IncomingPaymentsDb
import fr.acinq.lightning.io.PayToOpenResponseEvent
import fr.acinq.lightning.io.PeerEvent
import fr.acinq.lightning.io.WrappedChannelEvent
import fr.acinq.lightning.utils.*
import fr.acinq.lightning.wire.*

sealed class PaymentPart {
    abstract val amount: MilliSatoshi
    abstract val totalAmount: MilliSatoshi
    abstract val paymentHash: ByteVector32
    abstract val finalPayload: FinalPayload
}

data class HtlcPart(val htlc: UpdateAddHtlc, override val finalPayload: FinalPayload) : PaymentPart() {
    override val amount: MilliSatoshi = htlc.amountMsat
    override val totalAmount: MilliSatoshi = finalPayload.totalAmount
    override val paymentHash: ByteVector32 = htlc.paymentHash
}

data class PayToOpenPart(val payToOpenRequest: PayToOpenRequest, override val finalPayload: FinalPayload) : PaymentPart() {
    override val amount: MilliSatoshi = payToOpenRequest.amountMsat
    override val totalAmount: MilliSatoshi = finalPayload.totalAmount
    override val paymentHash: ByteVector32 = payToOpenRequest.paymentHash
}

class IncomingPaymentHandler(val nodeParams: NodeParams, val walletParams: WalletParams, val db: IncomingPaymentsDb) {

    sealed class ProcessAddResult {
        abstract val actions: List<PeerEvent>

        data class Accepted(override val actions: List<PeerEvent>, val incomingPayment: IncomingPayment, val received: IncomingPayment.Received) : ProcessAddResult()
        data class Rejected(override val actions: List<PeerEvent>, val incomingPayment: IncomingPayment?) : ProcessAddResult()
        data class Pending(val incomingPayment: IncomingPayment) : ProcessAddResult() {
            override val actions: List<PeerEvent> = listOf()
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
    private data class PendingPayment(val parts: Set<PaymentPart>, val totalAmount: MilliSatoshi, val startedAtSeconds: Long) {
        constructor(firstPart: PaymentPart) : this(setOf(firstPart), firstPart.totalAmount, currentTimestampSeconds())

        val amountReceived: MilliSatoshi = parts.map { it.amount }.sum()

        fun add(part: PaymentPart): PendingPayment = copy(parts = parts + part)
    }

    private val logger by lightningLogger()
    private val pending = mutableMapOf<ByteVector32, PendingPayment>()
    private val privateKey = nodeParams.nodePrivateKey

    suspend fun createInvoice(
        paymentPreimage: ByteVector32,
        amount: MilliSatoshi?,
        description: String,
        extraHops: List<List<PaymentRequest.TaggedField.ExtraHop>>,
        expirySeconds: Long? = null,
        timestampSeconds: Long = currentTimestampSeconds()
    ): PaymentRequest {
        val paymentHash = Crypto.sha256(paymentPreimage).toByteVector32()
        logger.debug { "h:$paymentHash using routing hints $extraHops" }
        val pr = PaymentRequest.create(
            nodeParams.chainHash,
            amount,
            paymentHash,
            nodeParams.nodePrivateKey,
            description,
            PaymentRequest.DEFAULT_MIN_FINAL_EXPIRY_DELTA,
            nodeParams.features,
            randomBytes32(),
            expirySeconds,
            extraHops,
            timestampSeconds
        )
        logger.info { "h:$paymentHash generated payment request ${pr.write()}" }
        db.addIncomingPayment(paymentPreimage, IncomingPayment.Origin.Invoice(pr))
        return pr
    }

    /**
     * Save the "received-with" details of an incoming amount.
     *
     * - for a pay-to-open origin, we only save the id of the channel that was created for this payment.
     * - for a swap-in origin, a new incoming payment must be created. We use the channel id to generate the payment's preimage.
     * - for unknown origin, the amount is handled as a swap-in coming from an unknown address.
     */
    suspend fun process(channelId: ByteVector32, action: ChannelAction.Storage.StoreIncomingAmount) {
        when (action.origin) {
            null -> {
                // TODO: hacky, needs clean-up
                logger.warning { "incoming amount with empty origin, we store only minimal information" }
                val fakePreimage = channelId.sha256()
                db.addAndReceivePayment(
                    preimage = fakePreimage,
                    origin = IncomingPayment.Origin.SwapIn(address = ""),
                    receivedWith = setOf(IncomingPayment.ReceivedWith.NewChannel(amount = action.amount, fees = 0.msat, channelId = channelId))
                )
            }
            is ChannelOrigin.PayToOpenOrigin -> {
                // In that case, the pay-to-open payment parts have already been handled in the main `processPaymentPart` handler. We just need
                // to update the channel id of the pay-to-open received-with parts with type new-channel.
                db.updateNewChannelReceivedWithChannelId(action.origin.paymentHash, channelId)
            }
            is ChannelOrigin.SwapInOrigin -> {
                // swap-ins are push payments made with an on-chain tx, there is no related preimage so we make up one so it fits in our model
                val fakePreimage = channelId.sha256()
                db.addAndReceivePayment(
                    preimage = fakePreimage,
                    origin = IncomingPayment.Origin.SwapIn(address = action.origin.bitcoinAddress),
                    receivedWith = setOf(IncomingPayment.ReceivedWith.NewChannel(amount = action.amount, fees = action.origin.fee.toMilliSatoshi(), channelId = channelId))
                )
            }
        }
    }

    /**
     * Process an incoming htlc.
     * Before calling this, the htlc must be committed and ack-ed by both sides.
     *
     * @return A result that indicates whether or not the packet was
     * accepted, rejected, or still pending (as the case may be for multipart payments).
     * Also includes the list of actions to be queued.
     */
    suspend fun process(htlc: UpdateAddHtlc, currentBlockHeight: Int): ProcessAddResult {
        // Security note:
        // There are several checks we could perform before decrypting the onion.
        // However an error message here would differ from an error message below,
        // as we don't know the `onion.totalAmount` yet.
        // So to prevent any kind of information leakage, we always peel the onion first.
        logger.info { "h:${htlc.paymentHash} received htlc amount=${htlc.amountMsat} expiry=${htlc.cltvExpiry}" }
        return when (val res = toPaymentPart(privateKey, htlc)) {
            is Either.Left -> res.value
            is Either.Right -> processPaymentPart(res.value, currentBlockHeight)
        }
    }

    /**
     * Process an incoming pay-to-open request.
     * This is very similar to the processing of an htlc.
     */
    suspend fun process(payToOpenRequest: PayToOpenRequest, currentBlockHeight: Int): ProcessAddResult {
        logger.info { "h:${payToOpenRequest.paymentHash} received pay-to-open amount=${payToOpenRequest.amountMsat} funding=${payToOpenRequest.fundingSatoshis} fees=${payToOpenRequest.payToOpenFeeSatoshis}" }
        return when (val res = toPaymentPart(privateKey, payToOpenRequest)) {
            is Either.Left -> res.value
            is Either.Right -> processPaymentPart(res.value, currentBlockHeight)
        }
    }

    /** Main payment processing, that handles payment parts. */
    private suspend fun processPaymentPart(paymentPart: PaymentPart, currentBlockHeight: Int): ProcessAddResult {
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
                                logger.info { "h:${paymentPart.paymentHash} accepting local replay of htlc=${paymentPart.htlc.id} on channel=${paymentPart.htlc.channelId}" }
                                val action = WrappedChannelEvent(paymentPart.htlc.channelId, ChannelEvent.ExecuteCommand(CMD_FULFILL_HTLC(paymentPart.htlc.id, incomingPayment.preimage, true)))
                                ProcessAddResult.Accepted(listOf(action), incomingPayment, incomingPayment.received)
                            } else {
                                logger.info { "h:${paymentPart.paymentHash} rejecting htlc part for an invoice that has already been paid" }
                                val action = actionForFailureMessage(IncorrectOrUnknownPaymentDetails(paymentPart.totalAmount, currentBlockHeight.toLong()), paymentPart.htlc)
                                ProcessAddResult.Rejected(listOf(action), incomingPayment)
                            }
                        }
                        is PayToOpenPart -> {
                            logger.info { "h:${paymentPart.paymentHash} rejecting pay-to-open part for an invoice that has already been paid" }
                            val action = actionForPayToOpenFailure(privateKey, IncorrectOrUnknownPaymentDetails(paymentPart.totalAmount, currentBlockHeight.toLong()), paymentPart.payToOpenRequest)
                            ProcessAddResult.Rejected(listOf(action), incomingPayment)
                        }
                    }
                } else {
                    val payment = pending[paymentPart.paymentHash]?.add(paymentPart) ?: PendingPayment(paymentPart)
                    val payToOpenMinAmount = payment.parts.filterIsInstance<PayToOpenPart>().map { it.payToOpenRequest.payToOpenMinAmountMsat }.firstOrNull()
                    val payToOpenAmount = payment.parts.filterIsInstance<PayToOpenPart>().map { it.payToOpenRequest.amountMsat }.sum()
                    when {
                        paymentPart.totalAmount != payment.totalAmount -> {
                            // Bolt 04:
                            // - SHOULD fail the entire HTLC set if `total_msat` is not the same for all HTLCs in the set.
                            logger.warning { "h:${paymentPart.paymentHash} invalid total_amount_msat: $paymentPart.totalAmount, expected ${payment.totalAmount}" }
                            val actions = payment.parts.map { part ->
                                val failureMsg = IncorrectOrUnknownPaymentDetails(part.totalAmount, currentBlockHeight.toLong())
                                when (part) {
                                    is HtlcPart -> actionForFailureMessage(failureMsg, part.htlc)
                                    is PayToOpenPart -> actionForPayToOpenFailure(privateKey, failureMsg, part.payToOpenRequest) // NB: this will fail all parts, we could only return one
                                }
                            }
                            pending.remove(paymentPart.paymentHash)
                            return ProcessAddResult.Rejected(actions, incomingPayment)
                        }
                        payment.amountReceived < payment.totalAmount -> {
                            // Still waiting for more payments.
                            pending[paymentPart.paymentHash] = payment
                            return ProcessAddResult.Pending(incomingPayment)
                        }
                        payToOpenMinAmount != null && payToOpenAmount < payToOpenMinAmount -> {
                            // Because of the cost of opening a new channel, there is a minimum amount for incoming payments to trigger
                            // a pay-to-open. Given that the total amount of a payment is included in each payment part, we could have
                            // rejected pay-to-open parts as they arrived, but it would have caused two issues:
                            // - in case there is a mix of htlc parts and pay-to-open parts, the htlc parts would have been accepted and we
                            // would have waited for a timeout before failing them (since the payment would never complete)
                            // - if we rejected each pay-to-open part individually, we wouldn't have been able to emit a single event
                            //   regarding the failed pay-to-open
                            // That is why, instead, we wait for all parts to arrive. Then, if there is at least one pay-to-open part, and if
                            // the total received amount is less than the minimum amount required for a pay-to-open, we fail the payment.
                            logger.warning { "h:${paymentPart.paymentHash} amount too low for a pay-to-open: $payToOpenAmount, min is $payToOpenMinAmount" }
                            val actions = payment.parts.map { part ->
                                val failureMsg = IncorrectOrUnknownPaymentDetails(part.totalAmount, currentBlockHeight.toLong())
                                when (part) {
                                    is HtlcPart -> actionForFailureMessage(failureMsg, part.htlc)
                                    is PayToOpenPart -> actionForPayToOpenFailure(privateKey, failureMsg, part.payToOpenRequest) // NB: this will fail all parts, we could only return one
                                }
                            }
                            pending.remove(paymentPart.paymentHash)
                            return ProcessAddResult.Rejected(actions, incomingPayment)
                        }
                        else -> {
                            // We have received all the payment parts.
                            logger.info { "h:${paymentPart.paymentHash} payment received (${payment.amountReceived})" }
                            val (actions, receivedWith) = payment.parts.map { part ->
                                when (part) {
                                    is HtlcPart -> {
                                        val cmd = CMD_FULFILL_HTLC(part.htlc.id, incomingPayment.preimage, true)
                                        val channelEvent = ChannelEvent.ExecuteCommand(cmd)
                                        WrappedChannelEvent(
                                            channelId = part.htlc.channelId,
                                            channelEvent = channelEvent
                                        ) to IncomingPayment.ReceivedWith.LightningPayment(
                                            amount = part.amount,
                                            htlcId = part.htlc.id,
                                            channelId = part.htlc.channelId
                                        )
                                    }
                                    is PayToOpenPart -> PayToOpenResponseEvent(
                                        PayToOpenResponse(
                                            chainHash = part.payToOpenRequest.chainHash,
                                            paymentHash = paymentPart.paymentHash,
                                            result = PayToOpenResponse.Result.Success(incomingPayment.preimage)
                                        )
                                    ) to IncomingPayment.ReceivedWith.NewChannel(
                                        // The part's amount is the full amount, including the fee. The fee must be subtracted.
                                        amount = part.amount - part.payToOpenRequest.payToOpenFeeSatoshis.toMilliSatoshi(),
                                        fees = part.payToOpenRequest.payToOpenFeeSatoshis.toMilliSatoshi(),
                                        // At that point we do not know the channel's id. It will be set later on.
                                        channelId = null
                                    )
                                }
                            }.unzip()
                            pending.remove(paymentPart.paymentHash)

                            val received = IncomingPayment.Received(receivedWith = receivedWith.toSet())

                            db.receivePayment(paymentPart.paymentHash, received.receivedWith)
                            return ProcessAddResult.Accepted(actions, incomingPayment.copy(received = received), received)
                        }
                    }
                }
            }
        }
    }

    private suspend fun validatePaymentPart(paymentPart: PaymentPart, currentBlockHeight: Int): Either<ProcessAddResult.Rejected, IncomingPayment> {
        val incomingPayment = db.getIncomingPayment(paymentPart.paymentHash)
        return when {
            incomingPayment == null -> {
                logger.warning { "h:${paymentPart.paymentHash} payment for which we don't have a preimage" }
                Either.Left(rejectPaymentPart(privateKey, paymentPart, null, currentBlockHeight))
            }
            // Payments are rejected for expired invoices UNLESS invoice has already been paid
            // We must accept payments for already paid invoices, because it could be the channel replaying HTLCs that we already fulfilled
            incomingPayment.isExpired() && incomingPayment.received == null -> {
                logger.warning { "h:${paymentPart.paymentHash} the invoice is expired" }
                Either.Left(rejectPaymentPart(privateKey, paymentPart, incomingPayment, currentBlockHeight))
            }
            incomingPayment.origin !is IncomingPayment.Origin.Invoice -> {
                logger.warning { "h:${paymentPart.paymentHash} unsupported payment type: ${incomingPayment.origin::class}" }
                Either.Left(rejectPaymentPart(privateKey, paymentPart, incomingPayment, currentBlockHeight))
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
                logger.warning { "h:${paymentPart.paymentHash} payment with invalid paymentSecret (${paymentPart.finalPayload.paymentSecret})" }
                Either.Left(rejectPaymentPart(privateKey, paymentPart, incomingPayment, currentBlockHeight))
            }
            incomingPayment.origin.paymentRequest.amount != null && paymentPart.totalAmount < incomingPayment.origin.paymentRequest.amount -> {
                // BOLT 04:
                // - if the amount paid is less than the amount expected:
                //   - MUST fail the HTLC.
                //   - MUST return an incorrect_or_unknown_payment_details error.
                logger.warning { "h:${paymentPart.paymentHash} invalid amount (underpayment): ${paymentPart.totalAmount}, expected: ${incomingPayment.origin.paymentRequest.amount}" }
                Either.Left(rejectPaymentPart(privateKey, paymentPart, incomingPayment, currentBlockHeight))
            }
            incomingPayment.origin.paymentRequest.amount != null && paymentPart.totalAmount > incomingPayment.origin.paymentRequest.amount * 2 -> {
                // BOLT 04:
                // - if the amount paid is more than twice the amount expected:
                //   - SHOULD fail the HTLC.
                //   - SHOULD return an incorrect_or_unknown_payment_details error.
                //
                //   Note: this allows the origin node to reduce information leakage by altering
                //   the amount while not allowing for accidental gross overpayment.
                logger.warning { "h:${paymentPart.paymentHash} invalid amount (overpayment): ${paymentPart.totalAmount}, expected: ${incomingPayment.origin.paymentRequest.amount}" }
                Either.Left(rejectPaymentPart(privateKey, paymentPart, incomingPayment, currentBlockHeight))
            }
            paymentPart is HtlcPart && paymentPart.htlc.cltvExpiry < minFinalCltvExpiry(incomingPayment.origin.paymentRequest, currentBlockHeight) -> {
                logger.warning { "h:${paymentPart.paymentHash} payment with expiry too small: ${paymentPart.htlc.cltvExpiry}, min is ${minFinalCltvExpiry(incomingPayment.origin.paymentRequest, currentBlockHeight)}" }
                Either.Left(rejectPaymentPart(privateKey, paymentPart, incomingPayment, currentBlockHeight))
            }
            paymentPart is PayToOpenPart && paymentPart.payToOpenRequest.fundingSatoshis < nodeParams.minFundingSatoshis -> {
                logger.warning { "h:${paymentPart.payToOpenRequest.paymentHash} received invalid funding amount for a pay-to-open: ${paymentPart.payToOpenRequest.fundingSatoshis}, min is ${nodeParams.minFundingSatoshis}" }
                Either.Left(rejectPaymentPart(privateKey, paymentPart, incomingPayment, currentBlockHeight))
            }
            else -> Either.Right(incomingPayment)
        }
    }

    fun checkPaymentsTimeout(currentTimestampSeconds: Long): List<PeerEvent> {
        val actions = mutableListOf<PeerEvent>()
        val keysToRemove = mutableSetOf<ByteVector32>()

        // BOLT 04:
        // - MUST fail all HTLCs in the HTLC set after some reasonable timeout.
        //   - SHOULD wait for at least 60 seconds after the initial HTLC.
        //   - SHOULD use mpp_timeout for the failure message.
        pending.forEach { (paymentHash, payment) ->
            val isExpired = currentTimestampSeconds > (payment.startedAtSeconds + nodeParams.multiPartPaymentExpirySeconds)
            if (isExpired) {
                keysToRemove += paymentHash
                payment.parts.forEach { part ->
                    when (part) {
                        is HtlcPart -> actions += actionForFailureMessage(PaymentTimeout, part.htlc)
                        is PayToOpenPart -> actions += actionForPayToOpenFailure(privateKey, PaymentTimeout, part.payToOpenRequest)
                    }
                }
            }
        }

        pending.minusAssign(keysToRemove)
        return actions
    }

    companion object {
        /** Convert an incoming htlc to a payment part abstraction. Payment parts are then summed together to reach the full payment amount. */
        private fun toPaymentPart(privateKey: PrivateKey, htlc: UpdateAddHtlc): Either<ProcessAddResult.Rejected, HtlcPart> {
            // NB: IncomingPacket.decrypt does additional validation on top of IncomingPacket.decryptOnion
            return when (val decrypted = IncomingPacket.decrypt(htlc, privateKey)) {
                is Either.Left -> { // Unable to decrypt onion
                    val failureMsg = decrypted.value
                    val action = actionForFailureMessage(failureMsg, htlc)
                    Either.Left(ProcessAddResult.Rejected(listOf(action), null))
                }
                is Either.Right -> Either.Right(HtlcPart(htlc, decrypted.value))
            }
        }

        /**
         * Convert a incoming pay-to-open request to a payment part abstraction.
         * This is very similar to the processing of a htlc, except that we only have a packet, to decrypt into a final payload.
         */
        private fun toPaymentPart(privateKey: PrivateKey, payToOpenRequest: PayToOpenRequest): Either<ProcessAddResult.Rejected, PayToOpenPart> {
            return when (val decrypted = IncomingPacket.decryptOnion(payToOpenRequest.paymentHash, payToOpenRequest.finalPacket, payToOpenRequest.finalPacket.payload.size(), privateKey)) {
                is Either.Left -> {
                    val failureMsg = decrypted.value
                    val action = actionForPayToOpenFailure(privateKey, failureMsg, payToOpenRequest)
                    Either.Left(ProcessAddResult.Rejected(listOf(action), null))
                }
                is Either.Right -> Either.Right(PayToOpenPart(payToOpenRequest, decrypted.value))
            }
        }

        private fun rejectPaymentPart(privateKey: PrivateKey, paymentPart: PaymentPart, incomingPayment: IncomingPayment?, currentBlockHeight: Int): ProcessAddResult.Rejected {
            val failureMsg = IncorrectOrUnknownPaymentDetails(paymentPart.totalAmount, currentBlockHeight.toLong())
            val rejectedAction = when (paymentPart) {
                is HtlcPart -> actionForFailureMessage(failureMsg, paymentPart.htlc)
                is PayToOpenPart -> actionForPayToOpenFailure(privateKey, failureMsg, paymentPart.payToOpenRequest)
            }
            return ProcessAddResult.Rejected(listOf(rejectedAction), incomingPayment)
        }

        private fun actionForFailureMessage(msg: FailureMessage, htlc: UpdateAddHtlc, commit: Boolean = true): WrappedChannelEvent {
            val cmd: Command = when (msg) {
                is BadOnion -> CMD_FAIL_MALFORMED_HTLC(htlc.id, msg.onionHash, msg.code, commit)
                else -> CMD_FAIL_HTLC(htlc.id, CMD_FAIL_HTLC.Reason.Failure(msg), commit)
            }
            val channelEvent = ChannelEvent.ExecuteCommand(cmd)
            return WrappedChannelEvent(htlc.channelId, channelEvent)
        }

        private fun actionForPayToOpenFailure(privateKey: PrivateKey, failure: FailureMessage, payToOpenRequest: PayToOpenRequest): PayToOpenResponseEvent {
            val reason = CMD_FAIL_HTLC.Reason.Failure(failure)
            val encryptedReason = when (val result = OutgoingPacket.buildHtlcFailure(privateKey, payToOpenRequest.paymentHash, payToOpenRequest.finalPacket, reason)) {
                is Either.Right -> result.value
                is Either.Left -> null
            }
            return PayToOpenResponseEvent(PayToOpenResponse(payToOpenRequest.chainHash, payToOpenRequest.paymentHash, PayToOpenResponse.Result.Failure(encryptedReason)))
        }

        private fun minFinalCltvExpiry(paymentRequest: PaymentRequest, currentBlockHeight: Int): CltvExpiry {
            val minFinalCltvExpiryDelta = paymentRequest.minFinalExpiryDelta ?: PaymentRequest.DEFAULT_MIN_FINAL_EXPIRY_DELTA
            return minFinalCltvExpiryDelta.toCltvExpiry(currentBlockHeight.toLong())
        }
    }

}
