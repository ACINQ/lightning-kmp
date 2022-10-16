package fr.acinq.lightning.serialization.v3

import fr.acinq.bitcoin.*
import fr.acinq.lightning.*
import fr.acinq.lightning.blockchain.fee.FeeratePerKw
import fr.acinq.lightning.channel.ChannelContext
import fr.acinq.lightning.crypto.ShaChain
import fr.acinq.lightning.transactions.Transactions
import fr.acinq.lightning.utils.Either
import fr.acinq.lightning.utils.UUID
import fr.acinq.lightning.utils.sat
import fr.acinq.lightning.utils.toByteVector
import fr.acinq.lightning.wire.*
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

@Serializable
internal sealed class DirectedHtlc {
    abstract val add: UpdateAddHtlc

    fun to(): fr.acinq.lightning.transactions.DirectedHtlc = when (this) {
        is IncomingHtlc -> fr.acinq.lightning.transactions.IncomingHtlc(this.add)
        is OutgoingHtlc -> fr.acinq.lightning.transactions.OutgoingHtlc(this.add)
    }

    companion object {
        fun from(input: fr.acinq.lightning.transactions.DirectedHtlc): DirectedHtlc = when (input) {
            is fr.acinq.lightning.transactions.IncomingHtlc -> IncomingHtlc(input.add)
            is fr.acinq.lightning.transactions.OutgoingHtlc -> OutgoingHtlc(input.add)
        }
    }
}

@Serializable
internal data class IncomingHtlc(override val add: UpdateAddHtlc) : DirectedHtlc()

@Serializable
internal data class OutgoingHtlc(override val add: UpdateAddHtlc) : DirectedHtlc()

@Serializable
internal data class CommitmentSpec(
    val htlcs: Set<DirectedHtlc>,
    val feerate: FeeratePerKw,
    val toLocal: MilliSatoshi,
    val toRemote: MilliSatoshi
) {
    constructor(from: fr.acinq.lightning.transactions.CommitmentSpec) : this(from.htlcs.map { DirectedHtlc.from(it) }.toSet(), from.feerate, from.toLocal, from.toRemote)

    fun export() = fr.acinq.lightning.transactions.CommitmentSpec(htlcs.map { it.to() }.toSet(), feerate, toLocal, toRemote)

}

@Serializable
internal data class LocalChanges(val proposed: List<UpdateMessage>, val signed: List<UpdateMessage>, val acked: List<UpdateMessage>) {
    constructor(from: fr.acinq.lightning.channel.LocalChanges) : this(from.proposed, from.signed, from.acked)

    fun export() = fr.acinq.lightning.channel.LocalChanges(proposed, signed, acked)
}

@Serializable
internal data class RemoteChanges(val proposed: List<UpdateMessage>, val acked: List<UpdateMessage>, val signed: List<UpdateMessage>) {
    constructor(from: fr.acinq.lightning.channel.RemoteChanges) : this(from.proposed, from.acked, from.signed)

    fun export() = fr.acinq.lightning.channel.RemoteChanges(proposed, acked, signed)
}

@Serializable
internal data class HtlcTxAndSigs(
    val txinfo: Transactions.TransactionWithInputInfo.HtlcTx,
    @Serializable(with = ByteVector64KSerializer::class) val localSig: ByteVector64,
    @Serializable(with = ByteVector64KSerializer::class) val remoteSig: ByteVector64
) {
    constructor(from: fr.acinq.lightning.channel.HtlcTxAndSigs) : this(from.txinfo, from.localSig, from.remoteSig)

    fun export() = fr.acinq.lightning.channel.HtlcTxAndSigs(txinfo, localSig, remoteSig)
}

@Serializable
internal data class PublishableTxs(val commitTx: Transactions.TransactionWithInputInfo.CommitTx, val htlcTxsAndSigs: List<HtlcTxAndSigs>) {
    constructor(from: fr.acinq.lightning.channel.PublishableTxs) : this(from.commitTx, from.htlcTxsAndSigs.map { HtlcTxAndSigs(it) })

    fun export() = fr.acinq.lightning.channel.PublishableTxs(commitTx, htlcTxsAndSigs.map { it.export() })
}

@Serializable
internal data class LocalCommit(val index: Long, val spec: CommitmentSpec, val publishableTxs: PublishableTxs) {
    constructor(from: fr.acinq.lightning.channel.LocalCommit) : this(from.index, CommitmentSpec(from.spec), PublishableTxs(from.publishableTxs))

    fun export() = fr.acinq.lightning.channel.LocalCommit(index, spec.export(), publishableTxs.export())
}

@Serializable
internal data class RemoteCommit(
    val index: Long,
    val spec: CommitmentSpec,
    @Serializable(with = ByteVector32KSerializer::class) val txid: ByteVector32,
    @Serializable(with = PublicKeyKSerializer::class) val remotePerCommitmentPoint: PublicKey
) {
    constructor(from: fr.acinq.lightning.channel.RemoteCommit) : this(from.index, CommitmentSpec(from.spec), from.txid, from.remotePerCommitmentPoint)

    fun export() = fr.acinq.lightning.channel.RemoteCommit(index, spec.export(), txid, remotePerCommitmentPoint)
}

@Serializable
internal data class WaitingForRevocation(val nextRemoteCommit: RemoteCommit, val sent: CommitSig, val sentAfterLocalCommitIndex: Long, val reSignAsap: Boolean = false) {
    constructor(from: fr.acinq.lightning.channel.WaitingForRevocation) : this(RemoteCommit(from.nextRemoteCommit), from.sent, from.sentAfterLocalCommitIndex, from.reSignAsap)

