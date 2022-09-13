package fr.acinq.lightning.wire

import fr.acinq.bitcoin.*
import fr.acinq.bitcoin.io.ByteArrayInput
import fr.acinq.bitcoin.io.ByteArrayOutput
import fr.acinq.bitcoin.io.Input
import fr.acinq.bitcoin.io.Output
import fr.acinq.lightning.*
import fr.acinq.lightning.blockchain.fee.FeeratePerKw
import fr.acinq.lightning.channel.ChannelType
import fr.acinq.lightning.router.Announcements
import fr.acinq.lightning.utils.*
import fr.acinq.secp256k1.Hex
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlin.math.max
import kotlin.math.min

interface LightningMessage {

    /**
     * Once decrypted, lightning messages start with a 2-byte type that uniquely identifies the kind of message received.
     * See https://github.com/lightningnetwork/lightning-rfc/blob/master/01-messaging.md#lightning-message-format.
     */
    val type: Long

    fun write(out: Output)

    fun write(): ByteArray {
        val out = ByteArrayOutput()
        write(out)
        return out.toByteArray()
    }

    companion object {

        /**
         * @param input a single, complete message (typically received over the transport layer).
         * There is a very strong assumption that framing has been taken care of and that there are no missing or extra bytes.
         * Whatever we don't read will simply be ignored, as per the BOLTs.
         */
        fun decode(input: ByteArray): LightningMessage {
            val stream = ByteArrayInput(input)
            val code = LightningCodecs.u16(stream)
            return when (code.toLong()) {
                Init.type -> Init.read(stream)
                ChannelReestablish.type -> ChannelReestablish.read(stream)
                Warning.type -> Warning.read(stream)
                Error.type -> Error.read(stream)
                Ping.type -> Ping.read(stream)
                Pong.type -> Pong.read(stream)
                OpenDualFundedChannel.type -> OpenDualFundedChannel.read(stream)
                AcceptDualFundedChannel.type -> AcceptDualFundedChannel.read(stream)
                FundingCreated.type -> FundingCreated.read(stream)
                FundingSigned.type -> FundingSigned.read(stream)
                FundingLocked.type -> FundingLocked.read(stream)
                TxAddInput.type -> TxAddInput.read(stream)
                TxAddOutput.type -> TxAddOutput.read(stream)
                TxRemoveInput.type -> TxRemoveInput.read(stream)
                TxRemoveOutput.type -> TxRemoveOutput.read(stream)
                TxComplete.type -> TxComplete.read(stream)
                TxSignatures.type -> TxSignatures.read(stream)
                TxInitRbf.type -> TxInitRbf.read(stream)
                TxAckRbf.type -> TxAckRbf.read(stream)
                TxAbort.type -> TxAbort.read(stream)
                CommitSig.type -> CommitSig.read(stream)
                RevokeAndAck.type -> RevokeAndAck.read(stream)
                UpdateAddHtlc.type -> UpdateAddHtlc.read(stream)
                UpdateFailHtlc.type -> UpdateFailHtlc.read(stream)
                UpdateFailMalformedHtlc.type -> UpdateFailMalformedHtlc.read(stream)
                UpdateFulfillHtlc.type -> UpdateFulfillHtlc.read(stream)
                UpdateFee.type -> UpdateFee.read(stream)
                AnnouncementSignatures.type -> AnnouncementSignatures.read(stream)
                ChannelAnnouncement.type -> ChannelAnnouncement.read(stream)
                ChannelUpdate.type -> ChannelUpdate.read(stream)
                Shutdown.type -> Shutdown.read(stream)
                ClosingSigned.type -> ClosingSigned.read(stream)
                PayToOpenRequest.type -> PayToOpenRequest.read(stream)
                PayToOpenResponse.type -> PayToOpenResponse.read(stream)
                FCMToken.type -> FCMToken.read(stream)
                UnsetFCMToken.type -> UnsetFCMToken
                SwapInRequest.type -> SwapInRequest.read(stream)
                SwapInResponse.type -> SwapInResponse.read(stream)
                SwapInPending.type -> SwapInPending.read(stream)
                SwapInConfirmed.type -> SwapInConfirmed.read(stream)
                SwapOutRequest.type -> SwapOutRequest.read(stream)
                SwapOutResponse.type -> SwapOutResponse.read(stream)
                PhoenixAndroidLegacyInfo.type -> PhoenixAndroidLegacyInfo.read(stream)
                PhoenixAndroidLegacyMigrate.type -> PhoenixAndroidLegacyMigrate.read(stream)
                OnionMessage.type -> OnionMessage.read(stream)
                else -> UnknownMessage(code.toLong())
            }
        }

        fun encode(input: LightningMessage, out: Output) {
            LightningCodecs.writeU16(input.type.toInt(), out)
            input.write(out)
        }

        fun encode(input: LightningMessage): ByteArray {
            val out = ByteArrayOutput()
            encode(input, out)
            return out.toByteArray()
        }
    }
}

interface LightningMessageReader<T : LightningMessage> {
    fun read(input: Input): T
    fun read(bytes: ByteArray): T = read(ByteArrayInput(bytes))
    fun read(hex: String): T = read(Hex.decode(hex))
}

interface HtlcMessage : LightningMessage
interface SetupMessage : LightningMessage
interface RoutingMessage : LightningMessage
interface AnnouncementMessage : RoutingMessage
interface HasTimestamp : LightningMessage {
    val timestampSeconds: Long
}

interface UpdateMessage : LightningMessage

interface HtlcSettlementMessage : UpdateMessage {
    val id: Long
}

sealed class InteractiveTxMessage : LightningMessage
sealed class InteractiveTxConstructionMessage : InteractiveTxMessage()

interface HasTemporaryChannelId : LightningMessage {
    val temporaryChannelId: ByteVector32
}

interface HasChannelId : LightningMessage {
    val channelId: ByteVector32
}

interface HasSerialId : LightningMessage {
    val serialId: Long
}

interface HasChainHash : LightningMessage {
    val chainHash: ByteVector32
}

@Serializable
data class EncryptedChannelData(@Contextual val data: ByteVector) {
    /** We don't want to log the encrypted channel backups, they take a lot of space. We only keep the first bytes to help correlate mobile/server backups. */
    override fun toString(): String {
        val bytes = data.take(min(data.size(), 10))
        return if (bytes.isEmpty()) "" else "$bytes (truncated)"
    }

    fun isEmpty(): Boolean = data.isEmpty()

    companion object {
        val empty: EncryptedChannelData = EncryptedChannelData(ByteVector.empty)
    }
}

interface HasEncryptedChannelData : LightningMessage {
    val channelData: EncryptedChannelData
    fun withChannelData(data: ByteVector): HasEncryptedChannelData = withChannelData(EncryptedChannelData(data))
    fun withChannelData(ecd: EncryptedChannelData): HasEncryptedChannelData = if (ecd.isEmpty()) this else withNonEmptyChannelData(ecd)
    fun withNonEmptyChannelData(ecd: EncryptedChannelData): HasEncryptedChannelData
}

interface ChannelMessage

@Serializable
data class Init(@Contextual val features: ByteVector, val tlvs: TlvStream<InitTlv> = TlvStream.empty()) : SetupMessage {
    @Transient
    val networks = tlvs.get<InitTlv.Networks>()?.chainHashes ?: listOf()

    override val type: Long get() = Init.type

    override fun write(out: Output) {
        LightningCodecs.writeU16(0, out)
        LightningCodecs.writeU16(features.size(), out)
        LightningCodecs.writeBytes(features, out)
        val tlvReaders = HashMap<Long, TlvValueReader<InitTlv>>()
        @Suppress("UNCHECKED_CAST")
        tlvReaders[InitTlv.Networks.tag] = InitTlv.Networks.Companion as TlvValueReader<InitTlv>
        @Suppress("UNCHECKED_CAST")
        tlvReaders[InitTlv.PhoenixAndroidLegacyNodeId.tag] = InitTlv.PhoenixAndroidLegacyNodeId.Companion as TlvValueReader<InitTlv>
        val serializer = TlvStreamSerializer(false, tlvReaders)
        serializer.write(tlvs, out)
    }

    companion object : LightningMessageReader<Init> {
        const val type: Long = 16

        override fun read(input: Input): Init {
            val gflen = LightningCodecs.u16(input)
            val globalFeatures = LightningCodecs.bytes(input, gflen)
            val lflen = LightningCodecs.u16(input)
            val localFeatures = LightningCodecs.bytes(input, lflen)
            val len = max(gflen, lflen)
            // merge features together
            val features = ByteVector(globalFeatures.leftPaddedCopyOf(len).or(localFeatures.leftPaddedCopyOf(len)))
            val tlvReaders = HashMap<Long, TlvValueReader<InitTlv>>()
            @Suppress("UNCHECKED_CAST")
            tlvReaders[InitTlv.Networks.tag] = InitTlv.Networks.Companion as TlvValueReader<InitTlv>
            @Suppress("UNCHECKED_CAST")
            tlvReaders[InitTlv.PhoenixAndroidLegacyNodeId.tag] = InitTlv.PhoenixAndroidLegacyNodeId.Companion as TlvValueReader<InitTlv>
            val serializer = TlvStreamSerializer(false, tlvReaders)
            val tlvs = serializer.read(input)
            return Init(features, tlvs)
        }
    }
}

