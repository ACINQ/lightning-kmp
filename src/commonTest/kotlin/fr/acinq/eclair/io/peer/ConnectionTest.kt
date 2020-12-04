package fr.acinq.eclair.io.peer

import fr.acinq.eclair.Eclair
import fr.acinq.eclair.NodeUri
import fr.acinq.eclair.channel.Offline
import fr.acinq.eclair.channel.TestsHelper.reachNormal
import fr.acinq.eclair.db.OutgoingPayment
import fr.acinq.eclair.io.Disconnected
import fr.acinq.eclair.io.ReceivePayment
import fr.acinq.eclair.io.SendPayment
import fr.acinq.eclair.payment.PaymentRequest
import fr.acinq.eclair.tests.newPeer
import fr.acinq.eclair.tests.newPeers
import fr.acinq.eclair.tests.utils.EclairTestSuite
import fr.acinq.eclair.tests.utils.runSuspendTest
import fr.acinq.eclair.utils.UUID
import fr.acinq.eclair.utils.msat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlin.test.Test
import kotlin.time.ExperimentalTime
import kotlin.time.seconds

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

    @Test
    fun `payment test between two phoenix nodes`() = runSuspendTest {
        val (alice0, bob0) = reachNormal()
        val nodeParams = Pair(
            // Alice must declare Bob as her trampoline node to enable direct payments.
            alice0.staticParams.nodeParams.copy(trampolineNode = NodeUri(bob0.staticParams.nodeParams.nodeId, "bob.com", 9735)),
            bob0.staticParams.nodeParams
        )
        val (alice, bob) = newPeers(this, nodeParams, listOf(alice0 to bob0))

        val deferredInvoice = CompletableDeferred<PaymentRequest>()
        bob.send(ReceivePayment(Eclair.randomBytes32(), 15_000_000.msat, "test invoice", deferredInvoice))
        val invoice = deferredInvoice.await()

        alice.send(SendPayment(UUID.randomUUID(), invoice.amount!!, alice.remoteNodeId, OutgoingPayment.Details.Normal(invoice)))

        delay(10.seconds)
    }
}