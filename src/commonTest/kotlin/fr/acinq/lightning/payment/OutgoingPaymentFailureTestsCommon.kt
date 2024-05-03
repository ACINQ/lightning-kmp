package fr.acinq.lightning.payment

import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.utils.Either
import fr.acinq.lightning.channel.TooManyAcceptedHtlcs
import fr.acinq.lightning.tests.utils.LightningTestSuite
import fr.acinq.lightning.utils.msat
import fr.acinq.lightning.wire.IncorrectOrUnknownPaymentDetails
import fr.acinq.lightning.wire.PaymentTimeout
import fr.acinq.lightning.wire.TemporaryNodeFailure
import fr.acinq.lightning.wire.UnknownNextPeer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OutgoingPaymentFailureTestsCommon : LightningTestSuite() {

    @Test
    fun `identify common route failures`() {
        val failure = OutgoingPaymentFailure(
            FinalFailure.InsufficientBalance(100.msat),
            listOf(
                Either.Right(TemporaryNodeFailure),
                Either.Right(UnknownNextPeer),
                Either.Left(TooManyAcceptedHtlcs(ByteVector32.Zeroes, 42))
            )
        )
        assertTrue(OutgoingPaymentFailure.isRouteError(failure.failures[0]))
        assertTrue(OutgoingPaymentFailure.isRouteError(failure.failures[1]))
        assertFalse(OutgoingPaymentFailure.isRouteError(failure.failures[2]))
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
        assertFalse(OutgoingPaymentFailure.isRejectedByRecipient(failure.failures[0]))
        assertFalse(OutgoingPaymentFailure.isRejectedByRecipient(failure.failures[1]))
        assertTrue(OutgoingPaymentFailure.isRejectedByRecipient(failure.failures[2]))
    }

    @Test
    fun `prints technical details`() {
        val failure = OutgoingPaymentFailure(
            FinalFailure.InsufficientBalance(500.msat),
            listOf(
                Either.Right(TemporaryNodeFailure),
                Either.Right(UnknownNextPeer),
                Either.Left(TooManyAcceptedHtlcs(ByteVector32.Zeroes, 42))
            )
        )
        val expected = "1: TemporaryRemoteFailure\n" +
                "2: RecipientIsOffline\n" +
                "3: TooManyPendingPayments\n"
        assertEquals(failure.details(), expected)
    }

}