package fr.acinq.eclair.utils

import kotlinx.coroutines.CoroutineScope

expect fun runTest(block: suspend CoroutineScope.() -> Unit)