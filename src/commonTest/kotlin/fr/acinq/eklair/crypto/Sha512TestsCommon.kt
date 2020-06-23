package fr.acinq.eklair.crypto

import fr.acinq.bitcoin.crypto.Sha512
import fr.acinq.eklair.Hex
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertTrue

@ExperimentalStdlibApi
class Sha512TestsCommon {
    @Test
    fun `reference tests`() {
      assertTrue { Sha512.hash("abc".encodeToByteArray()).contentEquals(Hex.decode("ddaf35a193617abacc417349ae20413112e6fa4e89a97ea20a9eeee64b55d39a2192992a274fc1a836ba3c23a3feebbd454d4423643ce80e2a9ac94fa54ca49f")) }
      assertTrue { Sha512.hash("".encodeToByteArray()).contentEquals(Hex.decode("cf83e1357eefb8bdf1542850d66d8007d620e4050b5715dc83f4a921d36ce9ce47d0d13c5d85f2b0ff8318d2877eec2f63b931bd47417a81a538327af927da3e")) }
      assertTrue { Sha512.hash("abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq".encodeToByteArray()).contentEquals(Hex.decode("204a8fc6dda82f0a0ced7beb8e08a41657c16ef468b228a8279be331a703c33596fd15c13b1b07f9aa1d3bea57789ca031ad85c7a71dd70354ec631238ca3445")) }
      assertTrue { Sha512.hash("abcdefghbcdefghicdefghijdefghijkefghijklfghijklmghijklmnhijklmnoijklmnopjklmnopqklmnopqrlmnopqrsmnopqrstnopqrstu".encodeToByteArray()).contentEquals(Hex.decode("8e959b75dae313da8cf4f72814fc143f8f7779c6eb9f7fa17299aeadb6889018501d289e4900f7e4331b99dec4b5433ac7d329eeb6dd26545e96e55b874be909")) }
      assertTrue { Sha512.hash(ByteArray(1000_000) { 0x61.toByte() }).contentEquals(Hex.decode("e718483d0ce769644e2e42c7bc15b4638e1f98b13b2044285632a803afa973ebde0ff244877ea60a4cb0432ce577c31beb009c5c2c49aa2e4eadb217ad8cc09b")) }
    }

    @Test
    @Ignore
    fun `very long input`() {
        val sha512 = Sha512()
        val input = "abcdefghbcdefghicdefghijdefghijkefghijklfghijklmghijklmnhijklmno".encodeToByteArray()
        for (i in 0L until 16_777_216L) sha512.update(input, 0, input.size)
        val output = ByteArray(64)
        sha512.doFinal(output, 0)
        assertTrue { output.contentEquals(Hex.decode("b47c933421ea2db149ad6e10fce6c7f93d0752380180ffd7f4629a712134831d77be6091b819ed352c2967a2e2d4fa5050723c9630691f1a05a7281dbe6c1086")) }
    }
}