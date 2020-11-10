package fr.acinq.eclair.channel

import fr.acinq.bitcoin.*
import fr.acinq.bitcoin.Crypto.ripemd160
import fr.acinq.bitcoin.Crypto.sha256
import fr.acinq.bitcoin.Script.pay2wsh
import fr.acinq.bitcoin.Script.write
import fr.acinq.eclair.Eclair.MinimumFeeratePerKw
import fr.acinq.eclair.Feature
import fr.acinq.eclair.MilliSatoshi
import fr.acinq.eclair.NodeParams
import fr.acinq.eclair.blockchain.BITCOIN_OUTPUT_SPENT
import fr.acinq.eclair.blockchain.BITCOIN_TX_CONFIRMED
import fr.acinq.eclair.blockchain.WatchConfirmed
import fr.acinq.eclair.blockchain.WatchSpent
import fr.acinq.eclair.blockchain.fee.OnchainFeerates
import fr.acinq.eclair.channel.Helpers.Closing.inputsAlreadySpent
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
import fr.acinq.eclair.wire.*
import kotlinx.serialization.ExperimentalSerializationApi
import org.kodein.log.Logger
import org.kodein.log.LoggerFactory
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

object Helpers {

    val logger = LoggerFactory.default.newLogger(Logger.Tag(Helpers::class))

    /**
     * Returns the number of confirmations needed to safely handle the funding transaction,
     * we make sure the cumulative block reward largely exceeds the channel size.
     *
     * @param fundingSatoshis funding amount of the channel
     * @return number of confirmations needed
     */
    fun minDepthForFunding(nodeParams: NodeParams, fundingSatoshis: Satoshi): Int =
        if (fundingSatoshis <= Channel.MAX_FUNDING) {
            nodeParams.minDepthBlocks
        } else {
            val blockReward = 6.25f // this is true as of ~May 2020, but will be too large after 2024
            val scalingFactor = 15
            val btc = fundingSatoshis.toLong().toDouble() / 100_000_000L
            val blocksToReachFunding: Int = (((scalingFactor * btc) / blockReward) + 1).toInt()
            max(nodeParams.minDepthBlocks, blocksToReachFunding)
        }

    /** Called by the fundee. */
    fun validateParamsFundee(nodeParams: NodeParams, open: OpenChannel, channelVersion: ChannelVersion, localFeeratePerKw: Long): Either<ChannelException, Unit> {
        // BOLT #2: if the chain_hash value, within the open_channel, message is set to a hash of a chain that is unknown to the receiver:
        // MUST reject the channel.
        if (nodeParams.chainHash != open.chainHash) {
            return Either.Left(InvalidChainHash(open.temporaryChannelId, local = nodeParams.chainHash, remote = open.chainHash))
        }

        if (open.fundingSatoshis < nodeParams.minFundingSatoshis || open.fundingSatoshis > nodeParams.maxFundingSatoshis) {
            return Either.Left(InvalidFundingAmount(open.temporaryChannelId, open.fundingSatoshis, nodeParams.minFundingSatoshis, nodeParams.maxFundingSatoshis))
        }

        // BOLT #2: Channel funding limits
        if (open.fundingSatoshis >= Channel.MAX_FUNDING && !nodeParams.features.hasFeature(Feature.Wumbo)) {
            return Either.Left(InvalidFundingAmount(open.temporaryChannelId, open.fundingSatoshis, nodeParams.minFundingSatoshis, Channel.MAX_FUNDING))
        }

        // BOLT #2: The receiving node MUST fail the channel if: push_msat is greater than funding_satoshis * 1000.
        if (open.pushMsat.truncateToSatoshi() > open.fundingSatoshis) {
            return Either.Left(InvalidPushAmount(open.temporaryChannelId, open.pushMsat, open.fundingSatoshis.toMilliSatoshi()))
        }

        // BOLT #2: The receiving node MUST fail the channel if: to_self_delay is unreasonably large.
        if (open.toSelfDelay > Channel.MAX_TO_SELF_DELAY || open.toSelfDelay > nodeParams.maxToLocalDelayBlocks) {
            return Either.Left(ToSelfDelayTooHigh(open.temporaryChannelId, open.toSelfDelay, nodeParams.maxToLocalDelayBlocks))
        }

        // BOLT #2: The receiving node MUST fail the channel if: max_accepted_htlcs is greater than 483.
        if (open.maxAcceptedHtlcs > Channel.MAX_ACCEPTED_HTLCS) {
            return Either.Left(InvalidMaxAcceptedHtlcs(open.temporaryChannelId, open.maxAcceptedHtlcs, Channel.MAX_ACCEPTED_HTLCS))
        }

        // BOLT #2: The receiving node MUST fail the channel if: push_msat is greater than funding_satoshis * 1000.
        if (isFeeTooSmall(open.feeratePerKw)) {
            return Either.Left(FeerateTooSmall(open.temporaryChannelId, open.feeratePerKw))
        }

        if (channelVersion.isSet(ChannelVersion.ZERO_RESERVE_BIT)) {
            // in zero-reserve channels, we don't make any requirements on the fundee's reserve (set by the funder in the open_message).
        } else {
            // BOLT #2: The receiving node MUST fail the channel if: dust_limit_satoshis is greater than channel_reserve_satoshis.
            if (open.dustLimitSatoshis > open.channelReserveSatoshis) {
                return Either.Left(DustLimitTooLarge(open.temporaryChannelId, open.dustLimitSatoshis, open.channelReserveSatoshis))
            }
        }

        // BOLT #2: The receiving node MUST fail the channel if both to_local and to_remote amounts for the initial commitment
        // transaction are less than or equal to channel_reserve_satoshis (see BOLT 3).
        val (toLocalMsat, toRemoteMsat) = Pair(open.pushMsat, open.fundingSatoshis.toMilliSatoshi() - open.pushMsat)
        if (toLocalMsat.truncateToSatoshi() < open.channelReserveSatoshis && toRemoteMsat.truncateToSatoshi() < open.channelReserveSatoshis) {
            return Either.Left(ChannelReserveNotMet(open.temporaryChannelId, toLocalMsat, toRemoteMsat, open.channelReserveSatoshis))
        }

        if (isFeeDiffTooHigh(open.feeratePerKw, localFeeratePerKw, nodeParams.onChainFeeConf.maxFeerateMismatch)) {
            return Either.Left(FeerateTooDifferent(open.temporaryChannelId, localFeeratePerKw, open.feeratePerKw))
        }

        // only enforce dust limit check on mainnet
        if (nodeParams.chainHash == Block.LivenetGenesisBlock.hash && open.dustLimitSatoshis < Channel.MIN_DUSTLIMIT) {
            return Either.Left(DustLimitTooSmall(open.temporaryChannelId, open.dustLimitSatoshis, Channel.MIN_DUSTLIMIT))
        }

        // we don't check that the funder's amount for the initial commitment transaction is sufficient for full fee payment
        // now, but it will be done later when we receive `funding_created`
        val reserveToFundingRatio = open.channelReserveSatoshis.toLong().toDouble() / max(open.fundingSatoshis.toLong(), 1)
        if (reserveToFundingRatio > nodeParams.maxReserveToFundingRatio) {
            return Either.Left(ChannelReserveTooHigh(open.temporaryChannelId, open.channelReserveSatoshis, reserveToFundingRatio, nodeParams.maxReserveToFundingRatio))
        }

        return Either.Right(Unit)
    }

