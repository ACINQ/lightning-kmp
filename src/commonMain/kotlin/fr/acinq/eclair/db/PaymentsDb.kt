package fr.acinq.eclair.db

import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.Crypto
import fr.acinq.bitcoin.PublicKey
import fr.acinq.eclair.MilliSatoshi
import fr.acinq.eclair.ShortChannelId
import fr.acinq.eclair.channel.ChannelException
import fr.acinq.eclair.payment.FinalFailure
import fr.acinq.eclair.payment.PaymentRequest
import fr.acinq.eclair.utils.*
import fr.acinq.eclair.wire.FailureMessage

interface PaymentsDb : IncomingPaymentsDb, OutgoingPaymentsDb {
    /** List sent and received payments (with most recent payments first). */
    suspend fun listPayments(count: Int, skip: Int, filters: Set<PaymentTypeFilter> = setOf()): List<Either<IncomingPayment, OutgoingPayment>>
}

interface IncomingPaymentsDb {
    /** Add a new expected incoming payment (not yet received). */
    suspend fun addIncomingPayment(pr: PaymentRequest, preimage: ByteVector32, details: IncomingPayment.Details, createdAt: Long = currentTimestampMillis())

    /** Get information about an incoming payment (paid or not) for the given payment hash, if any. */
    suspend fun getIncomingPayment(paymentHash: ByteVector32): IncomingPayment?

    /**
     * Mark an incoming payment as received (paid). The received amount may exceed the payment request amount.
     * Note that this function assumes that there is a matching payment request in the DB, otherwise it will be a no-op.
     */
    suspend fun receivePayment(paymentHash: ByteVector32, amount: MilliSatoshi, receivedAt: Long = currentTimestampMillis())

    /** List received payments (with most recent payments first). */
    suspend fun listReceivedPayments(count: Int, skip: Int, filters: Set<PaymentTypeFilter> = setOf()): List<IncomingPayment>
}

interface OutgoingPaymentsDb {
    /** Add a new pending outgoing payment (not yet settled). */
    suspend fun addOutgoingPayment(outgoingPayment: OutgoingPayment)

    /** Get information about an outgoing payment (settled or not). */
    suspend fun getOutgoingPayment(id: UUID): OutgoingPayment?

    /** Mark an outgoing payment as failed. */
    suspend fun updateOutgoingPayment(id: UUID, failure: FinalFailure, completedAt: Long = currentTimestampMillis())

    /** Mark an outgoing payment as succeeded. This should delete all intermediate failed payment parts and only keep the successful ones. */
    suspend fun updateOutgoingPayment(id: UUID, preimage: ByteVector32, completedAt: Long = currentTimestampMillis())

    /** Add new partial payments to a pending outgoing payment. */
    suspend fun addOutgoingParts(parentId: UUID, parts: List<OutgoingPayment.Part>)

    /** Mark an outgoing payment part as failed. */
    suspend fun updateOutgoingPart(partId: UUID, failure: Either<ChannelException, FailureMessage>, completedAt: Long = currentTimestampMillis())

    /** Mark an outgoing payment part as succeeded. This should not update the parent payment, since some parts may still be pending. */
    suspend fun updateOutgoingPart(partId: UUID, preimage: ByteVector32, completedAt: Long = currentTimestampMillis())

    /** Get information about an outgoing payment from the id of one of its parts. */
    suspend fun getOutgoingPart(partId: UUID): OutgoingPayment?

    /** List all the outgoing payment attempts that tried to pay the given payment hash. */
    suspend fun listOutgoingPayments(paymentHash: ByteVector32): List<OutgoingPayment>

    /** List outgoing payments (with most recent payments first). */
    suspend fun listOutgoingPayments(count: Int, skip: Int, filters: Set<PaymentTypeFilter> = setOf()): List<OutgoingPayment>
}

enum class PaymentTypeFilter { Normal, SwapIn, SwapOut, KeySend }

/**
 * An incoming payment received by this node.
 * At first it is in a pending state once the payment request has been generated, then will become either a success (if we receive a valid HTLC)
 * or a failure (if the payment request expires).
 *
 * @param paymentRequest Bolt 11 payment request.
 * @param paymentPreimage pre-image associated with the payment request's payment_hash.
 * @param details details specific to the type of payment (normal, swaps, etc).
 * @param createdAt absolute time in milliseconds since UNIX epoch when the payment request was generated.
 * @param status current status of the payment.
 */
