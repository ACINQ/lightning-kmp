package fr.acinq.eclair.payment

import fr.acinq.eclair.channel.ChannelException
import fr.acinq.eclair.db.OutgoingPayment
import fr.acinq.eclair.utils.Either
import fr.acinq.eclair.utils.currentTimestampMillis
import fr.acinq.eclair.wire.*

/** A fatal failure that stops payment attempts. */
sealed class FinalFailure {

    /** Use this function when no payment attempts have been made (e.g. when a precondition failed). */
    fun toPaymentFailure(): OutgoingPaymentFailure = OutgoingPaymentFailure(this, listOf<OutgoingPayment.Part.Status.Failed>())

    // @formatter:off
    object InvalidPaymentAmount : FinalFailure() { override fun toString(): String = "payment amount must be positive" }
    object InvalidPaymentId : FinalFailure() { override fun toString(): String = "payment ID must be unique" }
    object NoAvailableChannels : FinalFailure() { override fun toString(): String = "no channels available to send payment" }
    object InsufficientBalance : FinalFailure() { override fun toString(): String = "not enough funds in wallet to afford payment" }
    object NoRouteToRecipient : FinalFailure() { override fun toString(): String = "unable to route payment to recipient" }
    object RetryExhausted: FinalFailure() { override fun toString(): String = "payment attempts exhausted without success" }
    object WalletRestarted: FinalFailure() { override fun toString(): String = "wallet restarted while a payment was ongoing" }
    object UnknownError : FinalFailure() { override fun toString(): String = "an unknown error occurred" }
    // @formatter:on
}

data class OutgoingPaymentFailure(val reason: FinalFailure, val failures: List<OutgoingPayment.Part.Status.Failed>) {
    constructor(reason: FinalFailure, failures: List<Either<ChannelException, FailureMessage>>, completedAt: Long = currentTimestampMillis()) : this(reason, failures.map { convertFailure(it, completedAt) })

    /**
     * A detailed summary of the all internal errors.
     * This is targeted at users with technical knowledge of the lightning protocol.
     */
    fun details(): String = failures.foldIndexed("", { index, msg, problem -> msg + "${index + 1}: ${problem.details}\n" })

    companion object {
        fun convertFailure(failure: Either<ChannelException, FailureMessage>, completedAt: Long = currentTimestampMillis()): OutgoingPayment.Part.Status.Failed = when (failure) {
            is Either.Left -> OutgoingPayment.Part.Status.Failed(null, failure.value.details(), completedAt)
            is Either.Right -> OutgoingPayment.Part.Status.Failed(failure.value.code, failure.value.message, completedAt)
        }

        fun isRouteError(failure: OutgoingPayment.Part.Status.Failed) = when (failure.remoteFailureCode) {
            UnknownNextPeer.code -> true
            ChannelDisabled.code -> true
            TemporaryChannelFailure.code -> true
            PermanentChannelFailure.code -> true
            TemporaryNodeFailure.code -> true
            PermanentNodeFailure.code -> true
            else -> false
        }

        fun isRejectedByRecipient(failure: OutgoingPayment.Part.Status.Failed) = when (failure.remoteFailureCode) {
            IncorrectOrUnknownPaymentDetails.code -> true
            else -> false
        }
    }
}
