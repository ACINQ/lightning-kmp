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
import kotlinx.coroutines.sync.Mutex

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
    runBlocking { closure(this) }
}

private class MainDispatcher : CoroutineDispatcher() {
    override fun dispatch(context: CoroutineContext, block: Runnable) {
        dispatch_async(dispatch_get_main_queue()) { block.run() }
    }
}

internal class MainScope : CoroutineScope {
    private val dispatcher = MainDispatcher()
    private val job = Job()

    override val coroutineContext: CoroutineContext
        get() = dispatcher + job
}

fun runCoroutineStepping(
    closureStop: (String) -> String,
    closureOut: (String) -> String
) = runBlockingCoroutine {
    val mainScope = kotlinx.coroutines.MainScope()
    val context = mainScope.coroutineContext

    withContext(context) {
        val job = launch(Dispatchers.Main) {
            println("Entering count context")
            while (true) {
                coroutineStep(closureOut)
            }
        }
        val job2 = launch(Dispatchers.Unconfined) {
            println("Entering stop context")
            val closureStop1 = closureStop("??")
            println("Got stop $closureStop1")
            if (closureStop1 == "STOP") {
                throw Error()
            }
        }
    }

}

object Pumps {
    lateinit var inPump: CompletableDeferred<String>
    lateinit var outPump: CompletableDeferred<String>
    lateinit var app: Job

    var mutex = Mutex()

    fun startApp() = runBlockingCoroutine {
        withContext(Dispatchers.Default){
            Pumps.app = launch {
                while (true) {
                    println("startApp")
                    delay(1000)
                    inPump.await()
                }
            }
        }
    }

    fun startInPump(c: () -> String) = runBlockingCoroutine {
        withContext(Dispatchers.Unconfined){
            Pumps.inPump = CompletableDeferred()
            launch {
                Pumps.inPump.complete(c())
            }
        }
    }

    fun startOutPump(c: (String) -> Unit) = runBlockingCoroutine {
        withContext(Dispatchers.Default){
            Pumps.outPump = CompletableDeferred()
            /*launch {
                Pumps.outPump.complete(c())
            }*/
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

private var compteur = 0
internal suspend fun coroutineStep(
    closureOut: (String) -> String
) {
    println("Coroutines step!")
    //on fait quelque chose cote serveur
    val resultOut = closureOut("${compteur++}")
    println(">>> ${resultOut}")
//    Thread.sleep(1500)
//    platform.posix.sleep(2)
//    delayOnPlatform(1000)
    delay(1000)
}

//fun runCoroutine(closure: suspend (CoroutineScope) -> Unit){
//    runBlocking { closure(this) }
//}
