package fr.acinq.eclair.tests.bitcoind

import fr.acinq.eclair.utils.JsonRPCResponse
import fr.acinq.eclair.utils.newEclairLogger
import io.ktor.client.*
import io.ktor.client.features.auth.*
import io.ktor.client.features.auth.providers.*
import io.ktor.client.features.json.*
import io.ktor.client.features.json.serializer.*
import io.ktor.client.request.*


object BitcoinJsonRPCClient {
    private const val user: String = "foo"
    private const val pwd: String = "bar"
    private const val host: String = "127.0.0.1"
    private const val port: Int = 18443
    private const val ssl: Boolean = false

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

    private val logger by newEclairLogger()

    suspend fun <T : BitcoindResponse> sendRequest(request: BitcoindRequest): T {
        val rpcResponse = httpClient.post<JsonRPCResponse>(serviceUri) {
            logger.debug { "Send bitcoind command: ${request.asJsonRPCRequest()}" }
            body = request.asJsonRPCRequest()
        }
        logger.debug { "Receive bitcoind response: $rpcResponse" }
        @Suppress("UNCHECKED_CAST")
        return request.parseJsonResponse(rpcResponse) as T
    }
}

