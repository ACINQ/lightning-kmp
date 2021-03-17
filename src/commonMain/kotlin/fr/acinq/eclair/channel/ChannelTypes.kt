package fr.acinq.eclair.channel

import fr.acinq.bitcoin.*
import fr.acinq.eclair.CltvExpiry
import fr.acinq.eclair.CltvExpiryDelta
import fr.acinq.eclair.Features
import fr.acinq.eclair.MilliSatoshi
import fr.acinq.eclair.blockchain.fee.FeeratePerKw
import fr.acinq.eclair.channel.Helpers.publishIfNeeded
import fr.acinq.eclair.channel.Helpers.watchConfirmedIfNeeded
import fr.acinq.eclair.channel.Helpers.watchSpentIfNeeded
import fr.acinq.eclair.transactions.Transactions.TransactionWithInputInfo.*
import fr.acinq.eclair.utils.BitField
import fr.acinq.eclair.utils.UUID
import fr.acinq.eclair.wire.ClosingSigned
import fr.acinq.eclair.wire.FailureMessage
import fr.acinq.eclair.wire.OnionRoutingPacket
import kotlinx.serialization.Serializable

/*
       .d8888b.   .d88888b.  888b     d888 888b     d888        d8888 888b    888 8888888b.   .d8888b.
      d88P  Y88b d88P" "Y88b 8888b   d8888 8888b   d8888       d88888 8888b   888 888  "Y88b d88P  Y88b
      888    888 888     888 88888b.d88888 88888b.d88888      d88P888 88888b  888 888    888 Y88b.
      888        888     888 888Y88888P888 888Y88888P888     d88P 888 888Y88b 888 888    888  "Y888b.
      888        888     888 888 Y888P 888 888 Y888P 888    d88P  888 888 Y88b888 888    888     "Y88b.
      888    888 888     888 888  Y8P  888 888  Y8P  888   d88P   888 888  Y88888 888    888       "888
      Y88b  d88P Y88b. .d88P 888   "   888 888   "   888  d8888888888 888   Y8888 888  .d88P Y88b  d88P
       "Y8888P"   "Y88888P"  888       888 888       888 d88P     888 888    Y888 8888888P"   "Y8888P"
 */

sealed class Command

data class CMD_ADD_HTLC(val amount: MilliSatoshi, val paymentHash: ByteVector32, val cltvExpiry: CltvExpiry, val onion: OnionRoutingPacket, val paymentId: UUID, val commit: Boolean = false) : Command()

sealed class HtlcSettlementCommand : Command() {
    abstract val id: Long
}

data class CMD_FULFILL_HTLC(override val id: Long, val r: ByteVector32, val commit: Boolean = false) : HtlcSettlementCommand()
data class CMD_FAIL_MALFORMED_HTLC(override val id: Long, val onionHash: ByteVector32, val failureCode: Int, val commit: Boolean = false) : HtlcSettlementCommand()
data class CMD_FAIL_HTLC(override val id: Long, val reason: Reason, val commit: Boolean = false) : HtlcSettlementCommand() {
    sealed class Reason {
        data class Bytes(val bytes: ByteVector) : Reason()
        data class Failure(val message: FailureMessage) : Reason()
    }
}

object CMD_SIGN : Command()
data class CMD_UPDATE_FEE(val feerate: FeeratePerKw, val commit: Boolean = false) : Command()

sealed class CloseCommand : Command()
data class CMD_CLOSE(val scriptPubKey: ByteVector?) : CloseCommand()
object CMD_FORCECLOSE : CloseCommand()

/*
      8888888b.        d8888 88888888888     d8888
      888  "Y88b      d88888     888        d88888
      888    888     d88P888     888       d88P888
      888    888    d88P 888     888      d88P 888
      888    888   d88P  888     888     d88P  888
      888    888  d88P   888     888    d88P   888
      888  .d88P d8888888888     888   d8888888888
      8888888P" d88P     888     888  d88P     888
 */