data class Warning(override val channelId: ByteVector32, val data: ByteVector) : SetupMessage, HasChannelId {
    constructor(channelId: ByteVector32, message: String?) : this(channelId, ByteVector(message?.encodeToByteArray() ?: ByteArray(0)))
    constructor(message: String?) : this(ByteVector32.Zeroes, message)

    fun toAscii(): String = data.toByteArray().decodeToString()

    override val type: Long get() = Warning.type

    override fun write(out: Output) {
        LightningCodecs.writeBytes(channelId, out)
        LightningCodecs.writeU16(data.size(), out)
        LightningCodecs.writeBytes(data, out)
    }

    companion object : LightningMessageReader<Warning> {
        const val type: Long = 1

        override fun read(input: Input): Warning {
            return Warning(
                LightningCodecs.bytes(input, 32).toByteVector32(),
                LightningCodecs.bytes(input, LightningCodecs.u16(input)).toByteVector()
            )
        }
    }
}

data class Error(override val channelId: ByteVector32, val data: ByteVector) : SetupMessage, HasChannelId {
    constructor(channelId: ByteVector32, message: String?) : this(channelId, ByteVector(message?.encodeToByteArray() ?: ByteArray(0)))

    fun toAscii(): String = data.toByteArray().decodeToString()

    override val type: Long get() = Error.type

    override fun write(out: Output) {
        LightningCodecs.writeBytes(channelId, out)
        LightningCodecs.writeU16(data.size(), out)
        LightningCodecs.writeBytes(data, out)
    }

    companion object : LightningMessageReader<Error> {
        const val type: Long = 17

        override fun read(input: Input): Error {
            return Error(
                LightningCodecs.bytes(input, 32).toByteVector32(),
                LightningCodecs.bytes(input, LightningCodecs.u16(input)).toByteVector()
            )
        }
    }
}

data class Ping(val pongLength: Int, val data: ByteVector) : SetupMessage {
    override val type: Long get() = Ping.type

    override fun write(out: Output) {
        LightningCodecs.writeU16(pongLength, out)
        LightningCodecs.writeU16(data.size(), out)
        LightningCodecs.writeBytes(data, out)
    }

    companion object : LightningMessageReader<Ping> {
        const val type: Long = 18

        override fun read(input: Input): Ping {
            return Ping(LightningCodecs.u16(input), LightningCodecs.bytes(input, LightningCodecs.u16(input)).toByteVector())
        }
    }
}

data class Pong(val data: ByteVector) : SetupMessage {
    override val type: Long get() = Pong.type

    override fun write(out: Output) {
        LightningCodecs.writeU16(data.size(), out)
        LightningCodecs.writeBytes(data, out)
    }

    companion object : LightningMessageReader<Pong> {
        const val type: Long = 19

        override fun read(input: Input): Pong {
            return Pong(LightningCodecs.bytes(input, LightningCodecs.u16(input)).toByteVector())
        }
    }
}

@Serializable
data class TxAddInput(
    @Contextual override val channelId: ByteVector32,
    override val serialId: Long,
    @Contextual val previousTx: Transaction,
    val previousTxOutput: Long,
    val sequence: Long,
    val tlvs: TlvStream<TxAddInputTlv> = TlvStream.empty()
) : InteractiveTxConstructionMessage(), HasChannelId, HasSerialId {
    override val type: Long get() = TxAddInput.type

    override fun write(out: Output) {
        LightningCodecs.writeBytes(channelId.toByteArray(), out)
        LightningCodecs.writeU64(serialId, out)
        val encodedTx = Transaction.write(previousTx)
        LightningCodecs.writeU16(encodedTx.size, out)
        LightningCodecs.writeBytes(encodedTx, out)
        LightningCodecs.writeU32(previousTxOutput.toInt(), out)
        LightningCodecs.writeU32(sequence.toInt(), out)
    }

    companion object : LightningMessageReader<TxAddInput> {
        const val type: Long = 66

        override fun read(input: Input): TxAddInput = TxAddInput(
            LightningCodecs.bytes(input, 32).byteVector32(),
            LightningCodecs.u64(input),
            Transaction.read(LightningCodecs.bytes(input, LightningCodecs.u16(input))),
            LightningCodecs.u32(input).toLong(),
            LightningCodecs.u32(input).toLong(),
        )
    }
}

@Serializable
data class TxAddOutput(
    @Contextual override val channelId: ByteVector32,
    override val serialId: Long,
    @Contextual val amount: Satoshi,
    @Contextual val pubkeyScript: ByteVector,
    val tlvs: TlvStream<TxAddOutputTlv> = TlvStream.empty()
) : InteractiveTxConstructionMessage(), HasChannelId, HasSerialId {
    override val type: Long get() = TxAddOutput.type

    override fun write(out: Output) {
        LightningCodecs.writeBytes(channelId.toByteArray(), out)
        LightningCodecs.writeU64(serialId, out)
        LightningCodecs.writeU64(amount.toLong(), out)
        LightningCodecs.writeU16(pubkeyScript.size(), out)
        LightningCodecs.writeBytes(pubkeyScript, out)
    }

    companion object : LightningMessageReader<TxAddOutput> {
        const val type: Long = 67

        override fun read(input: Input): TxAddOutput = TxAddOutput(
            LightningCodecs.bytes(input, 32).byteVector32(),
            LightningCodecs.u64(input),
            LightningCodecs.u64(input).sat,
            LightningCodecs.bytes(input, LightningCodecs.u16(input)).byteVector(),
        )
    }
}

@Serializable
data class TxRemoveInput(
    @Contextual override val channelId: ByteVector32,
    override val serialId: Long,
    val tlvs: TlvStream<TxRemoveInputTlv> = TlvStream.empty()
) : InteractiveTxConstructionMessage(), HasChannelId, HasSerialId {
    override val type: Long get() = TxRemoveInput.type

    override fun write(out: Output) {
        LightningCodecs.writeBytes(channelId.toByteArray(), out)
        LightningCodecs.writeU64(serialId, out)
    }

    companion object : LightningMessageReader<TxRemoveInput> {
        const val type: Long = 68

        override fun read(input: Input): TxRemoveInput = TxRemoveInput(
            LightningCodecs.bytes(input, 32).byteVector32(),
            LightningCodecs.u64(input),
        )
    }
}

@Serializable
data class TxRemoveOutput(
    @Contextual override val channelId: ByteVector32,
    override val serialId: Long,
    val tlvs: TlvStream<TxRemoveOutputTlv> = TlvStream.empty()
) : InteractiveTxConstructionMessage(), HasChannelId, HasSerialId {
    override val type: Long get() = TxRemoveOutput.type

    override fun write(out: Output) {
        LightningCodecs.writeBytes(channelId.toByteArray(), out)
        LightningCodecs.writeU64(serialId, out)
    }

    companion object : LightningMessageReader<TxRemoveOutput> {
        const val type: Long = 69

        override fun read(input: Input): TxRemoveOutput = TxRemoveOutput(
            LightningCodecs.bytes(input, 32).byteVector32(),
            LightningCodecs.u64(input),
        )
    }
}

@Serializable
data class TxComplete(
    @Contextual override val channelId: ByteVector32,
    val tlvs: TlvStream<TxCompleteTlv> = TlvStream.empty()
) : InteractiveTxConstructionMessage(), HasChannelId {
    override val type: Long get() = TxComplete.type

    override fun write(out: Output) = LightningCodecs.writeBytes(channelId.toByteArray(), out)

    companion object : LightningMessageReader<TxComplete> {
        const val type: Long = 70

        override fun read(input: Input): TxComplete = TxComplete(LightningCodecs.bytes(input, 32).byteVector32())
    }
}

@Serializable
data class TxSignatures(
    @Contextual override val channelId: ByteVector32,
    @Contextual val txId: ByteVector32,
    val witnesses: List<@Contextual ScriptWitness>,
    val tlvs: TlvStream<TxSignaturesTlv> = TlvStream.empty()
) : InteractiveTxMessage(), HasChannelId {
    override val type: Long get() = TxSignatures.type

    override fun write(out: Output) {
        LightningCodecs.writeBytes(channelId.toByteArray(), out)
        LightningCodecs.writeBytes(txId.toByteArray(), out)
        LightningCodecs.writeU16(witnesses.size, out)
        witnesses.forEach { witness ->
            LightningCodecs.writeU16(witness.stack.size, out)
            witness.stack.forEach { element ->
                LightningCodecs.writeU16(element.size(), out)
                LightningCodecs.writeBytes(element.toByteArray(), out)
            }
        }
    }

    companion object : LightningMessageReader<TxSignatures> {
        const val type: Long = 71

        override fun read(input: Input): TxSignatures {
            val channelId = LightningCodecs.bytes(input, 32).byteVector32()
            val txId = LightningCodecs.bytes(input, 32).byteVector32()
            val witnessCount = LightningCodecs.u16(input)
            val witnesses = ArrayList<ScriptWitness>(witnessCount)
            for (i in 1..witnessCount) {
                val stackSize = LightningCodecs.u16(input)
                val stack = ArrayList<ByteVector>(stackSize)
                for (j in 1..stackSize) {
                    val elementSize = LightningCodecs.u16(input)
                    stack += LightningCodecs.bytes(input, elementSize).byteVector()
                }
                witnesses += ScriptWitness(stack.toList())
            }
            return TxSignatures(channelId, txId, witnesses)
        }
    }
}

