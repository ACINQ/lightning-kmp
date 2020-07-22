package fr.acinq.eklair.blockchain.electrum

import fr.acinq.bitcoin.BlockHeader
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.OutPoint
import fr.acinq.bitcoin.Transaction
import fr.acinq.bitcoin.io.ByteArrayInput
import fr.acinq.bitcoin.io.readNBytes
import fr.acinq.eklair.utils.*
import fr.acinq.secp256k1.Hex
import kotlinx.coroutines.channels.SendChannel
import kotlinx.serialization.*
import kotlinx.serialization.json.*

/**
 * Common communication objects between [ElectrumClient] and external ressources (e.g. [ElectrumWatcher])
 */
sealed class ElectrumMessage
sealed class ElectrumClientState : ElectrumMessage()
object ElectrumClientReady : ElectrumClientState()
object ElectrumClientClosed : ElectrumClientState()
sealed class ElectrumSubscription(val listener: SendChannel<ElectrumMessage>) : ElectrumMessage()
class ElectrumStatusSubscription(listener: SendChannel<ElectrumMessage>) : ElectrumSubscription(listener)
class ElectrumHeaderSubscription(listener: SendChannel<ElectrumMessage>) : ElectrumSubscription(listener)
data class ElectrumSendRequest(val electrumRequest: ElectrumRequest, val requestor: SendChannel<ElectrumMessage>? = null) : ElectrumMessage()

/**
 * [ElectrumClient] requests / responses
 */
sealed class ElectrumRequest(vararg params: Any) {
    abstract val method: String
    private val parameters = params.toList()

    fun asJsonRPCRequest(id: Int = 0): String =
        JsonRPCRequest(
            id = id,
            method = method,
            params = parameters.asJsonRPCParameters()
        ).encode()
}
sealed class ElectrumResponse : ElectrumMessage()

data class ServerVersion(
    private val clientName: String = ElectrumClient.ELECTRUM_CLIENT_NAME,
    private val protocolVersion: String = ElectrumClient.ELECTRUM_PROTOCOL_VERSION
) : ElectrumRequest(clientName, protocolVersion) {
    override val method: String = "server.version"
}
data class ServerVersionResponse(val clientName: String, val protocolVersion: String) : ElectrumResponse()

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
data class BroadcastTransactionResponse(val tx: Transaction, val error: JsonRPCError? = null) : ElectrumResponse()

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
data class ServerError(val request: ElectrumRequest, val error: JsonRPCError) : ElectrumResponse()

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

