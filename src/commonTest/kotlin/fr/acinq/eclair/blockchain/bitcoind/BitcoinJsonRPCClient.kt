package fr.acinq.eclair.blockchain.bitcoind

import fr.acinq.bitcoin.Base58
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.bitcoin.Transaction
import fr.acinq.eclair.utils.JsonRPCRequest
import fr.acinq.eclair.utils.JsonRPCResponse
import fr.acinq.eclair.utils.asJsonRPCParameters
import io.ktor.client.*
import io.ktor.client.features.auth.*
import io.ktor.client.features.auth.providers.*
import io.ktor.client.features.json.*
import io.ktor.client.features.json.serializer.*
import io.ktor.client.request.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.kodein.log.LoggerFactory
import org.kodein.log.frontend.simplePrintFrontend
import org.kodein.log.newLogger

class BitcoinJsonRPCClient(
    private val user: String = "foo",
    private val pwd: String = "bar",
    private val host: String = "127.0.0.1",
    private val port: Int = 18443,
    private val ssl: Boolean = false
) {

    private val scheme = if (ssl) "https" else "http"
    private val serviceUri = "$scheme://$host:$port/wallet/" // wallet/ specifies to use the default bitcoind wallet, named ""

    private val httpClient = HttpClient {
//        expectSuccess = false
        install(JsonFeature) {
            serializer = KotlinxSerializer()
        }
        install(Auth) {
            basic {
                username = user
                password = pwd
            }
        }
    }

    suspend fun <T : BitcoindResponse> sendRequest(request: BitcoindRequest): T {
        val rpcResponse = httpClient.post<JsonRPCResponse>(serviceUri) {
            logger.info { "Send bitcoind command: ${request.asJsonRPCRequest()}" }
            body = request.asJsonRPCRequest()
        }
        logger.info { "Receive bitcoind response: $rpcResponse" }
        @Suppress("UNCHECKED_CAST")
        return request.parseJsonResponse(rpcResponse) as T
    }

    companion object {
        private val logger = LoggerFactory(simplePrintFrontend).newLogger<BitcoinJsonRPCClient>()
    }
}

sealed class BitcoindRequest(vararg params: Any) {
    abstract val method: String
    private val parameters = params.toList()

    fun parseJsonResponse(rpcResponse: JsonRPCResponse): BitcoindResponse =
        if (rpcResponse.error != null) throw UnsupportedOperationException("${rpcResponse.error}")
        else parseResponse(rpcResponse)

    protected abstract fun parseResponse(rpcResponse: JsonRPCResponse): BitcoindResponse

    fun asJsonRPCRequest(id: Int = 0): String =
        JsonRPCRequest(
            id = id,
            method = method,
            params = parameters.asJsonRPCParameters()
        ).let { Json.encodeToString(JsonRPCRequest.serializer(), it) }
}
sealed class BitcoindResponse
object GetNetworkInfo : BitcoindRequest() {
    override val method: String = "getnetworkinfo"
    override fun parseResponse(rpcResponse: JsonRPCResponse): GetNetworkInfoResponse =
        GetNetworkInfoResponse(rpcResponse.result)
}
data class GetNetworkInfoResponse(val result: JsonElement) : BitcoindResponse()
object GetNewAddress : BitcoindRequest() {
    override val method: String = "getnewaddress"
    override fun parseResponse(rpcResponse: JsonRPCResponse): GetNewAddressResponse =
        GetNewAddressResponse(rpcResponse.result.jsonPrimitive.content)
}
data class GetNewAddressResponse(val address: String) : BitcoindResponse()
data class GenerateToAddress(val blockCount: Int, val address: String) : BitcoindRequest(blockCount, address) {
    override val method: String = "generatetoaddress"
    override fun parseResponse(rpcResponse: JsonRPCResponse): BitcoindResponse =
        GenerateToAddressResponse(rpcResponse.result.jsonArray.map { it.jsonPrimitive.content })
}
data class GenerateToAddressResponse(val blocks: List<String>) : BitcoindResponse()

data class DumpPrivateKey(val address: String) : BitcoindRequest(address) {
    override val method: String = "dumpprivkey"

    override fun parseResponse(rpcResponse: JsonRPCResponse): DumpPrivateKeyResponse {
        val wif = rpcResponse.result.jsonPrimitive.content
        val (privateKey, _) = PrivateKey.fromBase58(wif, Base58.Prefix.SecretKeyTestnet)

        return DumpPrivateKeyResponse(privateKey)
    }
}
data class DumpPrivateKeyResponse(val privateKey: PrivateKey) : BitcoindResponse()

data class SendToAddress(val address: String, val amount: Double) : BitcoindRequest(address, amount) {
    override val method: String = "sendtoaddress"
    override fun parseResponse(rpcResponse: JsonRPCResponse): SendToAddressResponse =
        SendToAddressResponse(rpcResponse.result.jsonPrimitive.content)
}
data class SendToAddressResponse(val txid: String) : BitcoindResponse()

data class GetRawTransaction(val txid: String) : BitcoindRequest(txid) {
    override val method: String = "getrawtransaction"
    override fun parseResponse(rpcResponse: JsonRPCResponse): GetRawTransactionResponse =
        GetRawTransactionResponse(
            Transaction.read(rpcResponse.result.jsonPrimitive.content)
        )
}
data class GetRawTransactionResponse(val tx: Transaction) : BitcoindResponse()
data class SendRawTransaction(val tx: Transaction) : BitcoindRequest(tx.toString()) {
    override val method: String = "sendrawtransaction"
    override fun parseResponse(rpcResponse: JsonRPCResponse): SendRawTransactionResponse =
        SendRawTransactionResponse(rpcResponse.result.jsonPrimitive.content)
}
data class SendRawTransactionResponse(val txid: String) : BitcoindResponse()