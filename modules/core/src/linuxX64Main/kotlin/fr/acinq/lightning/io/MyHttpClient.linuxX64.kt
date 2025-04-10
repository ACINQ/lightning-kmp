package fr.acinq.lightning.io

import io.ktor.client.*

actual fun MyHttpClient(block: HttpClientConfig<*>.() -> Unit): HttpClient = HttpClient(block)