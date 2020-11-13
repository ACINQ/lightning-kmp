package fr.acinq.eclair.payment

import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.bitcoin.PublicKey
import fr.acinq.eclair.CltvExpiry
import fr.acinq.eclair.Eclair
import fr.acinq.eclair.MilliSatoshi
import fr.acinq.eclair.channel.CMD_ADD_HTLC
import fr.acinq.eclair.channel.CMD_FAIL_HTLC
import fr.acinq.eclair.crypto.sphinx.FailurePacket
import fr.acinq.eclair.crypto.sphinx.PacketAndSecrets
import fr.acinq.eclair.crypto.sphinx.SharedSecrets
import fr.acinq.eclair.crypto.sphinx.Sphinx
import fr.acinq.eclair.router.ChannelHop
import fr.acinq.eclair.router.Hop
import fr.acinq.eclair.router.NodeHop
import fr.acinq.eclair.utils.Either
import fr.acinq.eclair.utils.Try
import fr.acinq.eclair.utils.UUID
import fr.acinq.eclair.wire.*

object OutgoingPacket {

    /**
     * Build an encrypted onion packet from onion payloads and node public keys.
     */
    fun buildOnion(nodes: List<PublicKey>, payloads: List<PerHopPayload>, associatedData: ByteVector32, payloadLength: Int): PacketAndSecrets {
        require(nodes.size == payloads.size)
        val sessionKey = Eclair.randomKey()
        val payloadsBin = payloads
            .map {
                when (it) {
                    is RelayLegacyPayload -> RelayLegacyPayload.write(it)
                    is NodeRelayPayload -> NodeRelayPayload.write(it)
                    is FinalPayload -> FinalPayload.write(it)
                }
            }
        return Sphinx.create(sessionKey, nodes, payloadsBin, associatedData, payloadLength)
    }

    /**
     * Build the onion payloads for each hop.
     *
     * @param hops         the hops as computed by the router + extra routes from payment request
     * @param finalPayload payload data for the final node (amount, expiry, etc)
     * @return a (firstAmount, firstExpiry, payloads) tuple where:
     *         - firstAmount is the amount for the first htlc in the route
     *         - firstExpiry is the cltv expiry for the first htlc in the route
     *         - a sequence of payloads that will be used to build the onion
     */
    fun buildPayloads(hops: List<Hop>, finalPayload: FinalPayload): Triple<MilliSatoshi, CltvExpiry, List<PerHopPayload>> {
        return hops.reversed().fold(Triple(finalPayload.amount, finalPayload.expiry, listOf<PerHopPayload>(finalPayload))) { triple, hop ->
            val (amount, expiry, payloads) = triple
            val payload = when (hop) {
                // Since we don't have any scenario where we add tlv data for intermediate hops, we use legacy payloads.
                is ChannelHop -> RelayLegacyPayload(hop.lastUpdate.shortChannelId, amount, expiry)
                is NodeHop -> NodeRelayPayload.create(amount, expiry, hop.nextNodeId)
            }
            Triple(amount + hop.fee(amount), expiry + hop.cltvExpiryDelta, listOf(payload) + payloads)
        }
    }

