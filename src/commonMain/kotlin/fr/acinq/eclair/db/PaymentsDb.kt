package fr.acinq.eclair.db

import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.Crypto
import fr.acinq.bitcoin.PublicKey
import fr.acinq.bitcoin.Satoshi
import fr.acinq.eclair.MilliSatoshi
import fr.acinq.eclair.ShortChannelId
import fr.acinq.eclair.channel.ChannelException
import fr.acinq.eclair.payment.FinalFailure
import fr.acinq.eclair.payment.PaymentRequest
import fr.acinq.eclair.utils.*
import fr.acinq.eclair.wire.FailureMessage
import kotlinx.serialization.Serializable

interface PaymentsDb : IncomingPaymentsDb, OutgoingPaymentsDb {
    /** List sent and received payments (with most recent payments first). */
    suspend fun listPayments(count: Int, skip: Int, filters: Set<PaymentTypeFilter> = setOf()): List<WalletPayment>
}

interface IncomingPaymentsDb {
    /** Add a new expected incoming payment (not yet received). */
    suspend fun addIncomingPayment(preimage: ByteVector32, origin: IncomingPayment.Origin, createdAt: Long = currentTimestampMillis())

    /** Get information about an incoming payment (paid or not) for the given payment hash, if any. */
    suspend fun getIncomingPayment(paymentHash: ByteVector32): IncomingPayment?

    /**
     * Mark an incoming payment as received (paid).
     * Note that this function assumes that there is a matching payment request in the DB, otherwise it will be a no-op.
     */
    suspend fun receivePayment(paymentHash: ByteVector32, amount: MilliSatoshi, receivedWith: IncomingPayment.ReceivedWith, receivedAt: Long = currentTimestampMillis())

    /** Add and receive a payment. Use this method when receiving a spontaneous payment, for example a swap-in payment. */
    suspend fun addAndReceivePayment(preimage: ByteVector32, origin: IncomingPayment.Origin, amount: MilliSatoshi, receivedWith: IncomingPayment.ReceivedWith, createdAt: Long = currentTimestampMillis(), receivedAt: Long = currentTimestampMillis())

    /** List received payments (with most recent payments first). */
    suspend fun listReceivedPayments(count: Int, skip: Int, filters: Set<PaymentTypeFilter> = setOf()): List<IncomingPayment>
}

interface OutgoingPaymentsDb {
    /** Add a new pending outgoing payment (not yet settled). */
    suspend fun addOutgoingPayment(outgoingPayment: OutgoingPayment)

    /** Get information about an outgoing payment (settled or not). */
    suspend fun getOutgoingPayment(id: UUID): OutgoingPayment?

    /** Mark an outgoing payment as completed (failed, succeeded, mined). */
    suspend fun completeOutgoingPayment(id: UUID, completed: OutgoingPayment.Status.Completed)

    suspend fun completeOutgoingPayment(id: UUID, finalFailure: FinalFailure, completedAt: Long = currentTimestampMillis()) =
        completeOutgoingPayment(id, OutgoingPayment.Status.Completed.Failed(finalFailure, completedAt))

