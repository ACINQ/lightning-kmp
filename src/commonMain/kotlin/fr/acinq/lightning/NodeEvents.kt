package fr.acinq.lightning

import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.PublicKey
import fr.acinq.bitcoin.OutPoint
import fr.acinq.bitcoin.Satoshi
import fr.acinq.lightning.blockchain.electrum.WalletState
import fr.acinq.lightning.channel.ChannelManagementFees
import fr.acinq.lightning.channel.InteractiveTxParams
import fr.acinq.lightning.channel.SharedFundingInput
import fr.acinq.lightning.channel.states.ChannelStateWithCommitments
import fr.acinq.lightning.channel.states.Normal
import fr.acinq.lightning.channel.states.WaitForFundingCreated
import fr.acinq.lightning.db.IncomingPayment
import fr.acinq.lightning.db.OutgoingPayment
import fr.acinq.lightning.utils.sum
import fr.acinq.lightning.wire.Init
import fr.acinq.lightning.wire.LiquidityAds

sealed interface NodeEvents

data class PeerConnected(val remoteNodeId: PublicKey, val theirInit: Init) : NodeEvents

sealed interface SwapInEvents : NodeEvents {
    data class Requested(val walletInputs: List<WalletState.Utxo>) : SwapInEvents {
        val totalAmount: Satoshi = walletInputs.map { it.amount }.sum()
    }
    data class Accepted(val inputs: Set<OutPoint>, val amountBeforeFees: Satoshi, val fees: ChannelManagementFees) : SwapInEvents {
        val receivedAmount: Satoshi = amountBeforeFees - fees.total
    }
}

sealed interface ChannelEvents : NodeEvents {
    data class Creating(val state: WaitForFundingCreated) : ChannelEvents
    data class Created(val state: ChannelStateWithCommitments) : ChannelEvents
    data class Confirmed(val state: Normal) : ChannelEvents
}

sealed interface LiquidityEvents : NodeEvents {
    enum class Source { OnChainWallet, OffChainPayment }
    data class Rejected(val amount: MilliSatoshi, val fee: MilliSatoshi, val source: Source, val reason: Reason) : LiquidityEvents {
        sealed class Reason {
            data object PolicySetToDisabled : Reason()
            sealed class TooExpensive : Reason() {
                data class OverAbsoluteFee(val maxAbsoluteFee: Satoshi) : TooExpensive()
                data class OverRelativeFee(val maxRelativeFeeBasisPoints: Int) : TooExpensive()
            }
            data object ChannelFundingInProgress : Reason()
            data object NoMatchingFundingRate : Reason()
            data class MissingOffChainAmountTooLow(val missingOffChainAmount: MilliSatoshi, val currentFeeCredit: MilliSatoshi) : Reason()
            data class TooManyParts(val parts: Int) : Reason()
        }
    }
    data class Purchased(val purchase: LiquidityAds.Purchase) : LiquidityEvents
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
        val amount: MilliSatoshi = receivedWith.map { it.amountReceived }.sum()
        val fees: MilliSatoshi = receivedWith.map { it.fees }.sum()
    }
    data class PaymentSent(val payment: OutgoingPayment) : PaymentEvents
}
