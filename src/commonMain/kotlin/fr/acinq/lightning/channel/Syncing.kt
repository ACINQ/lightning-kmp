package fr.acinq.lightning.channel

import fr.acinq.bitcoin.BlockHeader
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.lightning.blockchain.*
import fr.acinq.lightning.blockchain.fee.OnChainFeerates
import fr.acinq.lightning.channel.Channel.ANNOUNCEMENTS_MINCONF
import fr.acinq.lightning.channel.Channel.handleSync
import fr.acinq.lightning.serialization.Serialization
import fr.acinq.lightning.utils.Try
import fr.acinq.lightning.utils.runTrying
import fr.acinq.lightning.utils.toByteVector
import fr.acinq.lightning.wire.ChannelReestablish
import fr.acinq.lightning.wire.Error
import fr.acinq.lightning.wire.FundingLocked

/**
 * waitForTheirReestablishMessage == true means that we want to wait until we've received their channel_reestablish message before
 * we send ours (for example, to extract encrypted backup data from extra fields)
 * waitForTheirReestablishMessage == false means that we've already sent our channel_reestablish message
 */
data class Syncing(val state: ChannelStateWithCommitments, val waitForTheirReestablishMessage: Boolean) : ChannelState() {
    override val staticParams: StaticParams get() = state.staticParams
    override val currentTip: Pair<Int, BlockHeader> get() = state.currentTip
    override val currentOnChainFeerates: OnChainFeerates get() = state.currentOnChainFeerates
    val channelId = state.channelId

