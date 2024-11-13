package fr.acinq.lightning.wire

import fr.acinq.bitcoin.PublicKey
import fr.acinq.bitcoin.TxHash
import fr.acinq.bitcoin.TxId
import fr.acinq.bitcoin.io.Input
import fr.acinq.bitcoin.io.Output
import fr.acinq.lightning.utils.msat

sealed class UpdateAddHtlcTlv : Tlv {
    /** Blinding ephemeral public key that should be used to derive shared secrets when using route blinding. */
    data class Blinding(val publicKey: PublicKey) : UpdateAddHtlcTlv() {
        override val tag: Long get() = Blinding.tag

        override fun write(out: Output) = LightningCodecs.writeBytes(publicKey.value, out)

        companion object : TlvValueReader<Blinding> {
            const val tag: Long = 0
            override fun read(input: Input): Blinding = Blinding(PublicKey(LightningCodecs.bytes(input, 33)))
        }
    }

    /** When on-the-fly funding is used, the liquidity fees may be taken from HTLCs relayed after funding. */
    data class FundingFeeTlv(val fee: LiquidityAds.FundingFee) : UpdateAddHtlcTlv() {
        override val tag: Long get() = FundingFeeTlv.tag

        override fun write(out: Output) {
            LightningCodecs.writeU64(fee.amount.toLong(), out)
            LightningCodecs.writeTxHash(TxHash(fee.fundingTxId), out)
        }

        companion object : TlvValueReader<FundingFeeTlv> {
            const val tag: Long = 41041
            override fun read(input: Input): FundingFeeTlv = FundingFeeTlv(
                fee = LiquidityAds.FundingFee(
                    amount = LightningCodecs.u64(input).msat,
                    fundingTxId = TxId(LightningCodecs.txHash(input)),
                )
            )
        }
    }
}

sealed class WillAddHtlcTlv : Tlv {
    /** Blinding ephemeral public key that should be used to derive shared secrets when using route blinding. */
    data class Blinding(val publicKey: PublicKey) : WillAddHtlcTlv() {
        override val tag: Long get() = Blinding.tag

        override fun write(out: Output) = LightningCodecs.writeBytes(publicKey.value, out)

        companion object : TlvValueReader<Blinding> {
            const val tag: Long = 0
            override fun read(input: Input): Blinding = Blinding(PublicKey(LightningCodecs.bytes(input, 33)))
        }
    }
}