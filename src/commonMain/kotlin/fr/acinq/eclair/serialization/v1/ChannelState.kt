package fr.acinq.eclair.serialization.v1

import fr.acinq.bitcoin.*
import fr.acinq.eclair.*
import fr.acinq.eclair.blockchain.fee.FeeratePerKw
import fr.acinq.eclair.crypto.ShaChain
import fr.acinq.eclair.serialization.*
import fr.acinq.eclair.transactions.Transactions
import fr.acinq.eclair.utils.BitField
import fr.acinq.eclair.utils.Either
import fr.acinq.eclair.utils.UUID
import fr.acinq.eclair.utils.toByteVector
import fr.acinq.eclair.wire.*
import kotlinx.serialization.Serializable

@Serializable
sealed class DirectedHtlc {
    abstract val add: UpdateAddHtlc

    fun to(): fr.acinq.eclair.transactions.DirectedHtlc = when (this) {
        is IncomingHtlc -> fr.acinq.eclair.transactions.IncomingHtlc(this.add)
        is OutgoingHtlc -> fr.acinq.eclair.transactions.OutgoingHtlc(this.add)
    }

    companion object {
        fun from(input: fr.acinq.eclair.transactions.DirectedHtlc): DirectedHtlc = when (input) {
            is fr.acinq.eclair.transactions.IncomingHtlc -> IncomingHtlc(input.add)
            is fr.acinq.eclair.transactions.OutgoingHtlc -> OutgoingHtlc(input.add)
        }
    }
}

@Serializable
data class IncomingHtlc(override val add: UpdateAddHtlc) : DirectedHtlc()

@Serializable
data class OutgoingHtlc(override val add: UpdateAddHtlc) : DirectedHtlc()

@Serializable
data class CommitmentSpec(
    val htlcs: Set<DirectedHtlc>,
    val feerate: FeeratePerKw,
    val toLocal: MilliSatoshi,
    val toRemote: MilliSatoshi
) {
    constructor(from: fr.acinq.eclair.transactions.CommitmentSpec) : this(from.htlcs.map { DirectedHtlc.from(it) }.toSet(), from.feerate, from.toLocal, from.toRemote)

    fun export() = fr.acinq.eclair.transactions.CommitmentSpec(htlcs.map { it.to() }.toSet(), feerate, toLocal, toRemote)

}

@Serializable
data class LocalChanges(val proposed: List<UpdateMessage>, val signed: List<UpdateMessage>, val acked: List<UpdateMessage>) {
    constructor(from: fr.acinq.eclair.channel.LocalChanges) : this(from.proposed, from.signed, from.acked)

    fun export() = fr.acinq.eclair.channel.LocalChanges(proposed, signed, acked)
}

@Serializable
data class RemoteChanges(val proposed: List<UpdateMessage>, val acked: List<UpdateMessage>, val signed: List<UpdateMessage>) {
    constructor(from: fr.acinq.eclair.channel.RemoteChanges) : this(from.proposed, from.signed, from.acked)

    fun export() = fr.acinq.eclair.channel.RemoteChanges(proposed, signed, acked)
}

@Serializable
data class HtlcTxAndSigs(
    val txinfo: Transactions.TransactionWithInputInfo.HtlcTx,
    @Serializable(with = ByteVector64KSerializer::class) val localSig: ByteVector64,
    @Serializable(with = ByteVector64KSerializer::class) val remoteSig: ByteVector64
) {
    constructor(from: fr.acinq.eclair.channel.HtlcTxAndSigs) : this(from.txinfo, from.localSig, from.remoteSig)

    fun export() = fr.acinq.eclair.channel.HtlcTxAndSigs(txinfo, localSig, remoteSig)
}

@Serializable
data class PublishableTxs(val commitTx: Transactions.TransactionWithInputInfo.CommitTx, val htlcTxsAndSigs: List<HtlcTxAndSigs>) {
    constructor(from: fr.acinq.eclair.channel.PublishableTxs) : this(from.commitTx, from.htlcTxsAndSigs.map { HtlcTxAndSigs(it) })

    fun export() = fr.acinq.eclair.channel.PublishableTxs(commitTx, htlcTxsAndSigs.map { it.export() })
}

