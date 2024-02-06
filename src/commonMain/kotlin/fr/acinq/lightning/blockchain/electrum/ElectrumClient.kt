package fr.acinq.lightning.blockchain.electrum

import fr.acinq.bitcoin.*
import fr.acinq.lightning.blockchain.fee.FeeratePerKw
import fr.acinq.lightning.io.TcpSocket
import fr.acinq.lightning.io.send
import fr.acinq.lightning.logging.*
import fr.acinq.lightning.utils.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.Channel.Factory.RENDEZVOUS
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.Json
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

sealed class ElectrumConnectionStatus {
    data class Closed(val reason: TcpSocket.IOException?) : ElectrumConnectionStatus()
    data object Connecting : ElectrumConnectionStatus()
    data class Connected(val version: ServerVersionResponse, val height: Int, val header: BlockHeader) : ElectrumConnectionStatus()

    fun toConnectionState(): Connection = when (this) {
        is Closed -> Connection.CLOSED(this.reason)
        Connecting -> Connection.ESTABLISHING
        is Connected -> Connection.ESTABLISHED
    }
}

class ElectrumClient(
    private val scope: CoroutineScope,
    private val loggerFactory: LoggerFactory,
    private val pingInterval: Duration = 30.seconds,
    private var rpcTimeout: Duration = 10.seconds
) : IElectrumClient {

    private val logger = loggerFactory.newLogger(this::class)

    private val json = Json { ignoreUnknownKeys = true }

    // connection status
    private val _connectionStatus = MutableStateFlow<ElectrumConnectionStatus>(ElectrumConnectionStatus.Closed(null))
    override val connectionStatus: StateFlow<ElectrumConnectionStatus> get() = _connectionStatus.asStateFlow()

    // subscriptions notifications (headers, script_hashes, etc.)
    private val _notifications = MutableSharedFlow<ElectrumSubscriptionResponse>(replay = 0, extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.SUSPEND)
    override val notifications: Flow<ElectrumSubscriptionResponse> get() = _notifications.asSharedFlow()

    private sealed class Action {
        data class SendToServer(val request: Pair<ElectrumRequest, CompletableDeferred<ElectrumResponse>>) : Action()
        data class ProcessServerResponse(val response: Either<ElectrumSubscriptionResponse, JsonRPCResponse>) : Action()
    }

    // This channel acts as a queue for messages sent/received to/from the electrum server.
    // It lets us decouple message processing from the connection state (messages will be queued while we're disconnected).
    // We use a rendezvous channel, which means that writers will simply suspend until a read operation happens.
    // When connected, a dedicated coroutine will continuously read from the mailbox, send the corresponding requests to the
    // electrum server, and send the response back when it is received.
    // When disconnected, callers will suspend until we reconnect, at which point their messages will be processed.
    private val mailbox = Channel<Action>(capacity = RENDEZVOUS)

    data class ListenJob(val job: Job, val socket: TcpSocket) {
        fun cancel() {
            job.cancel()
            socket.close()
        }
    }

    private var listenJob: ListenJob? = null

    suspend fun connect(serverAddress: ServerAddress, socketBuilder: TcpSocket.Builder, timeout: Duration = 15.seconds): Boolean {
        if (_connectionStatus.value is ElectrumConnectionStatus.Closed) {
            listenJob?.cancel() // the job should already be cancelled, this is for extra safety
            val socket = openSocket(serverAddress, socketBuilder, timeout) ?: return false
            logger.info { "connected to electrumx instance" }
            return try {
                handshake(socket, timeout)
                listenJob = listen(socket)
                true
            } catch (ex: Throwable) {
                logger.warning { "electrum connection handshake failed: ${ex.message}" }
                val ioException = when (ex) {
                    is TcpSocket.IOException -> ex
                    else -> TcpSocket.IOException.Unknown(ex.message, ex)
                }
                socket.close()
                _connectionStatus.value = ElectrumConnectionStatus.Closed(ioException)
                false
            }
        } else {
            logger.warning { "ignoring connection request, electrum client is already running" }
            return false
        }
    }

    fun disconnect() {
        listenJob?.cancel()
        listenJob = null
        _connectionStatus.value = ElectrumConnectionStatus.Closed(null)
    }

    private suspend fun openSocket(serverAddress: ServerAddress, socketBuilder: TcpSocket.Builder, timeout: Duration): TcpSocket? {
        var socket: TcpSocket? = null
        return try {
            _connectionStatus.value = ElectrumConnectionStatus.Connecting
            val (host, port, tls) = serverAddress
            logger.info { "attempting connection to electrumx instance [host=$host, port=$port, tls=$tls]" }
            withTimeout(timeout) {
                socket = socketBuilder.connect(host, port, tls, loggerFactory)
                socket
            }
        } catch (ex: Throwable) {
            logger.warning { "could not connect to electrum server: ${ex.message}" }
            val ioException = when (ex) {
                is TcpSocket.IOException -> ex
                else -> TcpSocket.IOException.ConnectionRefused(ex)
            }
            socket?.close()
            _connectionStatus.value = ElectrumConnectionStatus.Closed(ioException)
            null
        }
    }

    /**
     * We fetch some general information about the Electrum server and subscribe to receive block headers.
     * This function will throw if the server returns unexpected content or the connection is closed.
     */
    private suspend fun handshake(socket: TcpSocket, timeout: Duration) {
        withTimeout(timeout) {
            val handshakeFlow = linesFlow(socket)
                .map { json.decodeFromString(ElectrumResponseDeserializer, it) }
                .filterIsInstance<Either.Right<Nothing, JsonRPCResponse>>()
                .map { it.value }
            // Note that since we synchronously wait for the response, we can safely reuse requestId = 0.
            val ourVersion = ServerVersion()
            socket.send(ourVersion.asJsonRPCRequest(id = 0).encodeToByteArray(), flush = true)
            val theirVersion = parseJsonResponse(ourVersion, handshakeFlow.first())
            require(theirVersion is ServerVersionResponse) { "invalid server version response $theirVersion" }
            logger.info { "server version $theirVersion" }
            socket.send(HeaderSubscription.asJsonRPCRequest(id = 0).encodeToByteArray(), flush = true)
            val header = parseJsonResponse(HeaderSubscription, handshakeFlow.first())
            require(header is HeaderSubscriptionResponse) { "invalid header subscription response $header" }
            _notifications.emit(header)
            _connectionStatus.value = ElectrumConnectionStatus.Connected(theirVersion, header.blockHeight, header.header)
            logger.info { "server tip $header" }
        }
    }

    /**
     * Start a background coroutine that sends/receive on the TCP socket with the remote server.
     *
     * This uses the coroutine scope of the [ElectrumClient], not the scope of the caller, and uses supervision
     * to ensure that its failure doesn't propagate to the parent, which is likely the application's main scope.
     *
     * If this job fails or is cancelled, we clean up resources and set the [connectionStatus] to [ElectrumConnectionStatus.Closed].
     * The wallet application can then decide to automatically reconnect or switch to a different Electrum server.
     */
    private fun listen(socket: TcpSocket): ListenJob {
        // We use a SupervisorJob to ensure that our CoroutineExceptionHandler is used and exceptions don't propagate
        // to our parent scope: we simply disconnect and wait for the application to initiate a reconnection.
        val job = scope.launch(CoroutineName("electrum-client") + SupervisorJob() + CoroutineExceptionHandler { _, ex ->
            logger.warning { "electrum connection error: ${ex.message}" }
            socket.close()
            val ioException = when (ex) {
                is TcpSocket.IOException -> ex
                else -> TcpSocket.IOException.Unknown(ex.message, ex)
            }
            _connectionStatus.value = ElectrumConnectionStatus.Closed(ioException)
        }) {
            launch(CoroutineName("keep-alive")) {
                while (isActive) {
                    delay(pingInterval)
                    val pong = rpcCall<PingResponse>(Ping)
                    logger.debug { "received ping response $pong" }
                }
            }

            launch(CoroutineName("process")) {
                var requestId = 0
                val requestMap = mutableMapOf<Int, Pair<ElectrumRequest, CompletableDeferred<ElectrumResponse>>>()
                for (msg in mailbox) {
                    when (msg) {
                        is Action.SendToServer -> {
                            requestMap[requestId] = msg.request
                            socket.send(msg.request.first.asJsonRPCRequest(requestId++).encodeToByteArray(), flush = true)
                        }
                        is Action.ProcessServerResponse -> when (msg.response) {
                            is Either.Left -> _notifications.emit(msg.response.value)
                            is Either.Right -> msg.response.value.id?.let { id ->
                                requestMap.remove(id)?.let { (request, replyTo) ->
                                    replyTo.complete(parseJsonResponse(request, msg.response.value))
                                }
                            }
                        }
                    }
                }
            }

            val listenJob = launch(CoroutineName("listen")) {
                val flow = linesFlow(socket).map { json.decodeFromString(ElectrumResponseDeserializer, it) }
                flow.collect { response -> mailbox.send(Action.ProcessServerResponse(response)) }
            }

            // Suspend until the coroutine is cancelled or the socket is closed.
            listenJob.join()
        }

        return ListenJob(job, socket)
    }

    /** This can be used to set longer timeouts if we detect a slow connection (e.g. Tor). */
    fun setRpcTimeout(timeout: Duration) {
        rpcTimeout = timeout
    }

    /**
     * We may never get a response to our requests, because the electrum protocol is fully asynchronous (we write a
     * request on the TCP socket, and at some point in the future the server may send us a response).
     *
     * This can for example happen if:
     *  - the server drops our request for whatever reason
     *  - we get disconnected after sending the request and reconnect to a different server
     *
     * We can mitigate that by retrying after a delay and disconnecting from servers that don't seem to be responsive.
     *
     * @param replyTo callers should use the same replyTo for retries: this guarantees that if a previous attempt
     * succeeds after its timeout, we will return the result and ignore the retries.
     */
    private suspend inline fun rpcCallWithTimeout(replyTo: CompletableDeferred<ElectrumResponse>, request: ElectrumRequest): ElectrumResponse? {
        // Sending to the mailbox suspends until the request is picked up and written to the TCP socket.
        // We explicitly keep this out of the timeout, which lets us suspend until we're connected to an electrum server.
        mailbox.send(Action.SendToServer(Pair(request, replyTo)))
        // We don't need to catch exceptions thrown by await(): our code only completes it with valid results.
        return withTimeoutOrNull(rpcTimeout) { replyTo.await() }
    }

    /** Send a request using timeouts, retrying once and giving up after that retry. */
    private suspend inline fun <reified T : ElectrumResponse> rpcCall(request: ElectrumRequest): Either<ServerError, T> {
        val replyTo = CompletableDeferred<ElectrumResponse>()
        val result = rpcCallWithTimeout(replyTo, request)
            ?: rpcCallWithTimeout(replyTo, request)
            ?: ServerError(request, JsonRPCError(0, "timeout"))
        return when (result) {
            is ServerError -> {
                when (request) {
                    // Some electrum servers don't seem to respond to ping requests, even though they keep the connection alive.
                    is Ping -> logger.debug { "received error for ${request.method}: ${result.error.message}" }
                    else -> logger.warning { "received error for ${request.method}: ${result.error.message}" }
                }
                Either.Left(result)
            }
            else -> Either.Right(result as T)
        }
    }

    /** Send a request until we get a response, disconnecting from servers that don't send a valid response. */
    private suspend fun rpcCallMustSucceed(request: ElectrumRequest): ElectrumResponse {
        val replyTo = CompletableDeferred<ElectrumResponse>()
        val result = rpcCallWithTimeout(replyTo, request)
            ?: rpcCallWithTimeout(replyTo, request)
            ?: ServerError(request, JsonRPCError(0, "timeout"))
        return when (result) {
            is ServerError -> {
                logger.warning { "received error for ${request.method}: ${result.error.message}, disconnecting..." }
                disconnect()
                rpcCallMustSucceed(request)
            }
            else -> result
        }
    }

    override suspend fun getTx(txId: TxId): Transaction? = rpcCall<GetTransactionResponse>(GetTransaction(txId)).right?.tx

    override suspend fun getHeader(blockHeight: Int): BlockHeader? = rpcCall<GetHeaderResponse>(GetHeader(blockHeight)).right?.header

    override suspend fun getHeaders(startHeight: Int, count: Int): List<BlockHeader> = rpcCall<GetHeadersResponse>(GetHeaders(startHeight, count)).right?.headers ?: listOf()

    override suspend fun getMerkle(txId: TxId, blockHeight: Int, contextOpt: Transaction?): GetMerkleResponse? = rpcCall<GetMerkleResponse>(GetMerkle(txId, blockHeight, contextOpt)).right

    override suspend fun getScriptHashHistory(scriptHash: ByteVector32): List<TransactionHistoryItem> = rpcCall<GetScriptHashHistoryResponse>(GetScriptHashHistory(scriptHash)).right?.history ?: listOf()

    override suspend fun getScriptHashUnspents(scriptHash: ByteVector32): List<UnspentItem> = rpcCall<ScriptHashListUnspentResponse>(ScriptHashListUnspent(scriptHash)).right?.unspents ?: listOf()

    override suspend fun broadcastTransaction(tx: Transaction): TxId = rpcCall<BroadcastTransactionResponse>(BroadcastTransaction(tx)).right?.tx?.txid ?: tx.txid

    override suspend fun estimateFees(confirmations: Int): FeeratePerKw? = rpcCall<EstimateFeeResponse>(EstimateFees(confirmations)).right?.feerate

    override suspend fun startScriptHashSubscription(scriptHash: ByteVector32): ScriptHashSubscriptionResponse = rpcCallMustSucceed(ScriptHashSubscription(scriptHash)) as ScriptHashSubscriptionResponse

    override suspend fun startHeaderSubscription(): HeaderSubscriptionResponse = rpcCallMustSucceed(HeaderSubscription) as HeaderSubscriptionResponse

    /** Stop this instance for good, the client cannot be used after it has been closed. */
    fun stop() {
        logger.info { "electrum client stopping" }
        disconnect()
        mailbox.cancel()
    }

    companion object {
        const val ELECTRUM_CLIENT_NAME = "3.3.6"
        const val ELECTRUM_PROTOCOL_VERSION = "1.4"
        val version = ServerVersion()
        internal fun computeScriptHash(publicKeyScript: ByteVector): ByteVector32 = Crypto.sha256(publicKeyScript).toByteVector32().reversed()
    }
}
