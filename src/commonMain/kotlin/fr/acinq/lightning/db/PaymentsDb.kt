package fr.acinq.lightning.db

import fr.acinq.bitcoin.*
import fr.acinq.lightning.MilliSatoshi
import fr.acinq.lightning.ShortChannelId
import fr.acinq.lightning.payment.*
import fr.acinq.lightning.utils.*
import fr.acinq.lightning.wire.LiquidityAds

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
    suspend fun setLocked(txId: TxId)
}

interface IncomingPaymentsDb {
    /** Add a new expected incoming payment (not yet received). */
    suspend fun addIncomingPayment(preimage: ByteVector32, origin: IncomingPayment.Origin, createdAt: Long = currentTimestampMillis()): IncomingPayment

    /** Get information about an incoming payment (paid or not) for the given payment hash, if any. */
    suspend fun getIncomingPayment(paymentHash: ByteVector32): IncomingPayment?

    /**
     * Mark an incoming payment as received (paid).
     * Note that this function assumes that there is a matching payment request in the DB, otherwise it will be a no-op.
     *
     * With pay-to-open, there is a delay before we receive the parts, and we may not receive any parts at all if the pay-to-open
     * was cancelled due to a disconnection. That is why the payment should not be considered received (and not be displayed to
     * the user) if there are no parts.
     *
     * This method is additive:
     * - receivedWith set is appended to the existing set in database.
     * - receivedAt must be updated in database.
     *
     * @param receivedWith Is a set containing the payment parts holding the incoming amount.
     */
    suspend fun receivePayment(paymentHash: ByteVector32, receivedWith: List<IncomingPayment.ReceivedWith>, receivedAt: Long = currentTimestampMillis())

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

    /** Mark an outgoing payment as completed over Lightning. */
    suspend fun completeOutgoingPaymentOffchain(id: UUID, preimage: ByteVector32, completedAt: Long = currentTimestampMillis())

    /** Mark an outgoing payment as failed. */
    suspend fun completeOutgoingPaymentOffchain(id: UUID, finalFailure: FinalFailure, completedAt: Long = currentTimestampMillis())

    /** Add new partial payments to a pending outgoing payment. */
    suspend fun addOutgoingLightningParts(parentId: UUID, parts: List<LightningOutgoingPayment.Part>)

    /** Mark an outgoing payment part as failed. */
    suspend fun completeOutgoingLightningPart(partId: UUID, failure: LightningOutgoingPayment.Part.Status.Failure, completedAt: Long = currentTimestampMillis())

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

    /**
     * This timestamp will be defined when the payment is final and usable for spending:
     * - for lightning payment it is instant.
     * - for on-chain payments, the associated transaction doesn't necessarily need to be
     *   confirmed (if zero-conf is used), but both sides have to agree that the funds are
     *   usable, a.k.a. "locked".
     */
    override val completedAt: Long?
        get() = when {
            received == null -> null // payment has not yet been received
            received.receivedWith.any { it is ReceivedWith.OnChainIncomingPayment && it.lockedAt == null } -> null // payment has been received, but there is at least one unconfirmed on-chain part
            else -> received.receivedAt
        }

    /** Total fees paid to receive this payment. */
    override val fees: MilliSatoshi = received?.fees ?: 0.msat

    /** Total amount actually received for this payment after applying the fees. If someone sent you 500 and the fee was 10, this amount will be 490. */
    override val amount: MilliSatoshi = received?.amount ?: 0.msat

    sealed class Origin {
        /** A normal, Bolt11 invoice-based lightning payment. */
        data class Invoice(val paymentRequest: Bolt11Invoice) : Origin()

        /** A payment for a Bolt 12 offer: note that we only keep a few fields from the corresponding Bolt 12 invoice. */
        data class Offer(val metadata: OfferPaymentMetadata) : Origin()

        /** KeySend payments are spontaneous donations for which we didn't create an invoice. */
        data object KeySend : Origin()

        /** DEPRECATED: this is the legacy trusted swap-in, which we keep for backwards-compatibility (previous payments inside the DB). */
        data class SwapIn(val address: String?) : Origin()

        /** Trustless swap-in (dual-funding or splice-in) */
        data class OnChain(val txId: TxId, val localInputs: Set<OutPoint>) : Origin()
    }

    data class Received(val receivedWith: List<ReceivedWith>, val receivedAt: Long = currentTimestampMillis()) {
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
            override val fees: MilliSatoshi get() = serviceFee + miningFee.toMilliSatoshi()
            abstract val channelId: ByteVector32
            abstract val txId: TxId
            abstract val confirmedAt: Long?
            abstract val lockedAt: Long?
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
            override val txId: TxId,
            override val confirmedAt: Long?,
            override val lockedAt: Long?
        ) : OnChainIncomingPayment()

