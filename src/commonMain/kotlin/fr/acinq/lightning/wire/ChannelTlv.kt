package fr.acinq.lightning.wire

import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.Satoshi
import fr.acinq.bitcoin.io.Input
import fr.acinq.bitcoin.io.Output
import fr.acinq.lightning.Features
import fr.acinq.lightning.MilliSatoshi
import fr.acinq.lightning.ShortChannelId
import fr.acinq.lightning.channel.ChannelOrigin
import fr.acinq.lightning.channel.ChannelType
import fr.acinq.lightning.utils.msat
import fr.acinq.lightning.utils.toByteVector
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

@Serializable
sealed class ChannelTlv : Tlv {
    /** Commitment to where the funds will go in case of a mutual close, which remote node will enforce in case we're compromised. */
    @Serializable
    data class UpfrontShutdownScriptTlv(@Contextual val scriptPubkey: ByteVector) : ChannelTlv() {
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

    @Serializable
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

    @Serializable
    data class ChannelOriginTlv(val channelOrigin: ChannelOrigin) : ChannelTlv() {
        override val tag: Long get() = ChannelOriginTlv.tag

        override fun write(out: Output) {
            when (channelOrigin) {
                is ChannelOrigin.PayToOpenOrigin -> {
                    LightningCodecs.writeU16(1, out)
                    LightningCodecs.writeBytes(channelOrigin.paymentHash, out)
                    LightningCodecs.writeU64(channelOrigin.fee.toLong(), out)
                }
                is ChannelOrigin.SwapInOrigin -> {
                    LightningCodecs.writeU16(2, out)
                    val addressBytes = channelOrigin.bitcoinAddress.encodeToByteArray()
                    LightningCodecs.writeBigSize(addressBytes.size.toLong(), out)
                    LightningCodecs.writeBytes(addressBytes, out)
                    LightningCodecs.writeU64(channelOrigin.fee.toLong(), out)
                }
            }
        }

        companion object : TlvValueReader<ChannelOriginTlv> {
            const val tag: Long = 0x47000005

            override fun read(input: Input): ChannelOriginTlv {
                val origin = when (LightningCodecs.u16(input)) {
                    1 -> ChannelOrigin.PayToOpenOrigin(
                        paymentHash = ByteVector32(LightningCodecs.bytes(input, 32)),
                        fee = Satoshi(LightningCodecs.u64(input))
                    )
                    2 -> {
                        val len = LightningCodecs.bigSize(input)
                        ChannelOrigin.SwapInOrigin(
                            bitcoinAddress = LightningCodecs.bytes(input, len).decodeToString(),
                            fee = Satoshi(LightningCodecs.u64(input))
                        )
                    }
                    else -> TODO("Unsupported channel origin discriminator")
                }
                return ChannelOriginTlv(origin)
            }
        }
    }

    /** Amount that will be offered by the initiator of a dual-funded channel to the non-initiator. */
    @Serializable
    data class PushAmountTlv(@Contextual val amount: MilliSatoshi) : ChannelTlv() {
        override val tag: Long get() = PushAmountTlv.tag

        override fun write(out: Output) = LightningCodecs.writeTU64(amount.toLong(), out)

        companion object : TlvValueReader<PushAmountTlv> {
            const val tag: Long = 0x47000007

            override fun read(input: Input): PushAmountTlv = PushAmountTlv(LightningCodecs.tu64(input).msat)
        }
    }
}

@Serializable
sealed class ChannelReadyTlv : Tlv {
    @Serializable
    data class ShortChannelIdTlv(val alias: ShortChannelId) : ChannelReadyTlv() {
        override val tag: Long get() = ShortChannelIdTlv.tag
        override fun write(out: Output) = LightningCodecs.writeU64(alias.toLong(), out)

        companion object : TlvValueReader<ShortChannelIdTlv> {
            const val tag: Long = 1
            override fun read(input: Input): ShortChannelIdTlv = ShortChannelIdTlv(ShortChannelId(LightningCodecs.u64(input)))
        }
    }
}

@Serializable
sealed class CommitSigTlv : Tlv {
    @Serializable
    data class ChannelData(@Contextual val ecb: EncryptedChannelData) : CommitSigTlv() {
        override val tag: Long get() = ChannelData.tag
        override fun write(out: Output) = LightningCodecs.writeBytes(ecb.data, out)

        companion object : TlvValueReader<ChannelData> {
            const val tag: Long = 0x47010000
            override fun read(input: Input): ChannelData = ChannelData(EncryptedChannelData(LightningCodecs.bytes(input, input.availableBytes).toByteVector()))
        }
    }
}

@Serializable
sealed class RevokeAndAckTlv : Tlv {
    @Serializable
    data class ChannelData(@Contextual val ecb: EncryptedChannelData) : RevokeAndAckTlv() {
        override val tag: Long get() = ChannelData.tag
        override fun write(out: Output) = LightningCodecs.writeBytes(ecb.data, out)

        companion object : TlvValueReader<ChannelData> {
            const val tag: Long = 0x47010000
            override fun read(input: Input): ChannelData = ChannelData(EncryptedChannelData(LightningCodecs.bytes(input, input.availableBytes).toByteVector()))
        }
    }
}

@Serializable
sealed class ChannelReestablishTlv : Tlv {
    @Serializable
    data class ChannelData(@Contextual val ecb: EncryptedChannelData) : ChannelReestablishTlv() {
        override val tag: Long get() = ChannelData.tag
        override fun write(out: Output) = LightningCodecs.writeBytes(ecb.data, out)

        companion object : TlvValueReader<ChannelData> {
            const val tag: Long = 0x47010000
            override fun read(input: Input): ChannelData = ChannelData(EncryptedChannelData(LightningCodecs.bytes(input, input.availableBytes).toByteVector()))
        }
    }
}

@Serializable
sealed class ShutdownTlv : Tlv {
    @Serializable
    data class ChannelData(@Contextual val ecb: EncryptedChannelData) : ShutdownTlv() {
        override val tag: Long get() = ChannelData.tag
        override fun write(out: Output) = LightningCodecs.writeBytes(ecb.data, out)

        companion object : TlvValueReader<ChannelData> {
            const val tag: Long = 0x47010000
            override fun read(input: Input): ChannelData = ChannelData(EncryptedChannelData(LightningCodecs.bytes(input, input.availableBytes).toByteVector()))
        }
    }
}

@Serializable
sealed class ClosingSignedTlv : Tlv {
    @Serializable
    data class FeeRange(@Contextual val min: Satoshi, @Contextual val max: Satoshi) : ClosingSignedTlv() {
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

    @Serializable
    data class ChannelData(@Contextual val ecb: EncryptedChannelData) : ClosingSignedTlv() {
        override val tag: Long get() = ChannelData.tag
        override fun write(out: Output) = LightningCodecs.writeBytes(ecb.data, out)

        companion object : TlvValueReader<ChannelData> {
            const val tag: Long = 0x47010000
            override fun read(input: Input): ChannelData = ChannelData(EncryptedChannelData(LightningCodecs.bytes(input, input.availableBytes).toByteVector()))
        }
    }
}
