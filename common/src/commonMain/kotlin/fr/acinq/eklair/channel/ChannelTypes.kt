package fr.acinq.eklair.channel

import fr.acinq.bitcoin.*
import fr.acinq.eklair.CltvExpiryDelta
import fr.acinq.eklair.Features
import fr.acinq.eklair.MilliSatoshi
import fr.acinq.eklair.utils.BitField
import kotlinx.serialization.InternalSerializationApi


@OptIn(ExperimentalUnsignedTypes::class, InternalSerializationApi::class)
data class LocalParams constructor(
    val nodeId: PublicKey,
    val fundingKeyPath: KeyPath,
    val dustLimit: Satoshi,
    val maxHtlcValueInFlightMsat: ULong, // this is not MilliSatoshi because it can exceed the total amount of MilliSatoshi
    val channelReserve: Satoshi,
    val htlcMinimum: MilliSatoshi,
    val toSelfDelay: CltvExpiryDelta,
    val maxAcceptedHtlcs: Int,
    val isFunder: Boolean,
    val defaultFinalScriptPubKey: ByteVector,
    val features: Features
)

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

        fun fromBit(bit: Int) = ChannelVersion(BitField(SIZE_BYTE).apply { setRight(bit) })

        val USE_PUBKEY_KEYPATH = fromBit(USE_PUBKEY_KEYPATH_BIT)

        val STANDARD = ZEROES or USE_PUBKEY_KEYPATH
    }
}
