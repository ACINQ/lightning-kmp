package fr.acinq.eklair.wire

import fr.acinq.bitcoin.*
import fr.acinq.bitcoin.io.ByteArrayInput
import fr.acinq.bitcoin.io.ByteArrayOutput
import fr.acinq.bitcoin.io.Input
import fr.acinq.bitcoin.io.Output
import fr.acinq.eklair.*
import fr.acinq.eklair.io.*
import fr.acinq.eklair.utils.leftPaddedCopyOf
import fr.acinq.eklair.utils.or
import fr.acinq.eklair.utils.toByteVector
import fr.acinq.eklair.utils.toByteVector32
import fr.acinq.secp256k1.Hex
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass
import org.kodein.log.Logger
import org.kodein.log.LoggerFactory
import kotlin.math.max


interface LightningMessage {
    companion object {
        val logger = LoggerFactory.default.newLogger(Logger.Tag(LightningMessage::class))

        fun decode(input: ByteArray): LightningMessage? {
            val stream = ByteArrayInput(input)
            val code = LightningSerializer.u16(stream)
            return when (code.toLong()) {
                Init.tag -> Init.read(stream)
                ChannelReestablish.tag -> ChannelReestablish.read(stream)
                Error.tag -> Error.read(stream)
                Ping.tag -> Ping.read(stream)
                Pong.tag -> Pong.read(stream)
                OpenChannel.tag -> OpenChannel.read(stream)
                AcceptChannel.tag -> AcceptChannel.read(stream)
                FundingCreated.tag -> FundingCreated.read(stream)
                FundingSigned.tag -> FundingSigned.read(stream)
                FundingLocked.tag -> FundingLocked.read(stream)
                CommitSig.tag -> CommitSig.read(stream)
                RevokeAndAck.tag -> RevokeAndAck.read(stream)
                UpdateAddHtlc.tag -> UpdateAddHtlc.read(stream)
                UpdateFailHtlc.tag -> UpdateFailHtlc.read(stream)
                UpdateFailMalformedHtlc.tag -> UpdateFailMalformedHtlc.read(stream)
                UpdateFulfillHtlc.tag -> UpdateFulfillHtlc.read(stream)
                else -> {
                    logger.warning { "cannot decode ${Hex.encode(input)}" }
                    null
                }
            }
        }

        fun encode(input: LightningMessage, out: Output) {
            when (input) {
                is LightningSerializable<*> -> {
                    LightningSerializer.writeU16(input.tag.toInt(), out)
                    @Suppress("UNCHECKED_CAST")
                    (LightningSerializer.writeBytes((input.serializer() as LightningSerializer<LightningSerializable<*>>).write(input), out))
                }
                else -> {
                    logger.warning { "cannot encode $input" }
                    Unit
                }
            }
        }

        fun encode(input: LightningMessage): ByteArray? {
            val out = ByteArrayOutput()
            encode(input, out)
            return out.toByteArray()
        }
    }
}
interface HtlcMessage : LightningMessage
interface SetupMessage : LightningMessage
interface RoutingMessage : LightningMessage
interface AnnouncementMessage : RoutingMessage // <- not in the spec
interface HasTimestamp : LightningMessage {
    val timestamp: Long
}

interface UpdateMessage : LightningMessage {
    companion object {
        val serializersModule = SerializersModule {
            polymorphic(UpdateMessage::class) {
                subclass(UpdateAddHtlc.serializer())
                subclass(UpdateFailHtlc.serializer())
                subclass(UpdateFailMalformedHtlc.serializer())
                subclass(UpdateFee.serializer())
                subclass(UpdateFulfillHtlc.serializer())
            }
        }
    }
}

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

@OptIn(ExperimentalUnsignedTypes::class)
@Serializable
data class Init(@Serializable(with = ByteVectorKSerializer::class) val features: ByteVector, val tlvs: TlvStream<InitTlv> = TlvStream.empty()) : SetupMessage, LightningSerializable<Init> {
    @Transient
    val networks = tlvs.get<InitTlv.Networks>()?.chainHashes ?: listOf()

    override fun serializer(): LightningSerializer<Init> = Init

