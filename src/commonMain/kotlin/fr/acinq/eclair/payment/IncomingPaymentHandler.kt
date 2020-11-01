package fr.acinq.eclair.payment

import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.Crypto
import fr.acinq.eclair.*
import fr.acinq.eclair.channel.*
import fr.acinq.eclair.io.PayToOpenResult
import fr.acinq.eclair.io.PeerEvent
import fr.acinq.eclair.io.WrappedChannelEvent
import fr.acinq.eclair.utils.*
import fr.acinq.eclair.wire.*

data class IncomingPayment(
    val paymentRequest: PaymentRequest,
    val paymentPreimage: ByteVector32
)

sealed class PaymentPart {
    abstract val amount: MilliSatoshi
    abstract val totalAmount: MilliSatoshi
    abstract val paymentHash: ByteVector32
    abstract val finalPayload: FinalPayload
}

data class HtlcPart( // htlc + decrypted onion
    val htlc: UpdateAddHtlc,
    override val finalPayload: FinalPayload
) : PaymentPart() {
    override val amount: MilliSatoshi = htlc.amountMsat
    override val totalAmount: MilliSatoshi = finalPayload.totalAmount
    override val paymentHash: ByteVector32 = htlc.paymentHash
}

data class PayToOpenPart(
    val payToOpenRequest: PayToOpenRequest,
    override val finalPayload: FinalPayload
) : PaymentPart() {
    override val amount: MilliSatoshi = payToOpenRequest.amountMsat
    override val totalAmount: MilliSatoshi = finalPayload.totalAmount
    override val paymentHash: ByteVector32 = payToOpenRequest.paymentHash
}