    fun export() = fr.acinq.lightning.channel.WaitingForRevocation(nextRemoteCommit.export(), sent, sentAfterLocalCommitIndex, reSignAsap)
}

@Serializable
internal data class LocalCommitPublished(
    @Serializable(with = TransactionKSerializer::class) val commitTx: Transaction,
    val claimMainDelayedOutputTx: Transactions.TransactionWithInputInfo.ClaimLocalDelayedOutputTx? = null,
    val htlcTxs: Map<@Serializable(with = OutPointKSerializer::class) OutPoint, Transactions.TransactionWithInputInfo.HtlcTx?> = emptyMap(),
    val claimHtlcDelayedTxs: List<Transactions.TransactionWithInputInfo.ClaimLocalDelayedOutputTx> = emptyList(),
    val claimAnchorTxs: List<Transactions.TransactionWithInputInfo.ClaimAnchorOutputTx> = emptyList(),
    val irrevocablySpent: Map<@Serializable(with = OutPointKSerializer::class) OutPoint, @Serializable(with = TransactionKSerializer::class) Transaction> = emptyMap()
) {
    constructor(from: fr.acinq.lightning.channel.LocalCommitPublished) : this(from.commitTx, from.claimMainDelayedOutputTx, from.htlcTxs, from.claimHtlcDelayedTxs, from.claimAnchorTxs, from.irrevocablySpent)

    fun export() = fr.acinq.lightning.channel.LocalCommitPublished(commitTx, claimMainDelayedOutputTx, htlcTxs, claimHtlcDelayedTxs, claimAnchorTxs, irrevocablySpent)
}

@Serializable
internal data class RemoteCommitPublished(
    @Serializable(with = TransactionKSerializer::class) val commitTx: Transaction,
    val claimMainOutputTx: Transactions.TransactionWithInputInfo.ClaimRemoteCommitMainOutputTx? = null,
    val claimHtlcTxs: Map<@Serializable(with = OutPointKSerializer::class) OutPoint, Transactions.TransactionWithInputInfo.ClaimHtlcTx?> = emptyMap(),
    val claimAnchorTxs: List<Transactions.TransactionWithInputInfo.ClaimAnchorOutputTx> = emptyList(),
    val irrevocablySpent: Map<@Serializable(with = OutPointKSerializer::class) OutPoint, @Serializable(with = TransactionKSerializer::class) Transaction> = emptyMap()
) {
    constructor(from: fr.acinq.lightning.channel.RemoteCommitPublished) : this(from.commitTx, from.claimMainOutputTx, from.claimHtlcTxs, from.claimAnchorTxs, from.irrevocablySpent)

    fun export() = fr.acinq.lightning.channel.RemoteCommitPublished(commitTx, claimMainOutputTx, claimHtlcTxs, claimAnchorTxs, irrevocablySpent)
}

@Serializable
internal data class RevokedCommitPublished(
    @Serializable(with = TransactionKSerializer::class) val commitTx: Transaction,
    @Serializable(with = PrivateKeyKSerializer::class) val remotePerCommitmentSecret: PrivateKey,
    val claimMainOutputTx: Transactions.TransactionWithInputInfo.ClaimRemoteCommitMainOutputTx? = null,
    val mainPenaltyTx: Transactions.TransactionWithInputInfo.MainPenaltyTx? = null,
    val htlcPenaltyTxs: List<Transactions.TransactionWithInputInfo.HtlcPenaltyTx> = emptyList(),
    val claimHtlcDelayedPenaltyTxs: List<Transactions.TransactionWithInputInfo.ClaimHtlcDelayedOutputPenaltyTx> = emptyList(),
    val irrevocablySpent: Map<@Serializable(with = OutPointKSerializer::class) OutPoint, @Serializable(with = TransactionKSerializer::class) Transaction> = emptyMap()
) {
    constructor(from: fr.acinq.lightning.channel.RevokedCommitPublished) : this(
        from.commitTx,
        from.remotePerCommitmentSecret,
        from.claimMainOutputTx,
        from.mainPenaltyTx,
        from.htlcPenaltyTxs,
        from.claimHtlcDelayedPenaltyTxs,
        from.irrevocablySpent
    )

    fun export() = fr.acinq.lightning.channel.RevokedCommitPublished(commitTx, remotePerCommitmentSecret, claimMainOutputTx, mainPenaltyTx, htlcPenaltyTxs, claimHtlcDelayedPenaltyTxs, irrevocablySpent)
}

/**
 * README: by design, we do not include channel private keys and secret here, so they won't be included in our backups (local files, encrypted peer backup, ...), so even
 * if these backups were compromised channel private keys would not be leaked unless the main seed was also compromised.
 * This means that they will be recomputed once when we convert serialized data to their "live" counterparts.
 */
