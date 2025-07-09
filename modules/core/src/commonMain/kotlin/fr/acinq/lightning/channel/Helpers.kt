package fr.acinq.lightning.channel

import fr.acinq.bitcoin.*
import fr.acinq.bitcoin.Crypto.ripemd160
import fr.acinq.bitcoin.Script.pay2wsh
import fr.acinq.bitcoin.Script.write
import fr.acinq.bitcoin.utils.Either
import fr.acinq.bitcoin.utils.Try
import fr.acinq.bitcoin.utils.runTrying
import fr.acinq.lightning.MilliSatoshi
import fr.acinq.lightning.NodeParams
import fr.acinq.lightning.blockchain.WatchConfirmed
import fr.acinq.lightning.blockchain.WatchSpent
import fr.acinq.lightning.blockchain.fee.FeeratePerKw
import fr.acinq.lightning.blockchain.fee.FeerateTolerance
import fr.acinq.lightning.blockchain.fee.OnChainFeerates
import fr.acinq.lightning.channel.Helpers.Closing.inputsAlreadySpent
import fr.acinq.lightning.channel.states.Channel
import fr.acinq.lightning.crypto.ChannelKeys
import fr.acinq.lightning.crypto.LocalCommitmentKeys
import fr.acinq.lightning.crypto.RemoteCommitmentKeys
import fr.acinq.lightning.crypto.ShaChain
import fr.acinq.lightning.logging.LoggingContext
import fr.acinq.lightning.transactions.*
import fr.acinq.lightning.transactions.Scripts.multiSig2of2
import fr.acinq.lightning.transactions.Transactions.TransactionWithInputInfo.ClaimHtlcDelayedOutputPenaltyTx
import fr.acinq.lightning.transactions.Transactions.TransactionWithInputInfo.ClaimHtlcTx.ClaimHtlcTimeoutTx
import fr.acinq.lightning.transactions.Transactions.TransactionWithInputInfo.ClosingTx
import fr.acinq.lightning.transactions.Transactions.TransactionWithInputInfo.HtlcTx.HtlcSuccessTx
import fr.acinq.lightning.transactions.Transactions.TransactionWithInputInfo.HtlcTx.HtlcTimeoutTx
import fr.acinq.lightning.transactions.Transactions.commitTxFee
import fr.acinq.lightning.transactions.Transactions.makeCommitTxOutputs
import fr.acinq.lightning.utils.sat
import fr.acinq.lightning.utils.sum
import fr.acinq.lightning.utils.toByteVector
import fr.acinq.lightning.wire.*

object Helpers {

    /** Called by the non-initiator. */
    fun validateParamsNonInitiator(nodeParams: NodeParams, open: OpenDualFundedChannel): Either<ChannelException, ChannelType> {
        // NB: we only accept channels from peers who support explicit channel type negotiation.
        val channelType = open.channelType ?: return Either.Left(MissingChannelType(open.temporaryChannelId))
        if (channelType is ChannelType.UnsupportedChannelType) {
            return Either.Left(InvalidChannelType(open.temporaryChannelId, ChannelType.SupportedChannelType.AnchorOutputsZeroReserve, channelType))
        }

        // BOLT #2: if the chain_hash value, within the open_channel, message is set to a hash of a chain that is unknown to the receiver:
        // MUST reject the channel.
        if (nodeParams.chainHash != open.chainHash) {
            return Either.Left(InvalidChainHash(open.temporaryChannelId, local = nodeParams.chainHash, remote = open.chainHash))
        }

        // BOLT #2: The receiving node MUST fail the channel if: to_self_delay is unreasonably large.
        if (open.toSelfDelay > Channel.MAX_TO_SELF_DELAY || open.toSelfDelay > nodeParams.maxToLocalDelayBlocks) {
            return Either.Left(ToSelfDelayTooHigh(open.temporaryChannelId, open.toSelfDelay, nodeParams.maxToLocalDelayBlocks))
        }

        // BOLT #2: The receiving node MUST fail the channel if: max_accepted_htlcs is greater than 483.
        if (open.maxAcceptedHtlcs > Channel.MAX_ACCEPTED_HTLCS) {
            return Either.Left(InvalidMaxAcceptedHtlcs(open.temporaryChannelId, open.maxAcceptedHtlcs, Channel.MAX_ACCEPTED_HTLCS))
        }

        // BOLT #2: The receiving node MUST fail the channel if: it considers feerate_per_kw too small for timely processing.
        if (isFeeTooSmall(open.commitmentFeerate)) {
            return Either.Left(FeerateTooSmall(open.temporaryChannelId, open.commitmentFeerate))
        }

        if (open.dustLimit > nodeParams.maxRemoteDustLimit) {
            return Either.Left(DustLimitTooLarge(open.temporaryChannelId, open.dustLimit, nodeParams.maxRemoteDustLimit))
        }

        if (open.dustLimit < Channel.MIN_DUST_LIMIT) {
            return Either.Left(DustLimitTooSmall(open.temporaryChannelId, open.dustLimit, Channel.MIN_DUST_LIMIT))
        }

        if (isFeeDiffTooHigh(FeeratePerKw.CommitmentFeerate, open.commitmentFeerate, nodeParams.onChainFeeConf.feerateTolerance)) {
            return Either.Left(FeerateTooDifferent(open.temporaryChannelId, FeeratePerKw.CommitmentFeerate, open.commitmentFeerate))
        }

        return Either.Right(channelType)
    }

    /** Called by the initiator. */
    fun validateParamsInitiator(nodeParams: NodeParams, init: ChannelCommand.Init.Initiator, open: OpenDualFundedChannel, accept: AcceptDualFundedChannel): Either<ChannelException, ChannelType> {
        require(open.channelType != null) { "we should have sent a channel type in open_channel" }
        if (accept.channelType == null) {
            // We only open channels to peers who support explicit channel type negotiation.
            return Either.Left(MissingChannelType(accept.temporaryChannelId))
        }
        if (open.channelType != accept.channelType) {
            return Either.Left(InvalidChannelType(accept.temporaryChannelId, open.channelType!!, accept.channelType!!))
        }

        if (accept.fundingAmount < 0.sat) {
            return Either.Left(InvalidFundingAmount(accept.temporaryChannelId, accept.fundingAmount))
        }

        if (accept.maxAcceptedHtlcs > Channel.MAX_ACCEPTED_HTLCS) {
            return Either.Left(InvalidMaxAcceptedHtlcs(accept.temporaryChannelId, accept.maxAcceptedHtlcs, Channel.MAX_ACCEPTED_HTLCS))
        }

        if (accept.dustLimit < Channel.MIN_DUST_LIMIT) {
            return Either.Left(DustLimitTooSmall(accept.temporaryChannelId, accept.dustLimit, Channel.MIN_DUST_LIMIT))
        }

        if (accept.dustLimit > nodeParams.maxRemoteDustLimit) {
            return Either.Left(DustLimitTooLarge(accept.temporaryChannelId, accept.dustLimit, nodeParams.maxRemoteDustLimit))
        }

        // if minimum_depth is unreasonably large: MAY reject the channel.
        if (accept.toSelfDelay > Channel.MAX_TO_SELF_DELAY || accept.toSelfDelay > nodeParams.maxToLocalDelayBlocks) {
            return Either.Left(ToSelfDelayTooHigh(accept.temporaryChannelId, accept.toSelfDelay, nodeParams.maxToLocalDelayBlocks))
        }

        return Either.Right(init.channelType)
    }

