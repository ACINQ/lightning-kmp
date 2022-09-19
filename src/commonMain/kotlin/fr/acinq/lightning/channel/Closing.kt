package fr.acinq.lightning.channel

import fr.acinq.bitcoin.*
import fr.acinq.lightning.blockchain.*
import fr.acinq.lightning.blockchain.fee.OnChainFeerates
import fr.acinq.lightning.channel.Helpers.Closing.extractPreimages
import fr.acinq.lightning.channel.Helpers.Closing.onChainOutgoingHtlcs
import fr.acinq.lightning.channel.Helpers.Closing.overriddenOutgoingHtlcs
import fr.acinq.lightning.channel.Helpers.Closing.timedOutHtlcs
import fr.acinq.lightning.db.ChannelClosingType
import fr.acinq.lightning.db.OutgoingPayment
import fr.acinq.lightning.transactions.Transactions
import fr.acinq.lightning.transactions.Transactions.TransactionWithInputInfo.ClosingTx
import fr.acinq.lightning.utils.*
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
    override val staticParams: StaticParams,
    override val currentTip: Pair<Int, BlockHeader>,
    override val currentOnChainFeerates: OnChainFeerates,
    override val commitments: Commitments,
    val fundingTx: Transaction?, // this will be non-empty if we are the initiator and we got in closing while waiting for our own tx to be published
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

    override fun processInternal(event: ChannelEvent): Pair<ChannelState, List<ChannelAction>> {
        return when (event) {
            is ChannelEvent.WatchReceived -> {
                val watch = event.watch
                when {
                    watch is WatchEventSpent && watch.event is BITCOIN_FUNDING_SPENT -> when {
                        mutualClosePublished.any { it.tx.txid == watch.tx.txid } -> {
                            // we already know about this tx, probably because we have published it ourselves after successful negotiation
                            Pair(this, listOf())
                        }
                        mutualCloseProposed.any { it.tx.txid == watch.tx.txid } -> {
                            // at any time they can publish a closing tx with any sig we sent them
                            val closingTx = mutualCloseProposed.first { it.tx.txid == watch.tx.txid }.copy(tx = watch.tx)
                            val nextState = this.copy(mutualClosePublished = this.mutualClosePublished + listOf(closingTx))
                            val actions = listOf(ChannelAction.Storage.StoreState(nextState), ChannelAction.Blockchain.PublishTx(watch.tx))
                            Pair(nextState, actions)
                        }
                        localCommitPublished?.commitTx == watch.tx || remoteCommitPublished?.commitTx == watch.tx || nextRemoteCommitPublished?.commitTx == watch.tx || futureRemoteCommitPublished?.commitTx == watch.tx -> {
                            // this is because WatchSpent watches never expire and we are notified multiple times
                            Pair(this, listOf())
                        }
                        watch.tx.txid == commitments.remoteCommit.txid -> {
                            // counterparty may attempt to spend its last commit tx at any time
                            handleRemoteSpentCurrent(watch.tx)
                        }
                        watch.tx.txid == commitments.remoteNextCommitInfo.left?.nextRemoteCommit?.txid -> {
                            // counterparty may attempt to spend its next commit tx at any time
                            handleRemoteSpentNext(watch.tx)
                        }
                        else -> {
                            // counterparty may attempt to spend a revoked commit tx at any time
                            handleRemoteSpentOther(watch.tx)
                        }
                    }
                    watch is WatchEventSpent && watch.event is BITCOIN_OUTPUT_SPENT -> {
                        // when a remote or local commitment tx containing outgoing htlcs is published on the network,
                        // we watch it in order to extract payment preimage if funds are pulled by the counterparty
                        // we can then use these preimages to fulfill payments
                        logger.info { "c:$channelId processing BITCOIN_OUTPUT_SPENT with txid=${watch.tx.txid} tx=${watch.tx}" }
                        val htlcSettledActions = mutableListOf<ChannelAction>()
                        commitments.localCommit.extractPreimages(watch.tx).forEach { (htlc, preimage) ->
                            when (val paymentId = commitments.payments[htlc.id]) {
                                null -> {
                                    // if we don't have a reference to the payment, it means that we already have forwarded the fulfill so that's not a big deal.
                                    // this can happen if they send a signature containing the fulfill, then fail the channel before we have time to sign it
                                    logger.info { "c:$channelId cannot fulfill htlc #${htlc.id} paymentHash=${htlc.paymentHash} (payment not found)" }
                                }
                                else -> {
                                    logger.info { "c:$channelId fulfilling htlc #${htlc.id} paymentHash=${htlc.paymentHash} paymentId=$paymentId" }
                                    htlcSettledActions += ChannelAction.ProcessCmdRes.AddSettledFulfill(paymentId, htlc, ChannelAction.HtlcResult.Fulfill.OnChainFulfill(preimage))
                                }
                            }
                        }

                        val revokedCommitPublishActions = mutableListOf<ChannelAction>()
                        val revokedCommitPublished1 = revokedCommitPublished.map { rev ->
                            val (newRevokedCommitPublished, penaltyTxs) = Helpers.Closing.claimRevokedHtlcTxOutputs(keyManager, commitments, rev, watch.tx, currentOnChainFeerates)
                            penaltyTxs.forEach {
                                revokedCommitPublishActions += ChannelAction.Blockchain.PublishTx(it.tx)
                                revokedCommitPublishActions += ChannelAction.Blockchain.SendWatch(WatchSpent(channelId, watch.tx, it.input.outPoint.index.toInt(), BITCOIN_OUTPUT_SPENT))
                            }
                            newRevokedCommitPublished
                        }

                        val nextState = copy(revokedCommitPublished = revokedCommitPublished1)
                        val actions = buildList {
                            add(ChannelAction.Storage.StoreState(nextState))
                            // one of the outputs of the local/remote/revoked commit was spent
                            // we just put a watch to be notified when it is confirmed
                            add(ChannelAction.Blockchain.SendWatch(WatchConfirmed(channelId, watch.tx, staticParams.nodeParams.minDepthBlocks.toLong(), BITCOIN_TX_CONFIRMED(watch.tx))))
                            addAll(revokedCommitPublishActions)
                            addAll(htlcSettledActions)
                        }
                        Pair(nextState, actions)
                    }
                    watch is WatchEventConfirmed && watch.event is BITCOIN_TX_CONFIRMED -> {
                        logger.info { "c:$channelId txid=${watch.tx.txid} has reached mindepth, updating closing state" }
                        // first we check if this tx belongs to one of the current local/remote commits, update it and update the channel data
                        val closing1 = this.copy(
                            localCommitPublished = this.localCommitPublished?.update(watch.tx),
                            remoteCommitPublished = this.remoteCommitPublished?.update(watch.tx),
                            nextRemoteCommitPublished = this.nextRemoteCommitPublished?.update(watch.tx),
                            futureRemoteCommitPublished = this.futureRemoteCommitPublished?.update(watch.tx),
                            revokedCommitPublished = this.revokedCommitPublished.map { it.update(watch.tx) }
                        )
                        closing1.networkFeePaid(watch.tx)?.let {
                            logger.info { "c:$channelId paid fee=${it.first} for txid=${watch.tx.txid} desc=${it.second}" }
                        } ?: run {
                            logger.info { "c:$channelId paid UNKNOWN fee for txid=${watch.tx.txid}" }
                        }

                        // we may need to fail some htlcs in case a commitment tx was published and they have reached the timeout threshold
                        val htlcSettledActions = mutableListOf<ChannelAction>()
                        val timedOutHtlcs = when (val closingType = closing1.closingTypeAlreadyKnown()) {
                            is LocalClose -> closingType.localCommit.timedOutHtlcs(closingType.localCommitPublished, commitments.localParams.dustLimit, watch.tx)
                            is RemoteClose -> closingType.remoteCommit.timedOutHtlcs(closingType.remoteCommitPublished, commitments.remoteParams.dustLimit, watch.tx)
                            else -> setOf() // we lose htlc outputs in option_data_loss_protect scenarios (future remote commit)
                        }
                        timedOutHtlcs.forEach { add ->
                            when (val paymentId = commitments.payments[add.id]) {
                                null -> {
                                    // same as for fulfilling the htlc (no big deal)
                                    logger.info { "c:$channelId cannot fail timedout htlc #${add.id} paymentHash=${add.paymentHash} (payment not found)" }
                                }
                                else -> {
                                    logger.info { "c:$channelId failing htlc #${add.id} paymentHash=${add.paymentHash} paymentId=$paymentId: htlc timed out" }
                                    htlcSettledActions += ChannelAction.ProcessCmdRes.AddSettledFail(paymentId, add, ChannelAction.HtlcResult.Fail.OnChainFail(HtlcsTimedOutDownstream(channelId, setOf(add))))
                                }
                            }
                        }

                        // we also need to fail outgoing htlcs that we know will never reach the blockchain
                        overriddenOutgoingHtlcs(commitments.localCommit, commitments.remoteCommit, commitments.remoteNextCommitInfo.left?.nextRemoteCommit, closing1.revokedCommitPublished, watch.tx).forEach { add ->
                            when (val paymentId = commitments.payments[add.id]) {
                                null -> {
                                    // same as for fulfilling the htlc (no big deal)
                                    logger.info { "c:$channelId cannot fail overridden htlc #${add.id} paymentHash=${add.paymentHash} (payment not found)" }
                                }
                                else -> {
                                    logger.info { "c:$channelId failing htlc #${add.id} paymentHash=${add.paymentHash} paymentId=$paymentId: overridden by local commit" }
                                    htlcSettledActions += ChannelAction.ProcessCmdRes.AddSettledFail(paymentId, add, ChannelAction.HtlcResult.Fail.OnChainFail(HtlcOverriddenByLocalCommit(channelId, add)))
                                }
                            }
                        }

                        // for our outgoing payments, let's log something if we know that they will settle on chain
                        onChainOutgoingHtlcs(commitments.localCommit, commitments.remoteCommit, commitments.remoteNextCommitInfo.left?.nextRemoteCommit, watch.tx).forEach { add ->
                            commitments.payments[add.id]?.let { paymentId -> logger.info { "c:$channelId paymentId=$paymentId will settle on-chain (htlc #${add.id} sending ${add.amountMsat})" } }
                        }

                        val (nextState, closedActions) = when (val closingType = closing1.isClosed(watch.tx)) {
                            null -> Pair(closing1, listOf())
                            else -> {
                                logger.info { "c:$channelId channel is now closed" }
                                if (closingType !is MutualClose) {
                                    logger.debug { "c:$channelId last known remoteChannelData=${commitments.remoteChannelData}" }
                                }
                                Pair(Closed(closing1), listOf(closing1.storeChannelClosed(watch.tx)))
                            }
                        }
                        val actions = buildList {
                            add(ChannelAction.Storage.StoreState(nextState))
                            addAll(htlcSettledActions)
                            addAll(closedActions)
                        }
                        Pair(nextState, actions)
                    }
                    else -> unhandled(event)
                }
            }
            is ChannelEvent.GetHtlcInfosResponse -> {
                val index = revokedCommitPublished.indexOfFirst { it.commitTx.txid == event.revokedCommitTxId }
                if (index >= 0) {
                    val revokedCommitPublished1 = Helpers.Closing.claimRevokedRemoteCommitTxHtlcOutputs(keyManager, commitments, revokedCommitPublished[index], currentOnChainFeerates, event.htlcInfos)
                    val nextState = copy(revokedCommitPublished = revokedCommitPublished.updated(index, revokedCommitPublished1))
                    val actions = buildList {
                        add(ChannelAction.Storage.StoreState(nextState))
                        addAll(revokedCommitPublished1.doPublish(channelId, staticParams.nodeParams.minDepthBlocks.toLong()))
                    }
                    Pair(nextState, actions)
                } else {
                    logger.warning { "c:$channelId cannot find revoked commit with txid=${event.revokedCommitTxId}" }
                    Pair(this, listOf())
                }
            }
            is ChannelEvent.MessageReceived -> when (event.message) {
                is ChannelReestablish -> {
                    // they haven't detected that we were closing and are trying to reestablish a connection
                    // we give them one of the published txs as a hint
                    // note spendingTx != Nil (that's a requirement of DATA_CLOSING)
                    val exc = FundingTxSpent(channelId, spendingTxs.first())
                    val error = Error(channelId, exc.message)
                    Pair(this, listOf(ChannelAction.Message.Send(error)))
                }
                is Error -> {
                    logger.error { "c:$channelId peer sent error: ascii=${event.message.toAscii()} bin=${event.message.data.toHex()}" }
                    // nothing to do, there is already a spending tx published
                    Pair(this, listOf())
                }
                else -> unhandled(event)
            }
            is ChannelEvent.ExecuteCommand -> when (event.command) {
                is CMD_CLOSE -> handleCommandError(event.command, ClosingAlreadyInProgress(channelId))
                is CMD_ADD_HTLC -> {
                    logger.info { "c:$channelId rejecting htlc request in state=${this::class}" }
                    // we don't provide a channel_update: this will be a permanent channel failure
                    handleCommandError(event.command, ChannelUnavailable(channelId))
                }
                is CMD_FULFILL_HTLC -> when (val result = commitments.sendFulfill(event.command)) {
                    is Either.Right -> {
                        logger.info { "c:$channelId got valid payment preimage, recalculating transactions to redeem the corresponding htlc on-chain" }
                        val commitments1 = result.value.first
                        val localCommitPublished1 = localCommitPublished?.let {
                            Helpers.Closing.claimCurrentLocalCommitTxOutputs(keyManager, commitments1, it.commitTx, currentOnChainFeerates)
                        }
                        val remoteCommitPublished1 = remoteCommitPublished?.let {
                            Helpers.Closing.claimRemoteCommitTxOutputs(keyManager, commitments1, commitments1.remoteCommit, it.commitTx, currentOnChainFeerates)
                        }
                        val nextRemoteCommitPublished1 = nextRemoteCommitPublished?.let {
                            val remoteCommit = commitments1.remoteNextCommitInfo.left?.nextRemoteCommit ?: error("next remote commit must be defined")
                            Helpers.Closing.claimRemoteCommitTxOutputs(keyManager, commitments1, remoteCommit, it.commitTx, currentOnChainFeerates)
                        }
                        val republishList = buildList {
                            val minDepth = staticParams.nodeParams.minDepthBlocks.toLong()
                            localCommitPublished1?.run { addAll(doPublish(channelId, minDepth)) }
                            remoteCommitPublished1?.run { addAll(doPublish(channelId, minDepth)) }
                            nextRemoteCommitPublished1?.run { addAll(doPublish(channelId, minDepth)) }
                        }
                        val nextState = copy(
                            commitments = commitments1,
                            localCommitPublished = localCommitPublished1,
                            remoteCommitPublished = remoteCommitPublished1,
                            nextRemoteCommitPublished = nextRemoteCommitPublished1
                        )
                        val actions = buildList {
                            add(ChannelAction.Storage.StoreState(nextState))
                            addAll(republishList)
                        }
                        Pair(nextState, actions)
                    }
                    is Either.Left -> handleCommandError(event.command, result.value)
                }
                else -> unhandled(event)
            }
            is ChannelEvent.GetFundingTxResponse -> when (event.getTxResponse.txid) {
                commitments.commitInput.outPoint.txid -> handleGetFundingTx(event.getTxResponse, waitingSinceBlock, fundingTx)
                else -> Pair(this, emptyList())
            }
            is ChannelEvent.CheckHtlcTimeout -> checkHtlcTimeout()
            is ChannelEvent.NewBlock -> Pair(this.copy(currentTip = Pair(event.height, event.Header)), listOf())
            is ChannelEvent.SetOnChainFeerates -> Pair(this.copy(currentOnChainFeerates = event.feerates), listOf())
            is ChannelEvent.Disconnected -> Pair(Offline(this), listOf())
            else -> unhandled(event)
        }
    }

    override fun handleLocalError(event: ChannelEvent, t: Throwable): Pair<ChannelState, List<ChannelAction>> {
        logger.error(t) { "c:$channelId error processing ${event::class} in state ${this::class}" }
        return localCommitPublished?.let {
            // we're already trying to claim our commitment, there's nothing more we can do
            Pair(this, listOf())
        } ?: spendLocalCurrent()
    }

    /**
     * Checks if a channel is closed (i.e. its closing tx has been confirmed)
     *
     * @param additionalConfirmedTx additional confirmed transaction; we need this for the mutual close scenario because we don't store the closing tx in the channel state
     * @return the channel closing type, if applicable
     */
    private fun isClosed(additionalConfirmedTx: Transaction?): ClosingType? {
        return when {
            additionalConfirmedTx?.let { tx -> mutualClosePublished.any { it.tx.txid == tx.txid } } ?: false -> {
                val closingTx = mutualClosePublished.first { it.tx.txid == additionalConfirmedTx!!.txid }.copy(tx = additionalConfirmedTx!!)
                MutualClose(closingTx)
            }
            localCommitPublished?.isDone() ?: false -> LocalClose(commitments.localCommit, localCommitPublished!!)
            remoteCommitPublished?.isDone() ?: false -> CurrentRemoteClose(commitments.remoteCommit, remoteCommitPublished!!)
            nextRemoteCommitPublished?.isDone() ?: false -> NextRemoteClose(commitments.remoteNextCommitInfo.left!!.nextRemoteCommit, nextRemoteCommitPublished!!)
            futureRemoteCommitPublished?.isDone() ?: false -> RecoveryClose(futureRemoteCommitPublished!!)
            revokedCommitPublished.any { it.isDone() } -> RevokedClose(revokedCommitPublished.first { it.isDone() })
            else -> null
        }
    }

    fun closingTypeAlreadyKnown(): ClosingType? {
        return when {
            localCommitPublished?.isConfirmed() ?: false -> LocalClose(commitments.localCommit, localCommitPublished!!)
            remoteCommitPublished?.isConfirmed() ?: false -> CurrentRemoteClose(commitments.remoteCommit, remoteCommitPublished!!)
            nextRemoteCommitPublished?.isConfirmed() ?: false -> NextRemoteClose(commitments.remoteNextCommitInfo.left!!.nextRemoteCommit, nextRemoteCommitPublished!!)
            futureRemoteCommitPublished?.isConfirmed() ?: false -> RecoveryClose(futureRemoteCommitPublished!!)
            revokedCommitPublished.any { it.isConfirmed() } -> RevokedClose(revokedCommitPublished.first { it.isConfirmed() })
            else -> null
        }
    }

    /**
     * This helper function returns the fee paid by the given transaction.
     * It relies on the current channel data to find the parent tx and compute the fee, and also provides a description.
     *
     * @param tx a tx for which we want to compute the fee
     * @return if the parent tx is found, a tuple (fee, description)
     */
    private fun networkFeePaid(tx: Transaction): Pair<Satoshi, String>? {
        // we can compute the fees only for transactions with a single parent for which we know the output amount
        if (tx.txIn.size != 1) return null
        // only the initiator pays the fee for the commit tx, but 2nd-stage and 3rd-stage tx fees are paid by their recipients
        val isCommitTx = tx.txIn.any { it.outPoint == commitments.commitInput.outPoint }
        if (isCommitTx && !commitments.localParams.isInitiator) return null

        // we build a map with all known txs (that's not particularly efficient, but it doesn't really matter)
        val txs = buildList {
            mutualClosePublished.map { it.tx to "mutual" }.forEach { add(it) }
            localCommitPublished?.let { localCommitPublished ->
                add(localCommitPublished.commitTx to "local-commit")
                localCommitPublished.claimMainDelayedOutputTx?.let { add(it.tx to "local-main-delayed") }
                localCommitPublished.htlcTxs.values.forEach {
                    when (it) {
                        is Transactions.TransactionWithInputInfo.HtlcTx.HtlcSuccessTx -> add(it.tx to "local-htlc-success")
                        is Transactions.TransactionWithInputInfo.HtlcTx.HtlcTimeoutTx -> add(it.tx to "local-htlc-timeout")
                        else -> Unit
                    }
                }
                localCommitPublished.claimHtlcDelayedTxs.forEach { add(it.tx to "local-htlc-delayed") }
            }
            remoteCommitPublished?.let { remoteCommitPublished ->
                add(remoteCommitPublished.commitTx to "remote-commit")
                remoteCommitPublished.claimMainOutputTx?.let { add(it.tx to "remote-main") }
                remoteCommitPublished.claimHtlcTxs.values.forEach {
                    when (it) {
                        is Transactions.TransactionWithInputInfo.ClaimHtlcTx.ClaimHtlcSuccessTx -> add(it.tx to "remote-htlc-success")
                        is Transactions.TransactionWithInputInfo.ClaimHtlcTx.ClaimHtlcTimeoutTx -> add(it.tx to "remote-htlc-timeout")
                        else -> Unit
                    }
                }
            }
            nextRemoteCommitPublished?.let { nextRemoteCommitPublished ->
                add(nextRemoteCommitPublished.commitTx to "remote-commit")
                nextRemoteCommitPublished.claimMainOutputTx?.let { add(it.tx to "remote-main") }
                nextRemoteCommitPublished.claimHtlcTxs.values.forEach {
                    when (it) {
                        is Transactions.TransactionWithInputInfo.ClaimHtlcTx.ClaimHtlcSuccessTx -> add(it.tx to "remote-htlc-success")
                        is Transactions.TransactionWithInputInfo.ClaimHtlcTx.ClaimHtlcTimeoutTx -> add(it.tx to "remote-htlc-timeout")
                        else -> Unit
                    }
                }
            }
            revokedCommitPublished.forEach { revokedCommitPublished ->
                add(revokedCommitPublished.commitTx to "revoked-commit")
                revokedCommitPublished.claimMainOutputTx?.let { add(it.tx to "revoked-main") }
                revokedCommitPublished.mainPenaltyTx?.let { add(it.tx to "revoked-main-penalty") }
                revokedCommitPublished.htlcPenaltyTxs.forEach { add(it.tx to "revoked-htlc-penalty") }
                revokedCommitPublished.claimHtlcDelayedPenaltyTxs.forEach { add(it.tx to "revoked-htlc-penalty-delayed") }
            }
        }.map { (tx, desc) ->
            // will allow easy lookup of parent transaction
            tx.txid to (tx to desc)
        }.toMap()

        return txs[tx.txid]?.let { (_, desc) ->
            val parentTxOut = if (isCommitTx) {
                commitments.commitInput.txOut
            } else {
                val outPoint = tx.txIn.first().outPoint
                txs[outPoint.txid]?.let { (parent, _) -> parent.txOut[outPoint.index.toInt()] }
            }
            parentTxOut?.let { txOut -> txOut.amount - tx.txOut.map { it.amount }.sum() }?.let { it to desc }
        }
    }

    /**
     * This method returns the closing transactions that should be persisted in a database. It should be
     * called when the channel has been actually closed, so that we know those transactions are confirmed.
     *
     * @param additionalConfirmedTx additional confirmed transaction for mutual close; we need this for the
     *      mutual close scenario because we don't store the closing tx in the channel state.
     */
    private fun storeChannelClosed(additionalConfirmedTx: Transaction): ChannelAction.Storage.StoreChannelClosed {

        /** Helper method to extract a [OutgoingPayment.ClosingTxPart] from a list of transactions, given a set of confirmed tx ids. */
        fun extractClosingTx(
            confirmedTxids: Set<ByteVector32>,
            txs: List<Transaction>,
            closingType: ChannelClosingType
        ): List<OutgoingPayment.ClosingTxPart> {
            return txs.filter { confirmedTxids.contains(it.txid) }.map {
                OutgoingPayment.ClosingTxPart(
                    id = UUID.randomUUID(),
                    txId = it.txid,
                    claimed = it.txOut.first().amount,
                    closingType = closingType,
                    createdAt = currentTimestampMillis()
                )
            }
        }

        val txs = listOfNotNull(localCommitPublished).flatMap { local ->
            extractClosingTx(
                confirmedTxids = local.irrevocablySpent.values.map { it.txid }.toSet(),
                txs = (local.claimHtlcDelayedTxs.map { it.tx } + local.claimMainDelayedOutputTx?.tx).filterNotNull(),
                closingType = ChannelClosingType.Local
            )
        } + listOfNotNull(remoteCommitPublished, nextRemoteCommitPublished, futureRemoteCommitPublished).flatMap { remote ->
            extractClosingTx(
                confirmedTxids = remote.irrevocablySpent.values.map { it.txid }.toSet(),
                txs = (remote.claimHtlcTxs.map { it.value?.tx } + remote.claimMainOutputTx?.tx).filterNotNull(),
                closingType = ChannelClosingType.Remote
            )
        } + revokedCommitPublished.flatMap { revoked ->
            extractClosingTx(
                confirmedTxids = revoked.irrevocablySpent.values.map { it.txid }.toSet(),
                txs = (revoked.claimHtlcDelayedPenaltyTxs.map { it.tx } + revoked.htlcPenaltyTxs.map { it.tx }
                        + revoked.claimMainOutputTx?.tx + revoked.mainPenaltyTx?.tx).filterNotNull(),
                closingType = ChannelClosingType.Revoked
            )
        } + mutualClosePublished.firstOrNull { it.tx == additionalConfirmedTx }?.let {
            OutgoingPayment.ClosingTxPart(
                id = UUID.randomUUID(),
                txId = it.tx.txid,
                claimed = it.toLocalOutput?.amount ?: 0.sat,
                closingType = ChannelClosingType.Mutual,
                createdAt = currentTimestampMillis()
            )
        }
        return ChannelAction.Storage.StoreChannelClosed(txs.filterNotNull())
    }
}
