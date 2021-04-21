package fr.acinq.lightning.utils

import java.security.SecureRandom
import kotlin.random.Random

class SecureRandomJvm : Random() {

    private val storage = object : ThreadLocal<SecureRandom>() {
        override fun initialValue(): SecureRandom {
            return SecureRandom()
        }
    }

    private val random: SecureRandom get() = storage.get()

    override fun nextBits(bitCount: Int): Int {
        val int = random.nextInt()
        return (int ushr (32 - bitCount)) and (-bitCount shr 31)
    }

    override fun nextInt(): Int = random.nextInt()
    override fun nextInt(until: Int): Int = random.nextInt(until)
    override fun nextLong(): Long = random.nextLong()
    override fun nextBoolean(): Boolean = random.nextBoolean()
    override fun nextDouble(): Double = random.nextDouble()
    override fun nextFloat(): Float = random.nextFloat()
    override fun nextBytes(array: ByteArray): ByteArray = array.also { random.nextBytes(it) }
}

actual fun Random.Default.secure(): Random = SecureRandomJvm()

actual fun runtimeEntropy(): ByteArray {
    val freeMemory = Runtime.getRuntime().freeMemory()
    val b = ByteArray(4)
    b.set(0, freeMemory.toByte())
    b.set(1, (freeMemory shr 8).toByte())
    b.set(2, (freeMemory shr 16).toByte())
    b.set(3, (freeMemory shr 24).toByte())
    return ByteArray(0)
}