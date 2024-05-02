package fr.acinq.lightning.payment

import fr.acinq.bitcoin.utils.Either
import fr.acinq.lightning.MilliSatoshi
import fr.acinq.lightning.channel.ChannelException
import fr.acinq.lightning.db.LightningOutgoingPayment
import fr.acinq.lightning.utils.currentTimestampMillis
import fr.acinq.lightning.wire.*

/** A fatal failure that stops payment attempts. */
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
    data object NoAvailableChannels : FinalFailure() { override fun toString(): String = "no channels available to send payment, please retry later" }
    data class InsufficientBalance(val available: MilliSatoshi) : FinalFailure() { override fun toString(): String = "not enough funds in wallet to afford payment (available = $available)" }
    data object NoRouteToRecipient : FinalFailure() { override fun toString(): String = "unable to route payment to recipient" }
    data object RecipientUnreachable : FinalFailure() { override fun toString(): String = "the recipient was offline or did not have enough liquidity to receive the payment" }
    data object RetryExhausted: FinalFailure() { override fun toString(): String = "payment attempts exhausted without success" }
    data object WalletRestarted: FinalFailure() { override fun toString(): String = "wallet restarted while a payment was ongoing" }
    data object UnknownError : FinalFailure() { override fun toString(): String = "an unknown error occurred" }
    // @formatter:on
}

data class OutgoingPaymentFailure(val reason: FinalFailure, val failures: List<LightningOutgoingPayment.Part.Status.Failed>) {
    constructor(reason: FinalFailure, failures: List<Either<ChannelException, FailureMessage>>, completedAt: Long = currentTimestampMillis()) : this(reason, failures.map { convertFailure(it, completedAt) })

    /**
     * A detailed summary of the all internal errors.
     * This is targeted at users with technical knowledge of the lightning protocol.
     */
    fun details(): String = failures.foldIndexed("") { index, msg, problem -> msg + "${index + 1}: ${problem.details}\n" }

    companion object {
        fun convertFailure(failure: Either<ChannelException, FailureMessage>, completedAt: Long = currentTimestampMillis()): LightningOutgoingPayment.Part.Status.Failed = when (failure) {
            is Either.Left -> LightningOutgoingPayment.Part.Status.Failed(null, failure.value.details(), completedAt)
            is Either.Right -> LightningOutgoingPayment.Part.Status.Failed(failure.value.code, failure.value.message, completedAt)
        }

        fun isRouteError(failure: LightningOutgoingPayment.Part.Status.Failed) = when (failure.remoteFailureCode) {
            UnknownNextPeer.code -> true
            ChannelDisabled.code -> true
            TemporaryChannelFailure.code -> true
            PermanentChannelFailure.code -> true
            TemporaryNodeFailure.code -> true
            PermanentNodeFailure.code -> true
            else -> false
        }

        fun isRejectedByRecipient(failure: LightningOutgoingPayment.Part.Status.Failed) = when (failure.remoteFailureCode) {
            IncorrectOrUnknownPaymentDetails.code -> true
            else -> false
        }
    }
}
