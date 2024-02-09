package fr.acinq.lightning.message

import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.bitcoin.PublicKey
import fr.acinq.lightning.crypto.sphinx.Sphinx
import fr.acinq.lightning.crypto.RouteBlinding
import fr.acinq.lightning.utils.Either
import fr.acinq.lightning.utils.toByteVector
import fr.acinq.lightning.wire.*

object OnionMessages {
    data class IntermediateNode(val nodeId: PublicKey, val padding: ByteVector? = null, val customTlvs: Set<GenericTlv> = setOf())

    sealed interface Destination {
        val introductionNodeId: PublicKey

        data class BlindedPath(val route: RouteBlinding.BlindedRoute) : Destination {
            override val introductionNodeId: PublicKey = route.introductionNodeId
        }

        data class Recipient(val nodeId: PublicKey, val pathId: ByteVector?, val padding: ByteVector? = null, val customTlvs: Set<GenericTlv> = setOf()) : Destination {
            override val introductionNodeId: PublicKey = nodeId
        }
    }

    private fun buildIntermediatePayloads(
        intermediateNodes: List<IntermediateNode>,
        nextTlvs: Set<RouteBlindingEncryptedDataTlv>
    ): List<ByteVector> {
        return if (intermediateNodes.isEmpty()) {
            listOf()
        } else {
            intermediateNodes.drop(1).map { node -> setOf(RouteBlindingEncryptedDataTlv.OutgoingNodeId(node.nodeId)) }
                .plusElement(nextTlvs)
                .zip(intermediateNodes).map { (tlvs, hop) ->
                    TlvStream(
                        setOfNotNull(hop.padding?.let { RouteBlindingEncryptedDataTlv.Padding(it) }) + tlvs,
                        hop.customTlvs
                    )
                }
                .map { tlvs -> RouteBlindingEncryptedData(tlvs).write().toByteVector() }
        }
    }

    fun buildRoute(
        blindingSecret: PrivateKey,
        intermediateNodes: List<IntermediateNode>,
        recipient: Destination.Recipient
    ): RouteBlinding.BlindedRoute {
        val intermediatePayloads = buildIntermediatePayloads(
            intermediateNodes,
            setOf(RouteBlindingEncryptedDataTlv.OutgoingNodeId(recipient.nodeId))
        )
        val tlvs: Set<RouteBlindingEncryptedDataTlv> =
            setOfNotNull(recipient.padding?.let { RouteBlindingEncryptedDataTlv.Padding(it) },
                recipient.pathId?.let { RouteBlindingEncryptedDataTlv.PathId(it) })
        val lastPayload = RouteBlindingEncryptedData(TlvStream(tlvs, recipient.customTlvs)).write().toByteVector()
        return RouteBlinding.create(
            blindingSecret,
            intermediateNodes.map { it.nodeId } + recipient.nodeId,
            intermediatePayloads + lastPayload)
    }

    private fun buildRouteFrom(
        originKey: PrivateKey,
        blindingSecret: PrivateKey,
        intermediateNodes: List<IntermediateNode>,
        destination: Destination
    ): RouteBlinding.BlindedRoute? {
        when (destination) {
            is Destination.Recipient -> return buildRoute(blindingSecret, intermediateNodes, destination)
            is Destination.BlindedPath ->
                when {
                    destination.route.introductionNodeId == originKey.publicKey() ->
                        return try {
                            val (payload, nextBlinding) = RouteBlinding.decryptPayload(
                                originKey,
                                destination.route.blindingKey,
                                destination.route.blindedNodes[0].encryptedPayload
                            )
                            val data = RouteBlindingEncryptedData.read(payload.toByteArray())
                            data.nextNodeId?.let {
                                RouteBlinding.BlindedRoute(
                                    it,
                                    nextBlinding,
                                    destination.route.blindedNodes.drop(1)
                                )
                            }
                        } catch (e: Throwable) {
                            null
                        }
                    intermediateNodes.isEmpty() -> return destination.route
                    else -> {
                        val intermediatePayloads = buildIntermediatePayloads(
                            intermediateNodes,
                            setOf(
                                RouteBlindingEncryptedDataTlv.OutgoingNodeId(destination.route.introductionNodeId),
                                RouteBlindingEncryptedDataTlv.NextBlinding(destination.route.blindingKey)
                            )
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
        nodeKey: PrivateKey,
        sessionKey: PrivateKey,
        blindingSecret: PrivateKey,
        intermediateNodes: List<IntermediateNode>,
        destination: Destination,
        content: TlvStream<OnionMessagePayloadTlv>
    ): Either<BuildMessageError, Pair<PublicKey, OnionMessage>> {
        val route = buildRouteFrom(nodeKey, blindingSecret, intermediateNodes, destination)
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
            return Either.Right(Pair(route.introductionNodeId, OnionMessage(route.blindingKey, packet)))
        }
    }
}
