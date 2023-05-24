package fr.acinq.lightning.channel.fsm

import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.PublicKey
import fr.acinq.bitcoin.crypto.Pack
import fr.acinq.lightning.ChannelEvents
import fr.acinq.lightning.MilliSatoshi
import fr.acinq.lightning.ShortChannelId
import fr.acinq.lightning.blockchain.BITCOIN_FUNDING_DEPTHOK
import fr.acinq.lightning.blockchain.WatchConfirmed
import fr.acinq.lightning.channel.*
import fr.acinq.lightning.crypto.ShaChain
import fr.acinq.lightning.utils.Either
import fr.acinq.lightning.utils.msat
import fr.acinq.lightning.wire.*
import kotlin.math.absoluteValue

/*
 * We exchange signatures for a new channel.
 * We have already sent our commit_sig.
 * If we send our tx_signatures first, the protocol flow is:
 *
 *       Local                        Remote
 *         |         commit_sig         |
 *         |<---------------------------|
 *         |        tx_signatures       |
 *         |--------------------------->|
 *
 * Otherwise, it is:
 *
 *       Local                        Remote
 *         |         commit_sig         |
 *         |<---------------------------|
 *         |        tx_signatures       |
 *         |<---------------------------|
 *         |        tx_signatures       |
 *         |--------------------------->|
 */
