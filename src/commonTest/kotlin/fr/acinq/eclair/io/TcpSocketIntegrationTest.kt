package fr.acinq.eclair.io

import fr.acinq.eclair.tests.utils.EclairTestSuite
import fr.acinq.eclair.tests.utils.runSuspendTest
import fr.acinq.eclair.utils.subArray
import io.ktor.utils.io.core.*
import kotlinx.coroutines.withTimeout
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.ExperimentalTime
import kotlin.time.seconds

@OptIn(ExperimentalTime::class)
class TcpSocketIntegrationTest : EclairTestSuite() {

    private val serverVersionRpc = buildString {
        append("""{ "id":"0", "method":"server.version", "params": ["3.3.6", "1.4"]}""")
        appendLine()
    }.toByteArray()

    @Test
    fun `TCP connection IntegrationTest`() = runSuspendTest(timeout = 250.seconds) {
        val socket = TcpSocket.Builder().connect("localhost", 51001)
        socket.send(serverVersionRpc)
        val ba = ByteArray(8192)
        val size = socket.receiveAvailable(ba)
        assertTrue { size > 0 }
        socket.close()
    }

    @Test
    fun `SSL connection`() = runSuspendTest(timeout = 5.seconds) {
        val socket = TcpSocket.Builder().connect("electrum.acinq.co", 50002, TcpSocket.TLS.UNSAFE_CERTIFICATES)
        socket.send(serverVersionRpc)
        val size = socket.receiveAvailable(ByteArray(8192))
        assertTrue { size > 0 }
        socket.close()
    }
}