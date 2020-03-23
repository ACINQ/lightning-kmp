package fr.acinq.eklair

import fr.acinq.eklair.crypto.Sha256
import kotlinx.coroutines.*
import kotlin.system.measureTimeMillis

data class EklairDTO(val message: String) {
    fun hashValues(): String = Hex.encode(Sha256.hash(message.encodeToByteArray()))
}

data class EklairUser(val id: String)

data class MessageContainer(val message: String, val counter: Int, val identity: EklairUser)

object MessageLogger {
    fun log(msg: MessageContainer) {
        println("[${msg.message}] - ${msg.identity.id} : ${msg.counter}")
    }

    fun nativeLog(closure: () -> String) {
        println(closure())
    }

    @InternalCoroutinesApi
    fun test() {
        runBlocking<Unit> {
            println("Running ping pong actor version...")
            try {
                val pinger = pingPongActor()
                withContext(Dispatchers.Default) {
                    try {
                        while (true) {
                            pinger.send(PingMessage)
                            pinger.send(PongMessage)
                        }
                    } catch (e: Exception) {
                        println("\uD83C\uDFD3 Ended")
                    }
                    cancel()
                }
            } catch (e: Throwable) {
                println("End ping pong actor version...")
            }
        }
    }

    @InternalCoroutinesApi
    fun withActor(onConnected: (String) -> Unit) {
        runBlocking<Unit> {
            println("Running actor version...")
            try {
                val eklairActor = eklairActor()
                withContext(Dispatchers.Default) {
                    eklairActor.send(ConnectMsg)
                    eklairActor.send(HandshakeMsg)
                    eklairActor.send(InitMsg)
                    val hostResponse = CompletableDeferred<String>()
                    eklairActor.send(GetHostMessage(hostResponse))
                    onConnected(hostResponse.await())
                    repeat(10) {
                        eklairActor.send(PingMsg)
                    }
                    cancel()
                }
            } catch (e: Throwable) {
                println("End actor version...")
            }
        }
    }

    @InternalCoroutinesApi
    fun channel() {
        println("Running channel version...")
        runBlocking<Unit>() {
            try {
                val connection = doConnect()
                val handshake = doHandshake(connection)
                val sessionInit = doCreateSession(handshake)
                for (init in sessionInit) {
                    repeat(10) {
                        doPing(init.session)
                        delay(2000)
                    }
                    cancel()
                }
            } catch (e: Throwable) {
                println("End channel version...")
            }
        }
    }
}