@Serializable
data class TxInitRbf(
    @Contextual override val channelId: ByteVector32,
    val lockTime: Long,
    val feerate: FeeratePerKw,
    val tlvs: TlvStream<TxInitRbfTlv> = TlvStream.empty()
) : InteractiveTxMessage(), HasChannelId {
    constructor(channelId: ByteVector32, lockTime: Long, feerate: FeeratePerKw, fundingContribution: Satoshi) : this(channelId, lockTime, feerate, TlvStream(listOf(TxInitRbfTlv.SharedOutputContributionTlv(fundingContribution))))

    @Transient
    val fundingContribution = tlvs.get<TxInitRbfTlv.SharedOutputContributionTlv>()?.amount ?: 0.sat

    override val type: Long get() = TxInitRbf.type

    override fun write(out: Output) {
        LightningCodecs.writeBytes(channelId.toByteArray(), out)
        LightningCodecs.writeU32(lockTime.toInt(), out)
        LightningCodecs.writeU32(feerate.toLong().toInt(), out)
        TlvStreamSerializer(false, readers).write(tlvs, out)
    }

    companion object : LightningMessageReader<TxInitRbf> {
        const val type: Long = 72

        @Suppress("UNCHECKED_CAST")
        val readers = mapOf(TxInitRbfTlv.SharedOutputContributionTlv.tag to TxInitRbfTlv.SharedOutputContributionTlv.Companion as TlvValueReader<TxInitRbfTlv>)

        override fun read(input: Input): TxInitRbf = TxInitRbf(
            LightningCodecs.bytes(input, 32).byteVector32(),
            LightningCodecs.u32(input).toLong(),
            FeeratePerKw(LightningCodecs.u32(input).toLong().sat),
            TlvStreamSerializer(false, readers).read(input),
        )
    }
}

@Serializable
data class TxAckRbf(
    @Contextual override val channelId: ByteVector32,
    val tlvs: TlvStream<TxAckRbfTlv> = TlvStream.empty()
) : InteractiveTxMessage(), HasChannelId {
    constructor(channelId: ByteVector32, fundingContribution: Satoshi) : this(channelId, TlvStream(listOf(TxAckRbfTlv.SharedOutputContributionTlv(fundingContribution))))

    @Transient
    val fundingContribution = tlvs.get<TxAckRbfTlv.SharedOutputContributionTlv>()?.amount ?: 0.sat

    override val type: Long get() = TxAckRbf.type

    override fun write(out: Output) {
        LightningCodecs.writeBytes(channelId.toByteArray(), out)
        TlvStreamSerializer(false, readers).write(tlvs, out)
    }

    companion object : LightningMessageReader<TxAckRbf> {
        const val type: Long = 73

        @Suppress("UNCHECKED_CAST")
        val readers = mapOf(TxAckRbfTlv.SharedOutputContributionTlv.tag to TxAckRbfTlv.SharedOutputContributionTlv.Companion as TlvValueReader<TxAckRbfTlv>)

        override fun read(input: Input): TxAckRbf = TxAckRbf(
            LightningCodecs.bytes(input, 32).byteVector32(),
            TlvStreamSerializer(false, readers).read(input),
        )
    }
}

@Serializable
data class TxAbort(
    @Contextual override val channelId: ByteVector32,
    @Contextual val data: ByteVector,
    val tlvs: TlvStream<TxAbortTlv> = TlvStream.empty()
) : InteractiveTxMessage(), HasChannelId {
    constructor(channelId: ByteVector32, message: String?) : this(channelId, ByteVector(message?.encodeToByteArray() ?: ByteArray(0)))

    fun toAscii(): String = data.toByteArray().decodeToString()

    override val type: Long get() = TxAbort.type

    override fun write(out: Output) {
        LightningCodecs.writeBytes(channelId, out)
        LightningCodecs.writeU16(data.size(), out)
        LightningCodecs.writeBytes(data, out)
    }

    companion object : LightningMessageReader<TxAbort> {
        const val type: Long = 74

        override fun read(input: Input): TxAbort = TxAbort(
            LightningCodecs.bytes(input, 32).byteVector32(),
            LightningCodecs.bytes(input, LightningCodecs.u16(input)).byteVector(),
        )
    }
}

@Serializable
data class OpenDualFundedChannel(
    @Contextual override val chainHash: ByteVector32,
    @Contextual override val temporaryChannelId: ByteVector32,
    val fundingFeerate: FeeratePerKw,
    val commitmentFeerate: FeeratePerKw,
    @Contextual val fundingAmount: Satoshi,
    @Contextual val dustLimit: Satoshi,
    val maxHtlcValueInFlightMsat: Long, // this is not MilliSatoshi because it can exceed the total amount of MilliSatoshi
    val htlcMinimum: MilliSatoshi,
    val toSelfDelay: CltvExpiryDelta,
    val maxAcceptedHtlcs: Int,
    val lockTime: Long,
    @Contextual val fundingPubkey: PublicKey,
    @Contextual val revocationBasepoint: PublicKey,
    @Contextual val paymentBasepoint: PublicKey,
    @Contextual val delayedPaymentBasepoint: PublicKey,
    @Contextual val htlcBasepoint: PublicKey,
    @Contextual val firstPerCommitmentPoint: PublicKey,
    val channelFlags: Byte,
    val tlvStream: TlvStream<ChannelTlv> = TlvStream.empty()
) : ChannelMessage, HasTemporaryChannelId, HasChainHash {
    val channelType: ChannelType? get() = tlvStream.get<ChannelTlv.ChannelTypeTlv>()?.channelType
    val pushAmount: MilliSatoshi get() = tlvStream.get<ChannelTlv.PushAmountTlv>()?.amount ?: 0.msat

    override val type: Long get() = OpenDualFundedChannel.type

    override fun write(out: Output) {
        @Suppress("UNCHECKED_CAST")
        val readers = mapOf(
            ChannelTlv.UpfrontShutdownScriptTlv.tag to ChannelTlv.UpfrontShutdownScriptTlv.Companion as TlvValueReader<ChannelTlv>,
            ChannelTlv.ChannelTypeTlv.tag to ChannelTlv.ChannelTypeTlv.Companion as TlvValueReader<ChannelTlv>,
            ChannelTlv.ChannelOriginTlv.tag to ChannelTlv.ChannelOriginTlv.Companion as TlvValueReader<ChannelTlv>,
            ChannelTlv.PushAmountTlv.tag to ChannelTlv.PushAmountTlv.Companion as TlvValueReader<ChannelTlv>,
        )
        LightningCodecs.writeBytes(chainHash, out)
        LightningCodecs.writeBytes(temporaryChannelId, out)
        LightningCodecs.writeU32(fundingFeerate.toLong().toInt(), out)
        LightningCodecs.writeU32(commitmentFeerate.toLong().toInt(), out)
        LightningCodecs.writeU64(fundingAmount.toLong(), out)
        LightningCodecs.writeU64(dustLimit.toLong(), out)
        LightningCodecs.writeU64(maxHtlcValueInFlightMsat, out)
        LightningCodecs.writeU64(htlcMinimum.toLong(), out)
        LightningCodecs.writeU16(toSelfDelay.toInt(), out)
        LightningCodecs.writeU16(maxAcceptedHtlcs, out)
        LightningCodecs.writeU32(lockTime.toInt(), out)
        LightningCodecs.writeBytes(fundingPubkey.value, out)
        LightningCodecs.writeBytes(revocationBasepoint.value, out)
        LightningCodecs.writeBytes(paymentBasepoint.value, out)
        LightningCodecs.writeBytes(delayedPaymentBasepoint.value, out)
        LightningCodecs.writeBytes(htlcBasepoint.value, out)
        LightningCodecs.writeBytes(firstPerCommitmentPoint.value, out)
        LightningCodecs.writeByte(channelFlags.toInt(), out)
        TlvStreamSerializer(false, readers).write(tlvStream, out)
    }

    companion object : LightningMessageReader<OpenDualFundedChannel> {
        const val type: Long = 64

        override fun read(input: Input): OpenDualFundedChannel {
            @Suppress("UNCHECKED_CAST")
            val readers = mapOf(
                ChannelTlv.UpfrontShutdownScriptTlv.tag to ChannelTlv.UpfrontShutdownScriptTlv.Companion as TlvValueReader<ChannelTlv>,
                ChannelTlv.ChannelTypeTlv.tag to ChannelTlv.ChannelTypeTlv.Companion as TlvValueReader<ChannelTlv>,
                ChannelTlv.ChannelOriginTlv.tag to ChannelTlv.ChannelOriginTlv.Companion as TlvValueReader<ChannelTlv>,
                ChannelTlv.PushAmountTlv.tag to ChannelTlv.PushAmountTlv.Companion as TlvValueReader<ChannelTlv>,
            )
            return OpenDualFundedChannel(
                ByteVector32(LightningCodecs.bytes(input, 32)),
                ByteVector32(LightningCodecs.bytes(input, 32)),
                FeeratePerKw(LightningCodecs.u32(input).toLong().sat),
                FeeratePerKw(LightningCodecs.u32(input).toLong().sat),
                Satoshi(LightningCodecs.u64(input)),
                Satoshi(LightningCodecs.u64(input)),
                LightningCodecs.u64(input), // this is not MilliSatoshi because it can exceed the total amount of MilliSatoshi
                MilliSatoshi(LightningCodecs.u64(input)),
                CltvExpiryDelta(LightningCodecs.u16(input)),
                LightningCodecs.u16(input),
                LightningCodecs.u32(input).toLong(),
                PublicKey(LightningCodecs.bytes(input, 33)),
                PublicKey(LightningCodecs.bytes(input, 33)),
                PublicKey(LightningCodecs.bytes(input, 33)),
                PublicKey(LightningCodecs.bytes(input, 33)),
                PublicKey(LightningCodecs.bytes(input, 33)),
                PublicKey(LightningCodecs.bytes(input, 33)),
                LightningCodecs.byte(input).toByte(),
                TlvStreamSerializer(false, readers).read(input)
            )
        }
    }
}