    companion object : LightningSerializer<Init>() {
        override val tag: Long
            get() = 16L

        override fun read(input: Input): Init {
            val gflen = u16(input)
            val globalFeatures = bytes(input, gflen)
            val lflen = u16(input)
            val localFeatures = bytes(input, lflen)
            val len = max(gflen, lflen)
            // merge features together
            val features = ByteVector(globalFeatures.leftPaddedCopyOf(len).or(localFeatures.leftPaddedCopyOf(len)))
            val serializers = HashMap<Long, LightningSerializer<InitTlv>>()
            @Suppress("UNCHECKED_CAST")
            serializers.put(InitTlv.Networks.tag, InitTlv.Networks.Companion as LightningSerializer<InitTlv>)
            val serializer = TlvStreamSerializer<InitTlv>(serializers)
            val tlvs = serializer.read(input)
            return Init(features, tlvs)
        }

        override fun write(message: Init, out: Output) {
            writeU16(0, out)
            writeU16(message.features.size(), out)
            writeBytes(message.features, out)
            val serializers = HashMap<Long, LightningSerializer<InitTlv>>()
            @Suppress("UNCHECKED_CAST")
            serializers.put(InitTlv.Networks.tag.toLong(), InitTlv.Networks.Companion as LightningSerializer<InitTlv>)
            val serializer = TlvStreamSerializer<InitTlv>(serializers)
            serializer.write(message.tlvs, out)
        }
    }
}

@OptIn(ExperimentalUnsignedTypes::class)
data class Error(override val channelId: ByteVector32, val data: ByteVector) :  SetupMessage, HasChannelId, LightningSerializable<Error> {
    fun toAscii(): String = data.toByteArray().decodeToString()

    override fun serializer(): LightningSerializer<Error> = Error

