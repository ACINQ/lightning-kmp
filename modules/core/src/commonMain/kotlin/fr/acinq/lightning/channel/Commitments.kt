package fr.acinq.lightning.channel

import fr.acinq.bitcoin.*
import fr.acinq.bitcoin.Crypto.sha256
import fr.acinq.bitcoin.crypto.musig2.IndividualNonce
import fr.acinq.bitcoin.utils.Either
import fr.acinq.lightning.CltvExpiryDelta
import fr.acinq.lightning.Feature
import fr.acinq.lightning.MilliSatoshi
import fr.acinq.lightning.ShortChannelId
import fr.acinq.lightning.blockchain.fee.FeeratePerByte
import fr.acinq.lightning.blockchain.fee.FeeratePerKw
import fr.acinq.lightning.blockchain.fee.FeerateTolerance
import fr.acinq.lightning.channel.states.Channel
import fr.acinq.lightning.channel.states.ChannelContext
import fr.acinq.lightning.crypto.ChannelKeys
import fr.acinq.lightning.crypto.KeyManager
import fr.acinq.lightning.crypto.LocalCommitmentKeys
import fr.acinq.lightning.crypto.RemoteCommitmentKeys
import fr.acinq.lightning.crypto.ShaChain
import fr.acinq.lightning.logging.MDCLogger
import fr.acinq.lightning.payment.OutgoingPaymentPacket
import fr.acinq.lightning.transactions.CommitmentSpec
import fr.acinq.lightning.transactions.Transactions
import fr.acinq.lightning.transactions.Transactions.CommitTx
import fr.acinq.lightning.transactions.Transactions.HtlcTx
import fr.acinq.lightning.transactions.Transactions.commitTxFee
import fr.acinq.lightning.transactions.Transactions.commitTxFeeMsat
import fr.acinq.lightning.transactions.Transactions.htlcOutputFee
import fr.acinq.lightning.transactions.Transactions.makeCommitTxOutputs
import fr.acinq.lightning.transactions.Transactions.offeredHtlcTrimThreshold
import fr.acinq.lightning.transactions.Transactions.receivedHtlcTrimThreshold
import fr.acinq.lightning.transactions.incomings
import fr.acinq.lightning.transactions.outgoings
import fr.acinq.lightning.utils.*
import fr.acinq.lightning.wire.*
import kotlin.math.min

/** Static channel parameters shared by all commitments. */
data class ChannelParams(
    val channelId: ByteVector32,
    val channelConfig: ChannelConfig,
    val channelFeatures: ChannelFeatures,
    val localParams: LocalChannelParams,
    val remoteParams: RemoteChannelParams,
    val channelFlags: ChannelFlags
) {
    init {
        require(channelConfig.hasOption(ChannelConfigOption.FundingPubKeyBasedChannelKeyPath)) { "FundingPubKeyBasedChannelKeyPath option must be enabled" }
    }

    fun updateFeatures(localInit: Init, remoteInit: Init) = this.copy(
        localParams = localParams.copy(features = localInit.features),
        remoteParams = remoteParams.copy(features = remoteInit.features)
    )
}

data class LocalChanges(val proposed: List<UpdateMessage>, val signed: List<UpdateMessage>, val acked: List<UpdateMessage>) {
    val all: List<UpdateMessage> get() = proposed + signed + acked
}

data class RemoteChanges(val proposed: List<UpdateMessage>, val acked: List<UpdateMessage>, val signed: List<UpdateMessage>) {
    val all: List<UpdateMessage> get() = proposed + signed + acked
}

/** Changes are applied to all commitments, and must be be valid for all commitments. */
data class CommitmentChanges(val localChanges: LocalChanges, val remoteChanges: RemoteChanges, val localNextHtlcId: Long, val remoteNextHtlcId: Long) {
    fun addLocalProposal(proposal: UpdateMessage): CommitmentChanges = copy(localChanges = localChanges.copy(proposed = localChanges.proposed + proposal))

    fun addRemoteProposal(proposal: UpdateMessage): CommitmentChanges = copy(remoteChanges = remoteChanges.copy(proposed = remoteChanges.proposed + proposal))

    fun localHasUnsignedOutgoingHtlcs(): Boolean = localChanges.proposed.find { it is UpdateAddHtlc } != null

    fun remoteHasUnsignedOutgoingHtlcs(): Boolean = remoteChanges.proposed.find { it is UpdateAddHtlc } != null

    fun localHasUnsignedOutgoingUpdateFee(): Boolean = localChanges.proposed.find { it is UpdateFee } != null

    fun remoteHasUnsignedOutgoingUpdateFee(): Boolean = remoteChanges.proposed.find { it is UpdateFee } != null

    fun localHasChanges(): Boolean = remoteChanges.acked.isNotEmpty() || localChanges.proposed.isNotEmpty()

    fun remoteHasChanges(): Boolean = localChanges.acked.isNotEmpty() || remoteChanges.proposed.isNotEmpty()

    companion object {
        fun init(): CommitmentChanges = CommitmentChanges(LocalChanges(listOf(), listOf(), listOf()), RemoteChanges(listOf(), listOf(), listOf()), 0, 0)

        fun alreadyProposed(changes: List<UpdateMessage>, id: Long): Boolean = changes.any {
            when (it) {
                is UpdateFulfillHtlc -> id == it.id
                is UpdateFailHtlc -> id == it.id
                is UpdateFailMalformedHtlc -> id == it.id
                else -> false
            }
        }
    }
}

sealed class ChannelSpendSignature {
    /** When using a 2-of-2 multisig, we need two individual ECDSA signatures. */
    data class IndividualSignature(val sig: ByteVector64) : ChannelSpendSignature()

    /** When using Musig2, we need two partial signatures and the signer's nonce. */
    data class PartialSignatureWithNonce(val partialSig: ByteVector32, val nonce: IndividualNonce) : ChannelSpendSignature()
}

/** The local commitment maps to a commitment transaction that we can sign and broadcast if necessary. */
data class LocalCommit(val index: Long, val spec: CommitmentSpec, val txId: TxId, val remoteSig: ChannelSpendSignature, val htlcRemoteSigs: List<ByteVector64>) {
    companion object {
        fun fromCommitSig(
            channelParams: ChannelParams,
            commitParams: CommitParams,
            commitKeys: LocalCommitmentKeys,
            fundingKey: PrivateKey,
            remoteFundingPubKey: PublicKey,
            commitInput: Transactions.InputInfo,
            commit: CommitSig,
            localCommitIndex: Long,
            commitmentFormat: Transactions.CommitmentFormat,
            spec: CommitmentSpec,
            log: MDCLogger
        ): Either<ChannelException, LocalCommit> {
            val (localCommitTx, sortedHtlcTxs) = Commitments.makeLocalTxs(
                channelParams = channelParams,
                commitParams = commitParams,
                commitKeys = commitKeys,
                commitTxNumber = localCommitIndex,
                localFundingKey = fundingKey,
                remoteFundingPubKey = remoteFundingPubKey,
                commitmentInput = commitInput,
                commitmentFormat = commitmentFormat,
                spec = spec,
            )
            if (!localCommitTx.checkRemoteSig(fundingKey.publicKey(), remoteFundingPubKey, commit.signature)) {
                log.error { "remote signature $commit is invalid" }
                return Either.Left(InvalidCommitmentSignature(channelParams.channelId, localCommitTx.tx.txid))
            }
            if (commit.htlcSignatures.size != sortedHtlcTxs.size) {
                return Either.Left(HtlcSigCountMismatch(channelParams.channelId, sortedHtlcTxs.size, commit.htlcSignatures.size))
            }
            sortedHtlcTxs.zip(commit.htlcSignatures).forEach { (htlcTx, remoteSig) ->
                if (!htlcTx.checkRemoteSig(commitKeys, remoteSig)) {
                    return Either.Left(InvalidHtlcSignature(channelParams.channelId, htlcTx.tx.txid))
                }
            }
            return Either.Right(LocalCommit(localCommitIndex, spec, localCommitTx.tx.txid, commit.signature, commit.htlcSignatures))
        }
    }
}

/** The remote commitment maps to a commitment transaction that only our peer can sign and broadcast. */
data class RemoteCommit(val index: Long, val spec: CommitmentSpec, val txid: TxId, val remotePerCommitmentPoint: PublicKey) {
    fun sign(
        channelParams: ChannelParams,
        commitParams: CommitParams,
        channelKeys: ChannelKeys,
        fundingTxIndex: Long,
        remoteFundingPubKey: PublicKey,
        commitInput: Transactions.InputInfo,
        commitmentFormat: Transactions.CommitmentFormat,
        batchSize: Int
    ): CommitSig {
        val fundingKey = channelKeys.fundingKey(fundingTxIndex)
        val commitKeys = channelKeys.remoteCommitmentKeys(channelParams, remotePerCommitmentPoint)
        val (remoteCommitTx, sortedHtlcsTxs) = Commitments.makeRemoteTxs(
            channelParams = channelParams,
            commitParams = commitParams,
            commitKeys = commitKeys,
            commitTxNumber = index,
            localFundingKey = fundingKey,
            remoteFundingPubKey = remoteFundingPubKey,
            commitmentInput = commitInput,
            commitmentFormat = commitmentFormat,
            spec = spec
        )
        val sig = remoteCommitTx.sign(fundingKey, remoteFundingPubKey)
        val htlcSigs = sortedHtlcsTxs.map { it.localSig(commitKeys) }
        val tlvs = buildSet<CommitSigTlv> {
            if (batchSize > 1) add(CommitSigTlv.Batch(batchSize))
        }
        return CommitSig(channelParams.channelId, sig, htlcSigs.toList(), TlvStream(tlvs))
    }

