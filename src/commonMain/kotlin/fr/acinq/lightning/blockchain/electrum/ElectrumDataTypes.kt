package fr.acinq.lightning.blockchain.electrum

import fr.acinq.bitcoin.*
import fr.acinq.bitcoin.io.ByteArrayInput
import fr.acinq.bitcoin.io.readNBytes
import fr.acinq.lightning.blockchain.fee.FeeratePerKB
import fr.acinq.lightning.blockchain.fee.FeeratePerKw
import fr.acinq.lightning.utils.*
import fr.acinq.secp256k1.Hex
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.*

/**
 * Common communication objects between [ElectrumClient] and external ressources (e.g. [ElectrumWatcher])
 * See the documentation for the ElectrumX protocol there: https://electrumx-spesmilo.readthedocs.io
 */

/**
 * ElectrumMessage
 *       |
 *       |`---- ElectrumRequest
 *       |             |`---- ServerVersion
 *       |             |`---- Ping
 *       |             |`---- GetScriptHashHistory
 *       |             |`---- ScriptHashListUnspent
 *       |             |`---- ScriptHashSubscription
 *       |             ...
 *       |
 *       `----- ElectrumResponse
 *                     |`---- ServerVersionResponse
 *                     |`---- PingResponse
 *                     |`---- GetScriptHashHistoryResponse
 *                     |`---- ScriptHashListUnspentResponse
 *                     |`---- ScriptHashSubscriptionResponse
 *                     ...
 */
sealed interface ElectrumMessage

/**
 * [ElectrumClient] requests / responses
 */
sealed class ElectrumRequest(vararg params: Any) : ElectrumMessage {
    abstract val method: String
    private val parameters = params.toList()

    fun asJsonRPCRequest(id: Int = 0): String {
        val request = JsonRPCRequest(
            id = id,
            method = method,
            params = parameters.asJsonRPCParameters()
        )
        return buildString {
            append(json.encodeToString(JsonRPCRequest.serializer(), request))
            appendLine()
        }
    }

    companion object {
        private val json = Json { encodeDefaults = true }
    }
}

sealed interface ElectrumResponse : ElectrumMessage

data class ServerVersion(
    private val clientName: String = ElectrumClient.ELECTRUM_CLIENT_NAME,
    private val protocolVersion: String = ElectrumClient.ELECTRUM_PROTOCOL_VERSION
) : ElectrumRequest(clientName, protocolVersion) {
    override val method: String = "server.version"
}

data class ServerVersionResponse(val clientName: String, val protocolVersion: String) : ElectrumResponse

data object Ping : ElectrumRequest() {
    override val method: String = "server.ping"
}

data object PingResponse : ElectrumResponse

data class GetScriptHashHistory(val scriptHash: ByteVector32) : ElectrumRequest(scriptHash) {
    override val method: String = "blockchain.scripthash.get_history"
}

data class TransactionHistoryItem(val blockHeight: Int, val txid: TxId)
data class GetScriptHashHistoryResponse(val scriptHash: ByteVector32, val history: List<TransactionHistoryItem>) : ElectrumResponse

data class ScriptHashListUnspent(val scriptHash: ByteVector32) : ElectrumRequest(scriptHash) {
    override val method: String = "blockchain.scripthash.listunspent"
}

data class UnspentItem(val txid: TxId, val outputIndex: Int, val value: Long, val blockHeight: Long) {
    val outPoint by lazy { OutPoint(txid, outputIndex.toLong()) }
}

data class ScriptHashListUnspentResponse(val scriptHash: ByteVector32, val unspents: List<UnspentItem>) : ElectrumResponse

data class BroadcastTransaction(val tx: Transaction) : ElectrumRequest(tx) {
    override val method: String = "blockchain.transaction.broadcast"
}

data class BroadcastTransactionResponse(val tx: Transaction, val error: JsonRPCError? = null) : ElectrumResponse

data class GetTransactionIdFromPosition(val blockHeight: Int, val txIndex: Int, val merkle: Boolean = false) : ElectrumRequest(blockHeight, txIndex, merkle) {
    override val method: String = "blockchain.transaction.id_from_pos"
}

