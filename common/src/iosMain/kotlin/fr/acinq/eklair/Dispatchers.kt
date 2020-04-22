package fr.acinq.eklair

import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.*
import kotlinx.coroutines.Dispatchers.Main

import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import platform.darwin.dispatch_queue_t
import platform.darwin.*

import platform.Foundation.*
import platform.posix.*

import kotlinx.cinterop.*
import kotlinx.coroutines.*

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

private class MainDispatcher: CoroutineDispatcher() {
    override fun dispatch(context: CoroutineContext, block: Runnable) {
        dispatch_async(dispatch_get_main_queue()) { block.run() }
    }
}

internal class MainScope: CoroutineScope {
    private val dispatcher = MainDispatcher()
    private val job = Job()

    override val coroutineContext: CoroutineContext
        get() = dispatcher + job
}

fun runCoroutineStepping() {
    MainScope().launch {
        while (true) {
            coroutineStep()
        }
    }
}

expect suspend fun delayOnPlatform(timeMillis: Long)
actual suspend fun delayOnPlatform(timeMillis: Long) {
//suspend fun delayOnPlatform(timeMillis: Long) {
    memScoped {
        val timespec = alloc<timespec>()
        timespec.tv_sec = timeMillis / 1000
        timespec.tv_nsec = ((timeMillis % 1000L) * 1000000L).convert()
        nanosleep(timespec.ptr, null)
    }
}

internal suspend fun coroutineStep() {
    println("Coroutines step!")
//    Thread.sleep(1500)
//    platform.posix.sleep(2)
    delayOnPlatform(1000)
}

//fun runCoroutine(closure: suspend (CoroutineScope) -> Unit){
//    runBlocking { closure(this) }
//}
