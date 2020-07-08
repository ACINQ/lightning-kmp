package fr.acinq.eklair.wire

import fr.acinq.bitcoin.*
import fr.acinq.bitcoin.io.Input
import fr.acinq.bitcoin.io.Output
import fr.acinq.eklair.*
import fr.acinq.eklair.utils.leftPaddedCopyOf
import fr.acinq.eklair.utils.or
import fr.acinq.eklair.utils.toByteVector
import fr.acinq.eklair.utils.toByteVector32
import kotlinx.serialization.Serializable
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
data class Init(val features: ByteVector, val tlvs: TlvStream<InitTlv> = TlvStream.empty()) : SetupMessage, LightningSerializable<Init> {
    val networks = tlvs.get<InitTlv.Companion.Networks>()?.chainHashes ?: listOf()

    override fun serializer(): LightningSerializer<Init> = Init

    companion object : LightningSerializer<Init>() {
        override val tag: ULong
            get() = 16UL

        override fun read(input: Input): Init {
            val gflen = u16(input)
            val globalFeatures = bytes(input, gflen)
            val lflen = u16(input)
            val localFeatures = bytes(input, lflen)
            val len = max(gflen, lflen)
            // merge features together
            val features = ByteVector(globalFeatures.leftPaddedCopyOf(len).or(localFeatures.leftPaddedCopyOf(len)))
            val serializers = HashMap<Long, LightningSerializer<InitTlv>>()
            serializers.put(InitTlv.Companion.Networks.tag.toLong(), InitTlv.Companion.Networks.Companion as LightningSerializer<InitTlv>)
            val serializer = TlvStreamSerializer<InitTlv>(serializers)
            val tlvs = serializer.read(input)
            return Init(features, tlvs)
        }

        override fun write(message: Init, out: Output) {
            writeU16(0, out)
            writeU16(message.features.size(), out)
            writeBytes(message.features, out)
            val serializers = HashMap<Long, LightningSerializer<InitTlv>>()
            serializers.put(InitTlv.Companion.Networks.tag.toLong(), InitTlv.Companion.Networks.Companion as LightningSerializer<InitTlv>)
            val serializer = TlvStreamSerializer<InitTlv>(serializers)
            serializer.write(message.tlvs, out)
        }
    }
}

data class Error(override val channelId: ByteVector32, val data: ByteVector) :  SetupMessage, HasChannelId, LightningSerializable<Error> {
    fun toAscii(): String = data.toByteArray().decodeToString()

    override fun serializer(): LightningSerializer<Error> = Error

    companion object : LightningSerializer<Error>() {
        override val tag: ULong
            get() = 17UL

        override fun read(input: Input): Error {
            return Error(bytes(input, 32).toByteVector32(), bytes(input, u16(input)).toByteVector())
        }

        override fun write(message: Error, out: Output) {
            writeBytes(message.channelId, out)
            writeU16(message.data.size(), out)
            writeBytes(message.data, out)
        }
    }
}

data class Ping(val pongLength: Int, val data: ByteVector) : SetupMessage, LightningSerializable<Ping> {
    override fun serializer(): LightningSerializer<Ping> = Ping

    companion object : LightningSerializer<Ping>() {
        override val tag: ULong
            get() = 18UL

        override fun read(input: Input): Ping {
            return Ping(u16(input), bytes(input, u16(input)).toByteVector())
        }

        override fun write(message: Ping, out: Output) {
            writeU16(message.pongLength, out)
            writeU16(message.data.size(), out)
            writeBytes(message.data, out)
        }
    }
}

data class Pong(val data: ByteVector) : SetupMessage, LightningSerializable<Pong> {
    override fun serializer(): LightningSerializer<Pong> = Pong

