package fr.acinq.eklair

import fr.acinq.eklair.crypto.Sha256
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.SendChannel
import kotlin.native.concurrent.AtomicReference
import kotlin.system.measureTimeMillis

@InternalCoroutinesApi
object Eklair {

    fun runChannel() {
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
