package fr.acinq.lightning.wire

import fr.acinq.bitcoin.*
import fr.acinq.bitcoin.crypto.musig2.IndividualNonce
import fr.acinq.bitcoin.io.Input
import fr.acinq.bitcoin.io.Output
import fr.acinq.lightning.Features
import fr.acinq.lightning.MilliSatoshi
import fr.acinq.lightning.ShortChannelId
import fr.acinq.lightning.blockchain.fee.FeeratePerKw
import fr.acinq.lightning.channel.ChannelSpendSignature
import fr.acinq.lightning.channel.ChannelType
import fr.acinq.lightning.serialization.InputExtensions.readIndividualNonce
import fr.acinq.lightning.utils.msat
import fr.acinq.lightning.utils.sat
import fr.acinq.lightning.utils.toByteVector64

sealed class ChannelTlv : Tlv {
    /** Commitment to where the funds will go in case of a mutual close, which remote node will enforce in case we're compromised. */
    data class UpfrontShutdownScriptTlv(val scriptPubkey: ByteVector) : ChannelTlv() {
        val isEmpty: Boolean get() = scriptPubkey.isEmpty()

        override val tag: Long get() = UpfrontShutdownScriptTlv.tag

        override fun write(out: Output) {
            LightningCodecs.writeBytes(scriptPubkey, out)
        }

        companion object : TlvValueReader<UpfrontShutdownScriptTlv> {
            const val tag: Long = 0

            override fun read(input: Input): UpfrontShutdownScriptTlv {
                val len = input.availableBytes
                val script = LightningCodecs.bytes(input, len)
                return UpfrontShutdownScriptTlv(ByteVector(script))
            }
        }
    }

    data class ChannelTypeTlv(val channelType: ChannelType) : ChannelTlv() {
        override val tag: Long get() = ChannelTypeTlv.tag

        override fun write(out: Output) {
            val features = when (channelType) {
                is ChannelType.SupportedChannelType -> channelType.toFeatures()
                is ChannelType.UnsupportedChannelType -> channelType.featureBits
            }
            LightningCodecs.writeBytes(features.toByteArray(), out)
        }

        companion object : TlvValueReader<ChannelTypeTlv> {
            const val tag: Long = 1

            override fun read(input: Input): ChannelTypeTlv {
                val len = input.availableBytes
                val features = LightningCodecs.bytes(input, len)
                return ChannelTypeTlv(ChannelType.fromFeatures(Features(features)))
            }
        }
    }

    // TLV used to upgrade to "simple taproot channels" format during splices.
    // We cannot reuse the channel_type TLV defined above because the tag is different
    data class SpliceChannelTypeTlv(val channelType: ChannelType) : ChannelTlv() {
        override val tag: Long get() = SpliceChannelTypeTlv.tag

        override fun write(out: Output) {
            val features = when (channelType) {
                is ChannelType.SupportedChannelType -> channelType.toFeatures()
                is ChannelType.UnsupportedChannelType -> channelType.featureBits
            }
            LightningCodecs.writeBytes(features.toByteArray(), out)
        }

        companion object : TlvValueReader<SpliceChannelTypeTlv> {
            const val tag: Long = 0x47000011

            override fun read(input: Input): SpliceChannelTypeTlv {
                val len = input.availableBytes
                val features = LightningCodecs.bytes(input, len)
                return SpliceChannelTypeTlv(ChannelType.fromFeatures(Features(features)))
            }
        }
    }

    object RequireConfirmedInputsTlv : ChannelTlv(), TlvValueReader<RequireConfirmedInputsTlv> {
        override val tag: Long get() = 2

        override fun write(out: Output) = Unit

        override fun read(input: Input): RequireConfirmedInputsTlv = this
    }

    /** Request inbound liquidity from our peer. */
    data class RequestFundingTlv(val request: LiquidityAds.RequestFunding) : ChannelTlv() {
        override val tag: Long get() = RequestFundingTlv.tag

        override fun write(out: Output) = request.write(out)

