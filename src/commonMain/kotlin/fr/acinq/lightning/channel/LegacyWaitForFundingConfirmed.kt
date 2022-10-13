@file:UseContextualSerialization(BlockHeader::class, ByteVector32::class, ByteVector64::class, PublicKey::class, Satoshi::class, ShaChain::class, Transaction::class)

package fr.acinq.lightning.channel

import fr.acinq.bitcoin.*
import fr.acinq.lightning.ShortChannelId
import fr.acinq.lightning.blockchain.WatchEventConfirmed
import fr.acinq.lightning.blockchain.WatchEventSpent
import fr.acinq.lightning.blockchain.fee.OnChainFeerates
import fr.acinq.lightning.crypto.ShaChain
import fr.acinq.lightning.serialization.v3.EitherSerializer
import fr.acinq.lightning.utils.Either
import fr.acinq.lightning.utils.Try
import fr.acinq.lightning.utils.runTrying
import fr.acinq.lightning.wire.*
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseContextualSerialization

/**
 * We changed the channel funding flow to use dual funding, and removed the ability to open legacy channels.
 * However, users may have legacy channels that were waiting for the funding transaction to confirm.
 * This class handles this scenario, and lets those channels safely transition to the normal state.
 */
@Serializable
data class LegacyWaitForFundingConfirmed(
    override val staticParams: StaticParams,
    override val currentTip: Pair<Int, BlockHeader>,
    override val currentOnChainFeerates: OnChainFeerates,
    override val commitments: Commitments,
    val fundingTx: Transaction?,
    val waitingSinceBlock: Long, // how many blocks have we been waiting for the funding tx to confirm
    val deferred: FundingLocked?,
    val lastSent: @Serializable(with = EitherSerializer::class)  Either<FundingCreated, FundingSigned>
) : ChannelStateWithCommitments() {
    override fun updateCommitments(input: Commitments): ChannelStateWithCommitments = this.copy(commitments = input)

    override fun processInternal(cmd: ChannelCommand): Pair<ChannelState, List<ChannelAction>> {
        return when (cmd) {
            is ChannelCommand.MessageReceived -> when (cmd.message) {
                is ChannelReady -> Pair(this.copy(deferred = FundingLocked(cmd.message.channelId, cmd.message.nextPerCommitmentPoint)), listOf())
                is Error -> handleRemoteError(cmd.message)
                else -> Pair(this, listOf())
            }
            is ChannelCommand.WatchReceived ->
                when (cmd.watch) {
                    is WatchEventConfirmed -> {
                        val result = runTrying {
                            Transaction.correctlySpends(commitments.localCommit.publishableTxs.commitTx.tx, listOf(cmd.watch.tx), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
                        }
                        if (result is Try.Failure) {
                            logger.error { "c:$channelId funding tx verification failed: ${result.error}" }
                            return handleLocalError(cmd, InvalidCommitmentSignature(channelId, cmd.watch.tx.txid))
                        }
                        val nextPerCommitmentPoint = keyManager.commitmentPoint(commitments.localParams.channelKeys.shaSeed, 1)
                        val channelReady = ChannelReady(commitments.channelId, nextPerCommitmentPoint)
                        // this is the temporary channel id that we will use in our channel_update message, the goal is to be able to use our channel
                        // as soon as it reaches NORMAL state, and before it is announced on the network
                        // (this id might be updated when the funding tx gets deeply buried, if there was a reorg in the meantime)
                        val blockHeight = cmd.watch.blockHeight
                        val txIndex = cmd.watch.txIndex
                        val shortChannelId = ShortChannelId(blockHeight, txIndex, commitments.commitInput.outPoint.index.toInt())
                        val nextState = LegacyWaitForFundingLocked(staticParams, currentTip, currentOnChainFeerates, commitments, shortChannelId, FundingLocked(commitments.channelId, nextPerCommitmentPoint))
                        val actions = listOf(
                            ChannelAction.Message.Send(channelReady),
                            ChannelAction.Storage.StoreState(nextState)
                        )
                        if (deferred != null) {
                            logger.info { "c:$channelId funding_locked has already been received" }
                            val resultPair = nextState.process(ChannelCommand.MessageReceived(deferred))
                            Pair(resultPair.first, actions + resultPair.second)
                        } else {
                            Pair(nextState, actions)
                        }
                    }
                    is WatchEventSpent -> when (cmd.watch.tx.txid) {
                        commitments.remoteCommit.txid -> handleRemoteSpentCurrent(cmd.watch.tx)
                        else -> handleRemoteSpentOther(cmd.watch.tx)
                    }
                }
            is ChannelCommand.ExecuteCommand -> when (cmd.command) {
                is CMD_CLOSE -> Pair(this, listOf(ChannelAction.ProcessCmdRes.NotExecuted(cmd.command, CommandUnavailableInThisState(channelId, this::class.toString()))))
                is CMD_FORCECLOSE -> handleLocalError(cmd, ForcedLocalCommit(channelId))
                else -> unhandled(cmd)
            }
            is ChannelCommand.CheckHtlcTimeout -> Pair(this, listOf())
            is ChannelCommand.NewBlock -> Pair(this.copy(currentTip = Pair(cmd.height, cmd.Header)), listOf())
            is ChannelCommand.SetOnChainFeerates -> Pair(this.copy(currentOnChainFeerates = cmd.feerates), listOf())
            is ChannelCommand.Disconnected -> Pair(Offline(this), listOf())
            else -> unhandled(cmd)
        }
    }

    override fun handleLocalError(cmd: ChannelCommand, t: Throwable): Pair<ChannelState, List<ChannelAction>> {
        logger.error(t) { "c:$channelId error on event ${cmd::class} in state ${this::class}" }
        val error = Error(channelId, t.message)
        return when {
            commitments.nothingAtStake() -> Pair(Aborted(staticParams, currentTip, currentOnChainFeerates), listOf(ChannelAction.Message.Send(error)))
            else -> spendLocalCurrent().run { copy(second = second + ChannelAction.Message.Send(error)) }
        }
    }
}