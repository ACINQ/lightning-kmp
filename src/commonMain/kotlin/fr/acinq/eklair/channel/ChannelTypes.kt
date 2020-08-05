package fr.acinq.eklair.channel

import fr.acinq.bitcoin.*
import fr.acinq.eklair.CltvExpiry
import fr.acinq.eklair.CltvExpiryDelta
import fr.acinq.eklair.Features
import fr.acinq.eklair.MilliSatoshi
import fr.acinq.eklair.utils.BitField
import fr.acinq.eklair.utils.UUID
import fr.acinq.eklair.utils.sum
import fr.acinq.eklair.wire.FailureMessage
import fr.acinq.eklair.wire.OnionRoutingPacket
import fr.acinq.eklair.wire.UpdateAddHtlc
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

sealed class Upstream {
    /** Our node is the origin of the payment. */
    data class Local(val id: UUID) : Upstream()
    /** Our node forwarded a single incoming HTLC to an outgoing channel. */
    data class Relayed(val add: UpdateAddHtlc) : Upstream()
    /** Our node forwarded an incoming HTLC set to a remote outgoing node (potentially producing multiple downstream HTLCs). */
    data class TrampolineRelayed(val adds: List<UpdateAddHtlc>) : Upstream() {
        val amountIn: MilliSatoshi = adds.map { it.amountMsat }.sum()
        val expiryIn: CltvExpiry = adds.map { it.cltvExpiry }.minOrNull()!!
    }
}

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
data class CMD_ADD_HTLC(val amount: MilliSatoshi, val paymentHash: ByteVector32, val cltvExpiry: CltvExpiry, val onion: OnionRoutingPacket, val upstream: Upstream, val commit: Boolean = false, val previousFailures: List<AddHtlcFailed> = emptyList()) : Command()
data class CMD_UPDATE_FEE(val feeratePerKw: Long, val commit: Boolean = false) : Command()
object CMD_SIGN : Command()
data class CMD_CLOSE(val scriptPubKey: ByteVector?) : Command()
data class CMD_UPDATE_RELAY_FEE(val feeBase: MilliSatoshi, val feeProportionalMillionths: Long) : Command()
object CMD_FORCECLOSE : Command()
object CMD_GETSTATE : Command()
object CMD_GETSTATEDATA : Command()


@OptIn(ExperimentalUnsignedTypes::class)
@Serializable
data class LocalParams constructor(
    val nodeId: PublicKey,
    val fundingKeyPath: KeyPath,
    val dustLimit: Satoshi,
    val maxHtlcValueInFlightMsat: Long, // this is not MilliSatoshi because it can exceed the total amount of MilliSatoshi
    val channelReserve: Satoshi,
    val htlcMinimum: MilliSatoshi,
    val toSelfDelay: CltvExpiryDelta,
    val maxAcceptedHtlcs: Int,
    val isFunder: Boolean,
    val defaultFinalScriptPubKey: ByteVector,
    val localPaymentBasepoint: PublicKey?,
    val features: Features
)

@OptIn(ExperimentalUnsignedTypes::class)
@Serializable
data class RemoteParams(
    val nodeId: PublicKey,
    val dustLimit: Satoshi,
    val maxHtlcValueInFlightMsat: Long, // this is not MilliSatoshi because it can exceed the total amount of MilliSatoshi
    val channelReserve: Satoshi,
    val htlcMinimum: MilliSatoshi,
    val toSelfDelay: CltvExpiryDelta,
    val maxAcceptedHtlcs: Int,
    val fundingPubKey: PublicKey,
    val revocationBasepoint: PublicKey,
    val paymentBasepoint: PublicKey,
    val delayedPaymentBasepoint: PublicKey,
    val htlcBasepoint: PublicKey,
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