    suspend fun completeOutgoingPayment(id: UUID, preimage: ByteVector32, completedAt: Long = currentTimestampMillis()) =
        completeOutgoingPayment(id, OutgoingPayment.Status.Completed.Succeeded.OffChain(preimage, completedAt))

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

enum class PaymentTypeFilter { Normal, KeySend, SwapIn, SwapOut, ChannelClosing }

/** A payment made to or from the wallet. */
sealed class WalletPayment {
    companion object {
        /** Absolute time in milliseconds since UNIX epoch when the payment was completed. */
        fun completedAt(payment: WalletPayment): Long = when (payment) {
            is IncomingPayment -> payment.received?.receivedAt ?: 0
            is OutgoingPayment -> when (val status = payment.status) {
                is OutgoingPayment.Status.Completed -> status.completedAt
                else -> 0
            }
        }

        /** Amount sent or received. */
        fun amount(payment: WalletPayment): MilliSatoshi = when (payment) {
            is IncomingPayment -> payment.received?.amount ?: 0.msat
            is OutgoingPayment -> payment.recipientAmount + payment.fees
        }

        /** Fees that applied to the payment. */
        fun fees(payment: WalletPayment): MilliSatoshi = when (payment) {
            is IncomingPayment -> payment.received?.receivedWith?.fees ?: 0.msat
            is OutgoingPayment -> payment.fees
        }
    }
}

/**
 * An incoming payment received by this node.
 * At first it is in a pending state, then will become either a success (if we receive a matching payment) or a failure (if the payment request expires).
 *
 * @param preimage payment preimage, which acts as a proof-of-payment for the payer.
 * @param origin origin of a payment (normal, swap, etc).
 * @param received funds received for this payment, null if no funds have been received yet.
 * @param createdAt absolute time in milliseconds since UNIX epoch when the payment request was generated.
 */
data class IncomingPayment(val preimage: ByteVector32, val origin: Origin, val received: Received?, val createdAt: Long = currentTimestampMillis()) : WalletPayment() {
    constructor(preimage: ByteVector32, origin: Origin) : this(preimage, origin, null, currentTimestampMillis())

    val paymentHash: ByteVector32 = Crypto.sha256(preimage).toByteVector32()

    sealed class Origin {
        /** A normal, invoice-based lightning payment. */
        data class Invoice(val paymentRequest: PaymentRequest) : Origin()

        /** KeySend payments are spontaneous donations for which we didn't create an invoice. */
        object KeySend : Origin()

        /** Swap-in works by sending an on-chain transaction to a swap server, which will pay us in exchange. We may not know the origin address. */
        data class SwapIn(val address: String?) : Origin()

        fun matchesFilters(filters: Set<PaymentTypeFilter>): Boolean = when (this) {
            is Invoice -> filters.isEmpty() || filters.contains(PaymentTypeFilter.Normal)
            is KeySend -> filters.isEmpty() || filters.contains(PaymentTypeFilter.KeySend)
            is SwapIn -> filters.isEmpty() || filters.contains(PaymentTypeFilter.SwapIn)
        }
    }

    data class Received(val amount: MilliSatoshi, val receivedWith: ReceivedWith, val receivedAt: Long = currentTimestampMillis())

    sealed class ReceivedWith {
        abstract val fees: MilliSatoshi

        /** Payment was received via existing lightning channels. */
        object LightningPayment : ReceivedWith() {
            override val fees: MilliSatoshi = 0.msat // with Lightning, the fee is paid by the sender
        }

        /** Payment was received via a new channel opened to us. */
        data class NewChannel(override val fees: MilliSatoshi, val channelId: ByteVector32?) : ReceivedWith()
    }

    /** A payment expires if its origin is [Origin.Invoice] and its invoice has expired. [Origin.KeySend] or [Origin.SwapIn] do not expire. */
    fun isExpired(): Boolean = origin is Origin.Invoice && origin.paymentRequest.isExpired()
}

/**
 * An outgoing payment sent by this node.
 * The payment may be split in multiple parts, which may fail, be retried, and then either succeed or fail.
 *
 * @param id internal payment identifier.
 * @param recipientAmount total amount that will be received by the final recipient (NB: it doesn't contain the fees paid).
 * @param recipient final recipient nodeId.
 * @param details details that depend on the payment type (normal payments, swaps, etc).
 * @param parts partial child payments that have actually been sent.
 * @param status current status of the payment.
 */
data class OutgoingPayment(val id: UUID, val recipientAmount: MilliSatoshi, val recipient: PublicKey, val details: Details, val parts: List<Part>, val status: Status, val createdAt: Long = currentTimestampMillis()) : WalletPayment() {
    constructor(id: UUID, amount: MilliSatoshi, recipient: PublicKey, details: Details) : this(id, amount, recipient, details, listOf(), Status.Pending)

