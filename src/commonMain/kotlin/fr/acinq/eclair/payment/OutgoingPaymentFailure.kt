package fr.acinq.eclair.payment

import fr.acinq.eclair.channel.ChannelException
import fr.acinq.eclair.utils.Either
import fr.acinq.eclair.wire.*

/**
 * Simple error types designed for general users.
 * Anything non-simple goes into the UnknownError category, and we rely on the inner failures list for details.
 */
sealed class FailureReason {
    abstract val message: String

    fun toPaymentFailure(): OutgoingPaymentFailure = OutgoingPaymentFailure(this, listOf())

    // @formatter:off
    object InvalidPaymentAmount : FailureReason() { override val message: String = "Invalid parameter: payment amount must be positive." }
    object InvalidPaymentId : FailureReason() { override val message: String = "Invalid parameter: payment ID must be unique." }
    object NoAvailableChannels : FailureReason() { override val message: String = "No channels available to send payment. Check internet connection or ensure you have an available balance." }
    object InsufficientBalanceBase : FailureReason() { override val message: String = "Not enough funds in wallet to afford payment." }
    object InsufficientBalanceFees : FailureReason() { override val message: String = "Not enough funds in wallet, after fees are taken into account." }
    object NoRouteToRecipient : FailureReason() { override val message: String = "Unable to route payment to recipient." }
    object UnknownError : FailureReason() { override val message: String = "An unknown error occurred." }
    // @formatter:on
}

data class OutgoingPaymentFailure(val reason: FailureReason, val failures: List<Either<ChannelException, FailureMessage>>) {

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

        fun isRouteError(failure: Either<ChannelException, FailureMessage>) = when (failure) {
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

        if (failures.any { isRouteError(it) }) {
            return FailureReason.NoRouteToRecipient.message
        }
        if (failures.any { (it is Either.Right) && (it.value is IncorrectOrUnknownPaymentDetails) }) {
            return "Payment rejected by the recipient. " +
                    "This usually occurs when the invoice has already been paid or when it contains an expiration date, " +
                    "and you attempted to send a payment after the expiration."
        }

        return reason.message
    }

    /**
     * A detailed summary of the all internal errors.
     * This is targeted at users with technical knowledge of the lightning protocol.
     */
    fun details(): String = failures.foldIndexed("", { index, msg, problem ->
        msg + "${index + 1}: ${problem.fold({ ex -> ex.message }, { f -> f.message })}\n"
    })

}
