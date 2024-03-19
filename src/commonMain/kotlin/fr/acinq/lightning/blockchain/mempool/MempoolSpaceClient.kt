package fr.acinq.lightning.blockchain.mempool

import fr.acinq.bitcoin.Transaction
import fr.acinq.bitcoin.TxId
import fr.acinq.lightning.blockchain.IClient
import fr.acinq.lightning.blockchain.fee.FeeratePerKw
import fr.acinq.lightning.logging.LoggerFactory
import fr.acinq.lightning.logging.debug
import fr.acinq.lightning.logging.warning
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

class MempoolSpaceClient(val mempoolHost: String, loggerFactory: LoggerFactory) : IClient {

    private val logger = loggerFactory.newLogger(this::class)

    @OptIn(ExperimentalSerializationApi::class)
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
                protocol = URLProtocol.HTTPS
                host = mempoolHost
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

    suspend fun getTransaction(txId: TxId): Transaction? {
        return kotlin.runCatching {
            val res = client.get("/api/tx/$txId/hex")
            if (res.status.isSuccess()) {
                Transaction.read(res.bodyAsText())
            } else null
        }.onFailure { logger.warning(it) { "error in getTransaction " } }
            .getOrNull()
    }

    suspend fun getTransactionMerkleProof(txId: TxId): MempoolSpaceTransactionMerkleProofResponse? {
        return kotlin.runCatching {
            val res = client.get("/api/tx/$txId/merkle-proof")
            if (res.status.isSuccess()) {
                val txStatus: MempoolSpaceTransactionMerkleProofResponse = res.body()
                txStatus
            } else null
        }.onFailure { logger.warning(it) { "error in getTransactionMerkleProof " } }
            .getOrNull()
    }

    suspend fun getOutspend(txId: TxId, outputIndex: Int): Transaction? {
        return kotlin.runCatching {
            logger.debug { "checking output $txId:$outputIndex" }
            val res: MempoolSpaceOutspendResponse = client.get("/api/tx/$txId/outspend/$outputIndex").body()
            res.txid?.let { getTransaction(TxId(it)) }
        }.onFailure { logger.warning(it) { "error in getOutspend " } }
            .getOrNull()
    }

    suspend fun getBlockTipHeight(): Int? {
        return kotlin.runCatching {
            val res = client.get("/api/blocks/tip/height")
            if (res.status.isSuccess()) {
                res.bodyAsText().toInt()
            } else null
        }.onFailure { logger.warning(it) { "error in getBlockTipHeight " } }
            .getOrNull()
    }

    override suspend fun getConfirmations(txId: TxId): Int? {
        return kotlin.runCatching {
            val confirmedAtBlockHeight = getTransactionMerkleProof(txId)?.block_height
            val currentBlockHeight = getBlockTipHeight()
            when {
                confirmedAtBlockHeight != null && currentBlockHeight != null -> currentBlockHeight - confirmedAtBlockHeight + 1
                else -> null
            }
        }.onFailure { logger.warning(it) { "error in getConfirmations" } }
            .getOrNull()
    }

    override suspend fun estimateFees(confirmations: Int): FeeratePerKw? {
        TODO("Not yet implemented")
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