@Serializable
internal data class LocalParams constructor(
    @Serializable(with = PublicKeyKSerializer::class) val nodeId: PublicKey,
    @Serializable(with = KeyPathKSerializer::class) val fundingKeyPath: KeyPath,
    @Serializable(with = SatoshiKSerializer::class) val dustLimit: Satoshi,
    val maxHtlcValueInFlightMsat: Long,
    @Serializable(with = SatoshiKSerializer::class) val channelReserve: Satoshi,
    val htlcMinimum: MilliSatoshi,
    val toSelfDelay: CltvExpiryDelta,
    val maxAcceptedHtlcs: Int,
    val isFunder: Boolean,
    @Serializable(with = ByteVectorKSerializer::class) val defaultFinalScriptPubKey: ByteVector,
    val features: Features
) {
    constructor(from: fr.acinq.lightning.channel.LocalParams) : this(
        from.nodeId,
        from.fundingKeyPath,
        from.dustLimit,
        from.maxHtlcValueInFlightMsat,
        0.sat, // ignored
        from.htlcMinimum,
        from.toSelfDelay,
        from.maxAcceptedHtlcs,
        from.isInitiator,
        from.defaultFinalScriptPubKey,
        from.features
    )

    fun export() = fr.acinq.lightning.channel.LocalParams(
        nodeId,
        fundingKeyPath,
        dustLimit,
        maxHtlcValueInFlightMsat,
        htlcMinimum,
        toSelfDelay,
        maxAcceptedHtlcs,
        isFunder,
        defaultFinalScriptPubKey,
        features
    )
}

@Serializable
internal data class RemoteParams(
    @Serializable(with = PublicKeyKSerializer::class) val nodeId: PublicKey,
    @Serializable(with = SatoshiKSerializer::class) val dustLimit: Satoshi,
    val maxHtlcValueInFlightMsat: Long,
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
) {
    constructor(from: fr.acinq.lightning.channel.RemoteParams) : this(
        from.nodeId,
        from.dustLimit,
        from.maxHtlcValueInFlightMsat,
        0.sat, // ignored
        from.htlcMinimum,
        from.toSelfDelay,
        from.maxAcceptedHtlcs,
        from.fundingPubKey,
        from.revocationBasepoint,
        from.paymentBasepoint,
        from.delayedPaymentBasepoint,
        from.htlcBasepoint,
        from.features
    )

    fun export() = fr.acinq.lightning.channel.RemoteParams(
        nodeId,
        dustLimit,
        maxHtlcValueInFlightMsat,
        htlcMinimum,
        toSelfDelay,
        maxAcceptedHtlcs,
        fundingPubKey,
        revocationBasepoint,
        paymentBasepoint,
        delayedPaymentBasepoint,
        htlcBasepoint,
        features
    )
}

@Serializable
internal data class ChannelConfig(@Serializable(with = ByteVectorKSerializer::class) val bin: ByteVector) {
    constructor(from: fr.acinq.lightning.channel.ChannelConfig) : this(from.toByteArray().toByteVector())

    fun export() = fr.acinq.lightning.channel.ChannelConfig(bin.toByteArray())
}

@Serializable
internal data class ChannelType(@Serializable(with = ByteVectorKSerializer::class) val bin: ByteVector) {
    constructor(from: fr.acinq.lightning.channel.ChannelType.SupportedChannelType) : this(from.toFeatures().toByteArray().toByteVector())
}

@Serializable
internal data class ChannelFeatures(@Serializable(with = ByteVectorKSerializer::class) val bin: ByteVector) {
    constructor(from: fr.acinq.lightning.channel.ChannelFeatures) : this(Features(from.features.associateWith { FeatureSupport.Mandatory }).toByteArray().toByteVector())

    fun export() = fr.acinq.lightning.channel.ChannelFeatures(Features(bin.toByteArray()).activated.keys)
}

@Serializable
internal data class ClosingFeerates(val preferred: FeeratePerKw, val min: FeeratePerKw, val max: FeeratePerKw) {
    constructor(from: fr.acinq.lightning.channel.ClosingFeerates) : this(from.preferred, from.min, from.max)

    fun export() = fr.acinq.lightning.channel.ClosingFeerates(preferred, min, max)
}

@Serializable
internal data class ClosingTxProposed(val unsignedTx: Transactions.TransactionWithInputInfo.ClosingTx, val localClosingSigned: ClosingSigned) {
    constructor(from: fr.acinq.lightning.channel.ClosingTxProposed) : this(from.unsignedTx, from.localClosingSigned)

    fun export() = fr.acinq.lightning.channel.ClosingTxProposed(unsignedTx, localClosingSigned)
}

@Serializable
internal data class Commitments(
    val channelConfig: ChannelConfig,
    val channelFeatures: ChannelFeatures,
    val localParams: LocalParams,
    val remoteParams: RemoteParams,
    val channelFlags: Byte,
    val localCommit: LocalCommit,
    val remoteCommit: RemoteCommit,
    val localChanges: LocalChanges,
    val remoteChanges: RemoteChanges,
    val localNextHtlcId: Long,
    val remoteNextHtlcId: Long,
    val payments: Map<Long, UUID>,
    @Serializable(with = EitherSerializer::class) val remoteNextCommitInfo: Either<WaitingForRevocation, @Serializable(with = PublicKeyKSerializer::class) PublicKey>,
    val commitInput: Transactions.InputInfo,
    @Serializable(with = ShaChainSerializer::class) val remotePerCommitmentSecrets: ShaChain,
    @Serializable(with = ByteVector32KSerializer::class) val channelId: ByteVector32,
    val remoteChannelData: EncryptedChannelData = EncryptedChannelData.empty
) {
    constructor(from: fr.acinq.lightning.channel.Commitments) : this(
        ChannelConfig(from.channelConfig),
        ChannelFeatures(from.channelFeatures),
        LocalParams(from.localParams),
        RemoteParams(from.remoteParams),
        from.channelFlags,
        LocalCommit(from.localCommit),
        RemoteCommit(from.remoteCommit),
        LocalChanges(from.localChanges),
        RemoteChanges(from.remoteChanges),
        from.localNextHtlcId,
        from.remoteNextHtlcId,
        from.payments,
        from.remoteNextCommitInfo.transform({ x -> WaitingForRevocation(x) }, { y -> y }),
        from.commitInput,
        from.remotePerCommitmentSecrets,
        from.channelId,
        from.remoteChannelData
    )

    fun export() = fr.acinq.lightning.channel.Commitments(
        channelConfig.export(),
        channelFeatures.export(),
        localParams.export(),
        remoteParams.export(),
        channelFlags,
        localCommit.export(),
        remoteCommit.export(),
        localChanges.export(),
        remoteChanges.export(),
        localNextHtlcId,
        remoteNextHtlcId,
        payments,
        remoteNextCommitInfo.transform({ x -> x.export() }, { y -> y }),
        commitInput,
        remotePerCommitmentSecrets,
        channelId,
        remoteChannelData
    )
}