/**
 * Details about a force-close where we published our commitment.
 *
 * @param commitTx commitment tx.
 * @param claimMainDelayedOutputTx tx claiming our main output (if we have one).
 * @param htlcTxs txs claiming HTLCs. There will be one entry for each pending HTLC. The value will be null only for
 * incoming HTLCs for which we don't have the preimage (we can't claim them yet).
 * @param claimHtlcDelayedTxs 3rd-stage txs (spending the output of HTLC txs).
 * @param claimAnchorTxs txs spending anchor outputs to bump the feerate of the commitment tx (if applicable).
 * @param irrevocablySpent map of relevant outpoints that have been spent and the confirmed transaction that spends them.
 */
data class LocalCommitPublished(
    val commitTx: Transaction,
    val claimMainDelayedOutputTx: ClaimLocalDelayedOutputTx? = null,
    val htlcTxs: Map<OutPoint, HtlcTx?> = emptyMap(),
    val claimHtlcDelayedTxs: List<ClaimLocalDelayedOutputTx> = emptyList(),
    val claimAnchorTxs: List<ClaimAnchorOutputTx> = emptyList(),
    val irrevocablySpent: Map<OutPoint, Transaction> = emptyMap()
) {
    /**
     * In CLOSING state, when we are notified that a transaction has been confirmed, we check if this tx belongs in the
     * local commit scenario and keep track of it.
     *
     * We need to keep track of all transactions spending the outputs of the commitment tx, because some outputs can be
     * spent both by us and our counterparty. Because of that, some of our transactions may never confirm and we don't
     * want to wait forever before declaring that the channel is CLOSED.
     *
     * @param tx a transaction that has been irrevocably confirmed
     */
    fun update(tx: Transaction): LocalCommitPublished {
        // even if our txs only have one input, maybe our counterparty uses a different scheme so we need to iterate
        // over all of them to check if they are relevant
        val relevantOutpoints = tx.txIn.map { it.outPoint }.filter { outPoint ->
            // is this the commit tx itself? (we could do this outside of the loop...)
            val isCommitTx = commitTx.txid == tx.txid
            // does the tx spend an output of the local commitment tx?
            val spendsTheCommitTx = commitTx.txid == outPoint.txid
            // is the tx one of our 3rd stage delayed txs? (a 3rd stage tx is a tx spending the output of an htlc tx, which
            // is itself spending the output of the commitment tx)
            val is3rdStageDelayedTx = claimHtlcDelayedTxs.map { it.input.outPoint }.contains(outPoint)
            isCommitTx || spendsTheCommitTx || is3rdStageDelayedTx
        }
        // then we add the relevant outpoints to the map keeping track of which txid spends which outpoint
        return this.copy(irrevocablySpent = irrevocablySpent + relevantOutpoints.map { it to tx }.toMap())
    }

    /**
     * A local commit is considered done when:
     * - all commitment tx outputs that we can spend have been spent and confirmed (even if the spending tx was not ours)
     * - all 3rd stage txs (txs spending htlc txs) have been confirmed
     */
    fun isDone(): Boolean {
        val confirmedTxs = irrevocablySpent.values.map { it.txid }.toSet()
        // is the commitment tx buried? (we need to check this because we may not have any outputs)
        val isCommitTxConfirmed = confirmedTxs.contains(commitTx.txid)
        // is our main output confirmed (if we have one)?
        val isMainOutputConfirmed = claimMainDelayedOutputTx?.let { irrevocablySpent.contains(it.input.outPoint) } ?: true
        // are all htlc outputs from the commitment tx spent (we need to check them all because we may receive preimages later)?
        val allHtlcsSpent = (htlcTxs.keys - irrevocablySpent.keys).isEmpty()
        // are all outputs from htlc txs spent?
        val unconfirmedHtlcDelayedTxs = claimHtlcDelayedTxs.map { it.input.outPoint }
            // only the txs which parents are already confirmed may get confirmed (note that this also eliminates outputs that have been double-spent by a competing tx)
            .filter { input -> confirmedTxs.contains(input.txid) }
            // has the tx already been confirmed?
            .filterNot { input -> irrevocablySpent.contains(input) }
        return isCommitTxConfirmed && isMainOutputConfirmed && allHtlcsSpent && unconfirmedHtlcDelayedTxs.isEmpty()
    }

    fun isConfirmed(): Boolean {
        return irrevocablySpent.values.any { it.txid == commitTx.txid } || irrevocablySpent.keys.any { it.txid == commitTx.txid }
    }

    internal fun doPublish(channelId: ByteVector32, minDepth: Long): List<ChannelAction> {
        val publishQueue = buildList {
            add(commitTx)
            claimMainDelayedOutputTx?.let { add(it.tx) }
            addAll(htlcTxs.values.mapNotNull { it?.tx })
            addAll(claimHtlcDelayedTxs.map { it.tx })
        }
        val publishList = publishIfNeeded(publishQueue, irrevocablySpent, channelId)

        // we watch:
        // - the commitment tx itself, so that we can handle the case where we don't have any outputs
        // - 'final txs' that send funds to our wallet and that spend outputs that only us control
        val watchConfirmedQueue = buildList {
            add(commitTx)
            claimMainDelayedOutputTx?.let { add(it.tx) }
            addAll(claimHtlcDelayedTxs.map { it.tx })
        }
        val watchConfirmedList = watchConfirmedIfNeeded(watchConfirmedQueue, irrevocablySpent, channelId, minDepth)

        // we watch outputs of the commitment tx that both parties may spend
        val watchSpentQueue = htlcTxs.keys.toList()
        val watchSpentList = watchSpentIfNeeded(commitTx, watchSpentQueue, irrevocablySpent, channelId)

        return buildList {
            addAll(publishList)
            addAll(watchConfirmedList)
            addAll(watchSpentList)
        }
    }
}

