package fr.acinq.eklair.utils

import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import platform.Foundation.NSDate
import platform.Foundation.NSRunLoop
import platform.Foundation.dateWithTimeIntervalSinceNow
import platform.Foundation.runUntilDate

actual fun runTest(block: suspend CoroutineScope.() -> Unit) {
    var result: Result<Unit>? = null

    IosMainScope().launch {
        result = kotlin.runCatching { block() }
    }

    while (result == null) NSRunLoop.mainRunLoop.runUntilDate(NSDate.dateWithTimeIntervalSinceNow(0.01))

    result!!.getOrThrow()
}