    companion object : LightningSerializer<Pong>() {
        override val tag: ULong
            get() = 19UL

        override fun read(input: Input): Pong {
            return Pong(bytes(input, u16(input)).toByteVector())
        }

        override fun write(message: Pong, out: Output) {
            writeU16(message.data.size(), out)
            writeBytes(message.data, out)
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
) : ChannelMessage, HasTemporaryChannelId, HasChainHash, LightningSerializable<OpenChannel> {
    override fun serializer(): LightningSerializer<OpenChannel> = OpenChannel

    companion object : LightningSerializer<OpenChannel>() {
        override val tag: ULong
            get() = 32UL

        override fun read(input: Input): OpenChannel {
            val serializers = HashMap<Long, LightningSerializer<ChannelTlv>>()
            serializers.put(ChannelTlv.Companion.UpfrontShutdownScript.tag.toLong(), ChannelTlv.Companion.UpfrontShutdownScript.Companion as LightningSerializer<ChannelTlv>)
            serializers.put(ChannelTlv.Companion.ChannelVersionTlv.tag.toLong(), ChannelTlv.Companion.ChannelVersionTlv.Companion as LightningSerializer<ChannelTlv>)

            return OpenChannel(
                ByteVector32(bytes(input, 32)),
                ByteVector32(bytes(input, 32)),
                Satoshi(u64(input)),
                MilliSatoshi(u64(input)),
                Satoshi(u64(input)),
                u64(input).toULong(), // this is not MilliSatoshi because it can exceed the total amount of MilliSatoshi
                Satoshi(u64(input)),
                MilliSatoshi(u64(input)),
                u32(input).toLong(),
                CltvExpiryDelta(u16(input)),
                u16(input),
                PublicKey(bytes(input, 33)),
                PublicKey(bytes(input, 33)),
                PublicKey(bytes(input, 33)),
                PublicKey(bytes(input, 33)),
                PublicKey(bytes(input, 33)),
                PublicKey(bytes(input, 33)),
                byte(input).toByte(),
                TlvStreamSerializer<ChannelTlv>(serializers).read(input)
            )
        }

        override fun write(message: OpenChannel, out: Output) {
            val serializers = HashMap<Long, LightningSerializer<ChannelTlv>>()
            serializers.put(ChannelTlv.Companion.UpfrontShutdownScript.tag.toLong(), ChannelTlv.Companion.UpfrontShutdownScript.Companion as LightningSerializer<ChannelTlv>)
            serializers.put(ChannelTlv.Companion.ChannelVersionTlv.tag.toLong(), ChannelTlv.Companion.ChannelVersionTlv.Companion as LightningSerializer<ChannelTlv>)

            writeBytes(message.chainHash, out)
            writeBytes(message.temporaryChannelId, out)
            writeU64(message.fundingSatoshis.toLong(), out)
            writeU64(message.pushMsat.toLong(), out)
            writeU64(message.dustLimitSatoshis.toLong(), out)
            writeU64(message.maxHtlcValueInFlightMsat.toLong(), out)
            writeU64(message.channelReserveSatoshis.toLong(), out)
            writeU64(message.htlcMinimumMsat.toLong(), out)
            writeU32(message.feeratePerKw.toInt(), out)
            writeU16(message.toSelfDelay.toInt(), out)
            writeU16(message.maxAcceptedHtlcs, out)
            writeBytes(message.fundingPubkey.value, out)
            writeBytes(message.revocationBasepoint.value, out)
            writeBytes(message.paymentBasepoint.value, out)
            writeBytes(message.delayedPaymentBasepoint.value, out)
            writeBytes(message.htlcBasepoint.value, out)
            writeBytes(message.firstPerCommitmentPoint.value, out)
            writeByte(message.channelFlags.toInt(), out)
            TlvStreamSerializer<ChannelTlv>(serializers).write(message.tlvStream, out)
        }
    }
}

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
) : ChannelMessage, HasTemporaryChannelId, LightningSerializable<AcceptChannel> {
    override fun serializer(): LightningSerializer<AcceptChannel> = AcceptChannel

    companion object : LightningSerializer<AcceptChannel>() {
        override val tag: ULong
            get() = 33UL

        override fun read(input: Input): AcceptChannel {
            val serializers = HashMap<Long, LightningSerializer<ChannelTlv>>()
            serializers.put(ChannelTlv.Companion.UpfrontShutdownScript.tag.toLong(), ChannelTlv.Companion.UpfrontShutdownScript.Companion as LightningSerializer<ChannelTlv>)
            serializers.put(ChannelTlv.Companion.ChannelVersionTlv.tag.toLong(), ChannelTlv.Companion.ChannelVersionTlv.Companion as LightningSerializer<ChannelTlv>)

            return AcceptChannel(
                ByteVector32(bytes(input, 32)),
                Satoshi(u64(input)),
                u64(input).toULong(), // this is not MilliSatoshi because it can exceed the total amount of MilliSatoshi,
                Satoshi(u64(input)),
                MilliSatoshi(u64(input)),
                u32(input).toLong(),
                CltvExpiryDelta(u16(input)),
                u16(input),
                PublicKey(bytes(input, 33)),
                PublicKey(bytes(input, 33)),
                PublicKey(bytes(input, 33)),
                PublicKey(bytes(input, 33)),
                PublicKey(bytes(input, 33)),
                PublicKey(bytes(input, 33)),
                TlvStreamSerializer<ChannelTlv>(serializers).read(input)
            )
        }

        override fun write(message: AcceptChannel, out: Output) {
            val serializers = HashMap<Long, LightningSerializer<ChannelTlv>>()
            serializers.put(ChannelTlv.Companion.UpfrontShutdownScript.tag.toLong(), ChannelTlv.Companion.UpfrontShutdownScript.Companion as LightningSerializer<ChannelTlv>)

            writeBytes(message.temporaryChannelId, out)
            writeU64(message.dustLimitSatoshis.toLong(), out)
            writeU64(message.maxHtlcValueInFlightMsat.toLong(), out)
            writeU64(message.channelReserveSatoshis.toLong(), out)
            writeU64(message.htlcMinimumMsat.toLong(), out)
            writeU32(message.minimumDepth.toInt(), out)
            writeU16(message.toSelfDelay.toInt(), out)
            writeU16(message.maxAcceptedHtlcs, out)
            writeBytes(message.fundingPubkey.value, out)
            writeBytes(message.revocationBasepoint.value, out)
            writeBytes(message.paymentBasepoint.value, out)
            writeBytes(message.delayedPaymentBasepoint.value, out)
            writeBytes(message.htlcBasepoint.value, out)
            writeBytes(message.firstPerCommitmentPoint.value, out)
            TlvStreamSerializer<ChannelTlv>(serializers).write(message.tlvStream, out)
        }
    }
}

data class FundingCreated(
    override val temporaryChannelId: ByteVector32,
    val fundingTxid: ByteVector32,
    val fundingOutputIndex: Int,
    val signature: ByteVector64
) : ChannelMessage, HasTemporaryChannelId, LightningSerializable<FundingCreated> {
    override fun serializer(): LightningSerializer<FundingCreated> = FundingCreated

    companion object : LightningSerializer<FundingCreated>() {
        override val tag: ULong
            get() = 34UL

        override fun read(input: Input): FundingCreated {
            return FundingCreated(
                ByteVector32(bytes(input, 32)),
                ByteVector32(bytes(input, 32)),
                u16(input),
                ByteVector64(bytes(input, 64))
            )
        }

        override fun write(message: FundingCreated, out: Output) {
            writeBytes(message.temporaryChannelId, out)
            writeBytes(message.fundingTxid, out)
            writeU16(message.fundingOutputIndex, out)
            writeBytes(message.signature, out)
        }
    }
}

data class FundingSigned(
    override val channelId: ByteVector32,
    val signature: ByteVector64,
    val channelData: ByteVector? = null
) : ChannelMessage, HasChannelId, LightningSerializable<FundingSigned> {
    override fun serializer(): LightningSerializer<FundingSigned> = FundingSigned

    companion object : LightningSerializer<FundingSigned>() {
        override val tag: ULong
            get() = 35UL

        override fun read(input: Input): FundingSigned {
            return FundingSigned(
                ByteVector32(bytes(input, 32)),
                ByteVector64(bytes(input, 64))
            )
        }

        override fun write(message: FundingSigned, out: Output) {
            writeBytes(message.channelId, out)
            writeBytes(message.signature, out)
        }
    }
}

data class FundingLocked(
    override val channelId: ByteVector32,
    val nextPerCommitmentPoint: PublicKey
) : ChannelMessage, HasChannelId, LightningSerializable<FundingLocked> {
    override fun serializer(): LightningSerializer<FundingLocked> = FundingLocked

    companion object : LightningSerializer<FundingLocked>() {
        override val tag: ULong
            get() = 36UL

        override fun read(input: Input): FundingLocked {
            return FundingLocked(
                ByteVector32(bytes(input, 32)),
                PublicKey(bytes(input, 33))
            )
        }

        override fun write(message: FundingLocked, out: Output) {
            writeBytes(message.channelId, out)
            writeBytes(message.nextPerCommitmentPoint.value, out)
        }
    }
}

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
) : HtlcMessage, UpdateMessage, HasChannelId, LightningSerializable<FundingLocked> {
    override fun serializer(): LightningSerializer<FundingLocked> = FundingLocked

    companion object : LightningSerializer<FundingLocked>() {
        override val tag: ULong
            get() = 130UL

        override fun read(input: Input): FundingLocked {
            return FundingLocked(
                ByteVector32(bytes(input, 32)),
                PublicKey(bytes(input, 33))
            )
        }

        override fun write(message: FundingLocked, out: Output) {
            writeBytes(message.channelId, out)
            writeBytes(message.nextPerCommitmentPoint.value, out)
        }
    }
}