    /**
     * @param remoteFeerate remote fee rate per kiloweight
     * @return true if the remote fee rate is too small
     */
    private fun isFeeTooSmall(remoteFeerate: FeeratePerKw): Boolean = remoteFeerate < FeeratePerKw.MinimumFeeratePerKw

    /**
     * @param referenceFee reference fee rate per kiloweight
     * @param currentFee current fee rate per kiloweight
     * @param tolerance maximum fee rate mismatch tolerated
     * @return true if the difference between proposed and reference fee rates is too high.
     */
    fun isFeeDiffTooHigh(referenceFee: FeeratePerKw, currentFee: FeeratePerKw, tolerance: FeerateTolerance): Boolean =
        currentFee < referenceFee * tolerance.ratioLow || referenceFee * tolerance.ratioHigh < currentFee

    /**
     * This indicates whether our side of the channel is above the reserve requested by our counterparty. In other words,
     * this tells if we can use the channel to make a payment.
     */
    fun aboveReserve(commitments: Commitments): Boolean = commitments.active.all {
        val remoteCommit = it.nextRemoteCommit?.commit ?: it.remoteCommit
        val toRemote = remoteCommit.spec.toRemote.truncateToSatoshi()
        // NB: this is an approximation (we don't take network fees into account)
        toRemote > it.localChannelReserve(commitments.params)
    }

    /** This helper method will publish txs only if they haven't yet reached minDepth. */
    fun LoggingContext.publishIfNeeded(txs: List<ChannelAction.Blockchain.PublishTx>, irrevocablySpent: Map<OutPoint, Transaction>): List<ChannelAction.Blockchain.PublishTx> {
        val (skip, process) = txs.partition { it.tx.inputsAlreadySpent(irrevocablySpent) }
        skip.forEach { tx -> logger.info { "no need to republish txid=${tx.tx.txid}, it has already been confirmed" } }
        return process.map { publish ->
            logger.info(mapOf("txType" to publish.txType)) { "publishing txid=${publish.tx.txid}" }
            publish
        }
    }

    /** This helper method will watch txs only if they haven't yet reached minDepth. */
    fun LoggingContext.watchConfirmedIfNeeded(nodeParams: NodeParams, channelId: ByteVector32, txs: List<Transaction>, irrevocablySpent: Map<OutPoint, Transaction>): List<ChannelAction.Blockchain.SendWatch> {
        val (skip, process) = txs.partition { it.inputsAlreadySpent(irrevocablySpent) }
        skip.forEach { tx -> logger.info { "no need to watch txid=${tx.txid}, it has already been confirmed" } }
        return process.map { tx -> ChannelAction.Blockchain.SendWatch(WatchConfirmed(channelId, tx, nodeParams.minDepthBlocks, WatchConfirmed.ClosingTxConfirmed)) }
    }

    /** This helper method will watch txs only if the utxo they spend hasn't already been irrevocably spent. */
    fun LoggingContext.watchSpentIfNeeded(channelId: ByteVector32, parentTx: Transaction, outputs: List<OutPoint>, irrevocablySpent: Map<OutPoint, Transaction>): List<ChannelAction.Blockchain.SendWatch> {
        val (skip, process) = outputs.partition { irrevocablySpent.contains(it) }
        skip.forEach { output -> logger.info { "no need to watch output=${output.txid}:${output.index}, it has already been spent by txid=${irrevocablySpent[output]?.txid}" } }
        return process.map { output ->
            require(output.txid == parentTx.txid) { "output doesn't belong to the given parentTx: txid=${output.txid} but expected txid=${parentTx.txid}" }
            require(output.index < parentTx.txOut.size) { "output doesn't belong to the given parentTx: index=${output.index} but parentTx has ${parentTx.txOut.size} outputs" }
            val outputAmount = parentTx.txOut[output.index.toInt()].amount
            ChannelAction.Blockchain.SendWatch(WatchSpent(channelId, parentTx, output.index.toInt(), WatchSpent.ClosingOutputSpent(outputAmount)))
        }
    }

    object Funding {

        /** Compute the channelId of a dual-funded channel. */
        fun computeChannelId(open: OpenDualFundedChannel, accept: AcceptDualFundedChannel): ByteVector32 {
            return if (LexicographicalOrdering.isLessThan(open.revocationBasepoint.value, accept.revocationBasepoint.value)) {
                (open.revocationBasepoint.value + accept.revocationBasepoint.value).sha256()
            } else {
                (accept.revocationBasepoint.value + open.revocationBasepoint.value).sha256()
            }
        }

        fun makeFundingPubKeyScript(localFundingPubkey: PublicKey, remoteFundingPubkey: PublicKey): ByteVector {
            return write(pay2wsh(multiSig2of2(localFundingPubkey, remoteFundingPubkey))).toByteVector()
        }

        fun makeFundingInputInfo(
            fundingTxId: TxId,
            fundingTxOutputIndex: Int,
            fundingAmount: Satoshi,
            fundingPubkey1: PublicKey,
            fundingPubkey2: PublicKey
        ): Transactions.InputInfo {
            val fundingScript = multiSig2of2(fundingPubkey1, fundingPubkey2)
            val fundingTxOut = TxOut(fundingAmount, pay2wsh(fundingScript))
            return Transactions.InputInfo(
                OutPoint(fundingTxId, fundingTxOutputIndex.toLong()),
                fundingTxOut,
                ByteVector(write(fundingScript))
            )
        }

        data class PairOfCommitTxs(
            val localSpec: CommitmentSpec,
            val localCommitTx: Transactions.TransactionWithInputInfo.CommitTx,
            val localHtlcTxs: List<Transactions.TransactionWithInputInfo.HtlcTx>,
            val remoteSpec: CommitmentSpec,
            val remoteCommitTx: Transactions.TransactionWithInputInfo.CommitTx,
            val remoteHtlcTxs: List<Transactions.TransactionWithInputInfo.HtlcTx>
        )

        /**
         * Creates both sides' first commitment transaction.
         *
         * @return (localSpec, localTx, remoteSpec, remoteTx, fundingTxOutput)
         */
        fun makeCommitTxs(
            channelParams: ChannelParams,
            fundingAmount: Satoshi,
            toLocal: MilliSatoshi,
            toRemote: MilliSatoshi,
            localHtlcs: Set<DirectedHtlc>,
            localCommitmentIndex: Long,
            remoteCommitmentIndex: Long,
            commitTxFeerate: FeeratePerKw,
            fundingTxId: TxId,
            fundingTxOutputIndex: Int,
            localFundingKey: PrivateKey,
            remoteFundingPubkey: PublicKey,
            localCommitKeys: LocalCommitmentKeys,
            remoteCommitKeys: RemoteCommitmentKeys
        ): Either<ChannelException, PairOfCommitTxs> {
            val localSpec = CommitmentSpec(localHtlcs, commitTxFeerate, toLocal = toLocal, toRemote = toRemote)
            val remoteSpec = CommitmentSpec(localHtlcs.map { it.opposite() }.toSet(), commitTxFeerate, toLocal = toRemote, toRemote = toLocal)

            if (!channelParams.localParams.paysCommitTxFees) {
                // They are responsible for paying the commitment transaction fee: we need to make sure they can afford it!
                // Note that the reserve may not be always be met: we could be using dual funding with a large funding amount on
                // our side and a small funding amount on their side. But we shouldn't care as long as they can pay the fees for
                // the commitment transaction.
                val fees = commitTxFee(channelParams.remoteParams.dustLimit, remoteSpec)
                val missing = fees - remoteSpec.toLocal.truncateToSatoshi()
                if (missing > 0.sat) {
                    return Either.Left(CannotAffordFirstCommitFees(channelParams.channelId, missing = missing, fees = fees))
                }
            }

            val commitmentInput = makeFundingInputInfo(fundingTxId, fundingTxOutputIndex, fundingAmount, localFundingKey.publicKey(), remoteFundingPubkey)
            val (localCommitTx, localHtlcTxs) = Commitments.makeLocalTxs(
                channelParams = channelParams,
                commitKeys = localCommitKeys,
                commitTxNumber = localCommitmentIndex,
                localFundingKey = localFundingKey,
                remoteFundingPubKey = remoteFundingPubkey,
                commitmentInput = commitmentInput,
                spec = localSpec
            )
            val (remoteCommitTx, remoteHtlcTxs) = Commitments.makeRemoteTxs(
                channelParams = channelParams,
                commitKeys = remoteCommitKeys,
                commitTxNumber = remoteCommitmentIndex,
                localFundingKey = localFundingKey,
                remoteFundingPubKey = remoteFundingPubkey,
                commitmentInput = commitmentInput,
                spec = remoteSpec
            )
            return Either.Right(PairOfCommitTxs(localSpec, localCommitTx, localHtlcTxs, remoteSpec, remoteCommitTx, remoteHtlcTxs))
        }

    }