@OptIn(UnstableDefault::class)
internal fun parseJsonResponse(request: ElectrumRequest, rpcResponse: JsonRPCResponse): ElectrumResponse =
    if (rpcResponse.error != null) when (request) {
        is BroadcastTransaction -> BroadcastTransactionResponse(request.tx, rpcResponse.error)
        else -> ServerError(
            request = request,
            error = rpcResponse.error
        )
    }
    else when (request) {
        is ServerVersion -> {
            val resultArray = rpcResponse.result.jsonArray
            ServerVersionResponse(resultArray[0].toString(), resultArray[1].toString())
        }
        Ping -> PingResponse
        is GetScriptHashHistory -> {
            val jsonArray = rpcResponse.result.jsonArray
            val items = jsonArray.map {
                val height = it.jsonObject.getAs<JsonLiteral>("height").int
                val txHash = it.jsonObject.getAs<JsonLiteral>("tx_hash").content
                TransactionHistoryItem(height, ByteVector32.fromValidHex(txHash))
            }
            GetScriptHashHistoryResponse(request.scriptHash, items)
        }
        is ScriptHashListUnspent -> {
            val jsonArray = rpcResponse.result.jsonArray
            val items = jsonArray.map {
                val txHash = it.jsonObject.getAs<JsonLiteral>("tx_hash").content
                val txPos = it.jsonObject.getAs<JsonLiteral>("tx_pos").int
                val value = it.jsonObject.getAs<JsonLiteral>("value").long
                val height = it.jsonObject.getAs<JsonLiteral>("height").long
                UnspentItem(ByteVector32.fromValidHex(txHash), txPos, value, height)
            }
            ScriptHashListUnspentResponse(request.scriptHash, items)
        }
        is GetTransactionIdFromPosition -> {
            val (txHash, leaves) = if (rpcResponse.result is JsonPrimitive) {
                rpcResponse.result.content to emptyList()
            } else {
                val jsonObject = rpcResponse.result.jsonObject
                jsonObject.getAs<JsonLiteral>("tx_hash").content to
                        jsonObject.getAs<JsonArray>("merkle").map { ByteVector32.fromValidHex(it.content) }
            }

            GetTransactionIdFromPositionResponse(ByteVector32.fromValidHex(txHash), request.height, request.tx_pos, leaves)
        }
        is GetTransaction -> {
            val hex = rpcResponse.result.content
            GetTransactionResponse(Transaction.read(hex))
        }
        is ScriptHashSubscription -> {
            val status = when(rpcResponse.result) {
                is JsonLiteral -> rpcResponse.result.content
                else -> ""
            }
            ScriptHashSubscriptionResponse(request.scriptHash, status)
        }
        is BroadcastTransaction -> {
            val message = rpcResponse.result.content
            // if we got here, it means that the server's response does not contain an error and message should be our
            // transaction id. However, it seems that at least on testnet some servers still use an older version of the
            // Electrum protocol and return an error message in the result field
            val result = runTrying<ByteVector32> {
                ByteVector32.fromValidHex(message)
            }
            when(result) {
                is Try.Success -> {
                    if (result.result == request.tx.txid) BroadcastTransactionResponse(request.tx)
                    else BroadcastTransactionResponse(request.tx, JsonRPCError(1, "response txid $result does not match request txid ${request.tx.txid}"))
                }
                is Try.Failure -> {
                    BroadcastTransactionResponse(request.tx, JsonRPCError(1, message))
                }
            }
        }
        is GetHeader -> {
            val hex = rpcResponse.result.content
            GetHeaderResponse(request.height, BlockHeader.read(hex))
        }
        is GetHeaders -> {
            val jsonObject = rpcResponse.result.jsonObject
            val max = jsonObject.getAs<JsonLiteral>("max").int
            val hex = jsonObject.getAs<JsonLiteral>("hex").content

            val blockHeaders= buildList {
                val input = ByteArrayInput(Hex.decode(hex))
                require(input.availableBytes % 80 == 0)

                val headerSize = 80
                var progress = 0
                while (progress < input.availableBytes) {
                    val header = input.readNBytes(headerSize)
                    add(BlockHeader.read(header))
                    progress += headerSize
                }
            }

            GetHeadersResponse(request.start_height, blockHeaders, max)
        }
        is GetMerkle -> {
            val jsonObject = rpcResponse.result.jsonObject
            val leaves = jsonObject.getAs<JsonArray>("merkle").map { ByteVector32.fromValidHex(it.content) }
            val blockHeight = jsonObject.getAs<JsonLiteral>("block_height").int
            val pos = jsonObject.getAs<JsonLiteral>("block_height").int
            GetMerkleResponse(request.txid, leaves, blockHeight, pos)
        }
        HeaderSubscription -> {
            val jsonObject = rpcResponse.result.jsonObject
            val height = jsonObject.getAs<JsonLiteral>("height").int
            val hex = jsonObject.getAs<JsonLiteral>("hex").content
            HeaderSubscriptionResponse(height, BlockHeader.read(hex))
        }
    }

/**
 * Utils
 */
@OptIn(UnstableDefault::class)
private fun JsonRPCRequest.encode(): String = buildString {
    append(Json.stringify(JsonRPCRequest.serializer(), this@encode))
    appendLine()
}