@Serializable
data class LocalCommit(val index: Long, val spec: CommitmentSpec, val publishableTxs: PublishableTxs) {
    constructor(from: fr.acinq.eclair.channel.LocalCommit) : this(from.index, CommitmentSpec(from.spec), PublishableTxs(from.publishableTxs))

    fun export() = fr.acinq.eclair.channel.LocalCommit(index, spec.export(), publishableTxs.export())
}

@Serializable
data class RemoteCommit(val index: Long, val spec: CommitmentSpec, @Serializable(with = ByteVector32KSerializer::class) val txid: ByteVector32, @Serializable(with = PublicKeyKSerializer::class) val remotePerCommitmentPoint: PublicKey) {
    constructor(from: fr.acinq.eclair.channel.RemoteCommit) : this(from.index, CommitmentSpec(from.spec), from.txid, from.remotePerCommitmentPoint)

    fun export() = fr.acinq.eclair.channel.RemoteCommit(index, spec.export(), txid, remotePerCommitmentPoint)
}

@Serializable
data class WaitingForRevocation(val nextRemoteCommit: RemoteCommit, val sent: CommitSig, val sentAfterLocalCommitIndex: Long, val reSignAsap: Boolean = false) {
    constructor(from: fr.acinq.eclair.channel.WaitingForRevocation) : this(RemoteCommit(from.nextRemoteCommit), from.sent, from.sentAfterLocalCommitIndex, from.reSignAsap)

    fun export() = fr.acinq.eclair.channel.WaitingForRevocation(nextRemoteCommit.export(), sent, sentAfterLocalCommitIndex, reSignAsap)
}

@Serializable
data class LocalCommitPublished(
    @Serializable(with = TransactionKSerializer::class) val commitTx: Transaction,
    val claimMainDelayedOutputTx: Transactions.TransactionWithInputInfo.ClaimLocalDelayedOutputTx? = null,
    val htlcTxs: Map<@Serializable(with = OutPointKSerializer::class) OutPoint, Transactions.TransactionWithInputInfo.HtlcTx?> = emptyMap(),
    val claimHtlcDelayedTxs: List<Transactions.TransactionWithInputInfo.ClaimLocalDelayedOutputTx> = emptyList(),
    val claimAnchorTxs: List<Transactions.TransactionWithInputInfo.ClaimAnchorOutputTx> = emptyList(),
    val irrevocablySpent: Map<@Serializable(with = OutPointKSerializer::class) OutPoint, @Serializable(with = TransactionKSerializer::class) Transaction> = emptyMap()
) {
    constructor(from: fr.acinq.eclair.channel.LocalCommitPublished) : this(from.commitTx, from.claimMainDelayedOutputTx, from.htlcTxs, from.claimHtlcDelayedTxs, from.claimAnchorTxs, from.irrevocablySpent)

    fun export() = fr.acinq.eclair.channel.LocalCommitPublished(commitTx, claimMainDelayedOutputTx, htlcTxs, claimHtlcDelayedTxs, claimAnchorTxs, irrevocablySpent)
}

@Serializable
data class RemoteCommitPublished(
    @Serializable(with = TransactionKSerializer::class) val commitTx: Transaction,
    val claimMainOutputTx: Transactions.TransactionWithInputInfo.ClaimRemoteCommitMainOutputTx? = null,
    val claimHtlcTxs: Map<@Serializable(with = OutPointKSerializer::class) OutPoint, Transactions.TransactionWithInputInfo.ClaimHtlcTx?> = emptyMap(),
    val claimAnchorTxs: List<Transactions.TransactionWithInputInfo.ClaimAnchorOutputTx> = emptyList(),
    val irrevocablySpent: Map<@Serializable(with = OutPointKSerializer::class) OutPoint, @Serializable(with = TransactionKSerializer::class) Transaction> = emptyMap()
) {
    constructor(from: fr.acinq.eclair.channel.RemoteCommitPublished) : this(from.commitTx, from.claimMainOutputTx, from.claimHtlcTxs, from.claimAnchorTxs, from.irrevocablySpent)

    fun export() = fr.acinq.eclair.channel.RemoteCommitPublished(commitTx, claimMainOutputTx, claimHtlcTxs, claimAnchorTxs, irrevocablySpent)
}

