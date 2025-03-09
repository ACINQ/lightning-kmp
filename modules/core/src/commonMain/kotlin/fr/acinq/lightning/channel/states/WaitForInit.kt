package fr.acinq.lightning.channel.states

import fr.acinq.lightning.Feature
import fr.acinq.lightning.Features
import fr.acinq.lightning.ShortChannelId
import fr.acinq.lightning.blockchain.WatchConfirmed
import fr.acinq.lightning.blockchain.WatchSpent
import fr.acinq.lightning.channel.ChannelAction
import fr.acinq.lightning.channel.ChannelCommand
import fr.acinq.lightning.channel.LocalFundingStatus
import fr.acinq.lightning.wire.ChannelTlv
import fr.acinq.lightning.wire.OpenDualFundedChannel
import fr.acinq.lightning.wire.TlvStream

data object WaitForInit : ChannelState() {
    override suspend fun ChannelContext.processInternal(cmd: ChannelCommand): Pair<ChannelState, List<ChannelAction>> {
        return when (cmd) {
            is ChannelCommand.Init.NonInitiator -> {
                val nextState = WaitForOpenChannel(
                    cmd.replyTo,
                    cmd.temporaryChannelId,
                    cmd.fundingAmount,
                    cmd.walletInputs,
                    cmd.localParams,
                    cmd.channelConfig,
                    cmd.remoteInit,
                    cmd.fundingRates,
                )
                Pair(nextState, listOf())
            }
            is ChannelCommand.Init.Initiator -> {
                val channelKeys = keyManager.channelKeys(cmd.localParams.fundingKeyPath)
                val open = OpenDualFundedChannel(
                    chainHash = staticParams.nodeParams.chainHash,
                    temporaryChannelId = cmd.temporaryChannelId(keyManager),
                    fundingFeerate = cmd.fundingTxFeerate,
                    commitmentFeerate = cmd.commitTxFeerate,
                    fundingAmount = cmd.fundingAmount,
                    dustLimit = cmd.localParams.dustLimit,
                    maxHtlcValueInFlightMsat = cmd.localParams.maxHtlcValueInFlightMsat,
                    htlcMinimum = cmd.localParams.htlcMinimum,
                    toSelfDelay = cmd.localParams.toSelfDelay,
                    maxAcceptedHtlcs = cmd.localParams.maxAcceptedHtlcs,
                    lockTime = currentBlockHeight.toLong(),
                    fundingPubkey = channelKeys.fundingPubKey(0),
                    revocationBasepoint = channelKeys.revocationBasepoint,
                    paymentBasepoint = channelKeys.paymentBasepoint,
                    delayedPaymentBasepoint = channelKeys.delayedPaymentBasepoint,
                    htlcBasepoint = channelKeys.htlcBasepoint,
                    firstPerCommitmentPoint = channelKeys.commitmentPoint(0),
                    secondPerCommitmentPoint = channelKeys.commitmentPoint(1),
                    channelFlags = cmd.channelFlags,
                    tlvStream = TlvStream(
                        buildSet {
                            add(ChannelTlv.ChannelTypeTlv(cmd.channelType))
                            cmd.requestRemoteFunding?.let { add(ChannelTlv.RequestFundingTlv(it)) }
                            if (Features.canUseFeature(cmd.localParams.features, cmd.remoteInit.features, Feature.SimpleTaprootStaging)) add(
                                ChannelTlv.NextLocalNoncesTlv(
                                    listOf(
                                        channelKeys.verificationNonce(0, 0).second,
                                        channelKeys.verificationNonce(0, 1).second,
                                    )
                                )
                            )
                        }
                    )
                )
                val nextState = WaitForAcceptChannel(cmd, open, cmd.channelOrigin)
                Pair(nextState, listOf(ChannelAction.Message.Send(open)))
            }
            is ChannelCommand.Init.Restore -> {
                logger.info { "restoring channel ${cmd.state.channelId} to state ${cmd.state::class.simpleName}" }
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
                        val fundingMinDepth = staticParams.nodeParams.minDepth(commitment.fundingAmount)
                        when (commitment.localFundingStatus) {
                            is LocalFundingStatus.UnconfirmedFundingTx -> WatchConfirmed(cmd.state.channelId, commitment.fundingTxId, commitment.commitInput.txOut.publicKeyScript, fundingMinDepth, WatchConfirmed.ChannelFundingDepthOk)
                            is LocalFundingStatus.ConfirmedFundingTx -> when (commitment.localFundingStatus.shortChannelId) {
                                // If the short_channel_id isn't correctly set, we put a watch on the funding transaction to compute it.
                                ShortChannelId(0) -> WatchConfirmed(cmd.state.channelId, commitment.fundingTxId, commitment.commitInput.txOut.publicKeyScript, fundingMinDepth, WatchConfirmed.ChannelFundingDepthOk)
                                else -> WatchSpent(cmd.state.channelId, commitment.fundingTxId, commitment.commitInput.outPoint.index.toInt(), commitment.commitInput.txOut.publicKeyScript, WatchSpent.ChannelSpent(commitment.fundingAmount))
                            }
                        }
                    }
                    else -> listOf()
                }
                when (cmd.state) {
                    is Closing -> {
                        val closingType = cmd.state.closingTypeAlreadyKnown()
                        logger.info { "channel is closing (closing type = ${closingType?.let { it::class } ?: "unknown yet"})" }
                        // if the closing type is known:
                        // - there is no need to watch funding txs because one has already been spent and the spending tx has already reached mindepth
                        // - there is no need to attempt to publish transactions for other type of closes
                        when (closingType) {
                            is MutualClose -> {
                                Pair(cmd.state, doPublish(closingType.tx, cmd.state.channelId))
                            }
                            is LocalClose -> {
                                val actions = closingType.localCommitPublished.run { doPublish(staticParams.nodeParams, cmd.state.channelId) }
                                Pair(cmd.state, actions)
                            }
                            is RemoteClose -> {
                                val actions = closingType.remoteCommitPublished.run { doPublish(staticParams.nodeParams, cmd.state.channelId) }
                                Pair(cmd.state, actions)
                            }
                            is RevokedClose -> {
                                val actions = closingType.revokedCommitPublished.run { doPublish(staticParams.nodeParams, cmd.state.channelId) }
                                Pair(cmd.state, actions)
                            }
                            is RecoveryClose -> {
                                val actions = closingType.remoteCommitPublished.run { doPublish(staticParams.nodeParams, cmd.state.channelId) }
                                Pair(cmd.state, actions)
                            }
                            null -> {
                                // in all other cases we need to be ready for any type of closing
                                val actions = buildList {
                                    addAll(unconfirmedFundingTxs.map { ChannelAction.Blockchain.PublishTx(it, ChannelAction.Blockchain.PublishTx.Type.FundingTx) })
                                    addAll(fundingTxWatches.map { ChannelAction.Blockchain.SendWatch(it) })
                                    cmd.state.mutualClosePublished.forEach { addAll(doPublish(it, cmd.state.channelId)) }
                                    cmd.state.localCommitPublished?.run { addAll(doPublish(staticParams.nodeParams, cmd.state.channelId)) }
                                    cmd.state.remoteCommitPublished?.run { addAll(doPublish(staticParams.nodeParams, cmd.state.channelId)) }
                                    cmd.state.nextRemoteCommitPublished?.run { addAll(doPublish(staticParams.nodeParams, cmd.state.channelId)) }
                                    cmd.state.revokedCommitPublished.forEach { it.run { addAll(doPublish(staticParams.nodeParams, cmd.state.channelId)) } }
                                    cmd.state.futureRemoteCommitPublished?.run { addAll(doPublish(staticParams.nodeParams, cmd.state.channelId)) }
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
