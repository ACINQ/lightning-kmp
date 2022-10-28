package fr.acinq.lightning.wire

import fr.acinq.bitcoin.Satoshi
import fr.acinq.bitcoin.io.Input
import fr.acinq.bitcoin.io.Output
import fr.acinq.lightning.utils.sat


sealed class TxAddInputTlv : Tlv


sealed class TxAddOutputTlv : Tlv


sealed class TxRemoveInputTlv : Tlv


sealed class TxRemoveOutputTlv : Tlv


sealed class TxCompleteTlv : Tlv


sealed class TxSignaturesTlv : Tlv


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
