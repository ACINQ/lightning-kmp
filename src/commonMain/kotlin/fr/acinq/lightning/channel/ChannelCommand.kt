package fr.acinq.lightning.channel

import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.Satoshi
import fr.acinq.bitcoin.TxOut
import fr.acinq.lightning.CltvExpiry
import fr.acinq.lightning.MilliSatoshi
import fr.acinq.lightning.blockchain.WatchEvent
import fr.acinq.lightning.blockchain.electrum.WalletState
import fr.acinq.lightning.blockchain.fee.FeeratePerKw
import fr.acinq.lightning.channel.states.ClosingFeerates
import fr.acinq.lightning.channel.states.PersistedChannelState
import fr.acinq.lightning.crypto.KeyManager
import fr.acinq.lightning.utils.UUID
import fr.acinq.lightning.utils.msat
import fr.acinq.lightning.wire.FailureMessage
import fr.acinq.lightning.wire.Init
import fr.acinq.lightning.wire.LightningMessage
import fr.acinq.lightning.wire.OnionRoutingPacket
import kotlinx.coroutines.CompletableDeferred

/** Channel Events (inputs to be fed to the state machine). */
sealed class ChannelCommand {
    // @formatter:off
    data class InitInitiator(
        val fundingAmount: Satoshi,
        val pushAmount: MilliSatoshi,
        val wallet: WalletState,
        val commitTxFeerate: FeeratePerKw,
        val fundingTxFeerate: FeeratePerKw,
        val localParams: LocalParams,
        val remoteInit: Init,
        val channelFlags: Byte,
        val channelConfig: ChannelConfig,
        val channelType: ChannelType.SupportedChannelType,
        val channelOrigin: Origin? = null
    ) : ChannelCommand() {
        fun temporaryChannelId(keyManager: KeyManager): ByteVector32 = keyManager.channelKeys(localParams.fundingKeyPath).temporaryChannelId
    }

    data class InitNonInitiator(
        val temporaryChannelId: ByteVector32,
        val fundingAmount: Satoshi,
        val pushAmount: MilliSatoshi,
        val wallet: WalletState,
        val localParams: LocalParams,
        val channelConfig: ChannelConfig,
        val remoteInit: Init
    ) : ChannelCommand()

    data class Restore(val state: PersistedChannelState) : ChannelCommand()
    object CheckHtlcTimeout : ChannelCommand()
    data class MessageReceived(val message: LightningMessage) : ChannelCommand()
    data class WatchReceived(val watch: WatchEvent) : ChannelCommand()
    sealed interface ForbiddenDuringSplice
    sealed class Htlc : ChannelCommand() {
        data class Add(val amount: MilliSatoshi, val paymentHash: ByteVector32, val cltvExpiry: CltvExpiry, val onion: OnionRoutingPacket, val paymentId: UUID, val commit: Boolean = false) : Htlc(), ForbiddenDuringSplice

        sealed class Settlement : Htlc(), ForbiddenDuringSplice {
            abstract val id: Long

            data class Fulfill(override val id: Long, val r: ByteVector32, val commit: Boolean = false) : Settlement()
            data class FailMalformed(override val id: Long, val onionHash: ByteVector32, val failureCode: Int, val commit: Boolean = false) : Settlement()
            data class Fail(override val id: Long, val reason: Reason, val commit: Boolean = false) : Settlement() {
                sealed class Reason {
                    data class Bytes(val bytes: ByteVector) : Reason()
                    data class Failure(val message: FailureMessage) : Reason()
                }
            }
        }
    }
    object Sign : ChannelCommand(), ForbiddenDuringSplice
    data class UpdateFee(val feerate: FeeratePerKw, val commit: Boolean = false) : ChannelCommand(), ForbiddenDuringSplice
    // We only support a very limited fee bumping mechanism where all spendable utxos will be used (only used in tests).
    data class BumpFundingFee(val targetFeerate: FeeratePerKw, val fundingAmount: Satoshi, val wallet: WalletState, val lockTime: Long) : ChannelCommand()
    sealed class Close : ChannelCommand() {
        data class MutualClose(val scriptPubKey: ByteVector?, val feerates: ClosingFeerates?) : Close()
        object ForceClose : Close()
    }
    sealed class Splice {
        data class Request(val replyTo: CompletableDeferred<Response>, val spliceIn: SpliceIn?, val spliceOut: SpliceOut?, val feerate: FeeratePerKw, val origins: List<Origin.PayToOpenOrigin> = emptyList()) : ChannelCommand() {
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
    data class GetHtlcInfosResponse(val revokedCommitTxId: ByteVector32, val htlcInfos: List<ChannelAction.Storage.HtlcInfo>) : ChannelCommand()
    object Disconnected : ChannelCommand()
    data class Connected(val localInit: Init, val remoteInit: Init) : ChannelCommand()
    // @formatter:on
}