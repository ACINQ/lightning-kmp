package fr.acinq.eklair

import io.ktor.network.selector.ActorSelectorManager
import io.ktor.network.sockets.aSocket
import io.ktor.util.KtorExperimentalAPI
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress

@ExperimentalStdlibApi
actual object SocketBuilder: Logging() {

    @KtorExperimentalAPI
    actual suspend fun buildSocketHandler(host: String, port: Int): SocketHandler {
        logger.info { "building ktor socket handler" }
        val socketBuilder = aSocket(ActorSelectorManager(Dispatchers.IO)).tcp()
        val address = InetSocketAddress(host, port)
        logger.info { "connecting to $address" }
        val connect = socketBuilder.connect(address)
        logger.info { "connected to $address!" }
        return KtorSocketWrapperHandler(connect)
    }

    actual fun runBlockingCoroutine(closure: suspend (CoroutineScope) -> Unit) {
        runBlocking { closure(this) }
    }

}
