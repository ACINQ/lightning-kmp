package fr.acinq.lightning.router

import fr.acinq.bitcoin.*
import fr.acinq.bitcoin.io.ByteArrayOutput
import fr.acinq.lightning.CltvExpiryDelta
import fr.acinq.lightning.MilliSatoshi
import fr.acinq.lightning.ShortChannelId
import fr.acinq.lightning.utils.currentTimestampSeconds
import fr.acinq.lightning.utils.toByteVector32
import fr.acinq.lightning.wire.ChannelUpdate
import fr.acinq.lightning.wire.LightningCodecs
import kotlin.experimental.or

object Announcements {

    /**
     * BOLT 7:
     * The creating node MUST set node-id-1 and node-id-2 to the public keys of the
     * two nodes who are operating the channel, such that node-id-1 is the numerically-lesser
     * of the two DER encoded keys sorted in ascending numerical order,
     *
     * @return true if localNodeId is node1
     */
    fun isNode1(localNodeId: PublicKey, remoteNodeId: PublicKey) = LexicographicalOrdering.isLessThan(localNodeId.value, remoteNodeId.value)

    fun makeChannelFlags(isNode1: Boolean, enable: Boolean): Byte {
        var result: Byte = 0
        if (!isNode1) result = result or 1
        if (!enable) result = result or 2
        return result
    }

    fun makeChannelUpdate(
        chainHash: ByteVector32,
        nodePrivateKey: PrivateKey,
        remoteNodeId: PublicKey,
        shortChannelId: ShortChannelId,
        cltvExpiryDelta: CltvExpiryDelta,
        htlcMinimum: MilliSatoshi,
        feeBase: MilliSatoshi,
        feeProportionalMillionths: Long,
        htlcMaximum: MilliSatoshi,
        enable: Boolean = true,
        timestampSeconds: Long = currentTimestampSeconds()
    ): ChannelUpdate {
        val messageFlags = 1.toByte() // NB: we always support option_channel_htlc_max
        val channelFlags = makeChannelFlags(isNode1(nodePrivateKey.publicKey(), remoteNodeId), enable)
        val witness = channelUpdateWitnessEncode(chainHash, shortChannelId, timestampSeconds, messageFlags, channelFlags, cltvExpiryDelta, htlcMinimum, feeBase, feeProportionalMillionths, htlcMaximum, ByteVector.empty)
        val sig = Crypto.sign(witness, nodePrivateKey)
        return ChannelUpdate(sig, chainHash, shortChannelId, timestampSeconds, messageFlags, channelFlags, cltvExpiryDelta, htlcMinimum, feeBase, feeProportionalMillionths, htlcMaximum)
    }

    fun disableChannel(channelUpdate: ChannelUpdate, nodePrivateKey: PrivateKey, remoteNodeId: PublicKey): ChannelUpdate {
        return when (channelUpdate.isEnabled()) {
            true -> makeChannelUpdate(
                channelUpdate.chainHash,
                nodePrivateKey,
                remoteNodeId,
                channelUpdate.shortChannelId,
                channelUpdate.cltvExpiryDelta,
                channelUpdate.htlcMinimumMsat,
                channelUpdate.feeBaseMsat,
                channelUpdate.feeProportionalMillionths,
                channelUpdate.htlcMaximumMsat!!,
                enable = false
            )
            false -> channelUpdate // channel is already disabled
        }
    }

    fun enableChannel(channelUpdate: ChannelUpdate, nodePrivateKey: PrivateKey, remoteNodeId: PublicKey): ChannelUpdate {
        return when (channelUpdate.isEnabled()) {
            true -> channelUpdate // channel is already enabled
            false -> makeChannelUpdate(
                channelUpdate.chainHash,
                nodePrivateKey,
                remoteNodeId,
                channelUpdate.shortChannelId,
                channelUpdate.cltvExpiryDelta,
                channelUpdate.htlcMinimumMsat,
                channelUpdate.feeBaseMsat,
                channelUpdate.feeProportionalMillionths,
                channelUpdate.htlcMaximumMsat!!,
                enable = true
            )
        }
    }

    private fun channelUpdateWitnessEncode(
        chainHash: ByteVector32,
        shortChannelId: ShortChannelId,
        timestampSeconds: Long,
        messageFlags: Byte,
        channelFlags: Byte,
        cltvExpiryDelta: CltvExpiryDelta,
        htlcMinimum: MilliSatoshi,
        feeBase: MilliSatoshi,
        feeProportionalMillionths: Long,
        htlcMaximum: MilliSatoshi,
        unknownFields: ByteVector
    ): ByteVector32 {
        val out = ByteArrayOutput()
        LightningCodecs.writeBytes(chainHash, out)
        LightningCodecs.writeU64(shortChannelId.toLong(), out)
        LightningCodecs.writeU32(timestampSeconds.toInt(), out)
        LightningCodecs.writeByte(messageFlags.toInt(), out)
        LightningCodecs.writeByte(channelFlags.toInt(), out)
        LightningCodecs.writeU16(cltvExpiryDelta.toInt(), out)
        LightningCodecs.writeU64(htlcMinimum.toLong(), out)
        LightningCodecs.writeU32(feeBase.toLong().toInt(), out)
        LightningCodecs.writeU32(feeProportionalMillionths.toInt(), out)
        LightningCodecs.writeU64(htlcMaximum.toLong(), out)
        LightningCodecs.writeBytes(unknownFields, out)
        return Crypto.sha256(Crypto.sha256(out.toByteArray())).toByteVector32()
    }

}