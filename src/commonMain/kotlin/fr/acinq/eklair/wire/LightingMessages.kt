package fr.acinq.eklair.wire

import fr.acinq.bitcoin.*
import fr.acinq.bitcoin.io.Input
import fr.acinq.bitcoin.io.Output
import fr.acinq.eklair.*
import fr.acinq.eklair.utils.leftPaddedCopyOf
import fr.acinq.eklair.utils.or
import kotlin.math.max


interface LightningMessage
interface HtlcMessage : LightningMessage
interface SetupMessage : LightningMessage
interface RoutingMessage : LightningMessage
interface AnnouncementMessage : RoutingMessage // <- not in the spec
interface HasTimestamp : LightningMessage {
    val timestamp: Long
}

interface UpdateMessage : LightningMessage
interface HasTemporaryChannelId : LightningMessage {
    val temporaryChannelId: ByteVector32
}

interface HasChannelId : LightningMessage {
    val channelId: ByteVector32
}

interface HasChainHash : LightningMessage {
    val chainHash: ByteVector32
} // <- not in the spec

interface ChannelMessage

@kotlin.ExperimentalUnsignedTypes
data class Init(val features: ByteVector, val tlvs: TlvStream<InitTlv> = TlvStream.empty()) : SetupMessage,
    LightningSerializable<Init> {
    val networks = tlvs.get<InitTlv.Companion.Networks>()?.chainHashes ?: listOf()

    override fun serializer(): LightningSerializer<Init> = Init

    companion object : LightningSerializer<Init>() {
        override fun read(input: Input): Init {
            val gflen = u16(input)
            val globalFeatures = bytes(input, gflen)
            val lflen = u16(input)
            val localFeatures = bytes(input, lflen)
            val len = max(gflen, lflen)
            // merge features together
            val features = ByteVector(globalFeatures.leftPaddedCopyOf(len).or(localFeatures.leftPaddedCopyOf(len)))
            val serializers = HashMap<Long, LightningSerializer<InitTlv>>()
            serializers.put(1L, InitTlv.Companion.Networks.Companion as LightningSerializer<InitTlv>)
            val serializer = TlvStreamSerializer<InitTlv>(serializers)
            val tlvs = serializer.read(input)
            return Init(features, tlvs)
        }

        override fun write(message: Init, out: Output) {
            writeU16(0, out)
            writeU16(message.features.size(), out)
            writeBytes(message.features, out)
            val serializers = HashMap<Long, LightningSerializer<InitTlv>>()
            serializers.put(1L, InitTlv.Companion.Networks.Companion as LightningSerializer<InitTlv>)
            val serializer = TlvStreamSerializer<InitTlv>(serializers)
            serializer.write(message.tlvs, out)
        }
    }
}

data class OpenChannel(
    override val chainHash: ByteVector32,
    override val temporaryChannelId: ByteVector32,
    val fundingSatoshis: Satoshi,
    val pushMsat: MilliSatoshi,
    val dustLimitSatoshis: Satoshi,
    val maxHtlcValueInFlightMsat: ULong, // this is not MilliSatoshi because it can exceed the total amount of MilliSatoshi
    val channelReserveSatoshis: Satoshi,
    val htlcMinimumMsat: MilliSatoshi,
    val feeratePerKw: Long,
    val toSelfDelay: CltvExpiryDelta,
    val maxAcceptedHtlcs: Int,
    val fundingPubkey: PublicKey,
    val revocationBasepoint: PublicKey,
    val paymentBasepoint: PublicKey,
    val delayedPaymentBasepoint: PublicKey,
    val htlcBasepoint: PublicKey,
    val firstPerCommitmentPoint: PublicKey,
    val channelFlags: Byte,
    val tlvStream: TlvStream<ChannelTlv> = TlvStream.empty()
) : ChannelMessage, HasTemporaryChannelId, HasChainHash

data class AcceptChannel(
    override val temporaryChannelId: ByteVector32,
    val dustLimitSatoshis: Satoshi,
    val maxHtlcValueInFlightMsat: ULong, // this is not MilliSatoshi because it can exceed the total amount of MilliSatoshi
    val channelReserveSatoshis: Satoshi,
    val htlcMinimumMsat: MilliSatoshi,
    val minimumDepth: Long,
    val toSelfDelay: CltvExpiryDelta,
    val maxAcceptedHtlcs: Int,
    val fundingPubkey: PublicKey,
    val revocationBasepoint: PublicKey,
    val paymentBasepoint: PublicKey,
    val delayedPaymentBasepoint: PublicKey,
    val htlcBasepoint: PublicKey,
    val firstPerCommitmentPoint: PublicKey,
    val tlvStream: TlvStream<ChannelTlv> = TlvStream.empty()
) : ChannelMessage, HasTemporaryChannelId

data class FundingCreated(
    override val temporaryChannelId: ByteVector32,
    val fundingTxid: ByteVector32,
    val fundingOutputIndex: Int,
    val signature: ByteVector64
) : ChannelMessage, HasTemporaryChannelId

data class FundingSigned(
    override val channelId: ByteVector32,
    val signature: ByteVector64,
    val channelData: ByteVector? = null
) : ChannelMessage, HasChannelId

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

data class ChannelAnnouncement(val nodeSignature1: ByteVector64,
                               val nodeSignature2: ByteVector64,
                               val bitcoinSignature1: ByteVector64,
                               val bitcoinSignature2: ByteVector64,
                               val features: Features,
                               override val chainHash: ByteVector32,
                               val shortChannelId: ShortChannelId,
                               val nodeId1: PublicKey,
                               val nodeId2: PublicKey,
                               val bitcoinKey1: PublicKey,
                               val bitcoinKey2: PublicKey,
                               val unknownFields: ByteVector = ByteVector.empty) : RoutingMessage, AnnouncementMessage, HasChainHash

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

data class Shutdown(
    override val channelId: ByteVector32,
    val scriptPubKey: ByteVector,
    val channelData: ByteVector? = null
) : ChannelMessage, HasChannelId

