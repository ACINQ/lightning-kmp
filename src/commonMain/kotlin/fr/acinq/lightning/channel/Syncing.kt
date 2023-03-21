package fr.acinq.lightning.channel

import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.lightning.ShortChannelId
import fr.acinq.lightning.blockchain.*
import fr.acinq.lightning.channel.Channel.ANNOUNCEMENTS_MINCONF
import fr.acinq.lightning.channel.Channel.handleSync
import fr.acinq.lightning.serialization.Encryption.from
import fr.acinq.lightning.utils.Either
import fr.acinq.lightning.utils.Try
import fr.acinq.lightning.utils.runTrying
import fr.acinq.lightning.utils.toByteVector
import fr.acinq.lightning.wire.*

/**
 * waitForTheirReestablishMessage == true means that we want to wait until we've received their channel_reestablish message before
 * we send ours (for example, to extract encrypted backup data from extra fields)
 * waitForTheirReestablishMessage == false means that we've already sent our channel_reestablish message
 */
data class Syncing(val state: PersistedChannelState, val waitForTheirReestablishMessage: Boolean) : ChannelState() {

    val channelId = state.channelId

    override fun ChannelContext.processInternal(cmd: ChannelCommand): Pair<ChannelState, List<ChannelAction>> {
        logger.warning { "syncing processing ${cmd::class}" }
        return when {
            cmd is ChannelCommand.MessageReceived && cmd.message is ChannelReestablish -> when {
                waitForTheirReestablishMessage -> {
                    val nextState = if (!cmd.message.channelData.isEmpty()) {
                        logger.info { "channel_reestablish includes a peer backup" }
                        when (val decrypted = runTrying { PersistedChannelState.from(staticParams.nodeParams.nodePrivateKey, cmd.message.channelData) }) {
                            is Try.Success -> {
                                val decryptedState = decrypted.get()
                                when {
                                    decryptedState is ChannelStateWithCommitments && state is ChannelStateWithCommitments && decryptedState.commitments.isMoreRecent(state.commitments) -> {
                                        logger.warning { "they have a more recent commitment, using it instead" }
                                        decryptedState
                                    }
                                    else -> {
                                        state
                                    }
                                }
                            }
                            is Try.Failure -> {
                                logger.error(decrypted.error) { "ignoring unreadable channel data" }
                                state
                            }
                        }
                    } else {
                        state
                    }
                    when (nextState) {
                        is WaitForFundingSigned -> {
                            TODO()
                        }
                        is ChannelStateWithCommitments -> {
                            val yourLastPerCommitmentSecret = nextState.commitments.remotePerCommitmentSecrets.lastIndex?.let { nextState.commitments.remotePerCommitmentSecrets.getHash(it) } ?: ByteVector32.Zeroes
                            val myCurrentPerCommitmentPoint = keyManager.commitmentPoint(nextState.commitments.params.localParams.channelKeys(keyManager).shaSeed, nextState.commitments.localCommitIndex)
                            val channelReestablish = ChannelReestablish(
                                channelId = nextState.channelId,
                                nextLocalCommitmentNumber = nextState.commitments.localCommitIndex + 1,
                                nextRemoteRevocationNumber = nextState.commitments.remoteCommitIndex,
                                yourLastCommitmentSecret = PrivateKey(yourLastPerCommitmentSecret),
                                myCurrentPerCommitmentPoint = myCurrentPerCommitmentPoint
                            ).withChannelData(nextState.commitments.remoteChannelData, logger)
                            val actions = listOf(ChannelAction.Message.Send(channelReestablish))
                            // now apply their reestablish message to the restored state
                            val (nextState1, actions1) = Syncing(nextState, waitForTheirReestablishMessage = false).run { processInternal(cmd) }
                            Pair(nextState1, actions + actions1)
                        }
                    }
                }
                state is LegacyWaitForFundingConfirmed -> {
                    Pair(state, listOf())
                }
                state is WaitForFundingSigned -> {
                    TODO()
                }
                state is WaitForFundingConfirmed -> {
                    logger.debug { "re-sending tx_signatures" }
                    Pair(state, listOf(ChannelAction.Message.Send(state.latestFundingTx.sharedTx.localSigs)))
                }
                state is WaitForChannelReady -> {
                    logger.debug { "re-sending channel_ready" }
                    val nextPerCommitmentPoint = keyManager.commitmentPoint(state.commitments.params.localParams.channelKeys(keyManager).shaSeed, 1)
                    val channelReady = ChannelReady(state.commitments.channelId, nextPerCommitmentPoint)
                    val actions = listOf(ChannelAction.Message.Send(channelReady))
                    Pair(state, actions)
                }
                state is LegacyWaitForFundingLocked -> {
                    logger.debug { "re-sending channel_ready" }
                    val nextPerCommitmentPoint = keyManager.commitmentPoint(state.commitments.params.localParams.channelKeys(keyManager).shaSeed, 1)
                    val channelReady = ChannelReady(state.commitments.channelId, nextPerCommitmentPoint)
                    val actions = listOf(ChannelAction.Message.Send(channelReady))
                    Pair(state, actions)
                }
                state is Normal -> {
                    when {
                        !Helpers.checkLocalCommit(state.commitments, cmd.message.nextRemoteRevocationNumber) -> {
                            // if next_remote_revocation_number is greater than our local commitment index, it means that either we are using an outdated commitment, or they are lying
                            // but first we need to make sure that the last per_commitment_secret that they claim to have received from us is correct for that next_remote_revocation_number minus 1
                            if (keyManager.commitmentSecret(state.commitments.params.localParams.channelKeys(keyManager).shaSeed, cmd.message.nextRemoteRevocationNumber - 1) == cmd.message.yourLastCommitmentSecret) {
                                // their data checks out, we indeed seem to be using an old revoked commitment, and must absolutely *NOT* publish it, because that would be a cheating attempt and they
                                // would punish us by taking all the funds in the channel
                                logger.warning { "counterparty proved that we have an outdated (revoked) local commitment!!! ourCommitmentNumber=${state.commitments.localCommitIndex} theirCommitmentNumber=${cmd.message.nextRemoteRevocationNumber}" }
                            } else {
                                // they are deliberately trying to fool us into thinking we have a late commitment, but we cannot risk publishing it ourselves, because it may really be revoked!
                                logger.warning { "counterparty claims that we have an outdated commitment, but they sent an invalid proof, so our commitment may or may not be revoked: ourLocalCommitmentNumber=${state.commitments.localCommitIndex} theirRemoteCommitmentNumber=${cmd.message.nextRemoteRevocationNumber}" }
                            }
                            val exc = PleasePublishYourCommitment(channelId)
                            val error = Error(channelId, exc.message.encodeToByteArray().toByteVector())
                            val nextState = WaitForRemotePublishFutureCommitment(state.commitments, cmd.message)
                            val actions = listOf(
                                ChannelAction.Storage.StoreState(nextState),
                                ChannelAction.Message.Send(error)
                            )
                            Pair(nextState, actions)
                        }
                        !Helpers.checkRemoteCommit(state.commitments, cmd.message.nextLocalCommitmentNumber) -> {
                            // if next_local_commit_number is more than one more our remote commitment index, it means that either we are using an outdated commitment, or they are lying
                            logger.warning { "counterparty says that they have a more recent commitment than the one we know of!!! ourCommitmentNumber=${state.commitments.latest.nextRemoteCommit?.commit?.index ?: state.commitments.latest.remoteCommit.index} theirCommitmentNumber=${cmd.message.nextLocalCommitmentNumber}" }
                            // there is no way to make sure that they are saying the truth, the best thing to do is ask them to publish their commitment right now
                            // maybe they will publish their commitment, in that case we need to remember their commitment point in order to be able to claim our outputs
                            // not that if they don't comply, we could publish our own commitment (it is not stale, otherwise we would be in the case above)
                            val exc = PleasePublishYourCommitment(channelId)
                            val error = Error(channelId, exc.message.encodeToByteArray().toByteVector())
                            val nextState = WaitForRemotePublishFutureCommitment(state.commitments, cmd.message)
                            val actions = listOf(
                                ChannelAction.Storage.StoreState(nextState),
                                ChannelAction.Message.Send(error)
                            )
                            Pair(nextState, actions)
                        }
                        else -> {
                            // normal case, our data is up-to-date
                            val actions = ArrayList<ChannelAction>()
                            if (cmd.message.nextLocalCommitmentNumber == 1L && state.commitments.localCommitIndex == 0L) {
                                // If next_local_commitment_number is 1 in both the channel_reestablish it sent and received, then the node MUST retransmit funding_locked, otherwise it MUST NOT
                                logger.debug { "re-sending channel_ready" }
                                val nextPerCommitmentPoint = keyManager.commitmentPoint(state.commitments.params.localParams.channelKeys(keyManager).shaSeed, 1)
                                val channelReady = ChannelReady(state.commitments.channelId, nextPerCommitmentPoint)
                                actions.add(ChannelAction.Message.Send(channelReady))
                            }

                            try {
                                val (commitments1, sendQueue1) = handleSync(cmd.message, state, keyManager, logger)
                                actions.addAll(sendQueue1)

                                // BOLT 2: A node if it has sent a previous shutdown MUST retransmit shutdown.
                                state.localShutdown?.let {
                                    logger.debug { "re-sending local shutdown" }
                                    actions.add(ChannelAction.Message.Send(it))
                                }

                                if (!state.buried) {
                                    // even if we were just disconnected/reconnected, we need to put back the watch because the event may have been
                                    // fired while we were in OFFLINE (if not, the operation is idempotent anyway)
                                    val watchConfirmed = WatchConfirmed(
                                        channelId,
                                        state.commitments.latest.commitInput.outPoint.txid,
                                        state.commitments.latest.commitInput.txOut.publicKeyScript,
                                        ANNOUNCEMENTS_MINCONF.toLong(),
                                        BITCOIN_FUNDING_DEEPLYBURIED
                                    )
                                    actions.add(ChannelAction.Blockchain.SendWatch(watchConfirmed))
                                }

                                logger.info { "switching to ${state::class.simpleName}" }
                                Pair(state.copy(commitments = commitments1), actions)
                            } catch (e: RevocationSyncError) {
                                val error = Error(channelId, e.message)
                                state.run { spendLocalCurrent() }.run { copy(second = second + ChannelAction.Message.Send(error)) }
                            }
                        }
                    }
                }
                // BOLT 2: A node if it has sent a previous shutdown MUST retransmit shutdown.
                // negotiation restarts from the beginning, and is initialized by the initiator
                // note: in any case we still need to keep all previously sent closing_signed, because they may publish one of them
                state is Negotiating && state.commitments.params.localParams.isInitiator -> {
                    // we could use the last closing_signed we sent, but network fees may have changed while we were offline so it is better to restart from scratch
                    val (closingTx, closingSigned) = Helpers.Closing.makeFirstClosingTx(
                        keyManager,
                        state.commitments.latest,
                        state.localShutdown.scriptPubKey.toByteArray(),
                        state.remoteShutdown.scriptPubKey.toByteArray(),
                        state.closingFeerates ?: ClosingFeerates(currentOnChainFeerates.mutualCloseFeerate)
                    )
                    val closingTxProposed1 = state.closingTxProposed + listOf(listOf(ClosingTxProposed(closingTx, closingSigned)))
                    val nextState = state.copy(closingTxProposed = closingTxProposed1)
                    val actions = listOf(
                        ChannelAction.Storage.StoreState(nextState),
                        ChannelAction.Message.Send(state.localShutdown),
                        ChannelAction.Message.Send(closingSigned)
                    )
                    return Pair(nextState, actions)
                }
                state is Negotiating -> {
                    // we start a new round of negotiation
                    val closingTxProposed1 = if (state.closingTxProposed.last().isEmpty()) state.closingTxProposed else state.closingTxProposed + listOf(listOf())
                    val nextState = state.copy(closingTxProposed = closingTxProposed1)
                    val actions = listOf(
                        ChannelAction.Storage.StoreState(nextState),
                        ChannelAction.Message.Send(state.localShutdown)
                    )
                    return Pair(nextState, actions)
                }
                else -> unhandled(cmd)
            }
            cmd is ChannelCommand.WatchReceived && state is ChannelStateWithCommitments -> when (val watch = cmd.watch) {
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
                            ChannelAction.Blockchain.PublishTx(watch.tx),
                            ChannelAction.Blockchain.SendWatch(WatchConfirmed(channelId, watch.tx, staticParams.nodeParams.minDepthBlocks.toLong(), BITCOIN_TX_CONFIRMED(watch.tx)))
                        )
                        Pair(nextState, actions)
                    }
                    else -> state.run { handlePotentialForceClose(watch) }
                }
                is WatchEventConfirmed -> {
                    if (watch.event is BITCOIN_FUNDING_DEPTHOK || watch.event is BITCOIN_FUNDING_DEEPLYBURIED) {
                        when (val res = state.run { acceptFundingTxConfirmed(watch) }) {
                            is Either.Left -> Pair(this@Syncing, listOf())
                            is Either.Right -> {
                                val (commitments1, _, actions) = res.value
                                val nextState = when (state) {
                                    is WaitForFundingConfirmed -> {
                                        logger.info { "was confirmed while syncing at blockHeight=${watch.blockHeight} txIndex=${watch.txIndex} with funding txid=${watch.tx.txid}" }
                                        val nextPerCommitmentPoint = keyManager.commitmentPoint(commitments1.params.localParams.channelKeys(keyManager).shaSeed, 1)
                                        val channelReady = ChannelReady(channelId, nextPerCommitmentPoint, TlvStream(listOf(ChannelReadyTlv.ShortChannelIdTlv(ShortChannelId.peerId(staticParams.nodeParams.nodeId)))))
                                        val shortChannelId = ShortChannelId(watch.blockHeight, watch.txIndex, commitments1.latest.commitInput.outPoint.index.toInt())
                                        WaitForChannelReady(commitments1, shortChannelId, channelReady)
                                    }
                                    else -> state
                                }
                                Pair(this@Syncing.copy(state = nextState), actions + listOf(ChannelAction.Storage.StoreState(nextState as PersistedChannelState)))
                            }
                        }
                    } else {
                        Pair(this@Syncing, listOf())
                    }
                }
            }
            cmd is ChannelCommand.CheckHtlcTimeout && state is ChannelStateWithCommitments -> {
                val (newState, actions) = state.run { checkHtlcTimeout() }
                when (newState) {
                    is Closing -> Pair(newState, actions)
                    is Closed -> Pair(newState, actions)
                    else -> Pair(Syncing(newState as PersistedChannelState, waitForTheirReestablishMessage), actions)
                }
            }
            cmd is ChannelCommand.Disconnected -> Pair(Offline(state), listOf())
            cmd is ChannelCommand.ExecuteCommand && cmd.command is CMD_FORCECLOSE -> {
                val (newState, actions) = state.run { process(cmd) }
                when (newState) {
                    is Closing -> Pair(newState, actions)
                    is Closed -> Pair(newState, actions)
                    else -> Pair(Syncing(newState as PersistedChannelState, waitForTheirReestablishMessage), actions)
                }
            }
            else -> unhandled(cmd)
        }
    }

    override fun ChannelContext.handleLocalError(cmd: ChannelCommand, t: Throwable): Pair<ChannelState, List<ChannelAction>> {
        logger.error(t) { "error on command ${cmd::class.simpleName} in state ${this@Syncing::class.simpleName}" }
        return Pair(this@Syncing, listOf(ChannelAction.ProcessLocalError(t, cmd)))
    }

}
