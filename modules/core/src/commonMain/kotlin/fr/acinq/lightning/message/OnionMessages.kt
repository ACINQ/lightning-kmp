package fr.acinq.lightning.message

import fr.acinq.bitcoin.*
import fr.acinq.bitcoin.utils.Either
import fr.acinq.lightning.EncodedNodeId
import fr.acinq.lightning.ShortChannelId
import fr.acinq.lightning.crypto.RouteBlinding
import fr.acinq.lightning.crypto.sphinx.Sphinx
import fr.acinq.lightning.logging.MDCLogger
import fr.acinq.lightning.utils.toByteVector
import fr.acinq.lightning.wire.*

object OnionMessages {
    data class IntermediateNode(val nodeId: EncodedNodeId.WithPublicKey, val outgoingChannelId: ShortChannelId? = null, val padding: ByteVector? = null, val customTlvs: Set<GenericTlv> = setOf()) {
        fun toTlvStream(nextNodeId: EncodedNodeId, nextPathKey: PublicKey? = null): TlvStream<RouteBlindingEncryptedDataTlv> {
            val tlvs = setOfNotNull(
                outgoingChannelId?.let { RouteBlindingEncryptedDataTlv.OutgoingChannelId(it) } ?: RouteBlindingEncryptedDataTlv.OutgoingNodeId(nextNodeId),
                nextPathKey?.let { RouteBlindingEncryptedDataTlv.NextPathKey(it) },
                padding?.let { RouteBlindingEncryptedDataTlv.Padding(it) },
            )
            return TlvStream(tlvs, customTlvs)
        }
    }

    sealed class Destination {
        data class BlindedPath(val route: RouteBlinding.BlindedRoute) : Destination()
        data class Recipient(val nodeId: EncodedNodeId.WithPublicKey, val pathId: ByteVector?, val padding: ByteVector? = null, val customTlvs: Set<GenericTlv> = setOf()) : Destination()

        companion object {
            operator fun invoke(contactInfo: OfferTypes.ContactInfo): Destination =
                when (contactInfo) {
                    is OfferTypes.ContactInfo.BlindedPath -> BlindedPath(contactInfo.route)
                    is OfferTypes.ContactInfo.RecipientNodeId -> Recipient(EncodedNodeId.WithPublicKey.Plain(contactInfo.nodeId), null)
                }
        }
    }

    private fun buildIntermediatePayloads(
        intermediateNodes: List<IntermediateNode>,
        lastNodeId: EncodedNodeId,
        lastPathKey: PublicKey? = null
    ): List<ByteVector> {
        return if (intermediateNodes.isEmpty()) {
            listOf()
        } else {
            val intermediatePayloads = intermediateNodes.dropLast(1).zip(intermediateNodes.drop(1)).map { (current, next) ->
                current.toTlvStream(next.nodeId)
            }
            // The last intermediate node may contain a path key override when the recipient is hidden behind a blinded path.
            val lastPayload = intermediateNodes.last().toTlvStream(lastNodeId, lastPathKey)
            (intermediatePayloads + lastPayload).map { RouteBlindingEncryptedData(it).write().byteVector() }
        }
    }

    fun buildRouteToRecipient(
        sessionKey: PrivateKey,
        intermediateNodes: List<IntermediateNode>,
        recipient: Destination.Recipient
    ): RouteBlinding. BlindedRouteDetails {
        val intermediatePayloads = buildIntermediatePayloads(intermediateNodes, recipient.nodeId)
        val tlvs = setOfNotNull(
            recipient.padding?.let { RouteBlindingEncryptedDataTlv.Padding(it) },
            recipient.pathId?.let { RouteBlindingEncryptedDataTlv.PathId(it) }
        )
        val lastPayload = RouteBlindingEncryptedData(TlvStream(tlvs, recipient.customTlvs)).write().toByteVector()
        return RouteBlinding.create(
            sessionKey,
            intermediateNodes.map { it.nodeId.publicKey } + recipient.nodeId.publicKey,
            intermediatePayloads + lastPayload
        )
    }

