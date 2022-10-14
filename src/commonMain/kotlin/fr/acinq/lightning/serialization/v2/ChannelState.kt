package fr.acinq.lightning.serialization.v2

import fr.acinq.bitcoin.*
import fr.acinq.bitcoin.crypto.Pack
import fr.acinq.bitcoin.io.ByteArrayInput
import fr.acinq.bitcoin.io.ByteArrayOutput
import fr.acinq.bitcoin.io.readNBytes
import fr.acinq.lightning.*
import fr.acinq.lightning.blockchain.fee.FeeratePerKw
import fr.acinq.lightning.crypto.ChaCha20Poly1305
import fr.acinq.lightning.crypto.ShaChain
import fr.acinq.lightning.transactions.Transactions
import fr.acinq.lightning.utils.Either
import fr.acinq.lightning.utils.UUID
import fr.acinq.lightning.utils.sat
import fr.acinq.lightning.utils.toByteVector
import fr.acinq.lightning.wire.*
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.*
import kotlinx.serialization.encoding.*
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.contextual
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass

private object ByteVectorKSerializer : KSerializer<ByteVector> {
    @Serializable
    private data class ByteVectorSurrogate(val value: ByteArray)

    override val descriptor: SerialDescriptor = ByteVectorSurrogate.serializer().descriptor

    override fun serialize(encoder: Encoder, value: ByteVector) {
        val surrogate = ByteVectorSurrogate(value.toByteArray())
        return encoder.encodeSerializableValue(ByteVectorSurrogate.serializer(), surrogate)
    }

    override fun deserialize(decoder: Decoder): ByteVector {
        val surrogate = decoder.decodeSerializableValue(ByteVectorSurrogate.serializer())
        return ByteVector(surrogate.value)
    }
}

private object ByteVector32KSerializer : KSerializer<ByteVector32> {
    @Serializable
    private data class ByteVector32Surrogate(val value: ByteArray) {
        init {
            require(value.size == 32)
        }
    }

    override val descriptor: SerialDescriptor = ByteVector32Surrogate.serializer().descriptor

    override fun serialize(encoder: Encoder, value: ByteVector32) {
        val surrogate = ByteVector32Surrogate(value.toByteArray())
        return encoder.encodeSerializableValue(ByteVector32Surrogate.serializer(), surrogate)
    }

    override fun deserialize(decoder: Decoder): ByteVector32 {
        val surrogate = decoder.decodeSerializableValue(ByteVector32Surrogate.serializer())
        return ByteVector32(surrogate.value)
    }
}

private object ByteVector64KSerializer : KSerializer<ByteVector64> {
    @Serializable
    private data class ByteVector64Surrogate(val value: ByteArray)

    override val descriptor: SerialDescriptor = ByteVector64Surrogate.serializer().descriptor

    override fun serialize(encoder: Encoder, value: ByteVector64) {
        val surrogate = ByteVector64Surrogate(value.toByteArray())
        return encoder.encodeSerializableValue(ByteVector64Surrogate.serializer(), surrogate)
    }

    override fun deserialize(decoder: Decoder): ByteVector64 {
        val surrogate = decoder.decodeSerializableValue(ByteVector64Surrogate.serializer())
        return ByteVector64(surrogate.value)
    }
}

private object PrivateKeyKSerializer : KSerializer<PrivateKey> {

    override fun deserialize(decoder: Decoder): PrivateKey {
        return PrivateKey(ByteVector32KSerializer.deserialize(decoder))
    }

    override val descriptor: SerialDescriptor get() = ByteVector32KSerializer.descriptor

    override fun serialize(encoder: Encoder, value: PrivateKey) {
        ByteVector32KSerializer.serialize(encoder, value.value)
    }
}

private object PublicKeyKSerializer : KSerializer<PublicKey> {

    override fun deserialize(decoder: Decoder): PublicKey {
        return PublicKey(ByteVectorKSerializer.deserialize(decoder))
    }

    override val descriptor: SerialDescriptor get() = ByteVectorKSerializer.descriptor

    override fun serialize(encoder: Encoder, value: PublicKey) {
        ByteVectorKSerializer.serialize(encoder, value.value)
    }
}

private object SatoshiKSerializer : KSerializer<Satoshi> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("Satoshi", PrimitiveKind.LONG)

    override fun serialize(encoder: Encoder, value: Satoshi) {
        encoder.encodeLong(value.toLong())
    }

    override fun deserialize(decoder: Decoder): Satoshi {
        return Satoshi(decoder.decodeLong())
    }
}

private abstract class AbstractBtcSerializableKSerializer<T : BtcSerializable<T>>(val name: String, val btcSerializer: BtcSerializer<T>) : KSerializer<T> {
    @Serializable
    data class Surrogate(val name: String, val bytes: ByteArray)

