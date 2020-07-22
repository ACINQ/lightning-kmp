package fr.acinq.eklair.payment

import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.PublicKey
import fr.acinq.eklair.CltvExpiry
import fr.acinq.eklair.Eclair
import fr.acinq.eklair.MilliSatoshi
import fr.acinq.eklair.channel.CMD_ADD_HTLC
import fr.acinq.eklair.channel.Upstream
import fr.acinq.eklair.crypto.sphinx.PacketAndSecrets
import fr.acinq.eklair.crypto.sphinx.Sphinx
import fr.acinq.eklair.router.ChannelHop
import fr.acinq.eklair.router.Hop
import fr.acinq.eklair.router.NodeHop
import fr.acinq.eklair.wire.*
import fr.acinq.secp256k1.Hex

object OutgoingPacket {

    /**
     * Build an encrypted onion packet from onion payloads and node public keys.
     */
    fun buildOnion(nodes: List<PublicKey>, payloads: List<PerHopPayload>, associatedData: ByteVector32, payloadLength: Int): PacketAndSecrets {
        require(nodes.size == payloads.size)
        val sessionKey = Eclair.randomKey()
        val payloadsBin = payloads
        .map {
            when(it) {
                // TODO: implement this
                is FinalPayload -> Hex.decode("01010101")
                else -> Hex.decode("01010101")
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
        return hops.reversed().fold(Triple(finalPayload.amount, finalPayload.expiry, listOf(finalPayload))) {
            triple, hop ->
            val payload = when(hop) {
                // Since we don't have any scenario where we add tlv data for intermediate hops, we use legacy payloads.
                is ChannelHop -> RelayLegacyPayload(hop.lastUpdate.shortChannelId, triple.first, triple.second)
                is NodeHop -> NodeRelayPayload.create(triple.first, triple.second, hop.nextNodeId)
                else -> throw IllegalArgumentException("unsupported hop $hop")
            }
            return Triple(triple.first + hop.fee(triple.first), triple.second + hop.cltvExpiryDelta, listOf(payload) + triple.third)
        }
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
    fun buildCommand(upstream: Upstream, paymentHash: ByteVector32, hops: List<ChannelHop>, finalPayload: FinalPayload): Pair<CMD_ADD_HTLC, List<Pair<ByteVector32, PublicKey>>> {
        val (firstAmount, firstExpiry, onion) = buildPacket(paymentHash, hops, finalPayload, 1300)
        return Pair(CMD_ADD_HTLC(firstAmount, paymentHash, firstExpiry, onion.packet, upstream, commit = true), onion.sharedSecrets)
    }
}