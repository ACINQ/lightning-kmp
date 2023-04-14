package fr.acinq.lightning

import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.ByteVector64
import fr.acinq.bitcoin.KeyPath
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.lightning.utils.secure
import kotlin.experimental.xor
import kotlin.random.Random

object Lightning {

    val secureRandom = Random.secure()

    fun randomBytes(length: Int): ByteArray {
        val buffer = ByteArray(length)
        secureRandom.nextBytes(buffer)
        return buffer
    }

    fun randomBytes32(): ByteVector32 = ByteVector32(randomBytes(32))
    fun randomBytes64(): ByteVector64 = ByteVector64(randomBytes(64))
    fun randomKey(): PrivateKey = PrivateKey(randomBytes32())

    /**
     * @param baseFee fixed fee
     * @param proportionalFee proportional fee (millionths)
     * @param paymentAmount payment amount in millisatoshi
     * @return the fee that a node should be paid to forward an HTLC of 'paymentAmount' millisatoshis
     */
    fun nodeFee(baseFee: MilliSatoshi, proportionalFee: Long, paymentAmount: MilliSatoshi): MilliSatoshi = baseFee + (paymentAmount * proportionalFee) / 1_000_000

}
