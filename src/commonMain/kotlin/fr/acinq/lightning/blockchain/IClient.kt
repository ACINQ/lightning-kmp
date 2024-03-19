package fr.acinq.lightning.blockchain

import fr.acinq.bitcoin.Satoshi
import fr.acinq.bitcoin.TxId
import fr.acinq.lightning.blockchain.fee.FeeratePerByte
import fr.acinq.lightning.blockchain.fee.FeeratePerKw
import fr.acinq.lightning.channel.Commitments
import fr.acinq.lightning.channel.LocalFundingStatus
import fr.acinq.lightning.logging.MDCLogger
import fr.acinq.lightning.transactions.Transactions
import fr.acinq.lightning.utils.sat

interface IClient {
    suspend fun getConfirmations(txId: TxId): Int?

    /** Estimate the feerate required for a transaction to be confirmed in the next [confirmations] blocks. */
    suspend fun getFeerates(): Feerates?
}

data class Feerates(
    val minimum: FeeratePerByte,
    val slow: FeeratePerByte,
    val medium: FeeratePerByte,
    val fast: FeeratePerByte,
    val fastest: FeeratePerByte
)

/**
 * @weight must be the total estimated weight of the splice tx, otherwise the feerate estimation will be wrong
 */
suspend fun IClient.computeSpliceCpfpFeerate(commitments: Commitments, targetFeerate: FeeratePerKw, spliceWeight: Int, logger: MDCLogger): Pair<FeeratePerKw, Satoshi> {
    val (parentsWeight, parentsFees) = commitments.all
        .takeWhile { getConfirmations(it.fundingTxId).let { confirmations -> confirmations == null || confirmations == 0 } } // we check for null in case the tx has been evicted
        .fold(Pair(0, 0.sat)) { (parentsWeight, parentsFees), commitment ->
            val weight = when (commitment.localFundingStatus) {
                // weight will be underestimated if the transaction is not fully signed
                is LocalFundingStatus.UnconfirmedFundingTx -> commitment.localFundingStatus.signedTx?.weight() ?: commitment.localFundingStatus.sharedTx.tx.buildUnsignedTx().weight()
                is LocalFundingStatus.ConfirmedFundingTx -> commitment.localFundingStatus.signedTx.weight()
            }
            Pair(parentsWeight + weight, parentsFees + commitment.localFundingStatus.fee)
        }
    val totalWeight = parentsWeight + spliceWeight
    val totalFees = Transactions.weight2fee(targetFeerate, totalWeight)
    val projectedFee = totalFees - parentsFees
    val projectedFeerate = Transactions.fee2rate(projectedFee, spliceWeight)
    // if ancestors have a higher feerate than target, min feerate could be negative
    val actualFeerate = maxOf(projectedFeerate, targetFeerate)
    val actualFee = Transactions.weight2fee(actualFeerate, spliceWeight)
    logger.info { "targetFeerate=$targetFeerate spliceWeight=$spliceWeight" }
    logger.info { "parentsWeight=$parentsWeight parentsFees=$parentsFees" }
    logger.info { "totalWeight=$totalWeight totalFees=$totalFees" }
    logger.info { "projectedFeerate=$projectedFeerate projectedFee=$projectedFee" }
    logger.info { "actualFeerate=$actualFeerate actualFee=$actualFee" }
    return Pair(actualFeerate, actualFee)
}