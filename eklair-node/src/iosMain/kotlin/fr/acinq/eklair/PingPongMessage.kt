package fr.acinq.eklair

import kotlinx.coroutines.*

sealed class PingPongMessage
object PingMessage: PingPongMessage()
object PongMessage : PingPongMessage()

class PingPongActor(var pingCount: Int = 0, var pongCount: Int = 0)

@InternalCoroutinesApi
fun CoroutineScope.pingPongActor() = actor<PingPongMessage>(capacity = 5){
    var state = PingPongActor()
    for (msg in channel){
        when (msg){
            is PingMessage -> {
                println("\uD83C\uDFD3 Received ping ! ${state.pingCount}")
                state.pingCount++
                delay(100)
            }
            is PongMessage -> {
                println("\uD83C\uDFD3 Received pong ! ${state.pongCount}")
                state.pongCount++
                delay(2000)
                if (state.pongCount == 100){
                    channel.close()
                }
            }
        }
    }
}
