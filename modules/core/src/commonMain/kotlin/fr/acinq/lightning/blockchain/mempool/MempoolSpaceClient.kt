package fr.acinq.lightning.blockchain.mempool

import fr.acinq.bitcoin.Transaction
import fr.acinq.bitcoin.TxId
import fr.acinq.lightning.blockchain.Feerates
import fr.acinq.lightning.blockchain.IClient
import fr.acinq.lightning.blockchain.fee.FeeratePerByte
import fr.acinq.lightning.logging.LoggerFactory
import fr.acinq.lightning.logging.debug
import fr.acinq.lightning.logging.warning
import fr.acinq.lightning.utils.sat
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

class MempoolSpaceClient(val mempoolUrl: Url, loggerFactory: LoggerFactory) : IClient {

    private val logger = loggerFactory.newLogger(this::class)

    val client = HttpClient {
        install(ContentNegotiation) {
            json(json = Json {
                prettyPrint = true
                isLenient = true
                explicitNulls = false // convert absent fields to null
                ignoreUnknownKeys = true
            })
        }
        install(DefaultRequest) {
            url {
                takeFrom(mempoolUrl)
            }
        }
    }

    suspend fun publish(tx: Transaction) {
        val res = client.post("api/tx") {
            contentType(ContentType.Text.Plain)
            setBody(tx.toString())
        }
        if (!res.status.isSuccess()) {
            logger.warning { "tx publish failed for txid=${tx.txid}: ${res.bodyAsText()}" }
        }
    }

    /** Helper method to factor error handling and logging for api calls. */
    private suspend fun <T> tryWithLogs(f: suspend () -> T?): T? {
        return kotlin.runCatching { f() }
            .onFailure { logger.warning(it) { "mempool.space api error: " } }
            .getOrNull()
    }

    suspend fun getTransaction(txId: TxId): Transaction? = tryWithLogs {
        val res = client.get("api/tx/$txId/hex")
        if (res.status.isSuccess()) {
            Transaction.read(res.bodyAsText())
        } else null
    }

    /**
     * Returns a merkle inclusion proof for the transaction using Electrum's blockchain.transaction.get_merkle
     * format.
     * */
    suspend fun getTransactionMerkleProof(txId: TxId): MempoolSpaceTransactionMerkleProofResponse? = tryWithLogs {
        val res = client.get("api/tx/$txId/merkle-proof")
        if (res.status.isSuccess()) {
            val txStatus: MempoolSpaceTransactionMerkleProofResponse = res.body()
            txStatus
        } else null
    }

    /** Returns the spending status of a transaction output. */
    suspend fun getOutspend(txId: TxId, outputIndex: Int): Transaction? = tryWithLogs {
        logger.debug { "checking output $txId:$outputIndex" }
        val res: MempoolSpaceOutspendResponse = client.get("api/tx/$txId/outspend/$outputIndex").body()
        res.txid?.let { getTransaction(TxId(it)) }
    }

    /** Returns the height of the last block. */
    suspend fun getBlockTipHeight(): Int? = tryWithLogs {
        val res = client.get("api/blocks/tip/height")
        if (res.status.isSuccess()) {
            res.bodyAsText().toInt()
        } else null
    }

    override suspend fun getConfirmations(txId: TxId): Int? = tryWithLogs {
        val confirmedAtBlockHeight = getTransactionMerkleProof(txId)?.block_height
        val currentBlockHeight = getBlockTipHeight()
        when {
            confirmedAtBlockHeight != null && currentBlockHeight != null -> currentBlockHeight - confirmedAtBlockHeight + 1
            else -> null
        }
    }

    override suspend fun getFeerates(): Feerates? = tryWithLogs {
        val res: MempoolSpaceRecommendedFeerates = client.get("api/v1/fees/recommended").body()
        Feerates(
            minimum = FeeratePerByte(res.minimumFee.sat),
            slow = FeeratePerByte(res.economyFee.sat),
            medium = FeeratePerByte(res.hourFee.sat),
            fast = FeeratePerByte(res.halfHourFee.sat),
            fastest = FeeratePerByte(res.fastestFee.sat),
        )
    }

    companion object {
        val OfficialMempoolMainnet = Url("https://mempool.space")
        val OfficialMempoolTestnet3 = Url("https://mempool.space/testnet/")
        val OfficialMempoolTestnet4 = Url("https://mempool.space/testnet4/")
    }
}

@Serializable
data class MempoolSpaceOutspendResponse(
    val spent: Boolean,
    val txid: String?,
    val vin: Int?,
)

@Serializable
data class MempoolSpaceTransactionMerkleProofResponse(
    val block_height: Int,
    val pos: Int
)

@Serializable
data class MempoolSpaceRecommendedFeerates(
    val fastestFee: Int,
    val halfHourFee: Int,
    val hourFee: Int,
    val economyFee: Int,
    val minimumFee: Int
)