package fr.acinq.lightning.wire

import fr.acinq.bitcoin.*
import fr.acinq.lightning.Lightning.randomBytes32
import fr.acinq.lightning.Lightning.randomBytes64
import fr.acinq.lightning.blockchain.fee.FeeratePerByte
import fr.acinq.lightning.blockchain.fee.FeeratePerKw
import fr.acinq.lightning.channel.ChannelException
import fr.acinq.lightning.channel.InvalidLiquidityAdsAmount
import fr.acinq.lightning.channel.InvalidLiquidityAdsSig
import fr.acinq.lightning.channel.MissingLiquidityAds
import fr.acinq.lightning.tests.utils.LightningTestSuite
import fr.acinq.lightning.utils.sat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class LiquidityAdsTestsCommon : LightningTestSuite() {

    @Test
    fun `validate liquidity ads lease`() {
        val nodeKey = PrivateKey.fromHex("57ac961f1b80ebfb610037bf9c96c6333699bde42257919a53974811c34649e3")
        assertEquals(PublicKey.fromHex("03ca9b880627d2d4e3b33164f66946349f820d26aa9572fe0e525e534850cbd413"), nodeKey.publicKey())

        val fundingLease = LiquidityAds.FundingLease.Basic(100_000.sat, 1_000_000.sat, LiquidityAds.LeaseRate(500, 10.sat, 100))
        assertEquals(fundingLease.fees(FeeratePerKw(FeeratePerByte(5.sat)), 500_000.sat, 500_000.sat).total, 5635.sat)
        assertEquals(fundingLease.fees(FeeratePerKw(FeeratePerByte(5.sat)), 500_000.sat, 600_000.sat).total, 5635.sat)
        assertEquals(fundingLease.fees(FeeratePerKw(FeeratePerByte(5.sat)), 500_000.sat, 400_000.sat).total, 4635.sat)
        assertEquals(fundingLease.fees(FeeratePerKw(FeeratePerByte(10.sat)), 500_000.sat, 500_000.sat).total, 6260.sat)

        val fundingRates = LiquidityAds.WillFundRates(listOf(fundingLease), setOf(LiquidityAds.PaymentType.FromChannelBalance))
        val request = LiquidityAds.RequestFunds.chooseLease(500_000.sat, LiquidityAds.PaymentDetails.FromChannelBalance, fundingRates)
        assertNotNull(request)
        val fundingScript = ByteVector.fromHex("00202395c9c52c02ca069f1d56a3c6124bf8b152a617328c76e6b31f83ace370c2ff")
        val willFund = fundingRates.validateRequest(nodeKey, fundingScript, FeeratePerKw(1000.sat), request)?.willFund
        assertNotNull(willFund)
        assertEquals(ByteVector64.fromValidHex("2da1162acfb5073213c43934663b6c4bd1d505a318f2229b39e20bfd5d5e5e96533520b0fa9de746ee051969c28647288cbdfa898a458b6e756a7a63ffc52bba"), willFund.signature)
        assertEquals(LiquidityAds.FundingLeaseWitness.Basic(fundingScript), willFund.leaseWitness)

        data class TestCase(val remoteFundingAmount: Satoshi, val willFund: LiquidityAds.WillFund?, val failure: ChannelException?)

        val channelId = randomBytes32()
        val testCases = listOf(
            TestCase(500_000.sat, willFund, failure = null),
            TestCase(500_000.sat, willFund = null, failure = MissingLiquidityAds(channelId)),
            TestCase(500_000.sat, willFund.copy(signature = randomBytes64()), failure = InvalidLiquidityAdsSig(channelId)),
            TestCase(0.sat, willFund, failure = InvalidLiquidityAdsAmount(channelId, 0.sat, 500_000.sat)),
        )
        testCases.forEach {
            val result = request.validateLease(nodeKey.publicKey(), channelId, fundingScript, it.remoteFundingAmount, FeeratePerKw(2500.sat), it.willFund)
            assertEquals(it.failure, result.left)
        }
    }

}