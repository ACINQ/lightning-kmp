package fr.acinq.lightning.blockchain.electrum

import fr.acinq.bitcoin.*
import fr.acinq.lightning.blockchain.fee.FeeratePerByte
import fr.acinq.lightning.blockchain.fee.FeeratePerKw
import fr.acinq.lightning.blockchain.fee.OnChainFeerates
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
import kotlin.time.Duration.Companion.seconds

/** Commands */
sealed interface ElectrumClientCommand {
    object Connected : ElectrumClientCommand
    object Disconnected : ElectrumClientCommand
}

sealed interface ElectrumConnectionStatus {
    data class Closed(val reason: TcpSocket.IOException?) : ElectrumConnectionStatus
    object Connecting : ElectrumConnectionStatus
    data class Connected(val version: ServerVersionResponse, val height: Int, val header: BlockHeader, val onchainFeeRates: OnChainFeerates) : ElectrumConnectionStatus
}

class ElectrumClient(
    socketBuilder: TcpSocket.Builder?,
    scope: CoroutineScope,
    private val loggerFactory: LoggerFactory,
    defaultExceptionHandler: CoroutineExceptionHandler? = null
) : CoroutineScope by scope, IElectrumClient {

    private val logger = loggerFactory.newLogger(this::class)

    var socketBuilder: TcpSocket.Builder? = socketBuilder
        set(value) {
            logger.debug { "swap socket builder=$value" }
            field = value
        }

    private val json = Json { ignoreUnknownKeys = true }

    // new connection status
    private val _connectionStatus = MutableStateFlow<ElectrumConnectionStatus>(ElectrumConnectionStatus.Closed(null))
    override val connectionStatus: StateFlow<ElectrumConnectionStatus> get() = _connectionStatus.asStateFlow()

    // legacy connection status
    private val _connectionState = MutableStateFlow<Connection>(Connection.CLOSED(null))
    override val connectionState: StateFlow<Connection> get() = _connectionState.asStateFlow()

    // subscriptions notifications (headers, script_hashes, etc.)
    private val _notifications = MutableSharedFlow<ElectrumSubscriptionResponse>(replay = 0, extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.SUSPEND)
    override val notifications: Flow<ElectrumSubscriptionResponse> get() = _notifications.asSharedFlow()

    private sealed interface Action {
        data class SendToServer(val request: Pair<ElectrumRequest, CompletableDeferred<ElectrumResponse>>) : Action
        data class ProcessServerResponse(val response: Either<ElectrumSubscriptionResponse, JsonRPCResponse>) : Action
        object Disconnect : Action
    }

    private var mailbox = Channel<Action>()

    private val statusJob: Job
    private var runJob: Job? = null

    init {
        logger.info { "initializing electrum client" }
        statusJob = launch {
            fun convert(input: ElectrumConnectionStatus): Connection = when (input) {
                is ElectrumConnectionStatus.Connecting -> Connection.ESTABLISHING
                is ElectrumConnectionStatus.Connected -> Connection.ESTABLISHED
                is ElectrumConnectionStatus.Closed -> Connection.CLOSED(input.reason)
            }

            var previousState = convert(_connectionStatus.value)
            _connectionStatus.map { convert(it) }.filter { it != previousState }.collect {
                logger.info { "connection state changed: ${it::class.simpleName}" }
                _connectionState.value = it
                previousState = it
            }
        }
    }

    fun connect(serverAddress: ServerAddress) {
        if (_connectionStatus.value is ElectrumConnectionStatus.Closed) {
            runJob = establishConnection(serverAddress)
        } else logger.warning { "electrum client is already running" }
    }

    @OptIn(DelicateCoroutinesApi::class)
    fun disconnect() {
        launch {
            if (!mailbox.isClosedForSend) {
                mailbox.send(Action.Disconnect)
            }
        }
    }

    val exceptionHandler = defaultExceptionHandler ?: CoroutineExceptionHandler { _, exception ->
        logger.error(exception) { "error starting electrum client" }
    }

    private fun establishConnection(serverAddress: ServerAddress) = launch(exceptionHandler) {
        _connectionStatus.value = ElectrumConnectionStatus.Connecting
        val socket: TcpSocket = try {
            val (host, port, tls) = serverAddress
            logger.info { "attempting connection to electrumx instance [host=$host, port=$port, tls=$tls]" }
            socketBuilder?.connect(host, port, tls, loggerFactory) ?: error("socket builder is null.")
        } catch (ex: Throwable) {
            logger.warning(ex) { "TCP connect: ${ex.message}: " }
            val ioException = when (ex) {
                is TcpSocket.IOException -> ex
                else -> TcpSocket.IOException.ConnectionRefused(ex)
            }
            _connectionStatus.value = ElectrumConnectionStatus.Closed(ioException)
            return@launch
        }

        logger.info { "connected to electrumx instance" }

        fun closeSocket(ex: TcpSocket.IOException?) {
            if (_connectionStatus.value is ElectrumConnectionStatus.Closed) return
            logger.warning(ex) { "closing TCP socket: " }
            socket.close()
            _connectionStatus.value = ElectrumConnectionStatus.Closed(ex)
            mailbox.close(ex)
            cancel()
        }

        suspend fun sendRequest(request: ElectrumRequest, requestId: Int) {
            val bytes = request.asJsonRPCRequest(requestId).encodeToByteArray()
            try {
                socket.send(bytes, flush = true)
            } catch (ex: TcpSocket.IOException) {
                logger.warning { "cannot send to electrum server" }
                closeSocket(ex)
            }
        }

        val flow = socket.linesFlow().map { json.decodeFromString(ElectrumResponseDeserializer, it) }
        val rpcFlow = flow.filterIsInstance<Either.Right<Nothing, JsonRPCResponse>>().map { it.value }
        var requestId = 0

        val version = ServerVersion()
        sendRequest(version, requestId++)
        val theirVersion = parseJsonResponse(version, rpcFlow.first())
        require(theirVersion is ServerVersionResponse) { "invalid server version response $theirVersion" }
        logger.info { "server version $theirVersion" }

        sendRequest(HeaderSubscription, requestId++)
        val header = parseJsonResponse(HeaderSubscription, rpcFlow.first())
        require(header is HeaderSubscriptionResponse) { "invalid header subscription response $header" }

        suspend fun estimateFee(confirmations: Int): EstimateFeeResponse {
            val request = EstimateFees(confirmations)
            sendRequest(request, requestId++)
            val response = parseJsonResponse(request, rpcFlow.first())
            require(response is EstimateFeeResponse) { "invalid estimatefee response $response" }
            return response
        }

        val fees = listOf(estimateFee(2), estimateFee(6), estimateFee(18), estimateFee(144))
        logger.info { "onchain fees $fees" }
        val feeRates = OnChainFeerates(
            fundingFeerate = fees[3].feerate ?: FeeratePerKw(FeeratePerByte(2.sat)),
            mutualCloseFeerate = fees[2].feerate ?: FeeratePerKw(FeeratePerByte(10.sat)),
            claimMainFeerate = fees[1].feerate ?: FeeratePerKw(FeeratePerByte(20.sat)),
            fastFeerate = fees[0].feerate ?: FeeratePerKw(FeeratePerByte(50.sat))
        )
        _notifications.emit(header)
        _connectionStatus.value = ElectrumConnectionStatus.Connected(theirVersion, header.blockHeight, header.header, feeRates)
        logger.info { "server tip $header" }

        // pending requests map
        val requestMap = mutableMapOf<Int, Pair<ElectrumRequest, CompletableDeferred<ElectrumResponse>>>()

        // reset mailbox
        mailbox.cancel(CancellationException("connection in progress"))
        mailbox = Channel()

        suspend fun ping() {
            while (isActive) {
                delay(30.seconds)
                val pong = rpcCall<PingResponse>(Ping)
                logger.debug { "received ping response $pong" }
            }
        }

        suspend fun respond() {
            for (msg in mailbox) {
                when (msg) {
                    is Action.SendToServer -> {
                        requestMap[requestId] = msg.request
                        sendRequest(msg.request.first, requestId++)
                    }
                    is Action.ProcessServerResponse -> when (msg.response) {
                        is Either.Left -> _notifications.emit(msg.response.value)
                        is Either.Right -> msg.response.value.id?.let { id ->
                            requestMap.remove(id)?.let { (request, replyTo) ->
                                replyTo.complete(parseJsonResponse(request, msg.response.value))
                            }
                        }
                    }
                    is Action.Disconnect -> {
                        closeSocket(null)
                    }
                }
            }
        }

        suspend fun listen() {
            try {
                flow.collect { response -> mailbox.send(Action.ProcessServerResponse(response)) }
                closeSocket(null)
            } catch (ex: TcpSocket.IOException) {
                logger.warning { "TCP receive: ${ex.message}" }
                closeSocket(ex)
            }
        }

        launch { ping() }
        launch { respond() }

        listen() // This suspends until the coroutine is cancelled or the socket is closed
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
        // NB: disconnecting cancels the output channel
        disconnect()
        // Cancel coroutine jobs
        statusJob.cancel()
        runJob?.cancel()
        // Cancel event channel
        mailbox.cancel()
    }

    companion object {
        const val ELECTRUM_CLIENT_NAME = "3.3.6"
        const val ELECTRUM_PROTOCOL_VERSION = "1.4"
        val version = ServerVersion()
        internal fun computeScriptHash(publicKeyScript: ByteVector): ByteVector32 = Crypto.sha256(publicKeyScript).toByteVector32().reversed()
    }
}
