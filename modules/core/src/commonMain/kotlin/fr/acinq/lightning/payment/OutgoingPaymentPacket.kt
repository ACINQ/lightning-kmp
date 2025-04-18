package fr.acinq.lightning.payment

import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.bitcoin.PublicKey
import fr.acinq.bitcoin.utils.Either
import fr.acinq.lightning.CltvExpiry
import fr.acinq.lightning.Feature
import fr.acinq.lightning.Lightning
import fr.acinq.lightning.MilliSatoshi
import fr.acinq.lightning.channel.ChannelCommand
import fr.acinq.lightning.crypto.RouteBlinding
import fr.acinq.lightning.crypto.sphinx.FailurePacket
import fr.acinq.lightning.crypto.sphinx.PacketAndSecrets
import fr.acinq.lightning.crypto.sphinx.Sphinx
import fr.acinq.lightning.router.NodeHop
import fr.acinq.lightning.wire.*

object OutgoingPaymentPacket {

    /**
     * Build an encrypted onion packet from onion payloads and node public keys.
     */
    fun buildOnion(nodes: List<PublicKey>, payloads: List<PaymentOnion.PerHopPayload>, associatedData: ByteVector32, payloadLength: Int? = null): PacketAndSecrets {
        val sessionKey = Lightning.randomKey()
        return buildOnion(sessionKey, nodes, payloads, associatedData, payloadLength)
    }

    private fun buildOnion(sessionKey: PrivateKey, nodes: List<PublicKey>, payloads: List<PaymentOnion.PerHopPayload>, associatedData: ByteVector32, payloadLength: Int? = null): PacketAndSecrets {
        require(nodes.size == payloads.size)
        val payloadsBin = payloads.map { it.write() }
        val totalPayloadLength = payloadLength ?: payloadsBin.sumOf { it.size + Sphinx.MacLength }
        return Sphinx.create(sessionKey, nodes, payloadsBin, associatedData, totalPayloadLength)
    }

    /**
     * Build an encrypted payment onion packet when the final recipient supports trampoline.
     * The trampoline node will receive instructions on how much to relay to the final recipient.
     *
     * @param invoice a Bolt 11 invoice that contains the trampoline feature bit.
     * @param amount amount that should be received by the final recipient.
     * @param expiry cltv expiry that should be received by the final recipient.
     * @param hop the trampoline hop from the trampoline node to the recipient.
     */
    fun buildPacketToTrampolineRecipient(invoice: Bolt11Invoice, amount: MilliSatoshi, expiry: CltvExpiry, hop: NodeHop): Triple<MilliSatoshi, CltvExpiry, PacketAndSecrets> {
        require(invoice.features.hasFeature(Feature.ExperimentalTrampolinePayment) || invoice.features.hasFeature(Feature.TrampolinePayment)) { "invoice must support trampoline" }
        val trampolineOnion = run {
            val finalPayload = PaymentOnion.FinalPayload.Standard.createSinglePartPayload(amount, expiry, invoice.paymentSecret, invoice.paymentMetadata)
            val trampolinePayload = PaymentOnion.NodeRelayPayload.create(amount, expiry, hop.nextNodeId)
            // We may be paying an older version of lightning-kmp that only supports trampoline packets of size 400.
            buildOnion(listOf(hop.nodeId, hop.nextNodeId), listOf(trampolinePayload, finalPayload), invoice.paymentHash, payloadLength = 400)
        }
        val trampolineAmount = amount + hop.fee(amount)
        val trampolineExpiry = expiry + hop.cltvExpiryDelta
        // We generate a random secret to avoid leaking the invoice secret to the trampoline node.
        val trampolinePaymentSecret = Lightning.randomBytes32()
        val payload = PaymentOnion.FinalPayload.Standard.createTrampolinePayload(trampolineAmount, trampolineAmount, trampolineExpiry, trampolinePaymentSecret, trampolineOnion.packet)
        val paymentOnion = buildOnion(listOf(hop.nodeId), listOf(payload), invoice.paymentHash, OnionRoutingPacket.PaymentPacketLength)
        return Triple(trampolineAmount, trampolineExpiry, paymentOnion)
    }