class IncomingPaymentHandler(
    val nodeParams: NodeParams
) {

    enum class Status {
        ACCEPTED,
        REJECTED,
        PENDING // neither accepted or rejected yet
    }

    // pending incoming payments, indexed by payment hash
    private val pendingIncomingPayments: HashMap<ByteVector32, IncomingPayment> = HashMap()

    data class ProcessAddResult(val status: Status, val actions: List<PeerEvent>, val incomingPayment: IncomingPayment?)

    private class FinalPacketSet { // htlc set + metadata
        val parts: Set<PaymentPart>
        val totalAmount: MilliSatoshi // all parts must have matching `total_msat`
        val mppStartedAt: Long // in seconds

        constructor(paymentPart: PaymentPart, totalAmount: MilliSatoshi) {
            this.parts = setOf(paymentPart)
            this.totalAmount = totalAmount
            this.mppStartedAt = currentTimestampSeconds()
        }

        private constructor(parts: Set<PaymentPart>, totalAmount: MilliSatoshi, mppStartedAt: Long) {
            this.parts = parts
            this.totalAmount = totalAmount
            this.mppStartedAt = mppStartedAt
        }

        fun add(paymentPart: PaymentPart): FinalPacketSet {
            return FinalPacketSet(
                parts = this.parts + paymentPart,
                totalAmount = this.totalAmount,
                mppStartedAt = this.mppStartedAt
            )
        }
    }

    private val logger = newEclairLogger()
    private val pending = mutableMapOf<ByteVector32, FinalPacketSet>()
    private val privateKey get() = nodeParams.nodePrivateKey

    fun createInvoice(
        paymentPreimage: ByteVector32,
        amount: MilliSatoshi?,
        description: String,
        expirySeconds: Long? = null,
        timestamp: Long = currentTimestampSeconds()
    ): PaymentRequest {
        val paymentHash = Crypto.sha256(paymentPreimage).toByteVector32()
        val invoiceFeatures = mutableSetOf(
            ActivatedFeature(Feature.VariableLengthOnion, FeatureSupport.Optional),
            ActivatedFeature(Feature.PaymentSecret, FeatureSupport.Mandatory)
        )
        if (nodeParams.features.hasFeature(Feature.BasicMultiPartPayment)) {
            invoiceFeatures.add(ActivatedFeature(Feature.BasicMultiPartPayment, FeatureSupport.Optional))
        }

        // we add one extra hop which uses a virtual channel with a "peer id"
        val extraHops = listOf(
            listOf(
                PaymentRequest.TaggedField.ExtraHop(
                    nodeId = nodeParams.trampolineNode.id,
                    shortChannelId = ShortChannelId.peerId(nodeParams.nodeId),
                    feeBase = MilliSatoshi(1000),
                    feeProportionalMillionths = 100,
                    cltvExpiryDelta = CltvExpiryDelta(144)
                )
            )
        )
        logger.info { "using routing hints $extraHops" }
        val pr = PaymentRequest.create(
            chainHash = nodeParams.chainHash,
            amount = amount,
            paymentHash = paymentHash,
            privateKey = nodeParams.nodePrivateKey,
            description = description,
            minFinalCltvExpiryDelta = PaymentRequest.DEFAULT_MIN_FINAL_EXPIRY_DELTA,
            features = Features(invoiceFeatures),
            expirySeconds = expirySeconds,
            timestamp = timestamp,
            extraHops = extraHops
        )
        logger.info { "payment request ${pr.write()}" }
        pendingIncomingPayments[paymentHash] = IncomingPayment(pr, paymentPreimage)
        return pr
    }

    /**
     * Converts an incoming htlc to a payment part abstraction. Payment parts are then
     * summed together to reach the full payment amount.
     */
    private fun toPaymentPart(htlc: UpdateAddHtlc): Either<ProcessAddResult, HtlcPart> {
        // NB: IncomingPacket.decrypt does additional validation on top of IncomingPacket.decryptOnion
        return when (val decrypted = IncomingPacket.decrypt(htlc, this.privateKey)) {
            is Either.Left -> { // Unable to decrypt onion
                val failureMessage = decrypted.value
                val action = actionForFailureMessage(failureMessage, htlc)
                Either.Left(ProcessAddResult(status = Status.REJECTED, actions = listOf(action), incomingPayment = null))
            }
            is Either.Right -> Either.Right(HtlcPart(htlc, decrypted.value))
        }
    }

    /**
     * Converts a incoming pay-to-open request to a payment part abstraction.
     * This is very similar to the processing of a htlc, except that we only
     * have a packet, to decrypt into a final payload.
     */
    private fun toPaymentPart(payToOpenRequest: PayToOpenRequest): Either<ProcessAddResult, PayToOpenPart> {
        return when (val decrypted = IncomingPacket.decryptOnion(payToOpenRequest.paymentHash, payToOpenRequest.finalPacket, payToOpenRequest.finalPacket.payload.size(), privateKey)) {
            is Either.Left -> {
                val action = actionForPayToOpenFailure(payToOpenRequest)
                Either.Left(ProcessAddResult(status = Status.REJECTED, actions = listOf(action), incomingPayment = null))
            }
            is Either.Right -> Either.Right(PayToOpenPart(payToOpenRequest, decrypted.value))
        }
    }

    /**
     * Processes an incoming htlc.
     * Before calling this, the htlc must be committed and acked by both sides.
     *
     * @return A result that indicates whether or not the packet was
     * accepted, rejected, or still pending (as the case may be for multipart payments).
     * Also includes the list of actions to be queued.
     */
    fun process(
        htlc: UpdateAddHtlc,
        currentBlockHeight: Int
    ): ProcessAddResult {
        // Security note:
        // There are several checks we could perform before decrypting the onion.
        // However an error message here would differ from an error message below,
        // as we don't know the `onion.totalAmount` yet.
        // So to prevent any kind of information leakage, we always peel the onion first.
        return when (val res = toPaymentPart(htlc)) {
            is Either.Left -> res.value
            is Either.Right -> processPaymentPart(res.value, currentBlockHeight)
        }
    }

    /**
     * Processed an incoming pay-to-open request.
     * This is very similar to the processing of an htlc.
     */
    fun process(
        payToOpenRequest: PayToOpenRequest,
        currentBlockHeight: Int
    ): ProcessAddResult {
        return when (val res = toPaymentPart(payToOpenRequest)) {
            is Either.Left -> res.value
            is Either.Right -> processPaymentPart(res.value, currentBlockHeight)
        }
    }

    /**
     * Main payment processing, that handle payment parts.
     */
    private fun processPaymentPart(
        paymentPart: PaymentPart,
        currentBlockHeight: Int
    ): ProcessAddResult {

        val incomingPayment: IncomingPayment? = pendingIncomingPayments[paymentPart.paymentHash]

        logger.info { "processing payload=${paymentPart.finalPayload} invoice=${incomingPayment?.paymentRequest}"}

        // depending on the type of the payment part, the default rejection result changes
        val rejectedAction = when (paymentPart) {
            is HtlcPart -> {
                val failureMsg = IncorrectOrUnknownPaymentDetails(paymentPart.totalAmount, currentBlockHeight.toLong())
                actionForFailureMessage(failureMsg, paymentPart.htlc)
            }
            is PayToOpenPart -> actionForPayToOpenFailure(paymentPart.payToOpenRequest)
        }
        val rejectedResult = ProcessAddResult(status = Status.REJECTED, actions = listOf(rejectedAction), incomingPayment = incomingPayment)

        return when {

            incomingPayment == null -> {
                logger.warning { "received payment for which we don't have a preimage" }
                rejectedResult
            }

            incomingPayment.paymentRequest.isExpired() -> {
                logger.warning { "received payment for expired invoice" }
                rejectedResult
            }

            incomingPayment.paymentRequest.paymentSecret != paymentPart.finalPayload.paymentSecret -> {
                // BOLT 04:
                // - if the payment_secret doesn't match the expected value for that payment_hash,
                //   or the payment_secret is required and is not present:
                //   - MUST fail the HTLC.
                //   - MUST return an incorrect_or_unknown_payment_details error.
                //
                //   Related: https://github.com/lightningnetwork/lightning-rfc/pull/671
                //
                // NB: We always include a paymentSecret, and mark the feature as mandatory.
                logger.warning { "received payment with invalid paymentSecret invoice=${incomingPayment.paymentRequest.paymentSecret} payload=${paymentPart.finalPayload.paymentSecret}" }
                rejectedResult
            }

            incomingPayment.paymentRequest.amount != null && paymentPart.totalAmount < incomingPayment.paymentRequest.amount -> {
                // BOLT 04:
                // - if the amount paid is less than the amount expected:
                //   - MUST fail the HTLC.
                //   - MUST return an incorrect_or_unknown_payment_details error.
                logger.warning { "received invalid amount (underpayment): ${paymentPart.totalAmount}, expected: ${incomingPayment.paymentRequest.amount}" }
                rejectedResult
            }

            incomingPayment.paymentRequest.amount != null && paymentPart.totalAmount > incomingPayment.paymentRequest.amount * 2 -> {
                // BOLT 04:
                // - if the amount paid is more than twice the amount expected:
                //   - SHOULD fail the HTLC.
                //   - SHOULD return an incorrect_or_unknown_payment_details error.
                //
                //   Note: this allows the origin node to reduce information leakage by altering
                //   the amount while not allowing for accidental gross overpayment.
                logger.warning { "received invalid amount (overpayment): ${paymentPart.totalAmount}, expected: ${incomingPayment.paymentRequest.amount}" }
                rejectedResult
            }

            paymentPart is HtlcPart && paymentPart.htlc.cltvExpiry < minFinalCltvExpiry(incomingPayment, currentBlockHeight) -> {
                // this check is only valid for htlc parts
                logger.warning { "received payment with expiry too small: received(${paymentPart.htlc.cltvExpiry}) min(${minFinalCltvExpiry(incomingPayment, currentBlockHeight)})" }
                rejectedResult
            }

            else -> {
                // All checks passed
                // We treat all payments as multipart payments
                return processMpp(paymentPart, incomingPayment, currentBlockHeight)
            }
        }
    }

    /**
     * NB: all payments are treated as mpp
     */
    private fun processMpp(
        paymentPart: PaymentPart,
        incomingPayment: IncomingPayment,
        currentBlockHeight: Int
    ): ProcessAddResult {

        // Add the payment part to pending htlcSet.
        // NB: We need to update the `pending` map too. But we do that right before we return.
        val htlcSet = pending[paymentPart.paymentHash]?.add(paymentPart) ?: FinalPacketSet(paymentPart, paymentPart.totalAmount)
        val parts = htlcSet.parts.toTypedArray()
        val cumulativeMsat = parts.map { it.amount }.sum()

        when {
            paymentPart.totalAmount != htlcSet.totalAmount -> {
                // Bolt 04:
                // - SHOULD fail the entire HTLC set if `total_msat` is not the same for all HTLCs in the set.
                logger.warning { "Discovered htlc set total_msat_mismatch. Failing entire set with paymentHash = ${paymentPart.paymentHash}" }
                val actions = parts.map { part ->
                    when (part) {
                        is HtlcPart -> {
                            val msg = IncorrectOrUnknownPaymentDetails(part.totalAmount, currentBlockHeight.toLong())
                            actionForFailureMessage(msg, part.htlc)
                        }
                        is PayToOpenPart -> actionForPayToOpenFailure(part.payToOpenRequest) // this will fail all parts, we could only return one
                    }
                }
                pending.remove(paymentPart.paymentHash)
                return ProcessAddResult(status = Status.REJECTED, actions = actions, incomingPayment = incomingPayment)
            }

            cumulativeMsat < htlcSet.totalAmount -> {
                // Bolt 04:
                // - if the total `amount_msat` of this HTLC set equals `total_msat`:
                //   - SHOULD fulfill all HTLCs in the HTLC set

                // Still waiting for more payments

                pending[paymentPart.paymentHash] = htlcSet
                return ProcessAddResult(status = Status.PENDING, actions = emptyList(), incomingPayment = incomingPayment)
            }

            else -> {
                // Accepting payment parts !
                val actions = parts.map { part ->
                    when (part) {
                        is HtlcPart -> {
                            val cmd = CMD_FULFILL_HTLC(
                                id = part.htlc.id,
                                r = incomingPayment.paymentPreimage,
                                commit = true
                            )
                            val channelEvent = ExecuteCommand(command = cmd)
                            WrappedChannelEvent(channelId = part.htlc.channelId, channelEvent = channelEvent)
                        }
                        is PayToOpenPart -> PayToOpenResult(PayToOpenResponse(part.payToOpenRequest.chainHash, paymentPart.paymentHash, incomingPayment.paymentPreimage))
                    }
                }
                pending.remove(paymentPart.paymentHash)
                pendingIncomingPayments.remove(paymentPart.paymentHash)
                return ProcessAddResult(status = Status.ACCEPTED, actions = actions, incomingPayment = incomingPayment)
            }
        }
    }

    fun checkPaymentsTimeout(
        currentTimestampSeconds: Long
    ): List<PeerEvent> {

        val actions = mutableListOf<PeerEvent>()
        val keysToRemove = mutableListOf<ByteVector32>()

        // BOLT 04:
        // - MUST fail all HTLCs in the HTLC set after some reasonable timeout.
        //   - SHOULD wait for at least 60 seconds after the initial HTLC.
        //   - SHOULD use mpp_timeout for the failure message.

        pending.forEach { (paymentHash, set) ->
            val isExpired = currentTimestampSeconds > (set.mppStartedAt + nodeParams.multiPartPaymentExpiry)
            if (isExpired) {
                keysToRemove += paymentHash
                set.parts.forEach { part ->
                    when (part) {
                        is HtlcPart -> actions += actionForFailureMessage(msg = PaymentTimeout, htlc = part.htlc)
                        is PayToOpenPart -> actions += actionForPayToOpenFailure(part.payToOpenRequest)
                    }
                }
            }
        }

        pending.minusAssign(keysToRemove)
        return actions
    }

    /**
     * Creates and returns a CMD_FAIL_HTLC (wrapped in a WrappedChannelEvent)
     */
    private fun actionForFailureMessage(
        msg: FailureMessage,
        htlc: UpdateAddHtlc,
        commit: Boolean = true
    ): WrappedChannelEvent {

        val cmd: Command = when (msg) {
            is BadOnion -> {
                CMD_FAIL_MALFORMED_HTLC(
                    id = htlc.id,
                    onionHash = msg.onionHash,
                    failureCode = msg.code,
                    commit = commit
                )
            }
            else -> {
                val reason = CMD_FAIL_HTLC.Reason.Failure(msg)
                CMD_FAIL_HTLC(
                    id = htlc.id,
                    reason = reason,
                    commit = commit
                )
            }
        }

        val channelEvent = ExecuteCommand(command = cmd)
        return WrappedChannelEvent(channelId = htlc.channelId, channelEvent = channelEvent)
    }

    private fun actionForPayToOpenFailure(payToOpenRequest: PayToOpenRequest): PayToOpenResult {
        return PayToOpenResult(PayToOpenResponse(payToOpenRequest.chainHash, payToOpenRequest.paymentHash, ByteVector32.Zeroes))
    }

    private fun minFinalCltvExpiry(incomingPayment: IncomingPayment, currentBlockHeight: Int): CltvExpiry {
        val minFinalCltvExpiryDelta = incomingPayment.paymentRequest.minFinalExpiryDelta ?: PaymentRequest.DEFAULT_MIN_FINAL_EXPIRY_DELTA
        return minFinalCltvExpiryDelta.toCltvExpiry(currentBlockHeight.toLong())
    }
}
