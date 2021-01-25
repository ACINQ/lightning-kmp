package fr.acinq.eclair.io.peer

import fr.acinq.eclair.channel.Offline
import fr.acinq.eclair.channel.TestsHelper.reachNormal
import fr.acinq.eclair.io.Disconnected
import fr.acinq.eclair.tests.TestConstants
import fr.acinq.eclair.tests.io.peer.newPeer
import fr.acinq.eclair.tests.utils.EclairTestSuite
import fr.acinq.eclair.tests.utils.runSuspendTest
import fr.acinq.eclair.utils.Connection
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.ExperimentalTime

@OptIn(ExperimentalCoroutinesApi::class, ExperimentalTime::class)
class ConnectionTest : EclairTestSuite() {

    @Test
    fun `connection lost`() = runSuspendTest {
        val (alice0, bob0) = reachNormal()
        val peer = newPeer(alice0.staticParams.nodeParams, TestConstants.Alice.walletParams, bob0) { channels.addOrUpdateChannel(alice0) }

        peer.send(Disconnected)
        // Wait until alice is Offline
        peer.channelsFlow.first { it.values.size == 1 && it.values.all { channelState -> channelState is Offline } }
        assertEquals(Connection.ESTABLISHED, peer.connectionState.value)
    }

}