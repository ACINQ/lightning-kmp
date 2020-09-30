package fr.acinq.eclair.channel

import fr.acinq.bitcoin.*
import fr.acinq.eclair.CltvExpiry
import fr.acinq.eclair.CltvExpiryDelta
import fr.acinq.eclair.Features
import fr.acinq.eclair.MilliSatoshi
import fr.acinq.eclair.io.*
import fr.acinq.eclair.utils.BitField
import fr.acinq.eclair.utils.UUID
import fr.acinq.eclair.utils.sum
import fr.acinq.eclair.wire.ClosingSigned
import fr.acinq.eclair.wire.FailureMessage
import fr.acinq.eclair.wire.OnionRoutingPacket
import fr.acinq.eclair.wire.UpdateAddHtlc
import kotlinx.serialization.Serializable


/*
       .d8888b.   .d88888b.  888b     d888 888b     d888        d8888 888b    888 8888888b.   .d8888b.
      d88P  Y88b d88P" "Y88b 8888b   d8888 8888b   d8888       d88888 8888b   888 888  "Y88b d88P  Y88b
      888    888 888     888 88888b.d88888 88888b.d88888      d88P888 88888b  888 888    888 Y88b.
      888        888     888 888Y88888P888 888Y88888P888     d88P 888 888Y88b 888 888    888  "Y888b.
      888        888     888 888 Y888P 888 888 Y888P 888    d88P  888 888 Y88b888 888    888     "Y88b.
      888    888 888     888 888  Y8P  888 888  Y8P  888   d88P   888 888  Y88888 888    888       "888
      Y88b  d88P Y88b. .d88P 888   "   888 888   "   888  d8888888888 888   Y8888 888  .d88P Y88b  d88P
       "Y8888P"   "Y88888P"  888       888 888       888 d88P     888 888    Y888 8888888P"   "Y8888P"
 */

sealed class Command
sealed class HasHtlcId : Command() { abstract val id: Long }
data class CMD_FULFILL_HTLC(override val id: Long, val r: ByteVector32, val commit: Boolean = false) : HasHtlcId()
data class CMD_FAIL_HTLC(override val id: Long, val reason: Reason, val commit: Boolean = false) : HasHtlcId() {
    sealed class Reason {
        data class Bytes(val bytes: ByteVector): Reason()
        data class Failure(val message: FailureMessage): Reason()
    }
}
data class CMD_FAIL_MALFORMED_HTLC(override val id: Long, val onionHash: ByteVector32, val failureCode: Int, val commit: Boolean = false) : HasHtlcId()
data class CMD_ADD_HTLC(val amount: MilliSatoshi, val paymentHash: ByteVector32, val cltvExpiry: CltvExpiry, val onion: OnionRoutingPacket, val paymentId: UUID, val commit: Boolean = false, val previousFailures: List<AddHtlcFailed> = emptyList()) : Command()
data class CMD_UPDATE_FEE(val feeratePerKw: Long, val commit: Boolean = false) : Command()
object CMD_SIGN : Command()
data class CMD_CLOSE(val scriptPubKey: ByteVector?) : Command()
data class CMD_UPDATE_RELAY_FEE(val feeBase: MilliSatoshi, val feeProportionalMillionths: Long) : Command()
object CMD_FORCECLOSE : Command()
object CMD_GETSTATE : Command()
object CMD_GETSTATEDATA : Command()


/*
      8888888b.        d8888 88888888888     d8888
      888  "Y88b      d88888     888        d88888
      888    888     d88P888     888       d88P888
      888    888    d88P 888     888      d88P 888
      888    888   d88P  888     888     d88P  888
      888    888  d88P   888     888    d88P   888
      888  .d88P d8888888888     888   d8888888888
      8888888P" d88P     888     888  d88P     888
 */
@Serializable
data class LocalCommitPublished(
    @Serializable(with = TransactionKSerializer::class)
    val commitTx: Transaction,
    @Serializable(with = TransactionKSerializer::class)
    val claimMainDelayedOutputTx: Transaction? = null,
    val htlcSuccessTxs: List<@Serializable(with = TransactionKSerializer::class) Transaction> = emptyList(),
    val htlcTimeoutTxs: List<@Serializable(with = TransactionKSerializer::class) Transaction> = emptyList(),
    val claimHtlcDelayedTxs: List<@Serializable(with = TransactionKSerializer::class) Transaction> = emptyList(),
    val irrevocablySpent: Map<@Serializable(with = OutPointKSerializer::class) OutPoint, @Serializable(with = ByteVectorKSerializer::class) ByteVector32> = emptyMap()
)

@Serializable
data class RemoteCommitPublished(
    @Serializable(with = TransactionKSerializer::class)
    val commitTx: Transaction,
    @Serializable(with = TransactionKSerializer::class)
    val claimMainOutputTx: Transaction? = null,
    val claimHtlcSuccessTxs: List<@Serializable(with = TransactionKSerializer::class) Transaction> = emptyList(),
    val claimHtlcTimeoutTxs: List<@Serializable(with = TransactionKSerializer::class) Transaction> = emptyList(),
    val irrevocablySpent: Map<@Serializable(with = OutPointKSerializer::class) OutPoint, @Serializable(with = ByteVectorKSerializer::class) ByteVector32> = emptyMap()
)

