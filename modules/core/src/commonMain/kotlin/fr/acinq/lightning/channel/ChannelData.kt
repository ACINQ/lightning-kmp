package fr.acinq.lightning.channel

import fr.acinq.bitcoin.*
import fr.acinq.lightning.CltvExpiryDelta
import fr.acinq.lightning.Features
import fr.acinq.lightning.MilliSatoshi
import fr.acinq.lightning.NodeParams
import fr.acinq.lightning.channel.Helpers.publishIfNeeded
import fr.acinq.lightning.channel.Helpers.watchConfirmedIfNeeded
import fr.acinq.lightning.channel.Helpers.watchSpentIfNeeded
import fr.acinq.lightning.crypto.KeyManager
import fr.acinq.lightning.logging.LoggingContext
import fr.acinq.lightning.transactions.Transactions
import fr.acinq.lightning.utils.toMilliSatoshi

/**
 * When a commitment is published, we keep track of all outputs that can be spent (even if we don't yet have the data
 * to spend them, for example the preimage for received HTLCs). Once all of those outputs have been spent by a confirmed
 * transaction, the channel close is complete.
 *
 * Note that we only store transactions after they have been confirmed: we're using RBF to get transactions confirmed,
 * and it would be wasteful to store previous versions of the transactions that have been replaced.
 */
sealed class CommitPublished {
    /** Fully signed commitment transaction. */
    abstract val commitTx: Transaction
    /** Our main output, if we had some balance in the channel. */
    abstract val localOutput: OutPoint?
    /** Our anchor output, if one is available to CPFP the [commitTx]. */
    abstract val anchorOutput: OutPoint?
    /**
     * Outputs corresponding to HTLCs that we may be able to claim (even when we don't have the preimage yet).
     * Note that some HTLC outputs of the [commitTx] may not be included, if we know that we will never claim them
     * (such as HTLCs that we started failing before the channel closed).
     */
    abstract val htlcOutputs: Set<OutPoint>
    /** Map of outpoints that have been spent and the confirmed transaction that spends them. */
    abstract val irrevocablySpent: Map<OutPoint, Transaction>

    /**
     * In CLOSING state, when we are notified that a transaction has been confirmed, we check if this tx belongs in the
     * local commit scenario and keep track of it.
     *
     * We need to keep track of all transactions spending the outputs of the commitment tx, because some outputs can be
     * spent both by us and our counterparty. Because of that, some of our transactions may never confirm and we don't
     * want to wait forever before declaring that the channel is CLOSED.
     *
     * @param tx a transaction that has been irrevocably confirmed.
     */
    abstract fun updateIrrevocablySpent(tx: Transaction): CommitPublished

    /** Returns true if the commitment transaction is confirmed. */
    val isConfirmed: Boolean get() = run {
        // NB: if multiple transactions end up in the same block, the first confirmation we receive may not be the commit tx.
        // However if the confirmed tx spends from the commit tx, we know that the commit tx is already confirmed and we know
        // the type of closing.
        irrevocablySpent.values.any { tx -> tx.txid == commitTx.txid } || irrevocablySpent.keys.any { spent -> spent.txid == commitTx.txid }
    }
    /**
     * Returns true when all outputs that can be claimed have been spent: we can forget the channel at that point.
     * Note that some of those outputs may be claimed by our peer (e.g. HTLCs that reached their expiry).
     */
    abstract val isDone: Boolean
}

/** Transactions spending outputs of our commitment transaction. */
data class LocalCommitSecondStageTransactions(val mainDelayedTx: Transactions.ClaimLocalDelayedOutputTx?, val htlcTxs: List<Transactions.HtlcTx>)

/** Transactions spending outputs of our HTLC transactions. */
data class LocalCommitThirdStageTransactions(val htlcDelayedTxs: List<Transactions.HtlcDelayedTx>)

/**
 * Details about a force-close where we published our commitment.
 *
 * @param htlcDelayedOutputs when an HTLC transaction confirms, we must claim its output using a 3rd-stage delayed
 *  transaction. An entry containing the corresponding output must be added to this set to
 *  ensure that we don't forget the channel too soon, and correctly wait until we've spent it.
 */
