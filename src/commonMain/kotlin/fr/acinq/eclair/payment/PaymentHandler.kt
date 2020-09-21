package fr.acinq.eclair.payment

import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.eclair.MilliSatoshi
import fr.acinq.eclair.NodeParams
import fr.acinq.eclair.channel.*
import fr.acinq.eclair.io.*
import fr.acinq.eclair.utils.Either
import fr.acinq.eclair.wire.*
import org.kodein.log.Logger
import org.kodein.log.LoggerFactory

class PaymentHandler(
	val nodeParams: NodeParams
) {

	enum class ProcessedStatus {
		ACCEPTED,
		REJECTED,
		PENDING // neither accepted or rejected yet
	}

	data class ProcessAddResult(val status: ProcessedStatus, val actions: List<PeerEvent>)

	private val logger = LoggerFactory.default.newLogger(Logger.Tag(PaymentHandler::class))

	private data class MppHtlc(val htlc: UpdateAddHtlc, val onion: FinalPayload)
	private val pending = mutableMapOf<ByteVector32, Set<MppHtlc>>()

	private val privateKey get() = nodeParams.privateKey

	/**
	 * @return A triple
	 */
	fun processAdd(
		htlc: UpdateAddHtlc,
		incomingPayment: IncomingPayment?

	): ProcessAddResult
	{
		// BOLT 04:
		//
		// - if the payment hash is unknown:
		//   - MUST fail the HTLC.
		//   - MUST return an incorrect_or_unknown_payment_details error.

		if (incomingPayment == null) {

			logger.warning { "received ${htlc} } for which we don't have a preimage" }

			val msg = IncorrectOrUnknownPaymentDetails(amount = MilliSatoshi(0), height = 0)
			val action = actionForFailureMessage(msg, htlc)

			return ProcessAddResult(status = PaymentHandler.ProcessedStatus.REJECTED, actions = listOf(action))
		}

		// Try to decrypt to onion
		when (val decrypted = IncomingPacket.decrypt(htlc, this.privateKey)) {

			is Either.Left -> { // Unable to decrypt onion

				val failureMessage = decrypted.value
				val action = actionForFailureMessage(failureMessage, htlc)

				return ProcessAddResult(status = ProcessedStatus.REJECTED, actions = listOf(action))
			}
			is Either.Right -> {

				val onion = decrypted.value
				return processAdd(htlc, onion, incomingPayment)
			}
		}
	}

	private fun processAdd(
		htlc: UpdateAddHtlc,
		onion: FinalPayload,
		incomingPayment: IncomingPayment

	): ProcessAddResult
	{
		// BOLT 04:
		//
		// - if the payment_secret doesn't match the expected value for that payment_hash,
		//   or the payment_secret is required and is not present:
		//   - MUST fail the HTLC.
		//   - MUST return an incorrect_or_unknown_payment_details error.
		//
		//   Related: https://github.com/lightningnetwork/lightning-rfc/pull/671

		val paymentSecret_expected = incomingPayment.paymentRequest.paymentSecret
		if (paymentSecret_expected != null) {

			val paymentSecret_received = onion.paymentSecret
			if (paymentSecret_received == null || paymentSecret_received != paymentSecret_expected) {

				val msg = IncorrectOrUnknownPaymentDetails(amount = onion.amount, height = 0)
				val action = actionForFailureMessage(msg, htlc)

				return ProcessAddResult(status = PaymentHandler.ProcessedStatus.REJECTED, actions = listOf(action))
			}
		}

		if (onion.totalAmount > onion.amount) {

			// This is a multipart payment.
			// Forward to alternative logic handler.

			return processMpp(htlc, onion, incomingPayment)
		}

		// BOLT 04:
		//
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

		val amount_expected = incomingPayment.paymentRequest.amount
		val amount_received = onion.amount

		if (amount_expected != null) { // invoice amount may have been unspecified

			if ((amount_received < amount_expected) || (amount_received > amount_expected * 2)) {

				logger.warning { "received invalid amount: ${amount_received}, expected: ${amount_expected}" }

				val msg = IncorrectOrUnknownPaymentDetails(amount = onion.amount, height = 0)
				val action = actionForFailureMessage(msg, htlc)

				return ProcessAddResult(status = PaymentHandler.ProcessedStatus.REJECTED, actions = listOf(action))
			}
		}

		logger.info { "received ${htlc} for ${incomingPayment.paymentRequest}" }
		val action = WrappedChannelEvent(
			channelId = htlc.channelId,
			channelEvent = ExecuteCommand(CMD_FULFILL_HTLC(htlc.id, incomingPayment.paymentPreimage, commit = true))
		)

		return ProcessAddResult(status = ProcessedStatus.ACCEPTED, actions = listOf(action))
	}

	private fun processMpp(
		htlc: UpdateAddHtlc,
		onion: FinalPayload,
		incomingPayment: IncomingPayment

	): ProcessAddResult
	{
		val actions = mutableListOf<PeerEvent>()

		// Add <htlc, onion> tuple to pending set.
		//
		// NB: We need to update the `pending` map too. But we do that right before we return.

		val updatedPendingSet = (pending[htlc.paymentHash] ?: setOf<MppHtlc>()) + MppHtlc(htlc, onion)

		// Bolt 04:
		// - SHOULD fail the entire HTLC set if `total_msat` is not the same for all HTLCs in the set.

		val parts = updatedPendingSet.toTypedArray()

		val total_msat: MilliSatoshi = parts[0].onion.totalAmount

		var total_msat_mismatch = false
		var mismatch_amount = MilliSatoshi(msat = 0)

		for (i in 1..parts.lastIndex) {

			val part_total_amount = parts[i].onion.totalAmount
			if (part_total_amount != total_msat) {
				total_msat_mismatch = true
				mismatch_amount = part_total_amount
			}
		}

		if (total_msat_mismatch) {

			for (part in parts) {

				val msg = FinalIncorrectHtlcAmount(amount = mismatch_amount)
				actions += actionForFailureMessage(msg, htlc)
			}

			logger.warning {
				"Discovered htlc set total_msat_mismatch."+
				" Failing entire set with paymentHash = ${incomingPayment.paymentRequest.paymentHash}"
			}

			pending.remove(htlc.paymentHash)
			return ProcessAddResult(status = ProcessedStatus.REJECTED, actions = actions)
		}

		// Bolt 04:
		// - if the total `amount_msat` of this HTLC set equals `total_msat`:
		//   - SHOULD fulfill all HTLCs in the HTLC set

		var cumulative_msat = MilliSatoshi(msat = 0)

		for (part in parts) {
			cumulative_msat += part.onion.amount
		}

		if (cumulative_msat < total_msat) {
			// Still waiting for more payments
			//
			// Future Work: This is where we need to request a timer/timeout action

			pending[htlc.paymentHash] = updatedPendingSet
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

	private fun actionForFailureMessage(
		msg: FailureMessage,
		htlc: UpdateAddHtlc,
		commit: Boolean = true

	): WrappedChannelEvent
	{
		val reason = CMD_FAIL_HTLC.Reason.Failure(msg)

		val cmd = CMD_FAIL_HTLC(
			id = htlc.id,
			reason = reason,
			commit = commit
		)
		val channelEvent = ExecuteCommand(command = cmd)
		val wrapper = WrappedChannelEvent(channelId = htlc.channelId, channelEvent = channelEvent)

		return wrapper
	}
}