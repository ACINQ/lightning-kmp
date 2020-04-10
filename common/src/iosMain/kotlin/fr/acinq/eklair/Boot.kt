package fr.acinq.eklair

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.*

@ExperimentalStdlibApi
actual object SocketBuilder{
    actual suspend fun buildSocketHandler(host: String, port: Int): SocketHandler =
        NativeSocketWrapperHandler(NativeSocket.connect(host, port))

    actual fun runBlockingCoroutine(closure: suspend (CoroutineScope) -> Unit){
        runBlocking { closure(this) }
    }
}
