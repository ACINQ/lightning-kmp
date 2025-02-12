package fr.acinq.lightning.channel.states

import fr.acinq.bitcoin.utils.Either
import fr.acinq.lightning.Feature
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
                        val nextState = state.updateCommitments(state.commitments.copy(params = state.commitments.params.updateFeatures(cmd.localInit, cmd.remoteInit)))
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
                        val sendChannelReestablish = !staticParams.nodeParams.features.hasFeature(Feature.ChannelBackupClient)
                        val actions = buildList {
                            if (!sendChannelReestablish) {
                                // We wait for them to go first, which lets us restore from the latest backup if we've lost data.
                                logger.info { "waiting for their channel_reestablish message" }
                            } else {
                                val channelReestablish = state.run { createChannelReestablish() }
                                add(ChannelAction.Message.Send(channelReestablish))
                            }
                        }
                        val nextState = state.updateCommitments(state.commitments.copy(params = state.commitments.params.updateFeatures(cmd.localInit, cmd.remoteInit)))
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
                                            val nextPerCommitmentPoint = commitments1.params.localParams.channelKeys(keyManager).commitmentPoint(1)
                                            val channelReady = ChannelReady(channelId, nextPerCommitmentPoint, TlvStream(ChannelReadyTlv.ShortChannelIdTlv(ShortChannelId.peerId(staticParams.nodeParams.nodeId))))
                                            val shortChannelId = ShortChannelId(watch.blockHeight, watch.txIndex, commitments1.latest.commitInput.outPoint.index.toInt())
                                            WaitForChannelReady(commitments1, shortChannelId, channelReady)
                                        }
                                        else -> state
                                    }
                                    Pair(this@Offline.copy(state = nextState), actions + listOf(ChannelAction.Storage.StoreState(nextState)))
                                }
                            }
                        }
                        else -> {
                            logger.warning { "unexpected watch-confirmed while offline: ${watch.event}" }
                            Pair(this@Offline, listOf())
                        }
                    }
                    is WatchSpentTriggered -> when {
                        state is Negotiating && state.closingTxProposed.flatten().any { it.unsignedTx.tx.txid == watch.spendingTx.txid } -> {
                            logger.info { "closing tx published: closingTxId=${watch.spendingTx.txid}" }
                            val closingTx = state.getMutualClosePublished(watch.spendingTx)
                            val nextState = Closing(
                                state.commitments,
                                waitingSinceBlock = currentBlockHeight.toLong(),
                                mutualCloseProposed = state.closingTxProposed.flatten().map { it.unsignedTx },
                                mutualClosePublished = listOf(closingTx)
                            )
                            val actions = listOf(
                                ChannelAction.Storage.StoreState(nextState),
                                ChannelAction.Blockchain.PublishTx(closingTx),
                                ChannelAction.Blockchain.SendWatch(WatchConfirmed(channelId, watch.spendingTx, staticParams.nodeParams.minDepth(state.commitments.capacityMax), WatchConfirmed.ClosingTxConfirmed))
                            )
                            Pair(nextState, actions)
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
            is ChannelCommand.Close.MutualClose -> Pair(this@Offline, listOf(ChannelAction.ProcessCmdRes.NotExecuted(cmd, CommandUnavailableInThisState(channelId, stateName))))
            is ChannelCommand.Close.ForceClose -> state.run { handleLocalError(cmd, ForcedLocalCommit(channelId)) }
            is ChannelCommand.Init -> unhandled(cmd)
            is ChannelCommand.Funding -> unhandled(cmd)
            is ChannelCommand.Closing -> unhandled(cmd)
        }
    }
}
