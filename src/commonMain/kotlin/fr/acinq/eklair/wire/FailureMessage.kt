package fr.acinq.eklair.wire

import fr.acinq.bitcoin.ByteVector32
import fr.acinq.eklair.CltvExpiry
import fr.acinq.eklair.MilliSatoshi


interface FailureMessage {
    val message: String
}
interface BadOnion : FailureMessage { val onionHash: ByteVector32 }
interface Perm : FailureMessage
interface Node : FailureMessage
interface Update : FailureMessage { val update: ChannelUpdate }

sealed class AbstractFailureMessage : FailureMessage {
    val code: Int by lazy { 0 } // TODO: This cannot go into prod!
}

object InvalidRealm : Perm { override val message get() = "realm was not understood by the processing node" }
object TemporaryNodeFailure : Node { override val message get() = "general temporary failure of the processing node" }
object PermanentNodeFailure : Perm, Node { override val message get() = "general permanent failure of the processing node" }
object RequiredNodeFeatureMissing : Perm, Node { override val message get() = "processing node requires features that are missing from this onion" }
data class InvalidOnionVersion(override val onionHash: ByteVector32) : BadOnion, Perm { override val message get() = "onion version was not understood by the processing node" }
data class InvalidOnionHmac(override val onionHash: ByteVector32) : BadOnion, Perm { override val message get() = "onion HMAC was incorrect when it reached the processing node" }
data class InvalidOnionKey(override val onionHash: ByteVector32) : BadOnion, Perm { override val message get() = "ephemeral key was unparsable by the processing node" }
data class TemporaryChannelFailure(override val update: ChannelUpdate) : Update { override val message get() = "channel ${update.shortChannelId} is currently unavailable" }
object PermanentChannelFailure : Perm { override val message get() = "channel is permanently unavailable" }
object RequiredChannelFeatureMissing : Perm { override val message get() = "channel requires features not present in the onion" }
object UnknownNextPeer : Perm { override val message get() = "processing node does not know the next peer in the route" }
data class AmountBelowMinimum(val amount: MilliSatoshi, override val update: ChannelUpdate) : Update { override val message get() = "payment amount was below the minimum required by the channel" }
data class FeeInsufficient(val amount: MilliSatoshi, override val update: ChannelUpdate) : Update { override val message get() = "payment fee was below the minimum required by the channel" }
object TrampolineFeeInsufficient : Node { override val message get() = "payment fee was below the minimum required by the trampoline node" }
data class ChannelDisabled(val messageFlags: Byte, val channelFlags: Byte, override val update: ChannelUpdate) : Update { override val message get() = "channel is currently disabled" }
data class IncorrectCltvExpiry(val expiry: CltvExpiry, override val update: ChannelUpdate) : Update { override val message get() = "payment expiry doesn't match the value in the onion" }
data class IncorrectOrUnknownPaymentDetails(val amount: MilliSatoshi, val height: Long) : Perm { override val message get() = "incorrect payment details or unknown payment hash" }
data class ExpiryTooSoon(override val update: ChannelUpdate) : Update { override val message get() = "payment expiry is too close to the current block height for safe handling by the relaying node" }
object TrampolineExpiryTooSoon : Node { override val message get() = "payment expiry is too close to the current block height for safe handling by the relaying node" }
data class FinalIncorrectCltvExpiry(val expiry: CltvExpiry) : FailureMessage { override val message get() = "payment expiry doesn't match the value in the onion" }
data class FinalIncorrectHtlcAmount(val amount: MilliSatoshi) : FailureMessage { override val message get() = "payment amount is incorrect in the final htlc" }
object ExpiryTooFar : FailureMessage { override val message get() = "payment expiry is too far in the future" }
@OptIn(ExperimentalUnsignedTypes::class)
data class InvalidOnionPayload(val tag: ULong, val offset: Int) : Perm { override val message get() = "onion per-hop payload is invalid" }
object PaymentTimeout : FailureMessage { override val message get() = "the complete payment amount was not received within a reasonable time" }

/**
 * We allow remote nodes to send us unknown failure codes (e.g. deprecated failure codes).
 * By reading the PERM and NODE bits we can still extract useful information for payment retry even without knowing how
 * to decode the failure payload (but we can't extract a channel update or onion hash).
 */
sealed class UnknownFailureMessage : AbstractFailureMessage() {
    override val message get() = "unknown failure message"
    override fun toString()= message
    override fun equals(other: Any?): Boolean {
        return (other as? UnknownFailureMessage)?.code == code
    }
}

object FailureMessageCodecs {
    val BADONION = 0x8000
}