    /**
     * Build an encrypted payment onion packet when the final recipient is our trampoline node.
     *
     * @param invoice a Bolt 11 invoice that contains the trampoline feature bit.
     * @param amount amount that should be received by the final recipient.
     * @param expiry cltv expiry that should be received by the final recipient.
     */
    fun buildPacketToTrampolinePeer(invoice: Bolt11Invoice, amount: MilliSatoshi, expiry: CltvExpiry): Triple<MilliSatoshi, CltvExpiry, PacketAndSecrets> {
        require(invoice.features.hasFeature(Feature.ExperimentalTrampolinePayment) || invoice.features.hasFeature(Feature.TrampolinePayment)) { "invoice must support trampoline" }
        val trampolineOnion = run {
            val finalPayload = PaymentOnion.FinalPayload.Standard.createSinglePartPayload(amount, expiry, invoice.paymentSecret, invoice.paymentMetadata)
            buildOnion(listOf(invoice.nodeId), listOf(finalPayload), invoice.paymentHash)
        }
        val payload = PaymentOnion.FinalPayload.Standard.createTrampolinePayload(amount, amount, expiry, invoice.paymentSecret, trampolineOnion.packet)
        val paymentOnion = buildOnion(listOf(invoice.nodeId), listOf(payload), invoice.paymentHash, OnionRoutingPacket.PaymentPacketLength)
        return Triple(amount, expiry, paymentOnion)
    }

    /**
     * Build an encrypted trampoline onion packet when the final recipient doesn't support trampoline.
     * The trampoline node will receive instructions to convert to a legacy payment.
     * This reveals to the trampoline node who the recipient is and details from the invoice.
     * This must be deprecated once recipients support either trampoline or blinded paths.
     *
     * @param invoice a Bolt11 invoice (features and routing hints will be provided to the trampoline node).
     * @param amount amount that should be received by the final recipient.
     * @param expiry cltv expiry that should be received by the final recipient.
     * @param hop the trampoline hop from the trampoline node to the recipient.
     */
    fun buildPacketToLegacyRecipient(invoice: Bolt11Invoice, amount: MilliSatoshi, expiry: CltvExpiry, hop: NodeHop): Triple<MilliSatoshi, CltvExpiry, PacketAndSecrets> {
        val trampolineOnion = run {
            // NB: the final payload will never reach the recipient, since the trampoline node will convert that to a legacy payment.
            // We use the smallest final payload possible, otherwise we may overflow the trampoline onion size.
            val dummyFinalPayload = PaymentOnion.FinalPayload.Standard.createSinglePartPayload(amount, expiry, invoice.paymentSecret, null)
            var routingInfo = invoice.routingInfo
            var trampolinePayload = PaymentOnion.RelayToNonTrampolinePayload.create(amount, amount, expiry, hop.nextNodeId, invoice, routingInfo)
            var trampolineOnion = buildOnion(listOf(hop.nodeId, hop.nextNodeId), listOf(trampolinePayload, dummyFinalPayload), invoice.paymentHash)
            // Ensure that this onion can fit inside the outer 1300 bytes onion. The outer onion fields need ~150 bytes and we add some safety margin.
            while (trampolineOnion.packet.payload.size() > 1000) {
                routingInfo = routingInfo.dropLast(1)
                trampolinePayload = PaymentOnion.RelayToNonTrampolinePayload.create(amount, amount, expiry, hop.nextNodeId, invoice, routingInfo)
                trampolineOnion = buildOnion(listOf(hop.nodeId, hop.nextNodeId), listOf(trampolinePayload, dummyFinalPayload), invoice.paymentHash)
            }
            trampolineOnion
        }
        val trampolineAmount = amount + hop.fee(amount)
        val trampolineExpiry = expiry + hop.cltvExpiryDelta
        val payload = PaymentOnion.FinalPayload.Standard.createTrampolinePayload(trampolineAmount, trampolineAmount, trampolineExpiry, invoice.paymentSecret, trampolineOnion.packet)
        val paymentOnion = buildOnion(listOf(hop.nodeId), listOf(payload), invoice.paymentHash, OnionRoutingPacket.PaymentPacketLength)
        return Triple(trampolineAmount, trampolineExpiry, paymentOnion)
    }