@Serializable
data class RevokedCommitPublished(
    @Serializable(with = TransactionKSerializer::class) val commitTx: Transaction,
    @Serializable(with = PrivateKeyKSerializer::class) val remotePerCommitmentSecret: PrivateKey,
    val claimMainOutputTx: Transactions.TransactionWithInputInfo.ClaimRemoteCommitMainOutputTx? = null,
    val mainPenaltyTx: Transactions.TransactionWithInputInfo.MainPenaltyTx? = null,
    val htlcPenaltyTxs: List<Transactions.TransactionWithInputInfo.HtlcPenaltyTx> = emptyList(),
    val claimHtlcDelayedPenaltyTxs: List<Transactions.TransactionWithInputInfo.ClaimHtlcDelayedOutputPenaltyTx> = emptyList(),
    val irrevocablySpent: Map<@Serializable(with = OutPointKSerializer::class) OutPoint, @Serializable(with = TransactionKSerializer::class) Transaction> = emptyMap()
) {
    constructor(from: fr.acinq.eclair.channel.RevokedCommitPublished) : this(
        from.commitTx,
        from.remotePerCommitmentSecret,
        from.claimMainOutputTx,
        from.mainPenaltyTx,
        from.htlcPenaltyTxs,
        from.claimHtlcDelayedPenaltyTxs,
        from.irrevocablySpent
    )

    fun export() = fr.acinq.eclair.channel.RevokedCommitPublished(commitTx, remotePerCommitmentSecret, claimMainOutputTx, mainPenaltyTx, htlcPenaltyTxs, claimHtlcDelayedPenaltyTxs, irrevocablySpent)
}

@OptIn(ExperimentalUnsignedTypes::class)
@Serializable
data class LocalParams constructor(
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
    constructor(from: fr.acinq.eclair.channel.LocalParams) : this(
        from.nodeId,
        from.fundingKeyPath,
        from.dustLimit,
        from.maxHtlcValueInFlightMsat,
        from.channelReserve,
        from.htlcMinimum,
        from.toSelfDelay,
        from.maxAcceptedHtlcs,
        from.isFunder,
        from.defaultFinalScriptPubKey,
        from.features
    )

    fun export() = fr.acinq.eclair.channel.LocalParams(nodeId, fundingKeyPath, dustLimit, maxHtlcValueInFlightMsat, channelReserve, htlcMinimum, toSelfDelay, maxAcceptedHtlcs, isFunder, defaultFinalScriptPubKey, features)
}

