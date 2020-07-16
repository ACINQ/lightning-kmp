package fr.acinq.eklair.blockchain.electrum

import fr.acinq.bitcoin.BlockHeader
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.OutPoint
import fr.acinq.bitcoin.Transaction
import fr.acinq.eklair.utils.*
import kotlinx.serialization.*
import kotlinx.serialization.json.*

/**
 * Electrum requests / responses
 */
sealed class ElectrumRequest(vararg params: Any) {
    abstract val method: String
    private val parameters = params.toList()

    fun asJsonRPCRequest(id: Int): String =
        JsonRPCRequest(
            id = id,
            method = method,
            params = parameters.asJsonRPCParameters()
        ).encode()
}
sealed class ElectrumResponse

data class ServerVersion(
    private val clientName: String,
    private val protocolVersion: String
) : ElectrumRequest(clientName, protocolVersion) {
    override val method: String = "server.version"
}
data class ServerVersionResponse(private val clientName: String, private val protocolVersion: String) : ElectrumResponse()

object Ping : ElectrumRequest() {
    override val method: String = "server.ping"
}
object PingResponse : ElectrumResponse()

data class GetScriptHashHistory(val scriptHash: ByteVector32) : ElectrumRequest(scriptHash) {
    override val method: String = "blockchain.scripthash.get_history"
}
data class TransactionHistoryItem(val height: Int, val tx_hash: ByteVector32)
data class GetScriptHashHistoryResponse(val scriptHash: ByteVector32, val history: List<TransactionHistoryItem>) : ElectrumResponse()

data class ScriptHashListUnspent(val scriptHash: ByteVector32) : ElectrumRequest(scriptHash) {
    override val method: String = "blockchain.scripthash.listunspent"
}
data class UnspentItem(val tx_hash: ByteVector32, val tx_pos: Int, val value: Long, val height: Long) {
    val outPoint by lazy { OutPoint(tx_hash.reversed(), tx_pos.toLong()) }
}
data class ScriptHashListUnspentResponse(val scriptHash: ByteVector32, val unspents: List<UnspentItem>) : ElectrumResponse()

data class BroadcastTransaction(val tx: Transaction) : ElectrumRequest(tx) {
    override val method: String = "blockchain.transaction.broadcast"
}
data class BroadcastTransactionResponse(val tx: Transaction, val error: Error? = null) : ElectrumResponse()

data class GetTransactionIdFromPosition(val height: Int, val tx_pos: Int, val merkle: Boolean = false) : ElectrumRequest(height, tx_pos, merkle) {
    override val method: String = "blockchain.transaction.id_from_pos"
}
data class GetTransactionIdFromPositionResponse(val txid: ByteVector32, val height: Int, val tx_pos: Int, val merkle: List<ByteVector32>) : ElectrumResponse()

data class GetTransaction(val txid: ByteVector32) : ElectrumRequest(txid) {
    override val method: String = "blockchain.transaction.get"
}
data class GetTransactionResponse(val tx: Transaction) : ElectrumResponse()

data class GetHeader(val height: Int) : ElectrumRequest() {
    override val method: String = "blockchain.block.header"
}
data class GetHeaderResponse(val height: Int, val header: BlockHeader) : ElectrumResponse()

data class GetHeaders(val start_height: Int, val count: Int, val cp_height: Int = 0) : ElectrumRequest(start_height, count, cp_height) {
    override val method: String = "blockchain.block.headers"
}
data class GetHeadersResponse(val start_height: Int, val headers: List<BlockHeader>, val max: Int) : ElectrumResponse()

data class GetMerkle(val txid: ByteVector32, val height: Int) : ElectrumRequest(txid, height) {
    override val method: String = "blockchain.transaction.get_merkle"
}
data class GetMerkleResponse(val txid: ByteVector32, val merkle: List<ByteVector32>, val block_height: Int, val pos: Int) : ElectrumResponse()

data class ScriptHashSubscription(val scriptHash: ByteVector32) : ElectrumRequest(scriptHash) {
    override val method: String = "blockchain.scripthash.subscribe"
}
data class ScriptHashSubscriptionResponse(val scriptHash: ByteVector32, val status: String?) : ElectrumResponse()

object HeaderSubscription : ElectrumRequest() {
    override val method: String = "blockchain.headers.subscribe"
}
data class HeaderSubscriptionResponse(val height: Int, val header: BlockHeader) : ElectrumResponse()

/**
 * Other Electrum responses
 */
data class TransactionHistory(val history: List<TransactionHistoryItem>) : ElectrumResponse()
data class AddressStatus(val address: String, val status: String?) : ElectrumResponse()
data class ServerError(val request: ElectrumRequest, val error: Error) : ElectrumResponse()

/**
 * ElectrumResponse deserializer
 */
@OptIn(UnstableDefault::class)
object ElectrumResponseDeserializer : KSerializer<Either<ElectrumResponse, JsonRPCResponse>> {
    private val json = Json(JsonConfiguration.Default.copy(ignoreUnknownKeys = true))

    override fun deserialize(decoder: Decoder): Either<ElectrumResponse, JsonRPCResponse> {
        // Decoder -> JsonInput
        val input = decoder as? JsonInput
            ?: throw SerializationException("This class can be loaded only by JSON")
        // JsonInput => JsonElement (JsonObject in this case)
        val jsonObject = input.decodeJson() as? JsonObject
            ?: throw SerializationException("Expected JsonObject")

        return when(val method = jsonObject["method"]) {
            is JsonPrimitive -> {
                val params = jsonObject["params"]?.jsonArray?.content.orEmpty().also {
                    if (it.isEmpty()) throw SerializationException("Parameters for ${method.content} notification should not null or be empty.")
                }

                when (method.content) {
                    "blockchain.headers.subscribe" -> params.first().jsonObject.let { header ->
                        val height = header.getAs<JsonPrimitive>("height").int
                        val hex = header.getAs<JsonPrimitive>("hex").content
                        Either.Left(HeaderSubscriptionResponse(height, BlockHeader.read(hex)))
                    }
                    "blockchain.scripthash.subscribe" -> params.first().jsonObject.let { header ->
                        val scriptHash = header.getAs<JsonPrimitive>("scripthash").content
                        val status = header.getAs<JsonPrimitive>("status").contentOrNull
                        Either.Left(ScriptHashSubscriptionResponse(ByteVector32.fromValidHex(scriptHash), status))
                    }
                    else -> throw SerializationException("JSON-RPC Method ${method.content} is not support")
                }
            }
            else -> Either.Right(json.fromJson(JsonRPCResponse.serializer(), jsonObject))
        }
    }

    override fun serialize(encoder: Encoder, value: Either<ElectrumResponse, JsonRPCResponse>) {
        throw SerializationException("This ($value) is not meant to be serialized!")
    }

    override val descriptor: SerialDescriptor
        get() = SerialDescriptor("fr.acinq.eklair.utils.Either", PolymorphicKind.SEALED)
}

/**
 * Utils
 */
@OptIn(UnstableDefault::class)
private fun JsonRPCRequest.encode(): String = buildString {
    append(Json.stringify(JsonRPCRequest.serializer(), this@encode))
    appendLine()
}