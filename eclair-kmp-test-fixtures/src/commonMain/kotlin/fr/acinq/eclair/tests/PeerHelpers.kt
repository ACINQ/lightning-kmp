package fr.acinq.eclair.tests

import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.bitcoin.PublicKey
import fr.acinq.eclair.*
import fr.acinq.eclair.blockchain.electrum.ElectrumClient
import fr.acinq.eclair.blockchain.electrum.ElectrumWatcher
import fr.acinq.eclair.channel.ChannelState
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
import fr.acinq.eclair.wire.Init
import fr.acinq.eclair.wire.LightningMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch

val activatedFeatures = Features(
    setOf(
        ActivatedFeature(Feature.OptionDataLossProtect, FeatureSupport.Mandatory),
        ActivatedFeature(Feature.VariableLengthOnion, FeatureSupport.Optional),
        ActivatedFeature(Feature.PaymentSecret, FeatureSupport.Optional),
        ActivatedFeature(Feature.BasicMultiPartPayment, FeatureSupport.Optional),
        ActivatedFeature(Feature.Wumbo, FeatureSupport.Optional),
        ActivatedFeature(Feature.StaticRemoteKey, FeatureSupport.Optional),
        ActivatedFeature(Feature.TrampolinePayment, FeatureSupport.Optional),
        ActivatedFeature(Feature.AnchorOutputs, FeatureSupport.Optional),
    )
)

private val remoteNodeId = PublicKey.fromHex("039dc0e0b1d25905e44fdf6f8e89755a5e219685840d0bc1d28d3308f9628a3585")
private val emptyNodeUri = NodeUri(remoteNodeId, "empty", 8080)

@OptIn(ExperimentalCoroutinesApi::class)
public suspend fun newPeers(scope: CoroutineScope, nodeParams: Pair<NodeParams, NodeParams>, initChannels: List<Pair<ChannelStateWithCommitments, ChannelStateWithCommitments>> = emptyList()): Pair<Peer, Peer> {
    val alice = buildPeer(scope, nodeParams.first, databases = newDatabases().apply {
        initChannels.forEach { channels.addOrUpdateChannel(it.first) }
    })
    val bob = buildPeer(scope, nodeParams.second, databases = newDatabases().apply {
        initChannels.forEach { channels.addOrUpdateChannel(it.second) }
    })

    scope.launch {
        alice.output.consumeEach {
            val msg = LightningMessage.decode(it)
            println("Alice forwards to Bob $msg")
            bob.send(BytesReceived(it))
        }
    }
    scope.launch {
        bob.output.consumeEach {
            val msg = LightningMessage.decode(it)
            println("Bob forwards to Alice $msg")
            alice.send(BytesReceived(it))
        }
    }

    val init = LightningMessage.encode(Init(features = activatedFeatures.toByteArray().toByteVector()))
        ?: error("LN message `Init` encoding failed")
    // Initialize Alice
    alice.send(BytesReceived(init))
    // Initialize Bob
    bob.send(BytesReceived(init))

    // Wait until the Peers are ready
    alice.waitForStatus(Connection.ESTABLISHED)
    bob.waitForStatus(Connection.ESTABLISHED)

    // Wait until the [Channels] are synchronised
    alice.channelsFlow.first { it.size == initChannels.size && it.values.all { state -> state is Normal } }
    bob.channelsFlow.first { it.size == initChannels.size && it.values.all { state -> state is Normal } }

    return alice to bob
}

public suspend fun CoroutineScope.newPeer(
    nodeParams: NodeParams,
    remotedNodeChannelState: ChannelStateWithCommitments? = null,
    setupDatabases: suspend InMemoryDatabases.() -> Unit = {},
): Peer {
    val db = newDatabases().apply { setupDatabases(this) }

    val peer = buildPeer(this, nodeParams, db)

    // send Init from remote node
    val theirInit = Init(features = activatedFeatures.toByteArray().toByteVector())

    val initMsg = LightningMessage.encode(theirInit) ?: error("LN message `Init` encoding failed")
    peer.send(BytesReceived(initMsg))
    peer.waitForStatus(Connection.ESTABLISHED)

    peer.channelsFlow.first {
        it.values.size == peer.db.channels.listLocalChannels().size
                && it.values.all { channelState -> channelState is Syncing }
    }

    remotedNodeChannelState?.let { state ->
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

        val msg = LightningMessage.encode(channelReestablish) ?: error("LN message `ChannelReestablish` encoding failed")
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

public fun newDatabases(
    channels: InMemoryChannelsDb = InMemoryChannelsDb(),
    payments: InMemoryPaymentsDb = InMemoryPaymentsDb(),
) = InMemoryDatabases(channels, payments)

suspend fun Peer.waitForStatus(await: Connection) = connectionState.first { it == await }

suspend inline fun <reified Status : ChannelState> Peer.waitForChannel(
    id: ByteVector32? = null,
    noinline waitCondition: (suspend Status.() -> Boolean)? = null,
): Pair<ByteVector32, Status> =
    channelsFlow
        .mapNotNull { map ->
            map.entries.find {
                (id == null || it.key == id) &&
                        it.value is Status &&
                        waitCondition?.invoke(it.value as Status) ?: true
            }
        }
        .map { it.key to it.value as Status }
        .first()
