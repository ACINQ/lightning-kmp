package fr.acinq.lightning.io

import io.ktor.client.*
import io.ktor.client.engine.curl.*

actual fun MyHttpClient(block: HttpClientConfig<*>.() -> Unit): HttpClient = HttpClient {
    engine {
        // Workaround for https://github.com/ktorio/ktor/pull/4758
        if (this is CurlClientEngineConfig) {
            caPath = "/etc/ssl/certs"
        }
    }
    block(this)
}