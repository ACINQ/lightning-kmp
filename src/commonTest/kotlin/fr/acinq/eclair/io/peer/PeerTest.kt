package fr.acinq.eclair.io.peer

import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.eclair.TestConstants
import fr.acinq.eclair.channel.Normal
import fr.acinq.eclair.channel.Offline
import fr.acinq.eclair.channel.Syncing
import fr.acinq.eclair.channel.TestsHelper
import fr.acinq.eclair.io.BytesReceived
import fr.acinq.eclair.tests.*
import fr.acinq.eclair.tests.utils.EclairTestSuite
import fr.acinq.eclair.tests.utils.runSuspendTest
import fr.acinq.eclair.utils.Connection
import fr.acinq.eclair.utils.toByteVector
import fr.acinq.eclair.wire.ChannelReestablish
import fr.acinq.eclair.wire.Init
import fr.acinq.eclair.wire.LightningMessage
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

        val init = LightningMessage.encode(Init(features = activatedFeatures.toByteArray().toByteVector()))
        // start Init for Alice
        alice.send(BytesReceived(init))
        // start Init for Bob
        bob.send(BytesReceived(init))

        // Wait until the Peer is ready
        alice.waitForStatus(Connection.ESTABLISHED)
        bob.waitForStatus(Connection.ESTABLISHED)
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
        peer.waitForStatus(Connection.ESTABLISHED)

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
}