package fr.acinq.eclair.crypto.noise

import fr.acinq.bitcoin.crypto.Sha256
import kotlin.experimental.xor

object SHA256HashFunctions : HashFunctions {
    override fun name() = "SHA256"

    override fun hashLen() = 32

    override fun blockLen() = 64

    override fun hash(data: ByteArray): ByteArray {
        val sha = Sha256()
        sha.update(data, 0, data.size)
        val output = ByteArray(32)
        sha.doFinal(output, 0)
        return output
    }

    override fun hmacHash(key: ByteArray, data: ByteArray): ByteArray {
        val key1 = if (key.size > 64) hash(key) else key
        val key2 = key1 + ByteArray(64 - key.size) { 0.toByte() }

        fun xor(a: ByteArray, b: ByteArray): ByteArray {
            require(a.size == b.size)
            val output = ByteArray(a.size)
            for (i in a.indices) output[i] = a[i] xor b[i]
            return output
        }

        val a = xor(key2, ByteArray(key2.size) { 0x5c.toByte() })
        val b = xor(key2, ByteArray(key2.size) { 0x36.toByte() })

        return hash(
          a + hash(
            b + data
          )
        )
    }
}