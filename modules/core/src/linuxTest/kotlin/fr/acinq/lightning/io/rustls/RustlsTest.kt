package fr.acinq.lightning.io.rustls

import fr.acinq.lightning.io.TcpSocket
import fr.acinq.lightning.io.send
import fr.acinq.lightning.tests.utils.testLoggerFactory
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue

@OptIn(ExperimentalForeignApi::class)
class RustlsTest {
    @Test
    fun `tcpsocket test -- no cert verification`() {
        runBlocking {
            val socketBuilder = TcpSocket.Builder()
            val socket = socketBuilder.connect("electrum.acinq.co", 50002, TcpSocket.TLS.UNSAFE_CERTIFICATES, testLoggerFactory)
            val request = "{\"jsonrpc\":\"2.0\",\"id\":0,\"method\":\"server.version\",\"params\":[\"3.3.6\",\"1.6\"]}\n"
            socket.send(request.encodeToByteArray())
            val response = ByteArray(1024)
            val received = socket.receiveAvailable(response, 0, response.size)
            println(response.copyOf(received).toKString())
        }
    }

    @Test
    fun `tcpsocket test -- full cert verification`() {
        runBlocking {
            val socketBuilder = TcpSocket.Builder()
            val socket = socketBuilder.connect("electrum.acinq.co", 50002, TcpSocket.TLS.TRUSTED_CERTIFICATES(), testLoggerFactory)
            val request = "{\"jsonrpc\":\"2.0\",\"id\":0,\"method\":\"server.version\",\"params\":[\"3.3.6\",\"1.6\"]}\n"
            socket.send(request.encodeToByteArray())
            val response = ByteArray(1024)
            val received = socket.receiveAvailable(response, 0, response.size)
            println(response.copyOf(received).toKString())
        }
    }

    // DER-encoded SubjectPublicKeyInfo of electrum.acinq.co, base64 (PEM body of `openssl x509 -pubkey`).
    private val acinqPubKey =
        "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEA0JbFX2sPIVKCwBjNMXpV" +
                "knD6KErkilQL0UFc8W+jMzuvMrSrKGpxZBcCGGe5L+pNDslopkRnJtL/zurB87Lr" +
                "hyng7aNo7nGOFvfh4xOc6GcYW4f0ffthReze9qwS4OW0FZ2wJ51OvLWFvV5tYtk3" +
                "4JKZvnclOuD224g3R2irwLXSLtnOUSwuYB9AfxGlCIONfDwnZuiWMgRTuOtnH2rs" +
                "yMHnuwN1GpkHVCqHR07/HGaHni9wSwBTFJpoVlHEV3JcO60ll61FhrjAOUg0Q2E5" +
                "PvD84SNezaIpVBRqfhiL3c6JEA1ZnIcl8GGRlcy/RUHNAIiPB0D7dT7F5fvvDUbe" +
                "RwIDAQAB"

    @Test
    fun `tcpsocket test -- pinned public key matches`() {
        runBlocking {
            val socketBuilder = TcpSocket.Builder()
            val socket = socketBuilder.connect("electrum.acinq.co", 50002, TcpSocket.TLS.PINNED_PUBLIC_KEY(acinqPubKey), testLoggerFactory)
            val request = "{\"jsonrpc\":\"2.0\",\"id\":0,\"method\":\"server.version\",\"params\":[\"3.3.6\",\"1.6\"]}\n"
            socket.send(request.encodeToByteArray())
            val response = ByteArray(1024)
            val received = socket.receiveAvailable(response, 0, response.size)
            println(response.copyOf(received).toKString())
            socket.close()
        }
    }

    @Test
    fun `tcpsocket test -- pinned public key mismatch is rejected`() {
        // Flip the last base64 char so the pinned key no longer matches the server's.
        val wrongKey = acinqPubKey.dropLast(2) + (if (acinqPubKey.endsWith("AB")) "AC" else "AB")
        runBlocking {
            val socketBuilder = TcpSocket.Builder()
            var failed = false
            try {
                socketBuilder.connect("electrum.acinq.co", 50002, TcpSocket.TLS.PINNED_PUBLIC_KEY(wrongKey), testLoggerFactory)
            } catch (e: Exception) {
                failed = true
                println("rejected as expected: ${e.message}")
            }
            assertTrue(failed, "handshake should fail when the pinned public key does not match")
        }
    }

}