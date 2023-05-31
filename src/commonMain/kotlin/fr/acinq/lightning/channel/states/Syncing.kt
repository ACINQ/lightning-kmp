package fr.acinq.lightning.channel.states

import fr.acinq.lightning.Feature
import fr.acinq.lightning.ShortChannelId
import fr.acinq.lightning.blockchain.*
import fr.acinq.lightning.channel.*
import fr.acinq.lightning.crypto.KeyManager
import fr.acinq.lightning.transactions.outgoings
import fr.acinq.lightning.utils.Either
import fr.acinq.lightning.utils.toByteVector
import fr.acinq.lightning.wire.*

/**
 * waitForTheirReestablishMessage == true means that we want to wait until we've received their channel_reestablish message before
 * we send ours (for example, to extract encrypted backup data from extra fields)
 * waitForTheirReestablishMessage == false means that we've already sent our channel_reestablish message
 */
data class Syncing(val state: PersistedChannelState) : ChannelState() {

    val channelId = state.channelId

    fun ChannelContext.channelKeys(): KeyManager.ChannelKeys = when (state) {
        is WaitForFundingSigned -> state.channelParams.localParams.channelKeys(keyManager)
        is ChannelStateWithCommitments -> state.commitments.params.localParams.channelKeys(keyManager)
    }

