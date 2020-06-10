//package fr.acinq.eklair.channel
//
//import fr.acinq.bitcoin.ByteVector32
//import fr.acinq.bitcoin.ByteVector64
//import fr.acinq.bitcoin.PublicKey
//import fr.acinq.eklair.transactions.CommitmentSpec
//import fr.acinq.eklair.transactions.Transactions
//import fr.acinq.eklair.transactions.Transactions.TransactionWithInputInfo
//import fr.acinq.eklair.wire.CommitSig
//import fr.acinq.eklair.wire.UpdateMessage
//import kotlinx.coroutines.MainScope
//
//// @formatter:off
//data class LocalChanges(val proposed: List<UpdateMessage>, val signed: List<UpdateMessage>, val acked: List<UpdateMessage>) {
//    val all: List<UpdateMessage> get() = proposed + signed + acked
//}
//data class RemoteChanges(val proposed: List<UpdateMessage>, val acked: List<UpdateMessage>, val signed: List<UpdateMessage>)
//data class Changes(val ourChanges: LocalChanges, val theirChanges: RemoteChanges)
//data class HtlcTxAndSigs(val txinfo: TransactionWithInputInfo, val localSig: ByteVector64, val remoteSig: ByteVector64)
//data class PublishableTxs(val commitTx: TransactionWithInputInfo.CommitTx, val htlcTxsAndSigs: List<HtlcTxAndSigs>)
//data class LocalCommit(val index: Long, val spec: CommitmentSpec, val publishableTxs: PublishableTxs)
//data class RemoteCommit(val index: Long, val spec: CommitmentSpec, val txid: ByteVector32, val remotePerCommitmentPoint: PublicKey)
//data class WaitingForRevocation(val nextRemoteCommit: RemoteCommit, val sent: CommitSig, val sentAfterLocalCommitIndex: Long, val reSignAsap: Boolean = false)
//// @formatter:on
//
///**
// * about remoteNextCommitInfo:
// * we either:
// * - have built and signed their next commit tx with their next revocation hash which can now be discarded
// * - have their next per-commitment point
// * So, when we've signed and sent a commit message and are waiting for their revocation message,
// * theirNextCommitInfo is their next commit tx. The rest of the time, it is their next per-commitment point
// */
//case class Commitments(
//    val channelVersion: ChannelVersion,
//    val localParams: LocalParams,
//    val remoteParams: RemoteParams,
//    val channelFlags: Byte,
//    val localCommit: LocalCommit,
//    val remoteCommit: RemoteCommit,
//    val localChanges: LocalChanges,
//    val remoteChanges: RemoteChanges,
//    val localNextHtlcId: Long,
//    val remoteNextHtlcId: Long,
//    val originChannels: Map<Long, Origin>, // for outgoing htlcs relayed through us, details about the corresponding incoming htlcs
//    val remoteNextCommitInfo: Either<WaitingForRevocation, PublicKey>,
//    val commitInput: Transactions.InputInfo,
//    val remotePerCommitmentSecrets: ShaChain,
//    val channelId: ByteVector32
//) {
//
////    def hasNoPendingHtlcs: Boolean = localCommit.spec.htlcs.isEmpty && remoteCommit.spec.htlcs.isEmpty && remoteNextCommitInfo.isRight
////
////    def timedOutOutgoingHtlcs(blockheight: Long): Set[UpdateAddHtlc] = {
////        def expired(add: UpdateAddHtlc) = blockheight >= add.cltvExpiry.toLong
////
////        localCommit.spec.htlcs.collect(outgoing).filter(expired) ++
////        remoteCommit.spec.htlcs.collect(incoming).filter(expired) ++
////        remoteNextCommitInfo.left.toSeq.flatMap(_.nextRemoteCommit.spec.htlcs.collect(incoming).filter(expired).toSet)
////    }
////
////    /**
////     * HTLCs that are close to timing out upstream are potentially dangerous. If we received the pre-image for those
////     * HTLCs, we need to get a remote signed updated commitment that removes this HTLC.
////     * Otherwise when we get close to the upstream timeout, we risk an on-chain race condition between their HTLC timeout
////     * and our HTLC success in case of a force-close.
////     */
////    def almostTimedOutIncomingHtlcs(blockheight: Long, fulfillSafety: CltvExpiryDelta): Set[UpdateAddHtlc] = {
////        def nearlyExpired(add: UpdateAddHtlc) = blockheight >= (add.cltvExpiry - fulfillSafety).toLong
////
////        localCommit.spec.htlcs.collect(incoming).filter(nearlyExpired)
////    }
////
////    def addLocalProposal(proposal: UpdateMessage): Commitments = Commitments.addLocalProposal(this, proposal)
////
////    def addRemoteProposal(proposal: UpdateMessage): Commitments = Commitments.addRemoteProposal(this, proposal)
////
////    val announceChannel: Boolean = (channelFlags & 0x01) != 0
////
////    lazy val availableBalanceForSend: MilliSatoshi = {
////        // we need to base the next current commitment on the last sig we sent, even if we didn't yet receive their revocation
////        val remoteCommit1 = remoteNextCommitInfo.left.toOption.map(_.nextRemoteCommit).getOrElse(remoteCommit)
////        val reduced = CommitmentSpec.reduce(remoteCommit1.spec, remoteChanges.acked, localChanges.proposed)
////        val balanceNoFees = (reduced.toRemote - remoteParams.channelReserve).max(0 msat)
////        if (localParams.isFunder) {
////            // The funder always pays the on-chain fees, so we must subtract that from the amount we can send.
////            val commitFees = commitTxFeeMsat(remoteParams.dustLimit, reduced)
////            // the funder needs to keep an extra reserve to be able to handle fee increase without getting the channel stuck
////            // (see https://github.com/lightningnetwork/lightning-rfc/issues/728)
////            val funderFeeReserve = htlcOutputFee(2 * reduced.feeratePerKw)
////            val htlcFees = htlcOutputFee(reduced.feeratePerKw)
////            if (balanceNoFees - commitFees < offeredHtlcTrimThreshold(remoteParams.dustLimit, reduced)) {
////                // htlc will be trimmed
////                (balanceNoFees - commitFees - funderFeeReserve).max(0 msat)
////            } else {
////                // htlc will have an output in the commitment tx, so there will be additional fees.
////                (balanceNoFees - commitFees - funderFeeReserve - htlcFees).max(0 msat)
////            }
////        } else {
////            // The fundee doesn't pay on-chain fees.
////            balanceNoFees
////        }
////    }
////
////    lazy val availableBalanceForReceive: MilliSatoshi = {
////        val reduced = CommitmentSpec.reduce(localCommit.spec, localChanges.acked, remoteChanges.proposed)
////        val balanceNoFees = (reduced.toRemote - localParams.channelReserve).max(0 msat)
////        if (localParams.isFunder) {
////            // The fundee doesn't pay on-chain fees so we don't take those into account when receiving.
////            balanceNoFees
////        } else {
////            // The funder always pays the on-chain fees, so we must subtract that from the amount we can receive.
////            val commitFees = commitTxFeeMsat(localParams.dustLimit, reduced)
////            // we expect the funder to keep an extra reserve to be able to handle fee increase without getting the channel stuck
////            // (see https://github.com/lightningnetwork/lightning-rfc/issues/728)
////            val funderFeeReserve = htlcOutputFee(2 * reduced.feeratePerKw)
////            val htlcFees = htlcOutputFee(reduced.feeratePerKw)
////            if (balanceNoFees - commitFees < receivedHtlcTrimThreshold(localParams.dustLimit, reduced)) {
////                // htlc will be trimmed
////                (balanceNoFees - commitFees - funderFeeReserve).max(0 msat)
////            } else {
////                // htlc will have an output in the commitment tx, so there will be additional fees.
////                (balanceNoFees - commitFees - funderFeeReserve - htlcFees).max(0 msat)
////            }
////        }
////    }
//}
