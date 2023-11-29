package fr.acinq.lightning.channel.states

import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.PublicKey
import fr.acinq.lightning.MilliSatoshi
import fr.acinq.lightning.blockchain.fee.FeeratePerKw
import fr.acinq.lightning.channel.*
import fr.acinq.lightning.utils.Either
import fr.acinq.lightning.wire.*

/*
 * We build the funding transaction for a new channel.
 *
 *       Local                        Remote
 *         |       tx_add_input         |
 *         |<---------------------------|
 *         |       tx_add_input         |
 *         |--------------------------->|
 *         |            ...             |
 *         |       tx_add_output        |
 *         |<---------------------------|
 *         |       tx_add_output        |
 *         |--------------------------->|
 *         |            ...             |
 *         |      tx_add_complete       |
 *         |<---------------------------|
 *         |      tx_add_complete       |
 *         |--------------------------->|
 *         |        commit_sig          |
 *         |--------------------------->|
 */
data class WaitForFundingCreated(
    val localParams: LocalParams,
    val remoteParams: RemoteParams,
    val interactiveTxSession: InteractiveTxSession,
    val localPushAmount: MilliSatoshi,
    val remotePushAmount: MilliSatoshi,
    val commitTxFeerate: FeeratePerKw,
    val remoteFirstPerCommitmentPoint: PublicKey,
    val remoteSecondPerCommitmentPoint: PublicKey,
    val channelFlags: Byte,
    val channelConfig: ChannelConfig,
    val channelFeatures: ChannelFeatures,
    val channelOrigin: Origin?
) : ChannelState() {
    val channelId: ByteVector32 = interactiveTxSession.fundingParams.channelId

    override fun ChannelContext.processInternal(cmd: ChannelCommand): Pair<ChannelState, List<ChannelAction>> {
        return when (cmd) {
            is ChannelCommand.MessageReceived -> when (cmd.message) {
                is InteractiveTxConstructionMessage -> {
                    val (interactiveTxSession1, interactiveTxAction) = interactiveTxSession.receive(cmd.message)
                    when (interactiveTxAction) {
                        is InteractiveTxSessionAction.SendMessage -> Pair(this@WaitForFundingCreated.copy(interactiveTxSession = interactiveTxSession1), listOf(ChannelAction.Message.Send(interactiveTxAction.msg)))
                        is InteractiveTxSessionAction.SignSharedTx -> {
                            val channelParams = ChannelParams(channelId, channelConfig, channelFeatures, localParams, remoteParams, channelFlags)
                            val signingSession = InteractiveTxSigningSession.create(
                                keyManager,
                                channelParams,
                                interactiveTxSession.fundingParams,
                                fundingTxIndex = 0,
                                interactiveTxAction.sharedTx,
                                localPushAmount,
                                remotePushAmount,
                                liquidityPurchased = null,
                                localCommitmentIndex = 0,
                                remoteCommitmentIndex = 0,
                                commitTxFeerate,
                                remoteFirstPerCommitmentPoint
                            )
                            when (signingSession) {
                                is Either.Left -> {
                                    logger.error(signingSession.value) { "cannot initiate interactive-tx signing session" }
                                    handleLocalError(cmd, signingSession.value)
                                }
                                is Either.Right -> {
                                    val (session, commitSig) = signingSession.value
                                    val nextState = WaitForFundingSigned(
                                        channelParams,
                                        session,
                                        localPushAmount,
                                        remotePushAmount,
                                        remoteSecondPerCommitmentPoint,
                                        channelOrigin
                                    )
                                    val actions = buildList {
                                        interactiveTxAction.txComplete?.let { add(ChannelAction.Message.Send(it)) }
                                        add(ChannelAction.Storage.StoreState(nextState))
                                        add(ChannelAction.Message.Send(commitSig))
                                    }
                                    Pair(nextState, actions)
                                }
                            }
                        }
                        is InteractiveTxSessionAction.RemoteFailure -> {
                            logger.warning { "interactive-tx failed: $interactiveTxAction" }
                            handleLocalError(cmd, DualFundingAborted(channelId, interactiveTxAction.toString()))
                        }
                    }
                }
                is CommitSig -> {
                    logger.warning { "received commit_sig too early, aborting" }
                    handleLocalError(cmd, UnexpectedCommitSig(channelId))
                }
                is TxSignatures -> {
                    logger.warning { "received tx_signatures too early, aborting" }
                    handleLocalError(cmd, UnexpectedFundingSignatures(channelId))
                }
                is TxInitRbf -> {
                    logger.info { "ignoring unexpected tx_init_rbf message" }
                    Pair(this@WaitForFundingCreated, listOf(ChannelAction.Message.Send(Warning(channelId, InvalidRbfAttempt(channelId).message))))
                }
                is TxAckRbf -> {
                    logger.info { "ignoring unexpected tx_ack_rbf message" }
                    Pair(this@WaitForFundingCreated, listOf(ChannelAction.Message.Send(Warning(channelId, InvalidRbfAttempt(channelId).message))))
                }
                is TxAbort -> {
                    logger.warning { "our peer aborted the dual funding flow: ascii='${cmd.message.toAscii()}' bin=${cmd.message.data.toHex()}" }
                    Pair(Aborted, listOf(ChannelAction.Message.Send(TxAbort(channelId, DualFundingAborted(channelId, "requested by peer").message))))
                }
                is Error -> handleRemoteError(cmd.message)
                else -> unhandled(cmd)
            }
            is ChannelCommand.Close.MutualClose -> Pair(this@WaitForFundingCreated, listOf(ChannelAction.ProcessCmdRes.NotExecuted(cmd, CommandUnavailableInThisState(channelId, stateName))))
            is ChannelCommand.Close.ForceClose -> handleLocalError(cmd, ForcedLocalCommit(channelId))
            is ChannelCommand.Init -> unhandled(cmd)
            is ChannelCommand.Htlc -> unhandled(cmd)
            is ChannelCommand.Commitment -> unhandled(cmd)
            is ChannelCommand.WatchReceived -> unhandled(cmd)
            is ChannelCommand.Funding -> unhandled(cmd)
            is ChannelCommand.Closing -> unhandled(cmd)
            is ChannelCommand.Connected -> unhandled(cmd)
            is ChannelCommand.Disconnected -> Pair(Aborted, listOf())
        }
    }
}