@Serializable
internal data class OnChainFeerates(val mutualCloseFeerate: FeeratePerKw, val claimMainFeerate: FeeratePerKw, val fastFeerate: FeeratePerKw) {
    constructor(from: fr.acinq.lightning.blockchain.fee.OnChainFeerates) : this(from.mutualCloseFeerate, from.claimMainFeerate, from.fastFeerate)
}

@Serializable
internal data class StaticParams(@Serializable(with = ByteVector32KSerializer::class) val chainHash: ByteVector32, @Serializable(with = PublicKeyKSerializer::class) val remoteNodeId: PublicKey) {
    constructor(from: fr.acinq.lightning.channel.StaticParams) : this(from.nodeParams.chainHash, from.remoteNodeId)
}

@Serializable
internal sealed class ChannelStateWithCommitments {
    abstract val staticParams: StaticParams
    abstract val currentTip: Pair<Int, BlockHeader>
    abstract val currentOnChainFeerates: OnChainFeerates
    abstract val commitments: Commitments
    val channelId: ByteVector32 get() = commitments.channelId
    abstract fun export(): fr.acinq.lightning.channel.ChannelStateWithCommitments

    companion object {
        fun import(ctx: ChannelContext, from: fr.acinq.lightning.channel.ChannelStateWithCommitments): ChannelStateWithCommitments = when (from) {
            is fr.acinq.lightning.channel.WaitForRemotePublishFutureCommitment -> WaitForRemotePublishFutureCommitment(ctx, from)
            is fr.acinq.lightning.channel.LegacyWaitForFundingConfirmed -> WaitForFundingConfirmed(ctx, from)
            is fr.acinq.lightning.channel.WaitForFundingConfirmed -> WaitForFundingConfirmed2(ctx, from)
            is fr.acinq.lightning.channel.LegacyWaitForFundingLocked -> WaitForFundingLocked(ctx, from)
            is fr.acinq.lightning.channel.WaitForChannelReady -> WaitForChannelReady(ctx, from)
            is fr.acinq.lightning.channel.Normal -> Normal(ctx, from)
            is fr.acinq.lightning.channel.ShuttingDown -> ShuttingDown(ctx, from)
            is fr.acinq.lightning.channel.Negotiating -> Negotiating(ctx, from)
            is fr.acinq.lightning.channel.Closing -> Closing2(ctx, from)
            is fr.acinq.lightning.channel.Closed -> Closed(ctx, from)
            is fr.acinq.lightning.channel.ErrorInformationLeak -> ErrorInformationLeak(ctx, from)
        }
    }
}

@Serializable
internal data class WaitForRemotePublishFutureCommitment(
    override val staticParams: StaticParams,
    override val currentTip: Pair<Int, @Serializable(with = BlockHeaderKSerializer::class) BlockHeader>,
    override val currentOnChainFeerates: OnChainFeerates,
    override val commitments: Commitments,
    val remoteChannelReestablish: ChannelReestablish
) : ChannelStateWithCommitments() {
    constructor(ctx: ChannelContext, from: fr.acinq.lightning.channel.WaitForRemotePublishFutureCommitment) : this(
        StaticParams(ctx.staticParams),
        ctx.currentTip,
        OnChainFeerates(ctx.currentOnChainFeerates),
        Commitments(from.commitments),
        from.remoteChannelReestablish
    )

    override fun export() =
        fr.acinq.lightning.channel.WaitForRemotePublishFutureCommitment(commitments.export(), remoteChannelReestablish)
}

@Serializable
internal data class UnspentItem(@Serializable(with = ByteVector32KSerializer::class) val txid: ByteVector32, val outputIndex: Int, val value: Long, val blockHeight: Long) {
    constructor(from: fr.acinq.lightning.blockchain.electrum.UnspentItem) : this(from.txid, from.outputIndex, from.value, from.blockHeight)
}

@Serializable
internal data class WalletState(
    val addresses: Map<String, List<UnspentItem>>,
    val parentTxs: Map<@Serializable(with = ByteVector32KSerializer::class) ByteVector32, @Serializable(with = TransactionKSerializer::class) Transaction>
) {
    constructor(from: fr.acinq.lightning.blockchain.electrum.WalletState) : this(from.addresses.mapValues { it.value.map { item -> UnspentItem(item) } }, from.parentTxs)
}

