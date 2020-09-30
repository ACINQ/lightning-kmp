package fr.acinq.eclair.channel

import fr.acinq.bitcoin.*
import fr.acinq.bitcoin.Crypto.ripemd160
import fr.acinq.bitcoin.Crypto.sha256
import fr.acinq.bitcoin.Script.pay2wsh
import fr.acinq.bitcoin.Script.write
import fr.acinq.eclair.MilliSatoshi
import fr.acinq.eclair.NodeParams
import fr.acinq.eclair.blockchain.fee.FeeEstimator
import fr.acinq.eclair.blockchain.fee.FeeTargets
import fr.acinq.eclair.crypto.ChaCha20Poly1305
import fr.acinq.eclair.crypto.Generators
import fr.acinq.eclair.crypto.KeyManager
import fr.acinq.eclair.transactions.*
import fr.acinq.eclair.transactions.Scripts.multiSig2of2
import fr.acinq.eclair.transactions.Transactions.TransactionWithInputInfo.HtlcSuccessTx
import fr.acinq.eclair.transactions.Transactions.TransactionWithInputInfo.HtlcTimeoutTx
import fr.acinq.eclair.transactions.Transactions.commitTxFee
import fr.acinq.eclair.transactions.Transactions.makeCommitTxOutputs
import fr.acinq.eclair.utils.*
import fr.acinq.eclair.wire.AcceptChannel
import fr.acinq.eclair.wire.OpenChannel
import fr.acinq.eclair.wire.UpdateAddHtlc
import fr.acinq.eclair.wire.UpdateFulfillHtlc
import kotlin.math.abs

object Helpers {
    /**
     * Returns the number of confirmations needed to safely handle the funding transaction,
     * we make sure the cumulative block reward largely exceeds the channel size.
     *
     * @param fundingSatoshis funding amount of the channel
     * @return number of confirmations needed
     */
    fun minDepthForFunding(nodeParams: NodeParams, fundingSatoshis: Satoshi): Int =
        if (fundingSatoshis <= Channel.MAX_FUNDING) nodeParams.minDepthBlocks
        else {
            val blockReward = 6.25f // this is true as of ~May 2020, but will be too large after 2024
            val scalingFactor = 15
            val btc = fundingSatoshis.toLong().toDouble() / 100_000_000L
            val blocksToReachFunding: Int = (((scalingFactor * btc) / blockReward) + 1).toInt()
            kotlin.math.max(nodeParams.minDepthBlocks, blocksToReachFunding)
        }

    /**
     * Called by the funder
     */
    fun validateParamsFunder(nodeParams: NodeParams, open: OpenChannel, accept: AcceptChannel) {
        if (accept.maxAcceptedHtlcs > Channel.MAX_ACCEPTED_HTLCS) throw InvalidMaxAcceptedHtlcs(accept.temporaryChannelId, accept.maxAcceptedHtlcs, Channel.MAX_ACCEPTED_HTLCS)
        // only enforce dust limit check on mainnet
        if (nodeParams.chainHash == Block.LivenetGenesisBlock.hash) {
            if (accept.dustLimitSatoshis < Channel.MIN_DUSTLIMIT) throw DustLimitTooSmall(accept.temporaryChannelId, accept.dustLimitSatoshis, Channel.MIN_DUSTLIMIT)
        }

        // BOLT #2: The receiving node MUST fail the channel if: dust_limit_satoshis is greater than channel_reserve_satoshis.
        if (accept.dustLimitSatoshis > accept.channelReserveSatoshis) throw DustLimitTooLarge(accept.temporaryChannelId, accept.dustLimitSatoshis, accept.channelReserveSatoshis)

        // if minimum_depth is unreasonably large:
        // MAY reject the channel.
        if (accept.toSelfDelay > Channel.MAX_TO_SELF_DELAY || accept.toSelfDelay > nodeParams.maxToLocalDelayBlocks) throw ToSelfDelayTooHigh(accept.temporaryChannelId, accept.toSelfDelay, nodeParams.maxToLocalDelayBlocks)

        if ((open.channelVersion ?: ChannelVersion.STANDARD).isSet(ChannelVersion.ZERO_RESERVE_BIT)) {
            // in zero-reserve channels, we don't make any requirements on the fundee's reserve (set by the funder in the open_message).
        } else {
            // if channel_reserve_satoshis from the open_channel message is less than dust_limit_satoshis:
            // MUST reject the channel. Other fields have the same requirements as their counterparts in open_channel.
            if (open.channelReserveSatoshis < accept.dustLimitSatoshis) throw DustLimitAboveOurChannelReserve(accept.temporaryChannelId, accept.dustLimitSatoshis, open.channelReserveSatoshis)
        }

        // if channel_reserve_satoshis is less than dust_limit_satoshis within the open_channel message:
        //  MUST reject the channel.
        if (accept.channelReserveSatoshis < open.dustLimitSatoshis) throw ChannelReserveBelowOurDustLimit(accept.temporaryChannelId, accept.channelReserveSatoshis, open.dustLimitSatoshis)

        val reserveToFundingRatio = accept.channelReserveSatoshis.toLong().toDouble() / kotlin.math.max(open.fundingSatoshis.toLong(), 1)
        if (reserveToFundingRatio > nodeParams.maxReserveToFundingRatio) throw ChannelReserveTooHigh(open.temporaryChannelId, accept.channelReserveSatoshis, reserveToFundingRatio, nodeParams.maxReserveToFundingRatio)
    }

    /**
     * This indicates whether our side of the channel is above the reserve requested by our counterparty. In other words,
     * this tells if we can use the channel to make a payment.
     *
     */
    fun aboveReserve(commitments: Commitments): Boolean {
        val remoteCommit = when (commitments.remoteNextCommitInfo) {
            is Either.Left -> commitments.remoteNextCommitInfo.value.nextRemoteCommit
            else -> commitments.remoteCommit
        }
        val toRemoteSatoshis = remoteCommit.spec.toRemote.truncateToSatoshi()
        // NB: this is an approximation (we don't take network fees into account)
        return toRemoteSatoshis > commitments.remoteParams.channelReserve
    }

    /**
     * Tells whether or not their expected next remote commitment number matches with our data
     *
     * @return
     *         - true if parties are in sync or remote is behind
     *         - false if we are behind
     */
    fun checkLocalCommit(commitments: Commitments, nextRemoteRevocationNumber: Long): Boolean {
        return when {
            // they just sent a new commit_sig, we have received it but they didn't receive our revocation
            commitments.localCommit.index == nextRemoteRevocationNumber -> true
            // we are in sync
            commitments.localCommit.index == nextRemoteRevocationNumber + 1 -> true
            // remote is behind: we return true because things are fine on our side
            commitments.localCommit.index > nextRemoteRevocationNumber + 1 -> true
            // we are behind
            else -> false
        }
    }

    /**
     * Tells whether or not their expected next local commitment number matches with our data
     *
     * @return
     *         - true if parties are in sync or remote is behind
     *         - false if we are behind
     */
    fun checkRemoteCommit(commitments: Commitments, nextLocalCommitmentNumber: Long): Boolean {
        return when {
            commitments.remoteNextCommitInfo.isLeft ->
                when {
                    // we just sent a new commit_sig but they didn't receive it
                    nextLocalCommitmentNumber == commitments.remoteNextCommitInfo.left!!.nextRemoteCommit.index -> true
                    // we just sent a new commit_sig, they have received it but we haven't received their revocation
                    nextLocalCommitmentNumber == (commitments.remoteNextCommitInfo.left!!.nextRemoteCommit.index + 1) -> true
                    // they are behind
                    nextLocalCommitmentNumber < commitments.remoteNextCommitInfo.left!!.nextRemoteCommit.index -> true
                    else -> false
                }
            commitments.remoteNextCommitInfo.isRight ->
                when {
                    // they have acknowledged the last commit_sig we sent
                    nextLocalCommitmentNumber == (commitments.remoteCommit.index + 1) -> true
                    // they are behind
                    nextLocalCommitmentNumber < (commitments.remoteCommit.index + 1) -> true
                    else -> false
                }
            else -> false
        }
    }

    object Funding {

        fun makeFundingInputInfo(
            fundingTxId: ByteVector32,
            fundingTxOutputIndex: Int,
            fundingSatoshis: Satoshi,
            fundingPubkey1: PublicKey,
            fundingPubkey2: PublicKey
        ): Transactions.InputInfo {
            val fundingScript = multiSig2of2(fundingPubkey1, fundingPubkey2)
            val fundingTxOut = TxOut(fundingSatoshis, pay2wsh(fundingScript))
            return Transactions.InputInfo(
                OutPoint(fundingTxId, fundingTxOutputIndex.toLong()),
                fundingTxOut,
                ByteVector(write(fundingScript))
            )
        }

