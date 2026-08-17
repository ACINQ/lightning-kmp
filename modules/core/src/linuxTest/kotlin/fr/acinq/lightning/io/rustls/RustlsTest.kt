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

    // public key of TLS certificate of electrum.acinq.co
    // to get this public key you can use: openssl s_client -connect electrum.acinq.co:50002 -servername electrum.acinq.co </dev/null 2>/dev/null | openssl x509 -pubkey
    private val acinqPubKey = "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAreuyKgAuXbE9LB3vV9A4" +
            "x3FCAlFH9s70P3TdcMvN2Pntbj2FY/B792q2TCQmiMmJVni+CPlMDAjSsX8s+KVm" +
            "Ph96ZYha+WIBk2tqhHGq9anuIRXvb36+HifbxJ7mTfYY1uzF2ote5q7LB+h4Mqzc" +
            "M927YOTQ7LPfhjIgsab3UeET3Y8uYaAC6py5uPeSusF2G3uXgM/TH1Bq2msoqhyt" +
            "MOeZjDIgTxXzONjvxaPyF7NXnTnvTDsVK9o8YgY5lidsTGXhrogf672TMIawAwsF" +
            "XoSyerOFbd8i3xQLfl7up8Nqc9pLR+gEc3jofDEhmIZXSHIwBCNnSreUA+9YV7xG" +
            "7wIDAQAB"

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