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
        // We start by filtering out confirmed transactions: we don't need to pay for their weight.
        .filter { it.localFundingStatus is LocalFundingStatus.UnconfirmedFundingTx }
        // Then we check if unconfirmed transactions are really unconfirmed, because we wait for min-depth
        // before updating their status to LocalFundingStatus.ConfirmedFundingTx, but we don't need to pay
        // for their weight as soon as they have at least one confirmation.
        .takeWhile { getConfirmations(it.fundingTxId).let { confirmations -> confirmations == null || confirmations == 0 } } // we check for null in case the tx has been evicted
        .fold(Pair(0, 0.sat)) { (parentsWeight, parentsFees), commitment ->
            when (commitment.localFundingStatus) {
                is LocalFundingStatus.UnconfirmedFundingTx -> {
                    // Note that the weight will be underestimated if the transaction is not fully signed.
                    val weight = commitment.localFundingStatus.signedTx?.weight() ?: commitment.localFundingStatus.sharedTx.tx.buildUnsignedTx().weight()
                    Pair(parentsWeight + weight, parentsFees + commitment.localFundingStatus.fee)
                }
                // We filtered confirmed transactions before, so this case shouldn't happen.
                is LocalFundingStatus.ConfirmedFundingTx -> Pair(parentsWeight, parentsFees)
            }
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