data class WaitForFundingSigned(
    val channelParams: ChannelParams,
    val signingSession: InteractiveTxSigningSession,
    val localPushAmount: MilliSatoshi,
    val remotePushAmount: MilliSatoshi,
    val remoteSecondPerCommitmentPoint: PublicKey,
    val channelOrigin: Origin?,
    val remoteChannelData: EncryptedChannelData = EncryptedChannelData.empty
) : PersistedChannelState() {
    override val channelId: ByteVector32 = channelParams.channelId

    override fun ChannelContext.processInternal(cmd: ChannelCommand): Pair<ChannelState, List<ChannelAction>> {
        return when {
            cmd is ChannelCommand.MessageReceived && cmd.message is CommitSig -> {
                val (signingSession1, action) = signingSession.receiveCommitSig(channelParams.localParams.channelKeys(keyManager), channelParams, cmd.message, currentBlockHeight.toLong())
                when (action) {
                    is InteractiveTxSigningSessionAction.AbortFundingAttempt -> handleLocalError(cmd, action.reason)
                    // No need to store their commit_sig, they will re-send it if we disconnect.
                    InteractiveTxSigningSessionAction.WaitForTxSigs -> Pair(this@WaitForFundingSigned.copy(signingSession = signingSession1, remoteChannelData = cmd.message.channelData), listOf())
                    is InteractiveTxSigningSessionAction.SendTxSigs -> sendTxSigs(action, cmd.message.channelData)
                }
            }
            cmd is ChannelCommand.MessageReceived && cmd.message is TxSignatures -> {
                when (val action = signingSession.receiveTxSigs(channelParams.localParams.channelKeys(keyManager), cmd.message, currentBlockHeight.toLong())) {
                    is InteractiveTxSigningSessionAction.AbortFundingAttempt -> handleLocalError(cmd, action.reason)
                    InteractiveTxSigningSessionAction.WaitForTxSigs -> Pair(this@WaitForFundingSigned, listOf())
                    is InteractiveTxSigningSessionAction.SendTxSigs -> sendTxSigs(action, cmd.message.channelData)
                }
            }
            cmd is ChannelCommand.MessageReceived && cmd.message is TxInitRbf -> {
                logger.info { "ignoring unexpected tx_init_rbf message" }
                Pair(this@WaitForFundingSigned, listOf(ChannelAction.Message.Send(Warning(channelId, InvalidRbfAttempt(channelId).message))))
            }
            cmd is ChannelCommand.MessageReceived && cmd.message is TxAckRbf -> {
                logger.info { "ignoring unexpected tx_ack_rbf message" }
                Pair(this@WaitForFundingSigned, listOf(ChannelAction.Message.Send(Warning(channelId, InvalidRbfAttempt(channelId).message))))
            }
            cmd is ChannelCommand.MessageReceived && cmd.message is TxAbort -> {
                logger.warning { "our peer aborted the dual funding flow: ascii='${cmd.message.toAscii()}' bin=${cmd.message.data.toHex()}" }
                Pair(Aborted, listOf(ChannelAction.Message.Send(TxAbort(channelId, DualFundingAborted(channelId, "requested by peer").message))))
            }
            cmd is ChannelCommand.MessageReceived && cmd.message is Error -> {
                logger.error { "peer sent error: ascii=${cmd.message.toAscii()} bin=${cmd.message.data.toHex()}" }
                Pair(Aborted, listOf())
            }
            cmd is ChannelCommand.Close -> handleLocalError(cmd, ChannelFundingError(channelId))
            cmd is ChannelCommand.CheckHtlcTimeout -> Pair(this@WaitForFundingSigned, listOf())
            // We should be able to complete the channel open when reconnecting.
            cmd is ChannelCommand.Disconnected -> Pair(Offline(this@WaitForFundingSigned), listOf())
            else -> unhandled(cmd)
        }
    }

    private fun ChannelContext.sendTxSigs(action: InteractiveTxSigningSessionAction.SendTxSigs, remoteChannelData: EncryptedChannelData): Pair<ChannelState, List<ChannelAction>> {
        logger.info { "funding tx created with txId=${action.fundingTx.txId}, ${action.fundingTx.sharedTx.tx.localInputs.size} local inputs, ${action.fundingTx.sharedTx.tx.remoteInputs.size} remote inputs, ${action.fundingTx.sharedTx.tx.localOutputs.size} local outputs and ${action.fundingTx.sharedTx.tx.remoteOutputs.size} remote outputs" }
        // We watch for confirmation in all cases, to allow pruning outdated commitments when transactions confirm.
        val fundingMinDepth = Helpers.minDepthForFunding(staticParams.nodeParams, action.fundingTx.fundingParams.fundingAmount)
        val watchConfirmed = WatchConfirmed(channelId, action.commitment.fundingTxId, action.commitment.commitInput.txOut.publicKeyScript, fundingMinDepth.toLong(), BITCOIN_FUNDING_DEPTHOK)
        val commitments = Commitments(
            channelParams,
            CommitmentChanges.init(),
            active = listOf(action.commitment),
            inactive = emptyList(),
            payments = mapOf(),
            remoteNextCommitInfo = Either.Right(remoteSecondPerCommitmentPoint),
            remotePerCommitmentSecrets = ShaChain.init,
            remoteChannelData = remoteChannelData
        )
        val commonActions = buildList {
            action.fundingTx.signedTx?.let { add(ChannelAction.Blockchain.PublishTx(it, ChannelAction.Blockchain.PublishTx.Type.FundingTx)) }
            add(ChannelAction.Blockchain.SendWatch(watchConfirmed))
            add(ChannelAction.Message.Send(action.localSigs))
            // If we receive funds as part of the channel creation, we will add it to our payments db
            if (action.commitment.localCommit.spec.toLocal > 0.msat) add(
                ChannelAction.Storage.StoreIncomingPayment.ViaNewChannel(
                    amount = action.commitment.localCommit.spec.toLocal,
                    serviceFee = channelOrigin?.serviceFee ?: 0.msat,
                    miningFee = channelOrigin?.miningFee ?: action.fundingTx.sharedTx.tx.localFees.truncateToSatoshi(),
                    localInputs = action.fundingTx.sharedTx.tx.localInputs.map { it.outPoint }.toSet(),
                    txId = action.fundingTx.txId,
                    origin = channelOrigin
                )
            )
        }
        return if (staticParams.useZeroConf) {
            logger.info { "channel is using 0-conf, we won't wait for the funding tx to confirm" }
            val nextPerCommitmentPoint = channelParams.localParams.channelKeys(keyManager).commitmentPoint(1)
            val channelReady = ChannelReady(channelId, nextPerCommitmentPoint, TlvStream(ChannelReadyTlv.ShortChannelIdTlv(ShortChannelId.peerId(staticParams.nodeParams.nodeId))))
            // We use part of the funding txid to create a dummy short channel id.
            // This gives us a probability of collisions of 0.1% for 5 0-conf channels and 1% for 20
            // Collisions mean that users may temporarily see incorrect numbers for their 0-conf channels (until they've been confirmed).
            val shortChannelId = ShortChannelId(0, Pack.int32BE(action.commitment.fundingTxId.slice(0, 16).toByteArray()).absoluteValue, action.commitment.commitInput.outPoint.index.toInt())
            val nextState = WaitForChannelReady(commitments, shortChannelId, channelReady)
            val actions = buildList {
                add(ChannelAction.Storage.StoreState(nextState))
                add(ChannelAction.EmitEvent(ChannelEvents.Created(nextState)))
                addAll(commonActions) // NB: order matters
                add(ChannelAction.Message.Send(channelReady))
            }
            Pair(nextState, actions)
        } else {
            logger.info { "will wait for $fundingMinDepth confirmations" }
            val nextState = WaitForFundingConfirmed(
                commitments,
                localPushAmount,
                remotePushAmount,
                currentBlockHeight.toLong(),
                null,
                RbfStatus.None
            )
            val actions = buildList {
                add(ChannelAction.Storage.StoreState(nextState))
                add(ChannelAction.EmitEvent(ChannelEvents.Created(nextState)))
                addAll(commonActions) // NB: order matters
            }
            Pair(nextState, actions)
        }
    }

    override fun ChannelContext.handleLocalError(cmd: ChannelCommand, t: Throwable): Pair<ChannelState, List<ChannelAction>> {
        logger.error(t) { "error on command ${cmd::class.simpleName} in state ${this@WaitForFundingSigned::class.simpleName}" }
        val error = Error(channelId, t.message)
        return Pair(Aborted, listOf(ChannelAction.Message.Send(error)))
    }
}
