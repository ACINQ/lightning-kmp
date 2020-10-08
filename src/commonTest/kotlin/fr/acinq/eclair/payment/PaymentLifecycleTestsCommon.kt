package fr.acinq.eclair.payment

import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.Crypto
import fr.acinq.eclair.*
import fr.acinq.eclair.channel.Normal
import fr.acinq.eclair.channel.TestsHelper
import fr.acinq.eclair.io.SendPayment
import fr.acinq.eclair.tests.utils.EclairTestSuite
import fr.acinq.eclair.utils.*
import fr.acinq.eclair.wire.ChannelUpdate
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PaymentLifecycleTestsCommon : EclairTestSuite() {

    private fun makeSendPayment(
        payee: Normal,
        amount: MilliSatoshi?,
        timestamp: Long = currentTimestampSeconds(),
        expirySeconds: Long? = null,
    ): SendPayment {

        val paymentPreimage: ByteVector32 = Eclair.randomBytes32()
        val paymentHash = Crypto.sha256(paymentPreimage).toByteVector32()

        val invoiceFeatures = setOf(
            ActivatedFeature(Feature.VariableLengthOnion, FeatureSupport.Optional),
            ActivatedFeature(Feature.PaymentSecret, FeatureSupport.Mandatory),
            ActivatedFeature(Feature.BasicMultiPartPayment, FeatureSupport.Optional)
        )
        val paymentRequest = PaymentRequest.create(
            chainHash = payee.staticParams.nodeParams.chainHash,
            amount = amount,
            paymentHash = paymentHash,
            privateKey = payee.staticParams.nodeParams.nodePrivateKey, // Payee creates invoice, sends to payer
            description = "unit test",
            minFinalCltvExpiryDelta = PaymentRequest.DEFAULT_MIN_FINAL_EXPIRY_DELTA,
            features = Features(invoiceFeatures),
            timestamp = timestamp,
            expirySeconds = expirySeconds
        )

        return SendPayment(paymentId = UUID.randomUUID(), paymentRequest = paymentRequest)
    }

    private fun expectedTrampolineFees(
        targetAmount: MilliSatoshi,
        channelUpdate: ChannelUpdate,
        schedule: PaymentLifecycle.PaymentAdjustmentSchedule
    ): MilliSatoshi {

        return Eclair.nodeFee(channelUpdate.feeBaseMsat, channelUpdate.feeProportionalMillionths, targetAmount) +
            schedule.feeBaseSat.toMilliSatoshi() +
            (targetAmount * schedule.feePercent)
    }

    @Test
    fun `PaymentAttempt calculations - with room for fees`() {

        val (alice, bob) = TestsHelper.reachNormal()

        val availableForSend = 1_000_000.sat.toMilliSatoshi()
        val targetAmount = 500_000.sat.toMilliSatoshi() // plenty of room for targetAmount & fees

        val sendPayment = makeSendPayment(payee = bob, amount = targetAmount)
        val paymentAttempt = PaymentLifecycle.PaymentAttempt(sendPayment)

        var trampolinePaymentAttempt: PaymentLifecycle.TrampolinePaymentAttempt? = null
        for (schedule in PaymentLifecycle.PaymentAdjustmentSchedule.all()) {

            trampolinePaymentAttempt = if (trampolinePaymentAttempt == null) {
                paymentAttempt.add(
                    channelId = alice.channelId,
                    channelUpdate = alice.channelUpdate,
                    targetAmount = targetAmount,
                    availableForSend = availableForSend
                )
            } else {
                paymentAttempt.fail(
                    channelId = alice.channelId,
                    channelUpdate = alice.channelUpdate,
                    availableForSend = availableForSend
                )
            }

            assertNotNull(trampolinePaymentAttempt)
            assertTrue { trampolinePaymentAttempt.nextAmount == targetAmount }

            val expectedFees = expectedTrampolineFees(targetAmount, alice.channelUpdate, schedule)
            assertTrue { trampolinePaymentAttempt.amount == (targetAmount + expectedFees) }
        }
    }

    @Test
    fun `PaymentAttempt calculations - with room for fees if we reduce targetAmount`() {

        val (alice, bob) = TestsHelper.reachNormal()

        val availableForSend = 500_000.sat.toMilliSatoshi()
        val targetAmount = 500_000.sat.toMilliSatoshi() // channel has room for a payment, but less than target

        // We are asking to send a payment of 500_000 sats on a channel with a cap of 500_000.
        // So we cannot send the targetAmount.
        // But we can still send something on this channel. And we can max it out.

        val sendPayment = makeSendPayment(payee = bob, amount = targetAmount)
        val paymentAttempt = PaymentLifecycle.PaymentAttempt(sendPayment)

        val trampolinePaymentAttempt = paymentAttempt.add(
            channelId = alice.channelId,
            channelUpdate = alice.channelUpdate,
            targetAmount = targetAmount,
            availableForSend = availableForSend
        )

        assertNotNull(trampolinePaymentAttempt)
        assertTrue { trampolinePaymentAttempt.amount == availableForSend } // maxed out channel

        val schedule = PaymentLifecycle.PaymentAdjustmentSchedule.get(0)!!
        val expectedFees = expectedTrampolineFees(trampolinePaymentAttempt.nextAmount, alice.channelUpdate, schedule)

        assertTrue { trampolinePaymentAttempt.nextAmount == (availableForSend - expectedFees) }
    }

    @Test
    fun `PaymentAttempt calculations - no room for payment after fees`() {

        val (alice, bob) = TestsHelper.reachNormal()

        val availableForSend = 546_000.msat
        val targetAmount = 500_000_000.msat

        // There won't be enough room in the channel to send anything.
        // Because, once the fees are taken into account, there's no room for anything else.

        val sendPayment = makeSendPayment(payee = bob, amount = targetAmount)
        val paymentAttempt = PaymentLifecycle.PaymentAttempt(sendPayment)

        val trampolinePaymentAttempt = paymentAttempt.add(
            channelId = alice.channelId,
            channelUpdate = alice.channelUpdate,
            targetAmount = targetAmount,
            availableForSend = availableForSend
        )

        assertNull(trampolinePaymentAttempt)
    }
}