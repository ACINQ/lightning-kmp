package fr.acinq.lightning.channel

import fr.acinq.bitcoin.BlockHeader
import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.Script
import fr.acinq.lightning.Feature
import fr.acinq.lightning.Features
import fr.acinq.lightning.blockchain.fee.OnChainFeerates
import fr.acinq.lightning.channel.Helpers.Funding.computeChannelId
import fr.acinq.lightning.transactions.Scripts
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
                        val open = event.message
                        when (val res = Helpers.validateParamsNonInitiator(staticParams.nodeParams, open, localParams.features, Features(remoteInit.features))) {
                            is Either.Right -> {
                                val channelFeatures = res.value
                                val channelOrigin = open.tlvStream.records.filterIsInstance<ChannelTlv.ChannelOriginTlv>().firstOrNull()?.channelOrigin
                                val minimumDepth = if (channelFeatures.hasFeature(Feature.ZeroConfChannels)) 0 else Helpers.minDepthForFunding(staticParams.nodeParams, open.fundingAmount)
                                val accept = AcceptDualFundedChannel(
                                    temporaryChannelId = open.temporaryChannelId,
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
                                    dustLimit = open.dustLimit,
                                    maxHtlcValueInFlightMsat = open.maxHtlcValueInFlightMsat,
                                    htlcMinimum = open.htlcMinimum,
                                    toSelfDelay = open.toSelfDelay,
                                    maxAcceptedHtlcs = open.maxAcceptedHtlcs,
                                    fundingPubKey = open.fundingPubkey,
                                    revocationBasepoint = open.revocationBasepoint,
                                    paymentBasepoint = open.paymentBasepoint,
                                    delayedPaymentBasepoint = open.delayedPaymentBasepoint,
                                    htlcBasepoint = open.htlcBasepoint,
                                    features = Features(remoteInit.features)
                                )
                                val channelId = computeChannelId(open, accept)
                                val channelIdAssigned = ChannelAction.ChannelId.IdAssigned(staticParams.remoteNodeId, temporaryChannelId, channelId)
                                val localFundingPubkey = localParams.channelKeys.fundingPubKey
                                val fundingPubkeyScript = ByteVector(Script.write(Script.pay2wsh(Scripts.multiSig2of2(localFundingPubkey, remoteParams.fundingPubKey))))
                                val dustLimit = open.dustLimit.max(localParams.dustLimit)
                                val fundingParams = InteractiveTxParams(channelId, false, fundingInputs.fundingAmount, open.fundingAmount, fundingPubkeyScript, open.lockTime, dustLimit, open.fundingFeerate)
                                when (val fundingContributions = FundingContributions.create(fundingParams, fundingInputs.inputs)) {
                                    is Either.Left -> {
                                        logger.error { "c:$temporaryChannelId could not fund channel: ${fundingContributions.value}" }
                                        Pair(Aborted(staticParams, currentTip, currentOnChainFeerates), listOf(ChannelAction.Message.Send(Error(temporaryChannelId, ChannelFundingError(temporaryChannelId).message))))
                                    }
                                    is Either.Right -> {
                                        val interactiveTxSession = InteractiveTxSession(fundingParams, fundingContributions.value)
                                        val nextState = WaitForFundingCreated(
                                            staticParams,
                                            currentTip,
                                            currentOnChainFeerates,
                                            localParams,
                                            remoteParams,
                                            interactiveTxSession,
                                            fundingInputs.privateKeys,
                                            open.pushAmount,
                                            open.commitmentFeerate,
                                            open.firstPerCommitmentPoint,
                                            open.channelFlags,
                                            channelConfig,
                                            channelFeatures,
                                            channelOrigin
                                        )
                                        Pair(nextState, listOf(channelIdAssigned, ChannelAction.Message.Send(accept)))
                                    }
                                }
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
