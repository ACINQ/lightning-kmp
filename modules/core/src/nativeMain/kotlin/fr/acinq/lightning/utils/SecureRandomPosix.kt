package fr.acinq.lightning.utils

import kotlinx.cinterop.*
import platform.posix.O_RDONLY
import platform.posix.close
import platform.posix.open
import platform.posix.read
import kotlin.random.Random

object SecureRandomPosix : Random() {

    @OptIn(ExperimentalForeignApi::class)
    override fun nextBytes(array: ByteArray, fromIndex: Int, toIndex: Int): ByteArray {
        val size = toIndex - fromIndex
        require(size >= 0) { "negative size" }
        if (size == 0) return array

        val fd = open("/dev/urandom", O_RDONLY).takeIf { it >= 0 } ?: error("Could not open /dev/urandom")
        try {
            memScoped {
                val mem = allocArray<ByteVar>(size)
                val read = read(fd, mem, size.convert())
                when {
                    read < 0 -> error("Could not read /dev/urandom")
                    read < size -> error("Interrupted while reading /dev/urandom")
                }
                for (i in 0 until size) array[fromIndex + i] = mem[i]
            }
        } finally {
            close(fd)
        }
        return array
    }

    override fun nextInt(): Int {
        val a = ByteArray(Int.SIZE_BYTES)
        nextBytes(a)
        return (a[0].toInt() shl 0) or
                (a[1].toInt() shl 8) or
                (a[2].toInt() shl 16) or
                (a[3].toInt() shl 24)
    }

    override fun nextBits(bitCount: Int): Int {
        val int = nextInt()
        return (int ushr (32 - bitCount)) and (-bitCount shr 31)
    }
}

actual fun Random.Default.secure(): Random = SecureRandomPosix
