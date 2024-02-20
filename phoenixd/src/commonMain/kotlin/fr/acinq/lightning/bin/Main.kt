package fr.acinq.lightning.bin

import co.touchlab.kermit.Severity
import co.touchlab.kermit.loggerConfigInit
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.*
import fr.acinq.bitcoin.Bitcoin
import fr.acinq.lightning.*
import fr.acinq.lightning.Lightning.randomBytes32
import fr.acinq.lightning.bin.conf.Conf
import fr.acinq.lightning.bin.conf.getOrGenerateSeed
import fr.acinq.lightning.bin.db.SqliteChannelsDb
import fr.acinq.lightning.bin.logs.FileLogWriter
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
import okio.FileSystem
import okio.Path.Companion.toPath
import kotlin.time.Duration.Companion.seconds


fun main(args: Array<String>) = Phoenixd().main(args)

@OptIn(DelicateCoroutinesApi::class)
class Phoenixd : CliktCommand() {
    val silent: Boolean by option("--silent", "-s", help = "Silent mode").flag(default = false)
    private val chain by option(help = "Bitcoin chain").switch(
        "--mainnet" to Bitcoin.Chain.Mainnet,
        "--testnet" to Bitcoin.Chain.Testnet
    ).default(Bitcoin.Chain.Mainnet)
    private val datadir by option("--datadir", "-d").convert { it.toPath() }.default(homeDirectory / ".phoenix")

    override fun run() {
        echo("hello")

        FileSystem.SYSTEM.createDirectories(datadir)
        echo("datadir:${FileSystem.SYSTEM.canonicalize(datadir)}")

        val scope = GlobalScope
        val loggerFactory = LoggerFactory(loggerConfigInit(FileLogWriter(datadir / "phoenix.log", scope), minSeverity = Severity.Info))
        val seed = getOrGenerateSeed(datadir)
        val config = Conf(
            chain = chain,
            electrumServer = ServerAddress("testnet1.electrum.acinq.co", 51001, TcpSocket.TLS.DISABLED),
            lsp = Conf.LSP_testnet
        )
        val keyManager = LocalKeyManager(seed, chain, config.lsp.swapInXpub)
        val electrum = ElectrumClient(scope, loggerFactory)
        scope.launch {
            electrum.connect(config.electrumServer, TcpSocket.Builder())
        }
        val walletParams = WalletParams(
            trampolineNode = Conf.LSP_testnet.uri,
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
                zeroConfPeers = setOf(Conf.LSP_testnet.uri.id),
                liquidityPolicy = MutableStateFlow(LiquidityPolicy.Auto(maxAbsoluteFee = 5_000.sat, maxRelativeFeeBasisPoints = 50_00 /* 50% */, skipAbsoluteFeeCheck = false))
            )
        println(nodeParams.nodeId)
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
        while (readln() != "quit") {
        }
        println("stopping")
        api.server.stop()
    }
}