        data class FirstCommitTx(val localSpec: CommitmentSpec, val localCommitTx: Transactions.TransactionWithInputInfo.CommitTx, val remoteSpec: CommitmentSpec, val remoteCommitTx: Transactions.TransactionWithInputInfo.CommitTx)

        /**
         * Creates both sides's first commitment transaction
         *
         * @return (localSpec, localTx, remoteSpec, remoteTx, fundingTxOutput)
         */
        fun makeFirstCommitTxs(keyManager: KeyManager, channelVersion: ChannelVersion, temporaryChannelId: ByteVector32, localParams: LocalParams, remoteParams: RemoteParams, fundingAmount: Satoshi, pushMsat: MilliSatoshi, initialFeeratePerKw: Long, fundingTxHash: ByteVector32, fundingTxOutputIndex: Int, remoteFirstPerCommitmentPoint: PublicKey): FirstCommitTx {
            val toLocalMsat = if (localParams.isFunder) MilliSatoshi(fundingAmount) - pushMsat else pushMsat
            val toRemoteMsat = if (localParams.isFunder) pushMsat else MilliSatoshi(fundingAmount) - pushMsat

            val localSpec = CommitmentSpec(setOf(), feeratePerKw = initialFeeratePerKw, toLocal = toLocalMsat, toRemote = toRemoteMsat)
            val remoteSpec = CommitmentSpec(setOf(), feeratePerKw = initialFeeratePerKw, toLocal = toRemoteMsat, toRemote = toLocalMsat)

            if (!localParams.isFunder) {
                // they are funder, therefore they pay the fee: we need to make sure they can afford it!
                val localToRemoteMsat = remoteSpec.toLocal
                val fees = commitTxFee(remoteParams.dustLimit, remoteSpec)
                val missing = localToRemoteMsat.truncateToSatoshi() - localParams.channelReserve - fees
                if (missing < Satoshi(0)) {
                    throw CannotAffordFees(temporaryChannelId, missing = -missing, reserve = localParams.channelReserve, fees = fees)
                }
            }

            val fundingPubKey = keyManager.fundingPublicKey(localParams.fundingKeyPath)
            val channelKeyPath = keyManager.channelKeyPath(localParams, channelVersion)
            val commitmentInput = makeFundingInputInfo(fundingTxHash, fundingTxOutputIndex, fundingAmount, fundingPubKey.publicKey, remoteParams.fundingPubKey)
            val localPerCommitmentPoint = keyManager.commitmentPoint(channelKeyPath, 0)
            val localCommitTx = Commitments.makeLocalTxs(keyManager, channelVersion, 0, localParams, remoteParams, commitmentInput, localPerCommitmentPoint, localSpec).first
            val remoteCommitTx = Commitments.makeRemoteTxs(keyManager, channelVersion, 0, localParams, remoteParams, commitmentInput, remoteFirstPerCommitmentPoint, remoteSpec).first

            return FirstCommitTx(localSpec, localCommitTx, remoteSpec, remoteCommitTx)
        }
    }

    object Closing {
        /**
         * Checks if a channel is closed (i.e. its closing tx has been confirmed)
         *
         * @param closing                      channel state data
         * @param additionalConfirmedTx_opt additional confirmed transaction; we need this for the mutual close scenario
         *                                  because we don't store the closing tx in the channel state
         * @return the channel closing type, if applicable
         */
        fun isClosed(closing: HasCommitments, additionalConfirmedTx_opt: Transaction?): ClosingType? {
            if(closing !is fr.acinq.eclair.channel.Closing) return null

            return when {
                closing.mutualClosePublished.contains(additionalConfirmedTx_opt) ->
                    additionalConfirmedTx_opt?.let { MutualClose(it) }
                closing.localCommitPublished != null && closing.localCommitPublished.isLocalCommitDone() ->
                    LocalClose(closing.commitments.localCommit, closing.localCommitPublished)
                closing.remoteCommitPublished != null && closing.remoteCommitPublished.isRemoteCommitDone() ->
                    CurrentRemoteClose(closing.commitments.remoteCommit, closing.remoteCommitPublished)
                closing.nextRemoteCommitPublished != null &&
                        closing.commitments.remoteNextCommitInfo.isLeft &&
                        closing.nextRemoteCommitPublished.isRemoteCommitDone() ->
                    NextRemoteClose(
                        closing.commitments.remoteNextCommitInfo.left?.nextRemoteCommit ?: error("remoteNextCommitInfo must be defined"),
                        closing.nextRemoteCommitPublished
                    )
                closing.futureRemoteCommitPublished != null && closing.futureRemoteCommitPublished.isRemoteCommitDone() ->
                    RecoveryClose(closing.futureRemoteCommitPublished)
                closing.revokedCommitPublished.any { it.isRevokedCommitDone() } ->
                    RevokedClose(closing.revokedCommitPublished.first { it.isRevokedCommitDone() })
                else -> null
            }
        }

        /**
         * A local commit is considered done when:
         * - all commitment tx outputs that we can spend have been spent and confirmed (even if the spending tx was not ours)
         * - all 3rd stage txes (txes spending htlc txes) have been confirmed
         */
        private fun LocalCommitPublished.isLocalCommitDone(): Boolean {
            // is the commitment tx buried? (we need to check this because we may not have any outputs)
            val isCommitTxConfirmed = irrevocablySpent.values.toSet().contains(commitTx.txid)
            // are there remaining spendable outputs from the commitment tx? we just subtract all known spent outputs from the ones we control
            val commitOutputsSpendableByUs = buildList {
                claimMainDelayedOutputTx?.let { add(it) }
                addAll(htlcSuccessTxs)
                addAll(htlcTimeoutTxs)
            }.flatMap { it.txIn.map(TxIn::outPoint) }.toSet() - irrevocablySpent.keys

            // which htlc delayed txes can we expect to be confirmed?
            val unconfirmedHtlcDelayedTxes = claimHtlcDelayedTxs
                // only the txes which parents are already confirmed may get confirmed (note that this also eliminates outputs that have been double-spent by a competing tx)
                .filter { tx -> (tx.txIn.map { it.outPoint.txid }.toSet() - irrevocablySpent.values).isEmpty() }
                // has the tx already been confirmed?
                .filterNot { tx -> irrevocablySpent.values.toSet().contains(tx.txid) }

            return isCommitTxConfirmed && commitOutputsSpendableByUs.isEmpty() && unconfirmedHtlcDelayedTxes.isEmpty()
        }

        /**
         * A remote commit is considered done when all commitment tx outputs that we can spend have been spent and confirmed
         * (even if the spending tx was not ours).
         */
        private fun RemoteCommitPublished.isRemoteCommitDone(): Boolean {
            // is the commitment tx buried? (we need to check this because we may not have any outputs)
            val isCommitTxConfirmed = irrevocablySpent.values.toSet().contains(commitTx.txid)
            // are there remaining spendable outputs from the commitment tx?
            val commitOutputsSpendableByUs = buildList {
                claimMainOutputTx?.let { add(it) }
                addAll(claimHtlcSuccessTxs)
                addAll(claimHtlcTimeoutTxs)
            }.flatMap { it.txIn.map(TxIn::outPoint) }.toSet() - irrevocablySpent.keys

            return isCommitTxConfirmed && commitOutputsSpendableByUs.isEmpty()
        }

        /**
         * A remote commit is considered done when all commitment tx outputs that we can spend have been spent and confirmed
         * (even if the spending tx was not ours).
         */
        private fun RevokedCommitPublished.isRevokedCommitDone(): Boolean {
            // is the commitment tx buried? (we need to check this because we may not have any outputs)
            val isCommitTxConfirmed = irrevocablySpent.values.toSet().contains(commitTx.txid)
            // are there remaining spendable outputs from the commitment tx?
            val commitOutputsSpendableByUs = buildList {
                claimMainOutputTx?.let { add(it) }
                mainPenaltyTx?.let { add(it) }
                addAll(htlcPenaltyTxs)
            }.flatMap { it.txIn.map(TxIn::outPoint) }.toSet() - irrevocablySpent.keys

            // which htlc delayed txes can we expect to be confirmed?
            val unconfirmedHtlcDelayedTxes = claimHtlcDelayedPenaltyTxs
                // only the txes which parents are already confirmed may get confirmed (note that this also eliminates outputs that have been double-spent by a competing tx)
                .filter { tx -> (tx.txIn.map { it.outPoint.txid }.toSet() - irrevocablySpent.values).isEmpty() }
                // has the tx already been confirmed?
                .filterNot { tx -> irrevocablySpent.values.toSet().contains(tx.txid) }

            return isCommitTxConfirmed && commitOutputsSpendableByUs.isEmpty() && unconfirmedHtlcDelayedTxes.isEmpty()
        }