data class GetTransactionIdFromPositionResponse(val txid: TxId, val blockHeight: Int, val txIndex: Int, val merkleProof: List<ByteVector32> = emptyList()) : ElectrumResponse

data class GetTransaction(val txid: TxId, val contextOpt: Any? = null) : ElectrumRequest(txid) {
    override val method: String = "blockchain.transaction.get"
}

data class GetTransactionResponse(val tx: Transaction, val contextOpt: Any? = null) : ElectrumResponse

data class GetHeader(val blockHeight: Int) : ElectrumRequest(blockHeight) {
    override val method: String = "blockchain.block.header"
}

data class GetHeaderResponse(val blockHeight: Int, val header: BlockHeader) : ElectrumResponse

data class GetHeaders(val start_height: Int, val count: Int, val cp_height: Int = 0) : ElectrumRequest(start_height, count, cp_height) {
    override val method: String = "blockchain.block.headers"
}

data class GetHeadersResponse(val start_height: Int, val headers: List<BlockHeader>, val max: Int) : ElectrumResponse {
    override fun toString(): String = "GetHeadersResponse($start_height, ${headers.size}, ${headers.first()}, ${headers.last()}, $max)"
}

data class GetMerkle(val txid: TxId, val blockHeight: Int, val contextOpt: Transaction? = null) : ElectrumRequest(txid, blockHeight) {
    override val method: String = "blockchain.transaction.get_merkle"
}

data class GetMerkleResponse(val txid: TxId, val merkleProof: List<ByteVector32>, val blockHeight: Int, val pos: Int, val contextOpt: Transaction? = null) : ElectrumResponse {
    val root: ByteVector32 by lazy {
        tailrec fun loop(pos: Int, hashes: List<ByteVector32>): ByteVector32 {
            return if (hashes.size == 1) hashes[0]
            else {
                val h = if (pos % 2 == 1) Crypto.hash256(hashes[1] + hashes[0]) else Crypto.hash256(hashes[0] + hashes[1])
                loop(pos / 2, listOf(h.byteVector32()) + hashes.drop(2))
            }
        }

        loop(pos, listOf(txid.value.reversed()) + merkleProof.map { it.reversed() })
    }
}

data class EstimateFees(val confirmations: Int) : ElectrumRequest(confirmations) {
    override val method: String = "blockchain.estimatefee"
}

data class EstimateFeeResponse(val confirmations: Int, val feerate: FeeratePerKw?) : ElectrumResponse

sealed interface ElectrumSubscriptionResponse : ElectrumResponse

data class ScriptHashSubscription(val scriptHash: ByteVector32) : ElectrumRequest(scriptHash) {
    override val method: String = "blockchain.scripthash.subscribe"
}

data class ScriptHashSubscriptionResponse(val scriptHash: ByteVector32, val status: String = "") : ElectrumSubscriptionResponse

data object HeaderSubscription : ElectrumRequest() {
    override val method: String = "blockchain.headers.subscribe"
}

data class HeaderSubscriptionResponse(val blockHeight: Int, val header: BlockHeader) : ElectrumSubscriptionResponse

/**
 * Other Electrum responses
 */
data class ServerError(val request: ElectrumRequest, val error: JsonRPCError) : ElectrumResponse

/**
 * The Electrum server sends two types of messages:
 *   - JSON RPC responses (in reply to requests such as [GetTransaction])
 *   - Notifications (following subscriptions such as [ScriptHashSubscriptionResponse]
 *
 * The former are correlated 1:1 with JSON RPC requests, based on request id. The latter are not: one
 * subscription can yield an indefinite number of notifications.
 */
object ElectrumResponseDeserializer : DeserializationStrategy<Either<ElectrumSubscriptionResponse, JsonRPCResponse>> {
    private val json = Json { ignoreUnknownKeys = true }

