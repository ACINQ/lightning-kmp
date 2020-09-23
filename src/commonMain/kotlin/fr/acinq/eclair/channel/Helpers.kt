package fr.acinq.eclair.channel

import fr.acinq.bitcoin.*
import fr.acinq.bitcoin.Script.pay2wsh
import fr.acinq.bitcoin.Script.write
import fr.acinq.eclair.MilliSatoshi
import fr.acinq.eclair.NodeParams
import fr.acinq.eclair.crypto.ChaCha20Poly1305
import fr.acinq.eclair.crypto.KeyManager
import fr.acinq.eclair.transactions.CommitmentSpec
import fr.acinq.eclair.transactions.Scripts.multiSig2of2
import fr.acinq.eclair.transactions.Transactions
import fr.acinq.eclair.transactions.Transactions.commitTxFee
import fr.acinq.eclair.utils.Either
import fr.acinq.eclair.wire.AcceptChannel
import fr.acinq.eclair.wire.OpenChannel
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
