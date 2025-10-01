package fr.acinq.lightning.channel

import fr.acinq.bitcoin.*
import fr.acinq.lightning.CltvExpiry
import fr.acinq.lightning.MilliSatoshi
import fr.acinq.lightning.NodeEvents
import fr.acinq.lightning.blockchain.Watch
import fr.acinq.lightning.channel.states.PersistedChannelState
import fr.acinq.lightning.db.ChannelCloseOutgoingPayment.ChannelClosingType
import fr.acinq.lightning.transactions.Transactions
import fr.acinq.lightning.utils.UUID
import fr.acinq.lightning.wire.*

/** Channel Actions (outputs produced by the state machine). */
sealed class ChannelAction {
    // @formatter:off
    sealed class Message : ChannelAction() {
        data class Send(val message: LightningMessage) : Message()
        data class SendToSelf(val command: ChannelCommand) : Message()
    }

    sealed class ChannelId : ChannelAction() {
        data class IdAssigned(val remoteNodeId: PublicKey, val temporaryChannelId: ByteVector32, val channelId: ByteVector32) : ChannelId()
    }

    sealed class Blockchain : ChannelAction() {
        data class SendWatch(val watch: Watch) : Blockchain()
        data class PublishTx(val tx: Transaction, val txType: Type) : Blockchain() {
            // region txType
            enum class Type {
                FundingTx,
                CommitTx,
                HtlcSuccessTx,
                HtlcTimeoutTx,
                HtlcDelayedTx,
                ClaimHtlcSuccessTx,
                ClaimHtlcTimeoutTx,
                ClaimLocalDelayedOutputTx,
                ClaimRemoteDelayedOutputTx,
                MainPenaltyTx,
                HtlcPenaltyTx,
                ClaimHtlcDelayedOutputPenaltyTx,
                ClosingTx,
            }

            constructor(txinfo: Transactions.TransactionWithInputInfo) : this(
                tx = txinfo.tx,
                txType = when (txinfo) {
                    is Transactions.CommitTx -> Type.CommitTx
                    is Transactions.HtlcSuccessTx -> Type.HtlcSuccessTx
                    is Transactions.HtlcTimeoutTx -> Type.HtlcTimeoutTx
                    is Transactions.HtlcDelayedTx -> Type.HtlcDelayedTx
                    is Transactions.ClaimHtlcSuccessTx -> Type.ClaimHtlcSuccessTx
                    is Transactions.ClaimHtlcTimeoutTx -> Type.ClaimHtlcTimeoutTx
                    is Transactions.ClaimLocalDelayedOutputTx -> Type.ClaimLocalDelayedOutputTx
                    is Transactions.ClaimRemoteDelayedOutputTx -> Type.ClaimRemoteDelayedOutputTx
                    is Transactions.MainPenaltyTx -> Type.MainPenaltyTx
                    is Transactions.HtlcPenaltyTx -> Type.HtlcPenaltyTx
                    is Transactions.ClaimHtlcDelayedOutputPenaltyTx -> Type.ClaimHtlcDelayedOutputPenaltyTx
                    is Transactions.ClosingTx -> Type.ClosingTx
                    is Transactions.SpliceTx -> Type.FundingTx
                }
            )
            // endregion
        }
    }

