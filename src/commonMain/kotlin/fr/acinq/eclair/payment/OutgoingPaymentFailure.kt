package fr.acinq.eclair.payment

import fr.acinq.eclair.channel.ChannelException
import fr.acinq.eclair.utils.Either
import fr.acinq.eclair.wire.*

/** A fatal failure that stops payment attempts. */
sealed class FinalFailure {
    abstract val message: String

    /** Use this function when no payment attempts have been made (e.g. when a precondition failed). */
    fun toPaymentFailure(): OutgoingPaymentFailure = OutgoingPaymentFailure(this, listOf())

    // @formatter:off
    object InvalidPaymentAmount : FinalFailure() { override val message: String = "Invalid parameter: payment amount must be positive." }
    object InvalidPaymentId : FinalFailure() { override val message: String = "Invalid parameter: payment ID must be unique." }
    object NoAvailableChannels : FinalFailure() { override val message: String = "No channels available to send payment. Check internet connection or ensure you have an available balance." }
    object InsufficientBalance : FinalFailure() { override val message: String = "Not enough funds in wallet to afford payment (note that fees may apply)." }
    object NoRouteToRecipient : FinalFailure() { override val message: String = "Unable to route payment to recipient." }
    object RetryExhausted: FinalFailure() { override val message: String = "Payment attempts exhausted without success." }
    object UnknownError : FinalFailure() { override val message: String = "An unknown error occurred." }
    // @formatter:on
}

data class OutgoingPaymentFailure(val reason: FinalFailure, val failures: List<Either<ChannelException, FailureMessage>>) {

    private fun isRouteError(failure: Either<ChannelException, FailureMessage>) = when (failure) {
        is Either.Left -> false
        is Either.Right -> when (failure.value) {
            is UnknownNextPeer -> true
            is ChannelDisabled -> true
            is TemporaryChannelFailure -> true
            is PermanentChannelFailure -> true
            is TemporaryNodeFailure -> true
            is PermanentNodeFailure -> true
            else -> false
        }
    }

    private fun isRejectedByRecipient(failure: Either<ChannelException, FailureMessage>) = when (failure) {
        is Either.Left -> false
        is Either.Right -> when (failure.value) {
            is IncorrectOrUnknownPaymentDetails -> true
            else -> false
        }
    }

    /** A simple summary of the problem, designed for general users. */
    fun message(): String {
        // We strive to provide a message that would lead a general user toward solving their own problem.
        // And we must not assume any knowledge of the lightning protocol.
        //
        // A concrete example:
        // - the user has 2 channels
        // - one channel fails with HtlcValueTooHighInFlight
        // - the remaining channel contains an insufficient balance to make the payment
        //
        // So the UI should say:
        // - message = Not enough funds in wallet ...
        // - details = HtlcValueTooHighInFlight ...
        //
        // The general user will see "not enough funds in wallet", and some gobbledygook.
        // It may be a bit confusing, but it leads them toward the solution: add funds, retry payment, success!
        return when {
            failures.any { isRejectedByRecipient(it) } -> "Payment rejected by the recipient. This usually occurs when the invoice has already been paid or when it contains an expiration date, and you attempted to send a payment after the expiration."
            failures.any { isRouteError(it) } -> FinalFailure.NoRouteToRecipient.message
            else -> reason.message
        }
    }

    /**
     * A detailed summary of the all internal errors.
     * This is targeted at users with technical knowledge of the lightning protocol.
     */
    fun details(): String = failures.foldIndexed("", { index, msg, problem ->
        msg + "${index + 1}: ${problem.fold({ ex -> ex.message }, { f -> f.message })}\n"
    })

}
