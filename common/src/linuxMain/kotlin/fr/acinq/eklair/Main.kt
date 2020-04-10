package fr.acinq.eklair

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking

object BootOld {
    fun main(args: Array<String>) {
        println(Hex.encode(byteArrayOf(0xde.toByte(), 0xad.toByte(), 0xbe.toByte(), 0xef.toByte())))
    }
}

actual object SocketBuilder {
    actual suspend fun buildSocketHandler(host: String, port: Int): SocketHandler = LinuxSocketHandler()
    actual fun runBlockingCoroutine(closure: suspend (CoroutineScope) -> Unit) {
        runBlocking { closure(this) }
    }
}

class LinuxSocketHandler : SocketHandler {
    override suspend fun readUpTo(length: Int): ByteArray {
        TODO("Not yet implemented")
    }

    override fun getHost(): String {
        TODO("Not yet implemented")
    }

    override suspend fun readFully(dst: ByteArray, offset: Int, length: Int) {
        TODO("Not yet implemented")
    }

    override suspend fun writeByte(b: Byte) {
        TODO("Not yet implemented")
    }

    override suspend fun writeFully(src: ByteArray, offset: Int, length: Int) {
        TODO("Not yet implemented")
    }

    override fun flush() {
        TODO("Not yet implemented")
    }
}