data class LocalCommitPublished(
    override val commitTx: Transaction,
    override val localOutput: OutPoint?,
    override val anchorOutput: OutPoint?,
    val incomingHtlcs: Map<OutPoint, Long>,
    val outgoingHtlcs: Map<OutPoint, Long>,
    val htlcDelayedOutputs: Set<OutPoint>,
    override val irrevocablySpent: Map<OutPoint, Transaction>
) : CommitPublished() {
    override val htlcOutputs: Set<OutPoint> = incomingHtlcs.keys + outgoingHtlcs.keys
    override val isDone: Boolean = run {
        val mainOutputSpent = localOutput?.let { irrevocablySpent.contains(it) } ?: true
        val allHtlcsSpent = (htlcOutputs - irrevocablySpent.keys).isEmpty()
        val allHtlcTxsSpent = (htlcDelayedOutputs - irrevocablySpent.keys).isEmpty()
        isConfirmed && mainOutputSpent && allHtlcsSpent && allHtlcTxsSpent
    }

    override fun updateIrrevocablySpent(tx: Transaction): LocalCommitPublished {
        // even if our txs only have one input, maybe our counterparty uses a different scheme so we need to iterate
        // over all of them to check if they are relevant
        val relevantOutpoints = tx.txIn.map { it.outPoint }.filter { outPoint ->
            // is this the commit tx itself? (we could do this outside of the loop...)
            val isCommitTx = commitTx.txid == tx.txid
            // does the tx spend an output of the local commitment tx?
            val spendsTheCommitTx = commitTx.txid == outPoint.txid
            // is the tx one of our 3rd stage delayed txs? (a 3rd stage tx is a tx spending the output of an htlc tx, which
            // is itself spending the output of the commitment tx)
            val is3rdStageDelayedTx = htlcDelayedOutputs.contains(outPoint)
            isCommitTx || spendsTheCommitTx || is3rdStageDelayedTx
        }
        // then we add the relevant outpoints to the map keeping track of which txid spends which outpoint
        return this.copy(irrevocablySpent = irrevocablySpent + relevantOutpoints.associateWith { tx }.toMap())
    }

    internal fun LoggingContext.doPublish(nodeParams: NodeParams, channelId: ByteVector32, txs: LocalCommitSecondStageTransactions): List<ChannelAction> {
        val publishQueue = buildList {
            add(ChannelAction.Blockchain.PublishTx(commitTx, ChannelAction.Blockchain.PublishTx.Type.CommitTx))
            txs.mainDelayedTx?.let { add(ChannelAction.Blockchain.PublishTx(it)) }
            addAll(txs.htlcTxs.map { ChannelAction.Blockchain.PublishTx(it) })
        }
        val publishList = publishIfNeeded(publishQueue, irrevocablySpent)
        // We watch the commitment transaction: once confirmed, it invalidates other types of force-close.
        val watchConfirmedList = watchConfirmedIfNeeded(nodeParams, channelId, listOf(commitTx), irrevocablySpent)
        // We watch outputs of the commitment transaction that we may spend: every time we detect a spending transaction,
        // we will watch for its confirmation. This ensures that we detect double-spends that could come from:
        //  - our own RBF attempts
        //  - remote transactions for outputs that both parties may spend (e.g. HTLCs)
        val watchSpentQueue = listOfNotNull(localOutput) + htlcOutputs
        val watchSpentList = watchSpentIfNeeded(channelId, commitTx, watchSpentQueue, irrevocablySpent)
        return buildList {
            addAll(publishList)
            addAll(watchConfirmedList)
            addAll(watchSpentList)
        }
    }

    internal fun LoggingContext.doPublish(channelId: ByteVector32, txs: LocalCommitThirdStageTransactions): List<ChannelAction> {
        val publishList = publishIfNeeded(txs.htlcDelayedTxs.map { ChannelAction.Blockchain.PublishTx(it) }, irrevocablySpent)
        // We watch the spent outputs to detect our RBF attempts.
        val watchSpentList = txs.htlcDelayedTxs.flatMap { tx -> watchSpentIfNeeded(channelId, tx.input, irrevocablySpent) }
        return buildList {
            addAll(publishList)
            addAll(watchSpentList)
        }
    }
}