        companion object : TlvValueReader<RequestFundingTlv> {
            const val tag: Long = 1339
            override fun read(input: Input): RequestFundingTlv = RequestFundingTlv(LiquidityAds.RequestFunding.read(input))
        }
    }

    /** Accept inbound liquidity request. */
    data class ProvideFundingTlv(val willFund: LiquidityAds.WillFund) : ChannelTlv() {
        override val tag: Long get() = ProvideFundingTlv.tag

        override fun write(out: Output) = willFund.write(out)

        companion object : TlvValueReader<ProvideFundingTlv> {
            const val tag: Long = 1339
            override fun read(input: Input): ProvideFundingTlv = ProvideFundingTlv(LiquidityAds.WillFund.read(input))
        }
    }

    /** Fee credit that will be used for the given on-the-fly funding operation. */
    data class FeeCreditUsedTlv(val amount: MilliSatoshi) : ChannelTlv() {
        override val tag: Long get() = FeeCreditUsedTlv.tag

        override fun write(out: Output) = LightningCodecs.writeTU64(amount.toLong(), out)

        companion object : TlvValueReader<FeeCreditUsedTlv> {
            const val tag: Long = 41042
            override fun read(input: Input): FeeCreditUsedTlv = FeeCreditUsedTlv(LightningCodecs.tu64(input).msat)
        }
    }
}

sealed class ChannelReadyTlv : Tlv {
    data class ShortChannelIdTlv(val alias: ShortChannelId) : ChannelReadyTlv() {
        override val tag: Long get() = ShortChannelIdTlv.tag
        override fun write(out: Output) = LightningCodecs.writeU64(alias.toLong(), out)

        companion object : TlvValueReader<ShortChannelIdTlv> {
            const val tag: Long = 1
            override fun read(input: Input): ShortChannelIdTlv = ShortChannelIdTlv(ShortChannelId(LightningCodecs.u64(input)))
        }
    }

    data class NextLocalNonce(val nonce: IndividualNonce) : ChannelReadyTlv() {
        override val tag: Long get() = NextLocalNonce.tag
        override fun write(out: Output) = LightningCodecs.writeBytes(nonce.toByteArray(), out)

        companion object : TlvValueReader<NextLocalNonce> {
            const val tag: Long = 4
            override fun read(input: Input): NextLocalNonce = NextLocalNonce(IndividualNonce(LightningCodecs.bytes(input, 66)))
        }
    }
}

sealed class CommitSigTlv : Tlv {
    data class PartialSignatureWithNonce(val psig: ChannelSpendSignature.PartialSignatureWithNonce) : CommitSigTlv() {
        override val tag: Long get() = PartialSignatureWithNonce.tag

        override fun write(out: Output) {
            LightningCodecs.writeBytes(psig.partialSig, out)
            LightningCodecs.writeBytes(psig.nonce.toByteArray(), out)
        }

        companion object : TlvValueReader<CommitSigTlv> {
            const val tag: Long = 2
            override fun read(input: Input): PartialSignatureWithNonce {
                return PartialSignatureWithNonce(
                    ChannelSpendSignature.PartialSignatureWithNonce(
                        LightningCodecs.bytes(input, 32).byteVector32(),
                        IndividualNonce(LightningCodecs.bytes(input, 66))
                    )
                )
            }
        }
    }

    data class AlternativeFeerateSig(val feerate: FeeratePerKw, val sig: ByteVector64)

    /**
     * When there are no pending HTLCs, we provide a list of signatures for the commitment transaction signed at various feerates.
     * This gives more options to the remote node to recover their funds if the user disappears without closing channels.
     */
    data class AlternativeFeerateSigs(val sigs: List<AlternativeFeerateSig>) : CommitSigTlv() {
        override val tag: Long get() = AlternativeFeerateSigs.tag
        override fun write(out: Output) {
            LightningCodecs.writeByte(sigs.size, out)
            sigs.forEach {
                LightningCodecs.writeU32(it.feerate.toLong().toInt(), out)
                LightningCodecs.writeBytes(it.sig, out)
            }
        }

