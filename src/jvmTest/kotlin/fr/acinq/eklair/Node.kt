package fr.acinq.eklair

import fr.acinq.bitcoin.Block
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.PublicKey
import fr.acinq.eklair.blockchain.fee.FeeTargets
import fr.acinq.eklair.blockchain.fee.OnChainFeeConf
import fr.acinq.eklair.blockchain.fee.TestFeeEstimator
import fr.acinq.eklair.crypto.LocalKeyManager
import fr.acinq.eklair.db.TestDatabases
import fr.acinq.eklair.io.TcpSocket
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
            feeEstimator = TestFeeEstimator().setFeerate(10000),
            maxFeerateMismatch = 1.5,
            closeOnOfflineMismatch = true,
            updateFeeMinDiffRatio = 0.1
        ),
        maxHtlcValueInFlightMsat = 150000000UL,
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
        db = TestDatabases(),
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

    @JvmStatic
    fun main(args: Array<String>) {
        // remote node on regtest is initialized with the following seed: 0202020202020202020202020202020202020202020202020202020202020202
        val nodeId = PublicKey.fromHex("02d684ecbdbde1b556715a4a56186dfe045df1a0d18fe632843299254b482df7d9")
        val peer = Peer(TcpSocket.Builder(), nodeParams, nodeId)

        val commandChannel = Channel<List<String>>(2)

        suspend fun stateLoop() {
            peer.openStateSubscription().consumeEach {
                println("State: $it")
            }
        }

        suspend fun eventLoop() {
            peer.openListenerEventSubscription().consumeEach {
                println("Event: $it")
            }
        }

        suspend fun readLoop() {
            println("node ${nodeParams.nodeId} is ready:")
            for(tokens in commandChannel) {
                when (tokens.first()) {
                    "connect" -> {
                        val host = tokens[1]
                        val port = tokens[2].toInt()
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
                    commandChannel.send(tokens)
                }
            }
        }

        runBlocking {
            launch { readLoop() }
            launch(newSingleThreadContext("Keyboard Input")) { writeLoop() } // Will get its own new thread
            launch { stateLoop() }
            launch { eventLoop() }
        }
    }

}