        /**
         * Claim all the HTLCs that we've received from our current commit tx. This will be
         * done using 2nd stage HTLC transactions
         *
         * @param commitments our commitment data, which include payment preimages
         * @return a list of transactions (one per HTLC that we can claim)
         */
        fun claimCurrentLocalCommitTxOutputs(keyManager: KeyManager, commitments: Commitments, tx: Transaction, feeEstimator: FeeEstimator, feeTargets: FeeTargets): LocalCommitPublished {
            val localCommit = commitments.localCommit
            val localParams = commitments.localParams
            val channelVersion = commitments.channelVersion
            require(localCommit.publishableTxs.commitTx.tx.txid == tx.txid) { "txid mismatch, provided tx is not the current local commit tx" }
            val channelKeyPath = keyManager.channelKeyPath(localParams, channelVersion)
            val localPerCommitmentPoint = keyManager.commitmentPoint(channelKeyPath, commitments.localCommit.index)
            val localRevocationPubkey = Generators.revocationPubKey(commitments.remoteParams.revocationBasepoint, localPerCommitmentPoint)
            val localDelayedPubkey = Generators.derivePubKey(keyManager.delayedPaymentPoint(channelKeyPath).publicKey, localPerCommitmentPoint)
            val feeratePerKwDelayed = feeEstimator.getFeeratePerKw(feeTargets.claimMainBlockTarget)

            // first we will claim our main output as soon as the delay is over
            val mainDelayedTx = generateTx {
                Transactions.makeClaimDelayedOutputTx(
                    tx,
                    localParams.dustLimit,
                    localRevocationPubkey,
                    commitments.remoteParams.toSelfDelay,
                    localDelayedPubkey,
                    localParams.defaultFinalScriptPubKey.toByteArray(),
                    feeratePerKwDelayed
                )
            }?.let {
                val sig = keyManager.sign(it, keyManager.delayedPaymentPoint(channelKeyPath), localPerCommitmentPoint)
                Transactions.addSigs(it, sig).tx
            }

            // those are the preimages to existing received htlcs
            val preimages = commitments.localChanges.all.filterIsInstance<UpdateFulfillHtlc>().map { it.paymentPreimage }

            val htlcTxes = localCommit.publishableTxs.htlcTxsAndSigs.mapNotNull {
                val (txinfo, localSig, remoteSig) = it

                when(txinfo) {
                    // incoming htlc for which we have the preimage: we spend it directly
                    is HtlcSuccessTx -> {
                        preimages.firstOrNull { r -> sha256(r).toByteVector() == txinfo.paymentHash }?.let { preimage ->
                            Transactions.addSigs(txinfo, localSig, remoteSig, preimage)
                        }
                    }
                    // (incoming htlc for which we don't have the preimage: nothing to do, it will timeout eventually and they will get their funds back)

                    // outgoing htlc: they may or may not have the preimage, the only thing to do is try to get back our funds after timeout
                    is HtlcTimeoutTx -> Transactions.addSigs(txinfo, localSig, remoteSig)
                    else -> null
                }

            }

            // all htlc output to us are delayed, so we need to claim them as soon as the delay is over
            val htlcDelayedTxes = htlcTxes.mapNotNull { txinfo ->
                generateTx {
                    Transactions.makeClaimDelayedOutputTx(
                        txinfo.tx,
                        localParams.dustLimit,
                        localRevocationPubkey,
                        commitments.remoteParams.toSelfDelay,
                        localDelayedPubkey,
                        localParams.defaultFinalScriptPubKey.toByteArray(),
                        feeratePerKwDelayed
                    )
                }?.let {
                    val sig = keyManager.sign(it, keyManager.delayedPaymentPoint(channelKeyPath), localPerCommitmentPoint)
                    Transactions.addSigs(it, sig).tx
                }
            }


            return LocalCommitPublished(
                commitTx = tx,
                claimMainDelayedOutputTx = mainDelayedTx,
                htlcSuccessTxs = htlcTxes.filterIsInstance<HtlcSuccessTx>().map(HtlcSuccessTx::tx),
                htlcTimeoutTxs = htlcTxes.filterIsInstance<HtlcTimeoutTx>().map(HtlcTimeoutTx::tx),
                claimHtlcDelayedTxs = htlcDelayedTxes
            )
        }

        /**
         * Claim all the HTLCs that we've received from their current commit tx, if the channel used option_static_remotekey
         * we don't need to claim our main output because it directly pays to one of our wallet's p2wpkh addresses.
         *
         * @param commitments  our commitment data, which include payment preimages
         * @param remoteCommit the remote commitment data to use to claim outputs (it can be their current or next commitment)
         * @param tx           the remote commitment transaction that has just been published
         * @return a list of transactions (one per HTLC that we can claim)
         */
        fun claimRemoteCommitTxOutputs(keyManager: KeyManager, commitments: Commitments, remoteCommit: RemoteCommit, tx: Transaction, feeEstimator: FeeEstimator, feeTargets: FeeTargets): RemoteCommitPublished {
            val channelVersion = commitments.channelVersion
            val localParams = commitments.localParams
            val remoteParams = commitments.remoteParams
            val commitInput = commitments.commitInput
            val (remoteCommitTx, _, _) = Commitments.makeRemoteTxs(keyManager, channelVersion, remoteCommit.index, localParams, remoteParams, commitInput, remoteCommit.remotePerCommitmentPoint, remoteCommit.spec)
            require(remoteCommitTx.tx.txid == tx.txid) { "txid mismatch, provided tx is not the current remote commit tx" }
            val channelKeyPath = keyManager.channelKeyPath(localParams, channelVersion)
            val localFundingPubkey = keyManager.fundingPublicKey(localParams.fundingKeyPath).publicKey
            val localHtlcPubkey = Generators.derivePubKey(keyManager.htlcPoint(channelKeyPath).publicKey, remoteCommit.remotePerCommitmentPoint)
            val remoteHtlcPubkey = Generators.derivePubKey(remoteParams.htlcBasepoint, remoteCommit.remotePerCommitmentPoint)
            val remoteRevocationPubkey = Generators.revocationPubKey(keyManager.revocationPoint(channelKeyPath).publicKey, remoteCommit.remotePerCommitmentPoint)
            val remoteDelayedPaymentPubkey = Generators.derivePubKey(remoteParams.delayedPaymentBasepoint, remoteCommit.remotePerCommitmentPoint)
            val localPaymentPubkey = Generators.derivePubKey(keyManager.paymentPoint(channelKeyPath).publicKey, remoteCommit.remotePerCommitmentPoint)
          //val outputs = makeCommitTxOutputs(!localParams.isFunder, remoteParams.dustLimit, remoteRevocationPubkey, localParams.toSelfDelay, remoteDelayedPaymentPubkey, localPaymentPubkey, remoteHtlcPubkey, localHtlcPubkey, remoteParams.fundingPubKey, localFundingPubkey, remoteCommit.spec, commitments.commitmentFormat)
            val outputs = makeCommitTxOutputs(!localParams.isFunder, remoteParams.dustLimit, remoteRevocationPubkey, localParams.toSelfDelay, remoteDelayedPaymentPubkey, localPaymentPubkey, remoteHtlcPubkey, localHtlcPubkey, remoteCommit.spec)

            // we need to use a rather high fee for htlc-claim because we compete with the counterparty
            val feeratePerKwHtlc = feeEstimator.getFeeratePerKw(target = 2)

            // those are the preimages to existing received htlcs
            val preimages = commitments.localChanges.all.filterIsInstance<UpdateFulfillHtlc>().map { it.paymentPreimage }

            // remember we are looking at the remote commitment so IN for them is really OUT for us and vice versa

            val claimHtlcSuccessTxs = remoteCommit.spec.htlcs.filterIsInstance<OutgoingHtlc>().map { it.add }.mapNotNull { add ->
                    // incoming htlc for which we have the preimage: we spend it directly.
                    // NB: we are looking at the remote's commitment, from its point of view it's an outgoing htlc.
                    preimages.firstOrNull { r -> sha256(r).toByteVector() == add.paymentHash }?.let { preimage ->
                        generateTx {
                            Transactions.makeClaimHtlcSuccessTx(
                                remoteCommitTx.tx,
                                outputs,
                                localParams.dustLimit,
                                localHtlcPubkey,
                                remoteHtlcPubkey,
                                remoteRevocationPubkey,
                                localParams.defaultFinalScriptPubKey.toByteArray(),
                                add,
                                feeratePerKwHtlc
                            )
                        }?.let {
                            val sig = keyManager.sign(it, keyManager.htlcPoint(channelKeyPath), remoteCommit.remotePerCommitmentPoint)
                            Transactions.addSigs(it, sig, preimage).tx
                        }
                    }
                }

            val claimHtlcTimeoutTxs = remoteCommit.spec.htlcs.filterIsInstance<IncomingHtlc>().map { it.add }.mapNotNull { add ->
                    // (incoming htlc for which we don't have the preimage: nothing to do, it will timeout eventually and they will get their funds back)
                    // outgoing htlc: they may or may not have the preimage, the only thing to do is try to get back our funds after timeout
                    generateTx {
                        Transactions.makeClaimHtlcTimeoutTx(
                            remoteCommitTx.tx,
                            outputs,
                            localParams.dustLimit,
                            localHtlcPubkey,
                            remoteHtlcPubkey,
                            remoteRevocationPubkey,
                            localParams.defaultFinalScriptPubKey.toByteArray(),
                            add,
                            feeratePerKwHtlc
                        )
                    }?.let {
                        val sig = keyManager.sign(it, keyManager.htlcPoint(channelKeyPath), remoteCommit.remotePerCommitmentPoint)
                        Transactions.addSigs(it, sig).tx
                    }
                }

            return if (channelVersion.hasStaticRemotekey) {
                RemoteCommitPublished(commitTx = tx, claimHtlcSuccessTxs = claimHtlcSuccessTxs, claimHtlcTimeoutTxs = claimHtlcTimeoutTxs)
            } else {
                claimRemoteCommitMainOutput(keyManager, commitments, remoteCommit.remotePerCommitmentPoint, tx, feeEstimator, feeTargets).copy(
                    claimHtlcSuccessTxs = claimHtlcSuccessTxs,
                    claimHtlcTimeoutTxs = claimHtlcTimeoutTxs
                )
            }
        }