        companion object : TlvValueReader<AlternativeFeerateSigs> {
            const val tag: Long = 0x47010001
            override fun read(input: Input): AlternativeFeerateSigs {
                val count = LightningCodecs.byte(input)
                val sigs = (0 until count).map {
                    AlternativeFeerateSig(
                        FeeratePerKw(LightningCodecs.u32(input).toLong().sat),
                        LightningCodecs.bytes(input, 64).toByteVector64()
                    )
                }
                return AlternativeFeerateSigs(sigs)
            }
        }
    }

    data class AlternativeFeeratePartialSig(val feerate: FeeratePerKw, val psig: ChannelSpendSignature.PartialSignatureWithNonce)

    /**
     * When there are no pending HTLCs, we provide a list of signatures for the commitment transaction signed at various feerates.
     * This gives more options to the remote node to recover their funds if the user disappears without closing channels.
     */
    data class AlternativeFeeratePartialSigs(val psigs: List<AlternativeFeeratePartialSig>) : CommitSigTlv() {
        override val tag: Long get() = AlternativeFeeratePartialSigs.tag
        override fun write(out: Output) {
            LightningCodecs.writeByte(psigs.size, out)
            psigs.forEach {
                LightningCodecs.writeU32(it.feerate.toLong().toInt(), out)
                LightningCodecs.writeBytes(it.psig.partialSig, out)
                LightningCodecs.writeBytes(it.psig.nonce.toByteArray(), out)
            }
        }

        companion object : TlvValueReader<AlternativeFeeratePartialSigs> {
            const val tag: Long = 0x47010003
            override fun read(input: Input): AlternativeFeeratePartialSigs {
                val count = LightningCodecs.byte(input)
                val sigs = (0 until count).map {
                    AlternativeFeeratePartialSig(
                        FeeratePerKw(LightningCodecs.u32(input).toLong().sat),
                        ChannelSpendSignature.PartialSignatureWithNonce(
                            LightningCodecs.bytes(input, 32).byteVector32(),
                            IndividualNonce(LightningCodecs.bytes(input, 66))
                        )
                    )
                }
                return AlternativeFeeratePartialSigs(sigs)
            }
        }
    }

    data class Batch(val size: Int) : CommitSigTlv() {
        override val tag: Long get() = Batch.tag
        override fun write(out: Output) = LightningCodecs.writeTU16(size, out)

        companion object : TlvValueReader<Batch> {
            const val tag: Long = 0x47010005
            override fun read(input: Input): Batch = Batch(size = LightningCodecs.tu16(input))
        }
    }
}

sealed class RevokeAndAckTlv : Tlv {
    /**
     * Verification nonces used for the next commitment transaction, when using taproot channels.
     * There must be a nonce for each active commitment (when there are pending splices or RBF attempts), indexed by the
     * corresponding fundingTxId.
     */
    data class NextLocalNonces(val nonces: List<Pair<TxId, IndividualNonce>>) : RevokeAndAckTlv() {
        override val tag: Long get() = NextLocalNonces.tag
        override fun write(out: Output) = nonces.forEach {
            LightningCodecs.writeTxHash(TxHash(it.first), out)
            out.write(it.second.toByteArray())
        }

        companion object : TlvValueReader<NextLocalNonces> {
            const val tag: Long = 22
            override fun read(input: Input): NextLocalNonces {
                val count = input.availableBytes / (32 + 66)
                val nonces = (0 until count).map { TxId(LightningCodecs.txHash(input)) to input.readIndividualNonce() }
                return NextLocalNonces(nonces)
            }
        }
    }
}

sealed class ChannelReestablishTlv : Tlv {
    data class NextFunding(val txId: TxId) : ChannelReestablishTlv() {
        override val tag: Long get() = NextFunding.tag
        override fun write(out: Output) = LightningCodecs.writeTxHash(TxHash(txId), out)

        companion object : TlvValueReader<NextFunding> {
            const val tag: Long = 0
            override fun read(input: Input): NextFunding = NextFunding(TxId(LightningCodecs.txHash(input)))
        }
    }