    object Closing {

        private fun isValidFinalScriptPubkey(scriptPubKey: ByteArray, allowAnySegwit: Boolean, allowOpReturn: Boolean): Boolean {
            return runTrying {
                val script = Script.parse(scriptPubKey)
                when {
                    Script.isPay2pkh(script) -> true
                    Script.isPay2sh(script) -> true
                    Script.isPay2wpkh(script) -> true
                    Script.isPay2wsh(script) -> true
                    // option_shutdown_anysegwit doesn't cover segwit v0
                    Script.isNativeWitnessScript(script) && script[0] != OP_0 -> allowAnySegwit
                    script.size == 2 && script[0] == OP_RETURN && allowOpReturn -> when (val push = script[1]) {
                        is OP_PUSHDATA -> OP_PUSHDATA.isMinimal(push.data.toByteArray(), push.code) && push.data.size() >= 6 && push.data.size() <= 80
                        else -> false
                    }
                    else -> false
                }
            }.getOrElse { false }
        }

        fun isValidFinalScriptPubkey(scriptPubKey: ByteVector, allowAnySegwit: Boolean, allowOpReturn: Boolean): Boolean = isValidFinalScriptPubkey(scriptPubKey.toByteArray(), allowAnySegwit, allowOpReturn)

        /** We are the closer: we sign closing transactions for which we pay the fees. */
        fun makeClosingTxs(
            channelKeys: ChannelKeys,
            commitment: FullCommitment,
            localScriptPubkey: ByteVector,
            remoteScriptPubkey: ByteVector,
            feerate: FeeratePerKw,
            lockTime: Long,
        ): Either<ChannelException, Pair<Transactions.ClosingTxs, ClosingComplete>> {
            // We must convert the feerate to a fee: we must build dummy transactions to compute their weight.
            val closingFee = run {
                val dummyClosingTxs = Transactions.makeClosingTxs(commitment.commitInput, commitment.localCommit.spec, Transactions.ClosingTxFee.PaidByUs(0.sat), lockTime, localScriptPubkey, remoteScriptPubkey)
                when (val dummyTx = dummyClosingTxs.preferred) {
                    null -> return Either.Left(CannotGenerateClosingTx(commitment.channelId))
                    else -> {
                        val dummySignedTx = Transactions.addSigs(dummyTx, Transactions.PlaceHolderPubKey, Transactions.PlaceHolderPubKey, Transactions.PlaceHolderSig, Transactions.PlaceHolderSig)
                        Transactions.ClosingTxFee.PaidByUs(Transactions.weight2fee(feerate, dummySignedTx.tx.weight()))
                    }
                }
            }
            // Now that we know the fee we're ready to pay, we can create our closing transactions.
            val closingTxs = Transactions.makeClosingTxs(commitment.commitInput, commitment.localCommit.spec, closingFee, lockTime, localScriptPubkey, remoteScriptPubkey)
            if (closingTxs.preferred == null || closingTxs.preferred.fee <= 0.sat) {
                return Either.Left(CannotGenerateClosingTx(commitment.channelId))
            }
            val localFundingKey = channelKeys.fundingKey(commitment.fundingTxIndex)
            val tlvs = TlvStream(
                setOfNotNull(
                    closingTxs.localAndRemote?.let { tx -> ClosingCompleteTlv.CloserAndCloseeOutputs(Transactions.sign(tx, localFundingKey)) },
                    closingTxs.localOnly?.let { tx -> ClosingCompleteTlv.CloserOutputOnly(Transactions.sign(tx, localFundingKey)) },
                    closingTxs.remoteOnly?.let { tx -> ClosingCompleteTlv.CloseeOutputOnly(Transactions.sign(tx, localFundingKey)) },
                )
            )
            val closingComplete = ClosingComplete(commitment.channelId, localScriptPubkey, remoteScriptPubkey, closingFee.fee, lockTime, tlvs)
            return Either.Right(Pair(closingTxs, closingComplete))
        }

        /**
         * We are the closee: we choose one of the closer's transactions and sign it back.
         *
         * Callers should ignore failures: since the protocol is fully asynchronous, failures here simply mean that they
         * are not using our latest script (race condition between our closing_complete and theirs).
         */
        fun signClosingTx(
            channelKeys: ChannelKeys,
            commitment: FullCommitment,
            localScriptPubkey: ByteVector,
            remoteScriptPubkey: ByteVector,
            closingComplete: ClosingComplete
        ): Either<ChannelException, Pair<ClosingTx, ClosingSig>> {
            val closingFee = Transactions.ClosingTxFee.PaidByThem(closingComplete.fees)
            val closingTxs = Transactions.makeClosingTxs(commitment.commitInput, commitment.localCommit.spec, closingFee, closingComplete.lockTime, localScriptPubkey, remoteScriptPubkey)
            // If our output isn't dust, they must provide a signature for a transaction that includes it.
            // Note that we're the closee, so we look for signatures including the closee output.
            if (closingTxs.localAndRemote != null && closingTxs.localOnly != null && closingComplete.closerAndCloseeOutputsSig == null && closingComplete.closeeOutputOnlySig == null) {
                return Either.Left(MissingCloseSignature(commitment.channelId))
            }
            if (closingTxs.localAndRemote != null && closingTxs.localOnly == null && closingComplete.closerAndCloseeOutputsSig == null) {
                return Either.Left(MissingCloseSignature(commitment.channelId))
            }
            if (closingTxs.localAndRemote == null && closingTxs.localOnly != null && closingComplete.closeeOutputOnlySig == null) {
                return Either.Left(MissingCloseSignature(commitment.channelId))
            }
            // We choose the closing signature that matches our preferred closing transaction.
            val closingTxsWithSigs = listOfNotNull<Triple<ClosingTx, ByteVector64, (ByteVector64) -> ClosingSigTlv>>(
                closingComplete.closerAndCloseeOutputsSig?.let { remoteSig -> closingTxs.localAndRemote?.let { tx -> Triple(tx, remoteSig) { localSig: ByteVector64 -> ClosingSigTlv.CloserAndCloseeOutputs(localSig) } } },
                closingComplete.closeeOutputOnlySig?.let { remoteSig -> closingTxs.localOnly?.let { tx -> Triple(tx, remoteSig) { localSig -> ClosingSigTlv.CloseeOutputOnly(localSig) } } },
                closingComplete.closerOutputOnlySig?.let { remoteSig -> closingTxs.remoteOnly?.let { tx -> Triple(tx, remoteSig) { localSig -> ClosingSigTlv.CloserOutputOnly(localSig) } } },
            )
            return when (val preferred = closingTxsWithSigs.firstOrNull()) {
                null -> Either.Left(MissingCloseSignature(commitment.channelId))
                else -> {
                    val (closingTx, remoteSig, sigToTlv) = preferred
                    val localFundingKey = channelKeys.fundingKey(commitment.fundingTxIndex)
                    val localSig = Transactions.sign(closingTx, localFundingKey)
                    val signedClosingTx = Transactions.addSigs(closingTx, localFundingKey.publicKey(), commitment.remoteFundingPubkey, localSig, remoteSig)
                    when (Transactions.checkSpendable(signedClosingTx)) {
                        is Try.Failure -> Either.Left(InvalidCloseSignature(commitment.channelId, signedClosingTx.tx.txid))
                        is Try.Success -> Either.Right(Pair(signedClosingTx, ClosingSig(commitment.channelId, remoteScriptPubkey, localScriptPubkey, closingComplete.fees, closingComplete.lockTime, TlvStream(sigToTlv(localSig)))))
                    }
                }
            }
        }