@Serializable
data class AcceptDualFundedChannel(
    @Contextual override val temporaryChannelId: ByteVector32,
    @Contextual val fundingAmount: Satoshi,
    @Contextual val dustLimit: Satoshi,
    val maxHtlcValueInFlightMsat: Long, // this is not MilliSatoshi because it can exceed the total amount of MilliSatoshi
    val htlcMinimum: MilliSatoshi,
    val minimumDepth: Long,
    val toSelfDelay: CltvExpiryDelta,
    val maxAcceptedHtlcs: Int,
    @Contextual val fundingPubkey: PublicKey,
    @Contextual val revocationBasepoint: PublicKey,
    @Contextual val paymentBasepoint: PublicKey,
    @Contextual val delayedPaymentBasepoint: PublicKey,
    @Contextual val htlcBasepoint: PublicKey,
    @Contextual val firstPerCommitmentPoint: PublicKey,
    val tlvStream: TlvStream<ChannelTlv> = TlvStream.empty()
) : ChannelMessage, HasTemporaryChannelId {
    val channelType: ChannelType? get() = tlvStream.get<ChannelTlv.ChannelTypeTlv>()?.channelType

    override val type: Long get() = AcceptDualFundedChannel.type

    override fun write(out: Output) {
        @Suppress("UNCHECKED_CAST")
        val readers = mapOf(
            ChannelTlv.UpfrontShutdownScriptTlv.tag to ChannelTlv.UpfrontShutdownScriptTlv.Companion as TlvValueReader<ChannelTlv>,
            ChannelTlv.ChannelTypeTlv.tag to ChannelTlv.ChannelTypeTlv.Companion as TlvValueReader<ChannelTlv>,
        )
        LightningCodecs.writeBytes(temporaryChannelId, out)
        LightningCodecs.writeU64(fundingAmount.toLong(), out)
        LightningCodecs.writeU64(dustLimit.toLong(), out)
        LightningCodecs.writeU64(maxHtlcValueInFlightMsat, out)
        LightningCodecs.writeU64(htlcMinimum.toLong(), out)
        LightningCodecs.writeU32(minimumDepth.toInt(), out)
        LightningCodecs.writeU16(toSelfDelay.toInt(), out)
        LightningCodecs.writeU16(maxAcceptedHtlcs, out)
        LightningCodecs.writeBytes(fundingPubkey.value, out)
        LightningCodecs.writeBytes(revocationBasepoint.value, out)
        LightningCodecs.writeBytes(paymentBasepoint.value, out)
        LightningCodecs.writeBytes(delayedPaymentBasepoint.value, out)
        LightningCodecs.writeBytes(htlcBasepoint.value, out)
        LightningCodecs.writeBytes(firstPerCommitmentPoint.value, out)
        TlvStreamSerializer(false, readers).write(tlvStream, out)
    }

    companion object : LightningMessageReader<AcceptDualFundedChannel> {
        const val type: Long = 65

        override fun read(input: Input): AcceptDualFundedChannel {
            @Suppress("UNCHECKED_CAST")
            val readers = mapOf(
                ChannelTlv.UpfrontShutdownScriptTlv.tag to ChannelTlv.UpfrontShutdownScriptTlv.Companion as TlvValueReader<ChannelTlv>,
                ChannelTlv.ChannelTypeTlv.tag to ChannelTlv.ChannelTypeTlv.Companion as TlvValueReader<ChannelTlv>,
            )
            return AcceptDualFundedChannel(
                ByteVector32(LightningCodecs.bytes(input, 32)),
                Satoshi(LightningCodecs.u64(input)),
                Satoshi(LightningCodecs.u64(input)),
                LightningCodecs.u64(input), // this is not MilliSatoshi because it can exceed the total amount of MilliSatoshi,
                MilliSatoshi(LightningCodecs.u64(input)),
                LightningCodecs.u32(input).toLong(),
                CltvExpiryDelta(LightningCodecs.u16(input)),
                LightningCodecs.u16(input),
                PublicKey(LightningCodecs.bytes(input, 33)),
                PublicKey(LightningCodecs.bytes(input, 33)),
                PublicKey(LightningCodecs.bytes(input, 33)),
                PublicKey(LightningCodecs.bytes(input, 33)),
                PublicKey(LightningCodecs.bytes(input, 33)),
                PublicKey(LightningCodecs.bytes(input, 33)),
                TlvStreamSerializer(false, readers).read(input)
            )
        }
    }
}

@Serializable
data class FundingCreated(
    @Contextual override val temporaryChannelId: ByteVector32,
    @Contextual val fundingTxid: ByteVector32,
    val fundingOutputIndex: Int,
    @Contextual val signature: ByteVector64
) : ChannelMessage, HasTemporaryChannelId {
    override val type: Long get() = FundingCreated.type

    override fun write(out: Output) {
        LightningCodecs.writeBytes(temporaryChannelId, out)
        LightningCodecs.writeBytes(fundingTxid, out)
        LightningCodecs.writeU16(fundingOutputIndex, out)
        LightningCodecs.writeBytes(signature, out)
    }

    companion object : LightningMessageReader<FundingCreated> {
        const val type: Long = 34

        override fun read(input: Input): FundingCreated {
            return FundingCreated(
                ByteVector32(LightningCodecs.bytes(input, 32)),
                ByteVector32(LightningCodecs.bytes(input, 32)),
                LightningCodecs.u16(input),
                ByteVector64(LightningCodecs.bytes(input, 64))
            )
        }
    }
}

@Serializable
data class FundingSigned(
    @Contextual override val channelId: ByteVector32,
    @Contextual val signature: ByteVector64,
    val tlvStream: TlvStream<FundingSignedTlv> = TlvStream.empty()
) : ChannelMessage, HasChannelId, HasEncryptedChannelData {
    override val type: Long get() = FundingSigned.type

    override val channelData: EncryptedChannelData get() = tlvStream.get<FundingSignedTlv.ChannelData>()?.ecb ?: EncryptedChannelData.empty
    override fun withNonEmptyChannelData(ecd: EncryptedChannelData): FundingSigned = copy(tlvStream = tlvStream.addOrUpdate(FundingSignedTlv.ChannelData(ecd)))

    override fun write(out: Output) {
        LightningCodecs.writeBytes(channelId, out)
        LightningCodecs.writeBytes(signature, out)
        TlvStreamSerializer(false, readers).write(tlvStream, out)
    }

    companion object : LightningMessageReader<FundingSigned> {
        const val type: Long = 35

        @Suppress("UNCHECKED_CAST")
        val readers = mapOf(FundingSignedTlv.ChannelData.tag to FundingSignedTlv.ChannelData.Companion as TlvValueReader<FundingSignedTlv>)

        override fun read(input: Input): FundingSigned {
            return FundingSigned(
                ByteVector32(LightningCodecs.bytes(input, 32)),
                ByteVector64(LightningCodecs.bytes(input, 64)),
                TlvStreamSerializer(false, readers).read(input)
            )
        }
    }
}

@Serializable
data class FundingLocked(
    @Contextual override val channelId: ByteVector32,
    @Contextual val nextPerCommitmentPoint: PublicKey
) : ChannelMessage, HasChannelId {
    override val type: Long get() = FundingLocked.type

    override fun write(out: Output) {
        LightningCodecs.writeBytes(channelId, out)
        LightningCodecs.writeBytes(nextPerCommitmentPoint.value, out)
    }

    companion object : LightningMessageReader<FundingLocked> {
        const val type: Long = 36

        override fun read(input: Input): FundingLocked {
            return FundingLocked(
                ByteVector32(LightningCodecs.bytes(input, 32)),
                PublicKey(LightningCodecs.bytes(input, 33))
            )
        }
    }
}