    override fun deserialize(decoder: Decoder): Either<ElectrumSubscriptionResponse, JsonRPCResponse> {
        // Decoder -> JsonInput
        val input = decoder as? JsonDecoder
            ?: throw SerializationException("This class can be loaded only by JSON")
        // JsonInput => JsonElement (JsonObject in this case)
        val jsonObject = input.decodeJsonElement() as? JsonObject
            ?: throw SerializationException("Expected JsonObject")

        return when (val method = jsonObject["method"]) {
            is JsonPrimitive -> {
                val params = jsonObject["params"]?.jsonArray.orEmpty().takeIf { it.isNotEmpty() }
                    ?: throw SerializationException("Parameters for ${method.content} notification should not null or be empty.")

                when (method.content) {
                    "blockchain.headers.subscribe" -> params.first().jsonObject.let { header ->
                        val height = header.getValue("height").jsonPrimitive.int
                        val hex = header.getValue("hex").jsonPrimitive.content
                        Either.Left(HeaderSubscriptionResponse(height, BlockHeader.read(hex)))
                    }

                    "blockchain.scripthash.subscribe" -> {
                        val scriptHash = params[0].jsonPrimitive.content
                        val status = params[1].jsonPrimitive.contentOrNull
                        Either.Left(ScriptHashSubscriptionResponse(ByteVector32.fromValidHex(scriptHash), status ?: ""))
                    }

                    else -> throw SerializationException("JSON-RPC Method ${method.content} is not supported")
                }
            }

            else -> Either.Right(json.decodeFromJsonElement(JsonRPCResponseDeserializer(json), jsonObject))
        }
    }

    override val descriptor: SerialDescriptor
        get() = buildClassSerialDescriptor("fr.acinq.lightning.utils.Either")
}

class JsonRPCResponseDeserializer(val json: Json) : KSerializer<JsonRPCResponse> {
    override fun deserialize(decoder: Decoder): JsonRPCResponse {
        val jsonObject = (decoder as? JsonDecoder)?.decodeJsonElement() as? JsonObject ?: throw SerializationException("Expected JsonObject")
        val error = jsonObject["error"]
        return if (error != null && error is JsonPrimitive && error.isString) {
            JsonRPCResponse(id = (jsonObject["id"] as? JsonPrimitive)?.intOrNull ?: 0, error = JsonRPCError(code = 0, error.content))
        } else {
            json.decodeFromJsonElement(JsonRPCResponse.serializer(), jsonObject)
        }
    }

    override fun serialize(encoder: Encoder, value: JsonRPCResponse) {
        throw SerializationException("This ($value) is not meant to be serialized!")
    }

    override val descriptor: SerialDescriptor
        get() = buildClassSerialDescriptor("fr.acinq.lightning.utils.JsonRPCResponse")
}

