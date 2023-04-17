package fr.acinq.lightning.channel

import fr.acinq.bitcoin.*
import fr.acinq.bitcoin.Crypto.sha256
import fr.acinq.lightning.CltvExpiryDelta
import fr.acinq.lightning.Feature
import fr.acinq.lightning.Features
import fr.acinq.lightning.MilliSatoshi
import fr.acinq.lightning.blockchain.fee.FeeratePerKw
import fr.acinq.lightning.blockchain.fee.FeerateTolerance
import fr.acinq.lightning.crypto.Bolt3Derivation.deriveForCommitment
import fr.acinq.lightning.crypto.Bolt3Derivation.deriveForRevocation
import fr.acinq.lightning.crypto.KeyManager
import fr.acinq.lightning.crypto.ShaChain
import fr.acinq.lightning.payment.OutgoingPaymentPacket
import fr.acinq.lightning.transactions.CommitmentSpec
import fr.acinq.lightning.transactions.Transactions
import fr.acinq.lightning.transactions.Transactions.TransactionWithInputInfo.CommitTx
import fr.acinq.lightning.transactions.Transactions.TransactionWithInputInfo.HtlcTx
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
    val localParams: LocalParams, val remoteParams: RemoteParams,
    val channelFlags: Byte
) {
    init {
        require(channelFeatures.hasFeature(Feature.AnchorOutputs)) { "invalid channel type: ${channelFeatures.channelType.name}" }
        require(channelConfig.hasOption(ChannelConfigOption.FundingPubKeyBasedChannelKeyPath)) { "FundingPubKeyBasedChannelKeyPath option must be enabled"}
    }

    fun updateFeatures(localInit: Init, remoteInit: Init) = this.copy(
        localParams = localParams.copy(features = Features(localInit.features)),
        remoteParams = remoteParams.copy(features = Features(remoteInit.features))
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

data class HtlcTxAndSigs(val txinfo: HtlcTx, val localSig: ByteVector64, val remoteSig: ByteVector64)
data class PublishableTxs(val commitTx: CommitTx, val htlcTxsAndSigs: List<HtlcTxAndSigs>)

/** The local commitment maps to a commitment transaction that we can sign and broadcast if necessary. */
data class LocalCommit(val index: Long, val spec: CommitmentSpec, val publishableTxs: PublishableTxs)

/** The remote commitment maps to a commitment transaction that only our peer can sign and broadcast. */
data class RemoteCommit(val index: Long, val spec: CommitmentSpec, val txid: ByteVector32, val remotePerCommitmentPoint: PublicKey) {
    fun sign(channelKeys: KeyManager.ChannelKeys, params: ChannelParams, fundingTxIndex: Long, remoteFundingPubKey: PublicKey, commitInput: Transactions.InputInfo): CommitSig {
        val (remoteCommitTx, htlcTxs) = Commitments.makeRemoteTxs(channelKeys, index, params.localParams, params.remoteParams, fundingTxIndex = fundingTxIndex, remoteFundingPubKey = remoteFundingPubKey, commitInput, remotePerCommitmentPoint = remotePerCommitmentPoint, spec)
        val sig = Transactions.sign(remoteCommitTx, channelKeys.fundingKey(fundingTxIndex))
        // we sign our peer's HTLC txs with SIGHASH_SINGLE || SIGHASH_ANYONECANPAY
        val sortedHtlcsTxs = htlcTxs.sortedBy { it.input.outPoint.index }
        val htlcSigs = sortedHtlcsTxs.map { Transactions.sign(it, channelKeys.htlcKey.deriveForCommitment(remotePerCommitmentPoint), SigHash.SIGHASH_SINGLE or SigHash.SIGHASH_ANYONECANPAY) }
        return CommitSig(params.channelId, sig, htlcSigs.toList())
    }
    fun sign(channelKeys: KeyManager.ChannelKeys, params: ChannelParams, signingSession: InteractiveTxSigningSession): CommitSig =
        sign(channelKeys, params, fundingTxIndex = signingSession.fundingTxIndex, remoteFundingPubKey = signingSession.fundingParams.remoteFundingPubkey, commitInput = signingSession.commitInput)
}

/** We have the next remote commit when we've sent our commit_sig but haven't yet received their revoke_and_ack. */
data class NextRemoteCommit(val sig: CommitSig, val commit: RemoteCommit)

sealed class LocalFundingStatus {
    abstract val signedTx: Transaction?
    abstract val txId: ByteVector32

    data class UnconfirmedFundingTx(val sharedTx: SignedSharedTransaction, val fundingParams: InteractiveTxParams, val createdAt: Long) : LocalFundingStatus() {
        override val signedTx: Transaction? = sharedTx.signedTx
        override val txId: ByteVector32 = sharedTx.localSigs.txId
    }

    data class ConfirmedFundingTx(override val signedTx: Transaction) : LocalFundingStatus() {
        override val txId: ByteVector32 = signedTx.txid
    }
}

sealed class RemoteFundingStatus {
    object NotLocked : RemoteFundingStatus()
    object Locked : RemoteFundingStatus()
}

/** A minimal commitment for a given funding tx. */
data class Commitment(
    val fundingTxIndex: Long,
    val remoteFundingPubkey: PublicKey,
    val localFundingStatus: LocalFundingStatus, val remoteFundingStatus: RemoteFundingStatus,
    val localCommit: LocalCommit, val remoteCommit: RemoteCommit, val nextRemoteCommit: NextRemoteCommit?
) {
    val commitInput = localCommit.publishableTxs.commitTx.input
    val fundingTxId: ByteVector32 = commitInput.outPoint.txid
    val fundingAmount: Satoshi = commitInput.txOut.amount

    fun localChannelReserve(params: ChannelParams): Satoshi = when {
        params.channelFeatures.hasFeature(Feature.ZeroReserveChannels) && !params.localParams.isInitiator -> 0.sat
        else -> (fundingAmount / 100).max(params.remoteParams.dustLimit)
    }

    fun remoteChannelReserve(params: ChannelParams): Satoshi = when {
        params.channelFeatures.hasFeature(Feature.ZeroReserveChannels) && params.localParams.isInitiator -> 0.sat
        else -> (fundingAmount / 100).max(params.localParams.dustLimit)
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
        val remoteCommit1 = nextRemoteCommit?.commit ?: remoteCommit
        val reduced = CommitmentSpec.reduce(remoteCommit1.spec, changes.remoteChanges.acked, changes.localChanges.proposed)
        val balanceNoFees = (reduced.toRemote - localChannelReserve(params).toMilliSatoshi()).coerceAtLeast(0.msat)
        return if (params.localParams.isInitiator) {
            // The initiator always pays the on-chain fees, so we must subtract that from the amount we can send.
            val commitFees = commitTxFeeMsat(params.remoteParams.dustLimit, reduced)
            // the initiator needs to keep a "initiator fee buffer" (see explanation above)
            val initiatorFeeBuffer = commitTxFeeMsat(params.remoteParams.dustLimit, reduced.copy(feerate = reduced.feerate * 2)) + htlcOutputFee(reduced.feerate * 2)
            val amountToReserve = commitFees.coerceAtLeast(initiatorFeeBuffer)
            if (balanceNoFees - amountToReserve < offeredHtlcTrimThreshold(params.remoteParams.dustLimit, reduced).toMilliSatoshi()) {
                // htlc will be trimmed
                (balanceNoFees - amountToReserve).coerceAtLeast(0.msat)
            } else {
                // htlc will have an output in the commitment tx, so there will be additional fees.
                val commitFees1 = commitFees + htlcOutputFee(reduced.feerate)
                // we take the additional fees for that htlc output into account in the fee buffer at a x2 feerate increase
                val initiatorFeeBuffer1 = initiatorFeeBuffer + htlcOutputFee(reduced.feerate * 2)
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
        return if (params.localParams.isInitiator) {
            // The non-initiator doesn't pay on-chain fees so we don't take those into account when receiving.
            balanceNoFees
        } else {
            // The initiator always pays the on-chain fees, so we must subtract that from the amount we can receive.
            val commitFees = commitTxFeeMsat(params.localParams.dustLimit, reduced)
            // we expected the initiator to keep a "initiator fee buffer" (see explanation above)
            val initiatorFeeBuffer = commitTxFeeMsat(params.localParams.dustLimit, reduced.copy(feerate = reduced.feerate * 2)) + htlcOutputFee(reduced.feerate * 2)
            val amountToReserve = commitFees.coerceAtLeast(initiatorFeeBuffer)
            if (balanceNoFees - amountToReserve < receivedHtlcTrimThreshold(params.localParams.dustLimit, reduced).toMilliSatoshi()) {
                // htlc will be trimmed
                (balanceNoFees - amountToReserve).coerceAtLeast(0.msat)
            } else {
                // htlc will have an output in the commitment tx, so there will be additional fees.
                val commitFees1 = commitFees + htlcOutputFee(reduced.feerate)
                // we take the additional fees for that htlc output into account in the fee buffer at a x2 feerate increase
                val initiatorFeeBuffer1 = initiatorFeeBuffer + htlcOutputFee(reduced.feerate * 2)
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

    fun isIdle(changes: CommitmentChanges): Boolean = hasNoPendingHtlcs() && changes.localChanges.all.isEmpty() && changes.remoteChanges.all.isEmpty()

    fun timedOutOutgoingHtlcs(blockHeight: Long): Set<UpdateAddHtlc> {
        fun expired(add: UpdateAddHtlc) = blockHeight >= add.cltvExpiry.toLong()

        val thisCommitAdds = localCommit.spec.htlcs.outgoings().filter(::expired).toSet() + remoteCommit.spec.htlcs.incomings().filter(::expired).toSet()
        return when (nextRemoteCommit) {
            null -> thisCommitAdds
            else -> thisCommitAdds + nextRemoteCommit.commit.spec.htlcs.incomings().filter(::expired).toSet()
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
        val localSigned = (nextRemoteCommit?.commit ?: remoteCommit).spec.findIncomingHtlcById(htlcId) ?: return null
        val remoteSigned = localCommit.spec.findOutgoingHtlcById(htlcId) ?: return null
        require(localSigned.add == remoteSigned.add)
        return localSigned.add
    }

    fun getIncomingHtlcCrossSigned(htlcId: Long): UpdateAddHtlc? {
        val localSigned = (nextRemoteCommit?.commit ?: remoteCommit).spec.findOutgoingHtlcById(htlcId) ?: return null
        val remoteSigned = localCommit.spec.findIncomingHtlcById(htlcId) ?: return null
        require(localSigned.add == remoteSigned.add)
        return localSigned.add
    }

    fun canSendAdd(amount: MilliSatoshi, params: ChannelParams, changes: CommitmentChanges): Either<ChannelException, Unit> {
        // we need to base the next current commitment on the last sig we sent, even if we didn't yet receive their revocation
        val remoteCommit1 = nextRemoteCommit?.commit ?: remoteCommit
        val reduced = CommitmentSpec.reduce(remoteCommit1.spec, changes.remoteChanges.acked, changes.localChanges.proposed)
        // the HTLC we are about to create is outgoing, but from their point of view it is incoming
        val outgoingHtlcs = reduced.htlcs.incomings()

        // note that the initiator pays the fee, so if sender != initiator, both sides will have to afford this payment
        val fees = commitTxFee(params.remoteParams.dustLimit, reduced)
        // the initiator needs to keep an extra buffer to be able to handle a x2 feerate increase and an additional htlc to avoid
        // getting the channel stuck (see https://github.com/lightningnetwork/lightning-rfc/issues/728).
        val initiatorFeeBuffer = commitTxFeeMsat(params.remoteParams.dustLimit, reduced.copy(feerate = reduced.feerate * 2)) + htlcOutputFee(reduced.feerate * 2)
        // NB: increasing the feerate can actually remove htlcs from the commit tx (if they fall below the trim threshold)
        // which may result in a lower commit tx fee; this is why we take the max of the two.
        val missingForSender = reduced.toRemote - localChannelReserve(params).toMilliSatoshi() - (if (params.localParams.isInitiator) fees.toMilliSatoshi().coerceAtLeast(initiatorFeeBuffer) else 0.msat)
        val missingForReceiver = reduced.toLocal - remoteChannelReserve(params).toMilliSatoshi() - (if (params.localParams.isInitiator) 0.msat else fees.toMilliSatoshi())
        if (missingForSender < 0.msat) {
            val actualFees = if (params.localParams.isInitiator) fees else 0.sat
            return Either.Left(InsufficientFunds(params.channelId, amount, -missingForSender.truncateToSatoshi(), localChannelReserve(params), actualFees))
        } else if (missingForReceiver < 0.msat) {
            if (params.localParams.isInitiator) {
                // receiver is not the initiator; it is ok if it can't maintain its channel_reserve for now, as long as its balance is increasing, which is the case if it is receiving a payment
            } else {
                return Either.Left(RemoteCannotAffordFeesForNewHtlc(params.channelId, amount = amount, missing = -missingForReceiver.truncateToSatoshi(), reserve = localChannelReserve(params), fees = fees))
            }
        }

        // README: we check against our peer's max_htlc_value_in_flight_msat parameter, as per the BOLTS, but also against our own setting
        val htlcValueInFlight = outgoingHtlcs.map { it.amountMsat }.sum()
        val maxHtlcValueInFlightMsat = min(params.remoteParams.maxHtlcValueInFlightMsat, params.localParams.maxHtlcValueInFlightMsat)
        if (htlcValueInFlight.toLong() > maxHtlcValueInFlightMsat) {
            return Either.Left(HtlcValueTooHighInFlight(params.channelId, maximum = maxHtlcValueInFlightMsat.toULong(), actual = htlcValueInFlight))
        }

        if (outgoingHtlcs.size > params.remoteParams.maxAcceptedHtlcs) {
            return Either.Left(TooManyAcceptedHtlcs(params.channelId, maximum = params.remoteParams.maxAcceptedHtlcs.toLong()))
        }

        // README: this is not part of the LN Bolts: we also check against our own limit, to avoid creating commit txs that have too many outputs
        if (outgoingHtlcs.size > params.localParams.maxAcceptedHtlcs) {
            return Either.Left(TooManyOfferedHtlcs(params.channelId, maximum = params.localParams.maxAcceptedHtlcs.toLong()))
        }

        return Either.Right(Unit)
    }

    fun canReceiveAdd(amount: MilliSatoshi, params: ChannelParams, changes: CommitmentChanges): Either<ChannelException, Unit> {
        // let's compute the current commitment *as seen by us* including this change
        val reduced = CommitmentSpec.reduce(localCommit.spec, changes.localChanges.acked, changes.remoteChanges.proposed)
        val incomingHtlcs = reduced.htlcs.incomings()

        // note that the initiator pays the fee, so if sender != initiator, both sides will have to afford this payment
        val fees = commitTxFee(params.remoteParams.dustLimit, reduced)
        // NB: we don't enforce the initiatorFeeReserve (see sendAdd) because it would confuse a remote initiator that doesn't have this mitigation in place
        // We could enforce it once we're confident a large portion of the network implements it.
        val missingForSender = reduced.toRemote - remoteChannelReserve(params).toMilliSatoshi() - (if (params.localParams.isInitiator) 0.sat else fees).toMilliSatoshi()
        val missingForReceiver = reduced.toLocal - localChannelReserve(params).toMilliSatoshi() - (if (params.localParams.isInitiator) fees else 0.sat).toMilliSatoshi()
        if (missingForSender < 0.sat) {
            val actualFees = if (params.localParams.isInitiator) 0.sat else fees
            return Either.Left(InsufficientFunds(params.channelId, amount, -missingForSender.truncateToSatoshi(), remoteChannelReserve(params), actualFees))
        } else if (missingForReceiver < 0.sat) {
            if (params.localParams.isInitiator) {
                return Either.Left(CannotAffordFees(params.channelId, missing = -missingForReceiver.truncateToSatoshi(), reserve = localChannelReserve(params), fees = fees))
            } else {
                // receiver is not the initiator; it is ok if it can't maintain its channel_reserve for now, as long as its balance is increasing, which is the case if it is receiving a payment
            }
        }

        val htlcValueInFlight = incomingHtlcs.map { it.amountMsat }.sum()
        if (params.localParams.maxHtlcValueInFlightMsat < htlcValueInFlight.toLong()) {
            return Either.Left(HtlcValueTooHighInFlight(params.channelId, maximum = params.localParams.maxHtlcValueInFlightMsat.toULong(), actual = htlcValueInFlight))
        }

        if (incomingHtlcs.size > params.localParams.maxAcceptedHtlcs) {
            return Either.Left(TooManyAcceptedHtlcs(params.channelId, maximum = params.localParams.maxAcceptedHtlcs.toLong()))
        }

        return Either.Right(Unit)
    }

    fun canSendFee(params: ChannelParams, changes: CommitmentChanges): Either<ChannelException, Unit> {
        val reduced = CommitmentSpec.reduce(remoteCommit.spec, changes.remoteChanges.acked, changes.localChanges.proposed)
        // a node cannot spend pending incoming htlcs, and need to keep funds above the reserve required by the counterparty, after paying the fee
        // we look from remote's point of view, so if local is initiator remote doesn't pay the fees
        val fees = commitTxFee(params.remoteParams.dustLimit, reduced)
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
        val fees = commitTxFee(params.remoteParams.dustLimit, reduced)
        val missing = reduced.toRemote.truncateToSatoshi() - remoteChannelReserve(params) - fees
        return if (missing < 0.sat) {
            Either.Left(CannotAffordFees(params.channelId, -missing, remoteChannelReserve(params), fees))
        } else {
            Either.Right(Unit)
        }
    }

    fun sendCommit(channelKeys: KeyManager.ChannelKeys, params: ChannelParams, changes: CommitmentChanges, remoteNextPerCommitmentPoint: PublicKey, log: MDCLogger): Pair<Commitment, CommitSig> {
        // remote commitment will include all local changes + remote acked changes
        val spec = CommitmentSpec.reduce(remoteCommit.spec, changes.remoteChanges.acked, changes.localChanges.proposed)
        val (remoteCommitTx, htlcTxs) = Commitments.makeRemoteTxs(channelKeys, commitTxNumber = remoteCommit.index + 1, params.localParams, params.remoteParams, fundingTxIndex = fundingTxIndex, remoteFundingPubKey = remoteFundingPubkey, commitInput, remotePerCommitmentPoint = remoteNextPerCommitmentPoint, spec)
        val sig = Transactions.sign(remoteCommitTx, channelKeys.fundingKey(fundingTxIndex))

        val sortedHtlcTxs: List<HtlcTx> = htlcTxs.sortedBy { it.input.outPoint.index }
        // we sign our peer's HTLC txs with SIGHASH_SINGLE || SIGHASH_ANYONECANPAY
        val htlcSigs = sortedHtlcTxs.map { Transactions.sign(it, channelKeys.htlcKey.deriveForCommitment(remoteNextPerCommitmentPoint), SigHash.SIGHASH_SINGLE or SigHash.SIGHASH_ANYONECANPAY) }

        // NB: IN/OUT htlcs are inverted because this is the remote commit
        log.info {
            val htlcsIn = spec.htlcs.outgoings().map { it.id }.joinToString(",")
            val htlcsOut = spec.htlcs.incomings().map { it.id }.joinToString(",")
            "built remote commit number=${remoteCommit.index + 1} toLocalMsat=${spec.toLocal.toLong()} toRemoteMsat=${spec.toRemote.toLong()} htlc_in=$htlcsIn htlc_out=$htlcsOut feeratePerKw=${spec.feerate} txId=${remoteCommitTx.tx.txid} fundingTxId=$fundingTxId"
        }

        val commitSig = CommitSig(params.channelId, sig, htlcSigs.toList())
        val commitment1 = copy(nextRemoteCommit = NextRemoteCommit(commitSig, RemoteCommit(remoteCommit.index + 1, spec, remoteCommitTx.tx.txid, remoteNextPerCommitmentPoint)))
        return Pair(commitment1, commitSig)
    }

    fun receiveCommit(channelKeys: KeyManager.ChannelKeys, params: ChannelParams, changes: CommitmentChanges, commit: CommitSig, log: MDCLogger): Either<ChannelException, Commitment> {
        // they sent us a signature for *their* view of *our* next commit tx
        // so in terms of rev.hashes and indexes we have:
        // ourCommit.index -> our current revocation hash, which is about to become our old revocation hash
        // ourCommit.index + 1 -> our next revocation hash, used by *them* to build the sig we've just received, and which
        // is about to become our current revocation hash
        // ourCommit.index + 2 -> which is about to become our next revocation hash
        // we will reply to this sig with our old revocation hash preimage (at index) and our next revocation hash (at index + 1)
        // and will increment our index

        // check that their signature is valid
        // signatures are now optional in the commit message, and will be sent only if the other party is actually
        // receiving money i.e its commit tx has one output for them
        val spec = CommitmentSpec.reduce(localCommit.spec, changes.localChanges.acked, changes.remoteChanges.proposed)
        val localPerCommitmentPoint = channelKeys.commitmentPoint(localCommit.index + 1)
        val (localCommitTx, htlcTxs) = Commitments.makeLocalTxs(channelKeys, commitTxNumber = localCommit.index + 1, params.localParams, params.remoteParams, fundingTxIndex = fundingTxIndex, remoteFundingPubKey = remoteFundingPubkey, commitInput, localPerCommitmentPoint = localPerCommitmentPoint, spec)
        val sig = Transactions.sign(localCommitTx, channelKeys.fundingKey(fundingTxIndex))

        log.info {
            val htlcsIn = spec.htlcs.incomings().map { it.id }.joinToString(",")
            val htlcsOut = spec.htlcs.outgoings().map { it.id }.joinToString(",")
            "built local commit number=${localCommit.index + 1} toLocalMsat=${spec.toLocal.toLong()} toRemoteMsat=${spec.toRemote.toLong()} htlc_in=$htlcsIn htlc_out=$htlcsOut feeratePerKw=${spec.feerate} txId=${localCommitTx.tx.txid} fundingTxId=$fundingTxId"
        }

        // no need to compute htlc sigs if commit sig doesn't check out
        val signedCommitTx = Transactions.addSigs(localCommitTx, channelKeys.fundingPubKey(fundingTxIndex), remoteFundingPubkey, sig, commit.signature)
        when (val check = Transactions.checkSpendable(signedCommitTx)) {
            is Try.Failure -> {
                log.error(check.error) { "remote signature $commit is invalid" }
                return Either.Left(InvalidCommitmentSignature(params.channelId, signedCommitTx.tx.txid))
            }
            else -> {}
        }

        val sortedHtlcTxs: List<HtlcTx> = htlcTxs.sortedBy { it.input.outPoint.index }
        if (commit.htlcSignatures.size != sortedHtlcTxs.size) {
            return Either.Left(HtlcSigCountMismatch(params.channelId, sortedHtlcTxs.size, commit.htlcSignatures.size))
        }
        val htlcSigs = sortedHtlcTxs.map { Transactions.sign(it, channelKeys.htlcKey.deriveForCommitment(localPerCommitmentPoint), SigHash.SIGHASH_ALL) }
        val remoteHtlcPubkey = params.remoteParams.htlcBasepoint.deriveForCommitment(localPerCommitmentPoint)
        // combine the sigs to make signed txs
        val htlcTxsAndSigs = Triple(sortedHtlcTxs, htlcSigs, commit.htlcSignatures).zipped().map { (htlcTx, localSig, remoteSig) ->
            when (htlcTx) {
                is HtlcTx.HtlcTimeoutTx -> {
                    if (Transactions.checkSpendable(Transactions.addSigs(htlcTx, localSig, remoteSig)).isFailure) {
                        return Either.Left(InvalidHtlcSignature(params.channelId, htlcTx.tx.txid))
                    }
                    HtlcTxAndSigs(htlcTx, localSig, remoteSig)
                }
                is HtlcTx.HtlcSuccessTx -> {
                    // we can't check that htlc-success tx are spendable because we need the payment preimage; thus we only check the remote sig
                    // which was created with SIGHASH_SINGLE || SIGHASH_ANYONECANPAY
                    if (!Transactions.checkSig(htlcTx, remoteSig, remoteHtlcPubkey, SigHash.SIGHASH_SINGLE or SigHash.SIGHASH_ANYONECANPAY)) {
                        return Either.Left(InvalidHtlcSignature(params.channelId, htlcTx.tx.txid))
                    }
                    HtlcTxAndSigs(htlcTx, localSig, remoteSig)
                }
            }
        }
        val localCommit1 = LocalCommit(localCommit.index + 1, spec, PublishableTxs(signedCommitTx, htlcTxsAndSigs))
        return Either.Right(copy(localCommit = localCommit1))
    }
}

/** Subset of Commitments when we want to work with a single, specific commitment. */
data class FullCommitment(
    val params: ChannelParams, val changes: CommitmentChanges,
    val fundingTxIndex: Long,
    val remoteFundingPubkey: PublicKey,
    val localFundingStatus: LocalFundingStatus, val remoteFundingStatus: RemoteFundingStatus,
    val localCommit: LocalCommit, val remoteCommit: RemoteCommit, val nextRemoteCommit: NextRemoteCommit?
) {
    val channelId = params.channelId
    val commitInput = localCommit.publishableTxs.commitTx.input
    val fundingTxId: ByteVector32 = commitInput.outPoint.txid
    val fundingAmount = commitInput.txOut.amount
    val localChannelReserve = when {
        params.channelFeatures.hasFeature(Feature.ZeroReserveChannels) && !params.localParams.isInitiator -> 0.sat
        else -> (fundingAmount / 100).max(params.remoteParams.dustLimit)
    }
    val remoteChannelReserve = when {
        params.channelFeatures.hasFeature(Feature.ZeroReserveChannels) && params.localParams.isInitiator -> 0.sat
        else -> (fundingAmount / 100).max(params.localParams.dustLimit)
    }
}

data class WaitingForRevocation(val sentAfterLocalCommitIndex: Long)

data class Commitments(
    val params: ChannelParams,
    val changes: CommitmentChanges,
    val active: List<Commitment>,
    val inactive: List<Commitment>,
    val payments: Map<Long, UUID>, // for outgoing htlcs, maps to paymentId
    val remoteNextCommitInfo: Either<WaitingForRevocation, PublicKey>, // this one is tricky, it must be kept in sync with Commitment.nextRemoteCommit
    val remotePerCommitmentSecrets: ShaChain,
    val remoteChannelData: EncryptedChannelData = EncryptedChannelData.empty
) {
    init {
        require(active.isNotEmpty()) { "there must be at least one active commitment" }
    }

    val channelId: ByteVector32 = params.channelId
    val localNodeId: PublicKey = params.localParams.nodeId
    val remoteNodeId: PublicKey = params.remoteParams.nodeId

    // Commitment numbers are the same for all active commitments.
    val localCommitIndex = active.first().localCommit.index
    val remoteCommitIndex = active.first().remoteCommit.index
    val nextRemoteCommitIndex = remoteCommitIndex + 1

    fun availableBalanceForSend(): MilliSatoshi = active.minOf { it.availableBalanceForSend(params, changes) }
    fun availableBalanceForReceive(): MilliSatoshi = active.minOf { it.availableBalanceForReceive(params, changes) }

    // We always use the last commitment that was created, to make sure we never go back in time.
    val latest = FullCommitment(params, changes, active.first().fundingTxIndex, active.first().remoteFundingPubkey, active.first().localFundingStatus, active.first().remoteFundingStatus, active.first().localCommit, active.first().remoteCommit, active.first().nextRemoteCommit)

    val all = buildList {
        addAll(active)
        addAll(inactive)
    }

    fun add(commitment: Commitment): Commitments = copy(active = buildList {
        add(commitment)
        addAll(active)
    })

    /**
     * @return true if channel was never open, or got closed immediately, had never any htlcs and local never had a positive balance
     */
    fun nothingAtStake(): Boolean = active.all {
        it.localCommit.index == 0L &&
                it.localCommit.spec.toLocal == 0.msat &&
                it.remoteCommit.index == 0L &&
                it.remoteCommit.spec.toRemote == 0.msat &&
                it.nextRemoteCommit == null
    }

    fun isMoreRecent(other: Commitments): Boolean {
        return this.localCommitIndex > other.localCommitIndex ||
                this.remoteCommitIndex > other.remoteCommitIndex ||
                (this.remoteCommitIndex == other.remoteCommitIndex && this.remoteNextCommitInfo.isLeft && other.remoteNextCommitInfo.isRight)
    }

    // @formatter:off
    // HTLCs and pending changes are the same for all active commitments, so we don't need to loop through all of them.
    fun isIdle(): Boolean = active.first().isIdle(changes)
    fun hasNoPendingHtlcsOrFeeUpdate(): Boolean = active.first().hasNoPendingHtlcsOrFeeUpdate(changes)
    fun timedOutOutgoingHtlcs(currentHeight: Long): Set<UpdateAddHtlc> = active.first().timedOutOutgoingHtlcs(currentHeight)
    fun almostTimedOutIncomingHtlcs(currentHeight: Long, fulfillSafety: CltvExpiryDelta): Set<UpdateAddHtlc> = active.first().almostTimedOutIncomingHtlcs(currentHeight, fulfillSafety, changes)
    fun getOutgoingHtlcCrossSigned(htlcId: Long): UpdateAddHtlc? = active.first().getOutgoingHtlcCrossSigned(htlcId)
    fun getIncomingHtlcCrossSigned(htlcId: Long): UpdateAddHtlc? = active.first().getIncomingHtlcCrossSigned(htlcId)
    // @formatter:on

    fun sendAdd(cmd: CMD_ADD_HTLC, paymentId: UUID, blockHeight: Long): Either<ChannelException, Pair<Commitments, UpdateAddHtlc>> {
        val maxExpiry = Channel.MAX_CLTV_EXPIRY_DELTA.toCltvExpiry(blockHeight)
        // we don't want to use too high a refund timeout, because our funds will be locked during that time if the payment is never fulfilled
        if (cmd.cltvExpiry >= maxExpiry) {
            return Either.Left(ExpiryTooBig(channelId, maximum = maxExpiry, actual = cmd.cltvExpiry, blockCount = blockHeight))
        }

        // even if remote advertises support for 0 msat htlc, we limit ourselves to values strictly positive, hence the max(1 msat)
        val htlcMinimum = params.remoteParams.htlcMinimum.coerceAtLeast(1.msat)
        if (cmd.amount < htlcMinimum) {
            return Either.Left(HtlcValueTooSmall(channelId, minimum = htlcMinimum, actual = cmd.amount))
        }

        // let's compute the current commitment *as seen by them* with this change taken into account
        val add = UpdateAddHtlc(channelId, changes.localNextHtlcId, cmd.amount, cmd.paymentHash, cmd.cltvExpiry, cmd.onion)
        // we increment the local htlc index and add an entry to the origins map
        val changes1 = changes.addLocalProposal(add).copy(localNextHtlcId = changes.localNextHtlcId + 1)
        val payments1 = payments + mapOf(add.id to paymentId)
        val failure = active.map { it.canSendAdd(cmd.amount, params, changes1).left }.firstOrNull()
        return failure?.let { Either.Left(it) } ?: Either.Right(Pair(copy(changes = changes1, payments = payments1), add))
    }

    fun receiveAdd(add: UpdateAddHtlc): Either<ChannelException, Commitments> {
        if (add.id != changes.remoteNextHtlcId) {
            return Either.Left(UnexpectedHtlcId(channelId, expected = changes.remoteNextHtlcId, actual = add.id))
        }

        // we used to not enforce a strictly positive minimum, hence the max(1 msat)
        val htlcMinimum = params.localParams.htlcMinimum.coerceAtLeast(1.msat)
        if (add.amountMsat < htlcMinimum) {
            return Either.Left(HtlcValueTooSmall(channelId, minimum = htlcMinimum, actual = add.amountMsat))
        }

        val changes1 = changes.addRemoteProposal(add).copy(remoteNextHtlcId = changes.remoteNextHtlcId + 1)
        val failure = active.map { it.canReceiveAdd(add.amountMsat, params, changes1).left }.firstOrNull()
        return failure?.let { Either.Left(it) } ?: Either.Right(copy(changes = changes1))
    }

    fun sendFulfill(cmd: CMD_FULFILL_HTLC): Either<ChannelException, Pair<Commitments, UpdateFulfillHtlc>> {
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

    fun sendFail(cmd: CMD_FAIL_HTLC, nodeSecret: PrivateKey): Either<ChannelException, Pair<Commitments, UpdateFailHtlc>> {
        val htlc = getIncomingHtlcCrossSigned(cmd.id) ?: return Either.Left(UnknownHtlcId(channelId, cmd.id))
        return when {
            // we have already sent a fail/fulfill for this htlc
            CommitmentChanges.alreadyProposed(changes.localChanges.proposed, htlc.id) -> Either.Left(UnknownHtlcId(channelId, cmd.id))
            else -> {
                when (val result = OutgoingPaymentPacket.buildHtlcFailure(nodeSecret, htlc.paymentHash, htlc.onionRoutingPacket, cmd.reason)) {
                    is Either.Right -> {
                        val fail = UpdateFailHtlc(channelId, cmd.id, result.value)
                        Either.Right(Pair(copy(changes = changes.addLocalProposal(fail)), fail))
                    }
                    is Either.Left -> Either.Left(CannotExtractSharedSecret(channelId, htlc))
                }
            }
        }
    }

    fun sendFailMalformed(cmd: CMD_FAIL_MALFORMED_HTLC): Either<ChannelException, Pair<Commitments, UpdateFailMalformedHtlc>> {
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

    fun sendFee(cmd: CMD_UPDATE_FEE): Either<ChannelException, Pair<Commitments, UpdateFee>> {
        if (!params.localParams.isInitiator) return Either.Left(NonInitiatorCannotSendUpdateFee(channelId))
        // let's compute the current commitment *as seen by them* with this change taken into account
        val fee = UpdateFee(channelId, cmd.feerate)
        // update_fee replace each other, so we can remove previous ones
        val changes1 = changes.copy(localChanges = changes.localChanges.copy(proposed = changes.localChanges.proposed.filterNot { it is UpdateFee } + fee))
        val failure = active.map { it.canSendFee(params, changes1).left }.firstOrNull()
        return failure?.let { Either.Left(it) } ?: Either.Right(Pair(copy(changes = changes1), fee))
    }

    fun receiveFee(fee: UpdateFee, feerateTolerance: FeerateTolerance): Either<ChannelException, Commitments> {
        if (params.localParams.isInitiator) return Either.Left(NonInitiatorCannotSendUpdateFee(channelId))
        if (fee.feeratePerKw < FeeratePerKw.MinimumFeeratePerKw) return Either.Left(FeerateTooSmall(channelId, remoteFeeratePerKw = fee.feeratePerKw))
        if (Helpers.isFeeDiffTooHigh(FeeratePerKw.CommitmentFeerate, fee.feeratePerKw, feerateTolerance)) return Either.Left(FeerateTooDifferent(channelId, FeeratePerKw.CommitmentFeerate, fee.feeratePerKw))
        val changes1 = changes.copy(remoteChanges = changes.remoteChanges.copy(proposed = changes.remoteChanges.proposed.filterNot { it is UpdateFee } + fee))
        val failure = active.map { it.canReceiveFee(params, changes1).left }.firstOrNull()
        return failure?.let { Either.Left(it) } ?: Either.Right(copy(changes = changes1))
    }

    fun sendCommit(channelKeys: KeyManager.ChannelKeys, log: MDCLogger): Either<ChannelException, Pair<Commitments, List<CommitSig>>> {
        val remoteNextPerCommitmentPoint = remoteNextCommitInfo.right ?: return Either.Left(CannotSignBeforeRevocation(channelId))
        if (!changes.localHasChanges()) return Either.Left(CannotSignWithoutChanges(channelId))
        val (active1, sigs) = active.map { it.sendCommit(channelKeys, params, changes, remoteNextPerCommitmentPoint, log) }.unzip()
        val commitments1 = copy(
            active = active1,
            remoteNextCommitInfo = Either.Left(WaitingForRevocation(localCommitIndex)),
            changes = changes.copy(
                localChanges = changes.localChanges.copy(proposed = emptyList(), signed = changes.localChanges.proposed),
                remoteChanges = changes.remoteChanges.copy(acked = emptyList(), signed = changes.remoteChanges.acked)
            )
        )
        val sigs1 = if (sigs.size > 1) {
            sigs.map { sig ->
                sig.copy(tlvStream = sig.tlvStream.copy(records = buildSet {
                    addAll(sig.tlvStream.records)
                    add(CommitSigTlv.Batch(sigs.size))
                }))
            }
        } else sigs
        return Either.Right(Pair(commitments1, sigs1))
    }

    fun receiveCommit(commits: List<CommitSig>, channelKeys: KeyManager.ChannelKeys, log: MDCLogger): Either<ChannelException, Pair<Commitments, RevokeAndAck>> {
        // We may receive more commit_sig than the number of active commitments, because there can be a race where we send splice_locked
        // while our peer is sending us a batch of commit_sig. When that happens, we simply need to discard the commit_sig that belong
        // to commitments we deactivated.
        if (commits.size < active.size) {
            return Either.Left(CommitSigCountMismatch(channelId, active.size, commits.size))
        }
        // Signatures are sent in order (most recent first), calling `zip` will drop trailing sigs that are for deactivated/pruned commitments.
        val active1 = active.zip(commits).map {
            when (val commitment1 = it.first.receiveCommit(channelKeys, params, changes, it.second, log)) {
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
            )
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
        val active1 = active.map { it.copy(remoteCommit = it.nextRemoteCommit!!.commit, nextRemoteCommit = null) }
        val commitments1 = this.copy(
            active = active1,
            changes = changes.copy(
                localChanges = changes.localChanges.copy(signed = emptyList(), acked = changes.localChanges.acked + changes.localChanges.signed),
                remoteChanges = changes.remoteChanges.copy(signed = emptyList()),
            ),
            remoteNextCommitInfo = Either.Right(revocation.nextPerCommitmentPoint),
            remotePerCommitmentSecrets = remotePerCommitmentSecrets.addHash(revocation.perCommitmentSecret.value, 0xFFFFFFFFFFFFL - remoteCommitIndex),
            payments = payments1
        )
        return Either.Right(Pair(commitments1, actions.toList()))
    }

    private fun ChannelContext.updateFundingStatus(fundingTxId: ByteVector32, updateMethod: (Commitment, Long) -> Commitment): Either<Commitments, Pair<Commitments, Commitment>> {
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

    fun ChannelContext.updateLocalFundingStatus(fundingTxId: ByteVector32, status: LocalFundingStatus): Either<Commitments, Pair<Commitments, Commitment>> =
        updateFundingStatus(fundingTxId) { c: Commitment, _: Long ->
            if (c.fundingTxId == fundingTxId) {
                logger.debug { "setting localFundingStatus=${status::class.simpleName} for fundingTxId=$fundingTxId" }
                c.copy(localFundingStatus = status)
            } else c
        }

    fun ChannelContext.updateRemoteFundingStatus(fundingTxId: ByteVector32): Either<Commitments, Pair<Commitments, Commitment>> =
        updateFundingStatus(fundingTxId) { c: Commitment, fundingTxIndex: Long ->
            // all funding older than this one are considered locked
            if (c.fundingTxId == fundingTxId || c.fundingTxIndex < fundingTxIndex) {
                logger.debug { "setting remoteFundingStatus=${RemoteFundingStatus.Locked::class.simpleName} for fundingTxId=$fundingTxId" }
                c.copy(remoteFundingStatus = RemoteFundingStatus.Locked)
            } else c
        }

    /**
     * Commitments are considered inactive when they have been superseded by a newer commitment, but can still potentially
     * end up on-chain. This is a consequence of using zero-conf. Inactive commitments will be cleaned up by
     * [pruneCommitments], when the next funding tx confirms.
     */
    private fun ChannelContext.deactivateCommitments(): Commitments {
        // When a commitment is locked, it implicitly locks all previous commitments.
        // This ensures that we only have to send splice_locked for the latest commitment instead of sending it for every commitment.
        // A side-effect is that previous commitments that are implicitly locked don't necessarily have their status correctly set.
        // That's why we look at locked commitments separately and then select the one with the oldest fundingTxIndex.
        val lastLocalLocked = active.find { staticParams.useZeroConf || it.localFundingStatus is LocalFundingStatus.ConfirmedFundingTx }
        val lastRemoteLocked = active.find { it.remoteFundingStatus == RemoteFundingStatus.Locked }
        val lastLocked = when {
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
        return when (lastLocked) {
            is Commitment -> {
                // all commitments older than this one are inactive
                val inactive1 = active.filter { it.fundingTxId != lastLocked.fundingTxId && it.fundingTxIndex <= lastLocked.fundingTxIndex }
                inactive1.forEach { logger.info { "deactivating commitment fundingTxIndex=${it.fundingTxIndex} fundingTxId=${it.fundingTxId}" } }
                copy(
                    active = active - inactive1.toSet(),
                    inactive = inactive1 + inactive.toSet()
                )
            }
            else -> this@Commitments
        }
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

    /**
     * Find the corresponding commitment, based on a spending transaction.
     *
     * @param spendingTx A transaction that may spend a current or former funding tx
     */
    fun resolveCommitment(spendingTx: Transaction): Commitment? {
        return all.find { commitment -> spendingTx.txIn.map { it.outPoint }.contains(commitment.commitInput.outPoint) }
    }

    companion object {

        val ANCHOR_AMOUNT = 330.sat
        const val COMMIT_WEIGHT = 1124
        const val HTLC_OUTPUT_WEIGHT = 172
        const val HTLC_TIMEOUT_WEIGHT = 666
        const val HTLC_SUCCESS_WEIGHT = 706

        fun makeLocalTxs(
            channelKeys: KeyManager.ChannelKeys,
            commitTxNumber: Long,
            localParams: LocalParams,
            remoteParams: RemoteParams,
            fundingTxIndex: Long,
            remoteFundingPubKey: PublicKey,
            commitmentInput: Transactions.InputInfo,
            localPerCommitmentPoint: PublicKey,
            spec: CommitmentSpec
        ): Pair<CommitTx, List<HtlcTx>> {
            val localDelayedPaymentPubkey = channelKeys.delayedPaymentBasepoint.deriveForCommitment(localPerCommitmentPoint)
            val localHtlcPubkey = channelKeys.htlcBasepoint.deriveForCommitment(localPerCommitmentPoint)
            val remotePaymentPubkey = remoteParams.paymentBasepoint
            val remoteHtlcPubkey = remoteParams.htlcBasepoint.deriveForCommitment(localPerCommitmentPoint)
            val localRevocationPubkey = remoteParams.revocationBasepoint.deriveForRevocation(localPerCommitmentPoint)
            val localPaymentBasepoint = channelKeys.paymentBasepoint
            val outputs = makeCommitTxOutputs(
                channelKeys.fundingPubKey(fundingTxIndex),
                remoteFundingPubKey,
                localParams.isInitiator,
                localParams.dustLimit,
                localRevocationPubkey,
                remoteParams.toSelfDelay,
                localDelayedPaymentPubkey,
                remotePaymentPubkey,
                localHtlcPubkey,
                remoteHtlcPubkey,
                spec
            )
            val commitTx = Transactions.makeCommitTx(commitmentInput, commitTxNumber, localPaymentBasepoint, remoteParams.paymentBasepoint, localParams.isInitiator, outputs)
            val htlcTxs = Transactions.makeHtlcTxs(commitTx.tx, localParams.dustLimit, localRevocationPubkey, remoteParams.toSelfDelay, localDelayedPaymentPubkey, spec.feerate, outputs)
            return Pair(commitTx, htlcTxs)
        }

        fun makeRemoteTxs(
            channelKeys: KeyManager.ChannelKeys,
            commitTxNumber: Long,
            localParams: LocalParams,
            remoteParams: RemoteParams,
            fundingTxIndex: Long,
            remoteFundingPubKey: PublicKey,
            commitmentInput: Transactions.InputInfo,
            remotePerCommitmentPoint: PublicKey,
            spec: CommitmentSpec
        ): Pair<CommitTx, List<HtlcTx>> {
            val localPaymentPubkey = channelKeys.paymentBasepoint
            val localHtlcPubkey = channelKeys.htlcBasepoint.deriveForCommitment(remotePerCommitmentPoint)
            val remoteDelayedPaymentPubkey = remoteParams.delayedPaymentBasepoint.deriveForCommitment(remotePerCommitmentPoint)
            val remoteHtlcPubkey = remoteParams.htlcBasepoint.deriveForCommitment(remotePerCommitmentPoint)
            val remoteRevocationPubkey = channelKeys.revocationBasepoint.deriveForRevocation(remotePerCommitmentPoint)
            val outputs = makeCommitTxOutputs(
                remoteFundingPubKey,
                channelKeys.fundingPubKey(fundingTxIndex),
                !localParams.isInitiator,
                remoteParams.dustLimit,
                remoteRevocationPubkey,
                localParams.toSelfDelay,
                remoteDelayedPaymentPubkey,
                localPaymentPubkey,
                remoteHtlcPubkey,
                localHtlcPubkey,
                spec
            )
            // NB: we are creating the remote commit tx, so local/remote parameters are inverted.
            val commitTx = Transactions.makeCommitTx(commitmentInput, commitTxNumber, remoteParams.paymentBasepoint, localPaymentPubkey, !localParams.isInitiator, outputs)
            val htlcTxs = Transactions.makeHtlcTxs(commitTx.tx, remoteParams.dustLimit, remoteRevocationPubkey, localParams.toSelfDelay, remoteDelayedPaymentPubkey, spec.feerate, outputs)
            return Pair(commitTx, htlcTxs)
        }
    }
}
