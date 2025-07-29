package fr.acinq.lightning.channel.states

import fr.acinq.bitcoin.Transaction
import fr.acinq.bitcoin.updated
import fr.acinq.bitcoin.utils.Either
import fr.acinq.lightning.blockchain.WatchConfirmed
import fr.acinq.lightning.blockchain.WatchConfirmedTriggered
import fr.acinq.lightning.blockchain.WatchSpent
import fr.acinq.lightning.blockchain.WatchSpentTriggered
import fr.acinq.lightning.channel.*
import fr.acinq.lightning.channel.Helpers.Closing
import fr.acinq.lightning.transactions.Transactions.ClosingTx
import fr.acinq.lightning.transactions.incomings
import fr.acinq.lightning.transactions.outgoings
import fr.acinq.lightning.utils.getValue
import fr.acinq.lightning.wire.ChannelReestablish
import fr.acinq.lightning.wire.Error

sealed class ClosingType
data class MutualClose(val tx: ClosingTx) : ClosingType()
data class LocalClose(val localCommit: LocalCommit, val localCommitPublished: LocalCommitPublished) : ClosingType()

sealed class RemoteClose : ClosingType() {
    abstract val remoteCommit: RemoteCommit
    abstract val remoteCommitPublished: RemoteCommitPublished
}

data class CurrentRemoteClose(override val remoteCommit: RemoteCommit, override val remoteCommitPublished: RemoteCommitPublished) : RemoteClose()
data class NextRemoteClose(override val remoteCommit: RemoteCommit, override val remoteCommitPublished: RemoteCommitPublished) : RemoteClose()

data class RecoveryClose(val remoteCommitPublished: RemoteCommitPublished) : ClosingType()
data class RevokedClose(val revokedCommitPublished: RevokedCommitPublished) : ClosingType()

