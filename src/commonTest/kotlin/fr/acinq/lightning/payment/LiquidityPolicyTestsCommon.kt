package fr.acinq.lightning.payment

import fr.acinq.lightning.LiquidityEvents
import fr.acinq.lightning.logging.MDCLogger
import fr.acinq.lightning.tests.utils.LightningTestSuite
import fr.acinq.lightning.utils.msat
import fr.acinq.lightning.utils.sat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LiquidityPolicyTestsCommon : LightningTestSuite() {

    private val logger = MDCLogger(loggerFactory.newLogger(this::class))

    @Test
    fun `policy rejection`() {

        val policy = LiquidityPolicy.Auto(maxAbsoluteFee = 2_000.sat, maxRelativeFeeBasisPoints = 3_000 /* 3000 = 30 % */, skipAbsoluteFeeCheck = false, maxAllowedCredit = 0.sat)

        // fee over both absolute and relative
        assertEquals(
            expected = LiquidityEvents.Decision.Rejected.Reason.TooExpensive.OverRelativeFee(policy.maxRelativeFeeBasisPoints),
            actual = (policy.maybeReject(amount = 4_000_000.msat, fee = 3_000_000.msat, source = LiquidityEvents.Source.OffChainPayment, logger, currentFeeCredit = 0.sat) as? LiquidityEvents.Decision.Rejected)?.reason
        )

        // fee over absolute
        assertEquals(
            expected = LiquidityEvents.Decision.Rejected.Reason.TooExpensive.OverAbsoluteFee(policy.maxAbsoluteFee),
            actual = (policy.maybeReject(amount = 15_000_000.msat, fee = 3_000_000.msat, source = LiquidityEvents.Source.OffChainPayment, logger, currentFeeCredit = 0.sat) as? LiquidityEvents.Decision.Rejected)?.reason
        )

        // fee over relative
        assertEquals(
            expected = LiquidityEvents.Decision.Rejected.Reason.TooExpensive.OverRelativeFee(policy.maxRelativeFeeBasisPoints),
            actual = (policy.maybeReject(amount = 4_000_000.msat, fee = 2_000_000.msat, source = LiquidityEvents.Source.OffChainPayment, logger, currentFeeCredit = 0.sat) as? LiquidityEvents.Decision.Rejected)?.reason
        )

        assertEquals(
            expected = LiquidityEvents.Decision.Accepted(amount = 10_000_000.msat, fee = 2_000_000.msat, source = LiquidityEvents.Source.OffChainPayment),
            actual = policy.maybeReject(amount = 10_000_000.msat, fee = 2_000_000.msat, source = LiquidityEvents.Source.OffChainPayment, logger, currentFeeCredit = 0.sat))

    }

    @Test
    fun `policy rejection skip absolute check`() {

        val policy = LiquidityPolicy.Auto(maxAbsoluteFee = 1_000.sat, maxRelativeFeeBasisPoints = 5_000 /* 3000 = 30 % */, skipAbsoluteFeeCheck = true, maxAllowedCredit = 0.sat)

        // fee is over absolute, and it's an offchain payment so the check passes
        assertEquals(
            expected = LiquidityEvents.Decision.Accepted(amount = 4_000_000.msat, fee = 2_000_000.msat, source = LiquidityEvents.Source.OffChainPayment),
            actual = policy.maybeReject(amount = 4_000_000.msat, fee = 2_000_000.msat, source = LiquidityEvents.Source.OffChainPayment, logger, currentFeeCredit = 0.sat))

        // fee is over absolute, but it's an on-chain payment so the check fails
        assertEquals(
            expected = LiquidityEvents.Decision.Rejected.Reason.TooExpensive.OverAbsoluteFee(policy.maxAbsoluteFee),
            actual = (policy.maybeReject(amount = 4_000_000.msat, fee = 2_000_000.msat, source = LiquidityEvents.Source.OnChainWallet, logger, currentFeeCredit = 0.sat) as? LiquidityEvents.Decision.Rejected)?.reason
        )

    }
}