    companion object : LightningSerializer<Error>() {
        override val tag: Long
            get() = 17L

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

@OptIn(ExperimentalUnsignedTypes::class)
data class Ping(val pongLength: Int, val data: ByteVector) : SetupMessage, LightningSerializable<Ping> {
    override fun serializer(): LightningSerializer<Ping> = Ping

    companion object : LightningSerializer<Ping>() {
        override val tag: Long
            get() = 18L

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

@OptIn(ExperimentalUnsignedTypes::class)
data class Pong(val data: ByteVector) : SetupMessage, LightningSerializable<Pong> {
    override fun serializer(): LightningSerializer<Pong> = Pong

    companion object : LightningSerializer<Pong>() {
        override val tag: Long
            get() = 19L

        override fun read(input: Input): Pong {
            return Pong(bytes(input, u16(input)).toByteVector())
        }

        override fun write(message: Pong, out: Output) {
            writeU16(message.data.size(), out)
            writeBytes(message.data, out)
        }
    }
}

@OptIn(ExperimentalUnsignedTypes::class)
@Serializable
data class OpenChannel(
    @Serializable(with = ByteVector32KSerializer::class) override val chainHash: ByteVector32,
    @Serializable(with = ByteVector32KSerializer::class) override val temporaryChannelId: ByteVector32,
    @Serializable(with = SatoshiKSerializer::class) val fundingSatoshis: Satoshi,
    val pushMsat: MilliSatoshi,
    @Serializable(with = SatoshiKSerializer::class) val dustLimitSatoshis: Satoshi,
    val maxHtlcValueInFlightMsat: Long, // this is not MilliSatoshi because it can exceed the total amount of MilliSatoshi
    @Serializable(with = SatoshiKSerializer::class) val channelReserveSatoshis: Satoshi,
    val htlcMinimumMsat: MilliSatoshi,
    val feeratePerKw: Long,
    val toSelfDelay: CltvExpiryDelta,
    val maxAcceptedHtlcs: Int,
    @Serializable(with = PublicKeyKSerializer::class) val fundingPubkey: PublicKey,
    @Serializable(with = PublicKeyKSerializer::class) val revocationBasepoint: PublicKey,
    @Serializable(with = PublicKeyKSerializer::class) val paymentBasepoint: PublicKey,
    @Serializable(with = PublicKeyKSerializer::class) val delayedPaymentBasepoint: PublicKey,
    @Serializable(with = PublicKeyKSerializer::class) val htlcBasepoint: PublicKey,
    @Serializable(with = PublicKeyKSerializer::class) val firstPerCommitmentPoint: PublicKey,
    val channelFlags: Byte,
    val tlvStream: TlvStream<ChannelTlv> = TlvStream.empty()
) : ChannelMessage, HasTemporaryChannelId, HasChainHash, LightningSerializable<OpenChannel> {
    override fun serializer(): LightningSerializer<OpenChannel> = OpenChannel

    companion object : LightningSerializer<OpenChannel>() {
        override val tag: Long
            get() = 32L

        override fun read(input: Input): OpenChannel {
            val serializers = HashMap<Long, LightningSerializer<ChannelTlv>>()
            @Suppress("UNCHECKED_CAST")
            serializers.put(ChannelTlv.UpfrontShutdownScript.tag, ChannelTlv.UpfrontShutdownScript.Companion as LightningSerializer<ChannelTlv>)
            @Suppress("UNCHECKED_CAST")
            serializers.put(ChannelTlv.ChannelVersionTlv.tag, ChannelTlv.ChannelVersionTlv.Companion as LightningSerializer<ChannelTlv>)

            return OpenChannel(
                ByteVector32(bytes(input, 32)),
                ByteVector32(bytes(input, 32)),
                Satoshi(u64(input)),
                MilliSatoshi(u64(input)),
                Satoshi(u64(input)),
                u64(input), // this is not MilliSatoshi because it can exceed the total amount of MilliSatoshi
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
            @Suppress("UNCHECKED_CAST")
            serializers.put(ChannelTlv.UpfrontShutdownScript.tag, ChannelTlv.UpfrontShutdownScript.Companion as LightningSerializer<ChannelTlv>)
            @Suppress("UNCHECKED_CAST")
            serializers.put(ChannelTlv.ChannelVersionTlv.tag, ChannelTlv.ChannelVersionTlv.Companion as LightningSerializer<ChannelTlv>)

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

@OptIn(ExperimentalUnsignedTypes::class)
@Serializable
data class AcceptChannel(
    @Serializable(with = ByteVector32KSerializer::class) override val temporaryChannelId: ByteVector32,
    @Serializable(with = SatoshiKSerializer::class) val dustLimitSatoshis: Satoshi,
    val maxHtlcValueInFlightMsat: Long, // this is not MilliSatoshi because it can exceed the total amount of MilliSatoshi
    @Serializable(with = SatoshiKSerializer::class) val channelReserveSatoshis: Satoshi,
    val htlcMinimumMsat: MilliSatoshi,
    val minimumDepth: Long,
    val toSelfDelay: CltvExpiryDelta,
    val maxAcceptedHtlcs: Int,
    @Serializable(with = PublicKeyKSerializer::class) val fundingPubkey: PublicKey,
    @Serializable(with = PublicKeyKSerializer::class) val revocationBasepoint: PublicKey,
    @Serializable(with = PublicKeyKSerializer::class) val paymentBasepoint: PublicKey,
    @Serializable(with = PublicKeyKSerializer::class) val delayedPaymentBasepoint: PublicKey,
    @Serializable(with = PublicKeyKSerializer::class) val htlcBasepoint: PublicKey,
    @Serializable(with = PublicKeyKSerializer::class) val firstPerCommitmentPoint: PublicKey,
    val tlvStream: TlvStream<ChannelTlv> = TlvStream.empty()
) : ChannelMessage, HasTemporaryChannelId, LightningSerializable<AcceptChannel> {
    override fun serializer(): LightningSerializer<AcceptChannel> = AcceptChannel

    companion object : LightningSerializer<AcceptChannel>() {
        override val tag: Long
            get() = 33L

        override fun read(input: Input): AcceptChannel {
            val serializers = HashMap<Long, LightningSerializer<ChannelTlv>>()
            @Suppress("UNCHECKED_CAST")
            serializers.put(ChannelTlv.UpfrontShutdownScript.tag, ChannelTlv.UpfrontShutdownScript.Companion as LightningSerializer<ChannelTlv>)
            @Suppress("UNCHECKED_CAST")
            serializers.put(ChannelTlv.ChannelVersionTlv.tag, ChannelTlv.ChannelVersionTlv.Companion as LightningSerializer<ChannelTlv>)

            return AcceptChannel(
                ByteVector32(bytes(input, 32)),
                Satoshi(u64(input)),
                u64(input), // this is not MilliSatoshi because it can exceed the total amount of MilliSatoshi,
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
            @Suppress("UNCHECKED_CAST")
            serializers.put(ChannelTlv.UpfrontShutdownScript.tag, ChannelTlv.UpfrontShutdownScript.Companion as LightningSerializer<ChannelTlv>)

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

@OptIn(ExperimentalUnsignedTypes::class)
@Serializable
data class FundingCreated(
    @Serializable(with = ByteVector32KSerializer::class) override val temporaryChannelId: ByteVector32,
    @Serializable(with = ByteVector32KSerializer::class) val fundingTxid: ByteVector32,
    val fundingOutputIndex: Int,
    @Serializable(with = ByteVector64KSerializer::class) val signature: ByteVector64
) : ChannelMessage, HasTemporaryChannelId, LightningSerializable<FundingCreated> {
    override fun serializer(): LightningSerializer<FundingCreated> = FundingCreated

    companion object : LightningSerializer<FundingCreated>() {
        override val tag: Long
            get() = 34L

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

@OptIn(ExperimentalUnsignedTypes::class)
@Serializable
data class FundingSigned(
    @Serializable(with = ByteVector32KSerializer::class) override val channelId: ByteVector32,
    @Serializable(with = ByteVector64KSerializer::class) val signature: ByteVector64,
    @Serializable(with = ByteVectorKSerializer::class) val channelData: ByteVector? = null
) : ChannelMessage, HasChannelId, LightningSerializable<FundingSigned> {
    override fun serializer(): LightningSerializer<FundingSigned> = FundingSigned

    companion object : LightningSerializer<FundingSigned>() {
        override val tag: Long
            get() = 35L

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

@OptIn(ExperimentalUnsignedTypes::class)
@Serializable
data class FundingLocked(
    @Serializable(with = ByteVector32KSerializer::class) override val channelId: ByteVector32,
    @Serializable(with = PublicKeyKSerializer::class) val nextPerCommitmentPoint: PublicKey
) : ChannelMessage, HasChannelId, LightningSerializable<FundingLocked> {
    override fun serializer(): LightningSerializer<FundingLocked> = FundingLocked

    companion object : LightningSerializer<FundingLocked>() {
        override val tag: Long
            get() = 36L

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

@OptIn(ExperimentalUnsignedTypes::class)
@Serializable
data class UpdateAddHtlc(
    @Serializable(with = ByteVector32KSerializer::class) override val channelId: ByteVector32,
    val id: Long,
    val amountMsat: MilliSatoshi,
    @Serializable(with = ByteVector32KSerializer::class) val paymentHash: ByteVector32,
    val cltvExpiry: CltvExpiry,
    val onionRoutingPacket: OnionRoutingPacket
) : HtlcMessage, UpdateMessage, HasChannelId, LightningSerializable<UpdateAddHtlc> {

    override fun serializer(): LightningSerializer<UpdateAddHtlc> = UpdateAddHtlc

    companion object : LightningSerializer<UpdateAddHtlc>() {
        override val tag: Long
            get() = 128L

        override fun read(input: Input): UpdateAddHtlc {
            val channelId = ByteVector32(bytes(input, 32))
            val id = u64(input)
            val amount = MilliSatoshi(u64(input))
            val paymentHash = ByteVector32(bytes(input, 32))
            val expiry = CltvExpiry(u32(input).toLong())
            val onion = OnionRoutingPacketSerializer(1300).read(input)
            return UpdateAddHtlc(channelId, id, amount, paymentHash, expiry, onion)
        }

        override fun write(message: UpdateAddHtlc, out: Output) {
            writeBytes(message.channelId, out)
            writeU64(message.id, out)
            writeU64(message.amountMsat.toLong(), out)
            writeBytes(message.paymentHash, out)
            writeU32(message.cltvExpiry.toLong().toInt(), out)
            OnionRoutingPacketSerializer(1300).write(message.onionRoutingPacket, out)
        }
    }
}

@OptIn(ExperimentalUnsignedTypes::class)
@Serializable
data class UpdateFulfillHtlc(
    @Serializable(with = ByteVector32KSerializer::class) override val channelId: ByteVector32,
    val id: Long,
    @Serializable(with = ByteVector32KSerializer::class) val paymentPreimage: ByteVector32
) : HtlcMessage, UpdateMessage, HasChannelId, LightningSerializable<UpdateFulfillHtlc> {
    override fun serializer(): LightningSerializer<UpdateFulfillHtlc> = UpdateFulfillHtlc

    companion object : LightningSerializer<UpdateFulfillHtlc>() {
        override val tag: Long
            get() = 130L

        override fun read(input: Input): UpdateFulfillHtlc {
            return UpdateFulfillHtlc(
                ByteVector32(bytes(input, 32)),
                u64(input),
                ByteVector32(bytes(input, 32))
            )
        }

        override fun write(message: UpdateFulfillHtlc, out: Output) {
            writeBytes(message.channelId, out)
            writeU64(message.id, out)
            writeBytes(message.paymentPreimage, out)
        }
    }
}

@OptIn(ExperimentalUnsignedTypes::class)
@Serializable
data class UpdateFailHtlc(
    @Serializable(with = ByteVector32KSerializer::class) override val channelId: ByteVector32,
    val id: Long,
    @Serializable(with = ByteVectorKSerializer::class) val reason: ByteVector
) : HtlcMessage, UpdateMessage, HasChannelId, LightningSerializable<UpdateFailHtlc> {
    override fun serializer(): LightningSerializer<UpdateFailHtlc> = UpdateFailHtlc

    companion object : LightningSerializer<UpdateFailHtlc>() {
        override val tag: Long
            get() = 131L

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

@OptIn(ExperimentalUnsignedTypes::class)
@Serializable
data class UpdateFailMalformedHtlc(
    @Serializable(with = ByteVector32KSerializer::class) override val channelId: ByteVector32,
    val id: Long,
    @Serializable(with = ByteVector32KSerializer::class) val onionHash: ByteVector32,
    val failureCode: Int
) : HtlcMessage, UpdateMessage, HasChannelId, LightningSerializable<UpdateFailMalformedHtlc> {
    override fun serializer(): LightningSerializer<UpdateFailMalformedHtlc> = UpdateFailMalformedHtlc

    companion object : LightningSerializer<UpdateFailMalformedHtlc>() {
        override val tag: Long
            get() = 135L

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

@OptIn(ExperimentalUnsignedTypes::class)
@Serializable
data class CommitSig(
    @Serializable(with = ByteVector32KSerializer::class) override val channelId: ByteVector32,
    @Serializable(with = ByteVector64KSerializer::class) val signature: ByteVector64,
    val htlcSignatures: List<@Serializable(with = ByteVector64KSerializer::class) ByteVector64>
) : HtlcMessage, HasChannelId, LightningSerializable<CommitSig> {
    override fun serializer(): LightningSerializer<CommitSig> = CommitSig

    companion object : LightningSerializer<CommitSig>() {
        override val tag: Long
            get() = 132L

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

@OptIn(ExperimentalUnsignedTypes::class)
data class RevokeAndAck(
    override val channelId: ByteVector32,
    val perCommitmentSecret: PrivateKey,
    val nextPerCommitmentPoint: PublicKey
) : HtlcMessage, HasChannelId, LightningSerializable<RevokeAndAck> {
    override fun serializer(): LightningSerializer<RevokeAndAck> = RevokeAndAck

    companion object : LightningSerializer<RevokeAndAck>() {
        override val tag: Long
            get() = 133L

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

@OptIn(ExperimentalUnsignedTypes::class)
@Serializable
data class UpdateFee(
    @Serializable(with = ByteVector32KSerializer::class) override val channelId: ByteVector32,
    val feeratePerKw: Long
) : ChannelMessage, UpdateMessage, HasChannelId, LightningSerializable<UpdateFee> {
    override fun serializer(): LightningSerializer<UpdateFee> = UpdateFee

    companion object : LightningSerializer<UpdateFee>() {
        override val tag: Long
            get() = 134L

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

@OptIn(ExperimentalUnsignedTypes::class)
@Serializable
data class ChannelReestablish(
    @Serializable(with = ByteVector32KSerializer::class) override val channelId: ByteVector32,
    val nextLocalCommitmentNumber: Long,
    val nextRemoteRevocationNumber: Long,
    @Serializable(with = PrivateKeyKSerializer::class) val yourLastCommitmentSecret: PrivateKey,
    @Serializable(with = PublicKeyKSerializer::class) val myCurrentPerCommitmentPoint: PublicKey
) : HasChannelId, LightningSerializable<ChannelReestablish> {
    override fun serializer(): LightningSerializer<ChannelReestablish> = ChannelReestablish

    companion object : LightningSerializer<ChannelReestablish>() {
        override val tag: Long
            get() = 136L

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
            writeU64(message.nextLocalCommitmentNumber, out)
            writeU64(message.nextRemoteRevocationNumber, out)
            writeBytes(message.yourLastCommitmentSecret.value, out)
            writeBytes(message.myCurrentPerCommitmentPoint.value, out)
        }
    }
}

@OptIn(ExperimentalUnsignedTypes::class)
data class AnnouncementSignatures(
    override val channelId: ByteVector32,
    val shortChannelId: ShortChannelId,
    val nodeSignature: ByteVector64,
    val bitcoinSignature: ByteVector64
) : RoutingMessage, HasChannelId, LightningSerializable<AnnouncementSignatures> {
    override fun serializer(): LightningSerializer<AnnouncementSignatures> = AnnouncementSignatures

    companion object : LightningSerializer<AnnouncementSignatures>() {
        override val tag: Long
            get() = 259L

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

@OptIn(ExperimentalUnsignedTypes::class)
@Serializable
data class ChannelAnnouncement(
    @Serializable(with = ByteVector64KSerializer::class) val nodeSignature1: ByteVector64,
    @Serializable(with = ByteVector64KSerializer::class) val nodeSignature2: ByteVector64,
    @Serializable(with = ByteVector64KSerializer::class) val bitcoinSignature1: ByteVector64,
    @Serializable(with = ByteVector64KSerializer::class) val bitcoinSignature2: ByteVector64,
    val features: Features,
    @Serializable(with = ByteVector32KSerializer::class) override val chainHash: ByteVector32,
    val shortChannelId: ShortChannelId,
    @Serializable(with = PublicKeyKSerializer::class) val nodeId1: PublicKey,
    @Serializable(with = PublicKeyKSerializer::class) val nodeId2: PublicKey,
    @Serializable(with = PublicKeyKSerializer::class) val bitcoinKey1: PublicKey,
    @Serializable(with = PublicKeyKSerializer::class) val bitcoinKey2: PublicKey,
    @Serializable(with = ByteVectorKSerializer::class) val unknownFields: ByteVector = ByteVector.empty
) : RoutingMessage, AnnouncementMessage, HasChainHash, LightningSerializable<ChannelAnnouncement> {
    override fun serializer(): LightningSerializer<ChannelAnnouncement> = ChannelAnnouncement

    companion object : LightningSerializer<ChannelAnnouncement>() {
        override val tag: Long
            get() = 256L

        override fun read(input: Input): ChannelAnnouncement {
            TODO()
        }

        override fun write(message: ChannelAnnouncement, out: Output) {
            TODO()
        }
    }
}

@OptIn(ExperimentalUnsignedTypes::class)
@Serializable
data class ChannelUpdate(
    @Serializable(with = ByteVector64KSerializer::class) val signature: ByteVector64,
    @Serializable(with = ByteVector32KSerializer::class) override val chainHash: ByteVector32,
    val shortChannelId: ShortChannelId,
    override val timestamp: Long,
    val messageFlags: Byte,
    val channelFlags: Byte,
    val cltvExpiryDelta: CltvExpiryDelta,
    val htlcMinimumMsat: MilliSatoshi,
    val feeBaseMsat: MilliSatoshi,
    val feeProportionalMillionths: Long,
    val htlcMaximumMsat: MilliSatoshi?,
    @Serializable(with = ByteVectorKSerializer::class) val unknownFields: ByteVector = ByteVector.empty
) : AnnouncementMessage, HasTimestamp, HasChainHash, LightningSerializable<ChannelUpdate> {
    init {
        require(((messageFlags.toInt() and 1) != 0) == (htlcMaximumMsat != null)) { "htlcMaximumMsat is not consistent with messageFlags" }
    }

    override fun serializer(): LightningSerializer<ChannelUpdate> = ChannelUpdate

    companion object : LightningSerializer<ChannelUpdate>() {
        override val tag: Long
            get() = 258L

        override fun read(input: Input): ChannelUpdate {
            TODO()
        }

        override fun write(message: ChannelUpdate, out: Output) {
            TODO()
        }
    }

    fun isNode1(): Boolean = (channelFlags.toInt() and 1) == 0
}

@OptIn(ExperimentalUnsignedTypes::class)
@Serializable
data class Shutdown(
    @Serializable(with = ByteVector32KSerializer::class) override val channelId: ByteVector32,
    @Serializable(with = ByteVectorKSerializer::class) val scriptPubKey: ByteVector,
    @Serializable(with = ByteVectorKSerializer::class) val channelData: ByteVector? = null
) : ChannelMessage, HasChannelId, LightningSerializable<FundingLocked> {
    override fun serializer(): LightningSerializer<FundingLocked> = FundingLocked

    companion object : LightningSerializer<FundingLocked>() {
        override val tag: Long
            get() = 36L

        override fun read(input: Input): FundingLocked {
            TODO()
        }

        override fun write(message: FundingLocked, out: Output) {
            TODO()
        }
    }
}

