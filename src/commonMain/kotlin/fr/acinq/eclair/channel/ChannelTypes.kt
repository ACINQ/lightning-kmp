package fr.acinq.eclair.channel

import fr.acinq.bitcoin.*
import fr.acinq.eclair.CltvExpiry
import fr.acinq.eclair.CltvExpiryDelta
import fr.acinq.eclair.Features
import fr.acinq.eclair.MilliSatoshi
import fr.acinq.eclair.channel.Helpers.publishIfNeeded
import fr.acinq.eclair.channel.Helpers.watchConfirmedIfNeeded
import fr.acinq.eclair.channel.Helpers.watchSpentIfNeeded
import fr.acinq.eclair.io.*
import fr.acinq.eclair.utils.BitField
import fr.acinq.eclair.utils.UUID
import fr.acinq.eclair.utils.sum
import fr.acinq.eclair.wire.ClosingSigned
import fr.acinq.eclair.wire.FailureMessage
import fr.acinq.eclair.wire.OnionRoutingPacket
import fr.acinq.eclair.wire.UpdateAddHtlc
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
sealed class HasHtlcId : Command() { abstract val id: Long }
data class CMD_FULFILL_HTLC(override val id: Long, val r: ByteVector32, val commit: Boolean = false) : HasHtlcId()
data class CMD_FAIL_HTLC(override val id: Long, val reason: Reason, val commit: Boolean = false) : HasHtlcId() {
    sealed class Reason {
        data class Bytes(val bytes: ByteVector): Reason()
        data class Failure(val message: FailureMessage): Reason()
    }
}
data class CMD_FAIL_MALFORMED_HTLC(override val id: Long, val onionHash: ByteVector32, val failureCode: Int, val commit: Boolean = false) : HasHtlcId()
data class CMD_ADD_HTLC(val amount: MilliSatoshi, val paymentHash: ByteVector32, val cltvExpiry: CltvExpiry, val onion: OnionRoutingPacket, val paymentId: UUID, val commit: Boolean = false, val previousFailures: List<AddHtlcFailed> = emptyList()) : Command()
data class CMD_UPDATE_FEE(val feeratePerKw: Long, val commit: Boolean = false) : Command()
object CMD_SIGN : Command()
data class CMD_CLOSE(val scriptPubKey: ByteVector?) : Command()
data class CMD_UPDATE_RELAY_FEE(val feeBase: MilliSatoshi, val feeProportionalMillionths: Long) : Command()
object CMD_FORCECLOSE : Command()
object CMD_GETSTATE : Command()
object CMD_GETSTATEDATA : Command()


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
@Serializable
data class LocalCommitPublished(
    @Serializable(with = TransactionKSerializer::class)
    val commitTx: Transaction,
    @Serializable(with = TransactionKSerializer::class)
    val claimMainDelayedOutputTx: Transaction? = null,
    val htlcSuccessTxs: List<@Serializable(with = TransactionKSerializer::class) Transaction> = emptyList(),
    val htlcTimeoutTxs: List<@Serializable(with = TransactionKSerializer::class) Transaction> = emptyList(),
    val claimHtlcDelayedTxs: List<@Serializable(with = TransactionKSerializer::class) Transaction> = emptyList(),
    val irrevocablySpent: Map<@Serializable(with = OutPointKSerializer::class) OutPoint, @Serializable(with = ByteVectorKSerializer::class) ByteVector32> = emptyMap()
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
        // even if our txes only have one input, maybe our counterparty uses a different scheme so we need to iterate
        // over all of them to check if they are relevant
        val relevantOutpoints = tx.txIn.map { it.outPoint }.filter { outPoint ->
            // is this the commit tx itself ? (we could do this outside of the loop...)
            val isCommitTx = commitTx.txid == tx.txid
            // does the tx spend an output of the local commitment tx?
            val spendsTheCommitTx = commitTx.txid == outPoint.txid
            // is the tx one of our 3rd stage delayed txes? (a 3rd stage tx is a tx spending the output of an htlc tx, which
            // is itself spending the output of the commitment tx)
            val is3rdStageDelayedTx = claimHtlcDelayedTxs.map { it.txid }.contains(tx.txid)
            isCommitTx || spendsTheCommitTx || is3rdStageDelayedTx
        }
        // then we add the relevant outpoints to the map keeping track of which txid spends which outpoint
        return this.copy(irrevocablySpent = irrevocablySpent + relevantOutpoints.map { it to tx.txid }.toMap())
    }

    /**
     * A local commit is considered done when:
     * - all commitment tx outputs that we can spend have been spent and confirmed (even if the spending tx was not ours)
     * - all 3rd stage txes (txes spending htlc txes) have been confirmed
     */
    fun isDone(): Boolean {
        // is the commitment tx buried? (we need to check this because we may not have any outputs)
        val isCommitTxConfirmed = irrevocablySpent.values.toSet().contains(commitTx.txid)

        // are there remaining spendable outputs from the commitment tx? we just subtract all known spent outputs from the ones we control
        val commitOutputsSpendableByUs = (listOfNotNull(claimMainDelayedOutputTx) + htlcSuccessTxs + htlcTimeoutTxs)
            .flatMap { it.txIn.map { it.outPoint }.toSet() - irrevocablySpent.keys }

        // which htlc delayed txes can we expect to be confirmed?
        val unconfirmedHtlcDelayedTxes = claimHtlcDelayedTxs
            .filter { tx ->
                (tx.txIn.map { it.outPoint.txid }.toSet() - irrevocablySpent.values.toSet()).isEmpty()
            } // only the txes which parents are already confirmed may get confirmed (note that this also eliminates outputs that have been double-spent by a competing tx)
            .filterNot {
                irrevocablySpent.values.toSet().contains(it.txid)
            } // has the tx already been confirmed?

        return isCommitTxConfirmed && commitOutputsSpendableByUs.isEmpty() && unconfirmedHtlcDelayedTxes.isEmpty()
    }

    fun isConfirmed(): Boolean {
        val confirmedTxs = irrevocablySpent.values.toSet()
        return (listOf(commitTx) + listOfNotNull(claimMainDelayedOutputTx) + htlcSuccessTxs + htlcTimeoutTxs).any {
            confirmedTxs.contains(it.txid)
        }
    }

    internal fun doPublish(channelId: ByteVector32, minDepth: Long): List<ChannelAction> {
        val publishQueue = buildList {
            add(commitTx)
            claimMainDelayedOutputTx?.let { add(it) }
            addAll(htlcSuccessTxs)
            addAll(htlcTimeoutTxs)
            addAll(claimHtlcDelayedTxs)
        }
        val publishList = publishIfNeeded(publishQueue, irrevocablySpent)

        // we watch:
        // - the commitment tx itself, so that we can handle the case where we don't have any outputs
        // - 'final txes' that send funds to our wallet and that spend outputs that only us control
        val watchConfirmedQueue = buildList {
            add(commitTx)
            claimMainDelayedOutputTx?.let { add(it) }
            addAll(claimHtlcDelayedTxs)
        }
        val watchConfirmedList = watchConfirmedIfNeeded(watchConfirmedQueue, irrevocablySpent, channelId, minDepth)

        // we watch outputs of the commitment tx that both parties may spend
        val watchSpentQueue = buildList {
            addAll(htlcSuccessTxs)
            addAll(htlcTimeoutTxs)
        }
        val watchSpentList = watchSpentIfNeeded(commitTx, watchSpentQueue, irrevocablySpent, channelId)

        return buildList {
            addAll(publishList)
            addAll(watchConfirmedList)
            addAll(watchSpentList)
        }
    }
}