/** Transactions spending outputs of a remote commitment transaction. */
data class RemoteCommitSecondStageTransactions(val mainTx: Transactions.ClaimRemoteDelayedOutputTx?, val htlcTxs: List<Transactions.ClaimHtlcTx>)

/**
 * Details about a force-close where they published their commitment (current or next).
 */
data class RemoteCommitPublished(
    override val commitTx: Transaction,
    override val localOutput: OutPoint?,
    override val anchorOutput: OutPoint?,
    val incomingHtlcs: Map<OutPoint, Long>,
    val outgoingHtlcs: Map<OutPoint, Long>,
    override val irrevocablySpent: Map<OutPoint, Transaction>
) : CommitPublished() {
    override val htlcOutputs: Set<OutPoint> = incomingHtlcs.keys + outgoingHtlcs.keys
    override val isDone: Boolean = run {
        val mainOutputSpent = localOutput?.let { irrevocablySpent.contains(it) } ?: true
        val allHtlcsSpent = (htlcOutputs - irrevocablySpent.keys).isEmpty()
        isConfirmed && mainOutputSpent && allHtlcsSpent
    }

    override fun updateIrrevocablySpent(tx: Transaction): RemoteCommitPublished {
        // even if our txs only have one input, maybe our counterparty uses a different scheme so we need to iterate
        // over all of them to check if they are relevant
        val relevantOutpoints = tx.txIn.map { it.outPoint }.filter { outPoint ->
            // is this the commit tx itself? (we could do this outside of the loop...)
            val isCommitTx = commitTx.txid == tx.txid
            // does the tx spend an output of the commitment tx?
            val spendsTheCommitTx = commitTx.txid == outPoint.txid
            isCommitTx || spendsTheCommitTx
        }
        // then we add the relevant outpoints to the map keeping track of which txid spends which outpoint
        return this.copy(irrevocablySpent = irrevocablySpent + relevantOutpoints.associateWith { tx }.toMap())
    }

    internal fun LoggingContext.doPublish(nodeParams: NodeParams, channelId: ByteVector32, txs: RemoteCommitSecondStageTransactions): List<ChannelAction> {
        val publishQueue = buildList {
            txs.mainTx?.let { add(ChannelAction.Blockchain.PublishTx(it)) }
            addAll(txs.htlcTxs.map { ChannelAction.Blockchain.PublishTx(it) })
        }
        val publishList = publishIfNeeded(publishQueue, irrevocablySpent)
        // We watch the commitment transaction: once confirmed, it invalidates other types of force-close.
        val watchEventConfirmedList = watchConfirmedIfNeeded(nodeParams, channelId, listOf(commitTx), irrevocablySpent)
        // We watch outputs of the commitment transaction that we may spend: every time we detect a spending transaction,
        // we will watch for its confirmation. This ensures that we detect double-spends that could come from:
        //  - our own RBF attempts
        //  - remote transactions for outputs that both parties may spend (e.g. HTLCs)
        val watchSpentQueue = listOfNotNull(localOutput) + htlcOutputs
        val watchEventSpentList = watchSpentIfNeeded(channelId, commitTx, watchSpentQueue, irrevocablySpent)
        return buildList {
            addAll(publishList)
            addAll(watchEventConfirmedList)
            addAll(watchEventSpentList)
        }
    }
}

/** Transactions spending outputs of a revoked remote commitment transactions. */
data class RevokedCommitSecondStageTransactions(val mainTx: Transactions.ClaimRemoteDelayedOutputTx?, val mainPenaltyTx: Transactions.MainPenaltyTx?, val htlcPenaltyTxs: List<Transactions.HtlcPenaltyTx>)

