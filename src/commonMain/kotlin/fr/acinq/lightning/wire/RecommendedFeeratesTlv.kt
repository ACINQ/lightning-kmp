package fr.acinq.lightning.wire

import fr.acinq.bitcoin.io.Input
import fr.acinq.bitcoin.io.Output
import fr.acinq.lightning.blockchain.fee.FeeratePerKw
import fr.acinq.lightning.utils.sat

sealed class RecommendedFeeratesTlv : Tlv {

    /** Detailed range of values that will be accepted until the next [RecommendedFeerates] message is received. */
    data class FundingFeerateRange(val min: FeeratePerKw, val max: FeeratePerKw) : RecommendedFeeratesTlv() {
        override val tag: Long get() = FundingFeerateRange.tag

        override fun write(out: Output) {
            LightningCodecs.writeU32(min.toLong().toInt(), out)
            LightningCodecs.writeU32(max.toLong().toInt(), out)
        }

        companion object : TlvValueReader<FundingFeerateRange> {
            const val tag: Long = 1

            override fun read(input: Input): FundingFeerateRange = FundingFeerateRange(
                min = FeeratePerKw(LightningCodecs.u32(input).sat),
                max = FeeratePerKw(LightningCodecs.u32(input).sat),
            )
        }
    }

    /** Detailed range of values that will be accepted until the next [RecommendedFeerates] message is received. */
    data class CommitmentFeerateRange(val min: FeeratePerKw, val max: FeeratePerKw) : RecommendedFeeratesTlv() {
        override val tag: Long get() = CommitmentFeerateRange.tag

        override fun write(out: Output) {
            LightningCodecs.writeU32(min.toLong().toInt(), out)
            LightningCodecs.writeU32(max.toLong().toInt(), out)
        }

        companion object : TlvValueReader<CommitmentFeerateRange> {
            const val tag: Long = 3

            override fun read(input: Input): CommitmentFeerateRange = CommitmentFeerateRange(
                min = FeeratePerKw(LightningCodecs.u32(input).sat),
                max = FeeratePerKw(LightningCodecs.u32(input).sat),
            )
        }
    }

}