data class UpdateFailHtlc(
    override val channelId: ByteVector32,
    val id: Long,
    val reason: ByteVector
) : HtlcMessage, UpdateMessage, HasChannelId, LightningSerializable<UpdateFailHtlc> {
    override fun serializer(): LightningSerializer<UpdateFailHtlc> = UpdateFailHtlc

    companion object : LightningSerializer<UpdateFailHtlc>() {
        override val tag: ULong
            get() = 131UL

        override fun read(input: Input): UpdateFailHtlc {
            return UpdateFailHtlc(
                ByteVector32(bytes(input, 32)),
                u64(input),
                ByteVector(bytes(input, u16(input)))
            )
        }

        override fun write(message: UpdateFailHtlc, out: Output) {
            writeBytes(message.channelId, out)
            writeU64(message.id, out)
            writeU16(message.reason.size(), out)
            writeBytes(message.reason, out)
        }
    }
}

data class UpdateFailMalformedHtlc(
    override val channelId: ByteVector32,
    val id: Long,
    val onionHash: ByteVector32,
    val failureCode: Int
) : HtlcMessage, UpdateMessage, HasChannelId, LightningSerializable<UpdateFailMalformedHtlc> {
    override fun serializer(): LightningSerializer<UpdateFailMalformedHtlc> = UpdateFailMalformedHtlc

    companion object : LightningSerializer<UpdateFailMalformedHtlc>() {
        override val tag: ULong
            get() = 135UL

        override fun read(input: Input): UpdateFailMalformedHtlc {
            return UpdateFailMalformedHtlc(
                ByteVector32(bytes(input, 32)),
                u64(input),
                ByteVector32(bytes(input, 32)),
                u16(input)
            )
        }

        override fun write(message: UpdateFailMalformedHtlc, out: Output) {
            writeBytes(message.channelId, out)
            writeU64(message.id, out)
            writeBytes(message.onionHash, out)
            writeU16(message.failureCode, out)
        }
    }
}

