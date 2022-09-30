package fr.acinq.lightning.channel

import fr.acinq.bitcoin.BlockHeader
import fr.acinq.bitcoin.ScriptFlags
import fr.acinq.bitcoin.Transaction
import fr.acinq.lightning.ShortChannelId
import fr.acinq.lightning.blockchain.WatchEventConfirmed
import fr.acinq.lightning.blockchain.WatchEventSpent
import fr.acinq.lightning.blockchain.fee.OnChainFeerates
import fr.acinq.lightning.utils.Either
import fr.acinq.lightning.utils.Try
import fr.acinq.lightning.utils.runTrying
import fr.acinq.lightning.wire.Error
import fr.acinq.lightning.wire.FundingCreated
import fr.acinq.lightning.wire.FundingLocked
import fr.acinq.lightning.wire.FundingSigned

/**
 * We changed the channel funding flow to use dual funding, and removed the ability to open legacy channels.
 * However, users may have legacy channels that were waiting for the funding transaction to confirm.
 * This class handles this scenario, and lets those channels safely transition to the normal state.
 */
data class LegacyWaitForFundingConfirmed(
    override val staticParams: StaticParams,
    override val currentTip: Pair<Int, BlockHeader>,
    override val currentOnChainFeerates: OnChainFeerates,
    override val commitments: Commitments,
    val fundingTx: Transaction?,
    val waitingSinceBlock: Long, // how many blocks have we been waiting for the funding tx to confirm
    val deferred: FundingLocked?,
    val lastSent: Either<FundingCreated, FundingSigned>
) : ChannelStateWithCommitments() {
    override fun updateCommitments(input: Commitments): ChannelStateWithCommitments = this.copy(commitments = input)

    override fun processInternal(event: ChannelEvent): Pair<ChannelState, List<ChannelAction>> {
        return when (event) {
            is ChannelEvent.MessageReceived -> when (event.message) {
                is FundingLocked -> Pair(this.copy(deferred = event.message), listOf())
                is Error -> handleRemoteError(event.message)
                else -> Pair(this, listOf())
            }
            is ChannelEvent.WatchReceived ->
                when (event.watch) {
                    is WatchEventConfirmed -> {
                        val result = runTrying {
                            Transaction.correctlySpends(commitments.localCommit.publishableTxs.commitTx.tx, listOf(event.watch.tx), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
                        }
                        if (result is Try.Failure) {
                            logger.error { "c:$channelId funding tx verification failed: ${result.error}" }
                            return handleLocalError(event, InvalidCommitmentSignature(channelId, event.watch.tx.txid))
                        }
                        val nextPerCommitmentPoint = keyManager.commitmentPoint(commitments.localParams.channelKeys.shaSeed, 1)
                        val fundingLocked = FundingLocked(commitments.channelId, nextPerCommitmentPoint)
                        // this is the temporary channel id that we will use in our channel_update message, the goal is to be able to use our channel
                        // as soon as it reaches NORMAL state, and before it is announced on the network
                        // (this id might be updated when the funding tx gets deeply buried, if there was a reorg in the meantime)
                        val blockHeight = event.watch.blockHeight
                        val txIndex = event.watch.txIndex
                        val shortChannelId = ShortChannelId(blockHeight, txIndex, commitments.commitInput.outPoint.index.toInt())
                        val nextState = LegacyWaitForFundingLocked(staticParams, currentTip, currentOnChainFeerates, commitments, shortChannelId, fundingLocked)
                        val actions = listOf(
                            ChannelAction.Message.Send(fundingLocked),
                            ChannelAction.Storage.StoreState(nextState)
                        )
                        if (deferred != null) {
                            logger.info { "c:$channelId funding_locked has already been received" }
                            val resultPair = nextState.process(ChannelEvent.MessageReceived(deferred))
                            Pair(resultPair.first, actions + resultPair.second)
                        } else {
                            Pair(nextState, actions)
                        }
                    }
                    is WatchEventSpent -> when (event.watch.tx.txid) {
                        commitments.remoteCommit.txid -> handleRemoteSpentCurrent(event.watch.tx)
                        else -> handleRemoteSpentOther(event.watch.tx)
                    }
                }
            is ChannelEvent.ExecuteCommand -> when (event.command) {
                is CMD_CLOSE -> Pair(this, listOf(ChannelAction.ProcessCmdRes.NotExecuted(event.command, CommandUnavailableInThisState(channelId, this::class.toString()))))
                is CMD_FORCECLOSE -> handleLocalError(event, ForcedLocalCommit(channelId))
                else -> unhandled(event)
            }
            is ChannelEvent.CheckHtlcTimeout -> Pair(this, listOf())
            is ChannelEvent.NewBlock -> Pair(this.copy(currentTip = Pair(event.height, event.Header)), listOf())
            is ChannelEvent.SetOnChainFeerates -> Pair(this.copy(currentOnChainFeerates = event.feerates), listOf())
            is ChannelEvent.Disconnected -> Pair(Offline(this), listOf())
            else -> unhandled(event)
        }
    }

    override fun handleLocalError(event: ChannelEvent, t: Throwable): Pair<ChannelState, List<ChannelAction>> {
        logger.error(t) { "c:$channelId error on event ${event::class} in state ${this::class}" }
        val error = Error(channelId, t.message)
        return when {
            commitments.nothingAtStake() -> Pair(Aborted(staticParams, currentTip, currentOnChainFeerates), listOf(ChannelAction.Message.Send(error)))
            else -> spendLocalCurrent().run { copy(second = second + ChannelAction.Message.Send(error)) }
        }
    }
}