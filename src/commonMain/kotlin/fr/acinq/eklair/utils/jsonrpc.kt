package fr.acinq.eklair.utils

import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.Transaction
import fr.acinq.secp256k1.Hex
import kotlinx.serialization.*
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull

/**
 * JSON-RPC request
 */
@Serializable
data class JsonRPCRequest(
    val jsonrpc: String = "2.0",
    val id: Int,
    val method: String,
    val params: List<JsonRPCParam> = emptyList()
)

fun List<Any>.asJsonRPCParameters(): List<JsonRPCParam> = map {
    when(it) {
        is Int -> it.asParam()
        is Double -> it.asParam()
        is String -> it.asParam()
        is Boolean -> when {
            it -> 1
            else -> 0
        }.asParam()
        is ByteVector -> it.toHex().asParam()
        is Transaction -> Hex.encode(Transaction.write(it)).asParam()
        else -> error("Unsupported type ${it::class} as JSON-RPC parameter")
    }
}

@Serializable
sealed class JsonRPCParam
@Serializable
data class JsonRPCInt(val value: Int) : JsonRPCParam() {
    @Serializer(forClass = JsonRPCInt::class)
    companion object: KSerializer<JsonRPCInt> {
        override val descriptor: SerialDescriptor = buildClassSerialDescriptor("IntParam")

        override fun serialize(encoder: Encoder, value: JsonRPCInt) {
            encoder.encodeInt(value.value)
        }

        override fun deserialize(decoder: Decoder): JsonRPCInt = JsonRPCInt(decoder.decodeInt())
    }
}
fun Int.asParam(): JsonRPCParam = JsonRPCInt(this)

@Serializable
data class JsonRPCDouble(val value: Double) : JsonRPCParam() {
    @Serializer(forClass = JsonRPCDouble::class)
    companion object: KSerializer<JsonRPCDouble> {
        override val descriptor: SerialDescriptor = buildClassSerialDescriptor("DoubleParam")

        override fun serialize(encoder: Encoder, value: JsonRPCDouble) {
            encoder.encodeDouble(value.value)
        }

        override fun deserialize(decoder: Decoder): JsonRPCDouble = JsonRPCDouble(decoder.decodeDouble())
    }
}
fun Double.asParam(): JsonRPCParam = JsonRPCDouble(this)

@Serializable
data class JsonRPCString(val value: String) : JsonRPCParam() {
    @Serializer(forClass = JsonRPCString::class)
    companion object: KSerializer<JsonRPCString> {
        override val descriptor: SerialDescriptor = buildClassSerialDescriptor("StringParam")

        override fun serialize(encoder: Encoder, value: JsonRPCString) {
            encoder.encodeString(value.value)
        }

        override fun deserialize(decoder: Decoder): JsonRPCString = JsonRPCString(decoder.decodeString())
    }
}
fun String.asParam(): JsonRPCParam = JsonRPCString(this)

/**
 * JSON-RPC result / error
 */
@Serializable
data class JsonRPCResponse(val id: Int = 0, val result: JsonElement = JsonNull, val error: JsonRPCError? = null)
@Serializable
data class JsonRPCError(val code: Int, val message: String)