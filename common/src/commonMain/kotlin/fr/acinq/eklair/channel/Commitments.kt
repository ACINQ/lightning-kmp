package fr.acinq.eklair.channel

import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.ByteVector64
import fr.acinq.bitcoin.PublicKey
import fr.acinq.eklair.CltvExpiryDelta
import fr.acinq.eklair.MilliSatoshi
import fr.acinq.eklair.crypto.ShaChain
import fr.acinq.eklair.payment.relay.Origin
import fr.acinq.eklair.transactions.*
import fr.acinq.eklair.transactions.Transactions.TransactionWithInputInfo
import fr.acinq.eklair.transactions.Transactions.commitTxFee
import fr.acinq.eklair.transactions.Transactions.commitTxFeeMsat
import fr.acinq.eklair.transactions.Transactions.htlcOutputFee
import fr.acinq.eklair.transactions.Transactions.offeredHtlcTrimThreshold
import fr.acinq.eklair.transactions.Transactions.receivedHtlcTrimThreshold
import fr.acinq.eklair.utils.*
import fr.acinq.eklair.wire.*
import kotlin.experimental.and

// @formatter:off
data class LocalChanges(val proposed: List<UpdateMessage>, val signed: List<UpdateMessage>, val acked: List<UpdateMessage>) {
    val all: List<UpdateMessage> get() = proposed + signed + acked
}
data class RemoteChanges(val proposed: List<UpdateMessage>, val acked: List<UpdateMessage>, val signed: List<UpdateMessage>)
data class Changes(val ourChanges: LocalChanges, val theirChanges: RemoteChanges)
data class HtlcTxAndSigs(val txinfo: TransactionWithInputInfo, val localSig: ByteVector64, val remoteSig: ByteVector64)
data class PublishableTxs(val commitTx: TransactionWithInputInfo.CommitTx, val htlcTxsAndSigs: List<HtlcTxAndSigs>)
data class LocalCommit(val index: Long, val spec: CommitmentSpec, val publishableTxs: PublishableTxs)
data class RemoteCommit(val index: Long, val spec: CommitmentSpec, val txid: ByteVector32, val remotePerCommitmentPoint: PublicKey)
data class WaitingForRevocation(val nextRemoteCommit: RemoteCommit, val sent: CommitSig, val sentAfterLocalCommitIndex: Long, val reSignAsap: Boolean = false)
// @formatter:on

/**
 * about remoteNextCommitInfo:
 * we either:
 * - have built and signed their next commit tx with their next revocation hash which can now be discarded
 * - have their next per-commitment point
 * So, when we've signed and sent a commit message and are waiting for their revocation message,
 * theirNextCommitInfo is their next commit tx. The rest of the time, it is their next per-commitment point
 */