@OptIn(ExperimentalUnsignedTypes::class)
@Serializable
data class RemoteParams(
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
    constructor(from: fr.acinq.eclair.channel.RemoteParams) : this(
        from.nodeId,
        from.dustLimit,
        from.maxHtlcValueInFlightMsat,
        from.channelReserve,
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

    fun export() = fr.acinq.eclair.channel.RemoteParams(
        nodeId,
        dustLimit,
        maxHtlcValueInFlightMsat,
        channelReserve,
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
data class ChannelVersion(@Serializable(with = ByteVectorKSerializer::class) val bits: ByteVector) {
    init {
        require(bits.size() == 4) { "channel version takes 4 bytes" }
    }

    constructor(from: fr.acinq.eclair.channel.ChannelVersion) : this(from.bits.bytes.toByteVector())

    fun export() = fr.acinq.eclair.channel.ChannelVersion(BitField.from(bits.toByteArray()))
}

@Serializable
data class ClosingTxProposed(val unsignedTx: Transactions.TransactionWithInputInfo.ClosingTx, val localClosingSigned: ClosingSigned) {
    constructor(from: fr.acinq.eclair.channel.ClosingTxProposed) : this(from.unsignedTx, from.localClosingSigned)

    fun export() = fr.acinq.eclair.channel.ClosingTxProposed(unsignedTx, localClosingSigned)
}

@Serializable
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
    val payments: Map<Long, UUID>,
    val remoteNextCommitInfo: Either<WaitingForRevocation, @Serializable(with = PublicKeyKSerializer::class) PublicKey>,
    val commitInput: Transactions.InputInfo,
    val remotePerCommitmentSecrets: ShaChain,
    @Serializable(with = ByteVector32KSerializer::class) val channelId: ByteVector32,
    val remoteChannelData: EncryptedChannelData = EncryptedChannelData.empty
) {
    constructor(from: fr.acinq.eclair.channel.Commitments) : this(
        ChannelVersion(from.channelVersion),
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

    fun export() = fr.acinq.eclair.channel.Commitments(
        channelVersion.export(),
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
data class OnChainFeerates(val mutualCloseFeerate: FeeratePerKw, val claimMainFeerate: FeeratePerKw, val fastFeerate: FeeratePerKw) {
    constructor(from: fr.acinq.eclair.blockchain.fee.OnChainFeerates) : this(from.mutualCloseFeerate, from.claimMainFeerate, from.fastFeerate)

    fun export() = fr.acinq.eclair.blockchain.fee.OnChainFeerates(mutualCloseFeerate, claimMainFeerate, fastFeerate)
}

@Serializable
data class StaticParams(@Serializable(with = PublicKeyKSerializer::class) val remoteNodeId: PublicKey) {
    constructor(from: fr.acinq.eclair.channel.StaticParams) : this(from.remoteNodeId)

    fun export(nodeParams: NodeParams) = fr.acinq.eclair.channel.StaticParams(nodeParams, this.remoteNodeId)
}

@Serializable
sealed class ChannelState {
    abstract val staticParams: StaticParams
    abstract val currentTip: Pair<Int, BlockHeader>
    abstract val currentOnChainFeerates: OnChainFeerates

    companion object {
        fun import(from: fr.acinq.eclair.channel.ChannelState): ChannelState = when (from) {
            is fr.acinq.eclair.channel.WaitForInit -> WaitForInit(from)
            is fr.acinq.eclair.channel.Aborted -> Aborted(from)
            is fr.acinq.eclair.channel.WaitForOpenChannel -> WaitForOpenChannel(from)
            is fr.acinq.eclair.channel.WaitForAcceptChannel -> WaitForAcceptChannel(from)
            is fr.acinq.eclair.channel.WaitForFundingInternal -> WaitForFundingInternal(from)
            is fr.acinq.eclair.channel.WaitForFundingLocked -> WaitForFundingLocked(from)
            is fr.acinq.eclair.channel.WaitForFundingConfirmed -> WaitForFundingConfirmed(from)
            is fr.acinq.eclair.channel.WaitForRemotePublishFutureCommitment -> WaitForRemotePublishFutureCommitment(from)
            is fr.acinq.eclair.channel.WaitForFundingCreated -> WaitForFundingCreated(from)
            is fr.acinq.eclair.channel.WaitForFundingSigned -> WaitForFundingSigned(from)
            is fr.acinq.eclair.channel.Offline -> Offline(from)
            is fr.acinq.eclair.channel.Syncing -> Syncing(from)
            is fr.acinq.eclair.channel.ChannelStateWithCommitments -> ChannelStateWithCommitments.import(from)
        }
    }
}

@Serializable
sealed class ChannelStateWithCommitments : ChannelState() {
    abstract val commitments: Commitments
    val channelId: ByteVector32 get() = commitments.channelId
    abstract fun export(nodeParams: NodeParams): fr.acinq.eclair.channel.ChannelStateWithCommitments

    companion object {
        fun import(from: fr.acinq.eclair.channel.ChannelStateWithCommitments): ChannelStateWithCommitments = when (from) {
            is fr.acinq.eclair.channel.WaitForRemotePublishFutureCommitment -> WaitForRemotePublishFutureCommitment(from)
            is fr.acinq.eclair.channel.WaitForFundingConfirmed -> WaitForFundingConfirmed(from)
            is fr.acinq.eclair.channel.WaitForFundingLocked -> WaitForFundingLocked(from)
            is fr.acinq.eclair.channel.Normal -> Normal(from)
            is fr.acinq.eclair.channel.ShuttingDown -> ShuttingDown(from)
            is fr.acinq.eclair.channel.Negotiating -> Negotiating(from)
            is fr.acinq.eclair.channel.Closing -> Closing(from)
            is fr.acinq.eclair.channel.Closed -> Closed(from)
            is fr.acinq.eclair.channel.ErrorInformationLeak -> ErrorInformationLeak(from)
        }
    }
}

@Serializable
data class Aborted(
    override val staticParams: StaticParams,
    override val currentTip: Pair<Int, @Serializable(with = BlockHeaderKSerializer::class) BlockHeader>,
    override val currentOnChainFeerates: OnChainFeerates
) : ChannelState() {
    constructor(from: fr.acinq.eclair.channel.Aborted) : this(StaticParams(from.staticParams), from.currentTip, OnChainFeerates(from.currentOnChainFeerates))
}

@Serializable
data class WaitForInit(
    override val staticParams: StaticParams,
    override val currentTip: Pair<Int, @Serializable(with = BlockHeaderKSerializer::class) BlockHeader>,
    override val currentOnChainFeerates: OnChainFeerates
) : ChannelState() {
    constructor(from: fr.acinq.eclair.channel.WaitForInit) : this(StaticParams(from.staticParams), from.currentTip, OnChainFeerates(from.currentOnChainFeerates))
}

@Serializable
data class Offline(val state: ChannelStateWithCommitments) : ChannelState() {
    override val staticParams: StaticParams get() = state.staticParams
    override val currentTip: Pair<Int, BlockHeader> get() = state.currentTip
    override val currentOnChainFeerates: OnChainFeerates get() = state.currentOnChainFeerates

    constructor(from: fr.acinq.eclair.channel.Offline) : this(ChannelStateWithCommitments.import(from.state))
}

@Serializable
data class Syncing(val state: ChannelStateWithCommitments, val waitForTheirReestablishMessage: Boolean) : ChannelState() {
    override val staticParams: StaticParams get() = state.staticParams
    override val currentTip: Pair<Int, BlockHeader> get() = state.currentTip
    override val currentOnChainFeerates: OnChainFeerates get() = state.currentOnChainFeerates

    constructor(from: fr.acinq.eclair.channel.Syncing) : this(ChannelStateWithCommitments.import(from.state), from.waitForTheirReestablishMessage)
}

@Serializable
data class WaitForOpenChannel(
    override val staticParams: StaticParams,
    override val currentTip: Pair<Int, @Serializable(with = BlockHeaderKSerializer::class) BlockHeader>,
    override val currentOnChainFeerates: OnChainFeerates,
    @Serializable(with = ByteVector32KSerializer::class) val temporaryChannelId: ByteVector32,
    val localParams: LocalParams,
    val remoteInit: Init
) : ChannelState() {
    constructor(from: fr.acinq.eclair.channel.WaitForOpenChannel) : this(
        StaticParams(from.staticParams),
        from.currentTip,
        OnChainFeerates(from.currentOnChainFeerates),
        from.temporaryChannelId,
        LocalParams(from.localParams),
        from.remoteInit
    )
}

@Serializable
data class WaitForRemotePublishFutureCommitment(
    override val staticParams: StaticParams,
    override val currentTip: Pair<Int, @Serializable(with = BlockHeaderKSerializer::class) BlockHeader>,
    override val currentOnChainFeerates: OnChainFeerates,
    override val commitments: Commitments,
    val remoteChannelReestablish: ChannelReestablish
) : ChannelStateWithCommitments() {
    constructor(from: fr.acinq.eclair.channel.WaitForRemotePublishFutureCommitment) : this(
        StaticParams(from.staticParams),
        from.currentTip,
        OnChainFeerates(from.currentOnChainFeerates),
        Commitments(from.commitments),
        from.remoteChannelReestablish
    )

    override fun export(nodeParams: NodeParams) =
        fr.acinq.eclair.channel.WaitForRemotePublishFutureCommitment(staticParams.export(nodeParams), currentTip, currentOnChainFeerates.export(), commitments.export(), remoteChannelReestablish)
}

@Serializable
data class WaitForFundingCreated(
    override val staticParams: StaticParams,
    override val currentTip: Pair<Int, @Serializable(with = BlockHeaderKSerializer::class) BlockHeader>,
    override val currentOnChainFeerates: OnChainFeerates,
    @Serializable(with = ByteVector32KSerializer::class) val temporaryChannelId: ByteVector32,
    val localParams: LocalParams,
    val remoteParams: RemoteParams,
    @Serializable(with = SatoshiKSerializer::class) val fundingAmount: Satoshi,
    val pushAmount: MilliSatoshi,
    val initialFeerate: FeeratePerKw,
    @Serializable(with = PublicKeyKSerializer::class) val remoteFirstPerCommitmentPoint: PublicKey,
    val channelFlags: Byte,
    val channelVersion: ChannelVersion,
    val lastSent: AcceptChannel
) : ChannelState() {
    constructor(from: fr.acinq.eclair.channel.WaitForFundingCreated) : this(
        StaticParams(from.staticParams),
        from.currentTip,
        OnChainFeerates(from.currentOnChainFeerates),
        from.temporaryChannelId,
        LocalParams(from.localParams),
        RemoteParams(from.remoteParams),
        from.fundingAmount,
        from.pushAmount,
        from.initialFeerate,
        from.remoteFirstPerCommitmentPoint,
        from.channelFlags,
        ChannelVersion(from.channelVersion),
        from.lastSent
    )
}

@Serializable
data class InitFunder(
    @Serializable(with = ByteVector32KSerializer::class) val temporaryChannelId: ByteVector32,
    @Serializable(with = SatoshiKSerializer::class) val fundingAmount: Satoshi,
    val pushAmount: MilliSatoshi,
    val initialFeerate: FeeratePerKw,
    val fundingTxFeerate: FeeratePerKw,
    val localParams: LocalParams,
    val remoteInit: Init,
    val channelFlags: Byte,
    val channelVersion: ChannelVersion
) {
    constructor(from: fr.acinq.eclair.channel.ChannelEvent.InitFunder) : this(
        from.temporaryChannelId,
        from.fundingAmount,
        from.pushAmount,
        from.initialFeerate,
        from.fundingTxFeerate,
        LocalParams(from.localParams),
        from.remoteInit,
        from.channelFlags,
        ChannelVersion(from.channelVersion),
    )
}

@Serializable
data class WaitForAcceptChannel(
    override val staticParams: StaticParams,
    override val currentTip: Pair<Int, @Serializable(with = BlockHeaderKSerializer::class) BlockHeader>,
    override val currentOnChainFeerates: OnChainFeerates,
    val initFunder: InitFunder,
    val lastSent: OpenChannel
) : ChannelState() {
    constructor(from: fr.acinq.eclair.channel.WaitForAcceptChannel) : this(
        StaticParams(from.staticParams),
        from.currentTip,
        OnChainFeerates(from.currentOnChainFeerates),
        InitFunder(from.initFunder),
        from.lastSent
    )
}

@Serializable
data class WaitForFundingInternal(
    override val staticParams: StaticParams,
    override val currentTip: Pair<Int, @Serializable(with = BlockHeaderKSerializer::class) BlockHeader>,
    override val currentOnChainFeerates: OnChainFeerates,
    @Serializable(with = ByteVector32KSerializer::class) val temporaryChannelId: ByteVector32,
    val localParams: LocalParams,
    val remoteParams: RemoteParams,
    @Serializable(with = SatoshiKSerializer::class) val fundingAmount: Satoshi,
    val pushAmount: MilliSatoshi,
    val initialFeerate: FeeratePerKw,
    @Serializable(with = PublicKeyKSerializer::class) val remoteFirstPerCommitmentPoint: PublicKey,
    val channelVersion: ChannelVersion,
    val lastSent: OpenChannel
) : ChannelState() {
    constructor(from: fr.acinq.eclair.channel.WaitForFundingInternal) : this(
        StaticParams(from.staticParams),
        from.currentTip,
        OnChainFeerates(from.currentOnChainFeerates),
        from.temporaryChannelId,
        LocalParams(from.localParams),
        RemoteParams(from.remoteParams),
        from.fundingAmount,
        from.pushAmount,
        from.initialFeerate,
        from.remoteFirstPerCommitmentPoint,
        ChannelVersion(from.channelVersion),
        from.lastSent
    )
}

@Serializable
data class WaitForFundingSigned(
    override val staticParams: StaticParams,
    override val currentTip: Pair<Int, @Serializable(with = BlockHeaderKSerializer::class) BlockHeader>,
    override val currentOnChainFeerates: OnChainFeerates,
    @Serializable(with = ByteVector32KSerializer::class) val channelId: ByteVector32,
    val localParams: LocalParams,
    val remoteParams: RemoteParams,
    @Serializable(with = TransactionKSerializer::class) val fundingTx: Transaction,
    @Serializable(with = SatoshiKSerializer::class) val fundingTxFee: Satoshi,
    val localSpec: CommitmentSpec,
    val localCommitTx: Transactions.TransactionWithInputInfo.CommitTx,
    val remoteCommit: RemoteCommit,
    val channelFlags: Byte,
    val channelVersion: ChannelVersion,
    val lastSent: FundingCreated
) : ChannelState() {
    constructor(from: fr.acinq.eclair.channel.WaitForFundingSigned) : this(
        StaticParams(from.staticParams),
        from.currentTip,
        OnChainFeerates(from.currentOnChainFeerates),
        from.channelId,
        LocalParams(from.localParams),
        RemoteParams(from.remoteParams),
        from.fundingTx,
        from.fundingTxFee,
        CommitmentSpec(from.localSpec),
        from.localCommitTx,
        RemoteCommit(from.remoteCommit),
        from.channelFlags,
        ChannelVersion(from.channelVersion),
        from.lastSent
    )
}

@Serializable
data class WaitForFundingConfirmed(
    override val staticParams: StaticParams,
    override val currentTip: Pair<Int, @Serializable(with = BlockHeaderKSerializer::class) BlockHeader>,
    override val currentOnChainFeerates: OnChainFeerates,
    override val commitments: Commitments,
    @Serializable(with = TransactionKSerializer::class) val fundingTx: Transaction?,
    val waitingSinceBlock: Long, // how long have we been waiting for the funding tx to confirm
    val deferred: FundingLocked?,
    val lastSent: Either<FundingCreated, FundingSigned>
) : ChannelStateWithCommitments() {
    constructor(from: fr.acinq.eclair.channel.WaitForFundingConfirmed) : this(
        StaticParams(from.staticParams),
        from.currentTip,
        OnChainFeerates(from.currentOnChainFeerates),
        Commitments(from.commitments),
        from.fundingTx,
        from.waitingSinceBlock,
        from.deferred,
        from.lastSent
    )

    override fun export(nodeParams: NodeParams) = fr.acinq.eclair.channel.WaitForFundingConfirmed(
        staticParams.export(nodeParams),
        currentTip,
        currentOnChainFeerates.export(),
        commitments.export(),
        fundingTx,
        waitingSinceBlock,
        deferred,
        lastSent
    )
}

@Serializable
data class WaitForFundingLocked(
    override val staticParams: StaticParams,
    override val currentTip: Pair<Int, @Serializable(with = BlockHeaderKSerializer::class) BlockHeader>,
    override val currentOnChainFeerates: OnChainFeerates,
    override val commitments: Commitments,
    val shortChannelId: ShortChannelId,
    val lastSent: FundingLocked
) : ChannelStateWithCommitments() {
    constructor(from: fr.acinq.eclair.channel.WaitForFundingLocked) : this(
        StaticParams(from.staticParams),
        from.currentTip,
        OnChainFeerates(from.currentOnChainFeerates),
        Commitments(from.commitments),
        from.shortChannelId,
        from.lastSent
    )

    override fun export(nodeParams: NodeParams) = fr.acinq.eclair.channel.WaitForFundingLocked(
        staticParams.export(nodeParams),
        currentTip,
        currentOnChainFeerates.export(),
        commitments.export(),
        shortChannelId,
        lastSent
    )
}

@Serializable
data class Normal(
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
    val remoteShutdown: Shutdown?
) : ChannelStateWithCommitments() {
    constructor(from: fr.acinq.eclair.channel.Normal) : this(
        StaticParams(from.staticParams),
        from.currentTip,
        OnChainFeerates(from.currentOnChainFeerates),
        Commitments(from.commitments),
        from.shortChannelId,
        from.buried,
        from.channelAnnouncement,
        from.channelUpdate,
        from.remoteChannelUpdate,
        from.localShutdown,
        from.remoteShutdown
    )

    override fun export(nodeParams: NodeParams) = fr.acinq.eclair.channel.Normal(
        staticParams.export(nodeParams),
        currentTip,
        currentOnChainFeerates.export(),
        commitments.export(),
        shortChannelId,
        buried,
        channelAnnouncement,
        channelUpdate,
        remoteChannelUpdate,
        localShutdown,
        remoteShutdown
    )
}

@Serializable
data class ShuttingDown(
    override val staticParams: StaticParams,
    override val currentTip: Pair<Int, @Serializable(with = BlockHeaderKSerializer::class) BlockHeader>,
    override val currentOnChainFeerates: OnChainFeerates,
    override val commitments: Commitments,
    val localShutdown: Shutdown,
    val remoteShutdown: Shutdown
) : ChannelStateWithCommitments() {
    constructor(from: fr.acinq.eclair.channel.ShuttingDown) : this(
        StaticParams(from.staticParams),
        from.currentTip,
        OnChainFeerates(from.currentOnChainFeerates),
        Commitments(from.commitments),
        from.localShutdown,
        from.remoteShutdown
    )

    override fun export(nodeParams: NodeParams) = fr.acinq.eclair.channel.ShuttingDown(
        staticParams.export(nodeParams),
        currentTip,
        currentOnChainFeerates.export(),
        commitments.export(),
        localShutdown,
        remoteShutdown
    )
}

@Serializable
data class Negotiating(
    override val staticParams: StaticParams,
    override val currentTip: Pair<Int, @Serializable(with = BlockHeaderKSerializer::class) BlockHeader>,
    override val currentOnChainFeerates: OnChainFeerates,
    override val commitments: Commitments,
    val localShutdown: Shutdown,
    val remoteShutdown: Shutdown,
    val closingTxProposed: List<List<ClosingTxProposed>>,
    val bestUnpublishedClosingTx: Transactions.TransactionWithInputInfo.ClosingTx?
) : ChannelStateWithCommitments() {
    init {
        require(closingTxProposed.isNotEmpty()) { "there must always be a list for the current negotiation" }
        require(!commitments.localParams.isFunder || !closingTxProposed.any { it.isEmpty() }) { "funder must have at least one closing signature for every negotiation attempt because it initiates the closing" }
    }

    constructor(from: fr.acinq.eclair.channel.Negotiating) : this(
        StaticParams(from.staticParams),
        from.currentTip,
        OnChainFeerates(from.currentOnChainFeerates),
        Commitments(from.commitments),
        from.localShutdown,
        from.remoteShutdown,
        from.closingTxProposed.map { x -> x.map { ClosingTxProposed(it) } },
        from.bestUnpublishedClosingTx
    )

    override fun export(nodeParams: NodeParams) = fr.acinq.eclair.channel.Negotiating(
        staticParams.export(nodeParams),
        currentTip,
        currentOnChainFeerates.export(),
        commitments.export(),
        localShutdown,
        remoteShutdown,
        closingTxProposed.map { x -> x.map { it.export() } },
        bestUnpublishedClosingTx
    )
}

@Serializable
data class Closing(
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
    constructor(from: fr.acinq.eclair.channel.Closing) : this(
        StaticParams(from.staticParams),
        from.currentTip,
        OnChainFeerates(from.currentOnChainFeerates),
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

    override fun export(nodeParams: NodeParams) = fr.acinq.eclair.channel.Closing(
        staticParams.export(nodeParams),
        currentTip,
        currentOnChainFeerates.export(),
        commitments.export(),
        fundingTx,
        waitingSinceBlock,
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
data class Closed(val state: Closing) : ChannelStateWithCommitments() {
    override val commitments: Commitments get() = state.commitments
    override val staticParams: StaticParams get() = state.staticParams
    override val currentTip: Pair<Int, BlockHeader> get() = state.currentTip
    override val currentOnChainFeerates: OnChainFeerates get() = state.currentOnChainFeerates

    constructor(from: fr.acinq.eclair.channel.Closed) : this(Closing(from.state))

    override fun export(nodeParams: NodeParams) = fr.acinq.eclair.channel.Closed(state.export(nodeParams))
}

@Serializable
data class ErrorInformationLeak(
    override val staticParams: StaticParams,
    override val currentTip: Pair<Int, @Serializable(with = BlockHeaderKSerializer::class) BlockHeader>,
    override val currentOnChainFeerates: OnChainFeerates,
    override val commitments: Commitments
) : ChannelStateWithCommitments() {
    constructor(from: fr.acinq.eclair.channel.ErrorInformationLeak) : this(
        StaticParams(from.staticParams),
        from.currentTip,
        OnChainFeerates(from.currentOnChainFeerates),
        Commitments(from.commitments)
    )

    override fun export(nodeParams: NodeParams) = fr.acinq.eclair.channel.ErrorInformationLeak(
        staticParams.export(nodeParams),
        currentTip,
        currentOnChainFeerates.export(),
        commitments.export()
    )
}
