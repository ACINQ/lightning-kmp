package fr.acinq.lightning.payment

import fr.acinq.lightning.LiquidityEvents
import fr.acinq.lightning.channel.ChannelManagementFees
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
        val policy = LiquidityPolicy.Auto(maxAbsoluteFee = 2_000.sat, maxRelativeFeeBasisPoints = 3_000 /* 3000 = 30 % */, skipAbsoluteFeeCheck = false, inboundLiquidityTarget = null, maxAllowedFeeCredit = 0.msat)
        // fee over both absolute and relative
        assertEquals(
            expected = LiquidityEvents.Rejected.Reason.TooExpensive.OverRelativeFee(policy.maxRelativeFeeBasisPoints),
            actual = policy.maybeReject(amount = 4_000_000.msat, fee = ChannelManagementFees(miningFee = 3_000.sat, serviceFee = 0.sat), source = LiquidityEvents.Source.OffChainPayment, logger)?.reason
        )
        // fee over absolute
        assertEquals(
            expected = LiquidityEvents.Rejected.Reason.TooExpensive.OverAbsoluteFee(policy.maxAbsoluteFee),
            actual = policy.maybeReject(amount = 15_000_000.msat, fee = ChannelManagementFees(miningFee = 3_000.sat, serviceFee = 0.sat), source = LiquidityEvents.Source.OffChainPayment, logger)?.reason
        )
        // fee over relative
        assertEquals(
            expected = LiquidityEvents.Rejected.Reason.TooExpensive.OverRelativeFee(policy.maxRelativeFeeBasisPoints),
            actual = policy.maybeReject(amount = 4_000_000.msat, fee = ChannelManagementFees(miningFee = 2_000.sat, serviceFee = 0.sat), source = LiquidityEvents.Source.OffChainPayment, logger)?.reason
        )
        assertNull(policy.maybeReject(amount = 10_000_000.msat, fee = ChannelManagementFees(miningFee = 2_000.sat, serviceFee = 0.sat), source = LiquidityEvents.Source.OffChainPayment, logger))
    }

    @Test
    fun `policy rejection skip absolute check`() {
        val policy = LiquidityPolicy.Auto(maxAbsoluteFee = 1_000.sat, maxRelativeFeeBasisPoints = 5_000 /* 3000 = 30 % */, skipAbsoluteFeeCheck = true, inboundLiquidityTarget = null, maxAllowedFeeCredit = 0.msat)
        // fee is over absolute, and it's an offchain payment so the check passes
        assertNull(policy.maybeReject(amount = 4_000_000.msat, fee = ChannelManagementFees(miningFee = 2_000.sat, serviceFee = 0.sat), source = LiquidityEvents.Source.OffChainPayment, logger))
        // fee is over absolute, but it's an on-chain payment so the check fails
        assertEquals(
            expected = LiquidityEvents.Rejected.Reason.TooExpensive.OverAbsoluteFee(policy.maxAbsoluteFee),
            actual = policy.maybeReject(amount = 4_000_000.msat, fee = ChannelManagementFees(miningFee = 2_000.sat, serviceFee = 0.sat), source = LiquidityEvents.Source.OnChainWallet, logger)?.reason
        )
    }
}