        /**
         * We are the closer: they sent us their signature so we should now have a fully signed closing transaction.
         *
         * Callers should ignore failures: since the protocol is fully asynchronous, failures here simply mean that we
         * sent another closing_complete before receiving their closing_sig, which is now obsolete: we ignore it and wait
         * for their next closing_sig that will match our latest closing_complete.
         */
        fun receiveClosingSig(
            channelKeys: ChannelKeys,
            commitment: FullCommitment,
            closingTxs: Transactions.ClosingTxs,
            closingSig: ClosingSig
        ): Either<ChannelException, ClosingTx> {
            val closingTxsWithSig = listOfNotNull(
                closingSig.closerAndCloseeOutputsSig?.let { sig -> closingTxs.localAndRemote?.let { tx -> Pair(tx, sig) } },
                closingSig.closerOutputOnlySig?.let { sig -> closingTxs.localOnly?.let { tx -> Pair(tx, sig) } },
                closingSig.closeeOutputOnlySig?.let { sig -> closingTxs.remoteOnly?.let { tx -> Pair(tx, sig) } },
            )
            return when (val preferred = closingTxsWithSig.firstOrNull()) {
                null -> Either.Left(MissingCloseSignature(commitment.channelId))
                else -> {
                    val (closingTx, remoteSig) = preferred
                    val localFundingKey = channelKeys.fundingKey(commitment.fundingTxIndex)
                    val localSig = Transactions.sign(closingTx, localFundingKey)
                    val signedClosingTx = Transactions.addSigs(closingTx, localFundingKey.publicKey(), commitment.remoteFundingPubkey, localSig, remoteSig)
                    when (Transactions.checkSpendable(signedClosingTx)) {
                        is Try.Failure -> Either.Left(InvalidCloseSignature(commitment.channelId, signedClosingTx.tx.txid))
                        is Try.Success -> Either.Right(signedClosingTx)
                    }
                }
            }
        }

        /**
         * Claim all the outputs that we can from our current commit tx.
         *
         * @param commitment our commitment data, which includes payment preimages.
         * @return a list of transactions (one per output that we can claim).
         */
        fun LoggingContext.claimCurrentLocalCommitTxOutputs(channelKeys: ChannelKeys, commitment: FullCommitment, commitTx: Transaction, feerates: OnChainFeerates): LocalCommitPublished {
            require(commitment.localCommit.publishableTxs.commitTx.tx.txid == commitTx.txid) { "txid mismatch, provided tx is not the current local commit tx" }
            val commitKeys = channelKeys.localCommitmentKeys(commitment.params, commitment.localCommit.index)
            val feerateDelayed = feerates.claimMainFeerate

            // first we will claim our main output as soon as the delay is over
            val mainDelayedTx = generateTx("main-delayed-output") {
                Transactions.makeClaimLocalDelayedOutputTx(
                    commitTx,
                    commitment.params.localParams.dustLimit,
                    commitKeys.revocationPublicKey,
                    commitment.params.remoteParams.toSelfDelay,
                    commitKeys.ourDelayedPaymentKey.publicKey(),
                    commitment.params.localParams.defaultFinalScriptPubKey.toByteArray(),
                    feerateDelayed
                )
            }?.let {
                val sig = Transactions.sign(it, commitKeys.ourDelayedPaymentKey, SigHash.SIGHASH_ALL)
                Transactions.addSigs(it, sig)
            }

            // We collect all the preimages we wanted to reveal to our peer.
            val hash2Preimage = commitment.changes.localChanges.all.filterIsInstance<UpdateFulfillHtlc>().map { it.paymentPreimage }.associateBy { r -> r.sha256() }
            // We collect incoming HTLCs that we started failing but didn't cross-sign.
            val failedIncomingHtlcs = commitment.changes.localChanges.all.mapNotNull {
                when (it) {
                    is UpdateFailHtlc -> it.id
                    is UpdateFailMalformedHtlc -> it.id
                    else -> null
                }
            }.toSet()

            val htlcTxs = commitment.localCommit.publishableTxs.htlcTxsAndSigs.mapNotNull {
                when (it.txinfo) {
                    is HtlcSuccessTx -> when {
                        // We immediately spend incoming htlcs for which we have the preimage.
                        hash2Preimage.containsKey(it.txinfo.paymentHash) -> Pair(it.txinfo.input.outPoint, Transactions.addSigs(it.txinfo, it.localSig, it.remoteSig, hash2Preimage[it.txinfo.paymentHash]!!))
                        // We can ignore incoming htlcs that we started failing: our peer will claim them after the timeout.
                        // We don't track those outputs because we want to forget the channel even if our peer never claims them.
                        failedIncomingHtlcs.contains(it.txinfo.htlcId) -> null
                        // For all other incoming htlcs, we may reveal the preimage later if it matches one of our unpaid invoices.
                        // We thus want to track the corresponding outputs to ensure we don't forget the channel until they've been spent,
                        // either by us if we accept the payment, or by our peer after the timeout.
                        else -> Pair(it.txinfo.input.outPoint, null)
                    }
                    // We track all outputs that belong to outgoing htlcs. Our peer may or may not have the preimage: if they
                    // claim the output, we will learn the preimage from their transaction, otherwise we will get our funds
                    // back after the timeout.
                    is HtlcTimeoutTx -> Pair(it.txinfo.input.outPoint, Transactions.addSigs(it.txinfo, it.localSig, it.remoteSig))
                }
            }.toMap()

            // All htlc output to us are delayed, so we need to claim them as soon as the delay is over.
            val htlcDelayedTxs = htlcTxs.values.filterNotNull().mapNotNull { txInfo ->
                generateTx("claim-htlc-delayed") {
                    Transactions.makeClaimLocalDelayedOutputTx(
                        txInfo.tx,
                        commitment.params.localParams.dustLimit,
                        commitKeys.revocationPublicKey,
                        commitment.params.remoteParams.toSelfDelay,
                        commitKeys.ourDelayedPaymentKey.publicKey(),
                        commitment.params.localParams.defaultFinalScriptPubKey.toByteArray(),
                        feerateDelayed
                    )
                }?.let {
                    val sig = Transactions.sign(it, commitKeys.ourDelayedPaymentKey, SigHash.SIGHASH_ALL)
                    Transactions.addSigs(it, sig)
                }
            }

            return LocalCommitPublished(
                commitTx = commitTx,
                claimMainDelayedOutputTx = mainDelayedTx,
                htlcTxs = htlcTxs,
                claimHtlcDelayedTxs = htlcDelayedTxs,
                claimAnchorTxs = emptyList(),
                irrevocablySpent = emptyMap()
            )
        }