        /**
         * Claim our Main output only, not used if option_static_remotekey was negotiated
         *
         * @param commitments              either our current commitment data in case of usual remote uncooperative closing
         *                                 or our outdated commitment data in case of data loss protection procedure; in any case it is used only
         *                                 to get some constant parameters, not commitment data
         * @param remotePerCommitmentPoint the remote perCommitmentPoint corresponding to this commitment
         * @param tx                       the remote commitment transaction that has just been published
         * @return a list of transactions (one per HTLC that we can claim)
         */
        private fun claimRemoteCommitMainOutput(keyManager: KeyManager, commitments: Commitments, remotePerCommitmentPoint: PublicKey, tx: Transaction, feeEstimator: FeeEstimator, feeTargets: FeeTargets): RemoteCommitPublished {
            val channelKeyPath = keyManager.channelKeyPath(commitments.localParams, commitments.channelVersion)
            val localPubkey = Generators.derivePubKey(keyManager.paymentPoint(channelKeyPath).publicKey, remotePerCommitmentPoint)
            val feeratePerKwMain = feeEstimator.getFeeratePerKw(feeTargets.claimMainBlockTarget)

            val mainTx = generateTx {
                Transactions.makeClaimP2WPKHOutputTx(
                    tx,
                    commitments.localParams.dustLimit,
                    localPubkey,
                    commitments.localParams.defaultFinalScriptPubKey.toByteArray(),
                    feeratePerKwMain
                )
            }?.let {
                val sig = keyManager.sign(it, keyManager.paymentPoint(channelKeyPath), remotePerCommitmentPoint)
                Transactions.addSigs(it, localPubkey, sig).tx
            }

            return RemoteCommitPublished(commitTx = tx, claimMainOutputTx = mainTx)
        }

        /**
         * When an unexpected transaction spending the funding tx is detected:
         * 1) we find out if the published transaction is one of remote's revoked txs
         * 2) and then:
         * a) if it is a revoked tx we build a set of transactions that will punish them by stealing all their funds
         * b) otherwise there is nothing we can do
         *
         * @return a [[RevokedCommitPublished]] object containing penalty transactions if the tx is a revoked commitment
         */
        fun claimRevokedRemoteCommitTxOutputs(keyManager: KeyManager, commitments: Commitments, tx: Transaction, feeEstimator: FeeEstimator, feeTargets: FeeTargets): RevokedCommitPublished? {
            val channelVersion = commitments.channelVersion
            val localParams = commitments.localParams
            val remoteParams = commitments.remoteParams

            require(tx.txIn.size == 1) { "commitment tx should have 1 input" }

            val channelKeyPath = keyManager.channelKeyPath(localParams, channelVersion)
            val obscuredTxNumber = Transactions.decodeTxNumber(tx.txIn.first().sequence, tx.lockTime)
            val localPaymentPoint = localParams.localPaymentBasepoint ?: keyManager.paymentPoint(channelKeyPath).publicKey

            // this tx has been published by remote, so we need to invert local/remote params
            val txnumber = Transactions.obscuredCommitTxNumber(obscuredTxNumber, !localParams.isFunder, remoteParams.paymentBasepoint, localPaymentPoint)
            require(txnumber <= 0xffffffffffffL) { "txnumber must be lesser than 48 bits long" }
            // TODO logger.warning { "a revoked commit has been published with txnumber=$txnumber" }

            // now we know what commit number this tx is referring to, we can derive the commitment point from the shachain
            val hash = commitments.remotePerCommitmentSecrets.getHash(0xFFFFFFFFFFFFL - txnumber) ?: return null

            val remotePerCommitmentSecret = PrivateKey.fromHex(hash.toHex())
            val remotePerCommitmentPoint = remotePerCommitmentSecret.publicKey()
            val remoteDelayedPaymentPubkey =
                Generators.derivePubKey(remoteParams.delayedPaymentBasepoint, remotePerCommitmentPoint)
            val remoteRevocationPubkey = Generators.revocationPubKey(
                keyManager.revocationPoint(channelKeyPath).publicKey,
                remotePerCommitmentPoint
            )
            val remoteHtlcPubkey = Generators.derivePubKey(remoteParams.htlcBasepoint, remotePerCommitmentPoint)
            val localPaymentPubkey =
                Generators.derivePubKey(keyManager.paymentPoint(channelKeyPath).publicKey, remotePerCommitmentPoint)
            val localHtlcPubkey =
                Generators.derivePubKey(keyManager.htlcPoint(channelKeyPath).publicKey, remotePerCommitmentPoint)

            val feeratePerKwMain = feeEstimator.getFeeratePerKw(feeTargets.claimMainBlockTarget)
            // we need to use a high fee here for punishment txes because after a delay they can be spent by the counterparty
            val feeratePerKwPenalty = feeEstimator.getFeeratePerKw(target = 2)

            // first we will claim our main output right away
            val mainTx = when {
                channelVersion.hasStaticRemotekey -> null // TODO logger.info { "channel uses option_static_remotekey, not claiming our p2wpkh output" }
                else -> generateTx {
                    Transactions.makeClaimP2WPKHOutputTx(
                        tx,
                        localParams.dustLimit,
                        localPaymentPubkey,
                        localParams.defaultFinalScriptPubKey.toByteArray(),
                        feeratePerKwMain
                    )
                }?.let {
                    val sig = keyManager.sign(it, keyManager.paymentPoint(channelKeyPath), remotePerCommitmentPoint)
                    Transactions.addSigs(it, localPaymentPubkey, sig).tx
                }
            }

            // then we punish them by stealing their main output
            val mainPenaltyTx = generateTx {
                Transactions.makeMainPenaltyTx(
                    tx,
                    localParams.dustLimit,
                    remoteRevocationPubkey,
                    localParams.defaultFinalScriptPubKey.toByteArray(),
                    localParams.toSelfDelay,
                    remoteDelayedPaymentPubkey,
                    feeratePerKwPenalty
                )
            }?.let {
                val sig = keyManager.sign(it, keyManager.revocationPoint(channelKeyPath), remotePerCommitmentSecret)
                Transactions.addSigs(it, sig).tx
            }

            // we retrieve the information needed to rebuild htlc scripts
//                commitments.localCommit.spec.htlcs.map { it.add.paymentHash to it.add.cltvExpiry }
            // TODO handling toRemote / toLocal
            //  We need to find a way to retrieve previous htlcs from revoked commit tx
                // from ECLAIR-CORE val htlcInfos = db.listHtlcInfos(commitments.channelId, txnumber)
            //  val htlcInfos = commitments.remoteCommit.spec.htlcs.map { it.add.paymentHash to it.add.cltvExpiry }
            //  logger.info{ "got htlcs=${htlcInfos.size} for txnumber=$txnumber" }
            //                val htlcsRedeemScripts =
            //                    htlcInfos.map { (paymentHash, cltvExpiry) ->
            //                        val htlcsReceived = Scripts.htlcReceived(
            //                            remoteHtlcPubkey,
            //                            localHtlcPubkey,
            //                            remoteRevocationPubkey,
            //                            Crypto.ripemd160(paymentHash),
            //                            cltvExpiry
            //                        )
            //                        val htlcsOffered = Scripts.htlcOffered(
            //                            remoteHtlcPubkey,
            //                            localHtlcPubkey,
            //                            remoteRevocationPubkey,
            //                            Crypto.ripemd160(paymentHash)
            //                        )
            //                        htlcsReceived + htlcsOffered
            //                    }.map { redeemScript -> Script.write(pay2wsh(redeemScript)) to Script.write(redeemScript) }
            //                        .toMap()
            //              and finally we steal the htlc outputs
            //                val htlcPenaltyTxs = tx.txOut.mapIndexedNotNull { outputIndex, txOut ->
            //                    htlcsRedeemScripts[txOut.publicKeyScript.toByteArray()]?.let { htlcRedeemScript ->
            //                        generateTx {
            //                            Transactions.makeHtlcPenaltyTx(
            //                                tx,
            //                                outputIndex,
            //                                htlcRedeemScript,
            //                                localParams.dustLimit,
            //                                localParams.defaultFinalScriptPubKey.toByteArray(),
            //                                feeratePerKwPenalty
            //                            )
            //                        }?.let { htlcPenalty ->
            //                            val sig = keyManager.sign(htlcPenalty, keyManager.revocationPoint(channelKeyPath))
            //                            Transactions.addSigs(htlcPenalty, sig, remoteRevocationPubkey).tx
            //                        }
            //                    }
            //                }

            return RevokedCommitPublished(
                commitTx = tx,
                claimMainOutputTx = mainTx,
                mainPenaltyTx = mainPenaltyTx,
//                    htlcPenaltyTxs = htlcPenaltyTxs
            )
        }

