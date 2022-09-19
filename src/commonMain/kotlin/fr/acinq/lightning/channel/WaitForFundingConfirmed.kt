package fr.acinq.lightning.channel

import fr.acinq.bitcoin.BlockHeader
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.lightning.MilliSatoshi
import fr.acinq.lightning.ShortChannelId
import fr.acinq.lightning.blockchain.BITCOIN_FUNDING_SPENT
import fr.acinq.lightning.blockchain.WatchEventConfirmed
import fr.acinq.lightning.blockchain.WatchSpent
import fr.acinq.lightning.blockchain.fee.OnChainFeerates
import fr.acinq.lightning.wire.Error
import fr.acinq.lightning.wire.FundingLocked
import fr.acinq.lightning.wire.TxSignatures

data class WaitForFundingConfirmed(
    override val staticParams: StaticParams,
    override val currentTip: Pair<Int, BlockHeader>,
    override val currentOnChainFeerates: OnChainFeerates,
    override val commitments: Commitments,
    val fundingParams: InteractiveTxParams,
    val pushAmount: MilliSatoshi,
    val fundingTx: SignedSharedTransaction,
    val fundingPrivateKeys: List<PrivateKey>,
    val waitingSinceBlock: Long, // how many blocks have we been waiting for the funding tx to confirm
    val deferred: FundingLocked?,
) : ChannelStateWithCommitments() {
    override fun updateCommitments(input: Commitments): ChannelStateWithCommitments = this.copy(commitments = input)

    override fun processInternal(event: ChannelEvent): Pair<ChannelState, List<ChannelAction>> {
        return when {
            event is ChannelEvent.MessageReceived && event.message is TxSignatures -> {
                when (fundingTx) {
                    is PartiallySignedSharedTransaction -> when (val fullySignedTx = fundingTx.addRemoteSigs(event.message)) {
                        null -> {
                            logger.warning { "c:$channelId received invalid remote funding signatures for txId=${event.message.txId}" }
                            // The funding transaction may still confirm (since our peer should be able to generate valid signatures),
                            // so we cannot close the channel yet.
                            val actions = listOf(ChannelAction.Message.Send(Error(channelId, InvalidFundingSignature(channelId, event.message.txId).message)))
                            Pair(this, actions)
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
            }
            event is ChannelEvent.MessageReceived && event.message is FundingLocked -> Pair(this.copy(deferred = event.message), listOf())
            event is ChannelEvent.MessageReceived && event.message is Error -> handleRemoteError(event.message)
            event is ChannelEvent.WatchReceived && event.watch is WatchEventConfirmed -> {
                val watchSpent = WatchSpent(channelId, commitments.commitInput.outPoint.txid, commitments.commitInput.outPoint.index.toInt(), commitments.commitInput.txOut.publicKeyScript, BITCOIN_FUNDING_SPENT)
                val nextPerCommitmentPoint = keyManager.commitmentPoint(commitments.localParams.channelKeys.shaSeed, 1)
                val fundingLocked = FundingLocked(commitments.channelId, nextPerCommitmentPoint)
                // this is the temporary channel id that we will use in our channel_update message, the goal is to be able to use our channel
                // as soon as it reaches NORMAL state, and before it is announced on the network
                // (this id might be updated when the funding tx gets deeply buried, if there was a reorg in the meantime)
                val shortChannelId = ShortChannelId(event.watch.blockHeight, event.watch.txIndex, commitments.commitInput.outPoint.index.toInt())
                val nextState = WaitForFundingLocked(staticParams, currentTip, currentOnChainFeerates, commitments, shortChannelId, fundingLocked)
                val actions = listOf(
                    ChannelAction.Blockchain.SendWatch(watchSpent),
                    ChannelAction.Message.Send(fundingLocked),
                    ChannelAction.Storage.StoreState(nextState)
                )
                if (deferred != null) {
                    logger.info { "c:$channelId funding_locked has already been received" }
                    val (nextState1, actions1) = nextState.process(ChannelEvent.MessageReceived(deferred))
                    Pair(nextState1, actions + actions1)
                } else {
                    Pair(nextState, actions)
                }
            }
            event is ChannelEvent.ExecuteCommand && event.command is CMD_CLOSE -> Pair(this, listOf(ChannelAction.ProcessCmdRes.NotExecuted(event.command, CommandUnavailableInThisState(channelId, this::class.toString()))))
            event is ChannelEvent.ExecuteCommand && event.command is CMD_FORCECLOSE -> handleLocalError(event, ForcedLocalCommit(channelId))
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
            commitments.nothingAtStake() -> Pair(Aborted(staticParams, currentTip, currentOnChainFeerates), listOf(ChannelAction.Message.Send(error)))
            else -> spendLocalCurrent().run { copy(second = second + ChannelAction.Message.Send(error)) }
        }
    }
}
