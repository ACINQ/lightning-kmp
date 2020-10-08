package fr.acinq.eclair

import fr.acinq.bitcoin.*
import fr.acinq.eclair.blockchain.electrum.ElectrumClient
import fr.acinq.eclair.blockchain.electrum.ElectrumWatcher
import fr.acinq.eclair.blockchain.fee.OnChainFeeConf
import fr.acinq.eclair.channel.CMD_CLOSE
import fr.acinq.eclair.channel.ChannelState
import fr.acinq.eclair.channel.ExecuteCommand
import fr.acinq.eclair.crypto.LocalKeyManager
import fr.acinq.eclair.db.sqlite.SqliteChannelsDb
import fr.acinq.eclair.io.*
import fr.acinq.eclair.payment.PaymentRequest
import fr.acinq.eclair.utils.*
import fr.acinq.secp256k1.Hex
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.consumeEach
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.decodeFromHexString
import kotlinx.serialization.encodeToHexString
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import java.sql.DriverManager
import kotlin.concurrent.thread


@OptIn(ExperimentalUnsignedTypes::class, ExperimentalCoroutinesApi::class, ObsoleteCoroutinesApi::class)
object Node {
    val seed = ByteVector32("0101010101010101010101010101010101010101010101010101010101010101")
    val keyManager = LocalKeyManager(seed, Block.RegtestGenesisBlock.hash)
    val defaultClosingPrivateKey = run {
        val master = DeterministicWallet.generate(seed)
        DeterministicWallet.derivePrivateKey(master, "m/84'/1'/0'/0/0").privateKey
    }
    val defaultClosingPubkeyScript = Script.write(Script.pay2wpkh(defaultClosingPrivateKey.publicKey()))

    val nodeParams = NodeParams(
        keyManager = keyManager,
        alias = "alice",
        features = Features(
            setOf(
                ActivatedFeature(Feature.OptionDataLossProtect, FeatureSupport.Optional),
                ActivatedFeature(Feature.VariableLengthOnion, FeatureSupport.Optional)
            )
        ),
        dustLimit = 100.sat,
        onChainFeeConf = OnChainFeeConf(
            maxFeerateMismatch = 1.5,
            closeOnOfflineMismatch = true,
            updateFeeMinDiffRatio = 0.1
        ),
        maxHtlcValueInFlightMsat = 150000000L,
        maxAcceptedHtlcs = 100,
        expiryDeltaBlocks = CltvExpiryDelta(144),
        fulfillSafetyBeforeTimeoutBlocks = CltvExpiryDelta(6),
        htlcMinimum = 0.msat,
        minDepthBlocks = 3,
        toRemoteDelayBlocks = CltvExpiryDelta(144),
        maxToLocalDelayBlocks = CltvExpiryDelta(1000),
        feeBase = 546000.msat,
        feeProportionalMillionth = 10,
        reserveToFundingRatio = 0.01, // note: not used (overridden below)
        maxReserveToFundingRatio = 0.05,
        revocationTimeout = 20,
        authTimeout = 10,
        initTimeout = 10,
        pingInterval = 30,
        pingTimeout = 10,
        pingDisconnect = true,
        autoReconnect = false,
        initialRandomReconnectDelay = 5,
        maxReconnectInterval = 3600,
        chainHash = Block.RegtestGenesisBlock.hash,
        channelFlags = 1,
        paymentRequestExpiry = 3600,
        multiPartPaymentExpiry = 60,
        minFundingSatoshis = 1000.sat,
        maxFundingSatoshis = 16777215.sat,
        maxPaymentAttempts = 5,
        enableTrampolinePayment = true
    )

    private val serializationModules = SerializersModule {
        include(eclairSerializersModule)
    }

    private val json = Json {
        serializersModule = serializationModules
    }

    @OptIn(ExperimentalSerializationApi::class)
    private val cbor = Cbor {
        serializersModule = serializationModules
    }

    private val mapSerializer = MapSerializer(ByteVector32KSerializer, ChannelState.serializer())

    @JvmStatic
    fun main(args: Array<String>) {
        // remote node on regtest is initialized with the following seed: 0202020202020202020202020202020202020202020202020202020202020202
        val nodeId = PublicKey.fromHex("039dc0e0b1d25905e44fdf6f8e89755a5e219685840d0bc1d28d3308f9628a3585")

        val commandChannel = Channel<List<String>>(2)

        Class.forName("org.sqlite.JDBC")

        suspend fun connectLoop(peer: Peer) {
            peer.openConnectedSubscription().consumeEach {
                println("Connected: $it")
            }
        }

        suspend fun channelsLoop(peer: Peer) {
            try {
                peer.openChannelsSubscription().consumeEach {
                    val cborHex = cbor.encodeToHexString(mapSerializer, it)
                    println("CBOR: $cborHex")
                    println("JSON: ${json.encodeToString(mapSerializer, it)}")
                    val dec = cbor.decodeFromHexString(mapSerializer, cborHex)
                    println("Serialization resistance: ${it == dec}")
                }
            } catch (ex: Throwable) {
                ex.printStackTrace()
                throw ex
            }
        }

        suspend fun eventLoop(peer: Peer) {
            peer.openListenerEventSubscription().consumeEach {
                println("Event: $it")
            }
        }

        suspend fun readLoop(peer: Peer) {
            println("node ${nodeParams.nodeId} is ready:")
            for (tokens in commandChannel) {
                println("got tokens $tokens")
                when (tokens.first()) {
                    "connect" -> {
                        println("connecting")
                        GlobalScope.launch { peer.connect("localhost", 48001) }
                    }
                    "receive" -> {
                        val paymentPreimage = ByteVector32(tokens[1])
                        val amount = if (tokens.size >= 3) MilliSatoshi(tokens[2].toLong()) else null
                        peer.send(ReceivePayment(paymentPreimage, amount, CltvExpiry(100), "this is a kotlin test"))
                    }
                    "pay" -> {
                        val invoice = PaymentRequest.read(tokens[1])
                        peer.send(SendPayment(UUID.randomUUID(), invoice))
                    }
                    else -> {
                        println("I don't understand $tokens")
                    }
                }
            }
        }

        fun writeLoop() {
            while (true) {
                val line = readLine()
                line?.let {
                    val tokens = it.split(" ")
                    println("tokens: $tokens")
                    runBlocking { commandChannel.send(tokens) }
                }
            }
        }

        thread(isDaemon = true, block = ::writeLoop)

        runBlocking {
            val electrum = ElectrumClient(TcpSocket.Builder(), this).apply { connect(ServerAddress("localhost", 51001, null)) }
            val watcher = ElectrumWatcher(electrum, this)
            val channelsDb = SqliteChannelsDb(DriverManager.getConnection("jdbc:sqlite:/tmp/eclair-kmp-node-channels.db"))
            val peer = Peer(TcpSocket.Builder(), nodeParams, nodeId, watcher, channelsDb, this)

            launch { readLoop(peer) }
            launch { connectLoop(peer) }
            launch { channelsLoop(peer) }
            launch { eventLoop(peer) }

            launch { peer.connect("localhost", 48001) }
        }
    }
}
