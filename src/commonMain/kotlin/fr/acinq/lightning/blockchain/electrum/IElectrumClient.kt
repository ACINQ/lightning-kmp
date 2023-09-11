package fr.acinq.lightning.blockchain.electrum

import fr.acinq.bitcoin.BlockHeader
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.Transaction
import fr.acinq.lightning.blockchain.fee.FeeratePerKw
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** Note to implementers: methods exposed through this interface must *not* throw exceptions. */
interface IElectrumClient {
    val notifications: Flow<ElectrumSubscriptionResponse>
    val connectionStatus: StateFlow<ElectrumConnectionStatus>

    /** Return the transaction matching the txId provided, if it can be found. */
    suspend fun getTx(txid: ByteVector32): Transaction?

    /** Return the block header at the given height, if it exists. */
    suspend fun getHeader(blockHeight: Int): BlockHeader?

    /** Return the block headers starting at the given height, if they exist (empty list otherwise). */
    suspend fun getHeaders(startHeight: Int, count: Int): List<BlockHeader>

    /** Return a merkle proof for the given transaction, if it can be found. */
    suspend fun getMerkle(txid: ByteVector32, blockHeight: Int, contextOpt: Transaction? = null): GetMerkleResponse?

    /** Return the transaction history for a given script, or an empty list if the script is unknown. */
    suspend fun getScriptHashHistory(scriptHash: ByteVector32): List<TransactionHistoryItem>

    /** Return the utxos matching a given script, or an empty list if the script is unknown. */
    suspend fun getScriptHashUnspents(scriptHash: ByteVector32): List<UnspentItem>

    /**
     * Try broadcasting a transaction: we cannot know whether the remote server really broadcast the transaction,
     * so we always consider it to be a success. The client should regularly retry transactions that don't confirm.
     */
    suspend fun broadcastTransaction(tx: Transaction): ByteVector32

    /** Estimate the feerate required for a transaction to be confirmed in the next [confirmations] blocks. */
    suspend fun estimateFees(confirmations: Int): FeeratePerKw?

    /******************** Subscriptions ********************/

    /** Subscribe to changes to a given script. */
    suspend fun startScriptHashSubscription(scriptHash: ByteVector32): ScriptHashSubscriptionResponse

    /** Subscribe to headers for new blocks found. */
    suspend fun startHeaderSubscription(): HeaderSubscriptionResponse
}