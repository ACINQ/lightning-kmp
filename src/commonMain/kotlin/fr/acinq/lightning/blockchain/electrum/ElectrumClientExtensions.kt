package fr.acinq.lightning.blockchain.electrum

import fr.acinq.bitcoin.Transaction

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