@Serializable
data class UpdateAddHtlc(
    @Contextual override val channelId: ByteVector32,
    val id: Long,
    val amountMsat: MilliSatoshi,
    @Contextual val paymentHash: ByteVector32,
    val cltvExpiry: CltvExpiry,
    val onionRoutingPacket: OnionRoutingPacket
) : HtlcMessage, UpdateMessage, HasChannelId {
    override val type: Long get() = UpdateAddHtlc.type

    override fun write(out: Output) {
        LightningCodecs.writeBytes(channelId, out)
        LightningCodecs.writeU64(id, out)
        LightningCodecs.writeU64(amountMsat.toLong(), out)
        LightningCodecs.writeBytes(paymentHash, out)
        LightningCodecs.writeU32(cltvExpiry.toLong().toInt(), out)
        OnionRoutingPacketSerializer(OnionRoutingPacket.PaymentPacketLength).write(onionRoutingPacket, out)
    }

    companion object : LightningMessageReader<UpdateAddHtlc> {
        const val type: Long = 128

        override fun read(input: Input): UpdateAddHtlc {
            val channelId = ByteVector32(LightningCodecs.bytes(input, 32))
            val id = LightningCodecs.u64(input)
            val amount = MilliSatoshi(LightningCodecs.u64(input))
            val paymentHash = ByteVector32(LightningCodecs.bytes(input, 32))
            val expiry = CltvExpiry(LightningCodecs.u32(input).toLong())
            val onion = OnionRoutingPacketSerializer(OnionRoutingPacket.PaymentPacketLength).read(input)
            return UpdateAddHtlc(channelId, id, amount, paymentHash, expiry, onion)
        }
    }
}

@Serializable
data class UpdateFulfillHtlc(
    @Contextual override val channelId: ByteVector32,
    override val id: Long,
    @Contextual val paymentPreimage: ByteVector32
) : HtlcMessage, HtlcSettlementMessage, HasChannelId {
    override val type: Long get() = UpdateFulfillHtlc.type

    override fun write(out: Output) {
        LightningCodecs.writeBytes(channelId, out)
        LightningCodecs.writeU64(id, out)
        LightningCodecs.writeBytes(paymentPreimage, out)
    }

    companion object : LightningMessageReader<UpdateFulfillHtlc> {
        const val type: Long = 130

        override fun read(input: Input): UpdateFulfillHtlc {
            return UpdateFulfillHtlc(
                ByteVector32(LightningCodecs.bytes(input, 32)),
                LightningCodecs.u64(input),
                ByteVector32(LightningCodecs.bytes(input, 32))
            )
        }
    }
}

@Serializable
data class UpdateFailHtlc(
    @Contextual override val channelId: ByteVector32,
    override val id: Long,
    @Contextual val reason: ByteVector
) : HtlcMessage, HtlcSettlementMessage, HasChannelId {
    override val type: Long get() = UpdateFailHtlc.type

    override fun write(out: Output) {
        LightningCodecs.writeBytes(channelId, out)
        LightningCodecs.writeU64(id, out)
        LightningCodecs.writeU16(reason.size(), out)
        LightningCodecs.writeBytes(reason, out)
    }

    companion object : LightningMessageReader<UpdateFailHtlc> {
        const val type: Long = 131

        override fun read(input: Input): UpdateFailHtlc {
            return UpdateFailHtlc(
                ByteVector32(LightningCodecs.bytes(input, 32)),
                LightningCodecs.u64(input),
                ByteVector(LightningCodecs.bytes(input, LightningCodecs.u16(input)))
            )
        }
    }
}

@Serializable
data class UpdateFailMalformedHtlc(
    @Contextual override val channelId: ByteVector32,
    override val id: Long,
    @Contextual val onionHash: ByteVector32,
    val failureCode: Int
) : HtlcMessage, HtlcSettlementMessage, HasChannelId {
    override val type: Long get() = UpdateFailMalformedHtlc.type

    override fun write(out: Output) {
        LightningCodecs.writeBytes(channelId, out)
        LightningCodecs.writeU64(id, out)
        LightningCodecs.writeBytes(onionHash, out)
        LightningCodecs.writeU16(failureCode, out)
    }

    companion object : LightningMessageReader<UpdateFailMalformedHtlc> {
        const val type: Long = 135

        override fun read(input: Input): UpdateFailMalformedHtlc {
            return UpdateFailMalformedHtlc(
                ByteVector32(LightningCodecs.bytes(input, 32)),
                LightningCodecs.u64(input),
                ByteVector32(LightningCodecs.bytes(input, 32)),
                LightningCodecs.u16(input)
            )
        }
    }
}

@Serializable
data class CommitSig(
    @Contextual override val channelId: ByteVector32,
    @Contextual val signature: ByteVector64,
    val htlcSignatures: List<@Contextual ByteVector64>,
    val tlvStream: TlvStream<CommitSigTlv> = TlvStream.empty()
) : HtlcMessage, HasChannelId, HasEncryptedChannelData {
    override val type: Long get() = CommitSig.type

    override val channelData: EncryptedChannelData get() = tlvStream.get<CommitSigTlv.ChannelData>()?.ecb ?: EncryptedChannelData.empty
    override fun withNonEmptyChannelData(ecd: EncryptedChannelData): CommitSig = copy(tlvStream = tlvStream.addOrUpdate(CommitSigTlv.ChannelData(ecd)))

    override fun write(out: Output) {
        LightningCodecs.writeBytes(channelId, out)
        LightningCodecs.writeBytes(signature, out)
        LightningCodecs.writeU16(htlcSignatures.size, out)
        htlcSignatures.forEach { LightningCodecs.writeBytes(it, out) }
        TlvStreamSerializer(false, readers).write(tlvStream, out)
    }

    companion object : LightningMessageReader<CommitSig> {
        const val type: Long = 132

        @Suppress("UNCHECKED_CAST")
        val readers = mapOf(CommitSigTlv.ChannelData.tag to CommitSigTlv.ChannelData.Companion as TlvValueReader<CommitSigTlv>)

        override fun read(input: Input): CommitSig {
            val channelId = ByteVector32(LightningCodecs.bytes(input, 32))
            val sig = ByteVector64(LightningCodecs.bytes(input, 64))
            val numHtlcs = LightningCodecs.u16(input)
            val htlcSigs = ArrayList<ByteVector64>(numHtlcs)
            for (i in 1..numHtlcs) {
                htlcSigs += ByteVector64(LightningCodecs.bytes(input, 64))
            }
            return CommitSig(channelId, sig, htlcSigs.toList(), TlvStreamSerializer(false, readers).read(input))
        }
    }
}

data class RevokeAndAck(
    override val channelId: ByteVector32,
    val perCommitmentSecret: PrivateKey,
    val nextPerCommitmentPoint: PublicKey,
    val tlvStream: TlvStream<RevokeAndAckTlv> = TlvStream.empty()
) : HtlcMessage, HasChannelId, HasEncryptedChannelData {
    override val type: Long get() = RevokeAndAck.type

    override val channelData: EncryptedChannelData get() = tlvStream.get<RevokeAndAckTlv.ChannelData>()?.ecb ?: EncryptedChannelData.empty
    override fun withNonEmptyChannelData(ecd: EncryptedChannelData): RevokeAndAck = copy(tlvStream = tlvStream.addOrUpdate(RevokeAndAckTlv.ChannelData(ecd)))

    override fun write(out: Output) {
        LightningCodecs.writeBytes(channelId, out)
        LightningCodecs.writeBytes(perCommitmentSecret.value, out)
        LightningCodecs.writeBytes(nextPerCommitmentPoint.value, out)
        TlvStreamSerializer(false, readers).write(tlvStream, out)
    }

    companion object : LightningMessageReader<RevokeAndAck> {
        const val type: Long = 133

        @Suppress("UNCHECKED_CAST")
        val readers = mapOf(RevokeAndAckTlv.ChannelData.tag to RevokeAndAckTlv.ChannelData.Companion as TlvValueReader<RevokeAndAckTlv>)

        override fun read(input: Input): RevokeAndAck {
            return RevokeAndAck(
                ByteVector32(LightningCodecs.bytes(input, 32)),
                PrivateKey(LightningCodecs.bytes(input, 32)),
                PublicKey(LightningCodecs.bytes(input, 33)),
                TlvStreamSerializer(false, readers).read(input)
            )
        }
    }
}

@Serializable
data class UpdateFee(
    @Contextual override val channelId: ByteVector32,
    val feeratePerKw: FeeratePerKw
) : ChannelMessage, UpdateMessage, HasChannelId {
    override val type: Long get() = UpdateFee.type

    override fun write(out: Output) {
        LightningCodecs.writeBytes(channelId, out)
        LightningCodecs.writeU32(feeratePerKw.feerate.toLong().toInt(), out)
    }

    companion object : LightningMessageReader<UpdateFee> {
        const val type: Long = 134

        override fun read(input: Input): UpdateFee {
            return UpdateFee(
                ByteVector32(LightningCodecs.bytes(input, 32)),
                FeeratePerKw(LightningCodecs.u32(input).toLong().sat)
            )
        }
    }
}

