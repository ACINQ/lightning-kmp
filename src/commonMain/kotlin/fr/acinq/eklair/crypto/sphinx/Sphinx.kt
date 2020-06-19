package fr.acinq.eklair.crypto.sphinx

import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.PublicKey
import fr.acinq.eklair.wire.OnionRoutingPacket
import fr.acinq.eklair.wire.PacketType

data class PacketAndSecrets(val packet: OnionRoutingPacket, val sharedSecrets: List<Pair<ByteVector32, PublicKey>>)

sealed class OnionRoutingPacket<T : PacketType> {
    /**
     * Supported packet version. Note that since this value is outside of the onion encrypted payload, intermediate
     * nodes may or may not use this value when forwarding the packet to the next node.
     */
    val Version = 0

    /**
     * Length of the encrypted onion payload.
     */
    abstract val PayloadLength: Int
}
