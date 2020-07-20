package fr.acinq.eklair.blockchain.electrum

import fr.acinq.eklair.utils.Either
import fr.acinq.eklair.utils.JsonRPCResponse
import io.ktor.network.selector.*
import io.ktor.network.sockets.*
import io.ktor.util.*
import io.ktor.util.cio.*
import io.ktor.utils.io.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.consumeEach
import kotlinx.serialization.UnstableDefault
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonConfiguration
import org.kodein.log.LoggerFactory
import org.kodein.log.newLogger
import java.net.InetSocketAddress
import kotlin.test.Test

@OptIn(KtorExperimentalAPI::class, UnstableDefault::class)
/**
 * TODO Garbage test ; this need to be automated at some point
 */
class ElectrumClientTest {

    private val logger = LoggerFactory.default.newLogger(ElectrumClientTest::class)
    private val json = Json(JsonConfiguration.Default.copy(ignoreUnknownKeys = true))

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `state machines`() = runBlocking {
        val socket = aSocket(ActorSelectorManager(Dispatchers.IO)).tcp()
            .connect(InetSocketAddress("localhost", 51001))

        val input = socket.openReadChannel()
        val output = socket.openWriteChannel(autoFlush = true)

        val outputChannel = Channel<String>(Channel.BUFFERED)
        val inputChannel = Channel<Either<ElectrumResponse, JsonRPCResponse>>(0)

        val client = ElectrumClient(inputChannel, outputChannel)
        val watcher = ElectrumWatcher(client)

        val sendSocketJob = launch {
            outputChannel.consumeEach {
                logger.info {
                    """Send request to Electrum Server
                    |$it
                    """.trimMargin()
                }
                try {
                    output.write(it)
                } catch (e: Exception) {
//                    responseChannel.send(ServerError(null, e.localizedMessage))
                }
            }
        }

        val readSocketJob = launch {
            while (true) {
                input.readUTF8Line().also {
                    logger.info {
                        """Response received from Electrum Server
                        |$it
                        """.trimMargin()
                    }
                }?.let {
                    inputChannel.send(json.parse(ElectrumResponseDeserializer, it))
                    logger.info { "Waiting for the next input socket" }
                }
            }
        }

        val start = launch {
            launch {
                logger.info { "Start electrum client" }
                client.start()
            }
            launch {
                logger.info { "Start electrum watcher" }
                watcher.start()
            }
        }


        launch {
            delay(120_000)
            logger.info { "EXIT" }
            start.cancel()
            sendSocketJob.cancel()
            output.close()
            readSocketJob.cancel()
            input.cancel()
        }.join()
    }
}