@Serializable
data class ChannelReestablish(
    @Contextual override val channelId: ByteVector32,
    val nextLocalCommitmentNumber: Long,
    val nextRemoteRevocationNumber: Long,
    @Contextual val yourLastCommitmentSecret: PrivateKey,
    @Contextual val myCurrentPerCommitmentPoint: PublicKey,
    val tlvStream: TlvStream<ChannelReestablishTlv> = TlvStream.empty()
) : HasChannelId, HasEncryptedChannelData {
    override val type: Long get() = ChannelReestablish.type

    override val channelData: EncryptedChannelData get() = tlvStream.get<ChannelReestablishTlv.ChannelData>()?.ecb ?: EncryptedChannelData.empty
    override fun withNonEmptyChannelData(ecd: EncryptedChannelData): ChannelReestablish = copy(tlvStream = tlvStream.addOrUpdate(ChannelReestablishTlv.ChannelData(ecd)))

    override fun write(out: Output) {
        LightningCodecs.writeBytes(channelId, out)
        LightningCodecs.writeU64(nextLocalCommitmentNumber, out)
        LightningCodecs.writeU64(nextRemoteRevocationNumber, out)
        LightningCodecs.writeBytes(yourLastCommitmentSecret.value, out)
        LightningCodecs.writeBytes(myCurrentPerCommitmentPoint.value, out)
        TlvStreamSerializer(false, readers).write(tlvStream, out)
    }

    companion object : LightningMessageReader<ChannelReestablish> {
        const val type: Long = 136

        @Suppress("UNCHECKED_CAST")
        val readers = mapOf(ChannelReestablishTlv.ChannelData.tag to ChannelReestablishTlv.ChannelData.Companion as TlvValueReader<ChannelReestablishTlv>)

        override fun read(input: Input): ChannelReestablish {
            return ChannelReestablish(
                ByteVector32(LightningCodecs.bytes(input, 32)),
                LightningCodecs.u64(input),
                LightningCodecs.u64(input),
                PrivateKey(LightningCodecs.bytes(input, 32)),
                PublicKey(LightningCodecs.bytes(input, 33)),
                TlvStreamSerializer(false, readers).read(input)
            )
        }
    }
}

data class AnnouncementSignatures(
    override val channelId: ByteVector32,
    val shortChannelId: ShortChannelId,
    val nodeSignature: ByteVector64,
    val bitcoinSignature: ByteVector64
) : RoutingMessage, HasChannelId {
    override val type: Long get() = AnnouncementSignatures.type

    override fun write(out: Output) {
        LightningCodecs.writeBytes(channelId, out)
        LightningCodecs.writeU64(shortChannelId.toLong(), out)
        LightningCodecs.writeBytes(nodeSignature, out)
        LightningCodecs.writeBytes(bitcoinSignature, out)
    }

    companion object : LightningMessageReader<AnnouncementSignatures> {
        const val type: Long = 259

        override fun read(input: Input): AnnouncementSignatures {
            return AnnouncementSignatures(
                ByteVector32(LightningCodecs.bytes(input, 32)),
                ShortChannelId(LightningCodecs.u64(input)),
                ByteVector64(LightningCodecs.bytes(input, 64)),
                ByteVector64(LightningCodecs.bytes(input, 64))
            )
        }
    }
}

@Serializable
data class ChannelAnnouncement(
    @Contextual val nodeSignature1: ByteVector64,
    @Contextual val nodeSignature2: ByteVector64,
    @Contextual val bitcoinSignature1: ByteVector64,
    @Contextual val bitcoinSignature2: ByteVector64,
    val features: Features,
    @Contextual override val chainHash: ByteVector32,
    val shortChannelId: ShortChannelId,
    @Contextual val nodeId1: PublicKey,
    @Contextual val nodeId2: PublicKey,
    @Contextual val bitcoinKey1: PublicKey,
    @Contextual val bitcoinKey2: PublicKey,
    @Contextual val unknownFields: ByteVector = ByteVector.empty
) : AnnouncementMessage, HasChainHash {
    override val type: Long get() = ChannelAnnouncement.type

    override fun write(out: Output) {
        LightningCodecs.writeBytes(nodeSignature1, out)
        LightningCodecs.writeBytes(nodeSignature2, out)
        LightningCodecs.writeBytes(bitcoinSignature1, out)
        LightningCodecs.writeBytes(bitcoinSignature2, out)
        val featureBytes = features.toByteArray()
        LightningCodecs.writeU16(featureBytes.size, out)
        LightningCodecs.writeBytes(featureBytes, out)
        LightningCodecs.writeBytes(chainHash, out)
        LightningCodecs.writeU64(shortChannelId.toLong(), out)
        LightningCodecs.writeBytes(nodeId1.value, out)
        LightningCodecs.writeBytes(nodeId2.value, out)
        LightningCodecs.writeBytes(bitcoinKey1.value, out)
        LightningCodecs.writeBytes(bitcoinKey2.value, out)
        LightningCodecs.writeBytes(unknownFields, out)
    }

    companion object : LightningMessageReader<ChannelAnnouncement> {
        const val type: Long = 256

        override fun read(input: Input): ChannelAnnouncement {
            val nodeSignature1 = LightningCodecs.bytes(input, 64).toByteVector64()
            val nodeSignature2 = LightningCodecs.bytes(input, 64).toByteVector64()
            val bitcoinSignature1 = LightningCodecs.bytes(input, 64).toByteVector64()
            val bitcoinSignature2 = LightningCodecs.bytes(input, 64).toByteVector64()
            val featureBytes = LightningCodecs.bytes(input, LightningCodecs.u16(input))
            val chainHash = LightningCodecs.bytes(input, 32).toByteVector32()
            val shortChannelId = ShortChannelId(LightningCodecs.u64(input))
            val nodeId1 = PublicKey(LightningCodecs.bytes(input, 33))
            val nodeId2 = PublicKey(LightningCodecs.bytes(input, 33))
            val bitcoinKey1 = PublicKey(LightningCodecs.bytes(input, 33))
            val bitcoinKey2 = PublicKey(LightningCodecs.bytes(input, 33))
            val unknownBytes = if (input.availableBytes > 0) LightningCodecs.bytes(input, input.availableBytes).toByteVector() else ByteVector.empty
            return ChannelAnnouncement(
                nodeSignature1,
                nodeSignature2,
                bitcoinSignature1,
                bitcoinSignature2,
                Features(featureBytes),
                chainHash,
                shortChannelId,
                nodeId1,
                nodeId2,
                bitcoinKey1,
                bitcoinKey2,
                unknownBytes
            )
        }
    }
}

@Serializable
data class ChannelUpdate(
    @Contextual val signature: ByteVector64,
    @Contextual override val chainHash: ByteVector32,
    val shortChannelId: ShortChannelId,
    override val timestampSeconds: Long,
    val messageFlags: Byte,
    val channelFlags: Byte,
    val cltvExpiryDelta: CltvExpiryDelta,
    val htlcMinimumMsat: MilliSatoshi,
    val feeBaseMsat: MilliSatoshi,
    val feeProportionalMillionths: Long,
    val htlcMaximumMsat: MilliSatoshi?,
    @Contextual val unknownFields: ByteVector = ByteVector.empty
) : AnnouncementMessage, HasTimestamp, HasChainHash {
    init {
        require(((messageFlags.toInt() and 1) != 0) == (htlcMaximumMsat != null)) { "htlcMaximumMsat is not consistent with messageFlags" }
    }

    override val type: Long get() = ChannelUpdate.type

    /** BOLT 7: The creating node [...] MUST set the direction bit of flags to 0 if the creating node is node-id-1 in that message, otherwise 1. */
    fun isNode1(): Boolean = (channelFlags.toInt() and 1) == 0

    /** BOLT 7: A node MAY create and send a channel_update with the disable bit set to signal the temporary unavailability of a channel */
    fun isEnabled(): Boolean = (channelFlags.toInt() and 2) == 0

    fun isRemote(localNodeId: PublicKey, remoteNodeId: PublicKey): Boolean = isNode1() != Announcements.isNode1(localNodeId, remoteNodeId)

    override fun write(out: Output) {
        LightningCodecs.writeBytes(signature, out)
        LightningCodecs.writeBytes(chainHash, out)
        LightningCodecs.writeU64(shortChannelId.toLong(), out)
        LightningCodecs.writeU32(timestampSeconds.toInt(), out)
        LightningCodecs.writeByte(messageFlags.toInt(), out)
        LightningCodecs.writeByte(channelFlags.toInt(), out)
        LightningCodecs.writeU16(cltvExpiryDelta.toInt(), out)
        LightningCodecs.writeU64(htlcMinimumMsat.toLong(), out)
        LightningCodecs.writeU32(feeBaseMsat.toLong().toInt(), out)
        LightningCodecs.writeU32(feeProportionalMillionths.toInt(), out)
        if (htlcMaximumMsat != null) {
            LightningCodecs.writeU64(htlcMaximumMsat.toLong(), out)
        }
        LightningCodecs.writeBytes(unknownFields, out)
    }

    companion object : LightningMessageReader<ChannelUpdate> {
        const val type: Long = 258

        override fun read(input: Input): ChannelUpdate {
            val signature = ByteVector64(LightningCodecs.bytes(input, 64))
            val chainHash = ByteVector32(LightningCodecs.bytes(input, 32))
            val shortChannelId = ShortChannelId(LightningCodecs.u64(input))
            val timestampSeconds = LightningCodecs.u32(input).toLong()
            val messageFlags = LightningCodecs.byte(input).toByte()
            val channelFlags = LightningCodecs.byte(input).toByte()
            val cltvExpiryDelta = CltvExpiryDelta(LightningCodecs.u16(input))
            val htlcMinimumMsat = MilliSatoshi(LightningCodecs.u64(input))
            val feeBaseMsat = MilliSatoshi(LightningCodecs.u32(input).toLong())
            val feeProportionalMillionths = LightningCodecs.u32(input).toLong()
            val htlcMaximumMsat = if ((messageFlags.toInt() and 1) != 0) MilliSatoshi(LightningCodecs.u64(input)) else null
            val unknownBytes = if (input.availableBytes > 0) LightningCodecs.bytes(input, input.availableBytes).toByteVector() else ByteVector.empty
            return ChannelUpdate(
                signature,
                chainHash,
                shortChannelId,
                timestampSeconds,
                messageFlags,
                channelFlags,
                cltvExpiryDelta,
                htlcMinimumMsat,
                feeBaseMsat,
                feeProportionalMillionths,
                htlcMaximumMsat,
                unknownBytes
            )
        }
    }
}

