package fr.acinq.eklair.wire

import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.eklair.CltvExpiry
import fr.acinq.eklair.MilliSatoshi

interface HtlcMessage
interface UpdateMessage
interface HasChannelId {
    val channelId: ByteVector32
}

interface ChannelMessage

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

data class UpdateFee(
    override val channelId: ByteVector32,
    val feeratePerKw: Long
) : ChannelMessage, UpdateMessage, HasChannelId


