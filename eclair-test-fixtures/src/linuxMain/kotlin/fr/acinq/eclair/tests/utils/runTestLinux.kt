package fr.acinq.eclair.tests.utils

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking

actual fun runSuspendBlocking(block: suspend CoroutineScope.() -> Unit) = runBlocking { block() }