    override val descriptor: SerialDescriptor = Surrogate.serializer().descriptor

    override fun serialize(encoder: Encoder, value: T) {
        val surrogate = Surrogate(name, btcSerializer.write(value))
        return encoder.encodeSerializableValue(Surrogate.serializer(), surrogate)
    }

    override fun deserialize(decoder: Decoder): T {
        val surrogate = decoder.decodeSerializableValue(Surrogate.serializer())
        return btcSerializer.read(surrogate.bytes)
    }
}

private object BlockHeaderKSerializer : AbstractBtcSerializableKSerializer<BlockHeader>("BlockHeader", BlockHeader)

private object OutPointKSerializer : AbstractBtcSerializableKSerializer<OutPoint>("OutPoint", OutPoint)

private object ScriptWitnessKSerializer : AbstractBtcSerializableKSerializer<ScriptWitness>("ScriptWitness", ScriptWitness)

private object TxInKSerializer : AbstractBtcSerializableKSerializer<TxIn>("TxIn", TxIn)

private object TxOutKSerializer : AbstractBtcSerializableKSerializer<TxOut>("TxOut", TxOut)

private object TransactionKSerializer : AbstractBtcSerializableKSerializer<Transaction>("Transaction", Transaction)

private  object ExtendedPublicKeyKSerializer : KSerializer<DeterministicWallet.ExtendedPublicKey> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("ExtendedPublicKey") {
        element("publickeybytes", ByteVectorKSerializer.descriptor)
        element("chaincode", ByteVector32KSerializer.descriptor)
        element<Int>("depth")
        element("path", KeyPathKSerializer.descriptor)
        element<Long>("parent")
    }

    override fun serialize(encoder: Encoder, value: DeterministicWallet.ExtendedPublicKey) {
        val compositeEncoder = encoder.beginStructure(descriptor)
        compositeEncoder.encodeSerializableElement(descriptor, 0, ByteVectorKSerializer, value.publickeybytes)
        compositeEncoder.encodeSerializableElement(descriptor, 1, ByteVector32KSerializer, value.chaincode)
        compositeEncoder.encodeIntElement(descriptor, 2, value.depth)
        compositeEncoder.encodeSerializableElement(descriptor, 3, KeyPathKSerializer, value.path)
        compositeEncoder.encodeLongElement(descriptor, 4, value.parent)
        compositeEncoder.endStructure(descriptor)
    }

    override fun deserialize(decoder: Decoder): DeterministicWallet.ExtendedPublicKey {
        var publickeybytes: ByteVector? = null
        var chaincode: ByteVector32? = null
        var depth: Int? = null
        var path: KeyPath? = null
        var parent: Long? = null

        val compositeDecoder = decoder.beginStructure(descriptor)
        loop@ while (true) {
            when (compositeDecoder.decodeElementIndex(descriptor)) {
                CompositeDecoder.DECODE_DONE -> break@loop
                0 -> publickeybytes = compositeDecoder.decodeSerializableElement(descriptor, 0, ByteVectorKSerializer)
                1 -> chaincode = compositeDecoder.decodeSerializableElement(descriptor, 1, ByteVector32KSerializer)
                2 -> depth = compositeDecoder.decodeIntElement(descriptor, 2)
                3 -> path = compositeDecoder.decodeSerializableElement(descriptor, 3, KeyPathKSerializer)
                4 -> parent = compositeDecoder.decodeLongElement(descriptor, 4)
            }
        }
        compositeDecoder.endStructure(descriptor)

        return DeterministicWallet.ExtendedPublicKey(publickeybytes!!, chaincode!!, depth!!, path!!, parent!!)
    }

}

private  object KeyPathKSerializer : KSerializer<KeyPath> {
    private val listSerializer = ListSerializer(Long.serializer())

    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("KeyPath") {
        element("path", listSerializer.descriptor)
    }

    override fun serialize(encoder: Encoder, value: KeyPath) {
        val compositeEncoder = encoder.beginStructure(ExtendedPublicKeyKSerializer.descriptor)
        compositeEncoder.encodeSerializableElement(descriptor, 0, listSerializer, value.path)
        compositeEncoder.endStructure(descriptor)
    }

    override fun deserialize(decoder: Decoder): KeyPath {
        val compositeDecoder = decoder.beginStructure(ExtendedPublicKeyKSerializer.descriptor)
        require(compositeDecoder.decodeElementIndex(descriptor) == 0)
        val path = compositeDecoder.decodeSerializableElement(descriptor, 0, listSerializer)
        compositeDecoder.endStructure(descriptor)
        return KeyPath(path)
    }
}

