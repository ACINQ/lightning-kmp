package fr.acinq.eclair

import com.typesafe.config.ConfigFactory
import fr.acinq.bitcoin.*
import fr.acinq.eclair.Eclair.randomBytes32
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
import io.ktor.application.*
import io.ktor.client.features.json.serializer.KotlinxSerializer.Companion.DefaultJson
import io.ktor.features.*
import io.ktor.http.*
import io.ktor.request.*
import io.ktor.response.*
import io.ktor.routing.*
import io.ktor.serialization.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import kotlinx.serialization.Serializable
import java.io.File
import java.nio.file.Files
import java.sql.DriverManager
import kotlin.concurrent.thread


@OptIn(ExperimentalUnsignedTypes::class, ExperimentalCoroutinesApi::class, ObsoleteCoroutinesApi::class)
object Node {
    private val logger = newEclairLogger()

    @Serializable
    data class Ping(val payload: String)

    @Serializable
    data class CreateInvoiceRequest(val amount: Long?, val description: String?)

    @Serializable
    data class CreateInvoiceResponse(val invoice: String)

    @Serializable
    data class PayInvoiceRequest(val invoice: String, val amount: Long? = null)

    @Serializable
    data class PayInvoiceResponse(val status: String)

    @Serializable
    data class CloseChannelRequest(val channelId: String)

    @Serializable
    data class CloseChannelResponse(val status: String)

    fun parseUri(uri: String): Triple<PublicKey, String, Int> {
        val a = uri.split('@')
        require(a.size == 2) { "invalid node URI: $uri" }
        val b = a[1].split(':')
        require(b.size == 2) { "invalid node URI: $uri" }
        return Triple(PublicKey.fromHex(a[0]), b[0], b[1].toInt())
    }

    fun parseElectrumServerAddress(address: String): ServerAddress {
        val a = address.split(':')
        require(a.size == 3) { "invalid server address: $address" }
        val tls = when (a[2]) {
            "tls" -> TcpSocket.TLS.UNSAFE_CERTIFICATES
            else -> null
        }
        return ServerAddress(a[0], a[1].toInt(), tls)
    }

    /**
     * Order of precedence for the configuration parameters:
     * 1) Java environment variables (-D...)
     * 2) Configuration file phoenix.conf
     * 3) Optionally provided config
     * 4) Default values in reference.conf
     */
    fun loadConfiguration(datadir: File) = ConfigFactory.parseProperties(System.getProperties())
        .withFallback(ConfigFactory.parseFile(File(datadir, "phoenix.conf")))
        .withFallback(ConfigFactory.load())

    fun getSeed(datadir: File): ByteVector32 {
        val seedPath = File(datadir, "seed.dat")
        return if (seedPath.exists()) {
            ByteVector32(Files.readAllBytes(seedPath.toPath()))
        } else {
            datadir.mkdirs()
            val seed = randomBytes32()
            logger.warning { "no seed was found, creating new one" }
            Files.write(seedPath.toPath(), seed.toByteArray())
            seed
        }
    }

