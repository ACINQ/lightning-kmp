package fr.acinq.lightning.blockchain.electrum

import fr.acinq.bitcoin.BlockHeader
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.lightning.tests.utils.LightningTestSuite
import fr.acinq.lightning.utils.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlin.test.Test
import kotlin.test.assertEquals


class ElectrumRequestTest : LightningTestSuite() {

    @Test
    fun `ServerVersion stringify JSON-RPC format`() {
        assertEquals(buildString {
            append("""{"jsonrpc":"2.0","id":0,"method":"server.version","params":["3.3.6","1.4"]}""")
            appendLine()
        }, ServerVersion().asJsonRPCRequest(0))

        assertEquals(buildString {
            append("""{"jsonrpc":"2.0","id":0,"method":"server.version","params":["lightning-kmp-test","1.4.2"]}""")
            appendLine()
        }, ServerVersion(clientName = "lightning-kmp-test", protocolVersion = "1.4.2").asJsonRPCRequest(0))
    }

    @Test
    fun `Ping stringify JSON-RPC format`() {
        assertEquals(buildString {
            append("""{"jsonrpc":"2.0","id":0,"method":"server.ping","params":[]}""")
            appendLine()
        }, Ping.asJsonRPCRequest(0))
    }

    @Test
    fun `Block headers deserialization`() {
        val json = Json { ignoreUnknownKeys = true }
        val jsonRpc = buildString {
            append("""
                {
                    "jsonrpc":"2.0",
                    "method": "blockchain.headers.subscribe",
                    "params": [{
                        "height": 520481,
                        "hex": "00000020890208a0ae3a3892aa047c5468725846577cfcd9b512b50000000000000000005dc2b02f2d297a9064ee103036c14d678f9afc7e3d9409cf53fd58b82e938e8ecbeca05a2d2103188ce804c4"
                    }]
                }
            """.trimIndent())
            appendLine()
        }
        val response = json.decodeFromString(ElectrumResponseDeserializer, jsonRpc)
        assertEquals(
            Either.Left(HeaderSubscriptionResponse(height = 520481, BlockHeader(
                version = 536870912,
                hashPreviousBlock = ByteVector32.fromValidHex("890208a0ae3a3892aa047c5468725846577cfcd9b512b5000000000000000000"),
                hashMerkleRoot = ByteVector32.fromValidHex("5dc2b02f2d297a9064ee103036c14d678f9afc7e3d9409cf53fd58b82e938e8e"),
                time = 1520495819,
                bits = 402858285,
                nonce = 3288656012
            ))), response)
    }

    @Test
    fun `JsonRPCError deserialization`() {
        val json = Json { ignoreUnknownKeys = true }
        val jsonRpc = buildString {
            append("""{"jsonrpc":"2.0","error":{"code":-32700,"message":"messages must be encoded in UTF-8"},"id":null}""")
            appendLine()
        }
        val response = json.decodeFromString(ElectrumResponseDeserializer, jsonRpc)
        assertEquals(Either.Right(JsonRPCResponse(null, JsonNull, JsonRPCError(-32700, "messages must be encoded in UTF-8"))), response)
    }
}