data class CommitSig(
    override val channelId: ByteVector32,
    val signature: ByteVector64,
    val htlcSignatures: List<ByteVector64>
) : HtlcMessage, HasChannelId, LightningSerializable<CommitSig> {
    override fun serializer(): LightningSerializer<CommitSig> = CommitSig

    companion object : LightningSerializer<CommitSig>() {
        override val tag: ULong
            get() = 132UL

        override fun read(input: Input): CommitSig {
            val channelId = ByteVector32(bytes(input, 32))
            val sig = ByteVector64(bytes(input, 64))
            val numHtlcs = u16(input)
            val htlcSigs = ArrayList<ByteVector64>(numHtlcs)
            for (i in 1..numHtlcs) {
                htlcSigs += ByteVector64(bytes(input, 64))
            }
            return CommitSig(channelId, sig, htlcSigs.toList())
        }

        override fun write(message: CommitSig, out: Output) {
            writeBytes(message.channelId, out)
            writeBytes(message.signature, out)
            writeU16(message.htlcSignatures.size, out)
            message.htlcSignatures.forEach { writeBytes(it, out) }
        }
    }
}

data class RevokeAndAck(
    override val channelId: ByteVector32,
    val perCommitmentSecret: PrivateKey,
    val nextPerCommitmentPoint: PublicKey
) : HtlcMessage, HasChannelId, LightningSerializable<RevokeAndAck> {
    override fun serializer(): LightningSerializer<RevokeAndAck> = RevokeAndAck

    companion object : LightningSerializer<RevokeAndAck>() {
        override val tag: ULong
            get() = 133UL

        override fun read(input: Input): RevokeAndAck {
            return RevokeAndAck(
                ByteVector32(bytes(input, 32)),
                PrivateKey(bytes(input, 32)),
                PublicKey(bytes(input, 33))
            )
        }

        override fun write(message: RevokeAndAck, out: Output) {
            writeBytes(message.channelId, out)
            writeBytes(message.perCommitmentSecret.value, out)
            writeBytes(message.nextPerCommitmentPoint.value, out)
        }
    }
}

data class UpdateFee(
    override val channelId: ByteVector32,
    val feeratePerKw: Long
) : ChannelMessage, UpdateMessage, HasChannelId, LightningSerializable<UpdateFee> {
    override fun serializer(): LightningSerializer<UpdateFee> = UpdateFee

    companion object : LightningSerializer<UpdateFee>() {
        override val tag: ULong
            get() = 134UL

        override fun read(input: Input): UpdateFee {
            return UpdateFee(
                ByteVector32(bytes(input, 32)),
                u32(input).toLong()
            )
        }

        override fun write(message: UpdateFee, out: Output) {
            writeBytes(message.channelId, out)
            writeU32(message.feeratePerKw.toInt(), out)
        }
    }
}

