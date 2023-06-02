package fr.acinq.lightning.wire

import fr.acinq.bitcoin.*
import fr.acinq.bitcoin.io.Input
import fr.acinq.bitcoin.io.Output
import fr.acinq.lightning.Features
import fr.acinq.lightning.MilliSatoshi
import fr.acinq.lightning.ShortChannelId
import fr.acinq.lightning.channel.ChannelType
import fr.acinq.lightning.channel.Origin
import fr.acinq.lightning.utils.*

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

    object RequireConfirmedInputsTlv : ChannelTlv(), TlvValueReader<RequireConfirmedInputsTlv> {
        override val tag: Long get() = 2

        override fun write(out: Output) = Unit

        override fun read(input: Input): RequireConfirmedInputsTlv = this
    }

    data class OriginTlv(val origin: Origin) : ChannelTlv() {
        override val tag: Long get() = OriginTlv.tag

        override fun write(out: Output) {
            when (origin) {
                is Origin.PayToOpenOrigin -> {
                    LightningCodecs.writeU16(1, out)
                    LightningCodecs.writeBytes(origin.paymentHash, out)
                    LightningCodecs.writeU64(origin.miningFee.toLong(), out)
                    LightningCodecs.writeU64(origin.serviceFee.toLong(), out)
                    LightningCodecs.writeU64(origin.amount.toLong(), out)
                }

                is Origin.PleaseOpenChannelOrigin -> {
                    LightningCodecs.writeU16(4, out)
                    LightningCodecs.writeBytes(origin.requestId, out)
                    LightningCodecs.writeU64(origin.miningFee.toLong(), out)
                    LightningCodecs.writeU64(origin.serviceFee.toLong(), out)
                    LightningCodecs.writeU64(origin.amount.toLong(), out)
                }
            }
        }

        companion object : TlvValueReader<OriginTlv> {
            const val tag: Long = 0x47000005

            override fun read(input: Input): OriginTlv {
                val origin = when (LightningCodecs.u16(input)) {
                    1 -> Origin.PayToOpenOrigin(
                        paymentHash = LightningCodecs.bytes(input, 32).byteVector32(),
                        miningFee = LightningCodecs.u64(input).sat,
                        serviceFee = LightningCodecs.u64(input).msat,
                        amount = LightningCodecs.u64(input).msat
                    )

                    4 -> Origin.PleaseOpenChannelOrigin(
                        requestId = LightningCodecs.bytes(input, 32).byteVector32(),
                        miningFee = LightningCodecs.u64(input).sat,
                        serviceFee = LightningCodecs.u64(input).msat,
                        amount = LightningCodecs.u64(input).msat
                    )

                    else -> error("Unsupported channel origin discriminator")
                }
                return OriginTlv(origin)
            }
        }
    }

    /** With rbfed splices we can have multiple origins*/
    data class OriginsTlv(val origins: List<Origin>) : ChannelTlv() {
        override val tag: Long get() = OriginsTlv.tag

        override fun write(out: Output) {
            LightningCodecs.writeU16(origins.size, out)
            origins.forEach { OriginTlv(it).write(out) }
        }

        companion object : TlvValueReader<OriginsTlv> {
            const val tag: Long = 0x47000009

            override fun read(input: Input): OriginsTlv {
                val size = LightningCodecs.u16(input)
                val origins = buildList {
                    for (i in 0 until size) {
                        add(OriginTlv.read(input).origin)
                    }
                }
                return OriginsTlv(origins)
            }
        }
    }

    /** Amount that will be offered by the initiator of a dual-funded channel to the non-initiator. */
    data class PushAmountTlv(val amount: MilliSatoshi) : ChannelTlv() {
        override val tag: Long get() = PushAmountTlv.tag

        override fun write(out: Output) = LightningCodecs.writeTU64(amount.toLong(), out)

        companion object : TlvValueReader<PushAmountTlv> {
            const val tag: Long = 0x47000007

            override fun read(input: Input): PushAmountTlv = PushAmountTlv(LightningCodecs.tu64(input).msat)
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
}

sealed class CommitSigTlv : Tlv {
    data class ChannelData(val ecb: EncryptedChannelData) : CommitSigTlv() {
        override val tag: Long get() = ChannelData.tag
        override fun write(out: Output) = LightningCodecs.writeBytes(ecb.data, out)

        companion object : TlvValueReader<ChannelData> {
            const val tag: Long = 0x47010000
            override fun read(input: Input): ChannelData = ChannelData(EncryptedChannelData(LightningCodecs.bytes(input, input.availableBytes).toByteVector()))
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
    data class ChannelData(val ecb: EncryptedChannelData) : RevokeAndAckTlv() {
        override val tag: Long get() = ChannelData.tag
        override fun write(out: Output) = LightningCodecs.writeBytes(ecb.data, out)

        companion object : TlvValueReader<ChannelData> {
            const val tag: Long = 0x47010000
            override fun read(input: Input): ChannelData = ChannelData(EncryptedChannelData(LightningCodecs.bytes(input, input.availableBytes).toByteVector()))
        }
    }
}

sealed class ChannelReestablishTlv : Tlv {
    data class NextFunding(val txHash: ByteVector32) : ChannelReestablishTlv() {
        override val tag: Long get() = NextFunding.tag
        override fun write(out: Output) = LightningCodecs.writeBytes(txHash, out)

        companion object : TlvValueReader<NextFunding> {
            const val tag: Long = 0
            override fun read(input: Input): NextFunding = NextFunding(LightningCodecs.bytes(input, 32).toByteVector32())
        }
    }

    data class ChannelData(val ecb: EncryptedChannelData) : ChannelReestablishTlv() {
        override val tag: Long get() = ChannelData.tag
        override fun write(out: Output) = LightningCodecs.writeBytes(ecb.data, out)

        companion object : TlvValueReader<ChannelData> {
            const val tag: Long = 0x47010000
            override fun read(input: Input): ChannelData = ChannelData(EncryptedChannelData(LightningCodecs.bytes(input, input.availableBytes).toByteVector()))
        }
    }
}

sealed class ShutdownTlv : Tlv {
    data class ChannelData(val ecb: EncryptedChannelData) : ShutdownTlv() {
        override val tag: Long get() = ChannelData.tag
        override fun write(out: Output) = LightningCodecs.writeBytes(ecb.data, out)

        companion object : TlvValueReader<ChannelData> {
            const val tag: Long = 0x47010000
            override fun read(input: Input): ChannelData = ChannelData(EncryptedChannelData(LightningCodecs.bytes(input, input.availableBytes).toByteVector()))
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

    data class ChannelData(val ecb: EncryptedChannelData) : ClosingSignedTlv() {
        override val tag: Long get() = ChannelData.tag
        override fun write(out: Output) = LightningCodecs.writeBytes(ecb.data, out)

        companion object : TlvValueReader<ChannelData> {
            const val tag: Long = 0x47010000
            override fun read(input: Input): ChannelData = ChannelData(EncryptedChannelData(LightningCodecs.bytes(input, input.availableBytes).toByteVector()))
        }
    }
}

sealed class PleaseOpenChannelTlv : Tlv {
    // NB: this is a temporary tlv that is only used to ensure a smooth migration to lightning-kmp for the android version of Phoenix.
    data class GrandParents(val outpoints: List<OutPoint>) : PleaseOpenChannelTlv() {
        override val tag: Long get() = GrandParents.tag
        override fun write(out: Output) {
            outpoints.forEach { outpoint ->
                LightningCodecs.writeBytes(outpoint.hash.toByteArray(), out)
                LightningCodecs.writeU64(outpoint.index, out)
            }
        }

        companion object : TlvValueReader<GrandParents> {
            const val tag: Long = 561
            override fun read(input: Input): GrandParents {
                val count = input.availableBytes / 40
                val outpoints = (0 until count).map { OutPoint(LightningCodecs.bytes(input, 32).toByteVector32(), LightningCodecs.u64(input)) }
                return GrandParents(outpoints)
            }
        }
    }
}

sealed class PleaseOpenChannelRejectedTlv : Tlv {
    data class ExpectedFees(val fees: MilliSatoshi) : PleaseOpenChannelRejectedTlv() {
        override val tag: Long get() = ExpectedFees.tag
        override fun write(out: Output) = LightningCodecs.writeTU64(fees.toLong(), out)

        companion object : TlvValueReader<ExpectedFees> {
            const val tag: Long = 1
            override fun read(input: Input): ExpectedFees = ExpectedFees(LightningCodecs.tu64(input).msat)
        }
    }
}