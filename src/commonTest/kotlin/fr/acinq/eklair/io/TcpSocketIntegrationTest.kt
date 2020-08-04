package fr.acinq.eklair.io

import fr.acinq.eklair.utils.runTest
import io.ktor.utils.io.core.*
import kotlinx.coroutines.withTimeout
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertTrue

class TcpSocketIntegrationTest {

    private val serverVersionRpc = buildString {
        append("""{ "id":"0", "method":"server.version", "params": ["3.3.6", "1.4"]}""")
        appendLine()
    }.toByteArray()

    @Test
    @Ignore // TODO activate this test with docker env
    fun `TCP connection`() = runTest {
        withTimeout(5_000) {
            val socket = TcpSocket.Builder().connect("localhost", 51001, false)
            socket.send(serverVersionRpc)
            val size = socket.receiveAvailable(ByteArray(32))
            assertTrue { size > 0 }
            socket.close()
        }
    }

    @Test
    @Ignore // TODO SSL handshake fails on iOS
    fun `SSL connection`() = runTest {
        withTimeout(5_000) {
            val socket = TcpSocket.Builder().connect("electrum.acinq.co", 50002, true)
            socket.send(serverVersionRpc)
            val size = socket.receiveAvailable(ByteArray(32))
            assertTrue { size > 0 }
            socket.close()
        }
    }
}