    fun buildRoute(
        sessionKey: PrivateKey,
        intermediateNodes: List<IntermediateNode>,
        destination: Destination
    ): RouteBlinding.BlindedRoute {
        return when (destination) {
            is Destination.Recipient -> {
                buildRouteToRecipient(sessionKey, intermediateNodes, destination).route
            }
            is Destination.BlindedPath -> when {
                intermediateNodes.isEmpty() -> destination.route
                else -> {
                    // We concatenate our blinded path with the destination's blinded path.
                    val intermediatePayloads = buildIntermediatePayloads(
                        intermediateNodes,
                        destination.route.firstNodeId,
                        destination.route.firstPathKey
                    )
                    val routePrefix = RouteBlinding.create(
                        sessionKey,
                        intermediateNodes.map { it.nodeId.publicKey },
                        intermediatePayloads
                    ).route
                    RouteBlinding.BlindedRoute(
                        routePrefix.firstNodeId,
                        routePrefix.firstPathKey,
                        routePrefix.blindedHops + destination.route.blindedHops
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
     * @param blindedPathSessionKey a random key to create the blinded path.
     * @param intermediateNodes list of intermediate nodes between us and the destination (can be empty if we want to contact the destination directly).
     * @param destination the destination of this message, can be a node id or a blinded route.
     * @param content list of TLVs to send to the recipient of the message.
     */
    fun buildMessage(
        sessionKey: PrivateKey,
        blindedPathSessionKey: PrivateKey,
        intermediateNodes: List<IntermediateNode>,
        destination: Destination,
        content: TlvStream<OnionMessagePayloadTlv>
    ): Either<BuildMessageError, OnionMessage> {
        val route = buildRoute(blindedPathSessionKey, intermediateNodes, destination)
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
            route.blindedHops.map { it.blindedPublicKey },
            payloads,
            associatedData = null,
            packetSize
        ).packet
        return Either.Right(OnionMessage(route.firstPathKey, packet))
    }

    /**
     * @param content message received.
     * @param blindedPrivateKey private key of the blinded node id used in our blinded path.
     * @param pathId path_id that we included in our blinded path for ourselves.
     */
    data class DecryptedMessage(val content: MessageOnion, val blindedPrivateKey: PrivateKey, val pathId: ByteVector)

    fun decryptMessage(privateKey: PrivateKey, msg: OnionMessage, logger: MDCLogger): DecryptedMessage? {
        val blindedPrivateKey = RouteBlinding.derivePrivateKey(privateKey, msg.pathKey)
        return when (val decrypted = Sphinx.peel(blindedPrivateKey, associatedData = ByteVector.empty, msg.onionRoutingPacket)) {
            is Either.Right -> {
                val message = try {
                    MessageOnion.read(decrypted.value.payload.toByteArray())
                } catch (e: Throwable) {
                    logger.warning { "ignoring onion message that couldn't be decoded: ${e.message}" }
                    return null
                }
                when (val payload = RouteBlinding.decryptPayload(privateKey, msg.pathKey, message.encryptedData)) {
                    is Either.Left -> {
                        logger.warning { "ignoring onion message that couldn't be decrypted: ${payload.value}" }
                        null
                    }
                    is Either.Right -> {
                        val (decryptedPayload, nextPathKey) = payload.value
                        when (val relayInfo = RouteBlindingEncryptedData.read(decryptedPayload.toByteArray())) {
                            is Either.Left -> {
                                logger.warning { "ignoring onion message with invalid relay info: ${relayInfo.value}" }
                                null
                            }
                            is Either.Right -> when {
                                !decrypted.value.isLastPacket && relayInfo.value.nextNodeId == EncodedNodeId.WithPublicKey.Wallet(privateKey.publicKey()) -> {
                                    // We may add ourselves to the route several times at the end to hide the real length of the route.
                                    val nextMessage = OnionMessage(relayInfo.value.nextPathKeyOverride ?: nextPathKey, decrypted.value.nextPacket)
                                    decryptMessage(privateKey, nextMessage, logger)
                                }
                                decrypted.value.isLastPacket -> DecryptedMessage(message, blindedPrivateKey, relayInfo.value.pathId ?: ByteVector32.Zeroes)
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
