package fr.acinq.lightning.payment

import fr.acinq.bitcoin.utils.Either
import fr.acinq.lightning.channel.*
import fr.acinq.lightning.db.LightningOutgoingPayment
import fr.acinq.lightning.db.OutgoingPaymentsDb
import fr.acinq.lightning.utils.UUID
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
    data object AlreadyInProgress : FinalFailure() { override fun toString(): String = "another payment is in progress for that invoice" }
    data object AlreadyPaid : FinalFailure() { override fun toString(): String = "this invoice has already been paid" }
    data object InvalidPaymentAmount : FinalFailure() { override fun toString(): String = "payment amount must be positive" }
    data object FeaturesNotSupported : FinalFailure() { override fun toString(): String = "payment request features not supported" }
    data object InvalidPaymentId : FinalFailure() { override fun toString(): String = "payment ID must be unique" }
    data object ChannelNotConnected : FinalFailure() { override fun toString(): String = "channel is not connected yet, please retry when connected" }
    data object ChannelOpening : FinalFailure() { override fun toString(): String = "channel creation is in progress, please retry when ready" }
    data object ChannelClosing : FinalFailure() { override fun toString(): String = "channel closing is in progress, please retry when a new channel has been created" }
    data object NoAvailableChannels : FinalFailure() { override fun toString(): String = "payment could not be sent through existing channels, check individual failures for more details" }
    data object InsufficientBalance : FinalFailure() { override fun toString(): String = "not enough funds in wallet to afford payment" }
    data object RecipientUnreachable : FinalFailure() { override fun toString(): String = "the recipient was offline or did not have enough liquidity to receive the payment" }
    data object RecipientRejectedPayment : FinalFailure() { override fun toString(): String = "the recipient rejected the payment" }
    data object RetryExhausted: FinalFailure() { override fun toString(): String = "payment attempts exhausted without success" }
    data object WalletRestarted: FinalFailure() { override fun toString(): String = "wallet restarted while a payment was ongoing" }
    data object UnknownError : FinalFailure() { override fun toString(): String = "an unknown error occurred" }
    // @formatter:on
}

data class OutgoingPaymentFailure(val reason: FinalFailure, val failures: List<LightningOutgoingPayment.Part.Status.Failed>) {
    constructor(reason: FinalFailure, failures: List<Either<ChannelException, FailureMessage>>, completedAt: Long = currentTimestampMillis()) : this(
        reason,
        failures.map { LightningOutgoingPayment.Part.Status.Failed(convertFailure(it), completedAt) }
    )

    /** Extracts the most user-friendly reason for the payment failure. */
    fun explain(): Either<LightningOutgoingPayment.Part.Status.Failed.Failure, FinalFailure> {
        val partFailure = failures.map { it.failure }.lastOrNull { it !is LightningOutgoingPayment.Part.Status.Failed.Failure.Uninterpretable } ?: failures.lastOrNull()?.failure
        return when (reason) {
            FinalFailure.NoAvailableChannels, FinalFailure.UnknownError, FinalFailure.RetryExhausted -> partFailure?.let { Either.Left(it) } ?: Either.Right(reason)
            else -> Either.Right(reason)
        }
    }

    /**
     * A detailed summary of the all internal errors.
     * This is targeted at users with technical knowledge of the lightning protocol.
     */
    fun details(): String = failures.foldIndexed("") { index, msg, problem -> msg + "${index + 1}: ${problem.failure}\n" }

