package fr.acinq.lightning.blockchain.electrum

import fr.acinq.bitcoin.BlockHeader
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.Satoshi
import fr.acinq.bitcoin.Transaction
import fr.acinq.lightning.blockchain.fee.FeeratePerKw
import fr.acinq.lightning.channel.Commitments
import fr.acinq.lightning.channel.LocalFundingStatus
import fr.acinq.lightning.transactions.Transactions
import fr.acinq.lightning.utils.MDCLogger
import fr.acinq.lightning.utils.sat
import kotlinx.coroutines.CompletableDeferred

data class ConfirmationStatus(
    val minedAtBlockHeight: Int,
    val currentConfirmationCount: Int,
    val firstBlock: BlockHeader // includes timestamp of when block was mined
)

suspend fun IElectrumClient.getConfirmationStatus(txId: ByteVector32): ConfirmationStatus? {
    val tx = kotlin.runCatching { getTx(txId) }.getOrNull()
    return tx?.let { getConfirmationStatus(tx) }
}

/**
 * Returns information about when the tx was confirmed
 * (including the time of the first mined block)
 * if the tx has 1 or more confirmations. Returns null otherwise.
 */
suspend fun IElectrumClient.getConfirmationStatus(tx: Transaction): ConfirmationStatus? {
    val scriptHash = ElectrumClient.computeScriptHash(tx.txOut.first().publicKeyScript)
    val scriptHashHistory = getScriptHashHistory(scriptHash)
    return scriptHashHistory.find { it.txid == tx.txid }?.let { item ->
        if (item.blockHeight <= 0) {
            null
        } else {
            val replyTo = CompletableDeferred<ElectrumResponse>()
            send(GetHeader(item.blockHeight), replyTo)
            when (val res = replyTo.await()) {
                is GetHeaderResponse -> {
                    val currentBlockHeight = startHeaderSubscription().blockHeight
                    val currentConfirmationCount = currentBlockHeight - item.blockHeight + 1
                    ConfirmationStatus(
                        minedAtBlockHeight = item.blockHeight,
                        currentConfirmationCount = currentConfirmationCount,
                        firstBlock = res.header
                    )
                }
                else -> null
            }
        }
    }
}

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