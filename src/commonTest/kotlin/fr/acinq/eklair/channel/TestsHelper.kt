package fr.acinq.eklair.channel

import fr.acinq.bitcoin.*
import fr.acinq.eklair.CltvExpiryDelta
import fr.acinq.eklair.Eclair
import fr.acinq.eklair.MilliSatoshi
import fr.acinq.eklair.ShortChannelId
import fr.acinq.eklair.payment.OutgoingPacket
import fr.acinq.eklair.router.ChannelHop
import fr.acinq.eklair.utils.UUID
import fr.acinq.eklair.utils.msat
import fr.acinq.eklair.utils.toByteVector32
import fr.acinq.eklair.wire.ChannelUpdate
import fr.acinq.eklair.wire.FinalLegacyPayload

object TestsHelper {
    fun makeCmdAdd(amount: MilliSatoshi, destination: PublicKey, currentBlockHeight: Long, paymentPreimage: ByteVector32 = Eclair.randomBytes32(), upstream: Upstream = Upstream.Local(UUID.randomUUID())): Pair<ByteVector32, CMD_ADD_HTLC> {
        val paymentHash: ByteVector32 = Crypto.sha256(paymentPreimage).toByteVector32()
        val expiry = CltvExpiryDelta(144).toCltvExpiry(currentBlockHeight)
        val dummyKey = PrivateKey(ByteVector32("0101010101010101010101010101010101010101010101010101010101010101")).publicKey()
        val dummyUpdate = ChannelUpdate(ByteVector64.Zeroes, ByteVector32.Zeroes, ShortChannelId(144,0,0), 0, 0, 0, CltvExpiryDelta(1), 0.msat, 0.msat, 0, null)
        val cmd = OutgoingPacket.buildCommand(upstream, paymentHash, listOf(ChannelHop(dummyKey, destination, dummyUpdate)), FinalLegacyPayload(amount, expiry)).first.copy(commit = false)
        return Pair(paymentPreimage, cmd)
    }
}