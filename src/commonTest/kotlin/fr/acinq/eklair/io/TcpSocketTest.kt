package fr.acinq.eklair.io

import fr.acinq.eklair.blockchain.electrum.ElectrumClient.Companion.version
import fr.acinq.eklair.utils.runTest
import io.ktor.utils.io.core.*
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertTrue

class TcpSocketTest {
    @Test
    fun `TCP connection`() = runTest {
        withTimeout(5_000) {
            val socket = TcpSocket.Builder().connect("localhost", 51001, false)
            socket.send(version.asJsonRPCRequest().toByteArray())
            val size = socket.receiveAvailable(ByteArray(256))
            assertTrue { size > 0 }
            socket.close()
        }
    }

    @Test
    fun `SSL connection`() = runTest {
        val socket = TcpSocket.Builder().connect("electrum.acinq.co", 50002, true)
        socket.send(version.asJsonRPCRequest().toByteArray())
        val size = socket.receiveAvailable(ByteArray(256))
        assertTrue { size > 0 }
        socket.close()
    }
}