package fr.acinq.lightning.channel

import fr.acinq.bitcoin.Transaction
import fr.acinq.bitcoin.updated
import fr.acinq.lightning.blockchain.BITCOIN_FUNDING_SPENT
import fr.acinq.lightning.blockchain.BITCOIN_TX_CONFIRMED
import fr.acinq.lightning.blockchain.WatchConfirmed
import fr.acinq.lightning.blockchain.WatchEventSpent
import fr.acinq.lightning.channel.Channel.MAX_NEGOTIATION_ITERATIONS
import fr.acinq.lightning.transactions.Transactions.TransactionWithInputInfo.ClosingTx
import fr.acinq.lightning.utils.Either
import fr.acinq.lightning.utils.msat
import fr.acinq.lightning.wire.ClosingSigned
import fr.acinq.lightning.wire.ClosingSignedTlv
import fr.acinq.lightning.wire.Error
import fr.acinq.lightning.wire.Shutdown

data class Negotiating(
    override val commitments: Commitments,
    val localShutdown: Shutdown,
    val remoteShutdown: Shutdown,
    val closingTxProposed: List<List<ClosingTxProposed>>, // one list for every negotiation (there can be several in case of disconnection)
    val bestUnpublishedClosingTx: ClosingTx?,
    val closingFeerates: ClosingFeerates?
) : ChannelStateWithCommitments() {
    init {
        require(closingTxProposed.isNotEmpty()) { "there must always be a list for the current negotiation" }
        require(!commitments.localParams.isInitiator || !closingTxProposed.any { it.isEmpty() }) { "initiator must have at least one closing signature for every negotiation attempt because it initiates the closing" }
    }

    override fun updateCommitments(input: Commitments): ChannelStateWithCommitments = this.copy(commitments = input)

    override fun ChannelContext.processInternal(cmd: ChannelCommand): Pair<ChannelState, List<ChannelAction>> {
        return when {
            cmd is ChannelCommand.MessageReceived && cmd.message is ClosingSigned -> {
                val remoteClosingFee = cmd.message.feeSatoshis
                logger.info { "c:$channelId received closing fee=$remoteClosingFee" }
                when (val result = Helpers.Closing.checkClosingSignature(keyManager, commitments, localShutdown.scriptPubKey.toByteArray(), remoteShutdown.scriptPubKey.toByteArray(), cmd.message.feeSatoshis, cmd.message.signature)) {
                    is Either.Left -> handleLocalError(cmd, result.value)
                    is Either.Right -> {
                        val (signedClosingTx, closingSignedRemoteFees) = result.value
                        val lastLocalClosingSigned = closingTxProposed.last().lastOrNull()?.localClosingSigned
                        when {
                            lastLocalClosingSigned?.feeSatoshis == remoteClosingFee -> {
                                logger.info { "c:$channelId they accepted our fee, publishing closing tx: closingTxId=${signedClosingTx.tx.txid}" }
                                completeMutualClose(signedClosingTx, null)
                            }
                            closingTxProposed.flatten().size >= MAX_NEGOTIATION_ITERATIONS -> {
                                logger.warning { "c:$channelId could not agree on closing fees after $MAX_NEGOTIATION_ITERATIONS iterations, publishing closing tx: closingTxId=${signedClosingTx.tx.txid}" }
                                completeMutualClose(signedClosingTx, closingSignedRemoteFees)
                            }
                            lastLocalClosingSigned?.tlvStream?.get<ClosingSignedTlv.FeeRange>()?.let { it.min <= remoteClosingFee && remoteClosingFee <= it.max } == true -> {
                                val localFeeRange = lastLocalClosingSigned.tlvStream.get<ClosingSignedTlv.FeeRange>()!!
                                logger.info { "c:$channelId they chose closing fee=$remoteClosingFee within our fee range (min=${localFeeRange.max} max=${localFeeRange.max}), publishing closing tx: closingTxId=${signedClosingTx.tx.txid}" }
                                completeMutualClose(signedClosingTx, closingSignedRemoteFees)
                            }
                            commitments.localCommit.spec.toLocal == 0.msat -> {
                                logger.info { "c:$channelId we have nothing at stake, publishing closing tx: closingTxId=${signedClosingTx.tx.txid}" }
                                completeMutualClose(signedClosingTx, closingSignedRemoteFees)
                            }
                            else -> {
                                val theirFeeRange = cmd.message.tlvStream.get<ClosingSignedTlv.FeeRange>()
                                val ourFeeRange = closingFeerates ?: ClosingFeerates(currentOnChainFeerates.mutualCloseFeerate)
                                when {
                                    theirFeeRange != null && !commitments.localParams.isInitiator -> {
                                        // if we are not the initiator and they proposed a fee range, we pick a value in that range and they should accept it without further negotiation
                                        // we don't care much about the closing fee since they're paying it (not us) and we can use CPFP if we want to speed up confirmation
                                        val closingFees = Helpers.Closing.firstClosingFee(commitments, localShutdown.scriptPubKey, remoteShutdown.scriptPubKey, ourFeeRange)
                                        val closingFee = when {
                                            closingFees.preferred > theirFeeRange.max -> theirFeeRange.max
                                            // if we underestimate the fee, then we're happy with whatever they propose (it will confirm more quickly and we're not paying it)
                                            closingFees.preferred < remoteClosingFee -> remoteClosingFee
                                            else -> closingFees.preferred
                                        }
                                        if (closingFee == remoteClosingFee) {
                                            logger.info { "c:$channelId accepting their closing fee=$remoteClosingFee, publishing closing tx: closingTxId=${signedClosingTx.tx.txid}" }
                                            completeMutualClose(signedClosingTx, closingSignedRemoteFees)
                                        } else {
                                            val (closingTx, closingSigned) = Helpers.Closing.makeClosingTx(
                                                keyManager,
                                                commitments,
                                                localShutdown.scriptPubKey.toByteArray(),
                                                remoteShutdown.scriptPubKey.toByteArray(),
                                                ClosingFees(closingFee, theirFeeRange.min, theirFeeRange.max)
                                            )
                                            logger.info { "c:$channelId proposing closing fee=${closingSigned.feeSatoshis}" }
                                            val closingProposed1 = closingTxProposed.updated(
                                                closingTxProposed.lastIndex,
                                                closingTxProposed.last() + listOf(ClosingTxProposed(closingTx, closingSigned))
                                            )
                                            val nextState = this@Negotiating.copy(
                                                commitments = commitments.copy(remoteChannelData = cmd.message.channelData),
                                                closingTxProposed = closingProposed1,
                                                bestUnpublishedClosingTx = signedClosingTx
                                            )
                                            val actions = listOf(ChannelAction.Storage.StoreState(nextState), ChannelAction.Message.Send(closingSigned))
                                            Pair(nextState, actions)
                                        }
                                    }
                                    else -> {
                                        val (closingTx, closingSigned) = run {
                                            // if we are not the initiator and we were waiting for them to send their first closing_signed, we compute our firstClosingFee, otherwise we use the last one we sent
                                            val localClosingFees = Helpers.Closing.firstClosingFee(commitments, localShutdown.scriptPubKey, remoteShutdown.scriptPubKey, ourFeeRange)
                                            val nextPreferredFee = Helpers.Closing.nextClosingFee(lastLocalClosingSigned?.feeSatoshis ?: localClosingFees.preferred, remoteClosingFee)
                                            Helpers.Closing.makeClosingTx(keyManager, commitments, localShutdown.scriptPubKey.toByteArray(), remoteShutdown.scriptPubKey.toByteArray(), localClosingFees.copy(preferred = nextPreferredFee))
                                        }
                                        when {
                                            lastLocalClosingSigned?.feeSatoshis == closingSigned.feeSatoshis -> {
                                                // next computed fee is the same than the one we previously sent (probably because of rounding)
                                                logger.info { "c:$channelId accepting their closing fee=$remoteClosingFee, publishing closing tx: closingTxId=${signedClosingTx.tx.txid}" }
                                                completeMutualClose(signedClosingTx, null)
                                            }
                                            closingSigned.feeSatoshis == remoteClosingFee -> {
                                                logger.info { "c:$channelId we have converged, publishing closing tx: closingTxId=${signedClosingTx.tx.txid}" }
                                                completeMutualClose(signedClosingTx, closingSigned)
                                            }
                                            else -> {
                                                logger.info { "c:$channelId proposing closing fee=${closingSigned.feeSatoshis}" }
                                                val closingProposed1 = closingTxProposed.updated(
                                                    closingTxProposed.lastIndex,
                                                    closingTxProposed.last() + listOf(ClosingTxProposed(closingTx, closingSigned))
                                                )
                                                val nextState = this@Negotiating.copy(
                                                    commitments = commitments.copy(remoteChannelData = cmd.message.channelData),
                                                    closingTxProposed = closingProposed1,
                                                    bestUnpublishedClosingTx = signedClosingTx
                                                )
                                                val actions = listOf(ChannelAction.Storage.StoreState(nextState), ChannelAction.Message.Send(closingSigned))
                                                Pair(nextState, actions)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            cmd is ChannelCommand.MessageReceived && cmd.message is Error -> handleRemoteError(cmd.message)
            cmd is ChannelCommand.WatchReceived -> when (val watch = cmd.watch) {
                is WatchEventSpent -> when {
                    watch.event is BITCOIN_FUNDING_SPENT && closingTxProposed.flatten().any { it.unsignedTx.tx.txid == watch.tx.txid } -> {
                        // they can publish a closing tx with any sig we sent them, even if we are not done negotiating
                        logger.info { "c:$channelId closing tx published: closingTxId=${watch.tx.txid}" }
                        val closingTx = getMutualClosePublished(watch.tx)
                        val nextState = Closing(
                            commitments,
                            fundingTx = null,
                            waitingSinceBlock = currentBlockHeight.toLong(),
                            alternativeCommitments = listOf(),
                            mutualCloseProposed = closingTxProposed.flatten().map { it.unsignedTx },
                            mutualClosePublished = listOf(closingTx)
                        )
                        val actions = listOf(
                            ChannelAction.Storage.StoreState(nextState),
                            ChannelAction.Blockchain.PublishTx(watch.tx),
                            ChannelAction.Blockchain.SendWatch(WatchConfirmed(channelId, watch.tx, staticParams.nodeParams.minDepthBlocks.toLong(), BITCOIN_TX_CONFIRMED(watch.tx)))
                        )
                        Pair(nextState, actions)
                    }
                    watch.tx.txid == commitments.remoteCommit.txid -> handleRemoteSpentCurrent(watch.tx)
                    watch.tx.txid == commitments.remoteNextCommitInfo.left?.nextRemoteCommit?.txid -> handleRemoteSpentNext(watch.tx)
                    else -> handleRemoteSpentOther(watch.tx)
                }
                else -> unhandled(cmd)
            }
            cmd is ChannelCommand.CheckHtlcTimeout -> checkHtlcTimeout()
            cmd is ChannelCommand.Disconnected -> Pair(Offline(this@Negotiating), listOf())
            cmd is ChannelCommand.ExecuteCommand && cmd.command is CMD_ADD_HTLC -> handleCommandError(cmd.command, ChannelUnavailable(channelId))
            cmd is ChannelCommand.ExecuteCommand && cmd.command is CMD_CLOSE -> handleCommandError(cmd.command, ClosingAlreadyInProgress(channelId))
            else -> unhandled(cmd)
        }
    }

    /** Return full information about a known closing tx. */
    internal fun getMutualClosePublished(tx: Transaction): ClosingTx {
        // they can publish a closing tx with any sig we sent them, even if we are not done negotiating
        // they added their signature, so we use their version of the transaction
        return closingTxProposed.flatten().first { it.unsignedTx.tx.txid == tx.txid }.unsignedTx.copy(tx = tx)
    }

    private fun ChannelContext.completeMutualClose(signedClosingTx: ClosingTx, closingSigned: ClosingSigned?): Pair<ChannelState, List<ChannelAction>> {
        val nextState = Closing(
            commitments,
            fundingTx = null,
            waitingSinceBlock = currentBlockHeight.toLong(),
            alternativeCommitments = listOf(),
            mutualCloseProposed = closingTxProposed.flatten().map { it.unsignedTx },
            mutualClosePublished = listOf(signedClosingTx)
        )
        val actions = buildList {
            add(ChannelAction.Storage.StoreState(nextState))
            closingSigned?.let { add(ChannelAction.Message.Send(it)) }
            add(ChannelAction.Blockchain.PublishTx(signedClosingTx.tx))
            add(ChannelAction.Blockchain.SendWatch(WatchConfirmed(channelId, signedClosingTx.tx, staticParams.nodeParams.minDepthBlocks.toLong(), BITCOIN_TX_CONFIRMED(signedClosingTx.tx))))
        }
        return Pair(nextState, actions)
    }

    override fun ChannelContext.handleLocalError(cmd: ChannelCommand, t: Throwable): Pair<ChannelState, List<ChannelAction>> {
        logger.error(t) { "c:$channelId error on event ${cmd::class} in state ${this::class}" }
        val error = Error(channelId, t.message)
        return when {
            nothingAtStake() -> Pair(Aborted, listOf(ChannelAction.Message.Send(error)))
            bestUnpublishedClosingTx != null -> {
                // if we were in the process of closing and already received a closing sig from the counterparty, it's always better to use that
                val nextState = Closing(
                    commitments,
                    fundingTx = null,
                    waitingSinceBlock = currentBlockHeight.toLong(),
                    alternativeCommitments = listOf(),
                    mutualCloseProposed = closingTxProposed.flatten().map { it.unsignedTx } + listOf(bestUnpublishedClosingTx),
                    mutualClosePublished = listOf(bestUnpublishedClosingTx)
                )
                val actions = listOf(
                    ChannelAction.Storage.StoreState(nextState),
                    ChannelAction.Blockchain.PublishTx(bestUnpublishedClosingTx.tx),
                    ChannelAction.Blockchain.SendWatch(WatchConfirmed(channelId, bestUnpublishedClosingTx.tx, staticParams.nodeParams.minDepthBlocks.toLong(), BITCOIN_TX_CONFIRMED(bestUnpublishedClosingTx.tx)))
                )
                Pair(nextState, actions)
            }
            else -> spendLocalCurrent().run { copy(second = second + ChannelAction.Message.Send(error)) }
        }
    }
}