@Serializable
data class Shutdown(
    @Contextual override val channelId: ByteVector32,
    @Contextual val scriptPubKey: ByteVector,
    val tlvStream: TlvStream<ShutdownTlv> = TlvStream.empty()
) : ChannelMessage, HasChannelId, HasEncryptedChannelData {
    override val type: Long get() = Shutdown.type

    override val channelData: EncryptedChannelData get() = tlvStream.get<ShutdownTlv.ChannelData>()?.ecb ?: EncryptedChannelData.empty
    override fun withNonEmptyChannelData(ecd: EncryptedChannelData): Shutdown = copy(tlvStream = tlvStream.addOrUpdate(ShutdownTlv.ChannelData(ecd)))

    override fun write(out: Output) {
        LightningCodecs.writeBytes(channelId, out)
        LightningCodecs.writeU16(scriptPubKey.size(), out)
        LightningCodecs.writeBytes(scriptPubKey, out)
        TlvStreamSerializer(false, readers).write(tlvStream, out)
    }

    companion object : LightningMessageReader<Shutdown> {
        const val type: Long = 38

        @Suppress("UNCHECKED_CAST")
        val readers = mapOf(ShutdownTlv.ChannelData.tag to ShutdownTlv.ChannelData.Companion as TlvValueReader<ShutdownTlv>)

        override fun read(input: Input): Shutdown {
            return Shutdown(
                ByteVector32(LightningCodecs.bytes(input, 32)),
                ByteVector(LightningCodecs.bytes(input, LightningCodecs.u16(input))),
                TlvStreamSerializer(false, readers).read(input)
            )
        }
    }
}

@Serializable
data class ClosingSigned(
    @Contextual override val channelId: ByteVector32,
    @Contextual val feeSatoshis: Satoshi,
    @Contextual val signature: ByteVector64,
    val tlvStream: TlvStream<ClosingSignedTlv> = TlvStream.empty()
) : ChannelMessage, HasChannelId, HasEncryptedChannelData {
    override val type: Long get() = ClosingSigned.type

    override val channelData: EncryptedChannelData get() = tlvStream.get<ClosingSignedTlv.ChannelData>()?.ecb ?: EncryptedChannelData.empty
    override fun withNonEmptyChannelData(ecd: EncryptedChannelData): ClosingSigned = copy(tlvStream = tlvStream.addOrUpdate(ClosingSignedTlv.ChannelData(ecd)))

    override fun write(out: Output) {
        LightningCodecs.writeBytes(channelId, out)
        LightningCodecs.writeU64(feeSatoshis.toLong(), out)
        LightningCodecs.writeBytes(signature, out)
        TlvStreamSerializer(false, readers).write(tlvStream, out)
    }

    companion object : LightningMessageReader<ClosingSigned> {
        const val type: Long = 39

        @Suppress("UNCHECKED_CAST")
        val readers = mapOf(
            ClosingSignedTlv.FeeRange.tag to ClosingSignedTlv.FeeRange.Companion as TlvValueReader<ClosingSignedTlv>,
            ClosingSignedTlv.ChannelData.tag to ClosingSignedTlv.ChannelData.Companion as TlvValueReader<ClosingSignedTlv>
        )

        override fun read(input: Input): ClosingSigned {
            return ClosingSigned(
                ByteVector32(LightningCodecs.bytes(input, 32)),
                Satoshi(LightningCodecs.u64(input)),
                ByteVector64(LightningCodecs.bytes(input, 64)),
                TlvStreamSerializer(false, readers).read(input)
            )
        }
    }
}

/**
 * When we don't have enough incoming liquidity to receive a payment, our peer may open a channel to us on-the-fly to carry that payment.
 * This message contains details that allow us to recalculate the fee that our peer will take in exchange for the new channel.
 * This allows us to combine multiple requests for the same payment and figure out the final fee that will be applied.
 *
 * @param chainHash chain we're on.
 * @param fundingSatoshis total capacity of the channel our peer will open to us (some of the funds may be on their side).
 * @param amountMsat payment amount covered by this new channel: we will receive push_msat = amountMsat - fees.
 * @param payToOpenMinAmountMsat minimum amount for a pay-to-open to be attempted, this should be compared to the total amount in the case of an MPP payment.
 * @param payToOpenFeeSatoshis fees that will be deducted from the amount pushed to us (this fee covers the on-chain fees our peer will pay to open the channel).
 * @param paymentHash payment hash.
 * @param expireAt after the proposal expires, our peer will fail the payment and won't open a channel to us.
 * @param finalPacket onion packet that we would have received if there had been a channel to forward the payment to.
 */
data class PayToOpenRequest(
    override val chainHash: ByteVector32,
    val fundingSatoshis: Satoshi,
    val amountMsat: MilliSatoshi,
    val payToOpenMinAmountMsat: MilliSatoshi,
    val payToOpenFeeSatoshis: Satoshi,
    val paymentHash: ByteVector32,
    val expireAt: Long,
    val finalPacket: OnionRoutingPacket
) : LightningMessage, HasChainHash {
    override val type: Long get() = PayToOpenRequest.type

    override fun write(out: Output) {
        LightningCodecs.writeBytes(chainHash, out)
        LightningCodecs.writeU64(fundingSatoshis.toLong(), out)
        LightningCodecs.writeU64(amountMsat.toLong(), out)
        LightningCodecs.writeU64(payToOpenMinAmountMsat.toLong(), out)
        LightningCodecs.writeU64(payToOpenFeeSatoshis.toLong(), out)
        LightningCodecs.writeBytes(paymentHash, out)
        LightningCodecs.writeU32(expireAt.toInt(), out)
        LightningCodecs.writeU16(finalPacket.payload.size(), out)
        OnionRoutingPacketSerializer(finalPacket.payload.size()).write(finalPacket, out)
    }

    companion object : LightningMessageReader<PayToOpenRequest> {
        const val type: Long = 35021

        override fun read(input: Input): PayToOpenRequest {
            return PayToOpenRequest(
                chainHash = ByteVector32(LightningCodecs.bytes(input, 32)),
                fundingSatoshis = Satoshi(LightningCodecs.u64(input)),
                amountMsat = MilliSatoshi(LightningCodecs.u64(input)),
                payToOpenMinAmountMsat = MilliSatoshi(LightningCodecs.u64(input)),
                payToOpenFeeSatoshis = Satoshi(LightningCodecs.u64(input)),
                paymentHash = ByteVector32(LightningCodecs.bytes(input, 32)),
                expireAt = LightningCodecs.u32(input).toLong(),
                finalPacket = OnionRoutingPacketSerializer(LightningCodecs.u16(input)).read(input)
            )
        }
    }
}

data class PayToOpenResponse(override val chainHash: ByteVector32, val paymentHash: ByteVector32, val result: Result) : LightningMessage, HasChainHash {
    override val type: Long get() = PayToOpenResponse.type

    sealed class Result {
        // @formatter:off
        data class Success(val paymentPreimage: ByteVector32) : Result()
        /** reason is an onion-encrypted failure message, like those in UpdateFailHtlc */
        data class Failure(val reason: ByteVector?) : Result()
        // @formatter:on
    }

    override fun write(out: Output) {
        LightningCodecs.writeBytes(chainHash, out)
        LightningCodecs.writeBytes(paymentHash, out)
        when (result) {
            is Result.Success -> LightningCodecs.writeBytes(result.paymentPreimage, out)
            is Result.Failure -> {
                LightningCodecs.writeBytes(ByteVector32.Zeroes, out) // this is for backward compatibility
                result.reason?.let {
                    LightningCodecs.writeU16(it.size(), out)
                    LightningCodecs.writeBytes(it, out)
                }
            }
        }
    }

