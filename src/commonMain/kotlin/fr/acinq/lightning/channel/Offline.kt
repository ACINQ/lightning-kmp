package fr.acinq.lightning.channel

import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.lightning.Feature
import fr.acinq.lightning.blockchain.*
import fr.acinq.lightning.wire.ChannelReestablish
import fr.acinq.lightning.wire.Error

data class Offline(val state: ChannelStateWithCommitments) : ChannelState() {

    val channelId = state.channelId

    override fun ChannelContext.processInternal(cmd: ChannelCommand): Pair<ChannelState, List<ChannelAction>> {
        logger.warning { "c:$channelId offline processing ${cmd::class}" }
        return when {
            cmd is ChannelCommand.Connected -> {
                when {
                    state is WaitForRemotePublishFutureCommitment -> {
                        // they already proved that we have an outdated commitment
                        // there isn't much to do except asking them again to publish their current commitment by sending an error
                        val exc = PleasePublishYourCommitment(channelId)
                        val error = Error(channelId, exc.message)
                        val nextState = state.updateCommitments(state.commitments.updateFeatures(cmd.localInit, cmd.remoteInit))
                        Pair(nextState, listOf(ChannelAction.Message.Send(error)))
                    }
                    staticParams.nodeParams.features.hasFeature(Feature.ChannelBackupClient) -> {
                        // We wait for them to go first, which lets us restore from the latest backup if we've lost data.
                        logger.info { "c:$channelId syncing ${state::class}, waiting fo their channelReestablish message" }
                        val nextState = state.updateCommitments(state.commitments.updateFeatures(cmd.localInit, cmd.remoteInit))
                        Pair(Syncing(nextState, true), listOf())
                    }
                    else -> {
                        val yourLastPerCommitmentSecret = state.commitments.remotePerCommitmentSecrets.lastIndex?.let { state.commitments.remotePerCommitmentSecrets.getHash(it) } ?: ByteVector32.Zeroes
                        val myCurrentPerCommitmentPoint = keyManager.commitmentPoint(state.commitments.localParams.channelKeys(keyManager).shaSeed, state.commitments.localCommit.index)
                        val channelReestablish = ChannelReestablish(
                            channelId = channelId,
                            nextLocalCommitmentNumber = state.commitments.localCommit.index + 1,
                            nextRemoteRevocationNumber = state.commitments.remoteCommit.index,
                            yourLastCommitmentSecret = PrivateKey(yourLastPerCommitmentSecret),
                            myCurrentPerCommitmentPoint = myCurrentPerCommitmentPoint
                        ).withChannelData(state.commitments.remoteChannelData)
                        logger.info { "c:$channelId syncing ${state::class}" }
                        val nextState = state.updateCommitments(state.commitments.updateFeatures(cmd.localInit, cmd.remoteInit))
                        Pair(Syncing(nextState, false), listOf(ChannelAction.Message.Send(channelReestablish)))
                    }
                }
            }
            cmd is ChannelCommand.WatchReceived && cmd.watch is WatchEventConfirmed -> {
                if (cmd.watch.event is BITCOIN_FUNDING_DEPTHOK || cmd.watch.event is BITCOIN_FUNDING_DEEPLYBURIED) {
                    val watchSpent = WatchSpent(channelId, cmd.watch.tx, state.commitments.commitInput.outPoint.index.toInt(), BITCOIN_FUNDING_SPENT)
                    val nextState = when {
                        state is WaitForFundingConfirmed && cmd.watch.tx.txid == state.commitments.fundingTxId -> {
                            logger.info { "c:$channelId was confirmed while offline at blockHeight=${cmd.watch.blockHeight} txIndex=${cmd.watch.txIndex} with funding txid=${cmd.watch.tx.txid}" }
                            state.copy(previousFundingTxs = listOf())
                        }
                        state is WaitForFundingConfirmed && state.previousFundingTxs.find { cmd.watch.tx.txid == it.second.fundingTxId } != null -> {
                            val (fundingTx, commitments) = state.previousFundingTxs.first { cmd.watch.tx.txid == it.second.fundingTxId }
                            logger.info { "c:$channelId was confirmed while offline at blockHeight=${cmd.watch.blockHeight} txIndex=${cmd.watch.txIndex} with a previous funding txid=${cmd.watch.tx.txid}" }
                            state.copy(fundingTx = fundingTx, commitments = commitments, previousFundingTxs = listOf())
                        }
                        else -> state
                    }
                    Pair(this@Offline.copy(state = nextState), listOf(ChannelAction.Blockchain.SendWatch(watchSpent)))
                } else {
                    Pair(this@Offline, listOf())
                }
            }
            cmd is ChannelCommand.WatchReceived && cmd.watch is WatchEventSpent -> when {
                state is Negotiating && state.closingTxProposed.flatten().any { it.unsignedTx.tx.txid == cmd.watch.tx.txid } -> {
                    logger.info { "c:$channelId closing tx published: closingTxId=${cmd.watch.tx.txid}" }
                    val closingTx = state.getMutualClosePublished(cmd.watch.tx)
                    val nextState = Closing(
                        state.commitments,
                        fundingTx = null,
                        waitingSinceBlock = currentBlockHeight.toLong(),
                        alternativeCommitments = listOf(),
                        mutualCloseProposed = state.closingTxProposed.flatten().map { it.unsignedTx },
                        mutualClosePublished = listOf(closingTx)
                    )
                    val actions = listOf(
                        ChannelAction.Storage.StoreState(nextState),
                        ChannelAction.Blockchain.PublishTx(cmd.watch.tx),
                        ChannelAction.Blockchain.SendWatch(WatchConfirmed(channelId, cmd.watch.tx, staticParams.nodeParams.minDepthBlocks.toLong(), BITCOIN_TX_CONFIRMED(cmd.watch.tx)))
                    )
                    Pair(nextState, actions)
                }
                cmd.watch.tx.txid == state.commitments.remoteCommit.txid -> state.run { handleRemoteSpentCurrent(cmd.watch.tx) }
                cmd.watch.tx.txid == state.commitments.remoteNextCommitInfo.left?.nextRemoteCommit?.txid -> state.run { handleRemoteSpentNext(cmd.watch.tx) }
                state is WaitForRemotePublishFutureCommitment -> state. run { handleRemoteSpentFuture(cmd.watch.tx) }
                else -> state.run { handleRemoteSpentOther(cmd.watch.tx) }
            }
            cmd is ChannelCommand.CheckHtlcTimeout -> {
                val (newState, actions) = state.run { checkHtlcTimeout() }
                when (newState) {
                    is Closing -> Pair(newState, actions)
                    is Closed -> Pair(newState, actions)
                    else -> Pair(Offline(newState as ChannelStateWithCommitments), actions)
                }
            }
            cmd is ChannelCommand.NewBlock -> {
                val (newState, actions) = state. run { process(cmd) }
                when (newState) {
                    is Closing -> Pair(newState, actions)
                    is Closed -> Pair(newState, actions)
                    else -> Pair(Offline(newState as ChannelStateWithCommitments), actions)
                }
            }
            cmd is ChannelCommand.ExecuteCommand && cmd.command is CMD_FORCECLOSE -> {
                val (newState, actions) = state.run { process(cmd) }
                when (newState) {
                    // NB: it doesn't make sense to try to send outgoing messages if we're offline.
                    is Closing -> Pair(newState, actions.filterNot { it is ChannelAction.Message.Send })
                    is Closed -> Pair(newState, actions.filterNot { it is ChannelAction.Message.Send })
                    else -> Pair(Offline(newState as ChannelStateWithCommitments), actions)
                }
            }
            else -> unhandled(cmd)
        }
    }

    override fun ChannelContext.handleLocalError(cmd: ChannelCommand, t: Throwable): Pair<ChannelState, List<ChannelAction>> {
        logger.error(t) { "c:$channelId error on event ${cmd::class} in state ${this::class}" }
        return Pair(this@Offline, listOf(ChannelAction.ProcessLocalError(t, cmd)))
    }
}
