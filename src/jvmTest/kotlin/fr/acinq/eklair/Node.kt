package fr.acinq.eklair

import fr.acinq.bitcoin.*
import fr.acinq.bitcoin.io.ByteArrayInput
import fr.acinq.bitcoin.io.ByteArrayOutput
import fr.acinq.bitcoin.io.Output
import fr.acinq.eklair.blockchain.WatchConfirmed
import fr.acinq.eklair.blockchain.WatchEvent
import fr.acinq.eklair.blockchain.WatchEventConfirmed
import fr.acinq.eklair.blockchain.fee.FeeTargets
import fr.acinq.eklair.blockchain.fee.OnChainFeeConf
import fr.acinq.eklair.blockchain.fee.TestFeeEstimator
import fr.acinq.eklair.channel.*
import fr.acinq.eklair.crypto.LocalKeyManager
import fr.acinq.eklair.crypto.noise.*
import fr.acinq.eklair.db.TestDatabases
import fr.acinq.eklair.io.LightningSession
import fr.acinq.eklair.utils.msat
import fr.acinq.eklair.utils.sat
import fr.acinq.eklair.wire.*
import fr.acinq.secp256k1.Hex
import io.ktor.network.selector.*
import io.ktor.network.sockets.*
import io.ktor.utils.io.*
import io.ktor.utils.io.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.kodein.log.Logger
import org.kodein.log.LoggerFactory
import java.net.InetSocketAddress
import kotlin.text.toByteArray

sealed class PeerEvent
data class BytesReceived(val data: ByteArray) : PeerEvent()
data class WatchReceived(val watch: WatchEvent) : PeerEvent()

