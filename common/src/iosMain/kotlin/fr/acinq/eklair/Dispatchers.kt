package fr.acinq.eklair

import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.*

import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import platform.darwin.dispatch_queue_t

internal actual val Main: CoroutineDispatcher = NsQueueDispatcher(dispatch_get_main_queue())
internal actual val Background: CoroutineDispatcher = Main

internal class NsQueueDispatcher(private val dispatchQueue: dispatch_queue_t) : CoroutineDispatcher() {

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        dispatch_async(dispatchQueue) {
            block.run()
        }
    }
}

actual fun runBlockingCoroutine(closure: suspend (CoroutineScope) -> Unit) {
    runBlocking{ closure(this) }
}
