package fr.acinq.lightning.db

import fr.acinq.bitcoin.*
import fr.acinq.lightning.MilliSatoshi
import fr.acinq.lightning.ShortChannelId
import fr.acinq.lightning.channel.ChannelException
import fr.acinq.lightning.payment.FinalFailure
import fr.acinq.lightning.payment.PaymentRequest
import fr.acinq.lightning.utils.*
import fr.acinq.lightning.wire.FailureMessage

interface PaymentsDb : IncomingPaymentsDb, OutgoingPaymentsDb {
    /**
     * On-chain-related payments are not instant, but needs to be displayed as soon as possible to the user
     * for UX purposes. They affect the balance differently whether they are incoming or outgoing.
     *
     * For example, if a user initiates a swap-in, they will expect to see an incoming payment immediately in
     * their payment history, even if the amount isn't included in the balance and the funds are not spendable right
     * away. Conversely, after a swap-out, the balance is immediately decreased even if the transaction is not confirmed.
     *
     * Note that this is all related to transaction confirmations on the blockchain, but involves a bit more than
     * that, because both sides of the channel need to agree, and they may also agree to not require any confirmation.
     *
     * Incoming payments (channel creation or splice-in) are considered confirmed when the corresponding funds are added
     * to the balance and can be spent. Before that, they should appear as "pending" to the user.
     *
     * Outgoing payments (splice-out or channel close) are considered confirmed when the corresponding funding transaction
     * is confirmed on the blockchain. Before that, they should appear as "final" to the user, but with some indication that
     * the transaction is not yet confirmed. In the case of a force-close, the outgoing payment will only be considered confirmed
     * when the channel is closed, meaning that all related transactions have been confirmed.
     */
    suspend fun setConfirmed(txId: ByteVector32)
}

interface IncomingPaymentsDb {
    /** Add a new expected incoming payment (not yet received). */
    suspend fun addIncomingPayment(preimage: ByteVector32, origin: IncomingPayment.Origin, createdAt: Long = currentTimestampMillis())

    /** Get information about an incoming payment (paid or not) for the given payment hash, if any. */
    suspend fun getIncomingPayment(paymentHash: ByteVector32): IncomingPayment?

    /**
     * Mark an incoming payment as received (paid).
     * Note that this function assumes that there is a matching payment request in the DB, otherwise it will be a no-op.
     *
     * This method is additive:
     * - receivedWith set is appended to the existing set in database.
     * - receivedAt must be updated in database.
     *
     * @param receivedWith is a set containing the payment parts holding the incoming amount, its aggregated amount should be equal to the amount param.
     */
    suspend fun receivePayment(paymentHash: ByteVector32, receivedWith: Set<IncomingPayment.ReceivedWith>, receivedAt: Long = currentTimestampMillis())

    /** Simultaneously add and receive a payment. Use this method when receiving a spontaneous payment, for example a swap-in payment. */
    suspend fun addAndReceivePayment(preimage: ByteVector32, origin: IncomingPayment.Origin, receivedWith: Set<IncomingPayment.ReceivedWith>, createdAt: Long = currentTimestampMillis(), receivedAt: Long = currentTimestampMillis())

    /** List expired unpaid normal payments created within specified time range (with the most recent payments first). */
    suspend fun listExpiredPayments(fromCreatedAt: Long = 0, toCreatedAt: Long = currentTimestampMillis()): List<IncomingPayment>

    /** Remove a pending incoming payment.*/
    suspend fun removeIncomingPayment(paymentHash: ByteVector32): Boolean
}

interface OutgoingPaymentsDb {
    /** Add a new pending outgoing payment (not yet settled). */
    suspend fun addOutgoingPayment(outgoingPayment: OutgoingPayment)

    /** Get information about an outgoing payment (settled or not). */
    suspend fun getLightningOutgoingPayment(id: UUID): LightningOutgoingPayment?

    /** Mark an outgoing payment as completed for closing a Lightning channel on-chain with a list of on-chain txs. */
    suspend fun completeOutgoingPaymentForClosing(id: UUID, parts: List<LightningOutgoingPayment.ClosingTxPart>, completedAt: Long = currentTimestampMillis())