@Serializable
internal data class InteractiveTxParams(
    @Serializable(with = ByteVector32KSerializer::class) val channelId: ByteVector32,
    val isInitiator: Boolean,
    @Serializable(with = SatoshiKSerializer::class) val localAmount: Satoshi,
    @Serializable(with = SatoshiKSerializer::class) val remoteAmount: Satoshi,
    @Serializable(with = ByteVectorKSerializer::class) val fundingPubkeyScript: ByteVector,
    val lockTime: Long,
    @Serializable(with = SatoshiKSerializer::class) val dustLimit: Satoshi,
    val targetFeerate: FeeratePerKw
) {
    constructor(from: fr.acinq.lightning.channel.InteractiveTxParams) : this(
        from.channelId,
        from.isInitiator,
        from.localAmount,
        from.remoteAmount,
        from.fundingPubkeyScript,
        from.lockTime,
        from.dustLimit,
        from.targetFeerate
    )

    fun export() = fr.acinq.lightning.channel.InteractiveTxParams(channelId, isInitiator, localAmount, remoteAmount, fundingPubkeyScript, lockTime, dustLimit, targetFeerate)
}

@Serializable
internal data class RemoteTxAddInput(
    val serialId: Long,
    @Serializable(with = OutPointKSerializer::class) val outPoint: OutPoint,
    @Serializable(with = TxOutKSerializer::class) val txOut: TxOut,
    val sequence: Long
) {
    constructor(from: fr.acinq.lightning.channel.RemoteTxAddInput) : this(from.serialId, from.outPoint, from.txOut, from.sequence)

    fun export() = fr.acinq.lightning.channel.RemoteTxAddInput(serialId, outPoint, txOut, sequence)
}

@Serializable
internal data class RemoteTxAddOutput(
    val serialId: Long,
    @Serializable(with = SatoshiKSerializer::class) val amount: Satoshi,
    @Serializable(with = ByteVectorKSerializer::class) val pubkeyScript: ByteVector
) {
    constructor(from: fr.acinq.lightning.channel.RemoteTxAddOutput) : this(from.serialId, from.amount, from.pubkeyScript)

    fun export() = fr.acinq.lightning.channel.RemoteTxAddOutput(serialId, amount, pubkeyScript)
}

@Serializable
internal data class SharedTransaction(val localInputs: List<TxAddInput>, val remoteInputs: List<RemoteTxAddInput>, val localOutputs: List<TxAddOutput>, val remoteOutputs: List<RemoteTxAddOutput>, val lockTime: Long) {
    constructor(from: fr.acinq.lightning.channel.SharedTransaction) : this(from.localInputs, from.remoteInputs.map { RemoteTxAddInput(it) }, from.localOutputs, from.remoteOutputs.map { RemoteTxAddOutput(it) }, from.lockTime)

    fun export() = fr.acinq.lightning.channel.SharedTransaction(localInputs, remoteInputs.map { it.export() }, localOutputs, remoteOutputs.map { it.export() }, lockTime)
}

@Serializable
internal sealed class SignedSharedTransaction {
    abstract fun export(): fr.acinq.lightning.channel.SignedSharedTransaction

    companion object {
        fun import(from: fr.acinq.lightning.channel.SignedSharedTransaction): SignedSharedTransaction = when (from) {
            is fr.acinq.lightning.channel.PartiallySignedSharedTransaction -> PartiallySignedSharedTransaction(from)
            is fr.acinq.lightning.channel.FullySignedSharedTransaction -> FullySignedSharedTransaction(from)
        }
    }
}

@Serializable
internal data class PartiallySignedSharedTransaction(val tx: SharedTransaction, val localSigs: TxSignatures) : SignedSharedTransaction() {
    constructor(from: fr.acinq.lightning.channel.PartiallySignedSharedTransaction) : this(SharedTransaction(from.tx), from.localSigs)

    override fun export() = fr.acinq.lightning.channel.PartiallySignedSharedTransaction(tx.export(), localSigs)
}

@Serializable
internal data class FullySignedSharedTransaction(val tx: SharedTransaction, val localSigs: TxSignatures, val remoteSigs: TxSignatures) : SignedSharedTransaction() {
    constructor(from: fr.acinq.lightning.channel.FullySignedSharedTransaction) : this(SharedTransaction(from.tx), from.localSigs, from.remoteSigs)

    override fun export() = fr.acinq.lightning.channel.FullySignedSharedTransaction(tx.export(), localSigs, remoteSigs)
}

/**
 * This class contains data used for channels opened before the migration to dual-funding.
 * We cannot update it or rename it otherwise we would break serialization backwards-compatibility.
 */
@Serializable
internal data class WaitForFundingConfirmed(
    override val staticParams: StaticParams,
    override val currentTip: Pair<Int, @Serializable(with = BlockHeaderKSerializer::class) BlockHeader>,
    override val currentOnChainFeerates: OnChainFeerates,
    override val commitments: Commitments,
    @Serializable(with = TransactionKSerializer::class) val fundingTx: Transaction?,
    val waitingSinceBlock: Long, // how long have we been waiting for the funding tx to confirm
    val deferred: FundingLocked?,
    @Serializable(with = EitherSerializer::class) val lastSent: Either<FundingCreated, FundingSigned>
) : ChannelStateWithCommitments() {
    constructor(ctx: ChannelContext, from: fr.acinq.lightning.channel.LegacyWaitForFundingConfirmed) : this(
        StaticParams(ctx.staticParams),
        ctx.currentTip,
        OnChainFeerates(ctx.currentOnChainFeerates),
        Commitments(from.commitments),
        from.fundingTx,
        from.waitingSinceBlock,
        from.deferred,
        from.lastSent
    )

    override fun export() = fr.acinq.lightning.channel.LegacyWaitForFundingConfirmed(
        commitments.export(),
        fundingTx,
        waitingSinceBlock,
        deferred,
        lastSent
    )
}

