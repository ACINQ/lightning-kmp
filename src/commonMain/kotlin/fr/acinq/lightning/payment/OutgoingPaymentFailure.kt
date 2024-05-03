package fr.acinq.lightning.payment

import fr.acinq.bitcoin.utils.Either
import fr.acinq.lightning.MilliSatoshi
import fr.acinq.lightning.channel.*
import fr.acinq.lightning.db.LightningOutgoingPayment
import fr.acinq.lightning.utils.currentTimestampMillis
import fr.acinq.lightning.wire.*

/**
 * A fatal failure that stops payment attempts.
 * Applications should define their own localized message for each of these failures.
 */
sealed class FinalFailure {

    /** Use this function when no payment attempts have been made (e.g. when a precondition failed). */
    fun toPaymentFailure(): OutgoingPaymentFailure = OutgoingPaymentFailure(this, listOf<LightningOutgoingPayment.Part.Status.Failed>())

    // @formatter:off
    data object AlreadyPaid : FinalFailure() { override fun toString(): String = "this invoice has already been paid" }
    data object InvalidPaymentAmount : FinalFailure() { override fun toString(): String = "payment amount must be positive" }
    data object FeaturesNotSupported : FinalFailure() { override fun toString(): String = "payment request features not supported" }
    data object InvalidPaymentId : FinalFailure() { override fun toString(): String = "payment ID must be unique" }
    data object ChannelNotConnected : FinalFailure() { override fun toString(): String = "channel is not connected yet, please retry when connected" }
    data object ChannelOpening : FinalFailure() { override fun toString(): String = "channel creation is in progress, please retry when ready" }
    data object ChannelClosing : FinalFailure() { override fun toString(): String = "channel closing is in progress, please retry when a new channel has been created" }
    data object NoAvailableChannels : FinalFailure() { override fun toString(): String = "payment could not be sent through existing channels, check individual failures for more details" }
    data class InsufficientBalance(val available: MilliSatoshi) : FinalFailure() { override fun toString(): String = "not enough funds in wallet to afford payment (available = $available)" }
    data object NoRouteToRecipient : FinalFailure() { override fun toString(): String = "unable to route payment to recipient" }
    data object RecipientUnreachable : FinalFailure() { override fun toString(): String = "the recipient was offline or did not have enough liquidity to receive the payment" }
    data object RetryExhausted: FinalFailure() { override fun toString(): String = "payment attempts exhausted without success" }
    data object WalletRestarted: FinalFailure() { override fun toString(): String = "wallet restarted while a payment was ongoing" }
    data object UnknownError : FinalFailure() { override fun toString(): String = "an unknown error occurred" }
    // @formatter:on
}

/**
 * A non-final failure of a payment part, after which the payment may be retried after a while or using a different channel.
 * Applications should define their own localized message for each of these failures.
 */
sealed class PartFailure {
    // @formatter:off
    /** The payment is too small: try sending a larger amount. */
    data object PaymentAmountTooSmall : PartFailure()
    /** The user has sufficient balance, but the payment is too big: try sending a smaller amount. */
    data object PaymentAmountTooBig : PartFailure()
    /** The user doesn't have sufficient balance: try sending a smaller amount. */
    data object NotEnoughFunds : PartFailure()
    /** The payment must be retried with more fees to reach the recipient. */
    data object NotEnoughFees : PartFailure()
    /** The payment expiry specified by the recipient is too far away in the future. */
    data object PaymentExpiryTooBig : PartFailure()
    /** There are too many pending payments: wait for them to settle and retry. */
    data object TooManyPendingPayments : PartFailure()
    /** Payments are temporarily paused while a channel is splicing: the payment can be retried after the splice. */
    data object ChannelIsSplicing : PartFailure()
    /** The channel is closing: another channel should be created to send the payment. */
    data object ChannelIsClosing : PartFailure()
    sealed class RouteFailure : PartFailure()
    /** A remote node had a temporary failure: the payment may succeed if retried. */
    data object TemporaryRemoteFailure : RouteFailure()
    /** The payment amount could not be relayed to the recipient, most likely because they don't have enough inbound liquidity. */
    data object RecipientLiquidityIssue : RouteFailure()
    /** The payment recipient is offline and could not accept the payment. */
    data object RecipientIsOffline : RouteFailure()
    /** The payment recipient received the payment but rejected it. */
    data object RecipientRejectedPayment : PartFailure()
    /** This is an error that cannot be easily interpreted: we don't know what exactly went wrong and cannot correctly inform the user. */
    data class Uninterpretable(val message: String) : PartFailure()
    // @formatter:on
}