@ExperimentalStdlibApi
class Peer(
    val nodeParams: NodeParams,
    val remoteNodeId: PublicKey,
    val input: Channel<PeerEvent>,
    val output: Channel<ByteArray>
) {
    private val logger = LoggerFactory.default.newLogger(Logger.Tag(Peer::class))

    private val channels: HashMap<ByteVector32, State> = HashMap()

    private var theirInit: Init? = null

    suspend fun run() {
        for (event in input) {
            when {
                event is BytesReceived -> {
                    val msg = Wire.decode(event.data)
                    when  {
                        msg is Init -> {
                            logger.info { "received $msg" }
                            theirInit = msg
                        }
                        msg is Ping -> {
                            logger.info { "received $msg" }
                            val pong = Pong(ByteVector(ByteArray(msg.pongLength)))
                            output.send(Wire.encode(pong)!!)

                        }
                        msg is Pong -> {
                            logger.info { "received $msg" }
                        }
                        msg is Error && msg.channelId == ByteVector32.Zeroes -> {
                            logger.error { "connection error, failing all channels: ${msg.toAscii()}" }
                        }
                        msg is OpenChannel -> {
                            val localParams = LocalParams(
                                nodeParams.nodeId,
                                KeyPath("/1/2/3"),
                                nodeParams.dustLimit,
                                nodeParams.maxHtlcValueInFlightMsat,
                                Satoshi(600),
                                nodeParams.htlcMinimum,
                                nodeParams.toRemoteDelayBlocks,
                                nodeParams.maxAcceptedHtlcs,
                                false,
                                ByteVector.empty,
                                PrivateKey(ByteVector32("0101010101010101010101010101010101010101010101010101010101010101")).publicKey(),
                                Features(
                                    setOf(
                                        ActivatedFeature(Feature.OptionDataLossProtect, FeatureSupport.Mandatory),
                                        ActivatedFeature(Feature.VariableLengthOnion, FeatureSupport.Optional),
                                        ActivatedFeature(Feature.StaticRemoteKey, FeatureSupport.Optional),
                                        ActivatedFeature(Feature.PaymentSecret, FeatureSupport.Optional),
                                        ActivatedFeature(Feature.BasicMultiPartPayment, FeatureSupport.Optional),
                                        ActivatedFeature(Feature.Wumbo, FeatureSupport.Optional),
                                        ActivatedFeature(Feature.TrampolinePayment, FeatureSupport.Optional),
                                    )
                                )

                            )
                            val state = WaitForInit(StaticParams(nodeParams, remoteNodeId), Pair(0, Block.RegtestGenesisBlock.header))
                            val (state1, actions1) = state.process(InitFundee(msg.temporaryChannelId, localParams, theirInit!!))
                            val (state2, actions2) = state1.process(MessageReceived(msg))
                            (actions1  + actions2).forEach {
                                when(it) {
                                    is SendMessage -> {
                                        val encoded = Wire.encode(it.message)
                                        encoded?.let { output.send(it) }
                                    }
                                    else -> logger.warning {"ignoring action $it" }
                                }
                            }
                            channels.put(msg.temporaryChannelId, state2)
                            logger.info { "new state for ${msg.temporaryChannelId}: $state2" }
                        }
                        msg is HasTemporaryChannelId && !channels.containsKey(msg.temporaryChannelId) -> {
                            logger.error { "received $msg for unknown temporary channel ${msg.temporaryChannelId}" }
                        }
                        msg is HasTemporaryChannelId -> {
                            logger.info { "received $msg for temporary channel ${msg.temporaryChannelId}" }
                            val state = channels[msg.temporaryChannelId]!!
                            val (state1, actions) = state.process(MessageReceived(msg))
                            channels.put(msg.temporaryChannelId, state1)
                            logger.info { "channel ${msg.temporaryChannelId} new state $state1" }
                            actions.forEach {
                                when(it) {
                                    is SendMessage -> {
                                        val encoded = Wire.encode(it.message)
                                        encoded?.let { output.send(it) }
                                    }
                                    is ChannelIdSwitch -> {
                                        logger.info { "id switch from ${msg.temporaryChannelId} to ${it.newChannelId}" }
                                        channels.put(it.newChannelId, state1)
                                    }
                                    is SendWatch -> {
                                        if (it.watch is WatchConfirmed) {
                                            val tx = Transaction(
                                                version = 2,
                                                txIn = listOf(),
                                                txOut = listOf(TxOut(Satoshi(100), (it.watch as WatchConfirmed).publicKeyScript)),
                                                lockTime = 0L
                                            )
                                            input.send(WatchReceived(WatchEventConfirmed(it.watch.channelId, it.watch.event, 100, 0, tx)))
                                        }
                                    }
                                    else -> logger.warning { "ignoring $it" }
                                }
                            }
                        }
                        msg is HasChannelId && !channels.containsKey(msg.channelId) -> {
                            logger.error { "received $msg for unknown channel ${msg.channelId}" }
                        }
                        msg is HasChannelId -> {
                            logger.info { "received $msg for channel ${msg.channelId}" }
                            val state = channels[msg.channelId]!!
                            val (state1, actions) = state.process(MessageReceived(msg))
                            actions.forEach {
                                when(it) {
                                    is SendMessage -> {
                                        val encoded = Wire.encode(it.message)
                                        encoded?.let { output.send(it) }
                                    }
                                    else -> logger.warning { "ignoring $it" }
                                }
                            }
                            channels.put(msg.channelId, state1)
                            logger.info { "channel ${msg.channelId} new state $state1" }
                        }
                        else -> logger.warning { "received unhandled message ${Hex.encode(event.data)}"}
                    }
                }
                event is WatchReceived && !channels.containsKey(event.watch.channelId) -> {
                    logger.error { "received watch event ${event.watch} for unknown channel ${event.watch.channelId}}" }
                }
                event is WatchReceived -> {
                    val state = channels[event.watch.channelId]!!
                    val (state1, actions) = state.process(fr.acinq.eklair.channel.WatchReceived(event.watch))
                    actions.forEach {
                        when(it) {
                            is SendMessage -> {
                                val encoded = Wire.encode(it.message)
                                encoded?.let { output.send(it) }
                            }
                            else -> logger.warning { "ignoring $it" }
                        }
                    }
                    channels.put(event.watch.channelId, state1)
                    logger.info { "channel ${event.watch.channelId} new state $state1" }
                }
            }
        }
    }
}

