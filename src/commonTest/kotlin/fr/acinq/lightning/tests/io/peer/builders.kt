package fr.acinq.lightning.tests.io.peer

import fr.acinq.bitcoin.Block
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.lightning.NodeParams
import fr.acinq.lightning.WalletParams
import fr.acinq.lightning.blockchain.electrum.ElectrumClient
import fr.acinq.lightning.blockchain.electrum.ElectrumWatcher
import fr.acinq.lightning.blockchain.fee.FeeratePerByte
import fr.acinq.lightning.blockchain.fee.FeeratePerKw
import fr.acinq.lightning.blockchain.fee.OnChainFeerates
import fr.acinq.lightning.channel.ChannelStateWithCommitments
import fr.acinq.lightning.channel.LNChannel
import fr.acinq.lightning.channel.Normal
import fr.acinq.lightning.channel.Syncing
import fr.acinq.lightning.db.InMemoryDatabases
import fr.acinq.lightning.io.BytesReceived
import fr.acinq.lightning.io.Peer
import fr.acinq.lightning.io.TcpSocket
import fr.acinq.lightning.utils.Connection
import fr.acinq.lightning.utils.sat
import fr.acinq.lightning.utils.toByteVector
import fr.acinq.lightning.wire.ChannelReady
import fr.acinq.lightning.wire.ChannelReestablish
import fr.acinq.lightning.wire.Init
import fr.acinq.lightning.wire.LightningMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.kodein.log.LoggerFactory

public suspend fun newPeers(
    scope: CoroutineScope,
    nodeParams: Pair<NodeParams, NodeParams>,
    walletParams: Pair<WalletParams, WalletParams>,
    initChannels: List<Pair<LNChannel<ChannelStateWithCommitments>, LNChannel<ChannelStateWithCommitments>>> = emptyList(),
    automateMessaging: Boolean = true
): PeerTuple {
    // Create Alice and Bob peers
    val alice = buildPeer(scope, nodeParams.first, walletParams.first, databases = InMemoryDatabases().apply {
        initChannels.forEach { channels.addOrUpdateChannel(it.first.state) }
    })
    val bob = buildPeer(scope, nodeParams.second, walletParams.second, databases = InMemoryDatabases().apply {
        initChannels.forEach { channels.addOrUpdateChannel(it.second.state) }
    })

    // Create collectors for Alice and Bob output messages
    val bob2alice = flow {
        while (scope.isActive) {
            val bytes = bob.outputLightningMessages.receive()
            val msg = LightningMessage.decode(bytes)
            println("Bob sends $msg")
            emit(msg)
        }
    }
    val alice2bob = flow {
        while (scope.isActive) {
            val bytes = alice.outputLightningMessages.receive()
            val msg = LightningMessage.decode(bytes)
            println("Alice sends $msg")
            emit(msg)
        }
    }

    // Initialize Bob with Alice's features
    bob.send(BytesReceived(LightningMessage.encode(Init(features = nodeParams.first.features.initFeatures().toByteArray().toByteVector()))))
    // Initialize Alice with Bob's features
    alice.send(BytesReceived(LightningMessage.encode(Init(features = nodeParams.second.features.initFeatures().toByteArray().toByteVector()))))

    // TODO update to depend on the initChannels size
    if (initChannels.isNotEmpty()) {
        val bobInit = scope.launch {
            val bobChannelReestablish = bob2alice.expect<ChannelReestablish>()
            alice.forward(bobChannelReestablish)
            val bobChannelReady = bob2alice.expect<ChannelReady>()
            alice.forward(bobChannelReady)
        }
        val aliceInit = scope.launch {
            val aliceChannelReestablish = alice2bob.expect<ChannelReestablish>()
            bob.forward(aliceChannelReestablish)
            val aliceChannelReady = alice2bob.expect<ChannelReady>()
            bob.forward(aliceChannelReady)
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
    walletParams: WalletParams,
    remotedNodeChannelState: LNChannel<ChannelStateWithCommitments>? = null,
    setupDatabases: suspend InMemoryDatabases.() -> Unit = {},
): Peer {
    val db = InMemoryDatabases().apply { setupDatabases(this) }

    val peer = buildPeer(this, nodeParams, walletParams, db)

    remotedNodeChannelState?.let { state ->
        // send Init from remote node
        val theirInit = Init(features = state.ctx.staticParams.nodeParams.features.initFeatures().toByteArray().toByteVector())

        val initMsg = LightningMessage.encode(theirInit)
        peer.send(BytesReceived(initMsg))
        peer.expectStatus(Connection.ESTABLISHED)

        peer.channelsFlow.first {
            it.values.size == peer.db.channels.listLocalChannels().size
                    && it.values.all { channelState -> channelState is Syncing }
        }

        val yourLastPerCommitmentSecret = state.commitments.remotePerCommitmentSecrets.lastIndex?.let { state.commitments.remotePerCommitmentSecrets.getHash(it) } ?: ByteVector32.Zeroes
        val channelKeyPath = peer.nodeParams.keyManager.channelKeyPath(state.commitments.localParams, state.commitments.channelConfig)
        val myCurrentPerCommitmentPoint = peer.nodeParams.keyManager.commitmentPoint(channelKeyPath, state.commitments.localCommit.index)

        val channelReestablish = ChannelReestablish(
            channelId = state.channelId,
            nextLocalCommitmentNumber = state.commitments.localCommit.index + 1,
            nextRemoteRevocationNumber = state.commitments.remoteCommit.index,
            yourLastCommitmentSecret = PrivateKey(yourLastPerCommitmentSecret),
            myCurrentPerCommitmentPoint = myCurrentPerCommitmentPoint
        ).withChannelData(state.commitments.remoteChannelData)

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
    walletParams: WalletParams,
    databases: InMemoryDatabases = InMemoryDatabases()
): Peer {
    val electrum = ElectrumClient(TcpSocket.Builder(), scope, LoggerFactory.default)
    val watcher = ElectrumWatcher(electrum.Caller(), scope, LoggerFactory.default)
    val peer = Peer(nodeParams, walletParams, watcher, databases, TcpSocket.Builder(), scope)
    peer.currentTipFlow.value = 0 to Block.RegtestGenesisBlock.header
    peer.onChainFeeratesFlow.value = OnChainFeerates(
        mutualCloseFeerate = FeeratePerKw(FeeratePerByte(20.sat)),
        claimMainFeerate = FeeratePerKw(FeeratePerByte(20.sat)),
        fastFeerate = FeeratePerKw(FeeratePerByte(50.sat))
    )

    return peer
}

data class PeerTuple(val alice: Peer, val bob: Peer, val alice2bob: Flow<LightningMessage>, val bob2alice: Flow<LightningMessage>)