data class OutgoingPaymentFailure(val reason: FinalFailure, val failures: List<LightningOutgoingPayment.Part.Status.Failed>) {
    constructor(reason: FinalFailure, failures: List<Either<ChannelException, FailureMessage>>, completedAt: Long = currentTimestampMillis()) : this(reason, failures.map { convertFailure(it, completedAt) })

    /**
     * A detailed summary of the all internal errors.
     * This is targeted at users with technical knowledge of the lightning protocol.
     */
    fun details(): String = failures.foldIndexed("") { index, msg, problem -> msg + "${index + 1}: ${problem.failure}\n" }

    companion object {
        fun convertFailure(failure: Either<ChannelException, FailureMessage>, completedAt: Long = currentTimestampMillis()): LightningOutgoingPayment.Part.Status.Failed {
            val converted = when (failure) {
                is Either.Left -> when (failure.value) {
                    is HtlcValueTooSmall -> PartFailure.PaymentAmountTooSmall
                    is CannotAffordFees -> PartFailure.PaymentAmountTooBig
                    is RemoteCannotAffordFeesForNewHtlc -> PartFailure.PaymentAmountTooBig
                    is HtlcValueTooHighInFlight -> PartFailure.PaymentAmountTooBig
                    is InsufficientFunds -> PartFailure.NotEnoughFunds
                    is TooManyAcceptedHtlcs -> PartFailure.TooManyPendingPayments
                    is TooManyOfferedHtlcs -> PartFailure.TooManyPendingPayments
                    is ExpiryTooBig -> PartFailure.PaymentExpiryTooBig
                    is ForbiddenDuringSplice -> PartFailure.ChannelIsSplicing
                    is ChannelUnavailable -> PartFailure.ChannelIsClosing
                    is ClosingAlreadyInProgress -> PartFailure.ChannelIsClosing
                    is ForcedLocalCommit -> PartFailure.ChannelIsClosing
                    is FundingTxSpent -> PartFailure.ChannelIsClosing
                    is HtlcOverriddenByLocalCommit -> PartFailure.ChannelIsClosing
                    is HtlcsTimedOutDownstream -> PartFailure.ChannelIsClosing
                    is NoMoreHtlcsClosingInProgress -> PartFailure.ChannelIsClosing
                    else -> PartFailure.Uninterpretable(failure.value.message)
                }
                is Either.Right -> when (failure.value) {
                    is AmountBelowMinimum -> PartFailure.PaymentAmountTooSmall
                    is FeeInsufficient -> PartFailure.NotEnoughFees
                    TrampolineExpiryTooSoon -> PartFailure.NotEnoughFees
                    TrampolineFeeInsufficient -> PartFailure.NotEnoughFees
                    is FinalIncorrectCltvExpiry -> PartFailure.RecipientRejectedPayment
                    is FinalIncorrectHtlcAmount -> PartFailure.RecipientRejectedPayment
                    is IncorrectOrUnknownPaymentDetails -> PartFailure.RecipientRejectedPayment
                    PaymentTimeout -> PartFailure.RecipientLiquidityIssue
                    UnknownNextPeer -> PartFailure.RecipientIsOffline
                    is ExpiryTooSoon -> PartFailure.TemporaryRemoteFailure
                    ExpiryTooFar -> PartFailure.TemporaryRemoteFailure
                    is ChannelDisabled -> PartFailure.TemporaryRemoteFailure
                    is TemporaryChannelFailure -> PartFailure.TemporaryRemoteFailure
                    TemporaryNodeFailure -> PartFailure.TemporaryRemoteFailure
                    PermanentChannelFailure -> PartFailure.TemporaryRemoteFailure
                    PermanentNodeFailure -> PartFailure.TemporaryRemoteFailure
                    is InvalidOnionBlinding -> PartFailure.Uninterpretable(failure.value.message)
                    is InvalidOnionHmac -> PartFailure.Uninterpretable(failure.value.message)
                    is InvalidOnionKey -> PartFailure.Uninterpretable(failure.value.message)
                    is InvalidOnionPayload -> PartFailure.Uninterpretable(failure.value.message)
                    is InvalidOnionVersion -> PartFailure.Uninterpretable(failure.value.message)
                    InvalidRealm -> PartFailure.Uninterpretable(failure.value.message)
                    is IncorrectCltvExpiry -> PartFailure.Uninterpretable(failure.value.message)
                    RequiredChannelFeatureMissing -> PartFailure.Uninterpretable(failure.value.message)
                    RequiredNodeFeatureMissing -> PartFailure.Uninterpretable(failure.value.message)
                    is UnknownFailureMessage -> PartFailure.Uninterpretable(failure.value.message)
                }
            }
            return LightningOutgoingPayment.Part.Status.Failed(converted, completedAt)
        }
    }
}
