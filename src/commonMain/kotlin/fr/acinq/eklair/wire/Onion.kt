package fr.acinq.eklair.wire

import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.ByteVector32

data class OnionRoutingPacket(val version: Int, val publicKey: ByteVector, val payload: ByteVector, val hmac: ByteVector32)