    @JvmStatic
    fun main(args: Array<String>) {
        val datadir = File(System.getProperty("phoenix.datadir", System.getProperty("user.home") + "/.phoenix"))
        logger.info { "datadir = $datadir" }
        datadir.mkdirs()
        val config = loadConfiguration(datadir)
        val seed = getSeed(datadir)
        val chain = config.getString("phoenix.chain")
        val chainHash: ByteVector32 = when (chain) {
            "regtest" -> Block.RegtestGenesisBlock.hash
            "testnet" -> Block.TestnetGenesisBlock.hash
            else -> error("invalid chain $chain")
        }
        val chaindir = File(datadir, chain)
        chaindir.mkdirs()
        val nodeUri = config.getString("phoenix.trampoline-node-uri")
        val (nodeId, nodeAddress, nodePort) = parseUri(nodeUri)
        val electrumServerAddress = parseElectrumServerAddress(config.getString("phoenix.electrum-server"))
        val keyManager = LocalKeyManager(seed, chainHash)
        logger.info { "node ${keyManager.nodeId} is starting" }
        val channelsDb = SqliteChannelsDb(DriverManager.getConnection("jdbc:sqlite:${File(chaindir, "phoenix.sqlite")}"))
        val nodeParams = NodeParams(
            keyManager = keyManager,
            alias = "phoenix",
            features = Features(
                setOf(
                    ActivatedFeature(Feature.OptionDataLossProtect, FeatureSupport.Mandatory),
                    ActivatedFeature(Feature.VariableLengthOnion, FeatureSupport.Optional),
                    ActivatedFeature(Feature.PaymentSecret, FeatureSupport.Optional),
                    ActivatedFeature(Feature.BasicMultiPartPayment, FeatureSupport.Optional),
                    ActivatedFeature(Feature.Wumbo, FeatureSupport.Optional),
                    ActivatedFeature(Feature.StaticRemoteKey, FeatureSupport.Optional),
                )
            ),
            dustLimit = 546.sat,
            onChainFeeConf = OnChainFeeConf(
                maxFeerateMismatch = 1000.0,
                closeOnOfflineMismatch = true,
                updateFeeMinDiffRatio = 0.1
            ),
            maxHtlcValueInFlightMsat = 5000000000L,
            maxAcceptedHtlcs = 30,
            expiryDeltaBlocks = CltvExpiryDelta(144),
            fulfillSafetyBeforeTimeoutBlocks = CltvExpiryDelta(24),
            htlcMinimum = 1.msat,
            minDepthBlocks = 3,
            toRemoteDelayBlocks = CltvExpiryDelta(2016),
            maxToLocalDelayBlocks = CltvExpiryDelta(2016),
            feeBase = 1000.msat,
            feeProportionalMillionth = 100,
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
            chainHash = chainHash,
            channelFlags = 0,
            paymentRequestExpiry = 3600,
            multiPartPaymentExpiry = 60,
            minFundingSatoshis = 100000.sat,
            maxFundingSatoshis = 16777215.sat,
            maxPaymentAttempts = 5,
            trampolineNode = NodeUri(nodeId, nodeAddress, nodePort),
            enableTrampolinePayment = true
        )

        // remote node on regtest is initialized with the following seed: 0202020202020202020202020202020202020202020202020202020202020202
//        val nodeId = PublicKey.fromHex("039dc0e0b1d25905e44fdf6f8e89755a5e219685840d0bc1d28d3308f9628a3585")

        Class.forName("org.sqlite.JDBC")

        suspend fun connectLoop(peer: Peer) {
            peer.openConnectedSubscription().consumeEach {
                logger.info { "Connected: $it" }
            }
        }

        runBlocking {
            val electrum = ElectrumClient(TcpSocket.Builder(), this).apply { connect(electrumServerAddress) }
            val watcher = ElectrumWatcher(electrum, this)
            val peer = Peer(TcpSocket.Builder(), nodeParams, nodeId, watcher, channelsDb, this)

            launch { connectLoop(peer) }

            embeddedServer(Netty, 8080) {
                install(StatusPages) {
                    exception<Throwable> {
                        call.respondText(it.localizedMessage, ContentType.Text.Plain, HttpStatusCode.InternalServerError)
                    }
                }
                install(ContentNegotiation) {
                    register(ContentType.Application.Json, SerializationConverter(Json {
                        serializersModule = eclairSerializersModule
                    }))
                }
                routing {
                    post("/ping") {
                        val ping = call.receive<Ping>()
                        call.respond(ping)
                    }
                    post("/invoice/create") {
                        val request = call.receive<CreateInvoiceRequest>()
                        val paymentPreimage = Eclair.randomBytes32()
                        val amount = MilliSatoshi(request.amount ?: 50000L)
                        val result = CompletableDeferred<PaymentRequest>()
                        peer.send(ReceivePayment(paymentPreimage, amount, request.description.orEmpty(), result))
                        call.respond(CreateInvoiceResponse(result.await().write()))
                    }
                    post("/invoice/pay") {
                        val request = call.receive<PayInvoiceRequest>()
                        val pr = PaymentRequest.read(request.invoice)
                        peer.send(SendPayment(UUID.randomUUID(), pr, pr.amount ?: request.amount?.run { MilliSatoshi(this) } ?: MilliSatoshi(50000)))
                        call.respond(PayInvoiceResponse("pending"))
                    }
                    get("/channels") {
                        val channels = CompletableDeferred<List<ChannelState>>()
                        peer.send(ListChannels(channels))
                        call.respond(channels.await())
                    }
                    post("/channels/{channelId}/close") {
                        val channelId = ByteVector32(call.parameters["channelId"] ?: error("channelId not provided"))
                        peer.send(WrappedChannelEvent(channelId, ExecuteCommand(CMD_CLOSE(null))))
                        call.respond(CloseChannelResponse("pending"))
                    }
                }
            }.start(wait = false)

            launch { peer.connect(nodeAddress, nodePort) }
        }
    }
}
