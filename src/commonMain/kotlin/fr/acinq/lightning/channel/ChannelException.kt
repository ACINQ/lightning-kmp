package fr.acinq.lightning.channel

import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.bitcoin.Satoshi
import fr.acinq.lightning.CltvExpiry
import fr.acinq.lightning.CltvExpiryDelta
import fr.acinq.lightning.MilliSatoshi
import fr.acinq.lightning.blockchain.fee.FeeratePerKw
import fr.acinq.lightning.wire.AnnouncementSignatures
import fr.acinq.lightning.wire.InteractiveTxMessage
import fr.acinq.lightning.wire.UpdateAddHtlc

open class ChannelException(open val channelId: ByteVector32, override val message: String) : RuntimeException(message) {
    fun details(): String = "$channelId: $message"
}

// @formatter:off
data class InvalidChainHash                        (override val channelId: ByteVector32, val local: ByteVector32, val remote: ByteVector32) : ChannelException(channelId, "invalid chainHash (local=$local remote=$remote)")
data class InvalidFundingAmount                    (override val channelId: ByteVector32, val fundingAmount: Satoshi, val min: Satoshi, val max: Satoshi) : ChannelException(channelId, "invalid funding_satoshis=$fundingAmount (min=$min max=$max)")
data class InvalidPushAmount                       (override val channelId: ByteVector32, val pushAmount: MilliSatoshi, val max: MilliSatoshi) : ChannelException(channelId, "invalid pushAmount=$pushAmount (max=$max)")
data class InvalidMaxAcceptedHtlcs                 (override val channelId: ByteVector32, val maxAcceptedHtlcs: Int, val max: Int) : ChannelException(channelId, "invalid max_accepted_htlcs=$maxAcceptedHtlcs (max=$max)")
data class InvalidChannelType                      (override val channelId: ByteVector32, val ourChannelType: ChannelType, val theirChannelType: ChannelType) : ChannelException(channelId, "invalid channel_type=${theirChannelType.name}, expected channel_type=${ourChannelType.name}")
data class MissingChannelType                      (override val channelId: ByteVector32) : ChannelException(channelId, "option_channel_type was negotiated but channel_type is missing")
data class DustLimitTooSmall                       (override val channelId: ByteVector32, val dustLimit: Satoshi, val min: Satoshi) : ChannelException(channelId, "dustLimit=$dustLimit is too small (min=$min)")
data class DustLimitTooLarge                       (override val channelId: ByteVector32, val dustLimit: Satoshi, val max: Satoshi) : ChannelException(channelId, "dustLimit=$dustLimit is too large (max=$max)")
data class ToSelfDelayTooHigh                      (override val channelId: ByteVector32, val toSelfDelay: CltvExpiryDelta, val max: CltvExpiryDelta) : ChannelException(channelId, "unreasonable to_self_delay=$toSelfDelay (max=$max)")
data class ChannelFundingError                     (override val channelId: ByteVector32) : ChannelException(channelId, "channel funding error")
data class RbfAttemptAborted                       (override val channelId: ByteVector32) : ChannelException(channelId, "rbf attempt aborted")
data class DualFundingAborted                      (override val channelId: ByteVector32, val reason: String) : ChannelException(channelId, "dual funding aborted: $reason")
data class UnexpectedInteractiveTxMessage          (override val channelId: ByteVector32, val msg: InteractiveTxMessage) : ChannelException(channelId, "unexpected interactive-tx message (${msg::class})")
data class UnexpectedCommitSig                     (override val channelId: ByteVector32) : ChannelException(channelId, "unexpected commitment signatures (commit_sig)")
data class UnexpectedFundingSignatures             (override val channelId: ByteVector32) : ChannelException(channelId, "unexpected funding signatures (tx_signatures)")
data class InvalidFundingSignature                 (override val channelId: ByteVector32, val txId: ByteVector32) : ChannelException(channelId, "invalid funding signature: txId=$txId")
data class InvalidRbfFeerate                       (override val channelId: ByteVector32, val proposed: FeeratePerKw, val expected: FeeratePerKw) : ChannelException(channelId, "invalid rbf attempt: the feerate must be at least $expected, you proposed $proposed")
data class InvalidRbfAlreadyInProgress             (override val channelId: ByteVector32) : ChannelException(channelId, "invalid rbf attempt: the current rbf attempt must be completed or aborted first")
data class InvalidRbfTxAbortNotAcked               (override val channelId: ByteVector32) : ChannelException(channelId, "invalid rbf attempt: our previous tx_abort has not been acked")
data class InvalidRbfTxConfirmed                   (override val channelId: ByteVector32, val txId: ByteVector32) : ChannelException(channelId, "no need to rbf, transaction is already confirmed with txId=$txId")
data class InvalidRbfNonInitiator                  (override val channelId: ByteVector32) : ChannelException(channelId, "cannot initiate rbf: we're not the initiator of this interactive-tx attempt")
data class InvalidRbfAttempt                       (override val channelId: ByteVector32) : ChannelException(channelId, "invalid rbf attempt")
data class NoMoreHtlcsClosingInProgress            (override val channelId: ByteVector32) : ChannelException(channelId, "cannot send new htlcs, closing in progress")
data class NoMoreFeeUpdateClosingInProgress        (override val channelId: ByteVector32) : ChannelException(channelId, "cannot send new update_fee, closing in progress")
data class ClosingAlreadyInProgress                (override val channelId: ByteVector32) : ChannelException(channelId, "closing already in progress")
data class CannotCloseWithUnsignedOutgoingHtlcs    (override val channelId: ByteVector32) : ChannelException(channelId, "cannot close when there are unsigned outgoing htlc")
data class CannotCloseWithUnsignedOutgoingUpdateFee(override val channelId: ByteVector32) : ChannelException(channelId, "cannot close when there is an unsigned fee update")
data class ChannelUnavailable                      (override val channelId: ByteVector32) : ChannelException(channelId, "channel is unavailable (offline or closing)")
data class InvalidFinalScript                      (override val channelId: ByteVector32) : ChannelException(channelId, "invalid final script")
data class FundingTxTimedout                       (override val channelId: ByteVector32) : ChannelException(channelId, "funding tx timed out")
data class FundingTxSpent                          (override val channelId: ByteVector32, val spendingTxId: ByteVector32) : ChannelException(channelId, "funding tx has been spent by txId=$spendingTxId")
data class HtlcsTimedOutDownstream                 (override val channelId: ByteVector32, val htlcs: Set<UpdateAddHtlc>) : ChannelException(channelId, "one or more htlcs timed out downstream: ids=${htlcs.map { it.id } .joinToString(",")}")
data class FulfilledHtlcsWillTimeout               (override val channelId: ByteVector32, val htlcs: Set<UpdateAddHtlc>) : ChannelException(channelId, "one or more htlcs that should be fulfilled are close to timing out: ids=${htlcs.map { it.id }.joinToString()}")
data class HtlcOverriddenByLocalCommit             (override val channelId: ByteVector32, val htlc: UpdateAddHtlc) : ChannelException(channelId, "htlc ${htlc.id} was overridden by local commit")
data class FeerateTooSmall                         (override val channelId: ByteVector32, val remoteFeeratePerKw: FeeratePerKw) : ChannelException(channelId, "remote fee rate is too small: remoteFeeratePerKw=${remoteFeeratePerKw.toLong()}")
data class FeerateTooDifferent                     (override val channelId: ByteVector32, val localFeeratePerKw: FeeratePerKw, val remoteFeeratePerKw: FeeratePerKw) : ChannelException(channelId, "local/remote feerates are too different: remoteFeeratePerKw=${remoteFeeratePerKw.toLong()} localFeeratePerKw=${localFeeratePerKw.toLong()}")
data class InvalidAnnouncementSignatures           (override val channelId: ByteVector32, val annSigs: AnnouncementSignatures) : ChannelException(channelId, "invalid announcement signatures: $annSigs")
data class InvalidCommitmentSignature              (override val channelId: ByteVector32, val txId: ByteVector32) : ChannelException(channelId, "invalid commitment signature: txId=$txId")
data class InvalidHtlcSignature                    (override val channelId: ByteVector32, val txId: ByteVector32) : ChannelException(channelId, "invalid htlc signature: txId=$txId")
data class InvalidCloseSignature                   (override val channelId: ByteVector32, val txId: ByteVector32) : ChannelException(channelId, "invalid close signature: txId=$txId")
data class InvalidCloseFee                         (override val channelId: ByteVector32, val fee: Satoshi) : ChannelException(channelId, "invalid close fee: fee_satoshis=$fee")
data class InvalidCloseAmountBelowDust             (override val channelId: ByteVector32, val txId: ByteVector32) : ChannelException(channelId, "invalid closing tx: some outputs are below dust: txId=$txId")
data class HtlcSigCountMismatch                    (override val channelId: ByteVector32, val expected: Int, val actual: Int) : ChannelException(channelId, "htlc sig count mismatch: expected=$expected actual: $actual")
data class ForcedLocalCommit                       (override val channelId: ByteVector32) : ChannelException(channelId, "forced local commit")
data class UnexpectedHtlcId                        (override val channelId: ByteVector32, val expected: Long, val actual: Long) : ChannelException(channelId, "unexpected htlc id: expected=$expected actual=$actual")
data class ExpiryTooSmall                          (override val channelId: ByteVector32, val minimum: CltvExpiry, val actual: CltvExpiry, val blockCount: Long) : ChannelException(channelId, "expiry too small: minimum=$minimum actual=$actual blockCount=$blockCount")
data class ExpiryTooBig                            (override val channelId: ByteVector32, val maximum: CltvExpiry, val actual: CltvExpiry, val blockCount: Long) : ChannelException(channelId, "expiry too big: maximum=$maximum actual=$actual blockCount=$blockCount")
data class HtlcValueTooSmall                       (override val channelId: ByteVector32, val minimum: MilliSatoshi, val actual: MilliSatoshi) : ChannelException(channelId, "htlc value too small: minimum=$minimum actual=$actual")
data class HtlcValueTooHighInFlight                (override val channelId: ByteVector32, val maximum: ULong, val actual: MilliSatoshi) : ChannelException(channelId, "in-flight htlcs hold too much value: maximum=$maximum actual=$actual")
data class TooManyAcceptedHtlcs                    (override val channelId: ByteVector32, val maximum: Long) : ChannelException(channelId, "too many accepted htlcs: maximum=$maximum")
data class TooManyOfferedHtlcs                     (override val channelId: ByteVector32, val maximum: Long) : ChannelException(channelId, "too many offered htlcs: maximum=$maximum")
data class InsufficientFunds                       (override val channelId: ByteVector32, val amount: MilliSatoshi, val missing: Satoshi, val reserve: Satoshi, val fees: Satoshi) : ChannelException(channelId, "insufficient funds: missing=$missing reserve=$reserve fees=$fees")
data class RemoteCannotAffordFeesForNewHtlc        (override val channelId: ByteVector32, val amount: MilliSatoshi, val missing: Satoshi, val reserve: Satoshi, val fees: Satoshi) : ChannelException(channelId, "remote can't afford increased commit tx fees once new HTLC is added: missing=$missing reserve=$reserve fees=$fees")
data class InvalidHtlcPreimage                     (override val channelId: ByteVector32, val id: Long) : ChannelException(channelId, "invalid htlc preimage for htlc id=$id")
data class UnknownHtlcId                           (override val channelId: ByteVector32, val id: Long) : ChannelException(channelId, "unknown htlc id=$id")
data class CannotExtractSharedSecret               (override val channelId: ByteVector32, val htlc: UpdateAddHtlc) : ChannelException(channelId, "can't extract shared secret: paymentHash=${htlc.paymentHash} onion=${htlc.onionRoutingPacket}")
data class NonInitiatorCannotSendUpdateFee         (override val channelId: ByteVector32) : ChannelException(channelId, "only the initiator should send update_fee message")
data class CannotAffordFees                        (override val channelId: ByteVector32, val missing: Satoshi, val reserve: Satoshi, val fees: Satoshi) : ChannelException(channelId, "can't pay the fee: missing=$missing reserve=$reserve fees=$fees")
data class CannotSignWithoutChanges                (override val channelId: ByteVector32) : ChannelException(channelId, "cannot sign when there are no change")
data class CannotSignBeforeRevocation              (override val channelId: ByteVector32) : ChannelException(channelId, "cannot sign until next revocation hash is received")
data class UnexpectedRevocation                    (override val channelId: ByteVector32) : ChannelException(channelId, "received unexpected RevokeAndAck message")
data class InvalidRevocation                       (override val channelId: ByteVector32) : ChannelException(channelId, "invalid revocation")
data class InvalidRevokedCommitProof               (override val channelId: ByteVector32, val ourCommitmentNumber: Long, val theirCommitmentNumber: Long, val perCommitmentSecret: PrivateKey) : ChannelException(channelId, "counterparty claimed that we have a revoked commit but their proof doesn't check out: ourCommitmentNumber=$ourCommitmentNumber theirCommitmentNumber=$theirCommitmentNumber perCommitmentSecret=$perCommitmentSecret")
data class CommitmentSyncError                     (override val channelId: ByteVector32) : ChannelException(channelId, "commitment sync error")
data class RevocationSyncError                     (override val channelId: ByteVector32) : ChannelException(channelId, "revocation sync error")
data class InvalidFailureCode                      (override val channelId: ByteVector32) : ChannelException(channelId, "UpdateFailMalformedHtlc message doesn't have BADONION bit set")
data class PleasePublishYourCommitment             (override val channelId: ByteVector32) : ChannelException(channelId, "please publish your local commitment")
data class CommandUnavailableInThisState           (override val channelId: ByteVector32, val state: String) : ChannelException(channelId, "cannot execute command in state=$state")
// @formatter:on
