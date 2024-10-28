package fr.acinq.lightning.crypto

import fr.acinq.lightning.tests.utils.LightningTestSuite
import org.bouncycastle.crypto.params.KeyParameter
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue

class Poly1305TestsJvm : LightningTestSuite() {
    @Test
    fun `poly1305`() {
        val random = Random
        for (length in 0 until 1000) {
            val key = random.nextBytes(32)
            val data = random.nextBytes(length)
            val result = Poly1305.mac(key, data)
            val check = poly1305check(key, data)
            assertTrue { check.contentEquals(result) }
        }
    }

    fun poly1305check(key: ByteArray, data: ByteArray): ByteArray {
        val mac = org.bouncycastle.crypto.macs.Poly1305()
        mac.init(KeyParameter(key))
        val out = ByteArray(16)
        mac.update(data, 0, data.size)
        mac.doFinal(out, 0)
        return out
    }
}