data class IncomingPayment(val paymentRequest: PaymentRequest, val paymentPreimage: ByteVector32, val details: Details, val createdAt: Long, val status: Status) {
    constructor(paymentRequest: PaymentRequest, paymentPreimage: ByteVector32, details: Details = Details.Normal) : this(paymentRequest, paymentPreimage, details, currentTimestampMillis(), Status.Pending)

    sealed class Details {
        object Normal : Details()
        data class SwapIn(val address: String) : Details()

        fun matchesFilters(filters: Set<PaymentTypeFilter>): Boolean = when (this) {
            is Normal -> filters.isEmpty() || filters.contains(PaymentTypeFilter.Normal)
            is SwapIn -> filters.isEmpty() || filters.contains(PaymentTypeFilter.SwapIn)
        }
    }

    sealed class Status {
        object Pending : Status()
        object Expired : Status()
        data class Received(val amount: MilliSatoshi, val receivedAt: Long) : Status()
    }
}

/**
 * An outgoing payment sent by this node.
 * The payment may be split in multiple parts, which may fail, be retried, and then either succeed or fail.
 *
 * @param id internal payment identifier.
 * @param amount total amount that will be received by the final recipient (NB: it doesn't contain the fees paid).
 * @param recipient final recipient nodeId.
 * @param details details that depend on the payment type (normal payments, swaps, etc).
 * @param parts partial child payments that have actually been sent.
 * @param status current status of the payment.
 */
data class OutgoingPayment(val id: UUID, val amount: MilliSatoshi, val recipient: PublicKey, val details: Details, val parts: List<Part>, val status: Status) {
    constructor(id: UUID, amount: MilliSatoshi, recipient: PublicKey, details: Details) : this(id, amount, recipient, details, listOf(), Status.Pending)

    val paymentHash: ByteVector32 = details.paymentHash
    val fees: MilliSatoshi = when (status) {
        is Status.Failed -> 0.msat
        else -> parts.filter { it.status is Part.Status.Succeeded || it.status == Part.Status.Pending }.map { it.amount }.sum() - amount
    }

    sealed class Details {
        abstract val paymentHash: ByteVector32

        /** A normal lightning payment. */
        data class Normal(val paymentRequest: PaymentRequest) : Details() {
            override val paymentHash: ByteVector32 = paymentRequest.paymentHash
        }

        /** KeySend payments are spontaneous donations that don't need an invoice from the recipient. */
        data class KeySend(val preimage: ByteVector32) : Details() {
            override val paymentHash: ByteVector32 = Crypto.sha256(preimage).toByteVector32()
        }

        /** Swaps out send a lightning payment to a swap server, which will send an on-chain transaction to a given address. */
        data class SwapOut(val address: String, override val paymentHash: ByteVector32) : Details()

        fun matchesFilters(filters: Set<PaymentTypeFilter>): Boolean = when (this) {
            is Normal -> filters.isEmpty() || filters.contains(PaymentTypeFilter.Normal)
            is KeySend -> filters.isEmpty() || filters.contains(PaymentTypeFilter.KeySend)
            is SwapOut -> filters.isEmpty() || filters.contains(PaymentTypeFilter.SwapOut)
        }
    }

    sealed class Status {
        object Pending : Status()
        data class Succeeded(val preimage: ByteVector32, val completedAt: Long) : Status()
        data class Failed(val reason: FinalFailure, val completedAt: Long) : Status()
    }

    /**
     * An child payment sent by this node (partial payment of the total amount).
     *
     * @param id internal payment identifier.
     * @param amount amount sent, including fees.
     * @param route payment route used.
     * @param createdAt absolute time in milliseconds since UNIX epoch when the payment was created.
     * @param status current status of the payment.
     */
    data class Part(val id: UUID, val amount: MilliSatoshi, val route: List<HopDesc>, val createdAt: Long, val status: Status) {
        sealed class Status {
            object Pending : Status()
            data class Succeeded(val preimage: ByteVector32, val completedAt: Long) : Status()
            data class Failed(val failure: Either<ChannelException, FailureMessage>, val completedAt: Long) : Status()
        }
    }

}

data class HopDesc(val nodeId: PublicKey, val nextNodeId: PublicKey, val shortChannelId: ShortChannelId? = null) {
    override fun toString(): String = when (shortChannelId) {
        null -> "$nodeId->$nextNodeId"
        else -> "$nodeId->$shortChannelId->$nextNodeId"
    }
}