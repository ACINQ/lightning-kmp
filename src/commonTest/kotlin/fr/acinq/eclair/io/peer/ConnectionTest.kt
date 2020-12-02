package fr.acinq.eclair.io.peer

import fr.acinq.eclair.channel.ChannelEvent
import fr.acinq.eclair.channel.Normal
import fr.acinq.eclair.channel.Offline
import fr.acinq.eclair.channel.TestsHelper
import fr.acinq.eclair.channel.TestsHelper.reachNormal
import fr.acinq.eclair.io.Disconnected
import fr.acinq.eclair.io.PaymentNotSent
import fr.acinq.eclair.io.WrappedChannelEvent
import fr.acinq.eclair.tests.newPeer
import fr.acinq.eclair.tests.utils.EclairTestSuite
import fr.acinq.eclair.tests.utils.runSuspendTest
import fr.acinq.eclair.utils.sat
import fr.acinq.eclair.utils.toMilliSatoshi
import fr.acinq.eclair.wire.UpdateAddHtlc
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.receiveOrNull
import kotlinx.coroutines.flow.first
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.ExperimentalTime

@OptIn(ExperimentalCoroutinesApi::class, ExperimentalTime::class)
class ConnectionTest : EclairTestSuite() {

    @Test
    fun `connection lost`() = runSuspendTest {
        val (alice0, _) = reachNormal()
        val peer = newPeer { channels.addOrUpdateChannel(alice0) }

        peer.send(Disconnected)
        // Wait until alice is Offline
        peer.channelsFlow.first { it.values.size == 1 && it.values.all { channelState -> channelState is Offline } }
    }

}