@Serializable
internal data class WaitForFundingConfirmed2(
    override val staticParams: StaticParams,
    override val currentTip: Pair<Int, @Serializable(with = BlockHeaderKSerializer::class) BlockHeader>,
    override val currentOnChainFeerates: OnChainFeerates,
    override val commitments: Commitments,
    val fundingParams: InteractiveTxParams,
    val localPushAmount: MilliSatoshi,
    val remotePushAmount: MilliSatoshi,
    val fundingTx: SignedSharedTransaction,
    val previousFundingTxs: List<Pair<SignedSharedTransaction, Commitments>>,
    val waitingSinceBlock: Long,
    val deferred: ChannelReady?,
) : ChannelStateWithCommitments() {
    constructor(ctx: ChannelContext, from: fr.acinq.lightning.channel.WaitForFundingConfirmed) : this(
        StaticParams(ctx.staticParams),
        ctx.currentTip,
        OnChainFeerates(ctx.currentOnChainFeerates),
        Commitments(from.commitments),
        InteractiveTxParams(from.fundingParams),
        from.localPushAmount,
        from.remotePushAmount,
        SignedSharedTransaction.import(from.fundingTx),
        from.previousFundingTxs.map { Pair(SignedSharedTransaction.import(it.first), Commitments(it.second)) },
        from.waitingSinceBlock,
        from.deferred,
    )

    override fun export() = fr.acinq.lightning.channel.WaitForFundingConfirmed(
        commitments.export(),
        fundingParams.export(),
        localPushAmount,
        remotePushAmount,
        fundingTx.export(),
        previousFundingTxs.map { Pair(it.first.export(), it.second.export()) },
        waitingSinceBlock,
        deferred,
    )
}

/**
 * This class contains data used for channels opened before the migration to dual-funding.
 * We cannot update it or rename it otherwise we would break serialization backwards-compatibility.
 */
@Serializable
internal data class WaitForFundingLocked(
    override val staticParams: StaticParams,
    override val currentTip: Pair<Int, @Serializable(with = BlockHeaderKSerializer::class) BlockHeader>,
    override val currentOnChainFeerates: OnChainFeerates,
    override val commitments: Commitments,
    val shortChannelId: ShortChannelId,
    val lastSent: FundingLocked
) : ChannelStateWithCommitments() {
    constructor(ctx: ChannelContext, from: fr.acinq.lightning.channel.LegacyWaitForFundingLocked) : this(
        StaticParams(ctx.staticParams),
        ctx.currentTip,
        OnChainFeerates(ctx.currentOnChainFeerates),
        Commitments(from.commitments),
        from.shortChannelId,
        from.lastSent
    )

    override fun export() = fr.acinq.lightning.channel.LegacyWaitForFundingLocked(
        commitments.export(),
        shortChannelId,
        lastSent
    )
}

@Serializable
internal data class WaitForChannelReady(
    override val staticParams: StaticParams,
    override val currentTip: Pair<Int, @Serializable(with = BlockHeaderKSerializer::class) BlockHeader>,
    override val currentOnChainFeerates: OnChainFeerates,
    override val commitments: Commitments,
    val fundingParams: InteractiveTxParams,
    val fundingTx: SignedSharedTransaction,
    val shortChannelId: ShortChannelId,
    val lastSent: ChannelReady
) : ChannelStateWithCommitments() {
    constructor(ctx: ChannelContext, from: fr.acinq.lightning.channel.WaitForChannelReady) : this(
        StaticParams(ctx.staticParams),
        ctx.currentTip,
        OnChainFeerates(ctx.currentOnChainFeerates),
        Commitments(from.commitments),
        InteractiveTxParams(from.fundingParams),
        SignedSharedTransaction.import(from.fundingTx),
        from.shortChannelId,
        from.lastSent
    )

    override fun export() = fr.acinq.lightning.channel.WaitForChannelReady(
        commitments.export(),
        fundingParams.export(),
        fundingTx.export(),
        shortChannelId,
        lastSent
    )
}

@Serializable
internal data class Normal(
    override val staticParams: StaticParams,
    override val currentTip: Pair<Int, @Serializable(with = BlockHeaderKSerializer::class) BlockHeader>,
    override val currentOnChainFeerates: OnChainFeerates,
    override val commitments: Commitments,
    val shortChannelId: ShortChannelId,
    val buried: Boolean,
    val channelAnnouncement: ChannelAnnouncement?,
    val channelUpdate: ChannelUpdate,
    val remoteChannelUpdate: ChannelUpdate?,
    val localShutdown: Shutdown?,
    val remoteShutdown: Shutdown?,
    val closingFeerates: ClosingFeerates?
) : ChannelStateWithCommitments() {
    constructor(ctx: ChannelContext, from: fr.acinq.lightning.channel.Normal) : this(
        StaticParams(ctx.staticParams),
        ctx.currentTip,
        OnChainFeerates(ctx.currentOnChainFeerates),
        Commitments(from.commitments),
        from.shortChannelId,
        from.buried,
        from.channelAnnouncement,
        from.channelUpdate,
        from.remoteChannelUpdate,
        from.localShutdown,
        from.remoteShutdown,
        from.closingFeerates?.let { ClosingFeerates(it) }
    )

    override fun export() = fr.acinq.lightning.channel.Normal(
        commitments.export(),
        shortChannelId,
        buried,
        channelAnnouncement,
        channelUpdate,
        remoteChannelUpdate,
        localShutdown,
        remoteShutdown,
        closingFeerates?.export()
    )
}

