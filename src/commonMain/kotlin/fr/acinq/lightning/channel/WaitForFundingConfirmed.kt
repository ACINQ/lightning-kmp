package fr.acinq.lightning.channel

import fr.acinq.bitcoin.BlockHeader
import fr.acinq.lightning.MilliSatoshi
import fr.acinq.lightning.ShortChannelId
import fr.acinq.lightning.blockchain.*
import fr.acinq.lightning.blockchain.electrum.WalletState
import fr.acinq.lightning.blockchain.fee.OnChainFeerates
import fr.acinq.lightning.utils.Either
import fr.acinq.lightning.wire.*

/** We wait for the channel funding transaction to confirm. */
data class WaitForFundingConfirmed(
    override val staticParams: StaticParams,
    override val currentTip: Pair<Int, BlockHeader>,
    override val currentOnChainFeerates: OnChainFeerates,
    override val commitments: Commitments,
    val wallet: WalletState,
    val fundingParams: InteractiveTxParams,
    val pushAmount: MilliSatoshi,
    val fundingTx: SignedSharedTransaction,
    val previousFundingTxs: List<Pair<SignedSharedTransaction, Commitments>>,
    val waitingSinceBlock: Long, // how many blocks have we been waiting for the funding tx to confirm
    val deferred: FundingLocked?,
    // We can have at most one ongoing RBF attempt.
    // It doesn't need to be persisted: if we disconnect before signing, the rbf attempt is discarded.
    val rbfStatus: RbfStatus = RbfStatus.None
) : ChannelStateWithCommitments() {
    override fun updateCommitments(input: Commitments): ChannelStateWithCommitments = this.copy(commitments = input)

    override fun processInternal(event: ChannelEvent): Pair<ChannelState, List<ChannelAction>> {
        return when {
            event is ChannelEvent.MessageReceived && event.message is TxSignatures -> when (fundingTx) {
                is PartiallySignedSharedTransaction -> when (val fullySignedTx = fundingTx.addRemoteSigs(event.message)) {
                    null -> {
                        logger.warning { "c:$channelId received invalid remote funding signatures for txId=${event.message.txId}" }
                        // The funding transaction may still confirm (since our peer should be able to generate valid signatures), so we cannot close the channel yet.
                        Pair(this, listOf(ChannelAction.Message.Send(Warning(channelId, InvalidFundingSignature(channelId, event.message.txId).message))))
                    }
                    else -> {
                        logger.info { "c:$channelId received remote funding signatures, publishing txId=${fullySignedTx.signedTx.txid}" }
                        val nextState = this.copy(fundingTx = fullySignedTx)
                        val actions = buildList {
                            // If we haven't sent our signatures yet, we do it now.
                            if (!fundingParams.shouldSignFirst(commitments.localParams.nodeId, commitments.remoteParams.nodeId)) add(ChannelAction.Message.Send(fullySignedTx.localSigs))
                            add(ChannelAction.Blockchain.PublishTx(fullySignedTx.signedTx))
                            add(ChannelAction.Storage.StoreState(nextState))
                        }
                        Pair(nextState, actions)
                    }
                }
                is FullySignedSharedTransaction -> when (rbfStatus) {
                    RbfStatus.None -> {
                        logger.info { "c:$channelId ignoring duplicate remote funding signatures" }
                        Pair(this, listOf())
                    }
                    else -> {
                        logger.warning { "c:$channelId received rbf tx_signatures before commit_sig, aborting" }
                        Pair(this.copy(rbfStatus = RbfStatus.None), listOf(ChannelAction.Message.Send(TxAbort(channelId, UnexpectedFundingSignatures(channelId).message))))
                    }
                }
            }
            event is ChannelEvent.MessageReceived && event.message is TxInitRbf -> {
                if (isInitiator) {
                    logger.info { "c:$channelId rejecting tx_init_rbf, we're the initiator, not them!" }
                    Pair(this, listOf(ChannelAction.Message.Send(TxAbort(channelId, InvalidRbfNonInitiator(channelId).message))))
                } else {
                    val minNextFeerate = fundingParams.minNextFeerate
                    when {
                        rbfStatus != RbfStatus.None -> {
                            logger.info { "c:$channelId rejecting rbf attempt: the current rbf attempt must be completed or aborted first" }
                            Pair(this, listOf(ChannelAction.Message.Send(TxAbort(channelId, InvalidRbfAlreadyInProgress(channelId).message))))
                        }
                        event.message.feerate < minNextFeerate -> {
                            logger.info { "c:$channelId rejecting rbf attempt: the new feerate must be at least $minNextFeerate (proposed=${event.message.feerate})" }
                            Pair(this, listOf(ChannelAction.Message.Send(TxAbort(channelId, InvalidRbfFeerate(channelId, event.message.feerate, minNextFeerate).message))))
                        }
                        else -> {
                            logger.info { "c:$channelId our peer wants to raise the feerate of the funding transaction (previous=${fundingParams.targetFeerate} target=${event.message.feerate})" }
                            val fundingParams = InteractiveTxParams(
                                channelId,
                                isInitiator,
                                fundingParams.localAmount, // we don't change our funding contribution
                                event.message.fundingContribution,
                                fundingParams.fundingPubkeyScript,
                                event.message.lockTime,
                                fundingParams.dustLimit,
                                event.message.feerate
                            )
                            when (val contributions = FundingContributions.create(fundingParams, wallet.spendable())) {
                                is Either.Left -> {
                                    logger.warning { "c:$channelId error creating funding contributions: ${contributions.value}" }
                                    Pair(this.copy(rbfStatus = RbfStatus.None), listOf(ChannelAction.Message.Send(TxAbort(channelId, ChannelFundingError(channelId).message))))
                                }
                                is Either.Right -> {
                                    val session = InteractiveTxSession(fundingParams, contributions.value, previousFundingTxs.map { it.first })
                                    val nextState = this.copy(rbfStatus = RbfStatus.InProgress(session))
                                    Pair(nextState, listOf(ChannelAction.Message.Send(TxAckRbf(channelId, fundingParams.localAmount))))
                                }
                            }
                        }
                    }
                }
            }
            event is ChannelEvent.MessageReceived && event.message is TxAckRbf -> when (rbfStatus) {
                is RbfStatus.RbfRequested -> {
                    logger.info { "c:$channelId our peer accepted our rbf attempt and will contribute ${event.message.fundingContribution} to the funding transaction" }
                    val fundingParams = InteractiveTxParams(
                        channelId,
                        isInitiator,
                        rbfStatus.command.fundingAmount,
                        event.message.fundingContribution,
                        fundingParams.fundingPubkeyScript,
                        rbfStatus.command.lockTime,
                        fundingParams.dustLimit,
                        rbfStatus.command.targetFeerate
                    )
                    when (val contributions = FundingContributions.create(fundingParams, rbfStatus.command.wallet.spendable())) {
                        is Either.Left -> {
                            logger.warning { "c:$channelId error creating funding contributions: ${contributions.value}" }
                            Pair(this.copy(rbfStatus = RbfStatus.None), listOf(ChannelAction.Message.Send(TxAbort(channelId, ChannelFundingError(channelId).message))))
                        }
                        is Either.Right -> {
                            val (session, action) = InteractiveTxSession(fundingParams, contributions.value, previousFundingTxs.map { it.first }).send()
                            when (action) {
                                is InteractiveTxSessionAction.SendMessage -> {
                                    val nextState = this.copy(rbfStatus = RbfStatus.InProgress(session), wallet = rbfStatus.command.wallet)
                                    Pair(nextState, listOf(ChannelAction.Message.Send(action.msg)))
                                }
                                else -> {
                                    logger.warning { "c:$channelId could not start rbf session: $action" }
                                    Pair(this.copy(rbfStatus = RbfStatus.None), listOf(ChannelAction.Message.Send(TxAbort(channelId, ChannelFundingError(channelId).message))))
                                }
                            }
                        }
                    }
                }
                else -> {
                    logger.info { "c:$channelId ignoring unexpected tx_ack_rbf" }
                    Pair(this, listOf(ChannelAction.Message.Send(Warning(channelId, UnexpectedInteractiveTxMessage(channelId, event.message).message))))
                }
            }
            event is ChannelEvent.MessageReceived && event.message is InteractiveTxConstructionMessage -> when (rbfStatus) {
                is RbfStatus.InProgress -> {
                    val (rbfSession1, interactiveTxAction) = rbfStatus.rbfSession.receive(event.message)
                    when (interactiveTxAction) {
                        is InteractiveTxSessionAction.SendMessage -> Pair(this.copy(rbfStatus = rbfStatus.copy(rbfSession1)), listOf(ChannelAction.Message.Send(interactiveTxAction.msg)))
                        is InteractiveTxSessionAction.SignSharedTx -> {
                            val firstCommitTxRes = Helpers.Funding.makeFirstCommitTxs(
                                keyManager,
                                channelId,
                                commitments.localParams,
                                commitments.remoteParams,
                                rbfSession1.fundingParams.localAmount,
                                rbfSession1.fundingParams.remoteAmount,
                                pushAmount,
                                commitments.localCommit.spec.feerate,
                                interactiveTxAction.sharedTx.buildUnsignedTx().hash,
                                interactiveTxAction.sharedOutputIndex,
                                commitments.remoteCommit.remotePerCommitmentPoint
                            )
                            when (firstCommitTxRes) {
                                is Either.Left -> {
                                    logger.error(firstCommitTxRes.value) { "c:$channelId cannot create rbf commit tx" }
                                    Pair(this.copy(rbfStatus = RbfStatus.None), listOf(ChannelAction.Message.Send(TxAbort(channelId, ChannelFundingError(channelId).message))))
                                }
                                is Either.Right -> {
                                    val firstCommitTx = firstCommitTxRes.value
                                    val localSigOfRemoteTx = keyManager.sign(firstCommitTx.remoteCommitTx, commitments.localParams.channelKeys.fundingPrivateKey)
                                    val commitSig = CommitSig(channelId, localSigOfRemoteTx, listOf())
                                    val actions = buildList {
                                        interactiveTxAction.txComplete?.let { add(ChannelAction.Message.Send(it)) }
                                        add(ChannelAction.Message.Send(commitSig))
                                    }
                                    Pair(this.copy(rbfStatus = RbfStatus.WaitForCommitSig(rbfSession1.fundingParams, interactiveTxAction.sharedTx, firstCommitTx)), actions)
                                }
                            }
                        }
                        is InteractiveTxSessionAction.RemoteFailure -> {
                            logger.warning { "c:$channelId rbf attempt failed: $interactiveTxAction" }
                            Pair(this.copy(rbfStatus = RbfStatus.None), listOf(ChannelAction.Message.Send(TxAbort(channelId, ChannelFundingError(channelId).message))))
                        }
                    }
                }
                else -> {
                    logger.info { "c:$channelId ignoring unexpected interactive-tx message: ${event.message::class}" }
                    Pair(this, listOf(ChannelAction.Message.Send(Warning(channelId, UnexpectedInteractiveTxMessage(channelId, event.message).message))))
                }
            }
            event is ChannelEvent.MessageReceived && event.message is CommitSig -> when (rbfStatus) {
                is RbfStatus.WaitForCommitSig -> {
                    val firstCommitmentsRes = Helpers.Funding.receiveFirstCommit(
                        keyManager, commitments.localParams, commitments.remoteParams,
                        rbfStatus.fundingTx, wallet,
                        rbfStatus.commitTx, event.message,
                        commitments.channelConfig, commitments.channelFeatures, commitments.channelFlags, commitments.remoteCommit.remotePerCommitmentPoint
                    )
                    when (firstCommitmentsRes) {
                        Helpers.Funding.InvalidRemoteCommitSig -> {
                            logger.warning { "c:$channelId rbf attempt failed: invalid commit_sig" }
                            Pair(this.copy(rbfStatus = RbfStatus.None), listOf(ChannelAction.Message.Send(TxAbort(channelId, InvalidCommitmentSignature(channelId, rbfStatus.commitTx.localCommitTx.tx.txid).message))))
                        }
                        Helpers.Funding.FundingSigFailure -> {
                            logger.warning { "c:$channelId could not sign rbf funding tx" }
                            Pair(this.copy(rbfStatus = RbfStatus.None), listOf(ChannelAction.Message.Send(TxAbort(channelId, ChannelFundingError(channelId).message))))
                        }
                        is Helpers.Funding.FirstCommitments -> {
                            val (signedFundingTx, commitments1) = firstCommitmentsRes
                            logger.info { "c:$channelId rbf funding tx created with txId=${commitments1.fundingTxId}. ${signedFundingTx.tx.localInputs.size} local inputs, ${signedFundingTx.tx.remoteInputs.size} remote inputs, ${signedFundingTx.tx.localOutputs.size} local outputs and ${signedFundingTx.tx.remoteOutputs.size} remote outputs" }
                            val fundingMinDepth = Helpers.minDepthForFunding(staticParams.nodeParams, rbfStatus.fundingParams.fundingAmount)
                            logger.info { "c:$channelId will wait for $fundingMinDepth confirmations" }
                            val watchConfirmed = WatchConfirmed(channelId, commitments1.fundingTxId, commitments1.commitInput.txOut.publicKeyScript, fundingMinDepth.toLong(), BITCOIN_FUNDING_DEPTHOK)
                            val nextState = WaitForFundingConfirmed(
                                staticParams, currentTip, currentOnChainFeerates,
                                commitments1,
                                wallet,
                                rbfStatus.fundingParams,
                                pushAmount,
                                signedFundingTx,
                                listOf(Pair(fundingTx, commitments)) + previousFundingTxs,
                                waitingSinceBlock,
                                deferred,
                                RbfStatus.None
                            )
                            val actions = buildList {
                                add(ChannelAction.Blockchain.SendWatch(watchConfirmed))
                                add(ChannelAction.Storage.StoreState(nextState))
                                if (fundingParams.shouldSignFirst(commitments.localParams.nodeId, commitments.remoteParams.nodeId)) add(ChannelAction.Message.Send(signedFundingTx.localSigs))
                            }
                            Pair(nextState, actions)
                        }
                    }
                }
                else -> {
                    logger.info { "c:$channelId ignoring unexpected commit_sig" }
                    Pair(this, listOf(ChannelAction.Message.Send(Warning(channelId, UnexpectedCommitSig(channelId).message))))
                }
            }
            event is ChannelEvent.MessageReceived && event.message is TxAbort -> when (rbfStatus) {
                RbfStatus.None -> {
                    logger.info { "c:$channelId ignoring unexpected tx_abort message" }
                    Pair(this, listOf(ChannelAction.Message.Send(Warning(channelId, UnexpectedInteractiveTxMessage(channelId, event.message).message))))
                }
                else -> {
                    logger.info { "c:$channelId our peer aborted the rbf attempt: ascii='${event.message.toAscii()}' bin=${event.message.data.toHex()}" }
                    Pair(this.copy(rbfStatus = RbfStatus.None), listOf())
                }
            }
            event is ChannelEvent.MessageReceived && event.message is FundingLocked -> Pair(this.copy(deferred = event.message), listOf())
            event is ChannelEvent.MessageReceived && event.message is Error -> handleRemoteError(event.message)
            event is ChannelEvent.WatchReceived && event.watch is WatchEventConfirmed -> {
                val allFundingTxs = listOf(Pair(fundingTx, commitments)) + previousFundingTxs
                when (val confirmedTx = allFundingTxs.find { it.second.fundingTxId == event.watch.tx.txid }) {
                    null -> {
                        logger.error { "c:$channelId internal error: the funding tx that confirmed doesn't match any of our funding txs: ${event.watch.tx}" }
                        Pair(this, listOf())
                    }
                    else -> {
                        logger.info { "c:$channelId was confirmed at blockHeight=${event.watch.blockHeight} txIndex=${event.watch.txIndex} with funding txid=${event.watch.tx.txid}" }
                        val (_, commitments) = confirmedTx
                        val watchSpent = WatchSpent(channelId, commitments.fundingTxId, commitments.commitInput.outPoint.index.toInt(), commitments.commitInput.txOut.publicKeyScript, BITCOIN_FUNDING_SPENT)
                        val nextPerCommitmentPoint = keyManager.commitmentPoint(commitments.localParams.channelKeys.shaSeed, 1)
                        val fundingLocked = FundingLocked(commitments.channelId, nextPerCommitmentPoint)
                        // this is the temporary channel id that we will use in our channel_update message, the goal is to be able to use our channel
                        // as soon as it reaches NORMAL state, and before it is announced on the network
                        // (this id might be updated when the funding tx gets deeply buried, if there was a reorg in the meantime)
                        val shortChannelId = ShortChannelId(event.watch.blockHeight, event.watch.txIndex, commitments.commitInput.outPoint.index.toInt())
                        val nextState = WaitForFundingLocked(staticParams, currentTip, currentOnChainFeerates, commitments, fundingParams, fundingTx, shortChannelId, fundingLocked)
                        val actions = buildList {
                            add(ChannelAction.Blockchain.SendWatch(watchSpent))
                            if (rbfStatus != RbfStatus.None) add(ChannelAction.Message.Send(TxAbort(channelId, InvalidRbfTxConfirmed(channelId, event.watch.tx.txid).message)))
                            add(ChannelAction.Message.Send(fundingLocked))
                            add(ChannelAction.Storage.StoreState(nextState))
                        }
                        if (deferred != null) {
                            logger.info { "c:$channelId funding_locked has already been received" }
                            val (nextState1, actions1) = nextState.process(ChannelEvent.MessageReceived(deferred))
                            Pair(nextState1, actions + actions1)
                        } else {
                            Pair(nextState, actions)
                        }
                    }
                }
            }
            event is ChannelEvent.ExecuteCommand && event.command is CMD_BUMP_FUNDING_FEE -> when {
                !fundingParams.isInitiator -> {
                    logger.warning { "c:$channelId cannot initiate rbf, we're not the initiator" }
                    Pair(this, listOf())
                }
                rbfStatus != RbfStatus.None -> {
                    logger.warning { "c:$channelId cannot initiate rbf, another one is already in progress" }
                    Pair(this, listOf())
                }
                else -> {
                    logger.info { "c:$channelId initiating rbf (current feerate = ${fundingParams.targetFeerate}, next feerate = ${event.command.targetFeerate})" }
                    val txInitRbf = TxInitRbf(channelId, event.command.lockTime, event.command.targetFeerate, event.command.fundingAmount)
                    Pair(this.copy(rbfStatus = RbfStatus.RbfRequested(event.command)), listOf(ChannelAction.Message.Send(txInitRbf)))
                }
            }
            event is ChannelEvent.ExecuteCommand && event.command is CMD_CLOSE -> Pair(this, listOf(ChannelAction.ProcessCmdRes.NotExecuted(event.command, CommandUnavailableInThisState(channelId, this::class.toString()))))
            event is ChannelEvent.ExecuteCommand && event.command is CMD_FORCECLOSE -> handleLocalError(event, ForcedLocalCommit(channelId))
            event is ChannelEvent.CheckHtlcTimeout -> Pair(this, listOf())
            event is ChannelEvent.NewBlock -> Pair(this.copy(currentTip = Pair(event.height, event.Header)), listOf())
            event is ChannelEvent.SetOnChainFeerates -> Pair(this.copy(currentOnChainFeerates = event.feerates), listOf())
            event is ChannelEvent.Disconnected -> Pair(Offline(this.copy(rbfStatus = RbfStatus.None)), listOf())
            else -> unhandled(event)
        }
    }

    override fun handleLocalError(event: ChannelEvent, t: Throwable): Pair<ChannelState, List<ChannelAction>> {
        logger.error(t) { "c:$channelId error on event ${event::class} in state ${this::class}" }
        val error = Error(channelId, t.message)
        return when {
            nothingAtStake() -> Pair(Aborted(staticParams, currentTip, currentOnChainFeerates), listOf(ChannelAction.Message.Send(error)))
            else -> spendLocalCurrent().run { copy(second = second + ChannelAction.Message.Send(error)) }
        }
    }

    companion object {
        sealed class RbfStatus {
            object None : RbfStatus()
            data class RbfRequested(val command: CMD_BUMP_FUNDING_FEE) : RbfStatus()
            data class InProgress(val rbfSession: InteractiveTxSession) : RbfStatus()
            data class WaitForCommitSig(val fundingParams: InteractiveTxParams, val fundingTx: SharedTransaction, val commitTx: Helpers.Funding.FirstCommitTx) : RbfStatus()
        }
    }
}