    val paymentHash: ByteVector32 = details.paymentHash
    val fees: MilliSatoshi = when (status) {
        is Status.Pending -> 0.msat
        is Status.Completed.Failed -> 0.msat
        is Status.Completed.Succeeded -> {
            parts.filter { it.status is Part.Status.Succeeded }.map { it.amount }.sum() - recipientAmount
        }
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

        /** Corresponds to the on-chain payments made when closing a channel. */
        data class ChannelClosing(
            val channelId: ByteVector32,
            val closingAddress: String, // btc address
            // The closingAddress may have been supplied by the user during a mutual close.
            // But in all other cases, the funds are sent to the default Phoenix address derived from the wallet seed.
            // So `isSentToDefaultAddress` means this default Phoenix address was used,
            // and is used by the UI to explain the situation to the user.
            val isSentToDefaultAddress: Boolean
        ) : Details() {
            override val paymentHash: ByteVector32 = channelId.sha256()
        }

        fun matchesFilters(filters: Set<PaymentTypeFilter>): Boolean = when (this) {
            is Normal -> filters.isEmpty() || filters.contains(PaymentTypeFilter.Normal)
            is KeySend -> filters.isEmpty() || filters.contains(PaymentTypeFilter.KeySend)
            is SwapOut -> filters.isEmpty() || filters.contains(PaymentTypeFilter.SwapOut)
            is ChannelClosing -> filters.isEmpty() || filters.contains(PaymentTypeFilter.ChannelClosing)
        }
    }

    sealed class Status {
        object Pending : Status()
        sealed class Completed : Status() {
            abstract val completedAt: Long
            data class Failed(val reason: FinalFailure, override val completedAt: Long = currentTimestampMillis()) : Completed()
            sealed class Succeeded : Completed() {
                data class OffChain(
                    val preimage: ByteVector32,
                    override val completedAt: Long = currentTimestampMillis()
                ) : Succeeded()
                data class OnChain(
                    val txids: List<ByteVector32>,
                    // The `claimed` field represents the sum total of bitcoin tx outputs claimed for the user.
                    // A simplified fees can be calculated as: OutgoingPayment.recipientAmount - claimed
                    // In the future, we plan on storing the closing btc transactions as parts.
                    // Then we can use those parts to calculate the fees, and provide more details to the user.
                    val claimed: Satoshi,
                    val closingType: ChannelClosingType,
                    override val completedAt: Long = currentTimestampMillis()
                ) : Succeeded()
            }
        }
    }

    /**
     * An child payment sent by this node (partial payment of the total amount).
     *
     * @param id internal payment identifier.
     * @param amount amount sent, including fees.
     * @param route payment route used.
     * @param status current status of the payment.
     * @param createdAt absolute time in milliseconds since UNIX epoch when the payment was created.
     */
    data class Part(val id: UUID, val amount: MilliSatoshi, val route: List<HopDesc>, val status: Status, val createdAt: Long = currentTimestampMillis()) {
        sealed class Status {
            object Pending : Status()
            data class Succeeded(val preimage: ByteVector32, val completedAt: Long = currentTimestampMillis()) : Status()

            /**
             * @param remoteFailureCode Bolt4 failure code when the failure came from a remote node (see [FailureMessage]).
             * If null this was a local error (channel unavailable for low-level technical reasons).
             */
            data class Failed(val remoteFailureCode: Int?, val details: String, val completedAt: Long = currentTimestampMillis()) : Status() {
                fun isLocalFailure(): Boolean = remoteFailureCode == null
            }
        }
    }
}

@Serializable
enum class ChannelClosingType {
    Mutual, Local, Remote, Revoked, Other;
}

data class HopDesc(val nodeId: PublicKey, val nextNodeId: PublicKey, val shortChannelId: ShortChannelId? = null) {
    override fun toString(): String = when (shortChannelId) {
        null -> "$nodeId->$nextNodeId"
        else -> "$nodeId->$shortChannelId->$nextNodeId"
    }
}