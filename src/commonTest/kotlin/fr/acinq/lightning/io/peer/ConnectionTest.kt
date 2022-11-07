package fr.acinq.lightning.io.peer

import fr.acinq.lightning.channel.Offline
import fr.acinq.lightning.channel.TestsHelper.reachNormal
import fr.acinq.lightning.io.Disconnected
import fr.acinq.lightning.tests.TestConstants
import fr.acinq.lightning.tests.io.peer.newPeer
import fr.acinq.lightning.tests.utils.LightningTestSuite
import fr.acinq.lightning.tests.utils.runSuspendTest
import fr.acinq.lightning.utils.Connection
import kotlinx.coroutines.flow.first
import kotlin.test.Test
import kotlin.test.assertEquals

class ConnectionTest : LightningTestSuite() {

    @Test
    fun `connection lost`() = runSuspendTest {
        val (alice0, bob0) = reachNormal()
        val peer = newPeer(alice0.staticParams.nodeParams, TestConstants.Alice.walletParams, bob0) { channels.addOrUpdateChannel(alice0.state) }

        peer.send(Disconnected)
        // Wait until alice is Offline
        peer.channelsFlow.first { it.values.size == 1 && it.values.all { channelState -> channelState is Offline } }
        assertEquals(Connection.ESTABLISHED, peer.connectionState.value)
    }

}