        /**
         * Claim all the outputs that we can from their current commit tx.
         *
         * @param commitment our commitment data, which includes payment preimages.
         * @param remoteCommit the remote commitment data to use to claim outputs (it can be their current or next commitment).
         * @param commitTx the remote commitment transaction that has just been published.
         * @return a list of transactions (one per output that we can claim).
         */
        fun LoggingContext.claimRemoteCommitTxOutputs(channelKeys: ChannelKeys, commitment: FullCommitment, remoteCommit: RemoteCommit, commitTx: Transaction, feerates: OnChainFeerates): RemoteCommitPublished {
            val fundingKey = channelKeys.fundingKey(commitment.fundingTxIndex)
            val commitKeys = channelKeys.remoteCommitmentKeys(commitment.params, remoteCommit.remotePerCommitmentPoint)
            val (remoteCommitTx, _) = Commitments.makeRemoteTxs(
                channelParams = commitment.params,
                commitKeys = commitKeys,
                commitTxNumber = remoteCommit.index,
                localFundingKey = fundingKey,
                remoteFundingPubKey = commitment.remoteFundingPubkey,
                commitmentInput = commitment.commitInput,
                spec = remoteCommit.spec
            )
            require(remoteCommitTx.tx.txid == commitTx.txid) { "txid mismatch, provided tx is not the current remote commit tx" }

            val outputs = makeCommitTxOutputs(
                localFundingPubkey = fundingKey.publicKey(),
                remoteFundingPubkey = commitment.remoteFundingPubkey,
                commitKeys = commitKeys.publicKeys,
                payCommitTxFees = !commitment.params.localParams.paysCommitTxFees,
                dustLimit = commitment.params.remoteParams.dustLimit,
                toSelfDelay = commitment.params.localParams.toSelfDelay,
                spec = remoteCommit.spec
            )

            // We need to use a rather high fee for htlc-claim because we compete with the counterparty.
            val feerateClaimHtlc = feerates.fastFeerate

            // We collect all the preimages we wanted to reveal to our peer.
            val hash2Preimage = commitment.changes.localChanges.all.filterIsInstance<UpdateFulfillHtlc>().map { it.paymentPreimage }.associateBy { r -> r.sha256() }
            // We collect incoming HTLCs that we started failing but didn't cross-sign.
            val failedIncomingHtlcs = commitment.changes.localChanges.all.mapNotNull {
                when (it) {
                    is UpdateFailHtlc -> it.id
                    is UpdateFailMalformedHtlc -> it.id
                    else -> null
                }
            }.toSet()

            // Remember we are looking at the remote commitment so IN for them is really OUT for us and vice versa.
            val claimHtlcTxs = remoteCommit.spec.htlcs.mapNotNull { htlc ->
                when (htlc) {
                    is OutgoingHtlc -> {
                        generateTx("claim-htlc-success") {
                            Transactions.makeClaimHtlcSuccessTx(
                                commitTx = remoteCommitTx.tx,
                                outputs = outputs,
                                localDustLimit = commitment.params.localParams.dustLimit,
                                localHtlcPubkey = commitKeys.ourHtlcKey.publicKey(),
                                remoteHtlcPubkey = commitKeys.theirHtlcPublicKey,
                                remoteRevocationPubkey = commitKeys.revocationPublicKey,
                                localFinalScriptPubKey = commitment.params.localParams.defaultFinalScriptPubKey.toByteArray(),
                                htlc = htlc.add,
                                feerate = feerateClaimHtlc
                            )
                        }?.let { claimHtlcTx ->
                            when {
                                // We immediately spend incoming htlcs for which we have the preimage.
                                hash2Preimage.containsKey(htlc.add.paymentHash) -> {
                                    val sig = Transactions.sign(claimHtlcTx, commitKeys.ourHtlcKey, SigHash.SIGHASH_ALL)
                                    Pair(claimHtlcTx.input.outPoint, Transactions.addSigs(claimHtlcTx, sig, hash2Preimage[htlc.add.paymentHash]!!))
                                }
                                // We can ignore incoming htlcs that we started failing: our peer will claim them after the timeout.
                                // We don't track those outputs because we want to forget the channel even if our peer never claims them.
                                failedIncomingHtlcs.contains(htlc.add.id) -> null
                                // For all other incoming htlcs, we may reveal the preimage later if it matches one of our unpaid invoices.
                                // We thus want to track the corresponding outputs to ensure we don't forget the channel until they've been spent,
                                // either by us if we accept the payment, or by our peer after the timeout.
                                else -> Pair(claimHtlcTx.input.outPoint, null)
                            }
                        }
                    }
                    is IncomingHtlc -> {
                        // We track all outputs that belong to outgoing htlcs. Our peer may or may not have the preimage: if they
                        // claim the output, we will learn the preimage from their transaction, otherwise we will get our funds
                        // back after the timeout.
                        generateTx("claim-htlc-timeout") {
                            Transactions.makeClaimHtlcTimeoutTx(
                                commitTx = remoteCommitTx.tx,
                                outputs = outputs,
                                localDustLimit = commitment.params.localParams.dustLimit,
                                localHtlcPubkey = commitKeys.ourHtlcKey.publicKey(),
                                remoteHtlcPubkey = commitKeys.theirHtlcPublicKey,
                                remoteRevocationPubkey = commitKeys.revocationPublicKey,
                                localFinalScriptPubKey = commitment.params.localParams.defaultFinalScriptPubKey.toByteArray(),
                                htlc = htlc.add,
                                feerate = feerateClaimHtlc
                            )
                        }?.let { claimHtlcTx ->
                            val sig = Transactions.sign(claimHtlcTx, commitKeys.ourHtlcKey, SigHash.SIGHASH_ALL)
                            Pair(claimHtlcTx.input.outPoint, Transactions.addSigs(claimHtlcTx, sig))
                        }
                    }
                }
            }.toMap()

            // We claim our output and add the htlc txs we just created.
            return claimRemoteCommitMainOutput(commitKeys, commitTx, commitment.params.localParams.dustLimit, commitment.params.localParams.defaultFinalScriptPubKey, feerates.claimMainFeerate).copy(claimHtlcTxs = claimHtlcTxs)
        }

        /**
         * Claim our main output only from their commit tx.
         *
         * @param commitTx the remote commitment transaction that has just been published.
         * @return a transaction to claim our main output.
         */
        internal fun LoggingContext.claimRemoteCommitMainOutput(commitKeys: RemoteCommitmentKeys, commitTx: Transaction, dustLimit: Satoshi, finalScriptPubKey: ByteVector, claimMainFeerate: FeeratePerKw): RemoteCommitPublished {
            val mainTx = generateTx("claim-remote-delayed-output") {
                Transactions.makeClaimRemoteDelayedOutputTx(
                    commitTx = commitTx,
                    localDustLimit = dustLimit,
                    localPaymentPubkey = commitKeys.ourPaymentKey.publicKey(),
                    localFinalScriptPubKey = finalScriptPubKey,
                    feerate = claimMainFeerate
                )
            }?.let {
                val sig = Transactions.sign(it, commitKeys.ourPaymentKey)
                Transactions.addSigs(it, sig)
            }
            return RemoteCommitPublished(commitTx = commitTx, claimMainOutputTx = mainTx)
        }

