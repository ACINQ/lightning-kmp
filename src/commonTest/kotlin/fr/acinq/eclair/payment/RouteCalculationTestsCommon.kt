package fr.acinq.eclair.payment

import fr.acinq.bitcoin.ByteVector32
import fr.acinq.eclair.Eclair.randomBytes32
import fr.acinq.eclair.MilliSatoshi
import fr.acinq.eclair.ShortChannelId
import fr.acinq.eclair.channel.Commitments
import fr.acinq.eclair.channel.Normal
import fr.acinq.eclair.channel.Offline
import fr.acinq.eclair.channel.Syncing
import fr.acinq.eclair.payment.RouteCalculation.findRoutes
import fr.acinq.eclair.tests.utils.EclairTestSuite
import fr.acinq.eclair.transactions.CommitmentSpec
import fr.acinq.eclair.utils.Either
import fr.acinq.eclair.utils.msat
import fr.acinq.eclair.utils.sum
import fr.acinq.eclair.utils.toMilliSatoshi
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RouteCalculationTestsCommon : EclairTestSuite() {

    private val defaultChannel = fr.acinq.eclair.channel.TestsHelper.reachNormal().first

    private fun makeChannel(channelId: ByteVector32, balance: MilliSatoshi, htlcMin: MilliSatoshi): Normal {
        val shortChannelId = ShortChannelId(Random.nextLong())
        val reserve = defaultChannel.commitments.remoteParams.channelReserve
        val commitments = defaultChannel.commitments.copy(
            channelId = channelId,
            remoteCommit = defaultChannel.commitments.remoteCommit.copy(spec = CommitmentSpec(setOf(), 0, 50_000.msat, balance + ((Commitments.ANCHOR_AMOUNT * 2) + reserve).toMilliSatoshi()))
        )
        val channelUpdate = defaultChannel.channelUpdate.copy(htlcMinimumMsat = htlcMin)
        return defaultChannel.copy(shortChannelId = shortChannelId, commitments = commitments, channelUpdate = channelUpdate)
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
        assertEquals(Either.Left(FinalFailure.NoAvailableChannels), findRoutes(5_000.msat, channels))
    }

    @Test
    fun `insufficient balance`() {
        val (channelId1, channelId2, channelId3) = listOf(randomBytes32(), randomBytes32(), randomBytes32())
        val channels = mapOf(
            channelId1 to makeChannel(channelId1, 15_000.msat, 10.msat),
            channelId2 to makeChannel(channelId2, 18_000.msat, 5.msat),
            channelId3 to makeChannel(channelId3, 12_000.msat, 10.msat),
        )
        assertEquals(Either.Left(FinalFailure.InsufficientBalance), findRoutes(50_000.msat, channels))
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
            val routes = findRoutes(38_000.msat, channels).right!!
            assertEquals(listOf(RouteCalculation.Route(38_000.msat, channels.getValue(channelId3))), routes)
        }
        run {
            val channels = mapOf(channelId3 to makeChannel(channelId3, 38_000.msat, 10.msat))
            val routes = findRoutes(38_000.msat, channels).right!!
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
        val routes = findRoutes(50_000.msat, channels).right!!
        val expected = setOf(
            RouteCalculation.Route(30_000.msat, channels.getValue(channelId3)),
            RouteCalculation.Route(20_000.msat, channels.getValue(channelId4)),
        )
        assertEquals(expected, routes.toSet())
        assertEquals(Either.Left(FinalFailure.InsufficientBalance), findRoutes(50010.msat, channels))
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
            val routes = findRoutes(300.msat, channels).right!!
            val expected = setOf(
                RouteCalculation.Route(50.msat, channels.getValue(channelId1)),
                RouteCalculation.Route(150.msat, channels.getValue(channelId2)),
                RouteCalculation.Route(25.msat, channels.getValue(channelId3)),
                RouteCalculation.Route(75.msat, channels.getValue(channelId4)),
            )
            assertEquals(expected, routes.toSet())
        }
        run {
            val routes = findRoutes(250.msat, channels).right!!
            assertTrue(routes.size >= 3)
            assertEquals(250.msat, routes.map { it.amount }.sum())
        }
        run {
            val routes = findRoutes(200.msat, channels).right!!
            assertTrue(routes.size >= 2)
            assertEquals(200.msat, routes.map { it.amount }.sum())
        }
        run {
            val routes = findRoutes(50.msat, channels).right!!
            assertTrue(routes.size == 1)
            assertEquals(50.msat, routes.map { it.amount }.sum())
        }
    }

}