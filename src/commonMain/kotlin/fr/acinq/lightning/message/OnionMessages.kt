package fr.acinq.lightning.message

import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.bitcoin.PublicKey
import fr.acinq.bitcoin.utils.Either
import fr.acinq.lightning.crypto.RouteBlinding
import fr.acinq.lightning.crypto.sphinx.Sphinx
import fr.acinq.lightning.utils.toByteVector
import fr.acinq.lightning.wire.*
import fr.acinq.lightning.EncodedNodeId
import fr.acinq.lightning.ShortChannelId

object OnionMessages {
    data class IntermediateNode(val nodeId: PublicKey, val outgoingChannelId: ShortChannelId? = null, val padding: ByteVector? = null, val customTlvs: Set<GenericTlv> = setOf())

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
        nextNodeId: EncodedNodeId,
        nextBlinding: PublicKey? = null
    ): List<ByteVector> {
        return if (intermediateNodes.isEmpty()) {
            listOf()
        } else {
            (intermediateNodes.zip(intermediateNodes.drop(1).map { Pair(EncodedNodeId(it.nodeId), null) }.plusElement(Pair(nextNodeId, nextBlinding)))).map { (current, next) ->
                val (outgoingNodeId, blinding) = next
                val tlvs = TlvStream(setOfNotNull(
                    current.outgoingChannelId?.let { RouteBlindingEncryptedDataTlv.OutgoingChannelId(it) } ?: RouteBlindingEncryptedDataTlv.OutgoingNodeId(outgoingNodeId),
                    blinding?.let { RouteBlindingEncryptedDataTlv.NextBlinding(it) },
                    current.padding?.let { RouteBlindingEncryptedDataTlv.Padding(it) }
                ), current.customTlvs)
                RouteBlindingEncryptedData(tlvs).write().toByteVector()
            }
        }
    }

    fun buildRoute(
        blindingSecret: PrivateKey,
        intermediateNodes: List<IntermediateNode>,
        recipient: Destination.Recipient
    ): RouteBlinding.BlindedRoute {
        val intermediatePayloads = buildIntermediatePayloads(
            intermediateNodes,
            EncodedNodeId(recipient.nodeId)
        )
        val tlvs: Set<RouteBlindingEncryptedDataTlv> = setOfNotNull(
            recipient.padding?.let { RouteBlindingEncryptedDataTlv.Padding(it) },
            recipient.pathId?.let { RouteBlindingEncryptedDataTlv.PathId(it) }
        )
        val lastPayload = RouteBlindingEncryptedData(TlvStream(tlvs, recipient.customTlvs)).write().toByteVector()
        return RouteBlinding.create(
            blindingSecret,
            intermediateNodes.map { it.nodeId } + recipient.nodeId,
            intermediatePayloads + lastPayload)
    }

    private fun buildRouteFrom(
        blindingSecret: PrivateKey,
        intermediateNodes: List<IntermediateNode>,
        destination: Destination
    ): RouteBlinding.BlindedRoute? {
        when (destination) {
            is Destination.Recipient -> return buildRoute(blindingSecret, intermediateNodes, destination)
            is Destination.BlindedPath ->
                when {
                    intermediateNodes.isEmpty() -> return destination.route
                    else -> {
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
                        return RouteBlinding.BlindedRoute(
                            routePrefix.introductionNodeId,
                            routePrefix.blindingKey,
                            routePrefix.blindedNodes + destination.route.blindedNodes
                        )
                    }
                }
        }
    }

    sealed interface BuildMessageError
    data class MessageTooLarge(val payloadSize: Int) : BuildMessageError
    data class InvalidDestination(val destination: Destination) : BuildMessageError


    fun buildMessage(
        sessionKey: PrivateKey,
        blindingSecret: PrivateKey,
        intermediateNodes: List<IntermediateNode>,
        destination: Destination,
        content: TlvStream<OnionMessagePayloadTlv>
    ): Either<BuildMessageError, OnionMessage> {
        val route = buildRouteFrom(blindingSecret, intermediateNodes, destination)
        if (route == null) {
            return Either.Left(InvalidDestination(destination))
        } else {
            val lastPayload = MessageOnion(
                TlvStream(
                    content.records + OnionMessagePayloadTlv.EncryptedData(route.encryptedPayloads.last()),
                    content.unknown
                )
            ).write()
            val payloads = route.encryptedPayloads.dropLast(1)
                .map { encTlv -> MessageOnion(TlvStream(OnionMessagePayloadTlv.EncryptedData(encTlv))).write() } + lastPayload
            val payloadSize = payloads.map { it.size + Sphinx.MacLength }.sum()
            val packetSize = if (payloadSize <= 1300) {
                1300
            } else if (payloadSize <= 32768) {
                32768
            } else if (payloadSize > 65432) {
                // A payload of size 65432 corresponds to a total lightning message size of 65535.
                return Either.Left(MessageTooLarge(payloadSize))
            } else {
                payloadSize
            }
            // Since we are setting the packet size based on the payload, the onion creation should never fail (hence the `.get`).
            val packet = Sphinx.create(
                sessionKey,
                route.blindedNodes.map { it.blindedPublicKey },
                payloads,
                null,
                packetSize
            ).packet
            return Either.Right( OnionMessage(route.blindingKey, packet))
        }
    }

    data class DecryptedMessage(val content: MessageOnion, val blindedPrivateKey: PrivateKey, val pathId: ByteVector)

    fun decryptMessage(privateKey: PrivateKey, msg: OnionMessage): DecryptedMessage? {
        val blindedPrivateKey = RouteBlinding.derivePrivateKey(privateKey, msg.blindingKey)
        when (val decrypted = Sphinx.peel(
            blindedPrivateKey,
            ByteVector.empty,
            msg.onionRoutingPacket
        )) {
            is Either.Right -> try {
                val message = MessageOnion.read(decrypted.value.payload.toByteArray())
                val (decryptedPayload, nextBlinding) = RouteBlinding.decryptPayload(
                    privateKey,
                    msg.blindingKey,
                    message.encryptedData
                )
                val relayInfo = RouteBlindingEncryptedData.read(decryptedPayload.toByteArray())
                if (!decrypted.value.isLastPacket && relayInfo.nextNodeId == EncodedNodeId(privateKey.publicKey())) {
                    // We may add ourselves to the route several times at the end to hide the real length of the route.
                    return decryptMessage(privateKey, OnionMessage(relayInfo.nextBlindingOverride ?: nextBlinding, decrypted.value.nextPacket))
                } else if (decrypted.value.isLastPacket && relayInfo.pathId != null) {
                    return DecryptedMessage(message, blindedPrivateKey, relayInfo.pathId)
                } else {
                    return null // Ignore messages to relay to other nodes
                }
            } catch (_: Throwable) {
                return null // Ignore bad messages
            }

            is Either.Left -> {
                return null // Ignore bad messages
            }
        }
    }
}
