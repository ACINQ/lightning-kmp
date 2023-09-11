package fr.acinq.lightning.blockchain.electrum

import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.Satoshi
import fr.acinq.bitcoin.Transaction
import fr.acinq.lightning.blockchain.fee.FeeratePerKw
import fr.acinq.lightning.channel.Commitments
import fr.acinq.lightning.channel.LocalFundingStatus
import fr.acinq.lightning.transactions.Transactions
import fr.acinq.lightning.utils.MDCLogger
import fr.acinq.lightning.utils.sat

suspend fun IElectrumClient.getConfirmations(txId: ByteVector32): Int? = getTx(txId)?.let { tx -> getConfirmations(tx) }

/**
 * @return the number of confirmations, zero if the transaction is in the mempool, null if the transaction is not found
 */
suspend fun IElectrumClient.getConfirmations(tx: Transaction): Int? {
    return when (val status = connectionStatus.value) {
        is ElectrumConnectionStatus.Connected -> {
            val currentBlockHeight = status.height
            val scriptHash = ElectrumClient.computeScriptHash(tx.txOut.first().publicKeyScript)
            val scriptHashHistory = getScriptHashHistory(scriptHash)
            val item = scriptHashHistory.find { it.txid == tx.txid }
            item?.let { if (item.blockHeight > 0) currentBlockHeight - item.blockHeight + 1 else 0 }
        }
        else -> null
    }
}

suspend fun IElectrumClient.computeSpliceCpfpFeerate(commitments: Commitments, targetFeerate: FeeratePerKw, spliceWeight: Int, logger: MDCLogger): Pair<FeeratePerKw, Satoshi> {
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