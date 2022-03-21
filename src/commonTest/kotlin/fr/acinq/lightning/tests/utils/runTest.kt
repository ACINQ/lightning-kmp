package fr.acinq.lightning.tests.utils

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration
import kotlin.time.ExperimentalTime
import kotlin.time.seconds

expect fun runSuspendBlocking(block: suspend CoroutineScope.() -> Unit)

@OptIn(ExperimentalTime::class)
fun runSuspendTest(timeout: Duration = Duration.seconds(30), test: suspend CoroutineScope.() -> Unit) {
    runSuspendBlocking {
        withTimeout(timeout) {
            launch {
                test()
                cancel()
            }.join()
        }
    }
}
