package fr.acinq.lightning.channel

import fr.acinq.lightning.blockchain.WatchEventSpent
import fr.acinq.lightning.transactions.Transactions
import fr.acinq.lightning.utils.Either
import fr.acinq.lightning.wire.*

data class ShuttingDown(
    override val commitments: Commitments,
    val localShutdown: Shutdown,
    val remoteShutdown: Shutdown,
    val closingFeerates: ClosingFeerates?
) : PersistedChannelState() {
    override fun updateCommitments(input: Commitments): ChannelStateWithCommitments = this.copy(commitments = input)

    override fun ChannelContext.processInternal(cmd: ChannelCommand): Pair<ChannelState, List<ChannelAction>> {
        return when (cmd) {
            is ChannelCommand.MessageReceived -> {
                when (cmd.message) {
                    is UpdateFulfillHtlc -> when (val result = commitments.receiveFulfill(cmd.message)) {
                        is Either.Left -> handleLocalError(cmd, result.value)
                        is Either.Right -> {
                            val (commitments1, paymentId, add) = result.value
                            val htlcResult = ChannelAction.HtlcResult.Fulfill.RemoteFulfill(cmd.message)
                            Pair(this@ShuttingDown.copy(commitments = commitments1), listOf(ChannelAction.ProcessCmdRes.AddSettledFulfill(paymentId, add, htlcResult)))
                        }
                    }
                    is UpdateFailHtlc -> when (val result = commitments.receiveFail(cmd.message)) {
                        is Either.Left -> handleLocalError(cmd, result.value)
                        is Either.Right -> Pair(this@ShuttingDown.copy(commitments = result.value.first), listOf())
                    }
                    is UpdateFailMalformedHtlc -> when (val result = commitments.receiveFailMalformed(cmd.message)) {
                        is Either.Left -> handleLocalError(cmd, result.value)
                        is Either.Right -> Pair(this@ShuttingDown.copy(commitments = result.value.first), listOf())
                    }
                    is UpdateFee -> when (val result = commitments.receiveFee(cmd.message, staticParams.nodeParams.onChainFeeConf.feerateTolerance)) {
                        is Either.Left -> handleLocalError(cmd, result.value)
                        is Either.Right -> Pair(this@ShuttingDown.copy(commitments = result.value), listOf())
                    }
                    is CommitSig -> when (val result = commitments.receiveCommit(listOf(cmd.message), keyManager, logger)) {
                        is Either.Left -> handleLocalError(cmd, result.value)
                        is Either.Right -> {
                            val (commitments1, revocation) = result.value
                            when {
                                commitments1.hasNoPendingHtlcsOrFeeUpdate() && commitments1.params.localParams.isInitiator -> {
                                    val (closingTx, closingSigned) = Helpers.Closing.makeFirstClosingTx(
                                        keyManager,
                                        commitments1.latest,
                                        localShutdown.scriptPubKey.toByteArray(),
                                        remoteShutdown.scriptPubKey.toByteArray(),
                                        closingFeerates ?: ClosingFeerates(currentOnChainFeerates.mutualCloseFeerate)
                                    )
                                    val nextState = Negotiating(
                                        commitments1,
                                        localShutdown,
                                        remoteShutdown,
                                        listOf(listOf(ClosingTxProposed(closingTx, closingSigned))),
                                        bestUnpublishedClosingTx = null,
                                        closingFeerates
                                    )
                                    val actions = listOf(
                                        ChannelAction.Storage.StoreState(nextState),
                                        ChannelAction.Message.Send(revocation),
                                        ChannelAction.Message.Send(closingSigned)
                                    )
                                    Pair(nextState, actions)
                                }
                                commitments1.hasNoPendingHtlcsOrFeeUpdate() -> {
                                    val nextState = Negotiating(commitments1, localShutdown, remoteShutdown, listOf(listOf()), null, closingFeerates)
                                    val actions = listOf(ChannelAction.Storage.StoreState(nextState), ChannelAction.Message.Send(revocation))
                                    Pair(nextState, actions)
                                }
                                else -> {
                                    val nextState = this@ShuttingDown.copy(commitments = commitments1)
                                    val actions = mutableListOf(ChannelAction.Storage.StoreState(nextState), ChannelAction.Message.Send(revocation))
                                    if (commitments1.changes.localHasChanges()) {
                                        // if we have newly acknowledged changes let's sign them
                                        actions.add(ChannelAction.Message.SendToSelf(CMD_SIGN))
                                    }
                                    Pair(nextState, actions)
                                }
                            }
                        }
                    }
                    is RevokeAndAck -> when (val result = commitments.receiveRevocation(cmd.message)) {
                        is Either.Left -> handleLocalError(cmd, result.value)
                        is Either.Right -> {
                            val (commitments1, actions) = result.value
                            val actions1 = actions.toMutableList()
                            when {
                                commitments1.hasNoPendingHtlcsOrFeeUpdate() && commitments1.params.localParams.isInitiator -> {
                                    val (closingTx, closingSigned) = Helpers.Closing.makeFirstClosingTx(
                                        keyManager,
                                        commitments1.latest,
                                        localShutdown.scriptPubKey.toByteArray(),
                                        remoteShutdown.scriptPubKey.toByteArray(),
                                        closingFeerates ?: ClosingFeerates(currentOnChainFeerates.mutualCloseFeerate)
                                    )
                                    val nextState = Negotiating(
                                        commitments1,
                                        localShutdown,
                                        remoteShutdown,
                                        listOf(listOf(ClosingTxProposed(closingTx, closingSigned))),
                                        bestUnpublishedClosingTx = null,
                                        closingFeerates
                                    )
                                    actions1.addAll(listOf(ChannelAction.Storage.StoreState(nextState), ChannelAction.Message.Send(closingSigned)))
                                    Pair(nextState, actions1)
                                }
                                commitments1.hasNoPendingHtlcsOrFeeUpdate() -> {
                                    val nextState = Negotiating(commitments1, localShutdown, remoteShutdown, listOf(listOf()), null, closingFeerates)
                                    actions1.add(ChannelAction.Storage.StoreState(nextState))
                                    Pair(nextState, actions1)
                                }
                                else -> {
                                    val nextState = this@ShuttingDown.copy(commitments = commitments1)
                                    actions1.add(ChannelAction.Storage.StoreState(nextState))
                                    if (commitments1.changes.localHasChanges() && commitments1.remoteNextCommitInfo.isLeft) {
                                        actions1.add(ChannelAction.Message.SendToSelf(CMD_SIGN))
                                    }
                                    Pair(nextState, actions1)
                                }
                            }
                        }
                    }
                    is Error -> {
                        handleRemoteError(cmd.message)
                    }
                    else -> unhandled(cmd)
                }
            }
            is ChannelCommand.ExecuteCommand -> when {
                cmd.command is CMD_ADD_HTLC -> {
                    logger.info { "rejecting htlc request in state=${this::class}" }
                    // we don't provide a channel_update: this will be a permanent channel failure
                    handleCommandError(cmd.command, ChannelUnavailable(channelId))
                }
                cmd.command is CMD_SIGN && !commitments.changes.localHasChanges() -> {
                    logger.debug { "ignoring CMD_SIGN (nothing to sign)" }
                    Pair(this@ShuttingDown, listOf())
                }
                cmd.command is CMD_SIGN && commitments.remoteNextCommitInfo.isLeft -> {
                    logger.debug { "already in the process of signing, will sign again as soon as possible" }
                    Pair(this@ShuttingDown, listOf())
                }
                cmd.command is CMD_SIGN -> when (val result = commitments.sendCommit(keyManager, logger)) {
                    is Either.Left -> handleCommandError(cmd.command, result.value)
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
                        val nextState = this@ShuttingDown.copy(commitments = commitments1)
                        val actions = buildList {
                            add(ChannelAction.Storage.StoreHtlcInfos(htlcInfos))
                            add(ChannelAction.Storage.StoreState(nextState))
                            addAll(result.value.second.map { ChannelAction.Message.Send(it) })
                        }
                        Pair(nextState, actions)
                    }
                }
                cmd.command is CMD_FULFILL_HTLC -> handleCommandResult(cmd.command, commitments.sendFulfill(cmd.command), cmd.command.commit)
                cmd.command is CMD_FAIL_HTLC -> handleCommandResult(cmd.command, commitments.sendFail(cmd.command, staticParams.nodeParams.nodePrivateKey), cmd.command.commit)
                cmd.command is CMD_FAIL_MALFORMED_HTLC -> handleCommandResult(cmd.command, commitments.sendFailMalformed(cmd.command), cmd.command.commit)
                cmd.command is CMD_UPDATE_FEE -> handleCommandResult(cmd.command, commitments.sendFee(cmd.command), cmd.command.commit)
                cmd.command is CMD_CLOSE -> handleCommandError(cmd.command, ClosingAlreadyInProgress(channelId))
                cmd.command is CMD_FORCECLOSE -> handleLocalError(cmd, ForcedLocalCommit(channelId))
                else -> unhandled(cmd)
            }
            is ChannelCommand.WatchReceived -> when (val watch = cmd.watch) {
                is WatchEventSpent -> when (watch.tx.txid) {
                    commitments.latest.remoteCommit.txid -> handleRemoteSpentCurrent(watch.tx, commitments.latest)
                    commitments.latest.nextRemoteCommit?.commit?.txid -> handleRemoteSpentNext(watch.tx, commitments.latest)
                    else -> handleRemoteSpentOther(watch.tx)
                }
                else -> unhandled(cmd)
            }
            is ChannelCommand.CheckHtlcTimeout -> checkHtlcTimeout()
            is ChannelCommand.Disconnected -> Pair(Offline(this@ShuttingDown), listOf())
            else -> unhandled(cmd)
        }
    }

    override fun ChannelContext.handleLocalError(cmd: ChannelCommand, t: Throwable): Pair<ChannelState, List<ChannelAction>> {
        logger.error(t) { "error on command ${cmd::class.simpleName} in state ${this@ShuttingDown::class.simpleName}" }
        val error = Error(channelId, t.message)
        return spendLocalCurrent().run { copy(second = second + ChannelAction.Message.Send(error)) }
    }

    private fun ChannelContext.handleCommandResult(command: Command, result: Either<ChannelException, Pair<Commitments, LightningMessage>>, commit: Boolean): Pair<ChannelState, List<ChannelAction>> {
        return when (result) {
            is Either.Left -> handleCommandError(command, result.value)
            is Either.Right -> {
                val (commitments1, message) = result.value
                val actions = mutableListOf<ChannelAction>(ChannelAction.Message.Send(message))
                if (commit) {
                    actions.add(ChannelAction.Message.SendToSelf(CMD_SIGN))
                }
                Pair(this@ShuttingDown.copy(commitments = commitments1), actions)
            }
        }
    }
}
