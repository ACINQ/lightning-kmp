package fr.acinq.lightning.channel.states

import fr.acinq.bitcoin.utils.Either
import fr.acinq.lightning.ShortChannelId
import fr.acinq.lightning.blockchain.WatchConfirmed
import fr.acinq.lightning.blockchain.WatchConfirmedTriggered
import fr.acinq.lightning.blockchain.WatchSpentTriggered
import fr.acinq.lightning.channel.*
import fr.acinq.lightning.wire.ChannelReady
import fr.acinq.lightning.wire.ChannelReadyTlv
import fr.acinq.lightning.wire.Error
import fr.acinq.lightning.wire.TlvStream

data class Offline(val state: PersistedChannelState) : ChannelState() {

    val channelId = state.channelId

    override suspend fun ChannelContext.processInternal(cmd: ChannelCommand): Pair<ChannelState, List<ChannelAction>> {
        return when (cmd) {
            is ChannelCommand.Connected -> {
                when (state) {
                    is WaitForRemotePublishFutureCommitment -> {
                        // they already proved that we have an outdated commitment
                        // there isn't much to do except asking them again to publish their current commitment by sending an error
                        val exc = PleasePublishYourCommitment(channelId)
                        val error = Error(channelId, exc.message)
                        val nextState = state.updateCommitments(state.commitments.copy(channelParams = state.commitments.channelParams.updateFeatures(cmd.localInit, cmd.remoteInit)))
                        Pair(nextState, listOf(ChannelAction.Message.Send(error)))
                    }
                    is WaitForFundingSigned -> {
                        logger.info { "syncing ${state::class}" }
                        // We send our channel_reestablish without waiting for theirs, even if we're using backups.
                        // Otherwise we could end up waiting forever for them if they have forgotten the channel because they didn't receive our tx_complete.
                        val channelReestablish = state.run { createChannelReestablish() }
                        val actions = listOf(ChannelAction.Message.Send(channelReestablish))
                        val nextState = state.copy(channelParams = state.channelParams.updateFeatures(cmd.localInit, cmd.remoteInit))
                        Pair(Syncing(nextState, channelReestablishSent = true), actions)
                    }
                    is ChannelStateWithCommitments -> {
                        logger.info { "syncing ${state::class}" }
                        val sendChannelReestablish = !staticParams.nodeParams.usePeerStorage
                        val actions = buildList {
                            if (!sendChannelReestablish) {
                                // We wait for them to go first, which lets us restore from the latest backup if we've lost data.
                                logger.info { "waiting for their peer_storage_retrieval and channel_reestablish message" }
                            } else {
                                val channelReestablish = state.run { createChannelReestablish() }
                                add(ChannelAction.Message.Send(channelReestablish))
                            }
                        }
                        val nextState = state.updateCommitments(state.commitments.copy(channelParams = state.commitments.channelParams.updateFeatures(cmd.localInit, cmd.remoteInit)))
                        Pair(Syncing(nextState, channelReestablishSent = sendChannelReestablish), actions)
                    }
                }
            }
            is ChannelCommand.Disconnected -> unhandled(cmd)
            is ChannelCommand.MessageReceived -> unhandled(cmd)
            is ChannelCommand.WatchReceived -> when (state) {
                is ChannelStateWithCommitments -> when (val watch = cmd.watch) {
                    is WatchConfirmedTriggered -> when (watch.event) {
                        WatchConfirmed.ChannelFundingDepthOk -> {
                            when (val res = state.run { acceptFundingTxConfirmed(watch) }) {
                                is Either.Left -> Pair(this@Offline, listOf())
                                is Either.Right -> {
                                    val (commitments1, _, actions) = res.value
                                    val nextState = when (state) {
                                        is WaitForFundingConfirmed -> {
                                            logger.info { "was confirmed while offline at blockHeight=${watch.blockHeight} txIndex=${watch.txIndex} with funding txid=${watch.tx.txid}" }
                                            val nextPerCommitmentPoint = commitments1.channelParams.localParams.channelKeys(keyManager).commitmentPoint(1)
                                            val channelReady = ChannelReady(channelId, nextPerCommitmentPoint, TlvStream(ChannelReadyTlv.ShortChannelIdTlv(ShortChannelId.peerId(staticParams.nodeParams.nodeId))))
                                            val shortChannelId = ShortChannelId(watch.blockHeight, watch.txIndex, commitments1.latest.fundingInput.index.toInt())
                                            WaitForChannelReady(commitments1, mapOf(), shortChannelId, channelReady)
                                        }
                                        else -> state
                                    }
                                    Pair(this@Offline.copy(state = nextState), actions + listOf(ChannelAction.Storage.StoreState(nextState)))
                                }
                            }
                        }
                        WatchConfirmed.ClosingTxConfirmed -> when {
                            state is Negotiating && state.publishedClosingTxs.any { it.tx.txid == watch.tx.txid } -> {
                                // One of our published transactions confirmed, the channel is now closed.
                                state.run { completeMutualClose(publishedClosingTxs.first { it.tx.txid == watch.tx.txid }) }
                            }
                            state is Negotiating && state.proposedClosingTxs.flatMap { it.all }.any { it.tx.txid == watch.tx.txid } -> {
                                // A transaction that we proposed for which they didn't send us their signature was confirmed, the channel is now closed.
                                state.run { completeMutualClose(getMutualClosePublished(watch.tx)) }
                            }
                            else -> {
                                logger.warning { "unknown closing transaction confirmed with txId=${watch.tx.txid}" }
                                Pair(this@Offline, listOf())
                            }
                        }
                        else -> Pair(this@Offline, listOf())
                    }
                    is WatchSpentTriggered -> when {
                        state is Negotiating && state.publishedClosingTxs.any { it.tx.txid == watch.spendingTx.txid } -> {
                            // This is one of the transactions we already published, we watch for confirmations.
                            val actions = listOf(ChannelAction.Blockchain.SendWatch(WatchConfirmed(channelId, watch.spendingTx, staticParams.nodeParams.minDepthBlocks, WatchConfirmed.ClosingTxConfirmed)))
                            Pair(this@Offline, actions)
                        }
                        state is Negotiating && state.proposedClosingTxs.flatMap { it.all }.any { it.tx.txid == watch.spendingTx.txid } -> {
                            // They published one of our closing transactions without sending us their signature.
                            val closingTx = state.run { getMutualClosePublished(watch.spendingTx) }
                            val nextState = state.copy(publishedClosingTxs = state.publishedClosingTxs + closingTx)
                            val actions = listOf(
                                ChannelAction.Storage.StoreState(nextState),
                                ChannelAction.Blockchain.PublishTx(closingTx),
                                ChannelAction.Blockchain.SendWatch(WatchConfirmed(channelId, watch.spendingTx, staticParams.nodeParams.minDepthBlocks, WatchConfirmed.ClosingTxConfirmed))
                            )
                            Pair(Offline(nextState), actions)
                        }
                        else -> {
                            val (nextState, actions) = state.run { handlePotentialForceClose(watch) }
                            when (nextState) {
                                is Closing -> Pair(nextState, actions)
                                is Closed -> Pair(nextState, actions)
                                else -> Pair(Offline(nextState), actions)
                            }
                        }
                    }
                }
                is WaitForFundingSigned -> Pair(this@Offline, listOf())
            }
            is ChannelCommand.Commitment.CheckHtlcTimeout -> when (state) {
                is ChannelStateWithCommitments -> {
                    val (newState, actions) = state.run { checkHtlcTimeout() }
                    when (newState) {
                        is Closing -> Pair(newState, actions)
                        is Closed -> Pair(newState, actions)
                        else -> Pair(Offline(newState), actions)
                    }
                }
                is WaitForFundingSigned -> Pair(state, listOf())
            }
            is ChannelCommand.Commitment -> unhandled(cmd)
            is ChannelCommand.Htlc -> unhandled(cmd)
            is ChannelCommand.Close.MutualClose -> {
                cmd.replyTo.complete(ChannelCloseResponse.Failure.ChannelOffline)
                Pair(this@Offline, listOf(ChannelAction.ProcessCmdRes.NotExecuted(cmd, CommandUnavailableInThisState(channelId, stateName))))
            }
            is ChannelCommand.Close.ForceClose -> state.run { handleLocalError(cmd, ForcedLocalCommit(channelId)) }
            is ChannelCommand.Init -> unhandled(cmd)
            is ChannelCommand.Funding -> unhandled(cmd)
            is ChannelCommand.Closing -> unhandled(cmd)
        }
    }
}