/**
 * Details about a force-close where they published their commitment.
 *
 * @param commitTx commitment tx.
 * @param claimMainOutputTx tx claiming our main output (if we have one).
 * @param claimHtlcTxs txs claiming HTLCs. There will be one entry for each pending HTLC. The value will be null only
 * for incoming HTLCs for which we don't have the preimage (we can't claim them yet).
 * @param claimAnchorTxs txs spending anchor outputs to bump the feerate of the commitment tx (if applicable).
 * @param irrevocablySpent map of relevant outpoints that have been spent and the confirmed transaction that spends them.
 */
data class RemoteCommitPublished(
    val commitTx: Transaction,
    val claimMainOutputTx: ClaimRemoteCommitMainOutputTx? = null,
    val claimHtlcTxs: Map<OutPoint, ClaimHtlcTx?> = emptyMap(),
    val claimAnchorTxs: List<ClaimAnchorOutputTx> = emptyList(),
    val irrevocablySpent: Map<OutPoint, Transaction> = emptyMap()
) {
    /**
     * In CLOSING state, when we are notified that a transaction has been confirmed, we check if this tx belongs in the
     * remote commit scenario and keep track of it.
     *
     * We need to keep track of all transactions spending the outputs of the commitment tx, because some outputs can be
     * spent both by us and our counterparty. Because of that, some of our transactions may never confirm and we don't
     * want to wait forever before declaring that the channel is CLOSED.
     *
     * @param tx a transaction that has been irrevocably confirmed
     */
    fun update(tx: Transaction): RemoteCommitPublished {
        // even if our txs only have one input, maybe our counterparty uses a different scheme so we need to iterate
        // over all of them to check if they are relevant
        val relevantOutpoints = tx.txIn.map { it.outPoint }.filter {
            // is this the commit tx itself? (we could do this outside of the loop...)
            val isCommitTx = commitTx.txid == tx.txid
            // does the tx spend an output of the commitment tx?
            val spendsTheCommitTx = commitTx.txid == it.txid
            isCommitTx || spendsTheCommitTx
        }
        // then we add the relevant outpoints to the map keeping track of which txid spends which outpoint
        return this.copy(irrevocablySpent = irrevocablySpent + relevantOutpoints.map { it to tx }.toMap())
    }

    /**
     * A remote commit is considered done when all commitment tx outputs that we can spend have been spent and confirmed
     * (even if the spending tx was not ours).
     */
    fun isDone(): Boolean {
        val confirmedTxs = irrevocablySpent.values.map { it.txid }.toSet()
        // is the commitment tx buried? (we need to check this because we may not have any outputs)
        val isCommitTxConfirmed = confirmedTxs.contains(commitTx.txid)
        // is our main output confirmed (if we have one)?
        val isMainOutputConfirmed = claimMainOutputTx?.let { irrevocablySpent.contains(it.input.outPoint) } ?: true
        // are all htlc outputs from the commitment tx spent (we need to check them all because we may receive preimages later)?
        val allHtlcsSpent = (claimHtlcTxs.keys - irrevocablySpent.keys).isEmpty()
        return isCommitTxConfirmed && isMainOutputConfirmed && allHtlcsSpent
    }

    fun isConfirmed(): Boolean {
        return irrevocablySpent.values.any { it.txid == commitTx.txid } || irrevocablySpent.keys.any { it.txid == commitTx.txid }
    }

    internal fun doPublish(channelId: ByteVector32, minDepth: Long): List<ChannelAction> {
        val publishQueue = buildList {
            claimMainOutputTx?.let { add(it.tx) }
            addAll(claimHtlcTxs.values.mapNotNull { it?.tx })
        }
        val publishList = publishIfNeeded(publishQueue, irrevocablySpent, channelId)

        // we watch:
        // - the commitment tx itself, so that we can handle the case where we don't have any outputs
        // - 'final txs' that send funds to our wallet and that spend outputs that only us control
        val watchConfirmedQueue = buildList {
            add(commitTx)
            claimMainOutputTx?.let { add(it.tx) }
        }
        val watchEventConfirmedList = watchConfirmedIfNeeded(watchConfirmedQueue, irrevocablySpent, channelId, minDepth)

        // we watch outputs of the commitment tx that both parties may spend
        val watchSpentQueue = claimHtlcTxs.keys.toList()
        val watchEventSpentList = watchSpentIfNeeded(commitTx, watchSpentQueue, irrevocablySpent, channelId)

        return buildList {
            addAll(publishList)
            addAll(watchEventConfirmedList)
            addAll(watchEventSpentList)
        }
    }
}

