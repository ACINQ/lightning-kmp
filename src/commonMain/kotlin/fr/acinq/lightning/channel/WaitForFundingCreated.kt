package fr.acinq.lightning.channel

import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.PublicKey
import fr.acinq.lightning.MilliSatoshi
import fr.acinq.lightning.blockchain.electrum.WalletState
import fr.acinq.lightning.blockchain.fee.FeeratePerKw
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
    val wallet: WalletState,
    val interactiveTxSession: InteractiveTxSession,
    val localPushAmount: MilliSatoshi,
    val remotePushAmount: MilliSatoshi,
    val commitTxFeerate: FeeratePerKw,
    val remoteFirstPerCommitmentPoint: PublicKey,
    val remoteSecondPerCommitmentPoint: PublicKey,
    val channelFlags: Byte,
    val channelConfig: ChannelConfig,
    val channelFeatures: ChannelFeatures,
    val channelOrigin: ChannelOrigin?
) : ChannelState() {
    val channelId: ByteVector32 = interactiveTxSession.fundingParams.channelId

    override fun ChannelContext.processInternal(cmd: ChannelCommand): Pair<ChannelState, List<ChannelAction>> {
        return when {
            cmd is ChannelCommand.MessageReceived && cmd.message is InteractiveTxConstructionMessage -> {
                val (interactiveTxSession1, interactiveTxAction) = interactiveTxSession.receive(cmd.message)
                when (interactiveTxAction) {
                    is InteractiveTxSessionAction.SendMessage -> Pair(this@WaitForFundingCreated.copy(interactiveTxSession = interactiveTxSession1), listOf(ChannelAction.Message.Send(interactiveTxAction.msg)))
                    is InteractiveTxSessionAction.SignSharedTx -> {
                        val firstCommitTxRes = Helpers.Funding.makeFirstCommitTxs(
                            keyManager,
                            channelId,
                            localParams,
                            remoteParams,
                            interactiveTxSession.fundingParams.localAmount,
                            interactiveTxSession.fundingParams.remoteAmount,
                            localPushAmount,
                            remotePushAmount,
                            commitTxFeerate,
                            interactiveTxAction.sharedTx.buildUnsignedTx().hash,
                            interactiveTxAction.sharedOutputIndex,
                            remoteFirstPerCommitmentPoint
                        )
                        when (firstCommitTxRes) {
                            is Either.Left -> {
                                logger.error(firstCommitTxRes.value) { "cannot create first commit tx" }
                                handleLocalError(cmd, firstCommitTxRes.value)
                            }
                            is Either.Right -> {
                                val firstCommitTx = firstCommitTxRes.value
                                val localSigOfRemoteTx = keyManager.sign(firstCommitTx.remoteCommitTx, localParams.channelKeys(keyManager).fundingPrivateKey)
                                val commitSig = CommitSig(channelId, localSigOfRemoteTx, listOf())
                                val nextState = WaitForFundingSigned(
                                    localParams,
                                    remoteParams,
                                    wallet,
                                    interactiveTxSession1.fundingParams,
                                    localPushAmount,
                                    remotePushAmount,
                                    interactiveTxAction.sharedTx,
                                    firstCommitTx,
                                    remoteFirstPerCommitmentPoint,
                                    remoteSecondPerCommitmentPoint,
                                    channelFlags,
                                    channelConfig,
                                    channelFeatures,
                                    channelOrigin
                                )
                                val actions = buildList {
                                    interactiveTxAction.txComplete?.let { add(ChannelAction.Message.Send(it)) }
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
            cmd is ChannelCommand.MessageReceived && cmd.message is CommitSig -> {
                logger.warning { "received commit_sig too early, aborting" }
                handleLocalError(cmd, UnexpectedCommitSig(channelId))
            }
            cmd is ChannelCommand.MessageReceived && cmd.message is TxSignatures -> {
                logger.warning { "received tx_signatures too early, aborting" }
                handleLocalError(cmd, UnexpectedFundingSignatures(channelId))
            }
            cmd is ChannelCommand.MessageReceived && cmd.message is TxInitRbf -> {
                logger.info { "ignoring unexpected tx_init_rbf message" }
                Pair(this@WaitForFundingCreated, listOf(ChannelAction.Message.Send(Warning(channelId, InvalidRbfAttempt(channelId).message))))
            }
            cmd is ChannelCommand.MessageReceived && cmd.message is TxAckRbf -> {
                logger.info { "ignoring unexpected tx_ack_rbf message" }
                Pair(this@WaitForFundingCreated, listOf(ChannelAction.Message.Send(Warning(channelId, InvalidRbfAttempt(channelId).message))))
            }
            cmd is ChannelCommand.MessageReceived && cmd.message is TxAbort -> {
                logger.warning { "our peer aborted the dual funding flow: ascii='${cmd.message.toAscii()}' bin=${cmd.message.data.toHex()}" }
                Pair(Aborted, listOf())
            }
            cmd is ChannelCommand.MessageReceived && cmd.message is Error -> {
                logger.error { "peer sent error: ascii=${cmd.message.toAscii()} bin=${cmd.message.data.toHex()}" }
                Pair(Aborted, listOf())
            }
            cmd is ChannelCommand.ExecuteCommand && cmd.command is CloseCommand -> handleLocalError(cmd, ChannelFundingError(channelId))
            cmd is ChannelCommand.CheckHtlcTimeout -> Pair(this@WaitForFundingCreated, listOf())
            cmd is ChannelCommand.Disconnected -> Pair(Aborted, listOf())
            else -> unhandled(cmd)
        }
    }

    override fun ChannelContext.handleLocalError(cmd: ChannelCommand, t: Throwable): Pair<ChannelState, List<ChannelAction>> {
        logger.error(t) { "error on command ${cmd::class.simpleName} in state ${this@WaitForFundingCreated::class.simpleName}" }
        val error = Error(channelId, t.message)
        return Pair(Aborted, listOf(ChannelAction.Message.Send(error)))
    }
}
