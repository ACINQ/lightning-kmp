package fr.acinq.eklair.wire

import fr.acinq.bitcoin.*
import fr.acinq.eklair.CltvExpiry
import fr.acinq.eklair.CltvExpiryDelta
import fr.acinq.eklair.MilliSatoshi
import fr.acinq.eklair.ShortChannelId


interface LightningMessage
interface HtlcMessage : LightningMessage
interface RoutingMessage : LightningMessage
interface AnnouncementMessage : RoutingMessage // <- not in the spec
interface HasTimestamp : LightningMessage { val timestamp: Long }
interface UpdateMessage : LightningMessage
interface HasChannelId : LightningMessage { val channelId: ByteVector32 }
interface HasChainHash : LightningMessage { val chainHash: ByteVector32 } // <- not in the spec

interface ChannelMessage

data class FundingLocked(
    override val channelId: ByteVector32,
    val nextPerCommitmentPoint: PublicKey
) : ChannelMessage, HasChannelId

data class UpdateAddHtlc(
    override val channelId: ByteVector32,
    val id: Long,
    val amountMsat: MilliSatoshi,
    val paymentHash: ByteVector32,
    val cltvExpiry: CltvExpiry,
    val onionRoutingPacket: OnionRoutingPacket
) : HtlcMessage, UpdateMessage, HasChannelId

data class UpdateFulfillHtlc(
    override val channelId: ByteVector32,
    val id: Long,
    val paymentPreimage: ByteVector32
) : HtlcMessage, UpdateMessage, HasChannelId

data class UpdateFailHtlc(
    override val channelId: ByteVector32,
    val id: Long,
    val reason: ByteVector
) : HtlcMessage, UpdateMessage, HasChannelId

data class UpdateFailMalformedHtlc(
    override val channelId: ByteVector32,
    val id: Long,
    val onionHash: ByteVector32,
    val failureCode: Int
) : HtlcMessage, UpdateMessage, HasChannelId

data class CommitSig(
    override val channelId: ByteVector32,
    val signature: ByteVector64,
    val htlcSignatures: List<ByteVector64>
) : HtlcMessage, HasChannelId

data class RevokeAndAck(
    override val channelId: ByteVector32,
    val perCommitmentSecret: PrivateKey,
    val nextPerCommitmentPoint: PublicKey
) : HtlcMessage, HasChannelId

data class UpdateFee(
    override val channelId: ByteVector32,
    val feeratePerKw: Long
) : ChannelMessage, UpdateMessage, HasChannelId

data class AnnouncementSignatures(
    override val channelId: ByteVector32,
    val shortChannelId: ShortChannelId,
    val nodeSignature: ByteVector64,
    val bitcoinSignature: ByteVector64
) : RoutingMessage, HasChannelId


data class ChannelUpdate(
    val signature: ByteVector64,
    override val chainHash: ByteVector32,
    val shortChannelId: ShortChannelId,
    override val timestamp: Long,
    val messageFlags: Byte,
    val channelFlags: Byte,
    val cltvExpiryDelta: CltvExpiryDelta,
    val htlcMinimumMsat: MilliSatoshi,
    val feeBaseMsat: MilliSatoshi,
    val feeProportionalMillionths: Long,
    val htlcMaximumMsat: MilliSatoshi?,
    val unknownFields: ByteVector = ByteVector.empty
) : AnnouncementMessage, HasTimestamp, HasChainHash {
    init {
        require(((messageFlags.toInt() and 1) != 0) == (htlcMaximumMsat != null)) { "htlcMaximumMsat is not consistent with messageFlags" }
    }

    fun isNode1(): Boolean = (channelFlags.toInt() and 1) == 0
}

