package fr.acinq.lightning.crypto

import fr.acinq.bitcoin.Crypto.sha1
import fr.acinq.bitcoin.Crypto.sha256
import fr.acinq.bitcoin.crypto.Pack
import fr.acinq.lightning.utils.currentTimestampMillis
import fr.acinq.lightning.utils.runtimeEntropy
import fr.acinq.lightning.utils.toByteVector
import fr.acinq.lightning.utils.xor
import kotlin.native.concurrent.ThreadLocal

/**
 * A weak pseudo-random number generator that regularly samples a few entropy sources to build a hash chain.
 * This should never be used alone but can be xor-ed with the OS random number generator in case it completely breaks.
 */
@ThreadLocal
object WeakRandom {

    private var seed = ByteArray(32)
    private var opsSinceLastSample: Int = 0

    private fun sampleEntropy() {
        opsSinceLastSample = 0
        val commonEntropy = Pack.writeInt64BE(currentTimestampMillis()) + Pack.writeInt32BE(ByteArray(0).hashCode())
        val runtimeEntropy = runtimeEntropy()
        seed = seed.xor(sha256(commonEntropy + runtimeEntropy))
    }

    /** We sample new entropy approximately every 8 operations and at most every 16 operations. */
    private fun shouldSample(): Boolean {
        opsSinceLastSample += 1
        val condition1 = -16 <= seed.last() && seed.last() <= 16
        val condition2 = opsSinceLastSample >= 16
        return condition1 || condition2
    }

    fun nextBytes(array: ByteArray): ByteArray {
        if (shouldSample()) {
            sampleEntropy()
        }

        // Generate pseudo-random stream from the internal state.
        val stream = ChaCha20(sha256(sha1(seed.toByteVector())), ByteArray(12), 0)
        stream.encrypt(array, array, array.size)
        // Update the internal state.
        seed = sha256(seed)

        return array
    }

    fun nextLong(): Long {
        val bytes = ByteArray(8)
        nextBytes(bytes)
        return Pack.int64BE(bytes)
    }
}