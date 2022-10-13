package fr.acinq.lightning.channel

import fr.acinq.bitcoin.*
import fr.acinq.lightning.ChannelEvents
import fr.acinq.lightning.Features
import fr.acinq.lightning.MilliSatoshi
import fr.acinq.lightning.blockchain.electrum.WalletState
import fr.acinq.lightning.blockchain.fee.OnChainFeerates
import fr.acinq.lightning.channel.Helpers.Funding.computeChannelId
import fr.acinq.lightning.transactions.Scripts
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
    override val staticParams: StaticParams,
    override val currentTip: Pair<Int, BlockHeader>,
    override val currentOnChainFeerates: OnChainFeerates,
    val temporaryChannelId: ByteVector32,
    val fundingAmount: Satoshi,
    val pushAmount: MilliSatoshi,
    val wallet: WalletState,
    val localParams: LocalParams,
    val channelConfig: ChannelConfig,
    val remoteInit: Init
) : ChannelState() {
    override fun processInternal(cmd: ChannelCommand): Pair<ChannelState, List<ChannelAction>> {
        return when {
            cmd is ChannelCommand.MessageReceived ->
                when (cmd.message) {
                    is OpenDualFundedChannel -> {
                        val open = cmd.message
                        when (val res = Helpers.validateParamsNonInitiator(staticParams.nodeParams, open, localParams.features)) {
                            is Either.Right -> {
                                val channelFeatures = res.value
                                val minimumDepth = if (staticParams.useZeroConf) 0 else Helpers.minDepthForFunding(staticParams.nodeParams, open.fundingAmount)
                                val accept = AcceptDualFundedChannel(
                                    temporaryChannelId = open.temporaryChannelId,
                                    fundingAmount = fundingAmount,
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
                                    tlvStream = TlvStream(
                                        buildList {
                                            add(ChannelTlv.ChannelTypeTlv(channelFeatures.channelType))
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
                                val fundingParams = InteractiveTxParams(channelId, false, fundingAmount, open.fundingAmount, fundingPubkeyScript, open.lockTime, dustLimit, open.fundingFeerate)
                                when (val fundingContributions = FundingContributions.create(fundingParams, wallet.utxos)) {
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
                                            wallet,
                                            interactiveTxSession,
                                            pushAmount,
                                            open.pushAmount,
                                            open.commitmentFeerate,
                                            open.firstPerCommitmentPoint,
                                            open.channelFlags,
                                            channelConfig,
                                            channelFeatures,
                                            open.origin
                                        )
                                        val actions = listOf(
                                            channelIdAssigned,
                                            ChannelAction.Message.Send(accept),
                                            ChannelAction.EmitEvent(ChannelEvents.Creating(nextState))
                                        )
                                        Pair(nextState, actions)
                                    }
                                }
                            }
                            is Either.Left -> {
                                logger.error(res.value) { "c:$temporaryChannelId invalid ${cmd.message::class} in state ${this::class}" }
                                Pair(Aborted(staticParams, currentTip, currentOnChainFeerates), listOf(ChannelAction.Message.Send(Error(temporaryChannelId, res.value.message))))
                            }
                        }
                    }
                    is Error -> {
                        logger.error { "c:$temporaryChannelId peer sent error: ascii=${cmd.message.toAscii()} bin=${cmd.message.data.toHex()}" }
                        return Pair(Aborted(staticParams, currentTip, currentOnChainFeerates), listOf())
                    }
                    else -> unhandled(cmd)
                }

            cmd is ChannelCommand.ExecuteCommand && cmd.command is CloseCommand -> Pair(Aborted(staticParams, currentTip, currentOnChainFeerates), listOf())
            cmd is ChannelCommand.CheckHtlcTimeout -> Pair(this, listOf())
            cmd is ChannelCommand.NewBlock -> Pair(this.copy(currentTip = Pair(cmd.height, cmd.Header)), listOf())
            cmd is ChannelCommand.SetOnChainFeerates -> Pair(this.copy(currentOnChainFeerates = cmd.feerates), listOf())
            else -> unhandled(cmd)
        }
    }

    override fun handleLocalError(cmd: ChannelCommand, t: Throwable): Pair<ChannelState, List<ChannelAction>> {
        logger.error(t) { "c:$temporaryChannelId error on event ${cmd::class} in state ${this::class}" }
        val error = Error(temporaryChannelId, t.message)
        return Pair(Aborted(staticParams, currentTip, currentOnChainFeerates), listOf(ChannelAction.Message.Send(error)))
    }
}
