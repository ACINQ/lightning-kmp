package fr.acinq.lightning.channel

import fr.acinq.bitcoin.BlockHeader
import fr.acinq.lightning.blockchain.WatchEventSpent
import fr.acinq.lightning.blockchain.fee.OnChainFeerates
import fr.acinq.lightning.transactions.Transactions
import fr.acinq.lightning.utils.Either
import fr.acinq.lightning.wire.*

data class ShuttingDown(
    override val staticParams: StaticParams,
    override val currentTip: Pair<Int, BlockHeader>,
    override val currentOnChainFeerates: OnChainFeerates,
    override val commitments: Commitments,
    val localShutdown: Shutdown,
    val remoteShutdown: Shutdown,
    val closingFeerates: ClosingFeerates?
) : ChannelStateWithCommitments() {
    override fun updateCommitments(input: Commitments): ChannelStateWithCommitments = this.copy(commitments = input)

    override fun processInternal(event: ChannelEvent): Pair<ChannelState, List<ChannelAction>> {
        return when (event) {
            is ChannelEvent.MessageReceived -> {
                when (event.message) {
                    is UpdateFulfillHtlc -> when (val result = commitments.receiveFulfill(event.message)) {
                        is Either.Left -> handleLocalError(event, result.value)
                        is Either.Right -> {
                            val (commitments1, paymentId, add) = result.value
                            val htlcResult = ChannelAction.HtlcResult.Fulfill.RemoteFulfill(event.message)
                            Pair(this.copy(commitments = commitments1), listOf(ChannelAction.ProcessCmdRes.AddSettledFulfill(paymentId, add, htlcResult)))
                        }
                    }
                    is UpdateFailHtlc -> when (val result = commitments.receiveFail(event.message)) {
                        is Either.Left -> handleLocalError(event, result.value)
                        is Either.Right -> Pair(this.copy(commitments = result.value.first), listOf())
                    }
                    is UpdateFailMalformedHtlc -> when (val result = commitments.receiveFailMalformed(event.message)) {
                        is Either.Left -> handleLocalError(event, result.value)
                        is Either.Right -> Pair(this.copy(commitments = result.value.first), listOf())
                    }
                    is UpdateFee -> when (val result = commitments.receiveFee(event.message, staticParams.nodeParams.onChainFeeConf.feerateTolerance)) {
                        is Either.Left -> handleLocalError(event, result.value)
                        is Either.Right -> Pair(this.copy(commitments = result.value), listOf())
                    }
                    is CommitSig -> when (val result = commitments.receiveCommit(event.message, keyManager, logger)) {
                        is Either.Left -> handleLocalError(event, result.value)
                        is Either.Right -> {
                            val (commitments1, revocation) = result.value
                            when {
                                commitments1.hasNoPendingHtlcsOrFeeUpdate() && commitments1.localParams.isFunder -> {
                                    val (closingTx, closingSigned) = Helpers.Closing.makeFirstClosingTx(
                                        keyManager,
                                        commitments1,
                                        localShutdown.scriptPubKey.toByteArray(),
                                        remoteShutdown.scriptPubKey.toByteArray(),
                                        closingFeerates ?: ClosingFeerates(currentOnChainFeerates.mutualCloseFeerate)
                                    )
                                    val nextState = Negotiating(
                                        staticParams,
                                        currentTip,
                                        currentOnChainFeerates,
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
                                    val nextState = Negotiating(staticParams, currentTip, currentOnChainFeerates, commitments1, localShutdown, remoteShutdown, listOf(listOf()), null, closingFeerates)
                                    val actions = listOf(ChannelAction.Storage.StoreState(nextState), ChannelAction.Message.Send(revocation))
                                    Pair(nextState, actions)
                                }
                                else -> {
                                    val nextState = this.copy(commitments = commitments1)
                                    val actions = mutableListOf(ChannelAction.Storage.StoreState(nextState), ChannelAction.Message.Send(revocation))
                                    if (commitments1.localHasChanges()) {
                                        // if we have newly acknowledged changes let's sign them
                                        actions.add(ChannelAction.Message.SendToSelf(CMD_SIGN))
                                    }
                                    Pair(nextState, actions)
                                }
                            }
                        }
                    }
                    is RevokeAndAck -> when (val result = commitments.receiveRevocation(event.message)) {
                        is Either.Left -> handleLocalError(event, result.value)
                        is Either.Right -> {
                            val (commitments1, actions) = result.value
                            val actions1 = actions.toMutableList()
                            when {
                                commitments1.hasNoPendingHtlcsOrFeeUpdate() && commitments1.localParams.isFunder -> {
                                    val (closingTx, closingSigned) = Helpers.Closing.makeFirstClosingTx(
                                        keyManager,
                                        commitments1,
                                        localShutdown.scriptPubKey.toByteArray(),
                                        remoteShutdown.scriptPubKey.toByteArray(),
                                        closingFeerates ?: ClosingFeerates(currentOnChainFeerates.mutualCloseFeerate)
                                    )
                                    val nextState = Negotiating(
                                        staticParams,
                                        currentTip,
                                        currentOnChainFeerates,
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
                                    val nextState = Negotiating(staticParams, currentTip, currentOnChainFeerates, commitments1, localShutdown, remoteShutdown, listOf(listOf()), null, closingFeerates)
                                    actions1.add(ChannelAction.Storage.StoreState(nextState))
                                    Pair(nextState, actions1)
                                }
                                else -> {
                                    val nextState = this.copy(commitments = commitments1)
                                    actions1.add(ChannelAction.Storage.StoreState(nextState))
                                    if (commitments1.localHasChanges() && commitments1.remoteNextCommitInfo.isLeft && commitments1.remoteNextCommitInfo.left!!.reSignAsap) {
                                        actions1.add(ChannelAction.Message.SendToSelf(CMD_SIGN))
                                    }
                                    Pair(nextState, actions1)
                                }
                            }
                        }
                    }
                    is Error -> {
                        handleRemoteError(event.message)
                    }
                    else -> unhandled(event)
                }
            }
            is ChannelEvent.ExecuteCommand -> when {
                event.command is CMD_ADD_HTLC -> {
                    logger.info { "c:$channelId rejecting htlc request in state=${this::class}" }
                    // we don't provide a channel_update: this will be a permanent channel failure
                    handleCommandError(event.command, ChannelUnavailable(channelId))
                }
                event.command is CMD_SIGN && !commitments.localHasChanges() -> {
                    logger.debug { "c:$channelId ignoring CMD_SIGN (nothing to sign)" }
                    Pair(this, listOf())
                }
                event.command is CMD_SIGN && commitments.remoteNextCommitInfo.isLeft -> {
                    logger.debug { "c:$channelId already in the process of signing, will sign again as soon as possible" }
                    Pair(this.copy(commitments = this.commitments.copy(remoteNextCommitInfo = Either.Left(this.commitments.remoteNextCommitInfo.left!!.copy(reSignAsap = true)))), listOf())
                }
                event.command is CMD_SIGN -> when (val result = commitments.sendCommit(keyManager, logger)) {
                    is Either.Left -> handleCommandError(event.command, result.value)
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
                        val nextState = this.copy(commitments = commitments1)
                        val actions = listOf(
                            ChannelAction.Storage.StoreHtlcInfos(htlcInfos),
                            ChannelAction.Storage.StoreState(nextState),
                            ChannelAction.Message.Send(result.value.second)
                        )
                        Pair(nextState, actions)
                    }
                }
                event.command is CMD_FULFILL_HTLC -> handleCommandResult(event.command, commitments.sendFulfill(event.command), event.command.commit)
                event.command is CMD_FAIL_HTLC -> handleCommandResult(event.command, commitments.sendFail(event.command, staticParams.nodeParams.nodePrivateKey), event.command.commit)
                event.command is CMD_FAIL_MALFORMED_HTLC -> handleCommandResult(event.command, commitments.sendFailMalformed(event.command), event.command.commit)
                event.command is CMD_UPDATE_FEE -> handleCommandResult(event.command, commitments.sendFee(event.command), event.command.commit)
                event.command is CMD_CLOSE -> handleCommandError(event.command, ClosingAlreadyInProgress(channelId))
                event.command is CMD_FORCECLOSE -> handleLocalError(event, ForcedLocalCommit(channelId))
                else -> unhandled(event)
            }
            is ChannelEvent.WatchReceived -> when (val watch = event.watch) {
                is WatchEventSpent -> when (watch.tx.txid) {
                    commitments.remoteCommit.txid -> handleRemoteSpentCurrent(watch.tx)
                    commitments.remoteNextCommitInfo.left?.nextRemoteCommit?.txid -> handleRemoteSpentNext(watch.tx)
                    else -> handleRemoteSpentOther(watch.tx)
                }
                else -> unhandled(event)
            }
            is ChannelEvent.CheckHtlcTimeout -> checkHtlcTimeout()
            is ChannelEvent.NewBlock -> Pair(this.copy(currentTip = Pair(event.height, event.Header)), listOf())
            is ChannelEvent.SetOnChainFeerates -> Pair(this.copy(currentOnChainFeerates = event.feerates), listOf())
            is ChannelEvent.Disconnected -> Pair(Offline(this), listOf())
            else -> unhandled(event)
        }
    }

    override fun handleLocalError(event: ChannelEvent, t: Throwable): Pair<ChannelState, List<ChannelAction>> {
        logger.error(t) { "error on event ${event::class} in state ${this::class}" }
        val error = Error(channelId, t.message)
        return spendLocalCurrent().run { copy(second = second + ChannelAction.Message.Send(error)) }
    }

    private fun handleCommandResult(command: Command, result: Either<ChannelException, Pair<Commitments, LightningMessage>>, commit: Boolean): Pair<ChannelState, List<ChannelAction>> {
        return when (result) {
            is Either.Left -> handleCommandError(command, result.value)
            is Either.Right -> {
                val (commitments1, message) = result.value
                val actions = mutableListOf<ChannelAction>(ChannelAction.Message.Send(message))
                if (commit) {
                    actions.add(ChannelAction.Message.SendToSelf(CMD_SIGN))
                }
                Pair(this.copy(commitments = commitments1), actions)
            }
        }
    }
}
