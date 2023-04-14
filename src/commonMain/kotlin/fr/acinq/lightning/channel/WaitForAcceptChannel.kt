package fr.acinq.lightning.channel

import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.Script
import fr.acinq.lightning.ChannelEvents
import fr.acinq.lightning.Features
import fr.acinq.lightning.channel.Helpers.Funding.computeChannelId
import fr.acinq.lightning.transactions.Scripts
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
    val init: ChannelCommand.InitInitiator,
    val lastSent: OpenDualFundedChannel
) : ChannelState() {
    val temporaryChannelId: ByteVector32 get() = lastSent.temporaryChannelId

    override fun ChannelContext.processInternal(cmd: ChannelCommand): Pair<ChannelState, List<ChannelAction>> {
        return when {
            cmd is ChannelCommand.MessageReceived && cmd.message is AcceptDualFundedChannel -> {
                val accept = cmd.message
                when (val res = Helpers.validateParamsInitiator(staticParams.nodeParams, init, lastSent, accept)) {
                    is Either.Right -> {
                        val channelFeatures = res.value
                        val remoteParams = RemoteParams(
                            nodeId = staticParams.remoteNodeId,
                            dustLimit = accept.dustLimit,
                            maxHtlcValueInFlightMsat = accept.maxHtlcValueInFlightMsat,
                            htlcMinimum = accept.htlcMinimum,
                            toSelfDelay = accept.toSelfDelay,
                            maxAcceptedHtlcs = accept.maxAcceptedHtlcs,
                            fundingPubKey = accept.fundingPubkey,
                            revocationBasepoint = accept.revocationBasepoint,
                            paymentBasepoint = accept.paymentBasepoint,
                            delayedPaymentBasepoint = accept.delayedPaymentBasepoint,
                            htlcBasepoint = accept.htlcBasepoint,
                            features = Features(init.remoteInit.features)
                        )
                        val channelId = computeChannelId(lastSent, accept)
                        val localFundingPubkey = keyManager.channelKeys(init.localParams.fundingKeyPath).fundingPubKey
                        val fundingPubkeyScript = ByteVector(Script.write(Script.pay2wsh(Scripts.multiSig2of2(localFundingPubkey, remoteParams.fundingPubKey))))
                        val dustLimit = accept.dustLimit.max(init.localParams.dustLimit)
                        val fundingParams = InteractiveTxParams(channelId, true, init.fundingAmount, accept.fundingAmount, fundingPubkeyScript, lastSent.lockTime, dustLimit, lastSent.fundingFeerate)
                        when (val fundingContributions = FundingContributions.create(fundingParams, init.wallet.confirmedUtxos)) {
                            is Either.Left -> {
                                logger.error { "could not fund channel: ${fundingContributions.value}" }
                                Pair(Aborted, listOf(ChannelAction.Message.Send(Error(channelId, ChannelFundingError(channelId).message))))
                            }
                            is Either.Right -> {
                                // The channel initiator always sends the first interactive-tx message.
                                val (interactiveTxSession, interactiveTxAction) = InteractiveTxSession(fundingParams, 0.msat, 0.msat, fundingContributions.value).send()
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
            cmd is ChannelCommand.MessageReceived && cmd.message is Error -> {
                logger.error { "peer sent error: ascii=${cmd.message.toAscii()} bin=${cmd.message.data.toHex()}" }
                Pair(Aborted, listOf())
            }
            cmd is ChannelCommand.ExecuteCommand && cmd.command is CloseCommand -> Pair(Aborted, listOf())
            else -> unhandled(cmd)
        }
    }

    override fun ChannelContext.handleLocalError(cmd: ChannelCommand, t: Throwable): Pair<ChannelState, List<ChannelAction>> {
        logger.error(t) { "error on command ${cmd::class.simpleName} in state ${this@WaitForAcceptChannel::class.simpleName}" }
        val error = Error(temporaryChannelId, t.message)
        return Pair(Aborted, listOf(ChannelAction.Message.Send(error)))
    }
}