        /**
         * When an unexpected transaction spending the funding tx is detected, we must be in one of the following scenarios:
         *
         *  - it is a revoked commitment: we then extract the remote per-commitment secret and publish penalty transactions
         *  - it is a future commitment: if we lost future state, our peer could publish a future commitment (which may be
         *    revoked, but we won't be able to know because we lost the corresponding state)
         *  - it is not a valid commitment transaction: if our peer was able to steal our funding private key, they can
         *    spend the funding transaction however they want, and we won't be able to do anything about it
         *
         * This function returns the per-commitment secret in the first case, and null in the other cases.
         */
        fun getRemotePerCommitmentSecret(params: ChannelParams, channelKeys: ChannelKeys, remotePerCommitmentSecrets: ShaChain, commitTx: Transaction): Pair<PrivateKey, Long>? {
            // a valid tx will always have at least one input, but this ensures we don't throw in tests
            val sequence = commitTx.txIn.first().sequence
            val obscuredTxNumber = Transactions.decodeTxNumber(sequence, commitTx.lockTime)
            val localPaymentPoint = channelKeys.paymentBasePoint
            // this tx has been published by remote, so we need to invert local/remote params
            val commitmentNumber = Transactions.obscuredCommitTxNumber(obscuredTxNumber, !params.localParams.isChannelOpener, params.remoteParams.paymentBasepoint, localPaymentPoint)
            if (commitmentNumber > 0xffffffffffffL) {
                // txNumber must be lesser than 48 bits long
                return null
            }
            // now we know what commit number this tx is referring to, we can derive the commitment point from the shachain
            val hash = remotePerCommitmentSecrets.getHash(0xFFFFFFFFFFFFL - commitmentNumber) ?: return null
            return Pair(PrivateKey(hash), commitmentNumber)
        }

        /**
         * When a revoked commitment transaction spending the funding tx is detected, we build a set of transactions that
         * will punish our peer by stealing all their funds.
         */
        fun LoggingContext.claimRevokedRemoteCommitTxOutputs(params: ChannelParams, channelKeys: ChannelKeys, commitTx: Transaction, remotePerCommitmentSecret: PrivateKey, feerates: OnChainFeerates): RevokedCommitPublished {
            val remotePerCommitmentPoint = remotePerCommitmentSecret.publicKey()
            val commitKeys = channelKeys.remoteCommitmentKeys(params, remotePerCommitmentPoint)
            val revocationKey = channelKeys.revocationKey(remotePerCommitmentSecret)
            val feerateMain = feerates.claimMainFeerate
            // we need to use a high fee here for punishment txs because after a delay they can be spent by the counterparty
            val feeratePenalty = feerates.fastFeerate

            // first we will claim our main output right away
            val mainTx = generateTx("claim-remote-delayed-output") {
                Transactions.makeClaimRemoteDelayedOutputTx(
                    commitTx,
                    params.localParams.dustLimit,
                    commitKeys.ourPaymentKey.publicKey(),
                    params.localParams.defaultFinalScriptPubKey,
                    feerateMain
                )
            }?.let {
                val sig = Transactions.sign(it, commitKeys.ourPaymentKey)
                Transactions.addSigs(it, sig)
            }

            // then we punish them by stealing their main output
            val mainPenaltyTx = generateTx("main-penalty") {
                Transactions.makeMainPenaltyTx(
                    commitTx,
                    params.localParams.dustLimit,
                    commitKeys.revocationPublicKey,
                    params.localParams.defaultFinalScriptPubKey.toByteArray(),
                    params.localParams.toSelfDelay,
                    commitKeys.theirDelayedPaymentPublicKey,
                    feeratePenalty
                )
            }?.let {
                val sig = Transactions.sign(it, revocationKey)
                Transactions.addSigs(it, sig)
            }

            return RevokedCommitPublished(commitTx = commitTx, remotePerCommitmentSecret = remotePerCommitmentSecret, claimMainOutputTx = mainTx, mainPenaltyTx = mainPenaltyTx)
        }

        /**
         * Once we've fetched htlc information for a revoked commitment from the DB, we create penalty transactions to claim all htlc outputs.
         */
        fun LoggingContext.claimRevokedRemoteCommitTxHtlcOutputs(
            params: ChannelParams,
            channelKeys: ChannelKeys,
            revokedCommitPublished: RevokedCommitPublished,
            feerates: OnChainFeerates,
            htlcInfos: List<ChannelAction.Storage.HtlcInfo>
        ): RevokedCommitPublished {
            // we need to use a high fee here for punishment txs because after a delay they can be spent by the counterparty
            val feeratePenalty = feerates.fastFeerate
            val remotePerCommitmentPoint = revokedCommitPublished.remotePerCommitmentSecret.publicKey()
            val commitKeys = channelKeys.remoteCommitmentKeys(params, remotePerCommitmentPoint)
            val revocationKey = channelKeys.revocationKey(revokedCommitPublished.remotePerCommitmentSecret)
            // we retrieve the information needed to rebuild htlc scripts
            logger.info { "found ${htlcInfos.size} htlcs for txid=${revokedCommitPublished.commitTx.txid}" }
            val htlcsRedeemScripts = htlcInfos.flatMap { htlcInfo ->
                val htlcReceived = Scripts.htlcReceived(commitKeys.theirHtlcPublicKey, commitKeys.ourHtlcKey.publicKey(), commitKeys.revocationPublicKey, ripemd160(htlcInfo.paymentHash), htlcInfo.cltvExpiry)
                val htlcOffered = Scripts.htlcOffered(commitKeys.theirHtlcPublicKey, commitKeys.ourHtlcKey.publicKey(), commitKeys.revocationPublicKey, ripemd160(htlcInfo.paymentHash))
                listOf(htlcReceived, htlcOffered)
            }.associate { redeemScript -> write(pay2wsh(redeemScript)).toByteVector() to write(redeemScript).toByteVector() }
            // and finally we steal the htlc outputs
            val htlcPenaltyTxs = revokedCommitPublished.commitTx.txOut.mapIndexedNotNull { outputIndex, txOut ->
                htlcsRedeemScripts[txOut.publicKeyScript]?.let { redeemScript ->
                    generateTx("htlc-penalty") {
                        Transactions.makeHtlcPenaltyTx(
                            revokedCommitPublished.commitTx,
                            outputIndex,
                            redeemScript.toByteArray(),
                            params.localParams.dustLimit,
                            params.localParams.defaultFinalScriptPubKey.toByteArray(),
                            feeratePenalty
                        )
                    }?.let { htlcPenaltyTx ->
                        val sig = Transactions.sign(htlcPenaltyTx, revocationKey)
                        Transactions.addSigs(htlcPenaltyTx, sig, commitKeys.revocationPublicKey)
                    }
                }
            }
            return revokedCommitPublished.copy(htlcPenaltyTxs = htlcPenaltyTxs)
        }