data class Commitments(
    val channelVersion: ChannelVersion,
    val localParams: LocalParams,
    val remoteParams: RemoteParams,
    val channelFlags: Byte,
    val localCommit: LocalCommit,
    val remoteCommit: RemoteCommit,
    val localChanges: LocalChanges,
    val remoteChanges: RemoteChanges,
    val localNextHtlcId: Long,
    val remoteNextHtlcId: Long,
    val originChannels: Map<Long, Origin>, // for outgoing htlcs relayed through us, details about the corresponding incoming htlcs
    val remoteNextCommitInfo: RemoteNextCommitInfo,
    val commitInput: Transactions.InputInfo,
    val remotePerCommitmentSecrets: ShaChain,
    val channelId: ByteVector32
) {

    sealed class RemoteNextCommitInfo {
        data class WFR(val wfr: WaitingForRevocation) : RemoteNextCommitInfo()
        data class PK(val pk: PublicKey) : RemoteNextCommitInfo()
    }

    fun hasNoPendingHtlcs(): Boolean = localCommit.spec.htlcs.isEmpty() && remoteCommit.spec.htlcs.isEmpty() && remoteNextCommitInfo is RemoteNextCommitInfo.PK

    fun timedOutOutgoingHtlcs(blockheight: Long): Set<UpdateAddHtlc> {
        fun expired(add: UpdateAddHtlc) = blockheight >= add.cltvExpiry.toLong()

        val thisCommitAdds = localCommit.spec.htlcs.filterIsInstance<OutgoingHtlc>().map { it.add } .filter(::expired).toSet() +
                remoteCommit.spec.htlcs.filterIsInstance<IncomingHtlc>().map { it.add } .filter(::expired).toSet()

        val wfr = (remoteNextCommitInfo as? RemoteNextCommitInfo.WFR)?.wfr
        return if (wfr == null) thisCommitAdds
        else thisCommitAdds + wfr.nextRemoteCommit.spec.htlcs.filterIsInstance<IncomingHtlc>() .map { it.add } .filter(::expired).toSet()
    }

    /**
     * HTLCs that are close to timing out upstream are potentially dangerous. If we received the pre-image for those
     * HTLCs, we need to get a remote signed updated commitment that removes this HTLC.
     * Otherwise when we get close to the upstream timeout, we risk an on-chain race condition between their HTLC timeout
     * and our HTLC success in case of a force-close.
     */
    fun almostTimedOutIncomingHtlcs(blockheight: Long, fulfillSafety: CltvExpiryDelta): Set<UpdateAddHtlc> =
        localCommit.spec.htlcs
            .filterIsInstance<IncomingHtlc>()
            .map { it.add }
            .filter { blockheight >= (it.cltvExpiry - fulfillSafety).toLong() }
            .toSet()

    /**
     * Add a change to our proposed change list.
     *
     * @param commitments current commitments.
     * @param proposal    proposed change to add.
     * @return an updated commitment instance.
     */
    fun addLocalProposal(proposal: UpdateMessage): Commitments =
        copy(localChanges = localChanges.copy(proposed = localChanges.proposed + proposal))

    fun addRemoteProposal(proposal: UpdateMessage): Commitments =
        copy(remoteChanges = remoteChanges.copy(proposed = remoteChanges.proposed + proposal))

    val announceChannel: Boolean = (channelFlags and 0x01).toInt() != 0

    val availableBalanceForSend: MilliSatoshi by lazy {
        // we need to base the next current commitment on the last sig we sent, even if we didn't yet receive their revocation
        val remoteCommit1 = (remoteNextCommitInfo as? RemoteNextCommitInfo.WFR)?.wfr?.nextRemoteCommit ?: remoteCommit
        val reduced = CommitmentSpec.reduce(remoteCommit1.spec, remoteChanges.acked, localChanges.proposed)
        val balanceNoFees = (reduced.toRemote - remoteParams.channelReserve.toMilliSatoshi()).coerceAtLeast(0.msat)
        if (localParams.isFunder) {
            // The funder always pays the on-chain fees, so we must subtract that from the amount we can send.
            val commitFees = commitTxFeeMsat(remoteParams.dustLimit, reduced)
            // the funder needs to keep an extra reserve to be able to handle fee increase without getting the channel stuck
            // (see https://github.com/lightningnetwork/lightning-rfc/issues/728)
            val funderFeeReserve = htlcOutputFee(2 * reduced.feeratePerKw)
            val htlcFees = htlcOutputFee(reduced.feeratePerKw)
            if (balanceNoFees - commitFees < offeredHtlcTrimThreshold(remoteParams.dustLimit, reduced).toMilliSatoshi()) {
                // htlc will be trimmed
                (balanceNoFees - commitFees - funderFeeReserve).coerceAtLeast(0.msat)
            } else {
                // htlc will have an output in the commitment tx, so there will be additional fees.
                (balanceNoFees - commitFees - funderFeeReserve - htlcFees).coerceAtLeast(0.msat)
            }
        } else {
            // The fundee doesn't pay on-chain fees.
            balanceNoFees
        }
    }

    val availableBalanceForReceive: MilliSatoshi by lazy {
        val reduced = CommitmentSpec.reduce(localCommit.spec, localChanges.acked, remoteChanges.proposed)
        val balanceNoFees = (reduced.toRemote - localParams.channelReserve.toMilliSatoshi()).coerceAtLeast(0.msat)
        if (localParams.isFunder) {
            // The fundee doesn't pay on-chain fees so we don't take those into account when receiving.
            balanceNoFees
        } else {
            // The funder always pays the on-chain fees, so we must subtract that from the amount we can receive.
            val commitFees = commitTxFeeMsat(localParams.dustLimit, reduced)
            // we expect the funder to keep an extra reserve to be able to handle fee increase without getting the channel stuck
            // (see https://github.com/lightningnetwork/lightning-rfc/issues/728)
            val funderFeeReserve = htlcOutputFee(2 * reduced.feeratePerKw)
            val htlcFees = htlcOutputFee(reduced.feeratePerKw)
            if (balanceNoFees - commitFees < receivedHtlcTrimThreshold(localParams.dustLimit, reduced).toMilliSatoshi()) {
                // htlc will be trimmed
                (balanceNoFees - commitFees - funderFeeReserve).coerceAtLeast(0.msat)
            } else {
                // htlc will have an output in the commitment tx, so there will be additional fees.
                (balanceNoFees - commitFees - funderFeeReserve - htlcFees).coerceAtLeast(0.msat)
            }
        }
    }

    companion object {

        fun alreadyProposed(changes: List<UpdateMessage>, id: Long): Boolean = changes.any {
            when (it) {
                is UpdateFulfillHtlc -> id == it.id
                is UpdateFailHtlc -> id == it.id
                is UpdateFailMalformedHtlc -> id == it.id
                else -> false
            }
        }

        /**
         *
         * @param commitments current commitments
         * @param cmd         add HTLC command
         * @return either Left(failure, error message) where failure is a failure message (see BOLT #4 and the Failure Message class) or Right((new commitments, updateAddHtlc)
         */
        @OptIn(ExperimentalUnsignedTypes::class)
        fun sendAdd(commitments: Commitments, cmd: CMD_ADD_HTLC, origin: Origin, blockHeight: Long): Result<Pair<Commitments, UpdateAddHtlc>> {
            // our counterparty needs a reasonable amount of time to pull the funds from downstream before we can get refunded (see BOLT 2 and BOLT 11 for a calculation and rationale)
            val minExpiry = Channel.MIN_CLTV_EXPIRY_DELTA.toCltvExpiry(blockHeight)
            if (cmd.cltvExpiry < minExpiry) {
                return Result.Failure(ExpiryTooSmall(commitments.channelId, minimum = minExpiry, actual = cmd.cltvExpiry, blockCount = blockHeight))
            }
            val maxExpiry = Channel.MAX_CLTV_EXPIRY_DELTA.toCltvExpiry(blockHeight)
            // we don't want to use too high a refund timeout, because our funds will be locked during that time if the payment is never fulfilled
            if (cmd.cltvExpiry >= maxExpiry) {
                return Result.Failure(ExpiryTooBig(commitments.channelId, maximum = maxExpiry, actual = cmd.cltvExpiry, blockCount = blockHeight))
            }

            // even if remote advertises support for 0 msat htlc, we limit ourselves to values strictly positive, hence the max(1 msat)
            val htlcMinimum = commitments.remoteParams.htlcMinimum.coerceAtLeast(1.msat)
            if (cmd.amount < htlcMinimum) {
                return Result.Failure(HtlcValueTooSmall(commitments.channelId, minimum = htlcMinimum, actual = cmd.amount))
            }

            // let's compute the current commitment *as seen by them* with this change taken into account
            val add = UpdateAddHtlc(commitments.channelId, commitments.localNextHtlcId, cmd.amount, cmd.paymentHash, cmd.cltvExpiry, cmd.onion)
            // we increment the local htlc index and add an entry to the origins map
            val commitments1 = commitments.addLocalProposal(add).copy(localNextHtlcId = commitments.localNextHtlcId + 1, originChannels = commitments.originChannels + mapOf(add.id to origin))
            // we need to base the next current commitment on the last sig we sent, even if we didn't yet receive their revocation
            val remoteCommit1 = (commitments1.remoteNextCommitInfo as? RemoteNextCommitInfo.WFR)?.wfr?.nextRemoteCommit ?: commitments1.remoteCommit
            val reduced = CommitmentSpec.reduce(remoteCommit1.spec, commitments1.remoteChanges.acked, commitments1.localChanges.proposed)
            // the HTLC we are about to create is outgoing, but from their point of view it is incoming
            val outgoingHtlcs = reduced.htlcs.filterIsInstance<IncomingHtlc>()

            // note that the funder pays the fee, so if sender != funder, both sides will have to afford this payment
            val fees = commitTxFee(commitments1.remoteParams.dustLimit, reduced)
            // the funder needs to keep an extra reserve to be able to handle fee increase without getting the channel stuck
            // (see https://github.com/lightningnetwork/lightning-rfc/issues/728)
            val funderFeeReserve = htlcOutputFee(2 * reduced.feeratePerKw)
            val missingForSender = reduced.toRemote - commitments1.remoteParams.channelReserve.toMilliSatoshi() - (if (commitments1.localParams.isFunder) fees.toMilliSatoshi() + funderFeeReserve else 0.msat)
            val missingForReceiver = reduced.toLocal - commitments1.localParams.channelReserve.toMilliSatoshi() - (if (commitments1.localParams.isFunder) 0.msat else fees.toMilliSatoshi())
            if (missingForSender < 0.msat) {
                return Result.Failure(InsufficientFunds(commitments.channelId, amount = cmd.amount, missing = -missingForSender.truncateToSatoshi(), reserve = commitments1.remoteParams.channelReserve, fees = if (commitments1.localParams.isFunder) fees else 0.sat))
            } else if (missingForReceiver < 0.msat) {
                if (commitments.localParams.isFunder) {
                    // receiver is fundee; it is ok if it can't maintain its channel_reserve for now, as long as its balance is increasing, which is the case if it is receiving a payment
                } else {
                    return Result.Failure(RemoteCannotAffordFeesForNewHtlc(commitments.channelId, amount = cmd.amount, missing = -missingForReceiver.truncateToSatoshi(), reserve = commitments1.remoteParams.channelReserve, fees = fees))
                }
            }

            // NB: we need the `toSeq` because otherwise duplicate amountMsat would be removed (since outgoingHtlcs is a Set).
            val htlcValueInFlight = outgoingHtlcs.toList().map { it.add.amountMsat }.sum()
            if (commitments1.remoteParams.maxHtlcValueInFlightMsat < htlcValueInFlight.msat.toULong()) {
                // TODO: this should be a specific UPDATE error
                return Result.Failure(HtlcValueTooHighInFlight(commitments.channelId, maximum = commitments1.remoteParams.maxHtlcValueInFlightMsat, actual = htlcValueInFlight))
            }

            if (outgoingHtlcs.size > commitments1.remoteParams.maxAcceptedHtlcs) {
                return Result.Failure(TooManyAcceptedHtlcs(commitments.channelId, maximum = commitments1.remoteParams.maxAcceptedHtlcs.toLong()))
            }

            return Result.Success(Pair(commitments1, add))
        }

//        def receiveAdd(commitments: Commitments, add: UpdateAddHtlc): Try[Commitments] = Try {
//            if (add.id != commitments.remoteNextHtlcId) {
//                throw UnexpectedHtlcId(commitments.channelId, expected = commitments.remoteNextHtlcId, actual = add.id)
//            }
//
//            // we used to not enforce a strictly positive minimum, hence the max(1 msat)
//            val htlcMinimum = commitments.localParams.htlcMinimum.max(1 msat)
//            if (add.amountMsat < htlcMinimum) {
//                throw HtlcValueTooSmall(commitments.channelId, minimum = htlcMinimum, actual = add.amountMsat)
//            }
//
//            // let's compute the current commitment *as seen by us* including this change
//            val commitments1 = addRemoteProposal(commitments, add).copy(remoteNextHtlcId = commitments.remoteNextHtlcId + 1)
//            val reduced = CommitmentSpec.reduce(commitments1.localCommit.spec, commitments1.localChanges.acked, commitments1.remoteChanges.proposed)
//            val incomingHtlcs = reduced.htlcs.collect(incoming)
//
//            // note that the funder pays the fee, so if sender != funder, both sides will have to afford this payment
//            val fees = commitTxFee(commitments1.remoteParams.dustLimit, reduced)
//            // NB: we don't enforce the funderFeeReserve (see sendAdd) because it would confuse a remote funder that doesn't have this mitigation in place
//            // We could enforce it once we're confident a large portion of the network implements it.
//            val missingForSender = reduced.toRemote - commitments1.localParams.channelReserve - (if (commitments1.localParams.isFunder) 0.sat else fees)
//            val missingForReceiver = reduced.toLocal - commitments1.remoteParams.channelReserve - (if (commitments1.localParams.isFunder) fees else 0.sat)
//            if (missingForSender < 0.sat) {
//                throw InsufficientFunds(commitments.channelId, amount = add.amountMsat, missing = -missingForSender.truncateToSatoshi, reserve = commitments1.localParams.channelReserve, fees = if (commitments1.localParams.isFunder) 0.sat else fees)
//            } else if (missingForReceiver < 0.sat) {
//                if (commitments.localParams.isFunder) {
//                    throw CannotAffordFees(commitments.channelId, missing = -missingForReceiver.truncateToSatoshi, reserve = commitments1.remoteParams.channelReserve, fees = fees)
//                } else {
//                    // receiver is fundee; it is ok if it can't maintain its channel_reserve for now, as long as its balance is increasing, which is the case if it is receiving a payment
//                }
//            }
//
//            // NB: we need the `toSeq` because otherwise duplicate amountMsat would be removed (since incomingHtlcs is a Set).
//            val htlcValueInFlight = incomingHtlcs.toSeq.map(_.amountMsat).sum
//            if (commitments1.localParams.maxHtlcValueInFlightMsat < htlcValueInFlight) {
//                throw HtlcValueTooHighInFlight(commitments.channelId, maximum = commitments1.localParams.maxHtlcValueInFlightMsat, actual = htlcValueInFlight)
//            }
//
//            if (incomingHtlcs.size > commitments1.localParams.maxAcceptedHtlcs) {
//                throw TooManyAcceptedHtlcs(commitments.channelId, maximum = commitments1.localParams.maxAcceptedHtlcs)
//            }
//
//            commitments1
//        }
//
//        def getOutgoingHtlcCrossSigned(commitments: Commitments, htlcId: Long): Option[UpdateAddHtlc] = for {
//            localSigned <- commitments.remoteNextCommitInfo.left.toOption.map(_.nextRemoteCommit).getOrElse(commitments.remoteCommit).spec.findIncomingHtlcById(htlcId)
//            remoteSigned <- commitments.localCommit.spec.findOutgoingHtlcById(htlcId)
//        } yield {
//            require(localSigned.add == remoteSigned.add)
//            localSigned.add
//        }
//
//        def getIncomingHtlcCrossSigned(commitments: Commitments, htlcId: Long): Option[UpdateAddHtlc] = for {
//            localSigned <- commitments.remoteNextCommitInfo.left.toOption.map(_.nextRemoteCommit).getOrElse(commitments.remoteCommit).spec.findOutgoingHtlcById(htlcId)
//            remoteSigned <- commitments.localCommit.spec.findIncomingHtlcById(htlcId)
//        } yield {
//            require(localSigned.add == remoteSigned.add)
//            localSigned.add
//        }
//
//        def sendFulfill(commitments: Commitments, cmd: CMD_FULFILL_HTLC): Try[(Commitments, UpdateFulfillHtlc)] =
//        getIncomingHtlcCrossSigned(commitments, cmd.id) match {
//            case Some(htlc) if alreadyProposed(commitments.localChanges.proposed, htlc.id) =>
//            // we have already sent a fail/fulfill for this htlc
//            Failure(UnknownHtlcId(commitments.channelId, cmd.id))
//            case Some(htlc) if htlc.paymentHash == sha256(cmd.r) =>
//            val fulfill = UpdateFulfillHtlc(commitments.channelId, cmd.id, cmd.r)
//            val commitments1 = addLocalProposal(commitments, fulfill)
//            Success((commitments1, fulfill))
//            case Some(_) => Failure(InvalidHtlcPreimage(commitments.channelId, cmd.id))
//            case None => Failure(UnknownHtlcId(commitments.channelId, cmd.id))
//        }
//
//        def receiveFulfill(commitments: Commitments, fulfill: UpdateFulfillHtlc): Try[(Commitments, Origin, UpdateAddHtlc)] =
//        getOutgoingHtlcCrossSigned(commitments, fulfill.id) match {
//            case Some(htlc) if htlc.paymentHash == sha256(fulfill.paymentPreimage) => Try((addRemoteProposal(commitments, fulfill), commitments.originChannels(fulfill.id), htlc))
//            case Some(_) => Failure(InvalidHtlcPreimage(commitments.channelId, fulfill.id))
//            case None => Failure(UnknownHtlcId(commitments.channelId, fulfill.id))
//        }
//
//        def sendFail(commitments: Commitments, cmd: CMD_FAIL_HTLC, nodeSecret: PrivateKey): Try[(Commitments, UpdateFailHtlc)] =
//        getIncomingHtlcCrossSigned(commitments, cmd.id) match {
//            case Some(htlc) if alreadyProposed(commitments.localChanges.proposed, htlc.id) =>
//            // we have already sent a fail/fulfill for this htlc
//            Failure(UnknownHtlcId(commitments.channelId, cmd.id))
//            case Some(htlc) =>
//            // we need the shared secret to build the error packet
//            Sphinx.PaymentPacket.peel(nodeSecret, htlc.paymentHash, htlc.onionRoutingPacket) match {
//                case Right(Sphinx.DecryptedPacket(_, _, sharedSecret)) =>
//                val reason = cmd.reason match {
//                    case Left(forwarded) => Sphinx.FailurePacket.wrap(forwarded, sharedSecret)
//                    case Right(failure) => Sphinx.FailurePacket.create(sharedSecret, failure)
//                }
//                val fail = UpdateFailHtlc(commitments.channelId, cmd.id, reason)
//                val commitments1 = addLocalProposal(commitments, fail)
//                Success((commitments1, fail))
//                case Left(_) => Failure(CannotExtractSharedSecret(commitments.channelId, htlc))
//            }
//            case None => Failure(UnknownHtlcId(commitments.channelId, cmd.id))
//        }
//
//        def sendFailMalformed(commitments: Commitments, cmd: CMD_FAIL_MALFORMED_HTLC): Try[(Commitments, UpdateFailMalformedHtlc)] = {
//            // BADONION bit must be set in failure_code
//            if ((cmd.failureCode & FailureMessageCodecs.BADONION) == 0) {
//                Failure(InvalidFailureCode(commitments.channelId))
//            } else {
//                getIncomingHtlcCrossSigned(commitments, cmd.id) match {
//                    case Some(htlc) if alreadyProposed(commitments.localChanges.proposed, htlc.id) =>
//                    // we have already sent a fail/fulfill for this htlc
//                    Failure(UnknownHtlcId(commitments.channelId, cmd.id))
//                    case Some(_) =>
//                    val fail = UpdateFailMalformedHtlc(commitments.channelId, cmd.id, cmd.onionHash, cmd.failureCode)
//                    val commitments1 = addLocalProposal(commitments, fail)
//                    Success((commitments1, fail))
//                    case None => Failure(UnknownHtlcId(commitments.channelId, cmd.id))
//                }
//            }
//        }
//
//        def receiveFail(commitments: Commitments, fail: UpdateFailHtlc): Try[(Commitments, Origin, UpdateAddHtlc)] =
//        getOutgoingHtlcCrossSigned(commitments, fail.id) match {
//            case Some(htlc) => Try((addRemoteProposal(commitments, fail), commitments.originChannels(fail.id), htlc))
//            case None => Failure(UnknownHtlcId(commitments.channelId, fail.id))
//        }
//
//        def receiveFailMalformed(commitments: Commitments, fail: UpdateFailMalformedHtlc): Try[(Commitments, Origin, UpdateAddHtlc)] = {
//            // A receiving node MUST fail the channel if the BADONION bit in failure_code is not set for update_fail_malformed_htlc.
//            if ((fail.failureCode & FailureMessageCodecs.BADONION) == 0) {
//                Failure(InvalidFailureCode(commitments.channelId))
//            } else {
//                getOutgoingHtlcCrossSigned(commitments, fail.id) match {
//                    case Some(htlc) => Try((addRemoteProposal(commitments, fail), commitments.originChannels(fail.id), htlc))
//                    case None => Failure(UnknownHtlcId(commitments.channelId, fail.id))
//                }
//            }
//        }
//
//        def sendFee(commitments: Commitments, cmd: CMD_UPDATE_FEE): Try[(Commitments, UpdateFee)] = {
//            if (!commitments.localParams.isFunder) {
//                Failure(FundeeCannotSendUpdateFee(commitments.channelId))
//            } else {
//                // let's compute the current commitment *as seen by them* with this change taken into account
//                val fee = UpdateFee(commitments.channelId, cmd.feeratePerKw)
//                // update_fee replace each other, so we can remove previous ones
//                val commitments1 = commitments.copy(localChanges = commitments.localChanges.copy(proposed = commitments.localChanges.proposed.filterNot(_.isInstanceOf[UpdateFee]) :+ fee))
//                val reduced = CommitmentSpec.reduce(commitments1.remoteCommit.spec, commitments1.remoteChanges.acked, commitments1.localChanges.proposed)
//
//                // a node cannot spend pending incoming htlcs, and need to keep funds above the reserve required by the counterparty, after paying the fee
//                // we look from remote's point of view, so if local is funder remote doesn't pay the fees
//                val fees = commitTxFee(commitments1.remoteParams.dustLimit, reduced)
//                val missing = reduced.toRemote.truncateToSatoshi - commitments1.remoteParams.channelReserve - fees
//                if (missing < 0.sat) {
//                    Failure(CannotAffordFees(commitments.channelId, missing = -missing, reserve = commitments1.localParams.channelReserve, fees = fees))
//                } else {
//                    Success((commitments1, fee))
//                }
//            }
//        }
//
//        def receiveFee(commitments: Commitments, feeEstimator: FeeEstimator, feeTargets: FeeTargets, fee: UpdateFee, maxFeerateMismatch: Double): Try[Commitments] = {
//            if (commitments.localParams.isFunder) {
//                Failure(FundeeCannotSendUpdateFee(commitments.channelId))
//            } else if (fee.feeratePerKw < fr.acinq.eclair.MinimumFeeratePerKw) {
//                Failure(FeerateTooSmall(commitments.channelId, remoteFeeratePerKw = fee.feeratePerKw))
//            } else {
//                val localFeeratePerKw = feeEstimator.getFeeratePerKw(target = feeTargets.commitmentBlockTarget)
//                if (Helpers.isFeeDiffTooHigh(fee.feeratePerKw, localFeeratePerKw, maxFeerateMismatch)) {
//                    Failure(FeerateTooDifferent(commitments.channelId, localFeeratePerKw = localFeeratePerKw, remoteFeeratePerKw = fee.feeratePerKw))
//                } else {
//                    // NB: we check that the funder can afford this new fee even if spec allows to do it at next signature
//                    // It is easier to do it here because under certain (race) conditions spec allows a lower-than-normal fee to be paid,
//                    // and it would be tricky to check if the conditions are met at signing
//                    // (it also means that we need to check the fee of the initial commitment tx somewhere)
//
//                    // let's compute the current commitment *as seen by us* including this change
//                    // update_fee replace each other, so we can remove previous ones
//                    val commitments1 = commitments.copy(remoteChanges = commitments.remoteChanges.copy(proposed = commitments.remoteChanges.proposed.filterNot(_.isInstanceOf[UpdateFee]) :+ fee))
//                    val reduced = CommitmentSpec.reduce(commitments1.localCommit.spec, commitments1.localChanges.acked, commitments1.remoteChanges.proposed)
//
//                    // a node cannot spend pending incoming htlcs, and need to keep funds above the reserve required by the counterparty, after paying the fee
//                    val fees = commitTxFee(commitments1.remoteParams.dustLimit, reduced)
//                    val missing = reduced.toRemote.truncateToSatoshi - commitments1.localParams.channelReserve - fees
//                    if (missing < 0.sat) {
//                        Failure(CannotAffordFees(commitments.channelId, missing = -missing, reserve = commitments1.localParams.channelReserve, fees = fees))
//                    } else {
//                        Success(commitments1)
//                    }
//                }
//            }
//        }
//
//        def localHasUnsignedOutgoingHtlcs(commitments: Commitments): Boolean = commitments.localChanges.proposed.collectFirst { case u: UpdateAddHtlc => u }.isDefined
//
//        def remoteHasUnsignedOutgoingHtlcs(commitments: Commitments): Boolean = commitments.remoteChanges.proposed.collectFirst { case u: UpdateAddHtlc => u }.isDefined
//
//        def localHasChanges(commitments: Commitments): Boolean = commitments.remoteChanges.acked.nonEmpty || commitments.localChanges.proposed.nonEmpty
//
//        def remoteHasChanges(commitments: Commitments): Boolean = commitments.localChanges.acked.nonEmpty || commitments.remoteChanges.proposed.nonEmpty
//
//        def revocationPreimage(seed: ByteVector32, index: Long): ByteVector32 = ShaChain.shaChainFromSeed(seed, 0xFFFFFFFFFFFFFFFFL - index)
//
//        def revocationHash(seed: ByteVector32, index: Long): ByteVector32 = Crypto.sha256(revocationPreimage(seed, index))
//
//        def sendCommit(commitments: Commitments, keyManager: KeyManager)(implicit log: LoggingAdapter): Try[(Commitments, CommitSig)] = {
//            import commitments._
//                    commitments.remoteNextCommitInfo match {
//                case Right(_) if !localHasChanges(commitments) =>
//                Failure(CannotSignWithoutChanges(commitments.channelId))
//                case Right(remoteNextPerCommitmentPoint) => Try {
//                // remote commitment will includes all local changes + remote acked changes
//                val spec = CommitmentSpec.reduce(remoteCommit.spec, remoteChanges.acked, localChanges.proposed)
//                val (remoteCommitTx, htlcTimeoutTxs, htlcSuccessTxs) = makeRemoteTxs(keyManager, channelVersion, remoteCommit.index + 1, localParams, remoteParams, commitInput, remoteNextPerCommitmentPoint, spec)
//                val sig = keyManager.sign(remoteCommitTx, keyManager.fundingPublicKey(commitments.localParams.fundingKeyPath))
//
//                val sortedHtlcTxs: Seq[TransactionWithInputInfo] = (htlcTimeoutTxs ++ htlcSuccessTxs).sortBy(_.input.outPoint.index)
//                val channelKeyPath = keyManager.channelKeyPath(commitments.localParams, commitments.channelVersion)
//                val htlcSigs = sortedHtlcTxs.map(keyManager.sign(_, keyManager.htlcPoint(channelKeyPath), remoteNextPerCommitmentPoint))
//
//                // NB: IN/OUT htlcs are inverted because this is the remote commit
//                log.info(s"built remote commit number=${remoteCommit.index + 1} toLocalMsat=${spec.toLocal.toLong} toRemoteMsat=${spec.toRemote.toLong} htlc_in={} htlc_out={} feeratePerKw=${spec.feeratePerKw} txid=${remoteCommitTx.tx.txid} tx={}", spec.htlcs.collect(outgoing).map(_.id).mkString(","), spec.htlcs.collect(incoming).map(_.id).mkString(","), remoteCommitTx.tx)
//
//                // don't sign if they don't get paid
//                val commitSig = CommitSig(
//                    channelId = commitments.channelId,
//                    signature = sig,
//                    htlcSignatures = htlcSigs.toList
//                )
//
//                val commitments1 = commitments.copy(
//                    remoteNextCommitInfo = Left(WaitingForRevocation(RemoteCommit(remoteCommit.index + 1, spec, remoteCommitTx.tx.txid, remoteNextPerCommitmentPoint), commitSig, commitments.localCommit.index)),
//                    localChanges = localChanges.copy(proposed = Nil, signed = localChanges.proposed),
//                    remoteChanges = remoteChanges.copy(acked = Nil, signed = remoteChanges.acked))
//                (commitments1, commitSig)
//            }
//                case Left(_) =>
//                Failure(CannotSignBeforeRevocation(commitments.channelId))
//            }
//        }
//
//        def receiveCommit(commitments: Commitments, commit: CommitSig, keyManager: KeyManager)(implicit log: LoggingAdapter): Try[(Commitments, RevokeAndAck)] = Try {
//            import commitments._
//                    // they sent us a signature for *their* view of *our* next commit tx
//                    // so in terms of rev.hashes and indexes we have:
//                    // ourCommit.index -> our current revocation hash, which is about to become our old revocation hash
//                    // ourCommit.index + 1 -> our next revocation hash, used by *them* to build the sig we've just received, and which
//                    // is about to become our current revocation hash
//                    // ourCommit.index + 2 -> which is about to become our next revocation hash
//                    // we will reply to this sig with our old revocation hash preimage (at index) and our next revocation hash (at index + 1)
//                    // and will increment our index
//
//                    // lnd sometimes sends a new signature without any changes, which is a (harmless) spec violation
//                    if (!remoteHasChanges(commitments)) {
//                        //  throw CannotSignWithoutChanges(commitments.channelId)
//                        log.warning("received a commit sig with no changes (probably coming from lnd)")
//                    }
//
//            // check that their signature is valid
//            // signatures are now optional in the commit message, and will be sent only if the other party is actually
//            // receiving money i.e its commit tx has one output for them
//
//            val spec = CommitmentSpec.reduce(localCommit.spec, localChanges.acked, remoteChanges.proposed)
//            val channelKeyPath = keyManager.channelKeyPath(commitments.localParams, commitments.channelVersion)
//            val localPerCommitmentPoint = keyManager.commitmentPoint(channelKeyPath, commitments.localCommit.index + 1)
//            val (localCommitTx, htlcTimeoutTxs, htlcSuccessTxs) = makeLocalTxs(keyManager, channelVersion, localCommit.index + 1, localParams, remoteParams, commitInput, localPerCommitmentPoint, spec)
//            val sig = keyManager.sign(localCommitTx, keyManager.fundingPublicKey(commitments.localParams.fundingKeyPath))
//
//            log.info(s"built local commit number=${localCommit.index + 1} toLocalMsat=${spec.toLocal.toLong} toRemoteMsat=${spec.toRemote.toLong} htlc_in={} htlc_out={} feeratePerKw=${spec.feeratePerKw} txid=${localCommitTx.tx.txid} tx={}", spec.htlcs.collect(incoming).map(_.id).mkString(","), spec.htlcs.collect(outgoing).map(_.id).mkString(","), localCommitTx.tx)
//
//            // TODO: should we have optional sig? (original comment: this tx will NOT be signed if our output is empty)
//
//            // no need to compute htlc sigs if commit sig doesn't check out
//            val signedCommitTx = Transactions.addSigs(localCommitTx, keyManager.fundingPublicKey(commitments.localParams.fundingKeyPath).publicKey, remoteParams.fundingPubKey, sig, commit.signature)
//            if (Transactions.checkSpendable(signedCommitTx).isFailure) {
//                throw InvalidCommitmentSignature(commitments.channelId, signedCommitTx.tx)
//            }
//
//            val sortedHtlcTxs: Seq[TransactionWithInputInfo] = (htlcTimeoutTxs ++ htlcSuccessTxs).sortBy(_.input.outPoint.index)
//            if (commit.htlcSignatures.size != sortedHtlcTxs.size) {
//                throw HtlcSigCountMismatch(commitments.channelId, sortedHtlcTxs.size, commit.htlcSignatures.size)
//            }
//            val htlcSigs = sortedHtlcTxs.map(keyManager.sign(_, keyManager.htlcPoint(channelKeyPath), localPerCommitmentPoint))
//            val remoteHtlcPubkey = Generators.derivePubKey(remoteParams.htlcBasepoint, localPerCommitmentPoint)
//            // combine the sigs to make signed txes
//            val htlcTxsAndSigs = (sortedHtlcTxs, htlcSigs, commit.htlcSignatures).zipped.toList.collect {
//                case (htlcTx: HtlcTimeoutTx, localSig, remoteSig) =>
//                if (Transactions.checkSpendable(Transactions.addSigs(htlcTx, localSig, remoteSig)).isFailure) {
//                    throw InvalidHtlcSignature(commitments.channelId, htlcTx.tx)
//                }
//                HtlcTxAndSigs(htlcTx, localSig, remoteSig)
//                case (htlcTx: HtlcSuccessTx, localSig, remoteSig) =>
//                // we can't check that htlc-success tx are spendable because we need the payment preimage; thus we only check the remote sig
//                if (!Transactions.checkSig(htlcTx, remoteSig, remoteHtlcPubkey)) {
//                    throw InvalidHtlcSignature(commitments.channelId, htlcTx.tx)
//                }
//                HtlcTxAndSigs(htlcTx, localSig, remoteSig)
//            }
//
//            // we will send our revocation preimage + our next revocation hash
//            val localPerCommitmentSecret = keyManager.commitmentSecret(channelKeyPath, commitments.localCommit.index)
//            val localNextPerCommitmentPoint = keyManager.commitmentPoint(channelKeyPath, commitments.localCommit.index + 2)
//            val revocation = RevokeAndAck(
//                channelId = commitments.channelId,
//                perCommitmentSecret = localPerCommitmentSecret,
//                nextPerCommitmentPoint = localNextPerCommitmentPoint
//            )
//
//            // update our commitment data
//            val localCommit1 = LocalCommit(
//                index = localCommit.index + 1,
//                spec,
//                publishableTxs = PublishableTxs(signedCommitTx, htlcTxsAndSigs))
//            val ourChanges1 = localChanges.copy(acked = Nil)
//            val theirChanges1 = remoteChanges.copy(proposed = Nil, acked = remoteChanges.acked ++ remoteChanges.proposed)
//            val commitments1 = commitments.copy(localCommit = localCommit1, localChanges = ourChanges1, remoteChanges = theirChanges1)
//
//            (commitments1, revocation)
//        }
//
//        def receiveRevocation(commitments: Commitments, revocation: RevokeAndAck): Try[(Commitments, Seq[Relayer.ForwardMessage])] = {
//            import commitments._
//                    // we receive a revocation because we just sent them a sig for their next commit tx
//                    remoteNextCommitInfo match {
//                case Left(_) if revocation.perCommitmentSecret.publicKey != remoteCommit.remotePerCommitmentPoint =>
//                Failure(InvalidRevocation(commitments.channelId))
//                case Left(WaitingForRevocation(theirNextCommit, _, _, _)) => Try {
//                val forwards = commitments.remoteChanges.signed collect {
//                    // we forward adds downstream only when they have been committed by both sides
//                    // it always happen when we receive a revocation, because they send the add, then they sign it, then we sign it
//                    case add: UpdateAddHtlc => Relayer.ForwardAdd(add)
//                    // same for fails: we need to make sure that they are in neither commitment before propagating the fail upstream
//                    case fail: UpdateFailHtlc =>
//                    val origin = commitments.originChannels(fail.id)
//                    val add = commitments.remoteCommit.spec.findIncomingHtlcById(fail.id).map(_.add).get
//                    Relayer.ForwardRemoteFail(fail, origin, add)
//                    // same as above
//                    case fail: UpdateFailMalformedHtlc =>
//                    val origin = commitments.originChannels(fail.id)
//                    val add = commitments.remoteCommit.spec.findIncomingHtlcById(fail.id).map(_.add).get
//                    Relayer.ForwardRemoteFailMalformed(fail, origin, add)
//                }
//                // the outgoing following htlcs have been completed (fulfilled or failed) when we received this revocation
//                // they have been removed from both local and remote commitment
//                // (since fulfill/fail are sent by remote, they are (1) signed by them, (2) revoked by us, (3) signed by us, (4) revoked by them
//                val completedOutgoingHtlcs = commitments.remoteCommit.spec.htlcs.collect(incoming).map(_.id) -- theirNextCommit.spec.htlcs.collect(incoming).map(_.id)
//                // we remove the newly completed htlcs from the origin map
//                val originChannels1 = commitments.originChannels -- completedOutgoingHtlcs
//                val commitments1 = commitments.copy(
//                    localChanges = localChanges.copy(signed = Nil, acked = localChanges.acked ++ localChanges.signed),
//                    remoteChanges = remoteChanges.copy(signed = Nil),
//                    remoteCommit = theirNextCommit,
//                    remoteNextCommitInfo = Right(revocation.nextPerCommitmentPoint),
//                    remotePerCommitmentSecrets = commitments.remotePerCommitmentSecrets.addHash(revocation.perCommitmentSecret.value, 0xFFFFFFFFFFFFL - commitments.remoteCommit.index),
//                    originChannels = originChannels1)
//                (commitments1, forwards)
//            }
//                case Right(_) =>
//                Failure(UnexpectedRevocation(commitments.channelId))
//            }
//        }
//
//        def makeLocalTxs(keyManager: KeyManager,
//        channelVersion: ChannelVersion,
//        commitTxNumber: Long,
//        localParams: LocalParams,
//        remoteParams: RemoteParams,
//        commitmentInput: InputInfo,
//        localPerCommitmentPoint: PublicKey,
//        spec: CommitmentSpec): (CommitTx, Seq[HtlcTimeoutTx], Seq[HtlcSuccessTx]) = {
//            val channelKeyPath = keyManager.channelKeyPath(localParams, channelVersion)
//            val localDelayedPaymentPubkey = Generators.derivePubKey(keyManager.delayedPaymentPoint(channelKeyPath).publicKey, localPerCommitmentPoint)
//            val localHtlcPubkey = Generators.derivePubKey(keyManager.htlcPoint(channelKeyPath).publicKey, localPerCommitmentPoint)
//            val remotePaymentPubkey = Generators.derivePubKey(remoteParams.paymentBasepoint, localPerCommitmentPoint)
//            val remoteHtlcPubkey = Generators.derivePubKey(remoteParams.htlcBasepoint, localPerCommitmentPoint)
//            val localRevocationPubkey = Generators.revocationPubKey(remoteParams.revocationBasepoint, localPerCommitmentPoint)
//            val outputs = makeCommitTxOutputs(localParams.isFunder, localParams.dustLimit, localRevocationPubkey, remoteParams.toSelfDelay, localDelayedPaymentPubkey, remotePaymentPubkey, localHtlcPubkey, remoteHtlcPubkey, spec)
//            val commitTx = Transactions.makeCommitTx(commitmentInput, commitTxNumber, keyManager.paymentPoint(channelKeyPath).publicKey, remoteParams.paymentBasepoint, localParams.isFunder, outputs)
//            val (htlcTimeoutTxs, htlcSuccessTxs) = Transactions.makeHtlcTxs(commitTx.tx, localParams.dustLimit, localRevocationPubkey, remoteParams.toSelfDelay, localDelayedPaymentPubkey, spec.feeratePerKw, outputs)
//            (commitTx, htlcTimeoutTxs, htlcSuccessTxs)
//        }
//
//        def makeRemoteTxs(keyManager: KeyManager,
//        channelVersion: ChannelVersion,
//        commitTxNumber: Long, localParams: LocalParams,
//        remoteParams: RemoteParams, commitmentInput: InputInfo,
//        remotePerCommitmentPoint: PublicKey,
//        spec: CommitmentSpec): (CommitTx, Seq[HtlcTimeoutTx], Seq[HtlcSuccessTx]) = {
//            val channelKeyPath = keyManager.channelKeyPath(localParams, channelVersion)
//            val localPaymentPubkey = Generators.derivePubKey(keyManager.paymentPoint(channelKeyPath).publicKey, remotePerCommitmentPoint)
//            val localHtlcPubkey = Generators.derivePubKey(keyManager.htlcPoint(channelKeyPath).publicKey, remotePerCommitmentPoint)
//            val remoteDelayedPaymentPubkey = Generators.derivePubKey(remoteParams.delayedPaymentBasepoint, remotePerCommitmentPoint)
//            val remoteHtlcPubkey = Generators.derivePubKey(remoteParams.htlcBasepoint, remotePerCommitmentPoint)
//            val remoteRevocationPubkey = Generators.revocationPubKey(keyManager.revocationPoint(channelKeyPath).publicKey, remotePerCommitmentPoint)
//            val outputs = makeCommitTxOutputs(!localParams.isFunder, remoteParams.dustLimit, remoteRevocationPubkey, localParams.toSelfDelay, remoteDelayedPaymentPubkey, localPaymentPubkey, remoteHtlcPubkey, localHtlcPubkey, spec)
//            val commitTx = Transactions.makeCommitTx(commitmentInput, commitTxNumber, remoteParams.paymentBasepoint, keyManager.paymentPoint(channelKeyPath).publicKey, !localParams.isFunder, outputs)
//            val (htlcTimeoutTxs, htlcSuccessTxs) = Transactions.makeHtlcTxs(commitTx.tx, remoteParams.dustLimit, remoteRevocationPubkey, localParams.toSelfDelay, remoteDelayedPaymentPubkey, spec.feeratePerKw, outputs)
//            (commitTx, htlcTimeoutTxs, htlcSuccessTxs)
//        }
//
//        def msg2String(msg: LightningMessage): String = msg match {
//            case u: UpdateAddHtlc => s"add-${u.id}"
//            case u: UpdateFulfillHtlc => s"ful-${u.id}"
//            case u: UpdateFailHtlc => s"fail-${u.id}"
//            case _: UpdateFee => s"fee"
//            case _: CommitSig => s"sig"
//            case _: RevokeAndAck => s"rev"
//            case _: Error => s"err"
//            case _: FundingLocked => s"funding_locked"
//            case _ => "???"
//        }
//
//        def changes2String(commitments: Commitments): String = {
//            import commitments._
//                    s"""commitments:
//       |    localChanges:
//       |        proposed: ${localChanges.proposed.map(msg2String(_)).mkString(" ")}
//       |        signed: ${localChanges.signed.map(msg2String(_)).mkString(" ")}
//       |        acked: ${localChanges.acked.map(msg2String(_)).mkString(" ")}
//       |    remoteChanges:
//       |        proposed: ${remoteChanges.proposed.map(msg2String(_)).mkString(" ")}
//       |        acked: ${remoteChanges.acked.map(msg2String(_)).mkString(" ")}
//       |        signed: ${remoteChanges.signed.map(msg2String(_)).mkString(" ")}
//       |    nextHtlcId:
//       |        local: $localNextHtlcId
//       |        remote: $remoteNextHtlcId""".stripMargin
//        }
//
//        def specs2String(commitments: Commitments): String = {
//            s"""specs:
//       |localcommit:
//       |  toLocal: ${commitments.localCommit.spec.toLocal}
//       |  toRemote: ${commitments.localCommit.spec.toRemote}
//       |  htlcs:
//       |${commitments.localCommit.spec.htlcs.map(h => s"    ${h.direction} ${h.add.id} ${h.add.cltvExpiry}").mkString("\n")}
//       |remotecommit:
//       |  toLocal: ${commitments.remoteCommit.spec.toLocal}
//       |  toRemote: ${commitments.remoteCommit.spec.toRemote}
//       |  htlcs:
//       |${commitments.remoteCommit.spec.htlcs.map(h => s"    ${h.direction} ${h.add.id} ${h.add.cltvExpiry}").mkString("\n")}
//       |next remotecommit:
//       |  toLocal: ${commitments.remoteNextCommitInfo.left.toOption.map(_.nextRemoteCommit.spec.toLocal).getOrElse("N/A")}
//       |  toRemote: ${commitments.remoteNextCommitInfo.left.toOption.map(_.nextRemoteCommit.spec.toRemote).getOrElse("N/A")}
//       |  htlcs:
//       |${commitments.remoteNextCommitInfo.left.toOption.map(_.nextRemoteCommit.spec.htlcs.map(h => s"    ${h.direction} ${h.add.id} ${h.add.cltvExpiry}").mkString("\n")).getOrElse("N/A")}""".stripMargin
//        }
    }
}
