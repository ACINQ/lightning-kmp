package fr.acinq.lightning.channel

import fr.acinq.bitcoin.BlockHeader
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.lightning.Feature
import fr.acinq.lightning.Features
import fr.acinq.lightning.blockchain.fee.OnChainFeerates
import fr.acinq.lightning.channel.Helpers.Funding.computeChannelId
import fr.acinq.lightning.utils.Either
import fr.acinq.lightning.wire.*

data class WaitForOpenChannel(
    override val staticParams: StaticParams,
    override val currentTip: Pair<Int, BlockHeader>,
    override val currentOnChainFeerates: OnChainFeerates,
    val temporaryChannelId: ByteVector32,
    val fundingInputs: FundingInputs,
    val localParams: LocalParams,
    val channelConfig: ChannelConfig,
    val remoteInit: Init
) : ChannelState() {
    override fun processInternal(event: ChannelEvent): Pair<ChannelState, List<ChannelAction>> {
        return when {
            event is ChannelEvent.MessageReceived ->
                when (event.message) {
                    is OpenDualFundedChannel -> {
                        when (val res = Helpers.validateParamsNonInitiator(staticParams.nodeParams, event.message, localParams.features, Features(remoteInit.features))) {
                            is Either.Right -> {
                                val channelFeatures = res.value
                                val channelOrigin = event.message.tlvStream.records.filterIsInstance<ChannelTlv.ChannelOriginTlv>().firstOrNull()?.channelOrigin
                                val minimumDepth = if (channelFeatures.hasFeature(Feature.ZeroConfChannels)) 0 else Helpers.minDepthForFunding(staticParams.nodeParams, event.message.fundingAmount)
                                val accept = AcceptDualFundedChannel(
                                    temporaryChannelId = event.message.temporaryChannelId,
                                    fundingAmount = fundingInputs.fundingAmount,
                                    dustLimit = localParams.dustLimit,
                                    maxHtlcValueInFlightMsat = localParams.maxHtlcValueInFlightMsat,
                                    htlcMinimum = localParams.htlcMinimum,
                                    minimumDepth = minimumDepth.toLong(),
                                    toSelfDelay = localParams.toSelfDelay,
                                    maxAcceptedHtlcs = localParams.maxAcceptedHtlcs,
                                    fundingPubkey = localParams.channelKeys.fundingPubKey,
                                    revocationBasepoint = localParams.channelKeys.revocationBasepoint,
                                    paymentBasepoint = localParams.channelKeys.paymentBasepoint,
                                    delayedPaymentBasepoint = localParams.channelKeys.delayedPaymentBasepoint,
                                    htlcBasepoint = localParams.channelKeys.htlcBasepoint,
                                    firstPerCommitmentPoint = keyManager.commitmentPoint(keyManager.channelKeyPath(localParams, channelConfig), 0),
                                    tlvStream = TlvStream(listOf(ChannelTlv.ChannelTypeTlv(channelFeatures.channelType))),
                                )
                                val remoteParams = RemoteParams(
                                    nodeId = staticParams.remoteNodeId,
                                    dustLimit = event.message.dustLimit,
                                    maxHtlcValueInFlightMsat = event.message.maxHtlcValueInFlightMsat,
                                    htlcMinimum = event.message.htlcMinimum,
                                    toSelfDelay = event.message.toSelfDelay,
                                    maxAcceptedHtlcs = event.message.maxAcceptedHtlcs,
                                    fundingPubKey = event.message.fundingPubkey,
                                    revocationBasepoint = event.message.revocationBasepoint,
                                    paymentBasepoint = event.message.paymentBasepoint,
                                    delayedPaymentBasepoint = event.message.delayedPaymentBasepoint,
                                    htlcBasepoint = event.message.htlcBasepoint,
                                    features = Features(remoteInit.features)
                                )
                                val channelId = computeChannelId(event.message, accept)
                                val channelIdAssigned = ChannelAction.ChannelId.IdAssigned(staticParams.remoteNodeId, temporaryChannelId, channelId)
                                // TODO: start interactive-tx flow
                                val nextState = WaitForFundingCreated(
                                    staticParams,
                                    currentTip,
                                    currentOnChainFeerates,
                                    channelId,
                                    localParams,
                                    remoteParams,
                                    event.message.fundingAmount,
                                    event.message.pushAmount,
                                    event.message.commitmentFeerate,
                                    event.message.firstPerCommitmentPoint,
                                    event.message.channelFlags,
                                    channelConfig,
                                    channelFeatures,
                                    channelOrigin,
                                    accept
                                )
                                Pair(nextState, listOf(channelIdAssigned, ChannelAction.Message.Send(accept)))
                            }
                            is Either.Left -> {
                                logger.error(res.value) { "c:$temporaryChannelId invalid ${event.message::class} in state ${this::class}" }
                                Pair(Aborted(staticParams, currentTip, currentOnChainFeerates), listOf(ChannelAction.Message.Send(Error(temporaryChannelId, res.value.message))))
                            }
                        }
                    }
                    is Error -> {
                        logger.error { "c:$temporaryChannelId peer sent error: ascii=${event.message.toAscii()} bin=${event.message.data.toHex()}" }
                        return Pair(Aborted(staticParams, currentTip, currentOnChainFeerates), listOf())
                    }
                    else -> unhandled(event)
                }
            event is ChannelEvent.ExecuteCommand && event.command is CloseCommand -> Pair(Aborted(staticParams, currentTip, currentOnChainFeerates), listOf())
            event is ChannelEvent.CheckHtlcTimeout -> Pair(this, listOf())
            event is ChannelEvent.NewBlock -> Pair(this.copy(currentTip = Pair(event.height, event.Header)), listOf())
            event is ChannelEvent.SetOnChainFeerates -> Pair(this.copy(currentOnChainFeerates = event.feerates), listOf())
            else -> unhandled(event)
        }
    }

    override fun handleLocalError(event: ChannelEvent, t: Throwable): Pair<ChannelState, List<ChannelAction>> {
        logger.error(t) { "c:$temporaryChannelId error on event ${event::class} in state ${this::class}" }
        val error = Error(temporaryChannelId, t.message)
        return Pair(Aborted(staticParams, currentTip, currentOnChainFeerates), listOf(ChannelAction.Message.Send(error)))
    }
}
