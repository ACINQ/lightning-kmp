package fr.acinq.lightning.tests.bitcoind

import fr.acinq.lightning.logging.*
import fr.acinq.lightning.tests.utils.testLoggerFactory
import fr.acinq.lightning.utils.JsonRPCResponse
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.auth.*
import io.ktor.client.plugins.auth.providers.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*


object BitcoinJsonRPCClient {
    private const val user: String = "foo"
    private const val pwd: String = "bar"
    private const val host: String = "127.0.0.1"
    private const val port: Int = 18443
    private const val ssl: Boolean = false

    private val scheme = if (ssl) "https" else "http"
    private val serviceUri = "$scheme://$host:$port/wallet/" // wallet/ specifies to use the default bitcoind wallet, named ""

    private val httpClient = HttpClient {
        install(ContentNegotiation) {
            json()
        }
        install(Auth) {
            basic {
                credentials { BasicAuthCredentials(user, pwd) }
            }
        }
    }

    private val logger = testLoggerFactory.newLogger(this::class)

    suspend fun <T : BitcoindResponse> sendRequest(request: BitcoindRequest): T {
        val rpcResponse: JsonRPCResponse = httpClient.post(Url(serviceUri)) {
            logger.debug { "Send bitcoind command: ${request.asJsonRPCRequest()}" }
            setBody(request.asJsonRPCRequest())
        }.body()
        logger.debug { "Receive bitcoind response: $rpcResponse" }
        @Suppress("UNCHECKED_CAST")
        return request.parseJsonResponse(rpcResponse) as T
    }
}