data class Closing(
    override val commitments: Commitments,
    val waitingSinceBlock: Long, // how many blocks since we initiated the closing
    val mutualCloseProposed: List<ClosingTx> = emptyList(), // all exchanged closing sigs are flattened, we use this only to keep track of what publishable tx they have
    val mutualClosePublished: List<ClosingTx> = emptyList(),
    val localCommitPublished: LocalCommitPublished? = null,
    val remoteCommitPublished: RemoteCommitPublished? = null,
    val nextRemoteCommitPublished: RemoteCommitPublished? = null,
    val futureRemoteCommitPublished: RemoteCommitPublished? = null,
    val revokedCommitPublished: List<RevokedCommitPublished> = emptyList()
) : ChannelStateWithCommitments() {

    private val spendingTxs: List<Transaction> by lazy {
        mutualClosePublished.map { it.tx } + revokedCommitPublished.map { it.commitTx } + listOfNotNull(
            localCommitPublished?.commitTx,
            remoteCommitPublished?.commitTx,
            nextRemoteCommitPublished?.commitTx,
            futureRemoteCommitPublished?.commitTx
        )
    }

    override fun updateCommitments(input: Commitments): ChannelStateWithCommitments = this.copy(commitments = input)

    override suspend fun ChannelContext.processInternal(cmd: ChannelCommand): Pair<ChannelState, List<ChannelAction>> {
        return when (cmd) {
            is ChannelCommand.WatchReceived -> {
                when (val watch = cmd.watch) {
                    is WatchConfirmedTriggered -> when (watch.event) {
                        WatchConfirmed.ChannelFundingDepthOk -> when (val res = acceptFundingTxConfirmed(watch)) {
                            is Either.Right -> {
                                val (commitments1, commitment, actions) = res.value
                                if (commitments.latest.fundingTxIndex == commitment.fundingTxIndex && commitments.latest.fundingTxId != commitment.fundingTxId) {
                                    // This is a corner case where:
                                    //  - the funding tx was RBF-ed
                                    //  - *and* we went to CLOSING before any funding tx got confirmed (probably due to a local or remote error)
                                    //  - *and* an older version of the funding tx confirmed and reached min depth (it won't be re-orged out)
                                    //
                                    // This means that:
                                    //  - the whole current commitment tree has been double-spent and can safely be forgotten
                                    //  - from now on, we only need to keep track of the commitment associated to the funding tx that got confirmed
                                    //
                                    // Force-closing is our only option here, if we are in this state the channel was closing and it is too late
                                    // to negotiate a mutual close.
                                    // The best funding tx candidate has been confirmed, we can forget alternative commitments.
                                    logger.info { "channel was confirmed at blockHeight=${watch.blockHeight} txIndex=${watch.txIndex} with a previous funding txid=${watch.tx.txid}" }
                                    val commitments2 = commitments1.copy(
                                        active = listOf(commitment),
                                        inactive = emptyList()
                                    )
                                    val (nextState, actions1) = this@Closing.copy(commitments = commitments2).run { spendLocalCurrent() }
                                    Pair(nextState, actions + actions1)
                                } else {
                                    // We're still on the same splice history, nothing to do
                                    val nextState = this@Closing.copy(commitments = commitments1)
                                    Pair(nextState, actions + listOf(ChannelAction.Storage.StoreState(nextState)))
                                }
                            }
                            is Either.Left -> Pair(this@Closing, listOf())
                        }
                        WatchConfirmed.AlternativeCommitTxConfirmed -> when (val commitment = commitments.resolveCommitment(watch.tx)) {
                            is Commitment -> {
                                logger.warning { "a commit tx for fundingTxIndex=${commitment.fundingTxIndex} fundingTxId=${commitment.fundingTxId} has been confirmed" }
                                val commitments1 = commitments.copy(
                                    active = listOf(commitment),
                                    inactive = emptyList()
                                )
                                val newState = this@Closing.copy(commitments = commitments1)
                                // This commitment may be revoked: we need to verify that its index matches our latest known index before overwriting our previous commitments.
                                when {
                                    watch.tx.txid == commitments1.latest.localCommit.txId -> {
                                        // Our local commit has been published from the outside, it's unexpected but let's deal with it anyway.
                                        newState.run { spendLocalCurrent() }
                                    }
                                    watch.tx.txid == commitments1.latest.remoteCommit.txid && commitments1.remoteCommitIndex == commitments.remoteCommitIndex -> {
                                        // Our counterparty may attempt to spend its last commit tx at any time.
                                        newState.run { handleRemoteSpentCurrent(watch.tx, commitments1.latest) }
                                    }
                                    watch.tx.txid == commitments1.latest.nextRemoteCommit?.txid && commitments1.remoteCommitIndex == commitments.remoteCommitIndex && commitments.remoteNextCommitInfo.isLeft -> {
                                        // Our counterparty may attempt to spend its next commit tx at any time.
                                        newState.run { handleRemoteSpentNext(watch.tx, commitments1.latest) }
                                    }
                                    else -> {
                                        // Our counterparty is trying to broadcast a revoked commit tx (cheating attempt).
                                        // We need to fail pending outgoing HTLCs, otherwise we will never properly settle them.
                                        // We must do it here because since we're overwriting the commitments data, we will lose all information
                                        // about HTLCs that are in the current commitments but were not in the revoked one.
                                        // We fail *all* outgoing HTLCs:
                                        //  - those that are not in the revoked commitment will never settle on-chain
                                        //  - those that are in the revoked commitment will be claimed on-chain, so it's as if they were failed
                                        // Note that if we already received the preimage for some of these HTLCs, we already relayed it to the
                                        // outgoing payment handler so the fail command will be a no-op.
                                        val outgoingHtlcs = commitments.latest.localCommit.spec.htlcs.outgoings().toSet() +
                                                commitments.latest.remoteCommit.spec.htlcs.incomings().toSet() +
                                                commitments.latest.nextRemoteCommit?.spec?.htlcs.orEmpty().incomings().toSet()
                                        val htlcSettledActions = outgoingHtlcs.mapNotNull { add ->
                                            commitments.payments[add.id]?.let { paymentId ->
                                                logger.info { "failing htlc #${add.id} paymentHash=${add.paymentHash} paymentId=$paymentId: overridden by revoked remote commit" }
                                                ChannelAction.ProcessCmdRes.AddSettledFail(paymentId, add, ChannelAction.HtlcResult.Fail.OnChainFail(HtlcOverriddenByRemoteCommit(channelId, add)))
                                            }
                                        }
                                        val (nextState, closingActions) = newState.run { handleRemoteSpentOther(watch.tx) }
                                        Pair(nextState, closingActions + htlcSettledActions)
                                    }
                                }
                            }
                            else -> {
                                logger.warning { "unrecognized alternative commit tx=${watch.tx.txid}" }
                                Pair(this@Closing, listOf())
                            }
                        }
                        WatchConfirmed.ClosingTxConfirmed -> {
                            logger.info { "txid=${watch.tx.txid} has reached mindepth, updating closing state" }
                            val channelKeys = channelKeys()
                            val onChainActions = mutableListOf<ChannelAction>()
                            // First we check if this tx belongs to one of the current local/remote commits and update it status.
                            val closing1 = this@Closing.copy(
                                localCommitPublished = localCommitPublished?.let { lcp ->
                                    // If the tx is one of our HTLC txs, we now publish a 3rd-stage transaction that claims its output.
                                    val (lcp1, htlcDelayedTxs) = Closing.LocalClose.run { claimHtlcDelayedOutput(lcp, channelKeys, commitments.latest, watch.tx, currentOnChainFeerates()) }
                                    onChainActions.addAll(lcp1.run { doPublish(channelId, htlcDelayedTxs) })
                                    lcp1.updateIrrevocablySpent(watch.tx)
                                },
                                remoteCommitPublished = remoteCommitPublished?.updateIrrevocablySpent(watch.tx),
                                nextRemoteCommitPublished = nextRemoteCommitPublished?.updateIrrevocablySpent(watch.tx),
                                futureRemoteCommitPublished = futureRemoteCommitPublished?.updateIrrevocablySpent(watch.tx),
                                revokedCommitPublished = revokedCommitPublished.map { rvk ->
                                    // If the tx is one of our peer's HTLC txs, they were able to claim the output before us.
                                    // In that case, we immediately publish a penalty transaction spending their HTLC tx to steal their funds.
                                    // TODO: once we allow changing the commitment format or to_self_delay during a splice, those values may be incorrect.
                                    val toSelfDelay = commitments.latest.remoteCommitParams.toSelfDelay
                                    val commitmentFormat = commitments.latest.commitmentFormat
                                    val dustLimit = commitments.latest.localCommitParams.dustLimit
                                    val (rvk1, penaltyTxs) = Closing.RevokedClose.run { claimHtlcTxOutputs(commitments.channelParams, channelKeys, rvk, watch.tx, dustLimit, toSelfDelay, commitmentFormat, currentOnChainFeerates()) }
                                    onChainActions.addAll(rvk1.run { doPublish(channelId, penaltyTxs) })
                                    rvk1.updateIrrevocablySpent(watch.tx)
                                }
                            )
                            // we may need to fail some htlcs in case a commitment tx was published and they have reached the timeout threshold
                            val htlcSettledActions = mutableListOf<ChannelAction>()
                            val timedOutHtlcs = when (val closingType = closing1.closingTypeAlreadyKnown()) {
                                is LocalClose -> Closing.run { trimmedOrTimedOutHtlcs(channelKeys, commitments.latest, closingType.localCommit, watch.tx) }
                                is RemoteClose -> Closing.run { trimmedOrTimedOutHtlcs(channelKeys, commitments.latest, closingType.remoteCommit, watch.tx) }
                                is RevokedClose -> setOf() // revoked commitments are handled using [overriddenOutgoingHtlcs] below
                                is RecoveryClose -> setOf() // we lose htlc outputs in option_data_loss_protect scenarios (future remote commit)
                                is MutualClose -> setOf()
                                null -> setOf()
                            }
                            timedOutHtlcs.forEach { add ->
                                when (val paymentId = commitments.payments[add.id]) {
                                    null -> {
                                        // same as for fulfilling the htlc (no big deal)
                                        logger.info { "cannot fail timed-out htlc #${add.id} paymentHash=${add.paymentHash} (payment not found)" }
                                    }
                                    else -> {
                                        logger.info { "failing htlc #${add.id} paymentHash=${add.paymentHash} paymentId=$paymentId: htlc timed out" }
                                        htlcSettledActions += ChannelAction.ProcessCmdRes.AddSettledFail(paymentId, add, ChannelAction.HtlcResult.Fail.OnChainFail(HtlcsTimedOutDownstream(channelId, setOf(add))))
                                    }
                                }
                            }
                            // we also need to fail outgoing htlcs that we know will never reach the blockchain
                            Closing.overriddenOutgoingHtlcs(commitments.latest.localCommit, commitments.latest.remoteCommit, commitments.latest.nextRemoteCommit, closing1.revokedCommitPublished, watch.tx).forEach { add ->
                                when (val paymentId = commitments.payments[add.id]) {
                                    null -> {
                                        // same as for fulfilling the htlc (no big deal)
                                        logger.info { "cannot fail overridden htlc #${add.id} paymentHash=${add.paymentHash} (payment not found)" }
                                    }
                                    else -> {
                                        logger.info { "failing htlc #${add.id} paymentHash=${add.paymentHash} paymentId=$paymentId: overridden by confirmed commit" }
                                        val failure = when {
                                            watch.tx.txid == commitments.latest.localCommit.txId -> HtlcOverriddenByLocalCommit(channelId, add)
                                            else -> HtlcOverriddenByRemoteCommit(channelId, add)
                                        }
                                        htlcSettledActions += ChannelAction.ProcessCmdRes.AddSettledFail(paymentId, add, ChannelAction.HtlcResult.Fail.OnChainFail(failure))
                                    }
                                }
                            }
                            // for our outgoing payments, let's log something if we know that they will settle on chain
                            Closing.onChainOutgoingHtlcs(commitments.latest.localCommit, commitments.latest.remoteCommit, commitments.latest.nextRemoteCommit, watch.tx).forEach { add ->
                                commitments.payments[add.id]?.let { paymentId -> logger.info { "paymentId=$paymentId will settle on-chain (htlc #${add.id} sending ${add.amountMsat})" } }
                            }
                            val (nextState, closedActions) = when (val closingType = closing1.isClosed(watch.tx)) {
                                null -> Pair(closing1, listOf())
                                else -> {
                                    logger.info { "channel is now closed" }
                                    Pair(Closed(closing1), listOf(setClosingStatus(closingType)))
                                }
                            }
                            val actions = buildList {
                                add(ChannelAction.Storage.StoreState(nextState))
                                addAll(onChainActions)
                                addAll(htlcSettledActions)
                                addAll(closedActions)
                            }
                            Pair(nextState, actions)
                        }
                        is WatchConfirmed.ParentTxConfirmed -> Pair(this@Closing, listOf())
                    }
                    is WatchSpentTriggered -> when (watch.event) {
                        is WatchSpent.ChannelSpent -> when {
                            commitments.all.any { it.fundingTxId == watch.spendingTx.txid } -> {
                                // if the spending tx is itself a funding tx, this is a splice and there is nothing to do
                                Pair(this@Closing, listOf())
                            }
                            mutualClosePublished.any { it.tx.txid == watch.spendingTx.txid } -> {
                                // we already know about this tx, probably because we have published it ourselves after successful negotiation
                                Pair(this@Closing, listOf())
                            }
                            mutualCloseProposed.any { it.tx.txid == watch.spendingTx.txid } -> {
                                // at any time they can publish a closing tx with any sig we sent them
                                val closingTx = mutualCloseProposed.first { it.tx.txid == watch.spendingTx.txid }.copy(tx = watch.spendingTx)
                                val nextState = this@Closing.copy(mutualClosePublished = mutualClosePublished + listOf(closingTx))
                                val actions = listOf(ChannelAction.Storage.StoreState(nextState), ChannelAction.Blockchain.PublishTx(closingTx))
                                Pair(nextState, actions)
                            }
                            setOfNotNull(localCommitPublished?.commitTx, remoteCommitPublished?.commitTx, nextRemoteCommitPublished?.commitTx, futureRemoteCommitPublished?.commitTx).contains(watch.spendingTx) -> {
                                // this is because WatchSpent watches never expire and we are notified multiple times
                                Pair(this@Closing, listOf())
                            }
                            watch.spendingTx.txid == commitments.latest.remoteCommit.txid -> {
                                // counterparty may attempt to spend its last commit tx at any time
                                handleRemoteSpentCurrent(watch.spendingTx, commitments.latest)
                            }
                            watch.spendingTx.txid == commitments.latest.nextRemoteCommit?.txid -> {
                                // counterparty may attempt to spend its next commit tx at any time
                                handleRemoteSpentNext(watch.spendingTx, commitments.latest)
                            }
                            watch.spendingTx.txIn.map { it.outPoint }.contains(commitments.latest.fundingInput) -> {
                                // counterparty may attempt to spend a revoked commit tx at any time
                                handleRemoteSpentOther(watch.spendingTx)
                            }
                            else -> when (val commitment = commitments.resolveCommitment(watch.spendingTx)) {
                                is Commitment -> {
                                    logger.warning { "a commit tx for an older commitment has been published fundingTxId=${commitment.fundingTxId} fundingTxIndex=${commitment.fundingTxIndex}" }
                                    Pair(this@Closing, listOf(ChannelAction.Blockchain.SendWatch(WatchConfirmed(channelId, watch.spendingTx, staticParams.nodeParams.minDepthBlocks, WatchConfirmed.AlternativeCommitTxConfirmed))))
                                }
                                else -> {
                                    logger.warning { "unrecognized tx=${watch.spendingTx.txid}" }
                                    Pair(this@Closing, listOf())
                                }
                            }
                        }
                        is WatchSpent.ClosingOutputSpent -> {
                            // One of the outputs of the local/remote/revoked commit transaction or of an HTLC transaction was spent.
                            // We put a watch to be notified when the transaction confirms: it may double-spend one of our transactions.
                            logger.info { "processing spent closing output with txid=${watch.spendingTx.txid} tx=${watch.spendingTx}" }
                            val htlcSettledActions = mutableListOf<ChannelAction>()
                            Closing.run { extractPreimages(commitments.latest, watch.spendingTx) }.forEach { (htlc, preimage) ->
                                when (val paymentId = commitments.payments[htlc.id]) {
                                    null -> {
                                        // if we don't have a reference to the payment, it means that we already have forwarded the fulfill so that's not a big deal.
                                        // this can happen if they send a signature containing the fulfill, then fail the channel before we have time to sign it
                                        logger.info { "cannot fulfill htlc #${htlc.id} paymentHash=${htlc.paymentHash} (payment not found)" }
                                    }
                                    else -> {
                                        logger.info { "fulfilling htlc #${htlc.id} paymentHash=${htlc.paymentHash} paymentId=$paymentId" }
                                        htlcSettledActions += ChannelAction.ProcessCmdRes.AddSettledFulfill(paymentId, htlc, ChannelAction.HtlcResult.Fulfill.OnChainFulfill(preimage))
                                    }
                                }
                            }
                            val actions = buildList {
                                add(ChannelAction.Blockchain.SendWatch(WatchConfirmed(channelId, watch.spendingTx, staticParams.nodeParams.minDepthBlocks, WatchConfirmed.ClosingTxConfirmed)))
                                addAll(htlcSettledActions)
                            }
                            Pair(this@Closing, actions)
                        }
                    }
                }
            }
            is ChannelCommand.Closing.GetHtlcInfosResponse -> {
                val index = revokedCommitPublished.indexOfFirst { it.commitTx.txid == cmd.revokedCommitTxId }
                if (index >= 0) {
                    // TODO: once we allow changing the commitment format during a splice, the value below may be incorrect.
                    val commitmentFormat = commitments.latest.commitmentFormat
                    val dustLimit = commitments.latest.localCommitParams.dustLimit
                    val htlcPenaltyTxs = Closing.RevokedClose.run {
                        claimHtlcOutputs(commitments.channelParams, channelKeys(), revokedCommitPublished[index], dustLimit, commitmentFormat, currentOnChainFeerates(), cmd.htlcInfos)
                    }
                    val revokedCommitPublished1 = revokedCommitPublished[index].copy(htlcOutputs = htlcPenaltyTxs.map { it.input.outPoint }.toSet())
                    val nextState = copy(revokedCommitPublished = revokedCommitPublished.updated(index, revokedCommitPublished1))
                    val actions = buildList {
                        add(ChannelAction.Storage.StoreState(nextState))
                        addAll(revokedCommitPublished1.run { doPublish(staticParams.nodeParams, channelId, RevokedCommitSecondStageTransactions(null, null, htlcPenaltyTxs)) })
                    }
                    Pair(nextState, actions)
                } else {
                    logger.warning { "cannot find revoked commit with txid=${cmd.revokedCommitTxId}" }
                    Pair(this@Closing, listOf())
                }
            }
            is ChannelCommand.MessageReceived -> when (cmd.message) {
                is ChannelReestablish -> {
                    // they haven't detected that we were closing and are trying to reestablish a connection
                    // we give them one of the published txs as a hint
                    // note spendingTx != Nil (that's a requirement of DATA_CLOSING)
                    val exc = FundingTxSpent(channelId, spendingTxs.first().txid)
                    val error = Error(channelId, exc.message)
                    Pair(this@Closing, listOf(ChannelAction.Message.Send(error)))
                }
                is Error -> {
                    logger.error { "peer sent error: ascii=${cmd.message.toAscii()} bin=${cmd.message.data.toHex()}" }
                    // nothing to do, there is already a spending tx published
                    Pair(this@Closing, listOf())
                }
                else -> unhandled(cmd)
            }
            is ChannelCommand.Close -> handleCommandError(cmd, ClosingAlreadyInProgress(channelId))
            is ChannelCommand.Htlc.Add -> {
                logger.info { "rejecting htlc request in state=${this::class}" }
                // we don't provide a channel_update: this will be a permanent channel failure
                handleCommandError(cmd, ChannelUnavailable(channelId))
            }
            is ChannelCommand.Htlc.Settlement.Fulfill -> when (val result = commitments.sendFulfill(cmd)) {
                is Either.Right -> {
                    logger.info { "htlc #${cmd.id} with payment_hash=${cmd.r.sha256()} is fulfilled, recalculating htlc-success transactions" }
                    // We may be able to publish HTLC-success transactions for which we didn't have the preimage.
                    // We are already watching the corresponding outputs: no need to set additional watches.
                    val commitments1 = result.value.first
                    val channelKeys = channelKeys()
                    val publishActions = mutableListOf<ChannelAction>()
                    localCommitPublished?.let {
                        val commitKeys = commitments1.latest.localKeys(channelKeys)
                        val htlcTxs = Closing.LocalClose.run { claimHtlcsWithPreimage(channelKeys, commitKeys, commitments1.latest, cmd.r) }
                        publishActions.addAll(htlcTxs.map { tx -> ChannelAction.Blockchain.PublishTx(tx) })
                    }
                    remoteCommitPublished?.let { rcp ->
                        val remoteCommit = commitments1.latest.remoteCommit
                        val htlcTxs = Closing.RemoteClose.run { claimHtlcsWithPreimage(channelKeys, rcp, commitments1.latest, remoteCommit, cmd.r, currentOnChainFeerates()) }
                        publishActions.addAll(htlcTxs.map { tx -> ChannelAction.Blockchain.PublishTx(tx) })
                    }
                    nextRemoteCommitPublished?.let { rcp ->
                        val remoteCommit = commitments1.latest.nextRemoteCommit ?: error("next remote commit must be defined")
                        val htlcTxs = Closing.RemoteClose.run { claimHtlcsWithPreimage(channelKeys, rcp, commitments1.latest, remoteCommit, cmd.r, currentOnChainFeerates()) }
                        publishActions.addAll(htlcTxs.map { tx -> ChannelAction.Blockchain.PublishTx(tx) })
                    }
                    val nextState = copy(commitments = commitments1)
                    val actions = buildList {
                        add(ChannelAction.Storage.StoreState(nextState))
                        addAll(publishActions)
                    }
                    Pair(nextState, actions)
                }
                is Either.Left -> handleCommandError(cmd, result.value)
            }
            is ChannelCommand.Htlc.Settlement.Fail -> {
                logger.info { "htlc #${cmd.id} was failed by us, recalculating watched htlc outputs" }
                val nextState = copy(
                    localCommitPublished = localCommitPublished?.let { Closing.LocalClose.ignoreFailedIncomingHtlc(cmd.id, it, commitments.latest) },
                    remoteCommitPublished = remoteCommitPublished?.let { Closing.RemoteClose.ignoreFailedIncomingHtlc(cmd.id, it, commitments.latest, commitments.latest.remoteCommit) },
                    nextRemoteCommitPublished = nextRemoteCommitPublished?.let { Closing.RemoteClose.ignoreFailedIncomingHtlc(cmd.id, it, commitments.latest, commitments.latest.nextRemoteCommit!!) },
                )
                Pair(nextState, listOf(ChannelAction.Storage.StoreState(nextState)))
            }
            is ChannelCommand.Htlc.Settlement.FailMalformed -> {
                logger.info { "htlc #${cmd.id} was failed by us, recalculating watched htlc outputs" }
                val nextState = copy(
                    localCommitPublished = localCommitPublished?.let { Closing.LocalClose.ignoreFailedIncomingHtlc(cmd.id, it, commitments.latest) },
                    remoteCommitPublished = remoteCommitPublished?.let { Closing.RemoteClose.ignoreFailedIncomingHtlc(cmd.id, it, commitments.latest, commitments.latest.remoteCommit) },
                    nextRemoteCommitPublished = nextRemoteCommitPublished?.let { Closing.RemoteClose.ignoreFailedIncomingHtlc(cmd.id, it, commitments.latest, commitments.latest.nextRemoteCommit!!) },
                )
                Pair(nextState, listOf(ChannelAction.Storage.StoreState(nextState)))
            }
            is ChannelCommand.Commitment.CheckHtlcTimeout -> checkHtlcTimeout()
            is ChannelCommand.Commitment -> unhandled(cmd)
            is ChannelCommand.Init -> unhandled(cmd)
            is ChannelCommand.Funding -> unhandled(cmd)
            is ChannelCommand.Connected -> unhandled(cmd)
            is ChannelCommand.Disconnected -> unhandled(cmd)
        }
    }

    /**
     * Checks if a channel is closed (i.e. its closing tx has been confirmed)
     *
     * @param additionalConfirmedTx additional confirmed transaction; we need this for the mutual close scenario because we don't store the closing tx in the channel state
     * @return the channel closing type, if applicable
     */
    private fun isClosed(additionalConfirmedTx: Transaction?): ClosingType? {
        return when {
            additionalConfirmedTx?.let { tx -> mutualClosePublished.any { it.tx.txid == tx.txid } } == true -> {
                val closingTx = mutualClosePublished.first { it.tx.txid == additionalConfirmedTx.txid }.copy(tx = additionalConfirmedTx)
                MutualClose(closingTx)
            }
            localCommitPublished?.isDone == true -> LocalClose(commitments.latest.localCommit, localCommitPublished)
            remoteCommitPublished?.isDone == true -> CurrentRemoteClose(commitments.latest.remoteCommit, remoteCommitPublished)
            nextRemoteCommitPublished?.isDone == true -> NextRemoteClose(commitments.latest.nextRemoteCommit!!, nextRemoteCommitPublished)
            futureRemoteCommitPublished?.isDone == true -> RecoveryClose(futureRemoteCommitPublished)
            revokedCommitPublished.any { it.isDone } -> RevokedClose(revokedCommitPublished.first { it.isDone })
            else -> null
        }
    }

    fun closingTypeAlreadyKnown(): ClosingType? {
        return when {
            localCommitPublished?.isConfirmed == true -> LocalClose(commitments.latest.localCommit, localCommitPublished)
            remoteCommitPublished?.isConfirmed == true -> CurrentRemoteClose(commitments.latest.remoteCommit, remoteCommitPublished)
            nextRemoteCommitPublished?.isConfirmed == true -> NextRemoteClose(commitments.latest.nextRemoteCommit!!, nextRemoteCommitPublished)
            futureRemoteCommitPublished?.isConfirmed == true -> RecoveryClose(futureRemoteCommitPublished)
            revokedCommitPublished.any { it.isConfirmed } -> RevokedClose(revokedCommitPublished.first { it.isConfirmed })
            else -> null
        }
    }

    companion object {
        /**
         * This method returns updates the status of the closing transaction that should be persisted in a database. It should be
         * called when the channel has been actually closed, so that we know those transactions are confirmed. Note that we only
         * keep track of the commit tx in the non-mutual-close case.
         */
        private fun setClosingStatus(closingType: ClosingType): ChannelAction.Storage.SetLocked {
            val txId = when (closingType) {
                is MutualClose -> closingType.tx.tx.txid
                is LocalClose -> closingType.localCommit.txId
                is RemoteClose -> closingType.remoteCommit.txid
                is RecoveryClose -> closingType.remoteCommitPublished.commitTx.txid
                is RevokedClose -> closingType.revokedCommitPublished.commitTx.txid
            }
            return ChannelAction.Storage.SetLocked(txId)
        }
    }
}
