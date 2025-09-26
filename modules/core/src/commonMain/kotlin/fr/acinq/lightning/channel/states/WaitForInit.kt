package fr.acinq.lightning.channel.states

import fr.acinq.lightning.ShortChannelId
import fr.acinq.lightning.blockchain.WatchConfirmed
import fr.acinq.lightning.blockchain.WatchSpent
import fr.acinq.lightning.channel.ChannelAction
import fr.acinq.lightning.channel.ChannelCommand
import fr.acinq.lightning.channel.Helpers
import fr.acinq.lightning.channel.LocalFundingStatus
import fr.acinq.lightning.wire.ChannelTlv
import fr.acinq.lightning.wire.OpenDualFundedChannel
import fr.acinq.lightning.wire.TlvStream

data object WaitForInit : ChannelState() {
    override suspend fun ChannelContext.processInternal(cmd: ChannelCommand): Pair<ChannelState, List<ChannelAction>> {
        return when (cmd) {
            is ChannelCommand.Init.NonInitiator -> {
                val nextState = WaitForOpenChannel(
                    replyTo = cmd.replyTo,
                    temporaryChannelId = cmd.temporaryChannelId,
                    fundingAmount = cmd.fundingAmount,
                    walletInputs = cmd.walletInputs,
                    localChannelParams = cmd.localParams,
                    dustLimit = cmd.dustLimit,
                    htlcMinimum = cmd.htlcMinimum,
                    maxHtlcValueInFlightMsat = cmd.maxHtlcValueInFlightMsat,
                    maxAcceptedHtlcs = cmd.maxAcceptedHtlcs,
                    toRemoteDelay = cmd.toRemoteDelay,
                    channelConfig = cmd.channelConfig,
                    remoteInit = cmd.remoteInit,
                    fundingRates = cmd.fundingRates,
                )
                Pair(nextState, listOf())
            }
            is ChannelCommand.Init.Initiator -> {
                val channelKeys = keyManager.channelKeys(cmd.localChannelParams.fundingKeyPath)
                val open = OpenDualFundedChannel(
                    chainHash = staticParams.nodeParams.chainHash,
                    temporaryChannelId = cmd.temporaryChannelId(channelKeys),
                    fundingFeerate = cmd.fundingTxFeerate,
                    commitmentFeerate = cmd.commitTxFeerate,
                    fundingAmount = cmd.fundingAmount,
                    dustLimit = cmd.dustLimit,
                    maxHtlcValueInFlightMsat = cmd.maxHtlcValueInFlightMsat,
                    htlcMinimum = cmd.htlcMinimum,
                    toSelfDelay = cmd.toRemoteDelay,
                    maxAcceptedHtlcs = cmd.maxAcceptedHtlcs,
                    lockTime = currentBlockHeight.toLong(),
                    fundingPubkey = channelKeys.fundingKey(0).publicKey(),
                    revocationBasepoint = channelKeys.revocationBasePoint,
                    paymentBasepoint = channelKeys.paymentBasePoint,
                    delayedPaymentBasepoint = channelKeys.delayedPaymentBasePoint,
                    htlcBasepoint = channelKeys.htlcBasePoint,
                    firstPerCommitmentPoint = channelKeys.commitmentPoint(0),
                    secondPerCommitmentPoint = channelKeys.commitmentPoint(1),
                    channelFlags = cmd.channelFlags,
                    tlvStream = TlvStream(
                        buildSet {
                            add(ChannelTlv.ChannelTypeTlv(cmd.channelType))
                            cmd.requestRemoteFunding?.let { add(ChannelTlv.RequestFundingTlv(it)) }
                        }
                    )
                )
                val nextState = WaitForAcceptChannel(cmd, open, cmd.channelOrigin)
                Pair(nextState, listOf(ChannelAction.Message.Send(open)))
            }
            is ChannelCommand.Init.Restore -> {
                logger.info { "restoring channel ${cmd.state.channelId} to state ${cmd.state::class.simpleName}" }
                val channelKeys = cmd.state.run { channelKeys() }
                // We republish unconfirmed transactions.
                val unconfirmedFundingTxs = when (cmd.state) {
                    is ChannelStateWithCommitments -> cmd.state.commitments.active.mapNotNull { commitment ->
                        when (val fundingStatus = commitment.localFundingStatus) {
                            is LocalFundingStatus.UnconfirmedFundingTx -> fundingStatus.signedTx
                            is LocalFundingStatus.ConfirmedFundingTx -> null
                        }
                    }
                    else -> listOf()
                }
                // We watch all funding transactions regardless of the underlying state.
                // There can be multiple funding transactions due to rbf, and they can be unconfirmed in any state due to zero-conf.
                val fundingTxWatches = when (cmd.state) {
                    is ChannelStateWithCommitments -> cmd.state.commitments.active.map { commitment ->
                        val fundingMinDepth = staticParams.nodeParams.minDepthBlocks
                        val commitInput = commitment.commitInput(channelKeys)
                        when (commitment.localFundingStatus) {
                            is LocalFundingStatus.UnconfirmedFundingTx -> WatchConfirmed(cmd.state.channelId, commitment.fundingTxId, commitInput.txOut.publicKeyScript, fundingMinDepth, WatchConfirmed.ChannelFundingDepthOk)
                            is LocalFundingStatus.ConfirmedFundingTx -> when (commitment.localFundingStatus.shortChannelId) {
                                // If the short_channel_id isn't correctly set, we put a watch on the funding transaction to compute it.
                                ShortChannelId(0) -> WatchConfirmed(cmd.state.channelId, commitment.fundingTxId, commitInput.txOut.publicKeyScript, fundingMinDepth, WatchConfirmed.ChannelFundingDepthOk)
                                else -> WatchSpent(cmd.state.channelId, commitment.fundingTxId, commitInput.outPoint.index.toInt(), commitInput.txOut.publicKeyScript, WatchSpent.ChannelSpent(commitment.fundingAmount))
                            }
                        }
                    }
                    else -> listOf()
                }
                when (cmd.state) {
                    is Closing -> {
                        val closingType = cmd.state.closingTypeAlreadyKnown()
                        logger.info { "channel is closing (closing type = ${closingType?.let { it::class } ?: "unknown yet"})" }
                        // If the closing type is known:
                        //  - there is no need to attempt to publish transactions for other type of closes
                        //  - there may be 3rd-stage transactions to publish
                        //  - there is a single commitment, the others have all been invalidated
                        val commitment = cmd.state.commitments.latest
                        val feerates = currentOnChainFeerates()
                        when (closingType) {
                            is MutualClose -> Pair(cmd.state, doPublish(closingType.tx, cmd.state.channelId))
                            is LocalClose -> {
                                val (_, secondStageTxs) = Helpers.Closing.LocalClose.run {
                                    claimCommitTxOutputs(channelKeys, commitment, closingType.localCommitPublished.commitTx, feerates)
                                }
                                val thirdStageTxs = Helpers.Closing.LocalClose.run {
                                    claimHtlcDelayedOutputs(closingType.localCommitPublished, channelKeys, commitment, feerates)
                                }
                                val actions = buildList {
                                    addAll(closingType.localCommitPublished.run { doPublish(staticParams.nodeParams, cmd.state.channelId, secondStageTxs) })
                                    addAll(closingType.localCommitPublished.run { doPublish(cmd.state.channelId, thirdStageTxs) })
                                }
                                Pair(cmd.state, actions)
                            }
                            is RemoteClose -> {
                                val (_, secondStageTxs) = Helpers.Closing.RemoteClose.run {
                                    claimCommitTxOutputs(channelKeys, commitment, closingType.remoteCommit, closingType.remoteCommitPublished.commitTx, feerates)
                                }
                                val actions = closingType.remoteCommitPublished.run { doPublish(staticParams.nodeParams, cmd.state.channelId, secondStageTxs) }
                                Pair(cmd.state, actions)
                            }
                            is RevokedClose -> {
                                val commitTx = closingType.revokedCommitPublished.commitTx
                                val remotePerCommitmentSecret = closingType.revokedCommitPublished.remotePerCommitmentSecret
                                // TODO: once we allow changing the commitment format or to_self_delay during a splice, those values may be incorrect.
                                val toSelfDelay = cmd.state.commitments.latest.remoteCommitParams.toSelfDelay
                                val commitmentFormat = cmd.state.commitments.latest.commitmentFormat
                                val dustLimit = cmd.state.commitments.latest.localCommitParams.dustLimit
                                val actions = buildList {
                                    val secondStageTxs = Helpers.Closing.RevokedClose.run {
                                        claimCommitTxOutputs(cmd.state.commitments.channelParams, channelKeys, commitTx, remotePerCommitmentSecret, dustLimit, toSelfDelay, commitmentFormat, feerates).second
                                    }
                                    val thirdStageTxs = Helpers.Closing.RevokedClose.run {
                                        claimHtlcTxsOutputs(cmd.state.commitments.channelParams, channelKeys, closingType.revokedCommitPublished, dustLimit, toSelfDelay, commitmentFormat, feerates)
                                    }
                                    closingType.revokedCommitPublished.run { addAll(doPublish(staticParams.nodeParams, cmd.state.channelId, secondStageTxs)) }
                                    closingType.revokedCommitPublished.run { addAll(doPublish(cmd.state.channelId, thirdStageTxs)) }
                                    // We fetch HTLC details to republish HTLC-penalty transactions.
                                    Helpers.Closing.RevokedClose.getRemotePerCommitmentSecret(cmd.state.commitments.channelParams, channelKeys, cmd.state.commitments.remotePerCommitmentSecrets, commitTx)?.let {
                                        add(ChannelAction.Storage.GetHtlcInfos(commitTx.txid, it.second))
                                    }
                                }
                                Pair(cmd.state, actions)
                            }
                            is RecoveryClose -> {
                                // We cannot do anything in that case: we've already published our recovery transaction before restarting,
                                // and must wait for it to confirm.
                                val rcp = closingType.remoteCommitPublished
                                val actions = Helpers.run { watchSpentIfNeeded(cmd.state.channelId, rcp.commitTx, listOfNotNull(rcp.localOutput), rcp.irrevocablySpent) }
                                Pair(cmd.state, actions)
                            }
                            null -> {
                                // The closing type isn't known yet:
                                //  - we publish transactions for all types of closes that we detected
                                //  - there may be other commitments, but we'll adapt if we receive WatchAlternativeCommitTxConfirmedTriggered
                                //  - there cannot be 3rd-stage transactions yet, no need to re-compute them
                                val actions = buildList {
                                    addAll(unconfirmedFundingTxs.map { ChannelAction.Blockchain.PublishTx(it, ChannelAction.Blockchain.PublishTx.Type.FundingTx) })
                                    addAll(fundingTxWatches.map { ChannelAction.Blockchain.SendWatch(it) })
                                    cmd.state.mutualClosePublished.forEach { addAll(doPublish(it, cmd.state.channelId)) }
                                    cmd.state.localCommitPublished?.let { lcp ->
                                        val (_, txs) = Helpers.Closing.LocalClose.run { claimCommitTxOutputs(channelKeys, commitment, lcp.commitTx, feerates) }
                                        addAll(lcp.run { doPublish(staticParams.nodeParams, cmd.state.channelId, txs) })
                                    }
                                    cmd.state.remoteCommitPublished?.let { rcp ->
                                        val (_, txs) = Helpers.Closing.RemoteClose.run { claimCommitTxOutputs(channelKeys, commitment, commitment.remoteCommit, rcp.commitTx, feerates) }
                                        addAll(rcp.run { doPublish(staticParams.nodeParams, cmd.state.channelId, txs) })
                                    }
                                    cmd.state.nextRemoteCommitPublished?.let { rcp ->
                                        val remoteCommit = commitment.nextRemoteCommit!!
                                        val (_, txs) = Helpers.Closing.RemoteClose.run { claimCommitTxOutputs(channelKeys, commitment, remoteCommit, rcp.commitTx, feerates) }
                                        addAll(rcp.run { doPublish(staticParams.nodeParams, cmd.state.channelId, txs) })
                                    }
                                    cmd.state.futureRemoteCommitPublished?.let { rcp ->
                                        val watchConfirmed = Helpers.run { watchConfirmedIfNeeded(staticParams.nodeParams, cmd.state.channelId, listOf(rcp.commitTx), rcp.irrevocablySpent) }
                                        addAll(watchConfirmed)
                                        val watchSpent = Helpers.run { watchSpentIfNeeded(cmd.state.channelId, rcp.commitTx, listOfNotNull(rcp.localOutput), rcp.irrevocablySpent) }
                                        addAll(watchSpent)
                                    }
                                    cmd.state.revokedCommitPublished.forEach { rvk ->
                                        // TODO: once we allow changing the commitment format or to_self_delay during a splice, those values may be incorrect.
                                        val toSelfDelay = cmd.state.commitments.latest.remoteCommitParams.toSelfDelay
                                        val commitmentFormat = cmd.state.commitments.latest.commitmentFormat
                                        val dustLimit = cmd.state.commitments.latest.localCommitParams.dustLimit
                                        val (_, txs) = Helpers.Closing.RevokedClose.run {
                                            claimCommitTxOutputs(cmd.state.commitments.channelParams, channelKeys, rvk.commitTx, rvk.remotePerCommitmentSecret, dustLimit, toSelfDelay, commitmentFormat, feerates)
                                        }
                                        rvk.run { addAll(doPublish(staticParams.nodeParams, cmd.state.channelId, txs)) }
                                        // We fetch HTLC details to republish HTLC-penalty transactions.
                                        Helpers.Closing.RevokedClose.getRemotePerCommitmentSecret(cmd.state.commitments.channelParams, channelKeys, cmd.state.commitments.remotePerCommitmentSecrets, rvk.commitTx)?.let {
                                            add(ChannelAction.Storage.GetHtlcInfos(rvk.commitTx.txid, it.second))
                                        }
                                    }
                                }
                                Pair(cmd.state, actions)
                            }
                        }
                    }
                    else -> {
                        val actions = buildList {
                            addAll(unconfirmedFundingTxs.map { ChannelAction.Blockchain.PublishTx(it, ChannelAction.Blockchain.PublishTx.Type.FundingTx) })
                            addAll(fundingTxWatches.map { ChannelAction.Blockchain.SendWatch(it) })
                        }
                        Pair(Offline(cmd.state), actions)
                    }
                }
            }
            is ChannelCommand.Close -> Pair(Aborted, listOf())
            is ChannelCommand.MessageReceived -> unhandled(cmd)
            is ChannelCommand.WatchReceived -> unhandled(cmd)
            is ChannelCommand.Commitment -> unhandled(cmd)
            is ChannelCommand.Htlc -> unhandled(cmd)
            is ChannelCommand.Funding -> unhandled(cmd)
            is ChannelCommand.Closing -> unhandled(cmd)
            is ChannelCommand.Connected -> unhandled(cmd)
            is ChannelCommand.Disconnected -> unhandled(cmd)
        }
    }
}
