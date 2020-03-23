package fr.acinq.eklair

import fr.acinq.eklair.crypto.Sha256
import kotlinx.coroutines.*
import kotlin.system.measureTimeMillis

data class EklairDTO(val message: String) {
    fun hashValues(): String = Hex.encode(Sha256.hash(message.encodeToByteArray()))
}

data class EklairUser(val id: String)

data class MessageContainer(val message: String, val counter: Int, val identity: EklairUser)

object MessageLogger{
    fun log(msg: MessageContainer){
        println("[${msg.message}] - ${msg.identity.id} : ${msg.counter}")
    }

    fun nativeLog(closure: () -> String){
        println(closure())
    }

    @InternalCoroutinesApi
    fun test(){
        runBlocking<Unit> {
            val counter = counterActor() // create the actor
            withContext(Dispatchers.Default) {
                massiveRun {
                    counter.send(IncCounter)
                }
            }
            // send a message to get a counter value from an actor
            val response = CompletableDeferred<Int>()
            counter.send(GetCounter(response))
            println("Counter = ${response.await()}")
            counter.close() // shutdown the actor
        }
    }

    suspend fun massiveRun(action: suspend () -> Unit) {
        val n = 100  // number of coroutines to launch
        val k = 1000 // times an action is repeated by each coroutine
        val time = measureTimeMillis {
            coroutineScope { // scope for coroutines
                repeat(n) {
                    launch {
                        repeat(k) { action() }
                    }
                }
            }
        }
        println("Completed ${n * k} actions in $time ms")
    }
}
