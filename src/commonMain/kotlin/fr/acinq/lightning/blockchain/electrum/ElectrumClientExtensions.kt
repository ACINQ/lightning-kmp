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


suspend fun IElectrumClient.getConfirmations(txId: ByteVector32): Int? {
    val tx = kotlin.runCatching { getTx(txId) }.getOrNull()
    return tx?.let { getConfirmations(tx) }
}

/**
 *   @return the number of confirmations, zero if the transaction is in the mempool, null if the transaction is not found
 */
suspend fun IElectrumClient.getConfirmations(tx: Transaction): Int? {
    val scriptHash = ElectrumClient.computeScriptHash(tx.txOut.first().publicKeyScript)
    val scriptHashHistory = getScriptHashHistory(scriptHash)
    val item = scriptHashHistory.find { it.txid == tx.txid }
    val blockHeight = startHeaderSubscription().blockHeight
    return item?.let { if (item.blockHeight > 0) blockHeight - item.blockHeight + 1 else 0 }
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
    val actualFee = totalFees - parentsFees
    val minFeerate = Transactions.fee2rate(actualFee, spliceWeight)
    // if ancestors have a higher feerate than target, min feerate could be negative
    val actualFeerate = maxOf(minFeerate, targetFeerate)
    logger.info { "targetFeerate=$targetFeerate spliceWeight=$spliceWeight" }
    logger.info { "parentsWeight=$parentsWeight parentsFees=$parentsFees" }
    logger.info { "totalWeight=$totalWeight totalFees=$totalFees" }
    logger.info { "minFeerate=$minFeerate actualFeerate=$actualFeerate" }
    return Pair(actualFeerate, actualFee)
}