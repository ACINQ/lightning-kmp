package fr.acinq.lightning.bin

import co.touchlab.kermit.Severity
import co.touchlab.kermit.StaticConfig
import fr.acinq.bitcoin.Bitcoin
import fr.acinq.lightning.*
import fr.acinq.lightning.Lightning.randomBytes32
import fr.acinq.lightning.bin.db.SqliteChannelsDb
import fr.acinq.lightning.blockchain.electrum.ElectrumClient
import fr.acinq.lightning.blockchain.electrum.ElectrumWatcher
import fr.acinq.lightning.crypto.LocalKeyManager
import fr.acinq.lightning.db.ChannelsDb
import fr.acinq.lightning.db.Databases
import fr.acinq.lightning.db.PaymentsDb
import fr.acinq.lightning.io.Peer
import fr.acinq.lightning.io.TcpSocket
import fr.acinq.lightning.logging.LoggerFactory
import fr.acinq.lightning.payment.LiquidityPolicy
import fr.acinq.lightning.utils.Connection
import fr.acinq.lightning.utils.ServerAddress
import fr.acinq.lightning.utils.msat
import fr.acinq.lightning.utils.sat
import io.ktor.server.engine.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlin.time.Duration.Companion.seconds

@OptIn(DelicateCoroutinesApi::class)
fun main() {
    val datadir = Path(homeDirectory, ".phoenix")
        .also { SystemFileSystem.createDirectories(it) }
    println("datadir:$datadir")

    val loggerFactory = LoggerFactory(StaticConfig(Severity.Info))
    val chain = Bitcoin.Chain.Testnet
    val seed = randomBytes32()
    val config = Config(
        chain = chain,
        electrumServer = ServerAddress("testnet1.electrum.acinq.co", 51001, TcpSocket.TLS.DISABLED),
        lsp = Config.LSP_testnet
    )


    val keyManager = LocalKeyManager(seed, chain, config.lsp.swapInXpub)
    val scope = GlobalScope
    val electrum = ElectrumClient(scope, loggerFactory)
    scope.launch {
        electrum.connect(config.electrumServer, TcpSocket.Builder())
    }
    val walletParams = WalletParams(
        trampolineNode = Config.LSP_testnet.uri,
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
            zeroConfPeers = setOf(Config.LSP_testnet.uri.id),
            liquidityPolicy = MutableStateFlow(LiquidityPolicy.Auto(maxAbsoluteFee = 5_000.sat, maxRelativeFeeBasisPoints = 50_00 /* 50% */, skipAbsoluteFeeCheck = false))
        )
    val peer = Peer(
        nodeParams = nodeParams,
        walletParams = walletParams,
        watcher = ElectrumWatcher(electrum, scope, loggerFactory),
        db = object : Databases {
            override val channels: ChannelsDb
                get() = SqliteChannelsDb(createAppDbDriver(datadir))
            override val payments: PaymentsDb
                get() = InMemoryPaymentsDb()
        },
        socketBuilder = TcpSocket.Builder(),
        scope
    )

    runBlocking {
        peer.connect(connectTimeout = 10.seconds, handshakeTimeout = 10.seconds)
        peer.connectionState.first() { it == Connection.ESTABLISHED }
        peer.registerFcmToken("super-${randomBytes32().toHex()}")
    }

    val api = Api(nodeParams, peer)
    api.server.addShutdownHook {
        println("stopping")
        electrum.stop()
        peer.watcher.stop()
        println("cancelling scope")
        scope.cancel()
        println("done")
    }
    api.server.start()
    while (readln() != "quit") { }
    println("stopping")
    api.server.stop()
}