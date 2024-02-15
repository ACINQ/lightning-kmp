package fr.acinq.lightning.bin

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
import fr.acinq.lightning.utils.ServerAddress
import fr.acinq.lightning.utils.msat
import fr.acinq.lightning.utils.sat
import kotlinx.coroutines.*

@OptIn(DelicateCoroutinesApi::class)
fun main() {
    val loggerFactory = LoggerFactory(StaticConfig())
    val chain = Bitcoin.Chain.Testnet
    val seed = randomBytes32()
    val enduranceSwapInPubkey = "tpubDAmCFB21J9ExKBRPDcVxSvGs9jtcf8U1wWWbS1xTYmnUsuUHPCoFdCnEGxLE3THSWcQE48GHJnyz8XPbYUivBMbLSMBifFd3G9KmafkM9og"
    val keyManager = LocalKeyManager(seed, chain, enduranceSwapInPubkey)
    val scope = GlobalScope
    val electrum = ElectrumClient(scope, loggerFactory)
    scope.launch {
        electrum.connect(ServerAddress("testnet1.electrum.acinq.co", 51002, TcpSocket.TLS.UNSAFE_CERTIFICATES), TcpSocket.Builder())
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
    val peer = Peer(
        nodeParams = NodeParams(chain, loggerFactory, keyManager),
        walletParams = walletParams,
        watcher = ElectrumWatcher(electrum, scope, loggerFactory),
        db = SqliteDatabases(SqliteChannelsDb(), SqlitePaymentsDb()),
        socketBuilder = TcpSocket.Builder(),
        scope
    )
    println("started peer $peer")
}