        /**
         * Claims the output of an [[HtlcSuccessTx]] or [[HtlcTimeoutTx]] transaction using a revocation key.
         *
         * In case a revoked commitment with pending HTLCs is published, there are two ways the HTLC outputs can be taken as punishment:
         * - by spending the corresponding output of the commitment tx, using [[HtlcPenaltyTx]] that we generate as soon as we detect that a revoked commit
         * as been spent; note that those transactions will compete with [[HtlcSuccessTx]] and [[HtlcTimeoutTx]] published by the counterparty.
         * - by spending the delayed output of [[HtlcSuccessTx]] and [[HtlcTimeoutTx]] if those get confirmed; because the output of these txes is protected by
         * an OP_CSV delay, we will have time to spend them with a revocation key. In that case, we generate the spending transactions "on demand",
         * this is the purpose of this method.
         */
        fun claimRevokedHtlcTxOutputs(keyManager: KeyManager, commitments: Commitments, revokedCommitPublished: RevokedCommitPublished, htlcTx: Transaction, feeEstimator: FeeEstimator): Pair<RevokedCommitPublished, Transaction?> {
            val claimTxes = buildList {
                revokedCommitPublished.claimMainOutputTx?.let { add(it) }
                revokedCommitPublished.mainPenaltyTx?.let { add(it) }
                addAll(revokedCommitPublished.htlcPenaltyTxs)
            }

            if (htlcTx.txIn.map { it.outPoint.txid }.contains(revokedCommitPublished.commitTx.txid) &&
                !claimTxes.map { it.txid }.toSet().contains(htlcTx.txid)) {
                // TODO log.info(s"looks like txid=${htlcTx.txid} could be a 2nd level htlc tx spending revoked commit txid=${revokedCommitPublished.commitTx.txid}")
                // Let's assume that htlcTx is an HtlcSuccessTx or HtlcTimeoutTx and try to generate a tx spending its output using a revocation key
                val localParams = commitments.localParams
                val channelVersion = commitments.channelVersion
                val remoteParams = commitments.remoteParams
                val remotePerCommitmentSecrets = commitments.remotePerCommitmentSecrets

                val tx = revokedCommitPublished.commitTx
                val obscuredTxNumber = Transactions.decodeTxNumber(tx.txIn.first().sequence, tx.lockTime)
                val channelKeyPath = keyManager.channelKeyPath(localParams, channelVersion)

                // this tx has been published by remote, so we need to invert local/remote params
                val txnumber = Transactions.obscuredCommitTxNumber(
                    obscuredTxNumber,
                    !localParams.isFunder,
                    remoteParams.paymentBasepoint,
                    keyManager.paymentPoint(channelKeyPath).publicKey
                )
                // now we know what commit number this tx is referring to, we can derive the commitment point from the shachain
                val hash = remotePerCommitmentSecrets.getHash(0xFFFFFFFFFFFFL - txnumber) ?: return revokedCommitPublished to null

                val remotePerCommitmentSecret = PrivateKey(hash)
                val remotePerCommitmentPoint = remotePerCommitmentSecret.publicKey()
                val remoteDelayedPaymentPubkey =
                    Generators.derivePubKey(remoteParams.delayedPaymentBasepoint, remotePerCommitmentPoint)
                val remoteRevocationPubkey = Generators.revocationPubKey(
                    keyManager.revocationPoint(channelKeyPath).publicKey,
                    remotePerCommitmentPoint
                )

                // we need to use a high fee here for punishment txes because after a delay they can be spent by the counterparty
                val feeratePerKwPenalty = feeEstimator.getFeeratePerKw(target = 1)

                val signedTx = generateTx {
                    Transactions.makeClaimDelayedOutputPenaltyTx(
                        htlcTx,
                        localParams.dustLimit,
                        remoteRevocationPubkey,
                        localParams.toSelfDelay,
                        remoteDelayedPaymentPubkey,
                        localParams.defaultFinalScriptPubKey.toByteArray(),
                        feeratePerKwPenalty
                    )
                }?.let {
                    val sig = keyManager.sign(it, keyManager.revocationPoint(channelKeyPath), remotePerCommitmentSecret)
                    val signedTx = Transactions.addSigs(it, sig).tx
                    // we need to make sure that the tx is indeed valid
                    Transaction.correctlySpends(signedTx, listOf(htlcTx), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
                    signedTx
                } ?: return revokedCommitPublished to null

                return revokedCommitPublished.copy(claimHtlcDelayedPenaltyTxs = revokedCommitPublished.claimHtlcDelayedPenaltyTxs + signedTx) to signedTx
            } else {
                return revokedCommitPublished to null
            }
        }

        /**
         * In CLOSING state, any time we see a new transaction, we try to extract a preimage from it in order to fulfill the
         * corresponding incoming htlc in an upstream channel.
         *
         * Not doing that would result in us losing money, because the downstream node would pull money from one side, and
         * the upstream node would get refunded after a timeout.
         *
         * @return a set of pairs (add, preimage) if extraction was successful:
         *           - add is the htlc in the downstream channel from which we extracted the preimage
         *           - preimage needs to be sent to the upstream channel
         */
        fun extractPreimages(localCommit: LocalCommit, tx: Transaction): Set<Pair<UpdateAddHtlc, ByteVector32>> {
            val htlcSuccess = tx.txIn.map { it.witness }.mapNotNull(Scripts.extractPreimageFromHtlcSuccess())
            // TODO            htlcSuccess.foreach(r => logger.info(s"extracted paymentPreimage=$r from tx=$tx (htlc-success)"))
            val claimHtlcSuccess = tx.txIn.map { it.witness }.mapNotNull(Scripts.extractPreimageFromClaimHtlcSuccess())
            // TODO            claimHtlcSuccess.foreach(r => log.info(s"extracted paymentPreimage=$r from tx=$tx (claim-htlc-success)"))
            val paymentPreimages = (htlcSuccess + claimHtlcSuccess).toSet()

            return paymentPreimages.flatMap { paymentPreimage ->
                // we only consider htlcs in our local commitment, because we only care about outgoing htlcs, which disappear first in the remote commitment
                // if an outgoing htlc is in the remote commitment, then:
                // - either it is in the local commitment (it was never fulfilled)
                // - or we have already received the fulfill and forwarded it upstream
                localCommit.spec.htlcs.filter {
                    it is OutgoingHtlc && it.add.paymentHash.contentEquals(sha256(paymentPreimage))
                }.map { it.add to paymentPreimage }
            }.toSet()
        }

        /**
         * We may have multiple HTLCs with the same payment hash because of MPP.
         * When a timeout transaction is confirmed, we need to find the best matching HTLC to fail upstream.
         * We need to handle potentially duplicate HTLCs (same amount and expiry): this function will use a deterministic
         * ordering of transactions and HTLCs to handle this.
         */
        private fun findTimedOutHtlc(tx: Transaction, paymentHash160: ByteVector, htlcs:  List<UpdateAddHtlc>, timeoutTxs: List<Transaction>, extractPaymentHash: (ScriptWitness) -> ByteVector?): UpdateAddHtlc? {
            // We use a deterministic ordering to match HTLCs to their corresponding HTLC-timeout tx.
            // We don't match on the expected amounts because this is error-prone: computing the correct weight of a claim-htlc-timeout
            // is hard because signatures can be either 71, 72 or 73 bytes long (ECDSA DER encoding).
            // We could instead look at the spent outpoint, but that requires more lookups and access to the published commitment transaction.
            // It's simpler to just use the amount as the first ordering key: since the feerate is the same for all timeout
            // transactions we will find the right HTLC to fail upstream.
            val matchingHtlcs = htlcs
                .filter { it.cltvExpiry.toLong() == tx.lockTime && ripemd160(it.paymentHash).toByteVector() == paymentHash160 }
                .sortedWith(compareBy({ it.amountMsat.toLong() }, { it.id }))

            val matchingTxs = timeoutTxs
                .filter { t -> t.lockTime == t.lockTime && t.txIn.map { it.witness }.map(extractPaymentHash).contains(paymentHash160) }
                .sortedWith(compareBy({ t -> t.txOut.map { it.amount.sat }.sum() }, { it.txid.toHex() }))

            if (matchingTxs.size != matchingHtlcs.size) {
                // TODO log.error(s"some htlcs don't have a corresponding timeout transaction: tx=$tx, htlcs=$matchingHtlcs, timeout-txs=$matchingTxs")
            }

            return matchingHtlcs.zip(matchingTxs).firstOrNull { (_, timeoutTx) -> timeoutTx.txid == tx.txid }?.first
        }

        /**
         * In CLOSING state, when we are notified that a transaction has been confirmed, we analyze it to find out if one or
         * more htlcs have timed out and need to be failed in an upstream channel.
         *
         * @param tx a tx that has reached mindepth
         * @return a set of htlcs that need to be failed upstream
         */
        fun timedoutHtlcs(localCommit: LocalCommit, localCommitPublished: LocalCommitPublished, localDustLimit: Satoshi, tx: Transaction): Set<UpdateAddHtlc> {
            val untrimmedHtlcs = Transactions.trimOfferedHtlcs(localDustLimit, localCommit.spec).map { it.add }
            return if (tx.txid == localCommit.publishableTxs.commitTx.tx.txid) {
                // the tx is a commitment tx, we can immediately fail all dust htlcs (they don't have an output in the tx)
                (localCommit.spec.htlcs.outgoings() - untrimmedHtlcs).toSet()
            } else {
                // maybe this is a timeout tx, in that case we can resolve and fail the corresponding htlc
                tx.txIn
                    .map { it.witness }
                    .mapNotNull(Scripts.extractPaymentHashFromHtlcTimeout())
                    .mapNotNull { paymentHash160 ->
                        // TODO log.info(s"extracted paymentHash160=$paymentHash160 and expiry=${tx.lockTime} from tx=$tx (htlc-timeout)")
                        findTimedOutHtlc(tx,
                            paymentHash160,
                            untrimmedHtlcs,
                            localCommitPublished.htlcTimeoutTxs,
                            Scripts.extractPaymentHashFromHtlcTimeout()
                        )
                    }.toSet()
            }
        }

        /**
         * In CLOSING state, when we are notified that a transaction has been confirmed, we analyze it to find out if one or
         * more htlcs have timed out and need to be failed in an upstream channel.
         *
         * @param tx a tx that has reached mindepth
         * @return a set of htlcs that need to be failed upstream
         */
        fun timedoutHtlcs(remoteCommit: RemoteCommit, remoteCommitPublished: RemoteCommitPublished, remoteDustLimit: Satoshi, tx: Transaction): Set<UpdateAddHtlc> {
            val untrimmedHtlcs = Transactions.trimReceivedHtlcs(remoteDustLimit, remoteCommit.spec).map { it.add }
            return if (tx.txid == remoteCommit.txid) {
                // the tx is a commitment tx, we can immediately fail all dust htlcs (they don't have an output in the tx)
                (remoteCommit.spec.htlcs.incomings() - untrimmedHtlcs).toSet()
            } else {
                // maybe this is a timeout tx, in that case we can resolve and fail the corresponding htlc
                tx.txIn
                    .map { it.witness }
                    .mapNotNull(Scripts.extractPaymentHashFromHtlcTimeout())
                    .mapNotNull { paymentHash160 ->
                        // TODO log.info(s"extracted paymentHash160=$paymentHash160 and expiry=${tx.lockTime} from tx=$tx (claim-htlc-timeout)")
                        findTimedOutHtlc(tx,
                            paymentHash160,
                            untrimmedHtlcs,
                            remoteCommitPublished.claimHtlcTimeoutTxs,
                            Scripts.extractPaymentHashFromClaimHtlcTimeout())
                    }.toSet()
            }
        }

        /**
         * As soon as a local or remote commitment reaches min_depth, we know which htlcs will be settled on-chain (whether
         * or not they actually have an output in the commitment tx).
         *
         * @param tx a transaction that is sufficiently buried in the blockchain
         */
        fun onchainOutgoingHtlcs(localCommit: LocalCommit, remoteCommit: RemoteCommit, nextRemoteCommit_opt: RemoteCommit?, tx: Transaction): Set<UpdateAddHtlc> =
            when {
                localCommit.publishableTxs.commitTx.tx.txid == tx.txid -> localCommit.spec.htlcs.outgoings().toSet()
                remoteCommit.txid == tx.txid -> remoteCommit.spec.htlcs.incomings().toSet()
                nextRemoteCommit_opt?.txid == tx.txid -> nextRemoteCommit_opt.spec.htlcs.incomings().toSet()
                else -> emptySet()
            }

        /**
         * If a local commitment tx reaches min_depth, we need to fail the outgoing htlcs that only us had signed, because
         * they will never reach the blockchain.
         *
         * Those are only present in the remote's commitment.
         */
        fun overriddenOutgoingHtlcs(closing: fr.acinq.eclair.channel.Closing, tx: Transaction): Set<UpdateAddHtlc> {
            val localCommit = closing.commitments.localCommit
            val remoteCommit = closing.commitments.remoteCommit
            val nextRemoteCommit_opt = closing.commitments.remoteNextCommitInfo.left?.nextRemoteCommit

            return when {
                localCommit.publishableTxs.commitTx.tx.txid == tx.txid -> {
                    // our commit got confirmed, so any htlc that we signed but they didn't sign will never reach the chain
                    val mostRecentRemoteCommit = nextRemoteCommit_opt ?: remoteCommit
                    // NB: from the p.o.v of remote, their incoming htlcs are our outgoing htlcs
                    (mostRecentRemoteCommit.spec.htlcs.incomings() - localCommit.spec.htlcs.outgoings()).toSet()
                }
                remoteCommit.txid == tx.txid -> {
                    // their commit got confirmed
                    nextRemoteCommit_opt?.let {
                        // we had signed a new commitment but they committed the previous one
                        // any htlc that we signed in the new commitment that they didn't sign will never reach the chain
                        (it.spec.htlcs.incomings() - localCommit.spec.htlcs.outgoings()).toSet()
                    }
                        // their last commitment got confirmed, so no htlcs will be overridden, they will timeout or be fulfilled on chain
                        ?: emptySet()
                }
                nextRemoteCommit_opt?.txid == tx.txid -> {
                    // their last commitment got confirmed, so no htlcs will be overridden, they will timeout or be fulfilled on chain
                    emptySet()
                }
                else -> emptySet()
            }
        }

        /**
         * In CLOSING state, when we are notified that a transaction has been confirmed, we check if this tx belongs in the
         * local commit scenario and keep track of it.
         *
         * We need to keep track of all transactions spending the outputs of the commitment tx, because some outputs can be
         * spent both by us and our counterparty. Because of that, some of our transactions may never confirm and we don't
         * want to wait forever before declaring that the channel is CLOSED.
         *
         * @param tx a transaction that has been irrevocably confirmed
         */
        fun updateLocalCommitPublished(localCommitPublished: LocalCommitPublished, tx: Transaction): LocalCommitPublished {
            // even if our txes only have one input, maybe our counterparty uses a different scheme so we need to iterate
            // over all of them to check if they are relevant
            val relevantOutpoints = tx.txIn.map { it.outPoint }.filter { outPoint ->
                // is this the commit tx itself ? (we could do this outside of the loop...)
                val isCommitTx = localCommitPublished.commitTx.txid == tx.txid
                // does the tx spend an output of the local commitment tx?
                val spendsTheCommitTx = localCommitPublished.commitTx.txid == outPoint.txid
                // is the tx one of our 3rd stage delayed txes? (a 3rd stage tx is a tx spending the output of an htlc tx, which
                // is itself spending the output of the commitment tx)
                val is3rdStageDelayedTx = localCommitPublished.claimHtlcDelayedTxs.map { it.txid }.contains(tx.txid)
                isCommitTx || spendsTheCommitTx || is3rdStageDelayedTx
            }
            // then we add the relevant outpoints to the map keeping track of which txid spends which outpoint
            return localCommitPublished.copy(irrevocablySpent = localCommitPublished.irrevocablySpent + relevantOutpoints.map { it to tx.txid }.toMap())
        }

        /**
         * In CLOSING state, when we are notified that a transaction has been confirmed, we check if this tx belongs in the
         * remote commit scenario and keep track of it.
         *
         * We need to keep track of all transactions spending the outputs of the commitment tx, because some outputs can be
         * spent both by us and our counterparty. Because of that, some of our transactions may never confirm and we don't
         * want to wait forever before declaring that the channel is CLOSED.
         *
         * @param tx a transaction that has been irrevocably confirmed
         */
        fun updateRemoteCommitPublished(remoteCommitPublished: RemoteCommitPublished, tx: Transaction): RemoteCommitPublished {
            // even if our txes only have one input, maybe our counterparty uses a different scheme so we need to iterate
            // over all of them to check if they are relevant
            val relevantOutpoints = tx.txIn.map { it.outPoint }.filter { outPoint ->
                // is this the commit tx itself ? (we could do this outside of the loop...)
                val isCommitTx = remoteCommitPublished.commitTx.txid == tx.txid
                // does the tx spend an output of the remote commitment tx?
                val spendsTheCommitTx = remoteCommitPublished.commitTx.txid == outPoint.txid
                isCommitTx || spendsTheCommitTx
            }
            // then we add the relevant outpoints to the map keeping track of which txid spends which outpoint
            return remoteCommitPublished.copy(irrevocablySpent = remoteCommitPublished.irrevocablySpent + relevantOutpoints.map { it to tx.txid }.toMap())
        }

        /**
         * In CLOSING state, when we are notified that a transaction has been confirmed, we check if this tx belongs in the
         * revoked commit scenario and keep track of it.
         *
         * We need to keep track of all transactions spending the outputs of the commitment tx, because some outputs can be
         * spent both by us and our counterparty. Because of that, some of our transactions may never confirm and we don't
         * want to wait forever before declaring that the channel is CLOSED.
         *
         * @param tx a transaction that has been irrevocably confirmed
         */
        fun updateRevokedCommitPublished(revokedCommitPublished: RevokedCommitPublished, tx: Transaction): RevokedCommitPublished {
            // even if our txes only have one input, maybe our counterparty uses a different scheme so we need to iterate
            // over all of them to check if they are relevant
            val relevantOutpoints = tx.txIn.map { it.outPoint }.filter { outPoint ->
                // is this the commit tx itself ? (we could do this outside of the loop...)
                val isCommitTx = revokedCommitPublished.commitTx.txid == tx.txid
                // does the tx spend an output of the remote commitment tx?
                val spendsTheCommitTx = revokedCommitPublished.commitTx.txid == outPoint.txid
                // is the tx one of our 3rd stage delayed txes? (a 3rd stage tx is a tx spending the output of an htlc tx, which
                // is itself spending the output of the commitment tx)
                val is3rdStageDelayedTx = revokedCommitPublished.claimHtlcDelayedPenaltyTxs.map { it.txid }.contains(tx.txid)
                isCommitTx || spendsTheCommitTx || is3rdStageDelayedTx
            }
            // then we add the relevant outpoints to the map keeping track of which txid spends which outpoint
            return revokedCommitPublished.copy(irrevocablySpent = revokedCommitPublished.irrevocablySpent + relevantOutpoints.map { it to tx.txid }.toMap())
        }

        /**
         * As soon as a tx spending the funding tx has reached min_depth, we know what the closing type will be, before
         * the whole closing process finishes (e.g. there may still be delayed or unconfirmed child transactions). It can
         * save us from attempting to publish some transactions.
         *
         * Note that we can't tell for mutual close before it is already final, because only one tx needs to be confirmed.
         *
         * @param closing channel state data
         * @return the channel closing type, if applicable
         */
        fun isClosingTypeAlreadyKnown(closing: fr.acinq.eclair.channel.Closing): ClosingType? {
            // NB: if multiple transactions end up in the same block, the first confirmation we receive may not be the commit tx.
            // However if the confirmed tx spends from the commit tx, we know that the commit tx is already confirmed and we know
            // the type of closing.
            fun LocalCommitPublished.isLocalCommitConfirmed(): Boolean {
                val confirmedTxs = irrevocablySpent.values.toSet()
                return buildList {
                    add(commitTx)
                    claimMainDelayedOutputTx?.let { add(it) }
                    addAll(htlcSuccessTxs)
                    addAll(htlcTimeoutTxs)
                    addAll(claimHtlcDelayedTxs)
                }.any { tx -> confirmedTxs.contains(tx.txid) }
            }

            fun RemoteCommitPublished.isRemoteCommitConfirmed(): Boolean {
                val confirmedTxs = irrevocablySpent.values.toSet()
                return buildList {
                    add(commitTx)
                    claimMainOutputTx?.let { add(it) }
                    addAll(claimHtlcSuccessTxs)
                    addAll(claimHtlcTimeoutTxs)
                }.any { tx -> confirmedTxs.contains(tx.txid) }
            }

            return when {
                closing.localCommitPublished != null && closing.localCommitPublished.isLocalCommitConfirmed() ->
                    LocalClose(closing.commitments.localCommit, closing.localCommitPublished)
                closing.remoteCommitPublished != null && closing.remoteCommitPublished.isRemoteCommitConfirmed() ->
                    CurrentRemoteClose(closing.commitments.remoteCommit, closing.remoteCommitPublished)
                closing.nextRemoteCommitPublished != null &&
                        closing.commitments.remoteNextCommitInfo.isLeft &&
                        closing.nextRemoteCommitPublished.isRemoteCommitConfirmed() ->
                    NextRemoteClose(
                        closing.commitments.remoteNextCommitInfo.left?.nextRemoteCommit
                            ?: error("nextRemoteCommit must be defined"),
                        closing.nextRemoteCommitPublished
                    )
                closing.futureRemoteCommitPublished != null && closing.futureRemoteCommitPublished.isRemoteCommitConfirmed() ->
                    RecoveryClose(closing.futureRemoteCommitPublished)
                closing.revokedCommitPublished.any { rcp -> rcp.irrevocablySpent.values.toSet().contains(rcp.commitTx.txid) } ->
                    RevokedClose(closing.revokedCommitPublished.first { rcp ->
                        rcp.irrevocablySpent.values.toSet().contains(rcp.commitTx.txid)
                    })
                else -> null
            }
        }

        /**
         * This helper function tells if the utxo consumed by the given transaction has already been irrevocably spent (possibly by this very transaction)
         *
         * It can be useful to:
         *   - not attempt to publish this tx when we know this will fail
         *   - not watch for confirmations if we know the tx is already confirmed
         *   - not watch the corresponding utxo when we already know the final spending tx
         *
         * @param tx               a tx with only one input
         * @param irrevocablySpent a map of known spent outpoints
         * @return true if we know for sure that the utxos consumed by the tx have already irrevocably been spent, false otherwise
         */
        fun inputsAlreadySpent(tx: Transaction, irrevocablySpent: Map<OutPoint, ByteVector32>): Boolean {
            require(tx.txIn.size == 1) { "only tx with one input is supported" }
            val outPoint = tx.txIn.first().outPoint
            return irrevocablySpent.contains(outPoint)
        }

        /**
         * This helper function returns the fee paid by the given transaction.
         *
         * It relies on the current channel data to find the parent tx and compute the fee, and also provides a description.
         *
         * @param tx a tx for which we want to compute the fee
         * @param d  current channel data
         * @return if the parent tx is found, a tuple (fee, description)
         */
        fun networkFeePaid(tx: Transaction, closing: fr.acinq.eclair.channel.Closing): Pair<Satoshi, String>? {
            // only funder pays the fee
            if (!closing.commitments.localParams.isFunder) return null

            // we build a map with all known txes (that's not particularly efficient, but it doesn't really matter)
            val txes = buildList {
                closing.mutualClosePublished.map { it to "mutual" }.forEach { add(it) }
                closing.localCommitPublished?.let { localCommitPublished ->
                    add(localCommitPublished.commitTx to  "local-commit")
                    localCommitPublished.claimMainDelayedOutputTx?.let { add(it to "local-main-delayed") }
                    localCommitPublished.htlcSuccessTxs.forEach { add(it to "local-htlc-success") }
                    localCommitPublished.htlcTimeoutTxs.forEach { add(it to "local-htlc-timeout") }
                    localCommitPublished.claimHtlcDelayedTxs.forEach { add(it to "local-htlc-delayed") }
                }
                closing.remoteCommitPublished?.let { remoteCommitPublished ->
                    add(remoteCommitPublished.commitTx to  "remote-commit")
                    remoteCommitPublished.claimMainOutputTx?.let { add(it to "remote-main") }
                    remoteCommitPublished.claimHtlcSuccessTxs.forEach { add(it to "remote-htlc-success") }
                    remoteCommitPublished.claimHtlcTimeoutTxs.forEach { add(it to "remote-htlc-timeout") }
                }
                closing.nextRemoteCommitPublished?.let { nextRemoteCommitPublished ->
                    add(nextRemoteCommitPublished.commitTx to  "remote-commit")
                    nextRemoteCommitPublished.claimMainOutputTx?.let { add(it to "remote-main") }
                    nextRemoteCommitPublished.claimHtlcSuccessTxs.forEach { add(it to "remote-htlc-success") }
                    nextRemoteCommitPublished.claimHtlcTimeoutTxs.forEach { add(it to "remote-htlc-timeout") }
                }
                closing.revokedCommitPublished.forEach {revokedCommitPublished ->
                    add(revokedCommitPublished.commitTx to "revoked-commit")
                    revokedCommitPublished.claimMainOutputTx?.let { add(it to "revoked-main") }
                    revokedCommitPublished.mainPenaltyTx?.let { add(it to "revoked-main-penalty") }
                    revokedCommitPublished.htlcPenaltyTxs.forEach { add(it to "revoked-htlc-penalty") }
                    revokedCommitPublished.claimHtlcDelayedPenaltyTxs.forEach { add(it to "revoked-htlc-penalty-delayed") }
                }
            }
                // will allow easy lookup of parent transaction
                .map { (tx, desc) -> tx.txid to (tx to desc) }
                .toMap()

            fun fee(child: Transaction): Satoshi? {
                require(child.txIn.size == 1) { "transaction must have exactly one input" }
                val outPoint = child.txIn.first().outPoint
                val parentTxOut_opt = if (outPoint == closing.commitments.commitInput.outPoint) {
                    closing.commitments.commitInput.txOut
                } else {
                    txes[outPoint.txid]?.let { (parent, _) ->
                        parent.txOut[outPoint.index.toInt()]
                    }
                }
                return parentTxOut_opt?.let { txOut -> txOut.amount - child.txOut.map { it.amount }.sum() }
            }

            return txes[tx.txid]?.let { (_, desc) ->
                fee(tx)?.let { it to desc }
            }
        }

        /**
         * Wraps transaction generation in a Try and filters failures to avoid one transaction negatively impacting a whole commitment.
         */
        fun <T : Transactions.TransactionWithInputInfo> generateTx(attempt: () -> Transactions.TxResult<T>): T? =
            when (val result = runTrying { attempt() }) {
                is Try.Success -> when (val txResult = result.get()) {
//            log.info(s"tx generation success: desc=$desc txid=${txinfo.tx.txid} amount=${txinfo.tx.txOut.map(_.amount).sum} tx=${txinfo.tx}")
                    is Transactions.TxResult.Success -> txResult.result
//            log.info(s"tx generation skipped: desc=$desc reason: ${skipped.toString}")
                    is Transactions.TxResult.Skipped -> null // TODO logging ?
                }
//            log.warning(s"tx generation failure: desc=$desc reason: ${t.getMessage}")
                is Try.Failure -> null // TODO logging ?
            }
    }

