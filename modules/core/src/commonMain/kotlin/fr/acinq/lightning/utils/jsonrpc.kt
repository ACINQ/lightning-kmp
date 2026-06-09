package fr.acinq.lightning.utils

import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.Transaction
import fr.acinq.bitcoin.TxId
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

/**
 * JSON-RPC request
 */
@Serializable
data class JsonRPCRequest(
    val jsonrpc: String = "2.0",
    val id: Int,
    val method: String,
    val params: List<JsonElement> = emptyList()
)

fun List<Any>.asJsonRPCParameters(): List<JsonElement> = map {
    when (it) {
        is Int -> JsonPrimitive(it)
        is Double -> JsonPrimitive(it)
        is String -> JsonPrimitive(it)
        is Boolean -> JsonPrimitive(it)
        is ByteVector -> JsonPrimitive(it.toHex())
        is TxId -> JsonPrimitive(it.value.toHex())
        is Transaction -> JsonPrimitive(it.toString())
        is Array<*> -> JsonArray(it.map { JsonPrimitive(it.toString()) })
        else -> error("Unsupported type ${it::class} as JSON-RPC parameter")
    }
}

/**
 * JSON-RPC result / error
 */
@Serializable
data class JsonRPCResponse(val jsonrpc: String? = "2.0", val id: Int? = 0, val result: JsonElement = JsonNull, val error: JsonRPCError? = null)

@Serializable
data class JsonRPCError(val code: Int, val message: String)