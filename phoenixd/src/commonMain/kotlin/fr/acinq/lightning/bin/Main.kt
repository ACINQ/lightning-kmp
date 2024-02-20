package fr.acinq.lightning.bin

import co.touchlab.kermit.Severity
import co.touchlab.kermit.loggerConfigInit
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.convert
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.choice
import fr.acinq.bitcoin.Chain
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

class Phoenixd() : CliktCommand() {
    private val datadir by option("--datadir", help = "Data directory").convert { it.toPath() }.default(homeDirectory / ".phoenix", defaultForHelp = "~/.phoenix")
    private val chain by option("--chain", help = "Bitcoin chain to use").choice(
        "mainnet" to Chain.Mainnet,
        "testnet" to Chain.Testnet
    ).default(Chain.Testnet, defaultForHelp = "testnet")
    private val autoLiquidity by option("--auto-liquidity", help = "Amount automatically requested when inbound liquidity is needed").choice(
        "off" to 0.sat,
        "100k" to 100_000.sat,
        "1m" to 1_000_000.sat,
        "2m" to 2_000_000.sat,
        "5m" to 5_000_000.sat,
        "10m" to 10_000_000.sat,
    ).default(1_000_000.sat)
    private val verbose: Boolean by option("--verbose", help = "Verbose mode").flag(default = false)
    private val silent: Boolean by option("--silent", "-s", help = "Silent mode").flag(default = false)

    @OptIn(DelicateCoroutinesApi::class)
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
                liquidityPolicy = MutableStateFlow(LiquidityPolicy.Auto(maxAbsoluteFee = 5_000.sat, maxRelativeFeeBasisPoints = 50_00 /* 50% */, skipAbsoluteFeeCheck = false, maxAllowedCredit = 100_000.sat))
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
            peer.setAutoLiquidityParams(autoLiquidity)
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