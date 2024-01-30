package fr.acinq.lightning.message

import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.bitcoin.PublicKey
import fr.acinq.lightning.Lightning.randomBytes32
import fr.acinq.lightning.Lightning.randomKey
import fr.acinq.lightning.NodeId
import fr.acinq.lightning.crypto.RouteBlinding
import fr.acinq.lightning.crypto.sphinx.Sphinx
import fr.acinq.lightning.utils.Either
import fr.acinq.lightning.utils.Either.Left
import fr.acinq.lightning.utils.Either.Right
import fr.acinq.lightning.wire.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.max
import kotlin.time.Duration

class Postman(
    val privateKey: PrivateKey,
    val remoteNodeId: PublicKey,
    val sendOnionMessage: (onionMessage: OnionMessage) -> SendMessageError?
) {
    private val nodeId = privateKey.publicKey()

    data class MessageWithRecipientData(val messageOnion: MessageOnion, val recipientData: RouteBlindingEncryptedData)

    data class SendMessageError(val error: String)

    private val subscribed: HashMap<ByteVector32, SendChannel<MessageWithRecipientData>> = HashMap()

    suspend fun processOnionMessage(msg: OnionMessage) {
        val blindedPrivateKey = RouteBlinding.derivePrivateKey(privateKey, msg.blindingKey)
        when (val decrypted = Sphinx.peel(
            blindedPrivateKey,
            ByteVector.empty,
            msg.onionRoutingPacket
        )) {
            is Right -> try {
                val message = MessageOnion.read(decrypted.value.payload.toByteArray())
                val (decryptedPayload, nextBlinding) = RouteBlinding.decryptPayload(
                    privateKey,
                    msg.blindingKey,
                    message.encryptedData
                )
                val relayInfo = RouteBlindingEncryptedData.read(decryptedPayload.toByteArray())
                if (decrypted.value.isLastPacket && subscribed.containsKey(relayInfo.pathId)) {
                    subscribed[relayInfo.pathId]?.send(MessageWithRecipientData(message, relayInfo))
                    subscribed.remove(relayInfo.pathId)
                } else if (!decrypted.value.isLastPacket && relayInfo.nextNodeId == NodeId(privateKey.publicKey())) {
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
        recipient: OfferTypes.ContactInfo,
        messageContent: TlvStream<OnionMessagePayloadTlv>,
    ): SendMessageError? {
        val destination: OnionMessages.Destination = when (recipient) {
            is OfferTypes.ContactInfo.BlindedPath -> OnionMessages.Destination.BlindedPath(recipient.route)
            is OfferTypes.ContactInfo.RecipientNodeId -> OnionMessages.Destination.Recipient(recipient.nodeId, null)
        }
        val intermediateNodes = if (destination.introductionNodeId == NodeId(remoteNodeId)) listOf() else listOf(remoteNodeId)
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
                val (_, onionMessage) = message.value
                return sendOnionMessage(onionMessage)
            }
        }
    }

    suspend fun sendMessageExpectingReply(
        recipient: OfferTypes.ContactInfo,
        messageContent: TlvStream<OnionMessagePayloadTlv>,
        minReplyPathHops: Int,
        timeout: Duration
    ): Either<SendMessageError, MessageWithRecipientData?> {
        val destination: OnionMessages.Destination = when (recipient) {
            is OfferTypes.ContactInfo.BlindedPath -> OnionMessages.Destination.BlindedPath(recipient.route)
            is OfferTypes.ContactInfo.RecipientNodeId -> OnionMessages.Destination.Recipient(recipient.nodeId, null)
        }
        val intermediateNodes = if (destination.introductionNodeId == NodeId(remoteNodeId)) listOf() else listOf(remoteNodeId)
        val messageId = randomBytes32()
        val numHopsToAdd = max(0, minReplyPathHops - 1)
        val replyPathHops =
            (listOf(remoteNodeId) + List(numHopsToAdd) { privateKey.publicKey() }).map {
                OnionMessages.IntermediateNode(it)
            }
        val lastHop = OnionMessages.Destination.Recipient(nodeId, messageId)
        val replyPath = OnionMessages.buildRoute(randomKey(), replyPathHops, lastHop)
        return when (val message = OnionMessages.buildMessage(
            privateKey,
            randomKey(),
            randomKey(),
            intermediateNodes.map { OnionMessages.IntermediateNode(it) },
            destination,
            TlvStream(messageContent.records + OnionMessagePayloadTlv.ReplyPath(replyPath), messageContent.unknown)
        )) {
            is Left -> {
                Left(SendMessageError(message.value.toString()))
            }
            is Right -> {
                val (_, onionMessage) = message.value
                val channel = Channel<MessageWithRecipientData>()
                subscribed[messageId] = channel
                val sendMessageError = sendOnionMessage(onionMessage)
                if(sendMessageError == null){
                    val response = withTimeoutOrNull(timeout) { channel.receive() }
                    if (response == null) {
                        subscribed.remove(messageId)
                    }
                    Right(response)
                } else {
                    Left(sendMessageError)
                }
            }
        }
    }

    suspend fun receiveMessage(pathId: ByteVector32, timeout: Duration? = null): MessageWithRecipientData? {
        val channel = Channel<MessageWithRecipientData>()
        subscribed[pathId] = channel
        val response = if (timeout == null) {
            channel.receive()
        } else {
            withTimeoutOrNull(timeout) { channel.receive() }
        }
        if (response == null) {
            subscribed.remove(pathId)
        }
        return response
    }
}
