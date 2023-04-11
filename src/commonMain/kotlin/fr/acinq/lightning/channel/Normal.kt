package fr.acinq.lightning.channel

import fr.acinq.bitcoin.ByteVector32
import fr.acinq.lightning.Feature
import fr.acinq.lightning.Features
import fr.acinq.lightning.ShortChannelId
import fr.acinq.lightning.blockchain.BITCOIN_FUNDING_DEPTHOK
import fr.acinq.lightning.blockchain.WatchConfirmed
import fr.acinq.lightning.blockchain.WatchEventConfirmed
import fr.acinq.lightning.blockchain.WatchEventSpent
import fr.acinq.lightning.router.Announcements
import fr.acinq.lightning.transactions.Transactions
import fr.acinq.lightning.utils.Either
import fr.acinq.lightning.utils.msat
import fr.acinq.lightning.utils.sat
import fr.acinq.lightning.utils.toMilliSatoshi
import fr.acinq.lightning.wire.*

data class Normal(
    override val commitments: Commitments,
    val shortChannelId: ShortChannelId,
    val buried: Boolean,
    val channelAnnouncement: ChannelAnnouncement?,
    val channelUpdate: ChannelUpdate,
    val remoteChannelUpdate: ChannelUpdate?,
    val localShutdown: Shutdown?,
    val remoteShutdown: Shutdown?,
    val closingFeerates: ClosingFeerates?,
    val spliceStatus: SpliceStatus
) : ChannelStateWithCommitments() {

    override fun updateCommitments(input: Commitments): ChannelStateWithCommitments = this.copy(commitments = input)

    override fun ChannelContext.processInternal(cmd: ChannelCommand): Pair<ChannelState, List<ChannelAction>> {
        return when (cmd) {
            is ChannelCommand.ExecuteCommand -> when {
                cmd.command is Command.ForbiddenDuringSplice && spliceStatus !is SpliceStatus.None -> {
                    val error = ForbiddenDuringSplice(channelId, cmd.command::class.simpleName)
                    return handleCommandError(cmd.command, error, channelUpdate)
                }
                else -> when (cmd.command) {
                    is CMD_ADD_HTLC -> {
                        if (localShutdown != null || remoteShutdown != null) {
                            // note: spec would allow us to keep sending new htlcs after having received their shutdown (and not sent ours)
                            // but we want to converge as fast as possible and they would probably not route them anyway
                            val error = NoMoreHtlcsClosingInProgress(channelId)
                            return handleCommandError(cmd.command, error, channelUpdate)
                        }
                        handleCommandResult(cmd.command, commitments.sendAdd(cmd.command, cmd.command.paymentId, currentBlockHeight.toLong()), cmd.command.commit)
                    }
                    is CMD_FULFILL_HTLC -> handleCommandResult(cmd.command, commitments.sendFulfill(cmd.command), cmd.command.commit)
                    is CMD_FAIL_HTLC -> handleCommandResult(cmd.command, commitments.sendFail(cmd.command, this.privateKey), cmd.command.commit)
                    is CMD_FAIL_MALFORMED_HTLC -> handleCommandResult(cmd.command, commitments.sendFailMalformed(cmd.command), cmd.command.commit)
                    is CMD_UPDATE_FEE -> handleCommandResult(cmd.command, commitments.sendFee(cmd.command), cmd.command.commit)
                    is CMD_SIGN -> when {
                        !commitments.changes.localHasChanges() -> {
                            logger.warning { "no changes to sign" }
                            Pair(this@Normal, listOf())
                        }
                        commitments.remoteNextCommitInfo is Either.Left -> {
                            logger.debug { "already in the process of signing, will sign again as soon as possible" }
                            Pair(this@Normal, listOf())
                        }
                        else -> when (val result = commitments.sendCommit(keyManager, logger)) {
                            is Either.Left -> handleCommandError(cmd.command, result.value, channelUpdate)
                            is Either.Right -> {
                                val commitments1 = result.value.first
                                val nextRemoteSpec = commitments1.latest.nextRemoteCommit!!.commit.spec
                                // we persist htlc data in order to be able to claim htlc outputs in case a revoked tx is published by our
                                // counterparty, so only htlcs above remote's dust_limit matter
                                val trimmedHtlcs = Transactions.trimOfferedHtlcs(commitments.params.remoteParams.dustLimit, nextRemoteSpec) + Transactions.trimReceivedHtlcs(commitments.params.remoteParams.dustLimit, nextRemoteSpec)
                                val htlcInfos = trimmedHtlcs.map { it.add }.map {
                                    logger.info { "adding paymentHash=${it.paymentHash} cltvExpiry=${it.cltvExpiry} to htlcs db for commitNumber=${commitments1.nextRemoteCommitIndex}" }
                                    ChannelAction.Storage.HtlcInfo(channelId, commitments1.nextRemoteCommitIndex, it.paymentHash, it.cltvExpiry)
                                }
                                val nextState = this@Normal.copy(commitments = commitments1)
                                val actions = buildList {
                                    add(ChannelAction.Storage.StoreHtlcInfos(htlcInfos))
                                    add(ChannelAction.Storage.StoreState(nextState))
                                    addAll(result.value.second.map { ChannelAction.Message.Send(it) })
                                }
                                Pair(nextState, actions)
                            }
                        }
                    }
                    is CMD_CLOSE -> {
                        val allowAnySegwit = Features.canUseFeature(commitments.params.localParams.features, commitments.params.remoteParams.features, Feature.ShutdownAnySegwit)
                        val localScriptPubkey = cmd.command.scriptPubKey ?: commitments.params.localParams.defaultFinalScriptPubKey
                        when {
                            localShutdown != null -> handleCommandError(cmd.command, ClosingAlreadyInProgress(channelId), channelUpdate)
                            commitments.changes.localHasUnsignedOutgoingHtlcs() -> handleCommandError(cmd.command, CannotCloseWithUnsignedOutgoingHtlcs(channelId), channelUpdate)
                            commitments.changes.localHasUnsignedOutgoingUpdateFee() -> handleCommandError(cmd.command, CannotCloseWithUnsignedOutgoingUpdateFee(channelId), channelUpdate)
                            !Helpers.Closing.isValidFinalScriptPubkey(localScriptPubkey, allowAnySegwit) -> handleCommandError(cmd.command, InvalidFinalScript(channelId), channelUpdate)
                            else -> {
                                val shutdown = Shutdown(channelId, localScriptPubkey)
                                val newState = this@Normal.copy(localShutdown = shutdown, closingFeerates = cmd.command.feerates)
                                val actions = listOf(ChannelAction.Storage.StoreState(newState), ChannelAction.Message.Send(shutdown))
                                Pair(newState, actions)
                            }
                        }
                    }
                    is CMD_FORCECLOSE -> handleLocalError(cmd, ForcedLocalCommit(channelId))
                    is CMD_BUMP_FUNDING_FEE -> unhandled(cmd)
                    is Command.Splice.Request -> when (spliceStatus) {
                        is SpliceStatus.None -> {
                            if (commitments.isIdle()) {
                                val parentCommitment = commitments.active.first()
                                val fundingContribution = FundingContributions.computeSpliceContribution(
                                    isInitiator = true,
                                    commitment = parentCommitment,
                                    walletInputs = cmd.command.spliceIn?.wallet?.confirmedUtxos ?: emptyList(),
                                    localOutputs = cmd.command.spliceOutputs,
                                    targetFeerate = cmd.command.feerate
                                )
                                if (parentCommitment.localCommit.spec.toLocal + fundingContribution.toMilliSatoshi() < 0.msat) {
                                    logger.warning { "cannot do splice: insufficient funds" }
                                    cmd.command.replyTo.complete(Command.Splice.Response.Failure.InsufficientFunds)
                                    Pair(this@Normal, emptyList())
                                } else if (cmd.command.spliceOut?.scriptPubKey?.let { Helpers.Closing.isValidFinalScriptPubkey(it, allowAnySegwit = true) } == false) {
                                    logger.warning { "cannot do splice: invalid splice-out script" }
                                    cmd.command.replyTo.complete(Command.Splice.Response.Failure.InvalidSpliceOutPubKeyScript)
                                    Pair(this@Normal, emptyList())
                                } else {
                                    val spliceInit = SpliceInit(
                                        channelId,
                                        fundingContribution = fundingContribution,
                                        lockTime = currentBlockHeight.toLong(),
                                        feerate = cmd.command.feerate,
                                        pushAmount = cmd.command.pushAmount
                                    )
                                    logger.info { "initiating splice with local.amount=${spliceInit.fundingContribution} local.push=${spliceInit.pushAmount}" }
                                    Pair(this@Normal.copy(spliceStatus = SpliceStatus.Requested(cmd.command, spliceInit)), listOf(ChannelAction.Message.Send(spliceInit)))
                                }
                            } else {
                                logger.warning { "cannot initiate splice, channel not idle" }
                                cmd.command.replyTo.complete(Command.Splice.Response.Failure.ChannelNotIdle)
                                Pair(this@Normal, emptyList())
                            }
                        }
                        else -> {
                            logger.warning { "cannot initiate splice, another splice is already in progress" }
                            cmd.command.replyTo.complete(Command.Splice.Response.Failure.SpliceAlreadyInProgress)
                            Pair(this@Normal, emptyList())
                        }
                    }
                }
            }
            is ChannelCommand.MessageReceived -> when {
                cmd.message is ForbiddenMessageDuringSplice && spliceStatus !is SpliceStatus.None && spliceStatus !is SpliceStatus.Requested -> {
                    // In case of a race between our splice_init and a forbidden message from our peer, we accept their message, because
                    // we know they are going to reject our splice attempt
                    val error = ForbiddenDuringSplice(channelId, cmd.message::class.simpleName)
                    handleLocalError(cmd, error)
                }
                else -> when (cmd.message) {
                    is UpdateAddHtlc -> when (val result = commitments.receiveAdd(cmd.message)) {
                        is Either.Left -> handleLocalError(cmd, result.value)
                        is Either.Right -> {
                            val newState = this@Normal.copy(commitments = result.value)
                            Pair(newState, listOf())
                        }
                    }
                    is UpdateFulfillHtlc -> when (val result = commitments.receiveFulfill(cmd.message)) {
                        is Either.Left -> handleLocalError(cmd, result.value)
                        is Either.Right -> {
                            val (commitments1, paymentId, add) = result.value
                            val htlcResult = ChannelAction.HtlcResult.Fulfill.RemoteFulfill(cmd.message)
                            Pair(this@Normal.copy(commitments = commitments1), listOf(ChannelAction.ProcessCmdRes.AddSettledFulfill(paymentId, add, htlcResult)))
                        }
                    }
                    is UpdateFailHtlc -> when (val result = commitments.receiveFail(cmd.message)) {
                        is Either.Left -> handleLocalError(cmd, result.value)
                        is Either.Right -> Pair(this@Normal.copy(commitments = result.value.first), listOf())
                    }
                    is UpdateFailMalformedHtlc -> when (val result = commitments.receiveFailMalformed(cmd.message)) {
                        is Either.Left -> handleLocalError(cmd, result.value)
                        is Either.Right -> Pair(this@Normal.copy(commitments = result.value.first), listOf())
                    }
                    is UpdateFee -> when (val result = commitments.receiveFee(cmd.message, staticParams.nodeParams.onChainFeeConf.feerateTolerance)) {
                        is Either.Left -> handleLocalError(cmd, result.value)
                        is Either.Right -> Pair(this@Normal.copy(commitments = result.value), listOf())
                    }
                    is CommitSig -> when {
                        spliceStatus is SpliceStatus.WaitingForSigs -> {
                            val (signingSession1, action) = spliceStatus.session.receiveCommitSig(keyManager, commitments.params, cmd.message, currentBlockHeight.toLong())
                            when (action) {
                                is InteractiveTxSigningSessionAction.AbortFundingAttempt -> {
                                    logger.warning { "splice attempt failed: ${action.reason.message}" }
                                    Pair(this@Normal.copy(spliceStatus = SpliceStatus.Aborted), listOf(ChannelAction.Message.Send(TxAbort(channelId, action.reason.message))))
                                }
                                // No need to store their commit_sig, they will re-send it if we disconnect.
                                InteractiveTxSigningSessionAction.WaitForTxSigs -> {
                                    logger.info { "waiting for tx_sigs" }
                                    Pair(this@Normal.copy(spliceStatus = spliceStatus.copy(session = signingSession1)), listOf())
                                }
                                is InteractiveTxSigningSessionAction.SendTxSigs -> sendSpliceTxSigs(action, cmd.message.channelData)
                            }
                        }
                        commitments.latest.localFundingStatus.signedTx == null && cmd.message.batchSize == 1 -> {
                            // The latest funding transaction is unconfirmed and we're missing our peer's tx_signatures: any commit_sig that we receive before that should be ignored,
                            // it's either a retransmission of a commit_sig we've already received or a bug that will eventually lead to a force-close anyway.
                            logger.info { "ignoring commit_sig, we're still waiting for tx_signatures" }
                            Pair(this@Normal, listOf())
                        }
                        // NB: in all other cases we process the commit_sig normally. We could do a full pattern matching on all splice statuses, but it would force us to handle
                        // corner cases like race condition between splice_init and a non-splice commit_sig
                        else -> {
                            when (val sigs = aggregateSigs(cmd.message)) {
                                is List<CommitSig> -> when (val result = commitments.receiveCommit(sigs, keyManager, logger)) {
                                    is Either.Left -> handleLocalError(cmd, result.value)
                                    is Either.Right -> {
                                        val nextState = this@Normal.copy(commitments = result.value.first)
                                        val actions = mutableListOf<ChannelAction>()
                                        actions.add(ChannelAction.Storage.StoreState(nextState))
                                        actions.add(ChannelAction.Message.Send(result.value.second))
                                        if (result.value.first.changes.localHasChanges()) {
                                            actions.add(ChannelAction.Message.SendToSelf(CMD_SIGN))
                                        }
                                        Pair(nextState, actions)
                                    }
                                }
                                else -> Pair(this@Normal, listOf())
                            }
                        }
                    }
                    is RevokeAndAck -> when (val result = commitments.receiveRevocation(cmd.message)) {
                        is Either.Left -> handleLocalError(cmd, result.value)
                        is Either.Right -> {
                            val commitments1 = result.value.first
                            val actions = mutableListOf<ChannelAction>()
                            actions.addAll(result.value.second)
                            if (result.value.first.changes.localHasChanges()) {
                                actions.add(ChannelAction.Message.SendToSelf(CMD_SIGN))
                            }
                            val nextState = if (remoteShutdown != null && !commitments1.changes.localHasUnsignedOutgoingHtlcs()) {
                                // we were waiting for our pending htlcs to be signed before replying with our local shutdown
                                val localShutdown = Shutdown(channelId, commitments.params.localParams.defaultFinalScriptPubKey)
                                actions.add(ChannelAction.Message.Send(localShutdown))
                                if (commitments1.latest.remoteCommit.spec.htlcs.isNotEmpty()) {
                                    // we just signed htlcs that need to be resolved now
                                    ShuttingDown(commitments1, localShutdown, remoteShutdown, closingFeerates)
                                } else {
                                    logger.warning { "we have no htlcs but have not replied with our Shutdown yet, this should never happen" }
                                    val closingTxProposed = if (isInitiator) {
                                        val (closingTx, closingSigned) = Helpers.Closing.makeFirstClosingTx(
                                            keyManager,
                                            commitments1.latest,
                                            localShutdown.scriptPubKey.toByteArray(),
                                            remoteShutdown.scriptPubKey.toByteArray(),
                                            closingFeerates ?: ClosingFeerates(currentOnChainFeerates.mutualCloseFeerate),
                                        )
                                        listOf(listOf(ClosingTxProposed(closingTx, closingSigned)))
                                    } else {
                                        listOf(listOf())
                                    }
                                    Negotiating(commitments1, localShutdown, remoteShutdown, closingTxProposed, bestUnpublishedClosingTx = null, closingFeerates)
                                }
                            } else {
                                this@Normal.copy(commitments = commitments1)
                            }
                            actions.add(0, ChannelAction.Storage.StoreState(nextState))
                            Pair(nextState, actions)
                        }
                    }
                    is ChannelUpdate -> {
                        if (cmd.message.shortChannelId == shortChannelId && cmd.message.isRemote(staticParams.nodeParams.nodeId, staticParams.remoteNodeId)) {
                            val nextState = this@Normal.copy(remoteChannelUpdate = cmd.message)
                            Pair(nextState, listOf(ChannelAction.Storage.StoreState(nextState)))
                        } else {
                            Pair(this@Normal, listOf())
                        }
                    }
                    is Shutdown -> {
                        val allowAnySegwit = Features.canUseFeature(commitments.params.localParams.features, commitments.params.remoteParams.features, Feature.ShutdownAnySegwit)
                        // they have pending unsigned htlcs         => they violated the spec, close the channel
                        // they don't have pending unsigned htlcs
                        //    we have pending unsigned htlcs
                        //      we already sent a shutdown message  => spec violation (we can't send htlcs after having sent shutdown)
                        //      we did not send a shutdown message
                        //        we are ready to sign              => we stop sending further htlcs, we initiate a signature
                        //        we are waiting for a rev          => we stop sending further htlcs, we wait for their revocation, will resign immediately after, and then we will send our shutdown message
                        //    we have no pending unsigned htlcs
                        //      we already sent a shutdown message
                        //        there are pending signed changes  => send our shutdown message, go to SHUTDOWN
                        //        there are no changes              => send our shutdown message, go to NEGOTIATING
                        //      we did not send a shutdown message
                        //        there are pending signed changes  => go to SHUTDOWN
                        //        there are no changes              => go to NEGOTIATING
                        when {
                            !Helpers.Closing.isValidFinalScriptPubkey(cmd.message.scriptPubKey, allowAnySegwit) -> handleLocalError(cmd, InvalidFinalScript(channelId))
                            commitments.changes.remoteHasUnsignedOutgoingHtlcs() -> handleLocalError(cmd, CannotCloseWithUnsignedOutgoingHtlcs(channelId))
                            commitments.changes.remoteHasUnsignedOutgoingUpdateFee() -> handleLocalError(cmd, CannotCloseWithUnsignedOutgoingUpdateFee(channelId))
                            commitments.changes.localHasUnsignedOutgoingHtlcs() -> {
                                require(localShutdown == null) { "can't have pending unsigned outgoing htlcs after having sent Shutdown" }
                                // are we in the middle of a signature?
                                when (commitments.remoteNextCommitInfo) {
                                    is Either.Left -> {
                                        // we already have a signature in progress, will resign when we receive the revocation
                                        Pair(this@Normal.copy(remoteShutdown = cmd.message), listOf())
                                    }
                                    is Either.Right -> {
                                        // no, let's sign right away
                                        val newState = this@Normal.copy(remoteShutdown = cmd.message, commitments = commitments.copy(remoteChannelData = cmd.message.channelData))
                                        Pair(newState, listOf(ChannelAction.Message.SendToSelf(CMD_SIGN)))
                                    }
                                }
                            }
                            else -> {
                                // so we don't have any unsigned outgoing changes
                                val actions = mutableListOf<ChannelAction>()
                                val localShutdown = this@Normal.localShutdown ?: Shutdown(channelId, commitments.params.localParams.defaultFinalScriptPubKey)
                                if (this@Normal.localShutdown == null) actions.add(ChannelAction.Message.Send(localShutdown))
                                val commitments1 = commitments.copy(remoteChannelData = cmd.message.channelData)
                                when {
                                    commitments1.hasNoPendingHtlcsOrFeeUpdate() && commitments1.params.localParams.isInitiator -> {
                                        val (closingTx, closingSigned) = Helpers.Closing.makeFirstClosingTx(
                                            keyManager,
                                            commitments1.latest,
                                            localShutdown.scriptPubKey.toByteArray(),
                                            cmd.message.scriptPubKey.toByteArray(),
                                            closingFeerates ?: ClosingFeerates(currentOnChainFeerates.mutualCloseFeerate),
                                        )
                                        val nextState = Negotiating(
                                            commitments1,
                                            localShutdown,
                                            cmd.message,
                                            listOf(listOf(ClosingTxProposed(closingTx, closingSigned))),
                                            bestUnpublishedClosingTx = null,
                                            closingFeerates
                                        )
                                        actions.addAll(listOf(ChannelAction.Storage.StoreState(nextState), ChannelAction.Message.Send(closingSigned)))
                                        Pair(nextState, actions)
                                    }
                                    commitments1.hasNoPendingHtlcsOrFeeUpdate() -> {
                                        val nextState = Negotiating(commitments1, localShutdown, cmd.message, listOf(listOf()), null, closingFeerates)
                                        actions.add(ChannelAction.Storage.StoreState(nextState))
                                        Pair(nextState, actions)
                                    }
                                    else -> {
                                        // there are some pending changes, we need to wait for them to be settled (fail/fulfill htlcs and sign fee updates)
                                        val nextState = ShuttingDown(commitments1, localShutdown, cmd.message, closingFeerates)
                                        actions.add(ChannelAction.Storage.StoreState(nextState))
                                        Pair(nextState, actions)
                                    }
                                }
                            }
                        }
                    }
                    is SpliceInit -> when (spliceStatus) {
                        is SpliceStatus.None ->
                            if (commitments.isIdle()) {
                                logger.info { "accepting splice with remote.amount=${cmd.message.fundingContribution} remote.push=${cmd.message.pushAmount}" }
                                val parentCommitment = commitments.active.first()
                                val spliceAck = SpliceAck(
                                    channelId,
                                    fundingContribution = 0.sat, // only remote contributes to the splice
                                    pushAmount = 0.msat
                                )
                                val fundingParams = InteractiveTxParams(
                                    channelId = channelId,
                                    isInitiator = false,
                                    localContribution = spliceAck.fundingContribution,
                                    remoteContribution = cmd.message.fundingContribution,
                                    sharedInput = SharedFundingInput.Multisig2of2(keyManager, commitments.params, parentCommitment),
                                    fundingPubkeyScript = parentCommitment.commitInput.txOut.publicKeyScript, // same pubkey script as before
                                    localOutputs = emptyList(),
                                    lockTime = cmd.message.lockTime,
                                    dustLimit = commitments.params.localParams.dustLimit.max(commitments.params.remoteParams.dustLimit),
                                    targetFeerate = cmd.message.feerate
                                )
                                val session = InteractiveTxSession(
                                    fundingParams,
                                    previousLocalBalance = parentCommitment.localCommit.spec.toLocal,
                                    previousRemoteBalance = parentCommitment.localCommit.spec.toRemote,
                                    fundingContributions = FundingContributions(emptyList(), emptyList()), // as non-initiator we don't contribute to this splice for now
                                    previousTxs = emptyList()
                                )
                                val nextState = this@Normal.copy(spliceStatus = SpliceStatus.InProgress(replyTo = null, session, localPushAmount = 0.msat, remotePushAmount = cmd.message.pushAmount, origins = cmd.message.origins))
                                Pair(nextState, listOf(ChannelAction.Message.Send(SpliceAck(channelId, fundingParams.localContribution))))
                            } else {
                                logger.info { "rejecting splice attempt: channel is not idle" }
                                Pair(this@Normal.copy(spliceStatus = SpliceStatus.Aborted), listOf(ChannelAction.Message.Send(TxAbort(channelId, InvalidSpliceChannelNotIdle(channelId).message))))
                            }
                        is SpliceStatus.Aborted -> {
                            logger.info { "rejecting splice attempt: our previous tx_abort was not acked" }
                            Pair(this@Normal, listOf(ChannelAction.Message.Send(Warning(channelId, InvalidSpliceAbortNotAcked(channelId).message))))
                        }
                        else -> {
                            logger.info { "rejecting splice attempt: the current splice attempt must be completed or aborted first" }
                            Pair(this@Normal, listOf(ChannelAction.Message.Send(Warning(channelId, InvalidSpliceAlreadyInProgress(channelId).message))))
                        }
                    }
                    is SpliceAck -> when (spliceStatus) {
                        is SpliceStatus.Requested -> {
                            logger.info { "our peer accepted our splice request and will contribute ${cmd.message.fundingContribution} to the funding transaction" }
                            val parentCommitment = commitments.active.first()
                            val sharedInput = SharedFundingInput.Multisig2of2(keyManager, commitments.params, parentCommitment)
                            val fundingParams = InteractiveTxParams(
                                channelId = channelId,
                                isInitiator = true,
                                localContribution = spliceStatus.spliceInit.fundingContribution,
                                remoteContribution = cmd.message.fundingContribution,
                                sharedInput = sharedInput,
                                fundingPubkeyScript = parentCommitment.commitInput.txOut.publicKeyScript, // same pubkey script as before
                                localOutputs = spliceStatus.command.spliceOutputs,
                                lockTime = spliceStatus.spliceInit.lockTime,
                                dustLimit = commitments.params.localParams.dustLimit.max(commitments.params.remoteParams.dustLimit),
                                targetFeerate = spliceStatus.spliceInit.feerate
                            )
                            when (val fundingContributions = FundingContributions.create(
                                params = fundingParams,
                                sharedUtxo = Pair(sharedInput, SharedFundingInputBalances(toLocal = parentCommitment.localCommit.spec.toLocal, toRemote = parentCommitment.localCommit.spec.toRemote)),
                                walletInputs = spliceStatus.command.spliceIn?.wallet?.confirmedUtxos ?: emptyList(),
                                localOutputs = spliceStatus.command.spliceOutputs,
                                changePubKey = null // we don't want a change output: we're spending every funds available
                            )) {
                                is Either.Left -> {
                                    logger.error { "could not create splice contributions: ${fundingContributions.value}" }
                                    spliceStatus.command.replyTo.complete(Command.Splice.Response.Failure.FundingFailure(fundingContributions.value))
                                    Pair(this@Normal.copy(spliceStatus = SpliceStatus.Aborted), listOf(ChannelAction.Message.Send(TxAbort(channelId, ChannelFundingError(channelId).message))))
                                }
                                is Either.Right -> {
                                    // The splice initiator always sends the first interactive-tx message.
                                    val (interactiveTxSession, interactiveTxAction) = InteractiveTxSession(
                                        fundingParams,
                                        previousLocalBalance = parentCommitment.localCommit.spec.toLocal,
                                        previousRemoteBalance = parentCommitment.localCommit.spec.toRemote,
                                        fundingContributions.value, previousTxs = emptyList()
                                    ).send()
                                    when (interactiveTxAction) {
                                        is InteractiveTxSessionAction.SendMessage -> {
                                            val nextState = this@Normal.copy(
                                                spliceStatus = SpliceStatus.InProgress(
                                                    replyTo = spliceStatus.command.replyTo,
                                                    interactiveTxSession,
                                                    localPushAmount = spliceStatus.spliceInit.pushAmount,
                                                    remotePushAmount = cmd.message.pushAmount,
                                                    origins = spliceStatus.spliceInit.origins
                                                )
                                            )
                                            Pair(nextState, listOf(ChannelAction.Message.Send(interactiveTxAction.msg)))
                                        }
                                        else -> {
                                            logger.error { "could not start interactive-tx session: $interactiveTxAction" }
                                            spliceStatus.command.replyTo.complete(Command.Splice.Response.Failure.CannotStartSession)
                                            Pair(this@Normal.copy(spliceStatus = SpliceStatus.Aborted), listOf(ChannelAction.Message.Send(TxAbort(channelId, ChannelFundingError(channelId).message))))
                                        }
                                    }
                                }
                            }
                        }
                        else -> {
                            logger.warning { "ignoring unexpected splice_ack" }
                            Pair(this@Normal, emptyList())
                        }
                    }
                    is InteractiveTxConstructionMessage -> when (spliceStatus) {
                        is SpliceStatus.InProgress -> {
                            val (interactiveTxSession, interactiveTxAction) = spliceStatus.spliceSession.receive(cmd.message)
                            when (interactiveTxAction) {
                                is InteractiveTxSessionAction.SendMessage -> Pair(this@Normal.copy(spliceStatus = spliceStatus.copy(spliceSession = interactiveTxSession)), listOf(ChannelAction.Message.Send(interactiveTxAction.msg)))
                                is InteractiveTxSessionAction.SignSharedTx -> {
                                    val parentCommitment = commitments.active.first()
                                    val signingSession = InteractiveTxSigningSession.create(
                                        keyManager,
                                        commitments.params,
                                        spliceStatus.spliceSession.fundingParams,
                                        fundingTxIndex = parentCommitment.fundingTxIndex + 1,
                                        interactiveTxAction.sharedTx,
                                        localPushAmount = spliceStatus.localPushAmount,
                                        remotePushAmount = spliceStatus.remotePushAmount,
                                        commitmentIndex = parentCommitment.localCommit.index, // localCommit.index == remoteCommit.index because the channel is idle
                                        parentCommitment.localCommit.spec.feerate,
                                        parentCommitment.remoteCommit.remotePerCommitmentPoint
                                    )
                                    when (signingSession) {
                                        is Either.Left -> {
                                            logger.error(signingSession.value) { "cannot initiate interactive-tx splice signing session" }
                                            spliceStatus.replyTo?.complete(Command.Splice.Response.Failure.CannotCreateCommitTx(signingSession.value))
                                            Pair(this@Normal.copy(spliceStatus = SpliceStatus.Aborted), listOf(ChannelAction.Message.Send(TxAbort(channelId, signingSession.value.message))))
                                        }
                                        is Either.Right -> {
                                            val (session, commitSig) = signingSession.value
                                            logger.info { "splice funding tx created with txId=${session.fundingTx.txId}, ${session.fundingTx.tx.localInputs.size} local inputs, ${session.fundingTx.tx.remoteInputs.size} remote inputs, ${session.fundingTx.tx.localOutputs.size} local outputs and ${session.fundingTx.tx.remoteOutputs.size} remote outputs" }
                                            // We cannot guarantee that the splice is successful: the only way to guarantee that is to wait for on-chain confirmations.
                                            // It is likely that we will restart before the transaction is confirmed, in which case we will lose the replyTo and the ability to notify the caller.
                                            // We should be able to resume the signing steps and complete the splice if we disconnect, so we optimistically notify the caller now.
                                            spliceStatus.replyTo?.complete(
                                                Command.Splice.Response.Created(
                                                    channelId = channelId,
                                                    fundingTxIndex = session.fundingTxIndex,
                                                    fundingTxId = session.fundingTx.txId,
                                                    capacity = session.fundingParams.fundingAmount,
                                                    balance = session.localCommit.fold({ it.spec }, { it.spec }).toLocal
                                                )
                                            )
                                            val nextState = this@Normal.copy(spliceStatus = SpliceStatus.WaitingForSigs(session, spliceStatus.origins))
                                            val actions = buildList {
                                                interactiveTxAction.txComplete?.let { add(ChannelAction.Message.Send(it)) }
                                                add(ChannelAction.Storage.StoreState(nextState))
                                                add(ChannelAction.Message.Send(commitSig))
                                            }
                                            Pair(nextState, actions)
                                        }
                                    }
                                }
                                is InteractiveTxSessionAction.RemoteFailure -> {
                                    logger.warning { "interactive-tx failed: $interactiveTxAction" }
                                    spliceStatus.replyTo?.complete(Command.Splice.Response.Failure.InteractiveTxSessionFailed(interactiveTxAction))
                                    Pair(this@Normal.copy(spliceStatus = SpliceStatus.Aborted), listOf(ChannelAction.Message.Send(TxAbort(channelId, interactiveTxAction.toString()))))
                                }
                            }
                        }
                        else -> {
                            logger.info { "ignoring unexpected interactive-tx message: ${cmd.message::class}" }
                            Pair(this@Normal, listOf(ChannelAction.Message.Send(Warning(channelId, UnexpectedInteractiveTxMessage(channelId, cmd.message).message))))
                        }
                    }
                    is TxSignatures -> when (spliceStatus) {
                        is SpliceStatus.WaitingForSigs -> {
                            when (val action = spliceStatus.session.receiveTxSigs(cmd.message, currentBlockHeight.toLong())) {
                                is InteractiveTxSigningSessionAction.AbortFundingAttempt -> {
                                    logger.warning { "splice attempt failed: ${action.reason.message}" }
                                    Pair(this@Normal.copy(spliceStatus = SpliceStatus.Aborted), listOf(ChannelAction.Message.Send(TxAbort(channelId, action.reason.message))))
                                }
                                InteractiveTxSigningSessionAction.WaitForTxSigs -> Pair(this@Normal, listOf())
                                is InteractiveTxSigningSessionAction.SendTxSigs -> sendSpliceTxSigs(action, cmd.message.channelData)
                            }
                        }
                        else -> when (commitments.latest.localFundingStatus) {
                            is LocalFundingStatus.UnconfirmedFundingTx -> when (commitments.latest.localFundingStatus.sharedTx) {
                                is PartiallySignedSharedTransaction -> when (val fullySignedTx = commitments.latest.localFundingStatus.sharedTx.addRemoteSigs(commitments.latest.localFundingStatus.fundingParams, cmd.message)) {
                                    null -> {
                                        logger.warning { "received invalid remote funding signatures for txId=${cmd.message.txId}" }
                                        logger.warning { "tx=${commitments.latest.localFundingStatus.sharedTx}" }
                                        // The funding transaction may still confirm (since our peer should be able to generate valid signatures), so we cannot close the channel yet.
                                        Pair(this@Normal, listOf(ChannelAction.Message.Send(Warning(channelId, InvalidFundingSignature(channelId, cmd.message.txId).message))))
                                    }
                                    else -> {
                                        when (val res = commitments.run { updateLocalFundingStatus(fullySignedTx.signedTx.txid, commitments.latest.localFundingStatus.copy(sharedTx = fullySignedTx)) }) {
                                            is Either.Left -> Pair(this@Normal, listOf())
                                            is Either.Right -> {
                                                logger.info { "received remote funding signatures, publishing txId=${fullySignedTx.signedTx.txid} fundingTxIndex=${commitments.latest.fundingTxIndex}" }
                                                val nextState = this@Normal.copy(commitments = res.value.first)
                                                val actions = buildList {
                                                    add(ChannelAction.Blockchain.PublishTx(fullySignedTx.signedTx, ChannelAction.Blockchain.PublishTx.Type.FundingTx))
                                                    add(ChannelAction.Storage.StoreState(nextState))
                                                }
                                                Pair(nextState, actions)
                                            }
                                        }
                                    }
                                }
                                is FullySignedSharedTransaction -> {
                                    logger.info { "ignoring duplicate remote funding signatures for txId=${cmd.message.txId}" }
                                    Pair(this@Normal, listOf())
                                }
                            }
                            is LocalFundingStatus.ConfirmedFundingTx -> {
                                logger.info { "ignoring funding signatures for txId=${cmd.message.txId}, transaction is already confirmed" }
                                Pair(this@Normal, listOf())
                            }
                        }
                    }
                    is TxAbort -> when (spliceStatus) {
                        is SpliceStatus.Requested -> {
                            logger.info { "our peer rejected our splice request: ascii='${cmd.message.toAscii()}' bin=${cmd.message.data}" }
                            spliceStatus.command.replyTo.complete(Command.Splice.Response.Failure.AbortedByPeer(cmd.message.toAscii()))
                            Pair(
                                this@Normal.copy(spliceStatus = SpliceStatus.None),
                                listOf(ChannelAction.Message.Send(TxAbort(channelId, SpliceAborted(channelId).message)))
                            )
                        }
                        is SpliceStatus.InProgress -> {
                            logger.info { "our peer aborted the splice attempt: ascii='${cmd.message.toAscii()}' bin=${cmd.message.data}" }
                            spliceStatus.replyTo?.complete(Command.Splice.Response.Failure.AbortedByPeer(cmd.message.toAscii()))
                            Pair(
                                this@Normal.copy(spliceStatus = SpliceStatus.None),
                                listOf(ChannelAction.Message.Send(TxAbort(channelId, SpliceAborted(channelId).message)))
                            )
                        }
                        is SpliceStatus.WaitingForSigs -> {
                            logger.info { "our peer aborted the splice attempt: ascii='${cmd.message.toAscii()}' bin=${cmd.message.data}" }
                            Pair(
                                this@Normal.copy(spliceStatus = SpliceStatus.None),
                                listOf(ChannelAction.Message.Send(TxAbort(channelId, SpliceAborted(channelId).message)))
                            )
                        }
                        is SpliceStatus.Aborted -> {
                            logger.info { "our peer acked our previous tx_abort" }
                            Pair(
                                this@Normal.copy(spliceStatus = SpliceStatus.None),
                                emptyList()
                            )
                        }
                        is SpliceStatus.None -> {
                            logger.info { "our peer wants to abort the splice, but we've already negotiated a splice transaction: ascii='${cmd.message.toAscii()}' bin=${cmd.message.data}" }
                            // We ack their tx_abort but we keep monitoring the funding transaction until it's confirmed or double-spent.
                            Pair(
                                this@Normal,
                                listOf(ChannelAction.Message.Send(TxAbort(channelId, SpliceAborted(channelId).message)))
                            )
                        }
                    }
                    is SpliceLocked -> {
                        when (val res = commitments.run { updateRemoteFundingStatus(cmd.message.fundingTxId) }) {
                            is Either.Left -> Pair(this@Normal, emptyList())
                            is Either.Right -> {
                                val (commitments1, _) = res.value
                                val nextState = this@Normal.copy(commitments = commitments1)
                                Pair(nextState, listOf(ChannelAction.Storage.StoreState(nextState)))
                            }
                        }
                    }
                    is Error -> handleRemoteError(cmd.message)
                    else -> unhandled(cmd)
                }
            }
            is ChannelCommand.CheckHtlcTimeout -> checkHtlcTimeout()
            is ChannelCommand.WatchReceived -> when (val watch = cmd.watch) {
                is WatchEventConfirmed -> {
                    val (nextState, actions) = updateFundingTxStatus(watch)
                    if (!staticParams.useZeroConf && nextState.commitments.active.any { it.fundingTxId == watch.tx.txid && it.fundingTxIndex > 0 }) {
                        // We're not using 0-conf and a splice transaction is confirmed, so we send splice_locked.
                        val spliceLocked = SpliceLocked(channelId, watch.tx.hash)
                        Pair(nextState, actions + ChannelAction.Message.Send(spliceLocked))
                    } else {
                        Pair(nextState, actions)
                    }
                }
                is WatchEventSpent -> handlePotentialForceClose(watch)
            }
            is ChannelCommand.Disconnected -> {
                // if we have pending unsigned outgoing htlcs, then we cancel them and advertise the fact that the channel is now disabled.
                val failedHtlcs = mutableListOf<ChannelAction>()
                val proposedHtlcs = commitments.changes.localChanges.proposed.filterIsInstance<UpdateAddHtlc>()
                if (proposedHtlcs.isNotEmpty()) {
                    logger.info { "updating channel_update announcement (reason=disabled)" }
                    val channelUpdate = Announcements.disableChannel(channelUpdate, staticParams.nodeParams.nodePrivateKey, staticParams.remoteNodeId)
                    proposedHtlcs.forEach { htlc ->
                        commitments.payments[htlc.id]?.let { paymentId ->
                            failedHtlcs.add(ChannelAction.ProcessCmdRes.AddSettledFail(paymentId, htlc, ChannelAction.HtlcResult.Fail.Disconnected(channelUpdate)))
                        } ?: logger.warning { "cannot find payment for $htlc" }
                    }
                }
                // If we are splicing and are early in the process, then we cancel it.
                val spliceStatus1 = when (spliceStatus) {
                    is SpliceStatus.None -> SpliceStatus.None
                    is SpliceStatus.Aborted -> SpliceStatus.None
                    is SpliceStatus.Requested -> {
                        spliceStatus.command.replyTo.complete(Command.Splice.Response.Failure.Disconnected)
                        SpliceStatus.None
                    }
                    is SpliceStatus.InProgress -> {
                        spliceStatus.replyTo?.complete(Command.Splice.Response.Failure.Disconnected)
                        SpliceStatus.None
                    }
                    is SpliceStatus.WaitingForSigs -> spliceStatus
                }
                // reset the commit_sig batch
                sigStash = emptyList()
                Pair(Offline(this@Normal.copy(spliceStatus = spliceStatus1)), failedHtlcs)
            }
            else -> unhandled(cmd)
        }
    }

    private fun ChannelContext.sendSpliceTxSigs(action: InteractiveTxSigningSessionAction.SendTxSigs, remoteChannelData: EncryptedChannelData): Pair<Normal, List<ChannelAction>> {
        logger.info { "sending tx_sigs" }
        // We watch for confirmation in all cases, to allow pruning outdated commitments when transactions confirm.
        val fundingMinDepth = Helpers.minDepthForFunding(staticParams.nodeParams, action.fundingTx.fundingParams.fundingAmount)
        val watchConfirmed = WatchConfirmed(channelId, action.commitment.fundingTxId, action.commitment.commitInput.txOut.publicKeyScript, fundingMinDepth.toLong(), BITCOIN_FUNDING_DEPTHOK)
        val commitments = commitments.add(action.commitment).copy(remoteChannelData = remoteChannelData)
        val nextState = this@Normal.copy(commitments = commitments, spliceStatus = SpliceStatus.None)
        val actions = buildList {
            add(ChannelAction.Storage.StoreState(nextState))
            action.fundingTx.signedTx?.let { add(ChannelAction.Blockchain.PublishTx(it, ChannelAction.Blockchain.PublishTx.Type.FundingTx)) }
            add(ChannelAction.Blockchain.SendWatch(watchConfirmed))
            add(ChannelAction.Message.Send(action.localSigs))
            if (staticParams.useZeroConf) {
                logger.info { "channel is using 0-conf, sending splice_locked right away" }
                val spliceLocked = SpliceLocked(channelId, action.fundingTx.txId.reversed())
                add(ChannelAction.Message.Send(spliceLocked))
            }
        }
        return Pair(nextState, actions)
    }

    /** If we haven't completed the signing steps of an interactive-tx session, we will ask our peer to retransmit signatures for the corresponding transaction. */
    fun getUnsignedFundingTxId(): ByteVector32? = when {
        spliceStatus is SpliceStatus.WaitingForSigs -> spliceStatus.session.fundingTx.txId
        commitments.latest.localFundingStatus is LocalFundingStatus.UnconfirmedFundingTx && commitments.latest.localFundingStatus.sharedTx is PartiallySignedSharedTransaction -> commitments.latest.localFundingStatus.txId
        else -> null
    }

    override fun ChannelContext.handleLocalError(cmd: ChannelCommand, t: Throwable): Pair<ChannelState, List<ChannelAction>> {
        logger.error(t) { "error on command ${cmd::class.simpleName} in state ${this@Normal::class.simpleName}" }
        val error = Error(channelId, t.message)
        return when {
            commitments.nothingAtStake() -> Pair(Aborted, listOf(ChannelAction.Message.Send(error)))
            else -> spendLocalCurrent().run { copy(second = second + ChannelAction.Message.Send(error)) }
        }
    }

    private fun ChannelContext.handleCommandResult(command: Command, result: Either<ChannelException, Pair<Commitments, LightningMessage>>, commit: Boolean): Pair<ChannelState, List<ChannelAction>> {
        return when (result) {
            is Either.Left -> handleCommandError(command, result.value, channelUpdate)
            is Either.Right -> {
                val (commitments1, message) = result.value
                val actions = mutableListOf<ChannelAction>(ChannelAction.Message.Send(message))
                if (commit) {
                    actions.add(ChannelAction.Message.SendToSelf(CMD_SIGN))
                }
                Pair(this@Normal.copy(commitments = commitments1), actions)
            }
        }
    }
}
