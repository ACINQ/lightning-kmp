package fr.acinq.lightning.io

import io.ktor.client.*

// This allows setting platform and engine-specific parameters for the client.
// It is particularly useful as a workaround for https://github.com/ktorio/ktor/pull/4758
expect fun MyHttpClient(
    block: HttpClientConfig<*>.() -> Unit = {}
): HttpClient