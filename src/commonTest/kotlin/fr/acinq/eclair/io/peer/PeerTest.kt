package fr.acinq.eclair.io.peer

import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.eclair.Eclair
import fr.acinq.eclair.NodeUri
import fr.acinq.eclair.TestConstants
import fr.acinq.eclair.channel.Normal
import fr.acinq.eclair.channel.Offline
import fr.acinq.eclair.channel.Syncing
import fr.acinq.eclair.channel.TestsHelper
import fr.acinq.eclair.db.OutgoingPayment
import fr.acinq.eclair.io.BytesReceived
import fr.acinq.eclair.io.ReceivePayment
import fr.acinq.eclair.io.SendPayment
import fr.acinq.eclair.payment.PaymentRequest
import fr.acinq.eclair.tests.io.peer.*
import fr.acinq.eclair.tests.utils.EclairTestSuite
import fr.acinq.eclair.tests.utils.runSuspendTest
import fr.acinq.eclair.utils.Connection
import fr.acinq.eclair.utils.UUID
import fr.acinq.eclair.utils.msat
import fr.acinq.eclair.utils.toByteVector
import fr.acinq.eclair.wire.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.ExperimentalTime

@OptIn(ExperimentalCoroutinesApi::class, ExperimentalTime::class)
class PeerTest : EclairTestSuite() {

    @Test
    fun `init peer`() = runSuspendTest {
        val alice = buildPeer(this, TestConstants.Alice.nodeParams)
        val bob = buildPeer(this, TestConstants.Bob.nodeParams)

        // start Init for Alice
        alice.send(BytesReceived(LightningMessage.encode(Init(features = TestConstants.Bob.nodeParams.features.toByteArray().toByteVector()))))
        // start Init for Bob
        bob.send(BytesReceived(LightningMessage.encode(Init(features = TestConstants.Alice.nodeParams.features.toByteArray().toByteVector()))))

        // Wait until the Peer is ready
        alice.expectStatus(Connection.ESTABLISHED)
        bob.expectStatus(Connection.ESTABLISHED)
    }

    @Test
    fun `init peer (bundled)`() = runSuspendTest { newPeers(this, Pair(TestConstants.Alice.nodeParams, TestConstants.Bob.nodeParams)) }

    @Test
    fun `restore channel`() = runSuspendTest {
        val (alice0, _) = TestsHelper.reachNormal()

        val db = newDatabases().also { it.channels.addOrUpdateChannel(alice0) }
        val peer = buildPeer(scope = this, nodeParams = alice0.staticParams.nodeParams, databases = db)

        val initChannels = peer.channelsFlow.first { it.values.isNotEmpty() }
        assertEquals(1, initChannels.size)
        assertEquals(alice0.channelId, initChannels.keys.first())
        assertTrue(initChannels.values.first() is Offline)

        // send Init from remote node
        val theirInit = Init(features = activatedFeatures.toByteArray().toByteVector())
        val initMsg = LightningMessage.encode(theirInit)
        peer.send(BytesReceived(initMsg))
        // Wait until the Peer is ready
        peer.expectStatus(Connection.ESTABLISHED)

        // Wait until the channels are Syncing
        val syncChannels = peer.channelsFlow
            .first { it.values.size == 1 && it.values.all { channelState -> channelState is Syncing } }
            .map { it.value as Syncing }
        assertEquals(alice0.channelId, syncChannels.first().channelId)

        val syncState = syncChannels.first()
        val yourLastPerCommitmentSecret = ByteVector32.Zeroes
        val channelKeyPath = peer.nodeParams.keyManager.channelKeyPath(syncState.commitments.localParams, syncState.commitments.channelVersion)
        val myCurrentPerCommitmentPoint = peer.nodeParams.keyManager.commitmentPoint(channelKeyPath, syncState.commitments.localCommit.index)

        val channelReestablish = ChannelReestablish(
            channelId = syncState.channelId,
            nextLocalCommitmentNumber = syncState.commitments.localCommit.index + 1,
            nextRemoteRevocationNumber = syncState.commitments.remoteCommit.index,
            yourLastCommitmentSecret = PrivateKey(yourLastPerCommitmentSecret),
            myCurrentPerCommitmentPoint = myCurrentPerCommitmentPoint,
            syncState.commitments.remoteChannelData
        )

        val reestablishMsg = LightningMessage.encode(channelReestablish)
        peer.send(BytesReceived(reestablishMsg))

        // Wait until the channels are Reestablished(=Normal)
        val reestablishChannels = peer.channelsFlow.first { it.values.size == 1 && it.values.all { channelState -> channelState is Normal } }
        assertEquals(alice0.channelId, reestablishChannels.keys.first())
    }

