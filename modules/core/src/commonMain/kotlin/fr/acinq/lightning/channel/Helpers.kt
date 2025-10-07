package fr.acinq.lightning.channel

import fr.acinq.bitcoin.*
import fr.acinq.bitcoin.crypto.musig2.IndividualNonce
import fr.acinq.bitcoin.utils.Either
import fr.acinq.bitcoin.utils.Try
import fr.acinq.bitcoin.utils.flatMap
import fr.acinq.bitcoin.utils.runTrying
import fr.acinq.lightning.CltvExpiryDelta
import fr.acinq.lightning.MilliSatoshi
import fr.acinq.lightning.NodeParams
import fr.acinq.lightning.blockchain.WatchConfirmed
import fr.acinq.lightning.blockchain.WatchSpent
import fr.acinq.lightning.blockchain.fee.FeeratePerKw
import fr.acinq.lightning.blockchain.fee.FeerateTolerance
import fr.acinq.lightning.blockchain.fee.OnChainFeerates
import fr.acinq.lightning.channel.Helpers.Closing.inputsAlreadySpent
import fr.acinq.lightning.channel.states.Channel
import fr.acinq.lightning.crypto.*
import fr.acinq.lightning.logging.LoggingContext
import fr.acinq.lightning.transactions.*
import fr.acinq.lightning.transactions.Transactions.commitTxFee
import fr.acinq.lightning.transactions.Transactions.makeCommitTxOutputs
import fr.acinq.lightning.utils.sat
import fr.acinq.lightning.utils.sum
import fr.acinq.lightning.wire.*

object Helpers {

