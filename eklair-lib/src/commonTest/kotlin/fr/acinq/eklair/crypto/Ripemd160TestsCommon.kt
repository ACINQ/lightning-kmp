package fr.acinq.eklair.crypto

import fr.acinq.eklair.Hex
import kotlin.test.Test
import kotlin.test.assertTrue

@ExperimentalStdlibApi
class Ripemd160TestsCommon {

  @Test // from https://homes.esat.kuleuven.be/~bosselae/ripemd160.html
  fun `reference tests`() {
    assertTrue { Ripemd160.hash("".encodeToByteArray()).contentEquals(Hex.decode("9c1185a5c5e9fc54612808977ee8f548b2258d31")) }
    assertTrue { Ripemd160.hash("abc".encodeToByteArray()).contentEquals(Hex.decode("8eb208f7e05d987a9b044a8e98c6b087f15a0bfc")) }
    assertTrue { Ripemd160.hash("message digest".encodeToByteArray()).contentEquals(Hex.decode("5d0689ef49d2fae572b881b123a85ffa21595f36")) }
    assertTrue { Ripemd160.hash("abcdefghijklmnopqrstuvwxyz".encodeToByteArray()).contentEquals(Hex.decode("f71c27109c692c1b56bbdceb5b9d2865b3708dbc")) }
    assertTrue { Ripemd160.hash("abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq".encodeToByteArray()).contentEquals(Hex.decode("12a053384a9c0c88e405a06c27dcf49ada62eb2b")) }
    assertTrue { Ripemd160.hash("abcdefghbcdefghicdefghijdefghijkefghijklfghijklmghijklmnhijklmno".encodeToByteArray()).contentEquals(Hex.decode("e0b62c9952c259e438bbbf82f643203f94e57550")) }
    assertTrue { Ripemd160.hash("ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789".encodeToByteArray()).contentEquals(Hex.decode("b0e20b6e3116640286ed3a87a5713079b21f5189")) }
    assertTrue { Ripemd160.hash(Array(8) { "1234567890" }.reduce { acc, s ->  acc + s}.encodeToByteArray()).contentEquals(Hex.decode("9b752e45573d4b39f4dbd3323cab82bf63326bfb")) }
    assertTrue { Ripemd160.hash(ByteArray(1000_000) { 0x61.toByte() }).contentEquals(Hex.decode("52783243c1697bdbe16d37f97f68f08325dc1528")) }
  }

  @Test
  fun `very long input`() {
    val ripemd160 = Ripemd160()
    val input = "abcdefghbcdefghicdefghijdefghijkefghijklfghijklmghijklmnhijklmno".encodeToByteArray()
    for (i in 0L until 167773) ripemd160.update(input, 0, input.size)
    val output = ByteArray(20)
    ripemd160.doFinal(output, 0)
    assertTrue { output.contentEquals(Hex.decode("c22925cae5c03927e9a5e8cdb7f5449b2aa4efac")) }
  }

}