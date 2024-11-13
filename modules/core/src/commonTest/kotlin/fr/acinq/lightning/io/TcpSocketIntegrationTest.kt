package fr.acinq.lightning.io

import fr.acinq.lightning.tests.utils.LightningTestSuite
import fr.acinq.lightning.tests.utils.runSuspendTest
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class TcpSocketIntegrationTest : LightningTestSuite() {

    private val serverVersionRpc = buildString {
        append("""{ "id":"0", "method":"server.version", "params": ["3.3.6", "1.4"]}""")
        appendLine()
    }.encodeToByteArray()

    @Test
    fun `TCP connection IntegrationTest`() = runSuspendTest(timeout = 250.seconds) {
        val socket = TcpSocket.Builder().connect("localhost", 51001, TcpSocket.TLS.DISABLED, loggerFactory)
        socket.send(serverVersionRpc)
        val ba = ByteArray(8192)
        val size = socket.receiveAvailable(ba)
        assertTrue { size > 0 }
        socket.close()
    }

    @Test
    fun `SSL connection`() = runSuspendTest(timeout = 5.seconds) {
        val socket = TcpSocket.Builder().connect("electrum.acinq.co", 50002, TcpSocket.TLS.UNSAFE_CERTIFICATES, loggerFactory)
        socket.send(serverVersionRpc)
        val size = socket.receiveAvailable(ByteArray(8192))
        assertTrue { size > 0 }
        socket.close()
    }
}