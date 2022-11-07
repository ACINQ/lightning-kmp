package fr.acinq.lightning.channel

import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.PublicKey
import fr.acinq.bitcoin.crypto.Pack
import fr.acinq.lightning.MilliSatoshi
import fr.acinq.lightning.ShortChannelId
import fr.acinq.lightning.blockchain.BITCOIN_FUNDING_DEPTHOK
import fr.acinq.lightning.blockchain.BITCOIN_FUNDING_SPENT
import fr.acinq.lightning.blockchain.WatchConfirmed
import fr.acinq.lightning.blockchain.WatchSpent
import fr.acinq.lightning.blockchain.electrum.WalletState
import fr.acinq.lightning.wire.*
import kotlin.math.absoluteValue

/*
 * We exchange signatures for a new channel.
 *
 *       Local                        Remote
 *         |         commit_sig         |
 *         |<---------------------------|
 *         |        tx_signatures       |
 *         |--------------------------->|
 */
data class WaitForFundingSigned(
    val localParams: LocalParams,
    val remoteParams: RemoteParams,
    val wallet: WalletState,
    val fundingParams: InteractiveTxParams,
    val localPushAmount: MilliSatoshi,
    val remotePushAmount: MilliSatoshi,
    val fundingTx: SharedTransaction,
    val firstCommitTx: Helpers.Funding.FirstCommitTx,
    val remoteFirstPerCommitmentPoint: PublicKey,
    val channelFlags: Byte,
    val channelConfig: ChannelConfig,
    val channelFeatures: ChannelFeatures,
    val channelOrigin: ChannelOrigin?
) : ChannelState() {
    val channelId: ByteVector32 = fundingParams.channelId

    override fun ChannelContext.processInternal(cmd: ChannelCommand): Pair<ChannelState, List<ChannelAction>> {
        return when {
            cmd is ChannelCommand.MessageReceived && cmd.message is CommitSig -> {
                val firstCommitmentsRes = Helpers.Funding.receiveFirstCommit(
                    keyManager, localParams, remoteParams,
                    fundingTx,
                    firstCommitTx, cmd.message,
                    channelConfig, channelFeatures, channelFlags, remoteFirstPerCommitmentPoint
                )
                when (firstCommitmentsRes) {
                    Helpers.Funding.InvalidRemoteCommitSig -> handleLocalError(cmd, InvalidCommitmentSignature(channelId, firstCommitTx.localCommitTx.tx.txid))
                    Helpers.Funding.FundingSigFailure -> {
                        logger.warning { "c:$channelId could not sign funding tx" }
                        Pair(Aborted, listOf(ChannelAction.Message.Send(Error(channelId, ChannelFundingError(channelId).message))))
                    }
                    is Helpers.Funding.FirstCommitments -> {
                        val (signedFundingTx, commitments) = firstCommitmentsRes
                        logger.info { "c:$channelId funding tx created with txId=${commitments.fundingTxId}. ${fundingTx.localInputs.size} local inputs, ${fundingTx.remoteInputs.size} remote inputs, ${fundingTx.localOutputs.size} local outputs and ${fundingTx.remoteOutputs.size} remote outputs" }
                        if (staticParams.useZeroConf) {
                            logger.info { "c:$channelId channel is using 0-conf, we won't wait for the funding tx to confirm" }
                            val watchSpent = WatchSpent(channelId, commitments.fundingTxId, commitments.commitInput.outPoint.index.toInt(), commitments.commitInput.txOut.publicKeyScript, BITCOIN_FUNDING_SPENT)
                            val nextPerCommitmentPoint = keyManager.commitmentPoint(commitments.localParams.channelKeys(keyManager).shaSeed, 1)
                            val channelReady = ChannelReady(commitments.channelId, nextPerCommitmentPoint, TlvStream(listOf(ChannelReadyTlv.ShortChannelIdTlv(ShortChannelId.peerId(staticParams.nodeParams.nodeId)))))
                            // We use part of the funding txid to create a dummy short channel id.
                            // This gives us a probability of collisions of 0.1% for 5 0-conf channels and 1% for 20
                            // Collisions mean that users may temporarily see incorrect numbers for their 0-conf channels (until they've been confirmed).
                            val shortChannelId = ShortChannelId(0, Pack.int32BE(commitments.fundingTxId.slice(0, 16).toByteArray()).absoluteValue, commitments.commitInput.outPoint.index.toInt())
                            val nextState = WaitForChannelReady(commitments, fundingParams, signedFundingTx, shortChannelId, channelReady)
                            val actions = buildList {
                                add(ChannelAction.Blockchain.SendWatch(watchSpent))
                                if (fundingParams.shouldSignFirst(localParams.nodeId, remoteParams.nodeId)) add(ChannelAction.Message.Send(signedFundingTx.localSigs))
                                add(ChannelAction.Message.Send(channelReady))
                                add(ChannelAction.Storage.StoreState(nextState))
                            }
                            Pair(nextState, actions)
                        } else {
                            val fundingMinDepth = Helpers.minDepthForFunding(staticParams.nodeParams, fundingParams.fundingAmount)
                            logger.info { "c:$channelId will wait for $fundingMinDepth confirmations" }
                            val watchConfirmed = WatchConfirmed(channelId, commitments.fundingTxId, commitments.commitInput.txOut.publicKeyScript, fundingMinDepth.toLong(), BITCOIN_FUNDING_DEPTHOK)
                            val nextState = WaitForFundingConfirmed(
                                commitments,
                                fundingParams,
                                localPushAmount,
                                remotePushAmount,
                                signedFundingTx,
                                listOf(),
                                currentBlockHeight.toLong(),
                                null
                            )
                            val actions = buildList {
                                add(ChannelAction.Blockchain.SendWatch(watchConfirmed))
                                add(ChannelAction.Storage.StoreState(nextState))
                                if (fundingParams.shouldSignFirst(localParams.nodeId, remoteParams.nodeId)) add(ChannelAction.Message.Send(signedFundingTx.localSigs))
                            }
                            Pair(nextState, actions)
                        }
                    }
                }
            }
            cmd is ChannelCommand.MessageReceived && cmd.message is TxSignatures -> {
                logger.warning { "c:$channelId received tx_signatures before commit_sig, aborting" }
                handleLocalError(cmd, UnexpectedFundingSignatures(channelId))
            }
            cmd is ChannelCommand.MessageReceived && cmd.message is TxInitRbf -> {
                logger.info { "c:$channelId ignoring unexpected tx_init_rbf message" }
                Pair(this@WaitForFundingSigned, listOf(ChannelAction.Message.Send(Warning(channelId, InvalidRbfAttempt(channelId).message))))
            }
            cmd is ChannelCommand.MessageReceived && cmd.message is TxAckRbf -> {
                logger.info { "c:$channelId ignoring unexpected tx_ack_rbf message" }
                Pair(this@WaitForFundingSigned, listOf(ChannelAction.Message.Send(Warning(channelId, InvalidRbfAttempt(channelId).message))))
            }
            cmd is ChannelCommand.MessageReceived && cmd.message is TxAbort -> {
                logger.warning { "c:$channelId our peer aborted the dual funding flow: ascii='${cmd.message.toAscii()}' bin=${cmd.message.data.toHex()}" }
                Pair(Aborted, listOf())
            }
            cmd is ChannelCommand.MessageReceived && cmd.message is Error -> {
                logger.error { "c:$channelId peer sent error: ascii=${cmd.message.toAscii()} bin=${cmd.message.data.toHex()}" }
                Pair(Aborted, listOf())
            }
            cmd is ChannelCommand.ExecuteCommand && cmd.command is CloseCommand -> handleLocalError(cmd, ChannelFundingError(channelId))
            cmd is ChannelCommand.CheckHtlcTimeout -> Pair(this@WaitForFundingSigned, listOf())
            cmd is ChannelCommand.Disconnected -> Pair(Aborted, listOf())
            else -> unhandled(cmd)
        }
    }

    override fun ChannelContext.handleLocalError(cmd: ChannelCommand, t: Throwable): Pair<ChannelState, List<ChannelAction>> {
        logger.error(t) { "c:$channelId error on event ${cmd::class} in state ${this::class}" }
        val error = Error(channelId, t.message)
        return Pair(Aborted, listOf(ChannelAction.Message.Send(error)))
    }
}
