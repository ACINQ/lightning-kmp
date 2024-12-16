package fr.acinq.lightning.payment

import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.utils.Either
import fr.acinq.lightning.channel.TooManyAcceptedHtlcs
import fr.acinq.lightning.db.LightningOutgoingPayment
import fr.acinq.lightning.tests.utils.LightningTestSuite
import fr.acinq.lightning.utils.msat
import fr.acinq.lightning.wire.IncorrectOrUnknownPaymentDetails
import fr.acinq.lightning.wire.PaymentTimeout
import fr.acinq.lightning.wire.TemporaryNodeFailure
import fr.acinq.lightning.wire.UnknownNextPeer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertIsNot

class OutgoingPaymentFailureTestsCommon : LightningTestSuite() {

    @Test
    fun `identify common route failures`() {
        val failure = OutgoingPaymentFailure(
            FinalFailure.InsufficientBalance,
            listOf(
                Either.Right(TemporaryNodeFailure),
                Either.Right(UnknownNextPeer),
                Either.Left(TooManyAcceptedHtlcs(ByteVector32.Zeroes, 42))
            )
        )
        assertIs<LightningOutgoingPayment.Part.Status.Failed.Failure.RouteFailure>(failure.failures[0].failure)
        assertIs<LightningOutgoingPayment.Part.Status.Failed.Failure.RouteFailure>(failure.failures[1].failure)
        assertIsNot<LightningOutgoingPayment.Part.Status.Failed.Failure.RouteFailure>(failure.failures[2].failure)
    }

    @Test
    fun `identify recipient failures`() {
        val failure = OutgoingPaymentFailure(
            FinalFailure.UnknownError,
            listOf(
                Either.Left(TooManyAcceptedHtlcs(ByteVector32.Zeroes, 42)),
                Either.Right(PaymentTimeout),
                Either.Right(IncorrectOrUnknownPaymentDetails(100_000.msat, 150))
            )
        )
        assertIsNot<LightningOutgoingPayment.Part.Status.Failed.Failure.RecipientRejectedPayment>(failure.failures[0].failure)
        assertIsNot<LightningOutgoingPayment.Part.Status.Failed.Failure.RecipientRejectedPayment>(failure.failures[1].failure)
        assertIs<LightningOutgoingPayment.Part.Status.Failed.Failure.RecipientRejectedPayment>(failure.failures[2].failure)
    }

    @Test
    fun `explain failures`() {
        val failure = OutgoingPaymentFailure(
            FinalFailure.NoAvailableChannels,
            listOf(
                Either.Left(TooManyAcceptedHtlcs(ByteVector32.Zeroes, 42)),
                Either.Right(PaymentTimeout),
            )
        )
        assertEquals(Either.Left(LightningOutgoingPayment.Part.Status.Failed.Failure.RecipientLiquidityIssue), failure.explain())
    }

    @Test
    fun `prints technical details`() {
        val failure = OutgoingPaymentFailure(
            FinalFailure.InsufficientBalance,
            listOf(
                Either.Right(TemporaryNodeFailure),
                Either.Right(UnknownNextPeer),
                Either.Left(TooManyAcceptedHtlcs(ByteVector32.Zeroes, 42))
            )
        )
        val expected = "1: a node in the route had a temporary failure\n" +
                "2: recipient node is offline or unreachable\n" +
                "3: too many pending payments\n"
        assertEquals(failure.details(), expected)
    }

}