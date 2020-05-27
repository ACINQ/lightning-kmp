package fr.acinq.eklair

import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.eklair.wire.OnionRoutingPacket

object TestConstants {
    val emptyOnionPacket = OnionRoutingPacket(0, ByteVector(ByteArray(33)), ByteVector(ByteArray(1300)), ByteVector32.Zeroes)
}