        data class SpliceIn(
            override val amount: MilliSatoshi,
            override val serviceFee: MilliSatoshi,
            override val miningFee: Satoshi,
            override val channelId: ByteVector32,
            override val txId: TxId,
            override val confirmedAt: Long?,
            override val lockedAt: Long?
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
    val parts: List<Part> = listOf(),
    val status: Status = Status.Pending,
    override val createdAt: Long = currentTimestampMillis()
) : OutgoingPayment() {

    val paymentHash: ByteVector32 = details.paymentHash

    @Suppress("MemberVisibilityCanBePrivate")
    val routingFee = parts.filter { it.status is Part.Status.Succeeded }.map { it.amount }.sum() - recipientAmount

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
    }

    /** Amount that actually left the wallet. It does include the fees. */
    override val amount: MilliSatoshi = when (details) {
        // For a swap-out, recipientAmount is the amount paid to the swap service. It contains the swap-out fee, but not the routing fee.
        is Details.SwapOut -> recipientAmount + routingFee
        else -> recipientAmount + fees
    }

    sealed class Details {
        abstract val paymentRequest: PaymentRequest
        abstract val paymentHash: ByteVector32

        /** A normal lightning payment. */
        data class Normal(override val paymentRequest: Bolt11Invoice) : Details() {
            override val paymentHash: ByteVector32 = paymentRequest.paymentHash
        }

        /** A blinded lightning payment. */
        data class Blinded(override val paymentRequest: Bolt12Invoice, val payerKey: PrivateKey) : Details() {
            override val paymentHash: ByteVector32 = paymentRequest.paymentHash
        }

        /**
         * Backward compatibility code for legacy trusted swap-out.
         * Swap-out payments send a lightning payment to a swap server, which will send an on-chain transaction to a given address.
         * The swap-out fee is taken by the swap server to cover the miner fee.
         */
        data class SwapOut(val address: String, override val paymentRequest: PaymentRequest, val swapOutFee: Satoshi) : Details() {
            override val paymentHash: ByteVector32 = paymentRequest.paymentHash
        }
    }

    sealed class Status {
        data object Pending : Status()
        sealed class Completed : Status() {
            abstract val completedAt: Long

