package fr.acinq.lightning.channel

import fr.acinq.bitcoin.BlockHeader
import fr.acinq.lightning.Feature
import fr.acinq.lightning.blockchain.BITCOIN_FUNDING_DEPTHOK
import fr.acinq.lightning.blockchain.BITCOIN_FUNDING_SPENT
import fr.acinq.lightning.blockchain.WatchConfirmed
import fr.acinq.lightning.blockchain.WatchSpent
import fr.acinq.lightning.blockchain.fee.OnChainFeerates
import fr.acinq.lightning.utils.msat
import fr.acinq.lightning.wire.ChannelTlv
import fr.acinq.lightning.wire.OpenDualFundedChannel
import fr.acinq.lightning.wire.TlvStream

data class WaitForInit(override val staticParams: StaticParams, override val currentTip: Pair<Int, BlockHeader>, override val currentOnChainFeerates: OnChainFeerates) : ChannelState() {
    override fun processInternal(event: ChannelEvent): Pair<ChannelState, List<ChannelAction>> {
        return when {
            event is ChannelEvent.InitNonInitiator -> {
                val nextState = WaitForOpenChannel(staticParams, currentTip, currentOnChainFeerates, event.temporaryChannelId, event.fundingInputs, event.localParams, event.channelConfig, event.remoteInit)
                Pair(nextState, listOf())
            }
            event is ChannelEvent.InitInitiator && isValidChannelType(event.channelType) -> {
                val open = OpenDualFundedChannel(
                    chainHash = staticParams.nodeParams.chainHash,
                    temporaryChannelId = event.temporaryChannelId,
                    fundingFeerate = event.fundingTxFeerate,
                    commitmentFeerate = event.commitTxFeerate,
                    fundingAmount = event.fundingInputs.fundingAmount,
                    dustLimit = event.localParams.dustLimit,
                    maxHtlcValueInFlightMsat = event.localParams.maxHtlcValueInFlightMsat,
                    htlcMinimum = event.localParams.htlcMinimum,
                    toSelfDelay = event.localParams.toSelfDelay,
                    maxAcceptedHtlcs = event.localParams.maxAcceptedHtlcs,
                    lockTime = currentBlockHeight.toLong(),
                    fundingPubkey = event.localParams.channelKeys.fundingPubKey,
                    revocationBasepoint = event.localParams.channelKeys.revocationBasepoint,
                    paymentBasepoint = event.localParams.channelKeys.paymentBasepoint,
                    delayedPaymentBasepoint = event.localParams.channelKeys.delayedPaymentBasepoint,
                    htlcBasepoint = event.localParams.channelKeys.htlcBasepoint,
                    firstPerCommitmentPoint = keyManager.commitmentPoint(event.localParams.channelKeys.shaSeed, 0),
                    channelFlags = event.channelFlags,
                    tlvStream = TlvStream(
                        buildList {
                            add(ChannelTlv.ChannelTypeTlv(event.channelType))
                            if (event.pushAmount > 0.msat) add(ChannelTlv.PushAmountTlv(event.pushAmount))
                            if (event.channelOrigin != null) add(ChannelTlv.ChannelOriginTlv(event.channelOrigin))
                        }
                    )
                )
                val nextState = WaitForAcceptChannel(staticParams, currentTip, currentOnChainFeerates, event, open)
                Pair(nextState, listOf(ChannelAction.Message.Send(open)))
            }
            event is ChannelEvent.InitInitiator -> {
                logger.warning { "c:${event.temporaryChannelId} cannot open channel with invalid channel_type=${event.channelType.name}" }
                Pair(Aborted(staticParams, currentTip, currentOnChainFeerates), listOf())
            }
            event is ChannelEvent.Restore && event.state is Closing && event.state.nothingAtStake() -> {
                logger.info { "c:${event.state.channelId} we have nothing at stake, going straight to CLOSED" }
                Pair(Closed(event.state), listOf())
            }
            event is ChannelEvent.Restore && event.state is Closing -> {
                val closingType = event.state.closingTypeAlreadyKnown()
                logger.info { "c:${event.state.channelId} channel is closing (closing type = ${closingType?.let { it::class } ?: "unknown yet"})" }
                // if the closing type is known:
                // - there is no need to watch the funding tx because it has already been spent and the spending tx has
                //   already reached mindepth
                // - there is no need to attempt to publish transactions for other type of closes
                when (closingType) {
                    is MutualClose -> {
                        Pair(event.state, doPublish(closingType.tx, event.state.channelId))
                    }
                    is LocalClose -> {
                        val actions = closingType.localCommitPublished.run { doPublish(event.state.channelId, event.state.staticParams.nodeParams.minDepthBlocks.toLong()) }
                        Pair(event.state, actions)
                    }
                    is RemoteClose -> {
                        val actions = closingType.remoteCommitPublished.run { doPublish(event.state.channelId, event.state.staticParams.nodeParams.minDepthBlocks.toLong()) }
                        Pair(event.state, actions)
                    }
                    is RevokedClose -> {
                        val actions = closingType.revokedCommitPublished.run { doPublish(event.state.channelId, event.state.staticParams.nodeParams.minDepthBlocks.toLong()) }
                        Pair(event.state, actions)
                    }
                    is RecoveryClose -> {
                        val actions = closingType.remoteCommitPublished.run { doPublish(event.state.channelId, event.state.staticParams.nodeParams.minDepthBlocks.toLong()) }
                        Pair(event.state, actions)
                    }
                    null -> {
                        // in all other cases we need to be ready for any type of closing
                        val commitments = event.state.commitments
                        val actions = mutableListOf<ChannelAction>(
                            ChannelAction.Blockchain.SendWatch(
                                WatchSpent(
                                    event.state.channelId,
                                    commitments.commitInput.outPoint.txid,
                                    commitments.commitInput.outPoint.index.toInt(),
                                    commitments.commitInput.txOut.publicKeyScript,
                                    BITCOIN_FUNDING_SPENT
                                )
                            ),
                        )
                        val minDepth = event.state.staticParams.nodeParams.minDepthBlocks.toLong()
                        event.state.mutualClosePublished.forEach { actions.addAll(doPublish(it, event.state.channelId)) }
                        event.state.localCommitPublished?.run { actions.addAll(doPublish(event.state.channelId, minDepth)) }
                        event.state.remoteCommitPublished?.run { actions.addAll(doPublish(event.state.channelId, minDepth)) }
                        event.state.nextRemoteCommitPublished?.run { actions.addAll(doPublish(event.state.channelId, minDepth)) }
                        event.state.revokedCommitPublished.forEach { it.run { actions.addAll(doPublish(event.state.channelId, minDepth)) } }
                        event.state.futureRemoteCommitPublished?.run { actions.addAll(doPublish(event.state.channelId, minDepth)) }
                        // if commitment number is zero, we also need to make sure that the funding tx has been published
                        if (commitments.localCommit.index == 0L && commitments.remoteCommit.index == 0L) {
                            event.state.fundingTx?.let { actions.add(ChannelAction.Blockchain.PublishTx(it)) }
                        }
                        Pair(event.state, actions)
                    }
                }
            }
            event is ChannelEvent.Restore && event.state is LegacyWaitForFundingConfirmed -> {
                val minDepth = Helpers.minDepthForFunding(staticParams.nodeParams, event.state.commitments.fundingAmount)
                logger.info { "c:${event.state.channelId} restoring legacy unconfirmed channel (waiting for $minDepth confirmations)" }
                val watch = WatchConfirmed(event.state.channelId, event.state.commitments.fundingTxId, event.state.commitments.commitInput.txOut.publicKeyScript, minDepth.toLong(), BITCOIN_FUNDING_DEPTHOK)
                Pair(Offline(event.state), listOf(ChannelAction.Blockchain.SendWatch(watch)))
            }
            event is ChannelEvent.Restore && event.state is WaitForFundingConfirmed -> {
                val minDepth = Helpers.minDepthForFunding(staticParams.nodeParams, event.state.fundingParams.fundingAmount)
                logger.info { "c:${event.state.channelId} restoring unconfirmed channel (waiting for $minDepth confirmations)" }
                val allCommitments = listOf(event.state.commitments) + event.state.previousFundingTxs.map { it.second }
                val watches = allCommitments.map { WatchConfirmed(it.channelId, it.fundingTxId, it.commitInput.txOut.publicKeyScript, minDepth.toLong(), BITCOIN_FUNDING_DEPTHOK) }
                val actions = buildList {
                    event.state.fundingTx.signedTx?.let { add(ChannelAction.Blockchain.PublishTx(it)) }
                    addAll(watches.map { ChannelAction.Blockchain.SendWatch(it) })
                }
                Pair(Offline(event.state), actions)
            }
            event is ChannelEvent.Restore && event.state is ChannelStateWithCommitments -> {
                logger.info { "c:${event.state.channelId} restoring channel" }
                // We only need to republish the funding transaction when using zero-conf: otherwise, it is already confirmed.
                val fundingTx = when {
                    event.state is WaitForFundingLocked && event.state.commitments.channelFeatures.hasFeature(Feature.ZeroConfChannels) -> event.state.fundingTx.signedTx
                    else -> null
                }
                val watchSpent = WatchSpent(
                    event.state.channelId,
                    event.state.commitments.fundingTxId,
                    event.state.commitments.commitInput.outPoint.index.toInt(),
                    event.state.commitments.commitInput.txOut.publicKeyScript,
                    BITCOIN_FUNDING_SPENT
                )
                val actions = buildList {
                    fundingTx?.let {
                        logger.info { "c:${event.state.channelId} republishing funding tx (txId=${it.txid})" }
                        add(ChannelAction.Blockchain.PublishTx(it))
                    }
                    add(ChannelAction.Blockchain.SendWatch(watchSpent))
                }
                Pair(Offline(event.state), actions)
            }
            event is ChannelEvent.NewBlock -> Pair(this.copy(currentTip = Pair(event.height, event.Header)), listOf())
            event is ChannelEvent.SetOnChainFeerates -> Pair(this.copy(currentOnChainFeerates = event.feerates), listOf())
            event is ChannelEvent.ExecuteCommand && event.command is CloseCommand -> Pair(Aborted(staticParams, currentTip, currentOnChainFeerates), listOf())
            else -> unhandled(event)
        }
    }

    private fun isValidChannelType(channelType: ChannelType.SupportedChannelType): Boolean {
        return when (channelType) {
            ChannelType.SupportedChannelType.AnchorOutputs -> true
            ChannelType.SupportedChannelType.AnchorOutputsZeroConfZeroReserve -> true
            else -> false
        }
    }

    override fun handleLocalError(event: ChannelEvent, t: Throwable): Pair<ChannelState, List<ChannelAction>> {
        logger.error(t) { "error on event ${event::class} in state ${this::class}" }
        return Pair(this, listOf(ChannelAction.ProcessLocalError(t, event)))
    }
}
