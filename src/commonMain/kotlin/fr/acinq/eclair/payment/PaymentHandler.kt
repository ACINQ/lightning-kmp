package fr.acinq.eclair.payment

import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.eclair.MilliSatoshi
import fr.acinq.eclair.NodeParams
import fr.acinq.eclair.channel.*
import fr.acinq.eclair.io.*
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
		receive: ReceivePayment

	): ProcessAddResult
	{
		// First we check to see if this is a multipart payment

		val decrypted = IncomingPacket.decrypt(htlc, this.privateKey)
		val onion = decrypted.right
		if ((onion != null) && (onion.totalAmount > onion.amount)) {

			// This is a multipart payment.
			// Forward to alternative logic handler.

			return processMpp(htlc, onion, receive)
		}

		val actions = mutableListOf<PeerEvent>()

		// TODO: check that we've been paid what we asked for
		logger.info { "received ${htlc} for ${receive}" }
		actions.add(
			WrappedChannelEvent(
				htlc.channelId,
				ExecuteCommand(CMD_FULFILL_HTLC(htlc.id, receive.paymentPreimage, commit = true))
			)
		)

		return ProcessAddResult(status = ProcessedStatus.ACCEPTED, actions = actions)
	}

	private fun processMpp(
		htlc: UpdateAddHtlc,
		onion: FinalPayload,
		receive: ReceivePayment

	): ProcessAddResult
	{
		val actions = mutableListOf<PeerEvent>()

		// Todo:
		// - Verify that the htlc.paymentSecret matches our invoice.paymentSecret
		// - if not, then we reject the htlc

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
				val reason = CMD_FAIL_HTLC.Reason.Failure(msg)

				val cmd = CMD_FAIL_HTLC(
					id = part.htlc.id,
					reason = reason,
					commit = true
				)
				val channelEvent = ExecuteCommand(command = cmd)
				val wrapper = WrappedChannelEvent(channelId = htlc.channelId, channelEvent = channelEvent)

				actions.add(wrapper)
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
				r = receive.paymentPreimage,
				commit = true
			)
			val channelEvent = ExecuteCommand(command = cmd)
			val wrapper = WrappedChannelEvent(channelId = part.htlc.channelId, channelEvent = channelEvent)

			actions.add(wrapper)
		}

		pending.remove(htlc.paymentHash)
		return ProcessAddResult(status = ProcessedStatus.ACCEPTED, actions = actions)
	}
}