    override fun processInternal(event: ChannelEvent): Pair<ChannelState, List<ChannelAction>> {
        logger.warning { "c:$channelId syncing processing ${event::class}" }
        return when {
            event is ChannelEvent.MessageReceived && event.message is ChannelReestablish -> when {
                waitForTheirReestablishMessage -> {
                    val nextState = if (!event.message.channelData.isEmpty()) {
                        logger.info { "c:$channelId channel_reestablish includes a peer backup" }
                        when (val decrypted = runTrying { Serialization.decrypt(state.staticParams.nodeParams.nodePrivateKey.value, event.message.channelData, staticParams.nodeParams) }) {
                            is Try.Success -> {
                                if (decrypted.get().commitments.isMoreRecent(state.commitments)) {
                                    logger.warning { "c:$channelId they have a more recent commitment, using it instead" }
                                    decrypted.get()
                                } else {
                                    logger.info { "c:$channelId ignoring their older backup" }
                                    state
                                }
                            }
                            is Try.Failure -> {
                                logger.error(decrypted.error) { "c:$channelId ignoring unreadable channel data" }
                                state
                            }
                        }
                    } else {
                        state
                    }
                    val yourLastPerCommitmentSecret = nextState.commitments.remotePerCommitmentSecrets.lastIndex?.let { nextState.commitments.remotePerCommitmentSecrets.getHash(it) } ?: ByteVector32.Zeroes
                    val myCurrentPerCommitmentPoint = keyManager.commitmentPoint(state.commitments.localParams.channelKeys.shaSeed, nextState.commitments.localCommit.index)
                    val channelReestablish = ChannelReestablish(
                        channelId = nextState.channelId,
                        nextLocalCommitmentNumber = nextState.commitments.localCommit.index + 1,
                        nextRemoteRevocationNumber = nextState.commitments.remoteCommit.index,
                        yourLastCommitmentSecret = PrivateKey(yourLastPerCommitmentSecret),
                        myCurrentPerCommitmentPoint = myCurrentPerCommitmentPoint
                    ).withChannelData(nextState.commitments.remoteChannelData)
                    val actions = buildList {
                        add(ChannelAction.Message.Send(channelReestablish))
                        if (state is WaitForFundingConfirmed) add(ChannelAction.Message.Send(state.fundingTx.localSigs))
                    }
                    // now apply their reestablish message to the restored state
                    val (nextState1, actions1) = Syncing(nextState, waitForTheirReestablishMessage = false).processInternal(event)
                    Pair(nextState1, actions + actions1)
                }
                state is LegacyWaitForFundingConfirmed -> {
                    val minDepth = Helpers.minDepthForFunding(staticParams.nodeParams, state.commitments.fundingAmount)
                    val watch = WatchConfirmed(state.channelId, state.commitments.fundingTxId, state.commitments.commitInput.txOut.publicKeyScript, minDepth.toLong(), BITCOIN_FUNDING_DEPTHOK)
                    Pair(state, listOf(ChannelAction.Blockchain.SendWatch(watch)))
                }
                state is WaitForFundingConfirmed -> {
                    val minDepth = Helpers.minDepthForFunding(staticParams.nodeParams, state.fundingParams.fundingAmount)
                    // we put back the watches (operation is idempotent) because the event may have been fired while we were in OFFLINE
                    val allCommitments = listOf(state.commitments) + state.previousFundingTxs.map { it.second }
                    val watches = allCommitments.map { WatchConfirmed(it.channelId, it.fundingTxId, it.commitInput.txOut.publicKeyScript, minDepth.toLong(), BITCOIN_FUNDING_DEPTHOK) }
                    val actions = buildList {
                        addAll(watches.map { ChannelAction.Blockchain.SendWatch(it) })
                        add(ChannelAction.Message.Send(state.fundingTx.localSigs))
                    }
                    Pair(state, actions)
                }
                state is WaitForFundingLocked || state is LegacyWaitForFundingLocked -> {
                    logger.debug { "c:$channelId re-sending fundingLocked" }
                    val nextPerCommitmentPoint = keyManager.commitmentPoint(state.commitments.localParams.channelKeys.shaSeed, 1)
                    val fundingLocked = FundingLocked(state.commitments.channelId, nextPerCommitmentPoint)
                    val actions = listOf(ChannelAction.Message.Send(fundingLocked))
                    Pair(state, actions)
                }
                state is Normal -> {
                    when {
                        !Helpers.checkLocalCommit(state.commitments, event.message.nextRemoteRevocationNumber) -> {
                            // if next_remote_revocation_number is greater than our local commitment index, it means that either we are using an outdated commitment, or they are lying
                            // but first we need to make sure that the last per_commitment_secret that they claim to have received from us is correct for that next_remote_revocation_number minus 1
                            if (keyManager.commitmentSecret(state.commitments.localParams.channelKeys.shaSeed, event.message.nextRemoteRevocationNumber - 1) == event.message.yourLastCommitmentSecret) {
                                logger.warning { "c:$channelId counterparty proved that we have an outdated (revoked) local commitment!!! ourCommitmentNumber=${state.commitments.localCommit.index} theirCommitmentNumber=${event.message.nextRemoteRevocationNumber}" }
                                // their data checks out, we indeed seem to be using an old revoked commitment, and must absolutely *NOT* publish it, because that would be a cheating attempt and they
                                // would punish us by taking all the funds in the channel
                                val exc = PleasePublishYourCommitment(channelId)
                                val error = Error(channelId, exc.message.encodeToByteArray().toByteVector())
                                val nextState = WaitForRemotePublishFutureCommitment(staticParams, state.currentTip, state.currentOnChainFeerates, state.commitments, event.message)
                                val actions = listOf(
                                    ChannelAction.Storage.StoreState(nextState),
                                    ChannelAction.Message.Send(error)
                                )
                                Pair(nextState, actions)
                            } else {
                                logger.warning { "c:$channelId they lied! the last per_commitment_secret they claimed to have received from us is invalid" }
                                val exc = InvalidRevokedCommitProof(channelId, state.commitments.localCommit.index, event.message.nextRemoteRevocationNumber, event.message.yourLastCommitmentSecret)
                                val error = Error(channelId, exc.message.encodeToByteArray().toByteVector())
                                val (nextState, spendActions) = state.spendLocalCurrent()
                                val actions = buildList {
                                    addAll(spendActions)
                                    add(ChannelAction.Message.Send(error))
                                }
                                Pair(nextState, actions)
                            }
                        }
                        !Helpers.checkRemoteCommit(state.commitments, event.message.nextLocalCommitmentNumber) -> {
                            // if next_local_commit_number is more than one more our remote commitment index, it means that either we are using an outdated commitment, or they are lying
                            logger.warning { "c:$channelId counterparty says that they have a more recent commitment than the one we know of!!! ourCommitmentNumber=${state.commitments.remoteNextCommitInfo.left?.nextRemoteCommit?.index ?: state.commitments.remoteCommit.index} theirCommitmentNumber=${event.message.nextLocalCommitmentNumber}" }
                            // there is no way to make sure that they are saying the truth, the best thing to do is ask them to publish their commitment right now
                            // maybe they will publish their commitment, in that case we need to remember their commitment point in order to be able to claim our outputs
                            // not that if they don't comply, we could publish our own commitment (it is not stale, otherwise we would be in the case above)
                            val exc = PleasePublishYourCommitment(channelId)
                            val error = Error(channelId, exc.message.encodeToByteArray().toByteVector())
                            val nextState = WaitForRemotePublishFutureCommitment(staticParams, state.currentTip, state.currentOnChainFeerates, state.commitments, event.message)
                            val actions = listOf(
                                ChannelAction.Storage.StoreState(nextState),
                                ChannelAction.Message.Send(error)
                            )
                            Pair(nextState, actions)
                        }
                        else -> {
                            // normal case, our data is up-to-date
                            val actions = ArrayList<ChannelAction>()
                            if (event.message.nextLocalCommitmentNumber == 1L && state.commitments.localCommit.index == 0L) {
                                // If next_local_commitment_number is 1 in both the channel_reestablish it sent and received, then the node MUST retransmit funding_locked, otherwise it MUST NOT
                                logger.debug { "c:$channelId re-sending fundingLocked" }
                                val nextPerCommitmentPoint = keyManager.commitmentPoint(state.commitments.localParams.channelKeys.shaSeed, 1)
                                val fundingLocked = FundingLocked(state.commitments.channelId, nextPerCommitmentPoint)
                                actions.add(ChannelAction.Message.Send(fundingLocked))
                            }

                            try {
                                val (commitments1, sendQueue1) = handleSync(event.message, state, keyManager, logger)
                                actions.addAll(sendQueue1)

                                // BOLT 2: A node if it has sent a previous shutdown MUST retransmit shutdown.
                                state.localShutdown?.let {
                                    logger.debug { "c:$channelId re-sending local shutdown" }
                                    actions.add(ChannelAction.Message.Send(it))
                                }

                                if (!state.buried) {
                                    // even if we were just disconnected/reconnected, we need to put back the watch because the event may have been
                                    // fired while we were in OFFLINE (if not, the operation is idempotent anyway)
                                    val watchConfirmed = WatchConfirmed(
                                        channelId,
                                        state.commitments.commitInput.outPoint.txid,
                                        state.commitments.commitInput.txOut.publicKeyScript,
                                        ANNOUNCEMENTS_MINCONF.toLong(),
                                        BITCOIN_FUNDING_DEEPLYBURIED
                                    )
                                    actions.add(ChannelAction.Blockchain.SendWatch(watchConfirmed))
                                }

                                logger.info { "c:$channelId switching to ${state::class}" }
                                Pair(state.copy(commitments = commitments1), actions)
                            } catch (e: RevocationSyncError) {
                                val error = Error(channelId, e.message)
                                state.spendLocalCurrent().run { copy(second = second + ChannelAction.Message.Send(error)) }
                            }
                        }
                    }
                }
                // BOLT 2: A node if it has sent a previous shutdown MUST retransmit shutdown.
                // negotiation restarts from the beginning, and is initialized by the initiator
                // note: in any case we still need to keep all previously sent closing_signed, because they may publish one of them
                state is Negotiating && state.commitments.localParams.isInitiator -> {
                    // we could use the last closing_signed we sent, but network fees may have changed while we were offline so it is better to restart from scratch
                    val (closingTx, closingSigned) = Helpers.Closing.makeFirstClosingTx(
                        keyManager,
                        state.commitments,
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
                else -> unhandled(event)
            }
            event is ChannelEvent.WatchReceived -> {
                val watch = event.watch
                when {
                    watch is WatchEventSpent -> when {
                        state is Negotiating && state.closingTxProposed.flatten().any { it.unsignedTx.tx.txid == watch.tx.txid } -> {
                            logger.info { "c:$channelId closing tx published: closingTxId=${watch.tx.txid}" }
                            val closingTx = state.getMutualClosePublished(watch.tx)
                            val nextState = Closing(
                                staticParams,
                                currentTip,
                                currentOnChainFeerates,
                                state.commitments,
                                fundingTx = null,
                                waitingSinceBlock = currentBlockHeight.toLong(),
                                alternativeCommitments = listOf(),
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
                        watch.tx.txid == state.commitments.remoteCommit.txid -> state.handleRemoteSpentCurrent(watch.tx)
                        watch.tx.txid == state.commitments.remoteNextCommitInfo.left?.nextRemoteCommit?.txid -> state.handleRemoteSpentNext(watch.tx)
                        state is WaitForRemotePublishFutureCommitment -> state.handleRemoteSpentFuture(watch.tx)
                        else -> state.handleRemoteSpentOther(watch.tx)
                    }
                    watch is WatchEventConfirmed && (watch.event is BITCOIN_FUNDING_DEPTHOK || watch.event is BITCOIN_FUNDING_DEEPLYBURIED) -> {
                        val watchSpent = WatchSpent(channelId, watch.tx, state.commitments.commitInput.outPoint.index.toInt(), BITCOIN_FUNDING_SPENT)
                        val nextState = when {
                            state is WaitForFundingConfirmed && watch.tx.txid == state.commitments.fundingTxId -> {
                                logger.info { "c:$channelId was confirmed while offline at blockHeight=${watch.blockHeight} txIndex=${watch.txIndex} with funding txid=${watch.tx.txid}" }
                                state.copy(previousFundingTxs = listOf())
                            }
                            state is WaitForFundingConfirmed && state.previousFundingTxs.find { watch.tx.txid == it.second.fundingTxId } != null -> {
                                val (fundingTx, commitments) = state.previousFundingTxs.first { watch.tx.txid == it.second.fundingTxId }
                                logger.info { "c:$channelId was confirmed while offline at blockHeight=${watch.blockHeight} txIndex=${watch.txIndex} with a previous funding txid=${watch.tx.txid}" }
                                state.copy(fundingTx = fundingTx, commitments = commitments, previousFundingTxs = listOf())
                            }
                            else -> state
                        }
                        Pair(this.copy(state = nextState), listOf(ChannelAction.Blockchain.SendWatch(watchSpent)))
                    }
                    else -> unhandled(event)
                }
            }
            event is ChannelEvent.CheckHtlcTimeout -> {
                val (newState, actions) = state.checkHtlcTimeout()
                when (newState) {
                    is Closing -> Pair(newState, actions)
                    is Closed -> Pair(newState, actions)
                    else -> Pair(Syncing(newState as ChannelStateWithCommitments, waitForTheirReestablishMessage), actions)
                }
            }
            event is ChannelEvent.NewBlock -> {
                val (newState, actions) = state.process(event)
                when (newState) {
                    is Closing -> Pair(newState, actions)
                    is Closed -> Pair(newState, actions)
                    else -> Pair(Syncing(newState as ChannelStateWithCommitments, waitForTheirReestablishMessage), actions)
                }
            }
            event is ChannelEvent.Disconnected -> Pair(Offline(state), listOf())
            event is ChannelEvent.ExecuteCommand && event.command is CMD_FORCECLOSE -> {
                val (newState, actions) = state.process(event)
                when (newState) {
                    is Closing -> Pair(newState, actions)
                    is Closed -> Pair(newState, actions)
                    else -> Pair(Syncing(newState as ChannelStateWithCommitments, waitForTheirReestablishMessage), actions)
                }
            }
            else -> unhandled(event)
        }
    }

    override fun handleLocalError(event: ChannelEvent, t: Throwable): Pair<ChannelState, List<ChannelAction>> {
        logger.error(t) { "c:$channelId error on event ${event::class} in state ${this::class}" }
        return Pair(this, listOf(ChannelAction.ProcessLocalError(t, event)))
    }

}
