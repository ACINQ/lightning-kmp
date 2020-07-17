package fr.acinq.eklair.blockchain.electrum

import fr.acinq.bitcoin.BlockHeader
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.Transaction
import fr.acinq.bitcoin.io.ByteArrayInput
import fr.acinq.bitcoin.io.readNBytes
import fr.acinq.eklair.utils.*
import fr.acinq.secp256k1.Hex
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.UnstableDefault
import kotlinx.serialization.json.*


sealed class Event

private sealed class Action

/**
 * [ElectrumClient] State
 */
private sealed class State {
    abstract fun process(event: Event): Pair<State, List<Action>>
}
private object Connected : State() {
    override fun process(event: Event): Pair<State, List<Action>> {
        TODO("Send some requests?")
    }
}

private object Disconnected : State() {
    override fun process(event: Event): Pair<State, List<Action>> {
        TODO("Retry connection")
    }
}

class ElectrumClient(coroutineScope: CoroutineScope) : CoroutineScope by coroutineScope {
    /**
     * Unique ID to match request / response
     */
    private var requestId = 0
    private var state: State = Disconnected

    /**
     * Keep the track of all pending Electrum request
     */
    private val requests = mutableMapOf<String, ElectrumRequest>()

    companion object {
        const val ELECTRUM_CLIENT_NAME = "3.3.6"
        const val ELECTRUM_PROTOCOL_VERSION = "1.4"

        @OptIn(UnstableDefault::class)
        fun parseJsonResponse(request: ElectrumRequest, rpcResponse: JsonRPCResponse): ElectrumResponse =
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
                            else BroadcastTransactionResponse(request.tx, Error(1, "response txid $result does not match request txid ${request.tx.txid}"))
                        }
                        is Try.Failure -> {
                            BroadcastTransactionResponse(request.tx, Error(1, message))
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

                        val headerSize = 80
                        for (n in headerSize..input.availableBytes step headerSize) {
                            val header = input.readNBytes(headerSize)
                            add(BlockHeader.read(header))
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
    }
}