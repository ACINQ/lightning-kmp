package fr.acinq.lightning.tests.utils

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * This defers to kotlin's [kotlinx.coroutines.runBlocking] on all platforms except Apple targets, which need custom code:
 * NativeSocket delivers its callbacks on the main GCD queue, so the test body runs on the main dispatcher while the test
 * runner pumps the main run loop (a plain runBlocking would park the main thread and the callbacks would never fire).
 */
expect fun runSuspendBlocking(block: suspend CoroutineScope.() -> Unit)

fun runSuspendTest(timeout: Duration = 30.seconds, test: suspend CoroutineScope.() -> Unit) {
    runSuspendBlocking {
        withTimeout(timeout) {
            launch {
                test()
                cancel()
            }.join()
        }
    }
}
