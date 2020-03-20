package fr.acinq.eklair

class NativeSocketWrapperHandler(private val socket: NativeSocket): SocketHandler {
    override suspend fun writeByte(b: Byte) {
        writeFully(ByteArray(1) { b }, 0, 1)
    }

    override suspend fun writeFully(src: ByteArray, offset: Int, length: Int) {
        socket.suspendSend(src, offset, length)
    }

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
