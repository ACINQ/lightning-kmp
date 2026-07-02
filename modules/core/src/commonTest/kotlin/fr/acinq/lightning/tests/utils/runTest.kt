package fr.acinq.lightning.tests.utils

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.fail
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

fun runSuspendTest(timeout: Duration = 30.seconds, test: suspend CoroutineScope.() -> Unit) = runBlocking {
    val testJob = launch {
        test()
        // the test body has returned: cancel whatever it left running in its scope,
        // otherwise join() below would wait for those coroutines forever
        cancel()
    }
    try {
        withTimeout(timeout) { testJob.join() }
    } catch (e: TimeoutCancellationException) {
        testJob.cancel()
        // wait for the test's coroutines to complete their cancellation cleanup: anything
        // they print after the test is reported finished confuses gradle's test runner
        testJob.join()
        fail("test did not complete within $timeout")
    }
}
