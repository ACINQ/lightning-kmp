package fr.acinq.lightning.channel.states

import fr.acinq.bitcoin.utils.Either
import fr.acinq.lightning.ChannelEvents
import fr.acinq.lightning.ShortChannelId
import fr.acinq.lightning.blockchain.WatchConfirmedTriggered
import fr.acinq.lightning.blockchain.WatchSpentTriggered
import fr.acinq.lightning.channel.*
import fr.acinq.lightning.router.Announcements
import fr.acinq.lightning.utils.toMilliSatoshi
import fr.acinq.lightning.wire.ChannelReady
import fr.acinq.lightning.wire.Error

/**
 * We changed the channel funding flow to use dual funding, and removed the ability to open legacy channels.
 * However, users may have legacy channels that were waiting to receive funding_locked from their counterparty.
 * This class handles this scenario, and lets those channels safely transition to the normal state.
 */
data class LegacyWaitForFundingLocked(
    override val commitments: Commitments,
    val shortChannelId: ShortChannelId,
    val lastSent: ChannelReady
) : ChannelStateWithCommitments() {
    override fun updateCommitments(input: Commitments): ChannelStateWithCommitments = this.copy(commitments = input)

    override suspend fun ChannelContext.processInternal(cmd: ChannelCommand): Pair<ChannelState, List<ChannelAction>> {
        return when (cmd) {
            is ChannelCommand.MessageReceived -> when (cmd.message) {
                is ChannelReady -> {
                    // we create a channel_update early so that we can use it to send payments through this channel, but it won't be propagated to other nodes since the channel is not yet announced
                    val initialChannelUpdate = Announcements.makeChannelUpdate(
                        staticParams.nodeParams.chainHash,
                        staticParams.nodeParams.nodePrivateKey,
                        staticParams.remoteNodeId,
                        shortChannelId,
                        staticParams.nodeParams.expiryDeltaBlocks,
                        commitments.params.remoteParams.htlcMinimum,
                        staticParams.nodeParams.feeBase,
                        staticParams.nodeParams.feeProportionalMillionths.toLong(),
                        commitments.latest.fundingAmount.toMilliSatoshi(),
                        enable = Helpers.aboveReserve(commitments)
                    )
                    val nextState = Normal(
                        commitments.copy(remoteNextCommitInfo = Either.Right(cmd.message.nextPerCommitmentPoint)),
                        shortChannelId,
                        initialChannelUpdate,
                        null,
                        SpliceStatus.None,
                        null,
                        null,
                        null,
                    )
                    val actions = listOf(
                        ChannelAction.Storage.StoreState(nextState),
                        ChannelAction.EmitEvent(ChannelEvents.Confirmed(nextState)),
                    )
                    Pair(nextState, actions)
                }
                is Error -> handleRemoteError(cmd.message)
                else -> unhandled(cmd)
            }
            is ChannelCommand.WatchReceived -> when (val watch = cmd.watch) {
                is WatchSpentTriggered -> when (watch.spendingTx.txid) {
                    commitments.latest.remoteCommit.txid -> handleRemoteSpentCurrent(watch.spendingTx, commitments.latest)
                    else -> handleRemoteSpentOther(watch.spendingTx)
                }
                is WatchConfirmedTriggered -> unhandled(cmd)
            }
            is ChannelCommand.Close.MutualClose -> {
                cmd.replyTo.complete(ChannelCloseResponse.Failure.ChannelNotOpenedYet(this::class.toString()))
                Pair(this@LegacyWaitForFundingLocked, listOf(ChannelAction.ProcessCmdRes.NotExecuted(cmd, CommandUnavailableInThisState(channelId, this::class.toString()))))
            }
            is ChannelCommand.Close.ForceClose -> handleLocalError(cmd, ForcedLocalCommit(channelId))
            is ChannelCommand.Init -> unhandled(cmd)
            is ChannelCommand.Funding -> unhandled(cmd)
            is ChannelCommand.Htlc -> unhandled(cmd)
            is ChannelCommand.Commitment -> unhandled(cmd)
            is ChannelCommand.Closing -> unhandled(cmd)
            is ChannelCommand.Connected -> unhandled(cmd)
            is ChannelCommand.Disconnected -> Pair(Offline(this@LegacyWaitForFundingLocked), listOf())
        }
    }
}