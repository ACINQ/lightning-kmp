package fr.acinq.lightning.channel

import fr.acinq.lightning.blockchain.BITCOIN_FUNDING_DEPTHOK
import fr.acinq.lightning.blockchain.BITCOIN_FUNDING_SPENT
import fr.acinq.lightning.blockchain.WatchConfirmed
import fr.acinq.lightning.blockchain.WatchSpent
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
                    cmd.wallet,
                    cmd.localParams,
                    cmd.channelConfig,
                    cmd.remoteInit
                )
                Pair(nextState, listOf())
            }
            cmd is ChannelCommand.InitInitiator && isValidChannelType(cmd.channelType) -> {
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
                    fundingPubkey = cmd.localParams.channelKeys(keyManager).fundingPubKey,
                    revocationBasepoint = cmd.localParams.channelKeys(keyManager).revocationBasepoint,
                    paymentBasepoint = cmd.localParams.channelKeys(keyManager).paymentBasepoint,
                    delayedPaymentBasepoint = cmd.localParams.channelKeys(keyManager).delayedPaymentBasepoint,
                    htlcBasepoint = cmd.localParams.channelKeys(keyManager).htlcBasepoint,
                    firstPerCommitmentPoint = keyManager.commitmentPoint(cmd.localParams.channelKeys(keyManager).shaSeed, 0),
                    channelFlags = cmd.channelFlags,
                    tlvStream = TlvStream(
                        buildList {
                            add(ChannelTlv.ChannelTypeTlv(cmd.channelType))
                            if (cmd.pushAmount > 0.msat) add(ChannelTlv.PushAmountTlv(cmd.pushAmount))
                            if (cmd.channelOrigin != null) add(ChannelTlv.ChannelOriginTlv(cmd.channelOrigin))
                        }
                    )
                )
                val nextState = WaitForAcceptChannel(cmd, open)
                Pair(nextState, listOf(ChannelAction.Message.Send(open)))
            }
            cmd is ChannelCommand.InitInitiator -> {
                logger.warning { "c:${cmd.temporaryChannelId(keyManager)} cannot open channel with invalid channel_type=${cmd.channelType.name}" }
                Pair(Aborted, listOf())
            }
            cmd is ChannelCommand.Restore && cmd.state is Closing && cmd.state.nothingAtStake() -> {
                logger.info { "c:${cmd.state.channelId} we have nothing at stake, going straight to CLOSED" }
                Pair(Closed(cmd.state), listOf())
            }
            cmd is ChannelCommand.Restore && cmd.state is Closing -> {
                val closingType = cmd.state.closingTypeAlreadyKnown()
                logger.info { "c:${cmd.state.channelId} channel is closing (closing type = ${closingType?.let { it::class } ?: "unknown yet"})" }
                // if the closing type is known:
                // - there is no need to watch the funding tx because it has already been spent and the spending tx has
                //   already reached mindepth
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
                        val commitments = cmd.state.commitments
                        val actions = mutableListOf<ChannelAction>(
                            ChannelAction.Blockchain.SendWatch(
                                WatchSpent(
                                    cmd.state.channelId,
                                    commitments.commitInput.outPoint.txid,
                                    commitments.commitInput.outPoint.index.toInt(),
                                    commitments.commitInput.txOut.publicKeyScript,
                                    BITCOIN_FUNDING_SPENT
                                )
                            ),
                        )
                        val minDepth = staticParams.nodeParams.minDepthBlocks.toLong()
                        cmd.state.mutualClosePublished.forEach { actions.addAll(doPublish(it, cmd.state.channelId)) }
                        cmd.state.localCommitPublished?.run { actions.addAll(doPublish(cmd.state.channelId, minDepth)) }
                        cmd.state.remoteCommitPublished?.run { actions.addAll(doPublish(cmd.state.channelId, minDepth)) }
                        cmd.state.nextRemoteCommitPublished?.run { actions.addAll(doPublish(cmd.state.channelId, minDepth)) }
                        cmd.state.revokedCommitPublished.forEach { it.run { actions.addAll(doPublish(cmd.state.channelId, minDepth)) } }
                        cmd.state.futureRemoteCommitPublished?.run { actions.addAll(doPublish(cmd.state.channelId, minDepth)) }
                        // if commitment number is zero, we also need to make sure that the funding tx has been published
                        if (commitments.localCommit.index == 0L && commitments.remoteCommit.index == 0L) {
                            cmd.state.fundingTx?.let { actions.add(ChannelAction.Blockchain.PublishTx(it)) }
                        }
                        Pair(cmd.state, actions)
                    }
                }
            }
            cmd is ChannelCommand.Restore && cmd.state is LegacyWaitForFundingConfirmed -> {
                val minDepth = Helpers.minDepthForFunding(staticParams.nodeParams, cmd.state.commitments.fundingAmount)
                logger.info { "c:${cmd.state.channelId} restoring legacy unconfirmed channel (waiting for $minDepth confirmations)" }
                val watch = WatchConfirmed(cmd.state.channelId, cmd.state.commitments.fundingTxId, cmd.state.commitments.commitInput.txOut.publicKeyScript, minDepth.toLong(), BITCOIN_FUNDING_DEPTHOK)
                Pair(Offline(cmd.state), listOf(ChannelAction.Blockchain.SendWatch(watch)))
            }
            cmd is ChannelCommand.Restore && cmd.state is WaitForFundingConfirmed -> {
                val minDepth = Helpers.minDepthForFunding(staticParams.nodeParams, cmd.state.fundingParams.fundingAmount)
                logger.info { "c:${cmd.state.channelId} restoring unconfirmed channel (waiting for $minDepth confirmations)" }
                val allCommitments = listOf(cmd.state.commitments) + cmd.state.previousFundingTxs.map { it.second }
                val watches = allCommitments.map { WatchConfirmed(it.channelId, it.fundingTxId, it.commitInput.txOut.publicKeyScript, minDepth.toLong(), BITCOIN_FUNDING_DEPTHOK) }
                val actions = buildList {
                    cmd.state.fundingTx.signedTx?.let { add(ChannelAction.Blockchain.PublishTx(it)) }
                    addAll(watches.map { ChannelAction.Blockchain.SendWatch(it) })
                }
                Pair(Offline(cmd.state), actions)
            }
            cmd is ChannelCommand.Restore && cmd.state is ChannelStateWithCommitments -> {
                logger.info { "c:${cmd.state.channelId} restoring channel" }
                // We only need to republish the funding transaction when using zero-conf: otherwise, it is already confirmed.
                val fundingTx = when {
                    cmd.state is WaitForChannelReady && staticParams.useZeroConf -> cmd.state.fundingTx.signedTx
                    else -> null
                }
                val watchSpent = WatchSpent(
                    cmd.state.channelId,
                    cmd.state.commitments.fundingTxId,
                    cmd.state.commitments.commitInput.outPoint.index.toInt(),
                    cmd.state.commitments.commitInput.txOut.publicKeyScript,
                    BITCOIN_FUNDING_SPENT
                )
                val actions = buildList {
                    fundingTx?.let {
                        logger.info { "c:${cmd.state.channelId} republishing funding tx (txId=${it.txid})" }
                        add(ChannelAction.Blockchain.PublishTx(it))
                    }
                    add(ChannelAction.Blockchain.SendWatch(watchSpent))
                }
                Pair(Offline(cmd.state), actions)
            }
            cmd is ChannelCommand.ExecuteCommand && cmd.command is CloseCommand -> Pair(Aborted, listOf())
            else -> unhandled(cmd)
        }
    }

    private fun isValidChannelType(channelType: ChannelType.SupportedChannelType): Boolean {
        return when (channelType) {
            ChannelType.SupportedChannelType.AnchorOutputs -> true
            ChannelType.SupportedChannelType.AnchorOutputsZeroReserve -> true
            else -> false
        }
    }

    override fun ChannelContext.handleLocalError(cmd: ChannelCommand, t: Throwable): Pair<ChannelState, List<ChannelAction>> {
        logger.error(t) { "error on event ${cmd::class} in state ${this::class}" }
        return Pair(this@WaitForInit, listOf(ChannelAction.ProcessLocalError(t, cmd)))
    }
}
