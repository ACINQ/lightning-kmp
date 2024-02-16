package fr.acinq.lightning.bin

import fr.acinq.bitcoin.utils.Either
import fr.acinq.lightning.Lightning.randomBytes32
import fr.acinq.lightning.NodeParams
import fr.acinq.lightning.bin.json.Balance
import fr.acinq.lightning.channel.states.ChannelState
import fr.acinq.lightning.channel.states.ChannelStateWithCommitments
import fr.acinq.lightning.io.Peer
import fr.acinq.lightning.utils.sat
import fr.acinq.lightning.utils.sum
import fr.acinq.lightning.utils.toMilliSatoshi
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.Json

class Api(private val nodeParams: NodeParams, private val peer: Peer) {

    fun start(): ApplicationEngine {
        val server = embeddedServer(CIO, port = 8080, host = "0.0.0.0") {

            install(ContentNegotiation) {
                json(Json {
                    prettyPrint = true
                    isLenient = true
                    serializersModule = fr.acinq.lightning.json.JsonSerializers.json.serializersModule
                })
            }

            routing {
                get("/") {
                    call.respondText("Hello World!")
                }
                get("/nodeid") {
                    call.respondText(nodeParams.nodeId.toHex())
                }
                get("/balance") {
                    val balance = peer.channels.values
                        .filterIsInstance<ChannelStateWithCommitments>()
                        .map { it.commitments.active.first().availableBalanceForSend(it.commitments.params, it.commitments.changes) }
                        .sum().truncateToSatoshi()
                    call.respond(Balance(balance))
                }
                get("/channels") {
                    call.respond(peer.channels.values.toList())
                }
                post("/invoice") {
                    val formParameters = call.receiveParameters()
                    val amount = formParameters["amountSat"]?.toLong()?.sat?.toMilliSatoshi()
                    val description = formParameters["description"].toString()
                    val invoice = peer.createInvoice(randomBytes32(), amount, Either.Left(description))
                    call.respondText(invoice.write())
                }
            }
        }
        return server.start(wait = true)
    }
}


