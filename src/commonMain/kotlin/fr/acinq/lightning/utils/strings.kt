package fr.acinq.lightning.utils

import fr.acinq.lightning.io.TcpSocket
import fr.acinq.lightning.io.receiveAvailable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** The first byte of a unicode character tells us how many bytes this character uses. */
fun utf8ByteCount(firstCodePoint: Byte) = when (firstCodePoint.toUByte().toInt()) {
    in 0..0x7F -> 1
    in 0xC0..0xDF -> 2
    in 0xE0..0xEF -> 3
    in 0xF0..0xF7 -> 4
    else -> error("Malformed UTF-8 character (bad first codepoint 0x${firstCodePoint.toUByte().toString(16).padStart(2, '0')})")
}

/**
 * When using TCP, the electrum protocol adds a newline character at the end of each message (encoded using JSON-RPC).
 * This allows multiplexing concurrent, unrelated messages over the same long-lived TCP connection.
 * This flow extracts individual messages from the TCP socket and returns them one-by-one.
 *
 * @param chunkSize we're receiving a potentially infinite stream of bytes on the TCP socket, so we need to read them
 * by chunks. The caller should provide a value that isn't too high (since we're pre-allocating that memory) while
 * allowing us to read most messages in a single chunk.
 */
fun linesFlow(socket: TcpSocket, chunkSize: Int = 8192): Flow<String> = flow {
    val buffer = ByteArray(chunkSize)
    while (true) {
        val size = socket.receiveAvailable(buffer)
        emit(buffer.subArray(size))
    }
}.decodeToString().splitByLines()

private fun Flow<ByteArray>.decodeToString(): Flow<String> = flow {
    // We receive chunks of bytes, but a chunk may stop in the middle of a utf8 character.
    // When that happens, we carry the bytes of the final partial character over to the next chunk of bytes we'll receive.
    val previousChunkIgnoredBytes = ByteArray(3) // utf8 characters use at most 4 bytes, hence the size of 3
    var previousChunkIgnoredBytesSize = 0
    collect { receivedBytes ->
        val bytes = previousChunkIgnoredBytes.subArray(previousChunkIgnoredBytesSize).concat(receivedBytes)

        var utf8AlignedSize = 0
        while (utf8AlignedSize < bytes.size) {
            val count = utf8ByteCount(bytes[utf8AlignedSize])
            if (utf8AlignedSize + count > bytes.size) break
            utf8AlignedSize += count
        }

        // The chunk ends with a truncated utf8 character: we store that partial character to be processed with the next chunk.
        if (utf8AlignedSize < bytes.size) {
            bytes.copyInto(previousChunkIgnoredBytes, 0, utf8AlignedSize, bytes.size)
        }
        previousChunkIgnoredBytesSize = bytes.size - utf8AlignedSize

        emit(bytes.subArray(utf8AlignedSize).decodeToString())
    }
}

private fun Flow<String>.splitByLines(): Flow<String> = flow {
    // We receive valid utf8 strings, and need to decompose them into individual messages (separated by a newline character).
    var buffer = ""
    val lineEnding = Regex("\\n")

    collect {
        buffer += it
        while (true) {
            // If we don't have any newline character, we wait for the next string chunk.
            val match = lineEnding.find(buffer) ?: break
            emit(buffer.substring(0, match.range.first))
            buffer = buffer.substring(match.range.first + 1)
        }
    }

    // Our parent flow has been closed. We may have an incomplete message in our buffer, but it doesn't make sense
    // to emit it, since listeners won't be able to decode it, so we simply silently drop it.
}
