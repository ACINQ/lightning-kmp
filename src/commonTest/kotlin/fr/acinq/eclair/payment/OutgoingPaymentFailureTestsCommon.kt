package fr.acinq.eclair.payment

import fr.acinq.bitcoin.ByteVector32
import fr.acinq.eclair.channel.TooManyAcceptedHtlcs
import fr.acinq.eclair.tests.utils.EclairTestSuite
import fr.acinq.eclair.utils.Either
import fr.acinq.eclair.wire.PaymentTimeout
import fr.acinq.eclair.wire.TemporaryNodeFailure
import fr.acinq.eclair.wire.UnknownNextPeer
import kotlin.test.Test
import kotlin.test.assertEquals

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
        assertEquals(failure.message(), "Unable to route payment to recipient.")
    }

    @Test
    fun `defaults to simple message`() {
        val failure = OutgoingPaymentFailure(FinalFailure.InsufficientBalance, listOf(Either.Right(PaymentTimeout)))
        assertEquals(failure.message(), "Not enough funds in wallet to afford payment.")
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