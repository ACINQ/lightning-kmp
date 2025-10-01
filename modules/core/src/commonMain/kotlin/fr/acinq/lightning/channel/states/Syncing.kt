package fr.acinq.lightning.channel.states

import fr.acinq.bitcoin.PrivateKey
import fr.acinq.bitcoin.utils.Either
import fr.acinq.lightning.ShortChannelId
import fr.acinq.lightning.blockchain.WatchConfirmed
import fr.acinq.lightning.blockchain.WatchConfirmedTriggered
import fr.acinq.lightning.blockchain.WatchSpentTriggered
import fr.acinq.lightning.channel.*
import fr.acinq.lightning.crypto.ChannelKeys
import fr.acinq.lightning.utils.toByteVector
import fr.acinq.lightning.wire.*

data class Syncing(val state: PersistedChannelState, val channelReestablishSent: Boolean) : ChannelState() {

    val channelId = state.channelId

    fun ChannelContext.channelKeys(): ChannelKeys = when (state) {
        is WaitForFundingSigned -> state.channelParams.localParams.channelKeys(keyManager)
        is ChannelStateWithCommitments -> state.commitments.params.localParams.channelKeys(keyManager)
    }

    override suspend fun ChannelContext.processInternal(cmd: ChannelCommand): Pair<ChannelState, List<ChannelAction>> {
        return when (cmd) {
            is ChannelCommand.MessageReceived -> when (cmd.message) {
                is ChannelReestablish -> {
                    val (nextState, actions) = when (state) {
                        is LegacyWaitForFundingConfirmed -> {
                            Pair(state, listOf())
                        }
                        is WaitForFundingSigned -> {
                            val actions = buildList {
                                if (cmd.message.nextFundingTxId == state.signingSession.fundingTx.txId && cmd.message.nextLocalCommitmentNumber == 0L) {
                                    // They haven't received our commit_sig: we retransmit it, and will send our tx_signatures once we've received
                                    // their commit_sig or their tx_signatures (depending on who must send tx_signatures first).
                                    logger.info { "re-sending commit_sig for channel creation with fundingTxId=${state.signingSession.fundingTx.txId}" }
                                    val commitSig = state.signingSession.remoteCommit.sign(state.channelParams, channelKeys(), state.signingSession)
                                    add(ChannelAction.Message.Send(commitSig))
                                }
                            }
                            Pair(state, actions)
                        }
                        is WaitForFundingConfirmed -> {
                            when (cmd.message.nextFundingTxId) {
                                null -> Pair(state, listOf())
                                else -> {
                                    if (state.rbfStatus is RbfStatus.WaitingForSigs && state.rbfStatus.session.fundingTx.txId == cmd.message.nextFundingTxId) {
                                        val actions = buildList {
                                            if (cmd.message.nextLocalCommitmentNumber == 0L) {
                                                // They haven't received our commit_sig: we retransmit it.
                                                // We're waiting for signatures from them, and will send our tx_signatures once we receive them.
                                                logger.info { "re-sending commit_sig for rbf attempt with fundingTxId=${cmd.message.nextFundingTxId}" }
                                                val commitSig = state.rbfStatus.session.remoteCommit.sign(state.commitments.params, channelKeys(), state.rbfStatus.session)
                                                add(ChannelAction.Message.Send(commitSig))
                                            }
                                        }
                                        Pair(state, actions)
                                    } else if (state.latestFundingTx.txId == cmd.message.nextFundingTxId) {
                                        // We've already received their commit_sig and sent our tx_signatures. We retransmit our tx_signatures
                                        // and our commit_sig if they haven't received it already.
                                        val actions = buildList {
                                            if (cmd.message.nextLocalCommitmentNumber == 0L) {
                                                logger.info { "re-sending commit_sig for fundingTxId=${cmd.message.nextFundingTxId}" }
                                                val commitSig = state.commitments.latest.remoteCommit.sign(
                                                    state.commitments.params,
                                                    channelKeys(),
                                                    fundingTxIndex = 0,
                                                    state.commitments.latest.remoteFundingPubkey,
                                                    state.commitments.latest.commitInput,
                                                    batchSize = 1
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
                        is WaitForChannelReady -> {
                            val actions = ArrayList<ChannelAction>()
                            // We've already received their commit_sig and sent our tx_signatures. We retransmit our tx_signatures
                            // and our commit_sig if they haven't received it already.
                            if (state.commitments.latest.fundingTxId == cmd.message.nextFundingTxId) {
                                if (state.commitments.latest.localFundingStatus is LocalFundingStatus.UnconfirmedFundingTx) {
                                    if (cmd.message.nextLocalCommitmentNumber == 0L) {
                                        logger.info { "re-sending commit_sig for fundingTxId=${state.commitments.latest.fundingTxId}" }
                                        val commitSig = state.commitments.latest.remoteCommit.sign(
                                            state.commitments.params,
                                            channelKeys(),
                                            fundingTxIndex = state.commitments.latest.fundingTxIndex,
                                            state.commitments.latest.remoteFundingPubkey,
                                            state.commitments.latest.commitInput,
                                            batchSize = 1
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
                        is LegacyWaitForFundingLocked -> {
                            logger.debug { "re-sending channel_ready" }
                            val nextPerCommitmentPoint = channelKeys().commitmentPoint(1)
                            val channelReady = ChannelReady(state.commitments.channelId, nextPerCommitmentPoint)
                            val actions = listOf(ChannelAction.Message.Send(channelReady))
                            Pair(state, actions)
                        }
                        is Normal -> {
                            when (val syncResult = handleSync(state.commitments, cmd.message)) {
                                is SyncResult.Failure -> handleSyncFailure(state.commitments, cmd.message, syncResult)
                                is SyncResult.Success -> {
                                    // normal case, our data is up-to-date
                                    val actions = ArrayList<ChannelAction>()

                                    // re-send channel_ready if necessary
                                    if (state.commitments.latest.fundingTxIndex == 0L && cmd.message.nextLocalCommitmentNumber == 1L && state.commitments.localCommitIndex == 0L) {
                                        // If next_local_commitment_number is 1 in both the channel_reestablish it sent and received, then the node MUST retransmit channel_ready, otherwise it MUST NOT
                                        logger.debug { "re-sending channel_ready" }
                                        val nextPerCommitmentPoint = channelKeys().commitmentPoint(1)
                                        val channelReady = ChannelReady(state.commitments.channelId, nextPerCommitmentPoint)
                                        actions.add(ChannelAction.Message.Send(channelReady))
                                    }

                                    // resume splice signing session if any
                                    val spliceStatus1 = if (state.spliceStatus is SpliceStatus.WaitingForSigs && state.spliceStatus.session.fundingTx.txId == cmd.message.nextFundingTxId) {
                                        if (cmd.message.nextLocalCommitmentNumber == state.commitments.remoteCommitIndex) {
                                            // They haven't received our commit_sig: we retransmit it.
                                            // We're waiting for signatures from them, and will send our tx_signatures once we receive them.
                                            logger.info { "re-sending commit_sig for splice attempt with fundingTxIndex=${state.spliceStatus.session.fundingTxIndex} fundingTxId=${state.spliceStatus.session.fundingTx.txId}" }
                                            val commitSig = state.spliceStatus.session.remoteCommit.sign(state.commitments.params, channelKeys(), state.spliceStatus.session)
                                            actions.add(ChannelAction.Message.Send(commitSig))
                                        }
                                        state.spliceStatus
                                    } else if (state.commitments.latest.fundingTxId == cmd.message.nextFundingTxId) {
                                        when (val localFundingStatus = state.commitments.latest.localFundingStatus) {
                                            is LocalFundingStatus.UnconfirmedFundingTx -> {
                                                // We've already received their commit_sig and sent our tx_signatures. We retransmit our tx_signatures
                                                // and our commit_sig if they haven't received it already.
                                                if (cmd.message.nextLocalCommitmentNumber == state.commitments.remoteCommitIndex) {
                                                    logger.info { "re-sending commit_sig for fundingTxIndex=${state.commitments.latest.fundingTxIndex} fundingTxId=${state.commitments.latest.fundingTxId}" }
                                                    val commitSig = state.commitments.latest.remoteCommit.sign(
                                                        state.commitments.params,
                                                        channelKeys(),
                                                        fundingTxIndex = state.commitments.latest.fundingTxIndex,
                                                        state.commitments.latest.remoteFundingPubkey,
                                                        state.commitments.latest.commitInput,
                                                        batchSize = 1
                                                    )
                                                    actions.add(ChannelAction.Message.Send(commitSig))
                                                }
                                                logger.info { "re-sending tx_signatures for fundingTxId=${cmd.message.nextFundingTxId}" }
                                                actions.add(ChannelAction.Message.Send(localFundingStatus.sharedTx.localSigs))
                                            }
                                            is LocalFundingStatus.ConfirmedFundingTx -> {
                                                // The funding tx is confirmed, and they have not received our tx_signatures, but they must have received our commit_sig, otherwise they
                                                // would not have sent their tx_signatures and we would not have been able to publish the funding tx in the first place.
                                                logger.info { "re-sending tx_signatures for fundingTxId=${cmd.message.nextFundingTxId}" }
                                                actions.add(ChannelAction.Message.Send(localFundingStatus.localSigs))
                                            }
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

                                    // Re-send splice_locked (must come *after* potentially retransmitting tx_signatures).
                                    // NB: there is a key difference between channel_ready and splice_locked:
                                    // - channel_ready: a non-zero commitment index implies that both sides have seen the channel_ready
                                    // - splice_locked: the commitment index can be updated as long as it is compatible with all splices, so
                                    //   we must keep sending our most recent splice_locked at each reconnection
                                    state.commitments.active
                                        .filter { it.fundingTxIndex > 0L } // only consider splice txs
                                        .firstOrNull { staticParams.useZeroConf || it.localFundingStatus is LocalFundingStatus.ConfirmedFundingTx }
                                        ?.let {
                                            logger.debug { "re-sending splice_locked for fundingTxId=${it.fundingTxId}" }
                                            val spliceLocked = SpliceLocked(channelId, it.fundingTxId)
                                            actions.add(ChannelAction.Message.Send(spliceLocked))
                                        }

                                    // we may need to retransmit updates and/or commit_sig and/or revocation
                                    actions.addAll(syncResult.retransmit.map { ChannelAction.Message.Send(it) })

                                    // then we clean up unsigned updates
                                    val commitments1 = discardUnsignedUpdates(state.commitments)

                                    if (commitments1.changes.localHasChanges()) {
                                        actions.add(ChannelAction.Message.SendToSelf(ChannelCommand.Commitment.Sign))
                                    }

                                    // When a channel is reestablished after a wallet restarts, we need to reprocess incoming HTLCs that may have been only partially processed
                                    // (either because they didn't reach the payment handler, or because the payment handler response didn't reach the channel).
                                    // Otherwise these HTLCs will stay in our commitment until they timeout and our peer closes the channel.
                                    val htlcsToReprocess = commitments1.reprocessIncomingHtlcs()
                                    logger.info { "re-processing signed IN: ${htlcsToReprocess.map { it.add.id }.joinToString()}" }
                                    actions.addAll(htlcsToReprocess)

                                    // BOLT 2: A node if it has sent a previous shutdown MUST retransmit shutdown.
                                    state.localShutdown?.let {
                                        logger.debug { "re-sending local shutdown" }
                                        actions.add(ChannelAction.Message.Send(it))
                                    }
                                    Pair(state.copy(commitments = commitments1, spliceStatus = spliceStatus1), actions)
                                }
                            }
                        }
                        is ShuttingDown -> {
                            when (val syncResult = handleSync(state.commitments, cmd.message)) {
                                is SyncResult.Failure -> handleSyncFailure(state.commitments, cmd.message, syncResult)
                                is SyncResult.Success -> {
                                    val commitments1 = discardUnsignedUpdates(state.commitments)
                                    val actions = buildList {
                                        addAll(syncResult.retransmit)
                                        add(state.localShutdown)
                                    }.map { ChannelAction.Message.Send(it) }
                                    Pair(state.copy(commitments = commitments1), actions)
                                }
                            }
                        }
                        is Negotiating -> {
                            // BOLT 2: A node if it has sent a previous shutdown MUST retransmit shutdown.
                            val shutdown = Shutdown(channelId, state.localScript)
                            Pair(state, listOf(ChannelAction.Message.Send(shutdown)))
                        }
                        is Closing, is Closed, is WaitForRemotePublishFutureCommitment -> unhandled(cmd)
                    }
                    Pair(nextState, buildList {
                        if (!channelReestablishSent) {
                            val channelReestablish = state.run { createChannelReestablish() }
                            add(ChannelAction.Message.Send(channelReestablish))
                        }
                        addAll(actions)
                    })
                }
                is Error -> state.run { handleRemoteError(cmd.message) }
                else -> unhandled(cmd)
            }
            is ChannelCommand.WatchReceived -> when (state) {
                is ChannelStateWithCommitments -> when (val watch = cmd.watch) {
                    is WatchSpentTriggered -> when {
                        state is Negotiating && state.publishedClosingTxs.any { it.tx.txid == watch.spendingTx.txid } -> {
                            // This is one of the transactions we already published, we watch for confirmations.
                            val actions = listOf(ChannelAction.Blockchain.SendWatch(WatchConfirmed(channelId, watch.spendingTx, staticParams.nodeParams.minDepthBlocks, WatchConfirmed.ClosingTxConfirmed)))
                            Pair(this@Syncing, actions)
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
                            Pair(this@Syncing.copy(state = nextState), actions)
                        }
                        else -> {
                            val (nextState, actions) = state.run { handlePotentialForceClose(watch) }
                            when (nextState) {
                                is Closing -> Pair(nextState, actions)
                                is Closed -> Pair(nextState, actions)
                                else -> Pair(Syncing(nextState, channelReestablishSent), actions)
                            }
                        }
                    }
                    is WatchConfirmedTriggered -> when (watch.event) {
                        WatchConfirmed.ChannelFundingDepthOk -> {
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
                                Pair(this@Syncing, listOf())
                            }
                        }
                        else -> Pair(this@Syncing, listOf())
                    }
                }
                is WaitForFundingSigned -> Pair(this@Syncing, listOf())
            }
            is ChannelCommand.Commitment.CheckHtlcTimeout -> when (state) {
                is ChannelStateWithCommitments -> {
                    val (newState, actions) = state.run { checkHtlcTimeout() }
                    when (newState) {
                        is Closing -> Pair(newState, actions)
                        is Closed -> Pair(newState, actions)
                        else -> Pair(Syncing(newState, channelReestablishSent), actions)
                    }
                }
                is WaitForFundingSigned -> Pair(this@Syncing, listOf())
            }
            is ChannelCommand.Commitment -> unhandled(cmd)
            is ChannelCommand.Htlc -> unhandled(cmd)
            is ChannelCommand.Connected -> unhandled(cmd)
            is ChannelCommand.Disconnected -> Pair(Offline(state), listOf())
            is ChannelCommand.Close.MutualClose -> {
                cmd.replyTo.complete(ChannelCloseResponse.Failure.ChannelOffline)
                Pair(this@Syncing, listOf(ChannelAction.ProcessCmdRes.NotExecuted(cmd, CommandUnavailableInThisState(channelId, stateName))))
            }
            is ChannelCommand.Close.ForceClose -> state.run { handleLocalError(cmd, ForcedLocalCommit(channelId)) }
            is ChannelCommand.Init -> unhandled(cmd)
            is ChannelCommand.Funding -> unhandled(cmd)
            is ChannelCommand.Closing -> unhandled(cmd)
        }
    }

    private fun ChannelContext.handleSyncFailure(commitments: Commitments, remoteChannelReestablish: ChannelReestablish, syncFailure: SyncResult.Failure): Pair<ChannelState, List<ChannelAction>> {
        return when (syncFailure) {
            is SyncResult.Failure.LocalLateProven -> {
                // their data checks out, we indeed seem to be using an old revoked commitment, and must absolutely *NOT* publish it, because that would be a cheating attempt and they
                // would punish us by taking all the funds in the channel
                logger.warning { "counterparty proved that we have an outdated (revoked) local commitment!!! ourCommitmentNumber=${commitments.localCommitIndex} theirCommitmentNumber=${remoteChannelReestablish.nextRemoteRevocationNumber}" }
                handleOutdatedCommitment(remoteChannelReestablish, commitments)
            }
            is SyncResult.Failure.LocalLateUnproven -> {
                // there is no way to make sure that they are saying the truth, the best thing to do is ask them to publish their commitment right now
                // maybe they will publish their commitment, in that case we need to remember their commitment point in order to be able to claim our outputs
                // not that if they don't comply, we could publish our own commitment (it is not stale, otherwise we would be in the case above)
                logger.warning { "counterparty says that they have a more recent commitment than the one we know of!!! ourCommitmentNumber=${commitments.latest.nextRemoteCommit?.commit?.index ?: commitments.latest.remoteCommit.index} theirCommitmentNumber=${remoteChannelReestablish.nextLocalCommitmentNumber}" }
                handleOutdatedCommitment(remoteChannelReestablish, commitments)
            }
            is SyncResult.Failure.RemoteLying -> {
                // they are deliberately trying to fool us into thinking we have a late commitment, but we cannot risk publishing it ourselves, because it may really be revoked!
                logger.warning { "counterparty claims that we have an outdated commitment, but they sent an invalid proof, so our commitment may or may not be revoked: ourLocalCommitmentNumber=${commitments.localCommitIndex} theirRemoteCommitmentNumber=${remoteChannelReestablish.nextRemoteRevocationNumber}" }
                handleOutdatedCommitment(remoteChannelReestablish, commitments)
            }
            is SyncResult.Failure.RemoteLate -> {
                logger.error { "counterparty appears to be using an outdated commitment, they may request a force-close, standing by..." }
                Pair(this@Syncing, listOf())
            }
        }
    }

    companion object {

        // @formatter:off
        sealed class SyncResult {
            data class Success(val retransmit: List<LightningMessage>) : SyncResult()
            sealed class Failure : SyncResult() {
                data class LocalLateProven(val ourLocalCommitmentNumber: Long, val theirRemoteCommitmentNumber: Long) : Failure()
                data class LocalLateUnproven(val ourRemoteCommitmentNumber: Long, val theirLocalCommitmentNumber: Long) : Failure()
                data class RemoteLying(val ourLocalCommitmentNumber: Long, val theirRemoteCommitmentNumber: Long, val invalidPerCommitmentSecret: PrivateKey) : Failure()
                data object RemoteLate : Failure()
            }
        }
        // @formatter:on

        /**
         * Check whether we are in sync with our peer.
         */
        fun ChannelContext.handleSync(commitments: Commitments, remoteChannelReestablish: ChannelReestablish): SyncResult {
            val channelKeys = keyManager.channelKeys(commitments.params.localParams.fundingKeyPath)
            // This is done in two steps:
            // - step 1: we check our local commitment
            // - step 2: we check the remote commitment
            // step 2 depends on step 1 because we need to preserve ordering between commit_sig and revocation

            // step 2: we check the remote commitment
            fun checkRemoteCommit(remoteChannelReestablish: ChannelReestablish, retransmitRevocation: RevokeAndAck?): SyncResult {
                return when (val rnci = commitments.remoteNextCommitInfo) {
                    is Either.Left -> {
                        when {
                            remoteChannelReestablish.nextLocalCommitmentNumber == commitments.nextRemoteCommitIndex -> {
                                // We just sent a new commit_sig but they didn't receive it: we resend the same updates and sign them again,
                                // and preserve the same ordering of messages.
                                val signedUpdates = commitments.changes.localChanges.signed
                                val channelParams = commitments.params
                                val batchSize = commitments.active.size
                                val commitSigs = CommitSigs.fromSigs(commitments.active.mapNotNull { c ->
                                    val commitInput = c.commitInput
                                    // Note that we ignore errors and simply skip failures to sign: we've already signed those updates before
                                    // the disconnection, so we don't expect any error here unless our peer sends an invalid nonce. In that
                                    // case, we simply won't send back our commit_sig until they fix their node.
                                    c.nextRemoteCommit?.commit?.sign(channelParams, channelKeys, c.fundingTxIndex, c.remoteFundingPubkey, commitInput, batchSize)
                                })
                                val retransmit = when (retransmitRevocation) {
                                    null -> buildList {
                                        addAll(signedUpdates)
                                        add(commitSigs)
                                    }
                                    else -> if (commitments.localCommitIndex > rnci.value.sentAfterLocalCommitIndex) {
                                        buildList {
                                            addAll(signedUpdates)
                                            add(commitSigs)
                                            add(retransmitRevocation)
                                        }
                                    } else {
                                        buildList {
                                            add(retransmitRevocation)
                                            addAll(signedUpdates)
                                            add(commitSigs)
                                        }
                                    }
                                }
                                SyncResult.Success(retransmit)
                            }
                            remoteChannelReestablish.nextLocalCommitmentNumber == (commitments.nextRemoteCommitIndex + 1) -> {
                                // we just sent a new commit_sig, they have received it but we haven't received their revocation
                                SyncResult.Success(retransmit = listOfNotNull(retransmitRevocation))
                            }
                            remoteChannelReestablish.nextLocalCommitmentNumber < commitments.nextRemoteCommitIndex -> {
                                // they are behind
                                SyncResult.Failure.RemoteLate
                            }
                            else -> {
                                // we are behind
                                SyncResult.Failure.LocalLateUnproven(
                                    ourRemoteCommitmentNumber = commitments.nextRemoteCommitIndex,
                                    theirLocalCommitmentNumber = remoteChannelReestablish.nextLocalCommitmentNumber - 1
                                )
                            }
                        }
                    }
                    is Either.Right -> {
                        when {
                            remoteChannelReestablish.nextLocalCommitmentNumber == (commitments.remoteCommitIndex + 1) -> {
                                // they have acknowledged the last commit_sig we sent
                                SyncResult.Success(retransmit = listOfNotNull(retransmitRevocation))
                            }
                            remoteChannelReestablish.nextLocalCommitmentNumber == commitments.remoteCommitIndex && remoteChannelReestablish.nextFundingTxId != null -> {
                                // they haven't received the commit_sig we sent as part of signing a splice transaction
                                // we will retransmit it before exchanging tx_signatures
                                SyncResult.Success(retransmit = listOfNotNull(retransmitRevocation))
                            }
                            remoteChannelReestablish.nextLocalCommitmentNumber < (commitments.remoteCommitIndex + 1) -> {
                                // they are behind
                                SyncResult.Failure.RemoteLate
                            }
                            else -> {
                                // we are behind
                                SyncResult.Failure.LocalLateUnproven(
                                    ourRemoteCommitmentNumber = commitments.remoteCommitIndex,
                                    theirLocalCommitmentNumber = remoteChannelReestablish.nextLocalCommitmentNumber - 1
                                )
                            }
                        }
                    }
                }
            }

            // step 1: we check our local commitment
            return if (commitments.localCommitIndex == remoteChannelReestablish.nextRemoteRevocationNumber) {
                // our local commitment is in sync, let's check the remote commitment
                checkRemoteCommit(remoteChannelReestablish, retransmitRevocation = null)
            } else if (commitments.localCommitIndex == remoteChannelReestablish.nextRemoteRevocationNumber + 1) {
                // they just sent a new commit_sig, we have received it but they didn't receive our revocation
                val localPerCommitmentSecret = channelKeys.commitmentSecret(commitments.localCommitIndex - 1)
                val localNextPerCommitmentPoint = channelKeys.commitmentPoint(commitments.localCommitIndex + 1)
                val revocation = RevokeAndAck(
                    channelId = commitments.channelId,
                    perCommitmentSecret = localPerCommitmentSecret,
                    nextPerCommitmentPoint = localNextPerCommitmentPoint
                )
                checkRemoteCommit(remoteChannelReestablish, retransmitRevocation = revocation)
            } else if (commitments.localCommitIndex > remoteChannelReestablish.nextRemoteRevocationNumber + 1) {
                SyncResult.Failure.RemoteLate
            } else {
                // if next_remote_revocation_number is greater than our local commitment index, it means that either we are using an outdated commitment, or they are lying
                // but first we need to make sure that the last per_commitment_secret that they claim to have received from us is correct for that next_remote_revocation_number minus 1
                if (channelKeys.commitmentSecret(remoteChannelReestablish.nextRemoteRevocationNumber - 1) == remoteChannelReestablish.yourLastCommitmentSecret) {
                    SyncResult.Failure.LocalLateProven(
                        ourLocalCommitmentNumber = commitments.localCommitIndex,
                        theirRemoteCommitmentNumber = remoteChannelReestablish.nextRemoteRevocationNumber
                    )
                } else {
                    // they lied! the last per_commitment_secret they claimed to have received from us is invalid
                    SyncResult.Failure.RemoteLying(
                        ourLocalCommitmentNumber = commitments.localCommitIndex,
                        theirRemoteCommitmentNumber = remoteChannelReestablish.nextRemoteRevocationNumber,
                        invalidPerCommitmentSecret = remoteChannelReestablish.yourLastCommitmentSecret
                    )
                }
            }
        }

        /** When reconnecting, we drop all unsigned changes. */
        fun ChannelContext.discardUnsignedUpdates(commitments: Commitments): Commitments {
            logger.debug { "discarding proposed OUT: ${commitments.changes.localChanges.proposed}" }
            logger.debug { "discarding proposed IN: ${commitments.changes.remoteChanges.proposed}" }
            val commitments1 = commitments.copy(
                changes = commitments.changes.copy(
                    localChanges = commitments.changes.localChanges.copy(proposed = emptyList()),
                    remoteChanges = commitments.changes.remoteChanges.copy(proposed = emptyList()),
                    localNextHtlcId = commitments.changes.localNextHtlcId - commitments.changes.localChanges.proposed.filterIsInstance<UpdateAddHtlc>().size,
                    remoteNextHtlcId = commitments.changes.remoteNextHtlcId - commitments.changes.remoteChanges.proposed.filterIsInstance<UpdateAddHtlc>().size
                )
            )
            logger.debug { "localNextHtlcId=${commitments.changes.localNextHtlcId}->${commitments1.changes.localNextHtlcId}" }
            logger.debug { "remoteNextHtlcId=${commitments.changes.remoteNextHtlcId}->${commitments1.changes.remoteNextHtlcId}" }
            return commitments1
        }

        private fun handleOutdatedCommitment(remoteChannelReestablish: ChannelReestablish, commitments: Commitments): Pair<ChannelStateWithCommitments, List<ChannelAction>> {
            val exc = PleasePublishYourCommitment(commitments.channelId)
            val error = Error(commitments.channelId, exc.message.encodeToByteArray().toByteVector())
            val nextState = WaitForRemotePublishFutureCommitment(commitments, remoteChannelReestablish)
            val actions = listOf(
                ChannelAction.Storage.StoreState(nextState),
                ChannelAction.Message.Send(error)
            )
            return Pair(nextState, actions)
        }
    }

}
