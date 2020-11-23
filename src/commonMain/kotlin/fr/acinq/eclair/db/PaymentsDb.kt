package fr.acinq.eclair.db

import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.PublicKey
import fr.acinq.eclair.MilliSatoshi
import fr.acinq.eclair.ShortChannelId
import fr.acinq.eclair.payment.PaymentRequest
import fr.acinq.eclair.utils.UUID
import fr.acinq.eclair.utils.currentTimestampMillis

interface PaymentsDb : IncomingPaymentsDb, OutgoingPaymentsDb

interface IncomingPaymentsDb {
    /** Add a new expected incoming payment (not yet received). */
    suspend fun addIncomingPayment(pr: PaymentRequest, preimage: ByteVector32, paymentType: PaymentType)

    /**
     * Mark an incoming payment as received (paid). The received amount may exceed the payment request amount.
     * Note that this function assumes that there is a matching payment request in the DB, otherwise it will be a no-op.
     */
    suspend fun receiveIncomingPayment(paymentHash: ByteVector32, amount: MilliSatoshi, receivedAt: Long)

    /** Get information about the incoming payment (paid or not) for the given payment hash, if any. */
    suspend fun getIncomingPayment(paymentHash: ByteVector32): IncomingPayment?

    /** List incoming payments (pending, expired and succeeded). */
    suspend fun listIncomingPayments(count: Int, skip: Int): List<IncomingPayment>

    /** List pending (not paid, not expired) incoming payments. */
    suspend fun listPendingIncomingPayments(count: Int, skip: Int): List<IncomingPayment>

    /** List expired (not paid) incoming payments. */
    suspend fun listExpiredIncomingPayments(count: Int, skip: Int): List<IncomingPayment>

    /** List received (paid) incoming payments. */
    suspend fun listReceivedIncomingPayments(count: Int, skip: Int): List<IncomingPayment>
}

enum class PaymentType { Standard, SwapIn, SwapOut, KeySend }

/**
 * An incoming payment received by this node.
 * At first it is in a pending state once the payment request has been generated, then will become either a success (if we receive a valid HTLC)
 * or a failure (if the payment request expires).
 *
 * @param paymentRequest Bolt 11 payment request.
 * @param paymentPreimage pre-image associated with the payment request's payment_hash.
 * @param paymentType distinguish different payment types (standard, swaps, etc).
 * @param createdAt absolute time in milli-seconds since UNIX epoch when the payment request was generated.
 * @param status current status of the payment.
 */
data class IncomingPayment(val paymentRequest: PaymentRequest, val paymentPreimage: ByteVector32, val paymentType: PaymentType, val createdAt: Long, val status: IncomingPaymentStatus) {
    constructor(paymentRequest: PaymentRequest, paymentPreimage: ByteVector32, paymentType: PaymentType = PaymentType.Standard) : this(paymentRequest, paymentPreimage, paymentType, currentTimestampMillis(), IncomingPaymentStatus.Pending)
}

sealed class IncomingPaymentStatus {
    /** Payment is pending (waiting to receive). */
    object Pending : IncomingPaymentStatus()

    /** Payment has expired. */
    object Expired : IncomingPaymentStatus()

    /**
     * Payment has been successfully received.
     *
     * @param amount amount of the payment received, in milli-satoshis (may exceed the payment request amount).
     * @param receivedAt absolute time in milli-seconds since UNIX epoch when the payment was received.
     */
    data class Received(val amount: MilliSatoshi, val receivedAt: Long) : IncomingPaymentStatus()
}

interface OutgoingPaymentsDb {
    // TODO
}

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