    /**
     * Verification nonces used for the next commitment transaction, when using taproot channels.
     * There must be a nonce for each active commitment (when there are pending splices or RBF attempts), indexed by the
     * corresponding fundingTxId.
     */
    data class NextLocalNonces(val nonces: List<Pair<TxId, IndividualNonce>>) : ChannelReestablishTlv() {
        override val tag: Long get() = NextLocalNonces.tag
        override fun write(out: Output) = nonces.forEach {
            LightningCodecs.writeTxHash(TxHash(it.first), out)
            out.write(it.second.toByteArray())
        }

        companion object : TlvValueReader<NextLocalNonces> {
            const val tag: Long = 22
            override fun read(input: Input): NextLocalNonces {
                val count = input.availableBytes / (32 + 66)
                val nonces = (0 until count).map { TxId(LightningCodecs.txHash(input)) to input.readIndividualNonce() }
                return NextLocalNonces(nonces)
            }
        }
    }

    /**
     * When disconnected during an interactive tx session, we'll include a verification nonce for our *current* commitment
     * which our peer will need to re-send a commit sig for our current commitment transaction spending the interactive tx.
     */
    data class CurrentCommitNonce(val nonce: IndividualNonce) : ChannelReestablishTlv() {
        override val tag: Long get() = CurrentCommitNonce.tag
        override fun write(out: Output) {
            LightningCodecs.writeBytes(nonce.toByteArray(), out)
        }

        companion object : TlvValueReader<CurrentCommitNonce> {
            const val tag: Long = 24
            override fun read(input: Input): CurrentCommitNonce {
                return CurrentCommitNonce(input.readIndividualNonce())
            }
        }
    }
}

sealed class ShutdownTlv : Tlv {
    data class ShutdownNonce(val nonce: IndividualNonce) : ShutdownTlv() {
        override val tag: Long get() = ShutdownNonce.tag

        override fun write(out: Output) {
            LightningCodecs.writeBytes(nonce.toByteArray(), out)
        }

        companion object : TlvValueReader<ShutdownNonce> {
            const val tag: Long = 8
            override fun read(input: Input): ShutdownNonce = ShutdownNonce(IndividualNonce(LightningCodecs.bytes(input, 66)))
        }
    }
}

sealed class ClosingSignedTlv : Tlv {
    data class FeeRange(val min: Satoshi, val max: Satoshi) : ClosingSignedTlv() {
        override val tag: Long get() = FeeRange.tag

        override fun write(out: Output) {
            LightningCodecs.writeU64(min.toLong(), out)
            LightningCodecs.writeU64(max.toLong(), out)
        }

        companion object : TlvValueReader<FeeRange> {
            const val tag: Long = 1
            override fun read(input: Input): FeeRange = FeeRange(Satoshi(LightningCodecs.u64(input)), Satoshi(LightningCodecs.u64(input)))
        }
    }
}

sealed class ClosingCompleteTlv : Tlv {
    /** Signature for a closing transaction containing only the closer's output. */
    data class CloserOutputOnly(val sig: ByteVector64) : ClosingCompleteTlv() {
        override val tag: Long get() = CloserOutputOnly.tag
        override fun write(out: Output) = LightningCodecs.writeBytes(sig.toByteArray(), out)

        companion object : TlvValueReader<CloserOutputOnly> {
            const val tag: Long = 1
            override fun read(input: Input): CloserOutputOnly = CloserOutputOnly(LightningCodecs.bytes(input, 64).toByteVector64())
        }
    }

    /** Signature for a closing transaction containing only the closee's output. */
    data class CloseeOutputOnly(val sig: ByteVector64) : ClosingCompleteTlv() {
        override val tag: Long get() = CloseeOutputOnly.tag
        override fun write(out: Output) = LightningCodecs.writeBytes(sig.toByteArray(), out)

        companion object : TlvValueReader<CloseeOutputOnly> {
            const val tag: Long = 2
            override fun read(input: Input): CloseeOutputOnly = CloseeOutputOnly(LightningCodecs.bytes(input, 64).toByteVector64())
        }
    }

