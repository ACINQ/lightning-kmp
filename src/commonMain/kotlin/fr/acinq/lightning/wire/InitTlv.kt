package fr.acinq.lightning.wire

import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.ByteVector64
import fr.acinq.bitcoin.PublicKey
import fr.acinq.bitcoin.byteVector32
import fr.acinq.bitcoin.io.Input
import fr.acinq.bitcoin.io.Output

/** Tlv types used inside Init messages. */

sealed class InitTlv : Tlv {

    data class Networks(val chainHashes: List<ByteVector32>) : InitTlv() {
        override val tag: Long get() = Networks.tag

        override fun write(out: Output) {
            chainHashes.forEach { LightningCodecs.writeBytes(it, out) }
        }

        companion object : TlvValueReader<Networks> {
            const val tag: Long = 1

            override fun read(input: Input): Networks {
                val networks = ArrayList<ByteVector32>()
                // README: it's up to the caller to make sure that the input stream will EOF when there are no more hashes to read!
                while (input.availableBytes > 0) {
                    networks.add(ByteVector32(LightningCodecs.bytes(input, 32)))
                }
                return Networks(networks.toList())
            }
        }
    }

    /** Rates at which we sell inbound liquidity to remote peers. */
    data class LiquidityAdsRates(val leaseRates: List<LiquidityAds.LeaseRate>) : InitTlv() {
        override val tag: Long get() = LiquidityAdsRates.tag

        override fun write(out: Output) {
            leaseRates.forEach { it.write(out) }
        }

        companion object : TlvValueReader<LiquidityAdsRates> {
            const val tag: Long = 1337

            override fun read(input: Input): LiquidityAdsRates {
                val count = input.availableBytes / 16
                val rates = (0 until count).map { LiquidityAds.LeaseRate.read(input) }
                return LiquidityAdsRates(rates)
            }
        }
    }

    data class PhoenixAndroidLegacyNodeId(val legacyNodeId: PublicKey, val signature: ByteVector64) : InitTlv() {
        override val tag: Long get() = PhoenixAndroidLegacyNodeId.tag

        override fun write(out: Output) {
            LightningCodecs.writeBytes(legacyNodeId.value, out)
            LightningCodecs.writeBytes(signature, out)
        }

        companion object : TlvValueReader<PhoenixAndroidLegacyNodeId> {
            const val tag: Long = 0x47020001

            override fun read(input: Input): PhoenixAndroidLegacyNodeId {
                val legacyNodeId = PublicKey(LightningCodecs.bytes(input, 33))
                val signature = ByteVector64(LightningCodecs.bytes(input, 64))
                return PhoenixAndroidLegacyNodeId(legacyNodeId, signature)
            }
        }
    }
}

sealed class CurrentFeeCreditTlv : Tlv {
    /** Latest payments that were used as fee credit. */
    data class LatestPayments(val preimages: List<ByteVector32>) : CurrentFeeCreditTlv() {
        override val tag: Long get() = LatestPayments.tag

        override fun write(out: Output) {
            preimages.forEach { LightningCodecs.writeBytes(it, out) }
        }

        companion object : TlvValueReader<LatestPayments> {
            const val tag: Long = 1

            override fun read(input: Input): LatestPayments {
                val count = input.availableBytes / 32
                val preimages = (0 until count).map { LightningCodecs.bytes(input, 32).byteVector32() }
                return LatestPayments(preimages)
            }
        }
    }
}
