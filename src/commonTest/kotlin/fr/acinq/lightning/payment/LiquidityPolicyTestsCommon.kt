package fr.acinq.lightning.payment

import fr.acinq.lightning.Lightning.randomBytes32
import fr.acinq.lightning.LiquidityEvents
import fr.acinq.lightning.tests.utils.LightningTestSuite
import fr.acinq.lightning.utils.MDCLogger
import fr.acinq.lightning.utils.msat
import fr.acinq.lightning.utils.sat
import org.kodein.log.LoggerFactory
import org.kodein.log.newLogger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LiquidityPolicyTestsCommon : LightningTestSuite() {

    private val logger = MDCLogger(LoggerFactory.default.newLogger(this::class))

    @Test
    fun `policy rejection`() {

        val policy = LiquidityPolicy.Auto(maxAbsoluteFee = 2_000.sat, maxRelativeFeeBasisPoints = 3_000 /* 3000 = 30 % */, skipAbsoluteFeeCheck = false)

        // fee over both absolute and relative
        assertEquals(
            expected = LiquidityEvents.Rejected.Reason.TooExpensive.OverRelativeFee(policy.maxRelativeFeeBasisPoints),
            actual = policy.maybeReject(amount = 4_000_000.msat, fee = 3_000_000.msat, source = LiquidityEvents.Source.OffChainPayment(randomBytes32()), logger)?.reason
        )

        // fee over absolute
        assertEquals(
            expected = LiquidityEvents.Rejected.Reason.TooExpensive.OverAbsoluteFee(policy.maxAbsoluteFee),
            actual = policy.maybeReject(amount = 15_000_000.msat, fee = 3_000_000.msat, source = LiquidityEvents.Source.OffChainPayment(randomBytes32()), logger)?.reason
        )

        // fee over relative
        assertEquals(
            expected = LiquidityEvents.Rejected.Reason.TooExpensive.OverRelativeFee(policy.maxRelativeFeeBasisPoints),
            actual = policy.maybeReject(amount = 4_000_000.msat, fee = 2_000_000.msat, source = LiquidityEvents.Source.OffChainPayment(randomBytes32()), logger)?.reason
        )

        assertNull(policy.maybeReject(amount = 10_000_000.msat, fee = 2_000_000.msat, source = LiquidityEvents.Source.OffChainPayment(randomBytes32()), logger))

    }

    @Test
    fun `policy rejection skip absolute check`() {

        val policy = LiquidityPolicy.Auto(maxAbsoluteFee = 1_000.sat, maxRelativeFeeBasisPoints = 5_000 /* 3000 = 30 % */, skipAbsoluteFeeCheck = true)

        // fee is over absolute, and it's an offchain payment so the check passes
        assertNull(policy.maybeReject(amount = 4_000_000.msat, fee = 2_000_000.msat, source = LiquidityEvents.Source.OffChainPayment(randomBytes32()), logger))

        // fee is over absolute, but it's an on-chain payment so the check fails
        assertEquals(
            expected = LiquidityEvents.Rejected.Reason.TooExpensive.OverAbsoluteFee(policy.maxAbsoluteFee),
            actual = policy.maybeReject(amount = 4_000_000.msat, fee = 2_000_000.msat, source = LiquidityEvents.Source.OnChainWallet, logger)?.reason
        )

    }
}