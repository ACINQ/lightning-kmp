package fr.acinq.eklair.crypto

import fr.acinq.bitcoin.crypto.Sha256
import fr.acinq.eklair.Hex
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertTrue

@ExperimentalStdlibApi
class Sha256TestsCommon {
    @Test
    fun `reference tests`() {
        assertTrue { Sha256.hash(Hex.decode("")).contentEquals(Hex.decode("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855")) }
        assertTrue { Sha256.hash(Hex.decode("616263")).contentEquals(Hex.decode("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")) }
        assertTrue { Sha256.hash("abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq".encodeToByteArray()).contentEquals(Hex.decode("248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1")) }
        assertTrue { Sha256.hash("abcdefghbcdefghicdefghijdefghijkefghijklfghijklmghijklmnhijklmnoijklmnopjklmnopqklmnopqrlmnopqrsmnopqrstnopqrstu".encodeToByteArray()).contentEquals(Hex.decode("cf5b16a778af8380036ce59e7b0492370b249b11e8f07a51afac45037afee9d1")) }
        assertTrue { Sha256.hash(ByteArray(1000_000) { 0x61.toByte() }).contentEquals(Hex.decode("cdc76e5c9914fb9281a1c7e284d73e67f1809a48a497200e046d39ccc7112cd0")) }
    }

    @Test
    @Ignore
    fun `very long input`() {
        val sha256 = Sha256()
        val input = "abcdefghbcdefghicdefghijdefghijkefghijklfghijklmghijklmnhijklmno".encodeToByteArray()
        for (i in 0L until 16_777_216L) sha256.update(input, 0, input.size)
        val output = ByteArray(32)
        sha256.doFinal(output, 0)
        assertTrue { output.contentEquals(Hex.decode("50e72a0e26442fe2552dc3938ac58658228c0cbfb1d2ca872ae435266fcd055e")) }
    }
}