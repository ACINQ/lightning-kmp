package fr.acinq.starblocks

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.websocket.*
import io.ktor.client.request.forms.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.util.*
import io.ktor.server.websocket.*
import io.ktor.server.websocket.WebSockets
import io.ktor.websocket.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okio.Path
import kotlin.random.Random
import kotlin.random.nextUInt

class Api(phoenixdUrl: Url, webDir: Path) {

    private val orders = mutableMapOf<String, Order>()
    private val paymentEvents = MutableSharedFlow<PaymentReceived>()

    val client = HttpClient(io.ktor.client.engine.cio.CIO) {
        install(io.ktor.client.plugins.contentnegotiation.ContentNegotiation) {
            json(json = Json {
                prettyPrint = true
                isLenient = true
            })
        }

        install(io.ktor.client.plugins.websocket.WebSockets) {
            pingInterval = 10_000
            contentConverter = KotlinxWebsocketSerializationConverter(Json)
        }
    }

    val server = embeddedServer(CIO, port = 8081, host = "0.0.0.0") {
        setupServer(this)

        launch {
            runBlocking {
                client.webSocket(method = HttpMethod.Get, host = phoenixdUrl.host, port = phoenixdUrl.port, path = "/websocket") {
                    while (true) {
                        try {
                            val paymentReceived = receiveDeserialized<PaymentReceived>()
                            println("<- payment_hash=${paymentReceived.paymentHash} amount=${paymentReceived.amountMsat} msat")
                            paymentEvents.emit(paymentReceived)
                            orders.values.firstOrNull { it.payment_hash == paymentReceived.paymentHash }?.let {
                                orders[it.id] = it.copy(paid = true)
                            }
                        } catch (e: Exception) {
                            println("error in websocket with phoenixd: ${e.message}")
                        }
                    }
                }
            }
        }

        routing {
            route("/") {
                val rootFolder = webDir
                staticRootFolder = rootFolder
                fileSystem.listRecursively(rootFolder).filter { path ->
                    fileSystem.metadata(path).isRegularFile
                }.forEach { path ->
                    val relativePath = path.relativeTo(rootFolder).toString()
                    file(relativePath, relativePath)
                }
                default("index.html")
                route("js") { files("js") }
                route("css") { files("css") }
            }
            get("/api/products") {
                val products = Database.products
                call.respond(HttpStatusCode.Accepted, products)
            }
            post("/api/order") {
                val userOrder = call.receive<List<OrderProductLine>>()
                val amount = userOrder.sumOf { it.count * it.unitary_price_satoshi }
                if (amount <= 0) {
                    call.respond(
                        status = HttpStatusCode.BadRequest,
                        message = ApiError(code = HttpStatusCode.BadRequest.value, message = "what have you done")
                    )
                } else {
                    val invoice = client.submitForm(
                        url = url {
                            takeFrom(phoenixdUrl)
                            path("invoice")
                        },
                        formParameters = parameters {
                            append("amountSat", amount.toString())
                            append("description", userOrder.joinToString(", ") {
                                "${it.count} ${it.product_name}"
                            })
                        }
                    ).body<GeneratedInvoice>()
                    val orderId = randomId()
                    val order = Order(
                        id = orderId,
                        items = userOrder,
                        payment_request = invoice.serialized,
                        payment_hash = invoice.paymentHash,
                        paid = false,
                    )
                    orders[order.id] = order
                    bindSocketForOrder(this@routing, order)
                    call.respond(status = HttpStatusCode.Created, message = order)
                }
            }
            get("/api/order/{id}") {
                val id = call.parameters["id"]!!
                val order: Order = orders[id]!!
                call.respond(order)
            }
        }
    }

    private fun bindSocketForOrder(routing: Routing, order: Order) {
        routing.webSocket("/ws-order/${order.payment_hash}") {
            paymentEvents.collect { event ->
                if (event.paymentHash == order.payment_hash) {
                    sendSerialized(event)
                    delay(1_000)
                    this.close(CloseReason(CloseReason.Codes.NORMAL, "payment flow terminated"))
                }
            }
        }
    }

    private fun setupServer(app: Application) {
        val json = Json {
            prettyPrint = true
            isLenient = true
        }

        app.install(ContentNegotiation) {
            json(json)
        }
        app.install(WebSockets) {
            contentConverter = KotlinxWebsocketSerializationConverter(json)
            pingPeriodMillis = 10_000
        }
        app.install(StatusPages) {
            exception<Throwable> { call, cause ->
                call.respondText(text = cause.message ?: "", status = defaultExceptionStatusCode(cause) ?: HttpStatusCode.InternalServerError)
            }
        }
        app.install(CORS) {
            allowHost("*")
            allowHeader(io.ktor.http.HttpHeaders.ContentType)
        }
    }
}

@OptIn(ExperimentalStdlibApi::class)
fun randomId(): String = Random.nextUInt().toHexString().padStart(8, '0')
