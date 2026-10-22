package fr.acinq.lightning.utils

import fr.acinq.lightning.tests.utils.LightningTestSuite
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Statistical sanity checks on the platform-specific secure RNG.
 * These run on every target (JVM, Linux, iOS simulator), which matters because each platform has its own implementation.
 */
class SecureRandomTestsCommon : LightningTestSuite() {

    private val random = Random.secure()
    private val draws = 20_000

    private fun assertBitsUniform(name: String, bitCount: Int, draw: (Int) -> Boolean) {
        val setCount = IntArray(bitCount)
        repeat(draws) {
            for (bit in 0 until bitCount) if (draw(bit)) setCount[bit]++
        }
        for (bit in 0 until bitCount) {
            val frequency = setCount[bit].toDouble() / draws
            assertTrue(frequency in 0.47..0.53, "$name: bit $bit is set with frequency $frequency")
        }
    }

    @Test
    fun `nextInt bits are uniform`() {
        val values = IntArray(draws) { random.nextInt() }
        var i = 0
        assertBitsUniform("nextInt", 32) { bit -> if (bit == 0) i++; (values[i - 1] ushr bit) and 1 == 1 }
    }

    @Test
    fun `nextLong bits are uniform`() {
        val values = LongArray(draws) { random.nextLong() }
        var i = 0
        assertBitsUniform("nextLong", 64) { bit -> if (bit == 0) i++; (values[i - 1] ushr bit) and 1L == 1L }
    }

    @Test
    fun `nextBoolean is unbiased`() {
        val trueCount = (0 until draws).count { random.nextBoolean() }
        val frequency = trueCount.toDouble() / draws
        assertTrue(frequency in 0.47..0.53, "nextBoolean is true with frequency $frequency")
    }

    @Test
    fun `nextInt with power-of-two bound is uniform`() {
        // power-of-two bounds use the top bits of nextInt(), which are the most sensitive to sign-extension bugs
        val counts = IntArray(256)
        repeat(draws) { counts[random.nextInt(0, 256)]++ }
        val expected = draws / 256
        for (value in 0 until 256) {
            assertTrue(counts[value] in (expected / 2)..(expected * 2), "nextInt(0, 256) returned $value ${counts[value]} times (expected ~$expected)")
        }
    }

    @Test
    fun `nextInt with non power-of-two bound stays in range and covers it`() {
        val counts = IntArray(72)
        repeat(draws) { counts[random.nextInt(72)]++ }
        val expected = draws / 72
        for (value in 0 until 72) {
            assertTrue(counts[value] in (expected / 2)..(expected * 2), "nextInt(72) returned $value ${counts[value]} times (expected ~$expected)")
        }
    }

    @Test
    fun `nextBytes fills the whole range`() {
        val bytes = ByteArray(4096)
        random.nextBytes(bytes)
        val distinct = bytes.map { it.toInt() and 0xff }.toSet()
        assertTrue(distinct.size > 200, "only ${distinct.size} distinct byte values in 4096 random bytes")
        assertTrue(bytes.count { it < 0 } in 1500..2600, "negative bytes: ${bytes.count { it < 0 }} of 4096")
    }
}