@Serializable
internal data class ShuttingDown(
    override val staticParams: StaticParams,
    override val currentTip: Pair<Int, @Serializable(with = BlockHeaderKSerializer::class) BlockHeader>,
    override val currentOnChainFeerates: OnChainFeerates,
    override val commitments: Commitments,
    val localShutdown: Shutdown,
    val remoteShutdown: Shutdown,
    val closingFeerates: ClosingFeerates?
) : ChannelStateWithCommitments() {
    constructor(ctx: ChannelContext, from: fr.acinq.lightning.channel.ShuttingDown) : this(
        StaticParams(ctx.staticParams),
        ctx.currentTip,
        OnChainFeerates(ctx.currentOnChainFeerates),
        Commitments(from.commitments),
        from.localShutdown,
        from.remoteShutdown,
        from.closingFeerates?.let { ClosingFeerates(it) }
    )

    override fun export() = fr.acinq.lightning.channel.ShuttingDown(
        commitments.export(),
        localShutdown,
        remoteShutdown,
        closingFeerates?.export()
    )
}

@Serializable
internal data class Negotiating(
    override val staticParams: StaticParams,
    override val currentTip: Pair<Int, @Serializable(with = BlockHeaderKSerializer::class) BlockHeader>,
    override val currentOnChainFeerates: OnChainFeerates,
    override val commitments: Commitments,
    val localShutdown: Shutdown,
    val remoteShutdown: Shutdown,
    val closingTxProposed: List<List<ClosingTxProposed>>,
    val bestUnpublishedClosingTx: Transactions.TransactionWithInputInfo.ClosingTx?,
    val closingFeerates: ClosingFeerates?
) : ChannelStateWithCommitments() {
    init {
        require(closingTxProposed.isNotEmpty()) { "there must always be a list for the current negotiation" }
        require(!commitments.localParams.isFunder || !closingTxProposed.any { it.isEmpty() }) { "initiator must have at least one closing signature for every negotiation attempt because it initiates the closing" }
    }

    constructor(ctx: ChannelContext, from: fr.acinq.lightning.channel.Negotiating) : this(
        StaticParams(ctx.staticParams),
        ctx.currentTip,
        OnChainFeerates(ctx.currentOnChainFeerates),
        Commitments(from.commitments),
        from.localShutdown,
        from.remoteShutdown,
        from.closingTxProposed.map { x -> x.map { ClosingTxProposed(it) } },
        from.bestUnpublishedClosingTx,
        from.closingFeerates?.let { ClosingFeerates(it) }
    )

    override fun export() = fr.acinq.lightning.channel.Negotiating(
        commitments.export(),
        localShutdown,
        remoteShutdown,
        closingTxProposed.map { x -> x.map { it.export() } },
        bestUnpublishedClosingTx,
        closingFeerates?.export()
    )
}

@Serializable
internal data class Closing(
    override val staticParams: StaticParams,
    override val currentTip: Pair<Int, @Serializable(with = BlockHeaderKSerializer::class) BlockHeader>,
    override val currentOnChainFeerates: OnChainFeerates,
    override val commitments: Commitments,
    @Serializable(with = TransactionKSerializer::class) val fundingTx: Transaction?,
    val waitingSinceBlock: Long,
    val mutualCloseProposed: List<Transactions.TransactionWithInputInfo.ClosingTx> = emptyList(),
    val mutualClosePublished: List<Transactions.TransactionWithInputInfo.ClosingTx> = emptyList(),
    val localCommitPublished: LocalCommitPublished? = null,
    val remoteCommitPublished: RemoteCommitPublished? = null,
    val nextRemoteCommitPublished: RemoteCommitPublished? = null,
    val futureRemoteCommitPublished: RemoteCommitPublished? = null,
    val revokedCommitPublished: List<RevokedCommitPublished> = emptyList()
) : ChannelStateWithCommitments() {
    constructor(ctx: ChannelContext, from: fr.acinq.lightning.channel.Closing) : this(
        StaticParams(ctx.staticParams),
        ctx.currentTip,
        OnChainFeerates(ctx.currentOnChainFeerates),
        Commitments(from.commitments),
        from.fundingTx,
        from.waitingSinceBlock,
        from.mutualCloseProposed,
        from.mutualClosePublished,
        from.localCommitPublished?.let { LocalCommitPublished(it) },
        from.remoteCommitPublished?.let { RemoteCommitPublished(it) },
        from.nextRemoteCommitPublished?.let { RemoteCommitPublished(it) },
        from.futureRemoteCommitPublished?.let { RemoteCommitPublished(it) },
        from.revokedCommitPublished.map { RevokedCommitPublished(it) }
    )

    override fun export() = fr.acinq.lightning.channel.Closing(
        commitments.export(),
        fundingTx,
        waitingSinceBlock,
        listOf(),
        mutualCloseProposed,
        mutualClosePublished,
        localCommitPublished?.export(),
        remoteCommitPublished?.export(),
        nextRemoteCommitPublished?.export(),
        futureRemoteCommitPublished?.export(),
        revokedCommitPublished.map { it.export() }
    )
}

