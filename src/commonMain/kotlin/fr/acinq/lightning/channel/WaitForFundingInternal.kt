package fr.acinq.lightning.channel

import fr.acinq.bitcoin.BlockHeader
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.PublicKey
import fr.acinq.bitcoin.Satoshi
import fr.acinq.lightning.Lightning
import fr.acinq.lightning.MilliSatoshi
import fr.acinq.lightning.blockchain.fee.FeeratePerKw
import fr.acinq.lightning.blockchain.fee.OnChainFeerates
import fr.acinq.lightning.utils.Either
import fr.acinq.lightning.wire.Error
import fr.acinq.lightning.wire.FundingCreated
import fr.acinq.lightning.wire.OpenChannel

data class WaitForFundingInternal(
    override val staticParams: StaticParams,
    override val currentTip: Pair<Int, BlockHeader>,
    override val currentOnChainFeerates: OnChainFeerates,
    val localParams: LocalParams,
    val remoteParams: RemoteParams,
    val fundingAmount: Satoshi,
    val pushAmount: MilliSatoshi,
    val commitTxFeerate: FeeratePerKw,
    val remoteFirstPerCommitmentPoint: PublicKey,
    val channelConfig: ChannelConfig,
    val channelFeatures: ChannelFeatures,
    val lastSent: OpenChannel
) : ChannelState() {
    val temporaryChannelId: ByteVector32 = localParams.channelKeys.temporaryChannelId

    override fun processInternal(event: ChannelEvent): Pair<ChannelState, List<ChannelAction>> {
        return when {
            event is ChannelEvent.MakeFundingTxResponse -> {
                // let's create the first commitment tx that spends the yet uncommitted funding tx
                val firstCommitTxRes = Helpers.Funding.makeFirstCommitTxs(
                    keyManager,
                    temporaryChannelId,
                    localParams,
                    remoteParams,
                    fundingAmount,
                    pushAmount,
                    commitTxFeerate,
                    event.fundingTx.hash,
                    event.fundingTxOutputIndex,
                    remoteFirstPerCommitmentPoint
                )
                when (firstCommitTxRes) {
                    is Either.Left -> {
                        logger.error(firstCommitTxRes.value) { "c:$temporaryChannelId cannot create first commit tx" }
                        handleLocalError(event, firstCommitTxRes.value)
                    }
                    is Either.Right -> {
                        val firstCommitTx = firstCommitTxRes.value
                        require(event.fundingTx.txOut[event.fundingTxOutputIndex].publicKeyScript == firstCommitTx.localCommitTx.input.txOut.publicKeyScript) { "pubkey script mismatch!" }
                        val localSigOfRemoteTx = keyManager.sign(firstCommitTx.remoteCommitTx, localParams.channelKeys.fundingPrivateKey)
                        // signature of their initial commitment tx that pays remote pushMsat
                        val fundingCreated = FundingCreated(
                            temporaryChannelId = temporaryChannelId,
                            fundingTxid = event.fundingTx.hash,
                            fundingOutputIndex = event.fundingTxOutputIndex,
                            signature = localSigOfRemoteTx
                        )
                        val channelId = Lightning.toLongId(event.fundingTx.hash, event.fundingTxOutputIndex)
                        val channelIdAssigned = ChannelAction.ChannelId.IdAssigned(staticParams.remoteNodeId, temporaryChannelId, channelId) // we notify the peer asap so it knows how to route messages
                        //context.system.eventStream.publish(ChannelIdAssigned(self, remoteNodeId, temporaryChannelId, channelId))
                        // NB: we don't send a ChannelSignatureSent for the first commit
                        val nextState = WaitForFundingSigned(
                            staticParams,
                            currentTip,
                            currentOnChainFeerates,
                            channelId,
                            localParams,
                            remoteParams,
                            event.fundingTx,
                            event.fee,
                            firstCommitTx.localSpec,
                            firstCommitTx.localCommitTx,
                            RemoteCommit(0, firstCommitTx.remoteSpec, firstCommitTx.remoteCommitTx.tx.txid, remoteFirstPerCommitmentPoint),
                            lastSent.channelFlags,
                            channelConfig,
                            channelFeatures,
                            fundingCreated
                        )
                        Pair(nextState, listOf(channelIdAssigned, ChannelAction.Message.Send(fundingCreated)))
                    }
                }
            }
            event is ChannelEvent.MessageReceived && event.message is Error -> {
                logger.error { "c:$temporaryChannelId peer sent error: ascii=${event.message.toAscii()} bin=${event.message.data.toHex()}" }
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
        logger.error(t) { "c:$temporaryChannelId error on event ${event::class} in state ${this::class}" }
        val error = Error(temporaryChannelId, t.message)
        return Pair(Aborted(staticParams, currentTip, currentOnChainFeerates), listOf(ChannelAction.Message.Send(error)))
    }
}
