package fr.acinq.lightning.io

import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.Chain
import fr.acinq.bitcoin.PublicKey
import fr.acinq.lightning.*
import fr.acinq.lightning.blockchain.mempool.MempoolSpaceClient
import fr.acinq.lightning.blockchain.mempool.MempoolSpaceWatcher
import fr.acinq.lightning.crypto.LocalKeyManager
import fr.acinq.lightning.db.InMemoryDatabases
import fr.acinq.lightning.tests.utils.testLoggerFactory
import fr.acinq.lightning.utils.Connection
import fr.acinq.lightning.utils.msat
import fr.acinq.lightning.utils.sat
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.dropWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class LeakTests {

    @Test
    fun `connection loop`(): Unit = runBlocking {
        val scope = this
        val loggerFactory = testLoggerFactory

        val trampolineFees = listOf(
            TrampolineFees(
                feeBase = 4.sat,
                feeProportional = 4_000,
                cltvExpiryDelta = CltvExpiryDelta(576)
            )
        )

        val invoiceDefaultRoutingFees = InvoiceDefaultRoutingFees(
            feeBase = 1_000.msat,
            feeProportional = 100,
            cltvExpiryDelta = CltvExpiryDelta(144)
        )

        val swapInParams = SwapInParams(
            minConfirmations = DefaultSwapInParams.MinConfirmations,
            maxConfirmations = DefaultSwapInParams.MaxConfirmations,
            refundDelay = DefaultSwapInParams.RefundDelay,
        )

        val mempoolSpace = MempoolSpaceClient(MempoolSpaceClient.OfficialMempoolTestnet3, loggerFactory)
        val watcher = MempoolSpaceWatcher(mempoolSpace, scope, loggerFactory, pollingInterval = 10.minutes)

        val peer = Peer(
            nodeParams = NodeParams(
                chain = Chain.Testnet3,
                loggerFactory = loggerFactory,
                keyManager = LocalKeyManager(
                    seed = ByteVector("78f2b7cd614bb474e9196a189e45f98efbde20eb73e12d8ece16bdf8f0afefca66c64a9e6fb6c09ca3f94679cb6360022739e74d19bfc0fa338f9809cd1247d1"),
                    chain = Chain.Testnet3,
                    remoteSwapInExtendedPublicKey = "tpubDAmCFB21J9ExKBRPDcVxSvGs9jtcf8U1wWWbS1xTYmnUsuUHPCoFdCnEGxLE3THSWcQE48GHJnyz8XPbYUivBMbLSMBifFd3G9KmafkM9og"
                )
            ),
            walletParams = WalletParams(
                trampolineNode = NodeUri(PublicKey.fromHex("03933884aaf1d6b108397e5efe5c86bcf2d8ca8d2f700eda99db9214fc2712b134"), "13.248.222.197", 9735),
                trampolineFees,
                invoiceDefaultRoutingFees,
                swapInParams
            ),
            client = mempoolSpace,
            watcher = watcher,
            db = InMemoryDatabases(),
            socketBuilder = TcpSocket.Builder(),
            scope = scope
        )
        scope.launch {
            // drop initial CLOSED event
            peer.connectionState.dropWhile { it is Connection.CLOSED }.collect {
                when (it) {
                    Connection.ESTABLISHING -> println("connecting to lightning peer...")
                    Connection.ESTABLISHED -> println("connected to lightning peer")
                    is Connection.CLOSED -> println("disconnected from lightning peer")
                }
            }
        }
        scope.launch {
            while (true) {
                peer.connect(connectTimeout = 10.seconds, handshakeTimeout = 10.seconds)
                delay(1.seconds)
                peer.disconnect()
                delay(1.seconds)
            }
        }
    }
}