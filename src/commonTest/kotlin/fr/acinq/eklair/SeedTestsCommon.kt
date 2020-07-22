package fr.acinq.eklair

import fr.acinq.bitcoin.MnemonicCode
import fr.acinq.eklair.crypto.assertArrayEquals
import fr.acinq.secp256k1.Hex
import kotlin.test.Test
import kotlin.test.assertEquals

class SeedTestsCommon {
    @Test
    fun `generate seed`() {
        val entropy = Hex.decode("77c2b00716cec7213839159e404db50d")
        val wordlist = MnemonicCode.toMnemonics(entropy).joinToString(" ")
        assertEquals("jelly better achieve collect unaware mountain thought cargo oxygen act hood bridge", wordlist)
        val passphrase = "TREZOR"
        val seed = MnemonicCode.toSeed(wordlist, passphrase)
        assertArrayEquals(Hex.decode("b5b6d0127db1a9d2226af0c3346031d77af31e918dba64287a1b44b8ebf63cdd52676f672a290aae502472cf2d602c051f3e6f18055e84e4c43897fc4e51a6ff"), seed)
    }
}