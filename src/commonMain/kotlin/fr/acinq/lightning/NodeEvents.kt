package fr.acinq.lightning

import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.Satoshi
import fr.acinq.lightning.channel.states.ChannelStateWithCommitments
import fr.acinq.lightning.channel.states.Normal
import fr.acinq.lightning.channel.states.WaitForFundingCreated
import fr.acinq.lightning.wire.PleaseOpenChannel
import kotlinx.coroutines.CompletableDeferred

sealed interface NodeEvents

sealed interface SwapInEvents : NodeEvents {
    data class Requested(val req: PleaseOpenChannel) : SwapInEvents
    data class Accepted(val requestId: ByteVector32, val serviceFee: MilliSatoshi, val miningFee: Satoshi) : SwapInEvents
}

sealed interface ChannelEvents : NodeEvents {
    data class Creating(val state: WaitForFundingCreated) : ChannelEvents
    data class Created(val state: ChannelStateWithCommitments) : ChannelEvents
    data class Confirmed(val state: Normal) : ChannelEvents
}

sealed interface LiquidityEvents : NodeEvents {
    val amount: MilliSatoshi
    val fee: MilliSatoshi
    val source: Source

    sealed class Source {
        data class OffChainPayment(val paymentHash: ByteVector32) : Source()
        object OnChainWallet : Source()
    }

    data class Rejected(override val amount: MilliSatoshi, override val fee: MilliSatoshi, override val source: Source, val reason: Reason) : LiquidityEvents {
        sealed class Reason {
            object PolicySetToDisabled : Reason()
            sealed class TooExpensive : Reason() {
                data class OverAbsoluteFee(val maxAbsoluteFee: Satoshi) : TooExpensive()
                data class OverRelativeFee(val maxRelativeFeeBasisPoints: Int) : TooExpensive()
            }
            object ChannelInitializing : Reason()
        }
    }

    data class ApprovalRequested(override val amount: MilliSatoshi, override val fee: MilliSatoshi, override val source: Source, val replyTo: CompletableDeferred<Boolean>) : LiquidityEvents
}

/** This will be emitted in a corner case where the user restores a wallet on an older version of the app, which is unable to read the channel data. */
object UpgradeRequired : NodeEvents
