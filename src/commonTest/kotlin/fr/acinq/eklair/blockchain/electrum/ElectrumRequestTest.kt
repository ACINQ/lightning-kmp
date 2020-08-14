package fr.acinq.eklair.blockchain.electrum

import kotlin.test.Test
import kotlin.test.assertEquals


class ElectrumRequestTest {

    @Test
    fun `ServerVersion stringify JSON-RPC format`() {
        assertEquals(buildString {
            append("""{"jsonrpc":"2.0","id":0,"method":"server.version","params":["3.3.6","1.4"]}""")
            appendLine()
        }, ServerVersion().asJsonRPCRequest(0))

        assertEquals(buildString {
            append("""{"jsonrpc":"2.0","id":0,"method":"server.version","params":["eklair-test","1.4.2"]}""")
            appendLine()
        }, ServerVersion(clientName = "eklair-test", protocolVersion = "1.4.2").asJsonRPCRequest(0))
    }

    @Test
    fun `Ping stringify JSON-RPC format`() {
        assertEquals(buildString {
            append("""{"jsonrpc":"2.0","id":0,"method":"server.ping","params":[]}""")
            appendLine()
        }, Ping.asJsonRPCRequest(0))
    }
}