    sealed class Storage : ChannelAction() {
        data class StoreState(val data: PersistedChannelState) : Storage()
        data class RemoveChannel(val data: PersistedChannelState) : Storage()
        data class HtlcInfo(val channelId: ByteVector32, val commitmentNumber: Long, val paymentHash: ByteVector32, val cltvExpiry: CltvExpiry)
        data class StoreHtlcInfos(val htlcs: List<HtlcInfo>) : Storage()
        data class GetHtlcInfos(val revokedCommitTxId: TxId, val commitmentNumber: Long) : Storage()
        /** Payment received through on-chain operations (channel creation or splice-in) */
        sealed class StoreIncomingPayment : Storage() {
            abstract val origin: Origin?
            abstract val txId: TxId
            abstract val localInputs: Set<OutPoint>
            data class ViaNewChannel(val amountReceived: MilliSatoshi, val miningFee: Satoshi, val serviceFee: MilliSatoshi, val liquidityPurchase: LiquidityAds.Purchase?, override val localInputs: Set<OutPoint>, override val txId: TxId, override val origin: Origin?) : StoreIncomingPayment()
            data class ViaSpliceIn(val amountReceived: MilliSatoshi, val miningFee: Satoshi, val liquidityPurchase: LiquidityAds.Purchase?, override val localInputs: Set<OutPoint>, override val txId: TxId, override val origin: Origin?) : StoreIncomingPayment()
        }
        /** Payment sent through on-chain operations (channel close or splice-out) */
        sealed class StoreOutgoingPayment : Storage() {
            abstract val miningFee: Satoshi
            abstract val txId: TxId
            data class ViaSpliceOut(val amount: Satoshi, override val miningFee: Satoshi, val address: String, override val txId: TxId, val liquidityPurchase: LiquidityAds.Purchase?) : StoreOutgoingPayment()
            data class ViaSpliceCpfp(override val miningFee: Satoshi, override val txId: TxId) : StoreOutgoingPayment()
            data class ViaLiquidityPurchase(override val txId: TxId, override val miningFee: Satoshi, val purchase: LiquidityAds.Purchase) : StoreOutgoingPayment()
            data class ViaClose(val amount: Satoshi, override val miningFee: Satoshi, val address: String, override val txId: TxId, val isSentToDefaultAddress: Boolean, val closingType: ChannelClosingType) : StoreOutgoingPayment()
        }
        data class SetLocked(val txId: TxId) : Storage()
    }

    data class ProcessIncomingHtlc(val add: UpdateAddHtlc) : ChannelAction()

    /**
     * Process the result of executing a given command.
     * [ChannelCommand.Htlc.Add] has a special treatment: there are two response patterns for this command:
     *  - either [ProcessCmdRes.AddFailed] immediately
     *  - or [ProcessCmdRes.AddSettledFail] / [ProcessCmdRes.AddSettledFulfill] (usually a while later)
     */
    sealed class ProcessCmdRes : ChannelAction() {
        data class NotExecuted(val cmd: ChannelCommand, val t: ChannelException) : ProcessCmdRes()
        data class AddSettledFulfill(val paymentId: UUID, val htlc: UpdateAddHtlc, val result: HtlcResult.Fulfill) : ProcessCmdRes()
        data class AddSettledFail(val paymentId: UUID, val htlc: UpdateAddHtlc, val result: HtlcResult.Fail) : ProcessCmdRes()
        data class AddFailed(val cmd: ChannelCommand.Htlc.Add, val error: ChannelException, val channelUpdate: ChannelUpdate?) : ProcessCmdRes() {
            override fun toString() = "cannot add htlc with paymentId=${cmd.paymentId} reason=${error.message}"
        }
    }

    sealed class HtlcResult {
        sealed class Fulfill : HtlcResult() {
            abstract val paymentPreimage: ByteVector32

            data class OnChainFulfill(override val paymentPreimage: ByteVector32) : Fulfill()
            data class RemoteFulfill(val fulfill: UpdateFulfillHtlc) : Fulfill() {
                override val paymentPreimage = fulfill.paymentPreimage
            }
        }

        sealed class Fail : HtlcResult() {
            data class RemoteFail(val fail: UpdateFailHtlc) : Fail()
            data class RemoteFailMalformed(val fail: UpdateFailMalformedHtlc) : Fail()
            data class OnChainFail(val cause: ChannelException) : Fail()
            data object Disconnected : Fail()
        }
    }

    data class EmitEvent(val event: NodeEvents) : ChannelAction()

    data object Disconnect : ChannelAction()
    // @formatter:on
}