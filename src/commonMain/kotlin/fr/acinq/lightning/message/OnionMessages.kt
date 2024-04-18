package fr.acinq.lightning.message

import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.bitcoin.PublicKey
import fr.acinq.bitcoin.byteVector
import fr.acinq.bitcoin.utils.Either
import fr.acinq.lightning.EncodedNodeId
import fr.acinq.lightning.ShortChannelId
import fr.acinq.lightning.crypto.RouteBlinding
import fr.acinq.lightning.crypto.sphinx.Sphinx
import fr.acinq.lightning.logging.MDCLogger
import fr.acinq.lightning.utils.toByteVector
import fr.acinq.lightning.wire.*

object OnionMessages {
    data class IntermediateNode(val nodeId: PublicKey, val outgoingChannelId: ShortChannelId? = null, val padding: ByteVector? = null, val customTlvs: Set<GenericTlv> = setOf()) {
        fun toTlvStream(nextNodeId: EncodedNodeId, nextBlinding: PublicKey? = null): TlvStream<RouteBlindingEncryptedDataTlv> {
            val tlvs = setOfNotNull(
                outgoingChannelId?.let { RouteBlindingEncryptedDataTlv.OutgoingChannelId(it) } ?: RouteBlindingEncryptedDataTlv.OutgoingNodeId(nextNodeId),
                nextBlinding?.let { RouteBlindingEncryptedDataTlv.NextBlinding(it) },
                padding?.let { RouteBlindingEncryptedDataTlv.Padding(it) },
            )
            return TlvStream(tlvs, customTlvs)
        }
    }

    sealed class Destination {
        data class BlindedPath(val route: RouteBlinding.BlindedRoute) : Destination()
        data class Recipient(val nodeId: PublicKey, val pathId: ByteVector?, val padding: ByteVector? = null, val customTlvs: Set<GenericTlv> = setOf()) : Destination()

        companion object {
            operator fun invoke(contactInfo: OfferTypes.ContactInfo): Destination =
                when (contactInfo) {
                    is OfferTypes.ContactInfo.BlindedPath -> BlindedPath(contactInfo.route)
                    is OfferTypes.ContactInfo.RecipientNodeId -> Recipient(contactInfo.nodeId, null)
                }
        }
    }

    private fun buildIntermediatePayloads(
        intermediateNodes: List<IntermediateNode>,
        lastNodeId: EncodedNodeId,
        lastBlinding: PublicKey? = null
    ): List<ByteVector> {
        return if (intermediateNodes.isEmpty()) {
            listOf()
        } else {
            val intermediatePayloads = intermediateNodes.dropLast(1).zip(intermediateNodes.drop(1)).map { (current, next) -> current.toTlvStream(EncodedNodeId(next.nodeId)) }
            // The last intermediate node may contain a blinding override when the recipient is hidden behind a blinded path.
            val lastPayload = intermediateNodes.last().toTlvStream(lastNodeId, lastBlinding)
            (intermediatePayloads + lastPayload).map { RouteBlindingEncryptedData(it).write().byteVector() }
        }
    }

    fun buildRoute(
        blindingSecret: PrivateKey,
        intermediateNodes: List<IntermediateNode>,
        destination: Destination
    ): RouteBlinding.BlindedRoute {
        return when (destination) {
            is Destination.Recipient -> {
                val intermediatePayloads = buildIntermediatePayloads(intermediateNodes, EncodedNodeId(destination.nodeId))
                val tlvs = setOfNotNull(
                    destination.padding?.let { RouteBlindingEncryptedDataTlv.Padding(it) },
                    destination.pathId?.let { RouteBlindingEncryptedDataTlv.PathId(it) }
                )
                val lastPayload = RouteBlindingEncryptedData(TlvStream(tlvs, destination.customTlvs)).write().toByteVector()
                RouteBlinding.create(
                    blindingSecret,
                    intermediateNodes.map { it.nodeId } + destination.nodeId,
                    intermediatePayloads + lastPayload
                )
            }
            is Destination.BlindedPath -> when {
                intermediateNodes.isEmpty() -> destination.route
                else -> {
                    // We concatenate our blinded path with the destination's blinded path.
                    val intermediatePayloads = buildIntermediatePayloads(
                        intermediateNodes,
                        destination.route.introductionNodeId,
                        destination.route.blindingKey
                    )
                    val routePrefix = RouteBlinding.create(
                        blindingSecret,
                        intermediateNodes.map { it.nodeId },
                        intermediatePayloads
                    )
                    RouteBlinding.BlindedRoute(
                        routePrefix.introductionNodeId,
                        routePrefix.blindingKey,
                        routePrefix.blindedNodes + destination.route.blindedNodes
                    )
                }
            }
        }
    }

