package fr.acinq.lightning.blockchain.electrum

import fr.acinq.bitcoin.*
import fr.acinq.lightning.io.TcpSocket
import fr.acinq.lightning.io.send
import fr.acinq.lightning.utils.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.Json
import org.kodein.log.LoggerFactory
import org.kodein.log.newLogger
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

sealed class ElectrumConnectionStatus {
    data class Closed(val reason: TcpSocket.IOException?) : ElectrumConnectionStatus()
    object Connecting : ElectrumConnectionStatus()
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
    private val pingInterval: Duration = 30.seconds
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

    // This channel acts as a queue for messages sent/received to the electrum server.
    // It lets us decouple message processing from the connection state (messages can be queued while we're connecting).
    private val mailbox = Channel<Action>()

    data class ListenJob(val job: Job, val socket: TcpSocket) {
        fun cancel() {
            job.cancel()
            socket.close()
        }
    }

    private var listenJob: ListenJob? = null

    suspend fun connect(serverAddress: ServerAddress, socketBuilder: TcpSocket.Builder, timeout: Duration = 15.seconds): Boolean {
        if (_connectionStatus.value is ElectrumConnectionStatus.Closed) {
            listenJob?.cancel()
            val socket = openSocket(serverAddress, socketBuilder, timeout) ?: return false
            logger.info { "connected to electrumx instance" }
            return try {
                handshake(socket, timeout)
                listenJob = listen(socket)
                true
            } catch (ex: Throwable) {
                logger.warning(ex) { "electrum connection handshake failed: " }
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
        return try {
            _connectionStatus.value = ElectrumConnectionStatus.Connecting
            val (host, port, tls) = serverAddress
            logger.info { "attempting connection to electrumx instance [host=$host, port=$port, tls=$tls]" }
            withTimeout(timeout) {
                socketBuilder.connect(host, port, tls, loggerFactory)
            }
        } catch (ex: Throwable) {
            logger.warning(ex) { "could not connect to electrum server: " }
            val ioException = when (ex) {
                is TcpSocket.IOException -> ex
                else -> TcpSocket.IOException.ConnectionRefused(ex)
            }
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
            delay(1.seconds)
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
        val job = scope.launch(CoroutineName("electrum-client") + SupervisorJob() + CoroutineExceptionHandler { _, ex ->
            logger.warning(ex) { "electrum connection error: " }
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

    override suspend fun send(request: ElectrumRequest, replyTo: CompletableDeferred<ElectrumResponse>) {
        mailbox.send(Action.SendToServer(Pair(request, replyTo)))
    }

    suspend inline fun <reified T : ElectrumResponse> rpcCall(request: ElectrumRequest): T {
        val replyTo = CompletableDeferred<ElectrumResponse>()
        send(request, replyTo)
        return when (val res = replyTo.await()) {
            is ServerError -> error(res)
            else -> res as T
        }
    }

    override suspend fun getTx(txid: ByteVector32): Transaction = rpcCall<GetTransactionResponse>(GetTransaction(txid)).tx

    override suspend fun getMerkle(txid: ByteVector32, blockHeight: Int, contextOpt: Transaction?): GetMerkleResponse = rpcCall<GetMerkleResponse>(GetMerkle(txid, blockHeight, contextOpt))

    override suspend fun getScriptHashHistory(scriptHash: ByteVector32): List<TransactionHistoryItem> = rpcCall<GetScriptHashHistoryResponse>(GetScriptHashHistory(scriptHash)).history

    override suspend fun getScriptHashUnspents(scriptHash: ByteVector32): List<UnspentItem> = rpcCall<ScriptHashListUnspentResponse>(ScriptHashListUnspent(scriptHash)).unspents

    override suspend fun startScriptHashSubscription(scriptHash: ByteVector32): ScriptHashSubscriptionResponse = rpcCall(ScriptHashSubscription(scriptHash))

    override suspend fun startHeaderSubscription(): HeaderSubscriptionResponse = rpcCall(HeaderSubscription)

    override suspend fun broadcastTransaction(tx: Transaction): BroadcastTransactionResponse = rpcCall(BroadcastTransaction(tx))

    override suspend fun estimateFees(confirmations: Int): EstimateFeeResponse = rpcCall(EstimateFees(confirmations))

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
