package fr.acinq.eklair.io

import fr.acinq.eklair.io.NativeSocket
import fr.acinq.eklair.io.suspendRecvExact
import fr.acinq.eklair.io.suspendRecvUpTo
import fr.acinq.eklair.io.suspendSend
import kotlinx.coroutines.CoroutineScope

class NativeSocketWrapperHandler(private val socket: NativeSocket): SocketHandler {

    override suspend fun writeByte(b: Byte) {
        writeFully(ByteArray(1) { b }, 0, 1)
    }

    override suspend fun writeFully(src: ByteArray, offset: Int, length: Int) {
        socket.suspendSend(src, offset, length)
    }

    override fun getHost(): String = socket.getRemoteEndpoint().toString()


    override suspend fun readUpTo(length: Int): ByteArray {
        return socket.suspendRecvUpTo(length)
    }

    override suspend fun readFully(dst: ByteArray, offset: Int, length: Int) {
        socket.suspendRecvExact(dst, offset, length)
    }

    override fun flush() {
        // no-op
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