/**
 * Details about a force-close where they published one of their revoked commitments.
 *
 * @param commitTx revoked commitment tx.
 * @param claimMainOutputTx tx claiming our main output (if we have one).
 * @param mainPenaltyTx penalty tx claiming their main output (if they have one).
 * @param htlcPenaltyTxs penalty txs claiming every HTLC output.
 * @param claimHtlcDelayedPenaltyTxs penalty txs claiming the output of their HTLC txs (if they managed to get them confirmed before our htlcPenaltyTxs).
 * @param irrevocablySpent map of relevant outpoints that have been spent and the confirmed transaction that spends them.
 */
data class RevokedCommitPublished(
    val commitTx: Transaction,
    val remotePerCommitmentSecret: PrivateKey,
    val claimMainOutputTx: ClaimRemoteCommitMainOutputTx? = null,
    val mainPenaltyTx: MainPenaltyTx? = null,
    val htlcPenaltyTxs: List<HtlcPenaltyTx> = emptyList(),
    val claimHtlcDelayedPenaltyTxs: List<ClaimHtlcDelayedOutputPenaltyTx> = emptyList(),
    val irrevocablySpent: Map<OutPoint, Transaction> = emptyMap()
) {
    /**
     * In CLOSING state, when we are notified that a transaction has been confirmed, we check if this tx belongs in the
     * revoked commit scenario and keep track of it.
     *
     * We need to keep track of all transactions spending the outputs of the commitment tx, because some outputs can be
     * spent both by us and our counterparty. Because of that, some of our transactions may never confirm and we don't
     * want to wait forever before declaring that the channel is CLOSED.
     *
     * @param tx a transaction that has been irrevocably confirmed
     */
    fun update(tx: Transaction): RevokedCommitPublished {
        // even if our txs only have one input, maybe our counterparty uses a different scheme so we need to iterate
        // over all of them to check if they are relevant
        val relevantOutpoints = tx.txIn.map { it.outPoint }.filter { outPoint ->
            // is this the commit tx itself? (we could do this outside of the loop...)
            val isCommitTx = commitTx.txid == tx.txid
            // does the tx spend an output of the commitment tx?
            val spendsTheCommitTx = commitTx.txid == outPoint.txid
            // is the tx a 3rd stage txs? (a 3rd stage tx is a tx spending the output of an htlc tx, which
            // is itself spending the output of the commitment tx)
            val is3rdStageDelayedTx = claimHtlcDelayedPenaltyTxs.map { it.input.outPoint }.contains(outPoint)
            isCommitTx || spendsTheCommitTx || is3rdStageDelayedTx
        }
        // then we add the relevant outpoints to the map keeping track of which txid spends which outpoint
        return this.copy(irrevocablySpent = irrevocablySpent + relevantOutpoints.map { it to tx }.toMap())
    }

    /**
     * A remote commit is considered done when all commitment tx outputs that we can spend have been spent and confirmed
     * (even if the spending tx was not ours).
     */
    fun isDone(): Boolean {
        val confirmedTxs = irrevocablySpent.values.map { it.txid }.toSet()
        // is the commitment tx buried? (we need to check this because we may not have any outputs)
        val isCommitTxConfirmed = confirmedTxs.contains(commitTx.txid)
        // are there remaining spendable outputs from the commitment tx?
        val unspentCommitTxOutputs = run {
            val commitOutputsSpendableByUs = (listOfNotNull(claimMainOutputTx) + listOfNotNull(mainPenaltyTx) + htlcPenaltyTxs).map { it.input.outPoint }
            commitOutputsSpendableByUs.toSet() - irrevocablySpent.keys
        }
        // are all outputs from htlc txs spent?
        val unconfirmedHtlcDelayedTxs = claimHtlcDelayedPenaltyTxs.map { it.input.outPoint }
            // only the txs which parents are already confirmed may get confirmed (note that this also eliminates outputs that have been double-spent by a competing tx)
            .filter { input -> confirmedTxs.contains(input.txid) }
            // if one of the tx inputs has been spent, the tx has already been confirmed or a competing tx has been confirmed
            .filterNot { input -> irrevocablySpent.contains(input) }
        return isCommitTxConfirmed && unspentCommitTxOutputs.isEmpty() && unconfirmedHtlcDelayedTxs.isEmpty()
    }

    fun isConfirmed(): Boolean {
        return irrevocablySpent.values.any { it.txid == commitTx.txid } || irrevocablySpent.keys.any { it.txid == commitTx.txid }
    }

    internal fun doPublish(channelId: ByteVector32, minDepth: Long): List<ChannelAction> {
        val publishQueue = buildList {
            claimMainOutputTx?.let { add(it.tx) }
            mainPenaltyTx?.let { add(it.tx) }
            addAll(htlcPenaltyTxs.map { it.tx })
            addAll(claimHtlcDelayedPenaltyTxs.map { it.tx })
        }
        val publishList = publishIfNeeded(publishQueue, irrevocablySpent, channelId)

        // we watch:
        // - the commitment tx itself, so that we can handle the case where we don't have any outputs
        // - 'final txs' that send funds to our wallet and that spend outputs that only us control
        val watchConfirmedQueue = buildList {
            add(commitTx)
            claimMainOutputTx?.let { add(it.tx) }
        }
        val watchEventConfirmedList = watchConfirmedIfNeeded(watchConfirmedQueue, irrevocablySpent, channelId, minDepth)

        // we watch outputs of the commitment tx that both parties may spend
        val watchSpentQueue = buildList {
            mainPenaltyTx?.let { add(it.input.outPoint) }
            addAll(htlcPenaltyTxs.map { it.input.outPoint })
        }
        val watchEventSpentList = watchSpentIfNeeded(commitTx, watchSpentQueue, irrevocablySpent, channelId)

        return buildList {
            addAll(publishList)
            addAll(watchEventConfirmedList)
            addAll(watchEventSpentList)
        }
    }
}

