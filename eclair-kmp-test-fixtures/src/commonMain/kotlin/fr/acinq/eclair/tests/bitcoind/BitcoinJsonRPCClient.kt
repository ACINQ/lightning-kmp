package fr.acinq.eclair.tests.bitcoind

import fr.acinq.eclair.utils.JsonRPCResponse
import io.ktor.client.*
import io.ktor.client.features.auth.*
import io.ktor.client.features.auth.providers.*
import io.ktor.client.features.json.*
import io.ktor.client.features.json.serializer.*
import io.ktor.client.request.*
import org.kodein.log.Logger
import org.kodein.log.LoggerFactory


class BitcoinJsonRPCClient(
    private val user: String = "foo",
    private val pwd: String = "bar",
    host: String = "127.0.0.1",
    port: Int = 18443,
    ssl: Boolean = false
) {

    private val scheme = if (ssl) "https" else "http"
    private val serviceUri = "$scheme://$host:$port/wallet/" // wallet/ specifies to use the default bitcoind wallet, named ""

    private val httpClient = HttpClient {
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
            logger.verbose { "Send bitcoind command: ${request.asJsonRPCRequest()}" }
            body = request.asJsonRPCRequest()
        }
        logger.verbose { "Receive bitcoind response: $rpcResponse" }
        @Suppress("UNCHECKED_CAST")
        return request.parseJsonResponse(rpcResponse) as T
    }

    companion object {
        private val logger = LoggerFactory.default.newLogger(Logger.Tag(BitcoinJsonRPCClient::class))
    }
}