    companion object : LightningMessageReader<PayToOpenResponse> {
        const val type: Long = 35003

        override fun read(input: Input): PayToOpenResponse {
            val chainHash = LightningCodecs.bytes(input, 32).toByteVector32()
            val paymentHash = LightningCodecs.bytes(input, 32).toByteVector32()
            return when (val preimage = LightningCodecs.bytes(input, 32).toByteVector32()) {
                ByteVector32.Zeroes -> {
                    val failure = if (input.availableBytes > 0) LightningCodecs.bytes(input, LightningCodecs.u16(input)).toByteVector() else null
                    PayToOpenResponse(chainHash, paymentHash, Result.Failure(failure))
                }
                else -> PayToOpenResponse(chainHash, paymentHash, Result.Success(preimage))
            }
        }
    }
}

@Serializable
data class FCMToken(@Contextual val token: ByteVector) : LightningMessage {
    constructor(token: String) : this(ByteVector(token.encodeToByteArray()))

    override val type: Long get() = FCMToken.type

    override fun write(out: Output) {
        LightningCodecs.writeU16(token.size(), out)
        LightningCodecs.writeBytes(token, out)
    }

    fun toAscii(): String = token.toByteArray().decodeToString()

    companion object : LightningMessageReader<FCMToken> {
        const val type: Long = 35017

        override fun read(input: Input): FCMToken {
            return FCMToken(LightningCodecs.bytes(input, LightningCodecs.u16(input)).toByteVector())
        }
    }
}

@Serializable
object UnsetFCMToken : LightningMessage {
    override val type: Long get() = 35019
    override fun write(out: Output) {}
}

data class SwapInRequest(
    override val chainHash: ByteVector32
) : LightningMessage, HasChainHash {
    override val type: Long get() = SwapInRequest.type

    override fun write(out: Output) {
        LightningCodecs.writeBytes(chainHash, out)
    }

    companion object : LightningMessageReader<SwapInRequest> {
        const val type: Long = 35007

        override fun read(input: Input): SwapInRequest {
            return SwapInRequest(LightningCodecs.bytes(input, 32).toByteVector32())
        }
    }
}

data class SwapInResponse(
    override val chainHash: ByteVector32,
    val bitcoinAddress: String
) : LightningMessage, HasChainHash {
    override val type: Long get() = SwapInResponse.type

    override fun write(out: Output) {
        LightningCodecs.writeBytes(chainHash, out)
        val addressBytes = bitcoinAddress.encodeToByteArray()
        LightningCodecs.writeU16(addressBytes.size, out)
        LightningCodecs.writeBytes(addressBytes, out)
    }

    companion object : LightningMessageReader<SwapInResponse> {
        const val type: Long = 35009

        override fun read(input: Input): SwapInResponse {
            return SwapInResponse(
                chainHash = ByteVector32(LightningCodecs.bytes(input, 32)),
                bitcoinAddress = LightningCodecs.bytes(input, LightningCodecs.u16(input)).decodeToString()
            )
        }
    }
}

data class SwapInPending(
    val bitcoinAddress: String,
    val amount: Satoshi
) : LightningMessage {
    override val type: Long get() = SwapInPending.type

    override fun write(out: Output) {
        val addressBytes = bitcoinAddress.encodeToByteArray()
        LightningCodecs.writeU16(addressBytes.size, out)
        LightningCodecs.writeBytes(addressBytes, out)
        LightningCodecs.writeU64(amount.toLong(), out)
    }

    companion object : LightningMessageReader<SwapInPending> {
        const val type: Long = 35005

        override fun read(input: Input): SwapInPending {
            return SwapInPending(
                bitcoinAddress = LightningCodecs.bytes(input, LightningCodecs.u16(input)).decodeToString(),
                amount = Satoshi(LightningCodecs.u64(input))
            )
        }
    }
}

data class SwapInConfirmed(
    val bitcoinAddress: String,
    val amount: MilliSatoshi
) : LightningMessage {
    override val type: Long get() = SwapInConfirmed.type

    override fun write(out: Output) {
        val addressBytes = bitcoinAddress.encodeToByteArray()
        LightningCodecs.writeU16(addressBytes.size, out)
        LightningCodecs.writeBytes(addressBytes, out)
        LightningCodecs.writeU64(amount.toLong(), out)
    }

    companion object : LightningMessageReader<SwapInConfirmed> {
        const val type: Long = 35015

        override fun read(input: Input): SwapInConfirmed {
            return SwapInConfirmed(
                bitcoinAddress = LightningCodecs.bytes(input, LightningCodecs.u16(input)).decodeToString(),
                amount = MilliSatoshi(LightningCodecs.u64(input))
            )
        }
    }
}

data class SwapOutRequest(
    override val chainHash: ByteVector32,
    val amount: Satoshi,
    val bitcoinAddress: String,
    val feePerKw: Long
) : LightningMessage, HasChainHash {
    override val type: Long get() = SwapOutRequest.type

    override fun write(out: Output) {
        LightningCodecs.writeBytes(chainHash, out)
        LightningCodecs.writeU64(amount.toLong(), out)
        val addressBytes = bitcoinAddress.encodeToByteArray()
        LightningCodecs.writeU16(addressBytes.size, out)
        LightningCodecs.writeBytes(addressBytes, out)
        LightningCodecs.writeU32(feePerKw.toInt(), out)
    }

    companion object : LightningMessageReader<SwapOutRequest> {
        const val type: Long = 35011

        override fun read(input: Input): SwapOutRequest {
            return SwapOutRequest(
                chainHash = LightningCodecs.bytes(input, 32).toByteVector32(),
                amount = Satoshi(LightningCodecs.u64(input)),
                bitcoinAddress = LightningCodecs.bytes(input, LightningCodecs.u16(input)).decodeToString(),
                feePerKw = LightningCodecs.u32(input).toLong(),
            )
        }
    }
}

data class SwapOutResponse(
    override val chainHash: ByteVector32,
    val amount: Satoshi,
    val fee: Satoshi,
    val paymentRequest: String
) : LightningMessage, HasChainHash {
    override val type: Long get() = SwapOutResponse.type

    override fun write(out: Output) {
        LightningCodecs.writeBytes(chainHash, out)
        LightningCodecs.writeU64(amount.toLong(), out)
        LightningCodecs.writeU64(fee.toLong(), out)
        val paymentRequestBytes = paymentRequest.encodeToByteArray()
        LightningCodecs.writeU16(paymentRequestBytes.size, out)
        LightningCodecs.writeBytes(paymentRequestBytes, out)
    }

    companion object : LightningMessageReader<SwapOutResponse> {
        const val type: Long = 35013

        override fun read(input: Input): SwapOutResponse {
            return SwapOutResponse(
                chainHash = ByteVector32(LightningCodecs.bytes(input, 32)),
                amount = Satoshi(LightningCodecs.u64(input)),
                fee = Satoshi(LightningCodecs.u64(input)),
                paymentRequest = LightningCodecs.bytes(input, LightningCodecs.u16(input)).decodeToString()
            )
        }
    }
}

data class PhoenixAndroidLegacyInfo(
    val hasChannels: Boolean
) : LightningMessage {
    override val type: Long get() = PhoenixAndroidLegacyInfo.type

    override fun write(out: Output) {
        LightningCodecs.writeByte(if (hasChannels) 0xff else 0, out)
    }

    companion object : LightningMessageReader<PhoenixAndroidLegacyInfo> {
        const val type: Long = 35023

        override fun read(input: Input): PhoenixAndroidLegacyInfo {
            return PhoenixAndroidLegacyInfo(LightningCodecs.byte(input) != 0)
        }
    }
}

data class PhoenixAndroidLegacyMigrate(
    val newNodeId: PublicKey
) : LightningMessage {
    override val type: Long get() = PhoenixAndroidLegacyMigrate.type

    override fun write(out: Output) {
        LightningCodecs.writeBytes(newNodeId.value, out)
    }

    companion object : LightningMessageReader<PhoenixAndroidLegacyMigrate> {
        const val type: Long = 35025

        override fun read(input: Input): PhoenixAndroidLegacyMigrate {
            return PhoenixAndroidLegacyMigrate(PublicKey(LightningCodecs.bytes(input, 33)))
        }
    }
}

@Serializable
data class OnionMessage(
    @Contextual val blindingKey: PublicKey,
    val onionRoutingPacket: OnionRoutingPacket
) : LightningMessage {
    override val type: Long get() = OnionMessage.type

    override fun write(out: Output) {
        LightningCodecs.writeBytes(blindingKey.value, out)
        LightningCodecs.writeU16(onionRoutingPacket.payload.size(), out)
        OnionRoutingPacketSerializer(onionRoutingPacket.payload.size()).write(onionRoutingPacket, out)
    }

    companion object : LightningMessageReader<OnionMessage> {
        const val type: Long = 513

        override fun read(input: Input): OnionMessage {
            val blindingKey = PublicKey(LightningCodecs.bytes(input, 33))
            val onion = OnionRoutingPacketSerializer(LightningCodecs.u16(input) - 66).read(input)
            return OnionMessage(blindingKey, onion)
        }
    }
}

data class UnknownMessage(
    override val type: Long

) : LightningMessage {
    override fun write(out: Output) {
        TODO("Serialization of unknown messages is not implemented")
    }

}