@Serializable
private sealed class DirectedHtlc {
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
private data class IncomingHtlc(override val add: UpdateAddHtlc) : DirectedHtlc()

@Serializable
private data class OutgoingHtlc(override val add: UpdateAddHtlc) : DirectedHtlc()

@Serializable
private data class CommitmentSpec(
    val htlcs: Set<DirectedHtlc>,
    val feerate: FeeratePerKw,
    val toLocal: MilliSatoshi,
    val toRemote: MilliSatoshi
) {
    constructor(from: fr.acinq.lightning.transactions.CommitmentSpec) : this(from.htlcs.map { DirectedHtlc.from(it) }.toSet(), from.feerate, from.toLocal, from.toRemote)

    fun export() = fr.acinq.lightning.transactions.CommitmentSpec(htlcs.map { it.to() }.toSet(), feerate, toLocal, toRemote)

}

@Serializable
private data class LocalChanges(val proposed: List<UpdateMessage>, val signed: List<UpdateMessage>, val acked: List<UpdateMessage>) {
    constructor(from: fr.acinq.lightning.channel.LocalChanges) : this(from.proposed, from.signed, from.acked)

    fun export() = fr.acinq.lightning.channel.LocalChanges(proposed, signed, acked)
}

@Serializable
private data class RemoteChanges(val proposed: List<UpdateMessage>, val acked: List<UpdateMessage>, val signed: List<UpdateMessage>) {
    constructor(from: fr.acinq.lightning.channel.RemoteChanges) : this(from.proposed, from.acked, from.signed)

    fun export() = fr.acinq.lightning.channel.RemoteChanges(proposed, acked, signed)
}

@Serializable
private data class HtlcTxAndSigs(
    val txinfo: Transactions.TransactionWithInputInfo.HtlcTx,
    @Serializable(with = ByteVector64KSerializer::class) val localSig: ByteVector64,
    @Serializable(with = ByteVector64KSerializer::class) val remoteSig: ByteVector64
) {
    constructor(from: fr.acinq.lightning.channel.HtlcTxAndSigs) : this(from.txinfo, from.localSig, from.remoteSig)

    fun export() = fr.acinq.lightning.channel.HtlcTxAndSigs(txinfo, localSig, remoteSig)
}

@Serializable
private data class PublishableTxs(val commitTx: Transactions.TransactionWithInputInfo.CommitTx, val htlcTxsAndSigs: List<HtlcTxAndSigs>) {
    constructor(from: fr.acinq.lightning.channel.PublishableTxs) : this(from.commitTx, from.htlcTxsAndSigs.map { HtlcTxAndSigs(it) })

    fun export() = fr.acinq.lightning.channel.PublishableTxs(commitTx, htlcTxsAndSigs.map { it.export() })
}

@Serializable
private data class LocalCommit(val index: Long, val spec: CommitmentSpec, val publishableTxs: PublishableTxs) {
    constructor(from: fr.acinq.lightning.channel.LocalCommit) : this(from.index, CommitmentSpec(from.spec), PublishableTxs(from.publishableTxs))

    fun export() = fr.acinq.lightning.channel.LocalCommit(index, spec.export(), publishableTxs.export())
}

@Serializable
private data class RemoteCommit(val index: Long, val spec: CommitmentSpec, @Serializable(with = ByteVector32KSerializer::class) val txid: ByteVector32, @Serializable(with = PublicKeyKSerializer::class) val remotePerCommitmentPoint: PublicKey) {
    constructor(from: fr.acinq.lightning.channel.RemoteCommit) : this(from.index, CommitmentSpec(from.spec), from.txid, from.remotePerCommitmentPoint)

    fun export() = fr.acinq.lightning.channel.RemoteCommit(index, spec.export(), txid, remotePerCommitmentPoint)
}

@Serializable
private data class WaitingForRevocation(val nextRemoteCommit: RemoteCommit, val sent: CommitSig, val sentAfterLocalCommitIndex: Long, val reSignAsap: Boolean = false) {
    constructor(from: fr.acinq.lightning.channel.WaitingForRevocation) : this(RemoteCommit(from.nextRemoteCommit), from.sent, from.sentAfterLocalCommitIndex, from.reSignAsap)

