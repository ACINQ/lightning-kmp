package fr.acinq.eklair

import io.ktor.network.selector.ActorSelectorManager
import io.ktor.network.sockets.aSocket
import io.ktor.util.KtorExperimentalAPI
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress

@ExperimentalStdlibApi
actual object SocketBuilder {

  @KtorExperimentalAPI
  actual suspend fun buildSocketHandler(host: String, port: Int): SocketHandler {
    log(LogLevel.INFO, "SocketBuilder", "building ktor socket")
    val socketBuilder = aSocket(ActorSelectorManager(Dispatchers.IO)).tcp()
    val address = InetSocketAddress(host, port)
    log(LogLevel.INFO, "SocketBuilder", "connecting to $address")
    val connect = socketBuilder.connect(address)
    log(LogLevel.INFO, "SocketBuilder", "connected to $address!")
    return KtorSocketWrapperHandler(connect)
  }

  actual fun runBlockingCoroutine(closure: suspend (CoroutineScope) -> Unit) {
    runBlocking { closure(this) }
  }

}
