package fr.acinq.eklair

import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.*
import kotlinx.coroutines.Dispatchers.Main

import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import platform.darwin.dispatch_queue_t

internal val Main: CoroutineDispatcher = NsQueueDispatcher(dispatch_get_main_queue())
internal val Background: CoroutineDispatcher = Main

internal class NsQueueDispatcher(private val dispatchQueue: dispatch_queue_t) : CoroutineDispatcher() {

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        dispatch_async(dispatchQueue) {
            block.run()
        }
    }
}

fun runBlockingCoroutine(closure: suspend (CoroutineScope) -> Unit) {
    runBlocking{ closure(this) }
}
