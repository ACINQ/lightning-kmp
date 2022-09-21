package fr.acinq.lightning.channel

import fr.acinq.bitcoin.BlockHeader
import fr.acinq.lightning.ShortChannelId
import fr.acinq.lightning.blockchain.BITCOIN_FUNDING_DEEPLYBURIED
import fr.acinq.lightning.blockchain.WatchConfirmed
import fr.acinq.lightning.blockchain.WatchEventSpent
import fr.acinq.lightning.blockchain.fee.OnChainFeerates
import fr.acinq.lightning.channel.Channel.ANNOUNCEMENTS_MINCONF
import fr.acinq.lightning.router.Announcements
import fr.acinq.lightning.utils.Either
import fr.acinq.lightning.wire.*

data class WaitForFundingLocked(
    override val staticParams: StaticParams,
    override val currentTip: Pair<Int, BlockHeader>,
    override val currentOnChainFeerates: OnChainFeerates,
    override val commitments: Commitments,
    val fundingParams: InteractiveTxParams,
    val fundingTx: SignedSharedTransaction,
    val shortChannelId: ShortChannelId,
    val lastSent: FundingLocked
) : ChannelStateWithCommitments() {
    override fun updateCommitments(input: Commitments): ChannelStateWithCommitments = this.copy(commitments = input)

    override fun processInternal(event: ChannelEvent): Pair<ChannelState, List<ChannelAction>> {
        return when {
            event is ChannelEvent.MessageReceived && event.message is TxSignatures -> when (fundingTx) {
                is PartiallySignedSharedTransaction -> when (val fullySignedTx = fundingTx.addRemoteSigs(event.message)) {
                    null -> {
                        logger.warning { "c:$channelId received invalid remote funding signatures for txId=${event.message.txId}" }
                        // The funding transaction may still confirm (since our peer should be able to generate valid signatures), so we cannot close the channel yet.
                        Pair(this, listOf(ChannelAction.Message.Send(Warning(channelId, InvalidFundingSignature(channelId, event.message.txId).message))))
                    }
                    else -> {
                        logger.info { "c:$channelId received remote funding signatures, publishing txId=${fullySignedTx.signedTx.txid}" }
                        val nextState = this.copy(fundingTx = fullySignedTx)
                        val actions = buildList {
                            // If we haven't sent our signatures yet, we do it now.
                            if (!fundingParams.shouldSignFirst(commitments.localParams.nodeId, commitments.remoteParams.nodeId)) add(ChannelAction.Message.Send(fullySignedTx.localSigs))
                            add(ChannelAction.Blockchain.PublishTx(fullySignedTx.signedTx))
                            add(ChannelAction.Storage.StoreState(nextState))
                        }
                        Pair(nextState, actions)
                    }
                }
                is FullySignedSharedTransaction -> {
                    logger.info { "c:$channelId ignoring duplicate remote funding signatures" }
                    Pair(this, listOf())
                }
            }
            event is ChannelEvent.MessageReceived && event.message is TxInitRbf -> {
                logger.info { "c:$channelId rejecting tx_init_rbf, we have already accepted the channel" }
                Pair(this, listOf(ChannelAction.Message.Send(TxAbort(channelId, InvalidRbfTxConfirmed(channelId, commitments.fundingTxId).message))))
            }
            event is ChannelEvent.MessageReceived && event.message is FundingLocked -> {
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
                    commitments.copy(remoteNextCommitInfo = Either.Right(event.message.nextPerCommitmentPoint)),
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
            event is ChannelEvent.MessageReceived && event.message is Error -> handleRemoteError(event.message)
            event is ChannelEvent.WatchReceived && event.watch is WatchEventSpent -> when (event.watch.tx.txid) {
                commitments.remoteCommit.txid -> handleRemoteSpentCurrent(event.watch.tx)
                else -> handleRemoteSpentOther(event.watch.tx)
            }
            event is ChannelEvent.ExecuteCommand -> when (event.command) {
                is CMD_CLOSE -> Pair(this, listOf(ChannelAction.ProcessCmdRes.NotExecuted(event.command, CommandUnavailableInThisState(channelId, this::class.toString()))))
                is CMD_FORCECLOSE -> handleLocalError(event, ForcedLocalCommit(channelId))
                else -> unhandled(event)
            }
            event is ChannelEvent.CheckHtlcTimeout -> Pair(this, listOf())
            event is ChannelEvent.NewBlock -> Pair(this.copy(currentTip = Pair(event.height, event.Header)), listOf())
            event is ChannelEvent.SetOnChainFeerates -> Pair(this.copy(currentOnChainFeerates = event.feerates), listOf())
            event is ChannelEvent.Disconnected -> Pair(Offline(this), listOf())
            else -> unhandled(event)
        }
    }

    override fun handleLocalError(event: ChannelEvent, t: Throwable): Pair<ChannelState, List<ChannelAction>> {
        logger.error(t) { "c:$channelId error on event ${event::class} in state ${this::class}" }
        val error = Error(channelId, t.message)
        return when {
            nothingAtStake() -> Pair(Aborted(staticParams, currentTip, currentOnChainFeerates), listOf(ChannelAction.Message.Send(error)))
            else -> spendLocalCurrent().run { copy(second = second + ChannelAction.Message.Send(error)) }
        }
    }
}
