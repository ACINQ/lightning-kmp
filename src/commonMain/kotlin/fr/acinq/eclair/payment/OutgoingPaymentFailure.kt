package fr.acinq.eclair.payment

import fr.acinq.eclair.channel.ChannelException
import fr.acinq.eclair.utils.Either
import fr.acinq.eclair.wire.*

data class OutgoingPaymentFailure(val reason: Reason, val message: String, val problems: List<Either<ChannelException, FailureMessage>>) {
    /**
     * Simple error types designed for general users.
     * Anything non-simple goes into the OTHER_ERROR category, and we rely on the problems list for details.
     */
    enum class Reason {
        INVALID_PARAMETER, // e.g. (paymentAmount < 0), (recycled paymentId)
        NO_AVAILABLE_CHANNELS, // Zero channels in Normal mode (excluding failed channels)
        INSUFFICIENT_BALANCE_BASE, // Not enough capacity in channel(s) to support payment
        INSUFFICIENT_BALANCE_FEES, // Not enough capacity, after fees are taken into account
        NO_ROUTE_TO_RECIPIENT, // Trampoline was unable to find an acceptable route
        OTHER_ERROR, // Some other error occurred
    }

    companion object {
        fun make(reason: Reason, problems: List<Either<ChannelException, FailureMessage>>): OutgoingPaymentFailure {
            val message = when (reason) {
                Reason.INVALID_PARAMETER -> "Invalid parameter"
                Reason.NO_AVAILABLE_CHANNELS -> "No channels available to send payment. Check internet connection or ensure you have an available balance."
                Reason.INSUFFICIENT_BALANCE_BASE -> "Not enough funds in wallet to afford payment"
                Reason.INSUFFICIENT_BALANCE_FEES -> "Not enough funds in wallet, after fees are taken into account."
                Reason.NO_ROUTE_TO_RECIPIENT -> "Unable to route payment to recipient"
                Reason.OTHER_ERROR -> "Unknown error occurred"
            }
            return OutgoingPaymentFailure(reason, message, problems)
        }
    }

    object InvalidParameter {
        fun paymentAmount() = OutgoingPaymentFailure(Reason.INVALID_PARAMETER, "Invalid parameter: payment amount must be positive", listOf())
        fun paymentId() = OutgoingPaymentFailure(Reason.INVALID_PARAMETER, "Invalid parameter: paymentId must be unique", listOf())
    }

    /** A simple summary of the problem, designed for general users. */
    @Suppress("unused")
    fun basicSummary(): String {
        // We strive to provide a message that would lead a general user toward solving their own problem.
        // And we must not assume any knowledge of the lightning protocol.
        //
        // A concrete example:
        // - the user has 2 channels
        // - one channel fails with HtlcValueTooHighInFlight
        // - the remaining channel contains an insufficient balance to make the payment
        //
        // So the UI should say:
        // - basic = Not enough funds in wallet ...
        // - details = HtlcValueTooHighInFlight ...
        //
        // The general user will see "not enough funds in wallet", and some gobbledygook.
        // It may be a bit confusing, but it leads them toward the solution
        // => add funds, retry payment, success!

        fun isRouteError(problem: Either<ChannelException, FailureMessage>) = when (problem) {
            is Either.Left -> false
            is Either.Right -> when (problem.value) {
                is UnknownNextPeer -> true
                is TemporaryChannelFailure -> true
                is PermanentChannelFailure -> true
                is TemporaryNodeFailure -> true
                is PermanentNodeFailure -> true
                else -> false
            }
        }

        if (reason == Reason.OTHER_ERROR) {
            if (problems.any { isRouteError(it) }) {
                return "Unable to route payment to recipient"
            }
            if (problems.any { (it is Either.Right) && (it.value is IncorrectOrUnknownPaymentDetails) }) {
                return "Payment rejected by the recipient. " +
                        "This usually occurs when the invoice has already been paid or when it contains an expiration date, " +
                        "and you attempted to send a payment after the expiration."
            }
        }

        return message
    }

    /**
     * A detailed summary of the ChannelExceptions and/or FailureMessages that occurred.
     * This is targeted at users with technical knowledge of the lightning protocol.
     */
    @Suppress("unused")
    fun detailedSummary(): String = problems.foldIndexed("", { index, msg, problem ->
        msg + "$index: ${problem.fold({ ex -> ex.message }, { f -> f.message })}\n"
    })

}