        /**
         * Claims the output of an [[HtlcSuccessTx]] or [[HtlcTimeoutTx]] transaction using a revocation key.
         *
         * In case a revoked commitment with pending HTLCs is published, there are two ways the HTLC outputs can be taken as punishment:
         * - by spending the corresponding output of the commitment tx, using [[ClaimHtlcDelayedOutputPenaltyTx]] that we generate as soon as we detect that a revoked commit
         * has been spent; note that those transactions will compete with [[HtlcSuccessTx]] and [[HtlcTimeoutTx]] published by the counterparty.
         * - by spending the delayed output of [[HtlcSuccessTx]] and [[HtlcTimeoutTx]] if those get confirmed; because the output of these txs is protected by
         * an OP_CSV delay, we will have time to spend them with a revocation key. In that case, we generate the spending transactions "on demand",
         * this is the purpose of this method.
         *
         * NB: when anchor outputs is used, htlc transactions can be aggregated in a single transaction if they share the same
         * lockTime (thanks to the use of sighash_single | sighash_anyonecanpay), so we may need to claim multiple outputs.
         */
        fun LoggingContext.claimRevokedHtlcTxOutputs(
            params: ChannelParams,
            channelKeys: ChannelKeys,
            revokedCommitPublished: RevokedCommitPublished,
            htlcTx: Transaction,
            feerates: OnChainFeerates
        ): Pair<RevokedCommitPublished, List<ClaimHtlcDelayedOutputPenaltyTx>> {
            // We published HTLC-penalty transactions for every HTLC output: this transaction may be ours, or it may be one
            // of their HTLC transactions that confirmed before our HTLC-penalty transaction. If it is spending an HTLC
            // output, we assume that it's an HTLC transaction published by our peer and try to create penalty transactions
            // that spend it, which will automatically be skipped if this was instead one of our HTLC-penalty transactions.
            val htlcOutputs = revokedCommitPublished.htlcPenaltyTxs.map { it.input.outPoint }.toSet()
            val spendsHtlcOutput = htlcTx.txIn.any { htlcOutputs.contains(it.outPoint) }
            if (spendsHtlcOutput) {
                val remotePerCommitmentPoint = revokedCommitPublished.remotePerCommitmentSecret.publicKey()
                val commitKeys = channelKeys.remoteCommitmentKeys(params, remotePerCommitmentPoint)
                val revocationKey = channelKeys.revocationKey(revokedCommitPublished.remotePerCommitmentSecret)
                // we need to use a high fee here for punishment txs because after a delay they can be spent by the counterparty
                val feeratePenalty = feerates.fastFeerate
                val penaltyTxs = Transactions.makeClaimDelayedOutputPenaltyTxs(
                    htlcTx,
                    params.localParams.dustLimit,
                    commitKeys.revocationPublicKey,
                    params.localParams.toSelfDelay,
                    commitKeys.theirDelayedPaymentPublicKey,
                    params.localParams.defaultFinalScriptPubKey.toByteArray(),
                    feeratePenalty
                ).mapNotNull { claimDelayedOutputPenaltyTx ->
                    generateTx("claim-htlc-delayed-penalty") {
                        claimDelayedOutputPenaltyTx
                    }?.let {
                        val sig = Transactions.sign(it, revocationKey)
                        val signedTx = Transactions.addSigs(it, sig)
                        // we need to make sure that the tx is indeed valid
                        when (runTrying { signedTx.tx.correctlySpends(listOf(htlcTx), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS) }) {
                            is Try.Success -> {
                                logger.info { "txId=${htlcTx.txid} is a 2nd level htlc tx spending revoked commit txId=${revokedCommitPublished.commitTx.txid}: publishing htlc-penalty txId=${signedTx.tx.txid}" }
                                signedTx
                            }
                            is Try.Failure -> null
                        }
                    }
                }
                return revokedCommitPublished.copy(claimHtlcDelayedPenaltyTxs = revokedCommitPublished.claimHtlcDelayedPenaltyTxs + penaltyTxs) to penaltyTxs
            } else {
                return revokedCommitPublished to listOf()
            }
        }

        /**
         * In CLOSING state, any time we see a new transaction, we try to extract a preimage from it in order to mark
         * the corresponding outgoing htlc as sent.
         *
         * If we didn't do that, some of our outgoing payments would appear as failed whereas the recipient has
         * revealed the preimage and our peer has claimed our htlcs, which would be misleading. We also would
         * not have a proof of payment to show to the recipient if they were malicious.
         *
         * @return a set of pairs (add, preimage) if extraction was successful:
         *           - add is the htlc from which we extracted the preimage
         *           - preimage needs to be sent to the outgoing payment handler
         */
        fun LoggingContext.extractPreimages(commitment: FullCommitment, tx: Transaction): Set<Pair<UpdateAddHtlc, ByteVector32>> {
            val htlcSuccess = Scripts.extractPreimagesFromHtlcSuccess(tx)
            htlcSuccess.forEach { logger.info { "extracted paymentPreimage=$it from tx=$tx (htlc-success)" } }
            val claimHtlcSuccess = Scripts.extractPreimagesFromClaimHtlcSuccess(tx)
            claimHtlcSuccess.forEach { logger.info { "extracted paymentPreimage=$it from tx=$tx (claim-htlc-success)" } }
            val paymentPreimages = htlcSuccess + claimHtlcSuccess
            return paymentPreimages.flatMap { paymentPreimage ->
                val paymentHash = paymentPreimage.sha256()
                // We only care about outgoing HTLCs when we're trying to learn a preimage.
                // Note that we may have already relayed the fulfill to the payment handler if we already saw the preimage.
                val fromLocal = commitment.localCommit.spec.htlcs
                    .filter { it is OutgoingHtlc && it.add.paymentHash == paymentHash }
                    .map { it.add to paymentPreimage }
                // From the remote point of view, those are incoming HTLCs.
                val fromRemote = commitment.remoteCommit.spec.htlcs
                    .filter { it is IncomingHtlc && it.add.paymentHash == paymentHash }
                    .map { it.add to paymentPreimage }
                val fromNextRemote = commitment.nextRemoteCommit?.commit?.spec?.htlcs.orEmpty()
                    .filter { it is IncomingHtlc && it.add.paymentHash == paymentHash }
                    .map { it.add to paymentPreimage }
                fromLocal + fromRemote + fromNextRemote
            }.toSet()
        }

        /**
         * In CLOSING state, when we are notified that a transaction has been confirmed, we analyze it to find out if one or
         * more htlcs have timed out and need to be considered failed. Trimmed htlcs can be failed as soon as the commitment
         * tx has been confirmed.
         *
         * @param tx a tx that has reached min_depth
         * @return a set of outgoing htlcs that can be considered failed
         */
        fun LoggingContext.trimmedOrTimedOutHtlcs(localCommit: LocalCommit, localCommitPublished: LocalCommitPublished, localDustLimit: Satoshi, tx: Transaction): Set<UpdateAddHtlc> {
            val untrimmedHtlcs = Transactions.trimOfferedHtlcs(localDustLimit, localCommit.spec).map { it.add }
            return when {
                tx.txid == localCommit.publishableTxs.commitTx.tx.txid -> {
                    // The commitment tx is confirmed: we can immediately fail all dust htlcs (they don't have an output in the tx).
                    (localCommit.spec.htlcs.outgoings() - untrimmedHtlcs.toSet()).toSet()
                }
                else -> {
                    // Maybe this is a timeout tx: in that case we can resolve and fail the corresponding htlc.
                    tx.txIn.mapNotNull { txIn ->
                        when (val htlcTx = localCommitPublished.htlcTxs[txIn.outPoint]) {
                            is HtlcTimeoutTx -> {
                                val htlc = untrimmedHtlcs.find { it.id == htlcTx.htlcId }
                                when {
                                    // This may also be our peer claiming the HTLC by revealing the preimage: in that case we have already
                                    // extracted the preimage with [extractPreimages] and relayed it to the payment handler.
                                    Scripts.extractPreimagesFromClaimHtlcSuccess(tx).isNotEmpty() -> {
                                        logger.info { "htlc-timeout double-spent by claim-htlc-success txId=${tx.txid} (tx=$tx)" }
                                        null
                                    }
                                    htlc != null -> {
                                        logger.info { "htlc-timeout tx for htlc #${htlc.id} paymentHash=${htlc.paymentHash} expiry=${tx.lockTime} has been confirmed (tx=$tx)" }
                                        htlc
                                    }
                                    else -> {
                                        logger.error { "could not find htlc #${htlcTx.htlcId} for htlc-timeout tx=$tx" }
                                        null
                                    }
                                }
                            }
                            else -> null
                        }
                    }.toSet()
                }
            }
        }

