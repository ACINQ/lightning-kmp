package fr.acinq.lightning.channel

import fr.acinq.lightning.MilliSatoshi
import fr.acinq.lightning.ShortChannelId
import fr.acinq.lightning.blockchain.*
import fr.acinq.lightning.utils.Either
import fr.acinq.lightning.utils.toMilliSatoshi
import fr.acinq.lightning.wire.*

/** We wait for the channel funding transaction to confirm. */
data class WaitForFundingConfirmed(
    override val commitments: Commitments,
    val fundingParams: InteractiveTxParams,
    val localPushAmount: MilliSatoshi,
    val remotePushAmount: MilliSatoshi,
    val fundingTx: SignedSharedTransaction,
    val previousFundingTxs: List<Pair<SignedSharedTransaction, Commitments>>,
    val waitingSinceBlock: Long, // how many blocks have we been waiting for the funding tx to confirm
    val deferred: ChannelReady?,
    // We can have at most one ongoing RBF attempt.
    // It doesn't need to be persisted: if we disconnect before signing, the rbf attempt is discarded.
    val rbfStatus: RbfStatus = RbfStatus.None
) : PersistedChannelState() {
    override fun updateCommitments(input: Commitments): ChannelStateWithCommitments = this.copy(commitments = input)

    override fun ChannelContext.processInternal(cmd: ChannelCommand): Pair<ChannelState, List<ChannelAction>> {
        return when {
            cmd is ChannelCommand.MessageReceived && cmd.message is TxSignatures -> when (fundingTx) {
                is PartiallySignedSharedTransaction -> when (val fullySignedTx = fundingTx.addRemoteSigs(cmd.message)) {
                    null -> {
                        logger.warning { "received invalid remote funding signatures for txId=${cmd.message.txId}" }
                        // The funding transaction may still confirm (since our peer should be able to generate valid signatures), so we cannot close the channel yet.
                        Pair(this@WaitForFundingConfirmed, listOf(ChannelAction.Message.Send(Warning(channelId, InvalidFundingSignature(channelId, cmd.message.txId).message))))
                    }
                    else -> {
                        logger.info { "received remote funding signatures, publishing txId=${fullySignedTx.signedTx.txid}" }
                        val nextState = this@WaitForFundingConfirmed.copy(fundingTx = fullySignedTx)
                        val actions = buildList {
                            add(ChannelAction.Blockchain.PublishTx(fullySignedTx.signedTx))
                            add(ChannelAction.Storage.StoreState(nextState))
                        }
                        Pair(nextState, actions)
                    }
                }
                is FullySignedSharedTransaction -> when (rbfStatus) {
                    RbfStatus.None -> {
                        logger.info { "ignoring duplicate remote funding signatures" }
                        Pair(this@WaitForFundingConfirmed, listOf())
                    }
                    else -> {
                        logger.warning { "received rbf tx_signatures before commit_sig, aborting" }
                        Pair(this@WaitForFundingConfirmed.copy(rbfStatus = RbfStatus.RbfAborted), listOf(ChannelAction.Message.Send(TxAbort(channelId, UnexpectedFundingSignatures(channelId).message))))
                    }
                }
            }
            cmd is ChannelCommand.MessageReceived && cmd.message is TxInitRbf -> {
                if (isInitiator) {
                    logger.info { "rejecting tx_init_rbf, we're the initiator, not them!" }
                    Pair(this@WaitForFundingConfirmed, listOf(ChannelAction.Message.Send(Error(channelId, InvalidRbfNonInitiator(channelId).message))))
                } else {
                    val minNextFeerate = fundingParams.minNextFeerate
                    when (rbfStatus) {
                        RbfStatus.None -> {
                            if (cmd.message.feerate < minNextFeerate) {
                                logger.info { "rejecting rbf attempt: the new feerate must be at least $minNextFeerate (proposed=${cmd.message.feerate})" }
                                Pair(this@WaitForFundingConfirmed.copy(rbfStatus = RbfStatus.RbfAborted), listOf(ChannelAction.Message.Send(TxAbort(channelId, InvalidRbfFeerate(channelId, cmd.message.feerate, minNextFeerate).message))))
                            } else if (cmd.message.fundingContribution.toMilliSatoshi() < remotePushAmount) {
                                logger.info { "rejecting rbf attempt: invalid amount pushed (fundingAmount=${cmd.message.fundingContribution}, pushAmount=$remotePushAmount)" }
                                Pair(this@WaitForFundingConfirmed.copy(rbfStatus = RbfStatus.RbfAborted), listOf(ChannelAction.Message.Send(TxAbort(channelId, InvalidPushAmount(channelId, remotePushAmount, cmd.message.fundingContribution.toMilliSatoshi()).message))))
                            } else {
                                logger.info { "our peer wants to raise the feerate of the funding transaction (previous=${fundingParams.targetFeerate} target=${cmd.message.feerate})" }
                                val fundingParams = InteractiveTxParams(
                                    channelId,
                                    isInitiator,
                                    fundingParams.localAmount, // we don't change our funding contribution
                                    cmd.message.fundingContribution,
                                    fundingParams.fundingPubkeyScript,
                                    cmd.message.lockTime,
                                    fundingParams.dustLimit,
                                    cmd.message.feerate
                                )
                                val toSend = buildList<Either<TxAddInput, TxAddOutput>> {
                                    addAll(fundingTx.tx.localInputs.map { Either.Left(it) })
                                    addAll(fundingTx.tx.localOutputs.map { Either.Right(it) })
                                }
                                val session = InteractiveTxSession(fundingParams, toSend, previousFundingTxs.map { it.first })
                                val nextState = this@WaitForFundingConfirmed.copy(rbfStatus = RbfStatus.InProgress(session))
                                Pair(nextState, listOf(ChannelAction.Message.Send(TxAckRbf(channelId, fundingParams.localAmount))))
                            }
                        }
                        RbfStatus.RbfAborted -> {
                            logger.info { "rejecting rbf attempt: our previous tx_abort was not acked" }
                            Pair(this@WaitForFundingConfirmed, listOf(ChannelAction.Message.Send(Warning(channelId, InvalidRbfTxAbortNotAcked(channelId).message))))
                        }
                        else -> {
                            logger.info { "rejecting rbf attempt: the current rbf attempt must be completed or aborted first" }
                            Pair(this@WaitForFundingConfirmed, listOf(ChannelAction.Message.Send(Warning(channelId, InvalidRbfAlreadyInProgress(channelId).message))))
                        }
                    }
                }
            }
            cmd is ChannelCommand.MessageReceived && cmd.message is TxAckRbf -> when (rbfStatus) {
                is RbfStatus.RbfRequested -> {
                    logger.info { "our peer accepted our rbf attempt and will contribute ${cmd.message.fundingContribution} to the funding transaction" }
                    val fundingParams = InteractiveTxParams(
                        channelId,
                        isInitiator,
                        rbfStatus.command.fundingAmount,
                        cmd.message.fundingContribution,
                        fundingParams.fundingPubkeyScript,
                        rbfStatus.command.lockTime,
                        fundingParams.dustLimit,
                        rbfStatus.command.targetFeerate
                    )
                    when (val contributions = FundingContributions.create(fundingParams, rbfStatus.command.wallet.confirmedUtxos)) {
                        is Either.Left -> {
                            logger.warning { "error creating funding contributions: ${contributions.value}" }
                            Pair(this@WaitForFundingConfirmed.copy(rbfStatus = RbfStatus.RbfAborted), listOf(ChannelAction.Message.Send(TxAbort(channelId, ChannelFundingError(channelId).message))))
                        }
                        is Either.Right -> {
                            val (session, action) = InteractiveTxSession(fundingParams, contributions.value, previousFundingTxs.map { it.first }).send()
                            when (action) {
                                is InteractiveTxSessionAction.SendMessage -> {
                                    val nextState = this@WaitForFundingConfirmed.copy(rbfStatus = RbfStatus.InProgress(session))
                                    Pair(nextState, listOf(ChannelAction.Message.Send(action.msg)))
                                }
                                else -> {
                                    logger.warning { "could not start rbf session: $action" }
                                    Pair(this@WaitForFundingConfirmed.copy(rbfStatus = RbfStatus.RbfAborted), listOf(ChannelAction.Message.Send(TxAbort(channelId, ChannelFundingError(channelId).message))))
                                }
                            }
                        }
                    }
                }
                else -> {
                    logger.info { "ignoring unexpected tx_ack_rbf" }
                    Pair(this@WaitForFundingConfirmed, listOf(ChannelAction.Message.Send(Warning(channelId, UnexpectedInteractiveTxMessage(channelId, cmd.message).message))))
                }
            }
            cmd is ChannelCommand.MessageReceived && cmd.message is InteractiveTxConstructionMessage -> when (rbfStatus) {
                is RbfStatus.InProgress -> {
                    val (rbfSession1, interactiveTxAction) = rbfStatus.rbfSession.receive(cmd.message)
                    when (interactiveTxAction) {
                        is InteractiveTxSessionAction.SendMessage -> Pair(this@WaitForFundingConfirmed.copy(rbfStatus = rbfStatus.copy(rbfSession1)), listOf(ChannelAction.Message.Send(interactiveTxAction.msg)))
                        is InteractiveTxSessionAction.SignSharedTx -> {
                            val firstCommitTxRes = Helpers.Funding.makeFirstCommitTxs(
                                keyManager,
                                channelId,
                                commitments.localParams,
                                commitments.remoteParams,
                                rbfSession1.fundingParams.localAmount,
                                rbfSession1.fundingParams.remoteAmount,
                                localPushAmount,
                                remotePushAmount,
                                commitments.localCommit.spec.feerate,
                                interactiveTxAction.sharedTx.buildUnsignedTx().hash,
                                interactiveTxAction.sharedOutputIndex,
                                commitments.remoteCommit.remotePerCommitmentPoint
                            )
                            when (firstCommitTxRes) {
                                is Either.Left -> {
                                    logger.error(firstCommitTxRes.value) { "cannot create rbf commit tx" }
                                    Pair(this@WaitForFundingConfirmed.copy(rbfStatus = RbfStatus.RbfAborted), listOf(ChannelAction.Message.Send(TxAbort(channelId, ChannelFundingError(channelId).message))))
                                }
                                is Either.Right -> {
                                    val firstCommitTx = firstCommitTxRes.value
                                    val localSigOfRemoteTx = keyManager.sign(firstCommitTx.remoteCommitTx, commitments.localParams.channelKeys(keyManager).fundingPrivateKey)
                                    val commitSig = CommitSig(channelId, localSigOfRemoteTx, listOf())
                                    val actions = buildList {
                                        interactiveTxAction.txComplete?.let { add(ChannelAction.Message.Send(it)) }
                                        add(ChannelAction.Message.Send(commitSig))
                                    }
                                    Pair(this@WaitForFundingConfirmed.copy(rbfStatus = RbfStatus.WaitForCommitSig(rbfSession1.fundingParams, interactiveTxAction.sharedTx, firstCommitTx)), actions)
                                }
                            }
                        }
                        is InteractiveTxSessionAction.RemoteFailure -> {
                            logger.warning { "rbf attempt failed: $interactiveTxAction" }
                            Pair(this@WaitForFundingConfirmed.copy(rbfStatus = RbfStatus.RbfAborted), listOf(ChannelAction.Message.Send(TxAbort(channelId, ChannelFundingError(channelId).message))))
                        }
                    }
                }
                else -> {
                    logger.info { "ignoring unexpected interactive-tx message: ${cmd.message::class}" }
                    Pair(this@WaitForFundingConfirmed, listOf(ChannelAction.Message.Send(Warning(channelId, UnexpectedInteractiveTxMessage(channelId, cmd.message).message))))
                }
            }
            cmd is ChannelCommand.MessageReceived && cmd.message is CommitSig -> when (rbfStatus) {
                is RbfStatus.WaitForCommitSig -> {
                    val firstCommitmentsRes = Helpers.Funding.receiveFirstCommit(
                        keyManager, commitments.localParams, commitments.remoteParams,
                        rbfStatus.fundingTx,
                        rbfStatus.commitTx, cmd.message,
                        commitments.channelConfig, commitments.channelFeatures, commitments.channelFlags,
                        commitments.remoteCommit.remotePerCommitmentPoint, commitments.remoteNextCommitInfo.right!!
                    )
                    when (firstCommitmentsRes) {
                        Helpers.Funding.InvalidRemoteCommitSig -> {
                            logger.warning { "rbf attempt failed: invalid commit_sig" }
                            Pair(this@WaitForFundingConfirmed.copy(rbfStatus = RbfStatus.RbfAborted), listOf(ChannelAction.Message.Send(TxAbort(channelId, InvalidCommitmentSignature(channelId, rbfStatus.commitTx.localCommitTx.tx.txid).message))))
                        }
                        Helpers.Funding.FundingSigFailure -> {
                            logger.warning { "could not sign rbf funding tx" }
                            Pair(this@WaitForFundingConfirmed.copy(rbfStatus = RbfStatus.RbfAborted), listOf(ChannelAction.Message.Send(TxAbort(channelId, ChannelFundingError(channelId).message))))
                        }
                        is Helpers.Funding.FirstCommitments -> {
                            val (signedFundingTx, commitments1) = firstCommitmentsRes
                            logger.info { "rbf funding tx created with txId=${commitments1.fundingTxId}. ${signedFundingTx.tx.localInputs.size} local inputs, ${signedFundingTx.tx.remoteInputs.size} remote inputs, ${signedFundingTx.tx.localOutputs.size} local outputs and ${signedFundingTx.tx.remoteOutputs.size} remote outputs" }
                            val fundingMinDepth = Helpers.minDepthForFunding(staticParams.nodeParams, rbfStatus.fundingParams.fundingAmount)
                            logger.info { "will wait for $fundingMinDepth confirmations" }
                            val watchConfirmed = WatchConfirmed(channelId, commitments1.fundingTxId, commitments1.commitInput.txOut.publicKeyScript, fundingMinDepth.toLong(), BITCOIN_FUNDING_DEPTHOK)
                            val nextState = WaitForFundingConfirmed(
                                commitments1,
                                rbfStatus.fundingParams,
                                localPushAmount,
                                remotePushAmount,
                                signedFundingTx,
                                listOf(Pair(fundingTx, commitments)) + previousFundingTxs,
                                waitingSinceBlock,
                                deferred,
                                RbfStatus.None
                            )
                            val actions = buildList {
                                add(ChannelAction.Blockchain.SendWatch(watchConfirmed))
                                add(ChannelAction.Storage.StoreState(nextState))
                                add(ChannelAction.Message.Send(signedFundingTx.localSigs))
                            }
                            Pair(nextState, actions)
                        }
                    }
                }
                else -> {
                    logger.info { "ignoring unexpected commit_sig" }
                    Pair(this@WaitForFundingConfirmed, listOf(ChannelAction.Message.Send(Warning(channelId, UnexpectedCommitSig(channelId).message))))
                }
            }
            cmd is ChannelCommand.MessageReceived && cmd.message is TxAbort -> when (rbfStatus) {
                RbfStatus.None -> {
                    logger.info { "our peer wants to abort the funding attempt, but we've already negotiated a funding transaction: ascii='${cmd.message.toAscii()}' bin=${cmd.message.data.toHex()}" }
                    // We ack their tx_abort but we keep monitoring the funding transaction until it's confirmed or double-spent.
                    Pair(this@WaitForFundingConfirmed, listOf(ChannelAction.Message.Send(TxAbort(channelId, DualFundingAborted(channelId, "requested by remote").message))))
                }
                RbfStatus.RbfAborted -> {
                    logger.info { "our peer acked our previous tx_abort" }
                    Pair(this@WaitForFundingConfirmed.copy(rbfStatus = RbfStatus.None), listOf())
                }
                else -> {
                    logger.info { "our peer aborted the rbf attempt: ascii='${cmd.message.toAscii()}' bin=${cmd.message.data.toHex()}" }
                    Pair(this@WaitForFundingConfirmed.copy(rbfStatus = RbfStatus.None), listOf(ChannelAction.Message.Send(TxAbort(channelId, RbfAttemptAborted(channelId).message))))
                }
            }
            cmd is ChannelCommand.MessageReceived && cmd.message is ChannelReady -> Pair(this@WaitForFundingConfirmed.copy(deferred = cmd.message), listOf())
            cmd is ChannelCommand.MessageReceived && cmd.message is Error -> handleRemoteError(cmd.message)
            cmd is ChannelCommand.WatchReceived && cmd.watch is WatchEventConfirmed -> {
                val allFundingTxs = listOf(Pair(fundingTx, commitments)) + previousFundingTxs
                when (val confirmedTx = allFundingTxs.find { it.second.fundingTxId == cmd.watch.tx.txid }) {
                    null -> {
                        logger.error { "internal error: the funding tx that confirmed doesn't match any of our funding txs: ${cmd.watch.tx}" }
                        Pair(this@WaitForFundingConfirmed, listOf())
                    }
                    else -> {
                        logger.info { "was confirmed at blockHeight=${cmd.watch.blockHeight} txIndex=${cmd.watch.txIndex} with funding txid=${cmd.watch.tx.txid}" }
                        val (_, commitments) = confirmedTx
                        val watchSpent = WatchSpent(channelId, commitments.fundingTxId, commitments.commitInput.outPoint.index.toInt(), commitments.commitInput.txOut.publicKeyScript, BITCOIN_FUNDING_SPENT)
                        val nextPerCommitmentPoint = keyManager.commitmentPoint(commitments.localParams.channelKeys(keyManager).shaSeed, 1)
                        val channelReady = ChannelReady(commitments.channelId, nextPerCommitmentPoint, TlvStream(listOf(ChannelReadyTlv.ShortChannelIdTlv(ShortChannelId.peerId(staticParams.nodeParams.nodeId)))))
                        // this is the temporary channel id that we will use in our channel_update message, the goal is to be able to use our channel
                        // as soon as it reaches NORMAL state, and before it is announced on the network
                        // (this id might be updated when the funding tx gets deeply buried, if there was a reorg in the meantime)
                        val shortChannelId = ShortChannelId(cmd.watch.blockHeight, cmd.watch.txIndex, commitments.commitInput.outPoint.index.toInt())
                        val nextState = WaitForChannelReady(commitments, fundingParams, fundingTx, shortChannelId, channelReady)
                        val actions = buildList {
                            add(ChannelAction.Blockchain.SendWatch(watchSpent))
                            if (rbfStatus != RbfStatus.None) add(ChannelAction.Message.Send(TxAbort(channelId, InvalidRbfTxConfirmed(channelId, cmd.watch.tx.txid).message)))
                            add(ChannelAction.Message.Send(channelReady))
                            add(ChannelAction.Storage.StoreState(nextState))
                        }
                        if (deferred != null) {
                            logger.info { "funding_locked has already been received" }
                            val (nextState1, actions1) = nextState.run { process(ChannelCommand.MessageReceived(deferred)) }
                            Pair(nextState1, actions + actions1)
                        } else {
                            Pair(nextState, actions)
                        }
                    }
                }
            }
            cmd is ChannelCommand.ExecuteCommand && cmd.command is CMD_BUMP_FUNDING_FEE -> when {
                !fundingParams.isInitiator -> {
                    logger.warning { "cannot initiate rbf, we're not the initiator" }
                    Pair(this@WaitForFundingConfirmed, listOf())
                }
                rbfStatus != RbfStatus.None -> {
                    logger.warning { "cannot initiate rbf, another one is already in progress" }
                    Pair(this@WaitForFundingConfirmed, listOf())
                }
                else -> {
                    logger.info { "initiating rbf (current feerate = ${fundingParams.targetFeerate}, next feerate = ${cmd.command.targetFeerate})" }
                    val txInitRbf = TxInitRbf(channelId, cmd.command.lockTime, cmd.command.targetFeerate, cmd.command.fundingAmount)
                    Pair(this@WaitForFundingConfirmed.copy(rbfStatus = RbfStatus.RbfRequested(cmd.command)), listOf(ChannelAction.Message.Send(txInitRbf)))
                }
            }
            cmd is ChannelCommand.ExecuteCommand && cmd.command is CMD_CLOSE -> Pair(
                this@WaitForFundingConfirmed,
                listOf(ChannelAction.ProcessCmdRes.NotExecuted(cmd.command, CommandUnavailableInThisState(channelId, this::class.toString())))
            )
            cmd is ChannelCommand.ExecuteCommand && cmd.command is CMD_FORCECLOSE -> handleLocalError(cmd, ForcedLocalCommit(channelId))
            cmd is ChannelCommand.CheckHtlcTimeout -> Pair(this@WaitForFundingConfirmed, listOf())
            cmd is ChannelCommand.Disconnected -> Pair(Offline(this@WaitForFundingConfirmed.copy(rbfStatus = RbfStatus.None)), listOf())
            else -> unhandled(cmd)
        }
    }

    override fun ChannelContext.handleLocalError(cmd: ChannelCommand, t: Throwable): Pair<ChannelState, List<ChannelAction>> {
        logger.error(t) { "error on command ${cmd::class.simpleName} in state ${this@WaitForFundingConfirmed::class.simpleName}" }
        val error = Error(channelId, t.message)
        return when {
            nothingAtStake() -> Pair(Aborted, listOf(ChannelAction.Message.Send(error)))
            else -> spendLocalCurrent().run { copy(second = second + ChannelAction.Message.Send(error)) }
        }
    }

    companion object {
        sealed class RbfStatus {
            object None : RbfStatus()
            data class RbfRequested(val command: CMD_BUMP_FUNDING_FEE) : RbfStatus()
            data class InProgress(val rbfSession: InteractiveTxSession) : RbfStatus()
            data class WaitForCommitSig(val fundingParams: InteractiveTxParams, val fundingTx: SharedTransaction, val commitTx: Helpers.Funding.FirstCommitTx) : RbfStatus()
            object RbfAborted : RbfStatus()
        }
    }
}