object Node {
    val prefix: Byte = 0x00
    val prologue = "lightning".toByteArray()
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
        val priv = nodeParams.keyManager.nodeKey.privateKey
        val pub = priv.publicKey()
        val keyPair = Pair(pub.value.toByteArray(), priv.value.toByteArray())
        val address = InetSocketAddress("localhost", 29735)
        // remote node on regtest is initialized with the following seed: 0202020202020202020202020202020202020202020202020202020202020202
        // To create such a seed, you can create a text file seed.hex with the following content:
        // 00000000: 0202 0202 0202 0202 0202 0202 0202 0202  ................
        // 00000010: 0202 0202 0202 0202 0202 0202 0202 0202  ................
        // and convert it to a binary file with:
        // $ xxd -r seed.hex seed.dat
        val remoteNodeId = PublicKey(Hex.decode("039dc0e0b1d25905e44fdf6f8e89755a5e219685840d0bc1d28d3308f9628a3585"))

        runBlocking {
            val socket = aSocket(ActorSelectorManager(Dispatchers.IO)).tcp().connect(address)
            val w = socket.openWriteChannel(autoFlush = false)
            val r = socket.openReadChannel()
            val (enc, dec, ck) = handshake(keyPair, remoteNodeId.value.toByteArray(), r, w)
            val session = LightningSession(enc, dec, ck)

            suspend fun receive(): ByteArray {
                return session.receive { size -> val buffer = ByteArray(size); r.readFully(buffer, 0, size); buffer }
            }

            suspend fun send(message: ByteArray) {
                session.send(message) { data, flush -> w.writeFully(data); if (flush) w.flush() }
            }

            val peer = Peer(nodeParams, remoteNodeId, Channel<PeerEvent>(10), Channel<ByteArray>())

            val init = Hex.decode("001000000002a8a0")
            send(init)

            suspend fun doPing() {
                val ping = Hex.decode("0012000a0004deadbeef")
                while (true) {
                    delay(10000)
                    send(ping)
                }
            }

            suspend fun listen() {
                while (true) {
                    val received = receive()
                    peer.input.send(BytesReceived(received))
                }
            }

            suspend fun respond() {
                for (msg in peer.output) {
                    send(msg)
                }
            }

            launch { peer.run() }
            launch { doPing() }
            launch { respond() }
            launch { listen() }

            delay(1000 * 1000)
        }
    }

    fun makeWriter(localStatic: Pair<ByteArray, ByteArray>, remoteStatic: ByteArray) = HandshakeState.initializeWriter(
        handshakePatternXK, prologue,
        localStatic, Pair(ByteArray(0), ByteArray(0)), remoteStatic, ByteArray(0),
        Secp256k1DHFunctions, Chacha20Poly1305CipherFunctions, SHA256HashFunctions
    )

    fun makeReader(localStatic: Pair<ByteArray, ByteArray>) = HandshakeState.initializeReader(
        handshakePatternXK, prologue,
        localStatic, Pair(ByteArray(0), ByteArray(0)), ByteArray(0), ByteArray(0),
        Secp256k1DHFunctions, Chacha20Poly1305CipherFunctions, SHA256HashFunctions
    )

    suspend fun handshake(
        ourKeys: Pair<ByteArray, ByteArray>,
        theirPubkey: ByteArray,
        r: ByteReadChannel,
        w: ByteWriteChannel
    ): Triple<CipherState, CipherState, ByteArray> {

        /**
         * See BOLT #8: during the handshake phase we are expecting 3 messages of 50, 50 and 66 bytes (including the prefix)
         *
         * @param reader handshake state reader
         * @return the size of the message the reader is expecting
         */
        fun expectedLength(reader: HandshakeStateReader): Int = when (reader.messages.size) {
            3, 2 -> 50
            1 -> 66
            else -> throw RuntimeException("invalid state")
        }

        val writer = makeWriter(ourKeys, theirPubkey)
        val (state1, message, _) = writer.write(ByteArray(0))
        w.writeByte(prefix)
        w.writeFully(message, 0, message.size)
        w.flush()

        val packet = r.readPacket(expectedLength(state1), 0)
        val payload = packet.readBytes(expectedLength(state1))
        assert(payload[0] == prefix)

        val (writer1, a, b) = state1.read(payload.drop(1).toByteArray())
        val (reader1, message1, foo) = writer1.write(ByteArray(0))
        val (enc, dec, ck) = foo!!
        w.writeByte(prefix)
        w.writeFully(message1, 0, message1.size)
        w.flush()
        assert(packet.remaining == 0L)
        packet.close()
        return Triple(enc, dec, ck)
    }
}

