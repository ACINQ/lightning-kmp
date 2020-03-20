package fr.acinq.eklair

import io.ktor.network.sockets.Socket
import io.ktor.network.sockets.openReadChannel
import io.ktor.network.sockets.openWriteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.core.readBytes

class KtorSocketWrapperHandler(socket: Socket) : SocketHandler {
    private val r: ByteReadChannel = socket.openReadChannel()
    private val w: ByteWriteChannel = socket.openWriteChannel(false)

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