    /** Called by the funder. */
    fun validateParamsFunder(nodeParams: NodeParams, open: OpenChannel, accept: AcceptChannel): Either<ChannelException, Unit> {
        if (accept.maxAcceptedHtlcs > Channel.MAX_ACCEPTED_HTLCS) {
            return Either.Left(InvalidMaxAcceptedHtlcs(accept.temporaryChannelId, accept.maxAcceptedHtlcs, Channel.MAX_ACCEPTED_HTLCS))
        }
        // only enforce dust limit check on mainnet
        if (nodeParams.chainHash == Block.LivenetGenesisBlock.hash && accept.dustLimitSatoshis < Channel.MIN_DUSTLIMIT) {
            return Either.Left(DustLimitTooSmall(accept.temporaryChannelId, accept.dustLimitSatoshis, Channel.MIN_DUSTLIMIT))
        }

        // BOLT #2: The receiving node MUST fail the channel if: dust_limit_satoshis is greater than channel_reserve_satoshis.
        if (accept.dustLimitSatoshis > accept.channelReserveSatoshis) {
            return Either.Left(DustLimitTooLarge(accept.temporaryChannelId, accept.dustLimitSatoshis, accept.channelReserveSatoshis))
        }

        // if minimum_depth is unreasonably large: MAY reject the channel.
        if (accept.toSelfDelay > Channel.MAX_TO_SELF_DELAY || accept.toSelfDelay > nodeParams.maxToLocalDelayBlocks) {
            return Either.Left(ToSelfDelayTooHigh(accept.temporaryChannelId, accept.toSelfDelay, nodeParams.maxToLocalDelayBlocks))
        }

        if ((open.channelVersion ?: ChannelVersion.STANDARD).isSet(ChannelVersion.ZERO_RESERVE_BIT)) {
            // in zero-reserve channels, we don't make any requirements on the fundee's reserve (set by the funder in the open_message).
        } else {
            // if channel_reserve_satoshis from the open_channel message is less than dust_limit_satoshis:
            // MUST reject the channel. Other fields have the same requirements as their counterparts in open_channel.
            if (open.channelReserveSatoshis < accept.dustLimitSatoshis) {
                return Either.Left(DustLimitAboveOurChannelReserve(accept.temporaryChannelId, accept.dustLimitSatoshis, open.channelReserveSatoshis))
            }
        }

        // if channel_reserve_satoshis is less than dust_limit_satoshis within the open_channel message: MUST reject the channel.
        if (accept.channelReserveSatoshis < open.dustLimitSatoshis) {
            return Either.Left(ChannelReserveBelowOurDustLimit(accept.temporaryChannelId, accept.channelReserveSatoshis, open.dustLimitSatoshis))
        }

        val reserveToFundingRatio = accept.channelReserveSatoshis.toLong().toDouble() / max(open.fundingSatoshis.toLong(), 1)
        if (reserveToFundingRatio > nodeParams.maxReserveToFundingRatio) {
            return Either.Left(ChannelReserveTooHigh(open.temporaryChannelId, accept.channelReserveSatoshis, reserveToFundingRatio, nodeParams.maxReserveToFundingRatio))
        }

        return Either.Right(Unit)
    }

    /**
     * @param referenceFeePerKw reference fee rate per kiloweight
     * @param currentFeePerKw   current fee rate per kiloweight
     * @return the "normalized" difference between i.e local and remote fee rate: |reference - current| / avg(current, reference)
     */
    fun feeRateMismatch(referenceFeePerKw: Long, currentFeePerKw: Long): Double = abs((2.0 * (referenceFeePerKw - currentFeePerKw)) / (currentFeePerKw + referenceFeePerKw))

    /**
     * @param remoteFeeratePerKw remote fee rate per kiloweight
     * @return true if the remote fee rate is too small
     */
    fun isFeeTooSmall(remoteFeeratePerKw: Long): Boolean = remoteFeeratePerKw < MinimumFeeratePerKw

