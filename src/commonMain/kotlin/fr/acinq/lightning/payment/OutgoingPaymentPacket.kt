package fr.acinq.lightning.payment

import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.bitcoin.PublicKey
import fr.acinq.lightning.CltvExpiry
import fr.acinq.lightning.Lightning
import fr.acinq.lightning.MilliSatoshi
import fr.acinq.lightning.channel.CMD_ADD_HTLC
import fr.acinq.lightning.channel.CMD_FAIL_HTLC
import fr.acinq.lightning.crypto.sphinx.FailurePacket
import fr.acinq.lightning.crypto.sphinx.PacketAndSecrets
import fr.acinq.lightning.crypto.sphinx.SharedSecrets
import fr.acinq.lightning.crypto.sphinx.Sphinx
import fr.acinq.lightning.router.ChannelHop
import fr.acinq.lightning.router.Hop
import fr.acinq.lightning.router.NodeHop
import fr.acinq.lightning.utils.Either
import fr.acinq.lightning.utils.Try
import fr.acinq.lightning.utils.UUID
import fr.acinq.lightning.wire.FailureMessage
import fr.acinq.lightning.wire.OnionRoutingPacket
import fr.acinq.lightning.wire.PaymentOnion

object OutgoingPaymentPacket {

    /**
     * Build an encrypted onion packet from onion payloads and node public keys.
     */
    private fun buildOnion(nodes: List<PublicKey>, payloads: List<PaymentOnion.PerHopPayload>, associatedData: ByteVector32, payloadLength: Int): Try<PacketAndSecrets> {
        require(nodes.size == payloads.size)
        val sessionKey = Lightning.randomKey()
        val payloadsBin = payloads
            .map {
                when (it) {
                    is PaymentOnion.ChannelRelayPayload -> it.write()
                    is PaymentOnion.NodeRelayPayload -> it.write()
                    is PaymentOnion.FinalPayload -> it.write()
                }
            }
        return Sphinx.create(sessionKey, nodes, payloadsBin, associatedData, payloadLength)
    }

    /**
     * Build the onion payloads for each hop.
     *
     * @param hops the hops as computed by the router + extra routes from payment request
     * @param finalPayload payload data for the final node (amount, expiry, etc)
     * @return a (firstAmount, firstExpiry, payloads) tuple where:
     *  - firstAmount is the amount for the first htlc in the route
     *  - firstExpiry is the cltv expiry for the first htlc in the route
     *  - a sequence of payloads that will be used to build the onion
     */
    private fun buildPayloads(hops: List<Hop>, finalPayload: PaymentOnion.FinalPayload): Triple<MilliSatoshi, CltvExpiry, List<PaymentOnion.PerHopPayload>> {
        return hops.reversed().fold(Triple(finalPayload.amount, finalPayload.expiry, listOf<PaymentOnion.PerHopPayload>(finalPayload))) { triple, hop ->
            val (amount, expiry, payloads) = triple
            val payload = when (hop) {
                // Since we don't have any scenario where we add tlv data for intermediate hops, we use legacy payloads.
                is ChannelHop -> PaymentOnion.ChannelRelayPayload.create(hop.lastUpdate.shortChannelId, amount, expiry)
                is NodeHop -> PaymentOnion.NodeRelayPayload.create(amount, expiry, hop.nextNodeId)
            }
            Triple(amount + hop.fee(amount), expiry + hop.cltvExpiryDelta, listOf(payload) + payloads)
        }
    }