@OptIn(ExperimentalUnsignedTypes::class)
data class LocalParams constructor(
    val nodeId: PublicKey,
    val fundingKeyPath: KeyPath,
    val dustLimit: Satoshi,
    val maxHtlcValueInFlightMsat: Long, // this is not MilliSatoshi because it can exceed the total amount of MilliSatoshi
    val channelReserve: Satoshi,
    val htlcMinimum: MilliSatoshi,
    val toSelfDelay: CltvExpiryDelta,
    val maxAcceptedHtlcs: Int,
    val isFunder: Boolean,
    val defaultFinalScriptPubKey: ByteVector,
    val features: Features
)

@OptIn(ExperimentalUnsignedTypes::class)
data class RemoteParams(
    val nodeId: PublicKey,
    val dustLimit: Satoshi,
    val maxHtlcValueInFlightMsat: Long, // this is not MilliSatoshi because it can exceed the total amount of MilliSatoshi
    val channelReserve: Satoshi,
    val htlcMinimum: MilliSatoshi,
    val toSelfDelay: CltvExpiryDelta,
    val maxAcceptedHtlcs: Int,
    val fundingPubKey: PublicKey,
    val revocationBasepoint: PublicKey,
    val paymentBasepoint: PublicKey,
    val delayedPaymentBasepoint: PublicKey,
    val htlcBasepoint: PublicKey,
    val features: Features
)

