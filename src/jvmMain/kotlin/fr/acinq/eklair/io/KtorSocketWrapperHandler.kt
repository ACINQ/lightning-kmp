package fr.acinq.eklair.io

import fr.acinq.eklair.Logging
import io.ktor.network.selector.ActorSelectorManager
import io.ktor.network.sockets.Socket
import io.ktor.network.sockets.aSocket
import io.ktor.network.sockets.openReadChannel
import io.ktor.network.sockets.openWriteChannel
import io.ktor.util.KtorExperimentalAPI
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.core.readBytes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import java.net.SocketAddress

class KtorSocketWrapperHandler(socket: Socket) : SocketHandler {
    private val remote: SocketAddress = socket.remoteAddress
    private val r: ByteReadChannel = socket.openReadChannel()
    private val w: ByteWriteChannel = socket.openWriteChannel(false)

    override fun getHost(): String = remote.toString()

    override suspend fun readUpTo(length: Int): ByteArray {
        val packet = r.readPacket(length, 0)
        val readBytes = packet.readBytes(length)
        packet.close()
        return readBytes
    }

    override suspend fun readFully(dst: ByteArray, offset: Int, length: Int) {
        r.readFully(dst, offset, length)
    }

    override suspend fun writeByte(b: Byte) {
        w.writeByte(b)
    }

    override suspend fun writeFully(src: ByteArray, offset: Int, length: Int) {
        w.writeFully(src, offset, length)
    }

    override fun flush() {
        w.flush()
    }
}

@ExperimentalStdlibApi
actual object SocketBuilder: Logging() {

    @KtorExperimentalAPI
    actual suspend fun buildSocketHandler(host: String, port: Int): SocketHandler {
        SocketBuilder.logger.info { "building ktor socket handler" }
        val socketBuilder = aSocket(ActorSelectorManager(Dispatchers.IO)).tcp()
        val address = InetSocketAddress(host, port)
        SocketBuilder.logger.info { "connecting to $address" }
        val connect = socketBuilder.connect(address)
        SocketBuilder.logger.info { "connected to $address!" }
        return KtorSocketWrapperHandler(connect)
    }

    actual fun runBlockingCoroutine(closure: suspend (CoroutineScope) -> Unit) {
        runBlocking { closure(this) }
    }

}