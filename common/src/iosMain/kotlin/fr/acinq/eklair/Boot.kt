package fr.acinq.eklair

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.*
@ExperimentalStdlibApi
actual object SocketBuilder{
    actual suspend fun buildSocketHandler():SocketHandler =
        NativeSocketWrapperHandler(NativeSocket.connect("51.77.223.203", 19735))

    actual fun runBlockingCoroutine(closure: suspend (CoroutineScope) -> Unit){
        runBlocking{ closure(this) }
    }
}
