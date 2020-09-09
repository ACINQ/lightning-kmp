package fr.acinq.eclair

import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.ByteVector64
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.eclair.utils.secure
import kotlin.experimental.xor
import kotlin.random.Random


object Eclair {
    /**
     * minimum relay fee rate, in satoshi per kilo
     * bitcoin core uses virtual size and not the actual size in bytes, see above
     **/
    val MinimumRelayFeeRate = 1000L

    /**
     * why 253 and not 250 since feerate-per-kw is feerate-per-kb / 250 and the minimum relay fee rate is 1000 satoshi/Kb ?
     *
     * because bitcoin core uses neither the actual tx size in bytes or the tx weight to check fees, but a "virtual size"
     * which is (3 * weight) / 4 ...
     * so we want :
     * fee > 1000 * virtual size
     * feerate-per-kw * weight > 1000 * (3 * weight / 4)
     * feerate_per-kw > 250 + 3000 / (4 * weight)
     * with a conservative minimum weight of 400, we get a minimum feerate_per-kw of 253
     *
     * see https://github.com/ElementsProject/lightning/pull/1251
     **/
    val MinimumFeeratePerKw = 253

    val secureRandom = Random.secure()

    fun randomBytes(length: Int): ByteArray {
        val buffer = ByteArray(length)
        secureRandom.nextBytes(buffer)
        return buffer
    }

    fun randomBytes32(): ByteVector32 = ByteVector32(randomBytes(32))

    fun randomBytes64(): ByteVector64 = ByteVector64(randomBytes(64))

    fun randomKey() : PrivateKey = PrivateKey(randomBytes32())

    fun toLongId(fundingTxHash: ByteVector32, fundingOutputIndex: Int): ByteVector32 {
        require(fundingOutputIndex < 65536) { "fundingOutputIndex must not be greater than FFFF" }
        val x1 = fundingTxHash[30] xor (fundingOutputIndex.shr(8)).toByte()
        val x2 = fundingTxHash[31] xor fundingOutputIndex.toByte()
        val channelId = ByteVector32(fundingTxHash.take(30).concat(x1).concat(x2))
        return channelId
    }

    /**
     * Converts fee rate in satoshi-per-kilobytes to fee rate in satoshi-per-kw
     *
     * @param feeratePerKB fee rate in satoshi-per-kilobytes
     * @return fee rate in satoshi-per-kw
     */
    fun feerateKB2Kw(feeratePerKB: Long): Long = kotlin.math.max(feeratePerKB / 4, MinimumFeeratePerKw.toLong())

    /**
     * Converts fee rate in satoshi-per-kw to fee rate in satoshi-per-kilobyte
     *
     * @param feeratePerKw fee rate in satoshi-per-kw
     * @return fee rate in satoshi-per-kilobyte
     */
    fun feerateKw2KB(feeratePerKw: Long): Long = feeratePerKw * 4

    /**
     * @param baseFee         fixed fee
     * @param proportionalFee proportional fee (millionths)
     * @param paymentAmount   payment amount in millisatoshi
     * @return the fee that a node should be paid to forward an HTLC of 'paymentAmount' millisatoshis
     */
    fun nodeFee(baseFee: MilliSatoshi, proportionalFee: Long, paymentAmount: MilliSatoshi): MilliSatoshi = baseFee + (paymentAmount * proportionalFee) / 1000000
}