    override fun ChannelContext.processInternal(cmd: ChannelCommand): Pair<ChannelState, List<ChannelAction>> {
        return when {
            cmd is ChannelCommand.MessageReceived && cmd.message is ChannelReestablish -> {
                val (nextState, actions) = when {
                    state is LegacyWaitForFundingConfirmed -> {
                        Pair(state, listOf())
                    }
                    state is WaitForFundingSigned -> {
                        when (cmd.message.nextFundingTxId) {
                            // We retransmit our commit_sig, and will send our tx_signatures once we've received their commit_sig.
                            state.signingSession.fundingTxId -> {
                                val remoteSwapLocalSigs = state.signingSession.unsignedFundingTx.signRemoteSwapInputs(keyManager)
                                val commitSig = state.signingSession.remoteCommit.sign(channelKeys(), state.channelParams, state.signingSession, remoteSwapLocalSigs)
                                Pair(state, listOf(ChannelAction.Message.Send(commitSig)))
                            }
                            else -> Pair(state, listOf())
                        }
                    }
                    state is WaitForFundingConfirmed -> {
                        when (cmd.message.nextFundingTxId) {
                            null -> Pair(state, listOf())
                            else -> {
                                if (state.rbfStatus is RbfStatus.WaitingForSigs && state.rbfStatus.session.fundingTxId == cmd.message.nextFundingTxId) {
                                    // We retransmit our commit_sig, and will send our tx_signatures once we've received their commit_sig.
                                    logger.info { "re-sending commit_sig for rbf attempt with fundingTxId=${cmd.message.nextFundingTxId}" }
                                    val remoteSwapLocalSigs = state.rbfStatus.session.unsignedFundingTx.signRemoteSwapInputs(keyManager)
                                    val commitSig = state.rbfStatus.session.remoteCommit.sign(channelKeys(), state.commitments.params, state.rbfStatus.session, remoteSwapLocalSigs)
                                    val actions = listOf(ChannelAction.Message.Send(commitSig))
                                    Pair(state, actions)
                                } else if (state.latestFundingTx.txId == cmd.message.nextFundingTxId) {
                                    val actions = buildList {
                                        if (state.latestFundingTx.sharedTx is PartiallySignedSharedTransaction) {
                                            // We have not received their tx_signatures: we retransmit our commit_sig because we don't know if they received it.
                                            logger.info { "re-sending commit_sig for fundingTxId=${cmd.message.nextFundingTxId}" }
                                            val remoteSwapLocalSigs = state.latestFundingTx.sharedTx.tx.signRemoteSwapInputs(keyManager)
                                            val commitSig = state.commitments.latest.remoteCommit.sign(
                                                channelKeys(),
                                                state.commitments.params,
                                                fundingTxIndex = 0,
                                                state.commitments.latest.remoteFundingPubkey,
                                                state.commitments.latest.commitInput,
                                                remoteSwapLocalSigs
                                            )
                                            add(ChannelAction.Message.Send(commitSig))
                                        }
                                        logger.info { "re-sending tx_signatures for fundingTxId=${cmd.message.nextFundingTxId}" }
                                        add(ChannelAction.Message.Send(state.latestFundingTx.sharedTx.localSigs))
                                    }
                                    Pair(state, actions)
                                } else {
                                    // The fundingTxId must be for an RBF attempt that we didn't store (we got disconnected before receiving their tx_complete).
                                    // We tell them to abort that RBF attempt.
                                    logger.info { "aborting obsolete rbf attempt for fundingTxId=${cmd.message.nextFundingTxId}" }
                                    Pair(state.copy(rbfStatus = RbfStatus.RbfAborted), listOf(ChannelAction.Message.Send(TxAbort(state.channelId, RbfAttemptAborted(state.channelId).message))))
                                }
                            }
                        }
                    }
                    state is WaitForChannelReady -> {
                        val actions = ArrayList<ChannelAction>()

                        if (state.commitments.latest.fundingTxId == cmd.message.nextFundingTxId) {
                            if (state.commitments.latest.localFundingStatus is LocalFundingStatus.UnconfirmedFundingTx) {
                                if (state.commitments.latest.localFundingStatus.sharedTx is PartiallySignedSharedTransaction) {
                                    // If we have not received their tx_signatures, we can't tell whether they had received our commit_sig, so we need to retransmit it
                                    logger.info { "re-sending commit_sig for fundingTxId=${state.commitments.latest.fundingTxId}" }
                                    val remoteSwapLocalSigs = state.commitments.latest.localFundingStatus.sharedTx.tx.signRemoteSwapInputs(keyManager)
                                    val commitSig = state.commitments.latest.remoteCommit.sign(
                                        channelKeys(),
                                        state.commitments.params,
                                        fundingTxIndex = state.commitments.latest.fundingTxIndex,
                                        state.commitments.latest.remoteFundingPubkey,
                                        state.commitments.latest.commitInput,
                                        remoteSwapLocalSigs
                                    )
                                    actions.add(ChannelAction.Message.Send(commitSig))
                                }
                                logger.info { "re-sending tx_signatures for fundingTxId=${cmd.message.nextFundingTxId}" }
                                actions.add(ChannelAction.Message.Send(state.commitments.latest.localFundingStatus.sharedTx.localSigs))
                            } else {
                                // The funding tx is confirmed, and they have not received our tx_signatures, but they must have received our commit_sig, otherwise they
                                // would not have sent their tx_signatures and we would not have been able to publish the funding tx in the first place. We could in theory
                                // recompute our tx_signatures, but instead we do nothing: they will shortly be notified that the funding tx has confirmed.
                                logger.warning { "cannot re-send tx_signatures for fundingTxId=${cmd.message.nextFundingTxId}, transaction is already confirmed" }
                            }
                        }

                        logger.debug { "re-sending channel_ready" }
                        val nextPerCommitmentPoint = channelKeys().commitmentPoint(1)
                        val channelReady = ChannelReady(state.commitments.channelId, nextPerCommitmentPoint)
                        actions.add(ChannelAction.Message.Send(channelReady))

                        Pair(state, actions)
                    }
                    state is LegacyWaitForFundingLocked -> {
                        logger.debug { "re-sending channel_ready" }
                        val nextPerCommitmentPoint = channelKeys().commitmentPoint(1)
                        val channelReady = ChannelReady(state.commitments.channelId, nextPerCommitmentPoint)
                        val actions = listOf(ChannelAction.Message.Send(channelReady))
                        Pair(state, actions)
                    }
                    state is Normal -> {
                        when {
                            !Helpers.checkLocalCommit(state.commitments, cmd.message.nextRemoteRevocationNumber) -> {
                                // if next_remote_revocation_number is greater than our local commitment index, it means that either we are using an outdated commitment, or they are lying
                                // but first we need to make sure that the last per_commitment_secret that they claim to have received from us is correct for that next_remote_revocation_number minus 1
                                if (channelKeys().commitmentSecret(cmd.message.nextRemoteRevocationNumber - 1) == cmd.message.yourLastCommitmentSecret) {
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

                                // re-send channel_ready or splice_locked
                                if (state.commitments.latest.fundingTxIndex == 0L && cmd.message.nextLocalCommitmentNumber == 1L && state.commitments.localCommitIndex == 0L) {
                                    // If next_local_commitment_number is 1 in both the channel_reestablish it sent and received, then the node MUST retransmit channel_ready, otherwise it MUST NOT
                                    logger.debug { "re-sending channel_ready" }
                                    val nextPerCommitmentPoint = channelKeys().commitmentPoint(1)
                                    val channelReady = ChannelReady(state.commitments.channelId, nextPerCommitmentPoint)
                                    actions.add(ChannelAction.Message.Send(channelReady))
                                } else {
                                    // NB: there is a key difference between channel_ready and splice_locked:
                                    // - channel_ready: a non-zero commitment index implies that both sides have seen the channel_ready
                                    // - splice_locked: the commitment index can be updated as long as it is compatible with all splices, so
                                    //   we must keep sending our most recent splice_locked at each reconnection
                                    state.commitments.active
                                        .filter { it.fundingTxIndex > 0L } // only consider splice txs
                                        .firstOrNull { staticParams.useZeroConf || it.localFundingStatus is LocalFundingStatus.ConfirmedFundingTx }
                                        ?.let {
                                            logger.debug { "re-sending splice_locked for fundingTxId=${it.fundingTxId}" }
                                            val spliceLocked = SpliceLocked(channelId, it.fundingTxId.reversed())
                                            actions.add(ChannelAction.Message.Send(spliceLocked))
                                        }
                                }

                                // resume splice signing session if any
                                val spliceStatus1 = if (state.spliceStatus is SpliceStatus.WaitingForSigs && state.spliceStatus.session.fundingTxId == cmd.message.nextFundingTxId) {
                                    // We retransmit our commit_sig, and will send our tx_signatures once we've received their commit_sig.
                                    logger.info { "re-sending commit_sig for splice attempt with fundingTxIndex=${state.spliceStatus.session.fundingTxIndex} fundingTxId=${state.spliceStatus.session.fundingTxId}" }
                                    val remoteSwapLocalSigs = state.spliceStatus.session.unsignedFundingTx.signRemoteSwapInputs(keyManager)
                                    val commitSig = state.spliceStatus.session.remoteCommit.sign(channelKeys(), state.commitments.params, state.spliceStatus.session, remoteSwapLocalSigs)
                                    actions.add(ChannelAction.Message.Send(commitSig))
                                    state.spliceStatus
                                } else if (state.commitments.latest.fundingTxId == cmd.message.nextFundingTxId) {
                                    if (state.commitments.latest.localFundingStatus is LocalFundingStatus.UnconfirmedFundingTx) {
                                        if (state.commitments.latest.localFundingStatus.sharedTx is PartiallySignedSharedTransaction) {
                                            // If we have not received their tx_signatures, we can't tell whether they had received our commit_sig, so we need to retransmit it
                                            logger.info { "re-sending commit_sig for fundingTxIndex=${state.commitments.latest.fundingTxIndex} fundingTxId=${state.commitments.latest.fundingTxId}" }
                                            val remoteSwapLocalSigs = state.commitments.latest.localFundingStatus.sharedTx.tx.signRemoteSwapInputs(keyManager)
                                            val commitSig = state.commitments.latest.remoteCommit.sign(
                                                channelKeys(),
                                                state.commitments.params,
                                                fundingTxIndex = state.commitments.latest.fundingTxIndex,
                                                state.commitments.latest.remoteFundingPubkey,
                                                state.commitments.latest.commitInput,
                                                remoteSwapLocalSigs
                                            )
                                            actions.add(ChannelAction.Message.Send(commitSig))
                                        }
                                        logger.info { "re-sending tx_signatures for fundingTxId=${cmd.message.nextFundingTxId}" }
                                        actions.add(ChannelAction.Message.Send(state.commitments.latest.localFundingStatus.sharedTx.localSigs))
                                    } else {
                                        // The funding tx is confirmed, and they have not received our tx_signatures, but they must have received our commit_sig, otherwise they
                                        // would not have sent their tx_signatures and we would not have been able to publish the funding tx in the first place. We could in theory
                                        // recompute our tx_signatures, but instead we do nothing: they will shortly be notified that the funding tx has confirmed.
                                        logger.warning { "cannot re-send tx_signatures for fundingTxId=${cmd.message.nextFundingTxId}, transaction is already confirmed" }
                                    }
                                    state.spliceStatus
                                } else if (cmd.message.nextFundingTxId != null) {
                                    // The fundingTxId must be for a splice attempt that we didn't store (we got disconnected before receiving their tx_complete)
                                    logger.info { "aborting obsolete splice attempt for fundingTxId=${cmd.message.nextFundingTxId}" }
                                    actions.add(ChannelAction.Message.Send(TxAbort(state.channelId, SpliceAborted(state.channelId).message)))
                                    SpliceStatus.Aborted
                                } else {
                                    state.spliceStatus
                                }

                                try {
                                    val (commitments1, sendQueue1) = handleSync(cmd.message, state)
                                    actions.addAll(sendQueue1)
                                    // BOLT 2: A node if it has sent a previous shutdown MUST retransmit shutdown.
                                    state.localShutdown?.let {
                                        logger.debug { "re-sending local shutdown" }
                                        actions.add(ChannelAction.Message.Send(it))
                                    }
                                    Pair(state.copy(commitments = commitments1, spliceStatus = spliceStatus1), actions)
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
                            channelKeys(),
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
                Pair(nextState, buildList {
                    if (staticParams.nodeParams.features.hasFeature(Feature.ChannelBackupClient)) {
                        // if the backup feature is enabled, we were waiting for their reestablish before sending ours
                        val channelReestablish = state.run { createChannelReestablish() }
                        add(ChannelAction.Message.Send(channelReestablish))
                    }
                    addAll(actions)
                })
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
                            ChannelAction.Blockchain.PublishTx(closingTx),
                            ChannelAction.Blockchain.SendWatch(WatchConfirmed(channelId, watch.tx, staticParams.nodeParams.minDepthBlocks.toLong(), BITCOIN_TX_CONFIRMED(watch.tx)))
                        )
                        Pair(nextState, actions)
                    }
                    else -> state.run { handlePotentialForceClose(watch) }
                }
                is WatchEventConfirmed -> {
                    if (watch.event is BITCOIN_FUNDING_DEPTHOK) {
                        when (val res = state.run { acceptFundingTxConfirmed(watch) }) {
                            is Either.Left -> Pair(this@Syncing, listOf())
                            is Either.Right -> {
                                val (commitments1, _, actions) = res.value
                                val nextState = when (state) {
                                    is WaitForFundingConfirmed -> {
                                        logger.info { "was confirmed while syncing at blockHeight=${watch.blockHeight} txIndex=${watch.txIndex} with funding txid=${watch.tx.txid}" }
                                        val nextPerCommitmentPoint = channelKeys().commitmentPoint(1)
                                        val channelReady = ChannelReady(channelId, nextPerCommitmentPoint, TlvStream(ChannelReadyTlv.ShortChannelIdTlv(ShortChannelId.peerId(staticParams.nodeParams.nodeId))))
                                        val shortChannelId = ShortChannelId(watch.blockHeight, watch.txIndex, commitments1.latest.commitInput.outPoint.index.toInt())
                                        WaitForChannelReady(commitments1, shortChannelId, channelReady)
                                    }
                                    else -> state
                                }
                                Pair(this@Syncing.copy(state = nextState), actions + listOf(ChannelAction.Storage.StoreState(nextState)))
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
                    else -> Pair(Syncing(newState), actions)
                }
            }
            cmd is ChannelCommand.Disconnected -> Pair(Offline(state), listOf())
            cmd is ChannelCommand.Close.ForceClose -> {
                val (newState, actions) = state.run { process(cmd) }
                when (newState) {
                    is Closing -> Pair(newState, actions)
                    is Closed -> Pair(newState, actions)
                    else -> Pair(Syncing(newState as PersistedChannelState), actions)
                }
            }
            cmd is ChannelCommand.MessageReceived && cmd.message is Error -> state.run { handleRemoteError(cmd.message) }
            else -> unhandled(cmd)
        }
    }

    override fun ChannelContext.handleLocalError(cmd: ChannelCommand, t: Throwable): Pair<ChannelState, List<ChannelAction>> {
        logger.error(t) { "error on command ${cmd::class.simpleName} in state ${this@Syncing::class.simpleName}" }
        return Pair(this@Syncing, listOf(ChannelAction.ProcessLocalError(t, cmd)))
    }

    companion object {
        private fun ChannelContext.handleSync(channelReestablish: ChannelReestablish, d: ChannelStateWithCommitments): Pair<Commitments, List<ChannelAction>> {
            val sendQueue = ArrayList<ChannelAction>()
            // first we clean up unacknowledged updates
            logger.debug { "discarding proposed OUT: ${d.commitments.changes.localChanges.proposed}" }
            logger.debug { "discarding proposed IN: ${d.commitments.changes.remoteChanges.proposed}" }
            val commitments1 = d.commitments.copy(
                changes = d.commitments.changes.copy(
                    localChanges = d.commitments.changes.localChanges.copy(proposed = emptyList()),
                    remoteChanges = d.commitments.changes.remoteChanges.copy(proposed = emptyList()),
                    localNextHtlcId = d.commitments.changes.localNextHtlcId - d.commitments.changes.localChanges.proposed.filterIsInstance<UpdateAddHtlc>().size,
                    remoteNextHtlcId = d.commitments.changes.remoteNextHtlcId - d.commitments.changes.remoteChanges.proposed.filterIsInstance<UpdateAddHtlc>().size
                )
            )
            logger.debug { "localNextHtlcId=${d.commitments.changes.localNextHtlcId}->${commitments1.changes.localNextHtlcId}" }
            logger.debug { "remoteNextHtlcId=${d.commitments.changes.remoteNextHtlcId}->${commitments1.changes.remoteNextHtlcId}" }

            fun resendRevocation() {
                // let's see the state of remote sigs
                when (commitments1.localCommitIndex) {
                    channelReestablish.nextRemoteRevocationNumber -> {
                        // nothing to do
                    }
                    channelReestablish.nextRemoteRevocationNumber + 1 -> {
                        // our last revocation got lost, let's resend it
                        logger.debug { "re-sending last revocation" }
                        val channelKeys = keyManager.channelKeys(d.commitments.params.localParams.fundingKeyPath)
                        val localPerCommitmentSecret = channelKeys.commitmentSecret(d.commitments.localCommitIndex - 1)
                        val localNextPerCommitmentPoint = channelKeys.commitmentPoint(d.commitments.localCommitIndex + 1)
                        val revocation = RevokeAndAck(commitments1.channelId, localPerCommitmentSecret, localNextPerCommitmentPoint)
                        sendQueue.add(ChannelAction.Message.Send(revocation))
                    }
                    else -> throw RevocationSyncError(d.channelId)
                }
            }

            when {
                commitments1.remoteNextCommitInfo.isLeft && commitments1.nextRemoteCommitIndex + 1 == channelReestablish.nextLocalCommitmentNumber -> {
                    // we had sent a new sig and were waiting for their revocation
                    // they had received the new sig but their revocation was lost during the disconnection
                    // they will send us the revocation, nothing to do here
                    logger.debug { "waiting for them to re-send their last revocation" }
                    resendRevocation()
                }
                commitments1.remoteNextCommitInfo.isLeft && commitments1.nextRemoteCommitIndex == channelReestablish.nextLocalCommitmentNumber -> {
                    // we had sent a new sig and were waiting for their revocation
                    // they didn't receive the new sig because of the disconnection
                    // we just resend the same updates and the same sig
                    val revWasSentLast = commitments1.localCommitIndex > commitments1.remoteNextCommitInfo.left!!.sentAfterLocalCommitIndex
                    if (!revWasSentLast) resendRevocation()

                    logger.debug { "re-sending previously local signed changes: ${commitments1.changes.localChanges.signed}" }
                    commitments1.changes.localChanges.signed.forEach { sendQueue.add(ChannelAction.Message.Send(it)) }
                    logger.debug { "re-sending the exact same previous sig" }
                    commitments1.active.forEach { sendQueue.add(ChannelAction.Message.Send(it.nextRemoteCommit!!.sig)) }
                    if (revWasSentLast) resendRevocation()
                }
                commitments1.remoteNextCommitInfo.isRight && commitments1.remoteCommitIndex + 1 == channelReestablish.nextLocalCommitmentNumber -> {
                    // there wasn't any sig in-flight when the disconnection occurred
                    resendRevocation()
                }
                else -> throw RevocationSyncError(d.channelId)
            }

            if (commitments1.changes.localHasChanges()) {
                sendQueue.add(ChannelAction.Message.SendToSelf(ChannelCommand.Sign))
            }

            // When a channel is reestablished after a wallet restarts, we need to reprocess incoming HTLCs that may have been only partially processed
            // (either because they didn't reach the payment handler, or because the payment handler response didn't reach the channel).
            // Otherwise these HTLCs will stay in our commitment until they timeout and our peer closes the channel.
            //
            // We are interested in incoming HTLCs, that have been *cross-signed* (otherwise they wouldn't have been forwarded to the payment handler).
            // They signed it first, so the HTLC will first appear in our commitment tx, and later on in their commitment when we subsequently sign it.
            // That's why we need to look in *their* commitment with direction=OUT.
            //
            // We also need to filter out htlcs that we already settled and signed (the settlement messages are being retransmitted).
            val alreadySettled = commitments1.changes.localChanges.signed.filterIsInstance<HtlcSettlementMessage>().map { it.id }.toSet()
            val htlcsToReprocess = commitments1.latest.remoteCommit.spec.htlcs.outgoings().filter { !alreadySettled.contains(it.id) }
            logger.debug { "re-processing signed IN: $htlcsToReprocess" }
            sendQueue.addAll(htlcsToReprocess.map { ChannelAction.ProcessIncomingHtlc(it) })

            return Pair(commitments1, sendQueue)
        }
    }

}
