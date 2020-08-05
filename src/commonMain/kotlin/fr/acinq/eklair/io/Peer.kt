package fr.acinq.eklair.io

import fr.acinq.bitcoin.*
import fr.acinq.eklair.*
import fr.acinq.eklair.blockchain.WatchConfirmed
import fr.acinq.eklair.blockchain.WatchEvent
import fr.acinq.eklair.blockchain.WatchEventConfirmed
import fr.acinq.eklair.channel.*
import fr.acinq.eklair.crypto.noise.*
import fr.acinq.eklair.payment.PaymentRequest
import fr.acinq.eklair.utils.currentTimestampSeconds
import fr.acinq.eklair.utils.toByteVector32
import fr.acinq.eklair.wire.*
import fr.acinq.secp256k1.Hex
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.kodein.log.Logger
import org.kodein.log.LoggerFactory

sealed class PeerEvent
data class BytesReceived(val data: ByteArray) : PeerEvent()
data class WatchReceived(val watch: WatchEvent) : PeerEvent()
data class ReceivePayment(val paymentPreimage: ByteVector32, val amount: MilliSatoshi, val expiry: CltvExpiry) : PeerEvent() {
    val paymentHash = Crypto.sha256(paymentPreimage).toByteVector32()
}

data class SendPayment(val paymentRequest: PaymentRequest) : PeerEvent()
data class ChannelEvent(val channelId: ByteVector32, val event: fr.acinq.eklair.channel.Event) : PeerEvent()