    @Test
    fun `restore channel (bundled)`() = runSuspendTest {
        val (alice0, bob0) = TestsHelper.reachNormal()
        newPeers(this, Pair(alice0.staticParams.nodeParams, bob0.staticParams.nodeParams), listOf(alice0 to bob0))
    }

    @Test
    fun `payment test between two phoenix nodes (manual mode)`() = runSuspendTest {
        val (alice0, bob0) = TestsHelper.reachNormal()
        val nodeParams = Pair(
            // Alice must declare Bob as her trampoline node to enable direct payments.
            alice0.staticParams.nodeParams.copy(trampolineNode = NodeUri(bob0.staticParams.nodeParams.nodeId, "bob.com", 9735)),
            bob0.staticParams.nodeParams
        )
        val (alice, bob) = newPeers(this, nodeParams, listOf(alice0 to bob0), automateMessaging = false)

        val deferredInvoice = CompletableDeferred<PaymentRequest>()
        bob.send(ReceivePayment(Eclair.randomBytes32(), 15_000_000.msat, "test invoice", deferredInvoice))
        val invoice = deferredInvoice.await()

        alice.send(SendPayment(UUID.randomUUID(), invoice.amount!!, alice.remoteNodeId, OutgoingPayment.Details.Normal(invoice)))

        val updateHtlc = alice.expectMessage<UpdateAddHtlc>()
        val aliceCommitSig = alice.expectMessage<CommitSig>()
        bob.forwardMessage(updateHtlc)
        bob.forwardMessage(aliceCommitSig)

        val bobRevokeAndAck = bob.expectMessage<RevokeAndAck>()
        val bobCommitSig = bob.expectMessage<CommitSig>()
        alice.forwardMessage(bobRevokeAndAck)
        alice.forwardMessage(bobCommitSig)

        val aliceRevokeAndAck = alice.expectMessage<RevokeAndAck>()
        bob.forwardMessage(aliceRevokeAndAck)

        val updateFulfillHtlc = bob.expectMessage<UpdateFulfillHtlc>()
        val bobCommitSig2 = bob.expectMessage<CommitSig>()
        alice.forwardMessage(updateFulfillHtlc)
        alice.forwardMessage(bobCommitSig2)

        val aliceRevokeAndAck2 = alice.expectMessage<RevokeAndAck>()
        val aliceCommitSig2 = alice.expectMessage<CommitSig>()
        bob.forwardMessage(aliceRevokeAndAck2)
        bob.forwardMessage(aliceCommitSig2)

        val bobRevokeAndAck2 = bob.expectMessage<RevokeAndAck>()
        alice.forwardMessage(bobRevokeAndAck2)

        alice.expectState<Normal> { commitments.availableBalanceForReceive() > alice0.commitments.availableBalanceForReceive() }
    }

    @Test
    fun `payment test between two phoenix nodes (automated messaging)`() = runSuspendTest {
        val (alice0, bob0) = TestsHelper.reachNormal()
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

        alice.expectState<Normal> { commitments.availableBalanceForReceive() > alice0.commitments.availableBalanceForReceive() }
    }
}