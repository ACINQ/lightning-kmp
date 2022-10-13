@file:UseContextualSerialization(BlockHeader::class)

package fr.acinq.lightning.channel

import fr.acinq.bitcoin.*
import fr.acinq.lightning.ShortChannelId
import fr.acinq.lightning.blockchain.BITCOIN_FUNDING_DEEPLYBURIED
import fr.acinq.lightning.blockchain.WatchConfirmed
import fr.acinq.lightning.blockchain.WatchEventSpent
import fr.acinq.lightning.blockchain.fee.OnChainFeerates
import fr.acinq.lightning.channel.Channel.ANNOUNCEMENTS_MINCONF
import fr.acinq.lightning.crypto.ShaChain
import fr.acinq.lightning.router.Announcements
import fr.acinq.lightning.utils.Either
import fr.acinq.lightning.wire.ChannelReady
import fr.acinq.lightning.wire.Error
import fr.acinq.lightning.wire.FundingLocked
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseContextualSerialization

/**
 * We changed the channel funding flow to use dual funding, and removed the ability to open legacy channels.
 * However, users may have legacy channels that were waiting to receive funding_locked from their counterparty.
 * This class handles this scenario, and lets those channels safely transition to the normal state.
 */
@Serializable
data class LegacyWaitForFundingLocked(
    override val staticParams: StaticParams,
    override val currentTip: Pair<Int, BlockHeader>,
    override val currentOnChainFeerates: OnChainFeerates,
    override val commitments: Commitments,
    val shortChannelId: ShortChannelId,
    val lastSent: FundingLocked
) : ChannelStateWithCommitments() {
    override fun updateCommitments(input: Commitments): ChannelStateWithCommitments = this.copy(commitments = input)

    override fun processInternal(cmd: ChannelCommand): Pair<ChannelState, List<ChannelAction>> {
        return when (cmd) {
            is ChannelCommand.MessageReceived -> when (cmd.message) {
                is ChannelReady -> {
                    // used to get the final shortChannelId, used in announcements (if minDepth >= ANNOUNCEMENTS_MINCONF this event will fire instantly)
                    val watchConfirmed = WatchConfirmed(
                        this.channelId,
                        commitments.commitInput.outPoint.txid,
                        commitments.commitInput.txOut.publicKeyScript,
                        ANNOUNCEMENTS_MINCONF.toLong(),
                        BITCOIN_FUNDING_DEEPLYBURIED
                    )
                    // we create a channel_update early so that we can use it to send payments through this channel, but it won't be propagated to other nodes since the channel is not yet announced
                    val initialChannelUpdate = Announcements.makeChannelUpdate(
                        staticParams.nodeParams.chainHash,
                        staticParams.nodeParams.nodePrivateKey,
                        staticParams.remoteNodeId,
                        shortChannelId,
                        staticParams.nodeParams.expiryDeltaBlocks,
                        commitments.remoteParams.htlcMinimum,
                        staticParams.nodeParams.feeBase,
                        staticParams.nodeParams.feeProportionalMillionth.toLong(),
                        commitments.localCommit.spec.totalFunds,
                        enable = Helpers.aboveReserve(commitments)
                    )
                    val nextState = Normal(
                        staticParams,
                        currentTip,
                        currentOnChainFeerates,
                        commitments.copy(remoteNextCommitInfo = Either.Right(cmd.message.nextPerCommitmentPoint)),
                        shortChannelId,
                        buried = false,
                        null,
                        initialChannelUpdate,
                        null,
                        null,
                        null,
                        null
                    )
                    val actions = listOf(
                        ChannelAction.Blockchain.SendWatch(watchConfirmed),
                        ChannelAction.Storage.StoreState(nextState)
                    )
                    Pair(nextState, actions)
                }
                is Error -> handleRemoteError(cmd.message)
                else -> unhandled(cmd)
            }
            is ChannelCommand.WatchReceived -> when (val watch = cmd.watch) {
                is WatchEventSpent -> when (watch.tx.txid) {
                    commitments.remoteCommit.txid -> handleRemoteSpentCurrent(watch.tx)
                    else -> handleRemoteSpentOther(watch.tx)
                }
                else -> unhandled(cmd)
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