package fr.acinq.lightning.channel

import fr.acinq.bitcoin.BlockHeader
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.Satoshi
import fr.acinq.bitcoin.Transaction
import fr.acinq.lightning.Feature
import fr.acinq.lightning.Lightning
import fr.acinq.lightning.blockchain.BITCOIN_FUNDING_DEPTHOK
import fr.acinq.lightning.blockchain.BITCOIN_FUNDING_SPENT
import fr.acinq.lightning.blockchain.WatchConfirmed
import fr.acinq.lightning.blockchain.WatchSpent
import fr.acinq.lightning.blockchain.fee.OnChainFeerates
import fr.acinq.lightning.crypto.ShaChain
import fr.acinq.lightning.transactions.CommitmentSpec
import fr.acinq.lightning.transactions.Transactions
import fr.acinq.lightning.utils.Either
import fr.acinq.lightning.utils.Try
import fr.acinq.lightning.wire.Error
import fr.acinq.lightning.wire.FundingCreated
import fr.acinq.lightning.wire.FundingSigned

data class WaitForFundingSigned(
    override val staticParams: StaticParams,
    override val currentTip: Pair<Int, BlockHeader>,
    override val currentOnChainFeerates: OnChainFeerates,
    val channelId: ByteVector32,
    val localParams: LocalParams,
    val remoteParams: RemoteParams,
    val fundingTx: Transaction,
    val fundingTxFee: Satoshi,
    val localSpec: CommitmentSpec,
    val localCommitTx: Transactions.TransactionWithInputInfo.CommitTx,
    val remoteCommit: RemoteCommit,
    val channelFlags: Byte,
    val channelConfig: ChannelConfig,
    val channelFeatures: ChannelFeatures,
    val lastSent: FundingCreated
) : ChannelState() {
    override fun processInternal(event: ChannelEvent): Pair<ChannelState, List<ChannelAction>> {
        return when {
            event is ChannelEvent.MessageReceived && event.message is FundingSigned -> {
                // we make sure that their sig checks out and that our first commit tx is spendable
                val fundingPubKey = localParams.channelKeys.fundingPubKey
                val localSigOfLocalTx = keyManager.sign(localCommitTx, localParams.channelKeys.fundingPrivateKey)
                val signedLocalCommitTx = Transactions.addSigs(localCommitTx, fundingPubKey, remoteParams.fundingPubKey, localSigOfLocalTx, event.message.signature)
                when (Transactions.checkSpendable(signedLocalCommitTx)) {
                    is Try.Failure -> handleLocalError(event, InvalidCommitmentSignature(channelId, signedLocalCommitTx.tx))
                    is Try.Success -> {
                        val commitInput = localCommitTx.input
                        val commitments = Commitments(
                            channelConfig, channelFeatures, localParams, remoteParams, channelFlags,
                            LocalCommit(0, localSpec, PublishableTxs(signedLocalCommitTx, listOf())), remoteCommit,
                            LocalChanges(listOf(), listOf(), listOf()), RemoteChanges(listOf(), listOf(), listOf()),
                            localNextHtlcId = 0L, remoteNextHtlcId = 0L,
                            payments = mapOf(),
                            remoteNextCommitInfo = Either.Right(Lightning.randomKey().publicKey()), // we will receive their next per-commitment point in the next message, so we temporarily put a random byte array
                            commitInput, ShaChain.init, channelId, event.message.channelData
                        )
                        logger.info { "c:$channelId publishing funding tx for channelId=$channelId fundingTxid=${commitInput.outPoint.txid}" }
                        val watchSpent = WatchSpent(
                            this.channelId,
                            commitments.commitInput.outPoint.txid,
                            commitments.commitInput.outPoint.index.toInt(),
                            commitments.commitInput.txOut.publicKeyScript,
                            BITCOIN_FUNDING_SPENT
                        )
                        val minDepthBlocks = if (commitments.channelFeatures.hasFeature(Feature.ZeroConfChannels)) 0 else staticParams.nodeParams.minDepthBlocks
                        val watchConfirmed = WatchConfirmed(
                            this.channelId,
                            commitments.commitInput.outPoint.txid,
                            commitments.commitInput.txOut.publicKeyScript,
                            minDepthBlocks.toLong(),
                            BITCOIN_FUNDING_DEPTHOK
                        )
                        logger.info { "c:$channelId committing txid=${fundingTx.txid}" }

                        val nextState = WaitForFundingConfirmed(staticParams, currentTip, currentOnChainFeerates, commitments, fundingTx, currentBlockHeight.toLong(), null, Either.Left(lastSent))
                        val actions = listOf(
                            ChannelAction.Blockchain.SendWatch(watchSpent),
                            ChannelAction.Blockchain.SendWatch(watchConfirmed),
                            ChannelAction.Storage.StoreState(nextState),
                            // we will publish the funding tx only after the channel state has been written to disk because we want to
                            // make sure we first persist the commitment that returns back the funds to us in case of problem
                            ChannelAction.Blockchain.PublishTx(fundingTx)
                        )
                        Pair(nextState, actions)
                    }
                }
            }
            event is ChannelEvent.MessageReceived && event.message is Error -> {
                logger.error { "c:$channelId peer sent error: ascii=${event.message.toAscii()} bin=${event.message.data.toHex()}" }
                Pair(Aborted(staticParams, currentTip, currentOnChainFeerates), listOf())
            }
            event is ChannelEvent.ExecuteCommand && event.command is CloseCommand -> Pair(Aborted(staticParams, currentTip, currentOnChainFeerates), listOf())
            event is ChannelEvent.CheckHtlcTimeout -> Pair(this, listOf())
            event is ChannelEvent.NewBlock -> Pair(this.copy(currentTip = Pair(event.height, event.Header)), listOf())
            event is ChannelEvent.SetOnChainFeerates -> Pair(this.copy(currentOnChainFeerates = event.feerates), listOf())
            else -> unhandled(event)
        }
    }

    override fun handleLocalError(event: ChannelEvent, t: Throwable): Pair<ChannelState, List<ChannelAction>> {
        logger.error(t) { "c:$channelId error on event ${event::class} in state ${this::class}" }
        val error = Error(channelId, t.message)
        return Pair(Aborted(staticParams, currentTip, currentOnChainFeerates), listOf(ChannelAction.Message.Send(error)))
    }
}
