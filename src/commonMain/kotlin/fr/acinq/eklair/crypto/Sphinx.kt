package fr.acinq.eklair.crypto

import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.eklair.wire.BadOnion
import fr.acinq.eklair.wire.FailureMessage
import fr.acinq.eklair.wire.OnionRoutingPacket as WireOnionRoutingPacket


object Sphinx {

    data class DecryptedPacket(val payload: ByteVector, val nextPacket: WireOnionRoutingPacket, val sharedSecret: ByteVector32) {
        val isLastPacket: Boolean = nextPacket.hmac == ByteVector32.Zeroes
    }

    sealed class OnionRoutingPacket(val payloadLength: Int) {

        sealed class PeelResult {
            data class Success(val decryptedPacket: DecryptedPacket): PeelResult()
            data class Error(val badOnion: BadOnion): PeelResult()
        }

        // TODO: Obviously a placeholder!
        fun peel(privateKey: PrivateKey, associatedData: ByteVector, packet: WireOnionRoutingPacket): PeelResult =
            PeelResult.Success(DecryptedPacket(
                ByteVector.empty,
                WireOnionRoutingPacket(0, ByteVector.empty, ByteVector.empty, ByteVector32.Zeroes),
                ByteVector32.Zeroes
            ))

        object PaymentPacket : OnionRoutingPacket(payloadLength = 1300)
    }

    object FailurePacket {
        // TODO: Obviously a placeholder!
        fun create(sharedSecret: ByteVector32, failure: FailureMessage): ByteVector = ByteVector.empty
        // TODO: Obviously a placeholder!
        fun wrap(packet: ByteVector, sharedSecret: ByteVector32): ByteVector = ByteVector.empty
    }

}