    /** Called by the non-initiator. */
    fun validateParamsNonInitiator(nodeParams: NodeParams, open: OpenDualFundedChannel): Either<ChannelException, ChannelType> {
        // NB: we only accept channels from peers who support explicit channel type negotiation.
        val channelType = open.channelType ?: return Either.Left(MissingChannelType(open.temporaryChannelId))
        if (channelType is ChannelType.UnsupportedChannelType) {
            return Either.Left(InvalidChannelType(open.temporaryChannelId, ChannelType.SupportedChannelType.SimpleTaprootChannels, channelType))
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
        val remoteCommit = it.nextRemoteCommit ?: it.remoteCommit
        val toRemote = remoteCommit.spec.toRemote.truncateToSatoshi()
        // NB: this is an approximation (we don't take network fees into account)
        toRemote > it.localChannelReserve(commitments.channelParams)
    }

    /** This helper method will publish txs only if they haven't yet reached minDepth. */
    fun LoggingContext.publishIfNeeded(txs: List<ChannelAction.Blockchain.PublishTx>, irrevocablySpent: Map<OutPoint, Transaction>): List<ChannelAction.Blockchain.PublishTx> {
        val (skip, process) = txs.partition { it.tx.inputsAlreadySpent(irrevocablySpent) }
        skip.forEach { tx -> logger.info { "no need to republish txid=${tx.tx.txid}, it has already been confirmed" } }
        process.forEach { tx -> logger.info(mapOf("txType" to tx.txType)) { "publishing txid=${tx.tx.txid}" } }
        return process
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

    /** This helper method will watch the given output only if it hasn't already been irrevocably spent. */
    fun LoggingContext.watchSpentIfNeeded(channelId: ByteVector32, input: Transactions.InputInfo, irrevocablySpent: Map<OutPoint, Transaction>): List<ChannelAction.Blockchain.SendWatch> {
        val watch = WatchSpent(channelId, input.outPoint.txid, input.outPoint.index.toInt(), input.txOut.publicKeyScript, WatchSpent.ClosingOutputSpent(input.txOut.amount))
        return when (val spendingTx = irrevocablySpent[input.outPoint]) {
            null -> listOf(ChannelAction.Blockchain.SendWatch(watch))
            else -> {
                logger.info { "no need to watch output=${input.outPoint.txid}:${input.outPoint.index}, it has already been spent by txid=${spendingTx.txid}" }
                listOf()
            }
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

        data class PairOfCommitTxs(
            val localSpec: CommitmentSpec,
            val localCommitTx: Transactions.CommitTx,
            val localHtlcTxs: List<Transactions.HtlcTx>,
            val remoteSpec: CommitmentSpec,
            val remoteCommitTx: Transactions.CommitTx,
            val remoteHtlcTxs: List<Transactions.HtlcTx>
        )

        /**
         * Creates both sides' first commitment transaction.
         *
         * @return (localSpec, localTx, remoteSpec, remoteTx, fundingTxOutput)
         */
        fun makeCommitTxs(
            channelParams: ChannelParams,
            localCommitParams: CommitParams,
            remoteCommitParams: CommitParams,
            fundingAmount: Satoshi,
            toLocal: MilliSatoshi,
            toRemote: MilliSatoshi,
            localHtlcs: Set<DirectedHtlc>,
            localCommitmentIndex: Long,
            remoteCommitmentIndex: Long,
            commitTxFeerate: FeeratePerKw,
            commitmentFormat: Transactions.CommitmentFormat,
            fundingTxId: TxId,
            fundingTxOutputIndex: Long,
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
                val fees = commitTxFee(remoteCommitParams.dustLimit, remoteSpec, commitmentFormat)
                val missing = fees - remoteSpec.toLocal.truncateToSatoshi()
                if (missing > 0.sat) {
                    return Either.Left(CannotAffordFirstCommitFees(channelParams.channelId, missing = missing, fees = fees))
                }
            }

            val commitmentInput = Transactions.makeFundingInputInfo(fundingTxId, fundingTxOutputIndex, fundingAmount, localFundingKey.publicKey(), remoteFundingPubkey, commitmentFormat)
            val (localCommitTx, localHtlcTxs) = Commitments.makeLocalTxs(
                channelParams = channelParams,
                commitParams = localCommitParams,
                commitKeys = localCommitKeys,
                commitTxNumber = localCommitmentIndex,
                localFundingKey = localFundingKey,
                remoteFundingPubKey = remoteFundingPubkey,
                commitmentInput = commitmentInput,
                commitmentFormat = commitmentFormat,
                spec = localSpec
            )
            val (remoteCommitTx, remoteHtlcTxs) = Commitments.makeRemoteTxs(
                channelParams = channelParams,
                commitParams = remoteCommitParams,
                commitKeys = remoteCommitKeys,
                commitTxNumber = remoteCommitmentIndex,
                localFundingKey = localFundingKey,
                remoteFundingPubKey = remoteFundingPubkey,
                commitmentInput = commitmentInput,
                commitmentFormat = commitmentFormat,
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

        fun createShutdown(channelKeys: ChannelKeys, commitment: FullCommitment, localScriptOverride: ByteVector? = null): Pair<Transactions.LocalNonce?, Shutdown> {
            val localScript = localScriptOverride ?: commitment.channelParams.localParams.defaultFinalScriptPubKey
            return when (commitment.commitmentFormat) {
                Transactions.CommitmentFormat.SimpleTaprootChannels -> {
                    // We create a fresh local closee nonce every time we send shutdown.
                    val localFundingPubKey = channelKeys.fundingKey(commitment.fundingTxIndex).publicKey()
                    val localCloseeNonce = NonceGenerator.signingNonce(localFundingPubKey, commitment.remoteFundingPubkey, commitment.fundingTxId)
                    Pair(localCloseeNonce, Shutdown(commitment.channelId, localScript, localCloseeNonce.publicNonce))
                }
                Transactions.CommitmentFormat.AnchorOutputs -> Pair(null, Shutdown(commitment.channelId, localScript))
            }
        }

        /** We are the closer: we sign closing transactions for which we pay the fees. */
        fun makeClosingTxs(
            channelKeys: ChannelKeys,
            commitment: FullCommitment,
            localScriptPubkey: ByteVector,
            remoteScriptPubkey: ByteVector,
            feerate: FeeratePerKw,
            lockTime: Long,
            remoteNonce: IndividualNonce?
        ): Either<ChannelException, Triple<Transactions.ClosingTxs, ClosingComplete, Transactions.CloserNonces>> {
            val commitInput = commitment.commitInput(channelKeys)
            // We must convert the feerate to a fee: we must build dummy transactions to compute their weight.
            val closingFee = run {
                val dummyClosingTxs = Transactions.makeClosingTxs(commitInput, commitment.localCommit.spec, Transactions.ClosingTxFee.PaidByUs(0.sat), lockTime, localScriptPubkey, remoteScriptPubkey)
                when (val dummyTx = dummyClosingTxs.preferred) {
                    null -> return Either.Left(CannotGenerateClosingTx(commitment.channelId))
                    else -> {
                        // We assume that we're using taproot channels for simplicity.
                        val dummyWitness = Script.witnessKeyPathPay2tr(Transactions.PlaceHolderSig)
                        val weight = dummyTx.tx.updateWitness(0, dummyWitness).weight()
                        Transactions.ClosingTxFee.PaidByUs(Transactions.weight2fee(feerate, weight))
                    }
                }
            }
            // Now that we know the fee we're ready to pay, we can create our closing transactions.
            val closingTxs = Transactions.makeClosingTxs(commitInput, commitment.localCommit.spec, closingFee, lockTime, localScriptPubkey, remoteScriptPubkey)
            if (closingTxs.preferred == null || closingTxs.preferred.fee <= 0.sat) {
                return Either.Left(CannotGenerateClosingTx(commitment.channelId))
            }
            val localFundingKey = channelKeys.fundingKey(commitment.fundingTxIndex)
            val localNonces = Transactions.CloserNonces.generate(localFundingKey.publicKey(), commitment.remoteFundingPubkey, commitment.fundingTxId)
            val tlvs = when (commitment.commitmentFormat) {
                Transactions.CommitmentFormat.AnchorOutputs -> TlvStream(
                    setOfNotNull(
                        closingTxs.localAndRemote?.let { tx -> ClosingCompleteTlv.CloserAndCloseeOutputs(tx.sign(localFundingKey, commitment.remoteFundingPubkey).sig) },
                        closingTxs.localOnly?.let { tx -> ClosingCompleteTlv.CloserOutputOnly(tx.sign(localFundingKey, commitment.remoteFundingPubkey).sig) },
                        closingTxs.remoteOnly?.let { tx -> ClosingCompleteTlv.CloseeOutputOnly(tx.sign(localFundingKey, commitment.remoteFundingPubkey).sig) },
                    )
                )
                Transactions.CommitmentFormat.SimpleTaprootChannels -> when (remoteNonce) {
                    null -> return Either.Left(MissingClosingNonce(commitment.channelId))
                    else -> {
                        // If we cannot create our partial signature for one of our closing txs, we just skip it.
                        // It will only happen if our peer sent an invalid nonce, in which case we cannot do anything anyway
                        // apart from eventually force-closing.
                        fun localSig(tx: Transactions.ClosingTx, localNonce: Transactions.LocalNonce): ChannelSpendSignature.PartialSignatureWithNonce? {
                            return tx.partialSign(localFundingKey, commitment.remoteFundingPubkey, mapOf(), localNonce, listOf(localNonce.publicNonce, remoteNonce)).right
                        }

                        TlvStream(
                            setOfNotNull(
                                closingTxs.localAndRemote?.let { tx -> localSig(tx, localNonces.localAndRemote)?.let { ClosingCompleteTlv.CloserAndCloseeOutputsPartialSignature(it) } },
                                closingTxs.localOnly?.let { tx -> localSig(tx, localNonces.localOnly)?.let { ClosingCompleteTlv.CloserOutputOnlyPartialSignature(it) } },
                                closingTxs.remoteOnly?.let { tx -> localSig(tx, localNonces.remoteOnly)?.let { ClosingCompleteTlv.CloseeOutputOnlyPartialSignature(it) } }
                            )
                        )
                    }
                }
            }
            val closingComplete = ClosingComplete(commitment.channelId, localScriptPubkey, remoteScriptPubkey, closingFee.fee, lockTime, tlvs)
            return Either.Right(Triple(closingTxs, closingComplete, localNonces))
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
            closingComplete: ClosingComplete,
            localNonce: Transactions.LocalNonce?
        ): Either<ChannelException, Triple<Transactions.ClosingTx, ClosingSig, Transactions.LocalNonce?>> {
            val closingFee = Transactions.ClosingTxFee.PaidByThem(closingComplete.fees)
            val closingTxs = Transactions.makeClosingTxs(commitment.commitInput(channelKeys), commitment.localCommit.spec, closingFee, closingComplete.lockTime, localScriptPubkey, remoteScriptPubkey)
            // If our output isn't dust, they must provide a signature for a transaction that includes it.
            // Note that we're the closee, so we look for signatures including the closee output.
            when (commitment.commitmentFormat) {
                Transactions.CommitmentFormat.SimpleTaprootChannels -> {
                    if (localNonce == null) {
                        return Either.Left(MissingClosingNonce(commitment.channelId))
                    }
                    if (closingTxs.localAndRemote != null && closingTxs.localOnly != null && closingComplete.closerAndCloseeOutputsPartialSig == null && closingComplete.closeeOutputOnlyPartialSig == null) {
                        return Either.Left(MissingCloseSignature(commitment.channelId))
                    }
                    if (closingTxs.localAndRemote != null && closingTxs.localOnly == null && closingComplete.closerAndCloseeOutputsPartialSig == null) {
                        return Either.Left(MissingCloseSignature(commitment.channelId))
                    }
                    if (closingTxs.localAndRemote == null && closingTxs.localOnly != null && closingComplete.closeeOutputOnlyPartialSig == null) {
                        return Either.Left(MissingCloseSignature(commitment.channelId))
                    }
                    // We choose the closing signature that matches our preferred closing transaction.
                    val closingTxsWithSigs = listOfNotNull<Triple<Transactions.ClosingTx, ChannelSpendSignature.PartialSignatureWithNonce, (ByteVector32) -> ClosingSigTlv>>(
                        closingComplete.closerAndCloseeOutputsPartialSig?.let { remoteSig -> closingTxs.localAndRemote?.let { tx -> Triple(tx, remoteSig) { localSig -> ClosingSigTlv.CloserAndCloseeOutputsPartialSignature(localSig) } } },
                        closingComplete.closeeOutputOnlyPartialSig?.let { remoteSig -> closingTxs.localOnly?.let { tx -> Triple(tx, remoteSig) { localSig -> ClosingSigTlv.CloseeOutputOnlyPartialSignature(localSig) } } },
                        closingComplete.closerOutputOnlyPartialSig?.let { remoteSig -> closingTxs.remoteOnly?.let { tx -> Triple(tx, remoteSig) { localSig -> ClosingSigTlv.CloserOutputOnlyPartialSignature(localSig) } } },
                    )
                    return when (val preferred = closingTxsWithSigs.firstOrNull()) {
                        null -> Either.Left(MissingCloseSignature(commitment.channelId))
                        else -> {
                            val (closingTx, remoteSig, sigToTlv) = preferred
                            val localFundingKey = channelKeys.fundingKey(commitment.fundingTxIndex)
                            val localSig = closingTx.partialSign(localFundingKey, commitment.remoteFundingPubkey, mapOf(), localNonce, listOf(localNonce.publicNonce, remoteSig.nonce)).right
                            val signedTx = localSig?.let { closingTx.aggregateSigs(localFundingKey.publicKey(), commitment.remoteFundingPubkey, it, remoteSig, mapOf()).right }
                            if (localSig == null || signedTx == null) {
                                return Either.Left(InvalidCloseSignature(commitment.channelId, closingTx.tx.txid))
                            }
                            val signedClosingTx = closingTx.copy(tx = signedTx)
                            if (!signedClosingTx.validate(mapOf())) {
                                return Either.Left(InvalidCloseSignature(commitment.channelId, closingTx.tx.txid))
                            }
                            val nextLocalNonce = NonceGenerator.signingNonce(localFundingKey.publicKey(), commitment.remoteFundingPubkey, commitment.fundingTxId)
                            val tlvs = TlvStream(sigToTlv(localSig.partialSig), ClosingSigTlv.NextCloseeNonce(nextLocalNonce.publicNonce))
                            Either.Right(Triple(signedClosingTx, ClosingSig(commitment.channelId, remoteScriptPubkey, localScriptPubkey, closingComplete.fees, closingComplete.lockTime, tlvs), nextLocalNonce))
                        }
                    }
                }
                Transactions.CommitmentFormat.AnchorOutputs -> {
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
                    val closingTxsWithSigs = listOfNotNull<Triple<Transactions.ClosingTx, ByteVector64, (ByteVector64) -> ClosingSigTlv>>(
                        closingComplete.closerAndCloseeOutputsSig?.let { remoteSig -> closingTxs.localAndRemote?.let { tx -> Triple(tx, remoteSig) { localSig: ByteVector64 -> ClosingSigTlv.CloserAndCloseeOutputs(localSig) } } },
                        closingComplete.closeeOutputOnlySig?.let { remoteSig -> closingTxs.localOnly?.let { tx -> Triple(tx, remoteSig) { localSig -> ClosingSigTlv.CloseeOutputOnly(localSig) } } },
                        closingComplete.closerOutputOnlySig?.let { remoteSig -> closingTxs.remoteOnly?.let { tx -> Triple(tx, remoteSig) { localSig -> ClosingSigTlv.CloserOutputOnly(localSig) } } },
                    )
                    return when (val preferred = closingTxsWithSigs.firstOrNull()) {
                        null -> Either.Left(MissingCloseSignature(commitment.channelId))
                        else -> {
                            val (closingTx, remoteSig, sigToTlv) = preferred
                            val localFundingKey = channelKeys.fundingKey(commitment.fundingTxIndex)
                            val localSig = closingTx.sign(localFundingKey, commitment.remoteFundingPubkey)
                            val signedTx = closingTx.aggregateSigs(localFundingKey.publicKey(), commitment.remoteFundingPubkey, localSig, ChannelSpendSignature.IndividualSignature(remoteSig))
                            val signedClosingTx = closingTx.copy(tx = signedTx)
                            if (!signedClosingTx.validate(extraUtxos = mapOf())) {
                                Either.Left(InvalidCloseSignature(commitment.channelId, signedClosingTx.tx.txid))
                            } else {
                                Either.Right(Triple(signedClosingTx, ClosingSig(commitment.channelId, remoteScriptPubkey, localScriptPubkey, closingComplete.fees, closingComplete.lockTime, TlvStream(sigToTlv(localSig.sig))), null))
                            }
                        }
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
            closingSig: ClosingSig,
            localNonces: Transactions.CloserNonces?,
            remoteNonce: IndividualNonce?
        ): Either<ChannelException, Transactions.ClosingTx> {
            val closingTxsWithSig = listOfNotNull(
                closingSig.closerAndCloseeOutputsSig?.let { sig -> closingTxs.localAndRemote?.let { tx -> Pair(tx, ChannelSpendSignature.IndividualSignature(sig)) } },
                closingSig.closerAndCloseeOutputsPartialSig?.let { sig -> remoteNonce?.let { nonce -> closingTxs.localAndRemote?.let { tx -> Pair(tx, ChannelSpendSignature.PartialSignatureWithNonce(sig, nonce)) } } },
                closingSig.closerOutputOnlySig?.let { sig -> closingTxs.localOnly?.let { tx -> Pair(tx, ChannelSpendSignature.IndividualSignature(sig)) } },
                closingSig.closerOutputOnlyPartialSig?.let { sig -> remoteNonce?.let { nonce -> closingTxs.localOnly?.let { tx -> Pair(tx, ChannelSpendSignature.PartialSignatureWithNonce(sig, nonce)) } } },
                closingSig.closeeOutputOnlySig?.let { sig -> closingTxs.remoteOnly?.let { tx -> Pair(tx, ChannelSpendSignature.IndividualSignature(sig)) } },
                closingSig.closeeOutputOnlyPartialSig?.let { sig -> remoteNonce?.let { nonce -> closingTxs.remoteOnly?.let { tx -> Pair(tx, ChannelSpendSignature.PartialSignatureWithNonce(sig, nonce)) } } },
            )
            return when (val preferred = closingTxsWithSig.firstOrNull()) {
                null -> Either.Left(MissingCloseSignature(commitment.channelId))
                else -> {
                    val (closingTx, remoteSig) = preferred
                    val localFundingKey = channelKeys.fundingKey(commitment.fundingTxIndex)
                    when (remoteSig) {
                        is ChannelSpendSignature.IndividualSignature -> {
                            val localSig = closingTx.sign(localFundingKey, commitment.remoteFundingPubkey)
                            val signedTx = closingTx.aggregateSigs(localFundingKey.publicKey(), commitment.remoteFundingPubkey, localSig, remoteSig)
                            val signedClosingTx = closingTx.copy(tx = signedTx)
                            if (!signedClosingTx.validate(extraUtxos = mapOf())) {
                                Either.Left(InvalidCloseSignature(commitment.channelId, signedClosingTx.tx.txid))
                            } else {
                                Either.Right(signedClosingTx)
                            }
                        }
                        is ChannelSpendSignature.PartialSignatureWithNonce -> {
                            val localNonce = when {
                                localNonces == null -> return Either.Left(InvalidCloseSignature(commitment.channelId, closingTx.tx.txid))
                                closingTx.tx.txOut.size == 2 -> localNonces.localAndRemote
                                closingTx.toLocalOutput != null -> localNonces.localOnly
                                else -> localNonces.remoteOnly
                            }
                            val signedClosingTx = closingTx.partialSign(localFundingKey, commitment.remoteFundingPubkey, mapOf(), localNonce, listOf(localNonce.publicNonce, remoteSig.nonce))
                                .flatMap { closingTx.aggregateSigs(localFundingKey.publicKey(), commitment.remoteFundingPubkey, it, remoteSig, mapOf()) }
                                .map { closingTx.copy(tx = it) }
                                .right ?: return Either.Left(InvalidCloseSignature(commitment.channelId, closingTx.tx.txid))
                            if (!signedClosingTx.validate(mapOf())) {
                                Either.Left(InvalidCloseSignature(commitment.channelId, signedClosingTx.tx.txid))
                            } else {
                                Either.Right(signedClosingTx)
                            }
                        }
                    }
                }
            }
        }

        object LocalClose {

            /** Claim all the outputs that belong to us in our local commitment transaction. */
            fun LoggingContext.claimCommitTxOutputs(channelKeys: ChannelKeys, commitment: FullCommitment, commitTx: Transaction, feerates: OnChainFeerates): Pair<LocalCommitPublished, LocalCommitSecondStageTransactions> {
                require(commitment.localCommit.txId == commitTx.txid) { "txid mismatch, provided tx is not the current local commit tx" }
                val commitKeys = channelKeys.localCommitmentKeys(commitment.channelParams, commitment.localCommit.index)
                val feerateDelayed = feerates.claimMainFeerate
                // first we will claim our main output as soon as the delay is over
                val mainDelayedTx = generateTx("main-delayed-output") {
                    Transactions.ClaimLocalDelayedOutputTx.createUnsignedTx(
                        commitKeys,
                        commitTx,
                        commitment.localCommitParams.dustLimit,
                        commitment.localCommitParams.toSelfDelay,
                        commitment.channelParams.localParams.defaultFinalScriptPubKey,
                        feerateDelayed,
                        commitment.commitmentFormat
                    ).map { it.sign() }
                }
                val unsignedHtlcTxs = commitment.unsignedHtlcTxs(channelKeys)
                val (incomingHtlcs, htlcSuccessTxs) = claimIncomingHtlcOutputs(commitKeys, commitment.changes, unsignedHtlcTxs)
                val (outgoingHtlcs, htlcTimeoutTxs) = claimOutgoingHtlcOutputs(commitKeys, unsignedHtlcTxs)
                val lcp = LocalCommitPublished(
                    commitTx = commitTx,
                    localOutput = mainDelayedTx?.input?.outPoint,
                    anchorOutput = null,
                    incomingHtlcs = incomingHtlcs,
                    outgoingHtlcs = outgoingHtlcs,
                    htlcDelayedOutputs = setOf(), // we will add these once the htlc txs are confirmed
                    irrevocablySpent = mapOf()
                )
                val txs = LocalCommitSecondStageTransactions(mainDelayedTx, htlcSuccessTxs + htlcTimeoutTxs)
                return Pair(lcp, txs)
            }

            /** Create outputs of the local commitment transaction, allowing us for example to identify HTLC outputs. */
            fun makeLocalCommitTxOutputs(channelKeys: ChannelKeys, commitKeys: LocalCommitmentKeys, commitment: FullCommitment): List<CommitmentOutput> {
                val fundingKey = channelKeys.fundingKey(commitment.fundingTxIndex)
                return makeCommitTxOutputs(
                    fundingKey.publicKey(),
                    commitment.remoteFundingPubkey,
                    commitKeys.publicKeys,
                    commitment.localChannelParams.paysCommitTxFees,
                    commitment.localCommitParams.dustLimit,
                    commitment.localCommitParams.toSelfDelay,
                    commitment.commitmentFormat,
                    commitment.localCommit.spec
                )
            }

            /**
             * Claim the outputs of a local commit tx corresponding to incoming HTLCs. If we don't have the preimage for an
             * incoming HTLC, we still include an entry in the map because we may receive that preimage later.
             */
            private fun LoggingContext.claimIncomingHtlcOutputs(
                commitKeys: LocalCommitmentKeys,
                changes: CommitmentChanges,
                unsignedHtlcTxs: List<Pair<Transactions.HtlcTx, ByteVector64>>
            ): Pair<Map<OutPoint, Long>, List<Transactions.HtlcSuccessTx>> {
                // We collect all the preimages available.
                val preimages = (changes.localChanges.all + changes.remoteChanges.all).filterIsInstance<UpdateFulfillHtlc>().map { it.paymentPreimage }.associateBy { r -> r.sha256() }
                // We collect incoming HTLCs that we started failing but didn't cross-sign.
                val failedIncomingHtlcs = changes.localChanges.all.mapNotNull {
                    when (it) {
                        is UpdateFailHtlc -> it.id
                        is UpdateFailMalformedHtlc -> it.id
                        else -> null
                    }
                }.toSet()
                val incomingHtlcs = unsignedHtlcTxs.mapNotNull {
                    when (val htlcTx = it.first) {
                        is Transactions.HtlcSuccessTx -> when {
                            // We immediately spend incoming htlcs for which we have the preimage.
                            preimages.contains(htlcTx.paymentHash) -> {
                                val preimage = preimages.getValue(htlcTx.paymentHash)
                                val signedHtlcTx = generateTx("htlc-success") {
                                    Either.Right(htlcTx.sign(commitKeys, it.second, preimage))
                                }
                                Triple(htlcTx.input.outPoint, htlcTx.htlcId, signedHtlcTx)
                            }
                            // We can ignore incoming htlcs that we started failing: our peer will claim them after the timeout.
                            // We don't track those outputs because we want to move to the CLOSED state even if our peer never claims them.
                            failedIncomingHtlcs.contains(htlcTx.htlcId) -> null
                            // For all other incoming htlcs, we may reveal the preimage later if it matches one of our unpaid invoices.
                            // We thus want to track the corresponding outputs to ensure we don't forget the channel until they've been spent,
                            // either by us if we accept the payment, or by our peer after the timeout.
                            else -> Triple(htlcTx.input.outPoint, htlcTx.htlcId, null)
                        }
                        is Transactions.HtlcTimeoutTx -> null
                    }
                }
                val htlcOutputs = incomingHtlcs.associate { (outpoint, htlcId, _) -> outpoint to htlcId }
                val htlcTxs = incomingHtlcs.mapNotNull { (_, _, signedHtlcTx) -> signedHtlcTx }
                return Pair(htlcOutputs, htlcTxs)
            }

            /**
             * Claim the outputs of a local commit tx corresponding to outgoing HTLCs, after their timeout.
             */
            private fun LoggingContext.claimOutgoingHtlcOutputs(
                commitKeys: LocalCommitmentKeys,
                unsignedHtlcTxs: List<Pair<Transactions.HtlcTx, ByteVector64>>
            ): Pair<Map<OutPoint, Long>, List<Transactions.HtlcTimeoutTx>> {
                val outgoingHtlcs = unsignedHtlcTxs.mapNotNull {
                    when (val htlcTx = it.first) {
                        // We track all outputs that belong to outgoing htlcs. Our peer may or may not have the preimage: if they
                        // claim the output, we will learn the preimage from their transaction, otherwise we will get our funds
                        // back after the timeout.
                        is Transactions.HtlcTimeoutTx -> {
                            val signedHtlcTx = generateTx("htlc-timeout") {
                                Either.Right(htlcTx.sign(commitKeys, it.second))
                            }
                            Triple(htlcTx.input.outPoint, htlcTx.htlcId, signedHtlcTx)
                        }
                        is Transactions.HtlcSuccessTx -> null
                    }
                }
                val htlcOutputs = outgoingHtlcs.associate { (outpoint, htlcId, _) -> outpoint to htlcId }
                val htlcTxs = outgoingHtlcs.mapNotNull { (_, _, signedHtlcTx) -> signedHtlcTx }
                return Pair(htlcOutputs, htlcTxs)
            }

            /** Claim the outputs of incoming HTLCs for the payment_hash matching the preimage provided. */
            fun LoggingContext.claimHtlcsWithPreimage(channelKeys: ChannelKeys, commitKeys: LocalCommitmentKeys, commitment: FullCommitment, preimage: ByteVector32): List<Transactions.HtlcSuccessTx> {
                return commitment.unsignedHtlcTxs(channelKeys).mapNotNull { (htlcTx, remoteSig) ->
                    when {
                        htlcTx is Transactions.HtlcSuccessTx && htlcTx.paymentHash == preimage.sha256() -> generateTx("htlc-success") { Either.Right(htlcTx.sign(commitKeys, remoteSig, preimage)) }
                        else -> null
                    }
                }
            }

            /**
             * An incoming HTLC that we've received has been failed by us: if the channel wasn't closing we would relay
             * that failure. Since the channel is closing, our peer should claim the HTLC on-chain after the timeout.
             * We stop tracking the corresponding output because we want to move to the CLOSED state even if our peer never
             * claims it (which may happen if the HTLC amount is low and on-chain fees are high).
             */
            fun ignoreFailedIncomingHtlc(htlcId: Long, localCommitPublished: LocalCommitPublished, commitment: FullCommitment): LocalCommitPublished {
                // If we have the preimage (e.g. for partially fulfilled multi-part payments), we keep the HTLC-success tx.
                val preimages = (commitment.changes.localChanges.all + commitment.changes.remoteChanges.all).filterIsInstance<UpdateFulfillHtlc>().map { it.paymentPreimage }.associateBy { r -> r.sha256() }
                val htlcsWithPreimage = commitment.localCommit.spec.htlcs.mapNotNull { htlc ->
                    when {
                        htlc is IncomingHtlc && preimages.contains(htlc.add.paymentHash) -> htlc.add.id
                        else -> null
                    }
                }
                val outpoints = localCommitPublished.incomingHtlcs.mapNotNull { (outpoint, id) ->
                    when {
                        id == htlcId && !htlcsWithPreimage.contains(id) -> outpoint
                        else -> null
                    }
                }.toSet()
                return localCommitPublished.copy(incomingHtlcs = localCommitPublished.incomingHtlcs - outpoints)
            }

            /**
             * Claim the output of a 2nd-stage HTLC transaction. If the provided transaction isn't an htlc, this will be a no-op.
             *
             * NB: with anchor outputs, it's possible to have transactions that spend *many* HTLC outputs at once, but we're not
             * doing that because it introduces a lot of subtle edge cases.
             */
            fun LoggingContext.claimHtlcDelayedOutput(
                localCommitPublished: LocalCommitPublished,
                channelKeys: ChannelKeys,
                commitment: FullCommitment,
                tx: Transaction,
                feerates: OnChainFeerates
            ): Pair<LocalCommitPublished, LocalCommitThirdStageTransactions> {
                return if (tx.txIn.any { txIn -> localCommitPublished.htlcOutputs.contains(txIn.outPoint) }) {
                    val feerateDelayed = feerates.claimMainFeerate
                    val commitKeys = commitment.localKeys(channelKeys)
                    // Note that this will return null if the transaction wasn't one of our HTLC transactions, which may happen
                    // if our peer was able to claim the HTLC output before us (race condition between success and timeout).
                    val htlcDelayedTx = generateTx("htlc-delayed") {
                        Transactions.HtlcDelayedTx.createUnsignedTx(
                            commitKeys,
                            tx,
                            commitment.localCommitParams.dustLimit,
                            commitment.localCommitParams.toSelfDelay,
                            commitment.channelParams.localParams.defaultFinalScriptPubKey,
                            feerateDelayed,
                            commitment.commitmentFormat
                        ).map { it.sign() }
                    }
                    val localCommitPublished1 = localCommitPublished.copy(htlcDelayedOutputs = localCommitPublished.htlcDelayedOutputs + setOfNotNull(htlcDelayedTx?.input?.outPoint))
                    Pair(localCommitPublished1, LocalCommitThirdStageTransactions(listOfNotNull(htlcDelayedTx)))
                } else {
                    Pair(localCommitPublished, LocalCommitThirdStageTransactions(listOf()))
                }
            }

            /**
             * Claim the outputs of all 2nd-stage HTLC transactions that have been confirmed.
             */
            fun LoggingContext.claimHtlcDelayedOutputs(localCommitPublished: LocalCommitPublished, channelKeys: ChannelKeys, commitment: FullCommitment, feerates: OnChainFeerates): LocalCommitThirdStageTransactions {
                val confirmedHtlcTxs = localCommitPublished.htlcOutputs.mapNotNull { htlcOutput -> localCommitPublished.irrevocablySpent[htlcOutput] }
                val htlcDelayedTxs = confirmedHtlcTxs.flatMap { tx -> claimHtlcDelayedOutput(localCommitPublished, channelKeys, commitment, tx, feerates).second.htlcDelayedTxs }
                return LocalCommitThirdStageTransactions(htlcDelayedTxs)
            }

        }

        object RemoteClose {

            /** Claim all the outputs that belong to us in the remote commitment transaction (which can be either their current or next commitment). */
            fun LoggingContext.claimCommitTxOutputs(
                channelKeys: ChannelKeys,
                commitment: FullCommitment,
                remoteCommit: RemoteCommit,
                commitTx: Transaction,
                feerates: OnChainFeerates
            ): Pair<RemoteCommitPublished, RemoteCommitSecondStageTransactions> {
                require(remoteCommit.txid == commitTx.txid) { "txid mismatch, provided tx is not the current remote commit tx" }
                val commitKeys = channelKeys.remoteCommitmentKeys(commitment.channelParams, remoteCommit.remotePerCommitmentPoint)
                val outputs = makeRemoteCommitTxOutputs(channelKeys, commitKeys, commitment, remoteCommit)
                val mainTx = claimMainOutput(commitKeys, commitTx, commitment.localCommitParams.dustLimit, commitment.commitmentFormat, commitment.localChannelParams.defaultFinalScriptPubKey, feerates.claimMainFeerate)
                // We need to use a rather high fee for htlc-claim because we compete with the counterparty.
                val feerateClaimHtlc = feerates.fastFeerate
                val (incomingHtlcs, htlcSuccessTxs) = claimIncomingHtlcOutputs(commitKeys, commitTx, outputs, commitment, remoteCommit, feerateClaimHtlc)
                val (outgoingHtlcs, htlcTimeoutTxs) = claimOutgoingHtlcOutputs(commitKeys, commitTx, outputs, commitment, remoteCommit, feerateClaimHtlc)
                val rcp = RemoteCommitPublished(
                    commitTx = commitTx,
                    localOutput = mainTx?.input?.outPoint,
                    anchorOutput = null,
                    incomingHtlcs = incomingHtlcs,
                    outgoingHtlcs = outgoingHtlcs,
                    irrevocablySpent = mapOf()
                )
                val txs = RemoteCommitSecondStageTransactions(mainTx, htlcSuccessTxs + htlcTimeoutTxs)
                return Pair(rcp, txs)
            }

            /** Create outputs of the remote commitment transaction, allowing us for example to identify HTLC outputs. */
            fun makeRemoteCommitTxOutputs(channelKeys: ChannelKeys, commitKeys: RemoteCommitmentKeys, commitment: FullCommitment, remoteCommit: RemoteCommit): List<CommitmentOutput> {
                val fundingKey = channelKeys.fundingKey(commitment.fundingTxIndex)
                return makeCommitTxOutputs(
                    commitment.remoteFundingPubkey,
                    fundingKey.publicKey(),
                    commitKeys.publicKeys,
                    !commitment.localChannelParams.paysCommitTxFees,
                    commitment.remoteCommitParams.dustLimit,
                    commitment.remoteCommitParams.toSelfDelay,
                    commitment.commitmentFormat,
                    remoteCommit.spec
                )
            }

            /** Claim our main output from the remote commitment transaction, if available. */
            internal fun LoggingContext.claimMainOutput(
                commitKeys: RemoteCommitmentKeys,
                commitTx: Transaction,
                dustLimit: Satoshi,
                commitmentFormat: Transactions.CommitmentFormat,
                finalScriptPubKey: ByteVector,
                claimMainFeerate: FeeratePerKw
            ): Transactions.ClaimRemoteDelayedOutputTx? {
                return generateTx("claim-remote-delayed-output") {
                    Transactions.ClaimRemoteDelayedOutputTx.createUnsignedTx(
                        commitKeys,
                        commitTx,
                        dustLimit,
                        finalScriptPubKey,
                        claimMainFeerate,
                        commitmentFormat,
                    ).map { it.sign() }
                }
            }

            /**
             * Claim the outputs of a remote commit tx corresponding to incoming HTLCs. If we don't have the preimage for an
             * incoming HTLC, we still include an entry in the map because we may receive that preimage later.
             */
            private fun LoggingContext.claimIncomingHtlcOutputs(
                commitKeys: RemoteCommitmentKeys,
                commitTx: Transaction,
                outputs: List<CommitmentOutput>,
                commitment: FullCommitment,
                remoteCommit: RemoteCommit,
                feerate: FeeratePerKw
            ): Pair<Map<OutPoint, Long>, List<Transactions.ClaimHtlcSuccessTx>> {
                // We collect all the preimages available.
                val preimages = (commitment.changes.localChanges.all + commitment.changes.remoteChanges.all).filterIsInstance<UpdateFulfillHtlc>().map { it.paymentPreimage }.associateBy { r -> r.sha256() }
                // We collect incoming HTLCs that we started failing but didn't cross-sign.
                val failedIncomingHtlcs = commitment.changes.localChanges.all.mapNotNull {
                    when (it) {
                        is UpdateFailHtlc -> it.id
                        is UpdateFailMalformedHtlc -> it.id
                        else -> null
                    }
                }.toSet()
                // Remember we are looking at the remote commitment so IN for them is really OUT for us and vice versa.
                val incomingHtlcs = remoteCommit.spec.htlcs.mapNotNull { htlc ->
                    when (htlc) {
                        is OutgoingHtlc -> when {
                            // We immediately spend incoming htlcs for which we have the preimage.
                            preimages.contains(htlc.add.paymentHash) -> {
                                val preimage = preimages.getValue(htlc.add.paymentHash)
                                val signedHtlcTx = generateTx("claim-htlc-success") {
                                    Transactions.ClaimHtlcSuccessTx.createUnsignedTx(
                                        commitKeys,
                                        commitTx,
                                        commitment.localCommitParams.dustLimit,
                                        outputs,
                                        commitment.localChannelParams.defaultFinalScriptPubKey,
                                        htlc.add,
                                        preimage,
                                        feerate,
                                        commitment.commitmentFormat
                                    ).map { it.sign() }
                                }
                                signedHtlcTx?.let { Triple(it.input.outPoint, htlc.add.id, it) }
                            }
                            // We can ignore incoming htlcs that we started failing: our peer will claim them after the timeout.
                            // We don't track those outputs because we want to move to the CLOSED state even if our peer never claims them.
                            failedIncomingHtlcs.contains(htlc.add.id) -> null
                            // For all other incoming htlcs, we may reveal the preimage later if it matches one of our unpaid invoices.
                            // We thus want to track the corresponding outputs to ensure we don't forget the channel until they've been spent,
                            // either by us if we accept the payment, or by our peer after the timeout.
                            else -> {
                                Transactions.ClaimHtlcSuccessTx.findInput(commitTx, outputs, htlc.add)?.let { Triple(it.outPoint, htlc.add.id, null) }
                            }
                        }
                        is IncomingHtlc -> null
                    }
                }
                val htlcOutputs = incomingHtlcs.associate { (outpoint, htlcId, _) -> outpoint to htlcId }
                val htlcTxs = incomingHtlcs.mapNotNull { (_, _, signedHtlcTx) -> signedHtlcTx }
                return Pair(htlcOutputs, htlcTxs)
            }

            /**
             * Claim the outputs of a remote commit tx corresponding to outgoing HTLCs, after their timeout.
             */
            private fun LoggingContext.claimOutgoingHtlcOutputs(
                commitKeys: RemoteCommitmentKeys,
                commitTx: Transaction,
                outputs: List<CommitmentOutput>,
                commitment: FullCommitment,
                remoteCommit: RemoteCommit,
                feerate: FeeratePerKw
            ): Pair<Map<OutPoint, Long>, List<Transactions.ClaimHtlcTimeoutTx>> {
                // Remember we are looking at the remote commitment so IN for them is really OUT for us and vice versa.
                val outgoingHtlcs = remoteCommit.spec.htlcs.mapNotNull { htlc ->
                    when (htlc) {
                        is IncomingHtlc -> {
                            // We track all outputs that belong to outgoing htlcs. Our peer may or may not have the preimage: if they
                            // claim the output, we will learn the preimage from their transaction, otherwise we will get our funds
                            // back after the timeout.
                            generateTx("claim-htlc-timeout") {
                                Transactions.ClaimHtlcTimeoutTx.createUnsignedTx(
                                    commitKeys,
                                    commitTx,
                                    commitment.localCommitParams.dustLimit,
                                    outputs,
                                    commitment.localChannelParams.defaultFinalScriptPubKey,
                                    htlc.add,
                                    feerate,
                                    commitment.commitmentFormat
                                ).map { it.sign() }
                            }?.let { Triple(it.input.outPoint, htlc.add.id, it) }
                        }
                        is OutgoingHtlc -> null
                    }
                }
                val htlcOutputs = outgoingHtlcs.associate { (outpoint, htlcId, _) -> outpoint to htlcId }
                val htlcTxs = outgoingHtlcs.map { (_, _, signedHtlcTx) -> signedHtlcTx }
                return Pair(htlcOutputs, htlcTxs)
            }

            /** Claim the outputs of incoming HTLCs for the payment_hash matching the preimage provided. */
            fun LoggingContext.claimHtlcsWithPreimage(
                channelKeys: ChannelKeys,
                remoteCommitPublished: RemoteCommitPublished,
                commitment: FullCommitment,
                remoteCommit: RemoteCommit,
                preimage: ByteVector32,
                feerates: OnChainFeerates
            ): List<Transactions.ClaimHtlcSuccessTx> {
                val commitKeys = commitment.remoteKeys(channelKeys, remoteCommit.remotePerCommitmentPoint)
                val outputs = makeRemoteCommitTxOutputs(channelKeys, commitKeys, commitment, remoteCommit)
                val feerate = feerates.fastFeerate
                return remoteCommit.spec.htlcs.mapNotNull { htlc ->
                    // Remember we are looking at the remote commitment so IN for them is really OUT for us and vice versa.
                    when {
                        htlc is OutgoingHtlc && htlc.add.paymentHash == preimage.sha256() -> generateTx("claim-htlc-success") {
                            Transactions.ClaimHtlcSuccessTx.createUnsignedTx(
                                commitKeys,
                                remoteCommitPublished.commitTx,
                                commitment.localCommitParams.dustLimit,
                                outputs,
                                commitment.localChannelParams.defaultFinalScriptPubKey,
                                htlc.add,
                                preimage,
                                feerate,
                                commitment.commitmentFormat
                            ).map { it.sign() }
                        }
                        else -> null
                    }
                }
            }

            /**
             * An incoming HTLC that we've received has been failed by us: if the channel wasn't closing we would relay
             * that failure. Since the channel is closing, our peer should claim the HTLC on-chain after the timeout.
             * We stop tracking the corresponding output because we want to move to the CLOSED state even if our peer never
             * claims it (which may happen if the HTLC amount is low and on-chain fees are high).
             */
            fun ignoreFailedIncomingHtlc(htlcId: Long, remoteCommitPublished: RemoteCommitPublished, commitment: FullCommitment, remoteCommit: RemoteCommit): RemoteCommitPublished {
                val preimages = (commitment.changes.localChanges.all + commitment.changes.remoteChanges.all).filterIsInstance<UpdateFulfillHtlc>().map { it.paymentPreimage }.associateBy { r -> r.sha256() }
                // Remember we are looking at the remote commitment so IN for them is really OUT for us and vice versa.
                val htlcsWithPreimage = remoteCommit.spec.htlcs.mapNotNull { htlc ->
                    when {
                        htlc is OutgoingHtlc && preimages.contains(htlc.add.paymentHash) -> htlc.add.id
                        else -> null
                    }
                }
                val outpoints = remoteCommitPublished.incomingHtlcs.mapNotNull { (outpoint, id) ->
                    when {
                        id == htlcId && !htlcsWithPreimage.contains(id) -> outpoint
                        else -> null
                    }
                }.toSet()
                return remoteCommitPublished.copy(incomingHtlcs = remoteCommitPublished.incomingHtlcs - outpoints)
            }

        }

        object RevokedClose {

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
                // This transaction has been published by the remote, so we need to invert local/remote params.
                val commitmentNumber = Transactions.getCommitTxNumber(
                    commitTx = commitTx,
                    isInitiator = !params.localParams.isChannelOpener,
                    localPaymentBasePoint = params.remoteParams.paymentBasepoint,
                    remotePaymentBasePoint = channelKeys.paymentBasePoint
                )
                if (commitmentNumber > 0xffffffffffffL) {
                    // The commitment number must be lesser than 48 bits long, otherwise this isn't a commitment transaction.
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
            fun LoggingContext.claimCommitTxOutputs(
                params: ChannelParams,
                channelKeys: ChannelKeys,
                commitTx: Transaction,
                remotePerCommitmentSecret: PrivateKey,
                dustLimit: Satoshi,
                toSelfDelay: CltvExpiryDelta,
                commitmentFormat: Transactions.CommitmentFormat,
                feerates: OnChainFeerates
            ): Pair<RevokedCommitPublished, RevokedCommitSecondStageTransactions> {
                val remotePerCommitmentPoint = remotePerCommitmentSecret.publicKey()
                val commitKeys = channelKeys.remoteCommitmentKeys(params, remotePerCommitmentPoint)
                val revocationKey = channelKeys.revocationKey(remotePerCommitmentSecret)
                val feerateMain = feerates.claimMainFeerate
                // We need to use a high fee here for punishment txs because after a delay they can be spent by the counterparty.
                val feeratePenalty = feerates.fastFeerate
                // First we will claim our main output right away.
                val mainTx = generateTx("claim-remote-delayed-output") {
                    Transactions.ClaimRemoteDelayedOutputTx.createUnsignedTx(
                        commitKeys,
                        commitTx,
                        dustLimit,
                        params.localParams.defaultFinalScriptPubKey,
                        feerateMain,
                        commitmentFormat
                    ).map { it.sign() }
                }
                // Then we punish them by stealing their main output.
                val mainPenaltyTx = generateTx("main-penalty") {
                    Transactions.MainPenaltyTx.createUnsignedTx(
                        commitKeys,
                        revocationKey,
                        commitTx,
                        dustLimit,
                        params.localParams.defaultFinalScriptPubKey,
                        toSelfDelay,
                        feeratePenalty,
                        commitmentFormat
                    ).map { it.sign() }
                }
                val rvk = RevokedCommitPublished(
                    commitTx = commitTx,
                    remotePerCommitmentSecret = remotePerCommitmentSecret,
                    localOutput = mainTx?.input?.outPoint,
                    remoteOutput = mainPenaltyTx?.input?.outPoint,
                    htlcOutputs = setOf(), // we will get HTLC data from our DB and fill this in [claimRevokedRemoteCommitTxHtlcOutputs]
                    htlcDelayedOutputs = setOf(),
                    irrevocablySpent = mapOf()
                )
                val txs = RevokedCommitSecondStageTransactions(mainTx, mainPenaltyTx, htlcPenaltyTxs = listOf())
                return Pair(rvk, txs)
            }

            /**
             * Once we've fetched htlc information for a revoked commitment from the DB, we create penalty transactions to claim all htlc outputs.
             */
            fun LoggingContext.claimHtlcOutputs(
                params: ChannelParams,
                channelKeys: ChannelKeys,
                revokedCommitPublished: RevokedCommitPublished,
                dustLimit: Satoshi,
                commitmentFormat: Transactions.CommitmentFormat,
                feerates: OnChainFeerates,
                htlcInfos: List<ChannelAction.Storage.HtlcInfo>
            ): List<Transactions.HtlcPenaltyTx> {
                // we need to use a high fee here for punishment txs because after a delay they can be spent by the counterparty
                val feeratePenalty = feerates.fastFeerate
                val remotePerCommitmentPoint = revokedCommitPublished.remotePerCommitmentSecret.publicKey()
                val commitKeys = channelKeys.remoteCommitmentKeys(params, remotePerCommitmentPoint)
                val revocationKey = channelKeys.revocationKey(revokedCommitPublished.remotePerCommitmentSecret)
                return Transactions.HtlcPenaltyTx.createUnsignedTxs(
                    commitKeys,
                    revocationKey,
                    revokedCommitPublished.commitTx,
                    htlcInfos.map { Pair(it.paymentHash, it.cltvExpiry) },
                    dustLimit,
                    params.localParams.defaultFinalScriptPubKey,
                    feeratePenalty,
                    commitmentFormat
                ).mapNotNull { tx -> generateTx("htlc-penalty") { tx.map { it.sign() } } }
            }

            /**
             * Claims the output of an [Transactions.HtlcSuccessTx] or [Transactions.HtlcTimeoutTx] transaction using a revocation key.
             *
             * In case a revoked commitment with pending HTLCs is published, there are two ways the HTLC outputs can be taken as punishment:
             * - by spending the corresponding output of the commitment tx, using [Transactions.ClaimHtlcDelayedOutputPenaltyTx] that we generate as soon as we detect that a revoked commit
             * has been spent; note that those transactions will compete with [Transactions.HtlcSuccessTx] and [Transactions.HtlcTimeoutTx] published by the counterparty.
             * - by spending the delayed output of [Transactions.HtlcSuccessTx] and [Transactions.HtlcTimeoutTx] if those get confirmed; because the output of these txs is protected by
             * an OP_CSV delay, we will have time to spend them with a revocation key. In that case, we generate the spending transactions "on demand",
             * this is the purpose of this method.
             *
             * NB: when anchor outputs is used, htlc transactions can be aggregated in a single transaction if they share the same
             * lockTime (thanks to the use of sighash_single | sighash_anyonecanpay), so we may need to claim multiple outputs.
             */
            fun LoggingContext.claimHtlcTxOutputs(
                params: ChannelParams,
                channelKeys: ChannelKeys,
                revokedCommitPublished: RevokedCommitPublished,
                htlcTx: Transaction,
                dustLimit: Satoshi,
                toSelfDelay: CltvExpiryDelta,
                commitmentFormat: Transactions.CommitmentFormat,
                feerates: OnChainFeerates
            ): Pair<RevokedCommitPublished, RevokedCommitThirdStageTransactions> {
                // We published HTLC-penalty transactions for every HTLC output: this transaction may be ours, or it may be one
                // of their HTLC transactions that confirmed before our HTLC-penalty transaction. If it is spending an HTLC
                // output, we assume that it's an HTLC transaction published by our peer and try to create penalty transactions
                // that spend it, which will automatically be skipped if this was instead one of our HTLC-penalty transactions.
                val spendsHtlcOutput = htlcTx.txIn.any { revokedCommitPublished.htlcOutputs.contains(it.outPoint) }
                return if (spendsHtlcOutput) {
                    val remotePerCommitmentPoint = revokedCommitPublished.remotePerCommitmentSecret.publicKey()
                    val commitKeys = channelKeys.remoteCommitmentKeys(params, remotePerCommitmentPoint)
                    val revocationKey = channelKeys.revocationKey(revokedCommitPublished.remotePerCommitmentSecret)
                    // we need to use a high fee here for punishment txs because after a delay they can be spent by the counterparty
                    val feeratePenalty = feerates.fastFeerate
                    val penaltyTxs = Transactions.ClaimHtlcDelayedOutputPenaltyTx.createUnsignedTxs(
                        commitKeys,
                        revocationKey,
                        htlcTx,
                        dustLimit,
                        toSelfDelay,
                        params.localParams.defaultFinalScriptPubKey,
                        feeratePenalty,
                        commitmentFormat
                    ).mapNotNull { tx -> generateTx("htlc-delayed-penalty") { tx.map { it.sign() } } }
                    val revokedCommitPublished1 = revokedCommitPublished.copy(htlcDelayedOutputs = revokedCommitPublished.htlcDelayedOutputs + penaltyTxs.map { it.input.outPoint })
                    val txs = RevokedCommitThirdStageTransactions(penaltyTxs)
                    Pair(revokedCommitPublished1, txs)
                } else {
                    Pair(revokedCommitPublished, RevokedCommitThirdStageTransactions(listOf()))
                }
            }

            /**
             * Claim the outputs of all 2nd-stage HTLC transactions that have been confirmed.
             */
            fun LoggingContext.claimHtlcTxsOutputs(
                params: ChannelParams,
                channelKeys: ChannelKeys,
                revokedCommitPublished: RevokedCommitPublished,
                dustLimit: Satoshi,
                toSelfDelay: CltvExpiryDelta,
                commitmentFormat: Transactions.CommitmentFormat,
                feerates: OnChainFeerates
            ): RevokedCommitThirdStageTransactions {
                val confirmedHtlcTxs = revokedCommitPublished.htlcOutputs.mapNotNull { htlcOutput -> revokedCommitPublished.irrevocablySpent[htlcOutput] }
                val penaltyTxs = confirmedHtlcTxs.flatMap { htlcTx ->
                    claimHtlcTxOutputs(params, channelKeys, revokedCommitPublished, htlcTx, dustLimit, toSelfDelay, commitmentFormat, feerates).second.htlcDelayedPenaltyTxs
                }
                return RevokedCommitThirdStageTransactions(penaltyTxs)
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
                val fromNextRemote = commitment.nextRemoteCommit?.spec?.htlcs.orEmpty()
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
         * @return a set of outgoing htlcs that can be considered failed
         */
        fun LoggingContext.trimmedOrTimedOutHtlcs(channelKeys: ChannelKeys, commitment: FullCommitment, localCommit: LocalCommit, confirmedTx: Transaction): Set<UpdateAddHtlc> {
            return if (confirmedTx.txid == localCommit.txId) {
                // The commitment tx is confirmed: we can immediately fail all dust htlcs (they don't have an output in the tx).
                val untrimmedHtlcs = Transactions.trimOfferedHtlcs(commitment.localCommitParams.dustLimit, localCommit.spec, commitment.commitmentFormat).map { it.add }
                (localCommit.spec.htlcs.outgoings() - untrimmedHtlcs.toSet()).toSet()
            } else if (confirmedTx.txIn.any { it.outPoint.txid == localCommit.txId }) {
                // The transaction spends the commitment tx: maybe it is a timeout tx, in which case we can resolve and fail the
                // corresponding htlc.
                val commitKeys = commitment.localKeys(channelKeys)
                val outputs = LocalClose.makeLocalCommitTxOutputs(channelKeys, commitKeys, commitment)
                confirmedTx.txIn.filter { it.outPoint.txid == localCommit.txId }.mapNotNull { txIn ->
                    when (val output = outputs[txIn.outPoint.index.toInt()]) {
                        // This may also be our peer claiming the HTLC by revealing the preimage: in that case we have already
                        // extracted the preimage with [extractPreimages] and relayed it to the payment handler.
                        is CommitmentOutput.OutHtlc -> {
                            if (Scripts.extractPreimagesFromClaimHtlcSuccess(confirmedTx).isEmpty()) {
                                logger.info { "htlc-timeout tx for htlc #${output.htlc.add.id} paymentHash=${output.htlc.add.paymentHash} expiry=${output.htlc.add.cltvExpiry} has been confirmed (txId=${confirmedTx.txid})" }
                                output.htlc.add
                            } else {
                                logger.info { "htlc-timeout tx for htlc #${output.htlc.add.id} paymentHash=${output.htlc.add.paymentHash} expiry=${output.htlc.add.cltvExpiry} double-spent by claim-htlc-success txId=${confirmedTx.txid}" }
                                null
                            }
                        }
                        else -> null
                    }
                }.toSet()
            } else {
                setOf()
            }
        }

        /**
         * In CLOSING state, when we are notified that a transaction has been confirmed, we analyze it to find out if one or
         * more htlcs have timed out and need to be considered failed. Trimmed htlcs can be failed as soon as the commitment
         * tx has been confirmed.
         *
         * @return a set of htlcs that need to be failed upstream
         */
        fun LoggingContext.trimmedOrTimedOutHtlcs(channelKeys: ChannelKeys, commitment: FullCommitment, remoteCommit: RemoteCommit, confirmedTx: Transaction): Set<UpdateAddHtlc> {
            return if (confirmedTx.txid == remoteCommit.txid) {
                // The commitment tx is confirmed: we can immediately fail all dust htlcs (they don't have an output in the tx).
                val untrimmedHtlcs = Transactions.trimReceivedHtlcs(commitment.remoteCommitParams.dustLimit, remoteCommit.spec, commitment.commitmentFormat).map { it.add }
                (remoteCommit.spec.htlcs.incomings() - untrimmedHtlcs.toSet()).toSet()
            } else if (confirmedTx.txIn.any { it.outPoint.txid == remoteCommit.txid }) {
                // The transaction spends the commitment tx: maybe it is a timeout tx, in which case we can resolve and fail the
                // corresponding htlc.
                val commitKeys = commitment.remoteKeys(channelKeys, remoteCommit.remotePerCommitmentPoint)
                val outputs = RemoteClose.makeRemoteCommitTxOutputs(channelKeys, commitKeys, commitment, remoteCommit)
                confirmedTx.txIn.filter { it.outPoint.txid == remoteCommit.txid }.mapNotNull { txIn ->
                    when (val output = outputs[txIn.outPoint.index.toInt()]) {
                        // This may also be our peer claiming the HTLC by revealing the preimage: in that case we have already
                        // extracted the preimage with [extractPreimages] and relayed it to the payment handler.
                        is CommitmentOutput.InHtlc -> {
                            if (Scripts.extractPreimagesFromHtlcSuccess(confirmedTx).isEmpty()) {
                                logger.info { "claim-htlc-timeout tx for htlc #${output.htlc.add.id} paymentHash=${output.htlc.add.paymentHash} expiry=${output.htlc.add.cltvExpiry} has been confirmed (txId=${confirmedTx.txid})" }
                                output.htlc.add
                            } else {
                                logger.info { "claim-htlc-timeout tx for htlc #${output.htlc.add.id} paymentHash=${output.htlc.add.paymentHash} expiry=${output.htlc.add.cltvExpiry} double-spent by htlc-success txId=${confirmedTx.txid}" }
                                null
                            }
                        }
                        else -> null
                    }
                }.toSet()
            } else {
                setOf()
            }
        }

        /**
         * As soon as a local or remote commitment reaches min_depth, we know which htlcs will be settled on-chain (whether
         * or not they actually have an output in the commitment tx).
         *
         * @param tx a transaction that is sufficiently buried in the blockchain
         */
        fun onChainOutgoingHtlcs(localCommit: LocalCommit, remoteCommit: RemoteCommit, nextRemoteCommit: RemoteCommit?, tx: Transaction): Set<UpdateAddHtlc> = when {
            localCommit.txId == tx.txid -> localCommit.spec.htlcs.outgoings().toSet()
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
                localCommit.txId == tx.txid -> outgoingHtlcs - localCommit.spec.htlcs.outgoings().toSet()
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
        private fun <T : Transactions.TransactionWithInputInfo> LoggingContext.generateTx(desc: String, attempt: () -> Either<Transactions.TxGenerationSkipped, T>): T? =
            when (val result = runTrying { attempt() }) {
                is Try.Success -> when (val txResult = result.get()) {
                    is Either.Right -> {
                        logger.info { "tx generation success: desc=$desc txid=${txResult.value.tx.txid} amount=${txResult.value.tx.txOut.map { it.amount }.sum()} tx=${txResult.value.tx}" }
                        txResult.value
                    }
                    is Either.Left -> {
                        logger.info { "tx generation skipped: desc=$desc reason: ${txResult.value}" }
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
