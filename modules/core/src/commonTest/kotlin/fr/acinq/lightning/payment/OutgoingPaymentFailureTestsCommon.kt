package fr.acinq.lightning.payment

import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.Block
import fr.acinq.bitcoin.utils.Either
import fr.acinq.lightning.CltvExpiryDelta
import fr.acinq.lightning.Lightning
import fr.acinq.lightning.ShortChannelId
import fr.acinq.lightning.channel.TooManyAcceptedHtlcs
import fr.acinq.lightning.db.LightningOutgoingPayment
import fr.acinq.lightning.tests.utils.LightningTestSuite
import fr.acinq.lightning.utils.msat
import fr.acinq.lightning.wire.ChannelUpdate
import fr.acinq.lightning.wire.FeeInsufficient
import fr.acinq.lightning.wire.IncorrectOrUnknownPaymentDetails
import fr.acinq.lightning.wire.PaymentTimeout
import fr.acinq.lightning.wire.TemporaryChannelFailure
import fr.acinq.lightning.wire.TemporaryNodeFailure
import fr.acinq.lightning.wire.TrampolineFeeInsufficient
import fr.acinq.lightning.wire.UnknownNextPeer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertIsNot

class OutgoingPaymentFailureTestsCommon : LightningTestSuite() {

    private val channelUpdate = ChannelUpdate(
        signature = Lightning.randomBytes64(),
        chainHash = Block.RegtestGenesisBlock.hash,
        shortChannelId = ShortChannelId(12345),
        timestampSeconds = 1234567,
        cltvExpiryDelta = CltvExpiryDelta(100),
        messageFlags = 0,
        channelFlags = 1,
        htlcMinimumMsat = 1000.msat,
        feeBaseMsat = 12.msat,
        feeProportionalMillionths = 76,
        htlcMaximumMsat = null
    )

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
    fun `categorize final failures`() {
        val testCases = listOf(
            FinalFailure.AlreadyInProgress to PaymentFailureCategory.LocalValidation,
            FinalFailure.AlreadyPaid to PaymentFailureCategory.LocalValidation,
            FinalFailure.InvalidPaymentAmount to PaymentFailureCategory.LocalValidation,
            FinalFailure.FeaturesNotSupported to PaymentFailureCategory.LocalValidation,
            FinalFailure.InvalidPaymentId to PaymentFailureCategory.LocalValidation,
            FinalFailure.ChannelNotConnected to PaymentFailureCategory.LocalChannel,
            FinalFailure.ChannelOpening to PaymentFailureCategory.LocalChannel,
            FinalFailure.ChannelClosing to PaymentFailureCategory.LocalChannel,
            FinalFailure.NoAvailableChannels to PaymentFailureCategory.LocalChannel,
            FinalFailure.InsufficientBalance to PaymentFailureCategory.LocalBalance,
            FinalFailure.RecipientUnreachable to PaymentFailureCategory.Recipient,
            FinalFailure.RetryExhausted to PaymentFailureCategory.Retry,
            FinalFailure.WalletRestarted to PaymentFailureCategory.Retry,
            FinalFailure.UnknownError to PaymentFailureCategory.Unknown,
        )
        testCases.forEach { (failure, category) ->
            assertEquals(category, failure.category)
        }
    }

    @Test
    fun `categorize payment part failures`() {
        val testCases = listOf(
            LightningOutgoingPayment.Part.Status.Failed.Failure.PaymentAmountTooSmall to PaymentFailureCategory.LocalValidation,
            LightningOutgoingPayment.Part.Status.Failed.Failure.PaymentAmountTooBig to PaymentFailureCategory.LocalValidation,
            LightningOutgoingPayment.Part.Status.Failed.Failure.NotEnoughFunds to PaymentFailureCategory.LocalBalance,
            LightningOutgoingPayment.Part.Status.Failed.Failure.NotEnoughFees to PaymentFailureCategory.Fee,
            LightningOutgoingPayment.Part.Status.Failed.Failure.PaymentExpiryTooBig to PaymentFailureCategory.Cltv,
            LightningOutgoingPayment.Part.Status.Failed.Failure.TooManyPendingPayments to PaymentFailureCategory.Retry,
            LightningOutgoingPayment.Part.Status.Failed.Failure.ChannelIsSplicing to PaymentFailureCategory.LocalChannel,
            LightningOutgoingPayment.Part.Status.Failed.Failure.ChannelIsClosing to PaymentFailureCategory.LocalChannel,
            LightningOutgoingPayment.Part.Status.Failed.Failure.TemporaryRemoteFailure to PaymentFailureCategory.Remote,
            LightningOutgoingPayment.Part.Status.Failed.Failure.RecipientLiquidityIssue to PaymentFailureCategory.Liquidity,
            LightningOutgoingPayment.Part.Status.Failed.Failure.RecipientIsOffline to PaymentFailureCategory.Recipient,
            LightningOutgoingPayment.Part.Status.Failed.Failure.RecipientRejectedPayment to PaymentFailureCategory.Recipient,
            LightningOutgoingPayment.Part.Status.Failed.Failure.Uninterpretable("unknown failure") to PaymentFailureCategory.Unknown,
        )
        testCases.forEach { (failure, category) ->
            assertEquals(category, failure.category)
        }
    }

    @Test
    fun `categorize outgoing payment failures using explanation`() {
        val testCases = listOf(
            OutgoingPaymentFailure(FinalFailure.NoAvailableChannels, listOf(Either.Right(TrampolineFeeInsufficient))) to PaymentFailureCategory.Fee,
            OutgoingPaymentFailure(FinalFailure.NoAvailableChannels, listOf(Either.Right(FeeInsufficient(10_000.msat, channelUpdate)))) to PaymentFailureCategory.Fee,
            OutgoingPaymentFailure(FinalFailure.NoAvailableChannels, listOf(Either.Right(TemporaryChannelFailure(channelUpdate)))) to PaymentFailureCategory.Remote,
            OutgoingPaymentFailure(FinalFailure.NoAvailableChannels, listOf(Either.Right(UnknownNextPeer))) to PaymentFailureCategory.Recipient,
            OutgoingPaymentFailure(FinalFailure.NoAvailableChannels, listOf(Either.Right(PaymentTimeout))) to PaymentFailureCategory.Liquidity,
            FinalFailure.RetryExhausted.toPaymentFailure() to PaymentFailureCategory.Retry,
            FinalFailure.InsufficientBalance.toPaymentFailure() to PaymentFailureCategory.LocalBalance,
        )
        testCases.forEach { (failure, category) ->
            assertEquals(category, failure.category)
        }
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
