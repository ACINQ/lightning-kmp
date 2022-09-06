package fr.acinq.lightning.channel

import fr.acinq.bitcoin.BlockHeader
import fr.acinq.bitcoin.ByteVector
import fr.acinq.lightning.blockchain.BITCOIN_FUNDING_DEPTHOK
import fr.acinq.lightning.blockchain.BITCOIN_FUNDING_SPENT
import fr.acinq.lightning.blockchain.WatchConfirmed
import fr.acinq.lightning.blockchain.WatchSpent
import fr.acinq.lightning.blockchain.fee.OnChainFeerates
import fr.acinq.lightning.wire.ChannelTlv
import fr.acinq.lightning.wire.OpenChannel
import fr.acinq.lightning.wire.TlvStream

data class WaitForInit(override val staticParams: StaticParams, override val currentTip: Pair<Int, BlockHeader>, override val currentOnChainFeerates: OnChainFeerates) : ChannelState() {
    override fun processInternal(event: ChannelEvent): Pair<ChannelState, List<ChannelAction>> {
        return when {
            event is ChannelEvent.InitNonInitiator -> {
                val nextState = WaitForOpenChannel(staticParams, currentTip, currentOnChainFeerates, event.temporaryChannelId, event.localParams, event.channelConfig, event.remoteInit)
                Pair(nextState, listOf())
            }
            event is ChannelEvent.InitInitiator && isValidChannelType(event.channelType) -> {
                val fundingPubKey = event.localParams.channelKeys.fundingPubKey
                val paymentBasepoint = event.localParams.channelKeys.paymentBasepoint
                val open = OpenChannel(
                    staticParams.nodeParams.chainHash,
                    temporaryChannelId = event.temporaryChannelId,
                    fundingSatoshis = event.fundingAmount,
                    pushMsat = event.pushAmount,
                    dustLimitSatoshis = event.localParams.dustLimit,
                    maxHtlcValueInFlightMsat = event.localParams.maxHtlcValueInFlightMsat,
                    channelReserveSatoshis = event.localParams.channelReserve,
                    htlcMinimumMsat = event.localParams.htlcMinimum,
                    feeratePerKw = event.initialFeerate,
                    toSelfDelay = event.localParams.toSelfDelay,
                    maxAcceptedHtlcs = event.localParams.maxAcceptedHtlcs,
                    fundingPubkey = fundingPubKey,
                    revocationBasepoint = event.localParams.channelKeys.revocationBasepoint,
                    paymentBasepoint = paymentBasepoint,
                    delayedPaymentBasepoint = event.localParams.channelKeys.delayedPaymentBasepoint,
                    htlcBasepoint = event.localParams.channelKeys.htlcBasepoint,
                    firstPerCommitmentPoint = keyManager.commitmentPoint(event.localParams.channelKeys.shaSeed, 0),
                    channelFlags = event.channelFlags,
                    tlvStream = TlvStream(
                        buildList {
                            // In order to allow TLV extensions and keep backwards-compatibility, we include an empty upfront_shutdown_script.
                            // See https://github.com/lightningnetwork/lightning-rfc/pull/714.
                            add(ChannelTlv.UpfrontShutdownScriptTlv(ByteVector.empty))
                            add(ChannelTlv.ChannelTypeTlv(event.channelType))
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
            event is ChannelEvent.Restore && event.state is Closing && event.state.commitments.nothingAtStake() -> {
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
                        val actions = closingType.localCommitPublished.doPublish(event.state.channelId, event.state.staticParams.nodeParams.minDepthBlocks.toLong())
                        Pair(event.state, actions)
                    }
                    is RemoteClose -> {
                        val actions = closingType.remoteCommitPublished.doPublish(event.state.channelId, event.state.staticParams.nodeParams.minDepthBlocks.toLong())
                        Pair(event.state, actions)
                    }
                    is RevokedClose -> {
                        val actions = closingType.revokedCommitPublished.doPublish(event.state.channelId, event.state.staticParams.nodeParams.minDepthBlocks.toLong())
                        Pair(event.state, actions)
                    }
                    is RecoveryClose -> {
                        val actions = closingType.remoteCommitPublished.doPublish(event.state.channelId, event.state.staticParams.nodeParams.minDepthBlocks.toLong())
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
                            //SendWatch(WatchLost(event.state.channelId, commitments.commitInput.outPoint.txid, event.state.staticParams.nodeParams.minDepthBlocks.toLong(), BITCOIN_FUNDING_LOST))
                        )
                        val minDepth = event.state.staticParams.nodeParams.minDepthBlocks.toLong()
                        event.state.mutualClosePublished.forEach { actions.addAll(doPublish(it, event.state.channelId)) }
                        event.state.localCommitPublished?.run { actions.addAll(doPublish(event.state.channelId, minDepth)) }
                        event.state.remoteCommitPublished?.run { actions.addAll(doPublish(event.state.channelId, minDepth)) }
                        event.state.nextRemoteCommitPublished?.run { actions.addAll(doPublish(event.state.channelId, minDepth)) }
                        event.state.revokedCommitPublished.forEach { actions.addAll(it.doPublish(event.state.channelId, minDepth)) }
                        event.state.futureRemoteCommitPublished?.run { actions.addAll(doPublish(event.state.channelId, minDepth)) }
                        // if commitment number is zero, we also need to make sure that the funding tx has been published
                        if (commitments.localCommit.index == 0L && commitments.remoteCommit.index == 0L) {
                            actions.add(ChannelAction.Blockchain.GetFundingTx(commitments.commitInput.outPoint.txid))
                        }
                        Pair(event.state, actions)
                    }
                }
            }
            event is ChannelEvent.Restore && event.state is ChannelStateWithCommitments -> {
                logger.info { "c:${event.state.channelId} restoring channel" }
                val watchSpent = WatchSpent(
                    event.state.channelId,
                    event.state.commitments.commitInput.outPoint.txid,
                    event.state.commitments.commitInput.outPoint.index.toInt(),
                    event.state.commitments.commitInput.txOut.publicKeyScript,
                    BITCOIN_FUNDING_SPENT
                )
                val watchConfirmed = WatchConfirmed(
                    event.state.channelId,
                    event.state.commitments.commitInput.outPoint.txid,
                    event.state.commitments.commitInput.txOut.publicKeyScript,
                    staticParams.nodeParams.minDepthBlocks.toLong(),
                    BITCOIN_FUNDING_DEPTHOK
                )
                val actions = listOf(
                    ChannelAction.Blockchain.SendWatch(watchSpent),
                    ChannelAction.Blockchain.SendWatch(watchConfirmed),
                    ChannelAction.Blockchain.GetFundingTx(event.state.commitments.commitInput.outPoint.txid)
                )
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
