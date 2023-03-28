package fr.acinq.lightning.channel

import fr.acinq.lightning.ChannelEvents
import fr.acinq.lightning.ShortChannelId
import fr.acinq.lightning.blockchain.BITCOIN_FUNDING_DEEPLYBURIED
import fr.acinq.lightning.blockchain.WatchConfirmed
import fr.acinq.lightning.blockchain.WatchEventConfirmed
import fr.acinq.lightning.blockchain.WatchEventSpent
import fr.acinq.lightning.channel.Channel.ANNOUNCEMENTS_MINCONF
import fr.acinq.lightning.router.Announcements
import fr.acinq.lightning.utils.Either
import fr.acinq.lightning.utils.toMilliSatoshi
import fr.acinq.lightning.wire.*

/** The channel funding transaction was confirmed, we exchange funding_locked messages. */
data class WaitForChannelReady(
    override val commitments: Commitments,
    val shortChannelId: ShortChannelId,
    val lastSent: ChannelReady
) : ChannelStateWithCommitments() {
    override fun updateCommitments(input: Commitments): ChannelStateWithCommitments = this.copy(commitments = input)

    override fun ChannelContext.processInternal(cmd: ChannelCommand): Pair<ChannelState, List<ChannelAction>> {
        return when {
            cmd is ChannelCommand.MessageReceived && cmd.message is TxSignatures -> when (commitments.latest.localFundingStatus) {
                is LocalFundingStatus.UnconfirmedFundingTx -> when (commitments.latest.localFundingStatus.sharedTx) {
                    is PartiallySignedSharedTransaction -> when (val fullySignedTx = commitments.latest.localFundingStatus.sharedTx.addRemoteSigs(commitments.latest.localFundingStatus.fundingParams, cmd.message)) {
                        null -> {
                            logger.warning { "received invalid remote funding signatures for txId=${cmd.message.txId}" }
                            // The funding transaction may still confirm (since our peer should be able to generate valid signatures), so we cannot close the channel yet.
                            Pair(this@WaitForChannelReady, listOf(ChannelAction.Message.Send(Warning(channelId, InvalidFundingSignature(channelId, cmd.message.txId).message))))
                        }
                        else -> {
                            when (val res = commitments.updateLocalFundingStatus(fullySignedTx.signedTx.txid, commitments.latest.localFundingStatus.copy(sharedTx = fullySignedTx), logger)) {
                                is Either.Left -> Pair(this@WaitForChannelReady, listOf())
                                is Either.Right -> {
                                    logger.info { "received remote funding signatures, publishing txId=${fullySignedTx.signedTx.txid}" }
                                    val nextState = this@WaitForChannelReady.copy(commitments = res.value.first)
                                    val actions = buildList {
                                        add(ChannelAction.Blockchain.PublishTx(fullySignedTx.signedTx))
                                        add(ChannelAction.Storage.StoreState(nextState))
                                    }
                                    Pair(nextState, actions)
                                }
                            }
                        }
                    }
                    is FullySignedSharedTransaction -> {
                        logger.info { "ignoring duplicate remote funding signatures" }
                        Pair(this@WaitForChannelReady, listOf())
                    }
                }
                is LocalFundingStatus.ConfirmedFundingTx -> {
                    logger.info { "ignoring funding signatures for txId=${cmd.message.txId}, transaction is already confirmed" }
                    Pair(this@WaitForChannelReady, listOf())
                }
            }
            cmd is ChannelCommand.MessageReceived && cmd.message is TxInitRbf -> {
                logger.info { "rejecting tx_init_rbf, we have already accepted the channel" }
                Pair(this@WaitForChannelReady, listOf(ChannelAction.Message.Send(TxAbort(channelId, InvalidRbfTxConfirmed(channelId, commitments.latest.fundingTxId).message))))
            }
            cmd is ChannelCommand.MessageReceived && cmd.message is ChannelReady -> {
                // used to get the final shortChannelId, used in announcements (if minDepth >= ANNOUNCEMENTS_MINCONF this event will fire instantly)
                val watchConfirmed = WatchConfirmed(
                    this@WaitForChannelReady.channelId,
                    commitments.latest.commitInput.outPoint.txid,
                    commitments.latest.commitInput.txOut.publicKeyScript,
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
                    commitments.params.remoteParams.htlcMinimum,
                    staticParams.nodeParams.feeBase,
                    staticParams.nodeParams.feeProportionalMillionth.toLong(),
                    commitments.latest.fundingAmount.toMilliSatoshi(),
                    enable = Helpers.aboveReserve(commitments)
                )
                val nextState = Normal(
                    commitments,
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
                    ChannelAction.Storage.StoreState(nextState),
                    ChannelAction.EmitEvent(ChannelEvents.Confirmed(nextState)),
                )
                Pair(nextState, actions)
            }
            cmd is ChannelCommand.MessageReceived && cmd.message is Error -> handleRemoteError(cmd.message)
            cmd is ChannelCommand.WatchReceived -> when (cmd.watch) {
                is WatchEventConfirmed -> updateFundingTxStatus(cmd.watch)
                is WatchEventSpent -> handlePotentialForceClose(cmd.watch)
            }
            cmd is ChannelCommand.ExecuteCommand -> when (cmd.command) {
                is CMD_CLOSE -> Pair(this@WaitForChannelReady, listOf(ChannelAction.ProcessCmdRes.NotExecuted(cmd.command, CommandUnavailableInThisState(channelId, this::class.toString()))))
                is CMD_FORCECLOSE -> handleLocalError(cmd, ForcedLocalCommit(channelId))
                else -> unhandled(cmd)
            }
            cmd is ChannelCommand.CheckHtlcTimeout -> Pair(this@WaitForChannelReady, listOf())
            cmd is ChannelCommand.Disconnected -> Pair(Offline(this@WaitForChannelReady), listOf())
            else -> unhandled(cmd)
        }
    }

    override fun ChannelContext.handleLocalError(cmd: ChannelCommand, t: Throwable): Pair<ChannelState, List<ChannelAction>> {
        logger.error(t) { "error on command ${cmd::class.simpleName} in state ${this@WaitForChannelReady::class.simpleName}" }
        val error = Error(channelId, t.message)
        return when {
            commitments.nothingAtStake() -> Pair(Aborted, listOf(ChannelAction.Message.Send(error)))
            else -> spendLocalCurrent().run { copy(second = second + ChannelAction.Message.Send(error)) }
        }
    }
}
