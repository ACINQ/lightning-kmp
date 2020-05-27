package fr.acinq.eklair.io

import kotlinx.coroutines.CoroutineScope

interface SocketHandler {
    fun getHost(): String
    suspend fun readUpTo(length: Int): ByteArray
    suspend fun readFully(dst: ByteArray, offset: Int, length: Int)
    suspend fun writeByte(b: Byte)
    suspend fun writeFully(src: ByteArray, offset: Int, length: Int)
    fun flush()
}

expect object SocketBuilder {
    suspend fun buildSocketHandler(host: String, port: Int): SocketHandler

    fun runBlockingCoroutine(closure: suspend (CoroutineScope) -> Unit)

}