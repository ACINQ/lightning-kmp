package fr.acinq.lightning.channel.states

import fr.acinq.lightning.blockchain.BITCOIN_FUNDING_DEPTHOK
import fr.acinq.lightning.blockchain.BITCOIN_FUNDING_SPENT
import fr.acinq.lightning.blockchain.WatchConfirmed
import fr.acinq.lightning.blockchain.WatchSpent
import fr.acinq.lightning.channel.*
import fr.acinq.lightning.utils.msat
import fr.acinq.lightning.wire.ChannelTlv
import fr.acinq.lightning.wire.OpenDualFundedChannel
import fr.acinq.lightning.wire.TlvStream

object WaitForInit : ChannelState() {
    override fun ChannelContext.processInternal(cmd: ChannelCommand): Pair<ChannelState, List<ChannelAction>> {
        return when {
            cmd is ChannelCommand.InitNonInitiator -> {
                val nextState = WaitForOpenChannel(
                    cmd.temporaryChannelId,
                    cmd.fundingAmount,
                    cmd.pushAmount,
                    cmd.utxos,
                    cmd.localParams,
                    cmd.channelConfig,
                    cmd.remoteInit
                )
                Pair(nextState, listOf())
            }
            cmd is ChannelCommand.InitInitiator -> {
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
                            if (cmd.pushAmount > 0.msat) add(ChannelTlv.PushAmountTlv(cmd.pushAmount))
                            if (cmd.channelOrigin != null) add(ChannelTlv.OriginTlv(cmd.channelOrigin))
                        }
                    )
                )
                val nextState = WaitForAcceptChannel(cmd, open)
                Pair(nextState, listOf(ChannelAction.Message.Send(open)))
            }
            cmd is ChannelCommand.Restore && cmd.state is Closing && cmd.state.commitments.nothingAtStake() -> {
                logger.info { "we have nothing at stake, going straight to CLOSED" }
                Pair(Closed(cmd.state), listOf())
            }
            cmd is ChannelCommand.Restore -> {
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
                        when (commitment.localFundingStatus) {
                            is LocalFundingStatus.UnconfirmedFundingTx -> {
                                val fundingMinDepth = Helpers.minDepthForFunding(staticParams.nodeParams, commitment.fundingAmount).toLong()
                                WatchConfirmed(cmd.state.channelId, commitment.fundingTxId, commitment.commitInput.txOut.publicKeyScript, fundingMinDepth, BITCOIN_FUNDING_DEPTHOK)
                            }
                            is LocalFundingStatus.ConfirmedFundingTx -> {
                                WatchSpent(cmd.state.channelId, commitment.fundingTxId, commitment.commitInput.outPoint.index.toInt(), commitment.commitInput.txOut.publicKeyScript, BITCOIN_FUNDING_SPENT)
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
                                val actions = closingType.localCommitPublished.run { doPublish(cmd.state.channelId, staticParams.nodeParams.minDepthBlocks.toLong()) }
                                Pair(cmd.state, actions)
                            }
                            is RemoteClose -> {
                                val actions = closingType.remoteCommitPublished.run { doPublish(cmd.state.channelId, staticParams.nodeParams.minDepthBlocks.toLong()) }
                                Pair(cmd.state, actions)
                            }
                            is RevokedClose -> {
                                val actions = closingType.revokedCommitPublished.run { doPublish(cmd.state.channelId, staticParams.nodeParams.minDepthBlocks.toLong()) }
                                Pair(cmd.state, actions)
                            }
                            is RecoveryClose -> {
                                val actions = closingType.remoteCommitPublished.run { doPublish(cmd.state.channelId, staticParams.nodeParams.minDepthBlocks.toLong()) }
                                Pair(cmd.state, actions)
                            }
                            null -> {
                                // in all other cases we need to be ready for any type of closing
                                val minDepth = staticParams.nodeParams.minDepthBlocks.toLong()
                                val actions = buildList {
                                    addAll(unconfirmedFundingTxs.map { ChannelAction.Blockchain.PublishTx(it, ChannelAction.Blockchain.PublishTx.Type.FundingTx) })
                                    addAll(fundingTxWatches.map { ChannelAction.Blockchain.SendWatch(it) })
                                    cmd.state.mutualClosePublished.forEach { addAll(doPublish(it, cmd.state.channelId)) }
                                    cmd.state.localCommitPublished?.run { addAll(doPublish(cmd.state.channelId, minDepth)) }
                                    cmd.state.remoteCommitPublished?.run { addAll(doPublish(cmd.state.channelId, minDepth)) }
                                    cmd.state.nextRemoteCommitPublished?.run { addAll(doPublish(cmd.state.channelId, minDepth)) }
                                    cmd.state.revokedCommitPublished.forEach { it.run { addAll(doPublish(cmd.state.channelId, minDepth)) } }
                                    cmd.state.futureRemoteCommitPublished?.run { addAll(doPublish(cmd.state.channelId, minDepth)) }
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
            cmd is ChannelCommand.Close -> Pair(Aborted, listOf())
            else -> unhandled(cmd)
        }
    }

    override fun ChannelContext.handleLocalError(cmd: ChannelCommand, t: Throwable): Pair<ChannelState, List<ChannelAction>> {
        logger.error(t) { "error on command ${cmd::class.simpleName} in state ${this@WaitForInit::class.simpleName}" }
        return Pair(this@WaitForInit, listOf(ChannelAction.ProcessLocalError(t, cmd)))
    }
}
