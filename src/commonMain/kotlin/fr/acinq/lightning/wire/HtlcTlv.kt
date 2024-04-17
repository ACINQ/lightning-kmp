package fr.acinq.lightning.wire

import fr.acinq.bitcoin.PublicKey
import fr.acinq.bitcoin.io.Input
import fr.acinq.bitcoin.io.Output

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
}