package fr.acinq.eklair.payment

import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.PublicKey
import fr.acinq.eklair.channel.CMD_ADD_HTLC
import fr.acinq.eklair.channel.Upstream
import fr.acinq.eklair.crypto.Sphinx
import fr.acinq.eklair.router.ChannelHop
import fr.acinq.eklair.wire.FinalPayload
import fr.acinq.eklair.wire.OnionRoutingPacket
import fr.acinq.eklair.wire.PacketType
import fr.acinq.eklair.wire.PaymentPacket

object OutgoingPacket {
    /**
     * Build the command to add an HTLC with the given final payload and using the provided hops.
     *
     * @return the command and the onion shared secrets (used to decrypt the error in case of payment failure)
     */
    fun buildCommand(upstream: Upstream, paymentHash: ByteVector32, hops: List<ChannelHop>, finalPayload: FinalPayload): Pair<CMD_ADD_HTLC, List<Pair<ByteVector32, PublicKey>>> {
        val firstAmount = finalPayload.amount
        val firstExpiry = finalPayload.expiry
        val packet = OnionRoutingPacket(0, ByteVector.empty, ByteVector.empty, ByteVector32.Zeroes)
        val sharedSecrets = listOf<Pair<ByteVector32, PublicKey>>()
        return Pair(CMD_ADD_HTLC(firstAmount, paymentHash, firstExpiry, packet, upstream, commit = true), sharedSecrets)
    }
}