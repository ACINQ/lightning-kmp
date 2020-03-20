package fr.acinq.eklair

import io.ktor.network.selector.ActorSelectorManager
import io.ktor.network.sockets.aSocket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress

@ExperimentalStdlibApi
actual object SocketBuilder{
    actual suspend fun buildSocketHandler(): SocketHandler = KtorSocketWrapperHandler(aSocket(ActorSelectorManager(Dispatchers.IO)).tcp().connect(InetSocketAddress("51.77.223.203", 19735)))

    actual fun runBlockingCoroutine(closure: suspend (CoroutineScope) -> Unit){
        runBlocking{ closure(this) }
    }

}
