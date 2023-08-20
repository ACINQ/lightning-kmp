package fr.acinq.lightning.utils

import fr.acinq.lightning.io.TcpSocket
import fr.acinq.lightning.io.receiveAvailable
import io.ktor.utils.io.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch

/**
 * When using TCP, the electrum protocol adds a newline character at the end of each message (encoded using JSON-RPC).
 * This allows multiplexing concurrent, unrelated messages over the same long-lived TCP connection.
 * This flow extracts individual messages from the TCP socket and returns them one-by-one.
 *
 * @param chunkSize we're receiving a potentially infinite stream of bytes on the TCP socket, so we need to read them
 * by chunks. The caller should provide a value that isn't too high (since we're pre-allocating that memory) while
 * allowing us to read most messages in a single chunk.
 */
fun linesFlow(socket: TcpSocket, chunkSize: Int = 8192): Flow<String> = channelFlow {
    val buffer = ByteArray(chunkSize)
    val chan = ByteChannel()
    launch {
        while (true) {
            val line = chan.readUTF8Line(Int.MAX_VALUE)
            line?.let { send(it) }
        }
    }
    while (true) {
        val size = socket.receiveAvailable(buffer)
        chan.writeFully(buffer.subArray(size), 0, size)
        chan.flush()
    }
}
