package fr.acinq.lightning.channel.fsm

import fr.acinq.lightning.Feature
import fr.acinq.lightning.ShortChannelId
import fr.acinq.lightning.blockchain.*
import fr.acinq.lightning.channel.ChannelAction
import fr.acinq.lightning.channel.ChannelCommand
import fr.acinq.lightning.channel.PleasePublishYourCommitment
import fr.acinq.lightning.utils.Either
import fr.acinq.lightning.wire.ChannelReady
import fr.acinq.lightning.wire.ChannelReadyTlv
import fr.acinq.lightning.wire.Error
import fr.acinq.lightning.wire.TlvStream

data class Offline(val state: PersistedChannelState) : ChannelState() {

    val channelId = state.channelId

    override fun ChannelContext.processInternal(cmd: ChannelCommand): Pair<ChannelState, List<ChannelAction>> {
        return when {
            cmd is ChannelCommand.Connected -> {
                when {
                    state is WaitForRemotePublishFutureCommitment -> {
                        // they already proved that we have an outdated commitment
                        // there isn't much to do except asking them again to publish their current commitment by sending an error
                        val exc = PleasePublishYourCommitment(channelId)
                        val error = Error(channelId, exc.message)
                        val nextState = state.updateCommitments(state.commitments.copy(params = state.commitments.params.updateFeatures(cmd.localInit, cmd.remoteInit)))
                        Pair(nextState, listOf(ChannelAction.Message.Send(error)))
                    }
                    else -> {
                        logger.info { "syncing ${state::class}" }
                        val nextState = when (state) {
                            is WaitForFundingSigned -> state.copy(channelParams = state.channelParams.updateFeatures(cmd.localInit, cmd.remoteInit))
                            is ChannelStateWithCommitments -> state.updateCommitments(state.commitments.copy(params = state.commitments.params.updateFeatures(cmd.localInit, cmd.remoteInit)))
                        }
                        val actions = buildList {
                            if (staticParams.nodeParams.features.hasFeature(Feature.ChannelBackupClient)) {
                                // We wait for them to go first, which lets us restore from the latest backup if we've lost data.
                                logger.info { "waiting for their channelReestablish message" }
                            } else {
                                val channelReestablish = state.run { createChannelReestablish() }
                                add(ChannelAction.Message.Send(channelReestablish))
                            }
                        }
                        Pair(Syncing(nextState), actions)
                    }
                }
            }
            cmd is ChannelCommand.WatchReceived && state is ChannelStateWithCommitments -> when (val watch = cmd.watch) {
                is WatchEventConfirmed -> {
                    if (watch.event is BITCOIN_FUNDING_DEPTHOK) {
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
                    } else {
                        Pair(this@Offline, listOf())
                    }
                }
                is WatchEventSpent -> when {
                    state is Negotiating && state.closingTxProposed.flatten().any { it.unsignedTx.tx.txid == watch.tx.txid } -> {
                        logger.info { "closing tx published: closingTxId=${watch.tx.txid}" }
                        val closingTx = state.getMutualClosePublished(watch.tx)
                        val nextState = Closing(
                            state.commitments,
                            waitingSinceBlock = currentBlockHeight.toLong(),
                            mutualCloseProposed = state.closingTxProposed.flatten().map { it.unsignedTx },
                            mutualClosePublished = listOf(closingTx)
                        )
                        val actions = listOf(
                            ChannelAction.Storage.StoreState(nextState),
                            ChannelAction.Blockchain.PublishTx(closingTx),
                            ChannelAction.Blockchain.SendWatch(WatchConfirmed(channelId, watch.tx, staticParams.nodeParams.minDepthBlocks.toLong(), BITCOIN_TX_CONFIRMED(watch.tx)))
                        )
                        Pair(nextState, actions)
                    }
                    else -> state.run { handlePotentialForceClose(watch) }
                }
            }
            cmd is ChannelCommand.CheckHtlcTimeout && state is ChannelStateWithCommitments -> {
                val (newState, actions) = state.run { checkHtlcTimeout() }
                when (newState) {
                    is Closing -> Pair(newState, actions)
                    is Closed -> Pair(newState, actions)
                    else -> Pair(Offline(newState), actions)
                }
            }
            cmd is ChannelCommand.Close.ForceClose -> {
                val (newState, actions) = state.run { process(cmd) }
                when (newState) {
                    // NB: it doesn't make sense to try to send outgoing messages if we're offline.
                    is Closing -> Pair(newState, actions.filterNot { it is ChannelAction.Message.Send })
                    is Closed -> Pair(newState, actions.filterNot { it is ChannelAction.Message.Send })
                    else -> Pair(Offline(newState as PersistedChannelState), actions)
                }
            }
            else -> unhandled(cmd)
        }
    }

    override fun ChannelContext.handleLocalError(cmd: ChannelCommand, t: Throwable): Pair<ChannelState, List<ChannelAction>> {
        logger.error(t) { "error on command ${cmd::class.simpleName} in state ${this@Offline::class.simpleName}" }
        return Pair(this@Offline, listOf(ChannelAction.ProcessLocalError(t, cmd)))
    }
}