data class ChannelReestablish(
    override val channelId: ByteVector32,
    val nextCommitmentNumber: Long,
    val nextRevocationNumber: Long,
    val yourLastCommitmentSecret: PrivateKey,
    val myCurrentPerCommitmentPoint: PublicKey
) : HasChannelId, LightningSerializable<ChannelReestablish> {
    override fun serializer(): LightningSerializer<ChannelReestablish> = ChannelReestablish

    companion object : LightningSerializer<ChannelReestablish>() {
        override val tag: ULong
            get() = 136UL

        override fun read(input: Input): ChannelReestablish {
            return ChannelReestablish(
                ByteVector32(bytes(input, 32)),
                u64(input),
                u64(input),
                PrivateKey(bytes(input, 32)),
                PublicKey(bytes(input, 33))
            )
        }

        override fun write(message: ChannelReestablish, out: Output) {
            writeBytes(message.channelId, out)
            writeU64(message.nextCommitmentNumber, out)
            writeU64(message.nextRevocationNumber, out)
            writeBytes(message.yourLastCommitmentSecret.value, out)
            writeBytes(message.myCurrentPerCommitmentPoint.value, out)
        }
    }
}

data class AnnouncementSignatures(
    override val channelId: ByteVector32,
    val shortChannelId: ShortChannelId,
    val nodeSignature: ByteVector64,
    val bitcoinSignature: ByteVector64
) : RoutingMessage, HasChannelId, LightningSerializable<AnnouncementSignatures> {
    override fun serializer(): LightningSerializer<AnnouncementSignatures> = AnnouncementSignatures

    companion object : LightningSerializer<AnnouncementSignatures>() {
        override val tag: ULong
            get() = 259UL

        override fun read(input: Input): AnnouncementSignatures {
            return AnnouncementSignatures(
                ByteVector32(bytes(input, 32)),
                ShortChannelId(u64(input)),
                ByteVector64(bytes(input, 64)),
                ByteVector64(bytes(input, 64))
            )
        }

        override fun write(message: AnnouncementSignatures, out: Output) {
            writeBytes(message.channelId, out)
            writeU64(message.shortChannelId.toLong(), out)
            writeBytes(message.nodeSignature, out)
            writeBytes(message.bitcoinSignature, out)
        }
    }
}

data class ChannelAnnouncement(
    val nodeSignature1: ByteVector64,
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
    val unknownFields: ByteVector = ByteVector.empty
) : RoutingMessage, AnnouncementMessage, HasChainHash, LightningSerializable<ChannelAnnouncement> {
    override fun serializer(): LightningSerializer<ChannelAnnouncement> = ChannelAnnouncement

    companion object : LightningSerializer<ChannelAnnouncement>() {
        override val tag: ULong
            get() = 256UL

        override fun read(input: Input): ChannelAnnouncement {
            TODO()
        }

        override fun write(message: ChannelAnnouncement, out: Output) {
            TODO()
        }
    }
}

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
) : AnnouncementMessage, HasTimestamp, HasChainHash, LightningSerializable<ChannelUpdate> {
    init {
        require(((messageFlags.toInt() and 1) != 0) == (htlcMaximumMsat != null)) { "htlcMaximumMsat is not consistent with messageFlags" }
    }

    override fun serializer(): LightningSerializer<ChannelUpdate> = ChannelUpdate

    companion object : LightningSerializer<ChannelUpdate>() {
        override val tag: ULong
            get() = 258UL

        override fun read(input: Input): ChannelUpdate {
            TODO()
        }

        override fun write(message: ChannelUpdate, out: Output) {
            TODO()
        }
    }

    fun isNode1(): Boolean = (channelFlags.toInt() and 1) == 0
}

data class Shutdown(
    override val channelId: ByteVector32,
    val scriptPubKey: ByteVector,
    val channelData: ByteVector? = null
) : ChannelMessage, HasChannelId, LightningSerializable<FundingLocked> {
    override fun serializer(): LightningSerializer<FundingLocked> = FundingLocked

    companion object : LightningSerializer<FundingLocked>() {
        override val tag: ULong
            get() = 36UL

        override fun read(input: Input): FundingLocked {
            TODO()
        }

        override fun write(message: FundingLocked, out: Output) {
            TODO()
        }
    }
}

