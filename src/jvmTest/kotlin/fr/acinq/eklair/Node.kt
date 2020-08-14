package fr.acinq.eklair

import fr.acinq.bitcoin.Block
import fr.acinq.bitcoin.BlockHeader
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.PublicKey
import fr.acinq.eklair.blockchain.electrum.*
import fr.acinq.eklair.blockchain.fee.FeeTargets
import fr.acinq.eklair.blockchain.fee.OnChainFeeConf
import fr.acinq.eklair.blockchain.fee.TestFeeEstimator
import fr.acinq.eklair.channel.NewBlock
import fr.acinq.eklair.crypto.LocalKeyManager
import fr.acinq.eklair.io.*
import fr.acinq.eklair.payment.PaymentRequest
import fr.acinq.eklair.utils.msat
import fr.acinq.eklair.utils.sat
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.consumeEach


@OptIn(ExperimentalUnsignedTypes::class, ExperimentalCoroutinesApi::class, ObsoleteCoroutinesApi::class)
object Node {
    val seed = ByteVector32("0101010101010101010101010101010101010101010101010101010101010101")
    val keyManager = LocalKeyManager(seed, Block.RegtestGenesisBlock.hash)
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
            feeTargets = FeeTargets(6, 2, 2, 6),
            feeEstimator = TestFeeEstimator(10000),
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
        multiPartPaymentExpiry = 30,
        minFundingSatoshis = 1000.sat,
        maxFundingSatoshis = 16777215.sat,
        maxPaymentAttempts = 5,
        enableTrampolinePayment = true
    )

//    private val serializationModules = SerializersModule {
//        include(Tlv.serializationModule)
//        include(KeyManager.serializationModule)
//
//        include(TestFeeEstimator.testSerializationModule)
//    }
//
//    private val json = Json {
////        prettyPrint = true
//        serializersModule = serializationModules
//    }
//
//    private val cbor = Cbor {
//        serializersModule = serializationModules
//    }
//
//    private val mapSerializer = MapSerializer(ByteVector32KSerializer, State.serializer())

    @JvmStatic
    fun main(args: Array<String>) {
        // remote node on regtest is initialized with the following seed: 0202020202020202020202020202020202020202020202020202020202020202
        val nodeId = PublicKey.fromHex("039dc0e0b1d25905e44fdf6f8e89755a5e219685840d0bc1d28d3308f9628a3585")

        val commandChannel = Channel<List<String>>(2)

        suspend fun connectLoop(peer: Peer) {
            peer.openConnectedSubscription().consumeEach {
                println("Connected: $it")
            }
        }

//        suspend fun channelsLoop() {
//            try {
//                peer.openChannelsSubscription().consumeEach {
//                    println("===== Original =====")
//                    println(it)
//                    println("===== JSON =====")
//                    val jsonStr = json.encodeToString(mapSerializer, it)
//                    println(jsonStr)
//                    println("===== CBOR =====")
//                    val cborHex = cbor.encodeToHexString(mapSerializer, it)
//                    println(cborHex)
//                    println("===== Decoded =====")
//                    val states = cbor.decodeFromHexString(mapSerializer, cborHex)
//                    println(states)
//                    println("===== Decoded is equal to original? =====")
//                    println(states == it)
//                    println("===== THE END! =====")
//                }
//            } catch (ex: Throwable) {
//                ex.printStackTrace()
//            }
//        }

        suspend fun eventLoop(peer: Peer) {
            peer.openListenerEventSubscription().consumeEach {
                println("Event: $it")
            }
        }

        suspend fun readLoop(peer: Peer) {
            println("node ${nodeParams.nodeId} is ready:")
            for(tokens in commandChannel) {
                println("got tokens $tokens")
                when (tokens.first()) {
                    "connect" -> {
                        val host = tokens[1]
                        val port = tokens[2].toInt()
                        println("connecting")
                        GlobalScope.launch { peer.connect(host, port) }
                    }
                    "receive" -> {
                        val paymentPreimage = ByteVector32(tokens[1])
                        val amount = MilliSatoshi(tokens[2].toLong())
                        peer.send(ReceivePayment(paymentPreimage, amount, CltvExpiry(100)))
                    }
                    "pay" -> {
                        val invoice = PaymentRequest.read(tokens[1])
                        peer.send(SendPayment(invoice))
                    }
                    "newblock" -> {
                        val height = tokens[1].toInt()
                        val header = BlockHeader.read(tokens[2])
                        peer.send(WrappedChannelEvent(ByteVector32.Zeroes, NewBlock(height, header)))
                    }
                    else -> {
                        println("I don't understand $tokens")
                    }
                }
            }
        }

        suspend fun writeLoop() {
            while (true) {
                val line = readLine()
                line?.let {
                    val tokens = it.split(" ")
                    println("tokens: $tokens")
                    commandChannel.send(tokens)
                }
            }
        }

        runBlocking {
            val electrum = ElectrumClient("localhost", 51001, null, this).apply { start() }
            val watcher = ElectrumWatcher(electrum, this).apply { start() }
            val peer = Peer(TcpSocket.Builder(), nodeParams, nodeId, watcher)
            val electrumChannel = Channel<ElectrumMessage>(2)
            launch {
                for(msg in electrumChannel) {
                    when(msg) {
                        is ElectrumClientReady -> electrum.sendMessage(ElectrumHeaderSubscription(electrumChannel))
                        is HeaderSubscriptionResponse -> peer.send(WrappedChannelEvent(ByteVector32.Zeroes, NewBlock(msg.height, msg.header)))
                    }
                }
            }
            electrum.sendMessage(ElectrumStatusSubscription(electrumChannel))
            launch { readLoop(peer) }
            launch(newSingleThreadContext("Keyboard Input")) { writeLoop() } // Will get its own new thread
            launch { connectLoop(peer) }
            //launch { channelsLoop() }
            launch { eventLoop(peer) }
        }
    }
}