    /**
     * Build an encrypted trampoline onion packet when the final recipient is using a blinded path.
     * The trampoline node will receive data from the invoice to allow them to pay the blinded path.
     * The data revealed to the trampoline node doesn't leak anything about the recipient's identity.
     * We only need a single trampoline node, who will find routes to the blinded paths.
     *
     * @param invoice a Bolt12 invoice (blinded path data will be provided to the trampoline node).
     * @param amount amount that should be received by the final recipient.
     * @param expiry cltv expiry that should be received by the final recipient.
     * @param hop the trampoline hop from the trampoline node to the recipient.
     */
    fun buildPacketToBlindedRecipient(invoice: Bolt12Invoice, amount: MilliSatoshi, expiry: CltvExpiry, hop: NodeHop): Triple<MilliSatoshi, CltvExpiry, PacketAndSecrets> {
        val trampolineOnion = run {
            var blindedPaths = invoice.blindedPaths
            var trampolinePayload = PaymentOnion.RelayToBlindedPayload.create(amount, expiry, invoice.features, blindedPaths)
            var trampolineOnion = buildOnion(listOf(hop.nodeId), listOf(trampolinePayload), invoice.paymentHash)
            // Ensure that this onion can fit inside the outer 1300 bytes onion. The outer onion fields need ~150 bytes and we add some safety margin.
            while (trampolineOnion.packet.payload.size() > 1000) {
                blindedPaths = blindedPaths.dropLast(1)
                trampolinePayload = PaymentOnion.RelayToBlindedPayload.create(amount, expiry, invoice.features, blindedPaths)
                trampolineOnion = buildOnion(listOf(hop.nodeId), listOf(trampolinePayload), invoice.paymentHash)
            }
            trampolineOnion
        }
        val trampolineAmount = amount + hop.fee(amount)
        val trampolineExpiry = expiry + hop.cltvExpiryDelta
        // We generate a random secret to avoid leaking the invoice secret to the trampoline node.
        val trampolinePaymentSecret = Lightning.randomBytes32()
        val payload = PaymentOnion.FinalPayload.Standard.createTrampolinePayload(trampolineAmount, trampolineAmount, trampolineExpiry, trampolinePaymentSecret, trampolineOnion.packet)
        val paymentOnion = buildOnion(listOf(hop.nodeId), listOf(payload), invoice.paymentHash, OnionRoutingPacket.PaymentPacketLength)
        return Triple(trampolineAmount, trampolineExpiry, paymentOnion)
    }

    fun buildHtlcFailure(nodeSecret: PrivateKey, paymentHash: ByteVector32, onion: OnionRoutingPacket, pathKey: PublicKey?, reason: ChannelCommand.Htlc.Settlement.Fail.Reason): Either<FailureMessage, ByteVector> {
        // We need to decrypt the payment onion to obtain the shared secret to build the error packet.
        val onionDecryptionKey = pathKey?.let { RouteBlinding.derivePrivateKey(nodeSecret, it) } ?: nodeSecret
        return when (val result = Sphinx.peel(onionDecryptionKey, paymentHash, onion)) {
            is Either.Right -> {
                val encryptedReason = when (reason) {
                    is ChannelCommand.Htlc.Settlement.Fail.Reason.Bytes -> FailurePacket.wrap(reason.bytes.toByteArray(), result.value.sharedSecret)
                    is ChannelCommand.Htlc.Settlement.Fail.Reason.Failure -> FailurePacket.create(result.value.sharedSecret, reason.message)
                }
                Either.Right(ByteVector(encryptedReason))
            }
            is Either.Left -> Either.Left(result.value)
        }
    }

    fun buildWillAddHtlcFailure(nodeSecret: PrivateKey, willAddHtlc: WillAddHtlc, failure: FailureMessage): OnTheFlyFundingMessage {
        val reason = ChannelCommand.Htlc.Settlement.Fail.Reason.Failure(failure)
        return when (val f = buildHtlcFailure(nodeSecret, willAddHtlc.paymentHash, willAddHtlc.finalPacket, willAddHtlc.pathKey, reason)) {
            is Either.Right -> WillFailHtlc(willAddHtlc.id, willAddHtlc.paymentHash, f.value)
            is Either.Left -> WillFailMalformedHtlc(willAddHtlc.id, willAddHtlc.paymentHash, Sphinx.hash(willAddHtlc.finalPacket), f.value.code)
        }
    }

}