@Serializable
data class RemoteCommitPublished(
    @Serializable(with = TransactionKSerializer::class)
    val commitTx: Transaction,
    @Serializable(with = TransactionKSerializer::class)
    val claimMainOutputTx: Transaction? = null,
    val claimHtlcSuccessTxs: List<@Serializable(with = TransactionKSerializer::class) Transaction> = emptyList(),
    val claimHtlcTimeoutTxs: List<@Serializable(with = TransactionKSerializer::class) Transaction> = emptyList(),
    val irrevocablySpent: Map<@Serializable(with = OutPointKSerializer::class) OutPoint, @Serializable(with = ByteVectorKSerializer::class) ByteVector32> = emptyMap()
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
        // even if our txes only have one input, maybe our counterparty uses a different scheme so we need to iterate
        // over all of them to check if they are relevant
        val relevantOutpoints = tx.txIn.map { it.outPoint }.filter {
            // is this the commit tx itself ? (we could do this outside of the loop...)
            val isCommitTx = commitTx.txid == tx.txid
            // does the tx spend an output of the local commitment tx?
            val spendsTheCommitTx = commitTx.txid == it.txid
            isCommitTx || spendsTheCommitTx
        }
        // then we add the relevant outpoints to the map keeping track of which txid spends which outpoint
        return this.copy(irrevocablySpent = irrevocablySpent + relevantOutpoints.map { it to tx.txid }.toMap())
    }

    /**
     * A remote commit is considered done when all commitment tx outputs that we can spend have been spent and confirmed
     * (even if the spending tx was not ours).
     */
    fun isDone(): Boolean {
        // is the commitment tx buried? (we need to check this because we may not have any outputs)
        val isCommitTxConfirmed = irrevocablySpent.values.toSet().contains(commitTx.txid)

        // are there remaining spendable outputs from the commitment tx?
        val commitOutputsSpendableByUs = (listOfNotNull(claimMainOutputTx) + claimHtlcSuccessTxs + claimHtlcTimeoutTxs)
            .flatMap { it.txIn.map(TxIn::outPoint) }.toSet() - irrevocablySpent.keys

        return isCommitTxConfirmed && commitOutputsSpendableByUs.isEmpty()
    }

    fun isConfirmed(): Boolean {
        val confirmedTxs = irrevocablySpent.values.toSet()
        return (listOf(commitTx) + listOfNotNull(claimMainOutputTx) + claimHtlcSuccessTxs + claimHtlcTimeoutTxs).any {
            confirmedTxs.contains(it.txid)
        }
    }

    internal fun doPublish(channelId: ByteVector32, minDepth: Long): List<ChannelAction> {
        val publishQueue = buildList {
            claimMainOutputTx?.let { add(it) }
            addAll(claimHtlcSuccessTxs)
            addAll(claimHtlcTimeoutTxs)
        }

        val publishList = publishIfNeeded(publishQueue, irrevocablySpent)

        // we watch:
        // - the commitment tx itself, so that we can handle the case where we don't have any outputs
        // - 'final txes' that send funds to our wallet and that spend outputs that only us control
        val watchConfirmedQueue = buildList {
            add(commitTx)
            claimMainOutputTx?.let { add(it) }
        }
        val watchEventConfirmedList = watchConfirmedIfNeeded(watchConfirmedQueue, irrevocablySpent, channelId, minDepth)

        // we watch outputs of the commitment tx that both parties may spend
        val watchSpentQueue = buildList {
            addAll(claimHtlcTimeoutTxs)
            addAll(claimHtlcSuccessTxs)
        }
        val watchEventSpentList = watchSpentIfNeeded(commitTx, watchSpentQueue, irrevocablySpent, channelId)

        return buildList {
            addAll(publishList)
            addAll(watchEventConfirmedList)
            addAll(watchEventSpentList)
        }
    }
}