/** Transactions spending outputs of confirmed remote HTLC transactions. */
data class RevokedCommitThirdStageTransactions(val htlcDelayedPenaltyTxs: List<Transactions.ClaimHtlcDelayedOutputPenaltyTx>)

/**
 * Details about a force-close where they published one of their revoked commitments.
 * In that case, we're able to spend every output of the commitment transaction (if economical).
 *
 * @param htlcDelayedOutputs if our peer manages to get some of their HTLC transactions confirmed before our penalty
 *  transactions, we must spend the output(s) of their HTLC transactions.
 */
data class RevokedCommitPublished(
    override val commitTx: Transaction,
    val remotePerCommitmentSecret: PrivateKey,
    override val localOutput: OutPoint?,
    val remoteOutput: OutPoint?,
    override val htlcOutputs: Set<OutPoint>,
    val htlcDelayedOutputs: Set<OutPoint>,
    override val irrevocablySpent: Map<OutPoint, Transaction>
) : CommitPublished() {
    // We don't use the anchor output, we can CPFP the commitment with any other output.
    override val anchorOutput: OutPoint? = null
    override val isDone: Boolean = run {
        val mainOutputSpent = localOutput?.let { irrevocablySpent.contains(it) } ?: true
        val remoteOutputSpent = remoteOutput?.let { irrevocablySpent.contains(it) } ?: true
        val allHtlcsSpent = (htlcOutputs - irrevocablySpent.keys).isEmpty()
        val allHtlcTxsSpent = (htlcDelayedOutputs - irrevocablySpent.keys).isEmpty()
        isConfirmed && mainOutputSpent && remoteOutputSpent && allHtlcsSpent && allHtlcTxsSpent
    }

    override fun updateIrrevocablySpent(tx: Transaction): RevokedCommitPublished {
        // even if our txs only have one input, maybe our counterparty uses a different scheme so we need to iterate
        // over all of them to check if they are relevant
        val relevantOutpoints = tx.txIn.map { it.outPoint }.filter { outPoint ->
            // is this the commit tx itself? (we could do this outside of the loop...)
            val isCommitTx = commitTx.txid == tx.txid
            // does the tx spend an output of the commitment tx?
            val spendsTheCommitTx = commitTx.txid == outPoint.txid
            // is the tx a 3rd stage txs? (a 3rd stage tx is a tx spending the output of an htlc tx, which
            // is itself spending the output of the commitment tx)
            val is3rdStageDelayedTx = htlcDelayedOutputs.contains(outPoint)
            isCommitTx || spendsTheCommitTx || is3rdStageDelayedTx
        }
        // then we add the relevant outpoints to the map keeping track of which txid spends which outpoint
        return this.copy(irrevocablySpent = irrevocablySpent + relevantOutpoints.associateWith { tx }.toMap())
    }

    internal fun LoggingContext.doPublish(nodeParams: NodeParams, channelId: ByteVector32, txs: RevokedCommitSecondStageTransactions): List<ChannelAction> {
        val publishQueue = buildList {
            txs.mainTx?.let { add(ChannelAction.Blockchain.PublishTx(it)) }
            txs.mainPenaltyTx?.let { add(ChannelAction.Blockchain.PublishTx(it)) }
            addAll(txs.htlcPenaltyTxs.map { ChannelAction.Blockchain.PublishTx(it) })
        }
        val publishList = publishIfNeeded(publishQueue, irrevocablySpent)
        // We watch the commitment transaction: once confirmed, it invalidates other types of force-close.
        val watchEventConfirmedList = watchConfirmedIfNeeded(nodeParams, channelId, listOf(commitTx), irrevocablySpent)
        // We watch outputs of the commitment tx that both parties may spend, or that we may RBF.
        val watchSpentQueue = listOfNotNull(localOutput, remoteOutput) + htlcOutputs
        val watchEventSpentList = watchSpentIfNeeded(channelId, commitTx, watchSpentQueue, irrevocablySpent)
        return buildList {
            addAll(publishList)
            addAll(watchEventConfirmedList)
            addAll(watchEventSpentList)
        }
    }

    internal fun LoggingContext.doPublish(channelId: ByteVector32, txs: RevokedCommitThirdStageTransactions): List<ChannelAction> {
        val publishList = publishIfNeeded(txs.htlcDelayedPenaltyTxs.map { ChannelAction.Blockchain.PublishTx(it) }, irrevocablySpent)
        // We watch the spent outputs to detect our RBF attempts.
        val watchSpentList = txs.htlcDelayedPenaltyTxs.flatMap { tx -> watchSpentIfNeeded(channelId, tx.input, irrevocablySpent) }
        return buildList {
            addAll(publishList)
            addAll(watchSpentList)
        }
    }
}