    /** Mark an outgoing payment as completed over Lightning. */
    suspend fun completeOutgoingPaymentOffchain(id: UUID, preimage: ByteVector32, completedAt: Long = currentTimestampMillis())

    /** Mark an outgoing payment as failed. */
    suspend fun completeOutgoingPaymentOffchain(id: UUID, finalFailure: FinalFailure, completedAt: Long = currentTimestampMillis())

    /** Add new partial payments to a pending outgoing payment. */
    suspend fun addOutgoingLightningParts(parentId: UUID, parts: List<LightningOutgoingPayment.LightningPart>)

    /** Mark an outgoing payment part as failed. */
    suspend fun completeOutgoingLightningPart(partId: UUID, failure: Either<ChannelException, FailureMessage>, completedAt: Long = currentTimestampMillis())

    /** Mark an outgoing payment part as succeeded. This should not update the parent payment, since some parts may still be pending. */
    suspend fun completeOutgoingLightningPart(partId: UUID, preimage: ByteVector32, completedAt: Long = currentTimestampMillis())

    /** Get information about an outgoing payment from the id of one of its parts. */
    suspend fun getLightningOutgoingPaymentFromPartId(partId: UUID): LightningOutgoingPayment?

    /** List all the outgoing payment attempts that tried to pay the given payment hash. */
    suspend fun listLightningOutgoingPayments(paymentHash: ByteVector32): List<LightningOutgoingPayment>
}

/** A payment made to or from the wallet. */
sealed class WalletPayment {
    /** Absolute time in milliseconds since UNIX epoch when the payment was created. */
    abstract val createdAt: Long

    /** Absolute time in milliseconds since UNIX epoch when the payment was completed. May be null. */
    abstract val completedAt: Long?

    /** Fees applied to complete this payment. */
    abstract val fees: MilliSatoshi

    /**
     * The actual amount that has been sent or received:
     * - for outgoing payments, the fee is included. This is what left the wallet;
     * - for incoming payments, this is the amount AFTER the fees are applied. This is what went into the wallet.
     */
    abstract val amount: MilliSatoshi
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
data class IncomingPayment(val preimage: ByteVector32, val origin: Origin, val received: Received?, override val createdAt: Long = currentTimestampMillis()) : WalletPayment() {

    val paymentHash: ByteVector32 = Crypto.sha256(preimage).toByteVector32()

    /** Returns the confirmed reception timestamp. If any on-chain parts have NOT yet confirmed, returns null. */
    override val completedAt: Long?
        get() = when {
            received == null -> null // payment has not yet been received
            received.receivedWith.any { it is ReceivedWith.OnChainIncomingPayment && it.confirmedAt == null } -> null // payment has been received, but there is at least one unconfirmed on-chain part
            else -> received.receivedAt
        }

    /** Total fees paid to receive this payment. */
    override val fees: MilliSatoshi = received?.fees ?: 0.msat

    /** Total amount actually received for this payment after applying the fees. If someone sent you 500 and the fee was 10, this amount will be 490. */
    override val amount: MilliSatoshi = received?.amount ?: 0.msat

    sealed class Origin {
        /** A normal, invoice-based lightning payment. */
        data class Invoice(val paymentRequest: PaymentRequest) : Origin()

        /** KeySend payments are spontaneous donations for which we didn't create an invoice. */
        object KeySend : Origin()

        /** DEPRECATED: this is the legacy trusted swap-in, which we keep for backwards-compatibility (previous payments inside the DB). */
        data class SwapIn(val address: String?) : Origin()

        /** Trustless swap-in (dual-funding or splice-in) */
        data class OnChain(val txid: ByteVector32, val localInputs: Set<OutPoint>) : Origin()
    }

    data class Received(val receivedWith: Set<ReceivedWith>, val receivedAt: Long = currentTimestampMillis()) {
        /** Total amount received after applying the fees. */
        val amount: MilliSatoshi = receivedWith.map { it.amount }.sum()

        /** Fees applied to receive this payment. */
        val fees: MilliSatoshi = receivedWith.map { it.fees }.sum()
    }

