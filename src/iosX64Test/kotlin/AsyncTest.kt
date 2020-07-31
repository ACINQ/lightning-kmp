import platform.darwin.*
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlin.test.Test


@OptIn(ExperimentalUnsignedTypes::class)
class AsyncTest {

    suspend fun suspend_dispatch(ms: ULong): Unit = suspendCoroutine { continuation ->
        dispatch_after(dispatch_time(DISPATCH_TIME_NOW, (ms * NSEC_PER_MSEC).toLong()), dispatch_get_current_queue()) {
            continuation.resume(Unit)
        }
    }

    @Test fun test() = runTest {
        println("Before")
        suspend_dispatch(500uL)
//        error("Go to hell!")
        println("After")
    }

}
