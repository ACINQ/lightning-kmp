package fr.acinq.lightning.channel.states

import fr.acinq.bitcoin.utils.Either
import fr.acinq.lightning.blockchain.WatchEventConfirmed
import fr.acinq.lightning.blockchain.WatchEventSpent
import fr.acinq.lightning.channel.*
import fr.acinq.lightning.transactions.Transactions
import fr.acinq.lightning.wire.*

data class ShuttingDown(
    override val commitments: Commitments,
    val localShutdown: Shutdown,
    val remoteShutdown: Shutdown,
    val closingFeerates: ClosingFeerates?
) : ChannelStateWithCommitments() {
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
                    is CommitSig -> when (val sigs = aggregateSigs(cmd.message)) {
                        is List<CommitSig> -> when (val result = commitments.receiveCommit(sigs, channelKeys(), logger)) {
                            is Either.Left -> handleLocalError(cmd, result.value)
                            is Either.Right -> {
                                val (commitments1, revocation) = result.value
                                when {
                                    commitments1.hasNoPendingHtlcsOrFeeUpdate() && commitments1.params.localParams.isInitiator -> {
                                        val (closingTx, closingSigned) = Helpers.Closing.makeFirstClosingTx(
                                            channelKeys(),
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
                                            actions.add(ChannelAction.Message.SendToSelf(ChannelCommand.Commitment.Sign))
                                        }
                                        Pair(nextState, actions)
                                    }
                                }
                            }
                        }
                        else -> Pair(this@ShuttingDown, listOf())
                    }
                    is RevokeAndAck -> when (val result = commitments.receiveRevocation(cmd.message)) {
                        is Either.Left -> handleLocalError(cmd, result.value)
                        is Either.Right -> {
                            val (commitments1, actions) = result.value
                            val actions1 = actions.toMutableList()
                            when {
                                commitments1.hasNoPendingHtlcsOrFeeUpdate() && commitments1.params.localParams.isInitiator -> {
                                    val (closingTx, closingSigned) = Helpers.Closing.makeFirstClosingTx(
                                        channelKeys(),
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
                                        actions1.add(ChannelAction.Message.SendToSelf(ChannelCommand.Commitment.Sign))
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
            is ChannelCommand.Htlc.Add -> {
                logger.info { "rejecting htlc request in state=${this::class}" }
                // we don't provide a channel_update: this will be a permanent channel failure
                handleCommandError(cmd, ChannelUnavailable(channelId))
            }
            is ChannelCommand.Commitment.Sign -> {
                if (!commitments.changes.localHasChanges()) {
                    logger.debug { "ignoring ChannelCommand.Commitment.Sign (nothing to sign)" }
                    Pair(this@ShuttingDown, listOf())
                } else if (commitments.remoteNextCommitInfo.isLeft) {
                    logger.debug { "already in the process of signing, will sign again as soon as possible" }
                    Pair(this@ShuttingDown, listOf())
                } else {
                    when (val result = commitments.sendCommit(channelKeys(), logger)) {
                        is Either.Left -> handleCommandError(cmd, result.value)
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
                }
            }
            is ChannelCommand.Htlc.Settlement.Fulfill -> handleCommandResult(cmd, commitments.sendFulfill(cmd), cmd.commit)
            is ChannelCommand.Htlc.Settlement.Fail -> handleCommandResult(cmd, commitments.sendFail(cmd, staticParams.nodeParams.nodePrivateKey), cmd.commit)
            is ChannelCommand.Htlc.Settlement.FailMalformed -> handleCommandResult(cmd, commitments.sendFailMalformed(cmd), cmd.commit)
            is ChannelCommand.Commitment.UpdateFee -> handleCommandResult(cmd, commitments.sendFee(cmd), cmd.commit)
            is ChannelCommand.Close.MutualClose -> handleCommandError(cmd, ClosingAlreadyInProgress(channelId))
            is ChannelCommand.Close.ForceClose -> handleLocalError(cmd, ForcedLocalCommit(channelId))
            is ChannelCommand.WatchReceived -> when (val watch = cmd.watch) {
                is WatchEventConfirmed -> updateFundingTxStatus(watch)
                is WatchEventSpent -> handlePotentialForceClose(watch)
            }
            is ChannelCommand.Commitment.CheckHtlcTimeout -> checkHtlcTimeout()
            is ChannelCommand.Disconnected -> {
                // reset the commit_sig batch
                sigStash = emptyList()
                Pair(Offline(this@ShuttingDown), listOf())
            }
            else -> unhandled(cmd)
        }
    }

    private fun ChannelContext.handleCommandResult(command: ChannelCommand, result: Either<ChannelException, Pair<Commitments, LightningMessage>>, commit: Boolean): Pair<ChannelState, List<ChannelAction>> {
        return when (result) {
            is Either.Left -> handleCommandError(command, result.value)
            is Either.Right -> {
                val (commitments1, message) = result.value
                val actions = mutableListOf<ChannelAction>(ChannelAction.Message.Send(message))
                if (commit) {
                    actions.add(ChannelAction.Message.SendToSelf(ChannelCommand.Commitment.Sign))
                }
                Pair(this@ShuttingDown.copy(commitments = commitments1), actions)
            }
        }
    }
}