/** Local params that apply for the channel's lifetime. */
data class LocalChannelParams(
    val nodeId: PublicKey,
    val fundingKeyPath: KeyPath,
    val isChannelOpener: Boolean,
    val paysCommitTxFees: Boolean,
    val defaultFinalScriptPubKey: ByteVector,
    val features: Features
) {
    constructor(nodeParams: NodeParams, isChannelOpener: Boolean, payCommitTxFees: Boolean) : this(
        nodeId = nodeParams.nodeId,
        fundingKeyPath = nodeParams.keyManager.newFundingKeyPath(isChannelOpener), // we make sure that initiator and non-initiator key path end differently
        isChannelOpener = isChannelOpener,
        paysCommitTxFees = payCommitTxFees,
        defaultFinalScriptPubKey = nodeParams.keyManager.finalOnChainWallet.pubkeyScript(addressIndex = 0), // the default closing address is the same for all channels
        features = nodeParams.features.initFeatures()
    )

    fun channelKeys(keyManager: KeyManager) = keyManager.channelKeys(fundingKeyPath)
}

/** Remote params that apply for the channel's lifetime. */
data class RemoteChannelParams(
    val nodeId: PublicKey,
    val revocationBasepoint: PublicKey,
    val paymentBasepoint: PublicKey,
    val delayedPaymentBasepoint: PublicKey,
    val htlcBasepoint: PublicKey,
    val features: Features
)

/** Configuration parameters that apply to local or remote commitment transactions, and may be updated dynamically. */
data class CommitParams(
    val dustLimit: Satoshi,
    val maxHtlcValueInFlightMsat: Long,
    val htlcMinimum: MilliSatoshi,
    val toSelfDelay: CltvExpiryDelta,
    val maxAcceptedHtlcs: Int,
)

/**
 * The [nonInitiatorPaysCommitFees] parameter can be set to true when the sender wants the receiver to pay the commitment transaction fees.
 * This is not part of the BOLTs and won't be needed anymore once commitment transactions don't pay any on-chain fees.
 */
data class ChannelFlags(val announceChannel: Boolean, val nonInitiatorPaysCommitFees: Boolean)

/**
 * @param miningFee fee paid to miners for the underlying on-chain transaction.
 * @param serviceFee fee paid to our peer for any service provided with the on-chain transaction.
 */
data class ChannelManagementFees(val miningFee: Satoshi, val serviceFee: Satoshi) {
    val total: Satoshi = miningFee + serviceFee
}

/** Reason for creating a new channel or splicing into an existing channel. */
// @formatter:off
sealed class Origin {
    /** Amount of the origin payment, before fees are paid. */
    abstract val amountBeforeFees: MilliSatoshi
    /** Fees applied for the channel funding transaction. */
    abstract val fees: ChannelManagementFees

    fun amountReceived(): MilliSatoshi = amountBeforeFees - fees.total.toMilliSatoshi()

    data class OnChainWallet(val inputs: Set<OutPoint>, override val amountBeforeFees: MilliSatoshi, override val fees: ChannelManagementFees) : Origin()
    data class OffChainPayment(val paymentPreimage: ByteVector32, override val amountBeforeFees: MilliSatoshi, override val fees: ChannelManagementFees) : Origin() {
        val paymentHash: ByteVector32 = Crypto.sha256(paymentPreimage).byteVector32()
    }
}
// @formatter:on
