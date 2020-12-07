package fr.acinq.eclair.io.peer

import fr.acinq.eclair.Eclair
import fr.acinq.eclair.NodeUri
import fr.acinq.eclair.channel.Normal
import fr.acinq.eclair.channel.Offline
import fr.acinq.eclair.channel.TestsHelper.reachNormal
import fr.acinq.eclair.db.OutgoingPayment
import fr.acinq.eclair.io.Disconnected
import fr.acinq.eclair.io.ReceivePayment
import fr.acinq.eclair.io.SendPayment
import fr.acinq.eclair.payment.PaymentRequest
import fr.acinq.eclair.tests.io.peer.*
import fr.acinq.eclair.tests.utils.EclairTestSuite
import fr.acinq.eclair.tests.utils.runSuspendTest
import fr.acinq.eclair.utils.UUID
import fr.acinq.eclair.utils.msat
import fr.acinq.eclair.wire.CommitSig
import fr.acinq.eclair.wire.RevokeAndAck
import fr.acinq.eclair.wire.UpdateAddHtlc
import fr.acinq.eclair.wire.UpdateFulfillHtlc
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlin.test.Test
import kotlin.time.ExperimentalTime

@OptIn(ExperimentalCoroutinesApi::class, ExperimentalTime::class)
class ConnectionTest : EclairTestSuite() {

    @Test
    fun `connection lost`() = runSuspendTest {
        val (alice0, bob0) = reachNormal()
        val peer = newPeer(alice0.staticParams.nodeParams, bob0) { channels.addOrUpdateChannel(alice0) }

        peer.send(Disconnected)
        // Wait until alice is Offline
        peer.channelsFlow.first { it.values.size == 1 && it.values.all { channelState -> channelState is Offline } }
    }

}