@Serializable
data class RevokedCommitPublished(
    @Serializable(with = TransactionKSerializer::class)
    val commitTx: Transaction,
    @Serializable(with = TransactionKSerializer::class)
    val claimMainOutputTx: Transaction? = null,
    @Serializable(with = TransactionKSerializer::class)
    val mainPenaltyTx: Transaction? = null,
    val htlcPenaltyTxs: List<@Serializable(with = TransactionKSerializer::class) Transaction> = emptyList(),
    val claimHtlcDelayedPenaltyTxs: List<@Serializable(with = TransactionKSerializer::class) Transaction> = emptyList(),
    val irrevocablySpent: Map<@Serializable(with = OutPointKSerializer::class) OutPoint, @Serializable(with = ByteVectorKSerializer::class) ByteVector32> = emptyMap()
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
        // even if our txes only have one input, maybe our counterparty uses a different scheme so we need to iterate
        // over all of them to check if they are relevant
        val relevantOutpoints = tx.txIn.map { it.outPoint }.filter { outPoint ->
            // is this the commit tx itself ? (we could do this outside of the loop...)
            val isCommitTx = commitTx.txid == tx.txid
            // does the tx spend an output of the local commitment tx?
            val spendsTheCommitTx = commitTx.txid == outPoint.txid
            // is the tx one of our 3rd stage delayed txes? (a 3rd stage tx is a tx spending the output of an htlc tx, which
            // is itself spending the output of the commitment tx)
            val is3rdStageDelayedTx = claimHtlcDelayedPenaltyTxs.map { it.txid }.contains(tx.txid)
            isCommitTx || spendsTheCommitTx || is3rdStageDelayedTx
        }
        // then we add the relevant outpoints to the map keeping track of which txid spends which outpoint
        return this.copy(irrevocablySpent = irrevocablySpent + relevantOutpoints.map { it to tx.txid }.toMap())
    }

    /**
     * A remote commit is considered done when all commitment tx outputs that we can spend have been spent and confirmed
     * (even if the spending tx was not ours).
     */
    fun done(): Boolean {
        // is the commitment tx buried? (we need to check this because we may not have any outputs)
        val isCommitTxConfirmed = irrevocablySpent.values.toSet().contains(commitTx.txid)
        // are there remaining spendable outputs from the commitment tx?
        val commitOutputsSpendableByUs = (listOfNotNull(claimMainOutputTx) + listOfNotNull(mainPenaltyTx) + htlcPenaltyTxs)
            .flatMap { it.txIn.map(TxIn::outPoint) }.toSet() - irrevocablySpent.keys

        // which htlc delayed txes can we expect to be confirmed?
        val unconfirmedHtlcDelayedTxes = claimHtlcDelayedPenaltyTxs
            .filter { tx ->
                (tx.txIn.map { it.outPoint.txid }.toSet() - irrevocablySpent.values.toSet()).isEmpty()
            } // only the txes which parents are already confirmed may get confirmed (note that this also eliminates outputs that have been double-spent by a competing tx)
            .filterNot {
                irrevocablySpent.values.toSet().contains(it.txid)
            } // has the tx already been confirmed?

        return isCommitTxConfirmed && commitOutputsSpendableByUs.isEmpty() && unconfirmedHtlcDelayedTxes.isEmpty()
    }

    internal fun doPublish(channelId: ByteVector32, minDepth: Long): List<ChannelAction> {
        val publishQueue = buildList {
            claimMainOutputTx?.let { add(it) }
            mainPenaltyTx?.let { add(it) }
            addAll(htlcPenaltyTxs)
            addAll(claimHtlcDelayedPenaltyTxs)
        }
        val publishList = publishIfNeeded(publishQueue, irrevocablySpent)

        // we watch:
        // - the commitment tx itself, so that we can handle the case where we don't have any outputs
        // - 'final txes' that send funds to our wallet and that spend outputs that only us control
        val watchConfirmedQueue = buildList {
            add(commitTx)
            claimMainOutputTx?.let { add(it) }
        }
        val watchEventConfirmedList = watchConfirmedIfNeeded(watchConfirmedQueue, irrevocablySpent, channelId, minDepth)


        // we watch outputs of the commitment tx that both parties may spend
        val watchSpentQueue = buildList {
            mainPenaltyTx?.let { add(it) }
            addAll(htlcPenaltyTxs)
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
@Serializable
data class LocalParams constructor(
    @Serializable(with = PublicKeyKSerializer::class) val nodeId: PublicKey,
    @Serializable(with = KeyPathKSerializer::class) val fundingKeyPath: KeyPath,
    @Serializable(with = SatoshiKSerializer::class) val dustLimit: Satoshi,
    val maxHtlcValueInFlightMsat: Long, // this is not MilliSatoshi because it can exceed the total amount of MilliSatoshi
    @Serializable(with = SatoshiKSerializer::class) val channelReserve: Satoshi,
    val htlcMinimum: MilliSatoshi,
    val toSelfDelay: CltvExpiryDelta,
    val maxAcceptedHtlcs: Int,
    val isFunder: Boolean,
    @Serializable(with = ByteVectorKSerializer::class) val defaultFinalScriptPubKey: ByteVector,
    @Serializable(with = PublicKeyKSerializer::class) val localPaymentBasepoint: PublicKey?,
    val features: Features
)

@OptIn(ExperimentalUnsignedTypes::class)
@Serializable
data class RemoteParams(
    @Serializable(with = PublicKeyKSerializer::class) val nodeId: PublicKey,
    @Serializable(with = SatoshiKSerializer::class) val dustLimit: Satoshi,
    val maxHtlcValueInFlightMsat: Long, // this is not MilliSatoshi because it can exceed the total amount of MilliSatoshi
    @Serializable(with = SatoshiKSerializer::class) val channelReserve: Satoshi,
    val htlcMinimum: MilliSatoshi,
    val toSelfDelay: CltvExpiryDelta,
    val maxAcceptedHtlcs: Int,
    @Serializable(with = PublicKeyKSerializer::class) val fundingPubKey: PublicKey,
    @Serializable(with = PublicKeyKSerializer::class) val revocationBasepoint: PublicKey,
    @Serializable(with = PublicKeyKSerializer::class) val paymentBasepoint: PublicKey,
    @Serializable(with = PublicKeyKSerializer::class) val delayedPaymentBasepoint: PublicKey,
    @Serializable(with = PublicKeyKSerializer::class) val htlcBasepoint: PublicKey,
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

    // TODO: This is baaad performance! The copy needs to be optimized out.
    fun isSet(bit: Int) = bits.getRight(bit)

    val hasPubkeyKeyPath: Boolean by lazy { isSet(USE_PUBKEY_KEYPATH_BIT) }
    val hasStaticRemotekey: Boolean by lazy { isSet(USE_STATIC_REMOTEKEY_BIT) }
    val hasAnchorOutputs: Boolean by lazy{ isSet(USE_ANCHOR_OUTPUTS_BIT) }
    //  True if our main output in the remote commitment is directly sent (without any delay) to one of our wallet addresses.
    val paysDirectlyToWallet: Boolean by lazy { hasStaticRemotekey && !hasAnchorOutputs }

    companion object {
        const val SIZE_BYTE = 4
        val ZEROES = ChannelVersion(BitField(SIZE_BYTE))
        const val USE_PUBKEY_KEYPATH_BIT = 0 // bit numbers start at 0
        const val USE_STATIC_REMOTEKEY_BIT = 1
        const val USE_ANCHOR_OUTPUTS_BIT = 2
        const val ZERO_RESERVE_BIT = 3

        fun fromBit(bit: Int) = ChannelVersion(BitField(SIZE_BYTE).apply { setRight(bit) })

        val USE_PUBKEY_KEYPATH = fromBit(USE_PUBKEY_KEYPATH_BIT)
        val USE_STATIC_REMOTEKEY = fromBit(USE_STATIC_REMOTEKEY_BIT)
        val ZERO_RESERVE = fromBit(ZERO_RESERVE_BIT)

        val STANDARD = ZEROES or USE_PUBKEY_KEYPATH
        val STATIC_REMOTEKEY = STANDARD or USE_STATIC_REMOTEKEY // USE_PUBKEY_KEYPATH + USE_STATIC_REMOTEKEY
    }
}

object ChannelFlags {
    val AnnounceChannel = 0x01.toByte()
    val Empty = 0x00.toByte()
}

@Serializable
data class ClosingTxProposed(@Serializable(with = TransactionKSerializer::class) val unsignedTx: Transaction, val localClosingSigned: ClosingSigned)