    fun sign(channelParams: ChannelParams, channelKeys: ChannelKeys, signingSession: InteractiveTxSigningSession): CommitSig {
        return sign(
            channelParams,
            signingSession.remoteCommitParams,
            channelKeys,
            signingSession.fundingTxIndex,
            signingSession.fundingParams.remoteFundingPubkey,
            signingSession.commitInput(channelKeys),
            signingSession.fundingParams.commitmentFormat,
            batchSize = 1
        )
    }
}

sealed class LocalFundingStatus {
    /** While the transaction is unconfirmed, we keep the funding transaction (if available) to allow rebroadcasting. */
    abstract val signedTx: Transaction?
    abstract val txId: TxId
    abstract val txOut: TxOut
    abstract val fee: Satoshi

    data class UnconfirmedFundingTx(val sharedTx: SignedSharedTransaction, val fundingParams: InteractiveTxParams, val createdAt: Long) : LocalFundingStatus() {
        override val signedTx: Transaction? = sharedTx.signedTx
        override val txId: TxId = sharedTx.localSigs.txId
        override val txOut: TxOut = TxOut(fundingParams.fundingAmount, sharedTx.tx.sharedOutput.pubkeyScript)
        override val fee: Satoshi = sharedTx.tx.fees
    }

    data class ConfirmedFundingTx(val spentInputs: List<OutPoint>, override val txOut: TxOut, override val fee: Satoshi, val localSigs: TxSignatures, val shortChannelId: ShortChannelId) : LocalFundingStatus() {
        override val txId: TxId = localSigs.txId
        override val signedTx: Transaction? = null
    }
}

sealed class RemoteFundingStatus {
    data object NotLocked : RemoteFundingStatus()
    data object Locked : RemoteFundingStatus()
}

