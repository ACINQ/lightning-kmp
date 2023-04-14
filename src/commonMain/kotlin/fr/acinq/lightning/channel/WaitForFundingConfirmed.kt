package fr.acinq.lightning.channel

import fr.acinq.bitcoin.ByteVector32
import fr.acinq.lightning.MilliSatoshi
import fr.acinq.lightning.ShortChannelId
import fr.acinq.lightning.blockchain.BITCOIN_FUNDING_DEPTHOK
import fr.acinq.lightning.blockchain.WatchConfirmed
import fr.acinq.lightning.blockchain.WatchEventConfirmed
import fr.acinq.lightning.utils.Either
import fr.acinq.lightning.utils.msat
import fr.acinq.lightning.utils.toMilliSatoshi
import fr.acinq.lightning.wire.*

/** We wait for the channel funding transaction to confirm. */
data class WaitForFundingConfirmed(
    override val commitments: Commitments,
    val localPushAmount: MilliSatoshi,
    val remotePushAmount: MilliSatoshi,
    val waitingSinceBlock: Long, // how many blocks have we been waiting for the funding tx to confirm
    val deferred: ChannelReady?,
    // We can have at most one ongoing RBF attempt.
    val rbfStatus: RbfStatus
) : ChannelStateWithCommitments() {

    val latestFundingTx = commitments.latest.localFundingStatus as LocalFundingStatus.UnconfirmedFundingTx
    private val allFundingTxs = commitments.active.map { it.localFundingStatus }.filterIsInstance<LocalFundingStatus.UnconfirmedFundingTx>()
    val previousFundingTxs = allFundingTxs.filter { it.txId != latestFundingTx.txId }

    override fun updateCommitments(input: Commitments): ChannelStateWithCommitments = this.copy(commitments = input)

    override fun ChannelContext.processInternal(cmd: ChannelCommand): Pair<ChannelState, List<ChannelAction>> {
        return when {
            cmd is ChannelCommand.MessageReceived && cmd.message is TxSignatures -> when (latestFundingTx.sharedTx) {
                is PartiallySignedSharedTransaction -> when (val fullySignedTx = latestFundingTx.sharedTx.addRemoteSigs(latestFundingTx.fundingParams, cmd.message)) {
                    null -> {
                        logger.warning { "received invalid remote funding signatures for txId=${cmd.message.txId}" }
                        // The funding transaction may still confirm (since our peer should be able to generate valid signatures), so we cannot close the channel yet.
                        Pair(this@WaitForFundingConfirmed, listOf(ChannelAction.Message.Send(Warning(channelId, InvalidFundingSignature(channelId, cmd.message.txId).message))))
                    }
                    else -> {
                        when (val res = commitments.run { updateLocalFundingStatus(fullySignedTx.signedTx.txid, latestFundingTx.copy(sharedTx = fullySignedTx)) }) {
                            is Either.Left -> Pair(this@WaitForFundingConfirmed, listOf())
                            is Either.Right -> {
                                logger.info { "received remote funding signatures, publishing txId=${fullySignedTx.signedTx.txid}" }
                                val nextState = this@WaitForFundingConfirmed.copy(commitments = res.value.first)
                                val actions = buildList {
                                    add(ChannelAction.Blockchain.PublishTx(fullySignedTx.signedTx, ChannelAction.Blockchain.PublishTx.Type.FundingTx))
                                    add(ChannelAction.Storage.StoreState(nextState))
                                }
                                Pair(nextState, actions)
                            }
                        }
                    }
                }
                is FullySignedSharedTransaction -> when (rbfStatus) {
                    is RbfStatus.WaitingForSigs -> {
                        when (val action = rbfStatus.session.receiveTxSigs(cmd.message, currentBlockHeight.toLong())) {
                            is InteractiveTxSigningSessionAction.AbortFundingAttempt -> {
                                logger.warning { "rbf attempt failed: ${action.reason.message}" }
                                Pair(this@WaitForFundingConfirmed.copy(rbfStatus = RbfStatus.RbfAborted), listOf(ChannelAction.Message.Send(TxAbort(channelId, action.reason.message))))
                            }
                            InteractiveTxSigningSessionAction.WaitForTxSigs -> Pair(this@WaitForFundingConfirmed, listOf())
                            is InteractiveTxSigningSessionAction.SendTxSigs -> sendRbfTxSigs(action, cmd.message.channelData)
                        }
                    }
                    else -> {
                        logger.warning { "rejecting unexpected tx_signatures for txId=${cmd.message.txId}" }
                        Pair(this@WaitForFundingConfirmed.copy(rbfStatus = RbfStatus.RbfAborted), listOf(ChannelAction.Message.Send(TxAbort(channelId, UnexpectedFundingSignatures(channelId).message))))
                    }
                }
            }
            cmd is ChannelCommand.MessageReceived && cmd.message is TxInitRbf -> {
                if (isInitiator) {
                    logger.info { "rejecting tx_init_rbf, we're the initiator, not them!" }
                    Pair(this@WaitForFundingConfirmed, listOf(ChannelAction.Message.Send(Error(channelId, InvalidRbfNonInitiator(channelId).message))))
                } else {
                    val minNextFeerate = latestFundingTx.fundingParams.minNextFeerate
                    when (rbfStatus) {
                        RbfStatus.None -> {
                            if (cmd.message.feerate < minNextFeerate) {
                                logger.info { "rejecting rbf attempt: the new feerate must be at least $minNextFeerate (proposed=${cmd.message.feerate})" }
                                Pair(this@WaitForFundingConfirmed.copy(rbfStatus = RbfStatus.RbfAborted), listOf(ChannelAction.Message.Send(TxAbort(channelId, InvalidRbfFeerate(channelId, cmd.message.feerate, minNextFeerate).message))))
                            } else if (cmd.message.fundingContribution.toMilliSatoshi() < remotePushAmount) {
                                logger.info { "rejecting rbf attempt: invalid amount pushed (fundingAmount=${cmd.message.fundingContribution}, pushAmount=$remotePushAmount)" }
                                Pair(
                                    this@WaitForFundingConfirmed.copy(rbfStatus = RbfStatus.RbfAborted),
                                    listOf(ChannelAction.Message.Send(TxAbort(channelId, InvalidPushAmount(channelId, remotePushAmount, cmd.message.fundingContribution.toMilliSatoshi()).message)))
                                )
                            } else {
                                logger.info { "our peer wants to raise the feerate of the funding transaction (previous=${latestFundingTx.fundingParams.targetFeerate} target=${cmd.message.feerate})" }
                                val fundingParams = InteractiveTxParams(
                                    channelId,
                                    isInitiator,
                                    latestFundingTx.fundingParams.localContribution, // we don't change our funding contribution
                                    cmd.message.fundingContribution,
                                    latestFundingTx.fundingParams.fundingPubkeyScript,
                                    cmd.message.lockTime,
                                    latestFundingTx.fundingParams.dustLimit,
                                    cmd.message.feerate
                                )
                                val toSend = buildList<Either<InteractiveTxInput.Outgoing, InteractiveTxOutput.Outgoing>> {
                                    addAll(latestFundingTx.sharedTx.tx.localInputs.map { Either.Left(it) })
                                    addAll(latestFundingTx.sharedTx.tx.localOutputs.map { Either.Right(it) })
                                }
                                val session = InteractiveTxSession(fundingParams, SharedFundingInputBalances(0.msat, 0.msat), toSend, previousFundingTxs.map { it.sharedTx })
                                val nextState = this@WaitForFundingConfirmed.copy(rbfStatus = RbfStatus.InProgress(session))
                                Pair(nextState, listOf(ChannelAction.Message.Send(TxAckRbf(channelId, fundingParams.localContribution))))
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
                        latestFundingTx.fundingParams.fundingPubkeyScript,
                        rbfStatus.command.lockTime,
                        latestFundingTx.fundingParams.dustLimit,
                        rbfStatus.command.targetFeerate
                    )
                    when (val contributions = FundingContributions.create(fundingParams, rbfStatus.command.wallet.confirmedUtxos)) {
                        is Either.Left -> {
                            logger.warning { "error creating funding contributions: ${contributions.value}" }
                            Pair(this@WaitForFundingConfirmed.copy(rbfStatus = RbfStatus.RbfAborted), listOf(ChannelAction.Message.Send(TxAbort(channelId, ChannelFundingError(channelId).message))))
                        }
                        is Either.Right -> {
                            val (session, action) = InteractiveTxSession(fundingParams, 0.msat, 0.msat, contributions.value, previousFundingTxs.map { it.sharedTx }).send()
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
                            val replacedCommitment = commitments.latest
                            val signingSession = InteractiveTxSigningSession.create(
                                keyManager,
                                commitments.params,
                                rbfSession1.fundingParams,
                                fundingTxIndex = replacedCommitment.fundingTxIndex,
                                interactiveTxAction.sharedTx,
                                localPushAmount,
                                remotePushAmount,
                                commitmentIndex = replacedCommitment.localCommit.index,
                                replacedCommitment.localCommit.spec.feerate,
                                replacedCommitment.remoteCommit.remotePerCommitmentPoint
                            )
                            when (signingSession) {
                                is Either.Left -> {
                                    logger.error(signingSession.value) { "cannot initiate interactive-tx rbf signing session" }
                                    Pair(this@WaitForFundingConfirmed.copy(rbfStatus = RbfStatus.RbfAborted), listOf(ChannelAction.Message.Send(TxAbort(channelId, signingSession.value.message))))
                                }
                                is Either.Right -> {
                                    val (session, commitSig) = signingSession.value
                                    val nextState = this@WaitForFundingConfirmed.copy(rbfStatus = RbfStatus.WaitingForSigs(session))
                                    val actions = buildList {
                                        interactiveTxAction.txComplete?.let { add(ChannelAction.Message.Send(it)) }
                                        add(ChannelAction.Storage.StoreState(nextState))
                                        add(ChannelAction.Message.Send(commitSig))
                                    }
                                    Pair(nextState, actions)
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
                is RbfStatus.WaitingForSigs -> {
                    val (signingSession1, action) = rbfStatus.session.receiveCommitSig(keyManager, commitments.params, cmd.message, currentBlockHeight.toLong())
                    when (action) {
                        is InteractiveTxSigningSessionAction.AbortFundingAttempt -> {
                            logger.warning { "rbf attempt failed: ${action.reason.message}" }
                            Pair(this@WaitForFundingConfirmed.copy(rbfStatus = RbfStatus.RbfAborted), listOf(ChannelAction.Message.Send(TxAbort(channelId, action.reason.message))))
                        }
                        // No need to store their commit_sig, they will re-send it if we disconnect.
                        InteractiveTxSigningSessionAction.WaitForTxSigs -> Pair(this@WaitForFundingConfirmed.copy(rbfStatus = rbfStatus.copy(session = signingSession1)), listOf())
                        is InteractiveTxSigningSessionAction.SendTxSigs -> sendRbfTxSigs(action, cmd.message.channelData)
                    }
                }
                else -> {
                    logger.info { "ignoring redundant commit_sig" }
                    Pair(this@WaitForFundingConfirmed, listOf())
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
                when (val res = acceptFundingTxConfirmed(cmd.watch)) {
                    is Either.Left -> Pair(this@WaitForFundingConfirmed, listOf())
                    is Either.Right -> {
                        val (commitments1, commitment, actions) = res.value
                        val nextPerCommitmentPoint = commitments1.params.localParams.channelKeys(keyManager).commitmentPoint(1)
                        val channelReady = ChannelReady(channelId, nextPerCommitmentPoint, TlvStream(ChannelReadyTlv.ShortChannelIdTlv(ShortChannelId.peerId(staticParams.nodeParams.nodeId))))
                        // this is the temporary channel id that we will use in our channel_update message, the goal is to be able to use our channel
                        // as soon as it reaches NORMAL state, and before it is announced on the network
                        // (this id might be updated when the funding tx gets deeply buried, if there was a reorg in the meantime)
                        val shortChannelId = ShortChannelId(cmd.watch.blockHeight, cmd.watch.txIndex, commitment.commitInput.outPoint.index.toInt())
                        val nextState = WaitForChannelReady(commitments1, shortChannelId, channelReady)
                        val actions1 = buildList {
                            if (rbfStatus != RbfStatus.None) add(ChannelAction.Message.Send(TxAbort(channelId, InvalidRbfTxConfirmed(channelId, cmd.watch.tx.txid).message)))
                            add(ChannelAction.Message.Send(channelReady))
                            add(ChannelAction.Storage.StoreState(nextState))
                        }
                        if (deferred != null) {
                            logger.info { "funding_locked has already been received" }
                            val (nextState1, actions2) = nextState.run { process(ChannelCommand.MessageReceived(deferred)) }
                            Pair(nextState1, actions + actions1 + actions2)
                        } else {
                            Pair(nextState, actions + actions1)
                        }
                    }
                }
            }
            cmd is ChannelCommand.ExecuteCommand && cmd.command is CMD_BUMP_FUNDING_FEE -> when {
                !latestFundingTx.fundingParams.isInitiator -> {
                    logger.warning { "cannot initiate rbf, we're not the initiator" }
                    Pair(this@WaitForFundingConfirmed, listOf())
                }
                rbfStatus != RbfStatus.None -> {
                    logger.warning { "cannot initiate rbf, another one is already in progress" }
                    Pair(this@WaitForFundingConfirmed, listOf())
                }
                else -> {
                    logger.info { "initiating rbf (current feerate = ${latestFundingTx.fundingParams.targetFeerate}, next feerate = ${cmd.command.targetFeerate})" }
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
            cmd is ChannelCommand.Disconnected -> {
                val rbfStatus1 = when (rbfStatus) {
                    // We keep track of the RBF status: we should be able to complete the signature steps on reconnection.
                    is RbfStatus.WaitingForSigs -> rbfStatus
                    else -> RbfStatus.None
                }
                Pair(Offline(this@WaitForFundingConfirmed.copy(rbfStatus = rbfStatus1)), listOf())
            }
            else -> unhandled(cmd)
        }
    }

    private fun ChannelContext.sendRbfTxSigs(action: InteractiveTxSigningSessionAction.SendTxSigs, remoteChannelData: EncryptedChannelData): Pair<WaitForFundingConfirmed, List<ChannelAction>> {
        logger.info { "rbf funding tx created with txId=${action.fundingTx.txId}, ${action.fundingTx.sharedTx.tx.localInputs.size} local inputs, ${action.fundingTx.sharedTx.tx.remoteInputs.size} remote inputs, ${action.fundingTx.sharedTx.tx.localOutputs.size} local outputs and ${action.fundingTx.sharedTx.tx.remoteOutputs.size} remote outputs" }
        val fundingMinDepth = Helpers.minDepthForFunding(staticParams.nodeParams, action.fundingTx.fundingParams.fundingAmount)
        logger.info { "will wait for $fundingMinDepth confirmations" }
        val watchConfirmed = WatchConfirmed(channelId, action.commitment.fundingTxId, action.commitment.commitInput.txOut.publicKeyScript, fundingMinDepth.toLong(), BITCOIN_FUNDING_DEPTHOK)
        val nextState = WaitForFundingConfirmed(
            commitments.add(action.commitment).copy(remoteChannelData = remoteChannelData),
            localPushAmount,
            remotePushAmount,
            waitingSinceBlock,
            deferred,
            RbfStatus.None
        )
        val actions = buildList {
            add(ChannelAction.Storage.StoreState(nextState))
            action.fundingTx.signedTx?.let { add(ChannelAction.Blockchain.PublishTx(it, ChannelAction.Blockchain.PublishTx.Type.FundingTx)) }
            add(ChannelAction.Blockchain.SendWatch(watchConfirmed))
            add(ChannelAction.Message.Send(action.localSigs))
        }
        return Pair(nextState, actions)
    }

    /** If we haven't completed the signing steps of an interactive-tx session, we will ask our peer to retransmit signatures for the corresponding transaction. */
    fun getUnsignedFundingTxId(): ByteVector32? {
        return when (rbfStatus) {
            is RbfStatus.WaitingForSigs -> rbfStatus.session.fundingTx.txId
            else -> when (latestFundingTx.sharedTx) {
                is PartiallySignedSharedTransaction -> latestFundingTx.txId
                is FullySignedSharedTransaction -> null
            }
        }
    }

    override fun ChannelContext.handleLocalError(cmd: ChannelCommand, t: Throwable): Pair<ChannelState, List<ChannelAction>> {
        logger.error(t) { "error on command ${cmd::class.simpleName} in state ${this@WaitForFundingConfirmed::class.simpleName}" }
        val error = Error(channelId, t.message)
        return when {
            commitments.nothingAtStake() -> Pair(Aborted, listOf(ChannelAction.Message.Send(error)))
            else -> spendLocalCurrent().run { copy(second = second + ChannelAction.Message.Send(error)) }
        }
    }
}