    /**
     * @param referenceFeePerKw reference fee rate per kiloweight
     * @param currentFeePerKw   current fee rate per kiloweight
     * @return the "normalized" difference between i.e local and remote fee rate: |reference - current| / avg(current, reference)
     */
    fun feeRateMismatch(referenceFeePerKw: Long, currentFeePerKw: Long): Double =
        abs((2.0 * (referenceFeePerKw - currentFeePerKw)) / (currentFeePerKw + referenceFeePerKw))

    /**
     * @param referenceFeePerKw       reference fee rate per kiloweight
     * @param currentFeePerKw         current fee rate per kiloweight
     * @param maxFeerateMismatchRatio maximum fee rate mismatch ratio
     * @return true if the difference between current and reference fee rates is too high.
     *         the actual check is |reference - current| / avg(current, reference) > mismatch ratio
     */
    fun isFeeDiffTooHigh(referenceFeePerKw: Long, currentFeePerKw: Long, maxFeerateMismatchRatio: Double): Boolean =
        feeRateMismatch(referenceFeePerKw, currentFeePerKw) > maxFeerateMismatchRatio

    fun encrypt(key: ByteVector32, state: HasCommitments): ByteArray {
        val bin = HasCommitments.serialize(state)
        // NB: there is a chance of collision here, due to how the nonce is calculated. Probability of collision is once every 2.2E19 times.
        // See https://en.wikipedia.org/wiki/Birthday_attack
        val nonce = Crypto.sha256(bin).take(12).toByteArray()
        val (ciphertext, tag) = ChaCha20Poly1305.encrypt(key.toByteArray(), nonce, bin, ByteArray(0))
        return ciphertext + nonce + tag
    }