/** A minimal commitment for a given funding tx. */
data class Commitment(
    val fundingTxIndex: Long,
    val fundingInput: OutPoint,
    val fundingAmount: Satoshi,
    val remoteFundingPubkey: PublicKey,
    val localFundingStatus: LocalFundingStatus,
    val remoteFundingStatus: RemoteFundingStatus,
    val commitmentFormat: Transactions.CommitmentFormat,
    val localCommitParams: CommitParams,
    val localCommit: LocalCommit,
    val remoteCommitParams: CommitParams,
    val remoteCommit: RemoteCommit,
    val nextRemoteCommit: RemoteCommit?
) {
    val fundingTxId: TxId = fundingInput.txid
    val shortChannelId: ShortChannelId? = when (localFundingStatus) {
        is LocalFundingStatus.ConfirmedFundingTx -> localFundingStatus.shortChannelId
        else -> null
    }

    fun localFundingKey(channelKeys: ChannelKeys): PrivateKey = channelKeys.fundingKey(fundingTxIndex)

    fun commitInput(fundingKey: PrivateKey): Transactions.InputInfo = Transactions.makeFundingInputInfo(fundingInput.txid, fundingInput.index, fundingAmount, fundingKey.publicKey(), remoteFundingPubkey, commitmentFormat)

    fun commitInput(channelKeys: ChannelKeys): Transactions.InputInfo = commitInput(localFundingKey(channelKeys))

    /** Return a fully signed commit tx, that can be published as-is. */
    fun fullySignedCommitTx(params: ChannelParams, channelKeys: ChannelKeys): Transaction {
        val fundingKey = localFundingKey(channelKeys)
        val commitKeys = channelKeys.localCommitmentKeys(params, localCommit.index)
        val (unsignedCommitTx, _) = Commitments.makeLocalTxs(params, localCommitParams, commitKeys, localCommit.index, fundingKey, remoteFundingPubkey, commitInput(channelKeys), commitmentFormat, localCommit.spec)
        return when (val remoteSig = localCommit.remoteSig) {
            is ChannelSpendSignature.IndividualSignature -> {
                val localSig = unsignedCommitTx.sign(fundingKey, remoteFundingPubkey)
                unsignedCommitTx.aggregateSigs(fundingKey.publicKey(), remoteFundingPubkey, localSig, remoteSig)
            }
            else -> throw IllegalArgumentException("not implemented") // FIXME
        }
    }

    /** Return the HTLC transactions for our local commit and the corresponding remote signatures. */
    fun unsignedHtlcTxs(params: ChannelParams, channelKeys: ChannelKeys): List<Pair<HtlcTx, ByteVector64>> {
        val fundingKey = channelKeys.fundingKey(fundingTxIndex)
        val commitKeys = channelKeys.localCommitmentKeys(params, localCommit.index)
        val (_, htlcTxs) = Commitments.makeLocalTxs(params, localCommitParams, commitKeys, localCommit.index, fundingKey, remoteFundingPubkey, commitInput(channelKeys), commitmentFormat, localCommit.spec)
        return htlcTxs.sortedBy { it.input.outPoint.index }.zip(localCommit.htlcRemoteSigs)
    }

    fun localChannelReserve(params: ChannelParams): Satoshi = when {
        params.channelFeatures.hasFeature(Feature.ZeroReserveChannels) -> 0.sat
        else -> (fundingAmount / 100).max(remoteCommitParams.dustLimit)
    }

    fun remoteChannelReserve(params: ChannelParams): Satoshi = when {
        params.channelFeatures.hasFeature(Feature.ZeroReserveChannels) -> 0.sat
        else -> (fundingAmount / 100).max(localCommitParams.dustLimit)
    }

    // NB: when computing availableBalanceForSend and availableBalanceForReceive, the initiator keeps an extra buffer on top
    // of its usual channel reserve to avoid getting channels stuck in case the on-chain feerate increases (see
    // https://github.com/lightningnetwork/lightning-rfc/issues/728 for details).
    //
    // This extra buffer (which we call "initiator fee buffer") is calculated as follows:
    //  1) Simulate a x2 feerate increase and compute the corresponding commit tx fee (note that it may trim some HTLCs)
    //  2) Add the cost of adding a new untrimmed HTLC at that increased feerate. This ensures that we'll be able to
    //     actually use the channel to add new HTLCs if the feerate doubles.
    //
    // If for example the current feerate is 1000 sat/kw, the dust limit 546 sat, and we have 3 pending outgoing HTLCs for
    // respectively 1250 sat, 2000 sat and 2500 sat.
    // commit tx fee = commitWeight * feerate + 3 * htlcOutputWeight * feerate = 724 * 1000 + 3 * 172 * 1000 = 1240 sat
    // To calculate the initiator fee buffer, we first double the feerate and calculate the corresponding commit tx fee.
    // By doubling the feerate, the first HTLC becomes trimmed so the result is: 724 * 2000 + 2 * 172 * 2000 = 2136 sat
    // We then add the additional fee for a potential new untrimmed HTLC: 172 * 2000 = 344 sat
    // The initiator fee buffer is 2136 + 344 = 2480 sat
    //
    // If there are many pending HTLCs that are only slightly above the trim threshold, the initiator fee buffer may be
    // smaller than the current commit tx fee because those HTLCs will be trimmed and the commit tx weight will decrease.
    // For example if we have 10 outgoing HTLCs of 1250 sat:
    //  - commit tx fee = 724 * 1000 + 10 * 172 * 1000 = 2444 sat
    //  - commit tx fee at twice the feerate = 724 * 2000 = 1448 sat (all HTLCs have been trimmed)
    //  - cost of an additional untrimmed HTLC = 172 * 2000 = 344 sat
    //  - initiator fee buffer = 1448 + 344 = 1792 sat
    // In that case the current commit tx fee is higher than the initiator fee buffer and will dominate the balance restrictions.

    fun availableBalanceForSend(params: ChannelParams, changes: CommitmentChanges): MilliSatoshi {
        // we need to base the next current commitment on the last sig we sent, even if we didn't yet receive their revocation
        val remoteCommit1 = nextRemoteCommit ?: remoteCommit
        val reduced = CommitmentSpec.reduce(remoteCommit1.spec, changes.remoteChanges.acked, changes.localChanges.proposed)
        val balanceNoFees = (reduced.toRemote - localChannelReserve(params).toMilliSatoshi()).coerceAtLeast(0.msat)
        return if (params.localParams.paysCommitTxFees) {
            // The initiator always pays the on-chain fees, so we must subtract that from the amount we can send.
            val commitFees = commitTxFeeMsat(remoteCommitParams.dustLimit, reduced, commitmentFormat)
            // the initiator needs to keep a "initiator fee buffer" (see explanation above)
            val initiatorFeeBuffer = commitTxFeeMsat(remoteCommitParams.dustLimit, reduced.copy(feerate = reduced.feerate * 2), commitmentFormat) + htlcOutputFee(reduced.feerate * 2, commitmentFormat)
            val amountToReserve = commitFees.coerceAtLeast(initiatorFeeBuffer)
            if (balanceNoFees - amountToReserve < offeredHtlcTrimThreshold(remoteCommitParams.dustLimit, reduced, commitmentFormat).toMilliSatoshi()) {
                // htlc will be trimmed
                (balanceNoFees - amountToReserve).coerceAtLeast(0.msat)
            } else {
                // htlc will have an output in the commitment tx, so there will be additional fees.
                val commitFees1 = commitFees + htlcOutputFee(reduced.feerate, commitmentFormat)
                // we take the additional fees for that htlc output into account in the fee buffer at a x2 feerate increase
                val initiatorFeeBuffer1 = initiatorFeeBuffer + htlcOutputFee(reduced.feerate * 2, commitmentFormat)
                val amountToReserve1 = commitFees1.coerceAtLeast(initiatorFeeBuffer1)
                (balanceNoFees - amountToReserve1).coerceAtLeast(0.msat)
            }
        } else {
            // The non-initiator doesn't pay on-chain fees.
            balanceNoFees
        }
    }

    fun availableBalanceForReceive(params: ChannelParams, changes: CommitmentChanges): MilliSatoshi {
        val reduced = CommitmentSpec.reduce(localCommit.spec, changes.localChanges.acked, changes.remoteChanges.proposed)
        val balanceNoFees = (reduced.toRemote - remoteChannelReserve(params).toMilliSatoshi()).coerceAtLeast(0.msat)
        return if (params.localParams.paysCommitTxFees) {
            // The non-initiator doesn't pay on-chain fees so we don't take those into account when receiving.
            balanceNoFees
        } else {
            // The initiator always pays the on-chain fees, so we must subtract that from the amount we can receive.
            val commitFees = commitTxFeeMsat(localCommitParams.dustLimit, reduced, commitmentFormat)
            // we expected the initiator to keep a "initiator fee buffer" (see explanation above)
            val initiatorFeeBuffer = commitTxFeeMsat(localCommitParams.dustLimit, reduced.copy(feerate = reduced.feerate * 2), commitmentFormat) + htlcOutputFee(reduced.feerate * 2, commitmentFormat)
            val amountToReserve = commitFees.coerceAtLeast(initiatorFeeBuffer)
            if (balanceNoFees - amountToReserve < receivedHtlcTrimThreshold(localCommitParams.dustLimit, reduced, commitmentFormat).toMilliSatoshi()) {
                // htlc will be trimmed
                (balanceNoFees - amountToReserve).coerceAtLeast(0.msat)
            } else {
                // htlc will have an output in the commitment tx, so there will be additional fees.
                val commitFees1 = commitFees + htlcOutputFee(reduced.feerate, commitmentFormat)
                // we take the additional fees for that htlc output into account in the fee buffer at a x2 feerate increase
                val initiatorFeeBuffer1 = initiatorFeeBuffer + htlcOutputFee(reduced.feerate * 2, commitmentFormat)
                val amountToReserve1 = commitFees1.coerceAtLeast(initiatorFeeBuffer1)
                (balanceNoFees - amountToReserve1).coerceAtLeast(0.msat)
            }
        }
    }

    fun hasNoPendingHtlcs(): Boolean = localCommit.spec.htlcs.isEmpty() && remoteCommit.spec.htlcs.isEmpty() && nextRemoteCommit == null

    fun hasNoPendingHtlcsOrFeeUpdate(changes: CommitmentChanges): Boolean {
        val hasNoPendingFeeUpdate = (changes.localChanges.signed + changes.localChanges.acked + changes.remoteChanges.signed + changes.remoteChanges.acked).find { it is UpdateFee } == null
        return hasNoPendingHtlcs() && hasNoPendingFeeUpdate
    }

    fun timedOutOutgoingHtlcs(blockHeight: Long): Set<UpdateAddHtlc> {
        fun expired(add: UpdateAddHtlc) = blockHeight >= add.cltvExpiry.toLong()

        val thisCommitAdds = localCommit.spec.htlcs.outgoings().filter(::expired).toSet() + remoteCommit.spec.htlcs.incomings().filter(::expired).toSet()
        return when (nextRemoteCommit) {
            null -> thisCommitAdds
            else -> thisCommitAdds + nextRemoteCommit.spec.htlcs.incomings().filter(::expired).toSet()
        }
    }

    /**
     * Incoming HTLCs that are close to timing out are potentially dangerous. If we released the pre-image for those
     * HTLCs, we need to get a remote signed updated commitment that removes this HTLC.
     * Otherwise when we get close to the timeout, we risk an on-chain race condition between their HTLC timeout
     * and our HTLC success in case of a force-close.
     */
    fun almostTimedOutIncomingHtlcs(blockHeight: Long, fulfillSafety: CltvExpiryDelta, changes: CommitmentChanges): Set<UpdateAddHtlc> {
        val relayedFulfills = changes.localChanges.all.filterIsInstance<UpdateFulfillHtlc>().map { it.id }.toSet()
        return localCommit.spec.htlcs.incomings().filter { relayedFulfills.contains(it.id) && blockHeight >= (it.cltvExpiry - fulfillSafety).toLong() }.toSet()
    }

    fun getOutgoingHtlcCrossSigned(htlcId: Long): UpdateAddHtlc? {
        val localSigned = (nextRemoteCommit ?: remoteCommit).spec.findIncomingHtlcById(htlcId) ?: return null
        val remoteSigned = localCommit.spec.findOutgoingHtlcById(htlcId) ?: return null
        require(localSigned.add == remoteSigned.add)
        return localSigned.add
    }

    fun getIncomingHtlcCrossSigned(htlcId: Long): UpdateAddHtlc? {
        val localSigned = (nextRemoteCommit ?: remoteCommit).spec.findOutgoingHtlcById(htlcId) ?: return null
        val remoteSigned = localCommit.spec.findIncomingHtlcById(htlcId) ?: return null
        require(localSigned.add == remoteSigned.add)
        return localSigned.add
    }

    fun canSendAdd(amount: MilliSatoshi, params: ChannelParams, changes: CommitmentChanges): Either<ChannelException, Unit> {
        // we need to base the next current commitment on the last sig we sent, even if we didn't yet receive their revocation
        val remoteCommit1 = nextRemoteCommit ?: remoteCommit
        val reduced = CommitmentSpec.reduce(remoteCommit1.spec, changes.remoteChanges.acked, changes.localChanges.proposed)
        // the HTLC we are about to create is outgoing, but from their point of view it is incoming
        val outgoingHtlcs = reduced.htlcs.incomings()

        // note that the initiator pays the fee, so if sender != initiator, both sides will have to afford this payment
        val fees = commitTxFee(remoteCommitParams.dustLimit, reduced, commitmentFormat)
        // the initiator needs to keep an extra buffer to be able to handle a x2 feerate increase and an additional htlc to avoid
        // getting the channel stuck (see https://github.com/lightningnetwork/lightning-rfc/issues/728).
        val initiatorFeeBuffer = commitTxFeeMsat(remoteCommitParams.dustLimit, reduced.copy(feerate = reduced.feerate * 2), commitmentFormat) + htlcOutputFee(reduced.feerate * 2, commitmentFormat)
        // NB: increasing the feerate can actually remove htlcs from the commit tx (if they fall below the trim threshold)
        // which may result in a lower commit tx fee; this is why we take the max of the two.
        val missingForSender = reduced.toRemote - localChannelReserve(params).toMilliSatoshi() - (if (params.localParams.paysCommitTxFees) fees.toMilliSatoshi().coerceAtLeast(initiatorFeeBuffer) else 0.msat)
        // According to BOLT 2, we should also subtract the channel reserve from the calculation below.
        // But this creates issues with splicing in the following scenario:
        //  - Alice opened a channel to Bob, and her balance is slightly above the reserve
        //  - Bob splices some funds in, which increases the size of the reserve since it is set to 1% by default
        //  - Alice is now below her reserve, so Bob is unable to send her any HTLC
        //  - The liquidity is mostly on Bob's side, but since he's unable to send HTLCs the channel is stuck
        // We instead only check that the channel initiator is able to pay the fees for the commit tx.
        // We are sending an outgoing HTLC, so once it's fulfilled it will increase their balance which is good for the channel reserve.
        val missingForReceiver = reduced.toLocal - (if (params.localParams.paysCommitTxFees) 0.msat else fees.toMilliSatoshi())
        if (missingForSender < 0.msat) {
            val actualFees = if (params.localParams.paysCommitTxFees) fees else 0.sat
            return Either.Left(InsufficientFunds(params.channelId, amount, -missingForSender.truncateToSatoshi(), localChannelReserve(params), actualFees))
        } else if (missingForReceiver < 0.msat) {
            if (params.localParams.paysCommitTxFees) {
                // receiver is not the initiator; it is ok if it can't maintain its channel_reserve for now, as long as its balance is increasing, which is the case if it is receiving a payment
            } else {
                return Either.Left(RemoteCannotAffordFeesForNewHtlc(params.channelId, amount = amount, missing = -missingForReceiver.truncateToSatoshi(), fees = fees))
            }
        }

        // README: we check against our peer's max_htlc_value_in_flight_msat parameter, as per the BOLTS, but also against our own setting
        val htlcValueInFlight = outgoingHtlcs.map { it.amountMsat }.sum()
        val maxHtlcValueInFlightMsat = min(remoteCommitParams.maxHtlcValueInFlightMsat, localCommitParams.maxHtlcValueInFlightMsat)
        if (htlcValueInFlight.toLong() > maxHtlcValueInFlightMsat) {
            return Either.Left(HtlcValueTooHighInFlight(params.channelId, maximum = maxHtlcValueInFlightMsat.toULong(), actual = htlcValueInFlight))
        }

        if (outgoingHtlcs.size > remoteCommitParams.maxAcceptedHtlcs) {
            return Either.Left(TooManyAcceptedHtlcs(params.channelId, maximum = remoteCommitParams.maxAcceptedHtlcs.toLong()))
        }

        // README: this is not part of the LN Bolts: we also check against our own limit, to avoid creating commit txs that have too many outputs
        if (outgoingHtlcs.size > localCommitParams.maxAcceptedHtlcs) {
            return Either.Left(TooManyOfferedHtlcs(params.channelId, maximum = localCommitParams.maxAcceptedHtlcs.toLong()))
        }

        return Either.Right(Unit)
    }

    fun canReceiveAdd(amount: MilliSatoshi, params: ChannelParams, changes: CommitmentChanges): Either<ChannelException, Unit> {
        // let's compute the current commitment *as seen by us* including this change
        val reduced = CommitmentSpec.reduce(localCommit.spec, changes.localChanges.acked, changes.remoteChanges.proposed)
        val incomingHtlcs = reduced.htlcs.incomings()

        // note that the initiator pays the fee, so if sender != initiator, both sides will have to afford this payment
        val fees = commitTxFee(localCommitParams.dustLimit, reduced, commitmentFormat)
        // NB: we don't enforce the initiatorFeeReserve (see sendAdd) because it would confuse a remote initiator that doesn't have this mitigation in place
        // We could enforce it once we're confident a large portion of the network implements it.
        val missingForSender = reduced.toRemote - remoteChannelReserve(params).toMilliSatoshi() - (if (params.localParams.paysCommitTxFees) 0.sat else fees).toMilliSatoshi()
        // We diverge from Bolt 2 and don't subtract the channel reserve: see `canSendAdd` for details.
        val missingForReceiver = reduced.toLocal - (if (params.localParams.paysCommitTxFees) fees else 0.sat).toMilliSatoshi()
        if (missingForSender < 0.sat) {
            val actualFees = if (params.localParams.paysCommitTxFees) 0.sat else fees
            return Either.Left(InsufficientFunds(params.channelId, amount, -missingForSender.truncateToSatoshi(), remoteChannelReserve(params), actualFees))
        } else if (missingForReceiver < 0.sat) {
            if (params.localParams.paysCommitTxFees) {
                return Either.Left(CannotAffordFees(params.channelId, missing = -missingForReceiver.truncateToSatoshi(), reserve = localChannelReserve(params), fees = fees))
            } else {
                // receiver is not the initiator; it is ok if it can't maintain its channel_reserve for now, as long as its balance is increasing, which is the case if it is receiving a payment
            }
        }

        val htlcValueInFlight = incomingHtlcs.map { it.amountMsat }.sum()
        if (localCommitParams.maxHtlcValueInFlightMsat < htlcValueInFlight.toLong()) {
            return Either.Left(HtlcValueTooHighInFlight(params.channelId, maximum = localCommitParams.maxHtlcValueInFlightMsat.toULong(), actual = htlcValueInFlight))
        }

        if (incomingHtlcs.size > localCommitParams.maxAcceptedHtlcs) {
            return Either.Left(TooManyAcceptedHtlcs(params.channelId, maximum = localCommitParams.maxAcceptedHtlcs.toLong()))
        }

        return Either.Right(Unit)
    }

    fun canSendFee(params: ChannelParams, changes: CommitmentChanges): Either<ChannelException, Unit> {
        val reduced = CommitmentSpec.reduce(remoteCommit.spec, changes.remoteChanges.acked, changes.localChanges.proposed)
        // a node cannot spend pending incoming htlcs, and need to keep funds above the reserve required by the counterparty, after paying the fee
        // we look from remote's point of view, so if local is initiator remote doesn't pay the fees
        val fees = commitTxFee(remoteCommitParams.dustLimit, reduced, commitmentFormat)
        val missing = reduced.toRemote.truncateToSatoshi() - localChannelReserve(params) - fees
        return if (missing < 0.sat) {
            Either.Left(CannotAffordFees(params.channelId, -missing, localChannelReserve(params), fees))
        } else {
            Either.Right(Unit)
        }
    }

    fun canReceiveFee(params: ChannelParams, changes: CommitmentChanges): Either<ChannelException, Unit> {
        // let's compute the current commitment *as seen by us* including this change
        // update_fee replace each other, so we can remove previous ones
        val reduced = CommitmentSpec.reduce(localCommit.spec, changes.localChanges.acked, changes.remoteChanges.proposed)
        // NB: we check that the initiator can afford this new fee even if spec allows to do it at next signature
        // It is easier to do it here because under certain (race) conditions spec allows a lower-than-normal fee to be paid,
        // and it would be tricky to check if the conditions are met at signing
        // (it also means that we need to check the fee of the initial commitment tx somewhere)
        val fees = commitTxFee(localCommitParams.dustLimit, reduced, commitmentFormat)
        val missing = reduced.toRemote.truncateToSatoshi() - remoteChannelReserve(params) - fees
        return if (missing < 0.sat) {
            Either.Left(CannotAffordFees(params.channelId, -missing, remoteChannelReserve(params), fees))
        } else {
            Either.Right(Unit)
        }
    }

    fun sendCommit(params: ChannelParams, channelKeys: ChannelKeys, commitKeys: RemoteCommitmentKeys, changes: CommitmentChanges, remoteNextPerCommitmentPoint: PublicKey, batchSize: Int, log: MDCLogger): Pair<Commitment, CommitSig> {
        val fundingKey = localFundingKey(channelKeys)
        // remote commitment will include all local changes + remote acked changes
        val spec = CommitmentSpec.reduce(remoteCommit.spec, changes.remoteChanges.acked, changes.localChanges.proposed)
        val (remoteCommitTx, sortedHtlcTxs) = Commitments.makeRemoteTxs(
            channelParams = params,
            commitParams = remoteCommitParams,
            commitKeys = commitKeys,
            commitTxNumber = remoteCommit.index + 1,
            localFundingKey = fundingKey,
            remoteFundingPubKey = remoteFundingPubkey,
            commitmentInput = commitInput(fundingKey),
            commitmentFormat = commitmentFormat,
            spec = spec
        )
        val sig = remoteCommitTx.sign(fundingKey, remoteFundingPubkey)
        val htlcSigs = sortedHtlcTxs.map { it.localSig(commitKeys) }

        // NB: IN/OUT htlcs are inverted because this is the remote commit
        log.info {
            val htlcsIn = spec.htlcs.outgoings().map { it.id }.joinToString(",")
            val htlcsOut = spec.htlcs.incomings().map { it.id }.joinToString(",")
            "built remote commit number=${remoteCommit.index + 1} toLocalMsat=${spec.toLocal.toLong()} toRemoteMsat=${spec.toRemote.toLong()} htlc_in=$htlcsIn htlc_out=$htlcsOut feeratePerKw=${spec.feerate} txId=${remoteCommitTx.tx.txid} fundingTxId=$fundingTxId"
        }

        val tlvs = buildSet {
            if (spec.htlcs.isEmpty()) {
                val alternativeSigs = Commitments.alternativeFeerates.map { feerate ->
                    val alternativeSpec = spec.copy(feerate = feerate)
                    val (alternativeRemoteCommitTx, _) = Commitments.makeRemoteTxs(
                        channelParams = params,
                        commitParams = remoteCommitParams,
                        commitKeys = commitKeys,
                        commitTxNumber = remoteCommit.index + 1,
                        localFundingKey = fundingKey,
                        remoteFundingPubKey = remoteFundingPubkey,
                        commitmentInput = commitInput(fundingKey),
                        commitmentFormat = commitmentFormat,
                        spec = alternativeSpec
                    )
                    val alternativeSig = alternativeRemoteCommitTx.sign(fundingKey, remoteFundingPubkey).sig
                    CommitSigTlv.AlternativeFeerateSig(feerate, alternativeSig)
                }
                add(CommitSigTlv.AlternativeFeerateSigs(alternativeSigs))
            }
            if (batchSize > 1) {
                add(CommitSigTlv.Batch(batchSize))
            }
        }
        val commitSig = CommitSig(params.channelId, sig, htlcSigs.toList(), TlvStream(tlvs))
        val commitment1 = copy(nextRemoteCommit = RemoteCommit(remoteCommit.index + 1, spec, remoteCommitTx.tx.txid, remoteNextPerCommitmentPoint))
        return Pair(commitment1, commitSig)
    }

    fun receiveCommit(params: ChannelParams, channelKeys: ChannelKeys, commitKeys: LocalCommitmentKeys, changes: CommitmentChanges, commit: CommitSig, log: MDCLogger): Either<ChannelException, Commitment> {
        // they sent us a signature for *their* view of *our* next commit tx
        // so in terms of rev.hashes and indexes we have:
        // ourCommit.index -> our current revocation hash, which is about to become our old revocation hash
        // ourCommit.index + 1 -> our next revocation hash, used by *them* to build the sig we've just received, and which
        // is about to become our current revocation hash
        // ourCommit.index + 2 -> which is about to become our next revocation hash
        // we will reply to this sig with our old revocation hash preimage (at index) and our next revocation hash (at index + 1)
        // and will increment our index
        val fundingKey = localFundingKey(channelKeys)
        val spec = CommitmentSpec.reduce(localCommit.spec, changes.localChanges.acked, changes.remoteChanges.proposed)
        return LocalCommit.fromCommitSig(params, localCommitParams, commitKeys, fundingKey, remoteFundingPubkey, commitInput(fundingKey), commit, localCommit.index + 1, commitmentFormat, spec, log).map { localCommit1 ->
            log.info {
                val htlcsIn = spec.htlcs.incomings().map { it.id }.joinToString(",")
                val htlcsOut = spec.htlcs.outgoings().map { it.id }.joinToString(",")
                "built local commit number=${localCommit.index + 1} toLocalMsat=${spec.toLocal.toLong()} toRemoteMsat=${spec.toRemote.toLong()} htlc_in=$htlcsIn htlc_out=$htlcsOut feeratePerKw=${spec.feerate} txid=${localCommit1.txId} fundingTxId=$fundingTxId"
            }
            copy(localCommit = localCommit1)
        }
    }
}

