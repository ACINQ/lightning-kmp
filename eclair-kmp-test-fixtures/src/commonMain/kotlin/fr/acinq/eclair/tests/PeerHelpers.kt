package fr.acinq.eclair.tests

import fr.acinq.bitcoin.Block
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.bitcoin.PublicKey
import fr.acinq.eclair.*
import fr.acinq.eclair.blockchain.electrum.ElectrumClient
import fr.acinq.eclair.blockchain.electrum.ElectrumWatcher
import fr.acinq.eclair.blockchain.fee.FeerateTolerance
import fr.acinq.eclair.blockchain.fee.OnChainFeeConf
import fr.acinq.eclair.channel.ChannelState
import fr.acinq.eclair.channel.Normal
import fr.acinq.eclair.channel.Syncing
import fr.acinq.eclair.crypto.LocalKeyManager
import fr.acinq.eclair.tests.db.InMemoryChannelsDb
import fr.acinq.eclair.tests.db.InMemoryPaymentsDb
import fr.acinq.eclair.io.*
import fr.acinq.eclair.tests.db.InMemoryDatabases
import fr.acinq.eclair.utils.*
import fr.acinq.eclair.wire.ChannelReestablish
import fr.acinq.eclair.wire.Init
import fr.acinq.eclair.wire.LightningMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull


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

public suspend fun CoroutineScope.newPeer(
    setupDatabases: suspend InMemoryDatabases.() -> Unit = {},
): Peer {
    val db = newDatabases().apply { setupDatabases(this) }

    val peer = buildPeer(scope = this, databases = db)

    // send Init from remote node
    val theirInit = Init(features = activatedFeatures.toByteArray().toByteVector())

    val initMsg = LightningMessage.encode(theirInit) ?: error("LN message `Init` encoding failed")
    peer.send(BytesReceived(initMsg))
    peer.waitForStatus(Connection.ESTABLISHED)

    peer.channelsFlow.first {
        it.values.size == peer.db.channels.listLocalChannels().size
            && it.values.all { channelState -> channelState is Syncing }
    }

    peer.db.channels.listLocalChannels().forEach { state ->
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
    nodeUri: NodeUri = emptyNodeUri,
    databases: InMemoryDatabases = InMemoryDatabases(),
): Peer {
    val chainHash: ByteVector32 = Block.RegtestGenesisBlock.hash
    val keyManager = LocalKeyManager(Eclair.randomBytes32(), chainHash)

    val params = NodeParams(
        keyManager = keyManager,
        alias = "phoenix",
        features = activatedFeatures,
        dustLimit = 546.sat,
        onChainFeeConf = OnChainFeeConf(
            closeOnOfflineMismatch = true,
            updateFeeMinDiffRatio = 0.1,
            feerateTolerance = FeerateTolerance(ratioLow = 0.5, ratioHigh = 2.0)
        ),
        maxHtlcValueInFlightMsat = 150000000L,
        maxAcceptedHtlcs = 30,
        expiryDeltaBlocks = CltvExpiryDelta(144),
        fulfillSafetyBeforeTimeoutBlocks = CltvExpiryDelta(6),
        htlcMinimum = 1000.msat,
        minDepthBlocks = 3,
        toRemoteDelayBlocks = CltvExpiryDelta(144),
        maxToLocalDelayBlocks = CltvExpiryDelta(1000),
        feeBase = 1000.msat,
        feeProportionalMillionth = 10,
        reserveToFundingRatio = 0.01, // note: not used (overridden below)
        maxReserveToFundingRatio = 0.05,
        revocationTimeoutSeconds = 20,
        authTimeoutSeconds = 10,
        initTimeoutSeconds = 10,
        pingIntervalSeconds = 30,
        pingTimeoutSeconds = 10,
        pingDisconnect = true,
        autoReconnect = false,
        initialRandomReconnectDelaySeconds = 5,
        maxReconnectIntervalSeconds = 3600,
        chainHash = Block.RegtestGenesisBlock.hash,
        channelFlags = 1,
        paymentRequestExpirySeconds = 3600,
        multiPartPaymentExpirySeconds = 30,
        minFundingSatoshis = 1000.sat,
        maxFundingSatoshis = 16777215.sat,
        maxPaymentAttempts = 5,
        trampolineNode = nodeUri,
        enableTrampolinePayment = true
    )
    val electrum = ElectrumClient(TcpSocket.Builder(), scope)
    val watcher = ElectrumWatcher(electrum, scope)

    return Peer(TcpSocket.Builder(), params, watcher, databases, scope)
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
