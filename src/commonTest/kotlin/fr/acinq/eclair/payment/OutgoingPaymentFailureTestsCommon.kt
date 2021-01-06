package fr.acinq.eclair.payment

import fr.acinq.bitcoin.ByteVector32
import fr.acinq.eclair.channel.TooManyAcceptedHtlcs
import fr.acinq.eclair.tests.utils.EclairTestSuite
import fr.acinq.eclair.utils.Either
import fr.acinq.eclair.utils.msat
import fr.acinq.eclair.wire.IncorrectOrUnknownPaymentDetails
import fr.acinq.eclair.wire.PaymentTimeout
import fr.acinq.eclair.wire.TemporaryNodeFailure
import fr.acinq.eclair.wire.UnknownNextPeer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OutgoingPaymentFailureTestsCommon : EclairTestSuite() {

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
            FinalFailure.InsufficientBalance,
            listOf(
                Either.Right(TemporaryNodeFailure),
                Either.Right(UnknownNextPeer),
                Either.Left(TooManyAcceptedHtlcs(ByteVector32.Zeroes, 42))
            )
        )
        val expected = "1: general temporary failure of the processing node\n" +
                "2: processing node does not know the next peer in the route\n" +
                "3: too many accepted htlcs: maximum=42\n"
        assertEquals(failure.details(), expected)
    }

}