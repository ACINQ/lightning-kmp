package fr.acinq.eklair

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking

object BootOld {
    fun main(args: Array<String>) {
        println(Hex.encode(byteArrayOf(0xde.toByte(), 0xad.toByte(), 0xbe.toByte(), 0xef.toByte())))
    }
}

@ExperimentalStdlibApi
actual object SocketBuilder{
    actual suspend fun buildSocketHandler(host: String, port: Int): SocketHandler =
        NativeSocketWrapperHandler(NativeSocket.connect(host, port))

    actual fun runBlockingCoroutine(closure: suspend (CoroutineScope) -> Unit){
        runBlocking { closure(this) }
    }
}