    fun export() = fr.acinq.lightning.channel.WaitingForRevocation(nextRemoteCommit.export(), sent, sentAfterLocalCommitIndex, reSignAsap)
}

@Serializable
private data class LocalCommitPublished(
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
private data class RemoteCommitPublished(
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
private data class RevokedCommitPublished(
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
private data class LocalParams constructor(
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
        from.channelKeys.fundingKeyPath,
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

    fun export(nodeParams: NodeParams) = fr.acinq.lightning.channel.LocalParams(
        nodeId,
        nodeParams.keyManager.channelKeys(fundingKeyPath),
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
private data class RemoteParams(
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
private data class ChannelVersion(@Serializable(with = ByteVectorKSerializer::class) val bits: ByteVector) {
    init {
        require(bits.size() == 4) { "channel version takes 4 bytes" }
    }

    companion object {
        // NB: this is the only value that was supported in v1
        val standard = ChannelVersion(ByteVector("0000000f"))

        // This is the corresponding channel config
        val channelConfig = fr.acinq.lightning.channel.ChannelConfig.standard

        // These are the corresponding channel features
        val channelFeatures = fr.acinq.lightning.channel.ChannelFeatures(
            setOf(
                Feature.Wumbo,
                Feature.StaticRemoteKey,
                Feature.AnchorOutputs,
                Feature.ZeroReserveChannels,
                Feature.ZeroConfChannels,
            )
        )
    }
}

@Serializable
private data class ClosingTxProposed(val unsignedTx: Transactions.TransactionWithInputInfo.ClosingTx, val localClosingSigned: ClosingSigned) {
    constructor(from: fr.acinq.lightning.channel.ClosingTxProposed) : this(from.unsignedTx, from.localClosingSigned)

    fun export() = fr.acinq.lightning.channel.ClosingTxProposed(unsignedTx, localClosingSigned)
}

@Serializable
private data class Commitments(
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
    @Serializable(with = EitherSerializer::class) val remoteNextCommitInfo: Either<WaitingForRevocation, @Serializable(with = PublicKeyKSerializer::class) PublicKey>,
    val commitInput: Transactions.InputInfo,
    @Serializable(with = ShaChainSerializer::class) val remotePerCommitmentSecrets: ShaChain,
    @Serializable(with = ByteVector32KSerializer::class) val channelId: ByteVector32,
    val remoteChannelData: EncryptedChannelData = EncryptedChannelData.empty
) {
    constructor(from: fr.acinq.lightning.channel.Commitments) : this(
        ChannelVersion.standard,
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

    fun export(nodeParams: NodeParams) = fr.acinq.lightning.channel.Commitments(
        ChannelVersion.channelConfig,
        ChannelVersion.channelFeatures,
        localParams.export(nodeParams),
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
private data class OnChainFeerates(val mutualCloseFeerate: FeeratePerKw, val claimMainFeerate: FeeratePerKw, val fastFeerate: FeeratePerKw) {
    constructor(from: fr.acinq.lightning.blockchain.fee.OnChainFeerates) : this(from.mutualCloseFeerate, from.claimMainFeerate, from.fastFeerate)

    fun export() = fr.acinq.lightning.blockchain.fee.OnChainFeerates(mutualCloseFeerate, claimMainFeerate, fastFeerate)
}

@Serializable
private data class StaticParams(@Serializable(with = ByteVector32KSerializer::class) val chainHash: ByteVector32, @Serializable(with = PublicKeyKSerializer::class) val remoteNodeId: PublicKey) {
    constructor(from: fr.acinq.lightning.channel.StaticParams) : this(from.nodeParams.chainHash, from.remoteNodeId)

    fun export(nodeParams: NodeParams): fr.acinq.lightning.channel.StaticParams {
        require(chainHash == nodeParams.chainHash) { "restoring data from a different chain" }
        return fr.acinq.lightning.channel.StaticParams(nodeParams, this.remoteNodeId)
    }
}

@Serializable
private sealed class ChannelState {
    abstract val staticParams: StaticParams
    abstract val currentTip: Pair<Int, BlockHeader>
    abstract val currentOnChainFeerates: OnChainFeerates
}

@Serializable
private sealed class ChannelStateWithCommitments : ChannelState() {
    abstract val commitments: Commitments
    val channelId: ByteVector32 get() = commitments.channelId
    abstract fun export(nodeParams: NodeParams): fr.acinq.lightning.channel.ChannelStateWithCommitments

    companion object {
        fun import(from: fr.acinq.lightning.channel.ChannelStateWithCommitments): ChannelStateWithCommitments = when (from) {
            is fr.acinq.lightning.channel.WaitForRemotePublishFutureCommitment -> WaitForRemotePublishFutureCommitment(from)
            is fr.acinq.lightning.channel.LegacyWaitForFundingConfirmed -> WaitForFundingConfirmed(from)
            is fr.acinq.lightning.channel.LegacyWaitForFundingLocked -> WaitForFundingLocked(from)
            is fr.acinq.lightning.channel.Normal -> Normal(from)
            is fr.acinq.lightning.channel.ShuttingDown -> ShuttingDown(from)
            is fr.acinq.lightning.channel.Negotiating -> Negotiating(from)
            is fr.acinq.lightning.channel.Closing -> Closing(from)
            is fr.acinq.lightning.channel.Closed -> Closed(from)
            is fr.acinq.lightning.channel.ErrorInformationLeak -> ErrorInformationLeak(from)
            else -> throw RuntimeException("unexpected state ${from::class}")
        }
    }
}

@Serializable
private data class WaitForRemotePublishFutureCommitment(
    override val staticParams: StaticParams,
    override val currentTip: Pair<Int, @Serializable(with = BlockHeaderKSerializer::class) BlockHeader>,
    override val currentOnChainFeerates: OnChainFeerates,
    override val commitments: Commitments,
    val remoteChannelReestablish: ChannelReestablish
) : ChannelStateWithCommitments() {
    constructor(from: fr.acinq.lightning.channel.WaitForRemotePublishFutureCommitment) : this(
        StaticParams(from.staticParams),
        from.currentTip,
        OnChainFeerates(from.currentOnChainFeerates),
        Commitments(from.commitments),
        from.remoteChannelReestablish
    )

    override fun export(nodeParams: NodeParams) =
        fr.acinq.lightning.channel.WaitForRemotePublishFutureCommitment(staticParams.export(nodeParams), currentTip, currentOnChainFeerates.export(), commitments.export(nodeParams), remoteChannelReestablish)
}

@Serializable
private data class WaitForFundingConfirmed(
    override val staticParams: StaticParams,
    override val currentTip: Pair<Int, @Serializable(with = BlockHeaderKSerializer::class) BlockHeader>,
    override val currentOnChainFeerates: OnChainFeerates,
    override val commitments: Commitments,
    @Serializable(with = TransactionKSerializer::class) val fundingTx: Transaction?,
    val waitingSinceBlock: Long, // how long have we been waiting for the funding tx to confirm
    val deferred: FundingLocked?,
    @Serializable(with = EitherSerializer::class) val lastSent: Either<FundingCreated, FundingSigned>
) : ChannelStateWithCommitments() {
    constructor(from: fr.acinq.lightning.channel.LegacyWaitForFundingConfirmed) : this(
        StaticParams(from.staticParams),
        from.currentTip,
        OnChainFeerates(from.currentOnChainFeerates),
        Commitments(from.commitments),
        from.fundingTx,
        from.waitingSinceBlock,
        from.deferred,
        from.lastSent
    )

    override fun export(nodeParams: NodeParams) = fr.acinq.lightning.channel.LegacyWaitForFundingConfirmed(
        staticParams.export(nodeParams),
        currentTip,
        currentOnChainFeerates.export(),
        commitments.export(nodeParams),
        fundingTx,
        waitingSinceBlock,
        deferred,
        lastSent
    )
}

@Serializable
private data class WaitForFundingLocked(
    override val staticParams: StaticParams,
    override val currentTip: Pair<Int, @Serializable(with = BlockHeaderKSerializer::class) BlockHeader>,
    override val currentOnChainFeerates: OnChainFeerates,
    override val commitments: Commitments,
    val shortChannelId: ShortChannelId,
    val lastSent: FundingLocked
) : ChannelStateWithCommitments() {
    constructor(from: fr.acinq.lightning.channel.LegacyWaitForFundingLocked) : this(
        StaticParams(from.staticParams),
        from.currentTip,
        OnChainFeerates(from.currentOnChainFeerates),
        Commitments(from.commitments),
        from.shortChannelId,
        from.lastSent
    )

    override fun export(nodeParams: NodeParams) = fr.acinq.lightning.channel.LegacyWaitForFundingLocked(
        staticParams.export(nodeParams),
        currentTip,
        currentOnChainFeerates.export(),
        commitments.export(nodeParams),
        shortChannelId,
        lastSent
    )
}

@Serializable
private data class Normal(
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
    constructor(from: fr.acinq.lightning.channel.Normal) : this(
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

    override fun export(nodeParams: NodeParams) = fr.acinq.lightning.channel.Normal(
        staticParams.export(nodeParams),
        currentTip,
        currentOnChainFeerates.export(),
        commitments.export(nodeParams),
        shortChannelId,
        buried,
        channelAnnouncement,
        channelUpdate,
        remoteChannelUpdate,
        localShutdown,
        remoteShutdown,
        null
    )
}

@Serializable
private data class ShuttingDown(
    override val staticParams: StaticParams,
    override val currentTip: Pair<Int, @Serializable(with = BlockHeaderKSerializer::class) BlockHeader>,
    override val currentOnChainFeerates: OnChainFeerates,
    override val commitments: Commitments,
    val localShutdown: Shutdown,
    val remoteShutdown: Shutdown
) : ChannelStateWithCommitments() {
    constructor(from: fr.acinq.lightning.channel.ShuttingDown) : this(
        StaticParams(from.staticParams),
        from.currentTip,
        OnChainFeerates(from.currentOnChainFeerates),
        Commitments(from.commitments),
        from.localShutdown,
        from.remoteShutdown
    )

    override fun export(nodeParams: NodeParams) = fr.acinq.lightning.channel.ShuttingDown(
        staticParams.export(nodeParams),
        currentTip,
        currentOnChainFeerates.export(),
        commitments.export(nodeParams),
        localShutdown,
        remoteShutdown,
        null
    )
}

@Serializable
private data class Negotiating(
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
        require(!commitments.localParams.isFunder || !closingTxProposed.any { it.isEmpty() }) { "initiator must have at least one closing signature for every negotiation attempt because it initiates the closing" }
    }

    constructor(from: fr.acinq.lightning.channel.Negotiating) : this(
        StaticParams(from.staticParams),
        from.currentTip,
        OnChainFeerates(from.currentOnChainFeerates),
        Commitments(from.commitments),
        from.localShutdown,
        from.remoteShutdown,
        from.closingTxProposed.map { x -> x.map { ClosingTxProposed(it) } },
        from.bestUnpublishedClosingTx
    )

    override fun export(nodeParams: NodeParams) = fr.acinq.lightning.channel.Negotiating(
        staticParams.export(nodeParams),
        currentTip,
        currentOnChainFeerates.export(),
        commitments.export(nodeParams),
        localShutdown,
        remoteShutdown,
        closingTxProposed.map { x -> x.map { it.export() } },
        bestUnpublishedClosingTx,
        null
    )
}

@Serializable
private data class Closing(
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
    constructor(from: fr.acinq.lightning.channel.Closing) : this(
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

    override fun export(nodeParams: NodeParams) = fr.acinq.lightning.channel.Closing(
        staticParams.export(nodeParams),
        currentTip,
        currentOnChainFeerates.export(),
        commitments.export(nodeParams),
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
private data class Closed(val state: Closing) : ChannelStateWithCommitments() {
    override val commitments: Commitments get() = state.commitments
    override val staticParams: StaticParams get() = state.staticParams
    override val currentTip: Pair<Int, BlockHeader> get() = state.currentTip
    override val currentOnChainFeerates: OnChainFeerates get() = state.currentOnChainFeerates

    constructor(from: fr.acinq.lightning.channel.Closed) : this(Closing(from.state))

    override fun export(nodeParams: NodeParams) = fr.acinq.lightning.channel.Closed(state.export(nodeParams))
}

@Serializable
private data class ErrorInformationLeak(
    override val staticParams: StaticParams,
    override val currentTip: Pair<Int, @Serializable(with = BlockHeaderKSerializer::class) BlockHeader>,
    override val currentOnChainFeerates: OnChainFeerates,
    override val commitments: Commitments
) : ChannelStateWithCommitments() {
    constructor(from: fr.acinq.lightning.channel.ErrorInformationLeak) : this(
        StaticParams(from.staticParams),
        from.currentTip,
        OnChainFeerates(from.currentOnChainFeerates),
        Commitments(from.commitments)
    )

    override fun export(nodeParams: NodeParams) = fr.acinq.lightning.channel.ErrorInformationLeak(
        staticParams.export(nodeParams),
        currentTip,
        currentOnChainFeerates.export(),
        commitments.export(nodeParams)
    )
}

private object ShaChainSerializer : KSerializer<ShaChain> {
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

private class EitherSerializer<A : Any, B : Any>(val aSer: KSerializer<A>, val bSer: KSerializer<B>) : KSerializer<Either<A, B>> {
    @Serializable
    private data class Surrogate<A : Any, B : Any>(val isRight: Boolean, val left: A?, val right: B?)

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

object Serialization {
    private val versionMagic = 2

    /**
     * Versioned serialized data.
     *
     * @README DO NOT change the structure of this class !!
     *
     * If a new serialization format is added, just change the `version` field and update serialize()/deserialize() methods
     * @param version version of the serialization algorithm
     * @param data serialized data
     */
    @Serializable
    private data class SerializedData(val version: Int, @Serializable(with = ByteVectorKSerializer::class) val data: ByteVector)

    private val updateSerializersModule = SerializersModule {
        polymorphic(UpdateMessage::class) {
            subclass(UpdateAddHtlc.serializer())
            subclass(UpdateFailHtlc.serializer())
            subclass(UpdateFailMalformedHtlc.serializer())
            subclass(UpdateFee.serializer())
            subclass(UpdateFulfillHtlc.serializer())
        }
    }

    private val tlvSerializersModule = SerializersModule {
        polymorphic(Tlv::class) {
            subclass(ChannelTlv.UpfrontShutdownScriptTlv.serializer())
            subclass(ChannelTlv.ChannelOriginTlv.serializer())
            subclass(InitTlv.Networks.serializer())
            subclass(InitTlv.PhoenixAndroidLegacyNodeId.serializer())
            subclass(OnionPaymentPayloadTlv.AmountToForward.serializer())
            subclass(OnionPaymentPayloadTlv.OutgoingCltv.serializer())
            subclass(OnionPaymentPayloadTlv.OutgoingChannelId.serializer())
            subclass(OnionPaymentPayloadTlv.PaymentData.serializer())
            subclass(OnionPaymentPayloadTlv.PaymentMetadata.serializer())
            subclass(OnionPaymentPayloadTlv.InvoiceFeatures.serializer())
            subclass(OnionPaymentPayloadTlv.OutgoingNodeId.serializer())
            subclass(OnionPaymentPayloadTlv.InvoiceRoutingInfo.serializer())
            subclass(OnionPaymentPayloadTlv.TrampolineOnion.serializer())
            subclass(GenericTlv.serializer())
        }
    }

    private val serializersModule = SerializersModule {
        polymorphic(ChannelStateWithCommitments::class) {
            subclass(Normal::class)
            subclass(WaitForFundingConfirmed::class)
            subclass(WaitForFundingLocked::class)
            subclass(WaitForRemotePublishFutureCommitment::class)
            subclass(ShuttingDown::class)
            subclass(Negotiating::class)
            subclass(Closing::class)
            subclass(Closed::class)
            subclass(ErrorInformationLeak::class)
        }
    }

    private val serializationModules = SerializersModule {
        include(tlvSerializersModule)
        include(updateSerializersModule)
        include(SerializersModule {
            contextual(ByteVector64KSerializer)
            contextual(ByteVector32KSerializer)
            contextual(ByteVectorKSerializer)
            contextual(SatoshiKSerializer)
            contextual(PrivateKeyKSerializer)
            contextual(PublicKeyKSerializer)
            contextual(OutPointKSerializer)
            contextual(TxInKSerializer)
            contextual(TxOutKSerializer)
            contextual(TransactionKSerializer)
            contextual(BlockHeaderKSerializer)
        })
    }

    private fun serialize(state: ChannelStateWithCommitments): ByteArray {
        val output = ByteArrayOutput()
        val encoder = DataOutputEncoder(output)
        encoder.encodeSerializableValue(ChannelStateWithCommitments.serializer(), state)
        val bytes = output.toByteArray()
        val versioned = SerializedData(version = versionMagic, data = bytes.toByteVector())
        val output1 = ByteArrayOutput()
        val encoder1 = DataOutputEncoder(output1)
        encoder1.encodeSerializableValue(SerializedData.serializer(), versioned)
        return output1.toByteArray()
    }

    fun serialize(state: fr.acinq.lightning.channel.ChannelStateWithCommitments): ByteArray {
        return serialize(ChannelStateWithCommitments.import(state))
    }

    @OptIn(ExperimentalSerializationApi::class)
    fun deserialize(bin: ByteArray, nodeParams: NodeParams): fr.acinq.lightning.channel.ChannelStateWithCommitments {
        val input = ByteArrayInput(bin)
        val decoder = DataInputDecoder(input)
        val versioned = decoder.decodeSerializableValue(SerializedData.serializer())
        return when (versioned.version) {
            versionMagic -> {
                val input1 = ByteArrayInput(versioned.data.toByteArray())
                val decoder1 = DataInputDecoder(input1)
                decoder1.decodeSerializableValue(ChannelStateWithCommitments.serializer()).export(nodeParams)
            }
            else -> error("unknown serialization version ${versioned.version}")
        }
    }

    private fun deserialize(bin: ByteVector, nodeParams: NodeParams): fr.acinq.lightning.channel.ChannelStateWithCommitments = deserialize(bin.toByteArray(), nodeParams)

    private fun encrypt(key: ByteVector32, state: ChannelStateWithCommitments): EncryptedChannelData {
        val bin = serialize(state)
        // NB: there is a chance of collision here, due to how the nonce is calculated. Probability of collision is once every 2.2E19 times.
        // See https://en.wikipedia.org/wiki/Birthday_attack
        val nonce = Crypto.sha256(bin).take(12).toByteArray()
        val (ciphertext, tag) = ChaCha20Poly1305.encrypt(key.toByteArray(), nonce, bin, ByteArray(0))
        return EncryptedChannelData((ciphertext + nonce + tag).toByteVector())
    }

    fun encrypt(key: ByteVector32, state: fr.acinq.lightning.channel.ChannelStateWithCommitments): EncryptedChannelData {
        val bin = serialize(state)
        // NB: there is a chance of collision here, due to how the nonce is calculated. Probability of collision is once every 2.2E19 times.
        // See https://en.wikipedia.org/wiki/Birthday_attack
        val nonce = Crypto.sha256(bin).take(12).toByteArray()
        val (ciphertext, tag) = ChaCha20Poly1305.encrypt(key.toByteArray(), nonce, bin, ByteArray(0))
        return EncryptedChannelData((ciphertext + nonce + tag).toByteVector())
    }

    fun encrypt(key: PrivateKey, state: fr.acinq.lightning.channel.ChannelStateWithCommitments): EncryptedChannelData = encrypt(key.value, state)

    fun decrypt(key: ByteVector32, data: ByteArray, nodeParams: NodeParams): fr.acinq.lightning.channel.ChannelStateWithCommitments {
        // nonce is 12B, tag is 16B
        val ciphertext = data.dropLast(12 + 16)
        val nonce = data.takeLast(12 + 16).take(12)
        val tag = data.takeLast(16)
        val plaintext = ChaCha20Poly1305.decrypt(key.toByteArray(), nonce.toByteArray(), ciphertext.toByteArray(), ByteArray(0), tag.toByteArray())
        return deserialize(plaintext, nodeParams)
    }

    fun decrypt(key: PrivateKey, data: ByteArray, nodeParams: NodeParams): fr.acinq.lightning.channel.ChannelStateWithCommitments = decrypt(key.value, data, nodeParams)
    fun decrypt(key: PrivateKey, backup: EncryptedChannelData, nodeParams: NodeParams): fr.acinq.lightning.channel.ChannelStateWithCommitments = decrypt(key, backup.data.toByteArray(), nodeParams)

    @OptIn(ExperimentalSerializationApi::class)
    private class DataOutputEncoder(val output: ByteArrayOutput) : AbstractEncoder() {
        override val serializersModule: SerializersModule = serializationModules
        override fun encodeBoolean(value: Boolean) = output.write(if (value) 1 else 0)
        override fun encodeByte(value: Byte) = output.write(value.toInt())
        override fun encodeShort(value: Short) = output.write(Pack.writeInt16BE(value))
        override fun encodeInt(value: Int) = output.write(Pack.writeInt32BE(value))
        override fun encodeLong(value: Long) = output.write(Pack.writeInt64BE(value))
        override fun encodeFloat(value: Float) {
            TODO()
        }

        override fun encodeDouble(value: Double) {
            TODO()
        }

        override fun encodeChar(value: Char) = output.write(value.code)
        override fun encodeString(value: String) {
            val bytes = value.encodeToByteArray()
            encodeInt(bytes.size)
            output.write(bytes)
        }

        override fun encodeEnum(enumDescriptor: SerialDescriptor, index: Int) = output.write(index)
        override fun beginCollection(descriptor: SerialDescriptor, collectionSize: Int): CompositeEncoder {
            encodeInt(collectionSize)
            return this
        }

        override fun encodeNull() = encodeBoolean(false)
        override fun encodeNotNullMark() = encodeBoolean(true)
    }

    @OptIn(ExperimentalSerializationApi::class)
    @ExperimentalSerializationApi
    private class DataInputDecoder(val input: ByteArrayInput, var elementsCount: Int = 0) : AbstractDecoder() {
        private var elementIndex = 0
        override val serializersModule: SerializersModule = serializationModules
        override fun decodeBoolean(): Boolean = input.read() != 0
        override fun decodeByte(): Byte = input.read().toByte()
        override fun decodeShort(): Short = Pack.int16BE(input.readNBytes(2)!!)
        override fun decodeInt(): Int = Pack.int32BE(input.readNBytes(4)!!)
        override fun decodeLong(): Long = Pack.int64BE(input.readNBytes(8)!!)
        override fun decodeFloat(): Float = TODO()
        override fun decodeDouble(): Double = TODO()
        override fun decodeChar(): Char = input.read().toChar()
        override fun decodeString(): String {
            val len = decodeInt()
            require(len <= input.availableBytes)
            return input.readNBytes(len)!!.decodeToString()
        }

        override fun decodeEnum(enumDescriptor: SerialDescriptor): Int = input.read()
        override fun decodeElementIndex(descriptor: SerialDescriptor): Int {
            if (elementIndex == elementsCount) return CompositeDecoder.DECODE_DONE
            return elementIndex++
        }

        override fun beginStructure(descriptor: SerialDescriptor): CompositeDecoder = DataInputDecoder(input, descriptor.elementsCount)
        override fun decodeSequentially(): Boolean = true
        override fun decodeCollectionSize(descriptor: SerialDescriptor): Int = decodeInt().also {
            require(it <= input.availableBytes)
            elementsCount = it
        }

        override fun decodeNotNullMark(): Boolean = decodeBoolean()
    }
}