    /**
     * @param referenceFeePerKw       reference fee rate per kiloweight
     * @param currentFeePerKw         current fee rate per kiloweight
     * @param maxFeerateMismatchRatio maximum fee rate mismatch ratio
     * @return true if the difference between current and reference fee rates is too high.
     *         the actual check is |reference - current| / avg(current, reference) > mismatch ratio
     */
    fun isFeeDiffTooHigh(referenceFeePerKw: Long, currentFeePerKw: Long, maxFeerateMismatchRatio: Double): Boolean = feeRateMismatch(referenceFeePerKw, currentFeePerKw) > maxFeerateMismatchRatio

    /**
     * This indicates whether our side of the channel is above the reserve requested by our counterparty. In other words,
     * this tells if we can use the channel to make a payment.
     */
    fun aboveReserve(commitments: Commitments): Boolean {
        val remoteCommit = when (commitments.remoteNextCommitInfo) {
            is Either.Left -> commitments.remoteNextCommitInfo.value.nextRemoteCommit
            else -> commitments.remoteCommit
        }
        val toRemote = remoteCommit.spec.toRemote.truncateToSatoshi()
        // NB: this is an approximation (we don't take network fees into account)
        return toRemote > commitments.remoteParams.channelReserve
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

    /** This helper method will publish txs only if they haven't yet reached minDepth. */
    fun publishIfNeeded(txs: List<Transaction>, irrevocablySpent: Map<OutPoint, ByteVector32>): List<ChannelAction.Blockchain.PublishTx> {
        val (skip, process) = txs.partition { it.inputsAlreadySpent(irrevocablySpent) }
        skip.forEach { tx -> logger.info { "no need to republish txid=${tx.txid}, it has already been confirmed" } }
        return process.map { tx ->
            logger.info { "publishing txid=${tx.txid}" }
            ChannelAction.Blockchain.PublishTx(tx)
        }
    }

    /** This helper method will watch txs only if they haven't yet reached minDepth. */
    fun watchConfirmedIfNeeded(txs: List<Transaction>, irrevocablySpent: Map<OutPoint, ByteVector32>, channelId: ByteVector32, minDepth: Long): List<ChannelAction.Blockchain.SendWatch> {
        val (skip, process) = txs.partition { it.inputsAlreadySpent(irrevocablySpent) }
        skip.forEach { tx -> logger.info { "no need to watch txid=${tx.txid}, it has already been confirmed" } }
        return process.map { tx -> ChannelAction.Blockchain.SendWatch(WatchConfirmed(channelId, tx, minDepth, BITCOIN_TX_CONFIRMED(tx))) }
    }

    /** This helper method will watch txs only if the utxo they spend hasn't already been irrevocably spent. */
    fun watchSpentIfNeeded(parentTx: Transaction, txs: List<Transaction>, irrevocablySpent: Map<OutPoint, ByteVector32>, channelId: ByteVector32): List<ChannelAction.Blockchain.SendWatch> {
        val (skip, process) = txs.partition { it.inputsAlreadySpent(irrevocablySpent) }
        skip.forEach { tx -> logger.info { "no need to watch txid=${tx.txid}, it has already been confirmed" } }
        return process.map { ChannelAction.Blockchain.SendWatch(WatchSpent(channelId, parentTx, it.txIn.first().outPoint.index.toInt(), BITCOIN_OUTPUT_SPENT)) }
    }

    object Funding {

        fun makeFundingInputInfo(
            fundingTxId: ByteVector32,
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

        data class FirstCommitTx(val localSpec: CommitmentSpec, val localCommitTx: Transactions.TransactionWithInputInfo.CommitTx, val remoteSpec: CommitmentSpec, val remoteCommitTx: Transactions.TransactionWithInputInfo.CommitTx)

        /**
         * Creates both sides' first commitment transaction.
         *
         * @return (localSpec, localTx, remoteSpec, remoteTx, fundingTxOutput)
         */
        fun makeFirstCommitTxs(
            commitmentsFormat: CommitmentsFormat,
            keyManager: KeyManager,
            channelVersion: ChannelVersion,
            temporaryChannelId: ByteVector32,
            localParams: LocalParams,
            remoteParams: RemoteParams,
            fundingAmount: Satoshi,
            pushMsat: MilliSatoshi,
            initialFeeratePerKw: Long,
            fundingTxHash: ByteVector32,
            fundingTxOutputIndex: Int,
            remoteFirstPerCommitmentPoint: PublicKey
        ): Either<ChannelException, FirstCommitTx> {
            val toLocalMsat = if (localParams.isFunder) MilliSatoshi(fundingAmount) - pushMsat else pushMsat
            val toRemoteMsat = if (localParams.isFunder) pushMsat else MilliSatoshi(fundingAmount) - pushMsat

            val localSpec = CommitmentSpec(setOf(), feeratePerKw = initialFeeratePerKw, toLocal = toLocalMsat, toRemote = toRemoteMsat)
            val remoteSpec = CommitmentSpec(setOf(), feeratePerKw = initialFeeratePerKw, toLocal = toRemoteMsat, toRemote = toLocalMsat)

            if (!localParams.isFunder) {
                // they are funder, therefore they pay the fee: we need to make sure they can afford it!
                val localToRemoteMsat = remoteSpec.toLocal
                val fees = commitTxFee(commitmentsFormat, remoteParams.dustLimit, remoteSpec)
                val missing = localToRemoteMsat.truncateToSatoshi() - localParams.channelReserve - fees
                if (missing < Satoshi(0)) {
                    return Either.Left(CannotAffordFees(temporaryChannelId, missing = -missing, reserve = localParams.channelReserve, fees = fees))
                }
            }

            val fundingPubKey = keyManager.fundingPublicKey(localParams.fundingKeyPath)
            val channelKeyPath = keyManager.channelKeyPath(localParams, channelVersion)
            val commitmentInput = makeFundingInputInfo(fundingTxHash, fundingTxOutputIndex, fundingAmount, fundingPubKey.publicKey, remoteParams.fundingPubKey)
            val localPerCommitmentPoint = keyManager.commitmentPoint(channelKeyPath, 0)
            val localCommitTx = Commitments.makeLocalTxs(commitmentsFormat, keyManager, channelVersion, 0, localParams, remoteParams, commitmentInput, localPerCommitmentPoint, localSpec).first
            val remoteCommitTx = Commitments.makeRemoteTxs(commitmentsFormat, keyManager, channelVersion, 0, localParams, remoteParams, commitmentInput, remoteFirstPerCommitmentPoint, remoteSpec).first

            return Either.Right(FirstCommitTx(localSpec, localCommitTx, remoteSpec, remoteCommitTx))
        }
    }

    object Closing {
        // used only to compute tx weights and estimate fees
        private val dummyPublicKey by lazy { PrivateKey(ByteArray(32) { 1.toByte() }).publicKey() }

        private fun isValidFinalScriptPubkey(scriptPubKey: ByteArray): Boolean {
            return runTrying {
                val script = Script.parse(scriptPubKey)
                Script.isPay2pkh(script) || Script.isPay2sh(script) || Script.isPay2wpkh(script) || Script.isPay2wsh(script)
            }.getOrElse { false }
        }

        fun isValidFinalScriptPubkey(scriptPubKey: ByteVector): Boolean = isValidFinalScriptPubkey(scriptPubKey.toByteArray())

        fun firstClosingFee(commitments: Commitments, localScriptPubkey: ByteArray, remoteScriptPubkey: ByteArray, requestedFeeratePerKw: Long): Satoshi {
            // this is just to estimate the weight, it depends on size of the pubkey scripts
            val dummyClosingTx = Transactions.makeClosingTx(commitments.commitInput, localScriptPubkey, remoteScriptPubkey, commitments.localParams.isFunder, Satoshi(0), Satoshi(0), commitments.localCommit.spec)
            val closingWeight = Transaction.weight(Transactions.addSigs(dummyClosingTx, dummyPublicKey, commitments.remoteParams.fundingPubKey, Transactions.PlaceHolderSig, Transactions.PlaceHolderSig).tx)
            val feeratePerKw = min(requestedFeeratePerKw, commitments.localCommit.spec.feeratePerKw)
            return Transactions.weight2fee(feeratePerKw, closingWeight)
        }

        fun nextClosingFee(localClosingFee: Satoshi, remoteClosingFee: Satoshi): Satoshi = ((localClosingFee + remoteClosingFee) / 4) * 2

        fun makeFirstClosingTx(
            keyManager: KeyManager,
            commitments: Commitments,
            localScriptPubkey: ByteArray,
            remoteScriptPubkey: ByteArray,
            requestedFeeratePerKw: Long
        ): Pair<Transactions.TransactionWithInputInfo.ClosingTx, ClosingSigned> {
            val closingFee = firstClosingFee(commitments, localScriptPubkey, remoteScriptPubkey, requestedFeeratePerKw)
            return makeClosingTx(keyManager, commitments, localScriptPubkey, remoteScriptPubkey, closingFee)
        }

        fun makeClosingTx(
            keyManager: KeyManager,
            commitments: Commitments,
            localScriptPubkey: ByteArray,
            remoteScriptPubkey: ByteArray,
            closingFee: Satoshi
        ): Pair<Transactions.TransactionWithInputInfo.ClosingTx, ClosingSigned> {
            require(isValidFinalScriptPubkey(localScriptPubkey)) { "invalid localScriptPubkey" }
            require(isValidFinalScriptPubkey(remoteScriptPubkey)) { "invalid remoteScriptPubkey" }
            val dustLimit = commitments.localParams.dustLimit.max(commitments.remoteParams.dustLimit)
            val closingTx = Transactions.makeClosingTx(commitments.commitInput, localScriptPubkey, remoteScriptPubkey, commitments.localParams.isFunder, dustLimit, closingFee, commitments.localCommit.spec)
            val localClosingSig = keyManager.sign(closingTx, keyManager.fundingPublicKey(commitments.localParams.fundingKeyPath))
            val closingSigned = ClosingSigned(commitments.channelId, closingFee, localClosingSig)
            return Pair(closingTx, closingSigned)
        }

        fun checkClosingSignature(
            keyManager: KeyManager,
            commitments: Commitments,
            localScriptPubkey: ByteArray,
            remoteScriptPubkey: ByteArray,
            remoteClosingFee: Satoshi,
            remoteClosingSig: ByteVector64
        ): Either<ChannelException, Transaction> {
            val lastCommitFee = commitments.commitInput.txOut.amount - commitments.localCommit.publishableTxs.commitTx.tx.txOut.map { it.amount }.sum()
            if (remoteClosingFee > lastCommitFee) {
                return Either.Left(InvalidCloseFee(commitments.channelId, remoteClosingFee))
            }
            val (closingTx, closingSigned) = makeClosingTx(keyManager, commitments, localScriptPubkey, remoteScriptPubkey, remoteClosingFee)
            val signedClosingTx = Transactions.addSigs(closingTx, keyManager.fundingPublicKey(commitments.localParams.fundingKeyPath).publicKey, commitments.remoteParams.fundingPubKey, closingSigned.signature, remoteClosingSig)
            return when (Transactions.checkSpendable(signedClosingTx)) {
                is Try.Success -> Either.Right(signedClosingTx.tx)
                is Try.Failure -> Either.Left(InvalidCloseSignature(commitments.channelId, signedClosingTx.tx))
            }
        }

        /**
         * Claim all the HTLCs that we've received from our current commit tx. This will be done using 2nd stage HTLC transactions.
         *
         * @param commitments our commitment data, which include payment preimages
         * @return a list of transactions (one per HTLC that we can claim)
         */
        fun claimCurrentLocalCommitTxOutputs(keyManager: KeyManager, commitments: Commitments, tx: Transaction, feerates: OnchainFeerates): LocalCommitPublished {
            val localCommit = commitments.localCommit
            val localParams = commitments.localParams
            val channelVersion = commitments.channelVersion
            require(localCommit.publishableTxs.commitTx.tx.txid == tx.txid) { "txid mismatch, provided tx is not the current local commit tx" }
            val channelKeyPath = keyManager.channelKeyPath(localParams, channelVersion)
            val localPerCommitmentPoint = keyManager.commitmentPoint(channelKeyPath, commitments.localCommit.index)
            val localRevocationPubkey = Generators.revocationPubKey(commitments.remoteParams.revocationBasepoint, localPerCommitmentPoint)
            val localDelayedPubkey = Generators.derivePubKey(keyManager.delayedPaymentPoint(channelKeyPath).publicKey, localPerCommitmentPoint)
            val feeratePerKwDelayed = feerates.claimMainFeeratePerKw

            // first we will claim our main output as soon as the delay is over
            val mainDelayedTx = generateTx("main-delayed-output") {
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

            val htlcTxs = localCommit.publishableTxs.htlcTxsAndSigs.mapNotNull {
                val (txInfo, localSig, remoteSig) = it
                when (txInfo) {
                    // incoming htlc for which we have the preimage: we spend it directly
                    is HtlcSuccessTx -> {
                        preimages.firstOrNull { r ->
                            sha256(r).toByteVector() == txInfo.paymentHash
                        }?.let { preimage -> Transactions.addSigs(txInfo, localSig, remoteSig, preimage, commitments.commitmentsFormat.htlcTxSighashFlag) }
                    }
                    // (incoming htlc for which we don't have the preimage: nothing to do, it will timeout eventually and they will get their funds back)

                    // outgoing htlc: they may or may not have the preimage, the only thing to do is try to get back our funds after timeout
                    is HtlcTimeoutTx -> Transactions.addSigs(txInfo, localSig, remoteSig, commitments.commitmentsFormat.htlcTxSighashFlag)
                    else -> null
                }
            }

            // all htlc output to us are delayed, so we need to claim them as soon as the delay is over
            val htlcDelayedTxes = htlcTxs.mapNotNull { txInfo ->
                generateTx("claim-htlc-delayed") {
                    Transactions.makeClaimDelayedOutputTx(
                        txInfo.tx,
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
                htlcSuccessTxs = htlcTxs.filterIsInstance<HtlcSuccessTx>().map(HtlcSuccessTx::tx),
                htlcTimeoutTxs = htlcTxs.filterIsInstance<HtlcTimeoutTx>().map(HtlcTimeoutTx::tx),
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
        fun claimRemoteCommitTxOutputs(keyManager: KeyManager, commitments: Commitments, remoteCommit: RemoteCommit, tx: Transaction, feerates: OnchainFeerates): RemoteCommitPublished {
            val channelVersion = commitments.channelVersion
            val localParams = commitments.localParams
            val remoteParams = commitments.remoteParams
            val commitInput = commitments.commitInput
            val (remoteCommitTx, _, _) = Commitments.makeRemoteTxs(
                commitments.commitmentsFormat,
                keyManager,
                channelVersion,
                remoteCommit.index,
                localParams,
                remoteParams,
                commitInput,
                remoteCommit.remotePerCommitmentPoint,
                remoteCommit.spec
            )
            require(remoteCommitTx.tx.txid == tx.txid) { "txid mismatch, provided tx is not the current remote commit tx" }
            val channelKeyPath = keyManager.channelKeyPath(localParams, channelVersion)
            val localHtlcPubkey = Generators.derivePubKey(keyManager.htlcPoint(channelKeyPath).publicKey, remoteCommit.remotePerCommitmentPoint)
            val remoteHtlcPubkey = Generators.derivePubKey(remoteParams.htlcBasepoint, remoteCommit.remotePerCommitmentPoint)
            val remoteRevocationPubkey = Generators.revocationPubKey(keyManager.revocationPoint(channelKeyPath).publicKey, remoteCommit.remotePerCommitmentPoint)
            val remoteDelayedPaymentPubkey = Generators.derivePubKey(remoteParams.delayedPaymentBasepoint, remoteCommit.remotePerCommitmentPoint)
            val localPaymentPubkey = Generators.derivePubKey(keyManager.paymentPoint(channelKeyPath).publicKey, remoteCommit.remotePerCommitmentPoint)
            val outputs =
                makeCommitTxOutputs(
                    commitments.commitmentsFormat,
                    commitments.remoteParams.fundingPubKey,
                    keyManager.fundingPublicKey(commitments.localParams.fundingKeyPath).publicKey,
                    !localParams.isFunder,
                    remoteParams.dustLimit,
                    remoteRevocationPubkey,
                    localParams.toSelfDelay,
                    remoteDelayedPaymentPubkey,
                    localPaymentPubkey,
                    remoteHtlcPubkey,
                    localHtlcPubkey,
                    remoteCommit.spec
                )

            // we need to use a rather high fee for htlc-claim because we compete with the counterparty
            val feeratePerKwHtlc = feerates.commitmentFeeratePerKw

            // those are the preimages to existing received htlcs
            val preimages = commitments.localChanges.all.filterIsInstance<UpdateFulfillHtlc>().map { it.paymentPreimage }

            // remember we are looking at the remote commitment so IN for them is really OUT for us and vice versa

            val claimHtlcSuccessTxs = remoteCommit.spec.htlcs.filterIsInstance<OutgoingHtlc>().map { it.add }.mapNotNull { add ->
                // incoming htlc for which we have the preimage: we spend it directly.
                // NB: we are looking at the remote's commitment, from its point of view it's an outgoing htlc.
                preimages.firstOrNull { r -> sha256(r).toByteVector() == add.paymentHash }?.let { preimage ->
                    generateTx("claim-htlc-success") {
                        Transactions.makeClaimHtlcSuccessTx(
                            commitments.commitmentsFormat,
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
                generateTx("claim-htlc-timeout") {
                    Transactions.makeClaimHtlcTimeoutTx(
                        commitments.commitmentsFormat,
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
                claimRemoteCommitMainOutput(keyManager, commitments, remoteCommit.remotePerCommitmentPoint, tx, feerates.claimMainFeeratePerKw).copy(
                    claimHtlcSuccessTxs = claimHtlcSuccessTxs,
                    claimHtlcTimeoutTxs = claimHtlcTimeoutTxs
                )
            }
        }

        /**
         * Claim our main output only, not used if option_static_remotekey was negotiated
         *
         * @param commitments              either our current commitment data in case of usual remote uncooperative closing
         *                                 or our outdated commitment data in case of data loss protection procedure; in any case it is used only
         *                                 to get some constant parameters, not commitment data
         * @param remotePerCommitmentPoint the remote perCommitmentPoint corresponding to this commitment
         * @param tx                       the remote commitment transaction that has just been published
         * @return a list of transactions (one per HTLC that we can claim)
         */
        internal fun claimRemoteCommitMainOutput(keyManager: KeyManager, commitments: Commitments, remotePerCommitmentPoint: PublicKey, tx: Transaction, claimMainFeeratePerKw: Long): RemoteCommitPublished {
            val channelKeyPath = keyManager.channelKeyPath(commitments.localParams, commitments.channelVersion)
            val localPubkey = Generators.derivePubKey(keyManager.paymentPoint(channelKeyPath).publicKey, remotePerCommitmentPoint)

            val mainTx = generateTx("claim-p2wpkh-output") {
                Transactions.makeClaimP2WPKHOutputTx(
                    tx,
                    commitments.localParams.dustLimit,
                    localPubkey,
                    commitments.localParams.defaultFinalScriptPubKey.toByteArray(),
                    claimMainFeeratePerKw
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
        fun claimRevokedRemoteCommitTxOutputs(keyManager: KeyManager, commitments: Commitments, tx: Transaction, feerates: OnchainFeerates): RevokedCommitPublished? {
            val channelVersion = commitments.channelVersion
            val localParams = commitments.localParams
            val remoteParams = commitments.remoteParams

            require(tx.txIn.size == 1) { "commitment tx should have 1 input" }

            val channelKeyPath = keyManager.channelKeyPath(localParams, channelVersion)
            val obscuredTxNumber = Transactions.decodeTxNumber(tx.txIn.first().sequence, tx.lockTime)
            val localPaymentPoint = localParams.walletStaticPaymentBasepoint ?: keyManager.paymentPoint(channelKeyPath).publicKey
            // this tx has been published by remote, so we need to invert local/remote params
            val txnumber = Transactions.obscuredCommitTxNumber(obscuredTxNumber, !localParams.isFunder, remoteParams.paymentBasepoint, localPaymentPoint)
            require(txnumber <= 0xffffffffffffL) { "txnumber must be lesser than 48 bits long" }
            logger.warning { "a revoked commit has been published with txnumber=$txnumber" }

            // now we know what commit number this tx is referring to, we can derive the commitment point from the shachain
            val hash = commitments.remotePerCommitmentSecrets.getHash(0xFFFFFFFFFFFFL - txnumber) ?: return null

            val remotePerCommitmentSecret = PrivateKey.fromHex(hash.toHex())
            val remotePerCommitmentPoint = remotePerCommitmentSecret.publicKey()
            val remoteDelayedPaymentPubkey = Generators.derivePubKey(remoteParams.delayedPaymentBasepoint, remotePerCommitmentPoint)
            val remoteRevocationPubkey = Generators.revocationPubKey(
                keyManager.revocationPoint(channelKeyPath).publicKey,
                remotePerCommitmentPoint
            )
//            val remoteHtlcPubkey = Generators.derivePubKey(remoteParams.htlcBasepoint, remotePerCommitmentPoint)
            val localPaymentPubkey = Generators.derivePubKey(keyManager.paymentPoint(channelKeyPath).publicKey, remotePerCommitmentPoint)
//            val localHtlcPubkey = Generators.derivePubKey(keyManager.htlcPoint(channelKeyPath).publicKey, remotePerCommitmentPoint)

            val feeratePerKwMain = feerates.claimMainFeeratePerKw
            // we need to use a high fee here for punishment txes because after a delay they can be spent by the counterparty
            val feeratePerKwPenalty = feerates.fastFeeratePerKw

            // first we will claim our main output right away
            val mainTx = when {
                channelVersion.hasStaticRemotekey -> {
                    logger.info { "channel uses option_static_remotekey, not claiming our p2wpkh output" }
                    null
                }
                else -> generateTx("claim-p2wpkh-output") {
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
            val mainPenaltyTx = generateTx("main-penalty") {
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
            //                        generateTx("htlc-penalty") {
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
        fun claimRevokedHtlcTxOutputs(keyManager: KeyManager, commitments: Commitments, revokedCommitPublished: RevokedCommitPublished, htlcTx: Transaction, feerates: OnchainFeerates): Pair<RevokedCommitPublished, Transaction?> {
            val claimTxs = buildList {
                revokedCommitPublished.claimMainOutputTx?.let { add(it) }
                revokedCommitPublished.mainPenaltyTx?.let { add(it) }
                addAll(revokedCommitPublished.htlcPenaltyTxs)
            }

            if (htlcTx.txIn.map { it.outPoint.txid }.contains(revokedCommitPublished.commitTx.txid) && !claimTxs.map { it.txid }.toSet().contains(htlcTx.txid)) {
                logger.info { "looks like txid=${htlcTx.txid} could be a 2nd level htlc tx spending revoked commit txid=${revokedCommitPublished.commitTx.txid}" }
                // Let's assume that htlcTx is an HtlcSuccessTx or HtlcTimeoutTx and try to generate a tx spending its output using a revocation key
                val localParams = commitments.localParams
                val channelVersion = commitments.channelVersion
                val remoteParams = commitments.remoteParams
                val remotePerCommitmentSecrets = commitments.remotePerCommitmentSecrets

                val tx = revokedCommitPublished.commitTx
                val obscuredTxNumber = Transactions.decodeTxNumber(tx.txIn.first().sequence, tx.lockTime)
                val channelKeyPath = keyManager.channelKeyPath(localParams, channelVersion)

                // this tx has been published by remote, so we need to invert local/remote params
                val txNumber = Transactions.obscuredCommitTxNumber(
                    obscuredTxNumber,
                    !localParams.isFunder,
                    remoteParams.paymentBasepoint,
                    keyManager.paymentPoint(channelKeyPath).publicKey
                )
                // now we know what commit number this tx is referring to, we can derive the commitment point from the shachain
                val hash = remotePerCommitmentSecrets.getHash(0xFFFFFFFFFFFFL - txNumber) ?: return revokedCommitPublished to null

                val remotePerCommitmentSecret = PrivateKey(hash)
                val remotePerCommitmentPoint = remotePerCommitmentSecret.publicKey()
                val remoteDelayedPaymentPubkey = Generators.derivePubKey(remoteParams.delayedPaymentBasepoint, remotePerCommitmentPoint)
                val remoteRevocationPubkey = Generators.revocationPubKey(keyManager.revocationPoint(channelKeyPath).publicKey, remotePerCommitmentPoint)

                // we need to use a high fee here for punishment txes because after a delay they can be spent by the counterparty
                val feeratePerKwPenalty = feerates.fastFeeratePerKw

                val signedTx = generateTx("claim-htlc-delayed-penalty") {
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
                    when (runTrying { Transaction.correctlySpends(signedTx, listOf(htlcTx), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS) }) {
                        is Try.Success -> signedTx
                        is Try.Failure -> null
                    }
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
        fun LocalCommit.extractPreimages(tx: Transaction): Set<Pair<UpdateAddHtlc, ByteVector32>> {
            val htlcSuccess = tx.txIn.map { it.witness }.mapNotNull(Scripts.extractPreimageFromHtlcSuccess())
                .also { it.forEach { logger.info { "extracted paymentPreimage=$it from tx=$tx (htlc-success)" } } }
            val claimHtlcSuccess = tx.txIn.map { it.witness }.mapNotNull(Scripts.extractPreimageFromClaimHtlcSuccess())
                .also { it.forEach { logger.info { "extracted paymentPreimage=$it from tx=$tx (claim-htlc-success)" } } }
            val paymentPreimages = (htlcSuccess + claimHtlcSuccess).toSet()

            return paymentPreimages.flatMap { paymentPreimage ->
                // we only consider htlcs in our local commitment, because we only care about outgoing htlcs, which disappear first in the remote commitment
                // if an outgoing htlc is in the remote commitment, then:
                // - either it is in the local commitment (it was never fulfilled)
                // - or we have already received the fulfill and forwarded it upstream
                spec.htlcs.filter { it is OutgoingHtlc && it.add.paymentHash.contentEquals(sha256(paymentPreimage)) }.map { it.add to paymentPreimage }
            }.toSet()
        }

        /**
         * We may have multiple HTLCs with the same payment hash because of MPP.
         * When a timeout transaction is confirmed, we need to find the best matching HTLC to fail upstream.
         * We need to handle potentially duplicate HTLCs (same amount and expiry): this function will use a deterministic
         * ordering of transactions and HTLCs to handle this.
         */
        private fun Transaction.findTimedOutHtlc(paymentHash160: ByteVector, htlcs: List<UpdateAddHtlc>, timeoutTxs: List<Transaction>, extractPaymentHash: (ScriptWitness) -> ByteVector?): UpdateAddHtlc? {
            // We use a deterministic ordering to match HTLCs to their corresponding HTLC-timeout tx.
            // We don't match on the expected amounts because this is error-prone: computing the correct weight of a claim-htlc-timeout
            // is hard because signatures can be either 71, 72 or 73 bytes long (ECDSA DER encoding).
            // We could instead look at the spent outpoint, but that requires more lookups and access to the published commitment transaction.
            // It's simpler to just use the amount as the first ordering key: since the feerate is the same for all timeout
            // transactions we will find the right HTLC to fail upstream.
            val matchingHtlcs = htlcs
                .filter { it.cltvExpiry.toLong() == lockTime && ripemd160(it.paymentHash).toByteVector() == paymentHash160 }
                .sortedWith(compareBy({ it.amountMsat.toLong() }, { it.id }))

            val matchingTxs = timeoutTxs
                .filter { t -> t.lockTime == t.lockTime && t.txIn.map { it.witness }.map(extractPaymentHash).contains(paymentHash160) }
                .sortedWith(compareBy({ t -> t.txOut.map { it.amount.sat }.sum() }, { it.txid.toHex() }))

            if (matchingTxs.size != matchingHtlcs.size) {
                logger.error { "some htlcs don't have a corresponding timeout transaction: tx=$this, htlcs=$matchingHtlcs, timeout-txs=$matchingTxs" }
            }

            return matchingHtlcs.zip(matchingTxs).firstOrNull { (_, timeoutTx) -> timeoutTx.txid == txid }?.first
        }

        /**
         * In CLOSING state, when we are notified that a transaction has been confirmed, we analyze it to find out if one or
         * more htlcs have timed out and need to be failed in an upstream channel.
         *
         * @param tx a tx that has reached mindepth
         * @return a set of htlcs that need to be failed upstream
         */
        fun LocalCommit.timedoutHtlcs(commitmentsFormat: CommitmentsFormat, localCommitPublished: LocalCommitPublished, localDustLimit: Satoshi, tx: Transaction): Set<UpdateAddHtlc> {
            val untrimmedHtlcs = Transactions.trimOfferedHtlcs(commitmentsFormat, localDustLimit, spec).map { it.add }
            return if (tx.txid == publishableTxs.commitTx.tx.txid) {
                // the tx is a commitment tx, we can immediately fail all dust htlcs (they don't have an output in the tx)
                (spec.htlcs.outgoings() - untrimmedHtlcs).toSet()
            } else {
                // maybe this is a timeout tx, in that case we can resolve and fail the corresponding htlc
                tx.txIn
                    .map { it.witness }
                    .mapNotNull(Scripts.extractPaymentHashFromHtlcTimeout())
                    .mapNotNull { paymentHash160 ->
                        logger.info { ("extracted paymentHash160=$paymentHash160 and expiry=${tx.lockTime} from tx=$tx (htlc-timeout)") }
                        tx.findTimedOutHtlc(
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
        fun RemoteCommit.timedoutHtlcs(commitmentsFormat: CommitmentsFormat, remoteCommitPublished: RemoteCommitPublished, remoteDustLimit: Satoshi, tx: Transaction): Set<UpdateAddHtlc> {
            val untrimmedHtlcs = Transactions.trimReceivedHtlcs(commitmentsFormat, remoteDustLimit, spec).map { it.add }
            return if (tx.txid == txid) {
                // the tx is a commitment tx, we can immediately fail all dust htlcs (they don't have an output in the tx)
                (spec.htlcs.incomings() - untrimmedHtlcs).toSet()
            } else {
                // maybe this is a timeout tx, in that case we can resolve and fail the corresponding htlc
                tx.txIn
                    .map { it.witness }
                    .mapNotNull(Scripts.extractPaymentHashFromHtlcTimeout())
                    .mapNotNull { paymentHash160 ->
                        logger.info { "extracted paymentHash160=$paymentHash160 and expiry=${tx.lockTime} from tx=$tx (claim-htlc-timeout)" }
                        tx.findTimedOutHtlc(
                            paymentHash160,
                            untrimmedHtlcs,
                            remoteCommitPublished.claimHtlcTimeoutTxs,
                            Scripts.extractPaymentHashFromClaimHtlcTimeout()
                        )
                    }.toSet()
            }
        }

        /**
         * As soon as a local or remote commitment reaches min_depth, we know which htlcs will be settled on-chain (whether
         * or not they actually have an output in the commitment tx).
         *
         * @param tx a transaction that is sufficiently buried in the blockchain
         */
        fun onChainOutgoingHtlcs(localCommit: LocalCommit, remoteCommit: RemoteCommit, nextRemoteCommit_opt: RemoteCommit?, tx: Transaction): Set<UpdateAddHtlc> =
            when {
                localCommit.publishableTxs.commitTx.tx.txid == tx.txid -> localCommit.spec.htlcs.outgoings().toSet()
                remoteCommit.txid == tx.txid -> remoteCommit.spec.htlcs.incomings().toSet()
                nextRemoteCommit_opt?.txid == tx.txid -> nextRemoteCommit_opt.spec.htlcs.incomings().toSet()
                else -> emptySet()
            }

        /**
         * This helper function tells if the utxo consumed by the given transaction has already been irrevocably spent (possibly by this very transaction)
         *
         * It can be useful to:
         *   - not attempt to publish this tx when we know this will fail
         *   - not watch for confirmations if we know the tx is already confirmed
         *   - not watch the corresponding utxo when we already know the final spending tx
         *
         * @param irrevocablySpent a map of known spent outpoints
         * @return true if we know for sure that the utxos consumed by the tx have already irrevocably been spent, false otherwise
         */
        fun Transaction.inputsAlreadySpent(irrevocablySpent: Map<OutPoint, ByteVector32>): Boolean {
            require(txIn.size == 1) { "only tx with one input is supported" }
            val outPoint = txIn.first().outPoint
            return irrevocablySpent.contains(outPoint)
        }

        /**
         * Wraps transaction generation in a Try and filters failures to avoid one transaction negatively impacting a whole commitment.
         */
        private fun <T : Transactions.TransactionWithInputInfo> generateTx(desc: String, attempt: () -> Transactions.TxResult<T>): T? =
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

    @OptIn(ExperimentalSerializationApi::class)
    fun encrypt(key: ByteVector32, state: ChannelStateWithCommitments): ByteArray {
        val bin = ChannelStateWithCommitments.serialize(state)
        // NB: there is a chance of collision here, due to how the nonce is calculated. Probability of collision is once every 2.2E19 times.
        // See https://en.wikipedia.org/wiki/Birthday_attack
        val nonce = sha256(bin).take(12).toByteArray()
        val (ciphertext, tag) = ChaCha20Poly1305.encrypt(key.toByteArray(), nonce, bin, ByteArray(0))
        return ciphertext + nonce + tag
    }

    @OptIn(ExperimentalSerializationApi::class)
    fun decrypt(key: ByteVector32, data: ByteArray): ChannelStateWithCommitments {
        // nonce is 12B, tag is 16B
        val ciphertext = data.dropLast(12 + 16)
        val nonce = data.takeLast(12 + 16).take(12)
        val tag = data.takeLast(16)
        val plaintext = ChaCha20Poly1305.decrypt(key.toByteArray(), nonce.toByteArray(), ciphertext.toByteArray(), ByteArray(0), tag.toByteArray())
        return ChannelStateWithCommitments.deserialize(plaintext)
    }

    fun decrypt(key: PrivateKey, data: ByteArray): ChannelStateWithCommitments = decrypt(key.value, data)

    fun decrypt(key: PrivateKey, data: ByteVector): ChannelStateWithCommitments = decrypt(key, data.toByteArray())
}
