package fr.acinq.eclair.db

import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.PublicKey
import fr.acinq.eclair.MilliSatoshi
import fr.acinq.eclair.ShortChannelId
import fr.acinq.eclair.payment.PaymentRequest
import fr.acinq.eclair.utils.UUID

/**
 * An outgoing payment sent by this node.
 * At first it is in a pending state, then will become either a success or a failure.
 *
 * @param id internal payment identifier.
 * @param parentId internal identifier of a parent payment, or [[id]] if single-part payment.
 * @param paymentHash payment_hash.
 * @param amount amount sent, including fees (note that a payment may be sent in multiple parts).
 * @param recipientAmount total amount that will be received by the final recipient.
 * @param recipientNodeId id of the final recipient.
 * @param route payment route used.
 * @param createdAt absolute time in milli-seconds since UNIX epoch when the payment was created.
 * @param paymentRequest Bolt 11 payment request (if paying from an invoice).
 * @param status current status of the payment.
 */
data class OutgoingPayment(
    val id: UUID,
    val parentId: UUID,
    val paymentHash: ByteVector32,
    // TODO: add payment type (support for swaps, keysend and pay-to-open)
    val amount: MilliSatoshi,
    val recipientAmount: MilliSatoshi,
    val recipientNodeId: PublicKey,
    val route: List<HopDesc>,
    val createdAt: Long,
    val paymentRequest: PaymentRequest?,
    val status: OutgoingPaymentStatus
)

sealed class OutgoingPaymentStatus {
    /** Payment is pending (waiting for the recipient to release the preimage). */
    object Pending : OutgoingPaymentStatus()

    /**
     * Payment has been successfully sent and the recipient released the preimage.
     * We now have a valid proof-of-payment.
     *
     * @param preimage the preimage of the paymentHash.
     * @param completedAt absolute time in milli-seconds since UNIX epoch when the payment was completed.
     */
    data class Succeeded(val preimage: ByteVector32, val completedAt: Long) : OutgoingPaymentStatus()

    /**
     * Payment has failed and may be retried.
     *
     * @param failure failure message.
     * @param completedAt absolute time in milli-seconds since UNIX epoch when the payment was completed.
     */
    data class Failed(val failure: String, val completedAt: Long) : OutgoingPaymentStatus()
}

data class HopDesc(val nodeId: PublicKey, val nextNodeId: PublicKey, val shortChannelId: ShortChannelId? = null) {
    override fun toString(): String = when (shortChannelId) {
        null -> "$nodeId->$nextNodeId"
        else -> "$nodeId->$shortChannelId->$nextNodeId"
    }
}

interface PaymentsDb {
    // TODO: @t-bast
}