@ExperimentalStdlibApi
class Peer(
    val nodeParams: NodeParams,
    val remoteNodeId: PublicKey,
    val input: Channel<PeerEvent>,
    val output: Channel<ByteArray>
) {
    private val logger = LoggerFactory.default.newLogger(Logger.Tag(Peer::class))

    // channels map, indexed by channel id
    // note that a channel starts with a temporary id then switchs to its final id once the funding tx is known
    private val channels: HashMap<ByteVector32, State> = HashMap()

    // pending payments, indexed by payment hash
    private val pendingPayments: HashMap<ByteVector32, ReceivePayment> = HashMap()

    private var theirInit: Init? = null

    suspend fun connect(nodeId: PublicKey, address: String, port: Int) {
        logger.info { "connecting to {$nodeId}@{$address}" }
        val socket = TcpSocket.Builder().connect(address, port, tls = false)
        val priv = nodeParams.keyManager.nodeKey.privateKey
        val pub = priv.publicKey()
        val keyPair = Pair(pub.value.toByteArray(), priv.value.toByteArray())
        val (enc, dec, ck) = handshake(keyPair, remoteNodeId.value.toByteArray(), { s -> socket.receiveFully(s) }, { b -> socket.send(b) })
        val session = LightningSession(enc, dec, ck)

        suspend fun receive(): ByteArray {
            return session.receive { size -> val buffer = ByteArray(size); socket.receiveFully(buffer); buffer }
        }

        suspend fun send(message: ByteArray) {
            session.send(message) { data, flush -> socket.send(data, flush) }
        }

        val init = Hex.decode("001000000002a8a0")
        send(init)

        suspend fun doPing() {
            val ping = Hex.decode("0012000a0004deadbeef")
            while (true) {
                delay(30000)
                send(ping)
            }
        }

        suspend fun listen() {
            while (true) {
                val received = receive()
                input.send(BytesReceived(received))
            }
        }

        suspend fun respond() {
            for (msg in output) {
                send(msg)
            }
        }

        coroutineScope {
            launch { run() }
            launch { doPing() }
            launch { respond() }
            launch { listen() }
        }

        delay(1000 * 1000)
    }


    private suspend fun send(actions: List<Action>) {
        actions.forEach {
            when (it) {
                is SendMessage -> {
                    val encoded = LightningMessage.encode(it.message)
                    encoded?.let { bin ->
                        logger.info { "sending ${it.message}" }
                        output.send(bin)
                    }
                }
                else -> Unit
            }
        }
    }

    suspend fun run() {
        logger.info { "peer is active" }
        for (event in input) {
            when {
                event is BytesReceived -> {
                    val msg = LightningMessage.decode(event.data)
                    when {
                        msg is Init -> {
                            logger.info { "received $msg" }
                            theirInit = msg
                        }
                        msg is Ping -> {
                            logger.info { "received $msg" }
                            val pong = Pong(ByteVector(ByteArray(msg.pongLength)))
                            output.send(LightningMessage.encode(pong)!!)

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
                            val state = WaitForInit(
                                StaticParams(nodeParams, remoteNodeId),
                                Pair(0, Block.RegtestGenesisBlock.header)
                            )
                            val (state1, actions1) = state.process(InitFundee(msg.temporaryChannelId, localParams, theirInit!!))
                            val (state2, actions2) = state1.process(MessageReceived(msg))
                            send(actions1 + actions2)
                            channels[msg.temporaryChannelId] = state2
                            logger.info { "new state for ${msg.temporaryChannelId}: $state2" }
                        }
                        msg is HasTemporaryChannelId && !channels.containsKey(msg.temporaryChannelId) -> {
                            logger.error { "received $msg for unknown temporary channel ${msg.temporaryChannelId}" }
                        }
                        msg is HasTemporaryChannelId -> {
                            logger.info { "received $msg for temporary channel ${msg.temporaryChannelId}" }
                            val state = channels[msg.temporaryChannelId]!!
                            val (state1, actions) = state.process(MessageReceived(msg))
                            channels[msg.temporaryChannelId] = state1
                            logger.info { "channel ${msg.temporaryChannelId} new state $state1" }
                            send(actions)
                            actions.forEach {
                                when (it) {
                                    is ChannelIdSwitch -> {
                                        logger.info { "id switch from ${it.oldChannelId} to ${it.newChannelId}" }
                                        channels[it.newChannelId] = state1
                                    }
                                    is SendWatch -> {
                                        if (it.watch is WatchConfirmed) {
                                            // TODO: use a real watcher, here we just blindly confirm whatever tx they sent us
                                            val tx = Transaction(
                                                version = 2,
                                                txIn = listOf(),
                                                txOut = listOf(TxOut(Satoshi(100), (it.watch as WatchConfirmed).publicKeyScript)),
                                                lockTime = 0L
                                            )
                                            input.send(
                                                WatchReceived(
                                                    WatchEventConfirmed(
                                                        it.watch.channelId,
                                                        it.watch.event,
                                                        100,
                                                        0,
                                                        tx
                                                    )
                                                )
                                            )
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
                            channels[msg.channelId] = state1
                            logger.info { "channel ${msg.channelId} new state $state1" }
                            send(actions)
                            actions.forEach {
                                when {
                                    it is ProcessAdd && !pendingPayments.containsKey(it.add.paymentHash) -> {
                                        logger.warning { "received ${it.add} } for which we don't have a preimage" }
                                    }
                                    it is ProcessAdd -> {
                                        val payment = pendingPayments[it.add.paymentHash]!!
                                        logger.info { "receive ${it.add} for $payment" }
                                        input.send(
                                            ChannelEvent(msg.channelId, ExecuteCommand(CMD_FULFILL_HTLC(it.add.id, payment.paymentPreimage, commit = true)))
                                        )
                                    }
                                    it is ProcessCommand -> input.send(
                                        ChannelEvent(msg.channelId, ExecuteCommand(it.command))
                                    )
                                    it !is SendMessage -> {
                                        logger.warning { "ignoring $it" }
                                    }
                                }
                            }
                        }
                        else -> logger.warning { "received unhandled message ${Hex.encode(event.data)}" }
                    }
                }
                event is WatchReceived && !channels.containsKey(event.watch.channelId) -> {
                    logger.error { "received watch event ${event.watch} for unknown channel ${event.watch.channelId}}" }
                }
                event is WatchReceived -> {
                    val state = channels[event.watch.channelId]!!
                    val (state1, actions) = state.process(fr.acinq.eklair.channel.WatchReceived(event.watch))
                    send(actions)
                    channels[event.watch.channelId] = state1
                    logger.info { "channel ${event.watch.channelId} new state $state1" }
                }
                event is ReceivePayment -> {
                    logger.info { "expecting to receive $event for payment hash ${event.paymentHash}" }
                    val pr = PaymentRequest(
                        "lnbcrt", event.amount, currentTimestampSeconds(), nodeParams.nodeId,
                        listOf(
                            PaymentRequest.Companion.TaggedField.PaymentHash(event.paymentHash),
                            PaymentRequest.Companion.TaggedField.Description("this is a kotlin test")
                        ),
                        ByteVector.empty
                    )
                        .sign(nodeParams.privateKey)
                    logger.info { "payment request ${pr.write()}" }
                    pendingPayments[event.paymentHash] = event
                }
                event is SendPayment && event.paymentRequest.nodeId != remoteNodeId -> {
                    // TODO: right now we only handle direct payments
                    logger.error { "no route to ${event.paymentRequest.nodeId}" }
                }
                event is SendPayment -> {
                    logger.info { "sending ${event.paymentRequest.amount} to ${event.paymentRequest.nodeId}" }
                    //val finalPayload = Onion.createSinglePartPayload(r.recipientAmount, finalExpiry, paymentSecret, r.userCustomTlvs)
                }
                event is ChannelEvent && !channels.containsKey(event.channelId) -> {
                    logger.error { "received ${event.event} for a unknown channel ${event.channelId}" }
                }
                event is ChannelEvent -> {
                    val state = channels[event.channelId]!!
                    val (state1, actions) = state.process(event.event)
                    channels[event.channelId] = state1
                    send(actions)
                    actions.forEach {
                        when (it) {
                            is ProcessCommand -> input.send(ChannelEvent(event.channelId, ExecuteCommand(it.command)))
                        }
                    }
                }
            }
        }
    }

    companion object {
        private val prefix: Byte = 0x00
        private val prologue = "lightning".encodeToByteArray()

        private fun makeWriter(localStatic: Pair<ByteArray, ByteArray>, remoteStatic: ByteArray) = HandshakeState.initializeWriter(
            handshakePatternXK, prologue,
            localStatic, Pair(ByteArray(0), ByteArray(0)), remoteStatic, ByteArray(0),
            Secp256k1DHFunctions, Chacha20Poly1305CipherFunctions, SHA256HashFunctions
        )

        private fun makeReader(localStatic: Pair<ByteArray, ByteArray>) = HandshakeState.initializeReader(
            handshakePatternXK, prologue,
            localStatic, Pair(ByteArray(0), ByteArray(0)), ByteArray(0), ByteArray(0),
            Secp256k1DHFunctions, Chacha20Poly1305CipherFunctions, SHA256HashFunctions
        )

        private suspend fun handshake(
            ourKeys: Pair<ByteArray, ByteArray>,
            theirPubkey: ByteArray,
            r: suspend (Int) -> ByteArray,
            w: suspend (ByteArray) -> Unit
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
            w(byteArrayOf(prefix) + message)

            val payload = r(expectedLength(state1))
            require(payload[0] == prefix) { "invalid Noise prefix" }

            val (writer1, a, b) = state1.read(payload.drop(1).toByteArray())
            val (reader1, message1, foo) = writer1.write(ByteArray(0))
            val (enc, dec, ck) = foo!!
            w(byteArrayOf(prefix) + message1)
            return Triple(enc, dec, ck)
        }

        fun makeChannelParams(nodeParams: NodeParams, defaultFinalScriptPubkey: ByteVector, localPaymentBasepoint: PublicKey?, isFunder: Boolean, fundingAmount: Satoshi): LocalParams {
            // we make sure that funder and fundee key path end differently
            val fundingKeyPath = nodeParams.keyManager.newFundingKeyPath(isFunder)
            return makeChannelParams(nodeParams, defaultFinalScriptPubkey, localPaymentBasepoint, isFunder, fundingAmount, fundingKeyPath)
        }

        fun makeChannelParams(nodeParams: NodeParams, defaultFinalScriptPubkey: ByteVector, localPaymentBasepoint: PublicKey?, isFunder: Boolean, fundingAmount: Satoshi, fundingKeyPath: KeyPath): LocalParams {
            return LocalParams(
                nodeParams.nodeId,
                fundingKeyPath,
                dustLimit = nodeParams.dustLimit,
                maxHtlcValueInFlightMsat = nodeParams.maxHtlcValueInFlightMsat,
                channelReserve = (fundingAmount * nodeParams.reserveToFundingRatio).max(nodeParams.dustLimit), // BOLT #2: make sure that our reserve is above our dust limit
                htlcMinimum = nodeParams.htlcMinimum,
                toSelfDelay = nodeParams.toRemoteDelayBlocks, // we choose their delay
                maxAcceptedHtlcs = nodeParams.maxAcceptedHtlcs,
                isFunder = isFunder,
                defaultFinalScriptPubKey = defaultFinalScriptPubkey,
                localPaymentBasepoint = localPaymentBasepoint,
                features = nodeParams.features
            )
        }

    }
}