@Serializable
data class RevokedCommitPublished(
    @Serializable(with = TransactionKSerializer::class)
    val commitTx: Transaction,
    @Serializable(with = TransactionKSerializer::class)
    val claimMainOutputTx: Transaction? = null,
    @Serializable(with = TransactionKSerializer::class)
    val mainPenaltyTx: Transaction? = null,
    val htlcPenaltyTxs: List<@Serializable(with = TransactionKSerializer::class) Transaction> = emptyList(),
    val claimHtlcDelayedPenaltyTxs: List<@Serializable(with = TransactionKSerializer::class) Transaction> = emptyList(),
    val irrevocablySpent: Map<@Serializable(with = OutPointKSerializer::class) OutPoint, @Serializable(with = ByteVectorKSerializer::class) ByteVector32> = emptyMap()
)

@OptIn(ExperimentalUnsignedTypes::class)
@Serializable
data class LocalParams constructor(
    @Serializable(with = PublicKeyKSerializer::class) val nodeId: PublicKey,
    @Serializable(with = KeyPathKSerializer::class) val fundingKeyPath: KeyPath,
    @Serializable(with = SatoshiKSerializer::class) val dustLimit: Satoshi,
    val maxHtlcValueInFlightMsat: Long, // this is not MilliSatoshi because it can exceed the total amount of MilliSatoshi
    @Serializable(with = SatoshiKSerializer::class) val channelReserve: Satoshi,
    val htlcMinimum: MilliSatoshi,
    val toSelfDelay: CltvExpiryDelta,
    val maxAcceptedHtlcs: Int,
    val isFunder: Boolean,
    @Serializable(with = ByteVectorKSerializer::class) val defaultFinalScriptPubKey: ByteVector,
    @Serializable(with = PublicKeyKSerializer::class) val localPaymentBasepoint: PublicKey?,
    val features: Features
)

@OptIn(ExperimentalUnsignedTypes::class)
@Serializable
data class RemoteParams(
    @Serializable(with = PublicKeyKSerializer::class) val nodeId: PublicKey,
    @Serializable(with = SatoshiKSerializer::class) val dustLimit: Satoshi,
    val maxHtlcValueInFlightMsat: Long, // this is not MilliSatoshi because it can exceed the total amount of MilliSatoshi
    @Serializable(with = SatoshiKSerializer::class) val channelReserve: Satoshi,
    val htlcMinimum: MilliSatoshi,
    val toSelfDelay: CltvExpiryDelta,
    val maxAcceptedHtlcs: Int,
    @Serializable(with = PublicKeyKSerializer::class) val fundingPubKey: PublicKey,
    @Serializable(with = PublicKeyKSerializer::class) val revocationBasepoint: PublicKey,
    @Serializable(with = PublicKeyKSerializer::class) val paymentBasepoint: PublicKey,
    @Serializable(with = PublicKeyKSerializer::class) val delayedPaymentBasepoint: PublicKey,
    @Serializable(with = PublicKeyKSerializer::class) val htlcBasepoint: PublicKey,
    val features: Features
)

@Serializable
data class ChannelVersion(val bits: BitField) {
    init {
        require(bits.byteSize == SIZE_BYTE) { "channel version takes 4 bytes" }
    }

    infix fun or(other: ChannelVersion) = ChannelVersion(bits or other.bits)
    infix fun and(other: ChannelVersion) = ChannelVersion(bits and other.bits)
    infix fun xor(other: ChannelVersion) = ChannelVersion(bits xor other.bits)

    // TODO: This is baaad performance! The copy needs to be optimized out.
    fun isSet(bit: Int) = bits.getRight(bit)

    companion object {
        val SIZE_BYTE = 4
        val ZEROES = ChannelVersion(BitField(SIZE_BYTE))
        val USE_PUBKEY_KEYPATH_BIT = 0 // bit numbers start at 0
        val USE_STATIC_REMOTEKEY_BIT = 1
        val ZERO_RESERVE_BIT = 3

        fun fromBit(bit: Int) = ChannelVersion(BitField(SIZE_BYTE).apply { setRight(bit) })

        val USE_PUBKEY_KEYPATH = fromBit(USE_PUBKEY_KEYPATH_BIT)
        val USE_STATIC_REMOTEKEY = fromBit(USE_STATIC_REMOTEKEY_BIT)
        val ZERO_RESERVE = fromBit(ZERO_RESERVE_BIT)

        val STANDARD = ZEROES or USE_PUBKEY_KEYPATH
        val STATIC_REMOTEKEY = STANDARD or USE_STATIC_REMOTEKEY // USE_PUBKEY_KEYPATH + USE_STATIC_REMOTEKEY
    }
}

object ChannelFlags {
    val AnnounceChannel = 0x01.toByte()
    val Empty = 0x00.toByte()
}

@Serializable
data class ClosingTxProposed(@Serializable(with = TransactionKSerializer::class) val unsignedTx: Transaction, val localClosingSigned: ClosingSigned)
