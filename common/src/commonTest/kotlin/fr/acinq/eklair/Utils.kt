package fr.acinq.eklair

import fr.acinq.bitcoin.ByteVector32
import kotlin.random.Random

object Utils {
    fun randomBytes32(): ByteVector32 = ByteVector32(Random.nextBytes(32))
}