internal fun parseJsonResponse(request: ElectrumRequest, rpcResponse: JsonRPCResponse): ElectrumResponse =
    if (rpcResponse.error != null) when (request) {
        is BroadcastTransaction -> BroadcastTransactionResponse(request.tx, rpcResponse.error)
        else -> ServerError(
            request = request,
            error = rpcResponse.error
        )
    }
    else if (rpcResponse.id != null && rpcResponse.id < 0) PingResponse
    else when (request) {
        is ServerVersion -> {
            val resultArray = rpcResponse.result.jsonArray
            ServerVersionResponse(resultArray[0].toString(), resultArray[1].toString())
        }

        Ping -> PingResponse
        is GetScriptHashHistory -> {
            val jsonArray = rpcResponse.result.jsonArray
            val items = jsonArray.map {
                val height = it.jsonObject.getValue("height").jsonPrimitive.int
                // Electrum calls this field tx_hash but actually returns the tx_id.
                val txId = TxId(it.jsonObject.getValue("tx_hash").jsonPrimitive.content)
                TransactionHistoryItem(height, txId)
            }
            GetScriptHashHistoryResponse(request.scriptHash, items)
        }

        is ScriptHashListUnspent -> {
            val jsonArray = rpcResponse.result.jsonArray
            val items = jsonArray.map {
                // Electrum calls this field tx_hash but actually returns the tx_id.
                val txId = TxId(it.jsonObject.getValue("tx_hash").jsonPrimitive.content)
                val txPos = it.jsonObject.getValue("tx_pos").jsonPrimitive.int
                val value = it.jsonObject.getValue("value").jsonPrimitive.long
                val height = it.jsonObject.getValue("height").jsonPrimitive.long
                UnspentItem(txId, txPos, value, height)
            }
            ScriptHashListUnspentResponse(request.scriptHash, items)
        }

        is GetTransactionIdFromPosition -> {
            val (txId, merkleProof) = if (rpcResponse.result is JsonPrimitive) {
                Pair(TxId(rpcResponse.result.content), emptyList())
            } else {
                val jsonObject = rpcResponse.result.jsonObject
                // Electrum calls this field tx_hash but actually returns the tx_id.
                val txId = TxId(jsonObject.getValue("tx_hash").jsonPrimitive.content)
                val merkleProof = jsonObject.getValue("merkle").jsonArray.map { ByteVector32.fromValidHex(it.jsonPrimitive.content) }
                Pair(txId, merkleProof)
            }
            GetTransactionIdFromPositionResponse(txId, request.blockHeight, request.txIndex, merkleProof)
        }

        is GetTransaction -> {
            val hex = rpcResponse.result.jsonPrimitive.content
            GetTransactionResponse(Transaction.read(hex), request.contextOpt)
        }

        is ScriptHashSubscription -> {
            val status = when (rpcResponse.result) {
                is JsonPrimitive -> rpcResponse.result.jsonPrimitive.content
                else -> ""
            }
            ScriptHashSubscriptionResponse(request.scriptHash, status)
        }

        is BroadcastTransaction -> {
            val message = rpcResponse.result.jsonPrimitive.content
            // if we got here, it means that the server's response does not contain an error and message should be our
            // transaction id. However, it seems that at least on testnet some servers still use an older version of the
            // Electrum protocol and return an error message in the result field
            val txId = runTrying {
                TxId(message)
            }
            when (txId) {
                is Try.Success -> {
                    if (txId.result == request.tx.txid) BroadcastTransactionResponse(request.tx)
                    else BroadcastTransactionResponse(request.tx, JsonRPCError(1, "response txid $txId does not match request txid ${request.tx.txid}"))
                }
                is Try.Failure -> {
                    BroadcastTransactionResponse(request.tx, JsonRPCError(1, message))
                }
            }
        }

        is GetHeader -> {
            val hex = rpcResponse.result.jsonPrimitive.content
            GetHeaderResponse(request.blockHeight, BlockHeader.read(hex))
        }

        is GetHeaders -> {
            val jsonObject = rpcResponse.result.jsonObject
            val max = jsonObject.getValue("max").jsonPrimitive.int
            val hex = jsonObject.getValue("hex").jsonPrimitive.content

            val blockHeaders = buildList {
                val input = ByteArrayInput(Hex.decode(hex))
                require(input.availableBytes % 80 == 0)

                val headerSize = 80
                var progress = 0
                val inputSize = input.availableBytes
                while (progress < inputSize) {
                    val header = input.readNBytes(headerSize)!!
                    add(BlockHeader.read(header))
                    progress += headerSize
                }
            }

            GetHeadersResponse(request.start_height, blockHeaders, max)
        }

        is GetMerkle -> {
            val jsonObject = rpcResponse.result.jsonObject
            val merkleProof = jsonObject.getValue("merkle").jsonArray.map { ByteVector32.fromValidHex(it.jsonPrimitive.content) }
            val blockHeight = jsonObject.getValue("block_height").jsonPrimitive.int
            val pos = jsonObject.getValue("pos").jsonPrimitive.int
            GetMerkleResponse(request.txid, merkleProof, blockHeight, pos, request.contextOpt)
        }

        is EstimateFees -> {
            val btcperkb: Double? = if (rpcResponse.result.jsonPrimitive.intOrNull == -1) null else rpcResponse.result.jsonPrimitive.double
            val feeratePerKb = btcperkb?.let { FeeratePerKB(Satoshi((it * 100_000_000).toLong())) }
            val feeratePerKw = feeratePerKb?.let { FeeratePerKw(it) }
            EstimateFeeResponse(request.confirmations, feeratePerKw)
        }

        HeaderSubscription -> {
            val jsonObject = rpcResponse.result.jsonObject
            val height = jsonObject.getValue("height").jsonPrimitive.int
            val hex = jsonObject.getValue("hex").jsonPrimitive.content
            HeaderSubscriptionResponse(height, BlockHeader.read(hex))
        }
    }
