package fr.acinq.lightning.channel

import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.Satoshi
import fr.acinq.bitcoin.TxOut
import fr.acinq.lightning.CltvExpiry
import fr.acinq.lightning.MilliSatoshi
import fr.acinq.lightning.blockchain.electrum.WalletState
import fr.acinq.lightning.blockchain.fee.FeeratePerKw
import fr.acinq.lightning.transactions.Transactions.weight2fee
import fr.acinq.lightning.utils.UUID
import fr.acinq.lightning.utils.msat
import fr.acinq.lightning.wire.FailureMessage
import fr.acinq.lightning.wire.OnionRoutingPacket
import kotlinx.coroutines.CompletableDeferred

sealed class Command {
    sealed interface ForbiddenDuringSplice

    sealed class Splice {
        data class Request(val replyTo: CompletableDeferred<Response>, val spliceIn: SpliceIn?, val spliceOut: SpliceOut?, val feerate: FeeratePerKw, val origins: List<Origin.PayToOpenOrigin> = emptyList()) : Command() {
            init {
                require(spliceIn != null || spliceOut != null) { "there must be a splice-in or a splice-out" }
            }

            val pushAmount: MilliSatoshi = spliceIn?.pushAmount ?: 0.msat
            val spliceOutputs: List<TxOut> = spliceOut?.let { listOf(TxOut(it.amount, it.scriptPubKey)) } ?: emptyList()

            data class SpliceIn(val wallet: WalletState, val pushAmount: MilliSatoshi = 0.msat)
            data class SpliceOut(val amount: Satoshi, val scriptPubKey: ByteVector)
        }

        sealed class Response {
            /**
             * This response doesn't fully guarantee that the splice will confirm, because our peer may potentially double-spend
             * the splice transaction. Callers should wait for on-chain confirmations and handle double-spend events.
             */
            data class Created(
                val channelId: ByteVector32,
                val fundingTxIndex: Long,
                val fundingTxId: ByteVector32,
                val capacity: Satoshi,
                val balance: MilliSatoshi
            ) : Response()

            sealed class Failure : Response() {
                object InsufficientFunds : Failure()
                object InvalidSpliceOutPubKeyScript : Failure()
                object SpliceAlreadyInProgress : Failure()
                object ChannelNotIdle : Failure()
                data class FundingFailure(val reason: FundingContributionFailure) : Failure()
                object CannotStartSession : Failure()
                data class InteractiveTxSessionFailed(val reason: InteractiveTxSessionAction.RemoteFailure) : Failure()
                data class CannotCreateCommitTx(val reason: ChannelException) : Failure()
                data class AbortedByPeer(val reason: String) : Failure()
                object Disconnected : Failure()
            }
        }
    }
}

data class CMD_ADD_HTLC(val amount: MilliSatoshi, val paymentHash: ByteVector32, val cltvExpiry: CltvExpiry, val onion: OnionRoutingPacket, val paymentId: UUID, val commit: Boolean = false) : Command(), Command.ForbiddenDuringSplice

sealed class HtlcSettlementCommand : Command(), Command.ForbiddenDuringSplice {
    abstract val id: Long
}

data class CMD_FULFILL_HTLC(override val id: Long, val r: ByteVector32, val commit: Boolean = false) : HtlcSettlementCommand()
data class CMD_FAIL_MALFORMED_HTLC(override val id: Long, val onionHash: ByteVector32, val failureCode: Int, val commit: Boolean = false) : HtlcSettlementCommand()
data class CMD_FAIL_HTLC(override val id: Long, val reason: Reason, val commit: Boolean = false) : HtlcSettlementCommand() {
    sealed class Reason {
        data class Bytes(val bytes: ByteVector) : Reason()
        data class Failure(val message: FailureMessage) : Reason()
    }
}

object CMD_SIGN : Command(), Command.ForbiddenDuringSplice
data class CMD_UPDATE_FEE(val feerate: FeeratePerKw, val commit: Boolean = false) : Command(), Command.ForbiddenDuringSplice

// We only support a very limited fee bumping mechanism where all spendable utxos will be used (only used in tests).
data class CMD_BUMP_FUNDING_FEE(val targetFeerate: FeeratePerKw, val fundingAmount: Satoshi, val wallet: WalletState, val lockTime: Long) : Command()

data class ClosingFees(val preferred: Satoshi, val min: Satoshi, val max: Satoshi) {
    constructor(preferred: Satoshi) : this(preferred, preferred, preferred)
}

data class ClosingFeerates(val preferred: FeeratePerKw, val min: FeeratePerKw, val max: FeeratePerKw) {
    fun computeFees(closingTxWeight: Int): ClosingFees = ClosingFees(weight2fee(preferred, closingTxWeight), weight2fee(min, closingTxWeight), weight2fee(max, closingTxWeight))

    companion object {
        operator fun invoke(preferred: FeeratePerKw): ClosingFeerates = ClosingFeerates(preferred, preferred / 2, preferred * 2)
    }
}

sealed class CloseCommand : Command()
data class CMD_CLOSE(val scriptPubKey: ByteVector?, val feerates: ClosingFeerates?) : CloseCommand()
object CMD_FORCECLOSE : CloseCommand()