    sealed class BuildMessageError
    data class MessageTooLarge(val payloadSize: Int) : BuildMessageError()

    /**
     * Builds an encrypted onion containing a message that should be relayed to the destination.
     *
     * @param sessionKey a random key to encrypt the onion.
     * @param blindingSecret a random key to create the blinded path.
     * @param intermediateNodes list of intermediate nodes between us and the destination (can be empty if we want to contact the destination directly).
     * @param destination the destination of this message, can be a node id or a blinded route.
     * @param content list of TLVs to send to the recipient of the message.
     */
    fun buildMessage(
        sessionKey: PrivateKey,
        blindingSecret: PrivateKey,
        intermediateNodes: List<IntermediateNode>,
        destination: Destination,
        content: TlvStream<OnionMessagePayloadTlv>
    ): Either<BuildMessageError, OnionMessage> {
        val route = buildRoute(blindingSecret, intermediateNodes, destination)
        val payloads = buildList {
            // Intermediate nodes only receive blinded path relay information.
            addAll(route.encryptedPayloads.dropLast(1).map { MessageOnion(TlvStream(OnionMessagePayloadTlv.EncryptedData(it))).write() })
            // The destination receives the message contents and the blinded path information.
            add(MessageOnion(content.copy(records = content.records + OnionMessagePayloadTlv.EncryptedData(route.encryptedPayloads.last()))).write())
        }
        val payloadSize = payloads.sumOf { it.size + Sphinx.MacLength }
        val packetSize = when {
            payloadSize <= 1300 -> 1300
            payloadSize <= 32768 -> 32768
            payloadSize <= 65432 -> 65432 // this corresponds to a total lightning message size of 65535
            else -> return Either.Left(MessageTooLarge(payloadSize))
        }
        // Since we are setting the packet size based on the payload, the onion creation should never fail.
        val packet = Sphinx.create(
            sessionKey,
            route.blindedNodes.map { it.blindedPublicKey },
            payloads,
            associatedData = null,
            packetSize
        ).packet
        return Either.Right(OnionMessage(route.blindingKey, packet))
    }

    /**
     * @param content message received.
     * @param blindedPrivateKey private key of the blinded node id used in our blinded path.
     * @param pathId path_id that we included in our blinded path for ourselves.
     */
    data class DecryptedMessage(val content: MessageOnion, val blindedPrivateKey: PrivateKey, val pathId: ByteVector)

    fun decryptMessage(privateKey: PrivateKey, msg: OnionMessage, logger: MDCLogger): DecryptedMessage? {
        val blindedPrivateKey = RouteBlinding.derivePrivateKey(privateKey, msg.blindingKey)
        return when (val decrypted = Sphinx.peel(blindedPrivateKey, associatedData = ByteVector.empty, msg.onionRoutingPacket)) {
            is Either.Right -> {
                val message = try {
                    MessageOnion.read(decrypted.value.payload.toByteArray())
                } catch (e: Throwable) {
                    logger.warning { "ignoring onion message that couldn't be decoded: ${e.message}" }
                    return null
                }
                when (val payload = RouteBlinding.decryptPayload(privateKey, msg.blindingKey, message.encryptedData)) {
                    is Either.Left -> {
                        logger.warning { "ignoring onion message that couldn't be decrypted: ${payload.value}" }
                        null
                    }
                    is Either.Right -> {
                        val (decryptedPayload, nextBlinding) = payload.value
                        when (val relayInfo = RouteBlindingEncryptedData.read(decryptedPayload.toByteArray())) {
                            is Either.Left -> {
                                logger.warning { "ignoring onion message with invalid relay info: ${relayInfo.value}" }
                                null
                            }
                            is Either.Right -> when {
                                !decrypted.value.isLastPacket && relayInfo.value.nextNodeId == EncodedNodeId(privateKey.publicKey()) -> {
                                    // We may add ourselves to the route several times at the end to hide the real length of the route.
                                    val nextMessage = OnionMessage(relayInfo.value.nextBlindingOverride ?: nextBlinding, decrypted.value.nextPacket)
                                    decryptMessage(privateKey, nextMessage, logger)
                                }
                                decrypted.value.isLastPacket && relayInfo.value.pathId != null -> DecryptedMessage(message, blindedPrivateKey, relayInfo.value.pathId!!)
                                else -> {
                                    logger.warning { "ignoring onion message for which we're not the destination (next_node_id=${relayInfo.value.nextNodeId}, path_id=${relayInfo.value.pathId?.toHex()})" }
                                    null
                                }
                            }
                        }
                    }
                }
            }
            is Either.Left -> {
                logger.warning { "ignoring onion message that couldn't be decrypted: ${decrypted.value.message}" }
                null
            }
        }
    }
}
