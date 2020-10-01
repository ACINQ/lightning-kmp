package fr.acinq.eclair.payment

import fr.acinq.bitcoin.ByteVector32
import fr.acinq.eclair.MilliSatoshi
import fr.acinq.eclair.NodeParams
import fr.acinq.eclair.channel.*
import fr.acinq.eclair.io.PeerEvent
import fr.acinq.eclair.io.WrappedChannelEvent
import fr.acinq.eclair.utils.Either
import fr.acinq.eclair.utils.currentTimestampSeconds
import fr.acinq.eclair.utils.newEclairLogger
import fr.acinq.eclair.utils.sum
import fr.acinq.eclair.wire.*

data class IncomingPayment(
    val paymentRequest: PaymentRequest,
    val paymentPreimage: ByteVector32
)

class PaymentHandler(
    val nodeParams: NodeParams
) {

    enum class ProcessedStatus {
        ACCEPTED,
        REJECTED,
        PENDING // neither accepted or rejected yet
    }

    data class ProcessAddResult(val status: ProcessedStatus, val actions: List<PeerEvent>)

    private data class FinalPacket( // htlc + decrypted onion
        val htlc: UpdateAddHtlc,
        val onion: FinalPayload
    )

    private class FinalPacketSet { // htlc set + metadata
        val parts: Set<FinalPacket>
        val totalAmount: MilliSatoshi // all parts must have matching `total_msat`
        val mppStartedAt: Long // in seconds

        constructor(htlc: UpdateAddHtlc, onion: FinalPayload) {
            parts = setOf(FinalPacket(htlc, onion))
            totalAmount = onion.totalAmount
            mppStartedAt = currentTimestampSeconds()
        }

        private constructor(parts: Set<FinalPacket>, totalAmount: MilliSatoshi, mppStartedAt: Long) {
            this.parts = parts
            this.totalAmount = totalAmount
            this.mppStartedAt = mppStartedAt
        }

        fun add(htlc: UpdateAddHtlc, onion: FinalPayload): FinalPacketSet {
            return FinalPacketSet(
                parts = this.parts + FinalPacket(htlc, onion),
                totalAmount = this.totalAmount,
                mppStartedAt = this.mppStartedAt
            )
        }
    }

    private val logger = newEclairLogger()
    private val pending = mutableMapOf<ByteVector32, FinalPacketSet>()
    private val privateKey get() = nodeParams.nodePrivateKey

    /**
     * Processes an incoming htlc.
     * Before calling this, the htlc must be committed and acked by both sides.
     *
     * @return A result that indicates whether or not the packet was
     * accepted, rejected, or still pending (as the case may be for multipart payments).
     * Also includes the list of actions to be queued.
     */
    fun processAdd(
        htlc: UpdateAddHtlc,
        incomingPayment: IncomingPayment?,
        currentBlockHeight: Int
    ): ProcessAddResult {
        // Security note:
        // There are several checks we could perform before decrypting the onion.
        // However an error message here would differ from an error message below,
        // as we don't know the `onion.totalAmount` yet.
        // So to prevent any kind of information leakage, we always peel the onion first.

        // Try to decrypt the onion
        return when (val decrypted = IncomingPacket.decrypt(htlc, this.privateKey)) {

            is Either.Left -> { // Unable to decrypt onion

                val failureMessage = decrypted.value
                val action = actionForFailureMessage(failureMessage, htlc)
                ProcessAddResult(status = ProcessedStatus.REJECTED, actions = listOf(action))
            }
            is Either.Right -> {

                val onion = decrypted.value
                processAdd(htlc, onion, incomingPayment, currentBlockHeight)
            }
        }
    }

    private fun processAdd(
        htlc: UpdateAddHtlc,
        onion: FinalPayload,
        incomingPayment: IncomingPayment?,
        currentBlockHeight: Int
    ): ProcessAddResult {
        val failureMsg = IncorrectOrUnknownPaymentDetails(onion.totalAmount, currentBlockHeight.toLong())
        val failureAction = actionForFailureMessage(failureMsg, htlc)
        val rejectedResult = ProcessAddResult(status = ProcessedStatus.REJECTED, actions = listOf(failureAction))

        if (incomingPayment == null) {
            logger.warning { "received $htlc for which we don't have a preimage" }
            return rejectedResult
        }

        if (incomingPayment.paymentRequest.isExpired()) {
            logger.warning { "received payment for expired invoice" }
            return rejectedResult
        }

        val minFinalCltvExpiryDelta =
            incomingPayment.paymentRequest.minFinalExpiryDelta ?: PaymentRequest.DEFAULT_MIN_FINAL_EXPIRY_DELTA
        val minFinalCltvExpiry = minFinalCltvExpiryDelta.toCltvExpiry(currentBlockHeight.toLong())

        if (htlc.cltvExpiry < minFinalCltvExpiry) {
            logger.warning { "received payment with expiry too small: received(${htlc.cltvExpiry}) min($minFinalCltvExpiry)" }
            return rejectedResult
        }

        // BOLT 04:
        // - if the payment_secret doesn't match the expected value for that payment_hash,
        //   or the payment_secret is required and is not present:
        //   - MUST fail the HTLC.
        //   - MUST return an incorrect_or_unknown_payment_details error.
        //
        //   Related: https://github.com/lightningnetwork/lightning-rfc/pull/671
        //
        // NB: We always include a paymentSecret, and mark the feature as mandatory.

        val paymentSecretExpected = incomingPayment.paymentRequest.paymentSecret
        val paymentSecretReceived = onion.paymentSecret

        if (paymentSecretExpected != paymentSecretReceived) {
            logger.warning { "received payment with invalid paymentSecret" }
            return rejectedResult
        }

        // BOLT 04:
        // - if the amount paid is less than the amount expected:
        //   - MUST fail the HTLC.
        //   - MUST return an incorrect_or_unknown_payment_details error.
        //
        // - if the amount paid is more than twice the amount expected:
        //   - SHOULD fail the HTLC.
        //   - SHOULD return an incorrect_or_unknown_payment_details error.
        //
        //   Note: this allows the origin node to reduce information leakage by altering
        //   the amount while not allowing for accidental gross overpayment.

        val amountExpected = incomingPayment.paymentRequest.amount
        val amountReceived = onion.totalAmount

        if (amountExpected != null) { // invoice amount may have been unspecified

            if ((amountReceived < amountExpected) || (amountReceived > amountExpected * 2)) {
                logger.warning { "received invalid amount: $amountReceived, expected: $amountExpected" }
                return rejectedResult
            }
        }

        // All checks passed

        if (onion.totalAmount > onion.amount) {

            // This is a multipart payment.
            // Forward to alternative logic handler.
            return processMpp(htlc, onion, incomingPayment, currentBlockHeight)

        } else {

            logger.info { "received $htlc for ${incomingPayment.paymentRequest}" }
            val action = WrappedChannelEvent(
                channelId = htlc.channelId,
                channelEvent = ExecuteCommand(CMD_FULFILL_HTLC(htlc.id, incomingPayment.paymentPreimage, commit = true))
            )
            return ProcessAddResult(status = ProcessedStatus.ACCEPTED, actions = listOf(action))
        }
    }

    private fun processMpp(
        htlc: UpdateAddHtlc,
        onion: FinalPayload,
        incomingPayment: IncomingPayment,
        currentBlockHeight: Int
    ): ProcessAddResult {
        val actions = mutableListOf<PeerEvent>()

        // Add <htlc, onion> tuple to pending htlcSet.
        // NB: We need to update the `pending` map too. But we do that right before we return.

        val htlcSet = pending[htlc.paymentHash]?.add(htlc, onion) ?: FinalPacketSet(htlc, onion)
        val parts = htlcSet.parts.toTypedArray()

        // Bolt 04:
        // - SHOULD fail the entire HTLC set if `total_msat` is not the same for all HTLCs in the set.

        if (onion.totalAmount != htlcSet.totalAmount) {

            parts.forEach { part ->
                val msg = IncorrectOrUnknownPaymentDetails(part.onion.totalAmount, currentBlockHeight.toLong())
                actions += actionForFailureMessage(msg, part.htlc)
            }

            logger.warning {
                "Discovered htlc set total_msat_mismatch." +
                    " Failing entire set with paymentHash = ${htlc.paymentHash}"
            }

            pending.remove(htlc.paymentHash)
            return ProcessAddResult(status = ProcessedStatus.REJECTED, actions = actions)
        }

        // Bolt 04:
        // - if the total `amount_msat` of this HTLC set equals `total_msat`:
        //   - SHOULD fulfill all HTLCs in the HTLC set

        val cumulativeMsat = parts.map { it.onion.amount }.sum()

        if (cumulativeMsat < htlcSet.totalAmount) {
            // Still waiting for more payments

            pending[htlc.paymentHash] = htlcSet
            return ProcessAddResult(status = ProcessedStatus.PENDING, actions = actions)
        }

        // Accepting payment parts !

        for (part in parts) {

            val cmd = CMD_FULFILL_HTLC(
                id = part.htlc.id,
                r = incomingPayment.paymentPreimage,
                commit = true
            )
            val channelEvent = ExecuteCommand(command = cmd)
            val wrapper = WrappedChannelEvent(channelId = part.htlc.channelId, channelEvent = channelEvent)

            actions.add(wrapper)
        }

        pending.remove(htlc.paymentHash)
        return ProcessAddResult(status = ProcessedStatus.ACCEPTED, actions = actions)
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
                    actions += actionForFailureMessage(msg = PaymentTimeout, htlc = part.htlc)
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
}
