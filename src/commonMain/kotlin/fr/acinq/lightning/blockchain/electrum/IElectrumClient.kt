package fr.acinq.lightning.blockchain.electrum

import fr.acinq.bitcoin.BlockHeader
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.Transaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

interface IElectrumClient {
    val notifications: Flow<ElectrumSubscriptionResponse>
    val connectionStatus: StateFlow<ElectrumConnectionStatus>

    suspend fun getTx(txid: ByteVector32): Transaction

    suspend fun getHeader(blockHeight: Int): BlockHeader

    suspend fun getHeaders(startHeight: Int, count: Int): List<BlockHeader>

    suspend fun getMerkle(txid: ByteVector32, blockHeight: Int, contextOpt: Transaction? = null): GetMerkleResponse

    suspend fun getScriptHashHistory(scriptHash: ByteVector32): List<TransactionHistoryItem>

    suspend fun getScriptHashUnspents(scriptHash: ByteVector32): List<UnspentItem>

    suspend fun startScriptHashSubscription(scriptHash: ByteVector32): ScriptHashSubscriptionResponse

    suspend fun startHeaderSubscription(): HeaderSubscriptionResponse

    suspend fun broadcastTransaction(tx: Transaction): BroadcastTransactionResponse

    suspend fun estimateFees(confirmations: Int): EstimateFeeResponse
}