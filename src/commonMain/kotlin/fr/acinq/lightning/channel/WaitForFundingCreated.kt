package fr.acinq.lightning.channel

import fr.acinq.bitcoin.BlockHeader
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.PublicKey
import fr.acinq.bitcoin.Satoshi
import fr.acinq.lightning.Feature
import fr.acinq.lightning.Lightning
import fr.acinq.lightning.MilliSatoshi
import fr.acinq.lightning.blockchain.BITCOIN_FUNDING_DEPTHOK
import fr.acinq.lightning.blockchain.BITCOIN_FUNDING_SPENT
import fr.acinq.lightning.blockchain.WatchConfirmed
import fr.acinq.lightning.blockchain.WatchSpent
import fr.acinq.lightning.blockchain.fee.FeeratePerKw
import fr.acinq.lightning.blockchain.fee.OnChainFeerates
import fr.acinq.lightning.crypto.ShaChain
import fr.acinq.lightning.transactions.Transactions
import fr.acinq.lightning.utils.Either
import fr.acinq.lightning.utils.Try
import fr.acinq.lightning.wire.AcceptChannel
import fr.acinq.lightning.wire.Error
import fr.acinq.lightning.wire.FundingCreated
import fr.acinq.lightning.wire.FundingSigned

data class WaitForFundingCreated(
    override val staticParams: StaticParams,
    override val currentTip: Pair<Int, BlockHeader>,
    override val currentOnChainFeerates: OnChainFeerates,
    val temporaryChannelId: ByteVector32,
    val localParams: LocalParams,
    val remoteParams: RemoteParams,
    val fundingAmount: Satoshi,
    val pushAmount: MilliSatoshi,
    val commitTxFeerate: FeeratePerKw,
    val remoteFirstPerCommitmentPoint: PublicKey,
    val channelFlags: Byte,
    val channelConfig: ChannelConfig,
    val channelFeatures: ChannelFeatures,
    val channelOrigin: ChannelOrigin?,
    val lastSent: AcceptChannel
) : ChannelState() {
    override fun processInternal(event: ChannelEvent): Pair<ChannelState, List<ChannelAction>> {
        return when {
            event is ChannelEvent.MessageReceived ->
                when (event.message) {
                    is FundingCreated -> {
                        // they fund the channel with their funding tx, so the money is theirs (but we are paid pushMsat)
                        val firstCommitTxRes = Helpers.Funding.makeFirstCommitTxs(
                            keyManager,
                            temporaryChannelId,
                            localParams,
                            remoteParams,
                            fundingAmount,
                            pushAmount,
                            commitTxFeerate,
                            event.message.fundingTxid,
                            event.message.fundingOutputIndex,
                            remoteFirstPerCommitmentPoint
                        )
                        when (firstCommitTxRes) {
                            is Either.Left -> {
                                logger.error(firstCommitTxRes.value) { "c:$temporaryChannelId cannot create first commit tx" }
                                handleLocalError(event, firstCommitTxRes.value)
                            }
                            is Either.Right -> {
                                val firstCommitTx = firstCommitTxRes.value
                                // check remote signature validity
                                val fundingPubKey = localParams.channelKeys.fundingPubKey
                                val localSigOfLocalTx = keyManager.sign(firstCommitTx.localCommitTx, localParams.channelKeys.fundingPrivateKey)
                                val signedLocalCommitTx = Transactions.addSigs(
                                    firstCommitTx.localCommitTx,
                                    fundingPubKey,
                                    remoteParams.fundingPubKey,
                                    localSigOfLocalTx,
                                    event.message.signature
                                )
                                when (val result = Transactions.checkSpendable(signedLocalCommitTx)) {
                                    is Try.Failure -> {
                                        logger.error(result.error) { "c:$temporaryChannelId their first commit sig is not valid for ${firstCommitTx.localCommitTx.tx}" }
                                        handleLocalError(event, result.error)
                                    }
                                    is Try.Success -> {
                                        val localSigOfRemoteTx = keyManager.sign(firstCommitTx.remoteCommitTx, localParams.channelKeys.fundingPrivateKey)
                                        val channelId = Lightning.toLongId(event.message.fundingTxid, event.message.fundingOutputIndex)
                                        // watch the funding tx transaction
                                        val commitInput = firstCommitTx.localCommitTx.input
                                        val fundingSigned = FundingSigned(channelId, localSigOfRemoteTx)
                                        val commitments = Commitments(
                                            channelConfig,
                                            channelFeatures,
                                            localParams,
                                            remoteParams,
                                            channelFlags,
                                            LocalCommit(0, firstCommitTx.localSpec, PublishableTxs(signedLocalCommitTx, listOf())),
                                            RemoteCommit(0, firstCommitTx.remoteSpec, firstCommitTx.remoteCommitTx.tx.txid, remoteFirstPerCommitmentPoint),
                                            LocalChanges(listOf(), listOf(), listOf()),
                                            RemoteChanges(listOf(), listOf(), listOf()),
                                            localNextHtlcId = 0L,
                                            remoteNextHtlcId = 0L,
                                            payments = mapOf(),
                                            remoteNextCommitInfo = Either.Right(Lightning.randomKey().publicKey()), // we will receive their next per-commitment point in the next message, so we temporarily put a random byte array
                                            commitInput,
                                            ShaChain.init,
                                            channelId = channelId
                                        )
                                        // NB: we don't send a ChannelSignatureSent for the first commit
                                        logger.info { "c:$channelId waiting for them to publish the funding tx with fundingTxid=${commitInput.outPoint.txid}" }
                                        val fundingMinDepth = if (commitments.channelFeatures.hasFeature(Feature.ZeroConfChannels)) 0 else Helpers.minDepthForFunding(staticParams.nodeParams, fundingAmount)
                                        logger.info { "c:$channelId will wait for $fundingMinDepth confirmations" }
                                        val watchSpent = WatchSpent(channelId, commitInput.outPoint.txid, commitInput.outPoint.index.toInt(), commitments.commitInput.txOut.publicKeyScript, BITCOIN_FUNDING_SPENT)
                                        val watchConfirmed = WatchConfirmed(channelId, commitInput.outPoint.txid, commitments.commitInput.txOut.publicKeyScript, fundingMinDepth.toLong(), BITCOIN_FUNDING_DEPTHOK)
                                        val nextState = WaitForFundingConfirmed(
                                            staticParams,
                                            currentTip,
                                            currentOnChainFeerates,
                                            commitments,
                                            null,
                                            currentBlockHeight.toLong(),
                                            null,
                                            Either.Right(fundingSigned)
                                        )
                                        val actions = listOf(
                                            ChannelAction.Blockchain.SendWatch(watchSpent),
                                            ChannelAction.Blockchain.SendWatch(watchConfirmed),
                                            ChannelAction.Message.Send(fundingSigned),
                                            ChannelAction.ChannelId.IdSwitch(temporaryChannelId, channelId),
                                            ChannelAction.Storage.StoreState(nextState)
                                        )
                                        Pair(nextState, actions)
                                    }
                                }
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