@Serializable
internal data class Closing2(
    override val staticParams: StaticParams,
    override val currentTip: Pair<Int, @Serializable(with = BlockHeaderKSerializer::class) BlockHeader>,
    override val currentOnChainFeerates: OnChainFeerates,
    override val commitments: Commitments,
    @Serializable(with = TransactionKSerializer::class) val fundingTx: Transaction?,
    val waitingSinceBlock: Long,
    val alternativeCommitments: List<Commitments> = emptyList(),
    val mutualCloseProposed: List<Transactions.TransactionWithInputInfo.ClosingTx> = emptyList(),
    val mutualClosePublished: List<Transactions.TransactionWithInputInfo.ClosingTx> = emptyList(),
    val localCommitPublished: LocalCommitPublished? = null,
    val remoteCommitPublished: RemoteCommitPublished? = null,
    val nextRemoteCommitPublished: RemoteCommitPublished? = null,
    val futureRemoteCommitPublished: RemoteCommitPublished? = null,
    val revokedCommitPublished: List<RevokedCommitPublished> = emptyList()
) : ChannelStateWithCommitments() {
    constructor(ctx: ChannelContext, from: fr.acinq.lightning.channel.Closing) : this(
        StaticParams(ctx.staticParams),
        ctx.currentTip,
        OnChainFeerates(ctx.currentOnChainFeerates),
        Commitments(from.commitments),
        from.fundingTx,
        from.waitingSinceBlock,
        from.alternativeCommitments.map { Commitments(it) },
        from.mutualCloseProposed,
        from.mutualClosePublished,
        from.localCommitPublished?.let { LocalCommitPublished(it) },
        from.remoteCommitPublished?.let { RemoteCommitPublished(it) },
        from.nextRemoteCommitPublished?.let { RemoteCommitPublished(it) },
        from.futureRemoteCommitPublished?.let { RemoteCommitPublished(it) },
        from.revokedCommitPublished.map { RevokedCommitPublished(it) }
    )

    override fun export() = fr.acinq.lightning.channel.Closing(
        commitments.export(),
        fundingTx,
        waitingSinceBlock,
        alternativeCommitments.map { it.export() },
        mutualCloseProposed,
        mutualClosePublished,
        localCommitPublished?.export(),
        remoteCommitPublished?.export(),
        nextRemoteCommitPublished?.export(),
        futureRemoteCommitPublished?.export(),
        revokedCommitPublished.map { it.export() }
    )
}

@Serializable
internal data class Closed(val state: Closing) : ChannelStateWithCommitments() {
    override val commitments: Commitments get() = state.commitments
    override val staticParams: StaticParams get() = state.staticParams
    override val currentTip: Pair<Int, BlockHeader> get() = state.currentTip
    override val currentOnChainFeerates: OnChainFeerates get() = state.currentOnChainFeerates

    constructor(ctx: ChannelContext, from: fr.acinq.lightning.channel.Closed) : this(Closing(ctx, from.state))

    override fun export() = fr.acinq.lightning.channel.Closed(state.export())
}

@Serializable
internal data class ErrorInformationLeak(
    override val staticParams: StaticParams,
    override val currentTip: Pair<Int, @Serializable(with = BlockHeaderKSerializer::class) BlockHeader>,
    override val currentOnChainFeerates: OnChainFeerates,
    override val commitments: Commitments
) : ChannelStateWithCommitments() {
    constructor(ctx: ChannelContext, from: fr.acinq.lightning.channel.ErrorInformationLeak) : this(
        StaticParams(ctx.staticParams),
        ctx.currentTip,
        OnChainFeerates(ctx.currentOnChainFeerates),
        Commitments(from.commitments)
    )

    override fun export() = fr.acinq.lightning.channel.ErrorInformationLeak(
        commitments.export()
    )
}

internal object ShaChainSerializer : KSerializer<ShaChain> {
    @Serializable
    private data class Surrogate(val knownHashes: List<Pair<String, ByteArray>>, val lastIndex: Long? = null)

    override val descriptor: SerialDescriptor = Surrogate.serializer().descriptor

    override fun serialize(encoder: Encoder, value: ShaChain) {
        val surrogate = Surrogate(
            value.knownHashes.map { Pair(it.key.toBinaryString(), it.value.toByteArray()) },
            value.lastIndex
        )
        return encoder.encodeSerializableValue(Surrogate.serializer(), surrogate)
    }

    override fun deserialize(decoder: Decoder): ShaChain {
        val surrogate = decoder.decodeSerializableValue(Surrogate.serializer())
        return ShaChain(surrogate.knownHashes.associate { it.first.toBooleanList() to ByteVector32(it.second) }, surrogate.lastIndex)
    }

    private fun List<Boolean>.toBinaryString(): String = this.map { if (it) '1' else '0' }.joinToString(separator = "")
    private fun String.toBooleanList(): List<Boolean> = this.map { it == '1' }
}

class EitherSerializer<A : Any, B : Any>(val aSer: KSerializer<A>, val bSer: KSerializer<B>) : KSerializer<Either<A, B>> {
    @Serializable
    internal data class Surrogate<A : Any, B : Any>(val isRight: Boolean, val left: A?, val right: B?)

    override val descriptor = Surrogate.serializer<A, B>(aSer, bSer).descriptor

    override fun serialize(encoder: Encoder, value: Either<A, B>) {
        val surrogate = Surrogate(value.isRight, value.left, value.right)
        return encoder.encodeSerializableValue(Surrogate.serializer<A, B>(aSer, bSer), surrogate)
    }

    override fun deserialize(decoder: Decoder): Either<A, B> {
        val surrogate = decoder.decodeSerializableValue(Surrogate.serializer<A, B>(aSer, bSer))
        return if (surrogate.isRight) Either.Right(surrogate.right!!) else Either.Left(surrogate.left!!)
    }
}