    /**
     * Build an encrypted trampoline onion packet when the final recipient doesn't support trampoline.
     * The next-to-last trampoline node payload will contain instructions to convert to a legacy payment.
     *
     * @param invoice Bolt 11 invoice (features and routing hints will be provided to the next-to-last node).
     * @param hops the trampoline hops (including ourselves in the first hop, and the non-trampoline final recipient in the last hop).
     * @param finalPayload payload data for the final node (amount, expiry, etc)
     * @return a (firstAmount, firstExpiry, onion) triple where:
     *  - firstAmount is the amount for the trampoline node in the route
     *  - firstExpiry is the cltv expiry for the first trampoline node in the route
     *  - the trampoline onion to include in final payload of a normal onion
     */
    fun buildTrampolineToLegacyPacket(invoice: PaymentRequest, hops: List<NodeHop>, finalPayload: PaymentOnion.FinalPayload): Try<Triple<MilliSatoshi, CltvExpiry, PacketAndSecrets>> {
        // NB: the final payload will never reach the recipient, since the next-to-last trampoline hop will convert that to a legacy payment
        // We use the smallest final payload possible, otherwise we may overflow the trampoline onion size.
        val dummyFinalPayload = PaymentOnion.FinalPayload.createSinglePartPayload(finalPayload.amount, finalPayload.expiry, finalPayload.paymentSecret, null)
        val (firstAmount, firstExpiry, payloads) = hops.drop(1).reversed().fold(Triple(finalPayload.amount, finalPayload.expiry, listOf<PaymentOnion.PerHopPayload>(dummyFinalPayload))) { triple, hop ->
            val (amount, expiry, payloads) = triple
            val payload = when (payloads.size) {
                // The next-to-last trampoline hop must include invoice data to indicate the conversion to a legacy payment.
                1 -> PaymentOnion.NodeRelayPayload.createNodeRelayToNonTrampolinePayload(finalPayload.amount, finalPayload.totalAmount, finalPayload.expiry, hop.nextNodeId, invoice)
                else -> PaymentOnion.NodeRelayPayload.create(amount, expiry, hop.nextNodeId)
            }
            Triple(amount + hop.fee(amount), expiry + hop.cltvExpiryDelta, listOf(payload) + payloads)
        }
        val nodes = hops.map { it.nextNodeId }
        return buildOnion(nodes, payloads, invoice.paymentHash, OnionRoutingPacket.TrampolinePacketLength).map { onion ->
            Triple(firstAmount, firstExpiry, onion)
        }
    }

    /**
     * Build an encrypted onion packet with the given final payload.
     *
     * @param hops the hops as computed by the router + extra routes from payment request, including ourselves in the first hop
     * @param finalPayload payload data for the final node (amount, expiry, etc)
     * @return a (firstAmount, firstExpiry, onion) tuple where:
     *  - firstAmount is the amount for the first htlc in the route
     *  - firstExpiry is the cltv expiry for the first htlc in the route
     *  - the onion to include in the HTLC
     */
    fun buildPacket(paymentHash: ByteVector32, hops: List<Hop>, finalPayload: PaymentOnion.FinalPayload, payloadLength: Int): Try<Triple<MilliSatoshi, CltvExpiry, PacketAndSecrets>> {
        val (firstAmount, firstExpiry, payloads) = buildPayloads(hops.drop(1), finalPayload)
        val nodes = hops.map { it.nextNodeId }
        // BOLT 2 requires that associatedData == paymentHash
        return buildOnion(nodes, payloads, paymentHash, payloadLength).map { onion ->
            Triple(firstAmount, firstExpiry, onion)
        }
    }

    /**
     * Build the command to add an HTLC with the given final payload and using the provided hops.
     *
     * @return the command and the onion shared secrets (used to decrypt the error in case of payment failure)
     */
    fun buildCommand(paymentId: UUID, paymentHash: ByteVector32, hops: List<ChannelHop>, finalPayload: PaymentOnion.FinalPayload): Try<Pair<CMD_ADD_HTLC, SharedSecrets>> {
        return buildPacket(paymentHash, hops, finalPayload, OnionRoutingPacket.PaymentPacketLength).map { (firstAmount, firstExpiry, onion) ->
            Pair(CMD_ADD_HTLC(firstAmount, paymentHash, firstExpiry, onion.packet, paymentId, commit = true), onion.sharedSecrets)
        }
    }

    fun buildHtlcFailure(nodeSecret: PrivateKey, paymentHash: ByteVector32, onion: OnionRoutingPacket, reason: CMD_FAIL_HTLC.Reason): Either<FailureMessage, ByteVector> {
        // we need to decrypt the payment onion to obtain the shared secret to build the error packet
        return when (val result = Sphinx.peel(nodeSecret, paymentHash, onion, onion.payload.size())) {
            is Either.Right -> {
                val encryptedReason = when (reason) {
                    is CMD_FAIL_HTLC.Reason.Bytes -> FailurePacket.wrap(reason.bytes.toByteArray(), result.value.sharedSecret)
                    is CMD_FAIL_HTLC.Reason.Failure -> FailurePacket.create(result.value.sharedSecret, reason.message)
                }
                Either.Right(ByteVector(encryptedReason))
            }
            is Either.Left -> Either.Left(result.value)
        }
    }

}