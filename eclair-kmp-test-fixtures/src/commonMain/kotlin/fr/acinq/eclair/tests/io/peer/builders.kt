package fr.acinq.eclair.tests.io.peer

import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.eclair.*
import fr.acinq.eclair.blockchain.electrum.ElectrumClient
import fr.acinq.eclair.blockchain.electrum.ElectrumWatcher
import fr.acinq.eclair.channel.ChannelStateWithCommitments
import fr.acinq.eclair.channel.Normal
import fr.acinq.eclair.channel.Syncing
import fr.acinq.eclair.db.InMemoryChannelsDb
import fr.acinq.eclair.db.InMemoryDatabases
import fr.acinq.eclair.db.InMemoryPaymentsDb
import fr.acinq.eclair.io.BytesReceived
import fr.acinq.eclair.io.Peer
import fr.acinq.eclair.io.TcpSocket
import fr.acinq.eclair.utils.Connection
import fr.acinq.eclair.utils.toByteVector
import fr.acinq.eclair.wire.ChannelReestablish
import fr.acinq.eclair.wire.FundingLocked
import fr.acinq.eclair.wire.Init
import fr.acinq.eclair.wire.LightningMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
public suspend fun newPeers(
    scope: CoroutineScope,
    nodeParams: Pair<NodeParams, NodeParams>,
    initChannels: List<Pair<ChannelStateWithCommitments, ChannelStateWithCommitments>> = emptyList(),
    automateMessaging: Boolean = true
): PeerTuple {
    // Create Alice and Bob peers
    val alice = buildPeer(scope, nodeParams.first, databases = InMemoryDatabases().apply {
        initChannels.forEach { channels.addOrUpdateChannel(it.first) }
    })
    val bob = buildPeer(scope, nodeParams.second, databases = InMemoryDatabases().apply {
        initChannels.forEach { channels.addOrUpdateChannel(it.second) }
    })

    // Create collectors for Alice and Bob output messages
    val bob2alice = flow {
        while (scope.isActive) {
            val bytes = bob.output.receive()
            val msg = LightningMessage.decode(bytes) ?: error("cannot decode lightning message $bytes")
            println("Bob sends $msg")
            emit(msg)
        }
    }
    val alice2bob = flow {
        while (scope.isActive) {
            val bytes = alice.output.receive()
            val msg = LightningMessage.decode(bytes) ?: error("cannot decode lightning message $bytes")
            println("Alice sends $msg")
            emit(msg)
        }
    }

    // Initialize Bob with Alice's features
    bob.send(BytesReceived(LightningMessage.encode(Init(features = nodeParams.first.features.toByteArray().toByteVector()))))
    // Initialize Alice with Bob's features
    alice.send(BytesReceived(LightningMessage.encode(Init(features = nodeParams.second.features.toByteArray().toByteVector()))))

    // TODO update to depend on the initChannels size
    if (initChannels.isNotEmpty()) {
        val bobInit = scope.launch {
            val bobChannelReestablish = bob2alice.expect<ChannelReestablish>()
            alice.forward(bobChannelReestablish)
            val bobFundingLocked = bob2alice.expect<FundingLocked>()
            alice.forward(bobFundingLocked)
        }
        val aliceInit = scope.launch {
            val aliceChannelReestablish = alice2bob.expect<ChannelReestablish>()
            bob.forward(aliceChannelReestablish)
            val aliceFundingLocked = alice2bob.expect<FundingLocked>()
            bob.forward(aliceFundingLocked)
        }
        bobInit.join()
        aliceInit.join()
    }

    // Wait until the Peers are ready
    alice.expectStatus(Connection.ESTABLISHED)
    bob.expectStatus(Connection.ESTABLISHED)

    // Wait until the [Channels] are synchronised
    alice.channelsFlow.first { it.size == initChannels.size && it.values.all { state -> state is Normal } }
    bob.channelsFlow.first { it.size == initChannels.size && it.values.all { state -> state is Normal } }

    if (automateMessaging) {
        scope.launch {
            bob2alice.collect {
                alice.send(BytesReceived(LightningMessage.encode(it)))
            }
        }
        scope.launch {
            alice2bob.collect {
                bob.send(BytesReceived(LightningMessage.encode(it)))
            }
        }
    }

    return PeerTuple(alice, bob, alice2bob, bob2alice)
}

public suspend fun CoroutineScope.newPeer(
    nodeParams: NodeParams,
    remotedNodeChannelState: ChannelStateWithCommitments? = null,
    setupDatabases: suspend InMemoryDatabases.() -> Unit = {},
): Peer {
    val db = InMemoryDatabases().apply { setupDatabases(this) }

    val peer = buildPeer(this, nodeParams, db)

    remotedNodeChannelState?.let { state ->
        // send Init from remote node
        val theirInit = Init(features = state.staticParams.nodeParams.features.toByteArray().toByteVector())

        val initMsg = LightningMessage.encode(theirInit)
        peer.send(BytesReceived(initMsg))
        peer.expectStatus(Connection.ESTABLISHED)

        peer.channelsFlow.first {
            it.values.size == peer.db.channels.listLocalChannels().size
                    && it.values.all { channelState -> channelState is Syncing }
        }

        val yourLastPerCommitmentSecret = state.commitments.remotePerCommitmentSecrets.lastIndex?.let { state.commitments.remotePerCommitmentSecrets.getHash(it) } ?: ByteVector32.Zeroes
        val channelKeyPath = peer.nodeParams.keyManager.channelKeyPath(state.commitments.localParams, state.commitments.channelVersion)
        val myCurrentPerCommitmentPoint = peer.nodeParams.keyManager.commitmentPoint(channelKeyPath, state.commitments.localCommit.index)

        val channelReestablish = ChannelReestablish(
            channelId = state.channelId,
            nextLocalCommitmentNumber = state.commitments.localCommit.index + 1,
            nextRemoteRevocationNumber = state.commitments.remoteCommit.index,
            yourLastCommitmentSecret = PrivateKey(yourLastPerCommitmentSecret),
            myCurrentPerCommitmentPoint = myCurrentPerCommitmentPoint,
            state.commitments.remoteChannelData
        )

        val msg = LightningMessage.encode(channelReestablish)
        peer.send(BytesReceived(msg))
    }

    peer.channelsFlow.first {
        it.values.size == peer.db.channels.listLocalChannels().size
                && it.values.all { channelState -> channelState is Normal }
    }

    return peer
}

public fun buildPeer(
    scope: CoroutineScope,
    nodeParams: NodeParams,
    databases: InMemoryDatabases = InMemoryDatabases()
): Peer {
    val electrum = ElectrumClient(TcpSocket.Builder(), scope)
    val watcher = ElectrumWatcher(electrum, scope)
    return Peer(TcpSocket.Builder(), nodeParams, watcher, databases, scope)
}

data class PeerTuple(val alice: Peer, val bob: Peer, val alice2bob: Flow<LightningMessage>, val bob2alice: Flow<LightningMessage>)