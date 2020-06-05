package fr.acinq.eklair

import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.ByteVector64
import fr.acinq.eklair.utils.secure
import kotlin.random.Random


object Eclair {
    val MinimumRelayFeeRate = 1000L

    val secureRandom = Random.secure()

    fun randomBytes(length: Int): ByteArray {
        val buffer = ByteArray(length)
        secureRandom.nextBytes(buffer)
        return buffer
    }

    fun randomBytes32(): ByteVector32 = ByteVector32(randomBytes(32))

    fun randomBytes64(): ByteVector64 = ByteVector64(randomBytes(64))

}