    /** Signature for a closing transaction containing the closer and closee's outputs. */
    data class CloserAndCloseeOutputs(val sig: ByteVector64) : ClosingCompleteTlv() {
        override val tag: Long get() = CloserAndCloseeOutputs.tag
        override fun write(out: Output) = LightningCodecs.writeBytes(sig.toByteArray(), out)

        companion object : TlvValueReader<CloserAndCloseeOutputs> {
            const val tag: Long = 3
            override fun read(input: Input): CloserAndCloseeOutputs = CloserAndCloseeOutputs(LightningCodecs.bytes(input, 64).toByteVector64())
        }
    }

    /** When closing taproot channels, partial signature for a closing transaction containing only the closer's output. */
    data class CloserOutputOnlyPartialSignature(val psig: ChannelSpendSignature.PartialSignatureWithNonce) : ClosingCompleteTlv() {
        override val tag: Long get() = CloserOutputOnlyPartialSignature.tag
        override fun write(out: Output) {
            LightningCodecs.writeBytes(psig.partialSig, out)
            LightningCodecs.writeBytes(psig.nonce.toByteArray(), out)
        }

        companion object : TlvValueReader<CloserOutputOnlyPartialSignature> {
            const val tag: Long = 5
            override fun read(input: Input): CloserOutputOnlyPartialSignature = CloserOutputOnlyPartialSignature(
                ChannelSpendSignature.PartialSignatureWithNonce(
                    LightningCodecs.bytes(input, 32).byteVector32(),
                    IndividualNonce(LightningCodecs.bytes(input, 66))
                )
            )
        }
    }

    /** When closing taproot channels, partial signature for a closing transaction containing only the closee's output. */
    data class CloseeOutputOnlyPartialSignature(val psig: ChannelSpendSignature.PartialSignatureWithNonce) : ClosingCompleteTlv() {
        override val tag: Long get() = CloseeOutputOnlyPartialSignature.tag
        override fun write(out: Output) {
            LightningCodecs.writeBytes(psig.partialSig, out)
            LightningCodecs.writeBytes(psig.nonce.toByteArray(), out)
        }

        companion object : TlvValueReader<CloseeOutputOnlyPartialSignature> {
            const val tag: Long = 6
            override fun read(input: Input): CloseeOutputOnlyPartialSignature = CloseeOutputOnlyPartialSignature(
                ChannelSpendSignature.PartialSignatureWithNonce(
                    LightningCodecs.bytes(input, 32).byteVector32(),
                    IndividualNonce(LightningCodecs.bytes(input, 66))
                )
            )
        }
    }

    /** When closing taproot channels, partial signature for a closing transaction containing the closer and closee's outputs. */
    data class CloserAndCloseeOutputsPartialSignature(val psig: ChannelSpendSignature.PartialSignatureWithNonce) : ClosingCompleteTlv() {
        override val tag: Long get() = CloserAndCloseeOutputsPartialSignature.tag
        override fun write(out: Output) {
            LightningCodecs.writeBytes(psig.partialSig, out)
            LightningCodecs.writeBytes(psig.nonce.toByteArray(), out)
        }

        companion object : TlvValueReader<CloserAndCloseeOutputsPartialSignature> {
            const val tag: Long = 7
            override fun read(input: Input): CloserAndCloseeOutputsPartialSignature = CloserAndCloseeOutputsPartialSignature(
                ChannelSpendSignature.PartialSignatureWithNonce(
                    LightningCodecs.bytes(input, 32).byteVector32(),
                    IndividualNonce(LightningCodecs.bytes(input, 66))
                )
            )
        }
    }
}

sealed class ClosingSigTlv : Tlv {
    /** Signature for a closing transaction containing only the closer's output. */
    data class CloserOutputOnly(val sig: ByteVector64) : ClosingSigTlv() {
        override val tag: Long get() = CloserOutputOnly.tag
        override fun write(out: Output) = LightningCodecs.writeBytes(sig.toByteArray(), out)

