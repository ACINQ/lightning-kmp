package fr.acinq.lightning

import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.Satoshi
import fr.acinq.lightning.channel.InteractiveTxParams
import fr.acinq.lightning.channel.SharedFundingInput
import fr.acinq.lightning.channel.states.ChannelStateWithCommitments
import fr.acinq.lightning.channel.states.Normal
import fr.acinq.lightning.channel.states.WaitForFundingCreated
import fr.acinq.lightning.db.IncomingPayment
import fr.acinq.lightning.utils.sum
import fr.acinq.lightning.wire.Node
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

    enum class Source { OnChainWallet, OffChainPayment }

    sealed interface Decision : LiquidityEvents {
        data class Rejected(override val amount: MilliSatoshi, override val fee: MilliSatoshi, override val source: Source, val reason: Reason) : Decision {
            sealed class Reason {
                data object PolicySetToDisabled : Reason()
                sealed class TooExpensive : Reason() {
                    data class OverAbsoluteFee(val maxAbsoluteFee: Satoshi) : TooExpensive()
                    data class OverRelativeFee(val maxRelativeFeeBasisPoints: Int) : TooExpensive()
                }
                data class OverMaxCredit(val maxAllowedCredit: Satoshi) : TooExpensive()

                data object ChannelInitializing : Reason()
            }
        }
        data class AddedToFeeCredit(override val amount: MilliSatoshi, override val fee: MilliSatoshi, override val source: Source) : Decision
        data class Accepted(override val amount: MilliSatoshi, override val fee: MilliSatoshi, override val source: Source) : Decision
    }

    data class ApprovalRequested(override val amount: MilliSatoshi, override val fee: MilliSatoshi, override val source: Source, val replyTo: CompletableDeferred<Boolean>) : LiquidityEvents
}

/** This is useful on iOS to ask the OS for time to finish some sensitive tasks. */
sealed interface SensitiveTaskEvents : NodeEvents {
    sealed class TaskIdentifier {
        data class InteractiveTx(val channelId: ByteVector32, val fundingTxIndex: Long) : TaskIdentifier() {
            constructor(fundingParams: InteractiveTxParams) : this(fundingParams.channelId, (fundingParams.sharedInput as? SharedFundingInput.Multisig2of2)?.fundingTxIndex?.let { it + 1 } ?: 0)
        }
        data class IncomingMultiPartPayment(val paymentHash: ByteVector32) : TaskIdentifier()
    }
    data class TaskStarted(val id: TaskIdentifier) : SensitiveTaskEvents
    data class TaskEnded(val id: TaskIdentifier) : SensitiveTaskEvents

}

/** This will be emitted in a corner case where the user restores a wallet on an older version of the app, which is unable to read the channel data. */
data object UpgradeRequired : NodeEvents

sealed interface PaymentEvents : NodeEvents {
    data class PaymentReceived(val paymentHash: ByteVector32, val receivedWith: List<IncomingPayment.ReceivedWith>) : PaymentEvents {
        val amount: MilliSatoshi = receivedWith.map { it.amount }.sum()
        val fees: MilliSatoshi = receivedWith.map { it.fees }.sum()
    }
}