            data class Failed(val reason: FinalFailure, override val completedAt: Long = currentTimestampMillis()) : Completed()
            sealed class Succeeded : Completed() {
                data class OffChain(
                    val preimage: ByteVector32,
                    override val completedAt: Long = currentTimestampMillis()
                ) : Succeeded()
            }
        }
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
    data class Part(
        val id: UUID,
        val amount: MilliSatoshi,
        val route: List<HopDesc>,
        val status: Status,
        val createdAt: Long = currentTimestampMillis()
    ) {
        sealed class Status {
            data object Pending : Status()
            data class Succeeded(val preimage: ByteVector32, val completedAt: Long = currentTimestampMillis()) : Status()
            data class Failed(val failure: Failure, val completedAt: Long = currentTimestampMillis()) : Status()

            /**
             * User-friendly payment part failure reason, whenever possible.
             * Applications should define their own localized message for each of these failure cases.
             */
            sealed class Failure {
                // @formatter:off
                /** The payment is too small: try sending a larger amount. */
                data object PaymentAmountTooSmall : Failure() { override fun toString(): String = "the payment amount is too small" }
                /** The user has sufficient balance, but the payment is too big: try sending a smaller amount. */
                data object PaymentAmountTooBig : Failure() { override fun toString(): String = "the payment amount is too large" }
                /** The user doesn't have sufficient balance: try sending a smaller amount. */
                data object NotEnoughFunds : Failure() { override fun toString(): String = "not enough funds" }
                /** The payment must be retried with more fees to reach the recipient. */
                data object NotEnoughFees : Failure() { override fun toString(): String = "routing fees are insufficient" }
                /** The payment expiry specified by the recipient is too far away in the future. */
                data object PaymentExpiryTooBig : Failure() { override fun toString(): String = "the payment expiry is too far in the future" }
                /** There are too many pending payments: wait for them to settle and retry. */
                data object TooManyPendingPayments : Failure() { override fun toString(): String = "too many pending payments" }
                /** Payments are temporarily paused while a channel is splicing: the payment can be retried after the splice. */
                data object ChannelIsSplicing : Failure() { override fun toString(): String = "a splicing operation is in progress" }
                /** The channel is closing: another channel should be created to send the payment. */
                data object ChannelIsClosing : Failure() { override fun toString(): String = "channel closing is in progress" }
                /** Remote failure from an intermediate node in the payment route. */
                sealed class RouteFailure : Failure()
                /** A remote node had a temporary failure: the payment may succeed if retried. */
                data object TemporaryRemoteFailure : RouteFailure() { override fun toString(): String = "a node in the route had a temporary failure" }
                /** The payment amount could not be relayed to the recipient, most likely because they don't have enough inbound liquidity. */
                data object RecipientLiquidityIssue : RouteFailure() { override fun toString(): String = "liquidity issue at the recipient node" }
                /** The payment recipient is offline and could not accept the payment. */
                data object RecipientIsOffline : RouteFailure() { override fun toString(): String = "recipient node is offline or unreachable" }
                /** The payment recipient received the payment but rejected it. */
                data object RecipientRejectedPayment : Failure() { override fun toString(): String = "recipient node rejected the payment" }
                /** This is an error that cannot be easily interpreted: we don't know what exactly went wrong and cannot correctly inform the user. */
                data class Uninterpretable(val message: String) : Failure() { override fun toString(): String = message }
                // @formatter:on
            }
        }
    }
}

sealed class OnChainOutgoingPayment : OutgoingPayment() {
    abstract override val id: UUID
    abstract val miningFees: Satoshi
    abstract val channelId: ByteVector32
    abstract val txId: TxId
    abstract override val createdAt: Long
    abstract val confirmedAt: Long?
    abstract val lockedAt: Long?
}

data class SpliceOutgoingPayment(
    override val id: UUID,
    val recipientAmount: Satoshi,
    val address: String,
    override val miningFees: Satoshi,
    override val channelId: ByteVector32,
    override val txId: TxId,
    override val createdAt: Long,
    override val confirmedAt: Long?,
    override val lockedAt: Long?,
) : OnChainOutgoingPayment() {
    override val amount: MilliSatoshi = (recipientAmount + miningFees).toMilliSatoshi()
    override val fees: MilliSatoshi = miningFees.toMilliSatoshi()
    override val completedAt: Long? = confirmedAt
}

data class SpliceCpfpOutgoingPayment(
    override val id: UUID,
    override val miningFees: Satoshi,
    override val channelId: ByteVector32,
    override val txId: TxId,
    override val createdAt: Long,
    override val confirmedAt: Long?,
    override val lockedAt: Long?,
) : OnChainOutgoingPayment() {
    override val amount: MilliSatoshi = miningFees.toMilliSatoshi()
    override val fees: MilliSatoshi = miningFees.toMilliSatoshi()
    override val completedAt: Long? = confirmedAt
}

data class InboundLiquidityOutgoingPayment(
    override val id: UUID,
    override val channelId: ByteVector32,
    override val txId: TxId,
    override val miningFees: Satoshi,
    val lease: LiquidityAds.Lease,
    override val createdAt: Long,
    override val confirmedAt: Long?,
    override val lockedAt: Long?,
) : OnChainOutgoingPayment() {
    override val fees: MilliSatoshi = (miningFees + lease.fees.serviceFee).toMilliSatoshi()
    override val amount: MilliSatoshi = fees
    override val completedAt: Long? = lockedAt
}

enum class ChannelClosingType {
    Mutual, Local, Remote, Revoked, Other;
}

data class ChannelCloseOutgoingPayment(
    override val id: UUID,
    val recipientAmount: Satoshi,
    val address: String,
    // The closingAddress may have been supplied by the user during a mutual close initiated by the user.
    // But in all other cases, the funds are sent to the default Phoenix address derived from the wallet seed.
    // So `isSentToDefaultAddress` means this default Phoenix address was used,
    // and is used by the UI to explain the situation to the user.
    val isSentToDefaultAddress: Boolean,
    override val miningFees: Satoshi,
    override val channelId: ByteVector32,
    override val txId: TxId,
    override val createdAt: Long,
    override val confirmedAt: Long?,
    override val lockedAt: Long?,
    val closingType: ChannelClosingType
) : OnChainOutgoingPayment() {
    override val amount: MilliSatoshi = (recipientAmount + miningFees).toMilliSatoshi()
    override val fees: MilliSatoshi = miningFees.toMilliSatoshi()
    override val completedAt: Long? = confirmedAt
}

data class HopDesc(val nodeId: PublicKey, val nextNodeId: PublicKey, val shortChannelId: ShortChannelId? = null) {
    override fun toString(): String = when (shortChannelId) {
        null -> "$nodeId->$nextNodeId"
        else -> "$nodeId->$shortChannelId->$nextNodeId"
    }
}