@ExperimentalStdlibApi
object Wire {
    val logger = LoggerFactory.default.newLogger(Logger.Tag(Wire::class))

    fun decode(input: ByteArray) : LightningMessage? {
        val stream = ByteArrayInput(input)
        val code = LightningSerializer.u16(stream)
        return when (code.toULong()) {
            Init.tag -> Init.read(stream)
            Error.tag -> Error.read(stream)
            Ping.tag -> Ping.read(stream)
            Pong.tag -> Pong.read(stream)
            OpenChannel.tag -> OpenChannel.read(stream)
            AcceptChannel.tag -> AcceptChannel.read(stream)
            FundingCreated.tag -> FundingCreated.read(stream)
            FundingSigned.tag -> FundingSigned.read(stream)
            FundingLocked.tag -> FundingLocked.read(stream)
            CommitSig.tag -> CommitSig.read(stream)
            RevokeAndAck.tag -> RevokeAndAck.read(stream)
            UpdateAddHtlc.tag -> UpdateAddHtlc.read(stream)
            else -> {
                logger.warning {"cannot decode ${Hex.encode(input)}" }
                null
            }
        }
    }

    fun encode(input: LightningMessage, out: Output) {
        when (input) {
            is Init -> {
                LightningSerializer.writeU16(input.tag.toInt(), out)
                LightningSerializer.writeBytes(input.serializer().write(input), out)
            }
            is Error -> {
                LightningSerializer.writeU16(input.tag.toInt(), out)
                LightningSerializer.writeBytes(input.serializer().write(input), out)
            }
            is Ping -> {
                LightningSerializer.writeU16(input.tag.toInt(), out)
                LightningSerializer.writeBytes(input.serializer().write(input), out)
            }
            is Pong -> {
                LightningSerializer.writeU16(input.tag.toInt(), out)
                LightningSerializer.writeBytes(input.serializer().write(input), out)
            }
            is OpenChannel -> {
                LightningSerializer.writeU16(input.tag.toInt(), out)
                LightningSerializer.writeBytes(input.serializer().write(input), out)
            }
            is AcceptChannel -> {
                LightningSerializer.writeU16(input.tag.toInt(), out)
                LightningSerializer.writeBytes(input.serializer().write(input), out)
            }
            is FundingCreated -> {
                LightningSerializer.writeU16(input.tag.toInt(), out)
                LightningSerializer.writeBytes(input.serializer().write(input), out)
            }
            is FundingSigned -> {
                LightningSerializer.writeU16(input.tag.toInt(), out)
                LightningSerializer.writeBytes(input.serializer().write(input), out)
            }
            is FundingLocked -> {
                LightningSerializer.writeU16(input.tag.toInt(), out)
                LightningSerializer.writeBytes(input.serializer().write(input), out)
            }
            is CommitSig -> {
                LightningSerializer.writeU16(input.tag.toInt(), out)
                LightningSerializer.writeBytes(input.serializer().write(input), out)
            }
            is RevokeAndAck -> {
                LightningSerializer.writeU16(input.tag.toInt(), out)
                LightningSerializer.writeBytes(input.serializer().write(input), out)
            }
            is UpdateAddHtlc -> {
                LightningSerializer.writeU16(input.tag.toInt(), out)
                LightningSerializer.writeBytes(input.serializer().write(input), out)
            }
            else -> {
                logger.warning { "cannot encode $input"}
                Unit
            }
        }
    }

    fun encode(input: LightningMessage) : ByteArray? {
        val out = ByteArrayOutput()
        encode(input, out)
        return out.toByteArray()
    }
}