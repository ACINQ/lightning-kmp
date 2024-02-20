package fr.acinq.lightning.bin

import co.touchlab.kermit.Severity
import co.touchlab.kermit.loggerConfigInit
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.context
import com.github.ajalt.clikt.output.MordantHelpFormatter
import com.github.ajalt.clikt.parameters.options.*
import com.github.ajalt.clikt.parameters.types.choice
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.sources.MapValueSource
import fr.acinq.bitcoin.Bitcoin
import fr.acinq.lightning.Lightning.randomBytes32
import fr.acinq.lightning.NodeParams
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
import fr.acinq.lightning.utils.Connection
import fr.acinq.lightning.utils.ServerAddress
import fr.acinq.lightning.utils.sat
import io.ktor.server.engine.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import okio.FileSystem
import okio.Path.Companion.toPath
import kotlin.time.Duration.Companion.seconds


fun main(args: Array<String>) {
    val additionalValues = try {
        val datadirParser = DatadirParser()
        datadirParser.parse(args)
        buildMap {
            val confFile = datadirParser.datadir / "phoenix.conf"
            if (FileSystem.SYSTEM.exists(confFile))
                FileSystem.SYSTEM.read(confFile) {
                    while (true) {
                        val line = readUtf8Line() ?: break
                        line.split("=").run { put(first(), last()) }
                    }
                }
        }
    } catch (t: Throwable) {
        emptyMap()
    }
    Phoenixd(additionalValues).main(args)
}

class DatadirParser : CliktCommand() {
    val datadir by option("--datadir", "-d", help = "Data directory", valueSourceKey = "path").convert { it.toPath() }.default(homeDirectory / ".phoenix")
    override fun run() {}
}

class Phoenixd(private val additionalValues: Map<String, String> = emptyMap()) : CliktCommand() {
    private val datadir by option("--datadir", help = "Data directory").convert { it.toPath() }.default(homeDirectory / ".phoenix", defaultForHelp = "~/.phoenix")
    private val chain by option("--chain", help = "Bitcoin chain to use").choice(
        "mainnet" to Bitcoin.Chain.Mainnet,
        "testnet" to Bitcoin.Chain.Testnet
    ).default(Bitcoin.Chain.Mainnet, defaultForHelp = "mainnet")
    private val liquidityTranche by option("--liquidity-tranche", help = "Amount requested when inbound liquidity is needed").int().convert { it.sat }.prompt()
    private val silent: Boolean by option("--silent", "-s", help = "Silent mode").flag(default = false)

    init {
        context {
            valueSource = MapValueSource(additionalValues)
            helpFormatter = { MordantHelpFormatter(it, showDefaultValues = true) }
        }
    }

    @OptIn(DelicateCoroutinesApi::class)
    override fun run() {
        echo("hello")

        FileSystem.SYSTEM.createDirectories(datadir)
        echo("datadir:${FileSystem.SYSTEM.canonicalize(datadir)}")
        echo("chain=$chain")
        echo("liquidityTranche=$liquidityTranche")

        val scope = GlobalScope
        val loggerFactory = LoggerFactory(loggerConfigInit(FileLogWriter(datadir / "phoenix.log", scope), minSeverity = Severity.Info))
        val seed = getOrGenerateSeed(datadir)
        val config = Conf(
            chain = chain,
            electrumServer = ServerAddress("testnet1.electrum.acinq.co", 51001, TcpSocket.TLS.DISABLED),
            liquidityTranche = liquidityTranche
        )
        val keyManager = LocalKeyManager(seed, chain, config.lsp.swapInXpub)
        val electrum = ElectrumClient(scope, loggerFactory)

        scope.launch {
            electrum.connect(config.electrumServer, TcpSocket.Builder())
        }
        val nodeParams = NodeParams(chain, loggerFactory, keyManager)
            .copy(
                zeroConfPeers = setOf(config.lsp.walletParams.trampolineNode.id),
                liquidityPolicy = MutableStateFlow(config.liquidityPolicy)
            )
        println(nodeParams.nodeId)
        val peer = Peer(
            nodeParams = nodeParams,
            walletParams = config.lsp.walletParams,
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