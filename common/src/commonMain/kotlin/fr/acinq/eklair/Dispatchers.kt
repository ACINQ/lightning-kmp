package fr.acinq.eklair

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope

internal expect val Main: CoroutineDispatcher
internal expect val Background: CoroutineDispatcher

expect fun runBlockingCoroutine(closure: suspend (CoroutineScope) -> Unit)
