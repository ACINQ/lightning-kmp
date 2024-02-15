package fr.acinq.lightning.channel.states

import fr.acinq.bitcoin.ByteVector32
import fr.acinq.lightning.ChannelEvents
import fr.acinq.lightning.channel.*
import fr.acinq.lightning.channel.Helpers.Funding.computeChannelId
import fr.acinq.lightning.utils.Either
import fr.acinq.lightning.utils.msat
import fr.acinq.lightning.wire.AcceptDualFundedChannel
import fr.acinq.lightning.wire.Error
import fr.acinq.lightning.wire.OpenDualFundedChannel

/*
 * We initiated a channel open and are waiting for our peer to accept it.
 *
 *       Local                        Remote
 *         |      accept_channel2       |
 *         |<---------------------------|
 *         |       tx_add_input         |
 *         |--------------------------->|
 */
data class WaitForAcceptChannel(
    val init: ChannelCommand.Init.Initiator,
    val lastSent: OpenDualFundedChannel
) : ChannelState() {
    val temporaryChannelId: ByteVector32 get() = lastSent.temporaryChannelId

    override fun ChannelContext.processInternal(cmd: ChannelCommand): Pair<ChannelState, List<ChannelAction>> {
        return when (cmd) {
            is ChannelCommand.MessageReceived -> when (cmd.message) {
                is AcceptDualFundedChannel -> {
                    val accept = cmd.message
                    when (val res = Helpers.validateParamsInitiator(staticParams.nodeParams, init, lastSent, accept)) {
                        is Either.Right -> {
                            val channelType = res.value
                            val channelFeatures = ChannelFeatures(channelType, localFeatures = init.localParams.features, remoteFeatures = init.remoteInit.features)
                            val remoteParams = RemoteParams(
                                nodeId = staticParams.remoteNodeId,
                                dustLimit = accept.dustLimit,
                                maxHtlcValueInFlightMsat = accept.maxHtlcValueInFlightMsat,
                                htlcMinimum = accept.htlcMinimum,
                                toSelfDelay = accept.toSelfDelay,
                                maxAcceptedHtlcs = accept.maxAcceptedHtlcs,
                                revocationBasepoint = accept.revocationBasepoint,
                                paymentBasepoint = accept.paymentBasepoint,
                                delayedPaymentBasepoint = accept.delayedPaymentBasepoint,
                                htlcBasepoint = accept.htlcBasepoint,
                                features = init.remoteInit.features
                            )
                            val channelId = computeChannelId(lastSent, accept)
                            val channelKeys = keyManager.channelKeys(init.localParams.fundingKeyPath)
                            val remoteFundingPubkey = accept.fundingPubkey
                            val dustLimit = accept.dustLimit.max(init.localParams.dustLimit)
                            val fundingParams = InteractiveTxParams(channelId, true, init.fundingAmount, accept.fundingAmount, remoteFundingPubkey, lastSent.lockTime, dustLimit, lastSent.fundingFeerate)
                            when (val fundingContributions = FundingContributions.create(channelKeys, keyManager.swapInOnChainWallet, fundingParams, init.walletInputs)) {
                                is Either.Left -> {
                                    logger.error { "could not fund channel: ${fundingContributions.value}" }
                                    Pair(Aborted, listOf(ChannelAction.Message.Send(Error(channelId, ChannelFundingError(channelId).message))))
                                }
                                is Either.Right -> {
                                    // The channel initiator always sends the first interactive-tx message.
                                    val (interactiveTxSession, interactiveTxAction) = InteractiveTxSession(staticParams.remoteNodeId,  channelKeys, keyManager.swapInOnChainWallet, fundingParams, 0.msat, 0.msat, emptySet(), fundingContributions.value).send()
                                    when (interactiveTxAction) {
                                        is InteractiveTxSessionAction.SendMessage -> {
                                            val nextState = WaitForFundingCreated(
                                                init.localParams,
                                                remoteParams,
                                                interactiveTxSession,
                                                lastSent.pushAmount,
                                                accept.pushAmount,
                                                lastSent.commitmentFeerate,
                                                accept.firstPerCommitmentPoint,
                                                accept.secondPerCommitmentPoint,
                                                lastSent.channelFlags,
                                                init.channelConfig,
                                                channelFeatures,
                                                null
                                            )
                                            val actions = listOf(
                                                ChannelAction.ChannelId.IdAssigned(staticParams.remoteNodeId, temporaryChannelId, channelId),
                                                ChannelAction.Message.Send(interactiveTxAction.msg),
                                                ChannelAction.EmitEvent(ChannelEvents.Creating(nextState))
                                            )
                                            Pair(nextState, actions)
                                        }
                                        else -> {
                                            logger.error { "could not start interactive-tx session: $interactiveTxAction" }
                                            Pair(Aborted, listOf(ChannelAction.Message.Send(Error(channelId, ChannelFundingError(channelId).message))))
                                        }
                                    }
                                }
                            }
                        }
                        is Either.Left -> {
                            logger.error(res.value) { "invalid ${cmd.message::class} in state ${this::class}" }
                            return Pair(Aborted, listOf(ChannelAction.Message.Send(Error(init.temporaryChannelId(keyManager), res.value.message))))
                        }
                    }
                }
                is Error -> handleRemoteError(cmd.message)
                else -> unhandled(cmd)
            }
            is ChannelCommand.Close.MutualClose -> Pair(this@WaitForAcceptChannel, listOf(ChannelAction.ProcessCmdRes.NotExecuted(cmd, CommandUnavailableInThisState(temporaryChannelId, stateName))))
            is ChannelCommand.Close.ForceClose -> handleLocalError(cmd, ForcedLocalCommit(temporaryChannelId))
            is ChannelCommand.Connected -> unhandled(cmd)
            is ChannelCommand.Disconnected -> Pair(Aborted, listOf())
            is ChannelCommand.Init -> unhandled(cmd)
            is ChannelCommand.Commitment -> unhandled(cmd)
            is ChannelCommand.Htlc -> unhandled(cmd)
            is ChannelCommand.WatchReceived -> unhandled(cmd)
            is ChannelCommand.Funding -> unhandled(cmd)
            is ChannelCommand.Closing -> unhandled(cmd)
        }
    }
}
