package fr.acinq.lightning.channel

import fr.acinq.bitcoin.BlockHeader
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.bitcoin.PublicKey
import fr.acinq.lightning.Feature
import fr.acinq.lightning.Lightning
import fr.acinq.lightning.MilliSatoshi
import fr.acinq.lightning.blockchain.BITCOIN_FUNDING_DEPTHOK
import fr.acinq.lightning.blockchain.WatchConfirmed
import fr.acinq.lightning.blockchain.fee.OnChainFeerates
import fr.acinq.lightning.crypto.ShaChain
import fr.acinq.lightning.transactions.Transactions
import fr.acinq.lightning.utils.Either
import fr.acinq.lightning.utils.Try
import fr.acinq.lightning.wire.*

data class WaitForFundingSigned(
    override val staticParams: StaticParams,
    override val currentTip: Pair<Int, BlockHeader>,
    override val currentOnChainFeerates: OnChainFeerates,
    val localParams: LocalParams,
    val remoteParams: RemoteParams,
    val fundingParams: InteractiveTxParams,
    val pushAmount: MilliSatoshi,
    val fundingTx: SharedTransaction,
    val fundingPrivateKeys: List<PrivateKey>,
    val firstCommitTx: Helpers.Funding.FirstCommitTx,
    val remoteFirstPerCommitmentPoint: PublicKey,
    val channelFlags: Byte,
    val channelConfig: ChannelConfig,
    val channelFeatures: ChannelFeatures,
    val channelOrigin: ChannelOrigin?
) : ChannelState() {
    val channelId: ByteVector32 = fundingParams.channelId

    override fun processInternal(event: ChannelEvent): Pair<ChannelState, List<ChannelAction>> {
        return when {
            event is ChannelEvent.MessageReceived && event.message is CommitSig -> {
                val fundingPubKey = localParams.channelKeys.fundingPubKey
                val localSigOfLocalTx = keyManager.sign(firstCommitTx.localCommitTx, localParams.channelKeys.fundingPrivateKey)
                val signedLocalCommitTx = Transactions.addSigs(firstCommitTx.localCommitTx, fundingPubKey, remoteParams.fundingPubKey, localSigOfLocalTx, event.message.signature)
                when (Transactions.checkSpendable(signedLocalCommitTx)) {
                    is Try.Failure -> handleLocalError(event, InvalidCommitmentSignature(channelId, signedLocalCommitTx.tx.txid))
                    is Try.Success -> {
                        val commitInput = firstCommitTx.localCommitTx.input
                        val commitments = Commitments(
                            channelConfig, channelFeatures, localParams, remoteParams, channelFlags,
                            LocalCommit(0, firstCommitTx.localSpec, PublishableTxs(signedLocalCommitTx, listOf())),
                            RemoteCommit(0, firstCommitTx.remoteSpec, firstCommitTx.remoteCommitTx.tx.txid, remoteFirstPerCommitmentPoint),
                            LocalChanges(listOf(), listOf(), listOf()),
                            RemoteChanges(listOf(), listOf(), listOf()),
                            localNextHtlcId = 0L, remoteNextHtlcId = 0L,
                            payments = mapOf(),
                            remoteNextCommitInfo = Either.Right(Lightning.randomKey().publicKey()), // we will receive their next per-commitment point in the next message, so we temporarily put a random byte array
                            commitInput, ShaChain.init, channelId, event.message.channelData
                        )
                        when (val signedFundingTx = fundingTx.sign(channelId, fundingPrivateKeys)) {
                            null -> {
                                logger.warning { "c:$channelId could not sign funding tx" }
                                Pair(Aborted(staticParams, currentTip, currentOnChainFeerates), listOf(ChannelAction.Message.Send(Error(channelId, ChannelFundingError(channelId).message))))
                            }
                            else -> {
                                val nextState = WaitForFundingConfirmed(
                                    staticParams,
                                    currentTip,
                                    currentOnChainFeerates,
                                    commitments,
                                    fundingParams,
                                    pushAmount,
                                    signedFundingTx,
                                    listOf(),
                                    fundingPrivateKeys,
                                    currentBlockHeight.toLong(),
                                    null
                                )
                                logger.info { "c:$channelId funding tx created with txId=${commitInput.outPoint.txid}. ${fundingTx.localInputs.size} local inputs, ${fundingTx.remoteInputs.size} remote inputs, ${fundingTx.localOutputs.size} local outputs and ${fundingTx.remoteOutputs.size} remote outputs" }
                                val fundingMinDepth = if (commitments.channelFeatures.hasFeature(Feature.ZeroConfChannels)) 0 else Helpers.minDepthForFunding(staticParams.nodeParams, fundingParams.fundingAmount)
                                logger.info { "c:$channelId will wait for $fundingMinDepth confirmations" }
                                val watchConfirmed = WatchConfirmed(channelId, commitInput.outPoint.txid, commitments.commitInput.txOut.publicKeyScript, fundingMinDepth.toLong(), BITCOIN_FUNDING_DEPTHOK)
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
            }
            event is ChannelEvent.MessageReceived && event.message is TxSignatures -> {
                logger.warning { "c:$channelId received tx_signatures before commit_sig, aborting" }
                handleLocalError(event, UnexpectedFundingSignatures(channelId))
            }
            event is ChannelEvent.MessageReceived && event.message is TxInitRbf -> {
                logger.info { "c:$channelId ignoring unexpected tx_init_rbf message" }
                Pair(this, listOf(ChannelAction.Message.Send(Warning(channelId, InvalidRbfAttempt(channelId).message))))
            }
            event is ChannelEvent.MessageReceived && event.message is TxAckRbf -> {
                logger.info { "c:$channelId ignoring unexpected tx_ack_rbf message" }
                Pair(this, listOf(ChannelAction.Message.Send(Warning(channelId, InvalidRbfAttempt(channelId).message))))
            }
            event is ChannelEvent.MessageReceived && event.message is TxAbort -> {
                logger.warning { "c:$channelId our peer aborted the dual funding flow: ascii='${event.message.toAscii()}' bin=${event.message.data.toHex()}" }
                Pair(Aborted(staticParams, currentTip, currentOnChainFeerates), listOf())
            }
            event is ChannelEvent.MessageReceived && event.message is Error -> {
                logger.error { "c:$channelId peer sent error: ascii=${event.message.toAscii()} bin=${event.message.data.toHex()}" }
                Pair(Aborted(staticParams, currentTip, currentOnChainFeerates), listOf())
            }
            event is ChannelEvent.ExecuteCommand && event.command is CloseCommand -> handleLocalError(event, ChannelFundingError(channelId))
            event is ChannelEvent.CheckHtlcTimeout -> Pair(this, listOf())
            event is ChannelEvent.NewBlock -> Pair(this.copy(currentTip = Pair(event.height, event.Header)), listOf())
            event is ChannelEvent.SetOnChainFeerates -> Pair(this.copy(currentOnChainFeerates = event.feerates), listOf())
            event is ChannelEvent.Disconnected -> Pair(Aborted(staticParams, currentTip, currentOnChainFeerates), listOf())
            else -> unhandled(event)
        }
    }

    override fun handleLocalError(event: ChannelEvent, t: Throwable): Pair<ChannelState, List<ChannelAction>> {
        logger.error(t) { "c:$channelId error on event ${event::class} in state ${this::class}" }
        val error = Error(channelId, t.message)
        return Pair(Aborted(staticParams, currentTip, currentOnChainFeerates), listOf(ChannelAction.Message.Send(error)))
    }
}
