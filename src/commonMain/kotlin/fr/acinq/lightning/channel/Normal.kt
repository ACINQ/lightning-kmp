package fr.acinq.lightning.channel

import fr.acinq.lightning.Feature
import fr.acinq.lightning.Features
import fr.acinq.lightning.ShortChannelId
import fr.acinq.lightning.blockchain.WatchEventSpent
import fr.acinq.lightning.router.Announcements
import fr.acinq.lightning.transactions.Transactions
import fr.acinq.lightning.utils.Either
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
    val closingFeerates: ClosingFeerates?
) : ChannelStateWithCommitments() {
    override fun updateCommitments(input: Commitments): ChannelStateWithCommitments = this.copy(commitments = input)

    override fun ChannelContext.processInternal(cmd: ChannelCommand): Pair<ChannelState, List<ChannelAction>> {
        return when (cmd) {
            is ChannelCommand.ExecuteCommand -> {
                when (cmd.command) {
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
                        !commitments.localHasChanges() -> {
                            logger.warning { "c:$channelId no changes to sign" }
                            Pair(this@Normal, listOf())
                        }
                        commitments.remoteNextCommitInfo is Either.Left -> {
                            logger.debug { "c:$channelId already in the process of signing, will sign again as soon as possible" }
                            val commitments1 = commitments.copy(remoteNextCommitInfo = Either.Left(commitments.remoteNextCommitInfo.left!!.copy(reSignAsap = true)))
                            Pair(this@Normal.copy(commitments = commitments1), listOf())
                        }
                        else -> when (val result = commitments.sendCommit(keyManager, logger)) {
                            is Either.Left -> handleCommandError(cmd.command, result.value, channelUpdate)
                            is Either.Right -> {
                                val commitments1 = result.value.first
                                val nextRemoteCommit = commitments1.remoteNextCommitInfo.left!!.nextRemoteCommit
                                val nextCommitNumber = nextRemoteCommit.index
                                // we persist htlc data in order to be able to claim htlc outputs in case a revoked tx is published by our
                                // counterparty, so only htlcs above remote's dust_limit matter
                                val trimmedHtlcs = Transactions.trimOfferedHtlcs(commitments.remoteParams.dustLimit, nextRemoteCommit.spec) + Transactions.trimReceivedHtlcs(commitments.remoteParams.dustLimit, nextRemoteCommit.spec)
                                val htlcInfos = trimmedHtlcs.map { it.add }.map {
                                    logger.info { "c:$channelId adding paymentHash=${it.paymentHash} cltvExpiry=${it.cltvExpiry} to htlcs db for commitNumber=$nextCommitNumber" }
                                    ChannelAction.Storage.HtlcInfo(channelId, nextCommitNumber, it.paymentHash, it.cltvExpiry)
                                }
                                val nextState = this@Normal.copy(commitments = commitments1)
                                val actions = listOf(
                                    ChannelAction.Storage.StoreHtlcInfos(htlcInfos),
                                    ChannelAction.Storage.StoreState(nextState),
                                    ChannelAction.Message.Send(result.value.second)
                                )
                                Pair(nextState, actions)
                            }
                        }
                    }
                    is CMD_CLOSE -> {
                        val allowAnySegwit = Features.canUseFeature(commitments.localParams.features, commitments.remoteParams.features, Feature.ShutdownAnySegwit)
                        val localScriptPubkey = cmd.command.scriptPubKey ?: commitments.localParams.defaultFinalScriptPubKey
                        when {
                            localShutdown != null -> handleCommandError(cmd.command, ClosingAlreadyInProgress(channelId), channelUpdate)
                            commitments.localHasUnsignedOutgoingHtlcs() -> handleCommandError(cmd.command, CannotCloseWithUnsignedOutgoingHtlcs(channelId), channelUpdate)
                            commitments.localHasUnsignedOutgoingUpdateFee() -> handleCommandError(cmd.command, CannotCloseWithUnsignedOutgoingUpdateFee(channelId), channelUpdate)
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
                }
            }
            is ChannelCommand.MessageReceived -> {
                when (cmd.message) {
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
                    is CommitSig -> when (val result = commitments.receiveCommit(cmd.message, keyManager, logger)) {
                        is Either.Left -> handleLocalError(cmd, result.value)
                        is Either.Right -> {
                            val nextState = this@Normal.copy(commitments = result.value.first)
                            val actions = mutableListOf<ChannelAction>()
                            actions.add(ChannelAction.Message.Send(result.value.second))
                            actions.add(ChannelAction.Storage.StoreState(nextState))
                            if (result.value.first.localHasChanges()) {
                                actions.add(ChannelAction.Message.SendToSelf(CMD_SIGN))
                            }
                            Pair(nextState, actions)
                        }
                    }
                    is RevokeAndAck -> when (val result = commitments.receiveRevocation(cmd.message)) {
                        is Either.Left -> handleLocalError(cmd, result.value)
                        is Either.Right -> {
                            val commitments1 = result.value.first
                            val actions = mutableListOf<ChannelAction>()
                            actions.addAll(result.value.second)
                            if (result.value.first.localHasChanges() && commitments.remoteNextCommitInfo.left?.reSignAsap == true) {
                                actions.add(ChannelAction.Message.SendToSelf(CMD_SIGN))
                            }
                            val nextState = if (remoteShutdown != null && !commitments1.localHasUnsignedOutgoingHtlcs()) {
                                // we were waiting for our pending htlcs to be signed before replying with our local shutdown
                                val localShutdown = Shutdown(channelId, commitments.localParams.defaultFinalScriptPubKey)
                                actions.add(ChannelAction.Message.Send(localShutdown))

                                if (commitments1.remoteCommit.spec.htlcs.isNotEmpty()) {
                                    // we just signed htlcs that need to be resolved now
                                    ShuttingDown(commitments1, localShutdown, remoteShutdown, closingFeerates)
                                } else {
                                    logger.warning { "c:$channelId we have no htlcs but have not replied with our Shutdown yet, this should never happen" }
                                    val closingTxProposed = if (isInitiator) {
                                        val (closingTx, closingSigned) = Helpers.Closing.makeFirstClosingTx(
                                            keyManager,
                                            commitments1,
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
                        val allowAnySegwit = Features.canUseFeature(commitments.localParams.features, commitments.remoteParams.features, Feature.ShutdownAnySegwit)
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
                            commitments.remoteHasUnsignedOutgoingHtlcs() -> handleLocalError(cmd, CannotCloseWithUnsignedOutgoingHtlcs(channelId))
                            commitments.remoteHasUnsignedOutgoingUpdateFee() -> handleLocalError(cmd, CannotCloseWithUnsignedOutgoingUpdateFee(channelId))
                            commitments.localHasUnsignedOutgoingHtlcs() -> {
                                require(localShutdown == null) { "can't have pending unsigned outgoing htlcs after having sent Shutdown" }
                                // are we in the middle of a signature?
                                when (commitments.remoteNextCommitInfo) {
                                    is Either.Left -> {
                                        // yes, let's just schedule a new signature ASAP, which will include all pending unsigned changes
                                        val commitments1 = commitments.copy(remoteNextCommitInfo = Either.Left(commitments.remoteNextCommitInfo.value.copy(reSignAsap = true)))
                                        val newState = this@Normal.copy(commitments = commitments1, remoteShutdown = cmd.message)
                                        Pair(newState, listOf())
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
                                val localShutdown = this@Normal.localShutdown ?: Shutdown(channelId, commitments.localParams.defaultFinalScriptPubKey)
                                if (this@Normal.localShutdown == null) actions.add(ChannelAction.Message.Send(localShutdown))
                                val commitments1 = commitments.copy(remoteChannelData = cmd.message.channelData)
                                when {
                                    commitments1.hasNoPendingHtlcsOrFeeUpdate() && commitments1.localParams.isInitiator -> {
                                        val (closingTx, closingSigned) = Helpers.Closing.makeFirstClosingTx(
                                            keyManager,
                                            commitments1,
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
                    is Error -> handleRemoteError(cmd.message)
                    else -> unhandled(cmd)
                }
            }
            is ChannelCommand.CheckHtlcTimeout -> checkHtlcTimeout()
            is ChannelCommand.WatchReceived -> when (val watch = cmd.watch) {
                is WatchEventSpent -> when (watch.tx.txid) {
                    commitments.remoteCommit.txid -> handleRemoteSpentCurrent(watch.tx)
                    commitments.remoteNextCommitInfo.left?.nextRemoteCommit?.txid -> handleRemoteSpentNext(watch.tx)
                    else -> handleRemoteSpentOther(watch.tx)
                }
                else -> unhandled(cmd)
            }
            is ChannelCommand.Disconnected -> {
                // if we have pending unsigned outgoing htlcs, then we cancel them and advertise the fact that the channel is now disabled.
                val failedHtlcs = mutableListOf<ChannelAction>()
                val proposedHtlcs = commitments.localChanges.proposed.filterIsInstance<UpdateAddHtlc>()
                if (proposedHtlcs.isNotEmpty()) {
                    logger.info { "c:$channelId updating channel_update announcement (reason=disabled)" }
                    val channelUpdate = Announcements.disableChannel(channelUpdate, staticParams.nodeParams.nodePrivateKey, staticParams.remoteNodeId)
                    proposedHtlcs.forEach { htlc ->
                        commitments.payments[htlc.id]?.let { paymentId ->
                            failedHtlcs.add(ChannelAction.ProcessCmdRes.AddSettledFail(paymentId, htlc, ChannelAction.HtlcResult.Fail.Disconnected(channelUpdate)))
                        } ?: logger.warning { "c:$channelId cannot find payment for $htlc" }
                    }
                }
                Pair(Offline(this@Normal), failedHtlcs)
            }
            else -> unhandled(cmd)
        }
    }

    override fun ChannelContext.handleLocalError(cmd: ChannelCommand, t: Throwable): Pair<ChannelState, List<ChannelAction>> {
        logger.error(t) { "c:$channelId error on event ${cmd::class} in state ${this::class}" }
        val error = Error(channelId, t.message)
        return when {
            nothingAtStake() -> Pair(Aborted, listOf(ChannelAction.Message.Send(error)))
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
