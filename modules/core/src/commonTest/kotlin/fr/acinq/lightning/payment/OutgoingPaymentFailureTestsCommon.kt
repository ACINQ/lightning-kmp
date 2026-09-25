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
            FinalFailure.AlreadyPaid to PaymentFailureCategory.LocalFatal,
            FinalFailure.InvalidPaymentAmount to PaymentFailureCategory.LocalFatal,
            FinalFailure.FeaturesNotSupported to PaymentFailureCategory.LocalFatal,
            FinalFailure.InvalidPaymentId to PaymentFailureCategory.LocalFatal,
            FinalFailure.AlreadyInProgress to PaymentFailureCategory.LocalTransient,
            FinalFailure.ChannelNotConnected to PaymentFailureCategory.LocalTransient,
            FinalFailure.ChannelOpening to PaymentFailureCategory.LocalTransient,
            FinalFailure.ChannelClosing to PaymentFailureCategory.LocalTransient,
            FinalFailure.NoAvailableChannels to PaymentFailureCategory.LocalTransient,
            FinalFailure.InsufficientBalance to PaymentFailureCategory.LocalTransient,
            FinalFailure.RetryExhausted to PaymentFailureCategory.LocalTransient,
            FinalFailure.WalletRestarted to PaymentFailureCategory.LocalTransient,
            FinalFailure.RecipientUnreachable to PaymentFailureCategory.InflightTransient,
            FinalFailure.UnknownError to PaymentFailureCategory.Unknown,
        )
        testCases.forEach { (failure, category) ->
            assertEquals(category, failure.category)
        }
    }

    @Test
    fun `categorize payment part failures`() {
        val testCases = listOf(
            LightningOutgoingPayment.Part.Status.Failed.Failure.PaymentAmountTooSmall to PaymentFailureCategory.LocalFatal,
            LightningOutgoingPayment.Part.Status.Failed.Failure.PaymentAmountTooBig to PaymentFailureCategory.LocalFatal,
            LightningOutgoingPayment.Part.Status.Failed.Failure.PaymentExpiryTooBig to PaymentFailureCategory.LocalFatal,
            LightningOutgoingPayment.Part.Status.Failed.Failure.NotEnoughFunds to PaymentFailureCategory.LocalTransient,
            LightningOutgoingPayment.Part.Status.Failed.Failure.TooManyPendingPayments to PaymentFailureCategory.LocalTransient,
            LightningOutgoingPayment.Part.Status.Failed.Failure.ChannelIsSplicing to PaymentFailureCategory.LocalTransient,
            LightningOutgoingPayment.Part.Status.Failed.Failure.ChannelIsClosing to PaymentFailureCategory.LocalTransient,
            LightningOutgoingPayment.Part.Status.Failed.Failure.NotEnoughFees to PaymentFailureCategory.NotEnoughFee,
            LightningOutgoingPayment.Part.Status.Failed.Failure.TemporaryRemoteFailure to PaymentFailureCategory.InflightTransient,
            LightningOutgoingPayment.Part.Status.Failed.Failure.RecipientLiquidityIssue to PaymentFailureCategory.InflightTransient,
            LightningOutgoingPayment.Part.Status.Failed.Failure.RecipientIsOffline to PaymentFailureCategory.InflightTransient,
            LightningOutgoingPayment.Part.Status.Failed.Failure.RecipientRejectedPayment to PaymentFailureCategory.RemoteFatal,
            LightningOutgoingPayment.Part.Status.Failed.Failure.Uninterpretable("unknown failure") to PaymentFailureCategory.Unknown,
        )
        testCases.forEach { (failure, category) ->
            assertEquals(category, failure.category)
        }
    }

    @Test
    fun `categorize outgoing payment failures using explanation`() {
        val testCases = listOf(
            OutgoingPaymentFailure(FinalFailure.NoAvailableChannels, listOf(Either.Right(TrampolineFeeInsufficient))) to PaymentFailureCategory.NotEnoughFee,
            OutgoingPaymentFailure(FinalFailure.NoAvailableChannels, listOf(Either.Right(FeeInsufficient(10_000.msat, channelUpdate)))) to PaymentFailureCategory.NotEnoughFee,
            OutgoingPaymentFailure(FinalFailure.NoAvailableChannels, listOf(Either.Right(TemporaryChannelFailure(channelUpdate)))) to PaymentFailureCategory.InflightTransient,
            OutgoingPaymentFailure(FinalFailure.NoAvailableChannels, listOf(Either.Right(UnknownNextPeer))) to PaymentFailureCategory.InflightTransient,
            OutgoingPaymentFailure(FinalFailure.NoAvailableChannels, listOf(Either.Right(PaymentTimeout))) to PaymentFailureCategory.InflightTransient,
            OutgoingPaymentFailure(FinalFailure.NoAvailableChannels, listOf(Either.Right(IncorrectOrUnknownPaymentDetails(100_000.msat, 150)))) to PaymentFailureCategory.RemoteFatal,
            FinalFailure.RetryExhausted.toPaymentFailure() to PaymentFailureCategory.LocalTransient,
            FinalFailure.InsufficientBalance.toPaymentFailure() to PaymentFailureCategory.LocalTransient,
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
