package fr.acinq.lightning.tests.utils

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import platform.Foundation.NSDate
import platform.Foundation.NSRunLoop
import platform.Foundation.dateWithTimeIntervalSinceNow
import platform.Foundation.runUntilDate

actual fun runSuspendBlocking(block: suspend CoroutineScope.() -> Unit) {
    var result: Result<Unit>? = null

    MainScope().launch {
        result = kotlin.runCatching { block() }
    }

    while (result == null) NSRunLoop.mainRunLoop.runUntilDate(NSDate.dateWithTimeIntervalSinceNow(0.01))

    result!!.getOrThrow()

}