    sealed class ReceivedWith {
        /** Amount received for this part after applying the fees. This is the final amount we can use. */
        abstract val amount: MilliSatoshi

        /** Fees applied to receive this part. Is zero for Lightning payments. */
        abstract val fees: MilliSatoshi

        /** Payment was received via existing lightning channels. */
        data class LightningPayment(override val amount: MilliSatoshi, val channelId: ByteVector32, val htlcId: Long) : ReceivedWith() {
            override val fees: MilliSatoshi = 0.msat // with Lightning, the fee is paid by the sender
        }

        sealed class OnChainIncomingPayment : ReceivedWith() {
            abstract val serviceFee: MilliSatoshi
            abstract val miningFee: Satoshi
            override val fees: MilliSatoshi
                get() = serviceFee + miningFee.toMilliSatoshi()
            abstract val channelId: ByteVector32
            abstract val txId: ByteVector32
            abstract val confirmedAt: Long?
        }

        /**
         * Payment was received via a new channel opened to us.
         *
         * @param amount Our side of the balance of this channel when it's created. This is the amount pushed to us once the creation fees are applied.
         * @param serviceFee Fees paid to Lightning Service Provider to open this channel.
         * @param miningFee Feed paid to bitcoin miners for processing the L1 transaction.
         * @param channelId The long id of the channel created to receive this payment. May be null if the channel id is not known.
         */
        data class NewChannel(
            override val amount: MilliSatoshi,
            override val serviceFee: MilliSatoshi,
            override val miningFee: Satoshi,
            override val channelId: ByteVector32,
            override val txId: ByteVector32,
            override val confirmedAt: Long?
        ) : OnChainIncomingPayment()

        data class SpliceIn(
            override val amount: MilliSatoshi,
            override val serviceFee: MilliSatoshi,
            override val miningFee: Satoshi,
            override val channelId: ByteVector32,
            override val txId: ByteVector32,
            override val confirmedAt: Long?
        ) : OnChainIncomingPayment()
    }

