package fr.acinq.eklair.blockchain.bitcoind

import fr.acinq.bitcoin.*
import fr.acinq.eklair.blockchain.electrum.ServerError
import fr.acinq.eklair.utils.*
import io.ktor.client.*
import io.ktor.client.features.auth.*
import io.ktor.client.features.auth.providers.*
import io.ktor.client.features.json.*
import io.ktor.client.features.json.serializer.*
import io.ktor.client.request.*
import kotlinx.serialization.UnstableDefault
import kotlinx.serialization.json.*
import org.kodein.log.LoggerFactory
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
        expectSuccess = false
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

    @OptIn(UnstableDefault::class)
    suspend fun <T : BitcoindResponse> sendRequest(request: BitcoindRequest): T {
        val rpcResponse = httpClient.post<JsonRPCResponse>(serviceUri) {
            logger.info { "Send bitcoind command: ${request.asJsonRPCRequest()}" }
            body = request.asJsonRPCRequest()
        }

        logger.info { "Receive bitcoind response: $rpcResponse" }
        return request.parseJsonResponse(rpcResponse) as T
    }

    companion object {
        private val logger = LoggerFactory.default.newLogger(BitcoinJsonRPCClient::class)
    }
}

sealed class BitcoindRequest(vararg params: Any) {
    abstract val method: String
    private val parameters = params.toList()

    fun parseJsonResponse(rpcResponse: JsonRPCResponse): BitcoindResponse =
        if (rpcResponse.error != null) throw UnsupportedOperationException("${rpcResponse.error}")
        else parseResponse(rpcResponse)

    protected abstract fun parseResponse(rpcResponse: JsonRPCResponse): BitcoindResponse

    @OptIn(UnstableDefault::class)
    fun asJsonRPCRequest(id: Int = 0): String =
        JsonRPCRequest(
            id = id,
            method = method,
            params = parameters.asJsonRPCParameters()
        ).let { Json.stringify(JsonRPCRequest.serializer(), it) }
}
sealed class BitcoindResponse

object GetNewAddress : BitcoindRequest() {
    override val method: String = "getnewaddress"
    override fun parseResponse(rpcResponse: JsonRPCResponse): GetNewAddressResponse =
        GetNewAddressResponse(rpcResponse.result.content)
}
data class GetNewAddressResponse(val address: String) : BitcoindResponse()
data class GenerateToAddress(val blockCount: Int, val address: String) : BitcoindRequest(blockCount, address) {
    override val method: String = "generatetoaddress"
    override fun parseResponse(rpcResponse: JsonRPCResponse): BitcoindResponse =
        GenerateToAddressReponse(rpcResponse.result.jsonArray.map { it.content })
}
data class GenerateToAddressReponse(val blocks: List<String>) : BitcoindResponse()

data class DumpPrivateKey(val address: String) : BitcoindRequest(address) {
    override val method: String = "dumpprivkey"

    override fun parseResponse(rpcResponse: JsonRPCResponse): DumpPrivateKeyResponse {
        val wif = rpcResponse.result.content
        val (privateKey, _) = PrivateKey.fromBase58(wif, Base58.Prefix.SecretKeyTestnet)

        return DumpPrivateKeyResponse(privateKey)
    }
}
data class DumpPrivateKeyResponse(val privateKey: PrivateKey) : BitcoindResponse()

data class SendToAddress(val address: String, val amount: Double) : BitcoindRequest(address, amount) {
    override val method: String = "sendtoaddress"
    override fun parseResponse(rpcResponse: JsonRPCResponse): SendToAddressResponse =
        SendToAddressResponse(rpcResponse.result.content)
}
data class SendToAddressResponse(val txid: String) : BitcoindResponse()

data class GetRawTransaction(val txid: String) : BitcoindRequest(txid) {
    override val method: String = "getrawtransaction"
    override fun parseResponse(rpcResponse: JsonRPCResponse): GetRawTransactionResponse =
        GetRawTransactionResponse(
            Transaction.Companion.read(rpcResponse.result.content)
        )
}
data class GetRawTransactionResponse(val tx: Transaction) : BitcoindResponse()