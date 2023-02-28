package fr.acinq.lightning.wire

import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.ByteVector64
import fr.acinq.bitcoin.Satoshi
import fr.acinq.bitcoin.io.Input
import fr.acinq.bitcoin.io.Output
import fr.acinq.lightning.utils.sat
import fr.acinq.lightning.utils.toByteVector
import fr.acinq.lightning.utils.toByteVector32
import fr.acinq.lightning.utils.toByteVector64

sealed class TxAddInputTlv : Tlv {
    /** When doing a splice, the initiator must provide the previous funding txId instead of the whole transaction. */
    data class SharedInputTxId(val txId: ByteVector32) : TxAddInputTlv() {
        override val tag: Long get() = SharedInputTxId.tag
        override fun write(out: Output) = LightningCodecs.writeBytes(txId, out)

        companion object : TlvValueReader<SharedInputTxId> {
            const val tag: Long = 1105
            override fun read(input: Input): SharedInputTxId = SharedInputTxId(LightningCodecs.bytes(input, 32).toByteVector32())
        }
    }
}

sealed class TxAddOutputTlv : Tlv

sealed class TxRemoveInputTlv : Tlv

sealed class TxRemoveOutputTlv : Tlv

sealed class TxCompleteTlv : Tlv

sealed class TxSignaturesTlv : Tlv {
    /** When doing a splice, each peer must provide their signature for the previous 2-of-2 funding output. */
    data class PreviousFundingTxSig(val sig: ByteVector64) : TxSignaturesTlv() {
        override val tag: Long get() = PreviousFundingTxSig.tag
        override fun write(out: Output) = LightningCodecs.writeBytes(sig.toByteArray(), out)

        companion object : TlvValueReader<PreviousFundingTxSig> {
            const val tag: Long = 601
            override fun read(input: Input): PreviousFundingTxSig = PreviousFundingTxSig(LightningCodecs.bytes(input, 64).toByteVector64())
        }
    }

    data class ChannelData(val ecb: EncryptedChannelData) : TxSignaturesTlv() {
        override val tag: Long get() = ChannelData.tag
        override fun write(out: Output) = LightningCodecs.writeBytes(ecb.data, out)

        companion object : TlvValueReader<ChannelData> {
            const val tag: Long = 0x47010000
            override fun read(input: Input): ChannelData = ChannelData(EncryptedChannelData(LightningCodecs.bytes(input, input.availableBytes).toByteVector()))
        }
    }
}

sealed class TxInitRbfTlv : Tlv {
    /** Amount that the peer will contribute to the transaction's shared output. */
    data class SharedOutputContributionTlv(val amount: Satoshi) : TxInitRbfTlv() {
        override val tag: Long get() = SharedOutputContributionTlv.tag

        override fun write(out: Output) = LightningCodecs.writeTU64(amount.toLong(), out)

        companion object : TlvValueReader<SharedOutputContributionTlv> {
            const val tag: Long = 0

            override fun read(input: Input): SharedOutputContributionTlv = SharedOutputContributionTlv(LightningCodecs.tu64(input).sat)
        }
    }
}

sealed class TxAckRbfTlv : Tlv {
    /** Amount that the peer will contribute to the transaction's shared output. */
    data class SharedOutputContributionTlv(val amount: Satoshi) : TxAckRbfTlv() {
        override val tag: Long get() = SharedOutputContributionTlv.tag

        override fun write(out: Output) = LightningCodecs.writeTU64(amount.toLong(), out)

        companion object : TlvValueReader<SharedOutputContributionTlv> {
            const val tag: Long = 0

            override fun read(input: Input): SharedOutputContributionTlv = SharedOutputContributionTlv(LightningCodecs.tu64(input).sat)
        }
    }
}

sealed class TxAbortTlv : Tlv
