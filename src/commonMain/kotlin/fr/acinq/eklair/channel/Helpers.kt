package fr.acinq.eklair.channel

import fr.acinq.bitcoin.*
import fr.acinq.bitcoin.Script.pay2wsh
import fr.acinq.bitcoin.Script.write
import fr.acinq.eklair.transactions.Scripts.multiSig2of2
import fr.acinq.eklair.transactions.Transactions
import kotlinx.serialization.InternalSerializationApi
import kotlin.math.abs


@OptIn(InternalSerializationApi::class)
object Helpers {

    object Funding {

        fun makeFundingInputInfo(fundingTxId: ByteVector32, fundingTxOutputIndex: Int, fundingSatoshis: Satoshi, fundingPubkey1: PublicKey, fundingPubkey2: PublicKey): Transactions.InputInfo {
            val fundingScript = multiSig2of2(fundingPubkey1, fundingPubkey2)
            val fundingTxOut = TxOut(fundingSatoshis, pay2wsh(fundingScript))
            return Transactions.InputInfo(OutPoint(fundingTxId, fundingTxOutputIndex.toLong()), fundingTxOut, ByteVector(write(fundingScript)))
        }

    }

    /**
     * @param referenceFeePerKw reference fee rate per kiloweight
     * @param currentFeePerKw   current fee rate per kiloweight
     * @return the "normalized" difference between i.e local and remote fee rate: |reference - current| / avg(current, reference)
     */
    fun feeRateMismatch(referenceFeePerKw: Long, currentFeePerKw: Long): Double =
        abs((2.0 * (referenceFeePerKw - currentFeePerKw)) / (currentFeePerKw + referenceFeePerKw))

    /**
     * @param referenceFeePerKw       reference fee rate per kiloweight
     * @param currentFeePerKw         current fee rate per kiloweight
     * @param maxFeerateMismatchRatio maximum fee rate mismatch ratio
     * @return true if the difference between current and reference fee rates is too high.
     *         the actual check is |reference - current| / avg(current, reference) > mismatch ratio
     */
    fun isFeeDiffTooHigh(referenceFeePerKw: Long, currentFeePerKw: Long, maxFeerateMismatchRatio: Double): Boolean =
        feeRateMismatch(referenceFeePerKw, currentFeePerKw) > maxFeerateMismatchRatio

}
