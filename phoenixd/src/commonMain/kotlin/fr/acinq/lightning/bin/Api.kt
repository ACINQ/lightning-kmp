package fr.acinq.lightning.bin

import fr.acinq.bitcoin.Bitcoin
import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.Script
import fr.acinq.bitcoin.utils.Either
import fr.acinq.bitcoin.utils.toEither
import fr.acinq.lightning.Lightning.randomBytes32
import fr.acinq.lightning.NodeParams
import fr.acinq.lightning.bin.json.Balance
import fr.acinq.lightning.bin.json.PaymentReceived
import fr.acinq.lightning.blockchain.fee.FeeratePerByte
import fr.acinq.lightning.blockchain.fee.FeeratePerKw
import fr.acinq.lightning.channel.ChannelCommand
import fr.acinq.lightning.channel.states.ChannelStateWithCommitments
import fr.acinq.lightning.channel.states.ClosingFeerates
import fr.acinq.lightning.io.Peer
import fr.acinq.lightning.io.WrappedChannelCommand
import fr.acinq.lightning.utils.sat
import fr.acinq.lightning.utils.sum
import fr.acinq.lightning.utils.toByteVector
import fr.acinq.lightning.utils.toMilliSatoshi
import io.ktor.http.*
import io.ktor.serialization.kotlinx.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import kotlinx.serialization.json.Json

class Api(private val nodeParams: NodeParams, private val peer: Peer) {

    public val server = embeddedServer(CIO, port = 8080, host = "0.0.0.0") {

        val json = Json {
            prettyPrint = true
            isLenient = true
            serializersModule = fr.acinq.lightning.json.JsonSerializers.json.serializersModule
        }

        install(ContentNegotiation) {
            json(json)
        }
        install(WebSockets) {
            contentConverter = KotlinxWebsocketSerializationConverter(json)
        }
        install(StatusPages) {
            exception<Throwable> { call, cause ->
                if (cause is IllegalArgumentException) {
                    call.respondText(text = "400: ${cause.message}", status = HttpStatusCode.BadRequest)
                } else {
                    call.respondText(text = "500: $cause", status = HttpStatusCode.InternalServerError)
                }
            }
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
            post("/close") {
                val formParameters = call.receiveParameters()
                val channelId = formParameters.getByteVector32("channelId")
                val scriptPubKey = formParameters.getAddressAndConvertToScript("address")
                val feerate = FeeratePerKw(FeeratePerByte(formParameters.getLong("feerateSatByte").sat))
                peer.send(WrappedChannelCommand(channelId, ChannelCommand.Close.MutualClose(scriptPubKey, ClosingFeerates(feerate))))
                call.respondText("ok")
            }
            post("/invoice") {
                val formParameters = call.receiveParameters()
                val amount = formParameters.getLong("amountSat").sat.toMilliSatoshi()
                val description = formParameters.getString("description")
                val invoice = peer.createInvoice(randomBytes32(), amount, Either.Left(description))
                call.respondText(invoice.write())
            }
            post("/bitcoin/send") {
                val res = kotlin.runCatching {
                    val formParameters = call.receiveParameters()
                    val amount = formParameters.getLong("amountSat").sat
                    val scriptPubKey = formParameters.getAddressAndConvertToScript("address")
                    val feerate = FeeratePerKw(FeeratePerByte(formParameters.getLong("feerateSatByte").sat))
                    peer.spliceOut(amount, scriptPubKey, feerate)
                }.toEither()
                when (res) {
                    is Either.Right -> when (val r = res.value) {
                        is ChannelCommand.Commitment.Splice.Response.Created -> call.respondText(r.fundingTxId.toString())
                        is ChannelCommand.Commitment.Splice.Response.Failure -> call.respondText(r.toString())
                        else -> call.respondText("no channel available")
                    }
                    is Either.Left -> call.respondText(res.value.message.toString())
                }
            }
            webSocket("/websocket") {
                try {
                    peer.eventsFlow.collect {
                        when (val event = it) {
                            is fr.acinq.lightning.io.PaymentReceived -> sendSerialized(PaymentReceived(event))
                            else -> {}
                        }
                    }
                } catch (e: Throwable) {
                    println("onError ${closeReason.await()}")
                }
            }
        }
    }

    fun Parameters.getString(argName: String): String = (this[argName] ?: missing(argName))

    fun Parameters.getByteVector32(argName: String): ByteVector32 = getString(argName).let { hex -> kotlin.runCatching { ByteVector32.fromValidHex(hex) }.getOrNull()  ?: invalidType(argName, "hex32") }

    fun Parameters.getAddressAndConvertToScript(argName: String): ByteVector = Script.write(Bitcoin.addressToPublicKeyScript(nodeParams.chainHash, getString(argName)).right ?: error("invalid address")).toByteVector()

    fun Parameters.getLong(argName: String): Long = ((this[argName] ?: missing(argName)).toLongOrNull()) ?: invalidType(argName, "integer")
}

fun missing(argName: String): Nothing = throw IllegalArgumentException("missing $argName")

fun invalidType(argName: String, typeName: String): Nothing = throw IllegalArgumentException("invalid type for $argName, expected $typeName")


