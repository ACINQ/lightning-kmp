package fr.acinq.eclair.channel

import fr.acinq.bitcoin.*
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
import fr.acinq.eclair.transactions.CommitmentSpec
import fr.acinq.eclair.transactions.IncomingHtlc
import fr.acinq.eclair.transactions.OutgoingHtlc
import fr.acinq.eclair.transactions.Scripts.multiSig2of2
import fr.acinq.eclair.transactions.Transactions
import fr.acinq.eclair.transactions.Transactions.commitTxFee
import fr.acinq.eclair.transactions.Transactions.makeCommitTxOutputs
import fr.acinq.eclair.utils.Either
import fr.acinq.eclair.utils.Try
import fr.acinq.eclair.utils.runTrying
import fr.acinq.eclair.utils.toByteVector
import fr.acinq.eclair.wire.AcceptChannel
import fr.acinq.eclair.wire.OpenChannel
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
                            val sig = keyManager.sign(it, keyManager.htlcPoint(channelKeyPath))
                            Transactions.addSigs(it, sig, preimage).tx
                        }
                    }
                }


            val claimHtlcTimeoutTxs = remoteCommit.spec.htlcs.filterIsInstance<IncomingHtlc>().map { it.add }.mapNotNull { add ->
                    // (incoming htlc for which we don't have the preimage: nothing to do, it will timeout eventually and they will get their funds back)
                    // outgoing htlc: they may or may not have the preimage, the only thing to do is try to get back our funds after timeout
                    val txResult = Transactions.makeClaimHtlcTimeoutTx(
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

                    when (txResult) {
                        is Transactions.TxResult.Success -> {
                            val sig = keyManager.sign(txResult.result, keyManager.htlcPoint(channelKeyPath))
                            Transactions.addSigs(txResult.result, sig).tx
                        }
                        is Transactions.TxResult.Skipped -> null
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
        fun claimRemoteCommitMainOutput(keyManager: KeyManager, commitments: Commitments, remotePerCommitmentPoint: PublicKey, tx: Transaction, feeEstimator: FeeEstimator, feeTargets: FeeTargets): RemoteCommitPublished {
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
                val sig = keyManager.sign(it, keyManager.paymentPoint(channelKeyPath))
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
            return commitments.remotePerCommitmentSecrets.getHash(0xFFFFFFFFFFFFL - txnumber)?.let { hash ->
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
                        val sig = keyManager.sign(it, keyManager.paymentPoint(channelKeyPath))
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
                    val sig = keyManager.sign(it, keyManager.revocationPoint(channelKeyPath))
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

                RevokedCommitPublished(
                    commitTx = tx,
                    claimMainOutputTx = mainTx,
                    mainPenaltyTx = mainPenaltyTx,
//                    htlcPenaltyTxs = htlcPenaltyTxs
                )
            }
        }

        /** Wraps transaction generation in a Try and filters failures to avoid one transaction negatively impacting a whole commitment. */
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
