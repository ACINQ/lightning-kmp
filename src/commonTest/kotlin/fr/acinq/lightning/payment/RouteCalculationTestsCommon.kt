package fr.acinq.lightning.payment

import fr.acinq.bitcoin.ByteVector32
import fr.acinq.lightning.Lightning.randomBytes32
import fr.acinq.lightning.MilliSatoshi
import fr.acinq.lightning.ShortChannelId
import fr.acinq.lightning.blockchain.fee.FeeratePerKw
import fr.acinq.lightning.channel.Commitments
import fr.acinq.lightning.channel.Normal
import fr.acinq.lightning.channel.Offline
import fr.acinq.lightning.channel.Syncing
import fr.acinq.lightning.channel.TestsHelper.reachNormal
import fr.acinq.lightning.tests.utils.LightningTestSuite
import fr.acinq.lightning.transactions.CommitmentSpec
import fr.acinq.lightning.utils.*
import org.kodein.log.LoggerFactory
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RouteCalculationTestsCommon : LightningTestSuite() {

    private val defaultChannel = reachNormal().first
    private val paymentId = UUID.randomUUID()
    private val routeCalculation = RouteCalculation(LoggerFactory.default)

    private fun makeChannel(channelId: ByteVector32, balance: MilliSatoshi, htlcMin: MilliSatoshi): Normal {
        val shortChannelId = ShortChannelId(Random.nextLong())
        val reserve = defaultChannel.commitments.localChannelReserve
        val commitments = defaultChannel.commitments.copy(
            channelId = channelId,
            remoteCommit = defaultChannel.commitments.remoteCommit.copy(spec = CommitmentSpec(setOf(), FeeratePerKw(0.sat), 50_000.msat, balance + ((Commitments.ANCHOR_AMOUNT * 2) + reserve).toMilliSatoshi()))
        )
        val channelUpdate = defaultChannel.state.channelUpdate.copy(htlcMinimumMsat = htlcMin)
        return defaultChannel.state.copy(shortChannelId = shortChannelId, commitments = commitments, channelUpdate = channelUpdate)
    }

    @Test
    fun `make channel fixture`() {
        val (channelId1, channelId2, channelId3) = listOf(randomBytes32(), randomBytes32(), randomBytes32())
        val offlineChannels = mapOf(
            channelId1 to Offline(makeChannel(channelId1, 15_000.msat, 10.msat)),
            channelId2 to Offline(makeChannel(channelId2, 20_000.msat, 5.msat)),
            channelId3 to Offline(makeChannel(channelId3, 10_000.msat, 10.msat)),
        )
        assertEquals(setOf(10_000.msat, 15_000.msat, 20_000.msat), offlineChannels.map { it.value.state.commitments.availableBalanceForSend() }.toSet())

        val normalChannels = mapOf(
            channelId1 to makeChannel(channelId1, 15_000.msat, 10.msat),
            channelId2 to makeChannel(channelId2, 15_000.msat, 5.msat),
            channelId3 to makeChannel(channelId3, 10_000.msat, 10.msat),
        )
        assertEquals(setOf(10_000.msat, 15_000.msat), normalChannels.map { it.value.commitments.availableBalanceForSend() }.toSet())
    }

    @Test
    fun `no available channels`() {
        val (channelId1, channelId2, channelId3) = listOf(randomBytes32(), randomBytes32(), randomBytes32())
        val channels = mapOf(
            channelId1 to Offline(makeChannel(channelId1, 15_000.msat, 10.msat)),
            channelId2 to Syncing(makeChannel(channelId2, 20_000.msat, 5.msat), false),
            channelId3 to Offline(makeChannel(channelId3, 10_000.msat, 10.msat)),
        )
        assertEquals(Either.Left(FinalFailure.NoAvailableChannels), routeCalculation.findRoutes(paymentId, 5_000.msat, channels))
    }

    @Test
    fun `insufficient balance`() {
        val (channelId1, channelId2, channelId3) = listOf(randomBytes32(), randomBytes32(), randomBytes32())
        val channels = mapOf(
            channelId1 to makeChannel(channelId1, 15_000.msat, 10.msat),
            channelId2 to makeChannel(channelId2, 18_000.msat, 5.msat),
            channelId3 to makeChannel(channelId3, 12_000.msat, 10.msat),
        )
        assertEquals(Either.Left(FinalFailure.InsufficientBalance), routeCalculation.findRoutes(paymentId, 50_000.msat, channels))
    }

    @Test
    fun `single payment`() {
        val (channelId1, channelId2, channelId3) = listOf(randomBytes32(), randomBytes32(), randomBytes32())
        run {
            val channels = mapOf(
                channelId1 to makeChannel(channelId1, 35_000.msat, 10.msat),
                channelId2 to makeChannel(channelId2, 30_000.msat, 5.msat),
                channelId3 to makeChannel(channelId3, 38_000.msat, 10.msat),
            )
            val routes = routeCalculation.findRoutes(paymentId, 38_000.msat, channels).right!!
            assertEquals(listOf(RouteCalculation.Route(38_000.msat, channels.getValue(channelId3))), routes)
        }
        run {
            val channels = mapOf(channelId3 to makeChannel(channelId3, 38_000.msat, 10.msat))
            val routes = routeCalculation.findRoutes(paymentId, 38_000.msat, channels).right!!
            assertEquals(listOf(RouteCalculation.Route(38_000.msat, channels.getValue(channelId3))), routes)
        }
    }

    @Test
    fun `ignore empty channels`() {
        val (channelId1, channelId2, channelId3, channelId4) = listOf(randomBytes32(), randomBytes32(), randomBytes32(), randomBytes32())
        val channels = mapOf(
            channelId1 to makeChannel(channelId1, 0.msat, 10.msat),
            channelId2 to makeChannel(channelId2, 50.msat, 100.msat),
            channelId3 to makeChannel(channelId3, 30_000.msat, 15.msat),
            channelId4 to makeChannel(channelId4, 20_000.msat, 50.msat),
        )
        val routes = routeCalculation.findRoutes(paymentId, 50_000.msat, channels).right!!
        val expected = setOf(
            RouteCalculation.Route(30_000.msat, channels.getValue(channelId3)),
            RouteCalculation.Route(20_000.msat, channels.getValue(channelId4)),
        )
        assertEquals(expected, routes.toSet())
        assertEquals(Either.Left(FinalFailure.InsufficientBalance), routeCalculation.findRoutes(paymentId, 50010.msat, channels))
    }

    @Test
    fun `split payment across many channels`() {
        val (channelId1, channelId2, channelId3, channelId4) = listOf(randomBytes32(), randomBytes32(), randomBytes32(), randomBytes32())
        val channels = mapOf(
            channelId1 to makeChannel(channelId1, 50.msat, 10.msat),
            channelId2 to makeChannel(channelId2, 150.msat, 100.msat),
            channelId3 to makeChannel(channelId3, 25.msat, 15.msat),
            channelId4 to makeChannel(channelId4, 75.msat, 50.msat),
        )
        run {
            val routes = routeCalculation.findRoutes(paymentId, 300.msat, channels).right!!
            val expected = setOf(
                RouteCalculation.Route(50.msat, channels.getValue(channelId1)),
                RouteCalculation.Route(150.msat, channels.getValue(channelId2)),
                RouteCalculation.Route(25.msat, channels.getValue(channelId3)),
                RouteCalculation.Route(75.msat, channels.getValue(channelId4)),
            )
            assertEquals(expected, routes.toSet())
        }
        run {
            val routes = routeCalculation.findRoutes(paymentId, 250.msat, channels).right!!
            assertTrue(routes.size >= 3)
            assertEquals(250.msat, routes.map { it.amount }.sum())
        }
        run {
            val routes = routeCalculation.findRoutes(paymentId, 200.msat, channels).right!!
            assertTrue(routes.size >= 2)
            assertEquals(200.msat, routes.map { it.amount }.sum())
        }
        run {
            val routes = routeCalculation.findRoutes(paymentId, 50.msat, channels).right!!
            assertTrue(routes.size == 1)
            assertEquals(50.msat, routes.map { it.amount }.sum())
        }
    }

}