        /**
         * In CLOSING state, when we are notified that a transaction has been confirmed, we analyze it to find out if one or
         * more htlcs have timed out and need to be considered failed. Trimmed htlcs can be failed as soon as the commitment
         * tx has been confirmed.
         *
         * @param tx a tx that has reached min_depth
         * @return a set of htlcs that need to be failed upstream
         */
        fun LoggingContext.trimmedOrTimedOutHtlcs(remoteCommit: RemoteCommit, remoteCommitPublished: RemoteCommitPublished, remoteDustLimit: Satoshi, tx: Transaction): Set<UpdateAddHtlc> {
            val untrimmedHtlcs = Transactions.trimReceivedHtlcs(remoteDustLimit, remoteCommit.spec).map { it.add }
            return when {
                tx.txid == remoteCommit.txid -> {
                    // The commitment tx is confirmed: we can immediately fail all dust htlcs (they don't have an output in the tx).
                    (remoteCommit.spec.htlcs.incomings() - untrimmedHtlcs.toSet()).toSet()
                }
                else -> {
                    // Maybe this is a timeout tx: in that case we can resolve and fail the corresponding htlc.
                    tx.txIn.mapNotNull { txIn ->
                        when (val htlcTx = remoteCommitPublished.claimHtlcTxs[txIn.outPoint]) {
                            is ClaimHtlcTimeoutTx -> {
                                val htlc = untrimmedHtlcs.find { it.id == htlcTx.htlcId }
                                when {
                                    // This may also be our peer claiming the HTLC by revealing the preimage: in that case we have already
                                    // extracted the preimage with [extractPreimages] and relayed it upstream.
                                    Scripts.extractPreimagesFromHtlcSuccess(tx).isNotEmpty() -> {
                                        logger.info { "claim-htlc-timeout double-spent by htlc-success txId=${tx.txid} (tx=$tx)" }
                                        null
                                    }
                                    htlc != null -> {
                                        logger.info { "claim-htlc-timeout tx for htlc #${htlc.id} paymentHash=${htlc.paymentHash} expiry=${tx.lockTime} has been confirmed (tx=$tx)" }
                                        htlc
                                    }
                                    else -> {
                                        logger.error { "could not find htlc #${htlcTx.htlcId} for claim-htlc-timeout tx=$tx" }
                                        null
                                    }
                                }
                            }
                            else -> null
                        }
                    }.toSet()
                }
            }
        }

        /**
         * As soon as a local or remote commitment reaches min_depth, we know which htlcs will be settled on-chain (whether
         * or not they actually have an output in the commitment tx).
         *
         * @param tx a transaction that is sufficiently buried in the blockchain
         */
        fun onChainOutgoingHtlcs(localCommit: LocalCommit, remoteCommit: RemoteCommit, nextRemoteCommit: RemoteCommit?, tx: Transaction): Set<UpdateAddHtlc> = when {
            localCommit.publishableTxs.commitTx.tx.txid == tx.txid -> localCommit.spec.htlcs.outgoings().toSet()
            remoteCommit.txid == tx.txid -> remoteCommit.spec.htlcs.incomings().toSet()
            nextRemoteCommit?.txid == tx.txid -> nextRemoteCommit.spec.htlcs.incomings().toSet()
            else -> emptySet()
        }

        /**
         * If a commitment tx reaches min_depth, we need to fail the outgoing htlcs that will never reach the blockchain.
         * It could be because only us had signed them, because a revoked commitment got confirmed, or the next commitment
         * didn't contain those HTLCs.
         */
        fun overriddenOutgoingHtlcs(localCommit: LocalCommit, remoteCommit: RemoteCommit, nextRemoteCommit: RemoteCommit?, revokedCommitPublished: List<RevokedCommitPublished>, tx: Transaction): Set<UpdateAddHtlc> {
            // NB: from the p.o.v of remote, their incoming htlcs are our outgoing htlcs.
            val outgoingHtlcs = (localCommit.spec.htlcs.outgoings() + remoteCommit.spec.htlcs.incomings() + nextRemoteCommit?.spec?.htlcs.orEmpty().incomings()).toSet()
            return when {
                // Our commit got confirmed: any htlc that is *not* in our commit will never reach the chain.
                localCommit.publishableTxs.commitTx.tx.txid == tx.txid -> outgoingHtlcs - localCommit.spec.htlcs.outgoings().toSet()
                // A revoked commitment got confirmed: we will claim its outputs, but we also need to resolve htlcs.
                // We consider *all* outgoing htlcs failed: our peer may reveal the preimage with an HTLC-success transaction,
                // but it's more likely that our penalty transaction will confirm first. In any case, since we will get those
                // funds back on-chain, it's as if the outgoing htlc had failed, therefore it doesn't hurt to be failed back
                // to the payment handler. If we already received the preimage, then the fail will be a no-op.
                revokedCommitPublished.map { it.commitTx.txid }.contains(tx.txid) -> outgoingHtlcs
                // Their current commit got confirmed: any htlc that is *not* in their current commit will never reach the chain.
                remoteCommit.txid == tx.txid -> outgoingHtlcs - remoteCommit.spec.htlcs.incomings().toSet()
                // Their next commit got confirmed: any htlc that is *not* in their next commit will never reach the chain.
                nextRemoteCommit?.txid == tx.txid -> outgoingHtlcs - nextRemoteCommit.spec.htlcs.incomings().toSet()
                else -> emptySet()
            }
        }

        /**
         * This helper function tells if the utxos consumed by the given transaction has already been irrevocably spent (possibly by this very transaction)
         *
         * It can be useful to:
         *   - not attempt to publish this tx when we know this will fail
         *   - not watch for confirmations if we know the tx is already confirmed
         *   - not watch the corresponding utxo when we already know the final spending tx
         *
         * @param irrevocablySpent a map of known spent outpoints
         * @return true if we know for sure that the utxos consumed by the tx have already irrevocably been spent, false otherwise
         */
        fun Transaction.inputsAlreadySpent(irrevocablySpent: Map<OutPoint, Transaction>): Boolean {
            // NB: some transactions may have multiple inputs (e.g. htlc txs)
            val outPoints = txIn.map { it.outPoint }
            return outPoints.any { irrevocablySpent.contains(it) }
        }

        /**
         * Wraps transaction generation in a Try and filters failures to avoid one transaction negatively impacting a whole commitment.
         */
        private fun <T : Transactions.TransactionWithInputInfo> LoggingContext.generateTx(desc: String, attempt: () -> Transactions.TxResult<T>): T? =
            when (val result = runTrying { attempt() }) {
                is Try.Success -> when (val txResult = result.get()) {
                    is Transactions.TxResult.Success -> {
                        logger.info { "tx generation success: desc=$desc txid=${txResult.result.tx.txid} amount=${txResult.result.tx.txOut.map { it.amount }.sum()} tx=${txResult.result.tx}" }
                        txResult.result
                    }
                    is Transactions.TxResult.Skipped -> {
                        logger.info { "tx generation skipped: desc=$desc reason: ${txResult.why}" }
                        null
                    }
                }
                is Try.Failure -> {
                    logger.warning { "tx generation failure: desc=$desc reason: ${result.error.message}" }
                    null
                }
            }
    }
}