        companion object : TlvValueReader<CloserOutputOnly> {
            const val tag: Long = 1
            override fun read(input: Input): CloserOutputOnly = CloserOutputOnly(LightningCodecs.bytes(input, 64).toByteVector64())
        }
    }

    /** Signature for a closing transaction containing only the closee's output. */
    data class CloseeOutputOnly(val sig: ByteVector64) : ClosingSigTlv() {
        override val tag: Long get() = CloseeOutputOnly.tag
        override fun write(out: Output) = LightningCodecs.writeBytes(sig.toByteArray(), out)

        companion object : TlvValueReader<CloseeOutputOnly> {
            const val tag: Long = 2
            override fun read(input: Input): CloseeOutputOnly = CloseeOutputOnly(LightningCodecs.bytes(input, 64).toByteVector64())
        }
    }

    /** Signature for a closing transaction containing the closer and closee's outputs. */
    data class CloserAndCloseeOutputs(val sig: ByteVector64) : ClosingSigTlv() {
        override val tag: Long get() = CloserAndCloseeOutputs.tag
        override fun write(out: Output) = LightningCodecs.writeBytes(sig.toByteArray(), out)

        companion object : TlvValueReader<CloserAndCloseeOutputs> {
            const val tag: Long = 3
            override fun read(input: Input): CloserAndCloseeOutputs = CloserAndCloseeOutputs(LightningCodecs.bytes(input, 64).toByteVector64())
        }
    }

    /** When closing taproot channels, partial signature for a closing transaction containing only the closer's output. */
    data class CloserOutputOnlyPartialSignature(val psig: ByteVector32) : ClosingSigTlv() {
        override val tag: Long get() = CloserOutputOnlyPartialSignature.tag
        override fun write(out: Output) = LightningCodecs.writeBytes(psig, out)

        companion object : TlvValueReader<CloserOutputOnlyPartialSignature> {
            const val tag: Long = 5
            override fun read(input: Input): CloserOutputOnlyPartialSignature = CloserOutputOnlyPartialSignature(
                LightningCodecs.bytes(input, 32).byteVector32()
            )
        }
    }

    /** When closing taproot channels, partial signature for a closing transaction containing only the closee's output. */
    data class CloseeOutputOnlyPartialSignature(val psig: ByteVector32) : ClosingSigTlv() {
        override val tag: Long get() = CloseeOutputOnlyPartialSignature.tag
        override fun write(out: Output) = LightningCodecs.writeBytes(psig, out)

        companion object : TlvValueReader<CloseeOutputOnlyPartialSignature> {
            const val tag: Long = 6
            override fun read(input: Input): CloseeOutputOnlyPartialSignature = CloseeOutputOnlyPartialSignature(
                LightningCodecs.bytes(input, 32).byteVector32()
            )
        }
    }

    /** When closing taproot channels, partial signature for a closing transaction containing the closer and closee's outputs. */
    data class CloserAndCloseeOutputsPartialSignature(val psig: ByteVector32) : ClosingSigTlv() {
        override val tag: Long get() = CloserAndCloseeOutputsPartialSignature.tag
        override fun write(out: Output) = LightningCodecs.writeBytes(psig, out)

        companion object : TlvValueReader<CloserAndCloseeOutputsPartialSignature> {
            const val tag: Long = 7
            override fun read(input: Input): CloserAndCloseeOutputsPartialSignature = CloserAndCloseeOutputsPartialSignature(
                LightningCodecs.bytes(input, 32).byteVector32()
            )
        }
    }

    /** When closing taproot channels, local nonce that will be used to sign the next remote closing transaction. */
    data class NextCloseeNonce(val nonce: IndividualNonce) : ClosingSigTlv() {
        override val tag: Long get() = NextCloseeNonce.tag
        override fun write(out: Output) = LightningCodecs.writeBytes(nonce.toByteArray(), out)

        companion object : TlvValueReader<NextCloseeNonce> {
            const val tag: Long = 22
            override fun read(input: Input): NextCloseeNonce = NextCloseeNonce(IndividualNonce(LightningCodecs.bytes(input, 66)))
        }
    }

}
