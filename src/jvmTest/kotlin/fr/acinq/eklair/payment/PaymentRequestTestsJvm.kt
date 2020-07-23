package fr.acinq.eklair.payment

import fr.acinq.bitcoin.Bech32
import fr.acinq.bitcoin.Crypto
import fr.acinq.bitcoin.Int5
import fr.acinq.bitcoin.PublicKey
import fr.acinq.eklair.utils.BitStream
import fr.acinq.eklair.utils.toByteVector64
import fr.acinq.secp256k1.Hex
import io.ktor.utils.io.core.*
import org.junit.Test
import kotlin.experimental.and

class PaymentRequestTestsJvm {

    fun toBits(value: Int5): List<Boolean> = listOf((value and 16) != 0.toByte(), (value and 8) != 0.toByte(), (value and 4) != 0.toByte(), (value and 2) != 0.toByte(), (value and 1) != 0.toByte())

    @Test
    fun `read payment request`() {
        val input = "lnbcrt1p03tr3cpp5fcpnv0mqxfakt25p9ghl4las2h7ayvew5lh4yz5844qawhd0fllqdq8w3jhxaqxqrrss9qy9qsqsp5u2m3txd70avrqjrhprq2zuxgjejr267vkly446pjk0ppf83709rq2wrlfz924rayzcrdqfhpd3qsccezcczaj6jar60ae8m82zdlaleysvw8atux5vdqef3tzjlm7c43ya0nevtvjxa03xd7jdn58u37r0qpdvu50z"
        val pr = PaymentRequest.read(input)
        println(pr)
    }
}