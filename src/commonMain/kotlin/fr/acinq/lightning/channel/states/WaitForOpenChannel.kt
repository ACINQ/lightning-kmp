package fr.acinq.lightning.channel.states

import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.Satoshi
import fr.acinq.lightning.ChannelEvents
import fr.acinq.lightning.MilliSatoshi
import fr.acinq.lightning.blockchain.electrum.WalletState
import fr.acinq.lightning.channel.*
import fr.acinq.lightning.channel.Helpers.Funding.computeChannelId
import fr.acinq.lightning.utils.Either
import fr.acinq.lightning.utils.msat
import fr.acinq.lightning.wire.*

/*
 * We are waiting for our peer to initiate a channel open.
 *
 *       Local                        Remote
 *         |       open_channel2        |
 *         |<---------------------------|
 *         |      accept_channel2       |
 *         |--------------------------->|
 */
data class WaitForOpenChannel(
    val temporaryChannelId: ByteVector32,
    val fundingAmount: Satoshi,
    val pushAmount: MilliSatoshi,
    val wallet: WalletState,
    val localParams: LocalParams,
    val channelConfig: ChannelConfig,
    val remoteInit: Init
) : ChannelState() {
    override fun ChannelContext.processInternal(cmd: ChannelCommand): Pair<ChannelState, List<ChannelAction>> {
        return when {
            cmd is ChannelCommand.MessageReceived ->
                when (cmd.message) {
                    is OpenDualFundedChannel -> {
                        val open = cmd.message
                        when (val res = Helpers.validateParamsNonInitiator(staticParams.nodeParams, open)) {
                            is Either.Right -> {
                                val channelType = res.value
                                val channelFeatures = ChannelFeatures(channelType, localFeatures = localParams.features, remoteFeatures = remoteInit.features)
                                val minimumDepth = if (staticParams.useZeroConf) 0 else Helpers.minDepthForFunding(staticParams.nodeParams, open.fundingAmount)
                                val channelKeys = keyManager.channelKeys(localParams.fundingKeyPath)
                                val accept = AcceptDualFundedChannel(
                                    temporaryChannelId = open.temporaryChannelId,
                                    fundingAmount = fundingAmount,
                                    dustLimit = localParams.dustLimit,
                                    maxHtlcValueInFlightMsat = localParams.maxHtlcValueInFlightMsat,
                                    htlcMinimum = localParams.htlcMinimum,
                                    minimumDepth = minimumDepth.toLong(),
                                    toSelfDelay = localParams.toSelfDelay,
                                    maxAcceptedHtlcs = localParams.maxAcceptedHtlcs,
                                    fundingPubkey = channelKeys.fundingPubKey(0),
                                    revocationBasepoint = channelKeys.revocationBasepoint,
                                    paymentBasepoint = channelKeys.paymentBasepoint,
                                    delayedPaymentBasepoint = channelKeys.delayedPaymentBasepoint,
                                    htlcBasepoint = channelKeys.htlcBasepoint,
                                    firstPerCommitmentPoint = channelKeys.commitmentPoint(0),
                                    secondPerCommitmentPoint = channelKeys.commitmentPoint(1),
                                    tlvStream = TlvStream(
                                        buildSet {
                                            add(ChannelTlv.ChannelTypeTlv(channelType))
                                            if (pushAmount > 0.msat) add(ChannelTlv.PushAmountTlv(pushAmount))
                                        }
                                    ),
                                )
                                val remoteParams = RemoteParams(
                                    nodeId = staticParams.remoteNodeId,
                                    dustLimit = open.dustLimit,
                                    maxHtlcValueInFlightMsat = open.maxHtlcValueInFlightMsat,
                                    htlcMinimum = open.htlcMinimum,
                                    toSelfDelay = open.toSelfDelay,
                                    maxAcceptedHtlcs = open.maxAcceptedHtlcs,
                                    revocationBasepoint = open.revocationBasepoint,
                                    paymentBasepoint = open.paymentBasepoint,
                                    delayedPaymentBasepoint = open.delayedPaymentBasepoint,
                                    htlcBasepoint = open.htlcBasepoint,
                                    features = remoteInit.features
                                )
                                val channelId = computeChannelId(open, accept)
                                val remoteFundingPubkey = open.fundingPubkey
                                val dustLimit = open.dustLimit.max(localParams.dustLimit)
                                val fundingParams = InteractiveTxParams(channelId, false, fundingAmount, open.fundingAmount, remoteFundingPubkey, open.lockTime, dustLimit, open.fundingFeerate)
                                when (val fundingContributions = FundingContributions.create(channelKeys, fundingParams, wallet.deeplyConfirmedUtxos)) {
                                    is Either.Left -> {
                                        logger.error { "could not fund channel: ${fundingContributions.value}" }
                                        Pair(Aborted, listOf(ChannelAction.Message.Send(Error(temporaryChannelId, ChannelFundingError(temporaryChannelId).message))))
                                    }
                                    is Either.Right -> {
                                        val interactiveTxSession = InteractiveTxSession(channelKeys, keyManager.swapInOnChainWallet, fundingParams, 0.msat, 0.msat, fundingContributions.value)
                                        val nextState = WaitForFundingCreated(
                                            localParams,
                                            remoteParams,
                                            interactiveTxSession,
                                            pushAmount,
                                            open.pushAmount,
                                            open.commitmentFeerate,
                                            open.firstPerCommitmentPoint,
                                            open.secondPerCommitmentPoint,
                                            open.channelFlags,
                                            channelConfig,
                                            channelFeatures,
                                            open.origin
                                        )
                                        val actions = listOf(
                                            ChannelAction.ChannelId.IdAssigned(staticParams.remoteNodeId, temporaryChannelId, channelId),
                                            ChannelAction.Message.Send(accept),
                                            ChannelAction.EmitEvent(ChannelEvents.Creating(nextState))
                                        )
                                        Pair(nextState, actions)
                                    }
                                }
                            }
                            is Either.Left -> {
                                logger.error(res.value) { "invalid ${cmd.message::class} in state ${this::class}" }
                                Pair(Aborted, listOf(ChannelAction.Message.Send(Error(temporaryChannelId, res.value.message))))
                            }
                        }
                    }
                    is Error -> {
                        logger.error { "peer sent error: ascii=${cmd.message.toAscii()} bin=${cmd.message.data.toHex()}" }
                        return Pair(Aborted, listOf())
                    }
                    else -> unhandled(cmd)
                }

            cmd is ChannelCommand.Close -> Pair(Aborted, listOf())
            cmd is ChannelCommand.CheckHtlcTimeout -> Pair(this@WaitForOpenChannel, listOf())
            else -> unhandled(cmd)
        }
    }

    override fun ChannelContext.handleLocalError(cmd: ChannelCommand, t: Throwable): Pair<ChannelState, List<ChannelAction>> {
        logger.error(t) { "error on command ${cmd::class.simpleName} in state ${this@WaitForOpenChannel::class.simpleName}" }
        val error = Error(temporaryChannelId, t.message)
        return Pair(Aborted, listOf(ChannelAction.Message.Send(error)))
    }
}