/** Subset of Commitments when we want to work with a single, specific commitment. */
data class FullCommitment(val channelParams: ChannelParams, val changes: CommitmentChanges, val commitment: Commitment) {
    val channelId: ByteVector32 = channelParams.channelId
    val remoteFundingPubkey: PublicKey = commitment.remoteFundingPubkey
    val fundingTxId: TxId = commitment.fundingTxId
    val fundingAmount: Satoshi = commitment.fundingAmount
    val fundingInput: OutPoint = commitment.fundingInput
    val fundingTxIndex: Long = commitment.fundingTxIndex
    val localFundingStatus: LocalFundingStatus = commitment.localFundingStatus
    val remoteFundingStatus: RemoteFundingStatus = commitment.remoteFundingStatus
    val commitmentFormat: Transactions.CommitmentFormat = commitment.commitmentFormat
    val localChannelParams: LocalChannelParams = channelParams.localParams
    val localCommitParams: CommitParams = commitment.localCommitParams
    val localCommit: LocalCommit = commitment.localCommit
    val remoteChannelParams: RemoteChannelParams = channelParams.remoteParams
    val remoteCommitParams: CommitParams = commitment.remoteCommitParams
    val remoteCommit: RemoteCommit = commitment.remoteCommit
    val nextRemoteCommit: RemoteCommit? = commitment.nextRemoteCommit
    val localChannelReserve: Satoshi = when {
        channelParams.channelFeatures.hasFeature(Feature.ZeroReserveChannels) -> 0.sat
        else -> (fundingAmount / 100).max(remoteCommitParams.dustLimit)
    }
    val remoteChannelReserve: Satoshi = when {
        channelParams.channelFeatures.hasFeature(Feature.ZeroReserveChannels) -> 0.sat
        else -> (fundingAmount / 100).max(localCommitParams.dustLimit)
    }

