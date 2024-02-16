@file:OptIn(ExperimentalForeignApi::class)

package fr.acinq.lightning.bin

import co.touchlab.kermit.Severity
import co.touchlab.kermit.StaticConfig
import fr.acinq.bitcoin.Bitcoin
import fr.acinq.bitcoin.PublicKey
import fr.acinq.lightning.*
import fr.acinq.lightning.Lightning.randomBytes32
import fr.acinq.lightning.blockchain.electrum.ElectrumClient
import fr.acinq.lightning.blockchain.electrum.ElectrumWatcher
import fr.acinq.lightning.crypto.LocalKeyManager
import fr.acinq.lightning.io.Peer
import fr.acinq.lightning.io.TcpSocket
import fr.acinq.lightning.logging.LoggerFactory
import fr.acinq.lightning.payment.LiquidityPolicy
import fr.acinq.lightning.utils.Connection
import fr.acinq.lightning.utils.ServerAddress
import fr.acinq.lightning.utils.msat
import fr.acinq.lightning.utils.sat
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.staticCFunction
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import platform.posix.SIGINT
import platform.posix.signal
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

@OptIn(DelicateCoroutinesApi::class)
fun main() {
    val loggerFactory = LoggerFactory(StaticConfig(Severity.Info))
    val chain = Bitcoin.Chain.Testnet
    val seed = randomBytes32()
    println("seed=${seed.toHex()}")
    val enduranceSwapInPubkey = "tpubDAmCFB21J9ExKBRPDcVxSvGs9jtcf8U1wWWbS1xTYmnUsuUHPCoFdCnEGxLE3THSWcQE48GHJnyz8XPbYUivBMbLSMBifFd3G9KmafkM9og"
    val keyManager = LocalKeyManager(seed, chain, enduranceSwapInPubkey)
    println("nodeId=${keyManager.nodeKeys.nodeKey.publicKey}")
    val scope = GlobalScope
    val electrum = ElectrumClient(scope, loggerFactory)
    scope.launch {
        electrum.connect(ServerAddress("testnet1.electrum.acinq.co", 51001, TcpSocket.TLS.DISABLED), TcpSocket.Builder())
    }
    val trampolineNodeId = PublicKey.fromHex("03933884aaf1d6b108397e5efe5c86bcf2d8ca8d2f700eda99db9214fc2712b134")
    val trampolineNodeUri = NodeUri(id = trampolineNodeId, "13.248.222.197", 9735)
    val walletParams = WalletParams(
        trampolineNode = trampolineNodeUri,
        trampolineFees = listOf(
            TrampolineFees(
                feeBase = 4.sat,
                feeProportional = 4_000,
                cltvExpiryDelta = CltvExpiryDelta(576)
            )
        ),
        invoiceDefaultRoutingFees = InvoiceDefaultRoutingFees(
            feeBase = 1_000.msat,
            feeProportional = 100,
            cltvExpiryDelta = CltvExpiryDelta(144)
        ),
        swapInParams = SwapInParams(
            minConfirmations = DefaultSwapInParams.MinConfirmations,
            maxConfirmations = DefaultSwapInParams.MaxConfirmations,
            refundDelay = DefaultSwapInParams.RefundDelay,
        ),
    )
    val nodeParams = NodeParams(chain, loggerFactory, keyManager)
        .copy(
            zeroConfPeers = setOf(trampolineNodeId),
            liquidityPolicy = MutableStateFlow(LiquidityPolicy.Auto(maxAbsoluteFee = 5_000.sat, maxRelativeFeeBasisPoints = 50_00 /* 50% */, skipAbsoluteFeeCheck = false))
        )
    val peer = Peer(
        nodeParams = nodeParams,
        walletParams = walletParams,
        watcher = ElectrumWatcher(electrum, scope, loggerFactory),
        db = SqliteDatabases(SqliteChannelsDb(), InMemoryPaymentsDb()),
        socketBuilder = TcpSocket.Builder(),
        scope
    )

    runBlocking {
        peer.connect(connectTimeout = 10.seconds, handshakeTimeout = 10.seconds)
        peer.connectionState.first() { it == Connection.ESTABLISHED }
        peer.registerFcmToken("super-${randomBytes32().toHex()}")
    }

    signal(
        SIGINT,
        staticCFunction<Int, Unit> {
            println("caught CTRL+C")
        }
    )

    scope.launch {
        val api = Api(nodeParams, peer)
        api.start()
    }

    runBlocking { delay(1.hours) }
    
    println("stopping")
    electrum.stop()
    peer.watcher.stop()
    println("cancelling scope")
    scope.cancel()
    println("done")
}