    /**
     * Build an encrypted trampoline onion packet when the final recipient doesn't support trampoline.
     * The next-to-last trampoline node payload will contain instructions to convert to a legacy payment.
     *
     * @param invoice      Bolt 11 invoice (features and routing hints will be provided to the next-to-last node).
     * @param hops         the trampoline hops (including ourselves in the first hop, and the non-trampoline final recipient in the last hop).
     * @param finalPayload payload data for the final node (amount, expiry, etc)
     * @return a (firstAmount, firstExpiry, onion) triple where:
     *         - firstAmount is the amount for the trampoline node in the route
     *         - firstExpiry is the cltv expiry for the first trampoline node in the route
     *         - the trampoline onion to include in final payload of a normal onion
     */
    fun buildTrampolineToLegacyPacket(invoice: PaymentRequest, hops: List<NodeHop>, finalPayload: FinalPayload): Triple<MilliSatoshi, CltvExpiry, PacketAndSecrets> {
        val (firstAmount, firstExpiry, payloads) = hops.drop(1).reversed().fold(Triple(finalPayload.amount, finalPayload.expiry, listOf<PerHopPayload>(finalPayload))) { triple, hop ->
            val (amount, expiry, payloads) = triple
            val payload = when (payloads.size) {
                // The next-to-last trampoline hop must include invoice data to indicate the conversion to a legacy payment.
                1 -> NodeRelayPayload.createNodeRelayToNonTrampolinePayload(finalPayload.amount, finalPayload.totalAmount, finalPayload.expiry, hop.nextNodeId, invoice)
                else -> NodeRelayPayload.create(amount, expiry, hop.nextNodeId)
            }
            Triple(amount + hop.fee(amount), expiry + hop.cltvExpiryDelta, listOf(payload) + payloads)
        }
        val nodes = hops.map { it.nextNodeId }
        val onion = buildOnion(nodes, payloads, invoice.paymentHash, OnionRoutingPacket.TrampolinePacketLength)
        return Triple(firstAmount, firstExpiry, onion)
    }

    /**
     * Build an encrypted onion packet with the given final payload.
     *
     * @param hops         the hops as computed by the router + extra routes from payment request, including ourselves in the first hop
     * @param finalPayload payload data for the final node (amount, expiry, etc)
     * @return a (firstAmount, firstExpiry, onion) tuple where:
     *         - firstAmount is the amount for the first htlc in the route
     *         - firstExpiry is the cltv expiry for the first htlc in the route
     *         - the onion to include in the HTLC
     */
    fun buildPacket(paymentHash: ByteVector32, hops: List<Hop>, finalPayload: FinalPayload, payloadLength: Int): Triple<MilliSatoshi, CltvExpiry, PacketAndSecrets> {
        val (firstAmount, firstExpiry, payloads) = buildPayloads(hops.drop(1), finalPayload)
        val nodes = hops.map { it.nextNodeId }
        // BOLT 2 requires that associatedData == paymentHash
        val onion = buildOnion(nodes, payloads, paymentHash, payloadLength)
        return Triple(firstAmount, firstExpiry, onion)
    }

    /**
     * Build the command to add an HTLC with the given final payload and using the provided hops.
     *
     * @return the command and the onion shared secrets (used to decrypt the error in case of payment failure)
     */
    fun buildCommand(paymentId: UUID, paymentHash: ByteVector32, hops: List<ChannelHop>, finalPayload: FinalPayload): Pair<CMD_ADD_HTLC, SharedSecrets> {
        val (firstAmount, firstExpiry, onion) = buildPacket(paymentHash, hops, finalPayload, OnionRoutingPacket.PaymentPacketLength)
        return Pair(CMD_ADD_HTLC(firstAmount, paymentHash, firstExpiry, onion.packet, paymentId, commit = true), onion.sharedSecrets)
    }

    fun buildHtlcFailure(nodeSecret: PrivateKey, paymentHash: ByteVector32, onion: OnionRoutingPacket, reason: CMD_FAIL_HTLC.Reason): Try<ByteVector> {
        // we need to decrypt the payment onion to obtain the shared secret to build the error packet
        return when (val result = Sphinx.peel(nodeSecret, paymentHash, onion, OnionRoutingPacket.PaymentPacketLength)) {
            is Either.Right -> {
                val encryptedReason = when (reason) {
                    is CMD_FAIL_HTLC.Reason.Bytes -> FailurePacket.wrap(reason.bytes.toByteArray(), result.value.sharedSecret)
                    is CMD_FAIL_HTLC.Reason.Failure -> FailurePacket.create(result.value.sharedSecret, reason.message)
                }
                Try.Success(ByteVector(encryptedReason))
            }
            is Either.Left -> Try.Failure(RuntimeException("failed to build htlc failure"))
        }
    }
}