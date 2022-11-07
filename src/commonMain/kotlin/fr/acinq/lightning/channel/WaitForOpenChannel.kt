package fr.acinq.lightning.channel

import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.Satoshi
import fr.acinq.bitcoin.Script
import fr.acinq.lightning.ChannelEvents
import fr.acinq.lightning.Features
import fr.acinq.lightning.MilliSatoshi
import fr.acinq.lightning.blockchain.electrum.WalletState
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
                                    fundingPubkey = localParams.channelKeys(keyManager).fundingPubKey,
                                    revocationBasepoint = localParams.channelKeys(keyManager).revocationBasepoint,
                                    paymentBasepoint = localParams.channelKeys(keyManager).paymentBasepoint,
                                    delayedPaymentBasepoint = localParams.channelKeys(keyManager).delayedPaymentBasepoint,
                                    htlcBasepoint = localParams.channelKeys(keyManager).htlcBasepoint,
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
                                val localFundingPubkey = localParams.channelKeys(keyManager).fundingPubKey
                                val fundingPubkeyScript = ByteVector(Script.write(Script.pay2wsh(Scripts.multiSig2of2(localFundingPubkey, remoteParams.fundingPubKey))))
                                val dustLimit = open.dustLimit.max(localParams.dustLimit)
                                val fundingParams = InteractiveTxParams(channelId, false, fundingAmount, open.fundingAmount, fundingPubkeyScript, open.lockTime, dustLimit, open.fundingFeerate)
                                when (val fundingContributions = FundingContributions.create(fundingParams, wallet.utxos)) {
                                    is Either.Left -> {
                                        logger.error { "c:$temporaryChannelId could not fund channel: ${fundingContributions.value}" }
                                        Pair(Aborted, listOf(ChannelAction.Message.Send(Error(temporaryChannelId, ChannelFundingError(temporaryChannelId).message))))
                                    }
                                    is Either.Right -> {
                                        val interactiveTxSession = InteractiveTxSession(fundingParams, fundingContributions.value)
                                        val nextState = WaitForFundingCreated(
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
                                Pair(Aborted, listOf(ChannelAction.Message.Send(Error(temporaryChannelId, res.value.message))))
                            }
                        }
                    }
                    is Error -> {
                        logger.error { "c:$temporaryChannelId peer sent error: ascii=${cmd.message.toAscii()} bin=${cmd.message.data.toHex()}" }
                        return Pair(Aborted, listOf())
                    }
                    else -> unhandled(cmd)
                }

            cmd is ChannelCommand.ExecuteCommand && cmd.command is CloseCommand -> Pair(Aborted, listOf())
            cmd is ChannelCommand.CheckHtlcTimeout -> Pair(this@WaitForOpenChannel, listOf())
            else -> unhandled(cmd)
        }
    }

    override fun ChannelContext.handleLocalError(cmd: ChannelCommand, t: Throwable): Pair<ChannelState, List<ChannelAction>> {
        logger.error(t) { "c:$temporaryChannelId error on event ${cmd::class} in state ${this::class}" }
        val error = Error(temporaryChannelId, t.message)
        return Pair(Aborted, listOf(ChannelAction.Message.Send(error)))
    }
}
