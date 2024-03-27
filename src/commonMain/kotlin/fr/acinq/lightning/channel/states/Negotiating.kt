package fr.acinq.lightning.channel.states

import fr.acinq.bitcoin.Transaction
import fr.acinq.bitcoin.utils.Either
import fr.acinq.lightning.blockchain.*
import fr.acinq.lightning.blockchain.fee.FeeratePerKw
import fr.acinq.lightning.channel.*
import fr.acinq.lightning.transactions.Transactions
import fr.acinq.lightning.transactions.Transactions.TransactionWithInputInfo.ClosingTx
import fr.acinq.lightning.wire.ClosingComplete
import fr.acinq.lightning.wire.ClosingSig
import fr.acinq.lightning.wire.Error
import fr.acinq.lightning.wire.Shutdown

data class Negotiating(
    override val commitments: Commitments,
    val localShutdown: Shutdown,
    val remoteShutdown: Shutdown,
    // Closing transactions we created, where we pay the fees (unsigned).
    val proposedClosingTxs: List<Transactions.ClosingTxs>,
    // Closing transactions we published: this contains our local transactions for
    // which they sent a signature, and their closing transactions that we signed.
    val publishedClosingTxs: List<ClosingTx>,
    val closingFeerate: FeeratePerKw?,
    val waitingSinceBlock: Long, // how many blocks since we initiated the closing
) : ChannelStateWithCommitments() {
    override fun updateCommitments(input: Commitments): ChannelStateWithCommitments = this.copy(commitments = input)

    override fun ChannelContext.processInternal(cmd: ChannelCommand): Pair<ChannelState, List<ChannelAction>> {
        return when (cmd) {
            is ChannelCommand.MessageReceived -> when (cmd.message) {
                is ClosingComplete -> {
                    val result = Helpers.Closing.signClosingTx(
                        channelKeys(),
                        commitments.latest,
                        localShutdown.scriptPubKey,
                        remoteShutdown.scriptPubKey,
                        cmd.message
                    )
                    when (result) {
                        is Either.Left -> {
                            // This may happen if scripts were updated concurrently, so we simply ignore failures.
                            // Bolt 2:
                            //  - If the signature field is not valid for the corresponding closing transaction:
                            //    - MUST ignore `closing_complete`.
                            logger.warning { "invalid closing_complete: ${result.value.message}" }
                            Pair(this@Negotiating, listOf())
                        }
                        is Either.Right -> {
                            val (closingTx, closingSig) = result.value
                            val nextState = this@Negotiating.copy(publishedClosingTxs = publishedClosingTxs + closingTx)
                            val actions = listOf(
                                ChannelAction.Storage.StoreState(nextState),
                                ChannelAction.Blockchain.PublishTx(closingTx),
                                ChannelAction.Blockchain.SendWatch(WatchConfirmed(channelId, closingTx.tx, staticParams.nodeParams.minDepthBlocks.toLong(), BITCOIN_TX_CONFIRMED(closingTx.tx))),
                                ChannelAction.Message.Send(closingSig)
                            )
                            Pair(nextState, actions)
                        }
                    }
                }
                is ClosingSig -> {
                    val result = Helpers.Closing.receiveClosingSig(
                        channelKeys(),
                        commitments.latest,
                        proposedClosingTxs.last(),
                        cmd.message
                    )
                    when (result) {
                        is Either.Left -> {
                            // This may happen if scripts were updated concurrently, so we simply ignore failures.
                            // Bolt 2:
                            //  - If the signature field is not valid for the corresponding closing transaction:
                            //    - MUST ignore `closing_sig`.
                            logger.warning { "invalid closing_sig: ${result.value.message}" }
                            Pair(this@Negotiating, listOf())
                        }
                        is Either.Right -> {
                            val closingTx = result.value
                            val nextState = this@Negotiating.copy(publishedClosingTxs = publishedClosingTxs + closingTx)
                            val actions = listOf(
                                ChannelAction.Storage.StoreState(nextState),
                                ChannelAction.Blockchain.PublishTx(closingTx),
                                ChannelAction.Blockchain.SendWatch(WatchConfirmed(channelId, closingTx.tx, staticParams.nodeParams.minDepthBlocks.toLong(), BITCOIN_TX_CONFIRMED(closingTx.tx))),
                            )
                            Pair(nextState, actions)
                        }
                    }
                }
                is Shutdown -> {
                    if (cmd.message.scriptPubKey != remoteShutdown.scriptPubKey) {
                        // Our peer changed their closing script: we sign a new version of our closing transaction using the new script.
                        logger.info { "our peer updated their shutdown script (previous=${remoteShutdown.scriptPubKey}, current=${cmd.message.scriptPubKey})" }
                        val result = Helpers.Closing.makeClosingTxs(
                            channelKeys(),
                            commitments.latest,
                            localShutdown.scriptPubKey,
                            cmd.message.scriptPubKey,
                            closingFeerate ?: currentOnChainFeerates.mutualCloseFeerate,
                        )
                        when (result) {
                            is Either.Left -> {
                                logger.warning { "cannot create local closing txs for new remote script, waiting for remote closing_complete: ${result.value.message}" }
                                val nextState = this@Negotiating.copy(remoteShutdown = cmd.message)
                                Pair(nextState, listOf(ChannelAction.Storage.StoreState(nextState)))
                            }
                            is Either.Right -> {
                                val (closingTxs, closingComplete) = result.value
                                val nextState = this@Negotiating.copy(remoteShutdown = cmd.message, proposedClosingTxs = proposedClosingTxs + closingTxs)
                                val actions = listOf(
                                    ChannelAction.Storage.StoreState(nextState),
                                    ChannelAction.Message.Send(closingComplete)
                                )
                                Pair(nextState, actions)
                            }
                        }
                    } else {
                        // This is a retransmission of their previous shutdown, we can ignore it.
                        Pair(this@Negotiating, listOf())
                    }
                }
                is Error -> handleRemoteError(cmd.message)
                else -> unhandled(cmd)
            }
            is ChannelCommand.WatchReceived -> when (val watch = cmd.watch) {
                is WatchEventConfirmed -> when {
                    publishedClosingTxs.any { it.tx.txid == watch.tx.txid } -> {
                        // One of our published transactions confirmed, the channel is now closed.
                        completeMutualClose(publishedClosingTxs.first { it.tx.txid == watch.tx.txid })
                    }
                    proposedClosingTxs.flatMap { it.all }.any { it.tx.txid == watch.tx.txid } -> {
                        // A transaction that we proposed for which they didn't send us their signature was confirmed, the channel is now closed.
                        completeMutualClose(getMutualClosePublished(watch.tx))
                    }
                    else -> {
                        // Otherwise, this must be a funding transaction that just got confirmed.
                        updateFundingTxStatus(watch)
                    }
                }
                is WatchEventSpent -> when {
                    watch.event is BITCOIN_FUNDING_SPENT && publishedClosingTxs.any { it.tx.txid == watch.tx.txid } -> {
                        // This is one of the transactions we already published, we have nothing to do.
                        Pair(this@Negotiating, listOf())
                    }
                    watch.event is BITCOIN_FUNDING_SPENT && proposedClosingTxs.flatMap { it.all }.any { it.tx.txid == watch.tx.txid } -> {
                        // They published one of our closing transactions without sending us their signature.
                        val closingTx = getMutualClosePublished(watch.tx)
                        val nextState = this@Negotiating.copy(publishedClosingTxs = publishedClosingTxs + closingTx)
                        val actions = listOf(
                            ChannelAction.Storage.StoreState(nextState),
                            ChannelAction.Blockchain.PublishTx(closingTx),
                            ChannelAction.Blockchain.SendWatch(WatchConfirmed(channelId, watch.tx, staticParams.nodeParams.minDepthBlocks.toLong(), BITCOIN_TX_CONFIRMED(watch.tx)))
                        )
                        Pair(nextState, actions)
                    }
                    else -> handlePotentialForceClose(watch)
                }
            }
            is ChannelCommand.Commitment.CheckHtlcTimeout -> checkHtlcTimeout()
            is ChannelCommand.Commitment -> unhandled(cmd)
            is ChannelCommand.Htlc.Add -> handleCommandError(cmd, ChannelUnavailable(channelId))
            is ChannelCommand.Htlc -> unhandled(cmd)
            is ChannelCommand.Close.ForceClose -> handleLocalError(cmd, ForcedLocalCommit(channelId))
            is ChannelCommand.Close.MutualClose -> {
                if (cmd.scriptPubKey != null || cmd.feerate != null) {
                    // We're updating our script or feerate (or both), so we generate a new set of closing transactions.
                    val localShutdown1 = cmd.scriptPubKey?.let { localShutdown.copy(scriptPubKey = it) } ?: localShutdown
                    val result = Helpers.Closing.makeClosingTxs(
                        channelKeys(),
                        commitments.latest,
                        localShutdown1.scriptPubKey,
                        remoteShutdown.scriptPubKey,
                        cmd.feerate ?: closingFeerate ?: currentOnChainFeerates.mutualCloseFeerate,
                    )
                    when (result) {
                        is Either.Left -> {
                            logger.warning { "cannot create local closing txs, waiting for remote closing_complete: ${result.value.message}" }
                            val nextState = this@Negotiating.copy(localShutdown = localShutdown1, closingFeerate = cmd.feerate ?: closingFeerate)
                            val actions = buildList {
                                add(ChannelAction.Storage.StoreState(nextState))
                                if (cmd.scriptPubKey != null) add(ChannelAction.Message.Send(localShutdown1))
                            }
                            Pair(nextState, actions)
                        }
                        is Either.Right -> {
                            val (closingTxs, closingComplete) = result.value
                            val nextState = this@Negotiating.copy(localShutdown = localShutdown1, closingFeerate = cmd.feerate ?: closingFeerate, proposedClosingTxs = proposedClosingTxs + closingTxs)
                            val actions = buildList {
                                add(ChannelAction.Storage.StoreState(nextState))
                                if (cmd.scriptPubKey != null) add(ChannelAction.Message.Send(localShutdown1))
                                add(ChannelAction.Message.Send(closingComplete))
                            }
                            Pair(nextState, actions)
                        }
                    }
                } else {
                    handleCommandError(cmd, ClosingAlreadyInProgress(channelId))
                }
            }
            is ChannelCommand.Init -> unhandled(cmd)
            is ChannelCommand.Funding -> unhandled(cmd)
            is ChannelCommand.Closing -> unhandled(cmd)
            is ChannelCommand.Connected -> unhandled(cmd)
            is ChannelCommand.Disconnected -> Pair(Offline(this@Negotiating), listOf())
        }
    }

    /** Return full information about a closing tx that we proposed and they then published. */
    internal fun getMutualClosePublished(tx: Transaction): ClosingTx {
        // They can publish a closing tx with any sig we sent them, even if we are not done negotiating.
        // They added their signature, so we use their version of the transaction.
        return proposedClosingTxs.flatMap { it.all }.first { it.tx.txid == tx.txid }.copy(tx = tx)
    }

    internal fun ChannelContext.completeMutualClose(signedClosingTx: ClosingTx): Pair<ChannelState, List<ChannelAction>> {
        logger.info { "channel was closed with txId=${signedClosingTx.tx.txid}" }
        val nextState = Closed(
            Closing(
                commitments,
                waitingSinceBlock = waitingSinceBlock,
                mutualCloseProposed = proposedClosingTxs.flatMap { it.all },
                mutualClosePublished = listOf(signedClosingTx)
            )
        )
        val actions = buildList {
            add(ChannelAction.Storage.StoreState(nextState))
            addAll(emitClosingEvents(this@Negotiating, nextState.state))
            add(ChannelAction.Storage.SetLocked(signedClosingTx.tx.txid))
        }
        return Pair(nextState, actions)
    }
}