    fun decrypt(key: ByteVector32, data: ByteArray): HasCommitments {
        // nonce is 12B, tag is 16B
        val ciphertext = data.dropLast(12 + 16)
        val nonce = data.takeLast(12 + 16).take(12)
        val tag = data.takeLast(16)
        val plaintext = ChaCha20Poly1305.decrypt(key.toByteArray(), nonce.toByteArray(), ciphertext.toByteArray(), ByteArray(0), tag.toByteArray())
        return HasCommitments.deserialize(plaintext)
    }

    fun decrypt(key: PrivateKey, data: ByteArray): HasCommitments = decrypt(key.value, data)

    fun decrypt(key: PrivateKey, data: ByteVector): HasCommitments = decrypt(key, data.toByteArray())
}

sealed class ClosingType
data class MutualClose(val tx: Transaction) : ClosingType()
data class LocalClose(val localCommit: LocalCommit, val localCommitPublished: LocalCommitPublished) : ClosingType()
sealed class RemoteClose() : ClosingType() { abstract val remoteCommit: RemoteCommit; abstract val remoteCommitPublished: RemoteCommitPublished }
data class CurrentRemoteClose(override val remoteCommit: RemoteCommit, override val remoteCommitPublished: RemoteCommitPublished) : RemoteClose()
data class NextRemoteClose(override val remoteCommit: RemoteCommit, override val remoteCommitPublished: RemoteCommitPublished) : RemoteClose()
data class RecoveryClose(val remoteCommitPublished: RemoteCommitPublished) : ClosingType()
data class RevokedClose(val revokedCommitPublished: RevokedCommitPublished) : ClosingType()