    companion object {
        fun convertFailure(failure: Either<ChannelException, FailureMessage>): LightningOutgoingPayment.Part.Status.Failed.Failure {
            return when (failure) {
                is Either.Left -> when (failure.value) {
                    is HtlcValueTooSmall -> LightningOutgoingPayment.Part.Status.Failed.Failure.PaymentAmountTooSmall
                    is CannotAffordFees -> LightningOutgoingPayment.Part.Status.Failed.Failure.PaymentAmountTooBig
                    is RemoteCannotAffordFeesForNewHtlc -> LightningOutgoingPayment.Part.Status.Failed.Failure.PaymentAmountTooBig
                    is HtlcValueTooHighInFlight -> LightningOutgoingPayment.Part.Status.Failed.Failure.PaymentAmountTooBig
                    is InsufficientFunds -> LightningOutgoingPayment.Part.Status.Failed.Failure.NotEnoughFunds
                    is TooManyAcceptedHtlcs -> LightningOutgoingPayment.Part.Status.Failed.Failure.TooManyPendingPayments
                    is TooManyOfferedHtlcs -> LightningOutgoingPayment.Part.Status.Failed.Failure.TooManyPendingPayments
                    is ExpiryTooBig -> LightningOutgoingPayment.Part.Status.Failed.Failure.PaymentExpiryTooBig
                    is ForbiddenDuringSplice -> LightningOutgoingPayment.Part.Status.Failed.Failure.ChannelIsSplicing
                    is ChannelUnavailable -> LightningOutgoingPayment.Part.Status.Failed.Failure.ChannelIsClosing
                    is ClosingAlreadyInProgress -> LightningOutgoingPayment.Part.Status.Failed.Failure.ChannelIsClosing
                    is ForcedLocalCommit -> LightningOutgoingPayment.Part.Status.Failed.Failure.ChannelIsClosing
                    is FundingTxSpent -> LightningOutgoingPayment.Part.Status.Failed.Failure.ChannelIsClosing
                    is HtlcOverriddenByLocalCommit -> LightningOutgoingPayment.Part.Status.Failed.Failure.ChannelIsClosing
                    is HtlcOverriddenByRemoteCommit -> LightningOutgoingPayment.Part.Status.Failed.Failure.ChannelIsClosing
                    is HtlcsTimedOutDownstream -> LightningOutgoingPayment.Part.Status.Failed.Failure.ChannelIsClosing
                    is NoMoreHtlcsClosingInProgress -> LightningOutgoingPayment.Part.Status.Failed.Failure.ChannelIsClosing
                    else -> LightningOutgoingPayment.Part.Status.Failed.Failure.Uninterpretable(failure.value.message)
                }
                is Either.Right -> when (failure.value) {
                    is AmountBelowMinimum -> LightningOutgoingPayment.Part.Status.Failed.Failure.PaymentAmountTooSmall
                    is FeeInsufficient -> LightningOutgoingPayment.Part.Status.Failed.Failure.NotEnoughFees
                    is TrampolineFeeOrExpiryInsufficient -> LightningOutgoingPayment.Part.Status.Failed.Failure.NotEnoughFees
                    is FinalIncorrectCltvExpiry -> LightningOutgoingPayment.Part.Status.Failed.Failure.RecipientRejectedPayment
                    is FinalIncorrectHtlcAmount -> LightningOutgoingPayment.Part.Status.Failed.Failure.RecipientRejectedPayment
                    is IncorrectOrUnknownPaymentDetails -> LightningOutgoingPayment.Part.Status.Failed.Failure.RecipientRejectedPayment
                    PaymentTimeout -> LightningOutgoingPayment.Part.Status.Failed.Failure.RecipientLiquidityIssue
                    UnknownNextPeer -> LightningOutgoingPayment.Part.Status.Failed.Failure.RecipientIsOffline
                    UnknownNextTrampoline -> LightningOutgoingPayment.Part.Status.Failed.Failure.RecipientIsOffline
                    is ExpiryTooSoon -> LightningOutgoingPayment.Part.Status.Failed.Failure.TemporaryRemoteFailure
                    ExpiryTooFar -> LightningOutgoingPayment.Part.Status.Failed.Failure.TemporaryRemoteFailure
                    is ChannelDisabled -> LightningOutgoingPayment.Part.Status.Failed.Failure.TemporaryRemoteFailure
                    is TemporaryChannelFailure -> LightningOutgoingPayment.Part.Status.Failed.Failure.TemporaryRemoteFailure
                    TemporaryNodeFailure -> LightningOutgoingPayment.Part.Status.Failed.Failure.TemporaryRemoteFailure
                    PermanentChannelFailure -> LightningOutgoingPayment.Part.Status.Failed.Failure.TemporaryRemoteFailure
                    PermanentNodeFailure -> LightningOutgoingPayment.Part.Status.Failed.Failure.TemporaryRemoteFailure
                    TemporaryTrampolineFailure -> LightningOutgoingPayment.Part.Status.Failed.Failure.TemporaryRemoteFailure
                    is InvalidOnionBlinding -> LightningOutgoingPayment.Part.Status.Failed.Failure.Uninterpretable(failure.value.message)
                    is InvalidOnionHmac -> LightningOutgoingPayment.Part.Status.Failed.Failure.Uninterpretable(failure.value.message)
                    is InvalidOnionKey -> LightningOutgoingPayment.Part.Status.Failed.Failure.Uninterpretable(failure.value.message)
                    is InvalidOnionPayload -> LightningOutgoingPayment.Part.Status.Failed.Failure.Uninterpretable(failure.value.message)
                    is InvalidOnionVersion -> LightningOutgoingPayment.Part.Status.Failed.Failure.Uninterpretable(failure.value.message)
                    InvalidRealm -> LightningOutgoingPayment.Part.Status.Failed.Failure.Uninterpretable(failure.value.message)
                    is IncorrectCltvExpiry -> LightningOutgoingPayment.Part.Status.Failed.Failure.Uninterpretable(failure.value.message)
                    RequiredChannelFeatureMissing -> LightningOutgoingPayment.Part.Status.Failed.Failure.Uninterpretable(failure.value.message)
                    RequiredNodeFeatureMissing -> LightningOutgoingPayment.Part.Status.Failed.Failure.Uninterpretable(failure.value.message)
                    is UnknownFailureMessage -> LightningOutgoingPayment.Part.Status.Failed.Failure.Uninterpretable(failure.value.message)
                }
            }
        }
    }
}