@Serializable
data class ChannelVersion(val bits: BitField) {
    init {
        require(bits.byteSize == SIZE_BYTE) { "channel version takes 4 bytes" }
    }

    infix fun or(other: ChannelVersion) = ChannelVersion(bits or other.bits)
    infix fun and(other: ChannelVersion) = ChannelVersion(bits and other.bits)
    infix fun xor(other: ChannelVersion) = ChannelVersion(bits xor other.bits)

    fun isSet(bit: Int) = bits.getRight(bit)

    val hasStaticRemotekey: Boolean by lazy { isSet(USE_STATIC_REMOTEKEY_BIT) }
    val hasAnchorOutputs: Boolean by lazy { isSet(USE_ANCHOR_OUTPUTS_BIT) }

    companion object {
        const val SIZE_BYTE = 4
        val ZEROES = ChannelVersion(BitField(SIZE_BYTE))
        const val USE_PUBKEY_KEYPATH_BIT = 0 // bit numbers start at 0
        const val USE_STATIC_REMOTEKEY_BIT = 1
        const val USE_ANCHOR_OUTPUTS_BIT = 2
        const val ZERO_RESERVE_BIT = 3

        private fun fromBit(bit: Int) = ChannelVersion(BitField(SIZE_BYTE).apply { setRight(bit) })

        private val USE_PUBKEY_KEYPATH = fromBit(USE_PUBKEY_KEYPATH_BIT)
        private val USE_STATIC_REMOTEKEY = fromBit(USE_STATIC_REMOTEKEY_BIT)
        private val USE_ANCHOR_OUTPUTS = fromBit(USE_ANCHOR_OUTPUTS_BIT)
        val ZERO_RESERVE = fromBit(ZERO_RESERVE_BIT)

        val STANDARD = ZEROES or USE_PUBKEY_KEYPATH or USE_STATIC_REMOTEKEY or USE_ANCHOR_OUTPUTS
    }
}

object ChannelFlags {
    const val AnnounceChannel = 0x01.toByte()
    const val Empty = 0x00.toByte()
}

data class ClosingTxProposed(val unsignedTx: ClosingTx, val localClosingSigned: ClosingSigned)

/** This gives the reason for creating a new channel */
@Serializable
sealed class ChannelOrigin {
    abstract val fee: Satoshi
    data class PayToOpenOrigin(val paymentHash: ByteVector32, override val fee: Satoshi) : ChannelOrigin()
    data class SwapInOrigin(val bitcoinAddress: String, override val fee: Satoshi) : ChannelOrigin()
}
