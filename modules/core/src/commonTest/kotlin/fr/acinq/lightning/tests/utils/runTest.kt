package fr.acinq.lightning.tests.utils

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.fail
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

fun runSuspendTest(timeout: Duration = 30.seconds, test: suspend CoroutineScope.() -> Unit) = runBlocking {
    // withTimeoutOrNull only swallows its own timeout: an exception thrown by the test body — including a
    // TimeoutCancellationException from a withTimeout inside the test — propagates and fails the test
    withTimeoutOrNull(timeout) {
        test()
        // the test body has returned: cancel whatever it left running in its scope, otherwise this scope
        // would never complete; withTimeoutOrNull only resumes once those coroutines have finished their
        // cancellation cleanup, so nothing they print can be misattributed by gradle's test runner
        coroutineContext.cancelChildren()
    } ?: fail("test did not complete within $timeout")
}
