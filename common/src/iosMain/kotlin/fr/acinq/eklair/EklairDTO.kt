package fr.acinq.eklair

import fr.acinq.eklair.crypto.Sha256
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.SendChannel
import kotlin.native.concurrent.AtomicReference
import kotlin.system.measureTimeMillis
import kotlinx.atomicfu.*

data class EklairDTO(val message: String) {
    fun hashValues(): String = Hex.encode(Sha256.hash(message.encodeToByteArray()))
}

data class EklairUser(val id: String)

data class MessageContainer(val message: String, val counter: Int, val identity: EklairUser)


@InternalCoroutinesApi
object MessageLogger {
    fun log(msg: MessageContainer) {
        println("[${msg.message}] - ${msg.identity.id} : ${msg.counter}")
    }

    fun nativeLog(closure: () -> String) {
        println(closure())
    }

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


    fun withActor(message: (EklairActorMessage?) -> Any) {
        runBlocking<Unit> {
            println("Running actor version...")
            try {
                val actor = eklairActor()
                withContext(Dispatchers.Default) {
                    actor.send(ConnectMsg)
                    actor.send(HandshakeMsg)
                    actor.send(InitMsg)

                    var response = message(InitMsg)
                    while (true) {
                        println("⚡ response $response")
                        (response as? EklairActorMessage)?.let {
                            when (it) {
                                is Disconnect -> throw CancellationException("Disconnect")
                                is HostMsg -> {
                                    val defer = CompletableDeferred<String>()
                                    actor.send(GetHostMessage(defer))
                                    response = message(HostResponseMessage(defer.await()))
                                }
                                is EmptyMsg -> response = message(it)
                                else -> {
                                    println("⚡ sending to actor $it")
                                    actor.send(it)
                                    delay(1000)
                                    println("⚡ waiting next message...")
                                    response = message(it)
                                }
                            }
                        }
                    }
                }
            } catch (e: Throwable) {
                e.printStackTrace()
                println("End actor version...")
            }
        }
    }

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
