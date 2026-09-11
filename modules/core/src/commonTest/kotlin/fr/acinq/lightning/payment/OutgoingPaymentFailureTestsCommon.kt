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
            FinalFailure.AlreadyInProgress to OutgoingPaymentFailure.Category.LocalValidation,
            FinalFailure.AlreadyPaid to OutgoingPaymentFailure.Category.LocalValidation,
            FinalFailure.InvalidPaymentAmount to OutgoingPaymentFailure.Category.LocalValidation,
            FinalFailure.FeaturesNotSupported to OutgoingPaymentFailure.Category.LocalValidation,
            FinalFailure.InvalidPaymentId to OutgoingPaymentFailure.Category.LocalValidation,
            FinalFailure.ChannelNotConnected to OutgoingPaymentFailure.Category.LocalChannel,
            FinalFailure.ChannelOpening to OutgoingPaymentFailure.Category.LocalChannel,
            FinalFailure.ChannelClosing to OutgoingPaymentFailure.Category.LocalChannel,
            FinalFailure.NoAvailableChannels to OutgoingPaymentFailure.Category.LocalChannel,
            FinalFailure.InsufficientBalance to OutgoingPaymentFailure.Category.LocalBalance,
            FinalFailure.RecipientUnreachable to OutgoingPaymentFailure.Category.Recipient,
            FinalFailure.RetryExhausted to OutgoingPaymentFailure.Category.Retry,
            FinalFailure.WalletRestarted to OutgoingPaymentFailure.Category.Retry,
            FinalFailure.UnknownError to OutgoingPaymentFailure.Category.Unknown,
        )
        testCases.forEach { (failure, category) ->
            assertEquals(category, failure.category)
        }
    }

    @Test
    fun `categorize payment part failures`() {
        val testCases = listOf(
            LightningOutgoingPayment.Part.Status.Failed.Failure.PaymentAmountTooSmall to OutgoingPaymentFailure.Category.LocalValidation,
            LightningOutgoingPayment.Part.Status.Failed.Failure.PaymentAmountTooBig to OutgoingPaymentFailure.Category.LocalValidation,
            LightningOutgoingPayment.Part.Status.Failed.Failure.NotEnoughFunds to OutgoingPaymentFailure.Category.LocalBalance,
            LightningOutgoingPayment.Part.Status.Failed.Failure.NotEnoughFees to OutgoingPaymentFailure.Category.Fee,
            LightningOutgoingPayment.Part.Status.Failed.Failure.PaymentExpiryTooBig to OutgoingPaymentFailure.Category.Cltv,
            LightningOutgoingPayment.Part.Status.Failed.Failure.TooManyPendingPayments to OutgoingPaymentFailure.Category.Retry,
            LightningOutgoingPayment.Part.Status.Failed.Failure.ChannelIsSplicing to OutgoingPaymentFailure.Category.LocalChannel,
            LightningOutgoingPayment.Part.Status.Failed.Failure.ChannelIsClosing to OutgoingPaymentFailure.Category.LocalChannel,
            LightningOutgoingPayment.Part.Status.Failed.Failure.TemporaryRemoteFailure to OutgoingPaymentFailure.Category.Remote,
            LightningOutgoingPayment.Part.Status.Failed.Failure.RecipientLiquidityIssue to OutgoingPaymentFailure.Category.Liquidity,
            LightningOutgoingPayment.Part.Status.Failed.Failure.RecipientIsOffline to OutgoingPaymentFailure.Category.Recipient,
            LightningOutgoingPayment.Part.Status.Failed.Failure.RecipientRejectedPayment to OutgoingPaymentFailure.Category.Recipient,
            LightningOutgoingPayment.Part.Status.Failed.Failure.Uninterpretable("unknown failure") to OutgoingPaymentFailure.Category.Unknown,
        )
        testCases.forEach { (failure, category) ->
            assertEquals(category, failure.category)
        }
    }

    @Test
    fun `categorize outgoing payment failures using explanation`() {
        val testCases = listOf(
            OutgoingPaymentFailure(FinalFailure.NoAvailableChannels, listOf(Either.Right(TrampolineFeeInsufficient))) to OutgoingPaymentFailure.Category.Fee,
            OutgoingPaymentFailure(FinalFailure.NoAvailableChannels, listOf(Either.Right(FeeInsufficient(10_000.msat, channelUpdate)))) to OutgoingPaymentFailure.Category.Fee,
            OutgoingPaymentFailure(FinalFailure.NoAvailableChannels, listOf(Either.Right(TemporaryChannelFailure(channelUpdate)))) to OutgoingPaymentFailure.Category.Remote,
            OutgoingPaymentFailure(FinalFailure.NoAvailableChannels, listOf(Either.Right(UnknownNextPeer))) to OutgoingPaymentFailure.Category.Recipient,
            OutgoingPaymentFailure(FinalFailure.NoAvailableChannels, listOf(Either.Right(PaymentTimeout))) to OutgoingPaymentFailure.Category.Liquidity,
            FinalFailure.RetryExhausted.toPaymentFailure() to OutgoingPaymentFailure.Category.Retry,
            FinalFailure.InsufficientBalance.toPaymentFailure() to OutgoingPaymentFailure.Category.LocalBalance,
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