    /** A payment expires if its origin is [Origin.Invoice] and its invoice has expired. [Origin.KeySend] or [Origin.SwapIn] do not expire. */
    fun isExpired(): Boolean = origin is Origin.Invoice && origin.paymentRequest.isExpired()
}

sealed class OutgoingPayment : WalletPayment() {
    abstract val id: UUID
}

/**
 * An outgoing payment sent by this node.
 * The payment may be split in multiple parts, which may fail, be retried, and then either succeed or fail.
 *
 * @param id internal payment identifier.
 * @param recipientAmount total amount that will be received by the final recipient.
 *          Note that, depending on the type of the payment, it may or may not contain the fees. See the `amount` and `fees` fields for details.
 * @param recipient final recipient nodeId.
 * @param details details that depend on the payment type (normal payments, swaps, etc).
 * @param parts list of partial child payments that have actually been sent.
 * @param status current status of the payment.
 */
data class LightningOutgoingPayment(
    override val id: UUID,
    val recipientAmount: MilliSatoshi,
    val recipient: PublicKey,
    val details: Details,
    val parts: List<Part>,
    val status: Status,
    override val createdAt: Long = currentTimestampMillis()
) : OutgoingPayment() {

    /** Create an outgoing payment in a pending status, without any parts yet. */
    constructor(id: UUID, amount: MilliSatoshi, recipient: PublicKey, invoice: PaymentRequest) : this(id, amount, recipient, Details.Normal(invoice), listOf(), Status.Pending)

    val paymentHash: ByteVector32 = details.paymentHash

    @Suppress("MemberVisibilityCanBePrivate")
    val routingFee = parts.filterIsInstance<LightningPart>().filter { it.status is LightningPart.Status.Succeeded }.map { it.amount }.sum() - recipientAmount

    override val completedAt: Long? = (status as? Status.Completed)?.completedAt

    /** This is the total fees that have been paid to make the payment work. It includes the LN routing fees, the fee for the swap-out service, the mining fees for closing a channel. */
    override val fees: MilliSatoshi = when (status) {
        is Status.Pending -> 0.msat
        is Status.Completed.Failed -> 0.msat
        is Status.Completed.Succeeded.OffChain -> {
            if (details is Details.SwapOut) {
                // The swap-out service takes a fee to cover the miner fee. It's the difference between what we paid the service (recipientAmount) and what goes to the address.
                // We also include the routing fee, in case the swap-service is NOT the trampoline node.
                details.swapOutFee.toMilliSatoshi() + routingFee
            } else {
                routingFee
            }
        }
        is Status.Completed.Succeeded.OnChain -> {
            if (details is Details.ChannelClosing) {
                // For a channel closing, recipientAmount is the balance of the channel that is being closed.
                // It DOES include the future mining fees. Fees are found by subtracting the aggregated claims from the recipient amount.
                recipientAmount - parts.filterIsInstance<ClosingTxPart>().map { it.claimed.toMilliSatoshi() }.sum()
            } else {
                routingFee
            }
        }
    }

    /** Amount that actually left the wallet. It does include the fees. */
    override val amount: MilliSatoshi = when (details) {
        // For a channel closing, recipientAmount is the balance of the channel that is closed. It already contains the fees.
        is Details.ChannelClosing -> recipientAmount
        // For a swap-out, recipientAmount is the amount paid to the swap service. It contains the swap-out fee, but not the routing fee.
        is Details.SwapOut -> recipientAmount + routingFee
        else -> recipientAmount + fees
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

        /**
         * Backward compatibility code for legacy trusted swap-out.
         * Swap-out payments send a lightning payment to a swap server, which will send an on-chain transaction to a given address.
         * The swap-out fee is taken by the swap server to cover the miner fee.
         */
        data class SwapOut(val address: String, val paymentRequest: PaymentRequest, val swapOutFee: Satoshi) : Details() {
            override val paymentHash: ByteVector32 = paymentRequest.paymentHash
        }

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
                    override val completedAt: Long = currentTimestampMillis()
                ) : Succeeded()
            }
        }
    }

    /**
     * An outgoing payment is an abstraction ; it is actually settled through one or several child payments that are
     * done over Lightning, or through on-chain transactions.
     */
    sealed class Part {
        abstract val id: UUID
        abstract val createdAt: Long
    }

    /**
     * A child payment sent by this node (partial payment of the total amount). This payment has a status and can fail.
     *
     * @param id internal payment identifier.
     * @param amount amount sent, including fees.
     * @param route payment route used.
     * @param status current status of the payment.
     * @param createdAt absolute time in milliseconds since UNIX epoch when the payment was created.
     */
    data class LightningPart(
        override val id: UUID,
        val amount: MilliSatoshi,
        val route: List<HopDesc>,
        val status: Status,
        override val createdAt: Long = currentTimestampMillis()
    ) : Part() {
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

    /**
     * A child payment for closing a channel through an on-chain transaction.
     *
     * @param claimed represents the sum total of bitcoin tx outputs claimed for the user. A simplified fees can be
     *      calculated as: OutgoingPayment.recipientAmount - claimed.
     * @param closingType the type of the closing : it can be mutually decided by both end of the channel, or unilaterally
     *      initiated by the remote or the local peer.
     */
    data class ClosingTxPart(
        override val id: UUID,
        val txId: ByteVector32,
        val claimed: Satoshi,
        val closingType: ChannelClosingType,
        override val createdAt: Long,
    ) : Part()
}

data class SpliceOutgoingPayment(
    override val id: UUID,
    val amountSatoshi: Satoshi,
    val address: String,
    val miningFees: Satoshi,
    val txId: ByteVector32,
    override val createdAt: Long,
    val confirmedAt: Long? = null
) : OutgoingPayment() {
    override val amount: MilliSatoshi = amountSatoshi.toMilliSatoshi()
    override val fees: MilliSatoshi = miningFees.toMilliSatoshi()
    override val completedAt: Long? = confirmedAt
}

enum class ChannelClosingType {
    Mutual, Local, Remote, Revoked, Other;
}

data class HopDesc(val nodeId: PublicKey, val nextNodeId: PublicKey, val shortChannelId: ShortChannelId? = null) {
    override fun toString(): String = when (shortChannelId) {
        null -> "$nodeId->$nextNodeId"
        else -> "$nodeId->$shortChannelId->$nextNodeId"
    }
}