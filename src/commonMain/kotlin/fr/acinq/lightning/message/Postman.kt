package fr.acinq.lightning.message

import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.bitcoin.PublicKey
import fr.acinq.lightning.Lightning.randomBytes32
import fr.acinq.lightning.Lightning.randomKey
import fr.acinq.lightning.crypto.RouteBlinding
import fr.acinq.lightning.crypto.sphinx.Sphinx
import fr.acinq.lightning.io.Peer
import fr.acinq.lightning.utils.Either
import fr.acinq.lightning.utils.Either.Left
import fr.acinq.lightning.utils.Either.Right
import fr.acinq.lightning.wire.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.max
import kotlin.time.Duration

class Postman(val privateKey: PrivateKey, val peer: Peer) {
    private val nodeId = privateKey.publicKey()

    sealed interface OnionMessageResponse {
        object NoResponse : OnionMessageResponse
        data class Response(val messageOnion: MessageOnion, val recipientData: RecipientData) : OnionMessageResponse
    }

    data class SendMessageError(val error: String)

    private val subscribed: HashMap<ByteVector32, SendChannel<OnionMessageResponse.Response>> = HashMap()

    suspend fun processOnionMessage(msg: OnionMessage) {
        val blindedPrivateKey = RouteBlinding.derivePrivateKey(privateKey, msg.blindingKey)
        when (val decrypted = Sphinx.peel(
            blindedPrivateKey,
            ByteVector.empty,
            msg.onionRoutingPacket,
            msg.onionRoutingPacket.payload.size()
        )) {
            is Right -> try {
                val message = MessageOnion.read(decrypted.value.payload.toByteArray())
                val (decryptedPayload, nextBlinding) = RouteBlinding.decryptPayload(
                    privateKey,
                    msg.blindingKey,
                    message.encryptedData
                )
                val relayInfo = RecipientData.read(decryptedPayload.toByteArray())
                if (decrypted.value.isLastPacket && subscribed.containsKey(relayInfo.pathId)) {
                    subscribed[relayInfo.pathId]?.send(OnionMessageResponse.Response(message, relayInfo))
                    subscribed.remove(relayInfo.pathId)
                } else if (!decrypted.value.isLastPacket && relayInfo.nextNodeId == privateKey.publicKey()) {
                    // We may add ourselves to the route several times at the end to hide the real length of the route.
                    processOnionMessage(
                        OnionMessage(
                            relayInfo.nextBlindingOverride ?: nextBlinding,
                            decrypted.value.nextPacket
                        )
                    )
                }
                // Ignore messages to relay to other nodes
            } catch (_: Throwable) {
                // Ignore bad messages
            }

            is Left -> {
                // Ignore bad messages
            }
        }
    }

    fun sendMessage(
        destination: OnionMessages.Destination,
        messageContent: TlvStream<OnionMessagePayloadTlv>,
        intermediateNodes: List<PublicKey>,
    ): SendMessageError? {
        when (val message = OnionMessages.buildMessage(
            privateKey,
            randomKey(),
            randomKey(),
            intermediateNodes.map { OnionMessages.IntermediateNode(it) },
            destination,
            TlvStream(messageContent.records, messageContent.unknown)
        )) {
            is Left -> {
                return SendMessageError(message.value.toString())
            }
            is Right -> {
                val (nextNodeId, onionMessage) = message.value
                return peer.sendOnionMessage(nextNodeId, onionMessage)
            }
        }
    }

    suspend fun sendMessageExpectingReply(
        destination: OnionMessages.Destination,
        messageContent: TlvStream<OnionMessagePayloadTlv>,
        intermediateNodes: List<PublicKey>,
        minIntermediateHops: Int,
        timeout: Duration
    ): Either<SendMessageError, OnionMessageResponse> {
        val messageId = randomBytes32()
        val numHopsToAdd = max(0, minIntermediateHops - intermediateNodes.size - 1)
        val intermediateHops =
            (listOf(destination.introductionNodeId) + intermediateNodes + List(numHopsToAdd) { privateKey.publicKey() }).map {
                OnionMessages.IntermediateNode(it)
            }
        val lastHop = OnionMessages.Destination.Recipient(nodeId, messageId)
        val replyRoute = OnionMessages.buildRoute(randomKey(), intermediateHops, lastHop)
        return when (val message = OnionMessages.buildMessage(
            privateKey,
            randomKey(),
            randomKey(),
            intermediateNodes.map { OnionMessages.IntermediateNode(it) },
            destination,
            TlvStream(messageContent.records + OnionMessagePayloadTlv.ReplyPath(replyRoute), messageContent.unknown)
        )) {
            is Left -> {
                Left(SendMessageError(message.value.toString()))
            }
            is Right -> {
                val (nextNodeId, onionMessage) = message.value
                val channel = Channel<OnionMessageResponse.Response>()
                subscribed[messageId] = channel
                val sendMessageError = peer.sendOnionMessage(nextNodeId, onionMessage)
                if(sendMessageError == null){
                    val response = withTimeoutOrNull(timeout) { channel.receive() }
                    if (response == null) {
                        subscribed.remove(messageId)
                        Right(OnionMessageResponse.NoResponse)
                    } else {
                        Right(response)
                    }
                } else {
                    Left(sendMessageError)
                }
            }
        }
    }
}