    fun localKeys(channelKeys: ChannelKeys): LocalCommitmentKeys = channelKeys.localCommitmentKeys(channelParams, localCommit.index)

    fun remoteKeys(channelKeys: ChannelKeys, remotePerCommitmentPoint: PublicKey): RemoteCommitmentKeys = channelKeys.remoteCommitmentKeys(channelParams, remotePerCommitmentPoint)

    fun commitInput(channelKeys: ChannelKeys): Transactions.InputInfo = commitment.commitInput(channelKeys)

    /** Return a fully signed commit tx, that can be published as-is. */
    fun fullySignedCommitTx(channelKeys: ChannelKeys): Transaction = commitment.fullySignedCommitTx(channelParams, channelKeys)

    /** Return the HTLC transactions for our local commit and the corresponding remote signatures. */
    fun unsignedHtlcTxs(channelKeys: ChannelKeys): List<Pair<HtlcTx, ByteVector64>> = commitment.unsignedHtlcTxs(channelParams, channelKeys)
}

data class WaitingForRevocation(val sentAfterLocalCommitIndex: Long)

data class Commitments(
    val channelParams: ChannelParams,
    val changes: CommitmentChanges,
    val active: List<Commitment>,
    val inactive: List<Commitment>,
    val payments: Map<Long, UUID>, // for outgoing htlcs, maps to paymentId
    val remoteNextCommitInfo: Either<WaitingForRevocation, PublicKey>, // this one is tricky, it must be kept in sync with Commitment.nextRemoteCommit
    val remotePerCommitmentSecrets: ShaChain,
) {
    init {
        require(active.isNotEmpty()) { "there must be at least one active commitment" }
    }

    val channelId: ByteVector32 = channelParams.channelId
    val localNodeId: PublicKey = channelParams.localParams.nodeId
    val remoteNodeId: PublicKey = channelParams.remoteParams.nodeId

    // Commitment numbers are the same for all active commitments.
    val localCommitIndex = active.first().localCommit.index
    val remoteCommitIndex = active.first().remoteCommit.index
    val nextRemoteCommitIndex = remoteCommitIndex + 1

    fun availableBalanceForSend(): MilliSatoshi = active.minOf { it.availableBalanceForSend(channelParams, changes) }
    fun availableBalanceForReceive(): MilliSatoshi = active.minOf { it.availableBalanceForReceive(channelParams, changes) }

    // We always use the last commitment that was created, to make sure we never go back in time.
    val latest = FullCommitment(channelParams, changes, active.first())

    val all = buildList {
        addAll(active)
        addAll(inactive)
    }

    fun add(commitment: Commitment): Commitments = copy(active = buildList {
        add(commitment)
        addAll(active)
    })

    fun channelKeys(keyManager: KeyManager): ChannelKeys = channelParams.localParams.channelKeys(keyManager)

    fun isMoreRecent(other: Commitments): Boolean {
        return this.localCommitIndex > other.localCommitIndex ||
                this.remoteCommitIndex > other.remoteCommitIndex ||
                (this.remoteCommitIndex == other.remoteCommitIndex && this.remoteNextCommitInfo.isLeft && other.remoteNextCommitInfo.isRight) ||
                this.latest.fundingTxIndex > other.latest.fundingTxIndex
    }

    // @formatter:off
    fun localIsQuiescent(): Boolean = changes.localChanges.all.isEmpty()
    fun remoteIsQuiescent(): Boolean = changes.remoteChanges.all.isEmpty()
    fun isQuiescent(): Boolean = localIsQuiescent() && remoteIsQuiescent()
    // HTLCs and pending changes are the same for all active commitments, so we don't need to loop through all of them.
    fun hasNoPendingHtlcsOrFeeUpdate(): Boolean = active.first().hasNoPendingHtlcsOrFeeUpdate(changes)
    fun timedOutOutgoingHtlcs(currentHeight: Long): Set<UpdateAddHtlc> = active.first().timedOutOutgoingHtlcs(currentHeight)
    fun almostTimedOutIncomingHtlcs(currentHeight: Long, fulfillSafety: CltvExpiryDelta): Set<UpdateAddHtlc> = active.first().almostTimedOutIncomingHtlcs(currentHeight, fulfillSafety, changes)
    fun getOutgoingHtlcCrossSigned(htlcId: Long): UpdateAddHtlc? = active.first().getOutgoingHtlcCrossSigned(htlcId)
    fun getIncomingHtlcCrossSigned(htlcId: Long): UpdateAddHtlc? = active.first().getIncomingHtlcCrossSigned(htlcId)
    // @formatter:on

    /**
     * Whenever we're not sure the `IncomingPaymentHandler` has received our previous `ChannelAction.ProcessIncomingHtlcs`,
     * or when we may have ignored the responses from the `IncomingPaymentHandler` (eg. while quiescent or disconnected),
     * we need to reprocess those incoming HTLCs.
     */
    fun reprocessIncomingHtlcs(): List<ChannelAction.ProcessIncomingHtlc> {
        // We are interested in incoming HTLCs, that have been *cross-signed* (otherwise they wouldn't have been forwarded to the payment handler).
        // They signed it first, so the HTLC will first appear in our commitment tx, and later on in their commitment when we subsequently sign it.
        // That's why we need to look in *their* commitment with direction=OUT.
        //
        // We also need to filter out htlcs that we already settled and signed (the settlement messages are being retransmitted).
        val alreadySettled = changes.localChanges.signed.filterIsInstance<HtlcSettlementMessage>().map { it.id }.toSet()
        return latest.remoteCommit.spec.htlcs.outgoings().filter { !alreadySettled.contains(it.id) }.map { ChannelAction.ProcessIncomingHtlc(it) }
    }

    fun sendAdd(cmd: ChannelCommand.Htlc.Add, paymentId: UUID, blockHeight: Long): Either<ChannelException, Pair<Commitments, UpdateAddHtlc>> {
        val maxExpiry = Channel.MAX_CLTV_EXPIRY_DELTA.toCltvExpiry(blockHeight)
        // we don't want to use too high a refund timeout, because our funds will be locked during that time if the payment is never fulfilled
        if (cmd.cltvExpiry >= maxExpiry) {
            return Either.Left(ExpiryTooBig(channelId, maximum = maxExpiry, actual = cmd.cltvExpiry, blockCount = blockHeight))
        }

        // even if remote advertises support for 0 msat htlc, we limit ourselves to values strictly positive, hence the max(1 msat)
        val htlcMinimum = active.minOf { it.remoteCommitParams.htlcMinimum }.coerceAtLeast(1.msat)
        if (cmd.amount < htlcMinimum) {
            return Either.Left(HtlcValueTooSmall(channelId, minimum = htlcMinimum, actual = cmd.amount))
        }

        // let's compute the current commitment *as seen by them* with this change taken into account
        val add = UpdateAddHtlc(channelId, changes.localNextHtlcId, cmd.amount, cmd.paymentHash, cmd.cltvExpiry, cmd.onion)
        // we increment the local htlc index and add an entry to the origins map
        val changes1 = changes.addLocalProposal(add).copy(localNextHtlcId = changes.localNextHtlcId + 1)
        val payments1 = payments + mapOf(add.id to paymentId)
        val failure = active.map { it.canSendAdd(cmd.amount, channelParams, changes1).left }.firstOrNull()
        return failure?.let { Either.Left(it) } ?: Either.Right(Pair(copy(changes = changes1, payments = payments1), add))
    }

    fun receiveAdd(add: UpdateAddHtlc): Either<ChannelException, Commitments> {
        if (add.id != changes.remoteNextHtlcId) {
            return Either.Left(UnexpectedHtlcId(channelId, expected = changes.remoteNextHtlcId, actual = add.id))
        }

        // we used to not enforce a strictly positive minimum, hence the max(1 msat)
        val htlcMinimum = active.minOf { it.localCommitParams.htlcMinimum }.coerceAtLeast(1.msat)
        if (add.amountMsat < htlcMinimum) {
            return Either.Left(HtlcValueTooSmall(channelId, minimum = htlcMinimum, actual = add.amountMsat))
        }

        val changes1 = changes.addRemoteProposal(add).copy(remoteNextHtlcId = changes.remoteNextHtlcId + 1)
        val failure = active.map { it.canReceiveAdd(add.amountMsat, channelParams, changes1).left }.firstOrNull()
        return failure?.let { Either.Left(it) } ?: Either.Right(copy(changes = changes1))
    }

    fun sendFulfill(cmd: ChannelCommand.Htlc.Settlement.Fulfill): Either<ChannelException, Pair<Commitments, UpdateFulfillHtlc>> {
        val htlc = getIncomingHtlcCrossSigned(cmd.id) ?: return Either.Left(UnknownHtlcId(channelId, cmd.id))
        return when {
            // we have already sent a fail/fulfill for this htlc
            CommitmentChanges.alreadyProposed(changes.localChanges.proposed, htlc.id) -> Either.Left(UnknownHtlcId(channelId, cmd.id))
            htlc.paymentHash.contentEquals(sha256(cmd.r)) -> {
                val fulfill = UpdateFulfillHtlc(channelId, cmd.id, cmd.r)
                Either.Right(Pair(copy(changes = changes.addLocalProposal(fulfill)), fulfill))
            }
            else -> Either.Left(InvalidHtlcPreimage(channelId, cmd.id))
        }
    }

    fun receiveFulfill(fulfill: UpdateFulfillHtlc): Either<ChannelException, Triple<Commitments, UUID, UpdateAddHtlc>> {
        val htlc = getOutgoingHtlcCrossSigned(fulfill.id) ?: return Either.Left(UnknownHtlcId(channelId, fulfill.id))
        val paymentId = payments[fulfill.id] ?: return Either.Left(UnknownHtlcId(channelId, fulfill.id))
        return when {
            htlc.paymentHash.contentEquals(sha256(fulfill.paymentPreimage)) -> Either.Right(Triple(copy(changes = changes.addRemoteProposal(fulfill)), paymentId, htlc))
            else -> Either.Left(InvalidHtlcPreimage(channelId, fulfill.id))
        }
    }

    fun sendFail(cmd: ChannelCommand.Htlc.Settlement.Fail, nodeSecret: PrivateKey): Either<ChannelException, Pair<Commitments, UpdateFailHtlc>> {
        val htlc = getIncomingHtlcCrossSigned(cmd.id) ?: return Either.Left(UnknownHtlcId(channelId, cmd.id))
        return when {
            // we have already sent a fail/fulfill for this htlc
            CommitmentChanges.alreadyProposed(changes.localChanges.proposed, htlc.id) -> Either.Left(UnknownHtlcId(channelId, cmd.id))
            else -> {
                when (val result = OutgoingPaymentPacket.buildHtlcFailure(nodeSecret, htlc.paymentHash, htlc.onionRoutingPacket, htlc.pathKey, cmd.reason)) {
                    is Either.Right -> {
                        val fail = UpdateFailHtlc(channelId, cmd.id, result.value)
                        Either.Right(Pair(copy(changes = changes.addLocalProposal(fail)), fail))
                    }
                    is Either.Left -> Either.Left(CannotExtractSharedSecret(channelId, htlc))
                }
            }
        }
    }

    fun sendFailMalformed(cmd: ChannelCommand.Htlc.Settlement.FailMalformed): Either<ChannelException, Pair<Commitments, UpdateFailMalformedHtlc>> {
        // BADONION bit must be set in failure_code
        if ((cmd.failureCode and FailureMessage.BADONION) == 0) return Either.Left(InvalidFailureCode(channelId))
        val htlc = getIncomingHtlcCrossSigned(cmd.id) ?: return Either.Left(UnknownHtlcId(channelId, cmd.id))
        return when {
            // we have already sent a fail/fulfill for this htlc
            CommitmentChanges.alreadyProposed(changes.localChanges.proposed, htlc.id) -> Either.Left(UnknownHtlcId(channelId, cmd.id))
            else -> {
                val fail = UpdateFailMalformedHtlc(channelId, cmd.id, cmd.onionHash, cmd.failureCode)
                Either.Right(Pair(copy(changes = changes.addLocalProposal(fail)), fail))
            }
        }
    }

    fun receiveFail(fail: UpdateFailHtlc): Either<ChannelException, Triple<Commitments, UUID, UpdateAddHtlc>> {
        val htlc = getOutgoingHtlcCrossSigned(fail.id) ?: return Either.Left(UnknownHtlcId(channelId, fail.id))
        val paymentId = payments[fail.id] ?: return Either.Left(UnknownHtlcId(channelId, fail.id))
        return Either.Right(Triple(copy(changes = changes.addRemoteProposal(fail)), paymentId, htlc))
    }

    fun receiveFailMalformed(fail: UpdateFailMalformedHtlc): Either<ChannelException, Triple<Commitments, UUID, UpdateAddHtlc>> {
        // A receiving node MUST fail the channel if the BADONION bit in failure_code is not set for update_fail_malformed_htlc.
        if ((fail.failureCode and FailureMessage.BADONION) == 0) return Either.Left(InvalidFailureCode(channelId))
        val htlc = getOutgoingHtlcCrossSigned(fail.id) ?: return Either.Left(UnknownHtlcId(channelId, fail.id))
        val paymentId = payments[fail.id] ?: return Either.Left(UnknownHtlcId(channelId, fail.id))
        return Either.Right(Triple(copy(changes = changes.addRemoteProposal(fail)), paymentId, htlc))
    }

    fun sendFee(cmd: ChannelCommand.Commitment.UpdateFee): Either<ChannelException, Pair<Commitments, UpdateFee>> {
        if (!channelParams.localParams.paysCommitTxFees) return Either.Left(NonInitiatorCannotSendUpdateFee(channelId))
        // let's compute the current commitment *as seen by them* with this change taken into account
        val fee = UpdateFee(channelId, cmd.feerate)
        // update_fee replace each other, so we can remove previous ones
        val changes1 = changes.copy(localChanges = changes.localChanges.copy(proposed = changes.localChanges.proposed.filterNot { it is UpdateFee } + fee))
        val failure = active.map { it.canSendFee(channelParams, changes1).left }.firstOrNull()
        return failure?.let { Either.Left(it) } ?: Either.Right(Pair(copy(changes = changes1), fee))
    }

    fun receiveFee(fee: UpdateFee, feerateTolerance: FeerateTolerance): Either<ChannelException, Commitments> {
        if (channelParams.localParams.paysCommitTxFees) return Either.Left(NonInitiatorCannotSendUpdateFee(channelId))
        if (fee.feeratePerKw < FeeratePerKw.MinimumFeeratePerKw) return Either.Left(FeerateTooSmall(channelId, remoteFeeratePerKw = fee.feeratePerKw))
        if (Helpers.isFeeDiffTooHigh(FeeratePerKw.CommitmentFeerate, fee.feeratePerKw, feerateTolerance)) return Either.Left(FeerateTooDifferent(channelId, FeeratePerKw.CommitmentFeerate, fee.feeratePerKw))
        val changes1 = changes.copy(remoteChanges = changes.remoteChanges.copy(proposed = changes.remoteChanges.proposed.filterNot { it is UpdateFee } + fee))
        val failure = active.map { it.canReceiveFee(channelParams, changes1).left }.firstOrNull()
        return failure?.let { Either.Left(it) } ?: Either.Right(copy(changes = changes1))
    }

    fun sendCommit(channelKeys: ChannelKeys, log: MDCLogger): Either<ChannelException, Pair<Commitments, CommitSigs>> {
        val remoteNextPerCommitmentPoint = remoteNextCommitInfo.right ?: return Either.Left(CannotSignBeforeRevocation(channelId))
        val commitKeys = channelKeys.remoteCommitmentKeys(channelParams, remoteNextPerCommitmentPoint)
        if (!changes.localHasChanges()) return Either.Left(CannotSignWithoutChanges(channelId))
        val (active1, sigs) = active.map { it.sendCommit(channelParams, channelKeys, commitKeys, changes, remoteNextPerCommitmentPoint, active.size, log) }.unzip()
        val commitments1 = copy(
            active = active1,
            remoteNextCommitInfo = Either.Left(WaitingForRevocation(localCommitIndex)),
            changes = changes.copy(
                localChanges = changes.localChanges.copy(proposed = emptyList(), signed = changes.localChanges.proposed),
                remoteChanges = changes.remoteChanges.copy(acked = emptyList(), signed = changes.remoteChanges.acked)
            )
        )
        return Either.Right(Pair(commitments1, CommitSigs.fromSigs(sigs)))
    }

    fun receiveCommit(commits: CommitSigs, channelKeys: ChannelKeys, log: MDCLogger): Either<ChannelException, Pair<Commitments, RevokeAndAck>> {
        // We may receive more commit_sig than the number of active commitments, because there can be a race where we send splice_locked
        // while our peer is sending us a batch of commit_sig. When that happens, we simply need to discard the commit_sig that belong
        // to commitments we deactivated.
        val sigs = when (commits) {
            is CommitSigBatch -> {
                if (commits.batchSize < active.size) {
                    return Either.Left(CommitSigCountMismatch(channelId, active.size, commits.batchSize))
                }
                commits.messages
            }
            is CommitSig -> {
                if (active.size > 1) {
                    return Either.Left(CommitSigCountMismatch(channelId, active.size, 1))
                }
                listOf(commits)
            }
        }
        val commitKeys = channelKeys.localCommitmentKeys(channelParams, localCommitIndex + 1)
        // Signatures are sent in order (most recent first), calling `zip` will drop trailing sigs that are for deactivated/pruned commitments.
        val active1 = active.zip(sigs).map {
            when (val commitment1 = it.first.receiveCommit(channelParams, channelKeys, commitKeys, changes, it.second, log)) {
                is Either.Left -> return Either.Left(commitment1.value)
                is Either.Right -> commitment1.value
            }
        }
        // we will send our revocation preimage + our next revocation hash
        val localPerCommitmentSecret = channelKeys.commitmentSecret(localCommitIndex)
        val localNextPerCommitmentPoint = channelKeys.commitmentPoint(localCommitIndex + 2)
        val revocation = RevokeAndAck(channelId, localPerCommitmentSecret, localNextPerCommitmentPoint)
        val commitments1 = copy(
            active = active1,
            changes = changes.copy(
                localChanges = changes.localChanges.copy(acked = emptyList()),
                remoteChanges = changes.remoteChanges.copy(proposed = emptyList(), acked = changes.remoteChanges.acked + changes.remoteChanges.proposed)
            ),
        )
        return Either.Right(Pair(commitments1, revocation))
    }

    fun receiveRevocation(revocation: RevokeAndAck): Either<ChannelException, Pair<Commitments, List<ChannelAction>>> {
        if (remoteNextCommitInfo.isRight) return Either.Left(UnexpectedRevocation(channelId))
        // Since htlcs are shared across all commitments, we generate the actions only once based on the first commitment.
        val remoteCommit = active.first().remoteCommit
        if (revocation.perCommitmentSecret.publicKey() != remoteCommit.remotePerCommitmentPoint) return Either.Left(InvalidRevocation(channelId))

        // the outgoing following htlcs have been completed (fulfilled or failed) when we received this revocation
        // they have been removed from both local and remote commitment
        // since fulfill/fail are sent by remote, they are (1) signed by them, (2) revoked by us, (3) signed by us, (4) revoked by them
        val completedOutgoingHtlcs = changes.remoteChanges.signed.mapNotNull {
            when (it) {
                is UpdateFulfillHtlc -> it.id
                is UpdateFailHtlc -> it.id
                is UpdateFailMalformedHtlc -> it.id
                else -> null
            }
        }
        // we remove the newly completed htlcs from the payments map
        val payments1 = payments - completedOutgoingHtlcs.toSet()
        val actions = mutableListOf<ChannelAction>()
        changes.remoteChanges.signed.forEach {
            when (it) {
                is UpdateAddHtlc -> actions += ChannelAction.ProcessIncomingHtlc(it)
                is UpdateFailHtlc -> {
                    val paymentId = payments[it.id]
                    val add = remoteCommit.spec.findIncomingHtlcById(it.id)?.add
                    if (paymentId != null && add != null) {
                        actions += ChannelAction.ProcessCmdRes.AddSettledFail(paymentId, add, ChannelAction.HtlcResult.Fail.RemoteFail(it))
                    }
                }
                is UpdateFailMalformedHtlc -> {
                    val paymentId = payments[it.id]
                    val add = remoteCommit.spec.findIncomingHtlcById(it.id)?.add
                    if (paymentId != null && add != null) {
                        actions += ChannelAction.ProcessCmdRes.AddSettledFail(paymentId, add, ChannelAction.HtlcResult.Fail.RemoteFailMalformed(it))
                    }
                }
                else -> Unit
            }
        }
        val active1 = active.map { it.copy(remoteCommit = it.nextRemoteCommit!!, nextRemoteCommit = null) }
        val commitments1 = this.copy(
            active = active1,
            changes = changes.copy(
                localChanges = changes.localChanges.copy(signed = emptyList(), acked = changes.localChanges.acked + changes.localChanges.signed),
                remoteChanges = changes.remoteChanges.copy(signed = emptyList()),
            ),
            remoteNextCommitInfo = Either.Right(revocation.nextPerCommitmentPoint),
            remotePerCommitmentSecrets = remotePerCommitmentSecrets.addHash(revocation.perCommitmentSecret.value, 0xFFFFFFFFFFFFL - remoteCommitIndex),
            payments = payments1,
        )
        return Either.Right(Pair(commitments1, actions.toList()))
    }

    private fun ChannelContext.updateFundingStatus(fundingTxId: TxId, updateMethod: (Commitment, Long) -> Commitment): Either<Commitments, Pair<Commitments, Commitment>> {
        return when (val c = all.find { it.fundingTxId == fundingTxId }) {
            is Commitment -> {
                val commitments1 = copy(
                    active = active.map { updateMethod(it, c.fundingTxIndex) },
                    inactive = inactive.map { updateMethod(it, c.fundingTxIndex) },
                )
                val commitment = commitments1.all.find { it.fundingTxId == fundingTxId }!! // NB: this commitment might be pruned at the next line
                val commitments2 = commitments1.run { deactivateCommitments() }.run { pruneCommitments() }
                logger.info { "commitments active=${commitments2.active.map { it.fundingTxIndex }} inactive=${commitments2.inactive.map { it.fundingTxIndex }}" }
                Either.Right(Pair(commitments2, commitment))
            }
            else -> {
                logger.warning { "fundingTxId=$fundingTxId doesn't match any of our funding txs" }
                Either.Left(this@Commitments)
            }
        }
    }

    fun ChannelContext.updateLocalFundingSigned(fundingTx: FullySignedSharedTransaction): Either<Commitments, Pair<Commitments, Commitment>> =
        updateFundingStatus(fundingTx.txId) { c: Commitment, _: Long ->
            if (c.fundingTxId == fundingTx.txId) {
                when (c.localFundingStatus) {
                    is LocalFundingStatus.UnconfirmedFundingTx -> {
                        logger.debug { "setting localFundingStatus fully signed for fundingTxId=${fundingTx.txId}" }
                        c.copy(localFundingStatus = c.localFundingStatus.copy(sharedTx = fundingTx))
                    }
                    is LocalFundingStatus.ConfirmedFundingTx -> c
                }
            } else c
        }

    fun ChannelContext.updateLocalFundingConfirmed(fundingTx: Transaction, blockHeight: Int, txIndex: Int): Either<Commitments, Pair<Commitments, Commitment>> =
        updateFundingStatus(fundingTx.txid) { c: Commitment, _: Long ->
            if (c.fundingTxId == fundingTx.txid) {
                val shortChannelId = ShortChannelId(blockHeight, txIndex, c.fundingInput.index.toInt())
                when (c.localFundingStatus) {
                    is LocalFundingStatus.UnconfirmedFundingTx -> {
                        logger.debug { "setting localFundingStatus confirmed for fundingTxId=${fundingTx.txid}" }
                        val localFundingStatus1 = LocalFundingStatus.ConfirmedFundingTx(
                            fundingTx.txIn.map { it.outPoint },
                            fundingTx.txOut[c.fundingInput.index.toInt()],
                            c.localFundingStatus.sharedTx.tx.fees,
                            c.localFundingStatus.sharedTx.localSigs,
                            shortChannelId
                        )
                        c.copy(localFundingStatus = localFundingStatus1)
                    }
                    is LocalFundingStatus.ConfirmedFundingTx -> when (c.localFundingStatus.shortChannelId) {
                        ShortChannelId(0) -> {
                            // This branch is only useful to update old channel states that didn't store the short_channel_id.
                            logger.debug { "setting short_channel_id for fundingTxId=${fundingTx.txid}" }
                            c.copy(localFundingStatus = c.localFundingStatus.copy(shortChannelId = shortChannelId))
                        }
                        else -> c
                    }
                }
            } else c
        }

    fun ChannelContext.updateRemoteFundingStatus(fundingTxId: TxId): Either<Commitments, Pair<Commitments, Commitment>> =
        updateFundingStatus(fundingTxId) { c: Commitment, fundingTxIndex: Long ->
            // all funding older than this one are considered locked
            if (c.fundingTxId == fundingTxId || c.fundingTxIndex < fundingTxIndex) {
                logger.debug { "setting remoteFundingStatus=${RemoteFundingStatus.Locked::class.simpleName} for fundingTxId=$fundingTxId" }
                c.copy(remoteFundingStatus = RemoteFundingStatus.Locked)
            } else c
        }

    /**
     * Return the most recent commitment locked by both sides.
     */
    fun ChannelContext.lastLocked(): Commitment? {
        // When a commitment is locked, it implicitly locks all previous commitments.
        // This ensures that we only have to send splice_locked for the latest commitment instead of sending it for every commitment.
        // A side-effect is that previous commitments that are implicitly locked don't necessarily have their status correctly set.
        // That's why we look at locked commitments separately and then select the one with the oldest fundingTxIndex.
        val lastLocalLocked = active.find { staticParams.useZeroConf || it.localFundingStatus is LocalFundingStatus.ConfirmedFundingTx }
        val lastRemoteLocked = active.find { it.remoteFundingStatus == RemoteFundingStatus.Locked }
        return when {
            // We select the locked commitment with the smaller value for fundingTxIndex, but both have to be defined.
            // If both have the same fundingTxIndex, they must actually be the same commitment, because:
            //  - we only allow RBF attempts when we're not using zero-conf
            //  - transactions with the same fundingTxIndex double-spend each other, so only one of them can confirm
            //  - we don't allow creating a splice on top of an unconfirmed transaction that has RBF attempts (because it
            //    would become invalid if another of the RBF attempts end up being confirmed)
            lastLocalLocked != null && lastRemoteLocked != null -> listOf(lastLocalLocked, lastRemoteLocked).minByOrNull { it.fundingTxIndex }
            // Special case for the initial funding tx, we only require a local lock because channel_ready doesn't explicitly reference a funding tx.
            lastLocalLocked != null && lastLocalLocked.fundingTxIndex == 0L -> lastLocalLocked
            else -> null
        }
    }

    /**
     * Commitments are considered inactive when they have been superseded by a newer commitment, but can still potentially
     * end up on-chain. This is a consequence of using zero-conf. Inactive commitments will be cleaned up by
     * [pruneCommitments], when the next funding tx confirms.
     */
    private fun ChannelContext.deactivateCommitments(): Commitments = when (val commitment = lastLocked()) {
        is Commitment -> {
            // all commitments older than this one are inactive
            val inactive1 = active.filter { it.fundingTxId != commitment.fundingTxId && it.fundingTxIndex <= commitment.fundingTxIndex }
            inactive1.forEach { logger.info { "deactivating commitment fundingTxIndex=${it.fundingTxIndex} fundingTxId=${it.fundingTxId}" } }
            copy(
                active = active - inactive1.toSet(),
                inactive = inactive1 + inactive.toSet()
            )
        }
        else -> this@Commitments
    }

    /**
     * We can prune commitments in two cases:
     *  - their funding tx has been permanently double-spent by the funding tx of a concurrent commitment (happens when using RBF)
     *  - their funding tx has been permanently spent by a splice tx
     */
    private fun ChannelContext.pruneCommitments(): Commitments {
        return when (val lastConfirmed = all.find { it.localFundingStatus is LocalFundingStatus.ConfirmedFundingTx }) {
            null -> this@Commitments
            else -> {
                // We can prune all other commitments with the same or lower funding index.
                // NB: we cannot prune active commitments, even if we know that they have been double-spent, because our peer may not yet
                // be aware of it, and will expect us to send commit_sig.
                val pruned = inactive.filter { it.fundingTxId != lastConfirmed.fundingTxId && it.fundingTxIndex <= lastConfirmed.fundingTxIndex }
                pruned.forEach { logger.info { "pruning commitment fundingTxIndex=${it.fundingTxIndex} fundingTxId=${it.fundingTxId}" } }
                copy(inactive = inactive - pruned.toSet())
            }
        }
    }

    /** Find the corresponding commitment based on its short_channel_id (once funding transaction is confirmed). */
    fun resolveCommitment(shortChannelId: ShortChannelId): Commitment? {
        return all.find { commitment -> commitment.shortChannelId == shortChannelId }
    }

    /**
     * Find the corresponding commitment, based on a spending transaction.
     *
     * @param spendingTx A transaction that may spend a current or former funding tx
     */
    fun resolveCommitment(spendingTx: Transaction): Commitment? {
        return all.find { commitment -> spendingTx.txIn.map { it.outPoint }.contains(commitment.fundingInput) }
    }

    companion object {

        /**
         * Alternative feerates at which we will sign commitment transactions that have no pending HTLCs.
         * WARNING: never remove a feerate from this list, we can only add more, otherwise we will not be able to detect when our peer broadcasts the commit tx at the removed feerate.
         */
        val alternativeFeerates = listOf(1.sat, 2.sat, 5.sat, 10.sat).map { FeeratePerKw(FeeratePerByte(it)) }

        /**
         * Our peer may publish an alternative version of their commitment using a different feerate.
         * This function lists all the alternative commitments they have signatures for.
         */
        fun alternativeFeerateCommits(commitments: Commitments, channelKeys: ChannelKeys): List<RemoteCommit> {
            val localFundingKey = channelKeys.fundingKey(commitments.latest.fundingTxIndex)
            val commitParams = commitments.latest.remoteCommitParams
            return buildList {
                add(commitments.latest.remoteCommit)
                commitments.latest.nextRemoteCommit?.let { add(it) }
            }.filter { remoteCommit ->
                remoteCommit.spec.htlcs.isEmpty()
            }.flatMap { remoteCommit ->
                alternativeFeerates.map { feerate ->
                    val alternativeSpec = remoteCommit.spec.copy(feerate = feerate)
                    val commitKeys = channelKeys.remoteCommitmentKeys(commitments.channelParams, remoteCommit.remotePerCommitmentPoint)
                    val (alternativeRemoteCommitTx, _) = makeRemoteTxs(
                        commitments.channelParams,
                        commitParams,
                        commitKeys,
                        remoteCommit.index,
                        localFundingKey,
                        commitments.latest.remoteFundingPubkey,
                        commitments.latest.commitInput(channelKeys),
                        commitments.latest.commitmentFormat,
                        alternativeSpec
                    )
                    RemoteCommit(remoteCommit.index, alternativeSpec, alternativeRemoteCommitTx.tx.txid, remoteCommit.remotePerCommitmentPoint)
                }
            }
        }

        fun makeLocalTxs(
            channelParams: ChannelParams,
            commitParams: CommitParams,
            commitKeys: LocalCommitmentKeys,
            commitTxNumber: Long,
            localFundingKey: PrivateKey,
            remoteFundingPubKey: PublicKey,
            commitmentInput: Transactions.InputInfo,
            commitmentFormat: Transactions.CommitmentFormat,
            spec: CommitmentSpec
        ): Pair<CommitTx, List<HtlcTx>> {
            val outputs = makeCommitTxOutputs(
                localFundingPubkey = localFundingKey.publicKey(),
                remoteFundingPubkey = remoteFundingPubKey,
                commitKeys = commitKeys.publicKeys,
                payCommitTxFees = channelParams.localParams.paysCommitTxFees,
                dustLimit = commitParams.dustLimit,
                toSelfDelay = commitParams.toSelfDelay,
                commitmentFormat = commitmentFormat,
                spec = spec
            )
            val commitTx = Transactions.makeCommitTx(commitmentInput, commitTxNumber, commitKeys.ourPaymentBasePoint, channelParams.remoteParams.paymentBasepoint, channelParams.localParams.isChannelOpener, outputs)
            val htlcTxs = Transactions.makeHtlcTxs(commitTx.tx, outputs, commitmentFormat)
            return Pair(commitTx, htlcTxs)
        }

        fun makeRemoteTxs(
            channelParams: ChannelParams,
            commitParams: CommitParams,
            commitKeys: RemoteCommitmentKeys,
            commitTxNumber: Long,
            localFundingKey: PrivateKey,
            remoteFundingPubKey: PublicKey,
            commitmentInput: Transactions.InputInfo,
            commitmentFormat: Transactions.CommitmentFormat,
            spec: CommitmentSpec
        ): Pair<CommitTx, List<HtlcTx>> {
            val outputs = makeCommitTxOutputs(
                localFundingPubkey = remoteFundingPubKey,
                remoteFundingPubkey = localFundingKey.publicKey(),
                commitKeys = commitKeys.publicKeys,
                payCommitTxFees = !channelParams.localParams.paysCommitTxFees,
                dustLimit = commitParams.dustLimit,
                toSelfDelay = commitParams.toSelfDelay,
                commitmentFormat = commitmentFormat,
                spec = spec
            )
            // NB: we are creating the remote commit tx, so local/remote parameters are inverted.
            val commitTx = Transactions.makeCommitTx(commitmentInput, commitTxNumber, channelParams.remoteParams.paymentBasepoint, commitKeys.ourPaymentBasePoint, !channelParams.localParams.isChannelOpener, outputs)
            val htlcTxs = Transactions.makeHtlcTxs(commitTx.tx, outputs, commitmentFormat)
            return Pair(commitTx, htlcTxs)
        }
    }
}
