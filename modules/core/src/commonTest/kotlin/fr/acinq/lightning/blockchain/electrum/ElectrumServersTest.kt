package fr.acinq.lightning.blockchain.electrum

import fr.acinq.bitcoin.TxId
import fr.acinq.lightning.blockchain.fee.FeeratePerByte
import fr.acinq.lightning.io.TcpSocket
import fr.acinq.lightning.tests.utils.runSuspendTest
import fr.acinq.lightning.tests.utils.testLoggerFactory
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTimedValue


/**
 * This test checks if servers configured in the preset electrum list are valid.
 *
 * Public list of servers: https://github.com/spesmilo/electrum/blob/master/electrum/chains/mainnet/servers.json
 *
 * Some servers use self-signed certificates, for those we use a pinned pubkey. To update the public key manually:
 * ```
 * openssl s_client -connect host:port -servername host </dev/null 2>/dev/null | openssl x509 -pubkey -noout
 * ```
 */
@Ignore
class ElectrumServersTest {

    @Test
    fun `connect to testnet servers`() = runSuspendTest(timeout = 1.minutes) {
        ElectrumServers.testnetElectrumServers.forEach { server ->
            println("-------- testing [ ${server.host}:${server.port} ${server.tls::class.simpleName} ] --------")
            val client = ElectrumClient(this, testLoggerFactory)
            withTimeout(5.seconds) {
                val (status, t) = measureTimedValue {
                    client.connect(server, TcpSocket.Builder())
                    client.connectionStatus.filterIsInstance<ElectrumConnectionStatus.Connected>().first()
                }
                println("✅ [${t.inWholeMilliseconds} ms] connected at height=${status.height}")
                client
            }
            client.stop()
        }
    }

    @Test
    fun `connect to mainnet servers`() = runSuspendTest(timeout = 1.minutes) {
        ElectrumServers.mainnetElectrumServers.forEach { server ->
            println("-------- testing [ ${server.host}:${server.port} ${server.tls::class.simpleName} ] --------")
            val client = ElectrumClient(this, testLoggerFactory)
            try {
                withTimeout(5.seconds) {
                    val (status, t) = measureTimedValue {
                        client.connect(server, TcpSocket.Builder())
                        client.connectionStatus.filterIsInstance<ElectrumConnectionStatus.Connected>().first()
                    }
                    println("✅ [${t.inWholeMilliseconds} ms] connected at height=${status.height}")
                }
                // evaluate the server's estimate-fee performance
                withTimeout(10.seconds) {
                    val (fees, t) = measureTimedValue {
                        client.estimateFees(1)
                    }
                    println("✅ [${t.inWholeMilliseconds} ms] next block is ${fees?.let { FeeratePerByte(it) }}")
                }
                // evaluate the server's get-confirmation count on a random transaction
                withTimeout(10.seconds) {
                    val (tx, t1) = measureTimedValue {
                        client.getTx(TxId("6703c9dc67a2d66ec027b2aaa4016e72dcb7ecc5fe6f4a270a35bdf52f3c4eeb"))
                    }
                    assertNotNull(tx)
                    println("✅ [${t1.inWholeMilliseconds} ms] tx found")
                    val (conf, t2) = measureTimedValue {
                        client.getConfirmations(tx)
                    }
                    println("✅ [${t2.inWholeMilliseconds} ms] tx reached $conf confs")
                }
            } catch(_: TimeoutCancellationException) {
                println("❌ ${server.host}:${server.port} is too slow")
            }
            client.stop()
        }
    }
}