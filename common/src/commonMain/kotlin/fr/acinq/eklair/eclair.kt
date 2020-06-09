package fr.acinq.eklair

import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.ByteVector64
import fr.acinq.eklair.asserts.secure
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

}
