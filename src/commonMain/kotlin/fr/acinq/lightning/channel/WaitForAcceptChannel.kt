package fr.acinq.lightning.channel

import fr.acinq.bitcoin.*
import fr.acinq.lightning.Features
import fr.acinq.lightning.Lightning
import fr.acinq.lightning.blockchain.fee.OnChainFeerates
import fr.acinq.lightning.channel.Helpers.Funding.computeChannelId
import fr.acinq.lightning.transactions.Scripts
import fr.acinq.lightning.utils.Either
import fr.acinq.lightning.utils.sat
import fr.acinq.lightning.utils.sum
import fr.acinq.lightning.wire.AcceptDualFundedChannel
import fr.acinq.lightning.wire.Error
import fr.acinq.lightning.wire.FundingCreated
import fr.acinq.lightning.wire.OpenDualFundedChannel

data class WaitForAcceptChannel(
    override val staticParams: StaticParams,
    override val currentTip: Pair<Int, BlockHeader>,
    override val currentOnChainFeerates: OnChainFeerates,
    val init: ChannelEvent.InitInitiator,
    val lastSent: OpenDualFundedChannel
) : ChannelState() {
    private val temporaryChannelId: ByteVector32 get() = lastSent.temporaryChannelId

    override fun processInternal(event: ChannelEvent): Pair<ChannelState, List<ChannelAction>> {
        return when {
            event is ChannelEvent.MessageReceived && event.message is AcceptDualFundedChannel -> {
                when (val res = Helpers.validateParamsInitiator(staticParams.nodeParams, init, lastSent, event.message)) {
                    is Either.Right -> {
                        val channelFeatures = res.value
                        val remoteParams = RemoteParams(
                            nodeId = staticParams.remoteNodeId,
                            dustLimit = event.message.dustLimit,
                            maxHtlcValueInFlightMsat = event.message.maxHtlcValueInFlightMsat,
                            htlcMinimum = event.message.htlcMinimum,
                            toSelfDelay = event.message.toSelfDelay,
                            maxAcceptedHtlcs = event.message.maxAcceptedHtlcs,
                            fundingPubKey = event.message.fundingPubkey,
                            revocationBasepoint = event.message.revocationBasepoint,
                            paymentBasepoint = event.message.paymentBasepoint,
                            delayedPaymentBasepoint = event.message.delayedPaymentBasepoint,
                            htlcBasepoint = event.message.htlcBasepoint,
                            features = Features(init.remoteInit.features)
                        )
                        val localFundingPubkey = init.localParams.channelKeys.fundingPubKey
                        val fundingPubkeyScript = ByteVector(Script.write(Script.pay2wsh(Scripts.multiSig2of2(localFundingPubkey, remoteParams.fundingPubKey))))
                        val (fundingTx, fundingTxFee) = createFundingTx(fundingPubkeyScript)
                        // let's create the first commitment tx that spends the yet uncommitted funding tx
                        val firstCommitTxRes = Helpers.Funding.makeFirstCommitTxs(
                            keyManager,
                            temporaryChannelId,
                            init.localParams,
                            remoteParams,
                            init.fundingAmount,
                            event.message.fundingAmount,
                            init.pushAmount,
                            init.commitTxFeerate,
                            fundingTx.hash,
                            0,
                            event.message.firstPerCommitmentPoint,
                        )
                        when (firstCommitTxRes) {
                            is Either.Left -> {
                                logger.error(firstCommitTxRes.value) { "c:$temporaryChannelId cannot create first commit tx" }
                                handleLocalError(event, firstCommitTxRes.value)
                            }
                            is Either.Right -> {
                                val firstCommitTx = firstCommitTxRes.value
                                require(fundingTx.txOut[0].publicKeyScript == firstCommitTx.localCommitTx.input.txOut.publicKeyScript) { "pubkey script mismatch!" }
                                val channelId = computeChannelId(lastSent, event.message)
                                val channelIdAssigned = ChannelAction.ChannelId.IdAssigned(staticParams.remoteNodeId, temporaryChannelId, channelId)
                                val localSigOfRemoteTx = keyManager.sign(firstCommitTx.remoteCommitTx, init.localParams.channelKeys.fundingPrivateKey)
                                // signature of their initial commitment tx that pays remote pushMsat
                                val fundingCreated = FundingCreated(
                                    temporaryChannelId = channelId,
                                    fundingTxid = fundingTx.hash,
                                    fundingOutputIndex = 0,
                                    signature = localSigOfRemoteTx
                                )
                                // TODO: start interactive-tx flow
                                val nextState = WaitForFundingSigned(
                                    staticParams,
                                    currentTip,
                                    currentOnChainFeerates,
                                    channelId,
                                    init.localParams,
                                    remoteParams,
                                    fundingTx,
                                    fundingTxFee,
                                    firstCommitTx.localSpec,
                                    firstCommitTx.localCommitTx,
                                    RemoteCommit(0, firstCommitTx.remoteSpec, firstCommitTx.remoteCommitTx.tx.txid, event.message.firstPerCommitmentPoint),
                                    lastSent.channelFlags,
                                    init.channelConfig,
                                    channelFeatures,
                                    fundingCreated
                                )
                                Pair(nextState, listOf(channelIdAssigned, ChannelAction.Message.Send(fundingCreated)))
                            }
                        }
                    }
                    is Either.Left -> {
                        logger.error(res.value) { "c:$temporaryChannelId invalid ${event.message::class} in state ${this::class}" }
                        return Pair(Aborted(staticParams, currentTip, currentOnChainFeerates), listOf(ChannelAction.Message.Send(Error(init.temporaryChannelId, res.value.message))))
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

    private fun createFundingTx(fundingPubkeyScript: ByteVector): Pair<Transaction, Satoshi> {
        val inputs = init.fundingInputs.inputs.map { i -> TxIn(i.outpoint, 0) }
        val unsignedTx = Transaction(2, inputs, listOf(TxOut(init.fundingAmount, fundingPubkeyScript)), currentBlockHeight.toLong())
        val witnesses = init.fundingInputs.inputs.mapIndexed { i, input ->
            val sig = Transaction.signInput(unsignedTx, i, Script.pay2pkh(input.privateKey.publicKey()), SigHash.SIGHASH_ALL, input.amount, SigVersion.SIGVERSION_WITNESS_V0, input.privateKey)
            Script.witnessPay2wpkh(input.privateKey.publicKey(), sig.byteVector())
        }
        val fees = init.fundingInputs.totalAmount - init.fundingAmount
        return Pair(unsignedTx.updateWitnesses(witnesses), fees)
    }

    override fun handleLocalError(event: ChannelEvent, t: Throwable): Pair<ChannelState, List<ChannelAction>> {
        logger.error(t) { "c:$temporaryChannelId error on event ${event::class} in state ${this::class}" }
        val error = Error(init.temporaryChannelId, t.message)
        return Pair(Aborted(staticParams, currentTip, currentOnChainFeerates), listOf(ChannelAction.Message.Send(error)))
    }
}
