package fr.acinq.eklair

import fr.acinq.eklair.SocketBuilder.runBlockingCoroutine

import kotlinx.cinterop.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.*
import kotlinx.coroutines.Dispatchers.Main
import kotlin.coroutines.CoroutineContext

import kotlin.native.concurrent.*
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import platform.darwin.dispatch_queue_t
import platform.darwin.*

import platform.Foundation.*
import platform.posix.*


fun runCoroutineStepping(
    closureStop: (String) -> String,
    closureOut: (String) -> String
) = runBlockingCoroutine {
    try {
        withContext(Dispatchers.Unconfined) mainContext@{

            val job = launch(Dispatchers.Main)/*(newSingleThreadContext("background"))*/ {
                println("Entering count context")
                while (true) {
                    coroutineStep(closureOut)
                }
            }
            val job2 = launch {
                println("Entering stop context")
                while (true) {
                    val closureStop1 = closureStop("??")
                    println("Got stop $closureStop1")
                    if (closureStop1 == "STOP") {
                        this@mainContext.cancel("End execution")
                    } else {
                        closureStop1.toIntOrNull(10)?.let {
                            pauseDuration = it
                            println("Changed pause duration to $it")
                        }
                    }
                }
            }
        }
    } catch (e: CancellationException) {
        println("Exiting execution due to cancellation")
    }

}

expect suspend fun delayOnPlatform(timeMillis: Long)
actual suspend fun delayOnPlatform(timeMillis: Long) {
    memScoped {
        val timespec = alloc<timespec>()
        timespec.tv_sec = timeMillis / 1000
        timespec.tv_nsec = ((timeMillis % 1000L) * 1000000L).convert()
        nanosleep(timespec.ptr, null)
    }
}

private var compteur = 0
private var pauseDuration = 5000
internal suspend fun coroutineStep(
    closureOut: (String) -> String
) {
    println("Coroutines step!")
    //on fait quelque chose cote serveur
    val resultOut = closureOut("${compteur++}")
    println(">>> ${resultOut}")
    delay(pauseDuration.toLong())
}
